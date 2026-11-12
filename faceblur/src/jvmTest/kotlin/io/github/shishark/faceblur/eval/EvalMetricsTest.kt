// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.eval

import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.jvm.GenericParityTest
import io.github.shishark.faceblur.jvm.TestModels
import io.github.shishark.faceblur.mask.MaskGeometry
import io.github.shishark.faceblur.testmodel.DerivedModels.Variant
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The measures, input formats and orientation of faceblur-eval against hand-computed values (DX-MA-001, ST-053 AC1). */
class EvalMetricsTest {
    private val a = RectF(0f, 0f, 100f, 100f)
    private val b = RectF(200f, 0f, 300f, 100f)
    private val ignoredC = RectF(400f, 0f, 500f, 100f)
    private val gt = listOf(a, b, ignoredC)
    private val ignored = listOf(false, false, true)

    /** A rectangle mask exactly over [a]; one over the left half of [b] (x 200 to 250). */
    private val masks = listOf(
        MaskGeometry(50f, 50f, 50f, 50f, MaskShape.RECTANGLE),
        MaskGeometry(225f, 50f, 25f, 50f, MaskShape.RECTANGLE),
    )

    @Test
    fun countsEqualTheHandComputedValues() {
        val dets = listOf(
            RectF(0f, 0f, 100f, 100f), // IoU 1 with a
            RectF(210f, 0f, 310f, 100f), // IoU 9,000 / 11,000 = 0.818 with b
            RectF(410f, 10f, 450f, 50f), // inside the ignored face: IoU 0.16, but all of its area inside -> not false
            RectF(600f, 600f, 650f, 650f), // nothing: false mask
        )
        // Coverage: a 16 x 16 = 256 samples all inside -> 1.0; b: columns 0..7 of 16 inside -> 0.5.
        assertEquals(1.0, EvalMetrics.covered(a, masks))
        assertEquals(0.5, EvalMetrics.covered(b, masks))
        assertEquals(0.0, EvalMetrics.covered(RectF(900f, 900f, 950f, 950f), masks))
        assertEquals(0.0, EvalMetrics.covered(RectF(5f, 5f, 5f, 9f), masks), "empty box")
        assertEquals(ImageCounts(2, 1, 2, 1), EvalMetrics.image(gt, ignored, dets, masks, 0.5f, 0.8f))
        assertEquals(ImageCounts(2, 2, 2, 1), EvalMetrics.image(gt, ignored, dets, masks, 0.5f, 0.5f), "0.5 >= 0.5")
        // At match IoU 0.9 the detection on b no longer matches and becomes a false mask.
        assertEquals(ImageCounts(2, 1, 1, 2), EvalMetrics.image(gt, ignored, dets, masks, 0.9f, 0.8f))
        // One-to-one: a second detection on a is a false mask.
        val dup = dets + RectF(0f, 0f, 100f, 90f)
        assertEquals(ImageCounts(2, 1, 2, 2), EvalMetrics.image(gt, ignored, dup, masks, 0.5f, 0.8f))
        // No detections, no masks: every face missed.
        assertEquals(ImageCounts(2, 0, 0, 0), EvalMetrics.image(gt, ignored, emptyList(), emptyList(), 0.5f, 0.8f))
        // An ellipse mask: the sample points of a box inside its circle's inscribed square are all covered.
        val circle = listOf(MaskGeometry(50f, 50f, 80f, 80f, MaskShape.ELLIPSE))
        assertEquals(1.0, EvalMetrics.covered(a, circle))
    }

    @Test
    fun totalsAddUpAndRatiosAreExact() {
        var t = Totals()
        t += ImageCounts(2, 1, 2, 1)
        t += ImageCounts(0, 0, 0, 3)
        t += ImageCounts(1, 1, 1, 0)
        t = t.failed(2)
        assertEquals(Totals(4, 3, 1, 5, 2, 3, 4, 1), t)
        assertEquals(2.0 / 5, t.protection)
        assertEquals(3.0 / 5, t.recall)
        assertEquals(3.0 / 7, t.precision)
        assertEquals(4.0 / 4, t.falseMasksPerImage)
        assertEquals(
            listOf(0.0, 0.0, 0.0, 0.0),
            Totals().let {
                listOf(it.protection, it.recall, it.precision, it.falseMasksPerImage)
            },
        )
    }

