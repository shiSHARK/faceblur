// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.FakeDetectionImage
import io.github.shishark.faceblur.FakeEngine
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.PointF
import io.github.shishark.faceblur.geometry.RectF
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GeometryTest {
    @Test
    fun iouBasics() {
        val a = RectF(0f, 0f, 10f, 10f)
        assertEquals(1f, a.iou(a))
        assertEquals(0f, a.iou(RectF(10f, 0f, 20f, 10f)), "touching edges do not overlap")
        assertEquals(0f, a.iou(RectF(20f, 20f, 30f, 30f)))
        // half overlap: inter 50, union 150
        assertEquals(1f / 3f, a.iou(RectF(5f, 0f, 15f, 10f)), 1e-6f)
        assertEquals(25f, a.intersectionArea(RectF(5f, 5f, 15f, 15f)))
        assertEquals(0f, RectF(5f, 5f, 4f, 4f).area)
    }

    @Test
    fun faceBoxMapping() {
        val f = DetectedFace(RectF(1f, 2f, 3f, 4f), 0.7f, listOf(PointF(2f, 3f)))
        val m = f.mapped(10f, 20f, 2f, 3f)
        assertEquals(RectF(12f, 26f, 16f, 32f), m.rect)
        assertEquals(PointF(14f, 29f), m.landmarks[0])
        assertEquals(0.7f, m.score)
    }
}

class NmsTest {
    private fun box(l: Float, t: Float, r: Float, b: Float, s: Float) = DetectedFace(RectF(l, t, r, b), s)

    /** Cross-pass merge of whole (not cut) boxes; the bench called this `Nms.merge`. */
    private fun merge(
        faces: List<DetectedFace>,
        iou: Float,
        containment: Float = TiledFaceDetector.CONTAINMENT_THRESHOLD,
    ) = Nms.mergeCandidates(faces.map { MergeCandidate(it, cut = false) }, iou, containment).faces

    /**
     * Plain greedy IoU NMS (the bench's `Nms.suppress`): the same merge with the containment rule switched off
     * (intersection / smaller area can never reach 1.01).
     */
    private fun suppress(faces: List<DetectedFace>, iou: Float) = merge(faces, iou, containment = 1.01f)

    @Test
    fun suppressKeepsBestOfEachCluster() {
        val faces = listOf(
            box(0f, 0f, 10f, 10f, 0.6f),
            box(1f, 1f, 11f, 11f, 0.9f), // overlaps the first strongly
            box(50f, 50f, 60f, 60f, 0.5f),
        )
        val kept = suppress(faces, 0.4f)
        assertEquals(listOf(0.9f, 0.5f), kept.map { it.score })
    }

    @Test
    fun suppressRespectsThreshold() {
        val a = box(0f, 0f, 10f, 10f, 0.9f)
        val b = box(5f, 0f, 15f, 10f, 0.8f) // IoU 1/3
        assertEquals(2, suppress(listOf(a, b), 0.4f).size)
        assertEquals(1, suppress(listOf(a, b), 0.3f).size)
    }

    @Test
    fun mergeDropsContainedHalfFace() {
        val whole = box(0f, 0f, 100f, 100f, 0.9f)
        val half = box(50f, 0f, 100f, 100f, 0.8f) // IoU 0.5, fully inside the whole face
        val quarter = box(60f, 10f, 100f, 90f, 0.85f) // IoU 0.32, fully inside the whole face
        assertEquals(2, suppress(listOf(whole, quarter), 0.4f).size, "plain IoU NMS keeps the partial face")
        assertEquals(listOf(whole), merge(listOf(whole, quarter), 0.4f))
        assertEquals(listOf(whole), merge(listOf(whole, half), 0.6f))
    }

    @Test
    fun cutBoxNeverWinsOverWholeFace() {
        val cutHalf = MergeCandidate(box(50f, 0f, 100f, 100f, 0.95f), cut = true)
        val whole = MergeCandidate(box(10f, 0f, 110f, 100f, 0.7f), cut = false)
        val merged = Nms.mergeCandidates(listOf(cutHalf, whole), 0.4f, 0.7f).faces
        assertEquals(1, merged.size)
        assertEquals(RectF(10f, 0f, 110f, 100f), merged[0].rect)
        assertEquals(0.95f, merged[0].score, "keeps the best score of the cluster")
    }

    @Test
    fun twoCutHalvesBecomeTheirUnion() {
        val left = MergeCandidate(box(0f, 0f, 80f, 100f, 0.8f), cut = true)
        val right = MergeCandidate(box(20f, 0f, 100f, 100f, 0.9f), cut = true) // IoU 0.6 with the left half
        val merged = Nms.mergeCandidates(listOf(left, right), 0.4f, 0.7f).faces
        assertEquals(listOf(RectF(0f, 0f, 100f, 100f)), merged.map { it.rect })
    }
}

