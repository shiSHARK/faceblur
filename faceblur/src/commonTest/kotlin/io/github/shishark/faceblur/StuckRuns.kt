// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.FakeSession
import io.github.shishark.faceblur.internal.runtime.AbandonedRuns
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.platformPassThreads
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * Test support for the hard run limit: runs that block without using CPU (a wait on a platform signal) and never return while the
 * test checks the library; [open] lets them return only in the test's cleanup.
 */
internal class Release {
    private val gate = platformPassThreads().newSignal()

    /**
     * Blocks the calling (pass) thread until [open]. A safety stop after [SAFETY_STOP_MILLIS] (far beyond every limit
     * under test) makes a regression fail its timing checks instead of hanging the test run.
     */
    fun await() {
        gate.await(SAFETY_STOP_MILLIS)
    }

    /** Lets every blocked run (and every later one) return. */
    fun open() {
        repeat(MAX_WAITERS) { gate.signal() }
    }

    private companion object {
        const val MAX_WAITERS = 64
        const val SAFETY_STOP_MILLIS = 60_000L
    }
}

/**
 * A session over [base] whose run number [blockFrom] and every later one (1 = the contract run at creation) first
 * wait on [release] without CPU, then return [base]'s output.
 */
internal class BlockingSession(
    private val base: ModelSession,
    private val release: Release,
    private val blockFrom: Int,
) : ModelSession by base {
    private val runCount = AtomicInt(0)
    private val closeCount = AtomicInt(0)
    private val closedSignal = platformPassThreads().newSignal()

    private val lastBlockedLimit = AtomicLong(-1L)

    /** The limit the last blocked run was given (-1: none blocked). */
    val blockedLimit: Long get() = lastBlockedLimit.load()

    val runs: Int get() = runCount.load()
    val closes: Int get() = closeCount.load()

    override fun run(
        input: FloatArray,
        inputShape: LongArray,
        outputNames: List<String>,
        maxElements: Long,
        timeoutMillis: Long,
    ): Map<String, PassOutput> {
        if (runCount.addAndFetch(1) >= blockFrom) {
            lastBlockedLimit.store(timeoutMillis)
            release.await()
        }
        return base.run(input, inputShape, outputNames, maxElements, timeoutMillis)
    }

    override fun close() {
        closeCount.addAndFetch(1)
        base.close()
        closedSignal.signal()
    }

    /** True if [close] was called within [millis]. */
    fun awaitClosed(millis: Long): Boolean = closedSignal.await(millis)
}

/**
 * A runtime over the one-face runner whose session creation number i (0-based) first waits on [release] without CPU
 * when [blockOpen] (i) is true and then returns its session all the same; session i blocks from run [blockFrom] (i).
 * Creations run on pass threads, so every session is recorded (thread-safe) before its creation blocks.
 */
internal class BlockingRuntime(
    private val release: Release,
    private val blockOpen: (Int) -> Boolean,
    private val blockFrom: (Int) -> Int = { Int.MAX_VALUE },
) : ModelRuntime {
    private val opened = AtomicReference<List<BlockingSession>>(emptyList())
    private val openCount = AtomicInt(0)

    /** The sessions created or being created, in order. */
    val sessions: List<BlockingSession> get() = opened.load()

    /** The creations started. */
    val opens: Int get() = openCount.load()

    override fun open(model: DetectorModel, threads: Int): ModelSession {
        val i = openCount.addAndFetch(1) - 1
        val session = BlockingSession(FakeSession(OneFaceRunner(), model), release, blockFrom(i))
        while (true) {
            val before = opened.load()
            if (opened.compareAndSet(before, before + session)) break
        }
        if (blockOpen(i)) release.await()
        return session
    }
}

/** Waits [millis] without CPU. */
internal fun pause(millis: Long) {
    platformPassThreads().newSignal().await(millis)
}

/** Milliseconds [block] took. */
internal inline fun millisOf(block: () -> Unit): Long {
    val mark = TimeSource.Monotonic.markNow()
    block()
    return mark.elapsedNow().inWholeMilliseconds
}

/** Waits up to 10 s until no abandoned run is left in the process, then asserts it. */
internal fun assertNoAbandonedRunsLeft() {
    var waited = 0L
    while (AbandonedRuns.count() != 0 && waited < 10_000L) {
        pause(10L)
        waited += 10L
    }
    assertEquals(0, AbandonedRuns.count(), "every abandoned run returned and released its slot")
}

/** The stated margin of the hard run limit: a call ends at most this long after its run limit. */
internal const val TIMEOUT_MARGIN_MILLIS: Long = 1_000L
