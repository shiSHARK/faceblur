// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.HiddenFromJava
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** How a platform decides whether a working area may be reserved (ADR 0013 decision 6). */
internal enum class MemoryRule {
    /**
     * JVM and Android: the check is the contract. remaining = heap maximum − heap in use − the bytes other running
     * calls have reserved (negative counts as 0); the working area must fit into it. A working area above the heap
     * maximum is refused at once; otherwise, if it does not fit, one garbage collection is requested and the
     * remaining heap computed again before the call is refused. An allocation that still fails (another thread of
     * the app allocated meanwhile) is caught only as a last-resort backstop ([reserveWorkingArea]).
     */
    HEAP_REMAINING,

    /**
     * iOS device (`iosArm64`): the working area plus [MaskStageLimits.AVAILABLE_MEMORY_MARGIN] must fit into the
     * memory the system reports as still available (`os_proc_available_memory()`), minus the bytes other calls have
     * reserved; one garbage collection before refusing. A figure of 0 ("not an app" or "already over the limit") is
     * refused.
     */
    AVAILABLE_ON_DEVICE,

    /** iOS simulator (`iosSimulatorArm64`): as [AVAILABLE_ON_DEVICE], but a figure of 0 skips the check. */
    AVAILABLE_ON_SIMULATOR,
}

/**
 * The platform's memory figures for [rule]. Installed by the platform binding through
 * `FaceBlurRuntime.installMemoryProbe` (JVM: `HeapRemainingProbe`; Android: the same rule and `java.lang.Runtime`
 * calls, installed by the Android binding; iOS: the available figure, installed by the iOS binding). On the JVM and
 * Android no other code of the stage reads `Runtime`. Tests install probes that report chosen values.
 */
internal interface MemoryProbe {
    val rule: MemoryRule

    /** [MemoryRule.HEAP_REMAINING]: the heap maximum (`maxMemory()`); the iOS rules: the memory still available. */
    fun figure(): Long

    /** [MemoryRule.HEAP_REMAINING]: the heap in use now (`totalMemory() − freeMemory()`); 0 for the iOS rules. */
    fun inUse(): Long = 0L

    /** One garbage collection request (`Runtime.gc()`, iOS `GC.collect()`) before the figures are read again. */
    fun collect() {}
}

/**
 * The process-wide count of the bytes of working areas that have been admitted but are not yet visible in the
 * platform's figures (ADR 0013 decision 6, all platforms). One primitive atomic value, initialised with the object
 * before the first check. After the touch the bytes show as used (heap in use, iOS footprint), so the call removes
 * them from the count then, or in a `finally` on every path that ends before. Every member is synthetic on the JVM
 * and Android ([HiddenFromJava]), so Java code cannot change the count or admit bytes under its own probe.
 */
internal object ReservedBytes {
    private val count = AtomicLong(0L)

    /** The bytes currently counted (tests: 0 after every call). */
    @HiddenFromJava
    fun current(): Long = count.load()

    /**
     * Admits [bytes] of a working area under [probe]'s rule and adds them to the count, or throws
     * [CoreFailure.NotEnoughMemory] (no cause) without changing the count and without allocating. [held]: bytes of
     * the same call already in the count (its tile tables); with the heap rule, a call whose [bytes] plus [held] are
     * above the heap maximum is refused at once. Without a probe the bytes are only counted. The caller removes the
     * bytes with [release].
     */
    @HiddenFromJava
    fun admit(bytes: Long, probe: MemoryProbe?, held: Long = 0L) {
        when (probe?.rule) {
            null -> count.addAndFetch(bytes)
            MemoryRule.HEAP_REMAINING -> admitHeap(bytes, held, probe)
            MemoryRule.AVAILABLE_ON_DEVICE, MemoryRule.AVAILABLE_ON_SIMULATOR -> admitAvailable(bytes, probe)
        }
    }

    /** Removes [bytes] that [admit] added. */
    @HiddenFromJava
    fun release(bytes: Long) {
        count.addAndFetch(-bytes)
    }

    private fun admitHeap(bytes: Long, held: Long, probe: MemoryProbe) {
        if (checkedAdd(bytes, held) > probe.figure()) throw CoreFailure.NotEnoughMemory(null)
        var collected = false
        while (true) {
            val reserved = count.load()
            if (remainingHeap(probe.figure(), probe.inUse(), reserved) >= bytes) {
                // Two checks never both use the same free memory: the count changes only through this CAS.
                if (count.compareAndSet(reserved, reserved + bytes)) return
                continue
            }
            if (collected) throw CoreFailure.NotEnoughMemory(null)
            probe.collect()
            collected = true
        }
    }

    private fun admitAvailable(bytes: Long, probe: MemoryProbe) {
        val need = checkedAdd(bytes, MaskStageLimits.AVAILABLE_MEMORY_MARGIN)
        var collected = false
        while (true) {
            val figure = probe.figure()
            if (figure <= 0L) {
                if (probe.rule == MemoryRule.AVAILABLE_ON_DEVICE) throw CoreFailure.NotEnoughMemory(null)
                count.addAndFetch(bytes)
                return
            }
            val reserved = count.load()
            if (figure - reserved >= need) {
                if (count.compareAndSet(reserved, reserved + bytes)) return
                continue
            }
            if (collected) throw CoreFailure.NotEnoughMemory(null)
            probe.collect()
            collected = true
        }
    }
}

/**
 * maximum − in use − reserved, without overflow (each value is at least 0, the maximum may be `Long.MAX_VALUE`);
 * a negative result counts as 0.
 */
internal fun remainingHeap(maximum: Long, inUse: Long, reserved: Long): Long {
    val m = maximum.coerceAtLeast(0L)
    val used = inUse.coerceAtLeast(0L)
    if (used >= m) return 0L
    val free = m - used
    val others = reserved.coerceAtLeast(0L)
    return if (others >= free) 0L else free - others
}

/** 64-bit addition that refuses an overflow as [CoreFailure.NotEnoughMemory] (the size cannot be reserved). */
internal fun checkedAdd(a: Long, b: Long): Long {
    val r = a + b
    if (((a xor r) and (b xor r)) < 0L) throw CoreFailure.NotEnoughMemory(null)
    return r
}

/** 64-bit multiplication of non-negative values that refuses an overflow as [CoreFailure.NotEnoughMemory]. */
internal fun checkedMul(a: Long, b: Long): Long {
    if (a < 0L || b < 0L) throw CoreFailure.NotEnoughMemory(null)
    if (a == 0L || b == 0L) return 0L
    if (a > Long.MAX_VALUE / b) throw CoreFailure.NotEnoughMemory(null)
    return a * b
}
