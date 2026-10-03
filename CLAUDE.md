# NoteONE — 开发约束

单一职责的本地加密标签归档笔记应用。粘贴一条记录 → 打多维分面标签 → 归档。本地加密，不联网，低频采集。
目标设备：小米 14（Android 16 / HyperOS），单设备自用。

**设计依据**：`docs/spec.md`（数据模型、加密架构、导出格式）。本文件是硬约束，冲突时以本文件为准。

---

## 不可违反的约束

**数据安全**
- 禁止 `fallbackToDestructiveMigration()`。表结构变更必须写 `Migration` 脚本，历史数据不许丢。
- `AndroidManifest.xml` 保持 `android:allowBackup="false"`，`dataExtractionRules` 排除数据库路径。系统云备份会破坏"本地不联网"的前提。
- 签名密钥（`*.jks` / `*.keystore`）与恢复密钥密文**永不入库**，永不进任何会被打包的资源。`.gitignore` 已覆盖。
- 导出 JSON 含 `schema_version`，且过滤内部字段（`normalizedValue`）。导出内容含真实笔记，属用户数据，不入库。
- **仪器测试禁止使用生产库名** `noteone.db`：测试自带 `@Before/@After` 清理，用生产名 = 每次跑测试删真机数据（已实际发生过一次）。测生产路径用隔离库名，`SqlCipherDatabaseProvider` 的 `databaseName` 参数就是为此存在（明文时代的 `PlaintextDatabaseProvider` 已随阶段 3-2 删除）。
- 跑 `connectedDebugAndroidTest` 依赖 `gradle.properties` 的 `android.injected.androidTest.leaveApksInstalledAfterRun=true`。删掉它，AGP 会在测完自动卸载 App、连数据一起清。

**标签体系**
- 多维分面（faceted），不是树状。`namespace` 不写死枚举，新增维度只加数据不改表。
- 任何标签必须显式归属某个 namespace，不存在游离标签。
- 零预置 namespace / value。首次使用面板为空，由用户自建。
- 去重靠 `normalizedValue`（小写 + trim），唯一索引建在 `(namespace, normalizedValue)`。

**加密**
- 整库 SQLCipher 加密，信封加密：一把 DEK，两份密文（Keystore 硬件密钥 / 主密码派生密钥）。
- Keystore 密钥**默认** `setInvalidatedByBiometricEnrollment(false)` —— 换指纹不该锁死数据。允许做**用户可见的"高安全模式"**（默认关闭，切 `true`：指纹变更即作废副本A），但必须闭环：① 开启前副本B必须已存在并当场验证可解；② 钥匙作废时引导「主密码+副本B → 恢复DEK → 自动重建副本A」，绝不允许死胡同；③ 副本A的重建是原子替换（新包好、校验过，才替换旧的）；④ 开启期间删除/覆盖恢复密钥必须显著警告。
- 加密阶段第一件事做恢复密钥（副本B），不要省。调试 Keystore 时它是唯一退路。

---

## 架构接缝（不得绕过）

这两处是"加密后置不等于返工"的全部保证。绕过它们 = 阶段 3 要重构。

**接缝 A — `DatabaseProvider`**
全项目唯一知道"怎么打开数据库"的地方。Entity / DAO / Repository / ViewModel / UI 一律不接触 `Room.databaseBuilder`，只拿 `AppDatabase`。SQLCipher 接入时**只替换这里的实现**。

**接缝 B — 入口状态机** `Locked ⇄ Unlocked`（两态；原先记的第三态 `Unlocking`
生产从不经过，2026-10-04 已连同弹出式验证器接口如实删除）
UI 从第一天起就按"数据要等解锁后才有"来写。指纹验证由系统 BiometricPrompt
在 App 之外完成，成功后调 `completeUnlock()` 报到。

**解锁策略**（接缝 B 的语义）
- 冷启动 / 进程被杀后重新进入 → 必须验证
- 退后台 60 秒内回到前台 → 不重复验证（切出去复制网址/文本的高频动作不被打断）
- 退后台超过 60 秒 → 重新验证
- 宽限期内 DEK 常驻内存、库保持打开，只挡一层 UI
- 无生物特征设备：不做 PIN，**直接亮主密码输入**（退路始终在、不影响可用性——这是已交付的真机行为，比"提示后退出"更宽）。

---

## 技术取舍（少依赖优先）

本项目已两次踩到依赖版本冲突（KSP/AGP 内置 Kotlin、kotlinx-serialization）。
**每加一个依赖就多一个冲突面**，所以在能力够用时优先不引第三方库。以下是已做的取舍：

| 能力 | 选择 | 替代方案 | 理由 |
|---|---|---|---|
| 导航 | sealed class 状态切换 | Jetpack Navigation | 两三个屏幕用不上它的能力 |
| 依赖注入 | 手工 `AppContainer` | Hilt / Koin | 场景简单，且 Hilt 又带 KSP |
| 列表/标签排序 | 展示层排序（`sortedTags`） | 核心表存 sortOrder | spec 3.2 明确「排序偏好归展示层管理」 |

**若将来要迁到 Navigation/Hilt**：触发条件是**语义需求**（深层链接、返回栈语义），不是屏幕数。
2026-10-04 评估过：屏幕已到 6 个，但手工返回栈够用、无深链需求，**暂不迁**。
真要迁时单独成步验证，不要夹带在功能改动里。

