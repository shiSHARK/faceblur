// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.Detection
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.PlanMasker
import io.github.shishark.faceblur.PlatformTargetValue
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.filemode.BoundedRead
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.ImageCodec
import io.github.shishark.faceblur.filemode.ImageFingerprint
import io.github.shishark.faceblur.filemode.OpenedImage
import io.github.shishark.faceblur.filemode.PlatformCallValue
import io.github.shishark.faceblur.filemode.PlatformSourceValue
import io.github.shishark.faceblur.filemode.StreamReader
import io.github.shishark.faceblur.filemode.StreamSink
import io.github.shishark.faceblur.filemode.TargetWriter
import io.github.shishark.faceblur.filemode.WorkingImage
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.model.ModelLoader
import io.github.shishark.faceblur.internal.model.Sha256
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.ModelDigest
import io.github.shishark.faceblur.internal.runtime.ModelFileReader
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.ReservedBytes
import io.github.shishark.faceblur.mask.StageAllocator
import io.github.shishark.faceblur.mask.StageObserver
import io.github.shishark.faceblur.mask.StageSeams
import io.github.shishark.faceblur.testmodel.ANY_SHA256
import io.github.shishark.faceblur.testmodel.Onnx
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** JVM-only checks of the ST-002 review fixes: bytecode visibility, fatal errors, telemetry guard, two faces. */
class JvmBoundaryTest {
    /**
     * Parameter types through which Java code could supply model bytes, a runtime, or a function that decides what
     * the runtime receives.
     */
    private val dangerous =
        setOf(
            Function0::class.java,
            Function1::class.java,
            ModelRuntime::class.java,
            ModelFileReader::class.java,
        )

    /**
     * Public, non-synthetic constructors and methods of [classes] that take a [dangerous] type, or that return a
     * `byte[]` (the verified model bytes must not be reachable for writing).
     */
    private fun javaCallableSeams(classes: List<Class<*>>): List<String> = classes.flatMap { c ->
        (c.declaredConstructors.toList<Executable>() + c.declaredMethods)
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .filter { m ->
                m.parameterTypes.any { it in dangerous } ||
                    (m is java.lang.reflect.Method && m.returnType == ByteArray::class.java)
            }
            .map { "${c.simpleName}.${it.name}" }
    }

    private val libraryClasses =
        listOf(
            FaceMasker::class.java,
            Class.forName("io.github.shishark.faceblur.FaceMasker\$Companion"),
            FaceBlurRuntime::class.java,
            ModelSource::class.java,
            Class.forName("io.github.shishark.faceblur.ModelSource\$Companion"),
            DetectorModel::class.java,
            Class.forName("io.github.shishark.faceblur.DetectorModel\$Companion"),
        )

    /** No public, non-synthetic member can feed model bytes or a runtime into the library. */
    @Test
    fun javaCannotSupplyModelBytesOrARuntime() {
        assertEquals(emptyList(), javaCallableSeams(libraryClasses))
        for (name in listOf(
            "install",
            "runtime",
            "uninstall",
            "installModelFiles",
            "modelFiles",
            "installDigest",
            "digest",
            "installMemoryProbe",
            "memoryProbe",
            "installImageCodec",
            "imageCodec",
            "installTargetWriter",
            "targetWriter",
            "overrideDefaults",
        )) {
            val m = FaceBlurRuntime::class.java.declaredMethods.single { it.name == name }
            assertTrue(m.isSynthetic, "FaceBlurRuntime.$name is synthetic")
        }
        // The mask stage's test seam on FaceMasker is synthetic as well.
        val seam = io.github.shishark.faceblur.FaceMasker::class.java.declaredMethods
            .single { it.name.startsWith("useStageSeamsForTest") }
        assertTrue(seam.isSynthetic, "FaceMasker.useStageSeamsForTest is synthetic")
        // The model's internal seams exist and are synthetic (not merely renamed).
        for (prefix in listOf("verifiedBytes", "platformForm")) {
            val m = DetectorModel::class.java.declaredMethods.single { it.name.startsWith(prefix) }
            assertTrue(m.isSynthetic, "DetectorModel.$prefix is synthetic")
        }
        // Negative controls: classes with such public methods are reported.
        assertEquals(
            listOf("Planted.bytes", "Planted.form", "Planted.load"),
            javaCallableSeams(listOf(Planted::class.java)).sorted(),
        )
    }

