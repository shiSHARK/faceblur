// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.PixelImage
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.model.Sha256
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The image fingerprint of ADR 0015 decision 6 and the incremental SHA-256 behind it. The digest has no accessor;
 * its definition (the bytes hashed) is checked on the JVM against the JDK digest (`ImageFingerprintJvmTest`).
 */
class ImageFingerprintTest {
    /** Records every block read; pixels are computed on the fly, so wide images need no full-size array. */
    private class CountingImage(override val width: Int, override val height: Int) : PixelImage {
        val blocks = ArrayList<IntArray>()

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
            blocks += intArrayOf(x, y, w, h)
            for (r in 0 until h) for (c in 0 until w) dst[r * w + c] = pixel(x + c, y + r)
        }

        fun pixel(x: Int, y: Int): Int = (0xFF shl 24) or ((x * 7 + y * 13) and 0xFFFFFF)
    }

    @Test
    fun anyChangeOfAPixelOrOfTheSizeGivesAnotherFingerprint() {
        val base = gradientPixels(40, 30)
        val f = ImageFingerprint.of(IntArrayPixels(40, 30, base.copyOf()))
        for (at in listOf(0, 1, base.size / 2 - 1, base.size - 1)) {
            for (bit in listOf(1, 0x100, 0x10000, 0x1000000)) {
                val changed = base.copyOf()
                changed[at] = changed[at] xor bit
                assertFalse(f.matches(ImageFingerprint.of(IntArrayPixels(40, 30, changed))), "pixel $at bit $bit")
            }
        }
        // The same pixels in another shape (30 x 40) differ through the size.
        assertFalse(f.matches(ImageFingerprint.of(IntArrayPixels(30, 40, base.copyOf()))))
        assertTrue(f.matches(ImageFingerprint.of(IntArrayPixels(40, 30, base.copyOf()))))
        assertFalse(f.matches(null), "a plan without a binding never matches")
        assertEquals("ImageFingerprint", f.toString())
    }

    @Test
    fun pixelsAreReadInBandsOfWholeRowsNeverAsAFullSizeCopy() {
        val image = CountingImage(1000, 1000)
        ImageFingerprint.of(image)
        assertEquals(16, image.blocks.size, "65 rows of 1,000 px per band: 16 bands for 1,000 rows")
        var y = 0
        for (b in image.blocks) {
            assertEquals(0, b[0])
            assertEquals(y, b[1], "bands follow each other")
            assertEquals(1000, b[2], "whole rows")
            assertTrue(b[2] * b[3] <= 65_536, "at most 65,536 pixels per band")
            y += b[3]
        }
        assertEquals(1000, y, "every row once")
        // A row longer than a band is read one row at a time.
        val wide = CountingImage(65_535, 3)
        ImageFingerprint.of(wide)
        assertEquals(listOf(1, 1, 1), wide.blocks.map { it[3] })
        // An empty image hashes the tag and the size only.
        val empty = CountingImage(0, 0)
        ImageFingerprint.of(empty)
        assertTrue(empty.blocks.isEmpty())
        // Banding gives the same digest as one read of the whole image.
        val pixels = IntArray(1000 * 1000) { image.pixel(it % 1000, it / 1000) }
        assertTrue(ImageFingerprint.of(image).matches(ImageFingerprint.of(IntArrayPixels(1000, 1000, pixels))))
    }

    @Test
    fun theIncrementalHasherEqualsTheOneShotDigestForAnySplit() {
        for (size in listOf(0, 1, 55, 56, 63, 64, 65, 119, 120, 127, 128, 129, 1000)) {
            val data = ByteArray(size) { (it * 31 + 7).toByte() }
            val expected = Sha256.digest(data)
            for (chunk in listOf(1, 7, 63, 64, 65, 200)) {
                val h = Sha256.Hasher()
                var at = 0
                while (at < size) {
                    val n = minOf(chunk, size - at)
                    h.update(data, at, n)
                    at += n
                }
                assertContentEquals(expected, h.finish(), "size $size chunk $chunk")
            }
            val whole = Sha256.Hasher()
            whole.update(data)
            whole.update(data, size, 0)
            assertContentEquals(expected, whole.finish(), "size $size in one part")
        }
        // Known answer (FIPS 180-4 example "abc").
        val abc = Sha256.Hasher()
        abc.update("abc".encodeToByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.toHex(abc.finish()))
    }

    @Test
    fun theHasherRefusesBadRangesAndUseAfterFinish() {
        val h = Sha256.Hasher()
        assertFailsWith<IllegalArgumentException> { h.update(ByteArray(4), 3, 2) }
        assertFailsWith<IllegalArgumentException> { h.update(ByteArray(4), -1, 1) }
        assertFailsWith<IllegalArgumentException> { h.update(ByteArray(4), 0, -1) }
        h.finish()
        assertFailsWith<IllegalStateException> { h.update(ByteArray(1)) }
        assertFailsWith<IllegalStateException> { h.finish() }
    }
}
