// SPDX-License-Identifier: Apache-2.0

// Version floors for build-tool libraries with published advisories (gradle/advisory-floors.properties).
// Constraints only raise versions already on the plugin classpath; they add nothing.
buildscript {
    val floors =
        java.util.Properties().apply {
            val file = layout.projectDirectory.file("gradle/advisory-floors.properties")
            load(providers.fileContents(file).asText.get().reader())
        }
    dependencies {
        constraints {
            floors.forEach { (module, version) ->
                classpath("$module:$version") { because("published advisory fixed in this version") }
            }
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.maven.publish) apply false
}
