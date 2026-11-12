// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.MemoryRule

/**
 * The Android memory probe of the mask stage ([MemoryRule.HEAP_REMAINING], ADR 0013 decision 6): the heap
 * maximum, the heap in use and one garbage-collection request, from `java.lang.Runtime`. The common check computes
 * the remaining heap and subtracts the bytes other running calls have reserved; it never relies on catching an
 * out-of-memory error. Bitmap pixels live outside the Java heap on Android, so this rule covers exactly the mask
 * stage's own working area (Kotlin arrays).
 */
internal object AndroidHeapProbe : MemoryProbe {
    override val rule: MemoryRule get() = MemoryRule.HEAP_REMAINING

    override fun figure(): Long = Runtime.getRuntime().maxMemory()

    override fun inUse(): Long = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    override fun collect() = Runtime.getRuntime().gc()
}

/** The processor count for the default thread count: the only other reviewed `Runtime` line (SAST gate). */
internal fun processorCount(): Int = Runtime.getRuntime().availableProcessors()
