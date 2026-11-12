// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceBlurInfo
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.filemode.OutputValidator
import io.github.shishark.faceblur.internal.runtime.SessionRejected
import io.github.shishark.faceblur.internal.runtime.SessionTimeout
import io.github.shishark.faceblur.maskJpeg
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * ST-003 (AC1-AC4) and ST-049 (AC2 matching case, AC3 at the session, AC4, AC5) on the phone: the Android binding with
 * the real ONNX Runtime 1.31.0 and XNNPACK, the reference model fixture loaded from the app's private storage through
 * `ModelSource.fromFile`, the contract run at creation, and the thin file-mode path on a registered synthetic image.
 * Any timing logged here comes from a debug build and is not performance evidence.
 */
@RunWith(AndroidJUnit4::class)
class AndroidRunnerDeviceTest {
    // No install call: the Android bindings are the platform defaults (ADR 0015 decision 1), as in an app that only
    // adds the dependency.
    @Before
    fun logHeader() {
        DeviceFixtures.logHeader()
    }

    private fun loadModel(): DetectorModel = DetectorModel.load(
        ModelSource.fromFile(DeviceFixtures.file(DeviceFixtures.YUNET_FILE).path),
        DeviceFixtures.yunetProfile(),
    )

    @Test
    fun telemetryIsOffAndTheSessionUsesXnnpackWithTheHandoverThreadPlan() {
        assertEquals("1", Os.getenv("ORT_DISABLE_TELEMETRY"), "set by the binding before the runtime is touched")
        val model = loadModel()
        val session = AndroidModelRuntime.open(model, 0) as OrtModelSession
        try {
            val workers = minOf(4, Runtime.getRuntime().availableProcessors())
            val expected =
                listOf(
                    "telemetry=false",
                    "session.load_model_format=ONNX",
                    "session.allow_released_opsets_only=1",
                    "session.intra_op.allow_spinning=0",
                    "optimization=ALL_OPT",
                    "session_log_level=FATAL",
                    "intra_op_threads=1",
                    "xnnpack.intra_op_num_threads=$workers",
                )
            assertEquals(expected, session.appliedOptions)
            assertTrue(OrtProvider.XNNPACK in OrtEnvironment.getAvailableProviders(), "XNNPACK is in this runtime")
            assertEquals("XNNPACK", session.executionProvider)
            assertEquals("1.31.0", session.runtimeVersion, "read from the runtime")
            // ST-049 AC2, matching case: the runtime's view equals the pre-parse result.
            assertEquals(
                model.description.inputs.map {
                    it.name to it.dims
                },
                session.inputs.map { it.name to it.shape },
            )
            assertEquals(
                model.description.outputs.map {
                    it.name to it.dims
                },
                session.outputs.map { it.name to it.shape },
            )
            Log.i(DeviceFixtures.TAG, "session options: ${session.appliedOptions}")
            Log.i(DeviceFixtures.TAG, "available providers: ${OrtEnvironment.getAvailableProviders()}")
        } finally {
            session.close()
        }
    }

    @Test
    fun aMaskerFromTheFixturePassesTheContractRunAndReportsTheRuntime() {
        val start = TimeSource.Monotonic.markNow()
        val model = loadModel()
        val loaded = start.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
        FaceMasker(model).use { masker ->
            val total = start.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
            val info = masker.modelInfo
            assertEquals("1.31.0", info.runtimeVersion)
            assertEquals("XNNPACK", info.executionProvider)
            assertEquals(FaceBlurInfo.VERSION, info.faceBlurVersion)
            assertEquals(DeviceFixtures.YUNET_SHA256, info.model.sha256)
            Log.i(
                DeviceFixtures.TAG,
                "model ${info.model.sizeBytes} bytes, runtime ${info.runtimeVersion}, ${info.executionProvider}",
            )
            Log.i(
                DeviceFixtures.TAG,
                "cold start (debug build, not performance evidence): load %.1f ms, load + creation %.1f ms".format(
                    loaded,
                    total,
                ),
            )
        }
    }

    /** The registered PNG re-encoded as a JPEG in the app's private storage (no JPEG fixture is registered yet). */
    private fun registeredJpeg(dir: File): File {
        val png = DeviceFixtures.file(DeviceFixtures.IMAGE_FILE)
        val bitmap = BitmapFactory.decodeFile(png.path)
        val jpeg = File(dir, "input.jpg")
        jpeg.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        bitmap.recycle()
        return jpeg
    }

