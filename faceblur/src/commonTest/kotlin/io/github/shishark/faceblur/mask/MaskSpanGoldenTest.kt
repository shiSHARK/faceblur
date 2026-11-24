// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.Golden
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The mask stage finds each row's inside and soft-edge pixels by spans (the radius test only at the span ends and in
 * the soft edge) and must give exactly the pixels of the per-pixel test of 0.1.1. The digests below were recorded on
 * 0.1.1 before the change, for every style and several soft-edge widths, on plans that stress the span ends: masks
 * across every border and corner (centres outside the picture), tiny masks raised to 1 px at integer, half- and
 * quarter-pixel centres, very thin masks, a close-up mask over the whole of a photo-sized picture, and translucent
 * pixels. Masks have no rotation (ellipse or rectangle, axis-aligned), so there is no rotated case. The masks are
 * given to the stage directly, in list order.
 */
class MaskSpanGoldenTest {
    private class Case(
        val name: String,
        val w: Int,
        val h: Int,
        val pixels: () -> IntArray,
        val masks: List<MaskGeometry>,
    )

    private fun both(cx: Float, cy: Float, rx: Float, ry: Float, dx: Float = 0f): List<MaskGeometry> = listOf(
        MaskGeometry(cx, cy, rx, ry, MaskShape.ELLIPSE),
        MaskGeometry(cx + dx, cy, rx, ry, MaskShape.RECTANGLE),
    )

    /** Masks across all four borders and corners, centres inside and outside the picture (517 × 389). */
    private val border = Case(
        "border 517x389",
        517,
        389,
        { Golden.photoLike(517, 389, 3) },
        listOf(
            MaskGeometry(-20f, -15f, 60f, 50f, MaskShape.ELLIPSE),
            MaskGeometry(530.25f, 400.5f, 45f, 70f, MaskShape.ELLIPSE),
            MaskGeometry(258f, -40f, 90f, 55f, MaskShape.RECTANGLE),
            MaskGeometry(258.5f, 395f, 70f, 20.5f, MaskShape.ELLIPSE),
            MaskGeometry(-3.75f, 200f, 12f, 80f, MaskShape.RECTANGLE),
            MaskGeometry(516.5f, 120.25f, 33f, 41f, MaskShape.ELLIPSE),
            MaskGeometry(0f, 388f, 25f, 25f, MaskShape.RECTANGLE),
            MaskGeometry(520f, -2f, 30f, 18f, MaskShape.ELLIPSE),
        ),
    )

    /** Tiny masks (radii below 1 px are raised to 1 px) at integer, half- and quarter-pixel centres (97 × 71). */
    private val tiny = Case(
        "tiny 97x71",
        97,
        71,
        { Golden.photoLike(97, 71, 8) },
        both(10f, 10f, 0.3f, 0.4f, dx = 8f) +
            both(30.5f, 10.5f, 0.5f, 0.5f, dx = 8f) +
            both(50.25f, 10.75f, 1f, 1f, dx = 8f) +
            both(70.5f, 12f, 1.5f, 1.25f, dx = 8f) +
            both(10.25f, 35.5f, 2.3f, 1.7f, dx = 12f) +
            both(40f, 35f, 3.7f, 2.9f, dx = 14f) +
            both(75.75f, 40.25f, 5.5f, 4.5f, dx = 14f) +
            both(0.5f, 70.5f, 2f, 2f, dx = 96f),
    )

    /** Very thin masks: one row or column high or wide, and between pixel centres (300 × 220). */
    private val thin = Case(
        "thin 300x220",
        300,
        220,
        { Golden.photoLike(300, 220, 13) },
        listOf(
            MaskGeometry(150f, 30.5f, 140f, 1.5f, MaskShape.ELLIPSE),
            MaskGeometry(150.5f, 60f, 160f, 0.6f, MaskShape.RECTANGLE),
            MaskGeometry(40f, 120f, 1.2f, 90f, MaskShape.ELLIPSE),
            MaskGeometry(80.5f, 130.5f, 0.8f, 120f, MaskShape.RECTANGLE),
            MaskGeometry(200.25f, 150.75f, 95f, 3.3f, MaskShape.ELLIPSE),
        ),
    )

