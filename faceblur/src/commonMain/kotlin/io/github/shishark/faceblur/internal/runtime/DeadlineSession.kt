// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.shishark.faceblur.internal.runtime

import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.isFatal
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

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
 * The process-wide count of abandoned runs: runs and session creations ([DeadlineSession.open]) that passed their
 * limit while still inside the runtime. Each one keeps a thread and its session's memory until it returns. At [MAX] no
 * new run and no new session creation starts anywhere in the process (fail closed) until one of them returns.
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
 * A pass started with [DeadlineSession.start] and not yet ended. The caller ends it with exactly one of [await] or
 * [abandon].
 */
internal interface StartedRun {
    /** Waits for the pass until its deadline and returns its outputs, with the rules of [DeadlineSession.run]. */
    fun await(): Map<String, PassOutput>

    /**
     * Gives the pass up without reading anything of it (fail closed): the session is retired and its runtime session
     * is closed when the pass has ended (at once if it has ended or never began).
     */
    fun abandon()
}

/**
 * A session whose every run has a hard limit. [run] hands the pass to a pass thread ([threads]) and waits at
 * most the run's `timeoutMillis` (0 = no limit); a run still going then is abandoned and the caller gets
 * [SessionTimeout] at once, whether or not the runtime ever returns. [start] hands the pass over and returns at once,
 * so that a caller can prepare the pass of another session meanwhile; its [StartedRun.await] waits until the same
 * deadline, counted from the start (a pass that had ended within its limit before the wait began is in time).
 *
 * After any timeout (the deadline passed, the [inner] session's own [SessionTimeout], or a run that ended only after
 * the deadline) this session is RETIRED: it never runs again ([SessionTimeout] at once), no output of the timed-out
 * run is ever read, and [inner] is closed: at once if its run has ended, otherwise by the pass thread if that run ever
 * returns (its output is then discarded). [close] of a retired session touches nothing of [inner]. The owner replaces
 * a retired session by a newly opened one ([retired]). Every other way a run can end on the caller's side without
 * its outcome (the start or the wait threw, the wait woke before the run ended, or the caller abandoned the pass)
 * retires the session the same way (fail closed): a late signal of that run is never read, and no later run shares
 * [inner] with it. A pass abandoned before its task began never runs [inner]: the caller closes it at once.
 *
 * While [AbandonedRuns] is at its maximum no run is started ([SessionTimeout], the session stays usable). Only a pass
 * whose task is inside [inner] counts there.
 *
 * The `FaceMasker` creates every session through [open], which bounds the creation the same way.
 *
 * One caller at a time (the `FaceMasker` rule) and at most one started pass: [inner] is used by one thread at a time,
 * the pass thread during a run and the caller otherwise; the signal and the atomic state order the hand-over.
 */
