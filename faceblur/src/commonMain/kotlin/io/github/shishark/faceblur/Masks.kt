// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.filemode.ImageFingerprint
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.mask.MaskGeometry
import io.github.shishark.faceblur.mask.MaskPainter
import io.github.shishark.faceblur.mask.MaskSettings
import io.github.shishark.faceblur.mask.MaskStageLimits
import io.github.shishark.faceblur.mask.PreparedMask
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Where a [Mask] came from. */
public enum class MaskOrigin {
    /** Built from a face the detector found. */
    DETECTED,

    /** Drawn or edited by the app or its user. */
    MANUAL,
}

/**
 * One opaque mask in pixel coordinates of the image it is written on (top-left origin): the final geometry, not the
 * face box. Every mask is fully opaque inside and gets an outward soft edge from the [MaskingConfig] it is applied
 * with. Create masks with [fromFace], [manual] or [exact]; values are validated on creation.
 */
public class Mask private constructor(
    /** Outline of the mask. */
    public val shape: MaskShape,
    /** Horizontal centre in pixels. */
    public val centerX: Float,
    /** Vertical centre in pixels. */
    public val centerY: Float,
    /** Half width in pixels (> 0). */
    public val radiusX: Float,
    /** Half height in pixels (> 0). */
    public val radiusY: Float,
    /** Detected or manual. */
    public val origin: MaskOrigin,
) {
    internal fun toGeometry(): MaskGeometry = MaskGeometry(centerX, centerY, radiusX, radiusY, shape)

    /** True when [other] is a [Mask] with the same geometry and origin. */
    override fun equals(other: Any?): Boolean = other is Mask &&
        shape == other.shape &&
        origin == other.origin &&
        centerX.toBits() == other.centerX.toBits() &&
        centerY.toBits() == other.centerY.toBits() &&
        radiusX.toBits() == other.radiusX.toBits() &&
        radiusY.toBits() == other.radiusY.toBits()

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int =
        listOf(shape, origin, centerX.toBits(), centerY.toBits(), radiusX.toBits(), radiusY.toBits()).hashCode()

    /** Debug text with the shape and origin only; coordinates are not printed (logs must not carry them). */
    override fun toString(): String = "Mask(shape=$shape, origin=$origin)"

    /** Factories. Every value is validated on creation; nothing invalid reaches the pixels. */
    public companion object {
        /** Largest accepted coordinate or radius in pixels (images are at most 65,535 px on a side). */
        private const val MAX_COORDINATE = PreparedMask.MAX_COORDINATE

        /**
         * The mask for a detected face: the box expanded by [MaskingConfig.expandX] and [MaskingConfig.expandY] and
         * moved up by 0.08 × the box height (the measured geometry), with the config's shape.
         *
         * @throws FaceMaskException.InvalidConfiguration (`box`) when the box has no width or height.
         */
        @JvmStatic
        @JvmOverloads
        @Throws(FaceMaskException::class)
        public fun fromFace(box: FaceBox, masking: MaskingConfig = MaskingConfig()): Mask = fromBox(
            DetectedFace(RectF(box.left, box.top, box.right, box.bottom), box.score),
            masking,
            MaskOrigin.DETECTED,
            "box",
        )

        /**
         * A mask for a box drawn by the app or its user, expanded exactly like a detected face so that it covers
         * the same area. The edges are pixel coordinates; they must be finite and at most 4,194,304 px from the
         * origin, with `right > left` and `bottom > top`.
         *
         * @throws FaceMaskException.InvalidConfiguration naming the first rejected edge.
         */
        @JvmStatic
        @JvmOverloads
        @Throws(FaceMaskException::class)
        public fun manual(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            masking: MaskingConfig = MaskingConfig(),
        ): Mask {
            requireCoordinate("left", left)
            requireCoordinate("top", top)
            requireCoordinate("right", right)
            requireCoordinate("bottom", bottom)
            if (!(right > left)) throw FaceMaskException.InvalidConfiguration("right", "greater than left")
            if (!(bottom > top)) throw FaceMaskException.InvalidConfiguration("bottom", "greater than top")
            return fromBox(DetectedFace(RectF(left, top, right, bottom)), masking, MaskOrigin.MANUAL, "right")
        }

        /**
         * A mask with exactly this geometry (no expansion): centre and radii in pixels, finite, radii greater than 0,
         * every value at most 4,194,304 px in size.
         *
         * @throws FaceMaskException.InvalidConfiguration naming the rejected value.
         */
        @JvmStatic
        @Throws(FaceMaskException::class)
        public fun exact(shape: MaskShape, centerX: Float, centerY: Float, radiusX: Float, radiusY: Float): Mask {
            requireCoordinate("centerX", centerX)
            requireCoordinate("centerY", centerY)
            requireRadius("radiusX", radiusX)
            requireRadius("radiusY", radiusY)
            return Mask(shape, centerX, centerY, radiusX, radiusY, MaskOrigin.MANUAL)
        }

        private fun requireCoordinate(name: String, v: Float) {
            if (!(v.isFinite() && v >= -MAX_COORDINATE && v <= MAX_COORDINATE)) {
                throw FaceMaskException.InvalidConfiguration(name, "finite, -4194304..4194304")
            }
        }

        private fun requireRadius(name: String, v: Float) {
            if (!(v.isFinite() && v > 0f && v <= MAX_COORDINATE)) {
                throw FaceMaskException.InvalidConfiguration(name, "greater than 0, at most 4194304")
            }
        }

        /**
         * The rectangle cover of a face whose box grew in more than 32 merge steps (ADR 0013 decision 1): each edge is
         * computed in double precision from the box with operations that are monotone in the box edges, then snapped
         * outwards to whole pixels, so that it holds the face's own expanded mask, every earlier (smaller) state of
         * the box with the part-test margin, and every mask whose radius the stage raised to 1 px; and the cover of a
         * box lies inside the cover of any box that contains it.
         */
        @HiddenFromJava
        internal fun cover(box: FaceBox, masking: MaskingConfig): Mask {
            val e = coverEdges(box.left, box.top, box.right, box.bottom, masking.expandX, masking.expandY)
            val cx = ((e[0] + e[2]) / 2.0).toFloat()
            val cy = ((e[1] + e[3]) / 2.0).toFloat()
            val rx = ((e[2] - e[0]) / 2.0).toFloat()
            val ry = ((e[3] - e[1]) / 2.0).toFloat()
            val ok =
                rx > 0f && ry > 0f && rx <= MAX_COORDINATE && ry <= MAX_COORDINATE &&
                    cx in -MAX_COORDINATE..MAX_COORDINATE && cy in -MAX_COORDINATE..MAX_COORDINATE
            if (!ok) throw FaceMaskException.InvalidConfiguration("box", "a box whose mask is finite and in range")
            return Mask(MaskShape.RECTANGLE, cx, cy, rx, ry, MaskOrigin.DETECTED)
        }

        /**
         * The cover's integer edges (left, top, right, bottom) for the box edges [l], [t], [r], [b] and the
         * expansions [ex], [ey] (ADR 0013 decision 1). Each value is built in the order written there from the
         * float box values, in double precision.
         */
        @HiddenFromJava
        internal fun coverEdges(l: Float, t: Float, r: Float, b: Float, ex: Float, ey: Float): DoubleArray {
            val left = l.toDouble()
            val top = t.toDouble()
            val right = r.toDouble()
            val bottom = b.toDouble()
            val w = right - left
            val h = bottom - top
            val x = ex.toDouble()
            val y = ey.toDouble()
            val s = COVER_SHIFT
            val kx = (x - 1.0) / 2.0 + COVER_RELATIVE * x / 2.0
            val kTop = 0.5 - s - y / 2.0 - COVER_RELATIVE * y / 2.0
            val kBottom = y / 2.0 - 0.5 - s + COVER_RELATIVE * y / 2.0
            return doubleArrayOf(
                floor(min(left - w * kx - COVER_MARGIN, left - COVER_RAISE)),
                floor(min(top + h * kTop - COVER_MARGIN, top - COVER_RAISE)),
                ceil(max(right + w * kx + COVER_MARGIN, right + COVER_RAISE)),
                ceil(max(bottom + h * kBottom + COVER_MARGIN, bottom + COVER_RAISE)),
            )
        }

        /** The fixed upward shift of the mask centre (0.08 of the box height). */
        private val COVER_SHIFT = MaskSettings.DEFAULT_SHIFT_UP.toDouble()

        /** The part test's margins: 0.05 px and 1e-4 relative. */
        private const val COVER_MARGIN = 0.05
        private const val COVER_RELATIVE = 1e-4

        /** Covers masks whose radius the stage raised to 1 px: 1 px plus the margin beyond the box. */
        private const val COVER_RAISE = 1.05

        /** The expanded geometry; its centre and radii are checked like [exact] (the expansion can overflow). */
        private fun fromBox(face: DetectedFace, masking: MaskingConfig, origin: MaskOrigin, parameter: String): Mask {
            val g =
                try {
                    MaskPainter.maskFor(face, masking.toSettings())
                } catch (e: CoreFailure) {
                    throw FaceMaskException.InvalidConfiguration(parameter, "finite coordinates with ordered edges")
                }
            val ok =
                g.rx > 0f && g.ry > 0f && g.rx <= MAX_COORDINATE && g.ry <= MAX_COORDINATE &&
                    g.cx in -MAX_COORDINATE..MAX_COORDINATE && g.cy in -MAX_COORDINATE..MAX_COORDINATE
            if (!ok) throw FaceMaskException.InvalidConfiguration(parameter, "a box whose mask is finite and in range")
            return Mask(g.shape, g.cx, g.cy, g.rx, g.ry, origin)
        }
    }
}

