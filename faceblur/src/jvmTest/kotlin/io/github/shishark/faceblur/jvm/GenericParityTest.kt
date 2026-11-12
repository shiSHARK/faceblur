// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import io.github.shishark.faceblur.BoxFormat
import io.github.shishark.faceblur.ColorOrder
import io.github.shishark.faceblur.CoordinateSpace
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceBox
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.PixelScaling
import io.github.shishark.faceblur.RowLayout
import io.github.shishark.faceblur.TensorLayout
import io.github.shishark.faceblur.detect.DetectionSettings
import io.github.shishark.faceblur.detect.ModelDetector
import io.github.shishark.faceblur.detect.TiledFaceDetector
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.image.PixelDetectionImage
import io.github.shishark.faceblur.internal.model.Sha256
import io.github.shishark.faceblur.testmodel.ANY_SHA256
import io.github.shishark.faceblur.testmodel.DerivedModels
import io.github.shishark.faceblur.testmodel.DerivedModels.Mutant
import io.github.shishark.faceblur.testmodel.DerivedModels.Variant
import io.github.shishark.faceblur.testmodel.pinnedTo
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR 0009 tier G on the JVM: the generic-format decoder against the YuNet route with real weights. The derived
 * models are built at test time from the pinned reference fixture and never committed. Every bound below is fixed in
 * advance (ADR 0009) and never derived from a run of the code under test.
 */
class GenericParityTest {
    internal companion object {
        /** P1 box tolerance in input pixels (ADR 0009). */
        const val BOX_TOLERANCE = 1e-3f

        /** P2 IoU bar (ADR 0009). */
        const val MIN_IOU = 0.99

        /**
         * SHA-256 of the derived models built by this writer from the pinned source (reproducibility pins: a change of
         * the writer or the source changes them).
         */
        val PINS = mapOf(
            Variant.G0 to "26242a6aaf657d045e0f561edcf1f203f25aa4c933638d9ca73d510804ef5f30",
            Variant.G2 to "075c983bbf6c0468425fe874a684a9eb38cff4cfc0ee01e46745feb5335045a6",
            Variant.G3 to "8e8fc4270f5da47354c4805cae5d0a7ac6e47b6520f5bdddaba9879402666346",
        )

        /** SHA-256 of G1 (NMS in the graph, [DerivedModels.buildG1] with its default thresholds). */
        const val G1_PIN = "160de8d85ce2140c27a732b354b033fb01ead90d036c1443284fca9df6fbff77"

        val source: ByteArray by lazy { TestModels.yunetBytes() }
        val g1: ByteArray by lazy { DerivedModels.buildG1(source) }

        /** G0's format with `nmsInGraph` = [nmsInGraph]: G1's profile (or, with false, its counter control). */
        fun profileG1(nmsInGraph: Boolean = true, threshold: Float = 0.5f): DetectorProfile {
            val f = profile(Variant.G0, threshold).format as DetectionFormat.GenericDetections
            val format = DetectionFormat.GenericDetections(
                f.boxesOutput, f.boxColumn, f.scoresOutput, f.scoreColumn, f.rows, f.boxFormat,
                f.coordinates, f.colorOrder, f.tensorLayout, f.scaling, nmsInGraph,
            )
            return DetectorProfile(format, 640, 640, threshold, ANY_SHA256)
        }

        fun modelG1(bytes: ByteArray = g1, nmsInGraph: Boolean = true): DetectorModel =
            DetectorModel.load(ModelSource.fromBytes(bytes), profileG1(nmsInGraph).pinnedTo(bytes))
        val derived = HashMap<Pair<Variant, Mutant>, ByteArray>()

        fun bytes(v: Variant, m: Mutant = Mutant.NONE): ByteArray =
            synchronized(derived) { derived.getOrPut(v to m) { DerivedModels.build(source, v, m) } }

        fun profile(v: Variant, threshold: Float = 0.5f): DetectorProfile {
            val identity = PixelScaling(1f, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
            val format =
                when (v) {
                    Variant.G0 -> DetectionFormat.GenericDetections(
                        DerivedModels.OUTPUT, 0, null, 4, RowLayout.ROWS, BoxFormat.XYXY,
                        CoordinateSpace.INPUT_PIXELS, ColorOrder.BGR, TensorLayout.NCHW, identity,
                    )

                    Variant.G2 -> DetectionFormat.GenericDetections(
                        DerivedModels.OUTPUT, 0, null, 4, RowLayout.ROWS, BoxFormat.XYXY,
                        CoordinateSpace.INPUT_PIXELS, ColorOrder.RGB, TensorLayout.NHWC,
                        PixelScaling(255f, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f)),
                    )

                    Variant.G3 -> DetectionFormat.GenericDetections(
                        DerivedModels.OUTPUT, 1, null, 0, RowLayout.ROWS, BoxFormat.XYWH,
                        CoordinateSpace.INPUT_PIXELS, ColorOrder.BGR, TensorLayout.NCHW, identity,
                    )
                }
            return DetectorProfile(format, 640, 640, threshold, ANY_SHA256)
        }

        fun model(v: Variant, m: Mutant = Mutant.NONE, threshold: Float = 0.5f): DetectorModel =
            bytes(v, m).let { DetectorModel.load(ModelSource.fromBytes(it), profile(v, threshold).pinnedTo(it)) }

        /** The lossless reference fixtures (the images of the reference data). */
        val fixtures: List<String> by lazy {
            @Suppress("UNCHECKED_CAST")
            val ref = Fixtures.referenceData()
            @Suppress("UNCHECKED_CAST")
            (ref["images"] as List<Map<String, Any?>>).map { it["file"] as String }
        }

        fun iou(a: FaceBox, b: FaceBox): Double {
            val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (w <= 0 || h <= 0) return 0.0
            val inter = w.toDouble() * h
            return inter / (a.width.toDouble() * a.height + b.width.toDouble() * b.height - inter)
        }

        /** P2 violations for one image: equal count and every one-to-one pair (greedy by IoU) at IoU ≥ 0.99. */
        fun p2(yunet: List<FaceBox>, generic: List<FaceBox>): List<String> {
            val v = ArrayList<String>()
            if (yunet.size != generic.size) v += "count ${yunet.size} != ${generic.size}"
            val pairs = yunet.indices.flatMap { i ->
                generic.indices.map { j -> Triple(i, j, iou(yunet[i], generic[j])) }
            }
                .filter { it.third >= 0.5 }.sortedByDescending { it.third }
            val ui = HashSet<Int>()
            val uj = HashSet<Int>()
            for ((i, j, x) in pairs) {
                if (i in ui || j in uj) continue
                ui += i
                uj += j
                if (x < MIN_IOU) v += "pair IoU $x"
            }
            if (ui.size != yunet.size || uj.size != generic.size) v += "unmatched boxes"
            return v
        }
    }

