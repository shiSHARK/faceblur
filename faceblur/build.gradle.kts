// SPDX-License-Identifier: Apache-2.0
// :faceblur — the published library io.github.shishark:faceblur.
// Targets: android (library, minSdk 30), iosArm64, iosSimulatorArm64, jvm (tests and tools).
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFrameworkTask
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.dokka)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.maven.publish)
}

// ---------------------------------------------------------------------------------------------
// Local test fixtures (kept OUT of the public tree). The library ships no model: nothing below reaches a compilation
// of the library or a published artifact. The public tree holds no reference model, no derived model and no
// face image for the tests; the maintainer's local merge gate supplies them from a folder outside every repository.
//
// The folder is named by the Gradle property `faceblur.localFixtures` or the environment variable
// FACEBLUR_LOCAL_FIXTURES (tools/merge_gate*.sh always set it). With it set:
//   syncLocalFixtures  verifies every file listed in <folder>/MANIFEST.sha256 (missing file or hash differs = the build
//                      FAILS), copies them to build/local-fixtures/ and verifies the copies
//   fetchYunetModel    <folder>/<reference model> -> build/test-models/<file>, size + SHA-256 checked against the pins
//                      in <folder>/yunet/yunet.properties
//   deriveTestModels   build/test-models/<file> -> build/test-models/derived/{G0,G1,G2,G3}.onnx, built by the
//                      test-source writer, each checked against its pin (source re-checked first)
//   fuzzOnnxPreParse   crash-freedom fuzzing of the ONNX pre-parse walker on mutations of the synthetic model (and,
//                      with the folder set, of the fixtures above)
// and jvmTest and testAndroidHostTest run EVERY test and get the fixture directories as the system properties
// faceblur.testModelsDir and faceblur.localFixturesDir.
// Without it (public CI) none of these tasks exists and the tests listed in config/local-fixture-tests.txt are
// excluded by name (the CI step `local-fixture-tests` reports them as NOT RUN). A listed test that runs must pass:
// scripts/check-local-fixture-tests.py checks the results of the gate run. Negative controls: scripts/test-model-step.sh.
// ---------------------------------------------------------------------------------------------
val localFixturesPath: String? =
    providers.gradleProperty("faceblur.localFixtures")
        .orElse(providers.environmentVariable("FACEBLUR_LOCAL_FIXTURES"))
        .orNull
        ?.takeIf { it.isNotBlank() }
val localFixturesDir: File? = localFixturesPath?.let { File(it) }
val localFixturesMode: Boolean = localFixturesDir != null
if (localFixturesDir != null && !File(localFixturesDir, "MANIFEST.sha256").isFile) {
    throw GradleException(
        "Local fixtures check FAILED: faceblur.localFixtures / FACEBLUR_LOCAL_FIXTURES is set but names no folder " +
            "with a MANIFEST.sha256. Unset it for a public-tree build.",
    )
}
val yunetPin: Properties =
    Properties().apply {
        if (localFixturesDir != null) {
            val pinFile = File(localFixturesDir, "yunet/yunet.properties")
            if (!pinFile.isFile) {
                throw GradleException("Local fixtures check FAILED: yunet/yunet.properties is missing in the folder.")
            }
            val text = providers.fileContents(layout.file(providers.provider { pinFile })).asText
            load(text.get().reader())
        }
    }

fun yunetPinValue(key: String): String = requireNotNull(yunetPin.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }) {
    "local fixtures yunet/yunet.properties: '$key' is missing"
}

/** Size and SHA-256 check shared by the model tasks (an object, so task classes do not capture the script). */
object YunetModelCheck {
    /** Fails unless [bytes] has exactly the pinned size and SHA-256. [what] must not contain local paths. */
    fun verify(bytes: ByteArray, expectedSize: Long, expectedSha256: String, what: String) {
        if (bytes.size.toLong() != expectedSize) {
            throw GradleException(
                "YuNet model check FAILED for $what: size ${bytes.size} bytes, pinned $expectedSize " +
                    "(local fixtures yunet/yunet.properties).",
            )
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            throw GradleException(
                "YuNet model check FAILED for $what: SHA-256 mismatch, got $actual, pinned $expectedSha256 " +
                    "(local fixtures yunet/yunet.properties).",
            )
        }
    }
}

/**
 * Verifies the local fixtures against <folder>/MANIFEST.sha256 (`<sha256>  <relative path>` per line; a missing file,
 * an unlisted-path escape or a different hash FAILS the build), copies the listed files to build/local-fixtures/ and
 * verifies the copies. The folder lies outside every repository and is never uploaded.
 */
@DisableCachingByDefault(because = "Verifies and copies local files; re-checked in every run")
abstract class SyncLocalFixtures : DefaultTask() {
    /** Absolute path of the local fixtures folder. Never printed (it contains local names). */
    @get:Input
    abstract val sourceFolder: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun sync() {
        val src = File(sourceFolder.get()).canonicalFile
        val manifest = File(src, "MANIFEST.sha256")
        if (!manifest.isFile) throw GradleException("Local fixtures check FAILED: MANIFEST.sha256 is missing.")
        val entries = manifest.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map {
            val m = Regex("^([0-9a-f]{64}) {2}(\\S.*)$").matchEntire(it)
                ?: throw GradleException("Local fixtures check FAILED: a MANIFEST.sha256 line is malformed.")
            m.groupValues[1] to m.groupValues[2]
        }
        if (entries.isEmpty()) throw GradleException("Local fixtures check FAILED: MANIFEST.sha256 lists no file.")
        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val outRoot = out.canonicalFile
        for ((expected, relative) in entries) {
            val from = File(src, relative).canonicalFile
            if (!from.path.startsWith(src.path + File.separator)) {
                throw GradleException("Local fixtures check FAILED: $relative leaves the fixtures folder.")
            }
            if (!from.isFile) throw GradleException("Local fixtures check FAILED: $relative is missing.")
            val bytes = from.readBytes()
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                throw GradleException(
                    "Local fixtures check FAILED: SHA-256 mismatch for $relative, got $actual, listed $expected.",
                )
            }
            val to = File(outRoot, relative)
            to.parentFile.mkdirs()
            to.writeBytes(bytes)
            val copy = MessageDigest.getInstance("SHA-256").digest(to.readBytes()).joinToString("") {
                "%02x".format(it)
            }
            if (copy != expected) throw GradleException("Local fixtures check FAILED: the copy of $relative differs.")
        }
        File(outRoot, "MANIFEST.sha256").writeBytes(manifest.readBytes())
        logger.lifecycle(
            "Local fixtures: ${entries.size} files verified against MANIFEST.sha256 and copied to build/local-fixtures/",
        )
    }
}

