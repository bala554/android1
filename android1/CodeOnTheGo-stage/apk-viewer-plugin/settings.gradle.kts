rootProject.name = "apk-viewer-plugin"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}

include(":plugin-api")
project(":plugin-api").projectDir = file("plugin-api")

// Relocated to the root level so gradle.includedBuild() can finally find it
includeBuild("plugin-api/plugin-builder")
