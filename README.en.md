# NoteONE

[简体中文](README.md) | **English**

**Local-encrypted, tag-based archiving & filtering. Targets Xiaomi 14 (Android 16+).**

**100% AI-generated (Claude Code + deepseekv4.1flash + mimo v2.6pro).**

> Note: the app UI and the project documentation (`docs/`, `CLAUDE.md`) are written in Chinese.

## Features

- **Faceted tags**: in `#短视频#萌宠#延时摄影`, each tag belongs to its own dimension
  (length / topic / technique). No preset dimensions or values — you create everything;
  rename / merge / delete and custom display order are supported.
- **Full-database + envelope encryption**: SQLCipher encrypts the whole database; one random
  DEK is wrapped twice — an Android Keystore hardware key (daily use, fingerprint unlock) and
  a master-password-derived key (PBKDF2, 600k iterations; for device migration / emergency).
- **Fingerprint unlock**: system BiometricPrompt; verification is required on cold start, with
  a 60-second grace period when switching out and back; the master password is always a fallback.
- **High-security mode** (optional, off by default): enrolling a new fingerprint invalidates the
  hardware-key copy, with a complete "invalidate → guide → rebuild" recovery loop.
- **Export / import**: encrypted or plaintext JSON (with `schema_version`), the disaster-recovery channel.
- **No network**: `USE_BIOMETRIC` is the only permission in the manifest; `allowBackup=false`,
  so system cloud backup can never touch your data.

## Build

Requirements:

- Android SDK: `compileSdk 37` / `minSdk 36` / `targetSdk 37`
- JDK 17+ to launch Gradle (daemon JVM target: see `gradle/gradle-daemon-jvm.properties`)
- **Bring your own signing key.** The build intentionally does NOT silently fall back to a debug
  signature (that would produce packages that install but can never be upgraded). A missing
  `keystore.properties` fails the build:

```bash
keytool -genkeypair -keystore noteone.p12 -alias noteone -keyalg RSA -keysize 4096 -validity 10000
```

Create `keystore.properties` in the project root (**not** committed; use forward slashes —
backslashes are eaten by properties parsing):

```properties
storeFile=/absolute/path/noteone.p12
storePassword=…
keyAlias=noteone
keyPassword=…
```

```bash
./gradlew :app:assembleDebug     # debug build
./gradlew :app:assembleRelease   # release build (same signing key as debug)
```

> Install requirement: `minSdk 36` — **Android 16 and above only**.

The Gradle wrapper points at a Tencent mirror (fast in China); switch it back to
`services.gradle.org` if you prefer.

## Docs (Chinese)

| Doc | Content |
|---|---|
| `CLAUDE.md` | Development constraints (data-safety red lines, architecture seams, trade-offs, known debt) |
| `docs/spec.md` | Design spec: data model, encryption architecture, export format (code wins on conflicts) |
| `docs/HANDOFF.md` | Full development log: progress, pitfalls, tuning parameters, on-device evidence |

## License

[MIT](LICENSE)