    @Test
    fun groundTruthFormatAndItsErrorsNameNoFile() {
        val ok = """{"format": "faceblur-gt", "version": 1, "images": [
            {"file": "sub/one.png", "width": 200, "height": 100,
             "faces": [{"x": 1, "y": 2, "w": 3, "h": 4}, {"x": 5, "y": 6, "w": 7, "h": 8, "ignore": true}]},
            {"file": "two.jpg", "faces": []}]}"""
        assertEquals(
            listOf(
                GtImage("sub/one.png", 200, 100, listOf(GtFace(1f, 2f, 3f, 4f), GtFace(5f, 6f, 7f, 8f, true))),
                GtImage("two.jpg", 0, 0, emptyList()),
            ),
            GroundTruthFile.parse(ok),
        )
        val canary = "CANARY-secret"
        val bad = listOf(
            """[]""",
            """{"format": "other", "version": 1, "images": []}""",
            """{"format": "faceblur-gt", "version": 2, "images": []}""",
            """{"format": "faceblur-gt", "version": 1}""",
            """{"format": "faceblur-gt", "version": 1, "images": [{"file": "/abs/$canary.png", "faces": []}]}""",
            """{"format": "faceblur-gt", "version": 1, "images": [{"file": "../$canary.png", "faces": []}]}""",
            """{"format": "faceblur-gt", "version": 1, "images": [{"file": "$canary.png", "faces": []},
                {"file": "$canary.png", "faces": []}]}""",
            """{"format": "faceblur-gt", "version": 1, "images": [{"file": "$canary.png"}]}""",
            """{"format": "faceblur-gt", "version": 1, "images": [{"file": "$canary.png", "faces": [{"x": 1, "y": 1, "w": 0, "h": 1}]}]}""",
            """{"format": "faceblur-gt", "version": 1, "images": [{"file": "$canary.png", "faces": [{"x": 1, "y": 1, "w": 1}]}]}""",
            """{"format": "faceblur-gt", "version": 1, "images": [{"file": "$canary.png", "width": 1.5, "faces": []}]}""",
        )
        for (text in bad) {
            val e = assertFailsWith<EvalInputError>(text) { GroundTruthFile.parse(text) }
            assertFalse(e.message!!.contains(canary), "error names no file: ${e.message}")
        }
        // Image boxes scale to the decoded size when the file gives another size.
        val scaled = FaceblurEval.gtBoxes(GtImage("x", 200, 100, listOf(GtFace(10f, 20f, 30f, 40f))), 400, 50)
        assertEquals(listOf(RectF(20f, 10f, 80f, 30f)), scaled)
    }

    @Test
    fun profileFileGivesTheSameProfileAsTheConstructors() {
        val yunet = """{"format": "YuNetLayout", "inputWidth": 640, "inputHeight": 640, "scoreThreshold": 0.5,
            "expectedSha256": "${TestModels.YUNET_SHA256}"}"""
        assertEquals(TestModels.yunetProfile(), ProfileFile.parse(yunet))
        val g0 = GenericParityTest.profile(Variant.G0)
        val output = (g0.format as DetectionFormat.GenericDetections).boxesOutput
        val generic = """{"format": "GenericDetections", "inputWidth": 640, "inputHeight": 640, "scoreThreshold": 0.5,
            "expectedSha256": "${g0.expectedSha256}", "minMaskExpandX": 1.6, "minMaskExpandY": 1.8,
            "generic": {"boxesOutput": "$output", "boxColumn": 0, "scoresOutput": null, "scoreColumn": 4,
              "rows": "ROWS", "boxFormat": "XYXY", "coordinates": "INPUT_PIXELS", "colorOrder": "BGR",
              "tensorLayout": "NCHW", "scaling": {"divisor": 1, "mean": [0, 0, 0], "std": [1, 1, 1]}}}"""
        assertEquals(g0, ProfileFile.parse(generic))
        assertFailsWith<EvalInputError> { ProfileFile.parse(generic.replace("\"XYXY\"", "\"XY\"")) }
        assertFailsWith<EvalInputError> { ProfileFile.parse("""{"format": "Other"}""") }
        assertFailsWith<EvalInputError> { ProfileFile.parse(yunet.replace("640,", "640.5,")) }
        // The library's own checks apply.
        assertFailsWith<FaceMaskException.InvalidConfiguration> { ProfileFile.parse(yunet.replace("0.5", "1.0")) }
    }

