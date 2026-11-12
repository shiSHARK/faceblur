// SPDX-License-Identifier: Apache-2.0
// Merged-manifest consumer (ADR 0007, amended): a minimal Android app built against the library from the
// repository-local Maven directory (build/local-maven, `publishAllPublicationsToLocalRepoRepository`), never through
// includeBuild. A standalone build, not part of the verification build's `check`: scripts/check-consumer-manifest.sh
// builds it twice (-Pcfg=C1: the app + faceblur-android; -Pcfg=C0: control, ONNX Runtime without FaceBlur) and checks
// the merged manifest and the APK's binary manifest. Plugin and library versions come from the library build's
// version catalog, so the consumer always uses the library's Android Gradle Plugin and ONNX Runtime versions.
rootProject.name = "faceblur-android-consumer"

pluginManagement {
    val catalog = file("../../gradle/libs.versions.toml").readLines()
    val agp = catalog.first { it.startsWith("agp = ") }.substringAfter('"').substringBefore('"')
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version agp
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The library only from the local publication; nothing else is taken from there.
        exclusiveContent {
            forRepository {
                maven {
                    name = "localLibrary"
                    url = uri(file("../../build/local-maven"))
                }
            }
            filter { includeGroup("io.github.shishark") }
        }
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

include(":app")
