// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.shishark.faceblur.internal.runtime

import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.isFatal
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A one-way completion signal with a timed wait. [signal] may be called from any thread, also after nobody waits any
 * more (the signal is then simply never read).
 */
internal interface PassSignal {
    fun signal()

    /** Waits until [signal] was called, at most [timeoutMillis] (0 = no limit); true if it was. */
    fun await(timeoutMillis: Long): Boolean
}

/** The platform's threads for model runs: [start] never runs [task] on the calling thread. */
internal interface PassThreads {
    /** Starts [task] on a pass thread; a pass thread that never returns delays no other pass. */
    fun start(task: () -> Unit)

    fun newSignal(): PassSignal
}

/**
 * The platform's pass threads: JVM and Android a process-wide cached pool of daemon threads, iOS the global dispatch
 * queue of the caller's quality-of-service class.
 */
internal expect fun platformPassThreads(): PassThreads

/**
 * The process-wide count of abandoned runs: runs that passed their limit while still inside the runtime.
 * Each one keeps a thread and its session's memory until it returns. At [MAX] no new run starts anywhere in the
 * process (fail closed) until one of them returns.
 */
internal object AbandonedRuns {
    /** The most abandoned runs before every new run is refused. */
    const val MAX: Int = 4

    private val count = AtomicInt(0)

    /** The runs abandoned and not yet returned. */
    @HiddenFromJava
    fun count(): Int = count.load()

    @HiddenFromJava
    internal fun add(delta: Int) {
        count.addAndFetch(delta)
    }
}

/**
 * A session whose every run has a hard limit. [run] hands the pass to a pass thread ([threads]) and waits at
 * most the run's `timeoutMillis` (0 = no limit); a run still going then is abandoned and the caller gets
 * [SessionTimeout] at once, whether or not the runtime ever returns.
 *
 * After any timeout (the deadline passed, the [inner] session's own [SessionTimeout], or a run that ended only after
 * the deadline) this session is RETIRED: it never runs again ([SessionTimeout] at once), no output of the timed-out
 * run is ever read, and [inner] is closed: at once if its run has ended, otherwise by the pass thread if that run ever
 * returns (its output is then discarded). [close] of a retired session touches nothing of [inner]. The owner replaces
 * a retired session by a newly opened one ([retired]).
 *
 * While [AbandonedRuns] is at its maximum no run is started ([SessionTimeout], the session stays usable).
 *
 * One caller at a time (the `FaceMasker` rule): [inner] is used by one thread at a time, the pass thread during a run
 * and the caller otherwise; the signal and the atomic state order the hand-over.
 */
internal class DeadlineSession(
    private val inner: ModelSession,
    private val threads: PassThreads = platformPassThreads(),
) : ModelSession {
    override val runtimeVersion: String get() = inner.runtimeVersion
    override val executionProvider: String get() = inner.executionProvider
    override val inputs: List<SessionTensor> get() = inner.inputs
    override val outputs: List<SessionTensor> get() = inner.outputs

    /** One signal per session: a retired session never waits again, so a late signal is never read. */
    private val signal = threads.newSignal()

    /** True after a timeout: this session never runs again and must be replaced. */
    var retired: Boolean = false
        private set

    private var closed = false

    override fun run(
        input: FloatArray,
        inputShape: LongArray,
        outputNames: List<String>,
        maxElements: Long,
        timeoutMillis: Long,
    ): Map<String, PassOutput> {
        if (retired || closed) throw SessionTimeout()
        if (AbandonedRuns.count() >= AbandonedRuns.MAX) throw SessionTimeout()
        val pass = Pass()
        threads.start {
            val outcome: Any =
                try {
                    inner.run(input, inputShape, outputNames, maxElements, timeoutMillis)
                } catch (e: Throwable) {
                    Failed(e)
                }
            pass.outcome.store(outcome)
            if (!pass.state.compareAndSet(RUNNING, DONE)) {
                // Abandoned: nobody reads this outcome, and nobody but this thread holds the session any more.
                pass.outcome.store(null)
                try {
                    inner.close()
                } catch (e: Throwable) {
                    // Nobody to report to on a pass thread; an escaping error would end the process.
                } finally {
                    AbandonedRuns.add(-1)
                }
            } else {
                signal.signal()
            }
        }
        if (!signal.await(timeoutMillis)) {
            AbandonedRuns.add(1)
            if (pass.state.compareAndSet(RUNNING, ABANDONED)) {
                retired = true
                throw SessionTimeout()
            }
            // The run ended just at the deadline: a timeout all the same, and the session is free to close now.
            AbandonedRuns.add(-1)
            retireAndClose()
            throw SessionTimeout()
        }
        val outcome = pass.outcome.load()
        if (outcome is Failed) {
            if (outcome.error is SessionTimeout) retireAndClose()
            throw outcome.error
        }
        @Suppress("UNCHECKED_CAST")
        return outcome as Map<String, PassOutput>
    }

    private fun retireAndClose() {
        retired = true
        closeQuietly(inner)
    }

    /** Closes [inner] unless this session is retired (then it is closed already or owned by its pass thread). */
    override fun close() {
        if (closed) return
        closed = true
        if (!retired) inner.close()
    }

    /** One pass: its state ([RUNNING] -> [DONE] by the pass thread, or -> [ABANDONED] by the caller) and outcome. */
    private class Pass {
        val state = AtomicInt(RUNNING)
        val outcome = AtomicReference<Any?>(null)
    }

    /** A failure of the inner run, rethrown on the caller's thread. */
    private class Failed(val error: Throwable)

    private companion object {
        const val RUNNING = 0
        const val DONE = 1
        const val ABANDONED = 2

        /** A close after a timeout: its failure is not reported (the timeout is), except the JVM's fatal errors. */
        fun closeQuietly(session: ModelSession) {
            try {
                session.close()
            } catch (e: Throwable) {
                if (isFatal(e)) throw e
            }
        }
    }
}
