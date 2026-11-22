// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.shishark.faceblur.internal.runtime

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.convert
import kotlinx.cinterop.staticCFunction
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSEC_PER_MSEC
import platform.darwin.dispatch_async_f
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.qos_class_self

/** iOS: the pass threads of [IosPassThreads]. */
internal actual fun platformPassThreads(): PassThreads = IosPassThreads

/**
 * iOS pass threads: each pass runs on the global dispatch queue of the caller's quality-of-service class,
 * started with a C function and a [StableRef] (no block object per pass). A pass stuck in the bridge is in native
 * code, so the garbage collector never waits for it; dispatch gives the next pass another thread. The number of
 * stuck threads is bounded by [AbandonedRuns].
 */
internal object IosPassThreads : PassThreads {
    override fun start(task: () -> Unit) {
        val ref = StableRef.create(task)
        val queue = dispatch_get_global_queue(qos_class_self().convert(), 0u)
        dispatch_async_f(queue, ref.asCPointer(), staticCFunction { context: COpaquePointer? -> runPass(context) })
    }

    /** One dispatch semaphore per session (not per call). */
    override fun newSignal(): PassSignal = DispatchSignal()
}

private fun runPass(context: COpaquePointer?) {
    val ref = context?.asStableRef<() -> Unit>() ?: return
    val task = ref.get()
    ref.dispose()
    // Global queues drain no autorelease pool per work item: objects the glue autoreleases (bridged strings) are
    // freed here, at the end of each pass.
    autoreleasepool {
        try {
            task()
        } catch (e: Throwable) {
            // The task reports its own failures; an exception escaping a dispatch thread would end the process.
        }
    }
}

private class DispatchSignal : PassSignal {
    private val semaphore = dispatch_semaphore_create(0)

    override fun signal() {
        dispatch_semaphore_signal(semaphore)
    }

    override fun await(timeoutMillis: Long): Boolean {
        val time =
            if (timeoutMillis <= 0L) {
                DISPATCH_TIME_FOREVER
            } else {
                dispatch_time(DISPATCH_TIME_NOW, timeoutMillis * NSEC_PER_MSEC.convert<Long>())
            }
        return dispatch_semaphore_wait(semaphore, time) == 0L
    }
}
