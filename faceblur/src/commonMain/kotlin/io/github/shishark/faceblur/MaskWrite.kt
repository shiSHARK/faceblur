// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.mask.MaskPainter
import io.github.shishark.faceblur.mask.MaskSettings
import io.github.shishark.faceblur.mask.StageSeams
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Writing a [MaskPlan] into an image through the bounded mask stage (ADR 0013): the one implementation behind
 * `FaceMasker.applyMasks`, `maskInPlace` and `maskFile` and behind `PlanMasker.maskFile`, so the same plan and the same
 * mask settings give the same pixels on every route.
 */
internal object MaskWrite {
    /**
     * Checks the image size and the plan size, then runs the stage and returns its time in milliseconds. Cancellation
     * ([checkCancelled]) is checked before the stage, inside it, and after the final copy (a cancellation requested
     * during the copy is reported after it: the image is fully masked). [onWorkLimit] names the error for a plan
     * whose summed working area is above the limit. Synthetic on the JVM and Android, as it takes the [StageSeams].
     */
    @HiddenFromJava
    fun write(
        image: MutablePixelImage,
        plan: MaskPlan,
        settings: MaskSettings,
        checkCancelled: () -> Unit,
        seams: StageSeams,
        onWorkLimit: (CoreFailure.MaskWorkLimit) -> FaceMaskException,
    ): Double {
        checkSize(image.width, image.height)
        val view = plan.view
        if (view != null && view != PlanView.viewOf(image)) throw FaceMaskException.MaskPlanMismatch.otherView()
        if (plan.width != image.width || plan.height != image.height) {
            throw FaceMaskException.MaskPlanMismatch(plan.width, plan.height, image.width, image.height)
        }
        val geometries = plan.masks.map { it.toGeometry() }
        checkCancelled()
        val mark = TimeSource.Monotonic.markNow()
        try {
            MaskPainter.applyMasks(image, geometries, settings, checkCancelled, seams)
        } catch (e: CoreFailure.MaskWorkLimit) {
            throw onWorkLimit(e)
        }
        val maskMs = mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
        // The copy is never interrupted; a cancellation requested meanwhile is reported now (image fully masked).
        checkCancelled()
        return maskMs
    }

    /** The work-limit error for a plan the app gave (not one from the model): `InvalidConfiguration("masks")`. */
    fun planWorkLimit(plan: MaskPlan, e: CoreFailure.MaskWorkLimit): FaceMaskException =
        FaceMaskException.InvalidConfiguration("masks", MaskLimitText.planArea(plan.masks.size, e.area, e.limit))

    /** Every image side at most 65,535 px and at most `Int.MAX_VALUE` pixels; `ImageTooLarge` otherwise. */
    fun checkSize(width: Int, height: Int) {
        if (width < 0 || height < 0 || width > FaceMasker.MAX_SIDE || height > FaceMasker.MAX_SIDE) {
            throw FaceMaskException.ImageTooLarge(width, height, Int.MAX_VALUE.toLong(), FaceMasker.SIDE_LIMIT)
        }
        if (width.toLong() * height > Int.MAX_VALUE) {
            throw FaceMaskException.ImageTooLarge(width, height, Int.MAX_VALUE.toLong())
        }
    }
}

/**
 * An image that shows stored pixels through a view (on iOS: a caller's buffer wrapped with an orientation). The
 * in-memory `detect` records the view it read in its [Detection] and plans ([MaskPlan.view]); [MaskWrite] refuses such
 * a plan through another view (ADR 0011), because its masks would land on other stored pixels than the
 * faces it was made for.
 */
internal interface PlanView {
    /** The view's identity: the orientation 1 to 8 (EXIF numbering); 1 is the upright view of every other image. */
    val planView: Int

    companion object {
        /** The upright view. */
        const val UPRIGHT: Int = 1

        /** The view of [image]: its [planView], or [UPRIGHT] for an image that is not a [PlanView]. */
        fun viewOf(image: PixelImage): Int = (image as? PlanView)?.planView ?: UPRIGHT
    }
}
