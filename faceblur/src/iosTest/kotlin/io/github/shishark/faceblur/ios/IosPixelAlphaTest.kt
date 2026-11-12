// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.toLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR 0011 decision 4a in the Kotlin pixel adapter (`FaceBlurIos.wrapRgba`), for every caller: a premultiplied
 * buffer with any pixel that is not opaque (alpha below 255), or a skip layout with a fourth byte other than 0 or
 * 255, is refused with `UnsupportedImage` before anything is read for detection or written, and the buffer stays
 * unchanged.
 */
class IosPixelAlphaTest {
    private val w = 37
    private val h = 23
    private val bpr = w * 4 + 12

    private val premultiplied = listOf(IosPixelLayout.RGBA_PREMULTIPLIED, IosPixelLayout.BGRA_PREMULTIPLIED)
    private val skip = listOf(IosPixelLayout.RGBA_NONE_SKIP, IosPixelLayout.BGRA_NONE_SKIP)

    /** Every pixel (r, g, b, a) = (c, c, c, a); padding 0x77 (never read, so any value is allowed there). */
    private fun buffer(c: Int, a: Int): CPointer<ByteVar> {
        val p = nativeHeap.allocArray<ByteVar>(bpr * h)
        for (i in 0 until bpr * h) p[i] = 0x77
        for (y in 0 until h) {
            for (x in 0 until w) {
                val o = y * bpr + x * 4
                p[o] = c.toByte()
                p[o + 1] = c.toByte()
                p[o + 2] = c.toByte()
                p[o + 3] = a.toByte()
            }
        }
        return p
    }

    private fun bytes(p: CPointer<ByteVar>) = ByteArray(bpr * h) { p[it] }

    private fun wrap(p: CPointer<ByteVar>, layout: IosPixelLayout) = FaceBlurIos.wrapRgba(p.toLong(), w, h, bpr, layout)

    private inline fun <T> withBuffer(c: Int, a: Int, block: (CPointer<ByteVar>) -> T): T {
        val p = buffer(c, a)
        try {
            return block(p)
        } finally {
            nativeHeap.free(p.rawValue)
        }
    }

    private fun assertRefused(p: CPointer<ByteVar>, layout: IosPixelLayout, what: String) {
        val before = bytes(p)
        val e = assertFailsWith<FaceMaskException.UnsupportedImage>("$what ${layout.name}") { wrap(p, layout) }
        assertTrue(e.message!!.startsWith("Unsupported image: "), e.message)
        assertContentEquals(before, bytes(p), "buffer unchanged: $what ${layout.name}")
    }

    @Test
    fun aPremultipliedPixelThatIsNotOpaqueIsRefused() {
        for (layout in premultiplied) {
            // A face drawn with alpha 0: detection would see black while an alpha-ignoring viewer sees the face.
            withBuffer(c = 0, a = 0xFF) { p ->
                val o = (h - 1) * bpr + (w - 1) * 4 // the very last pixel
                p[o + 1] = 1
                p[o + 3] = 0
                assertRefused(p, layout, "colour 1 at alpha 0 in the last pixel")
            }
            withBuffer(c = 100, a = 0xFF) { p ->
                p[(h / 2) * bpr + (w / 2) * 4 + 3] = 254.toByte()
                assertRefused(p, layout, "alpha 254 in one pixel")
            }
            // Valid premultiplied data is refused too when it is not opaque: reject, never repair.
            for ((c, a) in listOf(0 to 0, 100 to 100, 0 to 1, 254 to 254, 200 to 100)) {
                withBuffer(c, a) { p -> assertRefused(p, layout, "every pixel c=$c a=$a") }
            }
        }
    }

    @Test
    fun opaquePremultipliedBuffersAreAccepted() {
        for (layout in premultiplied) {
            for (c in listOf(0, 7, 128, 255)) {
                withBuffer(c, 0xFF) { p ->
                    val before = bytes(p)
                    val image = wrap(p, layout)
                    assertEquals(w, image.width)
                    assertContentEquals(before, bytes(p), "wrapping writes nothing")
                }
            }
        }
    }

    @Test
    fun aSkipLayoutAcceptsOnlyFourthBytesOf0Or255() {
        for (layout in skip) {
            for (a in listOf(0, 255)) {
                withBuffer(c = 200, a = a) { p -> wrap(p, layout) }
            }
            withBuffer(c = 200, a = 0) { p ->
                for (y in 0 until h step 2) p[y * bpr + 3] = 0xFF.toByte() // a mix of 0 and 255 is fine
                wrap(p, layout)
            }
            for (bad in listOf(1, 128, 254)) {
                withBuffer(c = 200, a = 255) { p ->
                    p[(h - 1) * bpr + (w - 1) * 4 + 3] = bad.toByte()
                    assertRefused(p, layout, "skip byte $bad in the last pixel")
                }
            }
        }
    }

    @Test
    fun rowPaddingIsNeverChecked() {
        // Padding 0x77 sits between rows in every buffer above; a check that read it would refuse every layout.
        for (layout in IosPixelLayout.entries) withBuffer(c = 0, a = 0xFF) { p -> wrap(p, layout) }
    }
}
