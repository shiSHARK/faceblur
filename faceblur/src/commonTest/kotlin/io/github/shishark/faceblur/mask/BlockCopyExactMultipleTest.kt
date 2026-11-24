// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.Golden
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The block path on images whose width and height are exact multiples of 64 (and one that is only in one side): the
 * last column and the last row of tiles are full tiles too, so they are written as 64 × 64 blocks and no row run is
 * left; the pixels are those of the old engine and every array handed to the image has exactly `w × h` elements.
 * [BlockCopyTest] covers the sizes with a narrower last column and a shorter last row.
 */
class BlockCopyExactMultipleTest {
    /** Records the size of every write and counts arrays whose size is not `w × h`. */
    private class ShapeImage(val base: IntArrayPixels) : MutablePixelImage {
        override val width: Int get() = base.width
        override val height: Int get() = base.height
        val writes = ArrayList<Pair<Int, Int>>()
        var sizeViolations = 0

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
            if (dst.size != w * h) sizeViolations++
            base.readPixels(x, y, w, h, dst)
        }

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            if (src.size != w * h) sizeViolations++
            writes += w to h
            base.writePixels(x, y, w, h, src)
        }
    }

    private fun picture(w: Int, h: Int) = IntArrayPixels(w, h, Golden.photoLike(w, h, 23))

    /** A rectangle over the whole picture, so every tile is written completely. */
    private fun wholeImageMask(w: Int, h: Int, shape: MaskShape) =
        listOf(MaskGeometry(w / 2f, h / 2f, w / 2f + 8, h / 2f + 8, shape))

    private fun check(w: Int, h: Int, fullTiles: Int) {
        for (style in MaskStyle.entries) {
            val masks = wholeImageMask(w, h, MaskShape.RECTANGLE)
            val s = settings(style, MaskShape.RECTANGLE)
            val image = ShapeImage(picture(w, h))
            MaskEngine.apply(image, masks, s)
            val old = picture(w, h)
            LegacyMaskEngine.apply(old, masks, s)
            val name = "$w × $h $style"
            assertEquals(old.data.toList(), image.base.data.toList(), "$name: the pixels of the old engine")
            assertEquals(0, image.sizeViolations, "$name: exact w × h arrays")
            assertEquals(fullTiles, image.writes.count { it == 64 to 64 }, "$name: one block per tile")
            assertTrue(image.writes.all { it == 64 to 64 }, "$name: no row run is left: ${image.writes.toSet()}")
        }
    }

    @Test
    fun anImageWithBothSidesAnExactMultipleOf64IsWrittenAsFullBlocksOnly() = check(256, 192, 4 * 3)

    @Test
    fun anImageWithOnlyItsWidthAnExactMultipleOf64KeepsTheRunsOfItsShortLastRow() {
        val w = 256
        val h = 200
        val masks = wholeImageMask(w, h, MaskShape.RECTANGLE)
        val s = settings(MaskStyle.SOLID, MaskShape.RECTANGLE)
        val image = ShapeImage(picture(w, h))
        MaskEngine.apply(image, masks, s)
        val old = picture(w, h)
        LegacyMaskEngine.apply(old, masks, s)
        assertEquals(old.data.toList(), image.base.data.toList(), "the pixels of the old engine")
        assertEquals(0, image.sizeViolations, "exact w × h arrays")
        assertEquals(4 * 3, image.writes.count { it == 64 to 64 }, "one block per full tile")
        assertTrue(image.writes.all { it == 64 to 64 || it == 64 to 1 }, "the last row (8 px): runs of 64")
    }
}
