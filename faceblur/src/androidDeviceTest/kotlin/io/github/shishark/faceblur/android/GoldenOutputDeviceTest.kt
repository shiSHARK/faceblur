// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.applyMasks
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Golden output of the in-memory `Bitmap` route on the phone: a synthetic picture masked with a fixed plan in every
 * style must give exactly the pixels recorded before the mask stage was reworked for speed. The picture, the plan and
 * the digest are those of the common tests (`Golden` in commonTest: `DetectionInputGoldenTest` checks the same values
 * on the common route on every platform, `GoldenOutputAndroidTest` on the host's `Bitmap`), repeated here because the
 * device tests do not see the common test sources. No fixture photo is read.
 */
@RunWith(AndroidJUnit4::class)
class GoldenOutputDeviceTest {
    private fun model(): DetectorModel = DetectorModel.load(
        ModelSource.fromFile(DeviceFixtures.file(DeviceFixtures.YUNET_FILE).path),
        DeviceFixtures.yunetProfile(),
    )

    @Test
    fun theMaskedBitmapOfEveryStyleIsUnchangedOnThePhone() {
        DeviceFixtures.logHeader()
        val model = model()
        val actual = LinkedHashMap<String, String>()
        for (style in MaskStyle.entries) {
            FaceMasker(model, FaceMaskerConfig(MaskingConfig(style = style))).use { masker ->
                val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(photoLike(W, H, SEED), 0, W, 0, 0, W, H)
                masker.applyMasks(bitmap, plan())
                val px = IntArray(W * H).also { bitmap.getPixels(it, 0, W, 0, 0, W, H) }
                actual["applyMasks synthetic $style"] = fnv(px)
            }
        }
        assertEquals(EXPECTED, actual, actual.toString())
    }

    private companion object {
        const val W = 1000
        const val H = 750
        const val SEED = 21
        const val FNV_OFFSET: Long = -0x340d631b7bdddcdbL
        const val FNV_PRIME: Long = 0x100000001b3L

        val EXPECTED: Map<String, String> = mapOf(
            "applyMasks synthetic GRADIENT_OVAL" to "962233205ec3d9ff",
            "applyMasks synthetic SMOOTH_FILL" to "8e670f9ad7cc518c",
            "applyMasks synthetic SOLID" to "85089264dfa22c5d",
        )

        fun plan(): MaskPlan = MaskPlan(
            W,
            H,
            listOf(
                Mask.exact(MaskShape.ELLIPSE, 420f, 330f, 260f, 300f),
                Mask.exact(MaskShape.RECTANGLE, 820f, 160f, 120f, 90f),
                Mask.exact(MaskShape.ELLIPSE, 980f, 730f, 70f, 80f),
            ),
        )

        /** As `Golden.fnv` of the common tests: 64-bit FNV-1a, one step per int. */
        fun fnv(values: IntArray): String {
            var h = FNV_OFFSET
            for (v in values) h = (h xor (v.toLong() and 0xFFFFFFFFL)) * FNV_PRIME
            return h.toULong().toString(16).padStart(16, '0')
        }

        /** As `Golden.photoLike` of the common tests. */
        fun photoLike(w: Int, h: Int, seed: Int): IntArray {
            var s = seed or 1
            return IntArray(w * h) { i ->
                s = s xor (s shl 13)
                s = s xor (s ushr 17)
                s = s xor (s shl 5)
                val x = i % w
                val y = i / w
                val n = (s and 0x1F) - 16
                val r = (x * 255 / maxOf(1, w - 1) + n).coerceIn(0, 255)
                val g = (y * 255 / maxOf(1, h - 1) - n).coerceIn(0, 255)
                val b = (if ((x / 23 + y / 17) % 2 == 0) 60 else 190) + n / 2
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b.coerceIn(0, 255)
            }
        }
    }
}
