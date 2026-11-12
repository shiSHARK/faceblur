// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.BoxFormat
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.ExpectedFace
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.testmodel.DerivedModels.Variant
import io.github.shishark.faceblur.testmodel.pinnedTo
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `checkProfile` with real models (FR-MA-011, ST-054 AC1, AC2, AC4; ADR 0012 "Verification"). The expected faces are
 * the OpenCV FaceDetectorYN boxes of the committed reference data, an oracle independent of the library.
 */
class ProfileCheckJvmTest {
    private val image = "images/face-0003542-s0625-x0000-y0000.png"

    @BeforeTest
    fun install() = JvmTestRuntime.install()

    /** The OpenCV boxes of [file] at or above [threshold] from the committed reference data. */
    private fun expected(file: String, threshold: Double = 0.5): List<ExpectedFace> {
        @Suppress("UNCHECKED_CAST")
        val ref = Fixtures.referenceData()

        @Suppress("UNCHECKED_CAST")
        val entry = (ref["images"] as List<Map<String, Any?>>).single { it["file"] == file }

        @Suppress("UNCHECKED_CAST")
        return (entry["faces"] as List<Map<String, Any?>>)
            .filter { (it["score"] as Number).toDouble() >= threshold }
            .map {
                val x = (it["x"] as Number).toFloat()
                val y = (it["y"] as Number).toFloat()
                ExpectedFace(x, y, x + (it["w"] as Number).toFloat(), y + (it["h"] as Number).toFloat())
            }
    }

    /** The derived generic model G0 declared with [boxFormat] (G0 is really XYXY). */
    private fun g0(boxFormat: BoxFormat): DetectorModel {
        val right = GenericParityTest.profile(Variant.G0)
        val f = right.format as DetectionFormat.GenericDetections
        val format = DetectionFormat.GenericDetections(
            f.boxesOutput, f.boxColumn, f.scoresOutput, f.scoreColumn, f.rows, boxFormat,
            f.coordinates, f.colorOrder, f.tensorLayout, f.scaling, f.nmsInGraph,
        )
        val bytes = GenericParityTest.bytes(Variant.G0)
        val profile = DetectorProfile(format, 640, 640, right.scoreThreshold, right.expectedSha256).pinnedTo(bytes)
        return DetectorModel.load(ModelSource.fromBytes(bytes), profile)
    }

    @Test
    fun theReferenceModelWithTheExampleProfileFindsTheKnownFace() {
        val faces = expected(image)
        assertEquals(1, faces.size, "the fixture has one face at 0.5")
        FaceMasker(TestModels.yunet()).use { m ->
            val img = Fixtures.image(image)
            val before = img.data.copyOf()
            val r = m.checkProfile(img, faces)
            assertTrue(r.allFound, "$r")
            assertTrue(r.bestIoU.single() >= 0.9f, "box parity with the OpenCV reference: $r")
            assertEquals(1, r.detections.size)
            assertContentEquals(before, img.data, "the helper writes no pixel")
        }
    }

    @Test
    fun aWrongBoxFormatFindsNothingWhileCreationSucceeds() {
        // Control: the generic route with the right declaration finds the face.
        FaceMasker(g0(BoxFormat.XYXY)).use {
            assertTrue(it.checkProfile(Fixtures.image(image), expected(image)).allFound)
        }
        // Residual risk (FR-MA-011): the same model declared with centre form loads, creates and runs without error;
        // only the helper shows that the profile does not find the face.
        val wrong = g0(BoxFormat.CXCYWH)
        FaceMasker(wrong).use { m ->
            val r = m.checkProfile(Fixtures.image(image), expected(image))
            assertFalse(r.allFound, "$r")
            m.maskInPlace(Fixtures.image(image))
        }
    }

    @Test
    fun theHelperPrintsNothing() {
        val out = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        val capture = PrintStream(out, true, "UTF-8")
        try {
            System.setOut(capture)
            System.setErr(capture)
            FaceMasker(TestModels.yunet()).use { it.checkProfile(Fixtures.image(image), expected(image)) }
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        val text = out.toString("UTF-8")
        assertEquals("", text, "load, creation and the helper print nothing")
        assertFalse(text.contains(image.substringAfterLast('/')), "canary: no file name")
    }
}
