// SPDX-License-Identifier: Apache-2.0
rootProject.name = "faceblur-build"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

// The published library: io.github.shishark:faceblur
include(":faceblur")

// The Android demo app (not published): an app module that depends on the library through its public API only.
include(":samples:android")
