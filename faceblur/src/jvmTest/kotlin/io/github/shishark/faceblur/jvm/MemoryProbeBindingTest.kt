// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.mask.HeapRemainingProbe
import io.github.shishark.faceblur.mask.MemoryRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * The binding-level probe check (ADR 0013 decision 6; the pattern every platform binding repeats with its own rule):
 * after the JVM binding's registration the mask stage's memory probe is installed, with the remaining-heap rule and
 * the JVM's own heap figures. Without it the stage would have no pre-check, only the count and the catch.
 */
class MemoryProbeBindingTest {
    @Test
    fun theJvmBindingInstallsTheRemainingHeapProbe() {
        val previous = FaceBlurRuntime.memoryProbe()
        FaceBlurRuntime.installMemoryProbe(null)
        try {
            JvmTestRuntime.install()
            val probe = assertNotNull(FaceBlurRuntime.memoryProbe(), "a probe is installed")
            assertSame(HeapRemainingProbe, probe, "the JVM binding installs its probe with its runtime")
            assertEquals(MemoryRule.HEAP_REMAINING, probe.rule)
            assertEquals(Runtime.getRuntime().maxMemory(), probe.figure())
        } finally {
            FaceBlurRuntime.installMemoryProbe(previous)
        }
    }
}
