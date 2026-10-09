# Workspace Instructions

- After every code change, fix, optimization, or feature update, bump both `versionCode` and `versionName` in `app/build.gradle.kts`. `versionCode` increases by at least 1; `versionName` gets at least a patch increment for fixes.
- Run `bash tests/native/run.sh` after changes under `src/` and `./gradlew testDebugUnitTest lintDebug` after changes under `app/`.
- Engine rules: no locks, allocation or logging in `OboeOutput::onAudioReady`; queries in `AudioPlayer` must never wait for decoding or I/O.
- Playback state belongs to `PlaybackService`; the UI only renders `PlayerState` and calls `PlayerCommands`.
- Update `CHANGELOG.md` and the documents in `docs/` when behaviour changes.
