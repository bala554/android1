rootProject.name = "apk-viewer-plugin[span_1](start_span)"[span_1](end_span)

pluginManagement {[span_2](start_span)[span_2](end_span)
    repositories {[span_3](start_span)[span_3](end_span)
        google()[span_4](start_span)[span_4](end_span)
        mavenCentral()[span_5](start_span)[span_5](end_span)
        gradlePluginPortal()[span_6](start_span)[span_6](end_span)
    }
}

dependencyResolutionManagement {[span_7](start_span)[span_7](end_span)
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)[span_8](start_span)[span_8](end_span)
    repositories {[span_9](start_span)[span_9](end_span)
        mavenCentral()[span_10](start_span)[span_10](end_span)
        google()[span_11](start_span)[span_11](end_span)
        gradlePluginPortal()[span_12](start_span)[span_12](end_span)
    }
}

include(":plugin-api")[span_13](start_span)[span_13](end_span)
project(":plugin-api").projectDir = file("../plugin-api")[span_14](start_span)[span_14](end_span)

// Relocated to the root level so gradle.includedBuild() can finally find it
includeBuild("../plugin-api/plugin-builder")
