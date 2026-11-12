// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.internal.model.ModelLoader
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.RuntimeOptionFailed
import io.github.shishark.faceblur.internal.runtime.SessionRejected
import io.github.shishark.faceblur.internal.runtime.SessionRunFailed
import io.github.shishark.faceblur.internal.runtime.SessionTensor
import io.github.shishark.faceblur.internal.runtime.SessionTimeout
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ONNX Runtime (JVM) binding for tests and tools; the JVM variant is not published (ADR 0002 decision 11). It follows
 * the Android/JVM rules of ADR 0012:
 * - the session is created only from the model's verified bytes, held once per model in a direct buffer
 *   ([DetectorModel.platformForm]); each session gets its own `duplicate()`;
 * - exactly the locked session options of ADR 0012 decision 4 (ONNX format, released opsets only, all graph
 *   optimisations, `fatal` logging, XNNPACK with CPU fallback, intra-op pool 1 thread, spinning off); an option that
 *   cannot be set fails with [RuntimeOptionFailed]. The desktop runtime package has no XNNPACK provider, so the CPU
 *   provider is used, with `threads` intra-op threads when set;
 * - inputs and outputs are reported from the runtime; a pass that runs longer than its timeout is stopped with
 *   `RunOptions.setTerminate` and reported as [SessionTimeout];
 * - runtime exceptions never leave: they become typed failures without a cause.
 * Telemetry is switched off here and by the ORT_DISABLE_TELEMETRY environment variable that the build sets.
 */
internal class OrtModelRuntime(
    /** Value of ORT_DISABLE_TELEMETRY; a parameter only so that the guard can be tested. */
    private val telemetryVariable: String? = System.getenv("ORT_DISABLE_TELEMETRY"),
) : ModelRuntime {
    var sessionsOpened = 0
        private set

    override fun open(model: DetectorModel, threads: Int): ModelSession {
        // ONNX Runtime may send its start-up event as soon as the environment exists, before setTelemetry(false)
        // can act; only ORT_DISABLE_TELEMETRY=1, set before the process starts, prevents it.
        check(telemetryVariable == "1") { "set ORT_DISABLE_TELEMETRY=1 before starting the JVM" }
        val env = OrtEnvironment.getEnvironment()
        val options =
            try {
                env.setTelemetry(false)
                lockedOptions(threads)
            } catch (e: OrtException) {
                throw RuntimeOptionFailed()
            }
        val buffer = model.platformForm { bytes -> ByteBuffer.allocateDirect(bytes.size).put(bytes).flip() }
        val session =
            try {
                options.use { env.createSession(buffer.duplicate(), it) }
            } catch (e: OrtException) {
                throw SessionRejected()
            }
        sessionsOpened++
        return try {
            OrtModelSession(env, session)
        } catch (e: OrtException) {
            // Reading the runtime's view of the inputs and outputs failed: the session is not leaked.
            session.close()
            throw SessionRejected()
        }
    }

    private fun lockedOptions(threads: Int): OrtSession.SessionOptions {
        val o = OrtSession.SessionOptions()
        o.addConfigEntry("session.load_model_format", "ONNX")
        o.addConfigEntry("session.allow_released_opsets_only", "1")
        o.addConfigEntry("session.intra_op.allow_spinning", "0")
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        o.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
        if (threads > 0) o.setIntraOpNumThreads(threads)
        return o
    }
}

private class OrtModelSession(private val env: OrtEnvironment, private val session: OrtSession) : ModelSession {
    override val runtimeVersion: String = env.version
    override val executionProvider: String = "CPU"
    override val inputs: List<SessionTensor> = describe(session.inputInfo)
    override val outputs: List<SessionTensor> = describe(session.outputInfo)
    private val inputName: String = session.inputNames.first()

    override fun run(
        input: FloatArray,
        inputShape: LongArray,
        outputNames: List<String>,
        maxElements: Long,
        timeoutMillis: Long,
    ): Map<String, PassOutput> {
        val stopped = AtomicBoolean(false)
        // The timer's setTerminate and RunOptions.close never overlap: ORT's RunOptions is not thread-safe, and a
        // terminate on a closed handle would reach freed native memory. Both run under [guard]; after close the
        // timer does nothing.
        val guard = Any()
        var released = false
        val runOptions =
            try {
                OrtSession.RunOptions()
            } catch (e: OrtException) {
                throw SessionRunFailed()
            }
        try {
            runOptions.setLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
            val timer =
                if (timeoutMillis > 0) {
                    TIMER.schedule(
                        {
                            synchronized(guard) {
                                if (!released) {
                                    stopped.set(true)
                                    runOptions.setTerminate(true)
                                }
                            }
                        },
                        timeoutMillis,
                        TimeUnit.MILLISECONDS,
                    )
                } else {
                    null
                }
            try {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(input), inputShape).use { tensor ->
                    session.run(mapOf(inputName to tensor), outputNames.toSet(), runOptions).use { result ->
                        val out = LinkedHashMap<String, PassOutput>()
                        for (entry in result) {
                            val v = entry.value as? OnnxTensor ?: continue
                            val info = v.info
                            val shape = info.shape
                            val elements = info.numElements
                            val isFloat = info.type == ai.onnxruntime.OnnxJavaType.FLOAT
                            val data =
                                if (isFloat && elements in 0..maxElements) {
                                    val fb = v.floatBuffer
                                    FloatArray(fb.remaining()).also { fb.get(it) }
                                } else {
                                    null
                                }
                            out[entry.key] = PassOutput(onnxCode(info.onnxType), shape, data)
                        }
                        return out
                    }
                }
            } finally {
                timer?.cancel(false)
            }
        } catch (e: OrtException) {
            if (stopped.get()) throw SessionTimeout()
            throw SessionRunFailed()
        } finally {
            synchronized(guard) {
                released = true
                runOptions.close()
            }
        }
    }

    override fun close() = session.close()

    private companion object {
        val TIMER: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "faceblur-test-run-timeout").apply {
                    isDaemon =
                        true
                }
            }

        /**
         * The ONNX `TensorProto.DataType` code of a runtime element type (the Java enum's own numbers differ from
         * ONNX's, so the enum is matched by name).
         */
        fun onnxCode(t: TensorInfo.OnnxTensorType): Int {
            val name = t.name.removePrefix("ONNX_TENSOR_ELEMENT_DATA_TYPE_")
            return (0..32).firstOrNull { ModelLoader.elementTypeName(it) == name } ?: -1
        }

        fun describe(info: Map<String, NodeInfo>): List<SessionTensor> = info.values.map { node ->
            val t = node.info as? TensorInfo
            SessionTensor(
                node.name,
                t?.onnxType?.let { onnxCode(it) },
                t?.shape?.map { if (it < 0) null else it },
            )
        }
    }
}

/**
 * Installs the JVM test runtime, the JVM model-file reader and the JVM memory probe of the mask stage (what the
 * platform modules do in production).
 */
internal object JvmTestRuntime {
    fun install() {
        FaceBlurRuntime.install(OrtModelRuntime())
        FaceBlurRuntime.installModelFiles(JvmModelFileReader)
        FaceBlurRuntime.installMemoryProbe(io.github.shishark.faceblur.mask.HeapRemainingProbe)
    }
}