@DisableCachingByDefault(because = "Copies one pinned file; the result lives in build/test-models/")
abstract class FetchYunetModel : DefaultTask() {
    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val sizeBytes: Property<Long>

    /** Absolute path of the local copy (build/local-fixtures/), or an override for the negative controls. */
    @get:Input
    abstract val localFile: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun fetch() {
        val out = outputFile.get().asFile
        val size = sizeBytes.get()
        val sha = sha256.get()
        if (out.exists()) {
            // An existing file is never overwritten silently: a modified fixture fails the build.
            YunetModelCheck.verify(
                out.readBytes(),
                size,
                sha,
                "build/test-models/${out.name} (delete build/test-models/ to copy again)",
            )
            logger.lifecycle("YuNet model: build/test-models/${out.name} present, size and SHA-256 OK")
            return
        }
        val file = File(localFile.get())
        if (!file.isFile) {
            throw GradleException("YuNet model source missing: the local fixture copy does not exist.")
        }
        if (file.length() > size) {
            throw GradleException("YuNet model check FAILED for the local copy: larger than the pinned $size bytes.")
        }
        val bytes = file.readBytes()
        val source = "the local fixture copy"
        YunetModelCheck.verify(bytes, size, sha, source)
        out.parentFile.mkdirs()
        val tmp = File.createTempFile("yunet-", ".part", out.parentFile)
        try {
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
        logger.lifecycle("YuNet model: written to build/test-models/${out.name} from $source, size and SHA-256 OK")
    }
}

/** Generates the synthetic HEIC device-test fixture with tools/build_heic_fixture.sh (macOS) and checks its SHA-256. */
@DisableCachingByDefault(because = "Runs a generator on the Mac and is re-checked in every run")
abstract class GenerateHeicFixture : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val script: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generatorSources: ConfigurableFileCollection

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        if (!System.getProperty("os.name").startsWith("Mac")) {
            throw GradleException(
                "generateHeicFixture needs macOS (ImageIO through swiftc). The HEIC device test fails without " +
                    "its fixture; it is not skipped.",
            )
        }
        val out = outputFile.get().asFile
        out.delete()
        exec.exec {
            commandLine("sh", script.get().asFile.path, out.parentFile.path)
            standardOutput = ByteArrayOutputStream()
        }
        if (!out.isFile) throw GradleException("HEIC fixture generation produced no file.")
        val actual = MessageDigest.getInstance("SHA-256").digest(out.readBytes()).joinToString("") { "%02x".format(it) }
        if (actual != sha256.get()) {
            throw GradleException(
                "HEIC fixture check FAILED: SHA-256 $actual, expected ${sha256.get()} (the encoder of this macOS " +
                    "differs from the one the pin was taken on; the pin is changed only by a reviewed edit).",
            )
        }
    }
}

/** The fixture directory (repository root `build/test-models/`, git-ignored, never uploaded or cached as a whole). */
val testModelsDir: Provider<Directory> = rootProject.layout.buildDirectory.dir("test-models")

/** The verified copy of the local fixtures (repository root `build/local-fixtures/`, git-ignored, never uploaded). */
val localFixturesBuildDir: Provider<Directory> = rootProject.layout.buildDirectory.dir("local-fixtures")

val syncLocalFixtures: TaskProvider<SyncLocalFixtures>? =
    if (localFixturesPath == null) {
        null
    } else {
        tasks.register<SyncLocalFixtures>("syncLocalFixtures") {
            group = "verification"
            description = "Verifies the maintainer's local fixtures against their manifest and copies them to " +
                "build/local-fixtures/."
            sourceFolder.set(localFixturesPath)
            outputDirectory.set(localFixturesBuildDir)
            // Never up to date: the files are re-checked in every run.
            outputs.upToDateWhen { false }
        }
    }

val fetchYunetModel: TaskProvider<FetchYunetModel>? =
    if (syncLocalFixtures == null) {
        null
    } else {
        tasks.register<FetchYunetModel>("fetchYunetModel") {
            group = "verification"
            description = "Copies the local reference model (a test fixture) to build/test-models/ and checks its " +
                "size and SHA-256."
            dependsOn(syncLocalFixtures)
            sha256.set(yunetPinValue("sha256"))
            sizeBytes.set(yunetPinValue("size").toLong())
            // The override is only for the negative controls (scripts/test-model-step.sh).
            localFile.set(
                providers.gradleProperty("faceblur.yunet.localFile").orElse(
                    localFixturesBuildDir.map { it.file("yunet/${yunetPinValue("fileName")}").asFile.path },
                ),
            )
            outputFile.set(testModelsDir.map { it.file(yunetPinValue("fileName")) })
            // Never up to date: an existing file is re-checked in every build.
            outputs.upToDateWhen { false }
        }
    }

// The synthetic HEIC input fixture of the Android device tests is NOT committed: it is generated at test time from
// tools/build_heic_fixture.sh (macOS only: ImageIO through swiftc) into build/test-fixtures/heic/ (git-ignored) and
// checked against the SHA-256 of the file the generator produced when the fixture was designed. Any other result,
// a missing tool or another operating system fails the build; the device test itself fails when the file is missing.
// The phone harness (scripts/phone-device-tests.sh) runs this task and places the file in the test app's storage.
val heicFixtureName = "synthetic-pattern-canary-256x192.heic"
val heicToolsDir: Directory = rootProject.layout.projectDirectory.dir("tools")

