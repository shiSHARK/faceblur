// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.randomPixels
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaskPainterTest {
    private val w = 320
    private val h = 240

    private fun face(l: Float, t: Float, r: Float, b: Float) = DetectedFace(RectF(l, t, r, b))

    private val faces = listOf(
        face(60f, 60f, 140f, 160f),
        face(120f, 70f, 190f, 150f), // overlaps the first one
        face(280f, 180f, 330f, 250f), // crosses the right/bottom border
        face(10f, 5f, 30f, 25f), // small, near the top-left corner
    )

    /** Fills every pixel that is inside any mask with a different random pattern. */
    private fun scrambleMasks(img: IntArrayPixels, masks: List<MaskGeometry>, seed: Int): IntArrayPixels {
        val out = img.copy()
        val noise = randomPixels(w, h, seed)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (masks.any { it.containsPixel(x, y) }) out.data[y * w + x] = noise[y * w + x]
            }
        }
        return out
    }

    private fun close(a: Int, b: Int): Boolean =
        (0..24 step 8).all { sh -> abs(((a shr sh) and 0xFF) - ((b shr sh) and 0xFF)) <= 1 }

    private fun privacyModes() = listOf(MaskStyle.SOLID, MaskStyle.GRADIENT_OVAL, MaskStyle.SMOOTH_FILL)

    @Test
    fun maskGeometry() {
        val cfg = MaskSettings(expandX = 2f, expandY = 2.5f, shiftUp = 0.1f)
        val m = MaskPainter.maskFor(face(100f, 100f, 140f, 160f), cfg)
        assertEquals(120f, m.cx)
        assertEquals(130f - 6f, m.cy)
        assertEquals(40f, m.rx)
        assertEquals(75f, m.ry)
        assertEquals(0f, m.normalizedRadius(120f, 124f))
        assertEquals(1f, m.normalizedRadius(160f, 124f), 1e-6f)
        assertTrue(m.containsPixel(120, 124))
        assertTrue(!m.containsPixel(161, 124))
        val rect = m.copy(shape = MaskShape.RECTANGLE)
        assertEquals(1f, rect.normalizedRadius(160f, 199f), 1e-6f, "corner of the rectangle is on the boundary")
    }

    @Test
    fun privacyModesNeverUseFacePixels() {
        val base = IntArrayPixels(w, h, gradientPixels(w, h))
        for (mode in privacyModes()) {
            for (shape in MaskShape.entries) {
                val cfg = MaskSettings(style = mode, shape = shape)
                val masks = faces.map { MaskPainter.maskFor(it, cfg) }
                val a = scrambleMasks(base, masks, 1)
                val b = scrambleMasks(base, masks, 2)
                assertTrue(!a.data.contentEquals(b.data))
                MaskPainter.apply(a, faces, cfg)
                MaskPainter.apply(b, faces, cfg)
                assertContentEquals(a.data, b.data, "$mode/$shape output depends on pixels inside the masks")
            }
        }
    }

    @Test
    fun privacyHoldsWithFixedCentreAndNoFeather() {
        val base = IntArrayPixels(w, h, gradientPixels(w, h))
        val cfg =
            MaskSettings(
                style = MaskStyle.GRADIENT_OVAL,
                centerColorSource = CenterColorSource.FIXED,
                feather = 0f,
                ringWidth = 0.3f,
            )
        val masks = faces.map { MaskPainter.maskFor(it, cfg) }
        val a = scrambleMasks(base, masks, 3).also { MaskPainter.apply(it, faces, cfg) }
        val b = scrambleMasks(base, masks, 4).also { MaskPainter.apply(it, faces, cfg) }
        assertContentEquals(a.data, b.data)
    }

    @Test
    fun insideTheMaskIsPureEffectEvenWithWideFeather() {
        val color = 0xFF123456.toInt()
        val img = IntArrayPixels(w, h, randomPixels(w, h, 9))
        val cfg = MaskSettings(style = MaskStyle.SOLID, color = color, feather = 0.5f)
        MaskPainter.apply(img, faces, cfg)
        val masks = faces.map { MaskPainter.maskFor(it, cfg) }
        var inside = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (masks.any { it.containsPixel(x, y) }) {
                    inside++
                    assertEquals(color, img.data[y * w + x], "pixel ($x,$y) inside a mask")
                }
            }
        }
        assertTrue(inside > 5000)
    }

    @Test
    fun gradientOvalCentreIsTheFixedColour() {
        val color = 0xFF336699.toInt()
        val img = IntArrayPixels(w, h, randomPixels(w, h, 5))
        val f = face(100f, 60f, 200f, 180f)
        val cfg =
            MaskSettings(style = MaskStyle.GRADIENT_OVAL, centerColorSource = CenterColorSource.FIXED, color = color)
        MaskPainter.apply(img, listOf(f), cfg)
        val m = MaskPainter.maskFor(f, cfg)
        assertEquals(color, img.data[m.cy.toInt() * w + m.cx.toInt()])
    }

    @Test
    fun gradientOvalBlendsIntoUniformSurroundings() {
        // Uniform background: the oval (centre = surrounding mean) must be invisible, i.e. the image unchanged.
        val bg = 0xFF5A7D3C.toInt()
        for (mode in listOf(MaskStyle.GRADIENT_OVAL, MaskStyle.SMOOTH_FILL)) {
            val img = IntArrayPixels(w, h, IntArray(w * h) { bg })
            // put a "face" inside the mask only
            val f = face(120f, 80f, 180f, 160f)
            val cfg = MaskSettings(style = mode, centerColorSource = CenterColorSource.SURROUNDING_MEAN)
            val m = MaskPainter.maskFor(f, cfg)
            for (y in 0 until h) for (x in 0 until w) if (m.containsPixel(x, y)) img.data[y * w + x] = -1
            MaskPainter.apply(img, listOf(f), cfg)
            for (i in img.data.indices) {
                assertTrue(
                    close(bg, img.data[i]),
                    "$mode pixel ${i % w},${i / w}: ${img.data[i].toUInt().toString(16)}",
                )
            }
        }
    }

    @Test
    fun pixelsBeyondTheFeatherAreUntouched() {
        val orig = randomPixels(w, h, 11)
        for (mode in MaskStyle.entries) {
            val img = IntArrayPixels(w, h, orig.copyOf())
            val cfg = MaskSettings(style = mode)
            MaskPainter.apply(img, faces, cfg)
            val masks = faces.map { MaskPainter.maskFor(it, cfg) }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val outside = masks.all {
                        it.normalizedRadius(x + 0.5f, y + 0.5f) >=
                            1f + maxOf(cfg.feather, 1f / minOf(it.rx, it.ry)) + 1e-3f
                    }
                    if (outside) assertEquals(orig[y * w + x], img.data[y * w + x], "$mode changed ($x,$y)")
                }
            }
        }
    }

    @Test
    fun alphaOutsideTheMasksAndTheirSoftEdgeIsKept() {
        // Replaces the bench test "alphaChannelIsKept": inside masks alpha is now 255 (MaskPrivacyTest, ST-007 AC7);
        // pixels beyond the soft edge keep their alpha.
        val img = IntArrayPixels(w, h, IntArray(w * h) { 0x80FFFFFF.toInt() })
        val f = face(100f, 60f, 200f, 180f)
        val cfg = MaskSettings(style = MaskStyle.SOLID)
        MaskPainter.apply(img, listOf(f), cfg)
        val m = MaskPainter.maskFor(f, cfg)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = m.normalizedRadius(x + 0.5f, y + 0.5f)
                if (r >= 1.1f + 1e-4f) assertEquals(0x80, img.data[y * w + x] ushr 24, "($x,$y)")
                if (r <= 1f) assertEquals(0xFF, img.data[y * w + x] ushr 24, "($x,$y)")
            }
        }
    }

    @Test
    fun aFaceOutsideTheImageOrNoFaceChangesNothing() {
        val orig = randomPixels(w, h, 13)
        val img = IntArrayPixels(w, h, orig.copyOf())
        for (mode in MaskStyle.entries) {
            MaskPainter.apply(img, listOf(face(-500f, -500f, -400f, -400f)), MaskSettings(style = mode))
        }
        MaskPainter.apply(img, emptyList(), MaskSettings())
        assertContentEquals(orig, img.data)
    }

    @Test
    fun faceCoveringTheWholeImageFallsBackToTheColour() {
        val img = IntArrayPixels(64, 48, randomPixels(64, 48, 17))
        val color = 0xFF204060.toInt()
        for (mode in listOf(MaskStyle.GRADIENT_OVAL, MaskStyle.SMOOTH_FILL)) {
            MaskPainter.apply(
                img,
                listOf(face(-200f, -200f, 300f, 300f)),
                MaskSettings(style = mode, color = color, centerColorSource = CenterColorSource.SURROUNDING_MEAN),
            )
            assertTrue(img.data.all { it == color }, "$mode")
        }
    }

    @Test
    fun fastAtan2IsAccurate() {
        var maxErr = 0.0
        for (i in 0 until 3600) {
            val a = i * kotlin.math.PI / 1800 - kotlin.math.PI
            val y = kotlin.math.sin(a).toFloat() * 3f
            val x = kotlin.math.cos(a).toFloat() * 3f
            maxErr =
                maxOf(
                    maxErr,
                    abs(fastAtan2(y, x) - kotlin.math.atan2(y, x)).toDouble().let {
                        if (it >
                            6
                        ) {
                            abs(it - 2 * kotlin.math.PI)
                        } else {
                            it
                        }
                    },
                )
        }
        assertTrue(maxErr < 3e-4, "max error $maxErr")
    }

    @Test
    fun srgbLutRoundTrips() {
        for (v in 0..255) assertEquals(v, Srgb.encode(Srgb.toLinear[v]), "value $v")
    }
}