    @Test
    fun jpegOrientationIsReadAndApplied() {
        // A JPEG start, an APP1 Exif block (big-endian TIFF, one IFD entry 0x0112 = 6), then start of scan.
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8, // header, IFD at 8
            0, 1, // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 6, 0, 0, // orientation, SHORT, count 1, value 6
            0, 0, 0, 0, // no next IFD
        )
        val app1 = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val len = app1.size + 2
        val jpeg =
            byteArrayOf(
                0xFF.toByte(),
                0xD8.toByte(),
                0xFF.toByte(),
                0xE1.toByte(),
                (len shr 8).toByte(),
                len.toByte(),
            ) +
                app1 + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2)
        assertEquals(6, ExifOrientation.read(jpeg))
        assertEquals(
            1,
            ExifOrientation.read(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())),
        )
        assertEquals(1, ExifOrientation.read(jpeg.copyOf(10)), "truncated")
        // 3 x 2 image, pixels 1..6 row by row; orientation 6 (rotate 90 clockwise) gives 2 x 3.
        val img = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, 3, 2, intArrayOf(1, 2, 3, 4, 5, 6), 0, 3)
        fun pixels(o: Int) = ExifOrientation.apply(img, o).let { p ->
            Triple(
                p.width,
                p.height,
                p.data.map {
                    it and
                        0xFFFFFF
                },
            )
        }
        assertEquals(Triple(3, 2, listOf(1, 2, 3, 4, 5, 6)), pixels(1))
        assertEquals(Triple(2, 3, listOf(4, 1, 5, 2, 6, 3)), pixels(6))
        assertEquals(Triple(3, 2, listOf(6, 5, 4, 3, 2, 1)), pixels(3))
        assertEquals(Triple(2, 3, listOf(3, 6, 2, 5, 1, 4)), pixels(8))
        assertEquals(Triple(3, 2, listOf(3, 2, 1, 6, 5, 4)), pixels(2))
        assertEquals(Triple(3, 2, listOf(4, 5, 6, 1, 2, 3)), pixels(4))
        assertEquals(Triple(2, 3, listOf(1, 4, 2, 5, 3, 6)), pixels(5))
        assertEquals(Triple(2, 3, listOf(6, 3, 5, 2, 4, 1)), pixels(7))
    }

    @Test
    fun theTelemetryVariableMustBeExactlyOne() {
        assertTrue(FaceblurEval.telemetryOff("1"))
        for (v in listOf(null, "", "0", "true", " 1", "1 ")) assertFalse(FaceblurEval.telemetryOff(v), "$v")
    }

    @Test
    fun unreadablePhotosStayInTheTotalsWithTheirFacesMissed() {
        val t = Totals() + ImageCounts(2, 2, 2, 1)
        val u = t.unreadable(3).unreadable(0)
        assertEquals(Totals(3, 2, 1, 5, 2, 2, 1, 0, 2), u)
        assertEquals(2.0 / 5, u.protection, "the unreadable faces count as missed")
        assertEquals(1.0, u.falseMasksPerImage, "false masks per photo that was read")
    }

    @Test
    fun profileTypeErrorsAreInputErrors() {
        val generic = """{"format": "GenericDetections", "inputWidth": 640, "inputHeight": 640, "scoreThreshold": 0.5,
            "expectedSha256": "${TestModels.YUNET_SHA256}",
            "generic": {"boxesOutput": "b", "boxColumn": 0, "scoresOutput": 7, "scoreColumn": 4,
              "rows": "ROWS", "boxFormat": "XYXY", "coordinates": "INPUT_PIXELS", "colorOrder": "BGR",
              "tensorLayout": "NCHW", "scaling": {"divisor": 1, "mean": [0, 0, 0], "std": [1, 1, 1]}}}"""
        assertFailsWith<EvalInputError> { ProfileFile.parse(generic) }
    }

    @Test
    fun theToolHasNoNetworkCode() {
        val dir = java.io.File("src/jvmTest/kotlin/io/github/shishark/faceblur/eval")
        val runner = listOf("OrtTensorRunner.kt", "JvmModelFileReader.kt").map {
            java.io.File(dir.parentFile, "jvm/$it")
        }
        val files =
            dir.listFiles { f -> f.name.endsWith(".kt") && !f.name.endsWith("Test.kt") }.orEmpty().toList() + runner
        assertTrue(files.size >= 6 && files.all { it.isFile }, "the tool's sources and the runtime it uses were found")
        val banned = listOf("java.net", "javax.net", "HttpClient", "Socket", "URL(", "URI(", "okhttp", "ktor")
        for (f in files) {
            val text = f.readText()
            for (b in banned) assertFalse(text.contains(b), "${f.name} contains $b")
        }
    }
}
