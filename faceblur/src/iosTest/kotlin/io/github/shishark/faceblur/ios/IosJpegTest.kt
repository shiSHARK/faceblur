// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.filemode.JpegParser
import io.github.shishark.faceblur.filemode.OutputValidator
import io.github.shishark.faceblur.gradientPixels
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import platform.posix.MAP_ANON
import platform.posix.MAP_FAILED
import platform.posix.MAP_PRIVATE
import platform.posix.PROT_NONE
import platform.posix.PROT_READ
import platform.posix.PROT_WRITE
import platform.posix.getpagesize
import platform.posix.mmap
import platform.posix.mprotect
import platform.posix.munmap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `FaceBlurIos.encodeJpeg` (the in-memory JPEG helper): the encoder of file mode on the caller's pixels, the common
 * rebuild and the fail-closed output check with `StripAll`; nothing on disk, the buffer unchanged.
 */
class IosJpegTest {
    private val w = 96
    private val h = 64
    private val bpr = w * 4 + 8

    private fun buffer(layout: IosPixelLayout, alpha: Int = 0xFF): CPointer<ByteVar> {
        val p = nativeHeap.allocArray<ByteVar>(bpr * h)
        for (i in 0 until bpr * h) p[i] = 0x33
        val px = gradientPixels(w, h)
        val rgba = layout == IosPixelLayout.RGBA_PREMULTIPLIED || layout == IosPixelLayout.RGBA_NONE_SKIP
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[y * w + x]
                val o = y * bpr + x * 4
                p[o + if (rgba) 0 else 2] = (c shr 16).toByte()
                p[o + 1] = (c shr 8).toByte()
                p[o + if (rgba) 2 else 0] = c.toByte()
                p[o + 3] = alpha.toByte()
            }
        }
        return p
    }

    private fun bytes(p: CPointer<ByteVar>) = ByteArray(bpr * h) { p[it] }

    private inline fun <T> withBuffer(layout: IosPixelLayout, alpha: Int = 0xFF, block: (CPointer<ByteVar>) -> T): T {
        val p = buffer(layout, alpha)
        try {
            return block(p)
        } finally {
            nativeHeap.free(p.rawValue)
        }
    }

    private fun markers(jpeg: ByteArray) = JpegParser.parse(jpeg).also { assertEquals(emptyList(), it.findings) }
        .segments.map { it.marker }

    @Test
    fun everyLayoutEncodesACheckedJpegWithoutMetadataAndLeavesTheBufferUnchanged() {
        val before = IosFiles.snapshot()
        for (layout in IosPixelLayout.entries) {
            withBuffer(layout) { p ->
                val original = bytes(p)
                val jpeg = FaceBlurIos.encodeJpeg(p.toLong(), w, h, bpr, layout, 90).toByteArray()
                assertContentEquals(original, bytes(p), "the buffer is unchanged: ${layout.name}")
                assertEquals(emptyList(), OutputValidator.findings(jpeg, MetadataPolicy.StripAll), layout.name)
                // Only the fixed JFIF APP0, the library's EXIF APP1 and coding segments: no ICC profile (APP2), no
                // XMP, no MPF, no comment.
                val m = markers(jpeg)
                assertEquals(listOf(0xE0, 0xE1), m.take(2), layout.name)
                assertTrue(m.drop(2).none { it in 0xE0..0xEF || it == 0xFE }, "${layout.name}: $m")
                assertTrue(!jpeg.decodeToString().contains("ICC_PROFILE"), "no ICC profile: ${layout.name}")
                val seen = IosFiles.inspect(jpeg)
                assertTrue(seen.clean, "${layout.name}: $seen")
                assertEquals(w.toLong(), seen.width)
                assertEquals(h.toLong(), seen.height)
            }
        }
        assertEquals(emptyList(), IosFiles.diff(before, IosFiles.snapshot()), "no file created, removed or resized")
    }

    @Test
    fun theRebuildDropsWhateverTheEncoderAddsAroundThePixels() {
        withBuffer(IosPixelLayout.BGRA_NONE_SKIP) { p ->
            val planted = IosJpeg.Encoder { base, ww, hh, rb, bgra, q ->
                val real = IosJpeg.encodePixels(base, ww, hh, rb, bgra, q)
                fun seg(marker: Int, payload: ByteArray): ByteArray {
                    val n = payload.size + 2
                    return byteArrayOf(0xFF.toByte(), marker.toByte(), (n shr 8).toByte(), n.toByte()) + payload
                }
                byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
                    seg(0xE1, "Exif\u0000\u0000${Canary.EXIF}".encodeToByteArray()) +
                    seg(0xE2, "ICC_PROFILE\u0000\u0001\u0001planted".encodeToByteArray()) +
                    seg(0xE1, "http://ns.adobe.com/xap/1.0/\u0000${Canary.XMP}".encodeToByteArray()) +
                    seg(0xFE, Canary.MAKE.encodeToByteArray()) +
                    real.copyOfRange(2, real.size)
            }
            val jpeg = IosJpeg.encode(p.toLong(), w, h, bpr, IosPixelLayout.BGRA_NONE_SKIP, 85, planted).toByteArray()
            val text = jpeg.decodeToString()
            for (c in listOf(Canary.EXIF, Canary.XMP, Canary.MAKE, "ICC_PROFILE")) {
                assertTrue(!text.contains(c), "dropped: $c")
            }
            assertEquals(emptyList(), OutputValidator.findings(jpeg, MetadataPolicy.StripAll))
            assertTrue(IosFiles.inspect(jpeg).clean)
        }
    }

    @Test
    fun theOutputCheckFailsClosed() {
        withBuffer(IosPixelLayout.RGBA_PREMULTIPLIED) { p ->
            val trailing = IosJpeg.Encoder { base, ww, hh, rb, bgra, q ->
                IosJpeg.encodePixels(base, ww, hh, rb, bgra, q) + byteArrayOf(0x00, 0x11, 0x22)
            }
            val e = assertFailsWith<FaceMaskException.OutputValidationFailed> {
                IosJpeg.encode(p.toLong(), w, h, bpr, IosPixelLayout.RGBA_PREMULTIPLIED, 90, trailing)
            }
            assertTrue(e.findings.isNotEmpty())
            val truncated = IosJpeg.Encoder { base, ww, hh, rb, bgra, q ->
                IosJpeg.encodePixels(base, ww, hh, rb, bgra, q).let { it.copyOf(it.size / 2) }
            }
            assertFailsWith<FaceMaskException.OutputValidationFailed> {
                IosJpeg.encode(p.toLong(), w, h, bpr, IosPixelLayout.RGBA_PREMULTIPLIED, 90, truncated)
            }
            // A frame of another size than the buffer's is refused too (the encoder is a platform part).
            val smaller = IosJpeg.Encoder { base, ww, hh, rb, bgra, q ->
                IosJpeg.encodePixels(base, ww - 1, hh, rb, bgra, q)
            }
            assertFailsWith<FaceMaskException.Internal> {
                IosJpeg.encode(p.toLong(), w, h, bpr, IosPixelLayout.RGBA_PREMULTIPLIED, 90, smaller)
            }
        }
    }

    @Test
    fun refusalsHappenBeforeAnythingIsEncoded() {
        var encoded = 0
        val counting = IosJpeg.Encoder { base, ww, hh, rb, bgra, q ->
            encoded++
            IosJpeg.encodePixels(base, ww, hh, rb, bgra, q)
        }
        for (layout in listOf(IosPixelLayout.RGBA_PREMULTIPLIED, IosPixelLayout.BGRA_PREMULTIPLIED)) {
            withBuffer(layout) { p ->
                p[(h - 1) * bpr + (w - 1) * 4 + 3] = 254.toByte()
                assertFailsWith<FaceMaskException.UnsupportedImage>(layout.name) {
                    IosJpeg.encode(p.toLong(), w, h, bpr, layout, 90, counting)
                }
            }
            withBuffer(layout, alpha = 0) { p ->
                assertFailsWith<FaceMaskException.UnsupportedImage> {
                    FaceBlurIos.encodeJpeg(p.toLong(), w, h, bpr, layout, 90)
                }
            }
        }
        withBuffer(IosPixelLayout.RGBA_NONE_SKIP) { p ->
            for (q in intArrayOf(0, 100 + 1, -1)) {
                val e = assertFailsWith<FaceMaskException.InvalidConfiguration>("q=$q") {
                    IosJpeg.encode(p.toLong(), w, h, bpr, IosPixelLayout.RGBA_NONE_SKIP, q, counting)
                }
                assertEquals("quality", e.parameter)
            }
            val e = assertFailsWith<FaceMaskException.InvalidConfiguration> {
                IosJpeg.encode(p.toLong(), w, h, w * 4 - 1, IosPixelLayout.RGBA_NONE_SKIP, 90, counting)
            }
            assertEquals("bytesPerRow", e.parameter)
        }
        // A side above the JPEG limit (65,535): one row of 65,536 pixels.
        val wide = 65_536
        val p = nativeHeap.allocArray<ByteVar>(wide * 4)
        try {
            for (i in 0 until wide * 4) p[i] = 0xFF.toByte()
            assertFailsWith<FaceMaskException.UnsupportedImage> {
                IosJpeg.encode(p.toLong(), wide, 1, wide * 4, IosPixelLayout.RGBA_NONE_SKIP, 90, counting)
            }
        } finally {
            nativeHeap.free(p.rawValue)
        }
        assertEquals(0, encoded, "nothing reached the encoder")
    }

    @Test
    fun encodedBytesExportInOneCopyAndRefuseASmallTarget() {
        withBuffer(IosPixelLayout.BGRA_PREMULTIPLIED) { p ->
            val jpeg = FaceBlurIos.encodeJpeg(p.toLong(), w, h, bpr, IosPixelLayout.BGRA_PREMULTIPLIED, 70)
            val expected = jpeg.toByteArray()
            assertEquals(expected.size, jpeg.byteCount)
            val out = nativeHeap.allocArray<ByteVar>(expected.size + 4)
            try {
                for (i in 0 until expected.size + 4) out[i] = 0x44
                jpeg.exportTo(out.toLong(), expected.size.toLong() + 4)
                assertContentEquals(expected, ByteArray(expected.size) { out[it] })
                assertEquals(0x44, out[expected.size].toInt(), "nothing after the bytes")
                fun param(block: () -> Unit) =
                    assertFailsWith<FaceMaskException.InvalidConfiguration> { block() }.parameter
                assertEquals("capacity", param { jpeg.exportTo(out.toLong(), expected.size.toLong() - 1) })
                assertEquals("address", param { jpeg.exportTo(0L, expected.size.toLong()) })
                assertEquals("address", param { jpeg.exportTo(-16L, expected.size.toLong()) })
            } finally {
                nativeHeap.free(out.rawValue)
            }
            assertTrue(jpeg.toByteArray() !== jpeg.toByteArray(), "toByteArray returns a copy")
        }
    }

    @Test
    fun keepsOneOutputForTheIndependentChecker() {
        withBuffer(IosPixelLayout.BGRA_PREMULTIPLIED) { p ->
            val jpeg = FaceBlurIos.encodeJpeg(
                p.toLong(),
                w,
                h,
                bpr,
                IosPixelLayout.BGRA_PREMULTIPLIED,
                90,
            ).toByteArray()
            IosFiles.keepForChecker("outputs", "encode-jpeg-helper.jpg", jpeg)
        }
    }

    /**
     * The encoder's no-copy image covers exactly the extent `wrapRgba` validates, so a buffer
     * whose last row ends right after its last pixel (no padding after it) is never read past. The buffer ends at an
     * inaccessible guard page: any read beyond the last pixel would stop the test process.
     */
    @Test
    fun aBufferWithoutPaddingAfterItsLastRowIsReadOnlyWithinItsExtent() {
        assertEquals(bpr.toLong() * (h - 1) + w * 4L, IosJpeg.extent(w, h, bpr.toLong()))
        val page = getpagesize().toLong()
        val extent = IosJpeg.extent(w, h, bpr.toLong())
        val dataPages = (extent + page - 1) / page
        val total = (dataPages + 1) * page
        val map = mmap(null, total.convert(), PROT_READ or PROT_WRITE, MAP_ANON or MAP_PRIVATE, -1, 0)
        assertTrue(map != null && map != MAP_FAILED, "mmap")
        try {
            val guard = map.toLong() + dataPages * page
            assertEquals(0, mprotect(guard.toCPointer<ByteVar>(), page.convert(), PROT_NONE), "guard page")
            val p = (guard - extent).toCPointer<ByteVar>()!!
            withBuffer(IosPixelLayout.RGBA_NONE_SKIP) { src ->
                for (i in 0 until extent.toInt()) p[i] = src[i]
            }
            val original = ByteArray(extent.toInt()) { p[it] }
            for (layout in listOf(IosPixelLayout.RGBA_NONE_SKIP, IosPixelLayout.BGRA_NONE_SKIP)) {
                val jpeg = FaceBlurIos.encodeJpeg(p.toLong(), w, h, bpr, layout, 90).toByteArray()
                assertEquals(emptyList(), OutputValidator.findings(jpeg, MetadataPolicy.StripAll), layout.name)
            }
            assertContentEquals(original, ByteArray(extent.toInt()) { p[it] }, "the buffer is unchanged")
        } finally {
            munmap(map, total.convert())
        }
    }
}
