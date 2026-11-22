// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.Release
import io.github.shishark.faceblur.RunLimits
import io.github.shishark.faceblur.TIMEOUT_MARGIN_MILLIS
import io.github.shishark.faceblur.assertNoAbandonedRunsLeft
import io.github.shishark.faceblur.detect.Limits
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.AbandonedRuns
import io.github.shishark.faceblur.internal.runtime.platformPassThreads
import io.github.shishark.faceblur.mask.ReservedBytes
import io.github.shishark.faceblur.millisOf
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGImageRelease
import platform.Foundation.NSFileManager
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The hard run limit on the simulator, through the real iOS session ([IosModelRuntime], its output table and buffers) and the
 * iOS pass threads: a glue whose run blocks without CPU (in native code, on a dispatch semaphore) and never returns
 * while the test checks the library. The call ends at its limit with `RUN_TIMEOUT`, the stuck glue is never closed
 * or used under its running pass, the next call opens a new glue and gives the output of a normal run, file mode
 * leaves no file, and a blocked contract run fails creation with `TIMEOUT`. When the stuck run returns at last, its
 * pass thread closes the glue (and only then frees its buffers).
 */
class IosHardRunLimitTest {
    private val releases = ArrayList<Release>()

    /** The release of the glues made from now on (every round of a test gets its own). */
    private var release = newRelease()

    private fun newRelease() = Release().also { releases += it }

    private val dirs = ArrayList<String>()

    /** A glue over [inner] whose run number [blockFrom] and every later one first wait on [release]. */
    private class BlockingBridge(
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
            // A late return still writes its outputs: into the abandoned session's own buffers, read by nobody.
            return inner.run(inputAddress, inputByteCount, outputTableAddress)
        }

        override fun close() {
            closeCount.addAndFetch(1)
            inner.close()
            closedSignal.signal()
        }