tasks.register<GenerateHeicFixture>("generateHeicFixture") {
    group = "verification"
    description = "Generates the synthetic HEIC device-test fixture into build/test-fixtures/heic/ (macOS) and " +
        "checks its SHA-256."
    script.set(heicToolsDir.file("build_heic_fixture.sh"))
    generatorSources.from(heicToolsDir.file("heic_fixture.swift"), heicToolsDir.file("heic_fixture_png.py"))
    sha256.set("972349db7acfdeb756cf3d32bc1afb1d4105207491dcb09be5b8ad81c6c25be2")
    outputFile.set(rootProject.layout.buildDirectory.file("test-fixtures/heic/$heicFixtureName"))
    // Never up to date: the file is regenerated and re-checked in every run, so a stale or edited file cannot pass.
    outputs.upToDateWhen { false }
}

kotlin {
    explicitApi()
    jvmToolchain(libs.versions.jvm.target.get().toInt())
    compilerOptions {
        // expect/actual classes are Beta in Kotlin 2.4; the library uses them (kmp-core, ADR 0002).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Kotlin's built-in ABI validation: `checkKotlinAbi` (part of `check`) / `updateKotlinAbi`.
    // The reference dump lives in faceblur/api/ and is reviewed in every change.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()

    android {
        namespace = "io.github.shishark.faceblur"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Android host (JVM) tests: `testAndroidHostTest`.
        withHostTest {}
        // Android device tests (ST-003, ST-049): `assembleAndroidDeviceTest`, run on the test phone by hand.
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        // Consumer R8/ProGuard rules: packaged as proguard.txt in the AAR and applied to every app that minifies
        // with FaceBlur (ONNX Runtime's JNI looks up its Java classes by name). AarConsumerRulesTest checks that the
        // rule is in the built AAR.
        optimization {
            consumerKeepRules.file("consumer-rules.pro")
            consumerKeepRules.publish = true
        }
    }

    // JVM target for tests and tools only; NOT published in 0.x (ADR 0002 decision 11), see "jvm not published"
    // below.
    jvm()

    // Static XCFramework for the Swift package (faceblur-swift): assembleFaceBlurCoreReleaseXCFramework.
    // Published klibs and frameworks must not embed absolute build paths (local user and folder names):
    // klib source paths are made relative to the repository root, debug info paths are remapped.
    val repoRoot = rootProject.projectDir.absolutePath
    val xcFramework = XCFramework("FaceBlurCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add("-Xklib-relative-path-base=$repoRoot")
            }
        }
        target.binaries.framework {
            baseName = "FaceBlurCore"
            isStatic = true
            binaryOption("bundleId", "io.github.shishark.faceblur.FaceBlurCore")
            freeCompilerArgs += "-Xdebug-prefix-map=$repoRoot=."
            xcFramework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // ADR 0002: SuspendingFaceMasker exposes coroutine types, so this is `api`, not `implementation`.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        // ST-033 AC6: the KMP guide's common sample is compiled (and run by KmpGuideSampleTest) for every target
        // that compiles commonTest (JVM, Android host, iOS simulator), so a sample that no longer compiles fails the
        // build. It is test code only: nothing of it reaches the published library.
        commonTest {
            kotlin.srcDir(rootProject.layout.projectDirectory.dir("docs/guides/snippets/kmp"))
        }
        androidMain.dependencies {
            // ONNX Runtime for Android (XNNPACK). The library manifest (src/androidMain/AndroidManifest.xml) removes
            // only its telemetry provider; its INTERNET and ACCESS_NETWORK_STATE permissions are left to the app
            // (ADR 0007, amended).
            implementation(libs.onnxruntime.android)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit4)
            // Robolectric with native graphics: ImageDecoder, Bitmap and the JPEG encoder on the host (ST-003).
            implementation(libs.robolectric)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.junit4)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.ext.junit)
        }
        jvmTest.dependencies {
            // JVM tensor runner for tests (ST-002, ADR 0009). Test scope only; telemetry off (see below).
            implementation(libs.onnxruntime.jvm)
        }
    }
}

// Version floors for build-tool libraries with published advisories, on every configuration of this project
// (lint, Dokka, ktlint, Kover, test classpaths). A floor only raises a lower requested version.
object AdvisoryFloors {
    /** True if dotted version [a] is lower than [b] (numeric parts compared numerically, others as text). */
    fun lower(a: String, b: String): Boolean {
        val pa = a.split('.', '-')
        val pb = b.split('.', '-')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: return true
            val y = pb.getOrNull(i) ?: return false
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            val c = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y)
            if (c != 0) return c < 0
        }
        return false
    }
}

val advisoryFloors: Map<String, String> =
    Properties()
        .apply {
            load(
                providers
                    .fileContents(rootProject.layout.projectDirectory.file("gradle/advisory-floors.properties"))
                    .asText
                    .get()
                    .reader(),
            )
        }.entries
        .associate { (k, v) -> k.toString() to v.toString() }

configurations.configureEach {
    resolutionStrategy.eachDependency {
        val floor = advisoryFloors["${requested.group}:${requested.name}"]
        val current = requested.version
        if (floor != null && current != null && AdvisoryFloors.lower(current, floor)) {
            useVersion(floor)
            because("published advisory fixed in $floor (gradle/advisory-floors.properties)")
        }
    }
}

// The resolved runtime classpaths of the library (Android, JVM, iOS klibs) in gradle.lockfile format,
// so that the CI OSV gate fails on any advisory in a runtime dependency (build-only tools are only reported).
// `./gradlew :faceblur:writeRuntimeLockfile` -> faceblur/build/osv/runtime.lockfile
abstract class WriteRuntimeLockfile : DefaultTask() {
    @get:Input
    abstract val entries: ListProperty<String>

    @get:OutputFile
    abstract val lockfile: RegularFileProperty

    @TaskAction
    fun write() {
        val lines = entries.get().toSortedSet()
        check(lines.isNotEmpty()) { "no runtime dependencies resolved" }
        lockfile.get().asFile.writeText(
            "# Runtime classpaths of io.github.shishark:faceblur (generated, for OSV-Scanner)\n" +
                lines.joinToString("\n", postfix = "\n"),
        )
    }
}

object RuntimeModules {
    /** group:name:version of every external module reachable from [root]. */
    fun collect(root: org.gradle.api.artifacts.result.ResolvedComponentResult): List<String> {
        val seen = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
        val out = mutableListOf<String>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (!seen.add(c)) continue
            val id = c.id
            if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                out += "${id.group}:${id.module}:${id.version}"
            }
            c.dependencies
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .forEach { queue.add(it.selected) }
        }
        return out
    }
}

