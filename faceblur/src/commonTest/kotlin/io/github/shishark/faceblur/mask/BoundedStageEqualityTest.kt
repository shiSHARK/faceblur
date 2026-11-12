// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bounded stage (ADR 0013 decision 3) against a verbatim copy of the engine before it ([LegacyMaskEngine]):
 * pixel for pixel on images with straight alpha, for every style and both shapes, on random plans with overlapping
 * masks, masks at and beyond the image edges, small masks raised to 1 px and big masks on a coarse grid. The two
 * documented exceptions are tested too: premultiplied storage (differences only at overlapping soft edges with alpha
 * below 255) and the SMOOTH_FILL cell cap.
 */
class BoundedStageEqualityTest {
    private fun both(img: IntArrayPixels, masks: List<MaskGeometry>, s: MaskSettings): Pair<IntArray, IntArray> {
        val old = img.copy()
        val new = img.copy()
        LegacyMaskEngine.apply(old, masks, s)
        MaskEngine.apply(new, masks, s)
        return old.data to new.data
    }

    private val settingsList: List<MaskSettings> =
        MaskStyle.entries.flatMap { style ->
            listOf(
                MaskSettings(style = style),
                MaskSettings(style = style, feather = 0f, ringWidth = 0.4f),
                MaskSettings(style = style, feather = 0.5f, centerColorSource = CenterColorSource.SURROUNDING_MEAN),
            )
        }

    @Test
    fun randomOverlappingPlansAreBitIdenticalToTheOldEngine() {
        for (seed in 1..12) {
            val r = Random(seed)
            val w = 80 + r.nextInt(400)
            val h = 60 + r.nextInt(300)
            val img = stageImage(w, h, seed)
            // Some translucent and fully transparent pixels: straight alpha is stored exactly.
            for (i in img.data.indices step 7) img.data[i] = (r.nextInt(256) shl 24) or (img.data[i] and 0xFFFFFF)
            val masks = randomMasks(r, w, h, 1 + r.nextInt(30), maxRadius = 40f + r.nextInt(160))
            for (s in settingsList) {
                val (old, new) = both(img, masks, s)
                assertContentEquals(old, new, "seed $seed ${w}x$h ${s.style} feather ${s.feather}")
            }
        }
    }

    @Test
    fun bigMasksOnACoarseGridAndTinyMasksAreBitIdentical() {
        val w = 900
        val h = 700
        val img = stageImage(w, h, 77)
        val masks = listOf(
            // k = 4..6 grid (radius / 48), overlapping each other, one partly outside.
            MaskGeometry(300f, 300f, 260f, 220f, MaskShape.ELLIPSE),
            MaskGeometry(520f, 380f, 230f, 300f, MaskShape.RECTANGLE),
            MaskGeometry(880f, 650f, 200f, 120f, MaskShape.ELLIPSE),
            // Radii below 1 px, raised by the stage; at the image corner and between pixel centres.
            MaskGeometry(0.2f, 0.3f, 0.4f, 0.1f, MaskShape.ELLIPSE),
            MaskGeometry(450.5f, 20.5f, 0.6f, 3f, MaskShape.RECTANGLE),
        )
        for (s in settingsList) {
            for (shapeMasks in listOf(masks, masks.reversed())) {
                val (old, new) = both(img, shapeMasks, s)
                assertContentEquals(old, new, "${s.style} feather ${s.feather}")
            }
        }
    }

    @Test
    fun aWholeImageMaskAndAnEmptyImageAreBitIdentical() {
        val img = stageImage(257, 129, 9)
        val whole = listOf(MaskGeometry(128f, 64f, 400f, 300f, MaskShape.ELLIPSE))
        for (s in settingsList) {
            val (old, new) = both(img, whole, s)
            assertContentEquals(old, new, "${s.style}")
        }
        val empty = IntArrayPixels(0, 0)
        MaskEngine.apply(empty, whole, MaskSettings())
    }

