// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.Limits
import io.github.shishark.faceblur.detect.ModelDetector
import io.github.shishark.faceblur.detect.OutputCheckFailed
import io.github.shishark.faceblur.internal.model.DeclaredTensor
import io.github.shishark.faceblur.internal.model.ModelLoader
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.SessionTensor
import kotlin.time.TimeSource

/** A session that passed steps 6 and 7 of ADR 0012 decision 4, with its detector and report. */
internal class OpenedSession private constructor(
    val session: ModelSession,
    val detector: ModelDetector,
    val info: MaskerModelInfo,
) {
    companion object {
        /** Step 6 (runtime view of the inputs and outputs) and step 7 (contract run) for [model] on [session]. */
        fun check(model: DetectorModel, session: ModelSession): OpenedSession {
            val d = model.description
            if (!matches(d.inputs, session.inputs) || !matches(d.outputs, session.outputs)) {
                throw FaceMaskException.ModelRejected(listOf(IO_MISMATCH))
            }
            val declared = declaredOutputs(model)
            val probe =
                ModelDetector(
                    session,
                    model.profile,
                    declaredOutputs = declared,
                    timeoutMillis = Limits.CONTRACT_RUN_MILLIS,
                )
            val mark = TimeSource.Monotonic.markNow()
            val outputs =
                try {
                    probe.runUniformGrey()
                } catch (e: OutputCheckFailed) {
                    throw FaceMaskException.ModelRejected(
                        listOf(if (ModelDetector.RUN_TIMEOUT in e.findings) TIMEOUT else CONTRACT_OUTPUT),
                    )
                }
            val contractMillis = mark.elapsedNow().inWholeMilliseconds
            // Platforms that cannot stop a run (iOS) check the limit when the run returns.
            if (contractMillis > Limits.CONTRACT_RUN_MILLIS) throw FaceMaskException.ModelRejected(listOf(TIMEOUT))
            try {
                probe.decode(outputs, model.profile.scoreThreshold) {}
            } catch (e: OutputCheckFailed) {
                val shapeCodes =
                    setOf(
                        ModelDetector.OUTPUT_TYPE,
                        ModelDetector.OUTPUT_SHAPE,
                        ModelDetector.TOO_MANY_ROWS,
                        ModelDetector.TOO_MANY_ELEMENTS,
                    )
                val codes = e.findings.map { if (it in shapeCodes) CONTRACT_OUTPUT else CONTRACT_VALUES }.distinct()
                throw FaceMaskException.ModelRejected(codes)
            }
            val recorded = HashMap<String, Long>()
            for ((name, out) in outputs) {
                val declared = d.outputs.lastOrNull { it.name == name }?.dims ?: continue
                if (declared.all { it != null }) {
                    val elements = ModelDetector.elementCount(out.shape)
                    val expected = declared.fold(1L) { acc, v -> acc * (v ?: 0L) }
                    if (elements != expected) throw FaceMaskException.ModelRejected(listOf(CONTRACT_OUTPUT))
                    recorded[name] = expected
                }
            }
            val timeout = maxOf(Limits.MIN_RUN_TIMEOUT_MILLIS, Limits.RUN_TIMEOUT_FACTOR * contractMillis)
            val detector = ModelDetector(session, model.profile, recorded, declared, timeout)
            val info =
                MaskerModelInfo(model.info, session.runtimeVersion, session.executionProvider, FaceBlurInfo.VERSION)
            return OpenedSession(session, detector, info)
        }

        /** The declared dimensions of every output the format reads (the last declaration of a name wins). */
        private fun declaredOutputs(model: DetectorModel): Map<String, List<Long?>> {
            val out = HashMap<String, List<Long?>>()
            for (need in ModelLoader.requiredOutputs(model.profile)) {
                val dims = model.description.outputs.lastOrNull { it.name == need.name }?.dims ?: continue
                out[need.name] = dims
            }
            return out
        }

        /**
         * True when the runtime reports the same names in the same order and, where it reports them (Android and
         * JVM; iOS reports names only), the same element types and shapes as the model file declares.
         */
        private fun matches(declared: List<DeclaredTensor>, reported: List<SessionTensor>): Boolean {
            if (declared.map { it.name } != reported.map { it.name }) return false
            for ((dt, rt) in declared.zip(reported)) {
                if (dt.isTensor && rt.elementType != null && rt.elementType != dt.elementType) return false
                val dims = dt.dims
                if (dims != null && rt.shape != null && rt.shape != dims) return false
            }
            return true
        }

        const val IO_MISMATCH = "IO_MISMATCH"
        const val RUNTIME_REJECTED = "RUNTIME_REJECTED"
        const val CONTRACT_OUTPUT = "CONTRACT_OUTPUT"
        const val CONTRACT_VALUES = "CONTRACT_VALUES"
        const val TIMEOUT = "TIMEOUT"
    }
}
