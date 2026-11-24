// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.Golden
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The copy compares what it reads back with the library's own shadow tile, never with the array it handed to the
 * image: an image that changes the array it is given (and so stores other pixels than the mask's) must fail closed for
 * row runs as for whole blocks, even though its read-back equals that changed array.
 */
class CopyReadBackTest {
    /** Changes one opaque pixel of the handed array before storing it, on the first write of the chosen kind. */
    private class ChangesItsInput(val base: IntArrayPixels, val blocks: Boolean) : MutablePixelImage {
        override val width: Int get() = base.width
        override val height: Int get() = base.height
        var changed = 0

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) = base.readPixels(x, y, w, h, dst)

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            val block = w == 64 && h == 64
            if (changed == 0 && block == blocks) {
                val j = src.indices.firstOrNull { src[it] ushr 24 == 0xFF }
                if (j != null) {
                    src[j] = src[j] xor 0x010101
                    changed++
                }
            }
            base.writePixels(x, y, w, h, src)
        }
    }

    private fun picture() = IntArrayPixels(300, 270, Golden.photoLike(300, 270, 31))

    @Test
    fun aRowRunWhoseHandedArrayWasChangedFailsClosed() {
        // A write rectangle that completes no 64 × 64 tile: only row runs.
        val masks = listOf(MaskGeometry(96f, 96f, 25f, 25f, MaskShape.RECTANGLE))
        for (style in MaskStyle.entries) {
            val image = ChangesItsInput(picture(), blocks = false)
            assertFailsWith<CoreFailure.WriteNotApplied>(style.name) { MaskEngine.apply(image, masks, settings(style)) }
            assertEquals(1, image.changed, "$style: a run was changed")
        }
    }

    @Test
    fun aBlockWhoseHandedArrayWasChangedFailsClosed() {
        val masks = listOf(MaskGeometry(150f, 135f, 140f, 125f, MaskShape.RECTANGLE))
        for (style in MaskStyle.entries) {
            val image = ChangesItsInput(picture(), blocks = true)
            assertFailsWith<CoreFailure.WriteNotApplied>(style.name) { MaskEngine.apply(image, masks, settings(style)) }
            assertEquals(1, image.changed, "$style: a block was changed")
        }
    }

    @Test
    fun anImageThatKeepsItsInputPassesTheReadBack() {
        val masks = listOf(MaskGeometry(96f, 96f, 25f, 25f, MaskShape.RECTANGLE))
        val image = picture()
        MaskEngine.apply(image, masks, settings(MaskStyle.SOLID))
        assertTrue(image.data[96 * 300 + 96] ushr 24 == 0xFF)
    }
}