/**
 * The masks to write on one image of [width] × [height] pixels. [FaceMasker.applyMasks] refuses a plan made for
 * another size ([FaceMaskException.MaskPlanMismatch]) instead of rescaling it.
 *
 * A plan holds at most 65,536 masks; more throw [FaceMaskException.InvalidConfiguration] (`masks`). The masks'
 * summed working area is checked when the plan is applied (see [FaceMasker.applyMasks]).
 *
 * File mode: a plan from `detectFile` (through [Detection.toMaskPlan]) or from [PlanMasker.planFile] is bound to the
 * decoded pixels of that file, and [withMasks] keeps the binding. `maskFile` accepts only such a plan, and only while
 * the file's pixels are unchanged: a changed file throws [FaceMaskException.SourceChanged], a plan without a binding
 * (made with this constructor) throws [FaceMaskException.MaskPlanMismatch] before the file is opened. The binding
 * cannot be read, set or stored, and it is not part of [equals], [hashCode] or [toString].
 *
 * In-memory mode: a plan from the in-memory `detect` (through [Detection.toMaskPlan], also edited with [withMasks]) is
 * bound to the view of the pixels that detection read: on iOS the orientation a buffer wrapper was made with, for
 * every other image the upright view. [FaceMasker.applyMasks] refuses it through another view (for example the same
 * iOS buffer wrapped with another orientation, or without one) with [FaceMaskException.MaskPlanMismatch], because its
 * masks would land on other pixels than the faces it was made for. A plan made with this constructor has no binding
 * and is applied to every image of the right size. This binding is not part of [equals], [hashCode] or [toString]
 * either.
 */
