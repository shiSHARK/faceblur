// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.geometry.DetectedFace
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

internal enum class CenterColorSource {
    /** Use [MaskSettings.color]. */
    FIXED,

    /** Average colour of the ring of pixels just outside the mask. */
    SURROUNDING_MEAN,
}

/**
 * Mask geometry and fill settings. Internal until the safe public ranges of protection-relevant settings are
 * approved; the defaults are the library defaults (whole-face ellipse with margin, outward feather, GRADIENT_OVAL
 * as decided by the project owner).
 *
 * Fail closed: construction throws [CoreFailure.InvalidSetting] for a non-finite or out-of-range value and
 * for any geometry whose opaque mask would not contain the default mask of the same face box (no shrinking).
 */
internal data class MaskSettings(
    val style: MaskStyle = MaskStyle.GRADIENT_OVAL,
    val shape: MaskShape = MaskShape.ELLIPSE,
    /** Mask width = face box width × expandX (covers ears / cheeks). */
    val expandX: Float = DEFAULT_EXPAND_X,
    /** Mask height = face box height × expandY (covers hair / chin). */
    val expandY: Float = DEFAULT_EXPAND_Y,
    /** Move the mask centre up by this fraction of the face box height (hair is above the box). */
    val shiftUp: Float = DEFAULT_SHIFT_UP,
    /** Soft edge OUTSIDE the opaque mask, as a fraction of the mask radius (never lets face pixels show). */
    val feather: Float = DEFAULT_FEATHER,
    /** ARGB colour for SOLID, and for GRADIENT_OVAL when [centerColorSource] = FIXED (alpha is ignored). */
    val color: Int = DEFAULT_COLOR,
    val centerColorSource: CenterColorSource = CenterColorSource.FIXED,
    /** GRADIENT_OVAL: fraction of the radius (0..1) that is pure centre colour before the gradient starts. */
    val innerSolidFraction: Float = 0.45f,
    /** GRADIENT_OVAL / SMOOTH_FILL: width of the sampling ring outside the mask, as a fraction of the radius. */
    val ringWidth: Float = 0.15f,
) {
    init {
        requireIn("expandX", expandX, DEFAULT_EXPAND_X, MAX_EXPAND)
        requireIn("expandY", expandY, DEFAULT_EXPAND_Y, MAX_EXPAND)
        requireIn("shiftUp", shiftUp, -MAX_EXPAND, MAX_EXPAND)
        requireIn("feather", feather, 0f, 1f)
        requireIn("innerSolidFraction", innerSolidFraction, 0f, 1f)
        requireIn("ringWidth", ringWidth, 0f, 1f)
        if (!containsDefaultMask()) {
            throw CoreFailure.InvalidSetting(
                "shiftUp",
                "only with expandX/expandY large enough that the mask contains the default mask of the same box",
            )
        }
    }

    /**
     * True when, for every face box, the opaque mask of these settings contains the opaque mask of the default
     * settings. Exact test in box-normalised coordinates (x / box width, y / box height; independent of the box):
     * the default ellipse is centred at y = -[DEFAULT_SHIFT_UP] with half-axes [DEFAULT_EXPAND_X] / 2 and
     * [DEFAULT_EXPAND_Y] / 2.
     */
    private fun containsDefaultMask(): Boolean {
        val a0 = DEFAULT_EXPAND_X / 2.0
        val b0 = DEFAULT_EXPAND_Y / 2.0
        val a = expandX / 2.0
        val b = expandY / 2.0
        // Offset of the default centre in this mask's frame (y grows downwards, shiftUp moves the mask up).
        val d = shiftUp.toDouble() - DEFAULT_SHIFT_UP.toDouble()
        if (shape == MaskShape.RECTANGLE) return a >= a0 && d - b0 >= -b - EPS && d + b0 <= b + EPS
        // A default boundary point (a0 cos t, d + b0 sin t) must satisfy (x / a)² + (y / b)² <= 1 for every t.
        // With s = sin t this is the quadratic f(s) = p s² + q s + c on [-1, 1].
        val k = (a0 / a) * (a0 / a)
        val p = (b0 * b0) / (b * b) - k
        val q = 2.0 * d * b0 / (b * b)
        val c = k + (d * d) / (b * b)
        var maxF = maxOf(p - q + c, p + q + c)
        if (p < 0.0) {
            val vertex = -q / (2.0 * p)
            if (vertex > -1.0 && vertex < 1.0) maxF = maxOf(maxF, c - q * q / (4.0 * p))
        }
        return maxF <= 1.0 + EPS
    }

    companion object {
        /** Largest expansion factor accepted (keeps mask extents finite and bounded). */
        const val MAX_EXPAND: Float = 4f
        private const val EPS = 1e-9

        private fun requireIn(parameter: String, value: Float, min: Float, max: Float) {
            if (!(value >= min && value <= max)) throw CoreFailure.InvalidSetting(parameter, "$min..$max")
        }

        const val DEFAULT_EXPAND_X: Float = 1.6f
        const val DEFAULT_EXPAND_Y: Float = 1.8f
        const val DEFAULT_SHIFT_UP: Float = 0.08f
        const val DEFAULT_FEATHER: Float = 0.10f
        const val DEFAULT_COLOR: Int = 0xFF7F7F7F.toInt()
    }
}

