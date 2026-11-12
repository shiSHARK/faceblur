// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `FaceMasker.checkProfile` (FR-MA-011, ADR 0012 decision 7) with a scripted one-face runner. */
class ProfileCheckTest {
    private val runner = OneFaceRunner()
    private val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(runner))

    private fun image() = IntArrayPixels(640, 640, gradientPixels(640, 640))

    /** The one face the runner reports, as the expected face with exactly its box. */
    private fun theFace(): FaceBox = masker.detect(image()).faces.single()

    private fun ExpectedFace(b: FaceBox) = ExpectedFace(b.left, b.top, b.right, b.bottom)

    @Test
    fun reportsFoundAndNotFoundPerExpectedFaceWithTheDetections() {
        val face = theFace()
        val elsewhere = ExpectedFace(500f, 500f, 560f, 560f)
        val r = masker.checkProfile(image(), listOf(ExpectedFace(face), elsewhere))
        assertEquals(listOf(face), r.detections)
        assertEquals(listOf(1f, 0f), r.bestIoU)
        assertFalse(r.allFound)
        assertTrue(masker.checkProfile(image(), listOf(ExpectedFace(face))).allFound)
        assertFalse(masker.checkProfile(image(), listOf(elsewhere)).allFound)
        // The result's lists are the library's read-only lists, not the caller's or a mutable copy.
        assertFalse(r.bestIoU is MutableList<*>)
        assertFalse(r.detections is MutableList<*>)
    }

    @Test
    fun minIoUIsInclusiveAndItsDefaultIsOneHalf() {
        val f = theFace()
        // Same height, twice the width: intersection = face area, union = 2 x face area, IoU exactly 0.5.
        val wide = ExpectedFace(f.left, f.top, f.right + f.width, f.bottom)
        val r = masker.checkProfile(image(), listOf(wide))
        assertEquals(0.5f, r.bestIoU.single())
        assertTrue(r.allFound, "IoU equal to the default minIoU 0.5 counts as found")
        assertTrue(masker.checkProfile(image(), listOf(wide), 0.5f).allFound)
        assertFalse(masker.checkProfile(image(), listOf(wide), 0.51f).allFound)
        assertTrue(masker.checkProfile(image(), listOf(ExpectedFace(f)), 1f).allFound, "minIoU 1 is allowed")
        // Touching edges do not overlap.
        val touching = ExpectedFace(f.right, f.top, f.right + 10f, f.bottom)
        assertEquals(listOf(0f), masker.checkProfile(image(), listOf(touching)).bestIoU)
    }

    @Test
    fun noDetectionGivesZeroOverlapAndNotFound() {
        val none = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(NoFaceRunner()))
        val r = none.checkProfile(image(), listOf(ExpectedFace(10f, 10f, 50f, 50f)))
        assertEquals(emptyList(), r.detections)
        assertEquals(listOf(0f), r.bestIoU)
        assertFalse(r.allFound)
    }

    @Test
    fun invalidExpectedFacesAndMinIoUAreRejectedBeforeAnyPass() {
        val bad: List<() -> Any> = listOf(
            { ExpectedFace(Float.NaN, 0f, 10f, 10f) },
            { ExpectedFace(0f, Float.NEGATIVE_INFINITY, 10f, 10f) },
            { ExpectedFace(0f, 0f, Float.POSITIVE_INFINITY, 10f) },
            { ExpectedFace(0f, 0f, 10f, Float.NaN) },
            { ExpectedFace(10f, 0f, 10f, 10f) },
            { ExpectedFace(11f, 0f, 10f, 10f) },
            { ExpectedFace(0f, 10f, 10f, 10f) },
            { ExpectedFace(0f, 11f, 10f, 10f) },
        )
        for (make in bad) {
            assertEquals("expectedFaces", assertFailsWith<FaceMaskException.InvalidConfiguration> { make() }.parameter)
        }
        val runsBefore = runner.runs
        assertEquals(
            "expectedFaces",
            assertFailsWith<FaceMaskException.InvalidConfiguration> {
                masker.checkProfile(image(), emptyList())
            }.parameter,
        )
        val one = listOf(ExpectedFace(0f, 0f, 1f, 1f))
        for (minIoU in listOf(0f, -0.1f, Float.NaN, 1.0001f, Float.POSITIVE_INFINITY)) {
            val e = assertFailsWith<FaceMaskException.InvalidConfiguration>("$minIoU") {
                masker.checkProfile(image(), one, minIoU)
            }
            assertEquals("minIoU", e.parameter)
        }
        assertEquals(runsBefore, runner.runs, "no pass ran for an invalid call")
        // Negative edges and boxes outside the image are valid expected faces.
        assertFalse(masker.checkProfile(image(), listOf(ExpectedFace(-20f, -20f, -10f, -10f))).allFound)
    }

    @Test
    fun neverRunsAtCreationAndNeverWritesThePixels() {
        // Creation runs exactly one pass: the contract run on grey (the helper is not called).
        val fresh = OneFaceRunner()
        val m = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(fresh))
        assertEquals(1, fresh.runs)
        val img = image()
        val before = img.data.copyOf()
        m.checkProfile(img, listOf(ExpectedFace(0f, 0f, 1f, 1f)))
        assertContentEquals(before, img.data, "checkProfile never writes the image")
        assertEquals(2, fresh.runs, "one detection pass for a 640 x 640 image")
    }

    @Test
    fun followsTheInstanceRulesAndCopiesTheList() {
        val face = theFace()
        val list = mutableListOf(ExpectedFace(face))
        val r = masker.checkProfile(image(), list)
        list += ExpectedFace(500f, 500f, 560f, 560f)
        assertEquals(1, r.bestIoU.size)
        val cancelled = object : CancellationToken {
            override val isCancellationRequested = true
        }
        assertFailsWith<FaceMaskException.Cancelled> { masker.checkProfile(image(), list, 0.5f, cancelled) }
        assertFailsWith<FaceMaskException.ImageTooLarge> { masker.checkProfile(IntArrayPixels(65_536, 1), list) }
        masker.close()
        assertFailsWith<FaceMaskException.Closed> { masker.checkProfile(image(), list) }
    }

    @Test
    fun valueSemanticsAndDebugText() {
        val a = ExpectedFace(1f, 2f, 3f, 4f)
        assertEquals(ExpectedFace(1f, 2f, 3f, 4f), a)
        assertEquals(ExpectedFace(1f, 2f, 3f, 4f).hashCode(), a.hashCode())
        assertFalse(a == ExpectedFace(1f, 2f, 3f, 5f))
        assertFalse(a.equals("x"))
        assertEquals("ExpectedFace(left=1.0, top=2.0, right=3.0, bottom=4.0)", a.toString())
        assertEquals(listOf(1f, 2f, 3f, 4f), listOf(a.left, a.top, a.right, a.bottom))
        val r = ProfileCheckResult.of(emptyList(), listOf(a), 0.5f)
        assertEquals("ProfileCheckResult(detections=0, bestIoU=[0.0], allFound=false)", r.toString())
    }

    /** A runner that never reports a face. */
    private class NoFaceRunner : io.github.shishark.faceblur.detect.TensorRunner by OneFaceRunner() {
        override fun run(input: FloatArray): Map<String, FloatArray> =
            OneFaceRunner().run(input).mapValues { (_, v) -> FloatArray(v.size) }
    }
}