    @Test
    fun fileModeMasksARegisteredJpegOnThePhone() {
        val dir = DeviceFixtures.scratch("file-mode")
        try {
            val input = registeredJpeg(dir)
            val output = File(dir, "output.jpg")
            val result =
                FaceMasker(loadModel()).use { masker ->
                    input.inputStream().use { i ->
                        output.outputStream().use { o -> masker.maskJpeg(i, o) }
                    }
                }
            val bytes = output.readBytes()
            assertEquals(emptyList(), OutputValidator.findings(bytes, MetadataPolicy.StripAll), "PRIV-007 marker walk")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            assertEquals("image/jpeg", bounds.outMimeType)
            assertEquals(640, bounds.outWidth)
            assertEquals(640, bounds.outHeight)
            assertTrue(result.faces.isNotEmpty(), "at least one face")
            assertTrue(result.faces.all { it.score > 0f })
            // Kept in the app's private storage for the independent checker (synthetic image; removed with the app).
            val evidence = File(DeviceFixtures.context.filesDir, "faceblur-evidence").apply { mkdirs() }
            output.copyTo(File(evidence, "st003-output.jpg"), overwrite = true)
            Log.i(
                DeviceFixtures.TAG,
                "file mode: ${result.faces.size} face(s), best score %.3f, %d masks, output %d bytes, %dx%d; "
                    .format(
                        result.faces.maxOf {
                            it.score
                        },
                        result.masks.size,
                        bytes.size,
                        bounds.outWidth,
                        bounds.outHeight,
                    ) +
                    "decode %.1f ms, detect %.1f ms (%d passes), mask %.1f ms, encode %.1f ms (debug build)".format(
                        result.timings.decodeMs,
                        result.timings.detectMs,
                        result.timings.passes,
                        result.timings.maskMs,
                        result.timings.encodeMs,
                    ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aPassOverItsLimitIsStoppedAndTheSessionStaysUsable() {
        val model = loadModel()
        val session = AndroidModelRuntime.open(model, 0)
        try {
            val input = FloatArray(3 * 640 * 640) { 127f }
            val shape = longArrayOf(1, 3, 640, 640)
            val names = session.outputs.map { it.name }
            val first = session.run(input, shape, names, Long.MAX_VALUE, 0)
            assertEquals(names.toSet(), first.keys, "a pass without a limit runs")
            assertFailsWith<SessionTimeout> { session.run(input, shape, names, Long.MAX_VALUE, 1) }
            val again = session.run(input, shape, names, Long.MAX_VALUE, 0)
            assertEquals(names.toSet(), again.keys, "the session stays usable after a stopped pass")
        } finally {
            session.close()
        }
    }

    @Test
    fun theMaskerKeepsWorkingAfterTheModelIsDropped() {
        val dir = DeviceFixtures.scratch("dropped-model")
        try {
            val masker = createAndDropModel()
            repeat(3) {
                System.gc()
                System.runFinalization()
            }
            masker.use { m ->
                val input = registeredJpeg(dir)
                val out = File(dir, "out.jpg")
                val result = input.inputStream().use { i ->
                    out.outputStream().use { o -> m.maskJpeg(i, o) }
                }
                assertTrue(result.faces.isNotEmpty())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun createAndDropModel(): FaceMasker = FaceMasker(loadModel())

    /**
     * The runtime refuses bytes that are not an ONNX model (in the same read-only buffer the binding makes for a
     * model): `SessionRejected`, without the runtime's exception or text. The other mapping in `OrtSessions`,
     * `RuntimeOptionFailed` for an option the runtime does not accept, cannot be provoked with the shipped runtime
     * (it accepts every locked option), so no test covers it.
     */
    @Test
    fun aModelTheRuntimeRejectsIsSessionRejected() {
        val notAModel = ByteArray(4096) { (it * 31 + 7).toByte() }
        val e = assertFailsWith<SessionRejected> { OrtSessions.open(ModelBuffer.of(notAModel), 0) }
        assertNull(e.cause, "the runtime's exception is not chained")
        assertEquals("the runtime refused the model", e.message)
        // Control: the same call with the reference model opens a session.
        OrtSessions.open(ModelBuffer.of(DeviceFixtures.file(DeviceFixtures.YUNET_FILE).readBytes()), 0).close()
    }
}