/** Final mask placed on the image for one face (ellipse or rectangle, full-image pixel coordinates). */
internal data class MaskGeometry(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val shape: MaskShape) {
    /**
     * Normalized radius of the point ([px], [py]): 1 on the mask boundary, < 1 inside. Ellipse: Euclidean in
     * coordinates scaled by 1/rx, 1/ry; rectangle: Chebyshev (max of the two). The pixel (x, y) is represented
     * by its centre (x + 0.5, y + 0.5).
     */
    fun normalizedRadius(px: Float, py: Float): Float {
        if (rx <= 0f || ry <= 0f) return Float.POSITIVE_INFINITY
        val dx = (px - cx) / rx
        val dy = (py - cy) / ry
        return if (shape == MaskShape.ELLIPSE) sqrt(dx * dx + dy * dy) else max(abs(dx), abs(dy))
    }

    /** True when the centre of pixel ([x], [y]) is inside the opaque mask (exactly the pixels that are fully replaced). */
    fun containsPixel(x: Int, y: Int): Boolean = normalizedRadius(x + 0.5f, y + 0.5f) <= 1f
}

/**
 * Pure-Kotlin face masking shared by every platform (identical output everywhere), through the bounded mask stage
 * ([MaskEngine], ADR 0013): every check, allocation and fill computation happens before the first pixel is written,
 * and the memory is reserved in full up front.
 *
 * Rules shared by all styles:
 * * Inside the mask (normalized radius r ≤ 1, see [MaskGeometry.normalizedRadius]) the colour is 100 % the fill.
 *   The soft edge is OUTWARD only: for 1 < r < 1 + feather the fill is blended into the photo with a smoothstep
 *   (at least a 1 px anti-aliasing band even when feather = 0). Inside the mask every pixel is fully opaque
 *   (alpha 255); in the soft edge the alpha moves towards 255 with the same weight as the colour, so the
 *   opacity never increases with r; beyond the soft edge nothing is changed.
 * * SOLID, GRADIENT_OVAL and SMOOTH_FILL never use the colour of a pixel that lies inside ANY face's mask (not only
 *   their own), so neither the face itself nor a neighbouring face leaks into the fill.
 * * Every fill is computed from the ORIGINAL pixels (nothing is written before the final copy), and the fills are
 *   composited in list order, so overlapping faces do not feed each other's fills.
 */
internal object MaskPainter {
    /**
     * Where the mask for [face] goes (also used by UI overlays and by the protection metric).
     * Throws [CoreFailure.InvalidFaceBox] for a non-finite or inverted box.
     */
    fun maskFor(face: DetectedFace, config: MaskSettings): MaskGeometry {
        val r = face.rect
        if (!(r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite())) {
            throw CoreFailure.InvalidFaceBox("non-finite coordinate")
        }
        if (r.right < r.left || r.bottom < r.top) throw CoreFailure.InvalidFaceBox("inverted edges")
        val w = max(0f, r.width)
        val h = max(0f, r.height)
        return MaskGeometry(
            cx = r.centerX,
            cy = r.centerY - config.shiftUp * h,
            rx = w * config.expandX / 2f,
            ry = h * config.expandY / 2f,
            shape = config.shape,
        )
    }

    /**
     * Apply [config] to every face in place. Fail closed: invalid boxes, masks or images throw a
     * [CoreFailure] before any pixel is read or written; a write that does not read back throws
     * [CoreFailure.WriteNotApplied] (the image must then be discarded).
     */
    fun apply(image: MutablePixelImage, faces: List<DetectedFace>, config: MaskSettings) {
        applyMasks(image, faces.map { maskFor(it, config) }, config)
    }

    /**
     * Apply [config] to explicit masks (e.g. masks drawn or edited by the user). [MaskSettings.shape] is ignored.
     * [checkCancelled] is called during the computation (it throws to stop, before anything is written). Synthetic on
     * the JVM and Android ([HiddenFromJava]), as it takes the [StageSeams].
     */
    @HiddenFromJava
    fun applyMasks(
        image: MutablePixelImage,
        masks: List<MaskGeometry>,
        config: MaskSettings,
        checkCancelled: () -> Unit = {},
        seams: StageSeams = StageSeams(),
    ) {
        MaskEngine.apply(image, masks, config, checkCancelled, seams)
    }
}
