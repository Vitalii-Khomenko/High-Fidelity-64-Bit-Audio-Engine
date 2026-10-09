# Building

## Toolchain

| Component | Version |
|---|---|
| JDK | 17 or 21 (Gradle 8.12 does not run on newer JDKs) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin / Compose compiler | 1.9.25 / 1.5.15 (Compose BOM 2024.09.03) |
| compileSdk / targetSdk / minSdk | 35 / 35 / 24 |
| NDK | 28.2.13676358 (16 KiB page-size compatible) |
| CMake | 3.22.1 |
| Oboe | 1.9.3 (prefab) |

`local.properties` (ignored by git) must point to the SDK:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Variants

| Command | Output | Package | Signing |
|---|---|---|---|
| `./gradlew assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` | `com.aiproject.musicplayer` | debug key |
| `./gradlew assembleRelease` | `…/release/app-release.apk` | `com.aiproject.musicplayer` | your release key |
| `./gradlew assembleAudit` | `…/audit/app-audit.apk` | `com.aiproject.musicplayer.audit` | separate test key |

The audit variant is the release build with its own application id and label
(*HiFi Player Audit*), so it installs next to the main app with separate data.

ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`. The native library is always
compiled with `-O2` so DSD decimation and time-stretching keep up in debug
builds too.

## Signing

No production key is stored in the repository. Add the credentials to
`local.properties`:

```properties
KEYSTORE_FILE=../hifi-player.jks
KEYSTORE_PASSWORD=...
KEY_ALIAS=hifi
KEY_PASSWORD=...

# optional, for assembleAudit
AUDIT_KEYSTORE_FILE=../signing/hifi-audit.jks
AUDIT_KEYSTORE_PASSWORD=...
AUDIT_KEY_ALIAS=hifi-audit
AUDIT_KEY_PASSWORD=...
```

Create a key with:

```bash
keytool -genkeypair -v -keystore hifi-player.jks -alias hifi -keyalg RSA -keysize 3072 -validity 10000
```

Keep the keystore: Android only accepts updates signed with the same key.

## Installing

```bash
./gradlew installDebug
# or
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A debug build cannot update an installed release build (different key);
uninstall first or use the audit variant.

## Versioning

Every change bumps `versionCode` and `versionName` in `app/build.gradle.kts`
(see `copilot-instructions.md`).
