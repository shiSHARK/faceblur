// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import kotlin.math.max
import kotlin.math.min

/** A rectangle in pixels of the working image (the photo upright, as the library reports it) or of a view. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Maps working-image pixels to the pixels of a view that shows the image centred and scaled to fit (letterboxed).
 * The overlay uses it to draw the boxes and masks exactly over the preview, for any EXIF orientation, because the
 * library's coordinates and the preview are both of the upright image.
 */
class ImageFit(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int) {
    val scale: Float
    val offsetX: Float
    val offsetY: Float

    init {
        require(imageWidth > 0 && imageHeight > 0) { "image size" }
        scale = if (viewWidth <= 0 || viewHeight <= 0) {
            0f
        } else {
            min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight)
        }
        offsetX = (viewWidth - imageWidth * scale) / 2f
        offsetY = (viewHeight - imageHeight * scale) / 2f
    }

    fun toView(box: Box): Box = Box(
        offsetX + box.left * scale,
        offsetY + box.top * scale,
        offsetX + box.right * scale,
        offsetY + box.bottom * scale,
    )
}

/** Non-gesture placement of a new mask: a box that is moved and resized with buttons, always inside the image. */
object Placement {
    /** Fraction of the image's long side that one Move press shifts the box by. */
    const val MOVE_STEP = 0.02f

    /** Factor of one Larger press (Smaller divides by it). */
    const val SIZE_FACTOR = 1.15f

    private const val START_SIZE = 0.2f
    private const val MIN_SIDE_FRACTION = 0.02f

    /** The starting box: a square in the middle of the image, 20 % of its short side. */
    fun initial(imageWidth: Int, imageHeight: Int): Box {
        val side = min(imageWidth, imageHeight) * START_SIZE
        val cx = imageWidth / 2f
        val cy = imageHeight / 2f
        return Box(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f)
    }

    fun move(box: Box, stepsX: Int, stepsY: Int, imageWidth: Int, imageHeight: Int): Box {
        val step = max(imageWidth, imageHeight) * MOVE_STEP
        val dx = (box.left + stepsX * step).coerceIn(0f, imageWidth - box.width) - box.left
        val dy = (box.top + stepsY * step).coerceIn(0f, imageHeight - box.height) - box.top
        return Box(box.left + dx, box.top + dy, box.right + dx, box.bottom + dy)
    }

    /** Scales the box around its centre; the result stays inside the image and keeps a minimum size. */
    fun resize(box: Box, factor: Float, imageWidth: Int, imageHeight: Int): Box {
        val minSide = max(imageWidth, imageHeight) * MIN_SIDE_FRACTION
        val w = (box.width * factor).coerceIn(minSide, imageWidth.toFloat())
        val h = (box.height * factor).coerceIn(minSide, imageHeight.toFloat())
        val left = (box.centerX - w / 2f).coerceIn(0f, imageWidth - w)
        val top = (box.centerY - h / 2f).coerceIn(0f, imageHeight - h)
        return Box(left, top, left + w, top + h)
    }
}