val runtimeLockfileConfigurations =
    listOf(
        "androidRuntimeClasspath",
        "jvmRuntimeClasspath",
        "iosArm64CompileKlibraries",
        "iosSimulatorArm64CompileKlibraries",
    )

tasks.register<WriteRuntimeLockfile>("writeRuntimeLockfile") {
    group = "verification"
    description = "Writes the library's runtime dependencies in gradle.lockfile format for the OSV gate."
    runtimeLockfileConfigurations.forEach { name ->
        entries.addAll(
            configurations.named(name).flatMap { conf ->
                conf.incoming.resolutionResult.rootComponent.map { root ->
                    RuntimeModules.collect(root).map { "$it=$name" }
                }
            },
        )
    }
    lockfile.set(layout.buildDirectory.file("osv/runtime.lockfile"))
}

// Test classpaths get Guava >= 33.7.2 (Robolectric 4.17 pulls 33.6.0-jre, which has a HIGH advisory).
// A constraint adds nothing by itself; it only raises the version where a test classpath already has Guava.
dependencies {
    listOf("jvmTestImplementation", "androidHostTestImplementation").forEach { configuration ->
        constraints.add(configuration, "com.google.guava:guava:${libs.versions.guava.test.floor.get()}") {
            because("test-only Guava below 33.7.2 has a HIGH advisory")
        }
    }
}

// Robolectric's Android framework jar (android-all-instrumented) comes from Gradle, not from Robolectric's own
// downloader: Gradle resolves it from this configuration and checks it against gradle/verification-metadata.xml, the
// sync task copies it to build/robolectric-deps/, and testAndroidHostTest runs Robolectric offline from that directory
// only (robolectric.offline + robolectric.dependency.dir). Robolectric then never reads ~/.m2 and never downloads; a
// test for an SDK without a jar here fails ("Unable to locate dependency"). The version must match @Config(sdk).
val robolectricAndroidAll: Configuration by configurations.creating {
    description = "Robolectric android-all jar for the Android host tests (offline mode)."
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    robolectricAndroidAll(libs.robolectric.android.all)
}
val robolectricDepsDir = layout.buildDirectory.dir("robolectric-deps")
val hostTestTmpDir = layout.buildDirectory.dir("tmp/host-test-jvm-tmp")

/** `-Djava.io.tmpdir=<dir>` for a test JVM; the directory is local state, not an input (relocatable cache entries). */
class JvmTmpDir(@get:LocalState val dir: File) : CommandLineArgumentProvider {
    override fun asArguments(): List<String> = listOf("-Djava.io.tmpdir=${dir.absolutePath}")
}
val robolectricDeps = tasks.register<Sync>("robolectricDeps") {
    description = "Copies the Gradle-verified Robolectric android-all jar to build/robolectric-deps/."
    from(robolectricAndroidAll)
    into(robolectricDepsDir)
}
tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    inputs
        .files(robolectricDeps)
        .withPropertyName("robolectricAndroidAll")
        .withPathSensitivity(PathSensitivity.NAME_ONLY)
    systemProperty("robolectric.offline", "true")
    // AarConsumerRulesTest opens the built AAR and checks the consumer R8 rules in it (content only; relative path
    // like the other fixtures so that build-cache entries stay relocatable).
    val aarFile = tasks.named<Zip>("bundleAndroidMainAar").flatMap { it.archiveFile }
    inputs.file(aarFile).withPropertyName("faceblurAar").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("faceblur.aar", aarFile.get().asFile.relativeTo(projectDir).invariantSeparatorsPath)
    // Relative to the test's working directory (the project directory), so build-cache entries stay relocatable.
    systemProperty(
        "robolectric.dependency.dir",
        robolectricDepsDir.get().asFile.relativeTo(projectDir).invariantSeparatorsPath,
    )
    // A temporary directory of the host-test JVM's own (not the system's, which other processes share), emptied before
    // each run: NoCopyLeftBehindTest checks that the library writes nothing there (PRIV-005).
    val ownTmp = hostTestTmpDir
    jvmArgumentProviders.add(JvmTmpDir(ownTmp.get().asFile))
    doFirst {
        val dir = ownTmp.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
    }
}

// The Java snippet of the Android guide (docs/guides/snippets/android/JavaAsync.java) as a Java test source of the
// Android host tests: the very file the guide shows is compiled here against the library classes and the Android
// platform jar (-Xlint:all -Werror), and the host test JavaAsyncCancellationTest runs it. The Kotlin host tests see
// the classes through a jar dependency (a plain class directory is not seen by the Kotlin compilation). The classpath
// is the MAIN compile classpath (the host test classpath contains this task's output, which would be a cycle).
val androidComponents =
    extensions.getByType<com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension>()
val guideSnippetsJavaVersion = JavaLanguageVersion.of(libs.versions.jvm.target.get().toInt())
val compileGuideJavaSnippets = tasks.register<JavaCompile>("compileGuideJavaSnippets") {
    description = "Compiles the Java files of docs/guides/snippets/android/ for the Android host tests."
    source(fileTree(rootProject.layout.projectDirectory.dir("docs/guides/snippets/android")) { include("*.java") })
    classpath = files(
        tasks.named("bundleAndroidMainClassesToCompileJar"),
        configurations.named("androidCompileClasspath"),
        androidComponents.sdkComponents.bootClasspath,
    )
    destinationDirectory.set(layout.buildDirectory.dir("guide-snippet-classes"))
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(guideSnippetsJavaVersion) })
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
val guideJavaSnippetsJar = tasks.register<Jar>("guideJavaSnippetsJar") {
    description = "Packs the classes of compileGuideJavaSnippets for the Android host test classpath."
    from(compileGuideJavaSnippets.flatMap { it.destinationDirectory })
    destinationDirectory.set(layout.buildDirectory.dir("guide-snippet-jar"))
    archiveBaseName.set("guide-java-snippets")
}
dependencies {
    add("androidHostTestCompilationImplementation", files(guideJavaSnippetsJar))
}

