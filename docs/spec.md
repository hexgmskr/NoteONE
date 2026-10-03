# 个人标签化归档笔记应用 — 技术规格文档

版本：v1.2（2026-09-30 修订：开发顺序、解锁策略、PIN 兜底三项按最终决策更新；其余沿用 v1.1）
定位：单一职责的低频数据采集与多维标签归档工具，本地加密存储，不联网。
目标设备：小米 14（Android 16 / HyperOS），`minSdk = 36`，单设备自用。

> **文档地位**：本文档是前期讨论产出的设计参考。日常开发约束见根目录 `CLAUDE.md`；两者冲突时以 `CLAUDE.md` 为准。
> （2026-10-04 补注）实现已多处演进——如解锁状态机落地为**两态**、交互细节经多轮真机迭代、
> 开发顺序各阶段已走完；本文档与**代码**冲突时，以代码为准（进度与实证见 `docs/HANDOFF.md`）。

---

## 1. 产品定位与边界

- **核心功能**：粘贴/输入一条记录（如视频链接） → 打多维标签 → 归档保存。
- **非目标**：不做富文本编辑、不做多设备实时同步、不做系统级分享接入（用户采集方式是"从别处复制 → 打开 App → 粘贴"）。
- **使用频率**：低频、偶发，因此交互路径必须短、无冗余步骤。
- **安全等级**：本地加密，**MVP 阶段即接入生物验证解锁**（非后置可选项）。

---

## 2. 技术选型

| 层面 | 选择 | 理由 |
|---|---|---|
| 平台 | 原生 Android | 单平台目标（小米14/HyperOS），无跨平台需求，原生更利于深度接入安全原语 |
| 语言 | Kotlin | Google 官方首推，空安全减少运行时崩溃，生态与 Jetpack 全面支持 |
| UI | Jetpack Compose | 官方现代方案，适合小而美的单页/少页应用 |
| 本地数据库 | Room（SQLite 封装） | 编译期校验 + Migration 机制，保证数据结构可演进且不丢历史数据 |
| 加密 | SQLCipher（整库加密） | 攻击面小（单机、不联网），整库加密性价比高于字段级加密 |
| 密钥管理 | Android Keystore + 信封加密（**恢复密钥MVP即做**） | 硬件级密钥保护 + 测试阶段可反复重置密钥而不丢数据 |
| 生物验证 | BiometricPrompt API，冷启动触发 + 60 秒后台宽限（见 7.1） | 访问控制层，不参与数据加密逻辑本身 |

---

## 3. 数据模型

标签体系是**多维分面分类（faceted classification）**，不是树状层级。`#长视频#萌宠#多镜头` 中每个标签属于不同维度（时长/主题/手法），互不隶属。

### 3.1 表结构

```kotlin
@Entity(tableName = "item")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,        // 粘贴/输入的正文（链接、文本等）
    val note: String? = null,   // 预留：补充备注
    val createdAt: Long,        // epoch millis
    val updatedAt: Long
)

@Entity(
    tableName = "tag",
    indices = [Index(value = ["namespace", "normalizedValue"], unique = true)]
)
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val namespace: String,          // 维度，如 "长度"/"主题"/"手法"
    val value: String,              // 展示用原始值，如 "MV"
    val normalizedValue: String     // 归一化后用于去重匹配，如 "mv"（见3.3）
)

@Entity(
    tableName = "item_tag",
    primaryKeys = ["itemId", "tagId"],
    foreignKeys = [
        ForeignKey(entity = Item::class, parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)
    ]
)
data class ItemTag(
    val itemId: Long,
    val tagId: Long,
    val sortOrder: Int = 0      // 预留：标签展示顺序
)
```

### 3.2 设计原则

