// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.PointF
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.geometry.toDetectedFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FaceBoxTest {
    private val box = FaceBox.create(10f, 20f, 50f, 80f, 0.75f)

    @Test
    fun sizeIsDerivedFromTheEdges() {
        assertEquals(40f, box.width)
        assertEquals(60f, box.height)
    }

    @Test
    fun equalityComparesAllFiveValues() {
        val same = FaceBox.create(10f, 20f, 50f, 80f, 0.75f)
        assertEquals(box, same)
        assertEquals(box.hashCode(), same.hashCode())
        assertTrue(box.equals(box))
        assertFalse(box.equals("FaceBox"))
        assertFalse(box.equals(null))
        assertNotEquals(box, FaceBox.create(11f, 20f, 50f, 80f, 0.75f))
        assertNotEquals(box, FaceBox.create(10f, 21f, 50f, 80f, 0.75f))
        assertNotEquals(box, FaceBox.create(10f, 20f, 51f, 80f, 0.75f))
        assertNotEquals(box, FaceBox.create(10f, 20f, 50f, 81f, 0.75f))
        assertNotEquals(box, FaceBox.create(10f, 20f, 50f, 80f, 0.76f))
    }

    @Test
    fun floatEqualityFollowsDataClassSemantics() {
        // Bitwise, like data classes: 0.0 differs from -0.0; hashCode stays consistent with equals.
        assertNotEquals(FaceBox.create(0f, 0f, 1f, 1f, 1f), FaceBox.create(-0f, 0f, 1f, 1f, 1f))
    }

    @Test
    fun theFactoryFailsClosedOnInvalidValues() {
        val bad =
            listOf(
                listOf(Float.NaN, 0f, 1f, 1f, 1f),
                listOf(0f, 0f, Float.POSITIVE_INFINITY, 1f, 1f),
                listOf(0f, 0f, 1f, 1f, Float.NaN),
                listOf(5f, 0f, 1f, 1f, 1f),
                listOf(0f, 5f, 1f, 1f, 1f),
            )
        for (v in bad) {
            assertFailsWith<CoreFailure.InvalidFaceBox>("$v") { FaceBox.create(v[0], v[1], v[2], v[3], v[4]) }
        }
        assertEquals(0f, FaceBox.create(1f, 1f, 1f, 1f, 0f).width, "control: an empty but ordered box is allowed")
    }

    @Test
    fun toStringNamesEveryValue() {
        assertEquals("FaceBox(left=10.0, top=20.0, right=50.0, bottom=80.0, score=0.75)", box.toString())
    }

    @Test
    fun convertsToAndFromTheInternalFaceWithoutLandmarks() {
        val internal = DetectedFace(RectF(10f, 20f, 50f, 80f), 0.75f, listOf(PointF(1f, 2f)))
        assertEquals(box, internal.toFaceBox())
        val back = box.toDetectedFace()
        assertEquals(RectF(10f, 20f, 50f, 80f), back.rect)
        assertEquals(0.75f, back.score)
        assertTrue(back.landmarks.isEmpty())
    }
}
