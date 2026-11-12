// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.FakeDetectionImage
import io.github.shishark.faceblur.FakeEngine
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The cross-pass merge (indexed = pairwise, union property, bound) and the pass cap (ADR 0012 decisions 3 and 6). */
class MergeAndPassCapTest {
    /**
     * The plain pairwise merge, the reference for the indexed one: greedy by score (stable), the first kept box in keep
     * order that is the same face (IoU > iou or containment ≥ c); a cut kept box becomes the union with the higher
     * score, and whole when the candidate is whole.
     */
    private fun pairwise(candidates: List<MergeCandidate>, iou: Float, c: Float): List<DetectedFace> {
        val sorted = candidates.sortedByDescending { it.face.score }
        val faces = ArrayList<DetectedFace>()
        val cut = ArrayList<Boolean>()
        for (cand in sorted) {
            val dup = faces.indices.firstOrNull { Nms.sameFace(faces[it].rect, cand.face.rect, iou, c) }
            if (dup == null) {
                faces += cand.face
                cut += cand.cut
            } else if (cut[dup]) {
                faces[dup] =
                    faces[dup].copy(
                        rect = Nms.union(faces[dup].rect, cand.face.rect),
                        score = max(faces[dup].score, cand.face.score),
                    )
                cut[dup] = cand.cut
            }
        }
        return faces
    }

    /** The merge before the union fix: a whole box replaced a cut one (planted, for the negative control). */
    private fun preFix(candidates: List<MergeCandidate>, iou: Float, c: Float): List<DetectedFace> {
        val sorted = candidates.sortedByDescending { it.face.score }
        val faces = ArrayList<DetectedFace>()
        val cut = ArrayList<Boolean>()
        for (cand in sorted) {
            val dup = faces.indices.firstOrNull { Nms.sameFace(faces[it].rect, cand.face.rect, iou, c) }
            when {
                dup == null -> {
                    faces += cand.face
                    cut += cand.cut
                }

                cut[dup] && !cand.cut -> {
                    faces[dup] = cand.face.copy(score = max(faces[dup].score, cand.face.score))
                    cut[dup] = false
                }

                cut[dup] && cand.cut -> faces[dup] = faces[dup].copy(rect = Nms.union(faces[dup].rect, cand.face.rect))
            }
        }
        return faces
    }

    private fun randomCandidates(
        random: Random,
        n: Int,
        extent: Float,
        maxSide: Float,
        scores: Int = 1000,
    ): List<MergeCandidate> = List(n) {
        val w = 1f + random.nextFloat() * maxSide
        val h = 1f + random.nextFloat() * maxSide
        val x = random.nextFloat() * extent
        val y = random.nextFloat() * extent
        MergeCandidate(
            DetectedFace(RectF(x, y, x + w, y + h), random.nextInt(1, scores) / scores.toFloat()),
            random.nextInt(3) == 0,
        )
    }

    @Test
    fun theIndexedMergeEqualsThePairwiseMerge() {
        val random = Random(4242)
        val sets = ArrayList<List<MergeCandidate>>()
        repeat(200) { sets += randomCandidates(random, random.nextInt(1, 300), 2000f, 300f) } // sparse to mixed
        repeat(50) { sets += randomCandidates(random, 400, 200f, 120f, scores = 5) } // dense, many equal scores
        repeat(20) { sets += randomCandidates(random, 300, 4000f, 4000f) } // huge boxes (the large-box list)
        sets += listOf(randomCandidates(random, 2000, 30f, 10f)) // very dense
        for ((i, set) in sets.withIndex()) {
            assertEquals(pairwise(set, 0.4f, 0.7f), Nms.mergeCandidates(set, 0.4f, 0.7f).faces, "set $i")
        }
        assertEquals(emptyList(), Nms.mergeCandidates(emptyList(), 0.4f, 0.7f).faces)
    }

    /** Union property at box level: every box kept at threshold t lies inside a box kept at any t' < t. */
    @Test
    fun aLowerThresholdNeverRemovesOrShrinksAKeptBox() {
        val random = Random(77)
        var preFixViolations = 0
        repeat(300) {
            val all = randomCandidates(random, random.nextInt(2, 120), 800f, 200f)
            for (t in listOf(0.3f, 0.5f, 0.7f)) {
                val high = all.filter { it.face.score >= t }
                val atT = Nms.mergeCandidates(high, 0.4f, 0.7f).faces
                for (tLow in listOf(0.05f, t - 0.1f)) {
                    val atLow = Nms.mergeCandidates(all.filter { it.face.score >= tLow }, 0.4f, 0.7f).faces
                    for (f in atT) {
                        assertTrue(atLow.any { contains(it.rect, f.rect) }, "box at $t lost at $tLow")
                    }
                    val old = preFix(all.filter { it.face.score >= tLow }, 0.4f, 0.7f)
                    if (preFix(high, 0.4f, 0.7f).any { f -> old.none { contains(it.rect, f.rect) } }) preFixViolations++
                }
            }
        }
        assertTrue(preFixViolations > 0, "negative control: the pre-fix merge shrinks boxes at a lower threshold")
        // The documented case: a cut half (high score) and the whole face (lower score).
        val cutHalf = MergeCandidate(DetectedFace(RectF(0f, 0f, 60f, 100f), 0.9f), cut = true)
        val wholeLower = MergeCandidate(DetectedFace(RectF(10f, 0f, 100f, 100f), 0.4f), cut = false)
        assertEquals(
            RectF(0f, 0f, 100f, 100f),
            Nms.mergeCandidates(listOf(cutHalf, wholeLower), 0.4f, 0.7f).faces.single().rect,
        )
        assertEquals(
            RectF(10f, 0f, 100f, 100f),
            preFix(listOf(cutHalf, wholeLower), 0.4f, 0.7f).single().rect,
            "pre-fix lost 10 px",
        )
    }