class TilingTest {
    private fun covers(tiles: List<IntArray>, w: Int, h: Int): Boolean {
        val step = 7
        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                if (tiles.none { x >= it[0] && x < it[0] + it[2] && y >= it[1] && y < it[1] + it[3] }) return false
            }
        }
        return tiles.any { it[0] + it[2] == w } && tiles.any { it[1] + it[3] == h }
    }

    private fun assertPlanCoversImageWithoutSlivers(inW: Int, inH: Int) {
        val det =
            TiledFaceDetector(FakeEngine(emptyList(), inputWidth = inW, inputHeight = inH), DetectionSettings(0.5f))
        for ((w, h) in listOf(4032 to 3024, 3024 to 4032, 1920 to 1536, 8000 to 6000, 1601 to 900, 12000 to 1000)) {
            val tiles = det.planTiles(w, h)
            assertTrue(tiles.isNotEmpty(), "$w×$h should be tiled")
            assertTrue(covers(tiles, w, h), "$w×$h not covered")
            for (t in tiles) {
                assertTrue(t[0] >= 0 && t[1] >= 0 && t[0] + t[2] <= w && t[1] + t[3] <= h, "tile inside image")
                assertEquals(minOf(inW, w), t[2], "full-size tiles only (no slivers): tile = input width")
                assertEquals(minOf(inH, h), t[3], "tile = input height")
            }
            // neighbouring tiles overlap by at least the configured 25 % on each axis
            val xs = tiles.map { it[0] }.distinct().sorted()
            for (i in 1 until xs.size) assertTrue(xs[i] - xs[i - 1] <= inW * 0.75 + 1, "overlap x $w×$h")
            val ys = tiles.map { it[1] }.distinct().sorted()
            for (i in 1 until ys.size) assertTrue(ys[i] - ys[i - 1] <= inH * 0.75 + 1, "overlap y $w×$h")
            assertEquals(
                TiledFaceDetector.passCount(w, h, inW, inH, 0.25f),
                1L + tiles.size,
                "the pass formula counts these tiles",
            )
        }
    }

    @Test
    fun planCoversImageWithoutSliversForASquareInput() {
        assertPlanCoversImageWithoutSlivers(640, 640)
    }

    @Test
    fun planCoversImageWithoutSliversForANonSquareInput() {
        assertPlanCoversImageWithoutSlivers(640, 480)
        assertPlanCoversImageWithoutSlivers(128, 1280)
    }

    @Test
    fun noTilesWhenTheImageFitsTheInput() {
        val det =
            TiledFaceDetector(FakeEngine(emptyList(), inputWidth = 640, inputHeight = 480), DetectionSettings(0.5f))
        assertTrue(det.planTiles(640, 480).isEmpty())
        assertTrue(det.planTiles(300, 200).isEmpty())
        assertEquals(2, det.planTiles(640, 481).size)
        assertEquals(2, det.planTiles(641, 480).size)
    }

    @Test
    fun axisStartsAreEvenAndEndAtTheEdge() {
        assertEquals(listOf(0), TiledFaceDetector.axisStarts(1000, 1280, 0.25f))
        assertEquals(listOf(0, 640), TiledFaceDetector.axisStarts(1920, 1280, 0.25f))
        val s = TiledFaceDetector.axisStarts(4032, 1280, 0.25f)
        assertEquals(0, s.first())
        assertEquals(4032 - 1280, s.last())
    }

    @Test
    fun tilesFindSmallFacesTheGlobalPassMissesAndMergeDuplicates() {
        assertTilesFindSmallFacesAndMergeDuplicates(640, 640, exactCount = true)
        // With 480 px high tiles the big face is cut by two tile rows into two partial boxes that do not match each
        // other; the greedy merge then keeps one of them next to the whole box (an extra mask inside the face, never a
        // missing one). Every face is still found and no box lies outside a face.
        assertTilesFindSmallFacesAndMergeDuplicates(640, 480, exactCount = false)
    }

    private fun assertTilesFindSmallFacesAndMergeDuplicates(inW: Int, inH: Int, exactCount: Boolean) {
        // 4000×3000 photo, faces of 20 px (3.3 px in the 640 global pass → invisible to the fake engine).
        val truth = listOf(
            RectF(100f, 100f, 120f, 120f),
            RectF(1270f, 500f, 1290f, 520f), // on the first tile border
            RectF(3900f, 2900f, 3920f, 2920f),
            RectF(1500f, 1000f, 1900f, 1400f), // big face, seen by the global pass and several tiles
        )
        val engine = FakeEngine(truth, inputWidth = inW, inputHeight = inH, minSidePx = 6f)
        val released = ArrayList<FakeDetectionImage>()
        val created = ArrayList<FakeDetectionImage>()
        val image = FakeDetectionImage(4000, 3000, released = released, created = created)
        val tiler = TiledFaceDetector(engine, DetectionSettings(0.5f))
        val outcome = tiler.detect(image)
        assertEquals(1 + tiler.planTiles(4000, 3000).size, outcome.passes)
        assertEquals(outcome.passes, engine.calls)
        assertEquals(created.size, released.size, "every crop released")
        if (exactCount) assertEquals(truth.size, outcome.faces.size, "found: ${outcome.faces.map { it.rect }}")
        for (f in outcome.faces) {
            assertTrue(truth.any { t -> f.rect.intersectionArea(t) >= 0.999f * f.rect.area }, "box inside a face: $f")
        }
        for (t in truth) {
            val best = outcome.faces.maxOf { it.rect.iou(t) }
            assertTrue(best > 0.95f, "face $t matched with IoU $best")
        }
        assertFalse(image.isReleased, "the source image is not released by the tiler")
    }

    @Test
    fun globalPassOnlyMapsBoxesBackToFullResolution() {
        val truth = listOf(RectF(300f, 600f, 700f, 1100f))
        val tiler = TiledFaceDetector(FakeEngine(truth), DetectionSettings(0.5f))
        val r = tiler.globalPassOnly(FakeDetectionImage(3000, 2000)).single().rect
        assertTrue(abs(r.left - 300f) < 1f && abs(r.bottom - 1100f) < 1f, "$r")
    }

    private fun assertFalse(v: Boolean, msg: String) = assertTrue(!v, msg)
}

