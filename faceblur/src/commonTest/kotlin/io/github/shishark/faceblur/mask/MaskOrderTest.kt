// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Golden
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.math.floor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Masks are composited smallest first, so that where masks overlap the bigger mask is on top: a small mask (a part
 * mask, or a second face) never draws its soft edge or its fill over the inside of a bigger one. Masks of the same size
 * keep the plan's order. The order decides only pixels that two masks reach; every pixel inside every mask stays
 * opaque in any order, and every other pixel is the release's (0.1.1, composited in plan order).
 */
class MaskOrderTest {
    private val w = 420
    private val h = 330

    private fun picture() = IntArrayPixels(w, h, Golden.photoLike(w, h, 23))

    /** The pixels of [masks] composited by the stage in exactly the given order. */
    private fun inOrder(
        masks: List<MaskGeometry>,
        s: MaskSettings,
        pixels: () -> IntArrayPixels = ::picture,
    ): IntArray {
        val image = pixels()
        MaskEngine.apply(image, masks, s)
        return image.data
    }

    /** The pixels of [masks] through the library's route (the order it chooses). */
    private fun painted(
        masks: List<MaskGeometry>,
        s: MaskSettings,
        pixels: () -> IntArrayPixels = ::picture,
    ): IntArray {
        val image = pixels()
        MaskPainter.applyMasks(image, masks, s)
        return image.data
    }

    private val big = MaskGeometry(200f, 170f, 120f, 140f, MaskShape.ELLIPSE)

    /**
     * Its inside reaches into the big mask's solid centre (r <= 0.45 of the big mask), and its sampling ring lies partly
     * outside the big mask, so its fill has colours of its own there.
     */
    private val small = MaskGeometry(305f, 170.5f, 80f, 50f, MaskShape.ELLIPSE)

    @Test
    fun theBiggerMaskIsOnTopWhateverThePlanOrder() {
        for (style in MaskStyle.entries) {
            for (feather in listOf(0f, 0.1f, 0.6f)) {
                val s = MaskSettings(style = style, feather = feather)
                val expected = inOrder(listOf(small, big), s)
                assertContentEquals(expected, painted(listOf(big, small), s), "$style feather $feather: big first")
                assertContentEquals(expected, painted(listOf(small, big), s), "$style feather $feather: small first")
                // The plan order alone would put the small mask on top: the two orders differ in the overlap. (SOLID
                // fills all have one colour, so there only crossing soft edges could show the order.)
                if (style != MaskStyle.SOLID) {
                    assertFalse(expected.contentEquals(inOrder(listOf(big, small), s)), "$style feather $feather")
                }
            }
        }
    }