    /**
     * Internal types through which Java code could reach, call or replace a platform binding or a part of the
     * file-mode seam (ADR 0015 decisions 1, 2 and 4): the platform defaults, the target and source values, the
     * per-call context, the codec, writer and image types, the memory probe and the digest.
     */
    private val seamTypes: Set<Class<*>> =
        setOf(
            PlatformDefaults::class.java,
            PlatformTargetValue::class.java,
            PlatformSourceValue::class.java,
            PlatformCallValue::class.java,
            CallContext::class.java,
            FileSource::class.java,
            FileSource.Path::class.java,
            FileSource.Bytes::class.java,
            FileSource.Platform::class.java,
            ImageCodec::class.java,
            OpenedImage::class.java,
            WorkingImage::class.java,
            TargetWriter::class.java,
            StreamReader::class.java,
            StreamSink::class.java,
            BoundedRead.Observer::class.java,
            MemoryProbe::class.java,
            ModelDigest::class.java,
            ModelRuntime::class.java,
            ModelFileReader::class.java,
            StageSeams::class.java,
            StageAllocator::class.java,
            StageObserver::class.java,
            ImageFingerprint::class.java,
        )

    /**
     * Public, non-synthetic constructors, methods and fields of [classes] that take or return a [seamTypes] type
     * (also as the type of a field). The seam types' own declarations are not scanned: Java may implement an
     * interface, but no Java-callable member may accept or hand out an implementation.
     */
    private fun javaCallableSeamUses(classes: List<Class<*>>): List<String> = classes.flatMap { c ->
        val executables = (c.declaredConstructors.toList<Executable>() + c.declaredMethods)
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .filter { m ->
                m.parameterTypes.any { it in seamTypes } ||
                    (m is java.lang.reflect.Method && m.returnType in seamTypes)
            }
            .map { "${c.name.substringAfterLast('.')}.${if (it is Constructor<*>) "<init>" else it.name}" }
        val fields = c.declaredFields
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.type in seamTypes }
            .map { "${c.name.substringAfterLast('.')}.${it.name}" }
        executables + fields
    }

    /** Every class of the library's JVM main output (not the tests), loaded without initialising it. */
    private fun allLibraryClasses(): List<Class<*>> {
        val root = Paths.get(FaceMasker::class.java.protectionDomain.codeSource.location.toURI())
        assertTrue(Files.isDirectory(root), "the library classes are a directory: $root")
        val loader = FaceMasker::class.java.classLoader
        return Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".class") }
                .map { root.relativize(it).toString().removeSuffix(".class").replace('/', '.').replace('\\', '.') }
                .sorted()
                .map { Class.forName(it, false, loader) }
                .toList()
        }
    }

    /**
     * ADR 0015 Verification: Java code can neither call `platformDefaults()` or the test switch, nor make or read a
     * `PlatformDefaults`, nor reach any part of the seam through a Java-callable member of the library.
     */
    @Test
    fun javaCannotReachThePlatformDefaultsOrTheFileModeSeam() {
        val defaultsFile = Class.forName("io.github.shishark.faceblur.internal.runtime.PlatformDefaults_jvmKt")
        val entry = defaultsFile.declaredMethods.single { it.name == "platformDefaults" }
        assertTrue(entry.isSynthetic, "platformDefaults() is synthetic")
        val overrideSwitch = FaceBlurRuntime::class.java.declaredMethods.single { it.name == "overrideDefaults" }
        assertTrue(overrideSwitch.isSynthetic, "the test switch is synthetic")
        // PlatformDefaults: no Java-callable constructor or member at all (the constructor is private, the factory,
        // the getters and NONE are synthetic).
        val defaultsClasses = listOf(PlatformDefaults::class.java, PlatformDefaults.Companion::class.java)
        val callable = defaultsClasses.flatMap { c ->
            (c.declaredConstructors.toList<Executable>() + c.declaredMethods)
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .filter { it.name !in setOf("equals", "hashCode", "toString") }
                .map { "${c.simpleName}.${it.name}" }
        }
        assertEquals(emptyList(), callable)
        val all = allLibraryClasses()
        assertTrue(defaultsFile in all && PlatformDefaults::class.java in all, "the scan covers the defaults")
        val scanned = all.filter { c -> seamTypes.none { it == c || c.enclosingClass == it } }
        assertEquals(emptyList(), javaCallableSeamUses(scanned))
        // Negative control: a class with such public members is reported.
        assertEquals(
            listOf(
                "JvmBoundaryTest${'$'}PlantedSeam.<init>",
                "JvmBoundaryTest${'$'}PlantedSeam.codec",
                "JvmBoundaryTest${'$'}PlantedSeam.defaults",
                "JvmBoundaryTest${'$'}PlantedSeam.probe",
                "JvmBoundaryTest${'$'}PlantedSeam.write",
            ),
            javaCallableSeamUses(listOf(PlantedSeam::class.java)).sorted(),
        )
    }

    /**
     * ADR 0015 decision 6 and Verification: no Java-callable way to read, set, compare, store or restore the plan
     * binding. The type is scanned with the other seam types above; here: the reflection checks of the two classes
     * that hold it, no public member that hands out bytes or text the digest could be copied into, and two planted
     * negative controls.
     */
    @Test
    fun javaCannotReachTheImageFingerprint() {
        val all = allLibraryClasses()
        assertTrue(ImageFingerprint::class.java in all && MaskPlan::class.java in all && Detection::class.java in all)
        val publicConstructors = { c: Class<*> ->
            c.declaredConstructors.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .map { it.parameterTypes.toList() }
        }
        assertEquals(
            listOf(listOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, List::class.java)),
            publicConstructors(MaskPlan::class.java),
            "the only Java-callable MaskPlan constructor is (int, int, List)",
        )
        assertEquals(emptyList(), publicConstructors(ImageFingerprint::class.java), "no fingerprint constructor")
        // Detection: no Java-callable constructor or member with the type (its internal constructor exists but has
        // no fingerprint parameter), and the accessor, the binding copy and the plan factory are synthetic.
        for (c in listOf(
            Detection::class.java,
            MaskPlan::class.java,
            Class.forName("${MaskPlan::class.java.name}\$Companion"),
        )) {
            val members = c.declaredConstructors.toList<Executable>() + c.declaredMethods
            val withType = members.filter { m ->
                m.parameterTypes.any { it == ImageFingerprint::class.java } ||
                    (m is java.lang.reflect.Method && m.returnType == ImageFingerprint::class.java)
            }
            assertTrue(withType.isNotEmpty(), "the scan sees the members of $c")
            assertEquals(
                emptyList(),
                withType.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }.map { it.toString() },
                "Java-callable members of $c with the fingerprint type",
            )
            assertEquals(
                emptyList(),
                c.declaredFields.filter { it.type == ImageFingerprint::class.java && !Modifier.isPrivate(it.modifiers) }
                    .map { it.name },
                "the fingerprint fields of $c are private",
            )
        }
        for (name in listOf("getFingerprint", "boundTo")) {
            val m = Detection::class.java.declaredMethods.single { it.name.startsWith(name) }
            assertTrue(m.isSynthetic, "Detection.$name is synthetic")
        }
        assertTrue(MaskPlan::class.java.declaredMethods.single { it.name.startsWith("getFingerprint") }.isSynthetic)
        // The fingerprint's own members: no Java-callable method except toString, the factory and matches synthetic,
        // the digest a private field.
        val own = ImageFingerprint::class.java.declaredMethods.filter {
            Modifier.isPublic(it.modifiers) &&
                !it.isSynthetic
        }
        assertEquals(listOf("toString"), own.map { it.name })
        assertTrue(ImageFingerprint::class.java.declaredMethods.single { it.name.startsWith("matches") }.isSynthetic)
        val factory = ImageFingerprint.Companion::class.java.declaredMethods.single { it.name.startsWith("of") }
        assertTrue(factory.isSynthetic, "ImageFingerprint.of is synthetic")
        assertTrue(
            ImageFingerprint::class.java.declaredFields.filter {
                it.type == ByteArray::class.java
            }.all { Modifier.isPrivate(it.modifiers) },
        )
        // No public member of the classes that carry it, or of PlanMasker, hands out a byte[] or a String other than
        // toString (the digest could be copied into one).
        val carriers =
            listOf(Detection::class.java, MaskPlan::class.java, ImageFingerprint::class.java, PlanMasker::class.java)
        val leaks = carriers.flatMap { c ->
            c.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .filter {
                    it.returnType == ByteArray::class.java ||
                        (it.returnType == String::class.java && it.name != "toString")
                }
                .map { "${c.simpleName}.${it.name}" } +
                c.declaredFields.filter {
                    Modifier.isPublic(it.modifiers) &&
                        (it.type == ByteArray::class.java || it.type == String::class.java)
                }
                    .map { "${c.simpleName}.${it.name}" }
        }
        assertEquals(emptyList(), leaks)
        // Negative controls: a public method returning the type and a public constructor taking it are reported.
        assertEquals(
            listOf(
                "JvmBoundaryTest${'$'}PlantedFingerprintCtor.<init>",
                "JvmBoundaryTest${'$'}PlantedFingerprintGetter.leak",
            ),
            javaCallableSeamUses(
                listOf(PlantedFingerprintGetter::class.java, PlantedFingerprintCtor::class.java),
            ).sorted(),
        )
    }

    @Suppress("unused")
    internal class PlantedFingerprintGetter {
        fun leak(plan: MaskPlan): ImageFingerprint? = plan.fingerprint
    }

    @Suppress("unused")
    internal class PlantedFingerprintCtor(print: ImageFingerprint?) {
        val bound = print != null
    }

    /**
     * The mask stage's memory count and test seams (ADR 0013 decision 6): Java code can neither change the reserved
     * count, admit bytes under its own probe, nor make a [StageSeams] or read its probe.
     */
    @Test
    fun javaCannotTouchTheReservedCountOrMakeStageSeams() {
        val reserved = ReservedBytes::class.java.declaredMethods.filterNot { Modifier.isPrivate(it.modifiers) }
        assertTrue(
            reserved.map { it.name }.containsAll(listOf("current", "admit", "release")),
            "the scan sees the members: ${reserved.map { it.name }}",
        )
        assertEquals(emptyList(), reserved.filterNot { it.isSynthetic }.map { it.name }, "non-synthetic members")
        val seams = StageSeams::class.java
        val constructors = seams.declaredConstructors.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        assertEquals(emptyList(), constructors.map { it.toString() }, "Java-callable StageSeams constructors")
        val probe = seams.declaredMethods.single { it.name == "getProbe" }
        assertTrue(probe.isSynthetic, "StageSeams.getProbe is synthetic")
        val factory = StageSeams.Companion::class.java.declaredMethods
            .filter { it.name.startsWith("invoke") && Modifier.isPublic(it.modifiers) }
        assertTrue(factory.isNotEmpty() && factory.all { it.isSynthetic }, "StageSeams factory is synthetic: $factory")
        // Negative control: the same filters report a class with a public constructor and public members.
        val planted = PlantedTarget::class.java
        assertEquals(1, planted.declaredConstructors.count { Modifier.isPublic(it.modifiers) && !it.isSynthetic })
        assertEquals(listOf("getValue"), planted.declaredMethods.filterNot { it.isSynthetic }.map { it.name })
    }

    @Suppress("unused")
    class PlantedTarget(val value: String)

    @Suppress("unused")
    internal class PlantedSeam(target: PlatformTargetValue) {
        @JvmField
        val probe: MemoryProbe? = null

        fun defaults(): PlatformDefaults = PlatformDefaults.NONE

        fun codec(): ImageCodec? = null

        fun write(writer: TargetWriter): Int = writer.hashCode()
    }

    /**
     * `DetectorModel.load` wraps an unexpected failure of its own checks into Internal (the cause is the library's
     * own exception) and lets the JVM's fatal errors pass unchanged.
     */
    @Test
    fun unexpectedFailuresWhileLoadingAreInternalAndFatalErrorsPass() {
        val profile = DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, Sha256.hex(Onnx.yunetModel()))
        val bytes = ModelSource.fromBytes(Onnx.yunetModel())
        val bug = IndexOutOfBoundsException("walker bug")
        val e =
            assertFailsWith<FaceMaskException.Internal> { ModelLoader.load(bytes, profile, 1_000_000L) { throw bug } }
        assertSame(bug, e.cause)
        val oom = OutOfMemoryError("x")
        assertSame(oom, runCatching { ModelLoader.load(bytes, profile, 1_000_000L) { throw oom } }.exceptionOrNull())
        val rejected = FaceMaskException.ModelRejected(listOf("NOT_ONNX"))
        assertSame(
            rejected,
            runCatching { ModelLoader.load(bytes, profile, 1_000_000L) { throw rejected } }.exceptionOrNull(),
        )
        // Control: the real walker loads it.
        assertEquals(640, ModelLoader.load(bytes, profile, 1_000_000L).profile.inputWidth)
    }

    @Suppress("unused")
    class Planted {
        fun load(source: Function0<Any>): Int = source.hashCode()

        fun form(create: Function1<ByteArray, Any>): Int = create.hashCode()

        fun bytes(): ByteArray = ByteArray(1)
    }

    /** The VirtualMachineError family passes unchanged; other Errors are wrapped. */
    @Test
    fun fatalErrorsPropagateUnchangedAndOtherErrorsAreWrapped() {
        for (t in listOf<Throwable>(OutOfMemoryError("x"), StackOverflowError("x"), InternalError("x"))) {
            var failing = false
            val runner =
                object : TensorRunner by OneFaceRunner() {
                    override fun run(input: FloatArray): Map<String, FloatArray> =
                        if (failing) throw t else OneFaceRunner().run(input)
                }
            val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(runner))
            failing = true
            val thrown = runCatching { masker.detect(IntArrayPixels(64, 48)) }.exceptionOrNull()
            assertSame(t, thrown, "${t::class.simpleName} passes unchanged")
            // The guard was released: the next call is not rejected as concurrent use.
            assertSame(t, runCatching { masker.detect(IntArrayPixels(64, 48)) }.exceptionOrNull())
            masker.close()
            assertSame(
                t,
                runCatching {
                    FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), ModelRuntime { _, _ -> throw t })
                }.exceptionOrNull(),
            )
        }
        var failing = false
        val wrapped =
            object : TensorRunner by OneFaceRunner() {
                override fun run(input: FloatArray): Map<String, FloatArray> =
                    if (failing) throw ExceptionInInitializerError("x") else OneFaceRunner().run(input)
            }
        val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(wrapped))
        failing = true
        val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.detect(IntArrayPixels(64, 48)) }
        assertEquals(listOf("RUN_FAILED"), e.findings)
        assertEquals(null, e.cause)
    }

    /** The JVM runtime refuses to start unless telemetry is disabled through the environment. */
    @Test
    fun theOrtRunnerRefusesToStartWithoutTheTelemetryVariable() {
        val model = TestModels.yunet()
        for (value in listOf(null, "0", "true", "")) {
            val runtime = OrtModelRuntime(telemetryVariable = value)
            val e = assertFailsWith<IllegalStateException>("$value") { runtime.open(model, 0) }
            assertTrue(e.message!!.contains("ORT_DISABLE_TELEMETRY"))
            // Through the library the refusal is a typed error (never chained) and no session exists.
            val viaLibrary =
                assertFailsWith<FaceMaskException.RuntimeUnavailable>("$value") {
                    FaceMasker.forTest(model, FaceMaskerConfig(), runtime)
                }
            assertEquals(null, viaLibrary.cause)
            assertEquals(0, runtime.sessionsOpened)
        }
        OrtModelRuntime(telemetryVariable = "1").open(model, 0).close()
        assertEquals("1", System.getenv("ORT_DISABLE_TELEMETRY"), "the Gradle test task sets it")
    }

    /** ST-008 AC1: two faces found by detect-only, the image unchanged, passes = 1 for a 640 px image. */
    @Test
    fun detectOnlyFindsTwoFacesAndLeavesTheImageUnchanged() {
        JvmTestRuntime.install()
        try {
            val img = Fixtures.image("pd/pd-p03-s100-x01141-y01015.png")
            val before = img.data.copyOf()
            val d = FaceMasker(TestModels.yunet()).use { it.detect(img) }
            assertEquals(2, d.faces.size, "two faces")
            assertContentEquals(before, img.data)
            assertEquals(1, d.timings.passes)
            assertTrue(d.timings.detectMs >= 0.0)
        } finally {
            FaceBlurRuntime.uninstall()
        }
    }
}
