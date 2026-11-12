// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.FaceBox
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMasker.Companion.globalPassForTest
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.detect.ModelDetector
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.image.PixelDetectionImage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One candidate box with score and five landmarks, in image pixels (edges right/bottom exclusive). */
internal class Cand(
    val l: Double,
    val t: Double,
    val r: Double,
    val b: Double,
    val score: Double,
    val lm: DoubleArray,
) {
    fun iou(o: Cand): Double {
        val iw = min(r, o.r) - max(l, o.l)
        val ih = min(b, o.b) - max(t, o.t)
        if (iw <= 0 || ih <= 0) return 0.0
        val inter = iw * ih
        return inter / ((r - l) * (b - t) + (o.r - o.l) * (o.b - o.t) - inter)
    }

    fun with(dx: Double = 0.0, score: Double = this.score) = Cand(l + dx, t, r + dx, b, score, lm)
}

/** One reference image of testdata/yunet_reference.json (raw OpenCV values). */
internal class RefImage(
    val file: String,
    val sha256: String,
    val width: Int,
    val height: Int,
    val inMarginSubset: Boolean,
    val raw: List<Cand>,
    val crossesBorder: List<Boolean>,
)

/**
 * ST-005 / ADR 0009 tiers 1 and 1b on the JVM: the library's YuNet path (letterbox, BGR tensor, ONNX Runtime JVM,
 * decoding, NMS, clamping) against OpenCV `FaceDetectorYN` on the committed lossless 640 px fixtures. Every number
 * below is fixed in advance (ADR 0009) and never derived from a run of the code under test.
 */