public class MaskPlan private constructor(
    /** Image width in pixels the masks refer to. */
    public val width: Int,
    /** Image height in pixels the masks refer to. */
    public val height: Int,
    masks: List<Mask>,
    /** The binding to a decoded file (file mode; ADR 0015 decision 6). Synthetic on the JVM and Android. */
    @get:HiddenFromJava internal val fingerprint: ImageFingerprint?,
    /** The view the in-memory `detect` read ([PlanView.viewOf]); `null`: no view binding. Synthetic on JVM/Android. */
    @get:HiddenFromJava internal val view: Int?,
) {
    /**
     * A plan of [masks] for an image of [width] × [height] pixels, without a file binding (for in-memory
     * [FaceMasker.applyMasks]; file mode needs a plan from `detectFile` or [PlanMasker.planFile]).
     *
     * @throws FaceMaskException.InvalidConfiguration (`width/height`) for a negative size; (`masks`) for more than
     * 65,536 masks.
     */
    @Throws(FaceMaskException::class)
    public constructor(width: Int, height: Int, masks: List<Mask>) : this(width, height, masks, null, null)

    init {
        if (width < 0 || height < 0) throw FaceMaskException.InvalidConfiguration("width/height", ">= 0")
        if (masks.size > MaskStageLimits.MAX_MASKS_PER_IMAGE) {
            throw FaceMaskException.InvalidConfiguration("masks", MaskLimitText.planCount(masks.size))
        }
    }

    /** The masks, in the order they are written. */
    public val masks: List<Mask> = masks.toList()

    /**
     * A new plan with the same [width], [height] and bindings (none if this plan has none) and the given [masks],
     * validated as by the constructor; this plan is unchanged. The way to edit a plan in both modes: for example
     * remove a mask the user rejected, or add [Mask.manual] masks the user drew or the app's own detector found.
     *
     * @throws FaceMaskException.InvalidConfiguration (`masks`) for more than 65,536 masks.
     */
    @Throws(FaceMaskException::class)
    public fun withMasks(masks: List<Mask>): MaskPlan = MaskPlan(width, height, masks, fingerprint, view)

    /** True when [other] is a [MaskPlan] with the same size and masks (the bindings are not compared). */
    override fun equals(other: Any?): Boolean =
        other is MaskPlan && width == other.width && height == other.height && masks == other.masks

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int = (31 * width + height) * 31 + masks.hashCode()

    /** Debug text with the size and the number of masks. */
    override fun toString(): String = "MaskPlan(width=$width, height=$height, masks=${masks.size})"

    internal companion object {
        /**
         * A plan bound to [fingerprint] and [view] (`null`: without that binding), for `toMaskPlan()` and `planFile`.
         * Synthetic on the JVM and Android ([HiddenFromJava]), so Java code cannot bind a plan to anything.
         */
        @HiddenFromJava
        fun bound(width: Int, height: Int, masks: List<Mask>, fingerprint: ImageFingerprint?, view: Int?): MaskPlan =
            MaskPlan(width, height, masks, fingerprint, view)
    }
}
