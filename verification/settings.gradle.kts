// SPDX-License-Identifier: Apache-2.0
// Verification build (ADR 0001 decision 3): a separate Gradle build, never part of the library build.
// It never uses includeBuild; the library, once a consumer module exists, is resolved only from the
// repository-local Maven directory.
rootProject.name = "faceblur-verification"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(":jpeg-privacy")
include(":pixel-oracle")
