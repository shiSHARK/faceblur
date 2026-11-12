// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.internal.model.OnnxPreParse
import io.github.shishark.faceblur.internal.model.Sha256
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.testmodel.Onnx
import io.github.shishark.faceblur.testmodel.Proto
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Sparse tensors with external data against the real ONNX Runtime 1.31.0. A model loaded from bytes may still read
 * external data inside the process's working directory in this runtime version (the canary below shows it), so the
 * pre-parse walker is the control that matters: it refuses every such model with `EXTERNAL_DATA`, and no runtime
 * session is ever created for it. If a runtime update flips the canary, re-assess this test deliberately.
 */
class SparseExternalDataTest {
    private val inside = 1111f

    /** A float tensor [1] whose data is the first 4 bytes of the file at [location] (relative path). */
    private fun external(name: String, location: String): Proto {
        val t = Proto().varint(1, 1).varint(2, Onnx.FLOAT.toLong()).string(8, name)
        t.message(13, Proto().string(1, "location").string(2, location))
        t.message(13, Proto().string(1, "offset").string(2, "0"))
        t.message(13, Proto().string(1, "length").string(2, "4"))
        return t.varint(14, 1)
    }

    private fun sparse(valuesName: String, location: String): Proto = Proto()
        .message(1, external(valuesName, location))
        .message(2, Onnx.int64Tensor("${valuesName}_idx", listOf(1), longArrayOf(0)))
        .varint(3, 1)

    private val io =
        listOf(Onnx.valueInfo("X", Onnx.FLOAT, listOf(1))) to listOf(Onnx.valueInfo("Y", Onnx.FLOAT, listOf(1)))

    /** Y = X + W, W a sparse initializer with external values. */
    private fun sparseInitializer(location: String): ByteArray {
        val g = Onnx.graph(
            nodes = listOf(Onnx.node("Add", listOf("X", "W"), listOf("Y"))),
            inputs = io.first,
            outputs = io.second,
        )
        g.message(15, sparse("W", location))
        return Onnx.model(g).bytes()
    }

    /** Y = X + W, W from a Constant node whose `sparse_value` has external values. */
    private fun sparseConstant(location: String): ByteArray {
        val attr = Proto().string(1, "sparse_value").message(22, sparse("Wv", location)).varint(20, 11)
        val g = Onnx.graph(
            nodes = listOf(
                Onnx.node("Constant", emptyList(), listOf("W"), listOf(attr)),
                Onnx.node("Add", listOf("X", "W"), listOf("Y")),
            ),
            inputs = io.first,
            outputs = io.second,
        )
        return Onnx.model(g).bytes()
    }

    /** The marker inside the working directory (the project directory), removed afterwards. */
    private fun <T> withMarker(block: (String) -> T): T {
        val dir = File("build/tmp/sparse-external-data")
        dir.mkdirs()
        val marker = File(dir, "marker.bin")
        marker.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(inside).array())
        try {
            return block("build/tmp/sparse-external-data/marker.bin")
        } finally {
            marker.delete()
        }
    }

    @Test
    fun theWalkerRefusesSparseExternalDataAndNoSessionIsCreated() = withMarker { location ->
        // A counting JVM runtime is the installed binding for this test: every session the library opens on any path
        // goes through it. Loading the refused models must not open one; the positive control shows that the counter
        // does count a session the library opens (the reference model through the public constructor).
        val previous = FaceBlurRuntime.runtime()
        val runtime = OrtModelRuntime()
        FaceBlurRuntime.install(runtime)
        try {
            for ((name, bytes) in listOf(
                "sparse initializer" to sparseInitializer(location),
                "sparse Constant" to sparseConstant(location),
            )) {
                val e = assertFailsWith<FaceMaskException.ModelRejected>(name) { OnnxPreParse.parse(bytes) }
                assertEquals(listOf("EXTERNAL_DATA"), e.findings, name)
                val profile = DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, Sha256.hex(bytes))
                val load = assertFailsWith<FaceMaskException.ModelRejected>(name) {
                    DetectorModel.load(ModelSource.fromBytes(bytes), profile)
                }
                assertEquals(listOf("EXTERNAL_DATA"), load.findings, name)
                assertEquals(0, runtime.sessionsOpened, "$name: no runtime session for a refused model")
            }
            FaceMasker(TestModels.yunet()).close()
            assertEquals(1, runtime.sessionsOpened, "control: the installed runtime counts the library's sessions")
        } finally {
            if (previous != null) FaceBlurRuntime.install(previous) else FaceBlurRuntime.uninstall()
        }
    }

    /** Canary: without the walker, ONNX Runtime 1.31.0 reads the in-working-directory marker of both models. */
    @Test
    fun theRawRuntimeStillReadsTheMarker() = withMarker { location ->
        check(System.getenv("ORT_DISABLE_TELEMETRY") == "1") { "set ORT_DISABLE_TELEMETRY=1 before starting the JVM" }
        val env = OrtEnvironment.getEnvironment()
        env.setTelemetry(false)
        for ((name, bytes) in listOf(
            "sparse initializer" to sparseInitializer(location),
            "sparse Constant" to sparseConstant(location),
        )) {
            OrtSession.SessionOptions().use { o ->
                o.addConfigEntry("session.load_model_format", "ONNX")
                o.addConfigEntry("session.allow_released_opsets_only", "1")
                o.addConfigEntry("session.intra_op.allow_spinning", "0")
                o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                env.createSession(bytes, o).use { session ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1)).use { x ->
                        session.run(mapOf("X" to x)).use { result ->
                            val y = (result[0].value as FloatArray)[0]
                            assertEquals(1f + inside, y, "$name: the runtime read the marker (canary)")
                        }
                    }
                }
            }
        }
    }
}