// The independent pixel oracle (verification/pixel-oracle, ST-045) as a JAR on the Android host-test classpath ONLY, so
// the host tests can ask it for a masked verdict on real Android-encoded output. The verification build stays a
// separate build (ADR 0001 decision 3: no includeBuild, no project dependency): this task runs ITS wrapper-driven
// `:pixel-oracle:jar` and the host tests receive the file. The jar holds no library code and the library holds none of
// it: it is on no main, published or runtime-dependency configuration (scripts/check-runtime-deps.py reads only
// those), and it is a file dependency of one test compilation exactly like guideJavaSnippetsJar.
val pixelOracleJar = rootProject.layout.projectDirectory.file("verification/pixel-oracle/build/libs/pixel-oracle.jar")
val buildPixelOracleJar = tasks.register<Exec>("buildPixelOracleJar") {
    description = "Builds verification/pixel-oracle/build/libs/pixel-oracle.jar with the verification build."
    val verification = rootProject.layout.projectDirectory.dir("verification")
    inputs.dir(verification.dir("pixel-oracle/src/main")).withPropertyName("oracleSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        verification.file("pixel-oracle/build.gradle.kts"),
        verification.file("build.gradle.kts"),
        verification.file("settings.gradle.kts"),
        verification.file("gradle.properties"),
        verification.file("gradle/libs.versions.toml"),
        verification.file("gradle/verification-metadata.xml"),
    ).withPropertyName("oracleBuildFiles").withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(pixelOracleJar)
    workingDir = rootProject.projectDir
    val offline = gradle.startParameter.isOffline
    commandLine(
        listOf("./gradlew", "-p", "verification", ":pixel-oracle:jar", "--console=plain", "-q") +
            if (offline) listOf("--offline") else emptyList(),
    )
}
dependencies {
    add(
        "androidHostTestCompilationImplementation",
        files(pixelOracleJar) { builtBy(buildPixelOracleJar) },
    )
}

// No ONNX Runtime telemetry from JVM and Android host test runs (ORT reads ORT_DISABLE_TELEMETRY).
tasks.withType<Test>().configureEach {
    environment("ORT_DISABLE_TELEMETRY", "1")
}

// The JVM file-mode tests write their real-encoder JPEG outputs and manifest.tsv here for the independent privacy
// checker (scripts/crosscheck-file-mode.sh, CI). This is the only definition of the path: the test gets it as the
// system property faceblur.crosscheckDir. The directory is emptied before each run (no stale files) and declared as a
// test output, so a cached test run restores exactly the files that run wrote.
tasks.named<Test>("jvmTest") {
    val crosscheckDir = layout.buildDirectory.dir("file-mode-crosscheck")
    outputs.dir(crosscheckDir).withPropertyName("fileModeCrosscheckOutputs")
    // Relative to the test's working directory (the project directory), so build-cache entries stay relocatable.
    systemProperty("faceblur.crosscheckDir", crosscheckDir.get().asFile.relativeTo(projectDir).invariantSeparatorsPath)
    doFirst { crosscheckDir.get().asFile.deleteRecursively() }
}

// Mask-stage tests that need their own heap (ADR 0013): the 25 MP worst case with a 2 GiB heap, and the real-heap
// parallel-calls check with a small heap sized so that one 25 MP working area fits after the test's images and two do
// not (the test asserts that range first). Both run in their own JVM, one fork each, from the jvmTest classes; jvmTest
// excludes them and is finalized by them, so every `jvmTest` run (also in CI) runs them too. Locally they can be
// skipped with -Pfaceblur.largeHeapTests=false; under CI (environment variable CI set and not "false", as on GitHub
// Actions) the build refuses that switch, and CI also checks that both tasks wrote test results.
val largeHeapPackages = mapOf(
    "jvmLargeHeapTest" to Triple("io.github.shishark.faceblur.largeheap", "2g", "25 MP worst case of the mask stage"),
    "jvmRealHeapTest" to Triple("io.github.shishark.faceblur.realheap", "380m", "remaining-heap check, real heap"),
)
tasks.named<Test>("jvmTest") {
    filter {
        for ((_, spec) in largeHeapPackages) excludeTestsMatching("${spec.first}.*")
    }
}
val largeHeapTasks = largeHeapPackages.map { (taskName, spec) ->
    tasks.register<Test>(taskName) {
        group = "verification"
        description = "Mask-stage tests in their own JVM: ${spec.third} (max heap ${spec.second})."
        val jvmTest = tasks.named<Test>("jvmTest").get()
        testClassesDirs = jvmTest.testClassesDirs
        classpath = jvmTest.classpath
        // The same framework as jvmTest (kotlin-test on JUnit 4), stated instead of relying on Gradle's default.
        useJUnit()
        filter { includeTestsMatching("${spec.first}.*") }
        maxHeapSize = spec.second
        maxParallelForks = 1
    }
}
val skipLargeHeapTests = providers.gradleProperty("faceblur.largeHeapTests").orNull == "false"
val ciEnvironment = providers.environmentVariable("CI").orNull
if (skipLargeHeapTests && !ciEnvironment.isNullOrEmpty() && !ciEnvironment.equals("false", ignoreCase = true)) {
    throw GradleException(
        "faceblur.largeHeapTests=false is a local-only switch: CI must run jvmLargeHeapTest and jvmRealHeapTest.",
    )
}
if (!skipLargeHeapTests) {
    tasks.named("jvmTest") { finalizedBy(largeHeapTasks) }
}

// The local fixtures for the JVM and Android host tests: the tests read the directories from the system properties
// faceblur.testModelsDir and faceblur.localFixturesDir (relative to the test working directory, the project directory,
// so build-cache entries stay relocatable). Both are declared inputs (content only), so the sync and fetch steps and
// their checks run first and a cached test result is reused only for the same fixture bytes.
// Without local fixtures (public builds) the tests named in config/local-fixture-tests.txt are excluded by name, and
// no fixture property is set. An entry is `<test task> <class>` or `<test task> <class>.<method>`.
val localFixtureTests: List<List<String>> =
    providers.fileContents(rootProject.layout.projectDirectory.file("config/local-fixture-tests.txt")).asText.get()
        .lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim().split(Regex("\\s+"), limit = 2) }
