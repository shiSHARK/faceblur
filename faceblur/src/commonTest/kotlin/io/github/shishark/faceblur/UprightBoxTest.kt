// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `UprightBox.fromRaw` (ADR 0015 decision 6, owner's decision on the plan frame): all eight EXIF orientations on an
 * asymmetric 4 x 2 raw frame with one marked pixel, the inverse round trip, the edge order and the refusals.
 */
class UprightBoxTest {
    /** The raw frame: 4 px wide, 2 px high; the marked pixel is column 1 of row 0, so its box is (1, 0)-(2, 1). */
    private val rawWidth = 4
    private val rawHeight = 2
    private val markX = 1
    private val markY = 0

    /**
     * Written from the EXIF definition of the Orientation tag (where row 0 and column 0 of the stored image are shown):
     * 1 row 0 top / column 0 left; 2 top / right; 3 bottom / right; 4 bottom / left; 5 left / top; 6 right / top;
     * 7 right / bottom; 8 left / bottom. Columns: orientation, the upright box of the marked pixel (left, top, right,
     * bottom), the upright size (width, height), the marked pixel's upright position (x, y).
     */
    private val table = listOf(
        Row(1, 1f, 0f, 2f, 1f, 4, 2, 1, 0),
        Row(2, 2f, 0f, 3f, 1f, 4, 2, 2, 0),
        Row(3, 2f, 1f, 3f, 2f, 4, 2, 2, 1),
        Row(4, 1f, 1f, 2f, 2f, 4, 2, 1, 1),
        Row(5, 0f, 1f, 1f, 2f, 2, 4, 0, 1),
        Row(6, 1f, 1f, 2f, 2f, 2, 4, 1, 1),
        Row(7, 1f, 2f, 2f, 3f, 2, 4, 1, 2),
        Row(8, 0f, 2f, 1f, 3f, 2, 4, 0, 2),
    )

    private class Row(
        val orientation: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val width: Int,
        val height: Int,
        val pixelX: Int,
        val pixelY: Int,
    )

    /**
     * An independent derivation of the upright image, pixel by pixel, from the definition's row/column placement:
     * the stored pixel at (column c, row r) is shown at the place the tag puts row r and column c.
     */
    private fun displayed(orientation: Int): Array<IntArray> {
        val w = rawWidth
        val h = rawHeight
        val turned = orientation >= 5
        val outW = if (turned) h else w
        val outH = if (turned) w else h
        val out = Array(outH) { IntArray(outW) }
        for (r in 0 until h) {
            for (c in 0 until w) {
                val value = if (c == markX && r == markY) 1 else 0
                // Where stored row r and stored column c end up (the definition's "row 0 is ..., column 0 is ...").
                val (x, y) = when (orientation) {
                    1 -> c to r

                    // row 0 top, column 0 left
                    2 -> (w - 1 - c) to r

                    // row 0 top, column 0 right
                    3 -> (w - 1 - c) to (h - 1 - r)

                    // row 0 bottom, column 0 right
                    4 -> c to (h - 1 - r)

                    // row 0 bottom, column 0 left
                    5 -> r to c

                    // row 0 left, column 0 top
                    6 -> (h - 1 - r) to c

                    // row 0 right, column 0 top
                    7 -> (h - 1 - r) to (w - 1 - c)

                    // row 0 right, column 0 bottom
                    else -> r to (w - 1 - c) // row 0 left, column 0 bottom
                }
                out[y][x] = value
            }
        }
        return out
    }

    @Test
    fun everyOrientationMatchesTheTableAndTheDefinition() {
        for (row in table) {
            val u = UprightBox.fromRaw(1f, 0f, 2f, 1f, rawWidth, rawHeight, row.orientation)
            val o = row.orientation
            assertEquals(row.left, u.left, "orientation $o left")
            assertEquals(row.top, u.top, "orientation $o top")
            assertEquals(row.right, u.right, "orientation $o right")
            assertEquals(row.bottom, u.bottom, "orientation $o bottom")
            assertEquals(row.width, u.imageWidth, "orientation $o width")
            assertEquals(row.height, u.imageHeight, "orientation $o height")
            // The box is exactly the marked pixel's cell in the upright image.
            assertEquals(row.pixelX.toFloat(), u.left)
            assertEquals(row.pixelY.toFloat(), u.top)
            val image = displayed(o)
            assertEquals(row.height, image.size, "orientation $o: the derived image's height")
            assertEquals(row.width, image[0].size)
            val marked = image.withIndex().flatMap { (y, line) ->
                line.withIndex().filter { it.value == 1 }.map {
                    it.index to
                        y
                }
            }
            assertEquals(listOf(row.pixelX to row.pixelY), marked, "orientation $o: the marked pixel of the derivation")
        }
        // Orientations 5 to 8 swap the frame's sides; 1 to 4 keep them.
        assertEquals(setOf(5, 6, 7, 8), table.filter { it.width == rawHeight }.map { it.orientation }.toSet())
    }

    /** The inverse orientation maps each result back onto the input box (6 and 8 are each other's inverse). */
    @Test
    fun theInverseMapsEveryResultBack() {
        val inverse = mapOf(1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5, 6 to 8, 7 to 7, 8 to 6)
        val boxes =
            listOf(floatArrayOf(1f, 0f, 2f, 1f), floatArrayOf(0.25f, 0.5f, 3.5f, 1.75f), floatArrayOf(-1f, -2f, 5f, 3f))
        for (o in 1..8) {
            for (b in boxes) {
                val u = UprightBox.fromRaw(b[0], b[1], b[2], b[3], rawWidth, rawHeight, o)
                val back = UprightBox.fromRaw(
                    u.left,
                    u.top,
                    u.right,
                    u.bottom,
                    u.imageWidth,
                    u.imageHeight,
                    inverse.getValue(o),
                )
                assertEquals(UprightBox.fromRaw(b[0], b[1], b[2], b[3], rawWidth, rawHeight, 1), back, "orientation $o")
            }
        }
    }

