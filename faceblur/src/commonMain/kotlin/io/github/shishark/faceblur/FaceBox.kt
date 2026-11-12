// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

/**
 * One detected face: its bounding box in pixel coordinates of the image that was analysed (origin top-left,
 * y grows downwards; [right] and [bottom] are exclusive edges) and the detector's confidence [score] (0..1).
 *
 * Boxes are the final, merged detections of all detection passes, clipped to the image. A mask placed for a box
 * is larger than the box (it also covers hair, ears and chin).
 *
 * Instances are created by the library only (no constructor is callable from Kotlin, Java or Swift). Equality
 * compares all five values.
 */
public class FaceBox private constructor(
    /** Left edge in pixels (inclusive). */
    public val left: Float,
    /** Top edge in pixels (inclusive). */
    public val top: Float,
    /** Right edge in pixels (exclusive). */
    public val right: Float,
    /** Bottom edge in pixels (exclusive). */
    public val bottom: Float,
    /** Detector confidence from 0 to 1. */
    public val score: Float,
) {
    /** Width of the box in pixels (`right - left`). */
    public val width: Float get() = right - left

    /** Height of the box in pixels (`bottom - top`). */
    public val height: Float get() = bottom - top

    /** True when [other] is a [FaceBox] with the same edges and score (floats compared bit by bit, like data classes). */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FaceBox) return false
        return left.toBits() == other.left.toBits() &&
            top.toBits() == other.top.toBits() &&
            right.toBits() == other.right.toBits() &&
            bottom.toBits() == other.bottom.toBits() &&
            score.toBits() == other.score.toBits()
    }

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int {
        var h = left.toBits()
        h = 31 * h + top.toBits()
        h = 31 * h + right.toBits()
        h = 31 * h + bottom.toBits()
        h = 31 * h + score.toBits()
        return h
    }

    /** Debug text with the four edges and the score. */
    override fun toString(): String = "FaceBox(left=$left, top=$top, right=$right, bottom=$bottom, score=$score)"

    internal companion object {
        /**
         * The library's only way to create a box. Fail closed: non-finite values or inverted edges throw
         * [CoreFailure.InvalidFaceBox]. Synthetic on the JVM ([HiddenFromJava]), so Java code cannot call it.
         */
        @HiddenFromJava
        internal fun create(left: Float, top: Float, right: Float, bottom: Float, score: Float): FaceBox {
            if (!(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && score.isFinite())) {
                throw CoreFailure.InvalidFaceBox("non-finite value")
            }
            if (right < left || bottom < top) throw CoreFailure.InvalidFaceBox("inverted edges")
            return FaceBox(left, top, right, bottom, score)
        }
    }
}