tasks.withType<Test>().matching { it.name == "jvmTest" || it.name == "testAndroidHostTest" }.configureEach {
    val taskName = name
    if (fetchYunetModel != null && syncLocalFixtures != null) {
        inputs
            .file(fetchYunetModel.flatMap { it.outputFile })
            .withPropertyName("testModelFixture")
            .withPathSensitivity(PathSensitivity.NONE)
        inputs
            .dir(syncLocalFixtures.flatMap { it.outputDirectory })
            .withPropertyName("localFixtures")
            .withPathSensitivity(PathSensitivity.NONE)
        systemProperty(
            "faceblur.testModelsDir",
            testModelsDir.get().asFile.relativeTo(projectDir).invariantSeparatorsPath,
        )
        systemProperty(
            "faceblur.localFixturesDir",
            localFixturesBuildDir.get().asFile.relativeTo(projectDir).invariantSeparatorsPath,
        )
    } else {
        filter {
            for ((task, pattern) in localFixtureTests) if (task == taskName) excludeTestsMatching(pattern)
        }
    }
}
// The Kotlin/Native simulator tests look for build/local-fixtures/ next to the repository's testdata/: present only
// after syncLocalFixtures; a public build removes a stale copy first, so it can never see local fixtures.
tasks.matching { it.name == "iosSimulatorArm64Test" }.configureEach {
    if (syncLocalFixtures != null) {
        dependsOn(syncLocalFixtures)
    } else {
        val stale = localFixturesBuildDir.get().asFile
        doFirst { stale.deleteRecursively() }
    }
}

// Test-model tool (scripts/test-models/TestModelTool.java): plain Java on the JVM test classpath, so it uses the one
// test-source ONNX writer and the library's own pre-parse walker. Build tooling only; it never reaches an artifact.
val jvmTestCompilation = kotlin.jvm().compilations.getByName("test")
val testModelToolClasspath: FileCollection =
    files(jvmTestCompilation.output.allOutputs, jvmTestCompilation.runtimeDependencyFiles)
val jvmTargetVersion = JavaLanguageVersion.of(libs.versions.jvm.target.get().toInt())

val compileTestModelTool = tasks.register<JavaCompile>("compileTestModelTool") {
    description = "Compiles scripts/test-models/TestModelTool.java against the JVM test classes."
    source(rootProject.layout.projectDirectory.dir("scripts/test-models"))
    classpath = testModelToolClasspath
    destinationDirectory.set(layout.buildDirectory.dir("test-model-tool/classes"))
    javaCompiler.set(javaToolchains.compilerFor { languageVersion.set(jvmTargetVersion) })
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:all")
    options.compilerArgs.add("-Werror")
}

val derivedModelVariants = listOf("G0", "G1", "G2", "G3")

// N8/N9 of the model-leak controls: the derived models are rebuilt in every run (never cached, never up to date)
// and compared with their pins; the source is re-checked against its pin by the tool itself.
val deriveTestModels: TaskProvider<JavaExec>? =
    if (fetchYunetModel == null) {
        null
    } else {
        tasks.register<JavaExec>("deriveTestModels") {
            group = "verification"
            description =
                "Rebuilds the derived test models G0/G1/G2/G3 into build/test-models/derived/ and checks their pins."
            val source = fetchYunetModel.flatMap { it.outputFile }
            val outDir = testModelsDir.map { it.dir("derived") }
            inputs.file(source).withPropertyName("referenceFixture").withPathSensitivity(PathSensitivity.NONE)
            outputs.dir(outDir).withPropertyName("derivedModels")
            classpath(compileTestModelTool.flatMap { it.destinationDirectory }, testModelToolClasspath)
            mainClass.set("TestModelTool")
            javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(jvmTargetVersion) })
            val pins = derivedModelVariants.map { v ->
                "$v=${yunetPinValue("derived.$v.sha256")}:${yunetPinValue("derived.$v.size")}"
            }
            val sha = yunetPinValue("sha256")
            val size = yunetPinValue("size")
            // Only for the negative control N8 (scripts/test-model-step.sh): a deliberately wrong builder must fail its pins.
            val mutant = providers.gradleProperty("faceblur.testModels.builderMutant")
            argumentProviders.add(
                CommandLineArgumentProvider {
                    listOf("derive", "--source", source.get().asFile.path, "--sha256", sha, "--size", size) +
                        listOf("--out", outDir.get().asFile.path) + pins.flatMap { listOf("--pin", it) } +
                        (mutant.orNull?.let { listOf("--mutant", it) } ?: emptyList())
                },
            )
            // Never up to date and never cached: the pins are checked in every run.
            outputs.upToDateWhen { false }
            outputs.cacheIf { false }
        }
    }

// Crash-freedom fuzzing of the ONNX pre-parse walker (fuzz workflow: on walker changes and by hand; a fixed and a
// logged random seed).
//   ./gradlew :faceblur:fuzzOnnxPreParse -Pfaceblur.fuzz.seed=<number|random> -Pfaceblur.fuzz.seconds=<n>
tasks.register<JavaExec>("fuzzOnnxPreParse") {
    group = "verification"
    description = "Fuzzes the ONNX pre-parse walker on mutations of the synthetic test model (and of the local " +
        "fixtures, when set); prints the seed first."
    val source = fetchYunetModel?.flatMap { it.outputFile }
    val derivedDir = deriveTestModels?.flatMap { it.outputs.files.elements }?.map { it.single() }
    val variants = derivedModelVariants
    if (source != null && derivedDir != null) {
        inputs.file(source).withPropertyName("referenceFixture").withPathSensitivity(PathSensitivity.NONE)
        inputs.dir(derivedDir).withPropertyName("derivedModels").withPathSensitivity(PathSensitivity.NONE)
    }
    classpath(compileTestModelTool.flatMap { it.destinationDirectory }, testModelToolClasspath)
    mainClass.set("TestModelTool")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(jvmTargetVersion) })
    maxHeapSize = "1g"
    val seed = providers.gradleProperty("faceblur.fuzz.seed").orElse("random")
    val seconds = providers.gradleProperty("faceblur.fuzz.seconds").orElse("60")
    val maxRounds = providers.gradleProperty("faceblur.fuzz.maxRounds")
    val startRound = providers.gradleProperty("faceblur.fuzz.startRound")
    // Only for the negative control (scripts/test-model-step.sh): a planted crash must be reported with the seed.
    val plant = providers.gradleProperty("faceblur.fuzz.plantCrashRound")
    // The local reference fixture and its derived models; without local fixtures only the synthetic model is fuzzed.
    val fixtureFiles = {
        if (source != null && derivedDir != null) {
            listOf(source.get().asFile.path) + variants.map { File(derivedDir.get().asFile, "$it.onnx").path }
        } else {
            emptyList()
        }
    }
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf("fuzz", "--seed", seed.get(), "--seconds", seconds.get()) +
                (maxRounds.orNull?.let { listOf("--max-rounds", it) } ?: emptyList()) +
                (startRound.orNull?.let { listOf("--start-round", it) } ?: emptyList()) +
                (plant.orNull?.let { listOf("--plant-crash-round", it) } ?: emptyList()) +
                fixtureFiles()
        },
    )
    outputs.upToDateWhen { false }
}