        fun awaitClosed(millis: Long): Boolean = closedSignal.await(millis)
    }

    private val bridges = ArrayList<BlockingBridge>()

    /** The first glue blocks from run [firstBlocksFrom] (1 = the contract run); later glues never block. */
    private fun masker(firstBlocksFrom: Int, limits: RunLimits = RunLimits.DEFAULT): FaceMasker = FaceMasker.forTest(
        testYuNetModel(),
        FaceMaskerConfig(),
        IosModelRuntime(
            OnnxBridgeFactory {
                val from = if (bridges.isEmpty()) firstBlocksFrom else Int.MAX_VALUE
                BlockingBridge(FakeBridge(OneFaceRunner()), release, from).also { bridges += it }
            },
        ),
        runLimits = limits,
    )

    @AfterTest
    fun cleanUp() {
        releases.forEach { it.open() }
        assertNoAbandonedRunsLeft()
        dirs.forEach { IosFiles.removeDir(it) }
        assertEquals(0L, ReservedBytes.current(), "no bytes left reserved")
    }

    @Test
    fun aGlueRunThatNeverReturnsEndsTheCallAtItsLimitAndTheNextCallGetsANewGlue() {
        val masker = masker(firstBlocksFrom = 2)
        val img = IntArrayPixels(640, 480, gradientPixels(640, 480))
        val before = img.data.copyOf()
        val took = millisOf {
            val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
            assertEquals(listOf("RUN_TIMEOUT"), e.findings)
        }
        assertTrue(took >= Limits.MIN_RUN_TIMEOUT_MILLIS, "the production pass limit applies: $took ms")
        assertTrue(took <= Limits.MIN_RUN_TIMEOUT_MILLIS + TIMEOUT_MARGIN_MILLIS, "ended at the limit: $took ms")
        assertContentEquals(before, img.data, "nothing was written into the image")
        assertEquals(0, bridges[0].closes, "the stuck glue is not closed under its running pass")
        assertEquals(1, AbandonedRuns.count())

        val result = masker.maskInPlace(img)
        assertEquals(2, bridges.size, "the next call opened a new glue")
        assertEquals(2, bridges[0].runs, "the stuck glue never ran again")
        val reference = IntArrayPixels(640, 480, gradientPixels(640, 480))
        val expected = IosFiles.masker().use { it.maskInPlace(reference) }
        assertEquals(expected.masks, result.masks)
        assertContentEquals(reference.data, img.data, "the same output as a normal run")

        release.open()
        assertTrue(bridges[0].awaitClosed(10_000L), "the abandoned glue is closed when its run returns")
        assertNoAbandonedRunsLeft()
        masker.close()
        assertEquals(1, bridges[1].closes)
        assertEquals(1, bridges[0].closes)
    }

    @Test
    fun aContractRunThatNeverReturnsFailsCreationWithTimeoutWithinItsLimit() {
        val limits = RunLimits(contractMillis = 1_000L, minPassMillis = 300L)
        val took = millisOf {
            val e = assertFailsWith<FaceMaskException.ModelRejected> { masker(firstBlocksFrom = 1, limits = limits) }
            assertEquals(listOf("TIMEOUT"), e.findings)
        }
        assertTrue(took <= limits.contractMillis + TIMEOUT_MARGIN_MILLIS, "ended at the limit: $took ms")
        assertEquals(0, bridges[0].closes, "the stuck glue is not closed under its running pass")
        release.open()
        assertTrue(bridges[0].awaitClosed(10_000L))
        assertNoAbandonedRunsLeft()
    }

    @Test
    fun fileModeLeavesNoFileOnATimeoutAndTheNextCallWritesTheNormalOutput() {
        val d = IosFiles.newDir().also { dirs += it }
        IosFiles.store("$d/in.jpg", jpeg())
        val input = IosFiles.read("$d/in.jpg")
        val limits = RunLimits(contractMillis = 1_000L, minPassMillis = 300L)
        IosFiles.masker().use { it.maskFile("$d/in.jpg", OutputTarget.NewFile("$d/normal.jpg")) }
        val normal = IosFiles.read("$d/normal.jpg")
        IosFiles.removeDir("$d/normal.jpg")
        for (target in listOf(OutputTarget.NewFile("$d/out.jpg"), OutputTarget.OverwriteSource)) {
            release = newRelease()
            bridges.clear()
            val masker = masker(firstBlocksFrom = 2, limits = limits)
            val before = IosFiles.snapshot()
            val took = millisOf {
                val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskFile("$d/in.jpg", target) }
                assertEquals(listOf("RUN_TIMEOUT"), e.findings)
            }
            assertTrue(took <= limits.minPassMillis + TIMEOUT_MARGIN_MILLIS, "ended at the limit: $took ms")
            assertEquals(emptyList(), IosFiles.diff(before, IosFiles.snapshot()), "nothing written anywhere")
            assertEquals(listOf("in.jpg"), names(d), "no output and no temporary file")
            assertContentEquals(input, IosFiles.read("$d/in.jpg"), "the source is unchanged")

            // The next call on the same masker (a new glue) writes the same file as a normal masker.
            masker.use { it.maskFile("$d/in.jpg", OutputTarget.NewFile("$d/next.jpg")) }
            assertEquals(2, bridges.size, "a new glue was opened")
            assertContentEquals(normal, IosFiles.read("$d/next.jpg"), "the same output as a normal run")
            IosFiles.removeDir("$d/next.jpg")
            assertEquals(listOf("in.jpg"), names(d))
            release.open()
            assertTrue(bridges[0].awaitClosed(10_000L))
            assertNoAbandonedRunsLeft()
        }
    }

    private fun jpeg(w: Int = 900, h: Int = 700): ByteArray {
        val img = IosFiles.imageOf(w, h, gradientPixels(w, h))
        val props = IosFiles.cannedProperties(1)
        try {
            return IosFiles.encode(img, "public.jpeg", props, xmp = false)
        } finally {
            platform.CoreFoundation.CFRelease(props)
            CGImageRelease(img)
        }
    }

    private fun names(d: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        val all = NSFileManager.defaultManager.contentsOfDirectoryAtPath(d, null) as List<String>
        return all.sorted()
    }
}
