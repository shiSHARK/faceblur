// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.ModelInfo
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.internal.model.ModelLoader
import io.github.shishark.faceblur.internal.model.OnnxDescription
import io.github.shishark.faceblur.internal.model.OnnxPreParse
import io.github.shishark.faceblur.internal.model.Sha256
import io.github.shishark.faceblur.testmodel.ANY_SHA256
import io.github.shishark.faceblur.testmodel.Onnx
import io.github.shishark.faceblur.testmodel.Proto
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The model-bytes path against the real runtime and against a concurrent writer. */
class ModelBytesSecurityTest {
    /** Y = X + 1 for X of shape [1]; its producer name puts "ORTM" (the ORT format identifier) at bytes 4-7. */
    private fun ortmAtBytes4To7(): ByteArray {
        val graph = Onnx.graph(
            nodes = listOf(Onnx.node("Add", listOf("X", "B"), listOf("Y"))),
            initializers = listOf(Onnx.floatTensor("B", listOf(1), floatArrayOf(1f))),
            inputs = listOf(Onnx.valueInfo("X", Onnx.FLOAT, listOf(1))),
            outputs = listOf(Onnx.valueInfo("Y", Onnx.FLOAT, listOf(1))),
        )
        val bytes = Proto().varint(1, 8).string(2, "ORTM producer")
            .message(8, Proto().string(1, "").varint(2, 13)).message(7, graph).bytes()
        assertEquals("ORTM", bytes.copyOfRange(4, 8).decodeToString(), "the identifier is at bytes 4-7")
        return bytes
    }

    /** A model object around [bytes] without the pre-parse (the walker would refuse them first). */
    private fun unchecked(bytes: ByteArray): DetectorModel = DetectorModel.create(
        bytes,
        OnnxDescription(8, mapOf("" to 13L), emptyList(), emptyList()),
        DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, ANY_SHA256),
        ModelInfo(Sha256.hex(bytes), bytes.size.toLong(), 8, emptyMap(), emptyList(), emptyList(), emptyList()),
    )

    /**
     * The format lock of the session options works: with it, the runtime reads bytes that carry "ORTM" at bytes 4-7
     * as an ONNX model; without it, the same bytes are taken for the ORT format and refused. The walker refuses
     * them before any runtime sees them anyway.
     */
    @Test
    fun theFormatLockMakesTheRuntimeReadOrtmBytesAsOnnx() {
        val bytes = ortmAtBytes4To7()
        assertEquals(
            listOf("NOT_ONNX"),
            assertFailsWith<FaceMaskException.ModelRejected> { OnnxPreParse.parse(bytes) }.findings,
        )
        val session = OrtModelRuntime(telemetryVariable = "1").open(unchecked(bytes), 0)
        try {
            val out = session.run(floatArrayOf(2f), longArrayOf(1), listOf("Y"), 16, 0).getValue("Y")
            assertContentEquals(floatArrayOf(3f), out.data, "read as ONNX: Y = X + 1")
        } finally {
            session.close()
        }
        // Negative control: without the lock the runtime takes the identifier and refuses the bytes.
        // Telemetry stays off here too: the variable must be set before the environment exists.
        check(System.getenv("ORT_DISABLE_TELEMETRY") == "1") { "set ORT_DISABLE_TELEMETRY=1 before starting the JVM" }
        val env = OrtEnvironment.getEnvironment()
        env.setTelemetry(false)
        OrtSession.SessionOptions().use { options ->
            assertFailsWith<OrtException> { env.createSession(bytes, options).close() }
        }
    }

    /** Changing the app's array while `load` runs changes nothing that is loaded: one copy is hashed, parsed, kept. */
    @Test
    fun anArrayChangedDuringLoadCannotSplitHashParseAndRuntimeBytes() {
        val original = Onnx.yunetModel()
        val profile = DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, Sha256.hex(original))
        val source = original.copyOf()
        // Deterministic: the writer acts at the worst moment, after the copy and before the parse.
        val model = ModelLoader.load(ModelSource.fromBytes(source), profile, 1_000_000L) { copy ->
            source.fill(0x7F)
            OnnxPreParse.parse(copy)
        }
        assertContentEquals(original, model.verifiedBytes())
        assertEquals(Sha256.hex(original), model.info.sha256)
        // Concurrent: a second thread keeps flipping the array during many loads. Every load that succeeds holds
        // exactly the approved bytes; a copy taken while the array was changed fails the pin and is never parsed.
        val running = AtomicBoolean(true)
        val shared = original.copyOf()
        val flips = java.util.concurrent.atomic.AtomicLong()
        val writer = Thread {
            var flip = false
            while (running.get()) {
                flips.incrementAndGet()
                for (i in 8 until shared.size step 7) {
                    shared[i] = if (flip) original[i] else (original[i].toInt() xor 0x55).toByte()
                }
                flip = !flip
            }
        }
        writer.start()
        while (flips.get() < 10) Thread.onSpinWait()
        var loaded = 0
        var refused = 0
        try {
            repeat(300) {
                try {
                    val m = DetectorModel.load(ModelSource.fromBytes(shared), profile)
                    assertContentEquals(original, m.verifiedBytes())
                    assertEquals(Sha256.hex(original), m.info.sha256)
                    loaded++
                } catch (e: FaceMaskException.ModelIntegrity) {
                    refused++
                }
            }
        } finally {
            running.set(false)
            writer.join()
        }
        assertEquals(300, loaded + refused)
        assertTrue(refused > 0, "the writer changed the array during some loads")
    }
}
