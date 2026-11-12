// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.RuntimeOptionFailed
import io.github.shishark.faceblur.internal.runtime.SessionRejected
import io.github.shishark.faceblur.internal.runtime.SessionRunFailed
import io.github.shishark.faceblur.internal.runtime.SessionTensor
import java.nio.ByteBuffer

/**
 * The only Android code that touches ONNX Runtime (onnxruntime-android 1.31.0). Callers must have set
 * `ORT_DISABLE_TELEMETRY=1` first ([AndroidModelRuntime] does); after getting the environment this switches event
 * collection off with `setTelemetry(false)` (ADR 0007 decision 2).
 *
 * Sessions get exactly the locked options of ADR 0012 decision 4: ONNX format only, released opsets only, graph
 * optimisation "all", session and run logging `fatal`, intra-op pool 1 thread with spinning off, and the XNNPACK
 * execution provider with [xnnpackThreads] workers (nodes XNNPACK does not take run on the CPU provider). Every option
 * is required: one that cannot be set fails with [RuntimeOptionFailed] (creation then throws `RuntimeUnavailable`).
 * The runtime's own exceptions (`OrtException`, and the `IllegalStateException` of `getEnvironment`) become typed
 * failures without a cause (their text can contain paths). Any other throwable (a native load error, for example)
 * passes unchanged after the native objects made so far are closed; the common callers map it without chaining
 * (`RuntimeUnavailable` at creation, `RUN_FAILED` for a pass). `RuntimeOptionFailed` from an option call cannot be
 * provoked with the shipped runtime (it accepts every locked option), so that mapping has no test.
 *
 * Phone-only (ONNX Runtime's Android natives do not load on the host): this file holds only the runtime calls; the
 * timed pass, the input buffer, the conversions and the close-after-failure rule are in `SessionRules.kt`
 * (host-tested).
 */
internal object OrtSessions {
    /** The execution provider every session uses. */
    const val EXECUTION_PROVIDER: String = "XNNPACK"

    /** The XNNPACK provider option that sets its worker pool. */
    private const val XNNPACK_THREADS_OPTION = "intra_op_num_threads"

    @HiddenFromJava
    fun open(buffer: ByteBuffer, threads: Int): OrtModelSession {
        val env =
            try {
                OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL, "faceblur")
            } catch (e: IllegalStateException) {
                throw RuntimeOptionFailed()
            }
        val applied = ArrayList<String>()
        val options =
            try {
                env.setTelemetry(false)
                applied += "telemetry=false"
                lockedOptions(threads, applied)
            } catch (e: OrtException) {
                throw RuntimeOptionFailed()
            }
        val session =
            try {
                options.use { env.createSession(buffer, it) }
            } catch (e: OrtException) {
                throw SessionRejected()
            }
        return try {
            OrtModelSession.create(env, session, applied)
        } catch (e: OrtException) {
            // Reading the runtime's view of the inputs and outputs failed: the session is not leaked.
            closeAfterFailure { session.close() }
            throw SessionRejected()
        } catch (e: Throwable) {
            closeAfterFailure { session.close() }
            throw e
        }
    }

    /** The locked options; [applied] records each one as it is set (for the configuration check of ST-003 AC2). */
    private fun lockedOptions(threads: Int, applied: MutableList<String>): OrtSession.SessionOptions {
        val o = OrtSession.SessionOptions()
        try {
            o.addConfigEntry("session.load_model_format", "ONNX")
            applied += "session.load_model_format=ONNX"
            o.addConfigEntry("session.allow_released_opsets_only", "1")
            applied += "session.allow_released_opsets_only=1"
            o.addConfigEntry("session.intra_op.allow_spinning", "0")
            applied += "session.intra_op.allow_spinning=0"
            o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            applied += "optimization=ALL_OPT"
            o.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
            applied += "session_log_level=FATAL"
            o.setIntraOpNumThreads(1)
            applied += "intra_op_threads=1"
            val workers = xnnpackThreads(threads)
            o.addXnnpack(mapOf(XNNPACK_THREADS_OPTION to workers.toString()))
            applied += "xnnpack.$XNNPACK_THREADS_OPTION=$workers"
            return o
        } catch (e: Throwable) {
            o.close()
            throw e
        }
    }
}

/**
 * One ONNX Runtime session. Not thread-safe: the `FaceMasker` makes at most one call at a time. Made only by
 * [OrtSessions.open] (private constructor, Java-hidden factory).
 */
internal class OrtModelSession private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    /** The settings applied when the session was created, in order (`name=value`); for tests and reports only. */
    val appliedOptions: List<String>,
) : ModelSession {
    override val runtimeVersion: String = env.version
    override val executionProvider: String = OrtSessions.EXECUTION_PROVIDER
    override val inputs: List<SessionTensor> = describe(session.inputInfo)
    override val outputs: List<SessionTensor> = describe(session.outputInfo)

    /** The single model input (the contract check has compared the runtime's view with the model file). */
    private val inputName: String = session.inputInfo.keys.firstOrNull() ?: ""

    private val inputData = InputBuffer()

    override fun run(
        input: FloatArray,
        inputShape: LongArray,
        outputNames: List<String>,
        maxElements: Long,
        timeoutMillis: Long,
    ): Map<String, PassOutput> {
        val runOptions =
            try {
                OrtSession.RunOptions()
            } catch (e: OrtException) {
                throw SessionRunFailed()
            }
        return timedPass(timeoutMillis, { runOptions.setTerminate(true) }, { runOptions.close() }) {
            try {
                runOptions.setLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
                OnnxTensor.createTensor(env, inputData.fill(input), inputShape).use { tensor ->
                    session.run(mapOf(inputName to tensor), outputNames.toSet(), runOptions).use {
                        collect(it, maxElements)
                    }
                }
            } catch (e: OrtException) {
                null
            }
        }
    }

    private fun collect(result: OrtSession.Result, maxElements: Long): Map<String, PassOutput> {
        val out = LinkedHashMap<String, PassOutput>()
        for (entry in result) {
            val v = entry.value as? OnnxTensor ?: continue
            val info = v.info
            out[entry.key] =
                passOutput(
                    info.onnxType.name,
                    info.shape,
                    info.type == OnnxJavaType.FLOAT,
                    info.numElements,
                    maxElements,
                ) {
                    val fb = v.floatBuffer
                    FloatArray(fb.remaining()).also { fb.get(it) }
                }
        }
        return out
    }

    override fun close() = session.close()

    internal companion object {
        @HiddenFromJava
        fun create(env: OrtEnvironment, session: OrtSession, applied: List<String>): OrtModelSession =
            OrtModelSession(env, session, applied)

        private fun describe(info: Map<String, NodeInfo>): List<SessionTensor> = info.values.map { node ->
            val t = node.info as? TensorInfo
            sessionTensor(node.name, t?.onnxType?.name, t?.shape)
        }
    }
}
