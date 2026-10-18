Fixed build files (apply TOGETHER - they depend on each other)
================================================================
Unzip at the ROOT of your repository (the folder that contains "android1/").

Files and what they fix:
  app/build.gradle.kts                 restores the plugin Maven-repo pipeline
                                       (createPluginMavenRepoZip, createPluginArtifactsZip, assemblePluginApiFatJar,
                                        writePluginApiPom, copyPluginApiJarToAssets, recompressApk) and uses
                                        flavorless task names (assembleDebug / assembleRelease)
  build.gradle.kts                     flavorless Sonar/JaCoCo paths
  .../plugins/AndroidIDEAssetsPlugin.kt  flavorless logsender aar task/path
  .../plugins/conf/AndroidModuleConf.kt  no product flavors, arm64-v8a only

Commit:
  git add android1/CodeOnTheGo-stage/app/build.gradle.kts android1/CodeOnTheGo-stage/build.gradle.kts \
          android1/CodeOnTheGo-stage/composite-builds/build-logic/plugins/src/main/java/com/itsaky/androidide/plugins
  git commit -m "Restore plugin Maven repo pipeline; single-ABI (no flavors) build"
  git push

NOT verified by a Gradle run (none was possible where these were prepared). Run in CI:
  ./gradlew :app:tasks --all | grep createPluginMavenRepoZip
  ./gradlew :app:createPluginMavenRepoZip
  ./gradlew :app:assembleDebug --stacktrace

SHA-256 (first 16 chars):
  36940fc7e4f405c1  app/build.gradle.kts
  c2067f3938b575b6  build.gradle.kts
  5ae9b3d32be45941  composite-builds/build-logic/plugins/src/main/java/com/itsaky/androidide/plugins/AndroidIDEAssetsPlugin.kt
  a3fee2121744a3c0  composite-builds/build-logic/plugins/src/main/java/com/itsaky/androidide/plugins/conf/AndroidModuleConf.kt