    @Test
    fun theBiggerMasksSolidCentreIsWholeWhereTheSmallerMaskOverlapsIt() {
        // GRADIENT_OVAL with the fixed centre colour: up to r = 0.45 every pixel has exactly that colour.
        val s = MaskSettings(style = MaskStyle.GRADIENT_OVAL)
        val centre = s.color and 0xFFFFFF
        val out = painted(listOf(big, small), s)
        val planOrder = inOrder(listOf(big, small), s)
        var overlapped = 0
        var coveredInPlanOrder = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                // A margin below 0.45 keeps the grid points of the coarse fill grid inside the solid centre too.
                if (big.normalizedRadius(x + 0.5f, y + 0.5f) > 0.4f) continue
                assertEquals(0xFF000000.toInt() or centre, out[y * w + x], "($x,$y) in the big mask's centre")
                if (small.normalizedRadius(x + 0.5f, y + 0.5f) <= 1f) {
                    overlapped++
                    if (planOrder[y * w + x] and 0xFFFFFF != centre) coveredInPlanOrder++
                }
            }
        }
        assertTrue(overlapped > 500, "the small mask overlaps the big mask's centre: $overlapped px")
        assertTrue(coveredInPlanOrder > 0, "in plan order the small mask's gradient lies over the big mask's centre")
    }

    @Test
    fun aRectangleIsBiggerThanTheEllipseWithTheSameRadiiAndTinyRadiiCountAsOnePixel() {
        val ellipse = MaskGeometry(100f, 100f, 10f, 10f, MaskShape.ELLIPSE)
        val rectangle = MaskGeometry(110f, 100f, 10f, 10f, MaskShape.RECTANGLE)
        assertEquals(listOf(ellipse, rectangle), MaskPainter.drawOrder(listOf(rectangle, ellipse)))
        // Both radii below 1 px are drawn at 1 px: the same size, so the plan order stays.
        val a = MaskGeometry(10f, 10f, 0.2f, 0.9f, MaskShape.ELLIPSE)
        val b = MaskGeometry(11f, 10f, 0.5f, 0.3f, MaskShape.ELLIPSE)
        assertEquals(MaskPainter.drawnArea(a), MaskPainter.drawnArea(b))
        assertEquals(listOf(b, a), MaskPainter.drawOrder(listOf(b, a)))
        assertEquals(listOf(a, b), MaskPainter.drawOrder(listOf(a, b)))
    }

    @Test
    fun masksOfTheSameSizeKeepThePlanOrder() {
        val m1 = MaskGeometry(150f, 150f, 50f, 60f, MaskShape.ELLIPSE)
        val m2 = MaskGeometry(190f, 165f, 60f, 50f, MaskShape.ELLIPSE) // same area as m1, overlapping it
        val tiny = MaskGeometry(170f, 160f, 15f, 12f, MaskShape.RECTANGLE)
        val huge = MaskGeometry(280f, 200f, 110f, 100f, MaskShape.ELLIPSE)
        assertEquals(MaskPainter.drawnArea(m1), MaskPainter.drawnArea(m2))
        for (style in MaskStyle.entries) {
            val s = MaskSettings(style = style, feather = 0.3f)
            assertContentEquals(
                inOrder(listOf(tiny, m1, m2, huge), s),
                painted(listOf(huge, m1, tiny, m2), s),
                "$style",
            )
            assertContentEquals(
                inOrder(listOf(tiny, m2, m1, huge), s),
                painted(listOf(m2, huge, m1, tiny), s),
                "$style",
            )
            // The order of the two equal masks shows in the pixels, so the two checks above see it.
            assertFalse(
                inOrder(listOf(tiny, m1, m2, huge), s).contentEquals(inOrder(listOf(tiny, m2, m1, huge), s)),
                "$style: the equal masks overlap",
            )
        }
        val equal = List(40) { MaskGeometry(10f + it * 9f, 50f, 12f, 12f, MaskShape.ELLIPSE) }
        assertEquals(equal, MaskPainter.drawOrder(equal))
        assertEquals(equal.reversed(), MaskPainter.drawOrder(equal.reversed()))
    }

    /** Random overlapping masks of many sizes, some across the edges and some raised to 1 px. */
    private fun plan(seed: Int): List<MaskGeometry> {
        val r = Random(seed)
        return List(6 + r.nextInt(12)) {
            val shape = if (r.nextBoolean()) MaskShape.ELLIPSE else MaskShape.RECTANGLE
            val radius = if (r.nextInt(5) == 0) 0.4f + r.nextFloat() * 3f else 5f + r.nextFloat() * 110f
            MaskGeometry(
                -15f + r.nextFloat() * (w + 30),
                -15f + r.nextFloat() * (h + 30),
                radius * (0.6f + r.nextFloat() * 0.8f),
                radius * (0.6f + r.nextFloat() * 0.8f),
                shape,
            )
        }
    }

    /** Alpha below 255 everywhere (0 included): an opaque output pixel was made opaque by a mask. */
    private fun translucent(): IntArrayPixels {
        val r = Random(4)
        return IntArrayPixels(w, h, IntArray(w * h) { (r.nextInt(255) shl 24) or r.nextInt(0x1000000) })
    }

    @Test
    fun everyPixelInsideEveryMaskIsOpaqueAndSolidKeepsItsColourInEitherOrder() {
        for (seed in 1..8) {
            val masks = plan(seed)
            for (order in listOf(masks, masks.reversed())) {
                for (style in MaskStyle.entries) {
                    val s = MaskSettings(style = style)
                    val out = painted(order, s, ::translucent)
                    for (g in masks) {
                        val m = PreparedMask.usable(g)
                        val e = PreparedMask(m, s).extent(1f, w, h)
                        for (y in e.top until e.bottom) {
                            for (x in e.left until e.right) {
                                if (!m.containsPixel(x, y)) continue
                                val p = out[y * w + x]
                                assertEquals(0xFF, p ushr 24, "seed $seed $style ($x,$y): opaque inside $g")
                                if (style == MaskStyle.SOLID) {
                                    assertEquals(s.color and 0xFFFFFF, p and 0xFFFFFF, "seed $seed ($x,$y): colour")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun everyPixelOfEveryFaceBoxIsOpaqueWhenFacesOverlap() {
        val faces = listOf(
            DetectedFace(RectF(60f, 40f, 300f, 300f)), // big
            DetectedFace(RectF(250f, 90f, 330f, 180f)), // smaller, over the big face's edge
            DetectedFace(RectF(140f, 120f, 190f, 175f)), // small, inside the big face
            DetectedFace(RectF(330f, 220f, 430f, 340f)), // across the right and bottom edges
        )
        for (order in listOf(faces, faces.reversed())) {
            for (style in MaskStyle.entries) {
                val image = translucent()
                MaskPainter.apply(image, order, MaskSettings(style = style))
                for (f in faces) {
                    val b = f.rect
                    for (y in maxOf(0, floor(b.top).toInt()) until minOf(h, b.bottom.toInt() + 1)) {
                        for (x in maxOf(0, floor(b.left).toInt()) until minOf(w, b.right.toInt() + 1)) {
                            // Every pixel whose centre lies in the face box.
                            if (x + 0.5f < b.left || x + 0.5f > b.right || y + 0.5f < b.top || y + 0.5f > b.bottom) {
                                continue
                            }
                            assertEquals(0xFF, image.data[y * w + x] ushr 24, "$style ($x,$y) in ${f.rect}")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun onlyPixelsThatTwoMasksReachDifferFromTheRelease() {
        var changed = 0
        for (seed in 1..8) {
            val masks = plan(seed)
            for (style in MaskStyle.entries) {
                val s = MaskSettings(style = style)
                // The release composited in plan order; on straight alpha its pixels are those of the per-pixel
                // engine (LegacyMaskEngine), see BoundedStageEqualityTest.
                val release = picture()
                LegacyMaskEngine.apply(release, masks, s)
                val out = painted(masks, s)
                val prepared = masks.map { PreparedMask(PreparedMask.usable(it), s) }
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = y * w + x
                        if (out[i] == release.data[i]) continue
                        changed++
                        val reached = prepared.count { it.radius(x, y) < 1f + it.featherN }
                        assertTrue(reached >= 2, "seed $seed $style ($x,$y) differs but $reached mask(s) reach it")
                    }
                }
            }
        }
        assertTrue(changed > 0, "the order changed some overlap pixels")
    }

    @Test
    fun theMaskerDrawsTheSamePixelsForAnyOrderOfThePlanAndKeepsThePlanOrderInItsResult() {
        val a = Mask.exact(MaskShape.ELLIPSE, 200f, 170f, 120f, 140f)
        val b = Mask.exact(MaskShape.ELLIPSE, 305f, 170.5f, 80f, 50f)
        val c = Mask.exact(MaskShape.RECTANGLE, 120f, 90f, 30f, 25f)
        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner())).use { masker ->
            val outputs = listOf(listOf(a, b, c), listOf(c, b, a), listOf(b, a, c)).map { masks ->
                val image = picture()
                val result = masker.applyMasks(image, MaskPlan(w, h, masks))
                assertEquals(masks, result.masks, "the result lists the plan's masks in the plan's order")
                image.data
            }
            assertContentEquals(outputs[0], outputs[1])
            assertContentEquals(outputs[0], outputs[2])
        }
    }
}