- **namespace 不写死枚举**：以后新增维度（如"评分""地点"）只加数据，不改表结构。
- **数据表不存展示相关字段**（如标签颜色、排序偏好归展示层管理，不进核心表）——保证导出数据"哑"而规整。
- **全文检索预留**：数据量增长后，可在 `content` 字段上加 SQLite FTS5 虚拟表，无需改动主表结构。
- **零预置 namespace**：不预先塞入"长度/主题/手法"等示例维度和值，首次使用时标签面板为空，由用户自建体系。这里不追求操作步骤少或省事（用户明确表示可以接受繁琐），要保证的是**标签体系搭建的逻辑完备且规整**——即新建 namespace、新建 value、value 归属哪个 namespace 这套规则在数据模型和交互上必须自洽、无歧义、无特例，不能出现"某些标签可以不归属任何namespace"或"同一个value在不同namespace下语义冲突"这类结构性漏洞。

  > **⚠️ 已修正的错误论证（2026-09-30）**：本节早期版本断言"数据模型层面（3.1 节的 `namespace`+`normalizedValue` 联合唯一索引）已经保证了这一点：任何标签创建都必须显式指定 namespace，不存在游离标签"。**这个断言是错的**——联合唯一索引只保证「给定 namespace 内不重复」，它管不了 namespace 本身是不是空的。`insert(namespace="", value="MV")` 完全合法，恰恰会产生它声称已杜绝的"游离标签"。
  >
  > 实际保证来自两层，缺一不可：
  > 1. **数据层**：`(namespace, normalizedValue)` 联合唯一索引 —— 挡住同维度内重复
  > 2. **写入口**：`TagRepository.normalizeNamespace()` 强制 namespace 非空（并 trim）—— 挡住游离标签
  >
  > 实现层补充：namespace **不折叠大小写**（与 value 侧的归一化不同）。spec 3.3 只对 value 定义了归一化，故不擅自扩展；中文维度名无此问题。若将来用英文维度名，需加 `normalizedNamespace` 列并写迁移。

### 3.3 标签归一化规则（用户无感）

- 用户输入的标签原始大小写/格式保留在 `value` 字段用于展示。
- 存储和去重匹配时额外维护一份 `normalizedValue`（统一转小写、去除首尾空格），唯一索引建在 `(namespace, normalizedValue)` 上。
- 效果：用户输入"MV"和"mv"会被识别为同一个标签（展示为最早创建时的那个大小写形式），不会产生两条看似重复的标签，全程不需要用户感知这层处理。

---

## 4. 加密架构（信封加密 / Key Wrapping）

### 4.1 核心结构

```
                    ┌─────────────────────┐
                    │   DEK（数据加密密钥）  │  ← 唯一，用于加解密 .db 文件
                    └──────────┬───────────┘
              ┌─────────────────┴─────────────────┐
              │                                     │
     ┌────────▼────────┐                 ┌──────────▼──────────┐
     │  副本A：Keystore  │                 │  副本B：主密码派生密钥  │
     │  硬件密钥加密DEK   │                 │  （Argon2/PBKDF2）   │
     │  → 生物验证解锁     │                 │  加密DEK，单独存放     │
     └──────────────────┘                 └──────────────────────┘
      （日常使用路径）                        （MVP即实现，测试/迁移用）
```

- **只有一把 DEK**，db 只加密一次；两条路径是两把不同的钥匙，锁的是同一个"信封"。
- **副本A（日常路径）**：Keystore 硬件密钥加密 DEK 生成的密文。生物验证通过 → Keystore 解密 → 得到明文 DEK → 解密 db。密钥物理不可导出。
- **副本B（恢复路径，MVP即实现）**：用户设置主密码，经 Argon2/PBKDF2 高强度派生后加密 DEK，生成的密文由用户自行手动保管（本地导出，不建议放联网自动同步目录）。**开发/测试阶段用途**：反复重置、替换、调试 Keystore 密钥相关逻辑时，可用副本B随时找回同一把 DEK，不必每次测试都清空数据重建。
- 吊销恢复路径时只需删除副本B密文，副本A不受影响，db 无需重新加密。

### 4.2 Keystore 密钥生成参数（关键决策已锁定）

```kotlin
val keyGenParameterSpec = KeyGenParameterSpec.Builder(
    "notes_app_dek_wrapper",
    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
    .setUserAuthenticationRequired(true)
    .setInvalidatedByBiometricEnrollment(false)  // 关闭：新增/修改指纹不使密钥失效
    .build()
```

- `setInvalidatedByBiometricEnrollment(false)`：默认值是 `true`（Android 的默认安全策略是"录入新指纹后旧密钥失效"，防止陌生指纹意外获得解锁权限）。**本项目手动关闭**——用户明确不需要区分"指纹级别"和"整机级别"的安全颗粒度，改指纹不应该导致数据被锁死。

### 4.3 已知边界

