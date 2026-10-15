# Single-ABI build (product flavors removed)

The `v7` / `v8` product flavors are gone. The project builds one APK for **arm64-v8a**:

    ./gradlew :app:assembleDebug      # installable on a phone (debug-signed)
    ./gradlew :app:assembleRelease    # NOT signed: the release build type has no signingConfig

## What changed
- `AndroidModuleConf.kt`: no `flavorDimensions`/`productFlavors`; every module gets `ndk.abiFilters = arm64-v8a`;
  version code keeps the old v8 value (`10 * base + 2`); APK name is `CodeOnTheGo-<buildType>-<date>.apk`.
- `AndroidIDEAssetsPlugin.kt`, `app/build.gradle.kts`: `assembleV8Release` -> `assembleRelease`,
  `logsender-v8-release.aar` -> `logsender-release.aar`, same for `quickbuild-runtime`.
- `app/build.gradle.kts` `recompressApk` now reads `outputs/apk/<buildType>` (no ABI folder).
- Root `build.gradle.kts`: Sonar/JaCoCo paths use `debug` instead of `v8Debug`.
- CI: `debug.yml`, `analyze.yml`, `instrumentation-test.yml` use the flavorless task names.

## Kept on purpose
- `assets/release/common` and `assets/release/v8` must still exist (release builds bundle assets from them).
  `v8` is now only a folder name.
- `assembleV8Assets` / `assembleV7Assets` (asset zip generation for `generate_assets.yml`) are unchanged.
- `release.yml` / `weekly-release.yml` still describe the upstream 32-bit + 64-bit pipelines and no longer match this build.
  Disable them in your fork (Actions tab) or adapt them.

## Not verified
Gradle could not be run while making this change (no network / toolchain). The first CI build is the real test.
