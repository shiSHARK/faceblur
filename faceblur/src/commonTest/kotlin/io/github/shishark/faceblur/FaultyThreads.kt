// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.internal.runtime.PassSignal
import io.github.shishark.faceblur.internal.runtime.PassThreads
import io.github.shishark.faceblur.internal.runtime.platformPassThreads

/** Thrown by a [FaultyThreads] start set to fail. */
internal class StartFailed : Error("test: the start of a pass failed")

/** Thrown by a [FaultyThreads] wait set to fail. */
internal class WaitFailed : Error("test: the wait for a pass failed")

/**
 * Pass threads over the platform's whose next start and next wait can be made to fail or to race, so that the
 * hand-over of a pass can be tested on every exit. Each setting applies to the next start or wait only, then the
 * normal behaviour returns. Set and used on the caller's thread.
 */
internal class FaultyThreads : PassThreads {
    enum class Start {
        /** The platform's pass thread. */
        NORMAL,

        /** The task runs to its end on the calling thread before [start] returns (so it ends before the wait). */
        INLINE,

        /** [start] throws [StartFailed] and the task never runs. */
        FAIL,

        /**
         * The task is handed to a pass thread, where it waits for [releaseHandedOver], and [start] then throws
         * [StartFailed] (a failure after the hand-over).
         */
        FAIL_AFTER_HAND_OVER,
    }

    enum class Wait {
        /** The platform's wait. */
        NORMAL,

        /** The wait throws [waitError] at once. */
        THROW,

        /** The wait reports a missed deadline at once. */
        MISS,

        /** The wait reports the signal at once, although none came (a stray wake-up). */
        WAKE,
    }

    private val base = platformPassThreads()
    private val handOver = Release()

    /** What the next [start] does. */
    var start: Start = Start.NORMAL

    /** What the next wait on a signal of these threads does. */
    var wait: Wait = Wait.NORMAL

    /** The error a [Wait.THROW] wait throws. */
    var waitError: () -> Throwable = { WaitFailed() }

    /** Runs before the next [Wait.THROW], [Wait.MISS] or [Wait.WAKE] wait acts (for example: until the run blocks). */
    var beforeWait: () -> Unit = {}

    /** Lets the tasks of [Start.FAIL_AFTER_HAND_OVER] starts go on. */
    fun releaseHandedOver() = handOver.open()

    override fun start(task: () -> Unit) {
        val mode = start
        start = Start.NORMAL
        when (mode) {
            Start.NORMAL -> base.start(task)

            Start.INLINE -> task()

            Start.FAIL -> throw StartFailed()

            Start.FAIL_AFTER_HAND_OVER -> {
                base.start {
                    handOver.await()
                    task()
                }
                throw StartFailed()
            }
        }
    }

    override fun newSignal(): PassSignal = object : PassSignal {
        private val signal = base.newSignal()

        override fun signal() = signal.signal()

        override fun await(timeoutMillis: Long): Boolean {
            val mode = wait
            wait = Wait.NORMAL
            if (mode != Wait.NORMAL) {
                val before = beforeWait
                beforeWait = {}
                before()
            }
            return when (mode) {
                Wait.NORMAL -> signal.await(timeoutMillis)
                Wait.THROW -> throw waitError()
                Wait.MISS -> false
                Wait.WAKE -> true
            }
        }
    }
}
