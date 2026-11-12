// SPDX-License-Identifier: Apache-2.0
// The consumer app of the merged-manifest gate. -Pcfg selects the dependencies:
//   C1  the app + faceblur-android (the library as an app gets it), plus androidx.lifecycle:lifecycle-process, whose
//       ProcessLifecycleInitializer is an androidx.startup initializer that must survive the library's removal;
//   C0  CONTROL: the same app with onnxruntime-android and lifecycle-process but WITHOUT FaceBlur, so ONNX Runtime's
//       telemetry starter must be present (proves that the check can fail).
// Release build, not minified, unsigned; no code of its own. The only code is the device test of the published library
// (src/androidTest, C1): scripts/consumer-device-test.sh builds and runs it.
plugins {
    id("com.android.application")
}

val cfg = providers.gradleProperty("cfg").getOrElse("C1")
val libraryVersion =
    rootDir.resolve("../../gradle.properties").readLines()
        .first { it.startsWith("VERSION_NAME=") }
        .substringAfter('=')

android {
    namespace = "io.github.shishark.faceblur.consumer"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "io.github.shishark.faceblur.consumer"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1"
        // The device test of the published library (src/androidTest, C1 only): scripts/consumer-device-test.sh.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    lint {
        // The gate reads manifests only; lint of an app without code would add time and tooling downloads.
        checkReleaseBuilds = false
    }
}

dependencies {
    when (cfg) {
        "C1" -> implementation("io.github.shishark:faceblur-android:$libraryVersion")
        "C0" -> implementation(libs.onnxruntime.android)
        else -> error("-Pcfg must be C1 or C0")
    }
    // Latest stable 2.x used by the manifest matrix of the library's Android binding.
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    // Device test (C1): the library's pinned test tooling versions. Not part of the release build.
    if (cfg == "C1") {
        androidTestImplementation(libs.junit4)
        androidTestImplementation(libs.androidx.test.runner)
        androidTestImplementation(libs.androidx.test.ext.junit)
        // One Kotlin standard library for the test app: the version of the library build (the test tooling asks for
        // an older one, whose metadata would otherwise have to be verified as well).
        constraints {
            androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib") {
                version { strictly(libs.versions.kotlin.get()) }
            }
        }
    }
}
