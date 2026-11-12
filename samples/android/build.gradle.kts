// SPDX-License-Identifier: Apache-2.0
// :samples:android - the Android demo app (not published). It uses the library through its public API only, with the
// one dependency line below; in your own app that line is `implementation("io.github.shishark:faceblur:<version>")`.
import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

plugins {
    id("com.android.application")
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
}

android {
    namespace = "io.github.shishark.faceblur.sample"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.shishark.faceblur.sample"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Shrunk with the consumer rules that the library ships, as an app would use them.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        absolutePaths = false
        // Version-update checks depend on the day the build runs; every pin is a reviewed decision.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric tests of the screen need the app's resources.
        unitTests.isIncludeAndroidResources = true
    }
}

// Robolectric runs offline from the Gradle-verified Android framework jar (same set-up as the library's host tests).
val robolectricAndroidAll: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    implementation(project(":faceblur"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    // Robolectric 4.17 pulls Guava 33.6.0-jre, which has a HIGH advisory (fixed from 33.7.2): raise it for tests only.
    constraints.add("testImplementation", "com.google.guava:guava:${libs.versions.guava.test.floor.get()}") {
        because("test-only Guava below 33.7.2 has a HIGH advisory")
    }
    robolectricAndroidAll(libs.robolectric.android.all)
}

val robolectricDepsDir = layout.buildDirectory.dir("robolectric-deps")
val robolectricDeps =
    tasks.register<Sync>("robolectricDeps") {
        description = "Copies the Gradle-verified Robolectric android-all jar to build/robolectric-deps/."
        from(robolectricAndroidAll)
        into(robolectricDepsDir)
    }
tasks.withType<Test>().configureEach {
    inputs.files(
        robolectricDeps,
    ).withPropertyName("robolectricAndroidAll").withPathSensitivity(PathSensitivity.NAME_ONLY)
    systemProperty("robolectric.offline", "true")
    systemProperty(
        "robolectric.dependency.dir",
        robolectricDepsDir.get().asFile.relativeTo(projectDir).invariantSeparatorsPath,
    )
    environment("ORT_DISABLE_TELEMETRY", "1")
}

// Line coverage of the demo's own code (screen logic, state machine, screen texts and the engine's helpers; the
// engine's calls into the library are exercised on the emulator and covered by the library's own tests).
kover {
    reports {
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

ktlint {
    version.set(libs.versions.ktlint.engine.get())
    relative.set(true)
}

// DEMO-009: the merged RELEASE manifest must hold neither of ONNX Runtime's two permissions (nor any location,
// broad-storage or media permission). The two `tools:node="remove"` lines in src/main/AndroidManifest.xml do that;
// this check reads the merged result and fails the build when a forbidden permission is present.
abstract class CheckMergedManifest : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val file = mergedManifest.get().asFile
        // Fail closed: a missing, empty or wrong input file must never pass as "no forbidden permission".
        val xml = if (file.isFile) file.readText() else ""
        if (xml.isBlank() || !Regex("""<manifest[\s>]""").containsMatchIn(xml)) {
            throw GradleException(
                "The merged release manifest is missing, empty or has no <manifest> root; it cannot be checked.",
            )
        }
        val declared =
            Regex("""<uses-permission[^>]*?android:name="([^"]+)"""")
                .findAll(xml)
                .map { it.groupValues[1] }
                .toList()
        val forbidden =
            declared.filter { name ->
                name == "android.permission.INTERNET" ||
                    name == "android.permission.ACCESS_NETWORK_STATE" ||
                    name.endsWith("_LOCATION") ||
                    name.endsWith("_EXTERNAL_STORAGE") ||
                    name.startsWith("android.permission.READ_MEDIA_")
            }
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "Merged release manifest of the demo declares forbidden permissions: ${forbidden.joinToString()}. " +
                    "Keep the two tools:node remove lines in samples/android/src/main/AndroidManifest.xml.",
            )
        }
        logger.lifecycle(
            "Merged release manifest: no network, location or storage permission (${declared.size} declared)",
        )
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val checkManifest =
            tasks.register<CheckMergedManifest>("checkReleaseMergedManifest") {
                group = "verification"
                description = "Fails if the merged release manifest declares a network, location or storage permission."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            }
        tasks.named("check") { dependsOn(checkManifest) }
    }
}
