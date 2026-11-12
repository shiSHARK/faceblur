// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import kotlin.jvm.JvmStatic

/**
 * A box converted from a photo's stored (raw, unrotated) frame into its upright frame: the frame of every file-mode
 * plan ([PlanMasker.planFile], `FaceMasker.detectFile`), the photo turned by its orientation tag as a photo viewer
 * shows it, top-left origin. Made by [fromRaw] only; a pure calculation (no file, no model, no state).
 *
 * The values are in the units of the raw size: [imageWidth] × [imageHeight] is the upright frame of that size. Scale
 * the box to the plan with `plan.width / imageWidth` and `plan.height / imageHeight` before building masks with
 * [Mask.manual]. The edges are in order ([left] <= [right], [top] <= [bottom]). The box may lie partly outside the
 * frame; the [Mask] factories check coordinates.
 *
 * WHAT IT CANNOT CHECK. It does not read the file, so it cannot prove that the orientation value is the file's own.
 * A wrong value that keeps the frame's shape (180 degrees, a mirror) cannot be detected from sizes. Comparing the
 * aspect ratio of [imageWidth] × [imageHeight] with the plan's catches a missing or extra 90 or 270 degree turn only on
 * photos that are clearly not square: on square and nearly square photos (aspect ratios within the comparison's
 * tolerance) a turned frame keeps its shape, so only the right orientation value helps. A box in the wrong frame masks
 * the wrong place while `maskFile` reports success, and the face stays visible.
 *
 * Equality compares the six values (floats bit by bit).
 */
public class UprightBox private constructor(
    /** Left edge in the upright frame, in the units of the raw size. */
    public val left: Float,
    /** Top edge in the upright frame. */
    public val top: Float,
    /** Right edge in the upright frame (>= [left]). */
    public val right: Float,
    /** Bottom edge in the upright frame (>= [top]). */
    public val bottom: Float,
    /** Width of the upright frame: the raw width for orientations 1 to 4, the raw height for 5 to 8. */
    public val imageWidth: Int,
    /** Height of the upright frame: the raw height for orientations 1 to 4, the raw width for 5 to 8. */
    public val imageHeight: Int,
) {
    /** True when [other] is an [UprightBox] with the same six values (floats compared bit by bit). */
    override fun equals(other: Any?): Boolean = other is UprightBox &&
        left.toBits() == other.left.toBits() &&
        top.toBits() == other.top.toBits() &&
        right.toBits() == other.right.toBits() &&
        bottom.toBits() == other.bottom.toBits() &&
        imageWidth == other.imageWidth &&
        imageHeight == other.imageHeight

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int =
        listOf(left.toBits(), top.toBits(), right.toBits(), bottom.toBits(), imageWidth, imageHeight).hashCode()

    /** Debug text with the six values. */
    override fun toString(): String = "UprightBox(left=$left, top=$top, right=$right, bottom=$bottom, " +
        "imageWidth=$imageWidth, imageHeight=$imageHeight)"

    /** The factory. */
    public companion object {
        /**
         * Converts the box ([left], [top], [right], [bottom]) of a raw image of [rawWidth] × [rawHeight] pixels (the
         * stored frame, before the orientation tag is applied) into the upright frame for the [orientation] value 1
         * to 8 (the EXIF and ImageIO numbering: 1 upright, 2 mirrored horizontally, 3 turned 180 degrees, 4 mirrored
         * vertically, 5 transposed, 6 turned 90 degrees clockwise, 7 transversed, 8 turned 270 degrees clockwise).
         * The box's two corners are transformed and the edges put in order again, so a box given with its corners in
         * either order is accepted. A file without the tag has orientation 1; pass that (the helper does not read the
         * file; some readers return 0, "undefined", for a missing tag: map it to 1 yourself).
         *
         * @throws FaceMaskException.InvalidConfiguration (`orientation`) for a value outside 1 to 8, also 0;
         * (`rawWidth`) or (`rawHeight`) for a size of 0 or less; (`box`) for a NaN or infinite edge. Nothing is
         * guessed.
         */
        @JvmStatic
        @Throws(FaceMaskException::class)
        public fun fromRaw(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            rawWidth: Int,
            rawHeight: Int,
            orientation: Int,
        ): UprightBox {
            if (orientation !in 1..8) throw FaceMaskException.InvalidConfiguration("orientation", "1 to 8")
            if (rawWidth <= 0) throw FaceMaskException.InvalidConfiguration("rawWidth", "greater than 0")
            if (rawHeight <= 0) throw FaceMaskException.InvalidConfiguration("rawHeight", "greater than 0")
            if (!(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())) {
                throw FaceMaskException.InvalidConfiguration("box", "finite edges")
            }
            val w = rawWidth.toFloat()
            val h = rawHeight.toFloat()
            // Each corner (x, y) of the raw frame maps to (x', y') of the upright frame (EXIF Orientation tag):
            // 1 (x, y); 2 (w - x, y); 3 (w - x, h - y); 4 (x, h - y);
            // 5 (y, x); 6 (h - y, x); 7 (h - y, w - x); 8 (y, w - x).
            val (x1, y1) = corner(left, top, w, h, orientation)
            val (x2, y2) = corner(right, bottom, w, h, orientation)
            val turned = orientation >= 5
            return UprightBox(
                minOf(x1, x2),
                minOf(y1, y2),
                maxOf(x1, x2),
                maxOf(y1, y2),
                if (turned) rawHeight else rawWidth,
                if (turned) rawWidth else rawHeight,
            )
        }

        private fun corner(x: Float, y: Float, w: Float, h: Float, orientation: Int): Pair<Float, Float> =
            when (orientation) {
                1 -> x to y
                2 -> (w - x) to y
                3 -> (w - x) to (h - y)
                4 -> x to (h - y)
                5 -> y to x
                6 -> (h - y) to x
                7 -> (h - y) to (w - x)
                else -> y to (w - x)
            }
    }
}