class YuNetReferenceStrictTest {
    internal companion object {
        /** ADR 0009: fixed score bound; never widened, never set from a run. */
        const val DELTA = 0.005
        const val THRESHOLD = 0.5
        const val FLOOR = 0.3
        const val MIN_IOU = 0.99
        const val PAIR_IOU = 0.5
        val EDGE_FIXTURES =
            listOf("images/face-0029247-s0625-x0000-y0000.png", "images/face-0049466-s1000-x0384-y0000.png")

        val reference: Map<String, Any?> by lazy {
            @Suppress("UNCHECKED_CAST")
            Fixtures.referenceData()
        }

        val images: List<RefImage> by lazy {
            @Suppress("UNCHECKED_CAST")
            (reference["images"] as List<Map<String, Any?>>).map { m ->
                val faces = m["faces"] as List<Map<String, Any?>>
                RefImage(
                    file = m["file"] as String,
                    sha256 = m["sha256"] as String,
                    width = (m["width"] as Double).toInt(),
                    height = (m["height"] as Double).toInt(),
                    inMarginSubset = m["inMarginSubset"] as Boolean,
                    raw =
                    faces.map { f ->
                        val x = f["x"] as Double
                        val y = f["y"] as Double
                        Cand(
                            x,
                            y,
                            x + (f["w"] as Double),
                            y + (f["h"] as Double),
                            f["score"] as Double,
                            (f["landmarks"] as List<Double>).toDoubleArray(),
                        )
                    },
                    crossesBorder = faces.map { it["crossesBorder"] as Boolean },
                )
            }
        }

        /** The reference fixture through the public API (`DetectorModel.load`, the documented profile, pinned). */
        val model by lazy { TestModels.yunet() }

        /** A masker created like an app creates it (session, runtime IO check, contract run), on the JVM runtime. */
        val masker: FaceMasker by lazy { FaceMasker.forTest(model, FaceMaskerConfig(), OrtModelRuntime()) }

        val library = HashMap<String, List<Cand>>()

        fun pixels(img: RefImage): IntArrayPixels = Fixtures.image(img.file).also {
            assertEquals(img.sha256, Fixtures.sha256(img.file), "${img.file}: fixture differs from the reference")
            assertEquals(img.width, it.width)
            assertEquals(img.height, it.height)
        }

        /** The library's candidates down to the floor: the production detector path, unchanged. */
        fun libraryCandidates(img: RefImage, floor: Double = FLOOR): List<Cand> {
            val compute = {
                masker.globalPassForTest(pixels(img), floor.toFloat()).map { it.toCand() }
            }
            return if (floor == FLOOR) synchronized(library) { library.getOrPut(img.file, compute) } else compute()
        }

        fun FaceBox.toCand() =
            Cand(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble(), score.toDouble(), DoubleArray(0))

        /**
         * The test's own clamp of a reference candidate to [0, w] × [0, h] (ADR 0009); landmarks to the same limits;
         * an empty box is dropped. Deliberately not the library's function.
         */
        fun clampReference(c: Cand, w: Int, h: Int): Cand? {
            val l = c.l.coerceIn(0.0, w.toDouble())
            val t = c.t.coerceIn(0.0, h.toDouble())
            val r = c.r.coerceIn(0.0, w.toDouble())
            val b = c.b.coerceIn(0.0, h.toDouble())
            if (r <= l || b <= t) return null
            val lm = DoubleArray(c.lm.size) { i ->
                c.lm[i].coerceIn(
                    0.0,
                    if (i % 2 ==
                        0
                    ) {
                        w.toDouble()
                    } else {
                        h.toDouble()
                    },
                )
            }
            return Cand(l, t, r, b, c.score, lm)
        }

        fun clampedReference(img: RefImage) = img.raw.mapNotNull { clampReference(it, img.width, img.height) }

        /** Library side: asserted, never transformed (ADR 0009). Returns the violations. */
        fun libraryInsideImage(ours: List<Cand>, w: Int, h: Int): List<String> {
            val bad = ArrayList<String>()
            for ((i, c) in ours.withIndex()) {
                if (!(c.l >= 0 && c.t >= 0 && c.r <= w && c.b <= h)) bad += "library box $i outside the image"
                if (!(c.r > c.l && c.b > c.t)) bad += "library box $i empty"
                for (k in c.lm.indices) {
                    val limit = if (k % 2 == 0) w else h
                    if (!(c.lm[k] >= 0 && c.lm[k] <= limit)) bad += "library box $i landmark outside the image"
                }
            }
            return bad
        }

        class Report(
            val violations: List<String>,
            val worstIou: Double,
            val maxScoreDiff: Double,
            val maxLandmarkDistance: Double,
            val bandExemptions: Int,
        )

        /** ADR 0009 criteria 1-4 for one image; [band] = tier 1b/2 rule (exemption read from the reference score). */
        fun compare(ref: List<Cand>, ours: List<Cand>, band: Boolean): Report {
            val pairs = ArrayList<Triple<Int, Int, Double>>()
            for (i in ref.indices) {
                for (j in ours.indices) {
                    val iou = ref[i].iou(ours[j])
                    if (iou >= PAIR_IOU) pairs += Triple(i, j, iou)
                }
            }
            pairs.sortByDescending { it.third }
            val refUsed = BooleanArray(ref.size)
            val ourUsed = BooleanArray(ours.size)
            val matched = ArrayList<Triple<Int, Int, Double>>()
            for (p in pairs) {
                if (refUsed[p.first] || ourUsed[p.second]) continue
                refUsed[p.first] = true
                ourUsed[p.second] = true
                matched += p
            }
            val v = ArrayList<String>()
            var worstIou = 1.0
            var maxDiff = 0.0
            var maxLm = 0.0
            var exemptions = 0
            for ((i, j, iou) in matched) {
                val rs = ref[i].score
                val os = ours[j].score
                val diff = abs(rs - os)
                if (max(rs, os) >= THRESHOLD - DELTA) {
                    worstIou = min(worstIou, iou)
                    maxDiff = max(maxDiff, diff)
                    if (iou < MIN_IOU) v += "pair ref#$i: IoU $iou < $MIN_IOU"
                    if (diff > DELTA) v += "pair ref#$i: score difference $diff > $DELTA"
                }
                if ((rs >= THRESHOLD) != (os >= THRESHOLD)) {
                    if (band && rs >= THRESHOLD - DELTA &&
                        rs < THRESHOLD + DELTA
                    ) {
                        exemptions++
                    } else {
                        v += "pair ref#$i: threshold disagreement"
                    }
                }
                for (k in 0 until min(ref[i].lm.size, ours[j].lm.size) / 2) {
                    maxLm =
                        max(
                            maxLm,
                            hypot(
                                ref[i].lm[2 * k] - ours[j].lm[2 * k],
                                ref[i].lm[2 * k + 1] - ours[j].lm[2 * k + 1],
                            ),
                        )
                }
            }
            for (i in ref.indices) {
                if (!refUsed[i] &&
                    ref[i].score >= THRESHOLD
                ) {
                    v += "reference box $i (score ${ref[i].score}) unmatched"
                }
            }
            for (j in ours.indices) {
                if (!ourUsed[j] &&
                    ours[j].score >= THRESHOLD
                ) {
                    v += "library box $j (score ${ours[j].score}) unmatched"
                }
            }
            return Report(v, worstIou, maxDiff, maxLm, exemptions)
        }

        fun inMargin(img: RefImage) = img.raw.none { it.score >= THRESHOLD - DELTA && it.score < THRESHOLD + DELTA }
    }

