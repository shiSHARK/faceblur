// SPDX-License-Identifier: Apache-2.0

// The library build's version floors for build-tool libraries with published advisories
// (gradle/advisory-floors.properties of the library build), and the library build's Kotlin Gradle Plugin version for
// the one the Android Gradle Plugin applies as its built-in Kotlin support (an older version has a published
// advisory). Constraints only raise versions already on the plugin classpath; they add nothing.
buildscript {
    val floors =
        java.util.Properties().apply {
            val file = layout.projectDirectory.file("../../gradle/advisory-floors.properties")
            load(providers.fileContents(file).asText.get().reader())
        }
    val kotlinVersion =
        providers.fileContents(layout.projectDirectory.file("../../gradle/libs.versions.toml")).asText.get()
            .lines()
            .first { it.startsWith("kotlin = ") }
            .substringAfter('"')
            .substringBefore('"')
    dependencies {
        constraints {
            floors.forEach { (module, version) ->
                classpath("$module:$version") { because("published advisory fixed in this version") }
            }
            classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion") {
                because("same version as the library build")
            }
        }
    }
}

plugins {
    id("com.android.application") apply false
}