    private val opened = ArrayList<AutoCloseable>()

    private fun masker(model: DetectorModel) = FaceMasker.forTest(model, FaceMaskerConfig(), OrtModelRuntime()).also {
        opened +=
            it
    }

    @AfterTest
    fun closeAll() = opened.forEach { it.close() }

    @Test
    fun theWriterKeepsTheSourceAndIsReproducible() {
        val (nodes, initializers) = DerivedModels.counts(source)
        println("tier G source: nodes=$nodes initializers=$initializers")
        val actual = LinkedHashMap<Variant, String>()
        for (v in Variant.entries) {
            val b = bytes(v)
            assertTrue(DerivedModels.sourcePartsPreserved(source, b), "$v keeps every source node and initializer")
            assertTrue(b.contentEquals(DerivedModels.build(source, v)), "$v is deterministic")
            actual[v] = Sha256.hex(b)
            println("tier G $v: sha256=${actual[v]} size=${b.size}")
            // Loads through the public API with its profile.
            assertEquals(listOf(1L, 8400L, 15L), model(v).info.outputs.single().shape)
        }
        assertEquals(PINS, actual)
        // Negative control: a mutated builder gives another model.
        assertTrue(Sha256.hex(bytes(Variant.G0, Mutant.GRID_OFFSET)) != PINS.getValue(Variant.G0))
    }

