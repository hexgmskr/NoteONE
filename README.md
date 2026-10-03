# NoteONE

[English](README.en.md) | 简体中文


**本地加密、标签化归档筛选，兼容小米14（Android 16+）**
**100%AI生成（ClaudeCode+deepseekv4.1flash+mimov2.6pro）**



## 特性

- **多维分面标签**：`#短视频#萌宠#延时摄影` 每个标签属于不同维度（长度 / 主题 / 拍摄手法），
  维度与值零预置、全部自建；支持改名 / 合并 / 删除与自定义展示顺序
- **整库加密 + 信封加密**：SQLCipher 整库加密；一把随机 DEK，两份密文——
  Android Keystore 硬件钥匙（日常支持指纹解锁）与主密码派生密钥（PBKDF2 60 万迭代，换机 / 救急）
- **指纹解锁**：系统 BiometricPrompt；冷启动必验证，退后台 60 秒内免重复验证；主密码退路始终在
- **高安全模式**（可选，默认关）：录入新指纹即作废硬件钥匙副本，带完整的"作废→引导→重建"闭环
- **导出 / 导入**：加密或明文 JSON（含 `schema_version`），灾难恢复通道
- **不联网**：Manifest 里只有 `USE_BIOMETRIC` 一个权限；`allowBackup=false`，系统云备份碰不到数据

## 构建

前置：

- Android SDK：`compileSdk 37` / `minSdk 36` / `targetSdk 37`
- JDK 17+（启动 Gradle 用；daemon 目标平台见 `gradle/gradle-daemon-jvm.properties`）
- **签名钥匙自备**。构建刻意不支持静默退回调试签名（静默会签出"装得上、以后升级不了"的
  包），项目根缺 `keystore.properties` 时直接报错：

```bash
keytool -genkeypair -keystore noteone.p12 -alias noteone -keyalg RSA -keysize 4096 -validity 10000
```

项目根建 `keystore.properties`（**不入库**；路径写正斜杠，反斜杠会被 properties 解析吃掉）：

```properties
storeFile=/绝对路径/noteone.p12
storePassword=…
keyAlias=noteone
keyPassword=…
```

```bash
./gradlew :app:assembleDebug     # 构建（调试）
./gradlew :app:assembleRelease   # 构建（正式；与调试共用同一把签名钥匙）
```

> 安装要求：`minSdk 36`——**仅 Android 16 及以上**设备可安装。

wrapper 的下载地址指向腾讯镜像，可自行改回 `services.gradle.org` 官方源。

## 文档

| 文档 | 内容 |
|---|---|
| `CLAUDE.md` | 开发硬约束（数据安全红线、架构接缝、技术取舍、已知债务） |
| `docs/spec.md` | 设计规格：数据模型、加密架构、导出格式（与实现冲突时以代码为准） |
| `docs/HANDOFF.md` | 开发过程全记录：阶段进度、已踩的坑、可调参数位置、真机实证 |

## 许可

[MIT](LICENSE)
