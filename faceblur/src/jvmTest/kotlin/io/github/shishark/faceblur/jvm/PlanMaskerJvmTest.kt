// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlanMasker
import io.github.shishark.faceblur.PlatformTargetValue
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.ImageCodec
import io.github.shishark.faceblur.filemode.ImageFingerprint
import io.github.shishark.faceblur.filemode.ImageFormat
import io.github.shishark.faceblur.filemode.JpegTestData
import io.github.shishark.faceblur.filemode.OpenedImage
import io.github.shishark.faceblur.filemode.PhotoMetadata
import io.github.shishark.faceblur.filemode.Size
import io.github.shishark.faceblur.filemode.TargetWriter
import io.github.shishark.faceblur.filemode.WorkingImage
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.model.Sha256
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.mask.HeapRemainingProbe
import io.github.shishark.faceblur.mask.ReservedBytes
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM-only checks of ADR 0015 decision 6: the masks-without-a-model route requests no ONNX Runtime class (separate
 * JVMs with a refusing class loader, and a positive control), `PlanMasker` keeps no per-call state and serves parallel
 * calls, and the fingerprint is SHA-256 of exactly the bytes its definition names (against the JDK digest).
 */
class PlanMaskerJvmTest {
    @AfterTest
    fun tearDown() {
        FaceBlurRuntime.overrideDefaults(null)
        assertEquals(0L, ReservedBytes.current())
    }

