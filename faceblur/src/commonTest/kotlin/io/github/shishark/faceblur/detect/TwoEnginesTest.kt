// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.Golden
import io.github.shishark.faceblur.RecordedInputs
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.image.PixelDetectionImage
import io.github.shishark.faceblur.internal.runtime.DeadlineSession
import io.github.shishark.faceblur.pause
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Two passes at a time: with a second engine every other tile runs on it while the caller prepares the next pass, and
 * the candidates enter the merge in tile order, so the merge input is exactly that of one engine: the same
 * candidates (box, score, cut) in the same order, whatever order the runs end in. The engines are the library's
 * detectors over hard-limit sessions on the platform's pass threads; the model runs end after a delay that depends on
 * their input, so they overtake each other.
 */
class TwoEnginesTest {
    private val profile = yunetProfile(160, 128)
    private val model = testYuNetModel(profile)

    /**
     * Three faces per pass from the input's digest (two of them overlap, so the in-pass NMS has work), after a delay of
     * 0 to 5 ms that also depends on it. Records every input; two sessions may run it at the same time.
     */
    private class JitterRunner : TensorRunner {
        override val inputWidth = 160
        override val inputHeight = 128
        val inputs = RecordedInputs()

        override fun initialize() = Unit

        override fun close() = Unit

        override fun run(input: FloatArray): Map<String, FloatArray> {
            val hash = Golden.fnv(input)
            inputs.add(hash)
            val mix = hash.toULong(16).toLong().let { (it xor (it ushr 31)).toInt() }
            // pause(0) would wait without a limit: only a positive delay.
            val delay = ((mix ushr 3) and 0x7).toLong() % 6
            if (delay > 0L) pause(delay)
            val m = HashMap<String, FloatArray>()
            for (s in intArrayOf(8, 16, 32)) {
                val n = (inputWidth / s) * (inputHeight / s)
                m["cls_$s"] = FloatArray(n)
                m["obj_$s"] = FloatArray(n)
                m["bbox_$s"] = FloatArray(n * 4)
            }
            val cols = inputWidth / 16
            val rows = inputHeight / 16
            for (k in 0 until 3) {
                val cx = 1 + ((mix ushr (4 * k)) and 0xFF) % (cols - 2)
                val cy = 1 + ((mix ushr (4 * k + 8)) and 0xFF) % (rows - 2)
                val cell = if (k == 2) (cy * cols + cx + 1).coerceAtMost(cols * rows - 1) else cy * cols + cx
                val score = 0.6f + ((mix ushr (5 * k)) and 0x1F) / 100f
                m.getValue("cls_16")[cell] = score
                m.getValue("obj_16")[cell] = score
                m.getValue("bbox_16")[cell * 4 + 2] = ln(2f + k)
                m.getValue("bbox_16")[cell * 4 + 3] = ln(2f + k)
            }
            return m
        }
    }

    private class Candidate(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val score: Float,
        val cut: Boolean,
    ) {
        override fun equals(other: Any?) = other is Candidate &&
            left.toBits() == other.left.toBits() && top.toBits() == other.top.toBits() &&
            right.toBits() == other.right.toBits() && bottom.toBits() == other.bottom.toBits() &&
            score.toBits() == other.score.toBits() && cut == other.cut

        override fun hashCode() = listOf(left, top, right, bottom, score, cut).hashCode()

        override fun toString() = "($left, $top, $right, $bottom, $score, $cut)"
    }

    private class Run(val candidates: List<Candidate>, val outcome: DetectionOutcome, val secondRuns: Int)

    private fun detector(runner: TensorRunner): Pair<ModelDetector, FakeSession> {
        val inner = FakeSession(runner, model)
        return ModelDetector(DeadlineSession(inner), profile) to inner
    }

    private fun run(
        w: Int,
        h: Int,
        seed: Int,
        two: Boolean,
        settings: DetectionSettings = DetectionSettings(0.5f),
    ): Run {
        val runner = JitterRunner()
        val (lead, _) = detector(runner)
        val (second, secondInner) = detector(runner)
        val seen = ArrayList<Candidate>()
        val tiler = TiledFaceDetector(
            lead,
            settings,
            secondEngine = if (two) ({ second }) else null,
            mergeInputObserver = { f, cut ->
                seen +=
                    Candidate(f.rect.left, f.rect.top, f.rect.right, f.rect.bottom, f.score, cut)
            },
        )
        try {
            val outcome = tiler.detect(PixelDetectionImage(IntArrayPixels(w, h, Golden.photoLike(w, h, seed))))
            return Run(seen, outcome, secondInner.runs)
        } finally {
            lead.close()
            second.close()
        }
    }

