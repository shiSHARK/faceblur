// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.SessionTensor
import io.github.shishark.faceblur.testmodel.ANY_SHA256
import io.github.shishark.faceblur.testmodel.Onnx
import io.github.shishark.faceblur.testmodel.pinnedTo

/**
 * Test double of a model runtime pass: returns every output flattened row-major by name (for example "cls_8").
 * Shapes are derived from the name ([FakeSession]).
 */
internal interface TensorRunner {
    val inputWidth: Int
    val inputHeight: Int

    fun initialize()

    fun run(input: FloatArray): Map<String, FloatArray>

    fun close()
}

/**
 * A [ModelSession] over a [TensorRunner]. It reports the model file's inputs and outputs (so the runtime check
 * passes), returns only the requested outputs, and gives YuNet-named outputs the shapes [1, N, 1] (`cls_*`, `obj_*`),
 * [1, N, 4] (`bbox_*`) and [1, N, 10] (`kps_*`); any other output gets [shapes] or [1, size].
 */
internal class FakeSession(
    private val runner: TensorRunner,
    model: DetectorModel,
    private val shapes: Map<String, LongArray> = emptyMap(),
    override val runtimeVersion: String = "test-runtime",
    override val executionProvider: String = "CPU",
) : ModelSession {
    override val inputs: List<SessionTensor> =
        model.description.inputs.map { SessionTensor(it.name, it.elementType, it.dims) }
    override val outputs: List<SessionTensor> =
        model.description.outputs.map { SessionTensor(it.name, it.elementType, it.dims) }
    var runs = 0
    var lastTimeout = -1L

    override fun run(
        input: FloatArray,
        inputShape: LongArray,
        outputNames: List<String>,
        maxElements: Long,
        timeoutMillis: Long,
    ): Map<String, PassOutput> {
        runs++
        lastTimeout = timeoutMillis
        val all = runner.run(input)
        val out = LinkedHashMap<String, PassOutput>()
        for (name in outputNames) {
            val data = all[name] ?: continue
            out[name] = PassOutput(Onnx.FLOAT, shapeOf(name, data.size), data)
        }
        return out
    }

    private fun shapeOf(name: String, size: Int): LongArray = shapes[name] ?: when {
        name.startsWith("cls_") || name.startsWith("obj_") -> longArrayOf(1, size.toLong(), 1)
        name.startsWith("bbox_") -> longArrayOf(1, size / 4L, 4)
        name.startsWith("kps_") -> longArrayOf(1, size / 10L, 10)
        else -> longArrayOf(1, size.toLong())
    }

    override fun close() = runner.close()
}

/** A runtime that opens a [FakeSession] over [runner] for every masker. */
internal class RunnerRuntime(
    private val runner: TensorRunner,
    private val shapes: Map<String, LongArray> = emptyMap(),
) : ModelRuntime {
    var opened = 0
    var last: FakeSession? = null

    override fun open(model: DetectorModel, threads: Int): ModelSession {
        opened++
        return FakeSession(runner, model, shapes).also { last = it }
    }
}

/** The example YuNet profile of the tests (threshold 0.5, the default mask minimum, no pin). */
internal fun yunetProfile(width: Int = 640, height: Int = 640, threshold: Float = 0.5f) =
    DetectorProfile(DetectionFormat.YuNetLayout(), width, height, threshold, ANY_SHA256)

/** A loaded, structurally valid YuNet-layout test model (no weights; runs only with a fake session). */
internal fun testYuNetModel(profile: DetectorProfile = yunetProfile()): DetectorModel =
    Onnx.yunetModel(profile.inputWidth, profile.inputHeight).let {
        DetectorModel.load(ModelSource.fromBytes(it), profile.pinnedTo(it))
    }

/** A per-pass detector over [runner] with the YuNet layout (threshold given per call). */
internal fun yunetDetector(runner: TensorRunner, profile: DetectorProfile = yunetProfile()): ModelDetector =
    ModelDetector(FakeSession(runner, testYuNetModel(profile)), profile)

/** The YuNet strides of the test models. */
internal val YUNET_STRIDES = intArrayOf(8, 16, 32)