    /** Runs [mode] of [OrtIsolationMain] in a new JVM with the test classpath and returns its one output line. */
    private fun isolated(mode: String): String {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            OrtIsolationMain::class.java.name,
            mode,
        )
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(finished, "the $mode JVM finished: $output")
        assertEquals(0, process.exitValue(), "the $mode JVM succeeded: $output")
        return output.lines().single { it.startsWith("requests=") }
    }

    /**
     * planFile -> withMasks -> maskFile runs with no model and no FaceMasker in the process and requests no ONNX
     * Runtime class. The control, in a separate JVM with the same harness and setup, requests them as soon as a
     * FaceMasker opens its session (the refusal makes that RuntimeUnavailable), so the harness does see them.
     */
    @Test
    fun theRouteWithoutAModelRequestsNoOnnxRuntimeClassAndTheControlDoes() {
        val plan = isolated("plan")
        assertEquals("requests=0 loaded= result=writes=1 masks=1 faces=0", plan)
        val control = isolated("control")
        val requests = Regex("requests=(\\d+)").find(control)!!.groupValues[1].toInt()
        assertTrue(requests > 0, "the control requests ONNX Runtime classes: $control")
        assertTrue(control.contains("result=writes=1 masks=1 faces=0 masker=RuntimeUnavailable"), control)
        assertTrue(control.contains(OrtIsolationMain.FACE_MASKER), "the control loads FaceMasker: $control")
    }

    /** PlanMasker holds only its masking settings: no field can keep a call's context, source or target. */
    @Test
    fun planMaskerHoldsNoPerCallState() {
        val fields = PlanMasker::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        assertEquals(setOf("masking", "settings"), fields.map { it.name }.toSet())
        assertTrue(fields.all { Modifier.isFinal(it.modifiers) }, "every field is final")
        val types = fields.map { it.type.name }
        assertTrue(
            types.none {
                it.contains("CallContext") || it.contains("FileSource") || it.contains("OutputTarget")
            },
            "$types",
        )
    }

    /** Thread-safe parts for the parallel test (the common fakes record into plain lists). */
    private class ParallelParts(private val imageSize: Size) {
        val writes = AtomicInteger()
        val codec = ImageCodec { _, _ ->
            object : OpenedImage {
                override val format = ImageFormat.JPEG
                override val size = imageSize
                override val hasGainMap = false
                override val metadata: PhotoMetadata? = null

                override fun decode(working: Size): WorkingImage = Working(
                    IntArrayPixels(working.width, working.height, gradientPixels(working.width, working.height)),
                )

                override fun close() = Unit
            }
        }

        class Working(override val pixels: IntArrayPixels) : WorkingImage {
            override fun scaledTo(output: Size): WorkingImage = Working(IntArrayPixels(output.width, output.height))

            override fun encodeJpeg(quality: Int): ByteArray =
                JpegTestData.encoderOutput(Size(pixels.width, pixels.height))

            override fun close() = Unit
        }

        val writer = object : TargetWriter {
            override fun check(target: OutputTarget, source: FileSource, call: CallContext) = Unit

            override fun accepts(value: PlatformTargetValue) = false

            override fun absolutePath(path: String) = path

            override fun write(target: OutputTarget, source: FileSource, bytes: ByteArray, call: CallContext) {
                writes.incrementAndGet()
            }
        }
    }

    @Test
    fun twoParallelCallsOnOneInstanceSucceed() {
        val parts = ParallelParts(Size(1200, 900))
        FaceBlurRuntime.overrideDefaults(
            PlatformDefaults(memoryProbe = HeapRemainingProbe, imageCodec = parts.codec, targetWriter = parts.writer),
        )
        val planMasker = PlanMasker(MaskingConfig())
        val rounds = 10
        val barrier = CyclicBarrier(2)
        val done = CountDownLatch(2)
        val failures = java.util.Collections.synchronizedList(ArrayList<Throwable>())
        val pool = Executors.newFixedThreadPool(2)
        repeat(2) { worker ->
            pool.execute {
                try {
                    repeat(rounds) { i ->
                        barrier.await(30, TimeUnit.SECONDS)
                        val source = "/photos/$worker-$i.jpg"
                        val plan = planMasker.planFile(source, FileOptions(maxLongSide = 0))
                            .withMasks(listOf(Mask.manual(100f + worker * 300, 100f, 300f + worker * 300, 400f)))
                        val r = planMasker.maskFile(
                            source,
                            OutputTarget.NewFile("/out/$worker-$i.jpg"),
                            plan,
                            FileOptions(maxLongSide = 0),
                        )
                        check(r.masks == plan.masks)
                    }
                } catch (t: Throwable) {
                    failures += t
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(120, TimeUnit.SECONDS))
        pool.shutdown()
        assertEquals(emptyList(), failures.map { it.toString() })
        assertEquals(2 * rounds, parts.writes.get())
    }

    /** The fingerprint's definition: SHA-256 (JDK) over the tag, width, height and every pixel, big-endian. */
    @Test
    fun theFingerprintIsSha256OfTheTagTheSizeAndEveryPixel() {
        for ((w, h) in listOf(5 to 3, 1000 to 70, 65_535 to 2)) {
            val pixels = IntArray(w * h) { (0xFF shl 24) or ((it * 2_654_435_761L).toInt() and 0xFFFFFF) }
            val md = MessageDigest.getInstance("SHA-256")
            md.update("FaceBlur image fingerprint v1".toByteArray(Charsets.US_ASCII))
            md.update(0)
            val buffer = java.nio.ByteBuffer.allocate(8 + pixels.size * 4).putInt(w).putInt(h)
            for (p in pixels) buffer.putInt(p)
            md.update(buffer.array())
            val expected = md.digest()
            val f = ImageFingerprint.of(IntArrayPixels(w, h, pixels))
            val field = ImageFingerprint::class.java.getDeclaredField("digest").apply { isAccessible = true }
            assertContentEquals(expected, field.get(f) as ByteArray, "$w x $h")
        }
    }

    /** The incremental hasher equals the JDK digest for parts of every size around the block boundary. */
    @Test
    fun theIncrementalHasherEqualsTheJdkDigest() {
        val data = ByteArray(10_000) { (it * 131 + 17).toByte() }
        for (chunk in listOf(1, 3, 63, 64, 65, 1000, 4096)) {
            val h = Sha256.Hasher()
            var at = 0
            while (at < data.size) {
                val n = minOf(chunk, data.size - at)
                h.update(data, at, n)
                at += n
            }
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(data), h.finish(), "chunk $chunk")
        }
    }
}
