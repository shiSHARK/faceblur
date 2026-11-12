// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.UprightBox
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
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
 * The orientation form of `FaceBlurIos.wrapRgba` (ADR 0011, in-memory orientation): the library sees and writes the
 * upright view of the stored pixels. The expected mapping comes from `UprightBox.fromRaw` (the documented EXIF
 * table), not from the adapter's own arithmetic.
 */
class IosOrientationTest {
    private val padding = 12

    /** The upright pixel (u, v) that raw pixel (x, y) of a [rawW] × [rawH] image is shown at, by `UprightBox`. */
    private fun upright(x: Int, y: Int, rawW: Int, rawH: Int, orientation: Int): Pair<Int, Int> {
        val b = UprightBox.fromRaw(x.toFloat(), y.toFloat(), x + 1f, y + 1f, rawW, rawH, orientation)
        assertEquals(1f, b.right - b.left)
        assertEquals(1f, b.bottom - b.top)
        return b.left.toInt() to b.top.toInt()
    }

    private class Raw(val p: CPointer<ByteVar>, val w: Int, val h: Int, val bpr: Int) {
        fun free() = nativeHeap.free(p.rawValue)
        fun bytes() = ByteArray(bpr * h) { p[it] }

        /** The opaque ARGB colour stored at raw (x, y) in [layout]. */
        fun argb(x: Int, y: Int, layout: IosPixelLayout): Int {
            val o = y * bpr + x * 4
            val rgba = layout == IosPixelLayout.RGBA_PREMULTIPLIED || layout == IosPixelLayout.RGBA_NONE_SKIP
            val r = p[o + if (rgba) 0 else 2].toInt() and 0xFF
            val g = p[o + 1].toInt() and 0xFF
            val b = p[o + if (rgba) 2 else 0].toInt() and 0xFF
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * A raw buffer of [rawW] × [rawH] that a viewer applying [orientation] shows as [upright] ([uw] × [uh], opaque),
     * built pixel by pixel from the `UprightBox` mapping; padding 0x5A, alpha 255.
     */
    private fun rawFor(
        upright: IntArray,
        uw: Int,
        orientation: Int,
        rawW: Int,
        rawH: Int,
        layout: IosPixelLayout,
    ): Raw {
        val bpr = rawW * 4 + padding
        val p = nativeHeap.allocArray<ByteVar>(bpr * rawH)
        for (i in 0 until bpr * rawH) p[i] = 0x5A
        val rgba = layout == IosPixelLayout.RGBA_PREMULTIPLIED || layout == IosPixelLayout.RGBA_NONE_SKIP
        for (y in 0 until rawH) {
            for (x in 0 until rawW) {
                val (u, v) = upright(x, y, rawW, rawH, orientation)
                val c = upright[v * uw + u]
                val o = y * bpr + x * 4
                p[o + if (rgba) 0 else 2] = (c shr 16).toByte()
                p[o + 1] = (c shr 8).toByte()
                p[o + if (rgba) 2 else 0] = c.toByte()
                p[o + 3] = 0xFF.toByte()
            }
        }
        return Raw(p, rawW, rawH, bpr)
    }

    @Test
    fun theUprightViewMatchesUprightBoxForEveryOrientationAndLayout() {
        val rawW = 5
        val rawH = 3
        for (layout in IosPixelLayout.entries) {
            for (o in 1..8) {
                val uw = if (o >= 5) rawH else rawW
                val uh = if (o >= 5) rawW else rawH
                // Every upright pixel has its own colour, so any wrong mapping shows.
                val upright = IntArray(uw * uh) { (0xFF shl 24) or (it * 10 + 7) }
                val raw = rawFor(upright, uw, o, rawW, rawH, layout)
                try {
                    val view = FaceBlurIos.wrapRgba(raw.p.toLong(), rawW, rawH, raw.bpr, layout, o)
                    assertEquals(uw, view.width, "width o=$o")
                    assertEquals(uh, view.height, "height o=$o")
                    val seen = IntArray(uw * uh)
                    view.readPixels(0, 0, uw, uh, seen)
                    assertContentEquals(upright, seen, "whole view o=$o ${layout.name}")
                    // A block inside the view, as the tiles read it.
                    val block = IntArray(2 * 2)
                    view.readPixels(1, 1, 2, 2, block)
                    assertContentEquals(
                        intArrayOf(upright[uw + 1], upright[uw + 2], upright[2 * uw + 1], upright[2 * uw + 2]),
                        block,
                        "block o=$o",
                    )
                } finally {
                    raw.free()
                }
            }
        }
    }

    @Test
    fun writesThroughTheViewLandOnTheRawPixelsAViewerShowsThere() {
        val rawW = 6
        val rawH = 4
        val layout = IosPixelLayout.BGRA_PREMULTIPLIED
        for (o in 1..8) {
            val uw = if (o >= 5) rawH else rawW
            val uh = if (o >= 5) rawW else rawH
            val raw = rawFor(IntArray(uw * uh) { (0xFF shl 24) or 0x101010 }, uw, o, rawW, rawH, layout)
            try {
                val view = FaceBlurIos.wrapRgba(raw.p.toLong(), rawW, rawH, raw.bpr, layout, o)
                // Upright block at (1, 2), 2 wide, 2 high, each pixel its own colour.
                val src = intArrayOf(0xFF0000AA.toInt(), 0xFF0000BB.toInt(), 0xFF0000CC.toInt(), 0xFF0000DD.toInt())
                view.writePixels(1, 2, 2, 2, src)
                for (y in 0 until rawH) {
                    for (x in 0 until rawW) {
                        val (u, v) = upright(x, y, rawW, rawH, o)
                        val inBlock = u in 1..2 && v in 2..3
                        val expected = if (inBlock) src[(v - 2) * 2 + (u - 1)] else (0xFF shl 24) or 0x101010
                        assertEquals(expected, raw.argb(x, y, layout), "raw ($x,$y) o=$o")
                        assertEquals(0xFF, raw.p[y * raw.bpr + x * 4 + 3].toInt() and 0xFF, "alpha ($x,$y) o=$o")
                    }
                    for (k in rawW * 4 until raw.bpr) assertEquals(0x5A, raw.p[y * raw.bpr + k].toInt(), "padding")
                }
            } finally {
                raw.free()
            }
        }
    }

    @Test
    fun maskingInEveryOrientationGivesTheMasksOfTheUprightImage() {
        val uw = 640
        val uh = 480
        val upright = gradientPixels(uw, uh)
        val ref = IntArrayPixels(uw, uh, upright.copyOf())
        val refResult = IosFiles.masker().use { it.maskInPlace(ref) }
        assertEquals(1, refResult.masks.size)
        assertTrue(!ref.data.contentEquals(upright), "the reference has a mask")
        for (layout in listOf(IosPixelLayout.BGRA_PREMULTIPLIED, IosPixelLayout.RGBA_NONE_SKIP)) {
            for (o in 1..8) {
                val rawW = if (o >= 5) uh else uw
                val rawH = if (o >= 5) uw else uh
                val raw = rawFor(upright, uw, o, rawW, rawH, layout)
                try {
                    val image = FaceBlurIos.wrapRgba(raw.p.toLong(), rawW, rawH, raw.bpr, layout, o)
                    val result = IosFiles.masker().use { it.maskInPlace(image) }
                    assertEquals(refResult.faces, result.faces, "faces in upright coordinates o=$o ${layout.name}")
                    assertEquals(refResult.masks, result.masks, "masks in upright coordinates o=$o ${layout.name}")
                    // Read the raw bytes directly (not through the view): each must equal the masked upright pixel
                    // a viewer shows there.
                    for (y in 0 until rawH) {
                        for (x in 0 until rawW) {
                            val (u, v) = upright(x, y, rawW, rawH, o)
                            val want = ref.data[v * uw + u]
                            if (raw.argb(x, y, layout) != want) {
                                throw AssertionError("raw ($x,$y) o=$o ${layout.name}: not the masked upright pixel")
                            }
                        }
                    }
                } finally {
                    raw.free()
                }
            }
        }
    }

    @Test
    fun anOrientationOutside1To8IsRefusedBeforeAnythingIsRead() {
        val p = nativeHeap.allocArray<ByteVar>(64)
        try {
            for (o in listOf(0, 9, -1, Int.MAX_VALUE)) {
                val e = assertFailsWith<FaceMaskException.InvalidConfiguration>("o=$o") {
                    FaceBlurIos.wrapRgba(p.toLong(), 4, 4, 16, IosPixelLayout.RGBA_NONE_SKIP, o)
                }
                assertEquals("orientation", e.parameter)
            }
            // The internal constructor refuses it too (file mode passes 1 only).
            assertFailsWith<FaceMaskException.InvalidConfiguration> {
                IosBufferPixels(p, 4, 4, 16, IosPixelLayout.RGBA_NONE_SKIP, 0)
            }
        } finally {
            nativeHeap.free(p.rawValue)
        }
    }

    @Test
    fun theFormWithoutAnOrientationIsOrientation1() {
        val raw = rawFor(IntArray(4 * 3) { (0xFF shl 24) or it }, 4, 1, 4, 3, IosPixelLayout.RGBA_NONE_SKIP)
        try {
            val a = IntArray(12).also {
                FaceBlurIos.wrapRgba(
                    raw.p.toLong(),
                    4,
                    3,
                    raw.bpr,
                    IosPixelLayout.RGBA_NONE_SKIP,
                ).readPixels(0, 0, 4, 3, it)
            }
            val b = IntArray(12).also {
                FaceBlurIos.wrapRgba(
                    raw.p.toLong(),
                    4,
                    3,
                    raw.bpr,
                    IosPixelLayout.RGBA_NONE_SKIP,
                    1,
                ).readPixels(0, 0, 4, 3, it)
            }
            assertContentEquals(a, b)
        } finally {
            raw.free()
        }
    }
}