    private fun contains(outer: RectF, inner: RectF) =
        outer.left <= inner.left && outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom

    @Test
    fun theMergeChecksCancellation() {
        val set = randomCandidates(Random(1), 10_000, 3000f, 50f)
        var calls = 0
        assertFailsWith<IllegalStateException> {
            Nms.mergeCandidates(set, 0.4f, 0.7f) { if (++calls == 2) throw IllegalStateException("cancelled") }
        }
        assertEquals(2, calls)
    }

    @Test
    fun moreMergeCandidatesThanTheBoundFailClosedWithTheImageUnchanged() {
        // 640 × 480 at one face per pass: 1 candidate. A bound of 0 is exceeded by the first candidate.
        val runner = OneFaceRunner()
        val model = testYuNetModel()
        val tight = DetectionSettings(0.5f, maxMergeCandidates = 0)
        val masker = FaceMasker.forTest(model, FaceMaskerConfig(), RunnerRuntime(runner), tight)
        val img = IntArrayPixels(640, 480, gradientPixels(640, 480))
        val before = img.data.copyOf()
        val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
        assertEquals(listOf("TOO_MANY_MERGE_CANDIDATES"), e.findings)
        assertContentEquals(before, img.data)
        masker.close()
        // At the bound: accepted.
        FaceMasker.forTest(
            model,
            FaceMaskerConfig(),
            RunnerRuntime(runner),
            DetectionSettings(0.5f, maxMergeCandidates = 1),
        ).use {
            assertEquals(1, it.maskInPlace(img).masks.size)
        }
        // The engine-level bound counts across passes (no truncation): 3 faces in tiles, bound 2.
        val engine =
            FakeEngine(listOf(RectF(10f, 10f, 60f, 60f), RectF(700f, 10f, 750f, 60f), RectF(1300f, 10f, 1350f, 60f)))
        val tiler = TiledFaceDetector(engine, DetectionSettings(0.5f, maxMergeCandidates = 2))
        assertEquals(
            listOf("TOO_MANY_MERGE_CANDIDATES"),
            assertFailsWith<OutputCheckFailed> {
                tiler.detect(FakeDetectionImage(1400, 600))
            }.findings,
        )
    }

    /** ADR 0012 decision 6: the pass formula's maximum over the size limits (128 px input) is 233,656 passes. */
    @Test
    fun thePassCapIsDerivedFromGeometryAndNeverBindsWithinTheSizeLimits() {
        var best = 0L
        var bestW = 0
        for (w in 1..65_535) {
            val h = min(65_535L, Int.MAX_VALUE.toLong() / w).toInt()
            val p = TiledFaceDetector.passCount(w, h, 128, 128, 0.25f)
            if (p > best) {
                best = p
                bestW = w
            }
        }
        assertEquals(233_656L, best)
        assertEquals(40_353, bestW)
        assertTrue(best < Limits.MAX_PASSES_PER_IMAGE)
        assertEquals(1_370L, TiledFaceDetector.passCount(3_500, 3_500, 128, 128, 0.25f))
        assertEquals(1L, TiledFaceDetector.passCount(640, 480, 640, 480, 0.25f))
        assertEquals(3L, TiledFaceDetector.passCount(641, 480, 640, 480, 0.25f))
    }

    @Test
    fun aLoweredPassCapRefusesTheImageBeforeTheFirstPass() {
        val runner = OneFaceRunner()
        val masker = FaceMasker.forTest(
            testYuNetModel(),
            FaceMaskerConfig(),
            RunnerRuntime(runner),
            DetectionSettings(0.5f, maxPasses = 4),
        )
        val runsBefore = runner.runs
        val img = IntArrayPixels(1000, 800, gradientPixels(1000, 800)) // 1 + 4 tiles = 5 passes
        val before = img.data.copyOf()
        val e = assertFailsWith<FaceMaskException.ImageTooLarge> { masker.maskInPlace(img) }
        assertEquals(runsBefore, runner.runs, "no pass ran")
        assertContentEquals(before, img.data)
        val side = TiledFaceDetector.largestSquareSide(4, 640, 640, 0.25f)
        assertEquals(side.toLong() * side, e.limit)
        assertEquals(640, side, "the largest square image within 4 passes needs only the global pass")
        assertTrue(e.message!!.contains("640 x 640 model input"), e.message)
        assertEquals(
            5,
            FaceMasker.forTest(
                testYuNetModel(),
                FaceMaskerConfig(),
                RunnerRuntime(runner),
                DetectionSettings(0.5f, maxPasses = 5),
            ).use {
                it.detect(img).timings.passes
            },
        )
    }

    @Test
    fun edgeFacesAreClippedAndMaskedAndBoxesInThePaddingAreDropped() {
        // A face at the left edge that sticks out by half its width, and a box wholly in the letterbox padding.
        val faces = listOf(
            DetectedFace(RectF(-50f, 100f, 50f, 200f), 0.9f),
            DetectedFace(RectF(100f, 500f, 200f, 600f), 0.9f), // below a 640 × 480 image
        )
        val engine = object : FaceDetectorEngine {
            override val inputWidth = 640
            override val inputHeight = 640

            override fun detect(image: DetectionImage, minScore: Float, checkCancelled: () -> Unit) = faces

            override fun close() = Unit
        }
        val out = TiledFaceDetector(engine, DetectionSettings(0.5f)).detect(FakeDetectionImage(640, 480))
        assertEquals(listOf(RectF(0f, 100f, 50f, 200f)), out.faces.map { it.rect })
    }
}
