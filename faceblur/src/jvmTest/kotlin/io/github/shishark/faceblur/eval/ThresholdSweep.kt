// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.eval

import io.github.shishark.faceblur.Detection
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.PixelImage
import io.github.shishark.faceblur.detect.DetectionSettings
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput

/**
 * The exact threshold sweep of faceblur-eval (DX-MA-001): per image, the model runs once per pass; every pass output
 * is stored, and the detection of every other threshold replays the stored outputs through the library's unchanged
 * decode, in-pass NMS, mapping and cross-pass merge. The model outputs do not depend on the threshold, so each result
 * equals a full rerun with that threshold in the profile (tested against such reruns).
 *
 * One instance per thread. [close] releases the one runtime session.
 */
internal class ThresholdSweep(
    model: DetectorModel,
    thresholds: List<Float>,
    config: FaceMaskerConfig,
    runtime: ModelRuntime,
    /** Test seam: the image the replayed thresholds see (production: the same image). */
    private val replayImage: (PixelImage) -> PixelImage = { it },
) : AutoCloseable {
    /** The thresholds, ascending and distinct. */
    val thresholds: List<Float> = thresholds.distinct().sorted()

    private val replay = ReplayRuntime(runtime)
    private val maskers: List<FaceMasker>

    init {
        require(this.thresholds.isNotEmpty()) { "at least one threshold" }
        val made = ArrayList<FaceMasker>()
        try {
            for (t in this.thresholds) {
                made +=
                    FaceMasker.forTest(model, config, replay, DetectionSettings(minScore = t))
            }
        } catch (e: Throwable) {
            made.forEach { it.close() }
            replay.close()
            throw e
        }
        maskers = made
    }

    /** Model passes actually run, creation contract runs included (for tests). */
    val modelRuns: Int get() = replay.modelRuns

    /** The masker of the highest threshold (its report and settings are those of every threshold). */
    val reportMasker: FaceMasker get() = maskers.last()

    /**
     * The detection of [image] at every threshold, in the order of [thresholds]: a [Detection] or the
     * [FaceMaskException] that the call threw at that threshold.
     *
     * @throws ReplayMismatch when a replayed threshold asked for other passes than the recording: a fault of the tool,
     * never a measurement (the library turns the replay's error into a model-output error, so it is checked here).
     */
    fun run(image: PixelImage): List<Result<Detection>> {
        val out = arrayOfNulls<Result<Detection>>(thresholds.size)
        val last = thresholds.lastIndex
        // The highest threshold runs the model and records; it is the run with the fewest candidates.
        replay.record()
        val recorded = runCatchingMask { maskers[last].detect(image) }
        val complete = recorded.isSuccess
        replay.stop()
        out[last] = recorded
        // A run that failed at the top threshold has no complete recording: the others rerun the model.
        for (i in 0 until last) {
            if (complete) replay.replay()
            val r = runCatchingMask { maskers[i].detect(if (complete) replayImage(image) else image) }
            // A run that fails stops before its last pass; a successful one must have used every recorded pass.
            // finishReplay throws ReplayMismatch for a replay fault even when the run itself reported a failure.
            if (complete) replay.finishReplay(allUsed = r.isSuccess)
            out[i] = r
        }
        replay.clear()
        return out.map { it!! }
    }

    override fun close() {
        maskers.forEach { it.close() }
        replay.close()
    }

    private inline fun runCatchingMask(block: () -> Detection): Result<Detection> = try {
        Result.success(block())
    } catch (e: FaceMaskException) {
        // Internal is a fault of the library or of this tool: never a measurement.
        if (e is FaceMaskException.Internal) throw e
        Result.failure(e)
    }
}

/**
 * A runtime that opens the real session once and shares it: in pass-through mode it runs the model, in record mode
 * it also stores each pass's input digest and outputs, in replay mode it returns the stored outputs in order and
 * fails loudly when the input or the number of passes differs from the recording.
 */
internal class ReplayRuntime(private val real: ModelRuntime) : ModelRuntime {
    private enum class Mode { PASS, RECORD, REPLAY }

    private var mode = Mode.PASS
    private var shared: ModelSession? = null
    private val recordedInputs = ArrayList<String>()
    private val recordedOutputs = ArrayList<Map<String, PassOutput>>()
    private var cursor = 0

    /** The first replay fault since [replay], if any (the library hides the error itself behind RUN_FAILED). */
    var fault: String? = null
        private set

    /** Model passes actually run (for tests). */
    var modelRuns: Int = 0
        private set

    fun record() {
        clear()
        mode = Mode.RECORD
    }

    fun stop() {
        mode = Mode.PASS
    }

    fun replay() {
        cursor = 0
        fault = null
        mode = Mode.REPLAY
    }

    fun finishReplay(allUsed: Boolean) {
        mode = Mode.PASS
        fault?.let { throw ReplayMismatch(it) }
        if (allUsed && cursor != recordedOutputs.size) {
            throw ReplayMismatch("replay used $cursor of ${recordedOutputs.size} recorded passes")
        }
    }

    fun clear() {
        recordedInputs.clear()
        recordedOutputs.clear()
        cursor = 0
    }

    override fun open(model: DetectorModel, threads: Int): ModelSession {
        val s = shared ?: real.open(model, threads).also { shared = it }
        return object : ModelSession by s {
            override fun run(
                input: FloatArray,
                inputShape: LongArray,
                outputNames: List<String>,
                maxElements: Long,
                timeoutMillis: Long,
            ): Map<String, PassOutput> {
                if (mode == Mode.REPLAY) {
                    if (cursor >= recordedOutputs.size) fail("replay asked for more passes than recorded")
                    if (recordedInputs[cursor] != digest(input)) fail("replay input differs from the recording")
                    return recordedOutputs[cursor++]
                }
                modelRuns++
                val result = s.run(input, inputShape, outputNames, maxElements, timeoutMillis)
                if (mode == Mode.RECORD) {
                    recordedInputs += digest(input)
                    recordedOutputs += result
                }
                return result
            }

            // The shared session is closed once, by the runtime's close.
            override fun close() = Unit
        }
    }

    private fun fail(reason: String): Nothing {
        if (fault == null) fault = reason
        throw ReplayMismatch(reason)
    }

    fun close() {
        shared?.close()
        shared = null
    }

    /** SHA-256 of the input tensor's float bits: a replay must see exactly the recorded input. */
    private fun digest(input: FloatArray): String {
        val buffer = java.nio.ByteBuffer.allocate(input.size * 4)
        buffer.asFloatBuffer().put(input)
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(buffer.array())
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** The replay did not match the recording: a fault of faceblur-eval, never a measurement. */
internal class ReplayMismatch(message: String) : IllegalStateException(message)
