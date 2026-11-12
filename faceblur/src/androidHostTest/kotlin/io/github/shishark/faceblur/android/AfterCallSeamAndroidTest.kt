// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.platformDefaults
import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.MemoryRule
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * ADR 0004 decision 4 on Android: the after-call seam is iOS-only. The Android defaults have none, so `FaceMasker`
 * never runs one, and public calls never request a garbage collection through the memory probe (alternative F,
 * rejected because it would change the Android artifact's behaviour).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AfterCallSeamAndroidTest {
    private class CountingProbe : MemoryProbe {
        var collects = 0
        override val rule: MemoryRule get() = MemoryRule.HEAP_REMAINING

        override fun figure(): Long = Long.MAX_VALUE / 4

        override fun collect() {
            collects++
        }
    }

    @Before
    @After
    fun reset() {
        FaceBlurRuntime.installMemoryProbe(null)
        FaceBlurRuntime.overrideDefaults(null)
    }

    @Test
    fun androidHasNoAfterCallSeam() {
        assertNull(platformDefaults().afterCall, "the Android defaults have no after-call seam")
        assertNull(FaceBlurRuntime.afterCall(), "so FaceMasker has nothing to run after a call")
    }

    @Test
    fun publicCallsOnAndroidNeverRequestACollection() {
        val probe = CountingProbe()
        FaceBlurRuntime.installMemoryProbe(probe)
        val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
        masker.use {
            val image = IntArrayPixels(640, 480, gradientPixels(640, 480))
            assertEquals(1, it.maskInPlace(image).masks.size)
            it.detect(image)
            assertFailsWith<FaceMaskException.ImageTooLarge> { it.detect(IntArrayPixels(70_000, 1)) }
        }
        assertEquals(0, probe.collects, "no collection after successful or failed calls")
        assertNull(FaceBlurRuntime.afterCall())
    }
}
