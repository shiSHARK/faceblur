// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.internal.runtime

import io.github.shishark.faceblur.HiddenFromJava
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** JVM: the pass threads of [JvmPassThreads] (the same code as the Android target). */
@HiddenFromJava
internal actual fun platformPassThreads(): PassThreads = JvmPassThreads

/**
 * One process-wide cached pool of daemon threads `faceblur-pass`: no core threads, an idle thread ends after
 * 30 s, and the hand-off queue gives every pass an idle or a new thread, so a thread stuck in the runtime delays no
 * other pass. The number of stuck threads is bounded by [AbandonedRuns].
 */
internal object JvmPassThreads : PassThreads {
    private val pool: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(0, Int.MAX_VALUE, IDLE_SECONDS, TimeUnit.SECONDS, SynchronousQueue()) { r ->
            Thread(r, "faceblur-pass").apply { isDaemon = true }
        }
    }

    override fun start(task: () -> Unit) {
        pool.execute {
            try {
                task()
            } catch (e: Throwable) {
                // The task reports its own failures; nothing may end the process from a pass thread.
            }
        }
    }

    override fun newSignal(): PassSignal = SemaphoreSignal()

    private const val IDLE_SECONDS = 30L
}

/**
 * A [PassSignal] on a [Semaphore]. The wait does not react to interrupts (a native run never did): it waits until
 * the signal or the deadline and then restores the caller's interrupt flag.
 */
private class SemaphoreSignal : PassSignal {
    private val semaphore = Semaphore(0)

    override fun signal() = semaphore.release()

    override fun await(timeoutMillis: Long): Boolean {
        if (timeoutMillis <= 0L) {
            semaphore.acquireUninterruptibly()
            return true
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var interrupted = false
        try {
            while (true) {
                val left = deadline - System.nanoTime()
                try {
                    return semaphore.tryAcquire(left.coerceAtLeast(0L), TimeUnit.NANOSECONDS)
                } catch (e: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