    /** P1: G0 rows against the Kotlin YuNet decode on the same inputs: scores bit-identical, boxes within 1e-3 px. */
    @Test
    fun p1RowsEqualTheKotlinDecoder() {
        val yunet = TestModels.yunet()
        val ys = OrtModelRuntime().open(yunet, 0).also { opened += AutoCloseable { it.close() } }
        val gs = OrtModelRuntime().open(model(Variant.G0), 0).also { opened += AutoCloseable { it.close() } }
        val names = intArrayOf(8, 16, 32).flatMap { listOf("cls_$it", "obj_$it", "bbox_$it") }
        val shape = longArrayOf(1, 3, 640, 640)
        var compared = 0
        var maxBoxDiff = 0f
        for (file in fixtures.take(6)) {
            val img = Fixtures.image(file)
            val input = FloatArray(3 * 640 * 640)
            ModelDetector(
                ys,
                yunet.profile,
            ).fillInput(PixelDetectionImage(img).readArgb(), img.width, img.height, input)
            val raw = ys.run(input, shape, names, Long.MAX_VALUE, 0)
            val rows = gs.run(
                input,
                shape,
                listOf(DerivedModels.OUTPUT),
                Long.MAX_VALUE,
                0,
            ).getValue(DerivedModels.OUTPUT).data!!
            var r = 0
            for (s in intArrayOf(8, 16, 32)) {
                val cols = 640 / s
                val cls = raw.getValue("cls_$s").data!!
                val obj = raw.getValue("obj_$s").data!!
                val bbox = raw.getValue("bbox_$s").data!!
                for (idx in 0 until cols * cols) {
                    val score = sqrt(cls[idx].coerceIn(0f, 1f) * obj[idx].coerceIn(0f, 1f))
                    assertEquals(score.toBits(), rows[r * 15 + 4].toBits(), "$file row $r score")
                    val c = idx % cols
                    val rr = idx / cols
                    val cx = (c + bbox[idx * 4]) * s
                    val cy = (rr + bbox[idx * 4 + 1]) * s
                    val w = kotlin.math.exp(bbox[idx * 4 + 2]) * s
                    val h = kotlin.math.exp(bbox[idx * 4 + 3]) * s
                    val x1 = cx - w / 2f
                    val y1 = cy - h / 2f
                    for ((k, expected) in listOf(x1, y1, x1 + w, y1 + h).withIndex()) {
                        val got = rows[r * 15 + k]
                        if (expected.isFinite() && expected < 1e6f) {
                            val d = abs(expected - got)
                            maxBoxDiff = maxOf(maxBoxDiff, d)
                            assertTrue(d <= BOX_TOLERANCE, "$file row $r column $k: $expected vs $got")
                        }
                    }
                    r++
                    compared++
                }
            }
        }
        println("tier G P1: rows=$compared maxBoxDiff=$maxBoxDiff")
    }

    /** P2: the generic route equals the YuNet route on every lossless fixture; P4: G2 and G3 equal G0. */
    @Test
    fun p2GenericRouteEqualsTheYuNetRouteAndP4VariantsEqualG0() {
        val yunet = masker(TestModels.yunet())
        val g0 = masker(model(Variant.G0))
        val g2 = masker(model(Variant.G2))
        val g3 = masker(model(Variant.G3))
        val violations = ArrayList<String>()
        var boxes = 0
        var worst = 1.0
        for (file in fixtures) {
            val img = Fixtures.image(file)
            val a = yunet.detect(img).faces
            val b = g0.detect(img).faces
            violations += p2(a, b).map { "$file: $it" }
            for ((x, y) in a.zip(b)) worst = minOf(worst, iou(x, y))
            boxes += a.size
            assertEquals(b, g2.detect(img).faces, "$file: G2 (RGB, NHWC, 0..1) identical to G0")
            assertEquals(b, g3.detect(img).faces, "$file: G3 (score first, XYWH) identical to G0")
        }
        println("tier G P2: images=${fixtures.size} boxes=$boxes worstIoU=$worst")
        assertEquals(emptyList(), violations)
        assertTrue(boxes >= 100, "enough boxes to compare: $boxes")
    }

    /** P6: builder mutants (a wrong grid offset, swapped columns) fail the P2 criteria. */
    @Test
    fun p6WriterMutantsFail() {
        val yunet = masker(TestModels.yunet())
        for (mutant in listOf(Mutant.GRID_OFFSET, Mutant.SWAPPED_COLUMNS)) {
            val g = masker(model(Variant.G0, mutant))
            val violations = fixtures.take(10).flatMap { f ->
                val img = Fixtures.image(f)
                p2(yunet.detect(img).faces, runCatching { g.detect(img).faces }.getOrElse { emptyList() })
            }
            assertTrue(violations.isNotEmpty(), "$mutant must fail")
        }
    }

