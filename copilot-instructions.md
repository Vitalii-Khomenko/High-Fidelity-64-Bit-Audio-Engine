# Workspace Instructions

- After every code change, fix, optimization, or feature update, bump both `versionCode` and `versionName` in `app/build.gradle.kts`. `versionCode` increases by at least 1; `versionName` gets at least a patch increment for fixes.
- Run `bash tests/native/run.sh` after changes under `src/` and `./gradlew testDebugUnitTest lintDebug` after changes under `app/`.
- Engine rules: no locks, allocation or logging in `OboeOutput::onAudioReady`; queries in `AudioPlayer` must never wait for decoding or I/O.
- Playback state belongs to `PlaybackService`; the UI only renders `PlayerState` and calls `PlayerCommands`.
- Update `CHANGELOG.md` and the documents in `docs/` when behaviour changes.
- After every version bump, build the signed audit APK (`./gradlew assembleAudit`) and publish it to `dist/` together with the code: replace `dist/HiFi-Player-audit.apk`, update the version and SHA-256 in `dist/README.md`, and commit both. `dist/` must contain only the latest APK, never older ones. Never commit keystores, `signing/` or `local.properties`.