// faceblur-eval (src/jvmTest/.../eval/README.md): measures a model and profile on annotated photos. Test-source tool,
// never published; not part of `check` and not run by CI except the public-fixture smoke (scripts/eval-smoke.sh).
//   ./gradlew :faceblur:faceblurEval --args="--model m.onnx --profile p.json --images dir --truth gt.json"
// Relative paths in --args resolve against the directory Gradle is started from. Heap: -Pfaceblur.eval.heap=8g.
tasks.register<JavaExec>("faceblurEval") {
    group = "verification"
    description = "Runs faceblur-eval (not published): measures a model and profile on annotated photos."
    classpath(testModelToolClasspath)
    mainClass.set("io.github.shishark.faceblur.eval.FaceblurEval")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(jvmTargetVersion) })
    // No ONNX Runtime telemetry (PRIV-015): ORT reads the variable at start-up; the tool refuses to start without it.
    environment("ORT_DISABLE_TELEMETRY", "1")
    workingDir = gradle.startParameter.currentDir
    maxHeapSize = providers.gradleProperty("faceblur.eval.heap").getOrElse("4g")
    // `:faceblur:fetchYunetModel :faceblur:faceblurEval` evaluates the pinned reference model (scripts/eval-smoke.sh).
    if (fetchYunetModel != null) mustRunAfter(fetchYunetModel)
    outputs.upToDateWhen { false }
}

dokka {
    moduleName.set("FaceBlur")
}

ktlint {
    version.set(libs.versions.ktlint.engine.get())
    // Report paths relative to the project: reports are uploaded from CI (SEC-006).
    relative.set(true)
}

// Android lint, same settings for the Android target (kotlin.android.lint) and the JVM target
// (project-level `lint`, from com.android.lint). Warnings are errors; no baseline.
fun com.android.build.api.dsl.Lint.faceblurLintSettings() {
    warningsAsErrors = true
    abortOnError = true
    checkDependencies = false
    // Reports are uploaded from CI: no absolute build-machine paths in them (SEC-006).
    absolutePaths = false
    // Version-update checks query the network for newer releases: they make the gate depend on the day the
    // build runs, and every pin here is a reviewed decision (ORT 1.31.0 changes need the owner's approval).
    // Updates are tracked by devops in the version catalog review instead.
    disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
}

kotlin.android {
    lint { faceblurLintSettings() }
}

lint { faceblurLintSettings() }

// Line coverage gates (Definition of Done: >= 80 %): this whole-module bound, plus the changed-lines gate
// scripts/diff-coverage.py over the Kover XML report (Kover itself has no "changed code only" mode; CI runs both).
kover {
    reports {
        verify {
            rule {
                minBound(80)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Publishing: com.vanniktech.maven.publish -> Central Portal.
//
// SIGNING: Maven Central rejects unsigned artifacts. Signing is switched ON automatically when
// signing properties are present (never stored in this repository):
//   in-memory key : signingInMemoryKey, signingInMemoryKeyId, signingInMemoryKeyPassword
//                   (gradle properties, or ORG_GRADLE_PROJECT_<name> environment variables)
//   key ring file : signing.keyId, signing.password, signing.secretKeyRingFile
// Without them, publishToMavenLocal produces UNSIGNED artifacts (local smoke tests only) and every
// Maven Central task fails fast before uploading anything (see the guard below).
// Central credentials: mavenCentralUsername / mavenCentralPassword (Central Portal user token).
// ---------------------------------------------------------------------------------------------
// Project group = Maven group; it also forms the klib unique name (io.github.shishark:faceblur).
group = "io.github.shishark"

val signingConfigured: Boolean =
    providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent

mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = SourcesJar.Sources(),
        ),
    )
    publishToMavenCentral(automaticRelease = false)
    if (signingConfigured) {
        signAllPublications()
    }

    // The version comes from VERSION_NAME in gradle.properties (read by the plugin itself).
    coordinates(groupId = "io.github.shishark", artifactId = "faceblur")

    pom {
        name.set("FaceBlur")
        description.set(
            "Kotlin Multiplatform library for Android and iOS that detects faces in still photos on the device " +
                "and covers them with opaque masks, re-encoding the image without metadata.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/shiSHARK/faceblur")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("shiSHARK")
                name.set("shiSHARK")
                url.set("https://github.com/shiSHARK")
            }
        }
        scm {
            url.set("https://github.com/shiSHARK/faceblur")
            connection.set("scm:git:https://github.com/shiSHARK/faceblur.git")
            developerConnection.set("scm:git:ssh://git@github.com/shiSHARK/faceblur.git")
        }
        issueManagement {
            system.set("GitHub Issues")
            url.set("https://github.com/shiSHARK/faceblur/issues")
        }
    }
}

// Repository-local Maven directory (ADR 0001): the verification build and the release-candidate samples
// resolve the library only from here. `./gradlew publishAllPublicationsToLocalRepoRepository`
publishing {
    repositories {
        maven {
            name = "localRepo"
            url = uri(rootProject.layout.buildDirectory.dir("local-maven"))
        }
    }
}