    @Test
    fun theEdgesAreInOrderForCornersGivenInEitherOrder() {
        for (o in 1..8) {
            val forward = UprightBox.fromRaw(0.5f, 0.25f, 3f, 1.5f, rawWidth, rawHeight, o)
            val reversed = UprightBox.fromRaw(3f, 1.5f, 0.5f, 0.25f, rawWidth, rawHeight, o)
            assertEquals(forward, reversed, "orientation $o")
            assertTrue(forward.left <= forward.right && forward.top <= forward.bottom, "orientation $o")
        }
        // A box partly outside the frame is converted as given (the Mask factories check coordinates later).
        val outside = UprightBox.fromRaw(-10f, -5f, 2f, 1f, rawWidth, rawHeight, 6)
        assertEquals(1f, outside.left)
        assertEquals(-10f, outside.top)
        assertEquals(7f, outside.right)
        assertEquals(2f, outside.bottom)
    }

    @Test
    fun invalidInputIsRefusedWithTheParameterName() {
        for (o in listOf(0, 9, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val e = assertFailsWith<FaceMaskException.InvalidConfiguration>("orientation $o") {
                UprightBox.fromRaw(0f, 0f, 1f, 1f, 4, 2, o)
            }
            assertEquals("orientation", e.parameter)
        }
        for (w in listOf(0, -1)) {
            val e =
                assertFailsWith<FaceMaskException.InvalidConfiguration> { UprightBox.fromRaw(0f, 0f, 1f, 1f, w, 2, 1) }
            assertEquals("rawWidth", e.parameter)
            val e2 =
                assertFailsWith<FaceMaskException.InvalidConfiguration> { UprightBox.fromRaw(0f, 0f, 1f, 1f, 4, w, 1) }
            assertEquals("rawHeight", e2.parameter)
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            for (at in 0 until 4) {
                val v = FloatArray(4) { if (it == at) bad else it.toFloat() }
                val e = assertFailsWith<FaceMaskException.InvalidConfiguration>("$bad at $at") {
                    UprightBox.fromRaw(v[0], v[1], v[2], v[3], 4, 2, 1)
                }
                assertEquals("box", e.parameter)
            }
        }
    }

    @Test
    fun equalityHashAndTextCoverTheSixValues() {
        val a = UprightBox.fromRaw(1f, 0f, 2f, 1f, 4, 2, 6)
        assertEquals(a, UprightBox.fromRaw(1f, 0f, 2f, 1f, 4, 2, 6))
        assertEquals(a.hashCode(), UprightBox.fromRaw(1f, 0f, 2f, 1f, 4, 2, 6).hashCode())
        assertNotEquals(a, UprightBox.fromRaw(1f, 0f, 2f, 1f, 4, 2, 5))
        assertNotEquals(a, UprightBox.fromRaw(1f, 0f, 2f, 1f, 4, 3, 6))
        assertNotEquals<Any>(a, "UprightBox")
        assertEquals("UprightBox(left=1.0, top=1.0, right=2.0, bottom=2.0, imageWidth=2, imageHeight=4)", a.toString())
    }

    /**
     * The guide's flow on a stored-frame detector result: a 4,000 x 3,000 stored photo with tag 6 (upright 3,000 x
     * 4,000), a detector that saw it at half size in the stored frame. The aspect check accepts the converted frame
     * and refuses the unconverted one; on a square photo it cannot tell them apart (the KDoc's limit).
     */
    @Test
    fun theAspectCheckCatchesAMissingTurnOnlyOnPhotosThatAreNotSquare() {
        val planWidth = 3_000
        val planHeight = 4_000
        val u = UprightBox.fromRaw(100f, 200f, 300f, 500f, 2_000, 1_500, 6)
        assertTrue(aspectError(u.imageWidth, u.imageHeight, planWidth, planHeight) < 0.01)
        val sx = planWidth.toFloat() / u.imageWidth
        val sy = planHeight.toFloat() / u.imageHeight
        assertEquals(2f, sx)
        assertEquals(2f, sy)
        assertEquals(2_000f, u.left * sx) // 1,500 - 500 = 1,000 at half size, 2,000 in the plan
        assertEquals(200f, u.top * sy) // 100 at half size
        val missing = UprightBox.fromRaw(100f, 200f, 300f, 500f, 2_000, 1_500, 1)
        assertTrue(aspectError(missing.imageWidth, missing.imageHeight, planWidth, planHeight) > 0.01)
        // Square: a missing turn keeps the shape, so the check passes although the box is in the wrong place.
        val squareTurned = UprightBox.fromRaw(100f, 200f, 300f, 500f, 2_000, 2_000, 6)
        val squareMissing = UprightBox.fromRaw(100f, 200f, 300f, 500f, 2_000, 2_000, 1)
        assertTrue(aspectError(squareMissing.imageWidth, squareMissing.imageHeight, 4_000, 4_000) < 0.01)
        assertNotEquals(squareTurned.left, squareMissing.left)
    }

    private fun aspectError(w: Int, h: Int, planWidth: Int, planHeight: Int): Double =
        kotlin.math.abs(w.toDouble() / h - planWidth.toDouble() / planHeight)
}
