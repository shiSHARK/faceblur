// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import java.nio.ByteBuffer
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The Android ONNX Runtime binding (ADR 0012 decisions 4 and 8, ADR 0007 decision 2). This class loads no ONNX Runtime
 * class: it first sets `ORT_DISABLE_TELEMETRY=1` (failing closed if that is impossible), then makes the model's
 * direct buffer once per model ([ModelBuffer]) and hands one `duplicate()` per session to [OrtSessions], the only
 * code that touches the runtime.
 */
internal object AndroidModelRuntime : ModelRuntime {
    override fun open(model: DetectorModel, threads: Int): ModelSession =
        open(model, threads) { buffer, workers -> OrtSessions.open(buffer, workers) }

    /** [open] with the session factory as a parameter (production: [OrtSessions]; host tests: a recorder). */
    @HiddenFromJava
    internal fun open(model: DetectorModel, threads: Int, sessions: (ByteBuffer, Int) -> ModelSession): ModelSession {
        AndroidBinding.disableOrtTelemetry()
        val buffer: ByteBuffer = model.platformForm { bytes -> ModelBuffer.of(bytes) }
        return sessions(buffer.duplicate(), threads)
    }
}

/**
 * The thread plan of the benchmark-proven defaults (ADR 0012 decision 4): ONNX Runtime's own intra-op pool has 1 thread with
 * spinning off, and XNNPACK owns the worker pool: [configured] threads, or for 0 (automatic) the smaller of 4 and the
 * processor count (at least 1).
 */
internal fun xnnpackThreads(configured: Int, processors: Int = processorCount()): Int =
    if (configured > 0) configured else minOf(MAX_AUTOMATIC_THREADS, processors).coerceAtLeast(1)

private const val MAX_AUTOMATIC_THREADS = 4

/**
 * Stops one run after [timeoutMillis] (0 = no limit) by calling [terminate] on the timer thread. The terminate call
 * and the release of the run's options never overlap and a terminate never follows the release (ONNX Runtime's run
 * options are not thread-safe, and a terminate on released options would reach freed native memory): both happen
 * under one lock, and after [finish] the timer does nothing.
 */
internal class RunTimer private constructor(timeoutMillis: Long, private val terminate: () -> Unit) {
    private val lock = Any()
    private var released = false

    /** True once the limit was reached (whether or not the runtime then stopped the run). */
    @Volatile
    var fired: Boolean = false
        private set

    private val pending =
        if (timeoutMillis > 0) {
            Timers.scheduler.schedule({ fire() }, timeoutMillis, TimeUnit.MILLISECONDS)
        } else {
            null
        }

    private fun fire() {
        synchronized(lock) {
            if (released) return
            fired = true
            terminate()
        }
    }

    /** Cancels the timer and runs [release] under the same lock as the terminate call. Call exactly once. */
    @HiddenFromJava
    fun finish(release: () -> Unit) {
        pending?.cancel(false)
        synchronized(lock) {
            released = true
            release()
        }
    }

    companion object {
        /** Starts a timer of [timeoutMillis] (0 = no limit) that calls [terminate] at the limit. */
        @HiddenFromJava
        fun start(timeoutMillis: Long, terminate: () -> Unit): RunTimer = RunTimer(timeoutMillis, terminate)
    }

    private object Timers {
        /** One daemon thread for all run timers of the process; cancelled timers leave the queue at once. */
        val scheduler: ScheduledThreadPoolExecutor by lazy {
            ScheduledThreadPoolExecutor(1) { r -> Thread(r, "faceblur-run-timeout").apply { isDaemon = true } }
                .apply { removeOnCancelPolicy = true }
        }
    }
}
