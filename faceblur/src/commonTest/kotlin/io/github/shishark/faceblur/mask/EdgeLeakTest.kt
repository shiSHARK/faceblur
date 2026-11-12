// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Edge-leak test (FR-004, PRIV-005): a mask that runs past an image edge must still cover every picture pixel that lies
 * inside it, including the outermost rows and columns. A unique colour is planted on the two outermost rows and columns
 * of the picture, only where the opaque mask will cover them. The background never reaches that colour and no fill
 * (a mean or interpolation of background colours, or the fixed centre colour) can produce it, so a single output pixel
 * with that colour is a leak. Faces are placed so that the mask runs past EACH of the four edges and each of the four
 * corners; every style (SMOOTH_FILL first), every shape and both centre-colour sources run with the default expansion
 * (1.6 / 1.8) and with 2.0 / 2.2.
 */
class EdgeLeakTest {
    private val w = 360
    private val h = 280

    /** Not reachable from the background below (R, G <= 180, B <= 150) nor from the fixed centre colour 0x7F7F7F. */
    private val unique = 0xFFFA03FB.toInt()
    private val depth = 2

    private fun background(): IntArray = IntArray(w * h) {
        val x = it % w
        val y = it / w
        (0xFF shl 24) or ((20 + x * 160 / w) shl 16) or ((20 + y * 160 / h) shl 8) or (100 + (x + y) % 50)
    }

    private class Case(val name: String, val face: DetectedFace, val edge: String)

    private fun face(l: Float, t: Float, r: Float, b: Float) = DetectedFace(RectF(l, t, r, b))

    private val cases = listOf(
        Case("top", face(130f, 2f, 230f, 110f), "top"),
        Case("bottom", face(130f, h - 112f, 230f, h - 2f), "bottom"),
        Case("left", face(2f, 90f, 100f, 190f), "left"),
        Case("right", face(w - 102f, 90f, w - 2f, 190f), "right"),
        Case("top-left corner", face(2f, 2f, 90f, 100f), "top"),
        Case("top-right corner", face(w - 92f, 2f, w - 2f, 100f), "right"),
        Case("bottom-left corner", face(2f, h - 102f, 90f, h - 2f), "left"),
        Case("bottom-right corner", face(w - 92f, h - 102f, w - 2f, h - 2f), "bottom"),
    )

    private fun onEdge(x: Int, y: Int, edge: String): Boolean = when (edge) {
        "top" -> y == 0
        "bottom" -> y == h - 1
        "left" -> x == 0
        else -> x == w - 1
    }

    /** Planted pixels (index list) for [mask]: the two outermost rows and columns inside the opaque mask. */
    private fun plant(data: IntArray, mask: MaskGeometry): List<Int> {
        val planted = ArrayList<Int>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val outer = x < depth || y < depth || x >= w - depth || y >= h - depth
                if (outer && mask.containsPixel(x, y)) {
                    data[y * w + x] = unique
                    planted += y * w + x
                }
            }
        }
        return planted
    }

    private fun leaks(out: IntArray): Int = out.count { (it and 0xFFFFFF) == (unique and 0xFFFFFF) }

    private fun run(style: MaskStyle, shape: MaskShape, source: CenterColorSource, ex: Float, ey: Float) {
        val cfg = MaskSettings(style = style, shape = shape, centerColorSource = source, expandX = ex, expandY = ey)
        for (case in cases) {
            val mask = MaskPainter.maskFor(case.face, cfg)
            val label = "$style/$shape/$source expand $ex x $ey, ${case.name}"
            val reaches = when (case.edge) {
                "top" -> mask.cy - mask.ry < 0f
                "bottom" -> mask.cy + mask.ry > h
                "left" -> mask.cx - mask.rx < 0f
                else -> mask.cx + mask.rx > w
            }
            assertTrue(reaches, "$label: the mask must run past the ${case.edge} edge")
            val data = background()
            assertEquals(0, leaks(data), "$label: the background holds the unique colour")
            val planted = plant(data, mask)
            assertTrue(
                planted.any { onEdge(it % w, it / w, case.edge) },
                "$label: nothing planted on the ${case.edge} edge row/column",
            )
            val img = IntArrayPixels(w, h, data)
            MaskPainter.apply(img, listOf(case.face), cfg)
            val left = planted.count { (img.data[it] and 0xFFFFFF) == (unique and 0xFFFFFF) }
            assertEquals(0, left, "$label: $left of ${planted.size} planted edge pixels survived the mask")
            assertEquals(0, leaks(img.data), "$label: the unique colour is still in the output")
        }
    }

    @Test
    fun noEdgePixelInsideAMaskSurvivesAtTheDefaultExpansion() {
        for (style in listOf(MaskStyle.SMOOTH_FILL, MaskStyle.GRADIENT_OVAL, MaskStyle.SOLID)) {
            for (shape in MaskShape.entries) {
                for (source in CenterColorSource.entries) {
                    run(style, shape, source, MaskSettings.DEFAULT_EXPAND_X, MaskSettings.DEFAULT_EXPAND_Y)
                }
            }
        }
    }

    @Test
    fun noEdgePixelInsideAMaskSurvivesAtExpansionTwoPointZeroByTwoPointTwo() {
        for (style in listOf(MaskStyle.SMOOTH_FILL, MaskStyle.GRADIENT_OVAL, MaskStyle.SOLID)) {
            for (shape in MaskShape.entries) {
                for (source in CenterColorSource.entries) {
                    run(style, shape, source, 2.0f, 2.2f)
                }
            }
        }
    }

    /** The styles listed above are all of them (a new style must be added to the loops). */
    @Test
    fun theLoopsCoverEveryStyle() {
        assertEquals(setOf(MaskStyle.SMOOTH_FILL, MaskStyle.GRADIENT_OVAL, MaskStyle.SOLID), MaskStyle.entries.toSet())
    }

    /** Negative control of the harness: restoring the last column and row of a correct output is flagged as a leak. */
    @Test
    fun theHarnessFlagsAMaskThatSkipsTheLastRowAndColumn() {
        val case = cases.first { it.name == "bottom-right corner" }
        val cfg = MaskSettings()
        val mask = MaskPainter.maskFor(case.face, cfg)
        val data = background()
        val planted = plant(data, mask)
        val img = IntArrayPixels(w, h, data.copyOf())
        MaskPainter.apply(img, listOf(case.face), cfg)
        assertEquals(0, leaks(img.data))
        for (y in 0 until h) img.data[y * w + w - 1] = data[y * w + w - 1]
        for (x in 0 until w) img.data[(h - 1) * w + x] = data[(h - 1) * w + x]
        assertTrue(leaks(img.data) > 0 && planted.isNotEmpty(), "the skipped last row and column must be detected")
    }
}