    /** A close-up mask over almost the whole photo-sized picture, with smaller masks on it (1280 × 960). */
    private val photo = Case(
        "photo 1280x960",
        1280,
        960,
        { Golden.photoLike(1280, 960, 29) },
        listOf(
            MaskGeometry(640.5f, 470.25f, 610f, 520f, MaskShape.ELLIPSE),
            MaskGeometry(200f, 150f, 80f, 95f, MaskShape.ELLIPSE),
            MaskGeometry(1100.5f, 820.5f, 120f, 100f, MaskShape.RECTANGLE),
        ),
    )

    /** Alpha 255, 200, 64 and 0 in a pattern: soft edges over translucent pixels (190 × 150). */
    private val translucent = Case(
        "translucent 190x150",
        190,
        150,
        {
            Golden.photoLike(190, 150, 41).also { px ->
                for (i in px.indices) {
                    val a = when ((i / 5) % 7) {
                        0 -> 0
                        1 -> 64
                        2 -> 200
                        else -> 255
                    }
                    px[i] = (a shl 24) or (px[i] and 0xFFFFFF)
                }
            }
        },
        both(60.5f, 70f, 45f, 38f, dx = 60f) + listOf(MaskGeometry(180f, 140f, 30f, 25f, MaskShape.ELLIPSE)),
    )

    private val cases = listOf(border, tiny, thin, photo, translucent)

    /** Soft edge off, the default and the widest (labels, so the keys read the same on every platform). */
    private val feathers = listOf("0" to 0f, "0.1" to 0.1f, "1" to 1f)

    private fun masked(case: Case, style: MaskStyle, feather: Float): IntArray {
        val image = IntArrayPixels(case.w, case.h, case.pixels())
        MaskEngine.apply(image, case.masks, MaskSettings(style = style, feather = feather))
        return image.data
    }

    private fun actual(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (case in cases) {
            for (style in MaskStyle.entries) {
                for ((label, f) in feathers) {
                    out["${case.name} $style feather $label"] =
                        Golden.fnv(masked(case, style, f))
                }
            }
        }
        return out
    }

    @Test
    fun theMaskedPixelsAreThoseOfTheRelease() {
        val actual = actual()
        assertEquals(EXPECTED, actual, actual.entries.joinToString("\n") { "\"${it.key}\" to \"${it.value}\"," })
    }

    /**
     * Many small random masks with fractional centres and radii, against the per-pixel engine before the bounded stage
     * ([LegacyMaskEngine], bit-identical to the release on images with straight alpha): every span end lands on a
     * different sub-pixel position.
     */
    @Test
    fun randomSubPixelMasksGiveThePixelsOfThePerPixelEngine() {
        for (seed in 1..6) {
            val r = Random(100 + seed)
            val w = 60 + r.nextInt(140)
            val h = 50 + r.nextInt(120)
            val masks = List(10 + r.nextInt(20)) {
                MaskGeometry(
                    -6f + r.nextFloat() * (w + 12),
                    -6f + r.nextFloat() * (h + 12),
                    0.2f + r.nextFloat() * (if (r.nextInt(4) == 0) 40f else 6f),
                    0.2f + r.nextFloat() * (if (r.nextInt(4) == 0) 40f else 6f),
                    if (r.nextBoolean()) MaskShape.ELLIPSE else MaskShape.RECTANGLE,
                )
            }
            for (style in MaskStyle.entries) {
                for (f in listOf(0f, 0.1f, 0.63f)) {
                    val s = MaskSettings(style = style, feather = f)
                    val old = stageImage(w, h, seed)
                    val new = stageImage(w, h, seed)
                    LegacyMaskEngine.apply(old, masks, s)
                    MaskEngine.apply(new, masks, s)
                    assertContentEquals(old.data, new.data, "seed $seed ${w}x$h $style feather $f")
                }
            }
        }
    }