    private fun runTier(
        selected: List<RefImage>,
        band: Boolean,
        name: String,
        library: (RefImage) -> List<Cand> = { libraryCandidates(it) },
    ) {
        var worst = 1.0
        var diff = 0.0
        var lm = 0.0
        var exemptions = 0
        var gated = 0
        val failures = ArrayList<String>()
        for (img in selected) {
            val ours = library(img)
            failures += libraryInsideImage(ours, img.width, img.height).map { "${img.file}: $it" }
            val r = compare(clampedReference(img), ours, band)
            failures += r.violations.map { "${img.file}: $it" }
            worst = min(worst, r.worstIou)
            diff = max(diff, r.maxScoreDiff)
            lm = max(lm, r.maxLandmarkDistance)
            exemptions += r.bandExemptions
            gated += clampedReference(img).count { it.score >= THRESHOLD }
        }
        println(
            "$name: images=${selected.size} referenceBoxes>=0.5=$gated worstIoU=$worst maxScoreDiff=$diff " +
                "maxLandmarkDistance=$lm bandExemptions=$exemptions",
        )
        assertEquals(emptyList(), failures, name)
    }

    @Test
    fun tier1StrictOnTheMarginSubsetHasNoBandAndNoException() {
        val margin = images.filter { it.inMarginSubset }
        val gatedBoxes = margin.sumOf { i -> i.raw.count { it.score >= THRESHOLD } }
        assertTrue(gatedBoxes >= 100, "margin subset needs >= 100 boxes")
        runTier(margin, band = false, name = "tier 1")
    }

    /** ADR 0002 decision 10 (onnxThreads has no effect on results): tier 1 again with one ONNX Runtime thread. */
    @Test
    fun tier1HoldsWithOneOnnxThread() {
        FaceMasker.forTest(model, FaceMaskerConfig(onnxThreads = 1), OrtModelRuntime()).use { single ->
            runTier(images.filter { it.inMarginSubset }, band = false, name = "tier 1 (1 thread)") { img ->
                single.globalPassForTest(pixels(img), FLOOR.toFloat()).map { it.toCand() }
            }
        }
    }

    /** The reference data was made with the model, thresholds and constants this gate assumes (ADR 0009). */
    @Test
    fun theReferenceHeaderMatchesTheGate() {
        assertEquals(TestModels.YUNET_SHA256, reference["modelSha256"])
        assertEquals(TestModels.YUNET_SHA256, model.info.sha256)
        assertEquals(FLOOR, reference["candidateFloor"])
        assertEquals(THRESHOLD, reference["scoreThreshold"])
        assertEquals(0.3, reference["nmsThreshold"], "the reference NMS is the library's in-model NMS")
        assertEquals(0.3f, ModelDetector.IN_PASS_NMS_IOU)
        assertEquals(5000.0, reference["topK"])
        assertEquals(640.0, reference["inputSide"])
        assertEquals(DELTA, reference["marginDelta"])
        @Suppress("UNCHECKED_CAST")
        for (m in reference["images"] as List<Map<String, Any?>>) {
            val count = (m["candidateCount"] as Double).toInt()
            assertTrue(count < 5000, "${m["file"]}: candidate list reached the cap")
            assertEquals(count, (m["faces"] as List<*>).size, "${m["file"]}")
        }
    }

    @Test
    fun tier1bPlatformRulesOnTheWholeLosslessGroup() {
        runTier(images, band = true, name = "tier 1b")
    }

    @Test
    fun theMarginSubsetIsAPureFunctionOfTheReferenceData() {
        for (img in images) assertEquals(img.inMarginSubset, inMargin(img), img.file)
        assertTrue(images.any { !it.inMarginSubset }, "the band is exercised by near-threshold images")
        val gatedBorder = images.filter { it.inMarginSubset }.flatMap { i ->
            i.raw.indices.filter { k ->
                val gated = i.raw[k].score >= THRESHOLD
                i.crossesBorder[k] && gated
            }
        }
        assertTrue(gatedBorder.isNotEmpty(), "a margin-subset image needs a border-crossing candidate >= 0.5")
        for (f in EDGE_FIXTURES) assertTrue(images.single { it.file == f }.inMarginSubset, f)
    }

