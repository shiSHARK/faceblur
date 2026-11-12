// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

/**
 * The JVM memory probe of the mask stage ([MemoryRule.HEAP_REMAINING]): the heap maximum, the heap in use and one
 * garbage-collection request, from `java.lang.Runtime`; the common check computes the remaining heap and subtracts
 * the bytes other running calls have reserved. It belongs to the JVM binding, which lives with the tests and tools
 * (the JVM target is not published); `JvmTestRuntime` installs it. The Android binding installs a probe with the same
 * three calls (`java.lang.Runtime` is the same API there).
 */
internal object HeapRemainingProbe : MemoryProbe {
    override val rule: MemoryRule get() = MemoryRule.HEAP_REMAINING

    override fun figure(): Long = Runtime.getRuntime().maxMemory()

    override fun inUse(): Long {
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    override fun collect() {
        Runtime.getRuntime().gc()
    }
}