    private fun assertSameOutcome(a: DetectionOutcome, b: DetectionOutcome, label: String) {
        assertEquals(a.faces, b.faces, "$label faces")
        assertEquals(a.parts.map { it.face to it.rect }, b.parts.map { it.face to it.rect }, "$label parts")
        assertEquals(a.covers, b.covers, "$label covers")
        assertEquals(a.passes, b.passes, "$label passes")
        assertEquals(a.mergeCandidates, b.mergeCandidates, "$label merge candidates")
        assertEquals(a.mergePairChecks, b.mergePairChecks, "$label merge pair checks")
        assertEquals(a.mergeWork, b.mergeWork, "$label merge work")
        assertEquals(a.nmsWork, b.nmsWork, "$label in-pass NMS work of both engines, counted once")
    }

    @Test
    fun theMergeInputOfTwoEnginesIsThatOfOneEngineInTheSameOrder() {
        for ((w, h, seed) in listOf(
            Triple(517, 389, 3),
            Triple(900, 700, 11),
            Triple(1203, 517, 5),
            Triple(330, 140, 2),
        )) {
            val one = run(w, h, seed, two = false)
            repeat(3) { round ->
                val two = run(w, h, seed, two = true)
                val label = "$w x $h round $round"
                assertEquals(one.candidates, two.candidates, "$label: the same candidates in the same order")
                assertSameOutcome(one.outcome, two.outcome, label)
                assertTrue(two.secondRuns > 0, "$label: the second engine ran tiles")
                assertTrue(one.outcome.nmsWork > 0, "$label: the in-pass NMS had work")
            }
        }
    }

    @Test
    fun oneTileOrNoneRunsOnTheFirstEngineOnly() {
        // Both fit the 160 x 128 input: the global pass only.
        for ((w, h) in listOf(160 to 128, 100 to 90)) {
            var asked = 0
            val runner = JitterRunner()
            val (lead, _) = detector(runner)
            val (second, secondInner) = detector(runner)
            TiledFaceDetector(lead, DetectionSettings(0.5f), secondEngine = { second.also { asked++ } })
                .detect(PixelDetectionImage(IntArrayPixels(w, h, Golden.photoLike(w, h, 1))))
            assertEquals(0, asked, "$w x $h: the second engine is not asked for")
            assertEquals(0, secondInner.runs)
            lead.close()
            second.close()
        }
    }

    @Test
    fun theNmsBoundOfThePhotoCoversBothEnginesAndStopsAtTheSamePass() {
        val full = run(900, 700, 11, two = false).outcome.nmsWork
        assertTrue(full > 4, "the photo has in-pass NMS work: $full")
        val bound = DetectionSettings(0.5f, maxNmsWork = full / 2)
        val one = assertFailsWith<OutputCheckFailed> { run(900, 700, 11, two = false, settings = bound) }
        val two = assertFailsWith<OutputCheckFailed> { run(900, 700, 11, two = true, settings = bound) }
        assertEquals(listOf(ModelDetector.TOO_MANY_NMS_PAIR_CHECKS), two.findings)
        assertEquals(one.pairChecks, two.pairChecks, "the bound is reached at the same pair check")
    }

    @Test
    fun aCancellationLetsThePassesInFlightEndAndKeepsBothSessionsUsable() {
        val runner = JitterRunner()
        val (lead, _) = detector(runner)
        val (second, _) = detector(runner)
        var reads = 0
        val tiler = TiledFaceDetector(
            lead,
            DetectionSettings(0.5f),
            checkCancelled = { if (++reads == 5) throw FaceMaskException.Cancelled() },
            secondEngine = { second },
        )
        val image = PixelDetectionImage(IntArrayPixels(900, 700, Golden.photoLike(900, 700, 11)))
        assertFailsWith<FaceMaskException.Cancelled> { tiler.detect(image) }
        // Both sessions are healthy: the same detectors give the result of a fresh pair.
        val again = TiledFaceDetector(lead, DetectionSettings(0.5f), secondEngine = { second }).detect(image)
        assertSameOutcome(run(900, 700, 11, two = true).outcome, again, "after the cancellation")
        lead.close()
        second.close()
    }
}
