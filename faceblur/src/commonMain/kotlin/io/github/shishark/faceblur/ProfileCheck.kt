// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

/**
 * A face that the developer knows is in a test image, for [FaceMasker.checkProfile]: its box in pixels of that image
 * (origin top-left, y grows downwards; [right] and [bottom] are exclusive edges, like [FaceBox]).
 *
 * @throws FaceMaskException.InvalidConfiguration (`expectedFaces`) when a value is not finite, [right] is not greater
 * than [left] or [bottom] is not greater than [top].
 */
public class ExpectedFace
@Throws(FaceMaskException::class)
constructor(
    /** Left edge in pixels (inclusive). */
    public val left: Float,
    /** Top edge in pixels (inclusive). */
    public val top: Float,
    /** Right edge in pixels (exclusive), greater than [left]. */
    public val right: Float,
    /** Bottom edge in pixels (exclusive), greater than [top]. */
    public val bottom: Float,
) {
    init {
        if (!(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())) {
            throw FaceMaskException.InvalidConfiguration("expectedFaces", "finite edges")
        }
        if (!(right > left && bottom > top)) {
            throw FaceMaskException.InvalidConfiguration("expectedFaces", "left < right and top < bottom")
        }
    }

    /** True when [other] is an [ExpectedFace] with the same edges (floats compared bit by bit). */
    override fun equals(other: Any?): Boolean = other is ExpectedFace &&
        left.toBits() == other.left.toBits() &&
        top.toBits() == other.top.toBits() &&
        right.toBits() == other.right.toBits() &&
        bottom.toBits() == other.bottom.toBits()

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int {
        var h = left.toBits()
        h = 31 * h + top.toBits()
        h = 31 * h + right.toBits()
        h = 31 * h + bottom.toBits()
        return h
    }

    /** Debug text with the four edges. */
    override fun toString(): String = "ExpectedFace(left=$left, top=$top, right=$right, bottom=$bottom)"
}

/**
 * The result of [FaceMasker.checkProfile]. It only reports; it is never logged and never stops anything.
 *
 * `allFound = false` means the model and profile did not find a face you know is there: check the profile (colour
 * order, pixel scaling, box format, coordinate space, score threshold) before you rely on this setup. `allFound = true`
 * on one image says nothing about other photos; measure on your own photos with faceblur-eval.
 */
public class ProfileCheckResult internal constructor(
    detections: List<FaceBox>,
    bestIoU: List<Float>,
    /** True when every expected face has a detection with IoU of at least the call's `minIoU`. */
    public val allFound: Boolean,
) {
    /** Every face the call detected (final merged boxes, as [Detection.faces]); read-only. */
    public val detections: List<FaceBox> = detections.readOnly()

    /**
     * For each expected face, in the order passed, the best intersection over union (0 to 1) with any detection;
     * 0 when no detection overlaps it. Read-only.
     */
    public val bestIoU: List<Float> = bestIoU.readOnly()

    /** Debug text with the counts and the overlaps (no image content). */
    override fun toString(): String =
        "ProfileCheckResult(detections=${detections.size}, bestIoU=$bestIoU, allFound=$allFound)"

    internal companion object {
        /** Builds the result for [expected] from [detections] at [minIoU] (validated by the caller). */
        fun of(detections: List<FaceBox>, expected: List<ExpectedFace>, minIoU: Float): ProfileCheckResult {
            val best = expected.map { e -> detections.maxOfOrNull { iou(e, it) } ?: 0f }
            return ProfileCheckResult(detections, best, best.all { it >= minIoU })
        }

        /** Intersection over union of the two boxes (exclusive right and bottom edges); 0 without overlap. */
        fun iou(e: ExpectedFace, d: FaceBox): Float {
            val iw = minOf(e.right, d.right) - maxOf(e.left, d.left)
            val ih = minOf(e.bottom, d.bottom) - maxOf(e.top, d.top)
            if (iw <= 0f || ih <= 0f) return 0f
            val inter = iw * ih
            val union = (e.right - e.left) * (e.bottom - e.top) + d.width * d.height - inter
            return if (union > 0f) minOf(1f, inter / union) else 0f
        }
    }
}