    @Test
    fun boxesAtThresholdAreTheSameAtFloorAndAtThreshold() {
        for (img in images) {
            val atFloor = libraryCandidates(img).filter { it.score >= THRESHOLD }
            val atThreshold = libraryCandidates(img, floor = THRESHOLD)
            assertEquals(atThreshold.size, atFloor.size, img.file)
            for ((a, b) in atThreshold.sortedBy { -it.score }.zip(atFloor.sortedBy { -it.score })) {
                assertTrue(a.l == b.l && a.t == b.t && a.r == b.r && a.b == b.b && a.score == b.score, img.file)
            }
        }
    }

    @Test
    fun theReferenceClampHasExactValues() {
        val lm = doubleArrayOf(-3.0, 5.0, 650.0, 700.0, 10.0, -1.0, 20.0, 20.0, 30.0, 30.0)
        val left = assertNotNull(clampReference(Cand(-5.0, 10.0, 20.0, 40.0, 0.9, lm), 640, 640))
        assertEquals(0.0, left.l)
        assertEquals(10.0, left.t)
        assertEquals(20.0, left.r)
        assertEquals(40.0, left.b)
        assertEquals(listOf(0.0, 5.0, 640.0, 640.0, 10.0, 0.0), left.lm.take(6))
        fun edges(c: Cand) = listOf(c.l, c.t, c.r, c.b)
        assertEquals(
            listOf(10.0, 0.0, 20.0, 40.0),
            edges(assertNotNull(clampReference(Cand(10.0, -7.0, 20.0, 40.0, 0.9, lm), 640, 640))),
        )
        assertEquals(
            listOf(630.0, 10.0, 640.0, 40.0),
            edges(assertNotNull(clampReference(Cand(630.0, 10.0, 650.0, 40.0, 0.9, lm), 640, 480))),
        )
        assertEquals(
            listOf(10.0, 470.0, 20.0, 480.0),
            edges(assertNotNull(clampReference(Cand(10.0, 470.0, 20.0, 490.0, 0.9, lm), 640, 480))),
        )
        assertNull(clampReference(Cand(-50.0, 10.0, -10.0, 40.0, 0.9, lm), 640, 640), "fully outside is dropped")
    }

    @Test
    fun onTheGatedBorderFixturesTheLibraryEdgeEqualsTheClampedReferenceEdgeExactly() {
        for (f in EDGE_FIXTURES) {
            val img = images.single { it.file == f }
            val ours = libraryCandidates(img)
            var checked = 0
            for ((k, raw) in img.raw.withIndex()) {
                if (!img.crossesBorder[k] || raw.score < THRESHOLD) continue
                val ref = assertNotNull(clampReference(raw, img.width, img.height))
                val mine = ours.maxBy { it.iou(ref) }
                if (raw.l < 0) assertEquals(0.0, mine.l, f)
                if (raw.t < 0) assertEquals(0.0, mine.t, f)
                if (raw.r > img.width) assertEquals(img.width.toDouble(), mine.r, f)
                if (raw.b > img.height) assertEquals(img.height.toDouble(), mine.b, f)
                checked++
            }
            assertTrue(checked > 0, f)
        }
    }

    /**
     * The public call at the documented threshold 0.5 (global pass, tiles and cross-pass merge, through
     * `FaceMasker.detect`) meets criteria 1-4 against the reference boxes >= 0.5 on the margin subset.
     */
    @Test
    fun thePublicDetectCallMeetsTheGateAtTheDocumentedThreshold() {
        val selected = images.filter { it.inMarginSubset }
        runTier(selected, band = false, name = "tier 1 (public detect, 0.5)") { img ->
            masker.detect(pixels(img)).faces.map { it.toCand() }
        }
    }

    /** Tier G, P3: the generic route (derived model G0) meets criteria 1-4 of tier 1 against OpenCV. */
    @Test
    fun tierGTheGenericRouteMeetsTheStrictGate() {
        val g0 = GenericParityTest.model(io.github.shishark.faceblur.testmodel.DerivedModels.Variant.G0)
        FaceMasker.forTest(g0, FaceMaskerConfig(), OrtModelRuntime()).use { generic ->
            runTier(images.filter { it.inMarginSubset }, band = false, name = "tier G (generic route)") { img ->
                generic.globalPassForTest(pixels(img), FLOOR.toFloat()).map { it.toCand() }
            }
        }
    }