class YuNetDecoderTest {
    /** Builds raw outputs with one strong anchor per entry (stride, row, col, values cls, obj, dx, dy, dw, dh). */
    private fun runner(vararg anchors: Triple<Int, Pair<Int, Int>, FloatArray>) = object : TensorRunner {
        override val inputWidth = 640
        override val inputHeight = 640
        var lastInput: FloatArray? = null

        override fun initialize() = Unit

        override fun close() = Unit

        override fun run(input: FloatArray): Map<String, FloatArray> {
            lastInput = input
            val m = HashMap<String, FloatArray>()
            for (s in YUNET_STRIDES) {
                val n = (640 / s) * (640 / s)
                m["cls_$s"] = FloatArray(n)
                m["obj_$s"] = FloatArray(n)
                m["bbox_$s"] = FloatArray(n * 4)
            }
            for ((s, rc, v) in anchors) {
                val (r, c) = rc
                val idx = r * (640 / s) + c
                m.getValue("cls_$s")[idx] = v[0]
                m.getValue("obj_$s")[idx] = v[1]
                for (k in 0 until 4) m.getValue("bbox_$s")[idx * 4 + k] = v[2 + k]
            }
            return m
        }
    }

    private val image = FakeDetectionImage(640, 640)

    @Test
    fun decodesLikeOpenCv() {
        // stride 16, row 10, col 20: cx = (20 + 0.25)·16 = 324, cy = (10 + 0.5)·16 = 168, w = e^1·16, h = e^1.2·16
        val r = runner(Triple(16, 10 to 20, floatArrayOf(0.81f, 1f, 0.25f, 0.5f, 1f, 1.2f)))
        val f = yunetDetector(r).detect(image, 0.5f).single()
        assertEquals(sqrt(0.81f * 1f), f.score, 1e-6f)
        val w = exp(1f) * 16
        val h = exp(1.2f) * 16
        assertEquals(324f - w / 2, f.rect.left, 1e-3f)
        assertEquals(168f - h / 2, f.rect.top, 1e-3f)
        assertEquals(w, f.rect.width, 1e-3f)
        assertEquals(h, f.rect.height, 1e-3f)
        assertTrue(f.landmarks.isEmpty(), "landmarks are ignored")
    }