**生命周期接线**：`LockController.onBackground`/`onForeground` 挂在 `onStop`/`onStart`，
不是 `onPause`/`onResume`——后者在分屏、弹对话框、权限弹窗时都会触发，
会导致用户还在用却被要求重新验证。

**当前数据库版本 v3**，历史 schema 导出在 `app/schemas/`（必须入库）。

---

## 开发顺序

探针先行 + 交互优先 + 接缝预留。完整版见 `docs/spec.md` 第 9 节。

0. **探针与接缝**：git 规范 → SQLCipher 薄探针（验证工具链能开加密 Room 库）→ 接缝 A / B → `allowBackup=false`
1. **明文数据层**：Room 三表 + `normalizedValue` + Migration
2. **核心交互**：新建记录 + 打标签 + 列表筛选（做完即有一个能用的工具）
3. **加密栈**：恢复密钥 → SQLCipher（换接缝 A）→ Keystore DEK → BiometricPrompt（接缝 B）
4. **导出与加固**：JSON 导出 → 签名证书双份备份 → 标签管理页

阶段 2→3 之间明文测试数据直接清掉，**不写搬迁代码**。

---

## 已知债务

**① KSP 与 AGP 9 内置 Kotlin 冲突**（2026-09-30，阶段 0 探针发现）
- 现象：AGP 9 内置 Kotlin 禁止 `kotlin.sourceSets` DSL，而 KSP 仍用它注入生成代码源集，直接编译失败。
- 处置：`gradle.properties` 设 `android.disallowKotlinSourceSets=false`。这是 Google 官方给第三方插件的兼容逃生口，非野路子 hack。
- 代价：放弃 AGP 9 对"混用两套 sourceSet 模型"的检查。
- **升级路线已查证，暂不可行**：KSP 版本谱系里，`2.2.10-2.0.2`（当前）到 `2.3.x` 之间**没有中间版本**。`2.3.x` 起才修好，但它依赖 Kotlin 2.3.20，会把 Kotlin 从 2.2.10 拖成跨版本升级，波及 Compose 编译器与 BOM。
- **解除条件**：等 KSP 出适配 AGP 9 内置 Kotlin 的 2.2.x 补丁版，或届时再做 Kotlin 2.3.x 整体升级。动手前先删掉这行验证是否恢复正常，不要长期赖着这个开关。

**② kotlinx-serialization 版本分裂**（2026-09-30，阶段 1 步骤 4 迁移测试发现）
- 现象：`MigrationTest` 抛 `AbstractMethodError: GeneratedSerializer.typeParametersSerializers()`。
- 根因：`androidx.room:room-migration` 2.8.5 明确依赖 kotlinx-serialization **1.8.1**，而解析里存在 `kotlinx-serialization-bom:{strictly 1.7.3}` 约束，经 Gradle 跨配置一致解析传导到 androidTest，形成 json 1.8.1 × core 1.7.3 的错配。
- **约束来源已核实更正（2026-10-04 审计）**：`strictly 1.7.3` 来自 **`androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.9.4`** 的模块元数据（它请求 kotlinx-serialization-core 1.7.3），**不是 Compose BOM**（该 BOM 的 POM 里没有序列化条目）。核验命令：`./gradlew :app:dependencyInsight --configuration debugAndroidTestRuntimeClasspath --dependency kotlinx-serialization-bom`。
- 处置：`app/build.gradle.kts` 里用 `configurations.configureEach { if (name.contains("AndroidTest")) resolutionStrategy.force(...) }` 对齐到 1.8.1。
- **作用域刻意收窄到 androidTest**：`room-migration` 只被 `room-testing` 引入，主应用用不到 kotlinx-serialization，不必动主 classpath。
- 用 `configureEach` 而非 `named()`：androidTest 的 configuration 由 AGP 延后创建，`named()` 在 build script 求值时找不到会直接抛异常。
- **解除条件**：等 `lifecycle-viewmodel-savedstate` 不再钉 1.7.3（用上面的 dependencyInsight 命令确认），再删掉这块 force 验证是否恢复正常。**别只盯 Compose BOM 版本**——约束不在它那里，照旧写法删 force 会直接把 `AbstractMethodError` 放回来。

---

## 构建

```bash
./gradlew :app:assembleDebug     # 构建
./gradlew :app:testDebugUnitTest # 单元测试
./gradlew :app:connectedDebugAndroidTest # 仪器测试（需设备/模拟器）
```

构建需要项目根的 `keystore.properties`（不入库，内容 = 项目外正式签名钥匙的路径与口令；
缺失即报错，刻意**不**静默退回调试签名）。debug 与 release 共用同一把正式钥匙，
换机器时 `keystore.properties` 要和 keystore 一起搬。详见 `docs/HANDOFF.md` §3.2。

工具链偏激进：AGP 9.4.x / Gradle 9.x / Kotlin 2.2.x / compileSdk 37 / minSdk 36。
AGP 9 注意点：无独立 `kotlin-android` 插件（内置 Kotlin）；release 用 `optimization { enable = false }` 而非 `isMinifyEnabled`；R8 规则放 `app/src/main/keepRules/`。

---

## 测试数据

开发期测试数据可随时清空重建。正式数据迁移（从外部迁入本 App）在完全可用后才开始。
