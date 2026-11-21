// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Golden
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The copy step writes a full-size tile whose pixels are all written as one 64 × 64 block and reads it back: the
 * pixels are those of the old engine, every array handed to the image has exactly `w × h` elements, partial and
 * partly written tiles keep the row runs, and a block whose read-back differs in a single opaque pixel fails closed.
 */
class BlockCopyTest {
    /** Records the size of every write and can change the image after a chosen kind of write. */
    private class ShapeImage(val base: IntArrayPixels) : MutablePixelImage {
        override val width: Int get() = base.width
        override val height: Int get() = base.height
        val writes = ArrayList<Pair<Int, Int>>()
        var sizeViolations = 0

        /** Called after every write with its block (x, y, w, h) and the array handed in. */
        var afterWrite: (Int, Int, Int, Int, IntArray) -> Unit = { _, _, _, _, _ -> }

        /** Called on every read-back result (x, y, w, h, dst). */
        var afterRead: (Int, Int, Int, Int, IntArray) -> Unit = { _, _, _, _, _ -> }

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
            if (dst.size != w * h) sizeViolations++
            base.readPixels(x, y, w, h, dst)
            afterRead(x, y, w, h, dst)
        }

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            if (src.size != w * h) sizeViolations++
            writes += w to h
            base.writePixels(x, y, w, h, src)
            afterWrite(x, y, w, h, src)
        }
    }

    /** 300 × 270: full tiles inside, a narrower last column and a shorter last row at the edges. */
    private fun picture() = IntArrayPixels(300, 270, Golden.photoLike(300, 270, 17))

    /** A rectangle whose write rectangle covers the whole picture, an ellipse inside it, one small mask. */
    private val masks = listOf(
        MaskGeometry(150f, 135f, 140f, 125f, MaskShape.RECTANGLE),
        MaskGeometry(120f, 100f, 60f, 70f, MaskShape.ELLIPSE),
        MaskGeometry(270f, 40f, 6f, 8f, MaskShape.ELLIPSE),
    )

    @Test
    fun wholeTilesAreOneBlockAndThePixelsAreThoseOfTheOldEngine() {
        for (style in MaskStyle.entries) {
            val s = settings(style)
            val image = ShapeImage(picture())
            MaskEngine.apply(image, masks, s)
            val old = picture()
            LegacyMaskEngine.apply(old, masks, s)
            assertEquals(old.data.toList(), image.base.data.toList(), "$style: the pixels of the old engine")
            assertEquals(0, image.sizeViolations, "$style: exact w × h arrays")
            // 4 × 4 full tiles of the 5 × 5 tile grid; the last column (44 px) and row (14 px) keep the runs.
            assertEquals(16, image.writes.count { it == 64 to 64 }, "$style: one block per full tile")
            assertTrue(image.writes.all { it == 64 to 64 || it.second == 1 }, "$style: otherwise row runs")
            assertTrue(image.writes.any { it == 44 to 1 } && image.writes.any { it == 64 to 1 }, "$style: edge runs")
        }
    }

    @Test
    fun aPartlyWrittenFullTileKeepsTheRowRuns() {
        // A write rectangle (about 68..124 on both axes) that ends inside every tile it touches: none is complete.
        val small = listOf(MaskGeometry(96f, 96f, 25f, 25f, MaskShape.RECTANGLE))
        val image = ShapeImage(picture())
        MaskEngine.apply(image, small, settings(MaskStyle.SOLID))
        assertTrue(image.writes.isNotEmpty())
        assertTrue(image.writes.all { it.second == 1 && it.first <= 64 }, "only row runs: ${image.writes.toSet()}")
        assertEquals(0, image.sizeViolations)
    }

    @Test
    fun aSinglePixelChangedAfterTheBlockWriteFailsClosed() {
        for (style in MaskStyle.entries) {
            val image = ShapeImage(picture())
            var corrupted = 0
            image.afterWrite = { x, y, w, h, _ ->
                if (w == 64 && h == 64 && corrupted == 0) {
                    // One opaque pixel of the block, after it was written, flips its lowest bit.
                    val i = (y + 37) * image.width + x + 21
                    image.base.data[i] = image.base.data[i] xor 1
                    corrupted++
                }
            }
            assertFailsWith<CoreFailure.WriteNotApplied>(style.name) { MaskEngine.apply(image, masks, settings(style)) }
            assertEquals(1, corrupted, "$style: the corrupted write was a block")
        }
    }

    @Test
    fun aSinglePixelChangedInTheBlockReadBackFailsClosed() {
        val image = ShapeImage(picture())
        image.afterRead = { _, _, w, h, dst -> if (w == 64 && h == 64) dst[4095] = dst[4095] xor 0x100 }
        assertFailsWith<CoreFailure.WriteNotApplied> { MaskEngine.apply(image, masks, settings(MaskStyle.SOLID)) }
    }

    @Test
    fun anImageThatAltersTheHandedArrayAndStoresItFailsClosed() {
        // The image changes the staged block and keeps the changed pixels: the read-back is compared with the
        // library's own tile, not with the array the image could change.
        val image = object : MutablePixelImage {
            val base = picture()
            override val width = base.width
            override val height = base.height

            override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) = base.readPixels(x, y, w, h, dst)

            override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
                if (w == 64 && h == 64) src[100] = src[100] xor 0x010101
                base.writePixels(x, y, w, h, src)
            }
        }
        assertFailsWith<CoreFailure.WriteNotApplied> { MaskEngine.apply(image, masks, settings(MaskStyle.SOLID)) }
    }

    @Test
    fun throughTheFaceMaskerABlockThatDoesNotReadBackIsMaskWriteFailed() {
        val image = ShapeImage(picture())
        image.afterWrite = { x, y, w, h, _ ->
            if (w == 64 && h == 64) image.base.data[y * image.width + x] = image.base.data[y * image.width + x] xor 1
        }
        val plan = MaskPlan(300, 270, listOf(Mask.exact(MaskShape.RECTANGLE, 150f, 135f, 140f, 125f)))
        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner())).use { masker ->
            assertFailsWith<FaceMaskException.MaskWriteFailed> { masker.applyMasks(image, plan) }
        }
    }

    @Test
    fun theBlockBuffersAreReservedOnlyWhenAFullTileIsTouched() {
        val withFull = MaskEngine.workingAreaBytes(300, 270, masks, settings(MaskStyle.SOLID))
        val tileBytes = AreaLayout.ARRAY_HEADER + 4L * 64 * 64
        val added = AreaLayout.blockBufferBytes(1) - AreaLayout.blockBufferBytes(0)
        assertEquals(2L * (tileBytes - AreaLayout.ARRAY_HEADER), added, "two 64 × 64 int arrays")
        assertTrue(withFull > 2 * tileBytes)
        // A 60 × 50 image has no full-size tile: the two block arrays are empty.
        val tiny = listOf(MaskGeometry(30f, 25f, 20f, 20f, MaskShape.ELLIPSE))
        val allocator = RecordingAllocator()
        val image = IntArrayPixels(60, 50, Golden.photoLike(60, 50, 1))
        MaskEngine.apply(image, tiny, settings(MaskStyle.SOLID), seams = StageSeams(allocator = allocator))
        assertEquals(MaskEngine.workingAreaBytes(60, 50, tiny, settings(MaskStyle.SOLID)), allocator.bytes)
        assertTrue(allocator.requests.none { it.kind == "int" && it.size == 64 * 64 }, "no 64 × 64 array")
    }
}