    /** P3 needs the reference data; it runs in [YuNetReferenceStrictTest]. A fully masked image: control for P2. */
    @Test
    fun aGenericMaskerMasksLikeTheYuNetMasker() {
        val file = fixtures.first()
        val a = Fixtures.image(file).let { IntArrayPixels(it.width, it.height, it.data.copyOf()) }
        val b = Fixtures.image(file).let { IntArrayPixels(it.width, it.height, it.data.copyOf()) }
        val ra = masker(TestModels.yunet()).maskInPlace(a)
        val rb = masker(model(Variant.G0)).maskInPlace(b)
        assertEquals(ra.masks.size, rb.masks.size)
        assertTrue(ra.masks.isNotEmpty())
    }

    /**
     * G1 keeps every source part, is deterministic, matches its pin and declares a dynamic row count: ONNX Runtime
     * itself reports the row dimension as symbolic (-1), and the library's report carries no value for it.
     */
    @Test
    fun g1WriterKeepsTheSourceAndIsReproducible() {
        assertTrue(DerivedModels.sourcePartsPreserved(source, g1), "G1 keeps every source node and initializer")
        assertTrue(g1.contentEquals(DerivedModels.buildG1(source)), "G1 is deterministic")
        println("tier G G1: sha256=${Sha256.hex(g1)} size=${g1.size}")
        assertEquals(G1_PIN, Sha256.hex(g1))
        val shape = modelG1().info.outputs.single().shape
        assertEquals(listOf(1L, null, 15L), shape, "G1's output in the library's report: the row count is symbolic")
        assertEquals(listOf(1L, -1L, 15L), runtimeOutputShape(g1), "ONNX Runtime's dynamic-dimension marker is -1")
        assertEquals(listOf(1L, 8400L, 15L), runtimeOutputShape(bytes(Variant.G0)), "control: G0's rows are static")
        // A different in-graph cut-off gives another model (the negative control below is not G1).
        assertTrue(Sha256.hex(DerivedModels.buildG1(source, scoreThreshold = 0.9f)) != G1_PIN)
    }

    /** The output shape of [model] as ONNX Runtime reports it (a symbolic dimension is -1), from a plain session. */
    private fun runtimeOutputShape(model: ByteArray): List<Long> {
        check(System.getenv("ORT_DISABLE_TELEMETRY") == "1") { "set ORT_DISABLE_TELEMETRY=1 before starting the JVM" }
        val env = OrtEnvironment.getEnvironment()
        env.setTelemetry(false)
        return OrtSession.SessionOptions().use { o ->
            o.addConfigEntry("session.load_model_format", "ONNX")
            o.addConfigEntry("session.allow_released_opsets_only", "1")
            o.addConfigEntry("session.intra_op.allow_spinning", "0")
            env.createSession(model, o).use { session ->
                (session.outputInfo.values.single().info as TensorInfo).shape.toList()
            }
        }
    }

    /** One detector and tiler on an opened session: the counters behind P5 (the masker keeps its own private). */
    private class Route(model: DetectorModel) : AutoCloseable {
        private val session = OrtModelRuntime().open(model, 0)
        val detector = ModelDetector(session, model.profile)
        val tiler = TiledFaceDetector(detector, DetectionSettings(minScore = model.profile.scoreThreshold))

        override fun close() = session.close()
    }

