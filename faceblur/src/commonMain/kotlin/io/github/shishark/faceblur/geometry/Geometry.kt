// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.geometry

import io.github.shishark.faceblur.FaceBox
import kotlin.math.max
import kotlin.math.min

/** A 2D point in image pixel coordinates. */
internal data class PointF(val x: Float, val y: Float)

/**
 * Axis-aligned rectangle in image pixel coordinates (origin top-left, y grows downwards).
 * `right`/`bottom` are exclusive edges, so width = right - left.
 */
internal data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val area: Float get() = if (width <= 0f || height <= 0f) 0f else width * height
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun intersect(other: RectF): RectF = RectF(
        max(left, other.left),
        max(top, other.top),
        min(right, other.right),
        min(bottom, other.bottom),
    )

    fun intersectionArea(other: RectF): Float = intersect(other).area

    fun iou(other: RectF): Float {
        val inter = intersectionArea(other)
        if (inter <= 0f) return 0f
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    fun clampTo(w: Float, h: Float) = RectF(
        left.coerceIn(0f, w),
        top.coerceIn(0f, h),
        right.coerceIn(0f, w),
        bottom.coerceIn(0f, h),
    )
}

/**
 * One detected face inside the detection pipeline.
 * @param rect bounding box in pixel coordinates of the image it was detected on.
 * @param score detector confidence 0..1.
 * @param landmarks optional key points (YuNet: eyes, nose, mouth corners); not part of the public result.
 */
internal data class DetectedFace(val rect: RectF, val score: Float = 1f, val landmarks: List<PointF> = emptyList()) {
    fun mapped(dx: Float, dy: Float, sx: Float, sy: Float = sx): DetectedFace = DetectedFace(
        rect = RectF(rect.left * sx + dx, rect.top * sy + dy, rect.right * sx + dx, rect.bottom * sy + dy),
        score = score,
        landmarks = landmarks.map { PointF(it.x * sx + dx, it.y * sy + dy) },
    )

    /** The public form of this face (box and score; landmarks are not exposed). */
    fun toFaceBox(): FaceBox = FaceBox.create(rect.left, rect.top, rect.right, rect.bottom, score)
}

/** The internal form of a public [FaceBox] (no landmarks). */
internal fun FaceBox.toDetectedFace(): DetectedFace = DetectedFace(RectF(left, top, right, bottom), score)