    @Test
    fun scoresMustBeProbabilitiesAndAreNeverClamped() {
        fun codes(cls: Float, obj: Float) = assertFailsWith<OutputCheckFailed> {
            yunetDetector(runner(Triple(16, 10 to 20, floatArrayOf(cls, obj, 0f, 0f, 1f, 1f)))).detect(image, 0.5f)
        }.findings
        assertEquals(listOf("SCORE_RANGE"), codes(0.81f, 1.3f), "the pre-model-agnostic decoder clamped 1.3 to 1")
        assertEquals(listOf("SCORE_RANGE"), codes(-0.01f, 0.9f))
        assertEquals(listOf("SCORE_RANGE"), codes(0.9f, 1.0000021f))
        assertEquals(listOf("NON_FINITE"), codes(Float.NaN, 0.9f))
        assertEquals(listOf("NON_FINITE"), codes(0.9f, Float.POSITIVE_INFINITY))
        // Inside the tolerance band: accepted and reported limited to [0, 1].
        val f = yunetDetector(runner(Triple(16, 10 to 20, floatArrayOf(1.0000005f, 1.0000005f, 0f, 0f, 1f, 1f))))
            .detect(image, 0.5f).single()
        assertEquals(1f, f.score)
        assertEquals(
            0,
            yunetDetector(runner(Triple(16, 10 to 20, floatArrayOf(-0.0000005f, 0.9f, 0f, 0f, 1f, 1f))))
                .detect(image, 0.5f).size,
        )
    }

    @Test
    fun thresholdAndNms() {
        val r = runner(
            Triple(8, 40 to 40, floatArrayOf(0.9f, 0.9f, 0f, 0f, ln(4f), ln(4f))), // 32×32 box, score 0.9
            Triple(8, 40 to 41, floatArrayOf(0.8f, 0.8f, 0f, 0f, ln(4f), ln(4f))), // same face shifted 8 px
            Triple(32, 2 to 2, floatArrayOf(0.2f, 0.2f, 0f, 0f, 0f, 0f)), // below threshold
        )
        val faces = yunetDetector(r).detect(image, 0.5f)
        assertEquals(1, faces.size)
        assertEquals(0.9f, faces[0].score, 1e-6f)
        assertEquals(2, yunetDetector(r).detect(image, 0.1f).size, "the 0.2 anchor is kept at a lower threshold")
    }

    @Test
    fun missingOutputFails() {
        val empty = object : TensorRunner by runner() {
            override fun run(input: FloatArray): Map<String, FloatArray> = emptyMap()
        }
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            assertFailsWith<OutputCheckFailed> {
                yunetDetector(empty).detect(image, 0.5f)
            }.findings,
        )
    }

    @Test
    fun fillInputIsBgrPlanarAndPadded() {
        val r = runner()
        yunetDetector(r).detect(FakeDetectionImage(640, 640), 0.5f)
        val argb = IntArray(640 * 640)
        argb[0] = 0xFF102030.toInt()
        argb[1] = 0xFF405060.toInt()
        val dst = FloatArray(3 * 640 * 640) { 7f }
        yunetDetector(r).fillInput(argb, 2, 1, dst)
        val plane = 640 * 640
        assertEquals(0x30.toFloat(), dst[0]) // B
        assertEquals(0x20.toFloat(), dst[plane]) // G
        assertEquals(0x10.toFloat(), dst[2 * plane]) // R
        assertEquals(0x60.toFloat(), dst[1])
        assertEquals(0f, dst[2], "padding is black")
        assertEquals(0f, dst[640 + 1])
    }

    @Test
    fun detectorAndTilerReportTheirStages() {
        val det = yunetDetector(runner())
        val clock = StageClock()
        det.stageClock = clock
        // 640 × 480 fits the input: one global pass.
        val tiler = TiledFaceDetector(det, DetectionSettings(0.5f), clock)
        tiler.detect(FakeDetectionImage(640, 480))
        val ms = clock.snapshotMs()
        assertEquals(
            listOf("crop", "model.pixels", "model.tensor", "model.inference", "model.decode", "engine", "merge"),
            ms.keys.toList(),
        )
        assertTrue(ms.getValue("model.inference") >= 0.0 && ms.getValue("engine") >= ms.getValue("model.decode"))
        clock.reset()
        assertTrue(clock.snapshotMs().isEmpty())
    }

    @Test
    fun detectorLetterboxesAndMapsBack() {
        // The runner returns one face at (100,100)-(164,164) in the 640×640 input (stride 32, row 4, col 4).
        val r = runner(Triple(32, 4 to 4, floatArrayOf(1f, 1f, 0.125f, 0.125f, ln(2f), ln(2f))))
        // 1920×1280 image → letterboxed to 640×427: scale 3.
        val released = ArrayList<FakeDetectionImage>()
        val faces = yunetDetector(r).detect(FakeDetectionImage(1920, 1280, released = released), 0.5f)
        assertEquals(1, released.size, "internal downscale released")
        val box = faces.single().rect
        assertEquals(100f * 3f, box.left, 0.5f)
        assertEquals(164f * 3f, box.right, 0.5f)
        assertEquals(100f * 1280f / 427f, box.top, 0.5f)
    }
}