    /**
     * P5 (ADR 0012, NFR-ACC-009): G1 (NMS in the graph, `nmsInGraph = true`) against the G0 route, with the P2
     * criteria fixed in advance (equal count, one-to-one pairs at IoU >= 0.99), on every lossless reference fixture
     * (640 px: the global pass only) and on 2x enlargements of them (1280 px: global pass plus tiles, so the
     * cross-pass merge has duplicates to remove). With `nmsInGraph = true` the in-pass NMS never runs and counts no
     * work; the cross-pass merge still runs. On the reference set (one pass per photo) the graph's NMS leaves the
     * merge nothing to remove, so the suppression is G1's own (negative control 3 shows the merge would hide a no-op).
     */
    @Test
    fun p5NmsInGraphEqualsTheG0Route() {
        val g0 = masker(model(Variant.G0))
        val g1Masker = masker(modelG1())
        val route = Route(modelG1()).also { opened += it }
        val sets = listOf(
            "reference" to fixtures.map { it to Fixtures.image(it) },
            "enlarged" to fixtures.map { it to enlarged(Fixtures.image(it)) },
        )
        val violations = ArrayList<String>()
        var mergedAway = 0
        var mergePairChecks = 0L
        for ((name, images) in sets) {
            var boxes = 0
            var identical = 0
            var worst = 1.0
            var passes = 0
            var merged = 0
            for ((file, img) in images) {
                val a = g0.detect(img).faces
                val b = g1Masker.detect(img).faces
                violations += p2(a, b).map { "$name $file: $it" }
                if (a == b) identical++
                for ((x, y) in a.zip(b)) worst = minOf(worst, iou(x, y))
                boxes += a.size
                val outcome = route.tiler.detect(PixelDetectionImage(img))
                assertEquals(b, outcome.faces.map { it.toFaceBox() }, "$file: the counted route is the masker's route")
                assertEquals(0L, outcome.nmsWork, "$file: no in-pass NMS work with nmsInGraph")
                passes += outcome.passes
                merged += outcome.mergeCandidates - outcome.faces.size
                mergePairChecks += outcome.mergePairChecks
            }
            mergedAway += merged
            if (name == "reference") assertEquals(0, merged, "one pass: G1's in-graph NMS removed every duplicate")
            println(
                "tier G P5 $name: images=${images.size} boxes=$boxes identical=$identical worstIoU=$worst " +
                    "passes=$passes mergedAway=$merged",
            )
            assertTrue(boxes >= 100, "$name: enough boxes to compare: $boxes")
        }
        println("tier G P5: mergePairChecks=$mergePairChecks inPassNmsRuns=${route.detector.inPassNmsRuns}")
        assertEquals(emptyList(), violations)
        assertEquals(0, route.detector.inPassNmsRuns, "the in-pass NMS is skipped")
        assertTrue(mergePairChecks > 0 && mergedAway > 0, "the cross-pass merge still runs and removes duplicates")
    }

    /** [img] enlarged 2x by pixel repetition (no new pixel values). */
    private fun enlarged(img: IntArrayPixels): IntArrayPixels {
        val w = img.width * 2
        val pixels = IntArray(w * img.height * 2) { img.data[(it / w / 2) * img.width + (it % w) / 2] }
        return IntArrayPixels(w, img.height * 2, pixels)
    }

    /**
     * P5 negative controls. (1) G1 whose graph keeps only scores above 0.9, under a profile threshold of 0.5: the
     * documented residual risk (faces below the graph's cut-off are lost) and the P5 check must see it. (2) G1's
     * bytes under a profile with `nmsInGraph = false`: the in-pass NMS runs and counts work, so the counters above
     * can tell the two cases apart; one fixture is enough for that (it only has to show the counters move). (3) G1
     * whose graph NMS is a no-op (IoU threshold 1.0): on the reference set the merge now removes duplicates, which is
     * what the `merged == 0` assertion of P5 catches.
     */
    @Test
    fun p5NegativeControls() {
        val g0 = masker(model(Variant.G0))
        val strict = masker(modelG1(DerivedModels.buildG1(source, scoreThreshold = 0.9f)))
        val lost = fixtures.flatMap { f ->
            val img = Fixtures.image(f)
            p2(g0.detect(img).faces, strict.detect(img).faces)
        }
        assertTrue(lost.isNotEmpty(), "a graph cut-off above the profile threshold must fail P5")
        val counted = Route(modelG1(nmsInGraph = false)).also { opened += it }
        val outcome = counted.tiler.detect(PixelDetectionImage(Fixtures.image(fixtures.first())))
        assertEquals(outcome.passes, counted.detector.inPassNmsRuns, "nmsInGraph = false: one in-pass NMS per pass")
        assertTrue(outcome.nmsWork > 0, "nmsInGraph = false: in-pass NMS work is counted")
        val noOp = Route(modelG1(DerivedModels.buildG1(source, iouThreshold = 1.0f))).also { opened += it }
        val mergedAway = fixtures.sumOf { f ->
            val o = noOp.tiler.detect(PixelDetectionImage(Fixtures.image(f)))
            assertEquals(1, o.passes, "$f: the reference set has one pass per photo")
            o.mergeCandidates - o.faces.size
        }
        println("tier G P5 control 3 (no-op graph NMS) reference: mergedAway=$mergedAway")
        assertTrue(mergedAway > 0, "a no-op graph NMS leaves duplicates that only the merge removes")
    }
}
