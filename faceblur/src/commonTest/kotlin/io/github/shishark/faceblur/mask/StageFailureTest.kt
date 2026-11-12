// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ADR 0013 decision 4: every failure before the final copy leaves the image untouched (zero writes, same checksum),
 * with the typed error stated there; cancellation is read during the computation, within every 65,536 units of
 * work and right before the copy; the copy is never interrupted.
 */
class StageFailureTest {
    private class Stop : RuntimeException("stop")

    /** A cancellation check that throws [Stop] from its [from]-th call on. */
    private class Flip(private val from: Int) {
        var calls = 0
        val check: () -> Unit = { if (++calls >= from) throw Stop() }
    }

    private fun wholeMask(w: Int, h: Int) =
        listOf(MaskGeometry(w / 2f, h / 2f, w * 0.48f, h * 0.48f, MaskShape.ELLIPSE))

    @Test
    fun cancellationInsideOneLargeMaskIsReadWithinEvery65536UnitsAndLeavesTheImageUntouched() {
        val w = 1_200
        val h = 900
        for (style in MaskStyle.entries) {
            val masks = wholeMask(w, h)
            // Uncancelled: the gaps between consecutive reads, from step 1 to the read right before the copy.
            val full = StepRecorder()
            MaskEngine.apply(
                RecordingImage(stageImage(w, h, 1)),
                masks,
                settings(style),
                seams = StageSeams(observer = full),
            )
            val reads = full.reads
            assertTrue(reads.size >= 10, "$style: many reads inside one mask (${reads.size})")
            for (k in 1 until reads.size) {
                assertTrue(
                    reads[k] - reads[k - 1] <= 65_536,
                    "$style: read ${k + 1} came ${reads[k] - reads[k - 1]} units after the one before",
                )
            }
            assertTrue(reads.first() <= 65_536, "$style: the first read is within 65,536 units")
            assertEquals(full.unitsAt(3), reads.last(), "$style: the last read is right before the copy")
            // Cancelled after k reads, at several points inside the mask: Cancelled at the next read, untouched.
            for (k in listOf(1, reads.size / 3, reads.size - 1)) {
                val image = RecordingImage(stageImage(w, h, 1))
                val before = checksum(image.base.data)
                val steps = StepRecorder()
                val flip = Flip(k + 1)
                assertFailsWith<Stop>("$style k=$k") {
                    MaskEngine.apply(image, masks, settings(style), flip.check, StageSeams(observer = steps))
                }
                assertEquals(0, image.writes, "$style k=$k: no write")
                assertEquals(before, checksum(image.base.data))
                assertTrue(steps.reads[k] - steps.reads[k - 1] <= 65_536, "$style k=$k: read within 65,536 units")
                assertTrue(steps.steps.none { it.first == 3 }, "$style k=$k: the copy never started")
            }
        }
    }

    @Test
    fun with40000SmallMasksCancellationIsReadBetweenEveryTwoMasks() {
        val w = 2_000
        val h = 1_000
        val masks = List(40_000) { i ->
            MaskGeometry(5f + (i % 200) * 10f, 5f + (i / 200) * 5f, 2f, 1.5f, MaskShape.ELLIPSE)
        }
        val full = StepRecorder()
        MaskEngine.apply(IntArrayPixels(w, h), masks, settings(MaskStyle.SOLID), seams = StageSeams(observer = full))
        assertTrue(full.reads.size >= 40_000, "a read before every mask (${full.reads.size})")
        val image = RecordingImage(IntArrayPixels(w, h))
        val flip = Flip(20_000)
        assertFailsWith<Stop> { MaskEngine.apply(image, masks, settings(MaskStyle.SOLID), flip.check) }
        assertEquals(20_000, flip.calls, "stopped at the flip, within one mask")
        assertEquals(0, image.writes)
    }

