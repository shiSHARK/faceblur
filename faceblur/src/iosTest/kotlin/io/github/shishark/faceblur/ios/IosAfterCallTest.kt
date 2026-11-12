// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.AfterCallSeam
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.internal.runtime.platformDefaults
import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.MemoryRule
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * ADR 0004 decision 4 on iOS: the after-call seam is `GC.collect()` by default and runs once after each public
 * `FaceMasker` call that did work, on success and on a thrown error; a call refused before any work (closed masker)
 * does not run it. It is not the memory probe's `collect()`.
 */
class IosAfterCallTest {
    private class Counter : AfterCallSeam {
        var calls = 0

        override fun afterCall() {
            calls++
        }
    }

    private class CountingProbe : MemoryProbe {
        var collects = 0
        override val rule: MemoryRule get() = MemoryRule.AVAILABLE_ON_SIMULATOR

        override fun figure(): Long = Long.MAX_VALUE / 4

        override fun collect() {
            collects++
        }
    }

    @BeforeTest
    @AfterTest
    fun reset() {
        FaceBlurRuntime.overrideDefaults(null)
    }

    @Test
    fun theIosDefaultIsTheGarbageCollectionSeam() {
        assertSame(IosAfterCall, platformDefaults().afterCall)
        assertSame(IosAfterCall, FaceBlurRuntime.afterCall())
        IosAfterCall.afterCall() // GC.collect() returns normally
    }

    @Test
    fun theSeamRunsOnceAfterEachPublicCallThatDidWork() {
        val seam = Counter()
        val probe = CountingProbe()
        FaceBlurRuntime.overrideDefaults(PlatformDefaults(memoryProbe = probe, afterCall = seam))
        val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
        val image = IntArrayPixels(640, 480, gradientPixels(640, 480))
        assertEquals(0, seam.calls, "creation is not a call on the masker")
        val result = masker.maskInPlace(image)
        assertEquals(1, result.masks.size)
        assertEquals(1, seam.calls, "after a successful maskInPlace")
        masker.detect(image)
        assertEquals(2, seam.calls, "after a successful detect")
        assertFailsWith<FaceMaskException.ImageTooLarge> { masker.detect(IntArrayPixels(70_000, 1)) }
        assertEquals(3, seam.calls, "after a call that threw")
        masker.close()
        assertFailsWith<FaceMaskException.Closed> { masker.detect(image) }
        assertEquals(3, seam.calls, "a call refused before any work does not run it")
        assertEquals(0, probe.collects, "the memory probe's collect() is not the seam")
    }
}