    private companion object {
        val EXPECTED: Map<String, String> = mapOf(
            "border 517x389 GRADIENT_OVAL feather 0" to "9da528c2a3883b2b",
            "border 517x389 GRADIENT_OVAL feather 0.1" to "737ac83f171ecdaa",
            "border 517x389 GRADIENT_OVAL feather 1" to "e27abf8154f97d04",
            "border 517x389 SMOOTH_FILL feather 0" to "7d1444ade19acd0f",
            "border 517x389 SMOOTH_FILL feather 0.1" to "c57a8ba933bcf96a",
            "border 517x389 SMOOTH_FILL feather 1" to "685d465ee465339e",
            "border 517x389 SOLID feather 0" to "4fa6009fb63a6f38",
            "border 517x389 SOLID feather 0.1" to "da0892e32dc6845f",
            "border 517x389 SOLID feather 1" to "29161a5c60b25dc2",
            "tiny 97x71 GRADIENT_OVAL feather 0" to "36130c52418df3d7",
            "tiny 97x71 GRADIENT_OVAL feather 0.1" to "36130c52418df3d7",
            "tiny 97x71 GRADIENT_OVAL feather 1" to "e2a347d7a917e957",
            "tiny 97x71 SMOOTH_FILL feather 0" to "139fe4c9b454f9b5",
            "tiny 97x71 SMOOTH_FILL feather 0.1" to "139fe4c9b454f9b5",
            "tiny 97x71 SMOOTH_FILL feather 1" to "8e6a51d97a39f38c",
            "tiny 97x71 SOLID feather 0" to "0744bcc8506700fd",
            "tiny 97x71 SOLID feather 0.1" to "0744bcc8506700fd",
            "tiny 97x71 SOLID feather 1" to "92a99f7f211bd15f",
            "thin 300x220 GRADIENT_OVAL feather 0" to "d28a96672015d39c",
            "thin 300x220 GRADIENT_OVAL feather 0.1" to "d28a96672015d39c",
            "thin 300x220 GRADIENT_OVAL feather 1" to "f9197a82a83a59f9",
            "thin 300x220 SMOOTH_FILL feather 0" to "df2943a965459038",
            "thin 300x220 SMOOTH_FILL feather 0.1" to "df2943a965459038",
            "thin 300x220 SMOOTH_FILL feather 1" to "3d007d222418f307",
            "thin 300x220 SOLID feather 0" to "6ffc5bcf8b960442",
            "thin 300x220 SOLID feather 0.1" to "6ffc5bcf8b960442",
            "thin 300x220 SOLID feather 1" to "df8d1c36dd9955e2",
            "photo 1280x960 GRADIENT_OVAL feather 0" to "429c441d1a934650",
            "photo 1280x960 GRADIENT_OVAL feather 0.1" to "710e068254e5ec34",
            "photo 1280x960 GRADIENT_OVAL feather 1" to "72aca3b0b80f81e0",
            "photo 1280x960 SMOOTH_FILL feather 0" to "d50dd1bb6578c78b",
            "photo 1280x960 SMOOTH_FILL feather 0.1" to "bef7b1436849b665",
            "photo 1280x960 SMOOTH_FILL feather 1" to "a3f5f26dcb81d421",
            "photo 1280x960 SOLID feather 0" to "8636997be09bc38a",
            "photo 1280x960 SOLID feather 0.1" to "0eb96cf87aa77121",
            "photo 1280x960 SOLID feather 1" to "f7e93744fdc0ab30",
            "translucent 190x150 GRADIENT_OVAL feather 0" to "18bfd4ce2f9b755a",
            "translucent 190x150 GRADIENT_OVAL feather 0.1" to "06a5c2b33065522d",
            "translucent 190x150 GRADIENT_OVAL feather 1" to "09fb1d7d8c1927e5",
            "translucent 190x150 SMOOTH_FILL feather 0" to "622126755a2eeb68",
            "translucent 190x150 SMOOTH_FILL feather 0.1" to "fa15eef30c305792",
            "translucent 190x150 SMOOTH_FILL feather 1" to "bdabf575e2057dcf",
            "translucent 190x150 SOLID feather 0" to "71ee41d05536e574",
            "translucent 190x150 SOLID feather 0.1" to "f1d2fa68e1691632",
            "translucent 190x150 SOLID feather 1" to "6ec4322a5fe10562",
        )
    }
}