| 场景 | 结果 |
|---|---|
| 手机丢失/被盗 | 安全（密钥锁在硬件里，无恢复密钥密文外泄前提下） |
| 换新设备 | 可通过主密码（副本B）恢复 |
| App 被误卸载/清除数据（设备未换） | 可通过主密码（副本B）恢复 |
| App 升级安装（同包名+同签名证书，非先卸载再装） | 密钥保留，可正常解密 |
| 新增/修改指纹录入 | 不受影响，密钥不失效（4.2节已关闭默认限制） |
| 恢复密钥密文泄露 + 主密码被破解 | 数据可被解密（安全上限降为主密码强度，是本方案主动接受的权衡） |

### 4.4 无生物特征设备兜底（v1.2 决策：MVP 不做，仅预留接入点）

- **不做 PIN 兜底**。目标设备是自己的小米 14，已录入指纹，单设备自用场景下 PIN 是纯负担。
- **但接入点必须预留**：解锁这一步抽象成接口（如 `AuthGate` / `UnlockMethod`），BiometricPrompt 只是其中一个实现。将来要加 PIN，是"多一个实现"，不是"改造入口状态机"。这与接缝 B 的设计天然契合，不额外增加工作量。
- 若设备无生物特征，MVP 阶段直接提示"需要指纹/面容"后退出，不做降级。

### 4.5 其他安全注意事项

- `AndroidManifest.xml` 显式设置 `android:allowBackup="false"`（或用 `dataExtractionRules` 排除数据库路径），防止系统自动备份把加密库文件同步上云，违背"本地不联网"的设计初衷。
- **签名证书备份（明确任务项）**：keystore 签名文件（`.jks`）+ 密码生成后，立即复制到至少两个独立存储位置（如本地硬盘+U盘，或你已有的其他备份习惯）。这份文件丢失等同于永久失去"以同一App身份升级安装"的能力，重要性不低于恢复密钥。

---

## 5. 数据库版本管理（Room Migration）

- 数据库从第一天起就带版本号（`@Database(version = N, ...)`）。当前为 v3；每个版本的表结构导出到 `app/schemas/` 并入库，用于迁移回归测试。
- 每次表结构变更，必须编写对应的 `Migration(oldVersion, newVersion)` 脚本，禁止用 `fallbackToDestructiveMigration()`（该方法会在结构不匹配时直接清空数据库，与"数据保存是重点"的核心需求冲突）。
- 目的：保证历史数据在 App 升级后依然可读，不因结构变化而丢失。

### 5.1 数据库损坏兜底

- 不做自动检测/自动修复机制（超出当前必要性，属于过度工程）。
- 依赖用户手动定期导出（见第6节）作为唯一的灾难恢复手段，这一责任明确落在使用习惯上，架构层不做隐性承诺。

---

## 6. 导出方案

### 6.1 JSON Schema（示例，v1）

```json
{
  "schema_version": 1,
  "exported_at": "2026-09-29T10:00:00+08:00",
  "items": [
    {
      "id": 1,
      "content": "https://example.com/video/xxx",
      "note": null,
      "created_at": 1758000000000,
      "tags": [
        { "namespace": "长度", "value": "长视频" },
        { "namespace": "主题", "value": "萌宠" },
        { "namespace": "手法", "value": "多镜头" }
      ]
    }
  ]
}
```

### 6.2 规则

- **`schema_version` 字段必须存在**，表结构变化导致导出格式变化时递增版本号，解析脚本按版本分支处理（`if version == 1: ... elif version == 2: ...`），避免新旧格式混读出错。
- 导出内容只反映核心数据表，不包含展示层信息（排序、UI 状态等）及内部的 `normalizedValue`（该字段是内部去重用，导出只保留用户可读的 `value`）。
- 导出格式与内部表结构解耦：内部表怎么改，只要导出脚本跟着更新 schema_version，历史导出文件依然可追溯解析。

---

## 7. 交互流程（MVP）

1. **解锁门禁**（v1.2 修订：非"每次前台化都验"，而是宽限期模型）：
   - **冷启动 / 进程被杀后重新进入**：必须生物验证。
   - **短时间切后台再回**（如切出去复制网址、复制一段文本）：不重复验证，不打断高频的跨应用取材动作。
   - **退后台超过 60 秒再回前台**：重新验证。
   - 宽限期内数据密钥（DEK）常驻内存、数据库保持打开，只挡一层 UI；进程一旦死亡，天然回落到冷启动的完整解锁路径。
   - 无生物特征设备的处理见 4.4（MVP 不做 PIN，仅预留接口）。