    @Test
    fun failuresBeforeTheCopyLeaveTheImageUntouched() {
        val w = 300
        val h = 220
        val masks = randomMasks(Random(4), w, h, 12, maxRadius = 70f)
        for (style in MaskStyle.entries) {
            val ok = RecordingImage(stageImage(w, h, 2))
            var step2Reads = 0
            val steps = StepRecorder().apply { onStep = { if (it == 3) step2Reads = ok.reads } }
            MaskEngine.apply(ok, masks, settings(style), seams = StageSeams(observer = steps))
            assertTrue(step2Reads > 2)
            // An exception from the caller's readPixels at several points of step 2: passes as it is.
            for (at in listOf(1, step2Reads / 2, step2Reads)) {
                val image = RecordingImage(stageImage(w, h, 2)).apply { failReadAt = at }
                val before = checksum(image.base.data)
                assertFailsWith<IllegalStateException>("$style read $at") {
                    MaskEngine.apply(image, masks, settings(style))
                }
                assertEquals(0, image.writes)
                assertEquals(before, checksum(image.base.data))
            }
            // An exception and an out-of-memory error from inside a fill (through the in-loop cancellation check):
            // both pass unchanged; the image is untouched.
            for (failure in listOf<Throwable>(IllegalArgumentException("planted"), OutOfMemoryError("planted"))) {
                val image = RecordingImage(stageImage(w, h, 2))
                val before = checksum(image.base.data)
                var calls = 0
                val thrown = assertFailsWith<Throwable> {
                    MaskEngine.apply(image, masks, settings(style), { if (++calls == 3) throw failure })
                }
                assertSame(failure, thrown, "$style: passed unchanged")
                assertEquals(0, image.writes)
                assertEquals(before, checksum(image.base.data))
            }
        }
    }

    @Test
    fun aFailureOfTheImageDuringTheCopyIsAWriteFailure() {
        val w = 300
        val h = 220
        val masks = randomMasks(Random(5), w, h, 10, maxRadius = 50f)
        val image = RecordingImage(stageImage(w, h, 2)).apply { failWriteAt = 2 }
        val e =
            assertFailsWith<CoreFailure.WriteInterrupted> { MaskEngine.apply(image, masks, settings(MaskStyle.SOLID)) }
        assertTrue(e.cause is IllegalStateException)
        assertEquals(2, image.writes, "the first run was written, the second threw")
        // A lossy image (writes ignored): the read-back comparison fails.
        val lossy = object : io.github.shishark.faceblur.MutablePixelImage {
            val base = stageImage(w, h, 2)
            override val width = w
            override val height = h

            override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) = base.readPixels(x, y, w, h, dst)

            override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) = Unit
        }
        assertFailsWith<CoreFailure.WriteNotApplied> { MaskEngine.apply(lossy, masks, settings(MaskStyle.SOLID)) }
    }

    @Test
    fun throughTheFaceMaskerAReadFailureIsInternalAndTheImageIsUntouched() {
        val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
        masker.use { m ->
            // Reads of the detection, then the first read of the mask stage throws.
            val probe = RecordingImage(IntArrayPixels(640, 480, gradientPixels(640, 480)))
            m.detect(probe)
            val detectionReads = probe.reads
            val image = RecordingImage(IntArrayPixels(640, 480, gradientPixels(640, 480))).apply {
                failReadAt = detectionReads + 1
            }
            val before = checksum(image.base.data)
            val e = assertFailsWith<FaceMaskException.Internal> { m.maskInPlace(image) }
            assertTrue(e.cause is IllegalStateException)
            assertEquals(0, image.writes)
            assertEquals(before, checksum(image.base.data))
            // A probe that refuses the working area: NotEnoughMemory, untouched.
            m.useStageSeamsForTest(StageSeams(probe = FixedProbe(MemoryRule.HEAP_REMAINING, 1)))
            val refused = RecordingImage(IntArrayPixels(640, 480, gradientPixels(640, 480)))
            val nem = assertFailsWith<FaceMaskException.NotEnoughMemory> { m.maskInPlace(refused) }
            assertEquals(null, nem.cause, "the check refused, not the catch")
            assertEquals(0, refused.writes)
        }
    }
}
