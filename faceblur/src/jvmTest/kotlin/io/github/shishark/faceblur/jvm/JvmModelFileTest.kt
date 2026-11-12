// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.Detection
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceBlurInfo
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.MaskResult
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.PixelScaling
import io.github.shishark.faceblur.Timings
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.testmodel.ANY_SHA256
import io.github.shishark.faceblur.testmodel.Onnx
import io.github.shishark.faceblur.testmodel.Proto
import io.github.shishark.faceblur.testmodel.pinnedTo
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** JVM read rules (ADR 0012 decision 4), read-only reports from Java, and runtime refusals on ONNX Runtime 1.31.0. */
class JvmModelFileTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("fb-model-test").toFile()
        FaceBlurRuntime.installModelFiles(JvmModelFileReader)
    }

    @AfterTest
    fun tearDown() {
        JvmModelFileReader.afterReadCall = null
        dir.deleteRecursively()
        FaceBlurRuntime.installModelFiles(null)
    }

    /** Pinned to the generated YuNet-layout model that the readable-file tests load. */
    private val profile = TestModels.yunetProfile().pinnedTo(Onnx.yunetModel())

    private fun write(name: String, bytes: ByteArray) = File(dir, name).also { it.writeBytes(bytes) }

    private fun rejected(path: String, max: Long = DetectorModel.DEFAULT_MAX_MODEL_BYTES) =
        assertFailsWith<FaceMaskException.ModelRejected> {
            DetectorModel.load(ModelSource.fromFile(path), profile, max)
        }.findings

    @Test
    fun aRegularFileLoadsLikeTheSameBytesAndTheCapIsExact() {
        val bytes = Onnx.yunetModel()
        val f = write("canary-model.onnx", bytes)
        val fromFile = DetectorModel.load(ModelSource.fromFile(f.path), profile, bytes.size.toLong())
        assertEquals(DetectorModel.load(ModelSource.fromBytes(bytes), profile).info, fromFile.info)
        assertEquals(listOf("TOO_LARGE"), rejected(f.path, bytes.size - 1L))
        assertEquals(listOf("EMPTY"), rejected(write("empty.onnx", ByteArray(0)).path))
    }

    /**
     * A file that grows or shrinks during the read is refused (ADR 0012 "Verification", read rules): growth beyond the
     * cap through the cap + 1 rule, growth or shrinking within the cap through the size check, also when the file
     * grows only after the read found its end, and when it grows during the read and is cut back to its old size
     * before the read ends (the sizes before and after agree; only the bytes read differ). The seam changes the file
     * at a known point of the read loop.
     */
    @Test
    fun aFileThatGrowsOrShrinksDuringTheReadIsRefused() {
        val bytes = Onnx.yunetModel()
        val f = write("canary-growing.onnx", bytes)
        fun loadChanging(max: Long, change: (total: Long, endOfFile: Boolean) -> Unit): Throwable? {
            f.writeBytes(bytes)
            var changed = false
            JvmModelFileReader.afterReadCall = { total, eof ->
                if (!changed) {
                    changed = true
                    change(total, eof)
                }
            }
            try {
                return runCatching { DetectorModel.load(ModelSource.fromFile(f.path), profile, max) }.exceptionOrNull()
            } finally {
                JvmModelFileReader.afterReadCall = null
            }
        }
        val grow = { _: Long, _: Boolean -> f.appendBytes(ByteArray(100)) }
        // Control: the seam alone changes nothing.
        assertEquals(null, loadChanging(bytes.size.toLong()) { _, _ -> })
        // Within the cap (the default 50 MiB): the extra bytes are read, more than the size before the read.
        val within = loadChanging(DetectorModel.DEFAULT_MAX_MODEL_BYTES, grow)
        assertTrue(within is FaceMaskException.IoFailed, "growth within the cap: $within")
        assertEquals("I/O failed: the model file changed while it was read", within.message)
        assertTrue("canary" !in within.message!! && dir.name !in within.message!!)
        // Beyond the cap: cap + 1 bytes are read, the loader refuses them.
        val beyond = loadChanging(bytes.size.toLong(), grow)
        assertEquals(listOf("TOO_LARGE"), (beyond as FaceMaskException.ModelRejected).findings)
        // Growth only after the read found the end: the bytes read equal the old size, the size after the read differs.
        var sawEnd = false
        JvmModelFileReader.afterReadCall = { _, eof ->
            if (eof && !sawEnd) {
                sawEnd = true
                f.appendBytes(ByteArray(100))
            }
        }
        val afterEnd = runCatching {
            f.writeBytes(bytes)
            DetectorModel.load(ModelSource.fromFile(f.path), profile)
        }.exceptionOrNull()
        JvmModelFileReader.afterReadCall = null
        assertTrue(sawEnd)
        assertTrue(afterEnd is FaceMaskException.IoFailed, "growth after the end of the read: $afterEnd")
        // Growth during the read, cut back to the old size before the read ends: the size after the read equals the
        // size before it, so only the count of bytes read shows the change.
        var grew = false
        var readAtEnd = -1L
        JvmModelFileReader.afterReadCall = { total, eof ->
            if (!grew) {
                grew = true
                f.appendBytes(ByteArray(100))
            } else if (eof && readAtEnd < 0) {
                readAtEnd = total
                java.io.RandomAccessFile(f, "rw").use { it.setLength(bytes.size.toLong()) }
            }
        }
        val cutBack = runCatching {
            f.writeBytes(bytes)
            DetectorModel.load(ModelSource.fromFile(f.path), profile)
        }.exceptionOrNull()
        JvmModelFileReader.afterReadCall = null
        assertEquals(bytes.size + 100L, readAtEnd, "construction: the appended bytes were read")
        assertEquals(bytes.size.toLong(), f.length(), "construction: the file is back at its old size")
        assertTrue(cutBack is FaceMaskException.IoFailed, "growth cut back before the end of the read: $cutBack")
        assertEquals("I/O failed: the model file changed while it was read", cutBack.message)
        // The size changes during the read (shrinking after the first read call). With a model smaller than one read
        // chunk every byte is already read at that point, so the size after the read is what differs.
        val shrink = loadChanging(DetectorModel.DEFAULT_MAX_MODEL_BYTES) { _, _ ->
            java.io.RandomAccessFile(f, "rw").use { it.setLength(bytes.size / 2L) }
        }
        assertTrue(shrink is FaceMaskException.IoFailed, "shrinking during the read: $shrink")
    }

    @Test
    fun linksDirectoriesAndFifosAreRefusedWithoutThePath() {
        val target = write("real.onnx", Onnx.yunetModel())
        val link = File(dir, "canary-link.onnx")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertEquals(listOf("NOT_REGULAR_FILE"), rejected(link.path))
        assertEquals(listOf("NOT_REGULAR_FILE"), rejected(dir.path))
        val fifo = File(dir, "canary-fifo")
        val made = ProcessBuilder("mkfifo", fifo.path).start().waitFor(10, TimeUnit.SECONDS)
        assertTrue(made && fifo.exists(), "mkfifo is available on the test machines")
        // Refused from its attributes, without opening it (a regression would block; the thread bounds the wait).
        var result: List<String>? = null
        val t = Thread { result = rejected(fifo.path) }.apply { start() }
        t.join(10_000)
        assertEquals(listOf("NOT_REGULAR_FILE"), result, "a FIFO is refused without blocking")
        val missing = assertFailsWith<FaceMaskException.IoFailed> {
            DetectorModel.load(ModelSource.fromFile(File(dir, "canary-missing.onnx").path), profile)
        }
        assertTrue("canary" !in missing.message!! && dir.name !in missing.message!!, missing.message)
        assertTrue(rejected(link.path).none { "canary" in it })
        val url = assertFailsWith<FaceMaskException.IoFailed> {
            DetectorModel.load(ModelSource.fromFile("https://example.invalid/canary.onnx"), profile)
        }
        assertTrue("canary" !in url.message!!)
    }

    @Test
    fun reportCollectionsAreReadOnlyFromJava() {
        val m = testYuNetModel()

        @Suppress("UNCHECKED_CAST")
        val lists = listOf(
            m.info.inputs,
            m.info.outputs,
            m.info.warnings,
            m.info.inputs.single().shape,
        ) as List<MutableList<Any?>>
        for (l in lists) {
            assertFailsWith<UnsupportedOperationException> { (l as java.util.List<Any?>).add(null) }
            assertFailsWith<UnsupportedOperationException> { (l as java.util.List<Any?>).clear() }
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (m.info.opsetImports as java.util.Map<String, Long>).put("x", 1L)
        }
        val scaling = PixelScaling(1f, floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 1f, 1f))
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (scaling.mean as java.util.List<Float>).set(0, 9f)
        }
        val e = FaceMaskException.ModelRejected(listOf("A", "A", "B"))
        assertEquals(listOf("A", "B"), e.findings)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (e.findings as java.util.List<String>).add("C")
        }
    }

    /**
     * `Detection.warnings` and `MaskResult.warnings` (ADR 0012 immutability list) are copies: changing the caller's
     * list afterwards changes nothing, and Java cannot change them.
     */
    @Test
    fun resultWarningsAreReadOnlyCopies() {
        val source = mutableListOf("SMALL_INPUT")
        val timings = Timings(null, null, 0.0, 1, 0.0, null, 0.0, null)
        val d = Detection(640, 480, emptyList(), timings, false, MaskingConfig(), source)
        val r = MaskResult(640, 480, emptyList(), emptyList(), timings, false, source)
        source += "OTHER"
        source[0] = "CHANGED"
        for (w in listOf(d.warnings, r.warnings)) {
            assertEquals(listOf("SMALL_INPUT"), w, "a copy of the caller's list")
            @Suppress("UNCHECKED_CAST")
            val j = w as java.util.List<String>
            assertFailsWith<UnsupportedOperationException> { j.add("C") }
            assertFailsWith<UnsupportedOperationException> { j.set(0, "C") }
            assertFailsWith<UnsupportedOperationException> { j.clear() }
        }
        assertEquals(listOf("SMALL_INPUT"), d.warnings)
    }

    /** A model with an operator from an unknown domain loads (no allow-list) and the runtime refuses it. */
    @Test
    fun theRuntimeRefusesAnUnknownOperatorDomain() {
        val node = Onnx.node("Mystery", listOf("input"), listOf("cls_8"), emptyList()).also {
            it.string(7, "com.example.unknown")
        }
        val outputs = ArrayList<Proto>()
        for (s in intArrayOf(8, 16, 32)) {
            val n = (640 / s) * (640 / s)
            outputs += Onnx.valueInfo("cls_$s", Onnx.FLOAT, listOf(1, n, 1))
            outputs += Onnx.valueInfo("obj_$s", Onnx.FLOAT, listOf(1, n, 1))
            outputs += Onnx.valueInfo("bbox_$s", Onnx.FLOAT, listOf(1, n, 4))
        }
        val g = Onnx.graph(
            listOf(node),
            inputs = listOf(Onnx.valueInfo("input", Onnx.FLOAT, listOf(1, 3, 640, 640))),
            outputs = outputs,
        )
        val bytes = Onnx.model(g) { it.message(8, Proto().string(1, "com.example.unknown").varint(2, 1)) }.bytes()
        val model = DetectorModel.load(ModelSource.fromBytes(bytes), profile.pinnedTo(bytes))
        assertEquals(1L, model.info.opsetImports["com.example.unknown"])
        val e =
            assertFailsWith<FaceMaskException.ModelRejected> {
                FaceMasker.forTest(model, FaceMaskerConfig(), OrtModelRuntime())
            }
        assertEquals(listOf("RUNTIME_REJECTED"), e.findings)
        assertEquals(null, e.cause)
    }

    /** The real runtime reports its own version; the library version equals the build's version name. */
    @Test
    fun theReportsCarryTheRealVersions() {
        FaceMasker.forTest(TestModels.yunet(), FaceMaskerConfig(), OrtModelRuntime()).use { m ->
            assertEquals("1.31.0", m.modelInfo.runtimeVersion)
            assertEquals("CPU", m.modelInfo.executionProvider)
        }
        val props = Properties().apply { File("../gradle.properties").reader().use { load(it) } }
        assertEquals(props.getProperty("VERSION_NAME"), FaceBlurInfo.VERSION)
    }
}
