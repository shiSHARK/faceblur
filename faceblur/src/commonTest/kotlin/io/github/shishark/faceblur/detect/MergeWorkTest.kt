// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The merge's work is bounded by operation count, never by wall-clock time (ADR 0012 decisions 3 and 6): face-like
 * sets examine a few kept boxes per candidate, the adversarial set grows quadratically, and the work bound fails
 * closed exactly when it is passed.
 */
class MergeWorkTest {
    /**
     * Face-like: a crowd photo of 3,500 px with [faces] faces on a jittered grid (each 40-90 % of its grid cell, so
     * neighbours can touch), each with 2-6 jittered detections, about one in four cut.
     */
    private fun faceLike(random: Random, faces: Int): List<MergeCandidate> = buildList {
        val perRow = kotlin.math.ceil(kotlin.math.sqrt(faces.toDouble())).toInt()
        val cell = 3500f / perRow
        repeat(faces) { f ->
            val s = cell * (0.4f + random.nextFloat() * 0.5f)
            val x = (f % perRow) * cell + random.nextFloat() * (cell - s)
            val y = (f / perRow) * cell + random.nextFloat() * (cell - s)
            repeat(random.nextInt(2, 7)) {
                val j = { random.nextFloat() * 0.1f * s - 0.05f * s }
                val l = x + j()
                val t = y + j()
                add(
                    MergeCandidate(
                        DetectedFace(RectF(l, t, l + s + j(), t + s + j()), random.nextFloat()),
                        random.nextInt(4) == 0,
                    ),
                )
            }
        }
    }

    /**
     * The adversarial set: [n] tiny separate high-score boxes in a 100 × 100 px square, then [n] large lower-scored
     * boxes that each cover the whole square (each large box must be compared with every tiny one).
     */
    private fun adversarial(n: Int): List<MergeCandidate> = buildList {
        val perRow = 50
        for (i in 0 until n) {
            val x = (i % perRow) * 2f
            val y = (i / perRow) * 100f / (n / perRow + 1)
            add(MergeCandidate(DetectedFace(RectF(x, y, x + 1f, y + 0.5f), 0.9f), false))
        }
        for (i in 0 until n) {
            val o = (i % 97) * 0.5f
            add(MergeCandidate(DetectedFace(RectF(-o, -o, 300f - o, 300f - o), 0.5f), false))
        }
    }

    @Test
    fun faceLikeSetsExamineAFewKeptBoxesPerCandidate() {
        val random = Random(31)
        for (faces in listOf(100, 1_000, 10_000)) {
            val set = faceLike(random, faces)
            val r = Nms.mergeCandidates(set, 0.4f, 0.7f)
            val perCandidate = r.pairChecks.toDouble() / set.size
            val workPerCandidate = r.work.toDouble() / set.size
            // Measured with this seed: 5.4 / 5.3 / 4.9 pair checks and 25 / 28 / 37 work units per candidate.
            assertTrue(perCandidate <= 8.0, "$faces faces: $perCandidate pair checks per candidate")
            assertTrue(workPerCandidate <= 48.0, "$faces faces: $workPerCandidate work units per candidate")
        }
    }

    @Test
    fun theAdversarialSetGrowsQuadratically() {
        val a = Nms.mergeCandidates(adversarial(1_000), 0.4f, 0.7f).work
        val b = Nms.mergeCandidates(adversarial(2_000), 0.4f, 0.7f).work
        val ratio = b.toDouble() / a
        assertTrue(ratio in 3.5..4.5, "doubling the set multiplies the work by $ratio ($a -> $b)")
    }

    @Test
    fun theWorkBoundFailsClosedExactlyWhenItIsPassed() {
        val set = adversarial(300)
        val full = Nms.mergeCandidates(set, 0.4f, 0.7f, maxWork = Long.MAX_VALUE)
        // Negative control: without a bound the merge runs to the end.
        assertTrue(full.work > 10_000, "${full.work}")
        assertEquals(full.faces, Nms.mergeCandidates(set, 0.4f, 0.7f, maxWork = full.work).faces, "at the bound")
        val e = assertFailsWith<OutputCheckFailed> { Nms.mergeCandidates(set, 0.4f, 0.7f, maxWork = full.work - 1) }
        assertEquals(listOf("TOO_MANY_MERGE_CANDIDATES"), e.findings)
    }

    @Test
    fun throughTheMaskerTheWorkBoundLeavesTheImageUnchanged() {
        // One face in a 640 × 480 image: one candidate and no kept box to look at, so the work is 0.
        val img = IntArrayPixels(640, 480, gradientPixels(640, 480))
        val before = img.data.copyOf()
        FaceMasker.forTest(
            testYuNetModel(),
            FaceMaskerConfig(),
            RunnerRuntime(OneFaceRunner()),
            DetectionSettings(0.5f, maxMergeWork = 0),
        ).use { assertEquals(1, it.detect(img).faces.size) }
        // A 1,000 × 800 image: 5 passes and 5 candidates, so the merge does look at kept boxes.
        val big = IntArrayPixels(1000, 800, gradientPixels(1000, 800))
        val bigBefore = big.data.copyOf()
        FaceMasker.forTest(
            testYuNetModel(),
            FaceMaskerConfig(),
            RunnerRuntime(OneFaceRunner()),
            DetectionSettings(0.5f, maxMergeWork = 0),
        ).use { m ->
            val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { m.maskInPlace(big) }
            assertEquals(listOf("TOO_MANY_MERGE_CANDIDATES"), e.findings)
        }
        assertContentEquals(bigBefore, big.data)
        assertContentEquals(before, img.data)
    }

    @Test
    fun cancellationIsReadEvery65536UnitsOfWork() {
        // 900 tiny separate boxes in one 8 px cell: the work per candidate grows with the kept count, and fewer than
        // 4,096 candidates means every cancellation read comes from the work count.
        val set = List(900) { i ->
            val x = (i % 30) * 0.2f
            val y = (i / 30) * 0.2f
            MergeCandidate(DetectedFace(RectF(x, y, x + 0.01f, y + 0.01f), 0.9f), false)
        }
        var reads = 0
        val r = Nms.mergeCandidates(set, 0.4f, 0.7f) { reads++ }
        assertTrue(r.work > 3 * 65_536L, "${r.work}")
        assertEquals((r.work / 65_536L).toInt(), reads)
        // A cancellation stops the merge at that read.
        var calls = 0
        assertFailsWith<IllegalStateException> {
            Nms.mergeCandidates(set, 0.4f, 0.7f) { if (++calls == 1) throw IllegalStateException("cancelled") }
        }
        assertEquals(1, calls)
    }
}