    // ---- Negative controls: each must make the gate fail (ST-005 AC2, ADR 0009 Verification). ----

    private fun firstMarginImage() = images.first {
        it.inMarginSubset && it.raw.count { c -> c.score >= THRESHOLD } >= 2
    }

    @Test
    fun controlAReferenceBoxShiftedByOnePercentFails() {
        val img = firstMarginImage()
        val ref = clampedReference(img).toMutableList()
        val k = ref.indexOfFirst { it.score >= THRESHOLD }
        ref[k] = ref[k].with(dx = 0.01 * (ref[k].r - ref[k].l))
        assertTrue(compare(ref, libraryCandidates(img), band = false).violations.isNotEmpty())
    }

    @Test
    fun controlADuplicatedLibraryBoxFails() {
        val img = firstMarginImage()
        val ours = libraryCandidates(img)
        val dup = ours + ours.first { it.score >= THRESHOLD }
        assertTrue(compare(clampedReference(img), dup, band = false).violations.isNotEmpty())
    }

    @Test
    fun controlALibraryScoreShiftedByMoreThanDeltaFails() {
        val img = firstMarginImage()
        val ours = libraryCandidates(img).toMutableList()
        val k = ours.indexOfFirst { it.score >= THRESHOLD }
        ours[k] = ours[k].with(score = ours[k].score - (DELTA + 0.001))
        assertTrue(compare(clampedReference(img), ours, band = false).violations.isNotEmpty())
    }

    @Test
    fun controlARemovedReferenceBoxFails() {
        val img = firstMarginImage()
        val ref = clampedReference(img).toMutableList()
        ref.removeAt(ref.indexOfFirst { it.score >= THRESHOLD })
        assertTrue(compare(ref, libraryCandidates(img), band = false).violations.isNotEmpty())
    }

    @Test
    fun controlTier1bFailsOnAPlantedNearThresholdPairWithLowIouOrLargeScoreDifference() {
        val img = images.first { !it.inMarginSubset }
        val ref = clampedReference(img)
        val ours = libraryCandidates(img)
        val near = ref.indexOfFirst { it.score >= THRESHOLD - DELTA && it.score < THRESHOLD + DELTA }
        val mine = ours.indices.maxBy { ours[it].iou(ref[near]) }
        val shifted = ours.toMutableList().also { it[mine] = it[mine].with(dx = 0.012 * (it[mine].r - it[mine].l)) }
        assertTrue(compare(ref, shifted, band = true).violations.isNotEmpty(), "IoU about 0.98")
        val rescored = ours.toMutableList().also { it[mine] = it[mine].with(score = ref[near].score + DELTA + 0.002) }
        assertTrue(compare(ref, rescored, band = true).violations.isNotEmpty(), "score difference above delta")
    }

    @Test
    fun controlSkippingTheReferenceClampFailsOnTheEdgeFixtures() {
        for (f in EDGE_FIXTURES) {
            val img = images.single { it.file == f }
            assertTrue(compare(img.raw, libraryCandidates(img), band = false).violations.isNotEmpty(), f)
        }
    }

    @Test
    fun controlALibraryWithoutItsClampFailsTheInsideAssertionOnTheEdgeFixtures() {
        for (f in EDGE_FIXTURES) {
            val img = images.single { it.file == f }
            // The production pass minus the clip: same tensor, run and decoder, raw boxes (no scaling at 640 px).
            val session = OrtModelRuntime().open(model, 0)
            try {
                val raw = ModelDetector(
                    session,
                    model.profile,
                ).detect(PixelDetectionImage(pixels(img)), FLOOR.toFloat())
                val cands = raw.map {
                    Cand(
                        it.rect.left.toDouble(),
                        it.rect.top.toDouble(),
                        it.rect.right.toDouble(),
                        it.rect.bottom.toDouble(),
                        it.score.toDouble(),
                        DoubleArray(0),
                    )
                }
                assertTrue(libraryInsideImage(cands, img.width, img.height).isNotEmpty(), f)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun everyFixtureOpenedComesFromTestdata() {
        images.take(2).forEach { pixels(it) }
        assertTrue(Fixtures.opened.isNotEmpty())
        assertTrue(
            Fixtures.opened.all { Fixtures.isRegisteredPath(it) },
            "a path outside testdata/ and the verified local fixtures was opened",
        )
    }
}
