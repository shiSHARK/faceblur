// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The view binding of in-memory plans (ADR 0011), on every platform: a plan from `detect` records the view of the image
 * it read ([PlanView]; upright for an image that is not one) and `applyMasks` refuses it through another view before
 * anything is written. The iOS buffer wrapper is the production [PlanView]; here a stand-in with a chosen view.
 */
class PlanViewBindingTest {
    private class Viewed(val pixels: IntArrayPixels, override val planView: Int) :
        MutablePixelImage by pixels,
        PlanView

    private fun masker() = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))

    private fun pixels() = IntArrayPixels(640, 480, gradientPixels(640, 480))

    private fun refused(m: FaceMasker, image: MutablePixelImage, data: IntArray, plan: MaskPlan) {
        val before = data.copyOf()
        val e = assertFailsWith<FaceMaskException.MaskPlanMismatch> { m.applyMasks(image, plan) }
        assertEquals(FaceMaskException.MaskPlanMismatch.OTHER_VIEW, e.message)
        assertContentEquals(before, data, "nothing written")
    }

    @Test
    fun aPlanFromDetectIsAppliedOnlyThroughTheSameView() = masker().use { m ->
        val source = Viewed(pixels(), 3)
        val plan = m.detect(source).toMaskPlan()
        val other = Viewed(pixels(), 2)
        refused(m, other, other.pixels.data, plan)
        refused(m, other, other.pixels.data, plan.withMasks(plan.masks))
        val plain = pixels()
        refused(m, plain, plain.data, plan)
        val same = Viewed(pixels(), 3)
        assertEquals(plan.masks, m.applyMasks(same, plan).masks)
        assertFalse(same.pixels.data.contentEquals(gradientPixels(640, 480)), "masked")
    }

    @Test
    fun anImageThatIsNotAViewIsTheUprightView() = masker().use { m ->
        val plan = m.detect(pixels()).toMaskPlan()
        val upright = Viewed(pixels(), PlanView.UPRIGHT)
        assertEquals(plan.masks, m.applyMasks(upright, plan).masks)
        val turned = Viewed(pixels(), 6)
        refused(m, turned, turned.pixels.data, plan)
        val plain = pixels()
        assertEquals(plan.masks, m.applyMasks(plain, plan).masks)
    }

    @Test
    fun aPlanFromThePublicConstructorHasNoViewBinding() = masker().use { m ->
        val masks = m.detect(Viewed(pixels(), 5)).toMaskPlan().masks
        for (v in 1..8) {
            val target = Viewed(pixels(), v)
            assertEquals(masks, m.applyMasks(target, MaskPlan(640, 480, masks)).masks, "view $v")
            val edited = Viewed(pixels(), v)
            assertEquals(masks, m.applyMasks(edited, MaskPlan(640, 480, masks).withMasks(masks)).masks, "edited $v")
        }
    }
}