    /** Stores premultiplied ARGB like a platform bitmap; reads return the rounded straight values. */
    private class PremultipliedImage(override val width: Int, override val height: Int, straight: IntArray) :
        MutablePixelImage {
        val stored = IntArray(straight.size) { premultiply(straight[it]) }
        val written = BooleanArray(straight.size)

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
            for (j in 0 until h) for (i in 0 until w) dst[j * w + i] = unpremultiply(stored[(y + j) * width + x + i])
        }

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            for (j in 0 until h) {
                for (i in 0 until w) {
                    stored[(y + j) * width + x + i] = premultiply(src[j * w + i])
                    written[(y + j) * width + x + i] = true
                }
            }
        }

        fun straight(): IntArray = IntArray(stored.size) { unpremultiply(stored[it]) }

        companion object {
            fun premultiply(p: Int): Int {
                val a = p ushr 24
                fun c(v: Int) = (v * a + 127) / 255
                return (a shl 24) or (c((p shr 16) and 0xFF) shl 16) or (c((p shr 8) and 0xFF) shl 8) or c(p and 0xFF)
            }

            fun unpremultiply(p: Int): Int {
                val a = p ushr 24
                if (a == 0) return 0
                fun c(v: Int) = minOf(255, (v * 255 + a / 2) / a)
                return (a shl 24) or (c((p shr 16) and 0xFF) shl 16) or (c((p shr 8) and 0xFF) shl 8) or c(p and 0xFF)
            }
        }
    }

    /**
     * Pixels where the old engine round-tripped a soft-edge value through premultiplied storage a second time: inside
     * the feather band (alpha below 1, above 0) of one mask and inside the write rectangles of at least two masks
     * (the later mask re-read and re-wrote its whole write rectangle). Only there can the results differ.
     */
    private fun overlappingSoftEdges(w: Int, h: Int, masks: List<MaskGeometry>, s: MaskSettings): BooleanArray {
        val soft = BooleanArray(w * h)
        val writes = IntArray(w * h)
        for (g in masks) {
            val m = PreparedMask(PreparedMask.usable(g), s)
            val e = m.extent(1f + m.featherN, w, h)
            for (y in e.top until e.bottom) {
                for (x in e.left until e.right) {
                    writes[y * w + x]++
                    val r = m.radius(x, y)
                    if (r > 1f && r < 1f + m.featherN) soft[y * w + x] = true
                }
            }
        }
        return BooleanArray(w * h) { soft[it] && writes[it] >= 2 }
    }

    @Test
    fun withPremultipliedStorageDifferencesAppearOnlyAtTranslucentSoftEdgesInsideAnotherWriteRectangle() {
        val w = 160
        val h = 120
        val r = Random(5)
        val straight = IntArray(w * h) { (r.nextInt(1, 256) shl 24) or r.nextInt(0x1000000) }
        val masks = listOf(
            MaskGeometry(60f, 60f, 30f, 25f, MaskShape.ELLIPSE),
            MaskGeometry(85f, 62f, 28f, 30f, MaskShape.ELLIPSE),
            MaskGeometry(120f, 40f, 20f, 20f, MaskShape.RECTANGLE),
        )
        var differences = 0
        for (style in MaskStyle.entries) {
            val s = MaskSettings(style = style, feather = 0.4f)
            val old = PremultipliedImage(w, h, straight)
            val new = PremultipliedImage(w, h, straight)
            LegacyMaskEngine.apply(old, masks, s)
            // The read-back comparison passes (no exception) with premultiplied storage.
            MaskEngine.apply(new, masks, s)
            val allowed = overlappingSoftEdges(w, h, masks, s)
            val a = old.straight()
            val b = new.straight()
            for (i in a.indices) {
                if (a[i] == b[i]) continue
                differences++
                // The old engine re-read a translucent intermediate value (alpha below 255) through the storage.
                assertTrue(allowed[i] && (straight[i] ushr 24) < 255, "$style pixel $i differs outside the exception")
            }
        }
        println("premultiplied storage: $differences pixels differ, all at overlapping translucent soft edges")
    }

    @Test
    fun theCopyWritesExactlyTheUnionOfTheWriteRectangles() {
        val w = 200
        val h = 140
        val r = Random(8)
        // Translucent pixels everywhere: a write of an untouched translucent pixel would round-trip lossy.
        val straight = IntArray(w * h) { (r.nextInt(1, 255) shl 24) or r.nextInt(0x1000000) }
        val masks = listOf(
            MaskGeometry(30f, 30f, 10f, 12f, MaskShape.ELLIPSE),
            MaskGeometry(150f, 100f, 25f, 10f, MaskShape.RECTANGLE),
            MaskGeometry(100f, 20f, 4f, 4f, MaskShape.ELLIPSE),
        )
        for (style in MaskStyle.entries) {
            val s = MaskSettings(style = style)
            val img = PremultipliedImage(w, h, straight)
            MaskEngine.apply(img, masks, s)
            val union = BooleanArray(w * h)
            for (g in masks) {
                val m = PreparedMask(PreparedMask.usable(g), s)
                val e = m.extent(1f + m.featherN, w, h)
                for (y in e.top until e.bottom) for (x in e.left until e.right) union[y * w + x] = true
            }
            assertContentEquals(union, img.written, "$style: written pixels = union of the write rectangles")
            assertTrue(union.count { it } < w * h / 4, "the touched tiles hold pixels outside every write rectangle")
        }
    }

    /** The read rectangle of a SMOOTH_FILL mask, as the stage computes it. */
    private fun smoothRead(g: MaskGeometry, s: MaskSettings, w: Int, h: Int): IRect {
        val m = PreparedMask(PreparedMask.usable(g), s)
        val write = m.extent(1f + m.featherN, w, h)
        return m.extent(1f + m.featherN + m.ringN, w, h).union(write)
    }

    @Test
    fun smoothFillBelowTheCellCapIsUnchangedAndAboveItUsesTheSmallestSpacingThatFits() {
        val w = 2_600
        val h = 300
        val s = MaskSettings(style = MaskStyle.SMOOTH_FILL)
        val cap = MaskStageLimits.MAX_SMOOTH_FILL_CELLS.toLong()
        // An elongated mask (ry < 96: usual spacing 1): find the radius where the base grid passes the cap.
        val ry = 90f
        var rx = 800f
        fun cells(rxv: Float): Long {
            val read = smoothRead(MaskGeometry(1_300f, 150f, rxv, ry, MaskShape.RECTANGLE), s, w, h)
            return read.width.toLong() * read.height
        }
        while (cells(rx) <= cap) rx += 0.5f
        val above = MaskGeometry(1_300f, 150f, rx, ry, MaskShape.RECTANGLE)
        val below = MaskGeometry(1_300f, 150f, rx - 0.5f, ry, MaskShape.RECTANGLE)
        assertTrue(cells(rx - 0.5f) <= cap && cells(rx) > cap, "the two masks straddle the cap")
        val img = stageImage(w, h, 4)
        val (old, new) = both(img, listOf(below), s)
        assertContentEquals(old, new, "below the cap: unchanged")
        // Above: the spacing is the smallest that fits, and the mask is opaque inside.
        val read = smoothRead(above, s, w, h)
        val k = SmoothFill.spacing(above.rx, above.ry, read.width, read.height)
        assertTrue(k > 1, "spacing raised")
        assertTrue(SmoothFill.cells(read.width, read.height, k) <= cap)
        assertTrue(SmoothFill.cells(read.width, read.height, k - 1) > cap, "the smallest spacing that fits")
        val out = img.copy()
        MaskEngine.apply(out, listOf(above), s)
        val m = PreparedMask(above, s)
        var inside = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (m.radius(x, y) <= 1f) {
                    inside++
                    assertEquals(0xFF, out.data[y * w + x] ushr 24, "opaque inside at ($x, $y)")
                }
            }
        }
        assertTrue(inside > 0)
    }
}
