// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.Golden
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.image.PixelDetectionImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * The detection input with one pixel copy per pass: the direct YuNet tensor fill gives bit for bit the tensor of the
 * general fill (also over a reused buffer full of other values), and a crop hands its own pixels to the fill.
 */
class TensorFillTest {
    private val detector = yunetDetector(
        object : TensorRunner {
            override val inputWidth = 160
            override val inputHeight = 128

            override fun initialize() = Unit

            override fun close() = Unit

            override fun run(input: FloatArray): Map<String, FloatArray> = emptyMap()
        },
        yunetProfile(160, 128),
    )

    private fun rawBits(values: FloatArray) = IntArray(values.size) { values[it].toRawBits() }

    @Test
    fun theDirectYuNetFillEqualsTheGeneralFillBitForBit() {
        val sizes = listOf(160 to 128, 159 to 128, 160 to 1, 1 to 1, 100 to 90, 37 to 128)
        for ((w, h) in sizes) {
            // Every channel value 0..255 and alpha values the fill must ignore.
            val argb = Golden.photoLike(w, h, w * 31 + h).also { px ->
                for (i in px.indices) px[i] = (((i * 37) and 0xFF) shl 24) or (px[i] and 0xFFFFFF)
                if (px.size > 2) {
                    px[0] = 0x00FFFFFF
                    px[1] = 0xFF000000.toInt()
                }
            }
            val general = FloatArray(3 * 160 * 128) { Float.NaN }
            val direct = FloatArray(3 * 160 * 128) { if (it % 2 == 0) Float.NaN else -0f }
            detector.fillInput(argb, w, h, general)
            detector.fillYuNet(argb, w, h, direct)
            assertEquals(rawBits(general).toList(), rawBits(direct).toList(), "$w x $h")
            val dispatched = FloatArray(3 * 160 * 128) { 7f }
            detector.fillTensor(argb, w, h, dispatched)
            assertEquals(
                rawBits(general).toList(),
                rawBits(dispatched).toList(),
                "$w x $h: YuNet takes the direct fill",
            )
        }
    }

    @Test
    fun theDirectFillWritesOnlyTheTensorAndReadsOnlyTheImage() {
        val argb = IntArray(20 * 10) { 0xFF000000.toInt() or it }
        val before = argb.copyOf()
        val dst = FloatArray(3 * 160 * 128 + 5) { 9f }
        detector.fillYuNet(argb, 20, 10, dst)
        assertEquals(before.toList(), argb.toList(), "the pixels are only read")
        for (i in 3 * 160 * 128 until dst.size) assertEquals(9f, dst[i], "nothing written beyond the tensor")
    }

    @Test
    fun aCropHandsItsOwnPixelsToTheFillAndTheCallersImageIsCopied() {
        val image = PixelDetectionImage(IntArrayPixels(300, 200, Golden.photoLike(300, 200, 3)))
        val whole = image.readArgb()
        assertNotSame(whole, image.readArgb(), "the caller's pixels are copied, never shared")
        for ((w, target) in listOf(300 to 300, 300 to 150)) {
            val crop = image.cropScaled(0, 0, w, 200, target)
            val pixels = crop.readArgb()
            assertSame(pixels, crop.readArgb(), "a crop returns its own array, no second copy")
            assertEquals(crop.width * crop.height, pixels.size)
            val expected = IntArray(crop.width * crop.height)
            (crop as PixelDetectionImage).cropScaled(0, 0, crop.width, crop.height, 1_000).readArgb()
                .copyInto(expected)
            assertEquals(expected.toList(), pixels.toList(), "the same pixels as a copy of them")
            crop.release()
        }
    }
}
