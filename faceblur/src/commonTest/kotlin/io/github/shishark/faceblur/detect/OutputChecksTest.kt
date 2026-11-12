// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.BoxFormat
import io.github.shishark.faceblur.ColorOrder
import io.github.shishark.faceblur.CoordinateSpace
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FakeDetectionImage
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.PixelScaling
import io.github.shishark.faceblur.RowLayout
import io.github.shishark.faceblur.TensorLayout
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.testmodel.ANY_SHA256
import io.github.shishark.faceblur.testmodel.Onnx
import io.github.shishark.faceblur.testmodel.pinnedTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The generic decoder and the per-pass checks of ADR 0012 decision 5, with fake sessions. */
class OutputChecksTest {
    private val scaling = PixelScaling(1f, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))

    private fun format(
        box: BoxFormat = BoxFormat.XYXY,
        space: CoordinateSpace = CoordinateSpace.INPUT_PIXELS,
        rows: RowLayout = RowLayout.ROWS,
        boxColumn: Int = 0,
        scoreColumn: Int = 4,
        scores: String? = null,
        nms: Boolean = false,
        color: ColorOrder = ColorOrder.BGR,
        layout: TensorLayout = TensorLayout.NCHW,
        pixelScaling: PixelScaling = scaling,
    ) = DetectionFormat.GenericDetections(
        "det",
        boxColumn,
        scores,
        scoreColumn,
        rows,
        box,
        space,
        color,
        layout,
        pixelScaling,
        nms,
    )

    /** A session that returns [rows] (each K values) as "det" ([1, N, K] or [1, K, N]) and optional scores. */
    private class GenericSession(
        val k: Int,
        var rows: List<FloatArray>,
        val columns: Boolean = false,
        var scoreRows: List<FloatArray>? = null,
        var override: ((Map<String, PassOutput>) -> Map<String, PassOutput>)? = null,
    ) : ModelSession {
        override val runtimeVersion = "test"
        override val executionProvider = "CPU"
        override val inputs = emptyList<io.github.shishark.faceblur.internal.runtime.SessionTensor>()
        override val outputs = emptyList<io.github.shishark.faceblur.internal.runtime.SessionTensor>()
        var lastInput: FloatArray? = null

        private fun tensor(r: List<FloatArray>, kk: Int): PassOutput {
            val n = r.size
            val data = FloatArray(n * kk)
            for (i in 0 until n) {
                for (j in 0 until kk) {
                    if (columns) {
                        data[j * n + i] = r[i][j]
                    } else {
                        data[i * kk + j] =
                            r[i][j]
                    }
                }
            }
            val shape = if (columns) {
                longArrayOf(
                    1,
                    kk.toLong(),
                    n.toLong(),
                )
            } else {
                longArrayOf(1, n.toLong(), kk.toLong())
            }
            return PassOutput(Onnx.FLOAT, shape, data)
        }

        override fun run(
            input: FloatArray,
            inputShape: LongArray,
            outputNames: List<String>,
            maxElements: Long,
            timeoutMillis: Long,
        ): Map<String, PassOutput> {
            lastInput = input.copyOf()
            val out = LinkedHashMap<String, PassOutput>()
            out["det"] = tensor(rows, k)
            scoreRows?.let { out["scores"] = tensor(it, it.first().size) }
            return override?.invoke(out) ?: out
        }

        override fun close() = Unit
    }

    private fun detector(session: ModelSession, f: DetectionFormat, w: Int = 640, h: Int = 640) =
        ModelDetector(session, DetectorProfile(f, w, h, 0.5f, ANY_SHA256))

    private fun detect(
        session: ModelSession,
        f: DetectionFormat,
        image: FakeDetectionImage = FakeDetectionImage(640, 640),
    ) = detector(session, f).detect(image, 0.5f)

    private fun codes(session: ModelSession, f: DetectionFormat = format()): List<String> =
        assertFailsWith<OutputCheckFailed> { detect(session, f) }.findings

    @Test
    fun everyBoxFormatAndCoordinateSpaceDecodesToTheSameBox() {
        val expected = listOf(100f, 50f, 300f, 250f)
        val cases = { rows: RowLayout ->
            mapOf(
                format(BoxFormat.XYXY, rows = rows) to floatArrayOf(100f, 50f, 300f, 250f, 0.9f),
                format(BoxFormat.XYWH, rows = rows) to floatArrayOf(100f, 50f, 200f, 200f, 0.9f),
                format(BoxFormat.CXCYWH, rows = rows) to floatArrayOf(200f, 150f, 200f, 200f, 0.9f),
                format(BoxFormat.XYXY, CoordinateSpace.NORMALIZED, rows) to
                    floatArrayOf(100f / 640f, 50f / 640f, 300f / 640f, 250f / 640f, 0.9f),
                format(rows = rows, boxColumn = 1, scoreColumn = 0) to floatArrayOf(0.9f, 100f, 50f, 300f, 250f),
            )
        }
        for (columns in listOf(false, true)) {
            for ((f, row) in cases(if (columns) RowLayout.COLUMNS else RowLayout.ROWS)) {
                // Two rows, so that rows and columns differ in shape: the second is below the threshold.
                val r = detect(GenericSession(5, listOf(row, FloatArray(5)), columns), f).single().rect
                val got = listOf(r.left, r.top, r.right, r.bottom)
                for (i in 0 until 4) assertEquals(expected[i], got[i], 1e-3f, "$f columns=$columns")
            }
        }
        // Separate scores tensor (softmax pair, column 1 = face).
        val two = format(scores = "scores", scoreColumn = 1)
        val s =
            GenericSession(4, listOf(floatArrayOf(100f, 50f, 300f, 250f)), scoreRows = listOf(floatArrayOf(0.1f, 0.9f)))
        assertEquals(0.9f, detect(s, two).single().score)
        // Negative control: an off-by-one column changes the result.
        val shifted = detect(
            GenericSession(6, listOf(floatArrayOf(7f, 100f, 50f, 300f, 250f, 0.9f))),
            format(boxColumn = 0, scoreColumn = 5),
        ).single().rect
        assertTrue(shifted.left != 100f, "a decoder reading the wrong column gives another box")
    }

    @Test
    fun theInputTensorFollowsTheDeclaredLayoutOrderAndScaling() {
        // One pixel (R 0x10, G 0x20, B 0x30) at the top left; the rest is padding.
        val image = object : io.github.shishark.faceblur.detect.DetectionImage {
            override val width = 1
            override val height = 1

            override fun cropScaled(x: Int, y: Int, w: Int, h: Int, targetMaxSide: Int) = this

            override fun readArgb() = intArrayOf(0xFF102030.toInt())

            override fun release() = Unit
        }
        val s = GenericSession(5, listOf(floatArrayOf(1f, 1f, 2f, 2f, 0.1f)))
        val sc = PixelScaling(255f, floatArrayOf(0.5f, 0f, 0f), floatArrayOf(2f, 1f, 1f))
        detector(
            s,
            format(color = ColorOrder.RGB, layout = TensorLayout.NHWC, pixelScaling = sc),
            128,
            128,
        ).detect(image, 0.5f)
        val t = s.lastInput!!
        assertEquals((0x10 / 255f - 0.5f) / 2f, t[0], "R first, mean and std of channel 0")
        assertEquals(0x20 / 255f, t[1])
        assertEquals(0x30 / 255f, t[2])
        assertEquals(-0.25f, t[3], "padding is black after scaling")
        detector(s, format(color = ColorOrder.BGR, layout = TensorLayout.NCHW), 128, 128).detect(image, 0.5f)
        val u = s.lastInput!!
        assertEquals(0x30.toFloat(), u[0], "B first")
        assertEquals(0x20.toFloat(), u[128 * 128])
        assertEquals(0x10.toFloat(), u[2 * 128 * 128])
        assertEquals(0f, u[1])
    }

    @Test
    fun brokenOutputFailsWithItsCodeAndIsNeverClampedDroppedOrTruncated() {
        val ok = floatArrayOf(100f, 100f, 200f, 200f, 0.9f)
        fun row(vararg v: Float) = GenericSession(5, listOf(v))
        assertEquals(listOf("SCORE_RANGE"), codes(row(100f, 100f, 200f, 200f, 1.2f)))
        assertEquals(listOf("SCORE_RANGE"), codes(row(100f, 100f, 200f, 200f, 1.01f)))
        assertEquals(listOf("SCORE_RANGE"), codes(row(100f, 100f, 200f, 200f, -0.01f)))
        assertEquals(listOf("SCORE_RANGE"), codes(row(100f, 100f, 200f, 200f, 3.5f)), "logit output")
        assertEquals(listOf("NON_FINITE"), codes(row(100f, 100f, 200f, 200f, Float.NaN)))
        assertEquals(listOf("NON_FINITE"), codes(row(100f, Float.POSITIVE_INFINITY, 200f, 200f, 0.9f)))
        assertEquals(listOf("BOX_INVERTED"), codes(row(200f, 100f, 100f, 200f, 0.9f)))
        assertEquals(listOf("BOX_INVERTED"), codes(row(100f, 100f, 100f, 200f, 0.9f)), "zero width is an error")
        assertEquals(listOf("BOX_INVERTED"), codes(row(100f, 100f, 200f, 100f, 0.9f)), "zero height is an error")
        // Technical bounds: corners in [-2, 3] input sides, size at most 5 sides (W = H = 640).
        assertEquals(listOf("BOX_BOUNDS"), codes(row(-1280.01f, 0f, 10f, 10f, 0.9f)))
        assertEquals(listOf("BOX_BOUNDS"), codes(row(0f, 0f, 1920.2f, 10f, 0.9f)))
        assertEquals(listOf("BOX_BOUNDS"), codes(row(0f, -1281f, 10f, 10f, 0.9f)))
        assertEquals(listOf("BOX_BOUNDS"), codes(row(0f, 0f, 10f, 1920.2f, 0.9f)))
        assertEquals(
            1,
            detect(row(-1280f, 0f, 1920f, 10f, 0.9f), format()).size,
            "exactly at the bounds: accepted (5 W wide)",
        )
        assertEquals(1, detect(row(0f, -1280f, 10f, 1920f, 0.9f), format()).size)
        // A row below the threshold is not a detection: its box is not read.
        assertEquals(0, detect(row(Float.NaN, 0f, 0f, 0f, 0.2f), format()).size)
        assertEquals(1, detect(GenericSession(5, listOf(ok)), format()).size, "control")
    }

    @Test
    fun shapesTypesAndCapsAreChecked() {
        val ok = listOf(floatArrayOf(100f, 100f, 200f, 200f, 0.9f))
        fun replaced(f: (PassOutput) -> PassOutput) =
            GenericSession(5, ok, override = { m -> m.mapValues { f(it.value) } })
        assertEquals(listOf("OUTPUT_TYPE"), codes(replaced { PassOutput(11, it.shape, null) }))
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            codes(
                replaced {
                    PassOutput(it.elementType, longArrayOf(1, 1, 5, 1), it.data)
                },
            ),
            "wrong rank",
        )
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            codes(
                replaced {
                    PassOutput(it.elementType, longArrayOf(1, 2, 5), it.data)
                },
            ),
            "longer than its data",
        )
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            codes(
                replaced {
                    PassOutput(it.elementType, it.shape, it.data!! + 1f)
                },
            ),
            "data longer than declared",
        )
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            codes(
                replaced {
                    PassOutput(it.elementType, longArrayOf(1, -1, 5), it.data)
                },
            ),
        )
        assertEquals(
            listOf("TOO_MANY_ELEMENTS"),
            codes(
                replaced {
                    PassOutput(it.elementType, longArrayOf(1, 838_861, 5), null)
                },
            ),
        )
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            codes(GenericSession(5, ok, override = { m -> m + ("extra" to m.getValue("det")) })),
            "an extra output",
        )
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            codes(
                GenericSession(5, ok, override = {
                    emptyMap()
                }),
            ),
            "a missing output",
        )
        // Rows: 65,536 are accepted (even all above a low threshold, no candidate cap), 65,537 are refused.
        val many = List(65_536) { floatArrayOf(it % 640f, 0f, it % 640f + 1f, 1f, 0.01f) }
        assertEquals(0, detect(GenericSession(5, many), format()).size, "below 0.5")
        val manyAbove = List(65_536) {
            floatArrayOf(
                (it % 600).toFloat(),
                (it / 600 % 600).toFloat(),
                (it % 600) + 1f,
                (it / 600 % 600) + 1f,
                0.9f,
            )
        }
        assertEquals(40_000, detect(GenericSession(5, manyAbove.take(40_000)), format(nms = true)).size, "at the cap")
        assertEquals(
            listOf("TOO_MANY_DETECTIONS"),
            codes(GenericSession(5, manyAbove.take(40_001)), format(nms = true)),
        )
        assertEquals(listOf("TOO_MANY_ROWS"), codes(GenericSession(5, many + many.take(1)), format(nms = true)))
        // The in-pass NMS runs on 65,536 candidates above a low threshold without refusing their number.
        val nms = detector(GenericSession(5, many), format())
        nms.detect(FakeDetectionImage(640, 640), 0.005f)
        assertEquals(1, nms.inPassNmsRuns)
        val inGraph = detector(GenericSession(5, ok), format(nms = true))
        inGraph.detect(FakeDetectionImage(640, 640), 0.5f)
        assertEquals(0, inGraph.inPassNmsRuns, "nmsInGraph skips only the in-pass NMS")
    }

    /** The detection cap stops the in-pass NMS as soon as the kept list passes it, not after the whole list. */
    @Test
    fun theInPassNmsStopsAtTheCap() {
        val faces = List(1_000) {
            io.github.shishark.faceblur.geometry.DetectedFace(
                io.github.shishark.faceblur.geometry.RectF(it * 2f, 0f, it * 2f + 1f, 1f),
                0.9f,
            )
        }
        var reads = 0
        val e = assertFailsWith<OutputCheckFailed> { InPassNms.nmsLikeOpenCv(faces, 0.3f, { reads++ }, maxKept = 10) }
        assertEquals(listOf("TOO_MANY_DETECTIONS"), e.findings)
        assertEquals(0, reads, "stopped at the 11th kept box, before the first cancellation read at 256")
        assertEquals(10, InPassNms.nmsLikeOpenCv(faces.take(10), 0.3f, maxKept = 10).size, "at the cap")
        assertEquals(
            listOf("TOO_MANY_DETECTIONS"),
            assertFailsWith<OutputCheckFailed> { InPassNms.nmsLikeOpenCv(faces.take(1), 0.3f, maxKept = 0) }.findings,
        )
    }

    /** The in-pass NMS work bound: one unit per pair check, exactly at the bound passes, one more fails closed. */
    @Test
    fun theInPassNmsPairChecksAreCountedAndBounded() {
        val faces = List(1_000) {
            io.github.shishark.faceblur.geometry.DetectedFace(
                io.github.shishark.faceblur.geometry.RectF(it * 2f, 0f, it * 2f + 1f, 1f),
                0.9f,
            )
        }
        // 1,000 disjoint boxes are all kept: candidate i is checked against the i kept before it.
        val pairs = 1_000L * 999 / 2
        val exact = NmsWork(pairs)
        var reads = 0
        assertEquals(1_000, InPassNms.nmsLikeOpenCv(faces, 0.3f, { reads++ }, work = exact).size)
        assertEquals(pairs, exact.used)
        assertEquals(3 + (pairs / 65_536).toInt(), reads, "every 256 candidates and every 65,536 units of work")
        val below = NmsWork(pairs - 1)
        val e = assertFailsWith<OutputCheckFailed> { InPassNms.nmsLikeOpenCv(faces, 0.3f, work = below) }
        assertEquals(listOf("TOO_MANY_NMS_PAIR_CHECKS"), e.findings)
        assertEquals(pairs, e.pairChecks, "the count when it stopped (one above the bound) travels with the finding")
        assertEquals(pairs - 1, e.pairCheckLimit)
        assertEquals(pairs, below.used, "the photo's total is kept when the run fails")
        assertEquals(null, e.maskCount)
    }

    /**
     * Regression check for parallel instances: the NMS loop counts its pair checks in a call-local variable and
     * writes the photo's [NmsWork] once per run, however many pairs it compares. A per-pair write to a heap field
     * (the earlier `tick` per pair) made separate instances on parallel threads slow each other down; this counts
     * those writes instead of timing anything, so it is deterministic.
     */
    @Test
    fun theNmsWorkIsWrittenOncePerRunNotOncePerPairCheck() {
        fun disjoint(n: Int) = List(n) {
            val x = (it % 1_000) * 2f
            val y = (it / 1_000) * 2f
            io.github.shishark.faceblur.geometry.DetectedFace(
                io.github.shishark.faceblur.geometry.RectF(x, y, x + 1f, y + 1f),
                0.9f,
            )
        }
        val work = NmsWork(Long.MAX_VALUE)
        var expected = 0L
        for ((run, n) in listOf(2, 1_000, 2_500).withIndex()) {
            InPassNms.nmsLikeOpenCv(disjoint(n), 0.3f, work = work)
            expected += n.toLong() * (n - 1) / 2
            assertEquals(expected, work.used, "the total is summed over the runs of one photo")
            assertEquals(run + 1, work.commits, "one write per run, not one per pair check")
        }
        work.reset(5)
        assertEquals(0, work.commits, "a new photo starts with no write")
        assertEquals(0L, work.used)
        assertEquals(5L, work.max)
        // A run stopped by cancellation keeps the work done so far, still with one write. The first read of the
        // cancellation (candidate 256) comes after 256 × 255 / 2 = 32,640 pair checks.
        val cancelled = NmsWork(Long.MAX_VALUE)
        val cancel: () -> Unit = { throw IllegalStateException("cancelled") }
        val faces = disjoint(1_000)
        assertFailsWith<IllegalStateException> { InPassNms.nmsLikeOpenCv(faces, 0.3f, cancel, work = cancelled) }
        assertEquals(32_640L, cancelled.used)
        assertEquals(1, cancelled.commits)
    }

    /** The bound applies per photo: the detector sums its passes until the tiler starts the next photo. */
    @Test
    fun theInPassNmsWorkIsSummedOverThePassesOfOnePhoto() {
        // Three disjoint boxes per pass: 0 + 1 + 2 = 3 pair checks per pass.
        val rows = listOf(
            floatArrayOf(10f, 10f, 20f, 20f, 0.9f),
            floatArrayOf(100f, 10f, 110f, 20f, 0.9f),
            floatArrayOf(200f, 10f, 210f, 20f, 0.9f),
        )
        val det = detector(GenericSession(5, rows), format())
        det.beginPhoto(6)
        det.detect(FakeDetectionImage(640, 640), 0.5f)
        det.detect(FakeDetectionImage(640, 640), 0.5f)
        assertEquals(6L, det.nmsWork)
        val e = assertFailsWith<OutputCheckFailed> { det.detect(FakeDetectionImage(640, 640), 0.5f) }
        assertEquals(listOf("TOO_MANY_NMS_PAIR_CHECKS"), e.findings)
        det.beginPhoto(6)
        assertEquals(0L, det.nmsWork, "a new photo starts at 0")
        det.detect(FakeDetectionImage(640, 640), 0.5f)
        // Through the tiler: a 640 × 640 image is one pass; a bound below its pair checks fails the photo.
        val tiler = TiledFaceDetector(det, DetectionSettings(0.5f, maxNmsWork = 2))
        val t = assertFailsWith<OutputCheckFailed> { tiler.detect(FakeDetectionImage(640, 640)) }
        assertEquals(listOf("TOO_MANY_NMS_PAIR_CHECKS"), t.findings)
        val ok = TiledFaceDetector(det, DetectionSettings(0.5f, maxNmsWork = 3)).detect(FakeDetectionImage(640, 640))
        assertEquals(3L, ok.nmsWork)
    }

    @Test
    fun theInPassNmsChecksCancellation() {
        val many = List(1_000) { floatArrayOf((it % 600).toFloat(), 0f, (it % 600) + 1f, 1f, 0.9f) }
        var calls = 0
        assertFailsWith<IllegalStateException> {
            detector(GenericSession(5, many), format()).detect(FakeDetectionImage(640, 640), 0.5f) {
                if (++calls == 2) throw IllegalStateException("cancelled")
            }
        }
        assertEquals(2, calls)
    }

    @Test
    fun runtimeFailuresAndTimeoutsAreTypedAndNeverChained() {
        val failing = object : ModelSession by GenericSession(5, emptyList()) {
            override fun run(
                input: FloatArray,
                inputShape: LongArray,
                outputNames: List<String>,
                maxElements: Long,
                timeoutMillis: Long,
            ): Map<String, PassOutput> = throw IllegalStateException("/path/in/a/runtime/message")
        }
        val e = assertFailsWith<OutputCheckFailed> { detect(failing, format()) }
        assertEquals(listOf("RUN_FAILED"), e.findings)
        assertEquals(null, e.cause)
        val slow = object : ModelSession by GenericSession(5, emptyList()) {
            override fun run(
                input: FloatArray,
                inputShape: LongArray,
                outputNames: List<String>,
                maxElements: Long,
                timeoutMillis: Long,
            ): Map<String, PassOutput> = throw io.github.shishark.faceblur.internal.runtime.SessionTimeout()
        }
        assertEquals(listOf("RUN_TIMEOUT"), assertFailsWith<OutputCheckFailed> { detect(slow, format()) }.findings)
    }

    @Test
    fun yunetOutputsMustHaveTheirExactShapes() {
        val profile = yunetProfile()
        val model: DetectorModel = DetectorModel.load(
            ModelSource.fromBytes(Onnx.yunetModel()),
            profile.pinnedTo(Onnx.yunetModel()),
        )
        val base = FakeSession(io.github.shishark.faceblur.OneFaceRunner(), model)
        val bad = object : ModelSession by base {
            override fun run(
                input: FloatArray,
                inputShape: LongArray,
                outputNames: List<String>,
                maxElements: Long,
                timeoutMillis: Long,
            ) = base.run(input, inputShape, outputNames, maxElements, timeoutMillis).mapValues { (k, v) ->
                if (k == "bbox_8") PassOutput(v.elementType, longArrayOf(1, 3200, 8), v.data) else v
            }
        }
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            assertFailsWith<OutputCheckFailed> {
                ModelDetector(bad, profile).detect(FakeDetectionImage(640, 640), 0.5f)
            }.findings,
        )
        // The element count recorded by the contract run is enforced on later passes.
        val recorded = ModelDetector(base, profile, mapOf("cls_8" to 6401L))
        assertEquals(
            listOf("OUTPUT_SHAPE"),
            assertFailsWith<OutputCheckFailed> {
                recorded.detect(FakeDetectionImage(640, 640), 0.5f)
            }.findings,
        )
        assertEquals(null, ModelDetector.elementCount(longArrayOf(Long.MAX_VALUE, 2)))
        assertEquals(1L, ModelDetector.elementCount(longArrayOf()))
    }
}
