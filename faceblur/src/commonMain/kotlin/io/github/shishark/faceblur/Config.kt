// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.mask.MaskSettings
import kotlin.jvm.JvmOverloads

/**
 * Cooperative cancellation for one call. FaceBlur reads [isCancellationRequested] before detection, between detector
 * passes, during long loops over many detections (at very low score thresholds) and immediately before writing masks;
 * see [FaceMaskException.Cancelled] for what the image looks like then. A pass already inside the runtime is not
 * interrupted.
 */
public interface CancellationToken {
    /** True once the caller wants the call to stop. */
    public val isCancellationRequested: Boolean
}

/**
 * How masks look. Every value is limited to a range that cannot weaken protection: masks can only be made larger,
 * every style is opaque, and the colour must be opaque.
 *
 * @throws FaceMaskException.InvalidConfiguration when a value is outside its range (the exception names it).
 */
public class MaskingConfig
@JvmOverloads
@Throws(FaceMaskException::class)
constructor(
    /** Fill of the mask; every style is opaque. Default [MaskStyle.GRADIENT_OVAL]. */
    public val style: MaskStyle = MaskStyle.GRADIENT_OVAL,
    /** Outline of the mask. Default [MaskShape.ELLIPSE]. */
    public val shape: MaskShape = MaskShape.ELLIPSE,
    /** Mask width as a multiple of the face box width: 1.6 (default and minimum) to 3.0. */
    public val expandX: Float = DEFAULT_EXPAND_X,
    /** Mask height as a multiple of the face box height: 1.8 (default and minimum) to 3.0. */
    public val expandY: Float = DEFAULT_EXPAND_Y,
    /** Width of the soft edge outside the opaque mask, as a fraction of the radius: 0 to 0.5 (default 0.10). */
    public val feather: Float = DEFAULT_FEATHER,
    /** Colour of [MaskStyle.SOLID] and of the centre of [MaskStyle.GRADIENT_OVAL]; alpha byte must be 0xFF. */
    public val color: Int = DEFAULT_COLOR,
) {
    init {
        requireRange("expandX", expandX, DEFAULT_EXPAND_X, MAX_EXPAND)
        requireRange("expandY", expandY, DEFAULT_EXPAND_Y, MAX_EXPAND)
        requireRange("feather", feather, 0f, MAX_FEATHER)
        val opaque = color ushr 24 == 0xFF
        if (!opaque) throw FaceMaskException.InvalidConfiguration("color", "an opaque colour (alpha 0xFF)")
    }

    /** The renderer settings; the fixed constants are the measured configuration (ADR 0002 decision 10). */
    internal fun toSettings(): MaskSettings = MaskSettings(
        style = style,
        shape = shape,
        expandX = expandX,
        expandY = expandY,
        feather = feather,
        color = color,
    )

    /** True when [other] is a [MaskingConfig] with the same values. */
    override fun equals(other: Any?): Boolean = other is MaskingConfig &&
        style == other.style &&
        shape == other.shape &&
        expandX.toBits() == other.expandX.toBits() &&
        expandY.toBits() == other.expandY.toBits() &&
        feather.toBits() == other.feather.toBits() &&
        color == other.color

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int =
        listOf(style, shape, expandX.toBits(), expandY.toBits(), feather.toBits(), color).hashCode()

    /** Debug text with every value. */
    override fun toString(): String =
        "MaskingConfig(style=$style, shape=$shape, expandX=$expandX, expandY=$expandY, feather=$feather, " +
            "color=0x${color.toUInt().toString(16).uppercase()})"

    /** Builder for Java callers; starts from the defaults. */
    public class Builder {
        private var style = MaskStyle.GRADIENT_OVAL
        private var shape = MaskShape.ELLIPSE
        private var expandX = DEFAULT_EXPAND_X
        private var expandY = DEFAULT_EXPAND_Y
        private var feather = DEFAULT_FEATHER
        private var color = DEFAULT_COLOR

        /** Sets [MaskingConfig.style]. */
        public fun style(value: MaskStyle): Builder = apply { style = value }

        /** Sets [MaskingConfig.shape]. */
        public fun shape(value: MaskShape): Builder = apply { shape = value }

        /** Sets [MaskingConfig.expandX]. */
        public fun expandX(value: Float): Builder = apply { expandX = value }

        /** Sets [MaskingConfig.expandY]. */
        public fun expandY(value: Float): Builder = apply { expandY = value }

        /** Sets [MaskingConfig.feather]. */
        public fun feather(value: Float): Builder = apply { feather = value }

        /** Sets [MaskingConfig.color]. */
        public fun color(value: Int): Builder = apply { color = value }

        /** Builds and validates the configuration. */
        @Throws(FaceMaskException::class)
        public fun build(): MaskingConfig = MaskingConfig(style, shape, expandX, expandY, feather, color)
    }

    private companion object {
        const val DEFAULT_EXPAND_X = 1.6f
        const val DEFAULT_EXPAND_Y = 1.8f
        const val DEFAULT_FEATHER = 0.10f
        const val DEFAULT_COLOR = 0xFF7F7F7F.toInt()
        const val MAX_EXPAND = 3.0f
        const val MAX_FEATHER = 0.5f
    }
}

/**
 * Settings of one [FaceMasker]: how masks look ([masking]) and the ONNX Runtime worker threads ([onnxThreads]).
 * The library's own detection rules (tiling, merging, NMS, mask shift) are fixed and not exposed; the model, its
 * profile and its score threshold are the app's ([DetectorModel]).
 *
 * @throws FaceMaskException.InvalidConfiguration when a value is outside its range (the exception names it).
 */
public class FaceMaskerConfig
@JvmOverloads
@Throws(FaceMaskException::class)
constructor(
    /** Mask appearance; default [MaskingConfig]. */
    public val masking: MaskingConfig = MaskingConfig(),
    /** ONNX Runtime worker threads: 0 (automatic, the default) to 4. */
    public val onnxThreads: Int = 0,
) {
    init {
        val inRange = onnxThreads in 0..MAX_THREADS
        if (!inRange) throw FaceMaskException.InvalidConfiguration("onnxThreads", "0..$MAX_THREADS")
    }

    /** True when [other] is a [FaceMaskerConfig] with the same values. */
    override fun equals(other: Any?): Boolean =
        other is FaceMaskerConfig && masking == other.masking && onnxThreads == other.onnxThreads

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int = 31 * masking.hashCode() + onnxThreads

    /** Debug text with every value. */
    override fun toString(): String = "FaceMaskerConfig(masking=$masking, onnxThreads=$onnxThreads)"

    /** Builder for Java callers; starts from the defaults. */
    public class Builder {
        private var masking = MaskingConfig()
        private var onnxThreads = 0

        /** Sets [FaceMaskerConfig.masking]. */
        public fun masking(value: MaskingConfig): Builder = apply { masking = value }

        /** Sets [FaceMaskerConfig.onnxThreads]. */
        public fun onnxThreads(value: Int): Builder = apply { onnxThreads = value }

        /** Builds and validates the configuration. */
        @Throws(FaceMaskException::class)
        public fun build(): FaceMaskerConfig = FaceMaskerConfig(masking, onnxThreads)
    }

    private companion object {
        const val MAX_THREADS = 4
    }
}

private fun requireRange(parameter: String, value: Float, min: Float, max: Float) {
    if (!(value >= min && value <= max)) throw FaceMaskException.InvalidConfiguration(parameter, "$min..$max")
}
