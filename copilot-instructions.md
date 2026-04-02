# Workspace Instructions

- After every code change, fix, optimization, or feature update, always bump both `versionCode` and `versionName` in `app/build.gradle.kts`.
- Keep `versionCode` strictly increasing by at least 1 on every change.
- Keep `versionName` user-visible and coherent with the scope of the update. Use at least a patch increment for fixes.
- Treat the version bump as part of the definition of done for every future modification in this repository.