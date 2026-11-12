// SPDX-License-Identifier: Apache-2.0
// Guide-snippet consumer: a minimal Android app that compiles every file of
// docs/guides/snippets/android/ (Kotlin and Java) against the library as an app gets it, from the repository-local
// Maven directory (build/local-maven, `publishAllPublicationsToLocalRepoRepository`), never through includeBuild, and
// takes the guide's no-network manifest as its own manifest. A standalone Gradle build, not part of the verification
// build's `check`: scripts/check-guide-snippets.sh builds it (-Pmanifest=snippet) and a control (-Pmanifest=plain) and
// checks the compiled classes and the permissions of the APK. Plugin and library versions come from the library
// build's version catalog.
rootProject.name = "faceblur-android-snippets"

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
