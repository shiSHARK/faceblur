// SPDX-License-Identifier: Apache-2.0
// The app of the guide-snippet gate. It has no code of its own: its sources are the files of
// docs/guides/snippets/android/ (Kotlin and Java), compiled against the published library. -Pmanifest selects the
// manifest:
//   snippet  the guide's AndroidManifest-no-network.xml (the app's own manifest, merged with the libraries');
//   plain    CONTROL: a plain manifest without the two removal lines, so the permissions that ONNX Runtime declares
//            must appear (proves that the permission check can fail).
// Release build, not minified, unsigned.
plugins {
    id("com.android.application")
}

val snippets = rootDir.resolve("../../docs/guides/snippets/android")
val manifestChoice = providers.gradleProperty("manifest").getOrElse("snippet")
val libraryVersion =
    rootDir.resolve("../../gradle.properties").readLines()
        .first { it.startsWith("VERSION_NAME=") }
        .substringAfter('=')

android {
    namespace = "io.github.shishark.faceblur.snippets"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "io.github.shishark.faceblur.snippets"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main") {
        java.srcDir(snippets)
        kotlin.srcDir(snippets)
        when (manifestChoice) {
            "snippet" -> manifest.srcFile(snippets.resolve("AndroidManifest-no-network.xml"))
            "plain" -> manifest.srcFile("src/control/AndroidManifest.xml")
            else -> error("-Pmanifest must be snippet or plain")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    lint {
        // The gate compiles and reads the manifest only.
        checkReleaseBuilds = false
    }
}

dependencies {
    // The coordinate the guide gives; Gradle picks the Android variant of the multiplatform module.
    implementation("io.github.shishark:faceblur:$libraryVersion")
}