// Refuse any Maven Central task when signing is not configured (fails before any upload).
tasks.matching { "MavenCentral" in it.name }.configureEach {
    val signed = signingConfigured
    doFirst {
        check(signed) {
            "Maven Central requires signed artifacts. Provide signingInMemoryKey (+ KeyId/KeyPassword) or " +
                "signing.keyId/signing.password/signing.secretKeyRingFile via ~/.gradle/gradle.properties or " +
                "ORG_GRADLE_PROJECT_* environment variables. See docs/ops/RELEASING.md."
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Notices (ADR 0008). LICENSE, NOTICE and the third-party licence texts of licenses/ travel inside the artifacts under
// META-INF/io.github.shishark.faceblur/ (a sub-folder, because AGP drops META-INF/LICENSE and META-INF/NOTICE at the
// top level): in the AAR (as Java resources of classes.jar), and in the sources and javadoc jars of every published
// target. licenses/INDEX.txt names the texts each artifact kind (aar, klib) carries; scripts/check-licence-notices.py
// checks the index against the resolved runtime dependencies and the published artifacts.
// The XCFramework zip is staged by scripts/package-xcframework-zip.sh.

// Privacy manifest of the XCFramework: Apple asks for one inside a binary framework that is distributed through
// SwiftPM. framework/PrivacyInfo.xcprivacy is copied to the root of every framework slice; the faceblur-swift
// facade ships the same file as a package resource, and its merge gate compares the two.
tasks.withType<XCFrameworkTask>().configureEach {
    val manifest = layout.projectDirectory.file("framework/PrivacyInfo.xcprivacy")
    inputs.file(manifest).withPropertyName("privacyManifest").withPathSensitivity(PathSensitivity.NONE)
    val outputFolders = outputs.files
    doLast {
        val slices = outputFolders.files.flatMap { dir ->
            dir.walkTopDown().filter { it.isDirectory && it.extension == "framework" }.toList()
        }
        check(slices.isNotEmpty()) { "no framework slice found in the XCFramework output" }
        slices.forEach { slice -> manifest.asFile.copyTo(slice.resolve("PrivacyInfo.xcprivacy"), overwrite = true) }
    }
}
// ---------------------------------------------------------------------------------------------
abstract class StageNotices : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenseFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val noticeFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licensesDir: DirectoryProperty

    /** The artifact kind whose rows of licenses/INDEX.txt are staged (`aar` or `klib`). */
    @get:Input
    abstract val artifact: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val target = root.resolve("META-INF/io.github.shishark.faceblur")
        val texts = target.resolve("licenses")
        texts.mkdirs()
        val index = licensesDir.get().file("INDEX.txt").asFile
        val names = sortedSetOf<String>()
        index.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
            val columns = line.split('\t')
            check(columns.size == 6) { "licenses/INDEX.txt: expected 6 tab-separated columns: $line" }
            if (artifact.get() in columns[5].split(';')) names += columns[3].split(';')
        }
        check(names.isNotEmpty()) { "licenses/INDEX.txt lists no text for the artifact kind '${artifact.get()}'" }
        licenseFile.get().asFile.copyTo(target.resolve("LICENSE"))
        noticeFile.get().asFile.copyTo(target.resolve("NOTICE"))
        index.copyTo(texts.resolve("INDEX.txt"))
        names.forEach { name ->
            val source = licensesDir.get().file(name).asFile
            check(source.isFile) { "licenses/INDEX.txt names $name, which is not in licenses/" }
            source.copyTo(texts.resolve(name))
        }
    }
}

fun registerStageNotices(taskName: String, kind: String) = tasks.register<StageNotices>(taskName) {
    description = "Stages LICENSE, NOTICE and the licence texts for the '$kind' artifacts (ADR 0008)."
    licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
    noticeFile.set(rootProject.layout.projectDirectory.file("NOTICE"))
    licensesDir.set(rootProject.layout.projectDirectory.dir("licenses"))
    artifact.set(kind)
    outputDir.set(layout.buildDirectory.dir("notices/$kind"))
}

val stageNoticesAar = registerStageNotices("stageNoticesAar", "aar")
val stageNoticesKlib = registerStageNotices("stageNoticesKlib", "klib")

kotlin.sourceSets.androidMain {
    resources.srcDir(stageNoticesAar.flatMap { it.outputDir })
}

// The sources, javadoc and metadata jars of the published targets. AbstractArchiveTask, not Jar: the Kotlin and
// Android Gradle plugins register org.gradle.jvm.tasks.Jar tasks that `withType<Jar>` (the bundling class) misses.
tasks.withType<AbstractArchiveTask>().configureEach {
    val isSourcesOrJavadoc = name == "sourcesJar" || name.endsWith("SourcesJar") || name.endsWith("DokkaJavadocJar")
    val isAndroid = name.startsWith("android")
    val isJvm = name.startsWith("jvm") // the jvm variant is not published (ADR 0002)
    if (!isJvm && isSourcesOrJavadoc && isAndroid) {
        from(stageNoticesAar.flatMap { it.outputDir })
    } else if (!isJvm && (isSourcesOrJavadoc || name == "allMetadataJar")) {
        from(stageNoticesKlib.flatMap { it.outputDir })
    }
}

// ---------------------------------------------------------------------------------------------
// jvm not published (ADR 0002 decision 11). Kotlin has no switch to exclude one target from a KMP
// publication, so: (1) the jvm publication's publish tasks are skipped, and (2) the root module metadata
// (faceblur-<version>.module) is rewritten right after generation, before signing and checksums, without the
// jvm variants. The verification step `sh scripts/check-publication.sh build/local-maven` proves both.
// ---------------------------------------------------------------------------------------------
tasks.withType<AbstractPublishToMaven>().configureEach {
    val isJvm = publication?.name == "jvm"
    onlyIf("the jvm variant is not published in 0.x (ADR 0002)") { !isJvm }
}

tasks.withType<GenerateModuleMetadata>().configureEach {
    if (name == "generateMetadataFileForKotlinMultiplatformPublication") {
        doLast {
            val file = outputFile.get().asFile

            @Suppress("UNCHECKED_CAST")
            val module = groovy.json.JsonSlurper().parse(file) as MutableMap<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val variants = module["variants"] as List<Map<String, Any?>>
            val kept =
                variants.filterNot { variant ->
                    val attributes = variant["attributes"] as? Map<*, *>
                    attributes?.get("org.jetbrains.kotlin.platform.type") == "jvm"
                }
            check(kept.size < variants.size) { "no jvm variant found in the root module metadata" }
            module["variants"] = kept
            file.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(module)) + "\n")
        }
    }
}