2. **主界面**：列表展示已归档条目（内容摘要 + 标签），支持按标签筛选。
3. **新建记录**：
   - 点击"新建" → 输入框（支持从剪贴板一键粘贴，非自动后台监听——Android 10+ 起后台读剪贴板受限，只有前台可读，因此设计为用户主动点击"粘贴"按钮触发）
   - 打标签：按 namespace 分组展示已有标签供选择，输入新词时做模糊匹配提示已存在标签（基于归一化值匹配，见3.3），面板常驻"+新建namespace/value"入口
   - 保存
4. **标签管理页（轻量，可后置）**：查看各 namespace 下所有标签及引用次数，支持手动合并同义标签。
5. **导出功能**：一键生成带版本号的 JSON 文件，保存到本地。
6. **恢复密钥设置**：首次使用或设置页内，允许用户设定主密码并生成副本B密文，提示手动保管去处。

---

## 8. 后续可拓展方向（架构已预留，非 MVP 必需）

- 全文检索（SQLite FTS5）
- 标签维度间的交叉统计分析（数据模型天然支持，只需查询层开发）
- 多种导出格式（CSV/Markdown），复用 schema_version 机制
- 数据库完整性校验/自动修复（当前判定为过度工程，暂不做）

---

## 9. 开发顺序（v1.2 修订：探针先行 + 交互优先 + 接缝预留）

> v1.1 是"加密栈全做完再做界面"。v1.2 改为：先验证工具链风险，再尽早拿到可用的交互，加密整体后置但**靠两个接缝保证后置不等于返工**。

**阶段 0 — 探针与接缝（半天，最先做）**
1. `git init` + 版本控制规范
2. **SQLCipher 薄探针**：不写业务，只验证"这套工具链（AGP 9.4.x / Kotlin 2.2.x / Gradle 9.x）能否用 SQLCipher 打开一个加密 Room 库"。这是全项目最可能卡住的构建风险，在最便宜的时候暴露
3. 建**接缝 A**：`DatabaseProvider` —— 全项目唯一"怎么打开数据库"的知识点。SQLCipher 接入时只替换这里的实现，Entity/DAO/Repository/ViewModel/UI 写法一律不变
4. 建**接缝 B**：入口状态机 `Locked → Unlocking → Unlocked` —— 明文阶段 `Locked` 自动跳到 `Unlocked`；加密阶段换成真正的 BiometricPrompt。UI 从第一天就习惯"数据要等解锁后才有"
5. `allowBackup=false` + `dataExtractionRules` 排除数据库路径

**阶段 1 — 明文数据层**
搭建 Room 三表（Item/Tag/ItemTag，含 `normalizedValue`）+ 联合唯一索引 + 第一版 Migration（禁用 `fallbackToDestructiveMigration`）

**阶段 2 — 核心交互**
新建记录（主动点"粘贴"，不搞后台监听）→ 按 namespace 分组打标签（模糊匹配已有标签、常驻新建入口）→ 列表展示 + 按标签筛选
*做完这一步就有一个能用的工具，只是数据未加密。*

**阶段 3 — 加密栈**
1. **恢复密钥（副本B）最先** —— 保留 v1.1 的理由：后续反复调试 Keystore 时这是退路，避免每次测试清空重来
2. SQLCipher 接入（替换接缝 A 的实现）
3. Keystore DEK 信封加密（含 `setInvalidatedByBiometricEnrollment(false)`）
4. BiometricPrompt 接入接缝 B + 60 秒宽限逻辑

**阶段 4 — 导出与加固**
JSON 导出（`schema_version`，过滤内部字段）→ 签名证书双份备份 → 标签管理页（合并同义标签，可后置）

### 9.1 已知的一次性代价

阶段 2 → 阶段 3 之间存在一次明文测试数据到加密库的切换。**开发期测试数据直接清掉重建，不写搬迁代码**。真要保数据 SQLCipher 有 `sqlcipher_export()`，但不值得为测试数据写它。正式数据迁移（从外部迁入本 App）在完全可用后才进行，不受此影响。
