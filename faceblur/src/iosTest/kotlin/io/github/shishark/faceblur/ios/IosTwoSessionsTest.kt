// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.Release
import io.github.shishark.faceblur.RunLimits
import io.github.shishark.faceblur.TIMEOUT_MARGIN_MILLIS
import io.github.shishark.faceblur.assertNoAbandonedRunsLeft
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.AbandonedRuns
import io.github.shishark.faceblur.internal.runtime.platformPassThreads
import io.github.shishark.faceblur.mask.ReservedBytes
import io.github.shishark.faceblur.millisOf
import io.github.shishark.faceblur.pause
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Two passes at a time on the simulator, through the real iOS session ([IosModelRuntime], one glue per session, its
 * output table and buffers) and the library's own iOS pass threads: the two glues run at the same time. A glue run
 * that never returns, or a glue fault, in either session ends the call closed (`RUN_TIMEOUT` at the limit, or
 * `RUN_FAILED`), nothing of either glue is used again (the next call opens two new glues), the result of the next
 * call is that of a normal masker, and a stuck glue is closed by its own pass thread when its run returns.
 */
class IosTwoSessionsTest {
    private val release = Release()

    /** A glue over [inner] whose run number [blockFrom] and every later one first wait on [release]. */
    private class BlockingGlue(
        private val inner: FakeBridge,
        private val release: Release,
        private val blockFrom: Int,
    ) : OnnxBridge by inner {
        private val runCount = AtomicInt(0)
        private val closeCount = AtomicInt(0)
        private val closedSignal = platformPassThreads().newSignal()

        val runs: Int get() = runCount.load()
        val closes: Int get() = closeCount.load()

        override fun run(inputAddress: Long, inputByteCount: Long, outputTableAddress: Long): Int {
            if (runCount.addAndFetch(1) >= blockFrom) release.await()
            return inner.run(inputAddress, inputByteCount, outputTableAddress)
        }

        override fun close() {
            closeCount.addAndFetch(1)
            inner.close()
            closedSignal.signal()
        }

        fun awaitClosed(millis: Long): Boolean = closedSignal.await(millis)
    }

    /** The glues made so far, in order (made on pass threads). */
    private val made = AtomicReference<List<BlockingGlue>>(emptyList())
    private val glues: List<BlockingGlue> get() = made.load()

    private val short = RunLimits(contractMillis = 1_000L, minPassMillis = 300L, openMillis = 500L)

    /** Glue i blocks from run [blockFrom] (i) and fails with [fault] from run [faultFrom] (i). */
    private fun masker(
        blockFrom: (Int) -> Int = { NEVER },
        fault: Fault = Fault.NONE,
        faultFrom: (Int) -> Int = { NEVER },
    ): FaceMasker = FaceMasker.forTest(
        testYuNetModel(),
        FaceMaskerConfig(),
        IosModelRuntime(
            OnnxBridgeFactory {
                val i = glues.size
                val glue = BlockingGlue(FakeBridge(OneFaceRunner(), fault, faultFrom(i)), release, blockFrom(i))
                while (true) {
                    val before = made.load()
                    if (made.compareAndSet(before, before + glue)) break
                }
                glue
            },
        ),
        runLimits = short,
    )

    /** 1280 x 960: the global pass and 6 tiles, so both glues run. */
    private fun image() = IntArrayPixels(1280, 960, gradientPixels(1280, 960))

    @AfterTest
    fun cleanUp() {
        release.open()
        assertNoAbandonedRunsLeft()
        assertEquals(0L, ReservedBytes.current(), "no bytes left reserved")
    }

    private fun normalOutput(): IntArray {
        val img = image()
        IosFiles.masker().use { it.maskInPlace(img) }
        return img.data
    }

    private fun aStuckGlueRunEndsTheCallAtItsLimit(stuck: Int) {
        val masker = masker(blockFrom = { i -> if (i == stuck) (if (stuck == 0) 3 else 2) else NEVER })
        val img = image()
        val before = img.data.copyOf()
        val took = millisOf {
            val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
            assertEquals(listOf("RUN_TIMEOUT"), e.findings)
        }
        assertTrue(took >= short.minPassMillis && took <= short.minPassMillis + TIMEOUT_MARGIN_MILLIS, "$took ms")
        assertContentEquals(before, img.data, "nothing was written into the image")
        assertEquals(2, glues.size, "both glues were in use")
        assertTrue(glues[1 - stuck].awaitClosed(10_000L), "the other glue is closed, not kept")
        var waited = 0L
        while (AbandonedRuns.count() != 1 && waited < 10_000L) {
            pause(5L)
            waited += 5L
        }
        assertEquals(1, AbandonedRuns.count(), "the stuck run")
        assertEquals(0, glues[stuck].closes, "the stuck glue is not closed under its running pass")
        val runs = glues.map { it.runs }

        masker.maskInPlace(img)
        assertEquals(4, glues.size, "the next call opened two new glues")
        assertEquals(runs, glues.take(2).map { it.runs }, "neither glue of the failed call ran again")
        assertContentEquals(normalOutput(), img.data, "the same output as a normal run")

        release.open()
        assertTrue(glues[stuck].awaitClosed(10_000L), "the stuck glue is closed when its run returns")
        assertNoAbandonedRunsLeft()
        masker.close()
        assertEquals(listOf(1, 1, 1, 1), glues.map { it.closes })
    }

    @Test
    fun aGlueRunThatNeverReturnsInTheFirstSessionEndsTheCallAtItsLimit() = aStuckGlueRunEndsTheCallAtItsLimit(0)

    @Test
    fun aGlueRunThatNeverReturnsInTheSecondSessionEndsTheCallAtItsLimit() = aStuckGlueRunEndsTheCallAtItsLimit(1)

    private fun aGlueFaultFailsTheCallClosed(failing: Int) {
        val masker = masker(fault = Fault.STATUS_2, faultFrom = { i ->
            if (i ==
                failing
            ) {
                (if (failing == 0) 4 else 3)
            } else {
                NEVER
            }
        })
        val img = image()
        val before = img.data.copyOf()
        val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
        assertEquals(listOf("RUN_FAILED"), e.findings)
        assertContentEquals(before, img.data)
        assertTrue(glues.all { it.awaitClosed(10_000L) }, "both glues are closed, not kept")
        assertNoAbandonedRunsLeft()
        val runs = glues.map { it.runs }

        masker.maskInPlace(img)
        assertEquals(4, glues.size, "the next call opened two new glues")
        assertEquals(runs, glues.take(2).map { it.runs })
        assertContentEquals(normalOutput(), img.data)
        masker.close()
        assertEquals(listOf(1, 1, 1, 1), glues.map { it.closes })
    }

    @Test
    fun aGlueFaultInTheFirstSessionFailsTheCallClosed() = aGlueFaultFailsTheCallClosed(0)

    @Test
    fun aGlueFaultInTheSecondSessionFailsTheCallClosed() = aGlueFaultFailsTheCallClosed(1)

    @Test
    fun bothGluesGiveTheOutputOfOneGlue() {
        val img = image()
        FaceMasker.forTest(
            testYuNetModel(),
            FaceMaskerConfig(onnxThreads = 1),
            IosModelRuntime(FakeFactory { FakeBridge(OneFaceRunner()) }),
        ).use { it.maskInPlace(img) }
        val m = masker()
        val two = image()
        m.maskInPlace(two)
        assertEquals(2, glues.size)
        assertTrue(glues.all { it.runs > 1 }, "both glues ran passes")
        assertContentEquals(img.data, two.data)
        m.close()
    }

    private companion object {
        const val NEVER = Int.MAX_VALUE
    }
}
