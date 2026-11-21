// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.Golden
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Golden output of the mask stage: the masked pixels of fixed plans on fixed synthetic pictures, for every style,
 * must stay bit-identical (the digests were recorded before the mask stage was reworked for speed, and any later
 * change that alters a single pixel fails here). Runs on every platform of the common tests; the pixels are pure
 * library arithmetic, so the values are the same everywhere.
 *
 * The plans cover what the copy and the fills distinguish: masks over many whole 64 × 64 tiles, a rectangle, overlaps,
 * masks across the right and bottom edges (partial tiles), a tiny mask, a picture whose size is not a multiple of 64,
 * a picture with translucent and fully transparent pixels, and the soft edge on and off.
 */
class MaskGoldenTest {
    private class Case(
        val name: String,
        val w: Int,
        val h: Int,
        val pixels: () -> IntArray,
        val masks: List<MaskGeometry>,
    )

    private val opaque = Case(
        "opaque 700x530",
        700,
        530,
        { Golden.photoLike(700, 530, 11) },
        listOf(
            MaskGeometry(260f, 240f, 170f, 200f, MaskShape.ELLIPSE),
            MaskGeometry(560f, 120f, 90f, 70f, MaskShape.RECTANGLE),
            MaskGeometry(400.5f, 330.25f, 60f, 80f, MaskShape.ELLIPSE),
            MaskGeometry(690f, 520f, 50f, 60f, MaskShape.ELLIPSE),
            MaskGeometry(30f, 30f, 3f, 4f, MaskShape.ELLIPSE),
        ),
    )

    /** Alpha 255, 128 and 0 in a pattern: soft edges over translucent pixels and fills that skip hidden colours. */
    private val translucent = Case(
        "translucent 333x197",
        333,
        197,
        {
            Golden.photoLike(333, 197, 5).also { px ->
                for (i in px.indices) {
                    val a = when ((i / 7) % 5) {
                        0 -> 0
                        1 -> 128
                        else -> 255
                    }
                    px[i] = (a shl 24) or (px[i] and 0xFFFFFF)
                }
            }
        },
        listOf(
            MaskGeometry(100f, 90f, 80f, 70f, MaskShape.ELLIPSE),
            MaskGeometry(5f, 150f, 40f, 45f, MaskShape.RECTANGLE),
            MaskGeometry(250f, 100f, 60f, 90f, MaskShape.ELLIPSE),
        ),
    )

    private fun digest(case: Case, style: MaskStyle, feather: Float): String {
        val image = IntArrayPixels(case.w, case.h, case.pixels())
        MaskEngine.apply(image, case.masks, MaskSettings(style = style, feather = feather))
        return Golden.fnv(image.data)
    }

    private fun actual(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (style in MaskStyle.entries) {
            out["${opaque.name} $style feather 0.1"] = digest(opaque, style, 0.1f)
            out["${opaque.name} $style feather 0"] = digest(opaque, style, 0f)
            out["${translucent.name} $style feather 0.1"] = digest(translucent, style, 0.1f)
        }
        return out
    }

    @Test
    fun theMaskedPixelsOfEveryStyleAreUnchanged() {
        val actual = actual()
        assertEquals(EXPECTED, actual, actual.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," })
    }

    @Test
    fun theDigestSeesASinglePixelChange() {
        val image = IntArrayPixels(opaque.w, opaque.h, opaque.pixels())
        MaskEngine.apply(image, opaque.masks, MaskSettings(style = MaskStyle.SMOOTH_FILL))
        val before = Golden.fnv(image.data)
        image.data[260 * opaque.w + 260] = image.data[260 * opaque.w + 260] xor 1
        assertNotEquals(before, Golden.fnv(image.data))
    }

    private companion object {
        val EXPECTED: Map<String, String> = mapOf(
            "opaque 700x530 GRADIENT_OVAL feather 0.1" to "986df855e27f3b20",
            "opaque 700x530 GRADIENT_OVAL feather 0" to "35aac0756ba6652f",
            "translucent 333x197 GRADIENT_OVAL feather 0.1" to "9e4e5a4e90d679fa",
            "opaque 700x530 SMOOTH_FILL feather 0.1" to "3c37da6f77fd6305",
            "opaque 700x530 SMOOTH_FILL feather 0" to "9a422ad6bc804cc3",
            "translucent 333x197 SMOOTH_FILL feather 0.1" to "045c0f9d71dd7343",
            "opaque 700x530 SOLID feather 0.1" to "9783e19952315af1",
            "opaque 700x530 SOLID feather 0" to "d9567ac9175f4e32",
            "translucent 333x197 SOLID feather 0.1" to "21750e563e33f3de",
        )
    }
}
