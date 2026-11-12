// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.DetectionImage
import io.github.shishark.faceblur.detect.FaceDetectorEngine
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * DetectionImage that remembers which full-image rectangle it shows ([srcX], [srcY], [srcW], [srcH]) so that a
 * [FakeEngine] can report the "true" faces visible in it. Pixels are not needed by the fake engine.
 */
internal class FakeDetectionImage(
    override val width: Int,
    override val height: Int,
    val srcX: Float = 0f,
    val srcY: Float = 0f,
    val srcW: Float = width.toFloat(),
    val srcH: Float = height.toFloat(),
    private val released: MutableList<FakeDetectionImage>? = null,
    private val created: MutableList<FakeDetectionImage>? = null,
) : DetectionImage {
    var isReleased = false
        private set

    override fun cropScaled(x: Int, y: Int, w: Int, h: Int, targetMaxSide: Int): DetectionImage {
        val s = if (max(w, h) <= targetMaxSide) 1f else targetMaxSide.toFloat() / max(w, h)
        val ow = max(1, (w * s).roundToInt())
        val oh = max(1, (h * s).roundToInt())
        val sx = srcW / width
        val sy = srcH / height
        return FakeDetectionImage(ow, oh, srcX + x * sx, srcY + y * sy, w * sx, h * sy, released, created)
            .also { created?.add(it) }
    }

    override fun readArgb(): IntArray = IntArray(width * height)

    override fun release() {
        if (!isReleased) released?.add(this)
        isReleased = true
    }
}

/**
 * Engine that "sees" [truth] faces (full-image coordinates). A face is reported when its visible part in the crop
 * is ≥ [minVisible] of its area and its size in the crop is ≥ [minSidePx]; partially visible faces are reported
 * clipped to the crop (like a real detector finding half a face at a tile border), with [partialScore].
 */
internal class FakeEngine(
    private val truth: List<RectF>,
    override val inputWidth: Int = 640,
    override val inputHeight: Int = inputWidth,
    private val minSidePx: Float = 10f,
    private val minVisible: Float = 0.4f,
    private val partialScore: Float = 0.95f,
) : FaceDetectorEngine {
    var calls = 0
    var closed = 0

    override fun detect(image: DetectionImage, minScore: Float, checkCancelled: () -> Unit): List<DetectedFace> {
        calls++
        val img = image as FakeDetectionImage
        val crop = RectF(img.srcX, img.srcY, img.srcX + img.srcW, img.srcY + img.srcH)
        val sx = img.width / img.srcW
        val sy = img.height / img.srcH
        val out = ArrayList<DetectedFace>()
        for (t in truth) {
            val vis = t.intersect(crop)
            if (vis.isEmpty || vis.area < minVisible * t.area) continue
            if (min(vis.width * sx, vis.height * sy) < minSidePx) continue
            val score = if (vis.area >= t.area * 0.999f) 0.9f else partialScore
            if (score < minScore) continue
            out +=
                DetectedFace(
                    RectF(
                        (vis.left - img.srcX) * sx,
                        (vis.top - img.srcY) * sy,
                        (vis.right - img.srcX) * sx,
                        (vis.bottom - img.srcY) * sy,
                    ),
                    score,
                )
        }
        return out
    }

    override fun close() {
        closed++
    }
}

/** Deterministic pseudo-random ARGB image (opaque). */
internal fun randomPixels(w: Int, h: Int, seed: Int): IntArray {
    var s = seed or 1
    return IntArray(w * h) {
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        (0xFF shl 24) or (s and 0xFFFFFF)
    }
}

/** Smooth-ish opaque test image (gradients + a few stripes) – closer to a photo than noise. */
internal fun gradientPixels(w: Int, h: Int): IntArray = IntArray(w * h) { i ->
    val x = i % w
    val y = i / w
    val r = (x * 255 / max(1, w - 1))
    val g = (y * 255 / max(1, h - 1))
    val b = if ((x / 7 + y / 11) % 2 == 0) 40 else 200
    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