internal class DeadlineSession(
    private val inner: ModelSession,
    private val threads: PassThreads = platformPassThreads(),
    /** One signal per session: a retired session never waits again, so a late signal is never read. */
    private val signal: PassSignal = threads.newSignal(),
) : ModelSession {
    override val runtimeVersion: String get() = inner.runtimeVersion
    override val executionProvider: String get() = inner.executionProvider
    override val inputs: List<SessionTensor> get() = inner.inputs
    override val outputs: List<SessionTensor> get() = inner.outputs

    /** True after a timeout or a failed hand-over: this session never runs again and must be replaced. */
    var retired: Boolean = false
        private set

    private var closed = false

    /** The pass started and not yet awaited or abandoned (at most one). */
    private var inFlight: Pass? = null

    override fun run(
        input: FloatArray,
        inputShape: LongArray,
        outputNames: List<String>,
        maxElements: Long,
        timeoutMillis: Long,
    ): Map<String, PassOutput> = start(input, inputShape, outputNames, maxElements, timeoutMillis).await()

    /**
     * Hands one pass to a pass thread and returns at once; the pass's deadline is [timeoutMillis] after this call
     * (0 = no limit). Throws [SessionTimeout] when this session is retired or closed or [AbandonedRuns] is at its
     * maximum (nothing started), and rethrows a failed hand-over after retiring the session. A start while another
     * pass of this session is in flight abandons that pass and throws (fail closed; never expected).
     */
    fun start(
        input: FloatArray,
        inputShape: LongArray,
        outputNames: List<String>,
        maxElements: Long,
        timeoutMillis: Long,
    ): StartedRun {
        if (retired || closed) throw SessionTimeout()
        inFlight?.let {
            inFlight = null
            retireAfter(it)
            throw IllegalStateException("a pass of this session is still in flight")
        }
        if (AbandonedRuns.count() >= AbandonedRuns.MAX) throw SessionTimeout()
        val pass = Pass(timeoutMillis)
        try {
            threads.start {
                // A pass abandoned before its task began never runs the session (the caller has closed it).
                if (pass.state.compareAndSet(CREATED, STARTED)) {
                    val outcome: Any =
                        try {
                            inner.run(input, inputShape, outputNames, maxElements, timeoutMillis)
                        } catch (e: Throwable) {
                            Failed(e)
                        }
                    pass.tookMillis.store(pass.mark.elapsedNow().inWholeMilliseconds)
                    // If abandoned, nobody reads the outcome and only this thread holds the session: it closes it.
                    finish(pass, outcome, signal) { inner.close() }
                }
            }
        } catch (e: Throwable) {
            retireAfter(pass)
            throw e
        }
        inFlight = pass
        return Started(pass)
    }

    private inner class Started(private val pass: Pass) : StartedRun {
        override fun await(): Map<String, PassOutput> {
            if (inFlight !== pass) throw IllegalStateException("this pass has ended already")
            inFlight = null
            val ended =
                try {
                    waitFor(pass)
                } catch (e: Throwable) {
                    // Fail closed: the run may still be going and its signal may come later; this session never waits
                    // again, so that signal is never read and no later run can share the session with this one.
                    retireAfter(pass)
                    throw e
                }
            if (!ended) {
                // The deadline passed (or the run ended just at it or after it): a timeout either way.
                retireAfter(pass)
                throw SessionTimeout()
            }
            if (pass.state.load() != DONE) {
                // Woken although the run has not ended: never expected; fail closed as on a timeout.
                retireAfter(pass)
                throw IllegalStateException("the pass signal came before its run ended")
            }
            val outcome = pass.outcome.load()
            if (outcome is Failed) {
                if (outcome.error is SessionTimeout) retireAndClose()
                throw outcome.error
            }
            @Suppress("UNCHECKED_CAST")
            return outcome as Map<String, PassOutput>
        }

        override fun abandon() {
            if (inFlight !== pass) return
            inFlight = null
            retireAfter(pass)
        }
    }

    /**
     * Waits for the signal of [pass] until its deadline; true if the run ended in time. When the deadline passed
     * before this wait began (the caller was busy with another session's pass), the run is in time only if it had
     * ended within its limit; its signal, sent right after it ended, is then taken at once.
     */
    private fun waitFor(pass: Pass): Boolean {
        val limit = pass.timeoutMillis
        if (limit <= 0L) return signal.await(0L)
        val left = limit - pass.mark.elapsedNow().inWholeMilliseconds
        if (left > 0L) return signal.await(left)
        val took = pass.tookMillis.load()
        return pass.state.load() == DONE && took in 0L..limit && signal.await(0L)
    }

    /**
     * Retires this session after its [pass] did not end normally: the pass is abandoned to its thread, which closes
     * [inner] if its run ever returns, or, if the run has ended already or never began, [inner] is closed now.
     */
    private fun retireAfter(pass: Pass) {
        retired = true
        if (!abandon(pass)) closeQuietly(inner)
    }

    private fun retireAndClose() {
        retired = true
        closeQuietly(inner)
    }

    /**
     * Closes [inner] unless this session is retired (then it is closed already or owned by its pass thread). A pass
     * still in flight is abandoned first (fail closed; the owner ends every pass before it closes a session).
     */
    override fun close() {
        if (closed) return
        closed = true
        inFlight?.let {
            inFlight = null
            retireAfter(it)
            return
        }
        if (!retired) inner.close()
    }

    /**
     * One pass or creation: its state ([CREATED] -> [STARTED] by its task, -> [DONE] by its task, or abandoned by the
     * caller: [CREATED] -> [ABANDONED_BEFORE_START], [STARTED] -> [ABANDONED]), outcome, start time and, for a run,
     * the time it took ([tookMillis], set by its task before [DONE]).
     */
    private class Pass(val timeoutMillis: Long = 0L) {
        val state = AtomicInt(CREATED)
        val outcome = AtomicReference<Any?>(null)
        val mark = TimeSource.Monotonic.markNow()
        val tookMillis = AtomicLong(-1L)
    }

    /** A failure of the inner run, rethrown on the caller's thread. */
    private class Failed(val error: Throwable)

    companion object {
        /** Handed over; its task has not begun. */
        private const val CREATED = 0

        /** Its task has begun and is inside the runtime (or about to be). */
        private const val STARTED = 4

        private const val DONE = 1

        /** Abandoned by the caller while its task was in the runtime; counted in [AbandonedRuns] until it returns. */
        private const val ABANDONED = 2

        /** Abandoned before its task began: the task never runs it, nothing is counted, the caller cleans up. */
        private const val ABANDONED_BEFORE_START = 3

        /**
         * Creates a session with [open] on a pass thread of [threads] and waits at most [timeoutMillis] for it. A
         * creation still going then is abandoned and [SessionTimeout] is thrown at once, whether or not the runtime
         * ever returns: the pass thread owns it, and if it ever returns a session, that session is closed there and
         * never used. A creation that ended only after the deadline is a timeout as well (its session is closed at
         * once). An abandoned creation counts in [AbandonedRuns] until it returns; at its maximum no creation starts
         * ([SessionTimeout]). A failure of [open] within the limit is rethrown unchanged. Any other failure on the
         * caller's side (the start or the wait threw) abandons the creation the same way and is rethrown: a session it
         * returns later is closed and never used; a creation abandoned before its task began never calls [open].
         */
        fun open(
            timeoutMillis: Long,
            threads: PassThreads = platformPassThreads(),
            open: () -> ModelSession,
        ): DeadlineSession {
            if (AbandonedRuns.count() >= AbandonedRuns.MAX) throw SessionTimeout()
            val pass = Pass()
            val signal = threads.newSignal()
            try {
                threads.start {
                    // A creation abandoned before its task began never runs.
                    if (pass.state.compareAndSet(CREATED, STARTED)) {
                        val outcome: Any =
                            try {
                                open()
                            } catch (e: Throwable) {
                                Failed(e)
                            }
                        // If abandoned, nobody waits for this session any more: it is closed here and never used.
                        finish(pass, outcome, signal) { (outcome as? ModelSession)?.close() }
                    }
                }
            } catch (e: Throwable) {
                abandonCreation(pass)
                throw e
            }
            val ended =
                try {
                    signal.await(timeoutMillis)
                } catch (e: Throwable) {
                    abandonCreation(pass)
                    throw e
                }
            if (!ended) {
                // The deadline passed (or the creation ended just at it): a timeout either way.
                abandonCreation(pass)
                throw SessionTimeout()
            }
            if (pass.state.load() != DONE) {
                abandonCreation(pass)
                throw IllegalStateException("the creation signal came before the creation ended")
            }
            val outcome = pass.outcome.load()
            if (outcome is Failed) throw outcome.error
            val session = outcome as ModelSession
            try {
                // The signal was consumed by this wait, so the session can keep it for its runs.
                return DeadlineSession(session, threads, signal)
            } catch (e: Throwable) {
                closeQuietly(session)
                throw e
            }
        }

        /**
         * Abandons a creation that did not end normally: its pass thread closes a session it returns later, or, if it
         * has ended already, its session is closed now (a creation that never began has none).
         */
        private fun abandonCreation(pass: Pass) {
            if (!abandon(pass)) (pass.outcome.load() as? ModelSession)?.let { closeQuietly(it) }
        }

        /**
         * The caller gives up [pass]: true if its task is inside the runtime (now abandoned: its thread owns the
         * session, discards the outcome and keeps one slot of [AbandonedRuns] until it returns), false if it had ended
         * or never began (the caller cleans up; nothing stays counted). The count is raised before the state changes,
         * so the pass thread, which gives the slot back only after it saw [ABANDONED], never takes it below zero.
         */
        private fun abandon(pass: Pass): Boolean {
            while (true) {
                when (pass.state.load()) {
                    CREATED -> if (pass.state.compareAndSet(CREATED, ABANDONED_BEFORE_START)) return false

                    STARTED -> {
                        AbandonedRuns.add(1)
                        if (pass.state.compareAndSet(STARTED, ABANDONED)) return true
                        AbandonedRuns.add(-1)
                    }

                    else -> return false
                }
            }
        }

        /**
         * The end of a pass on its thread: hands [outcome] to the waiting caller, or, if the caller abandoned the pass,
         * drops it, runs [discard] and gives back the pass's slot in [AbandonedRuns].
         */
        private fun finish(pass: Pass, outcome: Any?, signal: PassSignal, discard: () -> Unit) {
            pass.outcome.store(outcome)
            if (pass.state.compareAndSet(STARTED, DONE)) {
                signal.signal()
                return
            }
            pass.outcome.store(null)
            try {
                discard()
            } catch (e: Throwable) {
                // Nobody to report to on a pass thread; an escaping error would end the process.
            } finally {
                if (pass.state.load() == ABANDONED) AbandonedRuns.add(-1)
            }
        }

        /** A close after a timeout: its failure is not reported (the timeout is), except the JVM's fatal errors. */
        private fun closeQuietly(session: ModelSession) {
            try {
                session.close()
            } catch (e: Throwable) {
                if (isFatal(e)) throw e
            }
        }
    }
}
