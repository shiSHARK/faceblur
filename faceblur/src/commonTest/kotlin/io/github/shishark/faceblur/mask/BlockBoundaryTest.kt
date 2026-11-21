// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The edges of the whole-tile write (a 64 × 64 block for a tile whose pixels are all written, row runs for every
 * other tile): a tile written completely against a tile with one pixel left out, images that are not a multiple of
 * 64 or smaller than a tile or one pixel wide or high, SMOOTH_FILL columns and rows at the image edges, translucent
 * pixels in a block, and a single changed pixel at the first and the last position of a block and of a run, after
 * the write and in the read-back. Pixels are compared with the old engine ([LegacyMaskEngine]); every pixel is
 * written exactly once if and only if it lies in a write rectangle.
 */
class BlockBoundaryTest {
    /** Counts the writes per pixel and their shapes; can change the image after a write or a read. */
    private class WatchedImage(val base: IntArrayPixels) : MutablePixelImage {
        override val width: Int get() = base.width
        override val height: Int get() = base.height
        val writtenTimes = IntArray(base.width * base.height)

        /** Every write as (x, y, w, h), in order. */
        val writes = ArrayList<IntArray>()
        var sizeViolations = 0

        /** Called after every write (x, y, w, h) and after every read (x, y, w, h, dst). */
        var afterWrite: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> }
        var afterRead: (Int, Int, Int, Int, IntArray) -> Unit = { _, _, _, _, _ -> }

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
            if (dst.size != w * h) sizeViolations++
            base.readPixels(x, y, w, h, dst)
            afterRead(x, y, w, h, dst)
        }

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            if (src.size != w * h) sizeViolations++
            writes += intArrayOf(x, y, w, h)
            for (j in 0 until h) for (i in 0 until w) writtenTimes[(y + j) * width + x + i]++
            base.writePixels(x, y, w, h, src)
            afterWrite(x, y, w, h)
        }

        fun blocks(): List<IntArray> = writes.filter { it[2] == 64 && it[3] == 64 }
    }

    // ---- exact write rectangles -------------------------------------------------------------------------------------

    /** Feather 1 gives q = 2 exactly, so the write rectangle below is exact (checked by [writeRect]). */
    private fun exact(style: MaskStyle) = MaskSettings(style = style, feather = 1f)

    /** A rectangle mask whose write rectangle is [x0, x1) × [y0, y1) (at least 4 px on each side). */
    private fun rectMask(x0: Int, y0: Int, x1: Int, y1: Int) = MaskGeometry(
        (x0 + x1) / 2f,
        (y0 + y1) / 2f,
        ((x1 - x0) / 2f - 0.75f) / 2f,
        ((y1 - y0) / 2f - 0.75f) / 2f,
        MaskShape.RECTANGLE,
    )

    private fun writeRect(g: MaskGeometry, s: MaskSettings, w: Int, h: Int): IRect {
        val m = PreparedMask(PreparedMask.usable(g), s)
        return m.extent(1f + m.featherN, w, h)
    }

    private fun assertRect(g: MaskGeometry, s: MaskSettings, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        val r = writeRect(g, s, w, h)
        assertEquals(listOf(x0, y0, x1, y1), listOf(r.left, r.top, r.right, r.bottom), "the write rectangle")
    }

    private fun legacy(img: IntArrayPixels, masks: List<MaskGeometry>, s: MaskSettings): IntArray {
        val old = img.copy()
        LegacyMaskEngine.apply(old, masks, s)
        return old.data
    }

    /** The pixels of the write rectangles of [masks] (clipped to the image). */
    private fun union(masks: List<MaskGeometry>, s: MaskSettings, w: Int, h: Int): BooleanArray {
        val u = BooleanArray(w * h)
        for (g in masks) {
            val e = writeRect(g, s, w, h)
            for (y in e.top until e.bottom) for (x in e.left until e.right) u[y * w + x] = true
        }
        return u
    }

    // ---- a tile written completely against one pixel left out -------------------------------------------------------

    private val side = 200

    /** The tile [64, 128)² of a 200 × 200 picture completely covered by one write rectangle. */
    private fun fullTilePlan(s: MaskSettings): List<MaskGeometry> =
        listOf(rectMask(64, 64, 128, 128)).also { assertRect(it[0], s, side, side, 64, 64, 128, 128) }

    /** The same tile covered by two write rectangles that leave exactly its last pixel (127, 127) out. */
    private fun notchedTilePlan(s: MaskSettings): List<MaskGeometry> = listOf(
        rectMask(64, 64, 128, 127),
        rectMask(64, 122, 127, 128),
    ).also {
        assertRect(it[0], s, side, side, 64, 64, 128, 127)
        assertRect(it[1], s, side, side, 64, 122, 127, 128)
    }

    @Test
    fun aTileWrittenCompletelyIsOneBlockAndOnePixelLeftOutKeepsTheRows() {
        for (style in MaskStyle.entries) {
            val s = exact(style)
            val picture = stageImage(side, side, 5)

            val full = WatchedImage(picture.copy())
            val fullPlan = fullTilePlan(s)
            MaskEngine.apply(full, fullPlan, s)
            assertContentEquals(legacy(picture, fullPlan, s), full.base.data, "$style: full tile, old engine's pixels")
            assertEquals(listOf(listOf(64, 64, 64, 64)), full.writes.map { it.toList() }, "$style: one block")
            assertEquals(0, full.sizeViolations)

            val notched = WatchedImage(picture.copy())
            val notchedPlan = notchedTilePlan(s)
            MaskEngine.apply(notched, notchedPlan, s)
            assertContentEquals(legacy(picture, notchedPlan, s), notched.base.data, "$style: notched tile, old engine")
            assertTrue(notched.blocks().isEmpty(), "$style: a tile with an unwritten pixel is not written as a block")
            assertEquals(64, notched.writes.size, "$style: one run per row")
            assertTrue(notched.writes.all { it[3] == 1 })
            assertEquals(63, notched.writes.count { it[2] == 64 }, "$style: 63 whole rows")
            assertEquals(listOf(64, 127, 63, 1), notched.writes.single { it[2] == 63 }.toList(), "$style: last row")
            val last = 127 * side + 127
            assertEquals(0, notched.writtenTimes[last], "$style: the pixel left out is not written")
            assertEquals(picture.data[last], notched.base.data[last], "$style: and not changed")
            assertEquals(0, notched.sizeViolations)
        }
    }

    // ---- one changed pixel: first and last pixel of a block and of a run -------------------------------------------

    /** Four full tiles, [64, 192)² of a 200 × 200 picture, written as four blocks. */
    private fun fourTilePlan(s: MaskSettings): List<MaskGeometry> =
        listOf(rectMask(64, 64, 192, 192)).also { assertRect(it[0], s, side, side, 64, 64, 192, 192) }

    @Test
    fun oneChangedPixelAtTheFirstOrLastPlaceOfAnyBlockAfterTheWriteFailsClosed() {
        for (style in MaskStyle.entries) {
            val s = exact(style)
            val plan = fourTilePlan(s)
            // (block index, offset in the block) for the first and the last pixel of the first and the last block.
            for (block in listOf(0, 3)) {
                for (offset in listOf(0, 63, 63 * 64, 64 * 64 - 1)) {
                    val image = WatchedImage(stageImage(side, side, 6))
                    var seen = 0
                    image.afterWrite = { x, y, w, h ->
                        if (w == 64 && h == 64) {
                            if (seen == block) {
                                val i = (y + offset / 64) * side + x + offset % 64
                                image.base.data[i] = image.base.data[i] xor 1
                            }
                            seen++
                        }
                    }
                    assertFailsWith<CoreFailure.WriteNotApplied>("$style block $block offset $offset") {
                        MaskEngine.apply(image, plan, s)
                    }
                    assertEquals(block + 1, seen, "$style: the failure came at the corrupted block")
                }
            }
        }
    }

    @Test
    fun oneChangedPixelAtTheFirstOrLastPlaceOfABlockReadBackFailsClosed() {
        for (style in MaskStyle.entries) {
            val s = exact(style)
            val plan = fourTilePlan(s)
            for (block in listOf(0, 3)) {
                for (offset in listOf(0, 63, 63 * 64, 64 * 64 - 1)) {
                    val image = WatchedImage(stageImage(side, side, 6))
                    var seen = 0
                    // Tiles are loaded by reads before the first write; only reads after it are read-backs.
                    var writesDone = 0
                    image.afterWrite = { _, _, w, h -> if (w == 64 && h == 64) writesDone++ }
                    image.afterRead = { _, _, w, h, dst ->
                        if (w == 64 && h == 64 && writesDone > 0) {
                            if (seen == block) dst[offset] = dst[offset] xor 0x10000
                            seen++
                        }
                    }
                    assertFailsWith<CoreFailure.WriteNotApplied>("$style block $block offset $offset") {
                        MaskEngine.apply(image, plan, s)
                    }
                }
            }
        }
    }

    @Test
    fun oneChangedPixelAtTheFirstOrLastPlaceOfARunFailsClosed() {
        for (style in MaskStyle.entries) {
            val s = exact(style)
            val plan = notchedTilePlan(s)
            // The first row (64 px), a middle row, the last row (63 px): the first and last pixel of the run.
            val runs = listOf(listOf(64, 64, 64), listOf(64, 100, 64), listOf(64, 127, 63))
            for ((rx, ry, len) in runs) {
                for (pixel in listOf(0, len - 1)) {
                    val afterWrite = WatchedImage(stageImage(side, side, 7))
                    afterWrite.afterWrite = { x, y, w, h ->
                        if (x == rx && y == ry && w == len && h == 1) {
                            val i = y * side + x + pixel
                            afterWrite.base.data[i] = afterWrite.base.data[i] xor 0x100
                        }
                    }
                    assertFailsWith<CoreFailure.WriteNotApplied>("$style run at row $ry pixel $pixel after write") {
                        MaskEngine.apply(afterWrite, plan, s)
                    }
                    val readBack = WatchedImage(stageImage(side, side, 7))
                    var written = false
                    readBack.afterWrite = { x, y, w, h -> written = x == rx && y == ry && w == len && h == 1 }
                    readBack.afterRead = { _, _, w, h, dst ->
                        if (written && w == len && h == 1) {
                            dst[pixel] = dst[pixel] xor 0x1
                            written = false
                        }
                    }
                    assertFailsWith<CoreFailure.WriteNotApplied>("$style run at row $ry pixel $pixel read back") {
                        MaskEngine.apply(readBack, plan, s)
                    }
                }
            }
        }
    }

    @Test
    fun aCleanBlockAndRunWriteDoesNotFail() {
        for (style in MaskStyle.entries) {
            val s = exact(style)
            MaskEngine.apply(WatchedImage(stageImage(side, side, 8)), fourTilePlan(s), s)
            MaskEngine.apply(WatchedImage(stageImage(side, side, 8)), notchedTilePlan(s), s)
        }
    }

    // ---- image sizes -----------------------------------------------------------------------------------------------

    private val sizes = listOf(
        1 to 1, 1 to 70, 70 to 1, 2 to 3, 63 to 63, 64 to 64, 64 to 65, 65 to 64, 65 to 65,
        127 to 129, 128 to 128, 130 to 66, 192 to 64, 193 to 193, 63 to 200, 200 to 63,
    )

    private val styleSettings: List<MaskSettings> = MaskStyle.entries.flatMap { style ->
        listOf(
            MaskSettings(style = style),
            MaskSettings(style = style, feather = 0f, ringWidth = 0.4f),
            MaskSettings(style = style, feather = 0.5f, centerColorSource = CenterColorSource.SURROUNDING_MEAN),
        )
    }

    @Test
    fun aMaskOverTheWholePictureWritesEveryPixelOnceAndCopiesBlocksOnlyForWholeTiles() {
        for ((w, h) in sizes) {
            val whole = listOf(MaskGeometry(w / 2f, h / 2f, 2f * maxOf(w, h), 2f * maxOf(w, h), MaskShape.RECTANGLE))
            for (s in styleSettings) {
                val picture = stageImage(w, h, w * 31 + h)
                val watched = WatchedImage(picture.copy())
                MaskEngine.apply(watched, whole, s)
                val label = "${w}x$h ${s.style} feather ${s.feather}"
                assertContentEquals(legacy(picture, whole, s), watched.base.data, "$label: old engine's pixels")
                assertTrue(watched.writtenTimes.all { it == 1 }, "$label: every pixel written exactly once")
                assertEquals(0, watched.sizeViolations, "$label: exact w × h arrays")
                assertEquals((w / 64) * (h / 64), watched.blocks().size, "$label: a block for every whole tile")
                assertTrue(
                    watched.writes.all { (it[2] == 64 && it[3] == 64) || it[3] == 1 },
                    "$label: otherwise row runs",
                )
                val inBlocks = watched.blocks().size * 64 * 64
                val inRuns = watched.writes.filter { it[3] == 1 }.sumOf { it[2] }
                assertEquals(w * h, inBlocks + inRuns, "$label: all pixels accounted for")
            }
        }
    }

    @Test
    fun randomPlansOnAwkwardSizesWriteExactlyTheWriteRectanglesAndEqualTheOldEngine() {
        var blocksSeen = 0
        for ((n, size) in sizes.withIndex()) {
            val (w, h) = size
            val r = Random(100 + n)
            val masks = randomMasks(r, w, h, 1 + r.nextInt(6), maxRadius = 5f + r.nextInt(120))
            for (s in styleSettings) {
                val picture = stageImage(w, h, n + 1)
                val watched = WatchedImage(picture.copy())
                MaskEngine.apply(watched, masks, s)
                val label = "${w}x$h ${s.style} feather ${s.feather}"
                assertContentEquals(legacy(picture, masks, s), watched.base.data, "$label: old engine's pixels")
                val u = union(masks, s, w, h)
                for (i in u.indices) {
                    assertEquals(if (u[i]) 1 else 0, watched.writtenTimes[i], "$label: pixel $i written times")
                }
                assertEquals(0, watched.sizeViolations)
                blocksSeen += watched.blocks().size
            }
        }
        assertTrue(blocksSeen > 0, "the plans reach the block path")
    }

    @Test
    fun picturesSmallerThanATileAndOnePixelWideOrHighNeverUseTheBlockPath() {
        for ((w, h) in listOf(1 to 1, 1 to 200, 200 to 1, 63 to 200, 200 to 63, 64 to 63, 63 to 64)) {
            val whole = listOf(MaskGeometry(w / 2f, h / 2f, 300f, 300f, MaskShape.ELLIPSE))
            for (s in styleSettings) {
                val picture = stageImage(w, h, 3)
                val watched = WatchedImage(picture.copy())
                MaskEngine.apply(watched, whole, s)
                assertTrue(watched.blocks().isEmpty(), "${w}x$h ${s.style}: no whole tile")
                assertContentEquals(legacy(picture, whole, s), watched.base.data, "${w}x$h ${s.style}")
            }
        }
    }

    // ---- SMOOTH_FILL columns and rows at the edges -----------------------------------------------------------------

    @Test
    fun smoothFillAtTheEdgesAndCornersEqualsTheOldEngine() {
        // Radii below 96 px use one grid cell per pixel (no interpolation); 130 and 260 px use a coarser grid (spacing
        // 3 and 6), where each column blends two grid columns.
        val cases = listOf(
            150 to 130, 64 to 64, 130 to 70, 65 to 129, 200 to 40, 40 to 200, 1 to 90, 90 to 1, 300 to 280,
        )
        val runs = cases.flatMap { (w, h) -> listOf(0f, 130f, 260f).map { Triple(w, h, it) } }
        for ((w, h, big) in runs) {
            val radius = if (big == 0f) 25f + minOf(w, h) / 2f else big
            val masks = listOf(
                // The four corners and the middle of two sides: the part inside the picture starts or ends at an edge.
                MaskGeometry(0f, 0f, radius, radius * 0.8f, MaskShape.ELLIPSE),
                MaskGeometry(w.toFloat(), 0f, radius, radius, MaskShape.RECTANGLE),
                MaskGeometry(0f, h.toFloat(), radius * 1.3f, radius, MaskShape.ELLIPSE),
                MaskGeometry(w.toFloat(), h.toFloat(), radius, radius * 1.2f, MaskShape.RECTANGLE),
                MaskGeometry(w / 2f, 0f, radius * 0.7f, radius * 0.6f, MaskShape.ELLIPSE),
                MaskGeometry(0f, h / 2f, radius * 0.6f, radius * 0.7f, MaskShape.RECTANGLE),
                // One column or one row of the mask inside the picture (the rest outside), at every edge.
                MaskGeometry(-radius + 0.6f, h / 2f, radius, radius, MaskShape.RECTANGLE),
                MaskGeometry(w + radius - 0.6f, h / 2f, radius, radius, MaskShape.RECTANGLE),
                MaskGeometry(w / 2f, -radius + 0.6f, radius, radius, MaskShape.RECTANGLE),
                MaskGeometry(w / 2f, h + radius - 0.6f, radius, radius, MaskShape.RECTANGLE),
            )
            for (s in styleSettings.filter { it.style != MaskStyle.SOLID }) {
                val picture = stageImage(w, h, w + h)
                for ((k, plan) in listOf(masks, masks.reversed(), masks.take(5)).withIndex()) {
                    val watched = WatchedImage(picture.copy())
                    MaskEngine.apply(watched, plan, s)
                    assertContentEquals(
                        legacy(picture, plan, s),
                        watched.base.data,
                        "${w}x$h ${s.style} feather ${s.feather} plan $k",
                    )
                    assertEquals(0, watched.sizeViolations)
                }
                // Every mask alone as well: a tile part that starts or ends inside a tile, at every position.
                for ((k, g) in masks.withIndex()) {
                    val watched = WatchedImage(picture.copy())
                    MaskEngine.apply(watched, listOf(g), s)
                    assertContentEquals(
                        legacy(picture, listOf(g), s),
                        watched.base.data,
                        "${w}x$h ${s.style} feather ${s.feather} mask $k alone",
                    )
                }
            }
        }
    }

    @Test
    fun smoothFillMasksThatStartAndEndInsideATileEqualTheOldEngine() {
        val w = 300
        val h = 260
        val picture = stageImage(w, h, 12)
        val r = Random(5)
        for (round in 0 until 24) {
            // A centre and radii at every fraction of a pixel: the write rectangle starts and ends anywhere in a tile.
            // Rounds 0..7: radii up to 100 px (one grid cell per pixel); the others 100..260 px (coarser grid).
            val reach = if (round < 8) 100f else 160f
            val low = if (round < 8) 1f else 100f
            val g = MaskGeometry(
                20f + r.nextFloat() * 260f,
                20f + r.nextFloat() * 220f,
                low + r.nextFloat() * reach,
                low + r.nextFloat() * reach,
                if (round % 2 == 0) MaskShape.ELLIPSE else MaskShape.RECTANGLE,
            )
            for (s in styleSettings.filter { it.style == MaskStyle.SMOOTH_FILL }) {
                val watched = WatchedImage(picture.copy())
                MaskEngine.apply(watched, listOf(g), s)
                val label = "round $round feather ${s.feather}"
                assertContentEquals(legacy(picture, listOf(g), s), watched.base.data, label)
            }
        }
    }

    // ---- translucent pixels in a block -----------------------------------------------------------------------------

    /** Stores premultiplied ARGB like a platform bitmap; reads return the rounded straight values. */
    private class LossyImage(override val width: Int, override val height: Int, straight: IntArray) :
        MutablePixelImage {
        val stored = IntArray(straight.size) { premultiply(straight[it]) }
        var blockWrites = 0

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
            for (j in 0 until h) for (i in 0 until w) dst[j * w + i] = unpremultiply(stored[(y + j) * width + x + i])
        }

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            if (w == 64 && h == 64) blockWrites++
            for (j in 0 until h) for (i in 0 until w) stored[(y + j) * width + x + i] = premultiply(src[j * w + i])
        }

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

    @Test
    fun translucentPixelsInABlockAreWrittenLikeTheOldEngineAndDoNotFailTheReadBack() {
        val r = Random(9)
        val straight = IntArray(side * side) { i ->
            // A third of the pixels translucent (a lossy round trip through premultiplied storage), the rest opaque.
            val a = if (i % 3 == 0) 1 + r.nextInt(254) else 255
            (a shl 24) or r.nextInt(0x1000000)
        }
        for (style in MaskStyle.entries) {
            val s = exact(style)
            for (plan in listOf(fourTilePlan(s), fullTilePlan(s))) {
                val old = LossyImage(side, side, straight)
                LegacyMaskEngine.apply(old, plan, s)
                val new = LossyImage(side, side, straight)
                MaskEngine.apply(new, plan, s)
                assertContentEquals(old.stored, new.stored, "$style: the same stored pixels as the old engine")
                assertTrue(new.blockWrites > 0, "$style: the blocks were used")
            }
        }
    }
}
