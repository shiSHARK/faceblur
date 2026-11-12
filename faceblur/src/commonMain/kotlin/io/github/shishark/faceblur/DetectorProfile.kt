// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import kotlin.jvm.JvmOverloads

/**
 * Describes the app's face-detection model: how its output is laid out ([format]), its input size, the score
 * threshold and the minimum mask size for its boxes. The app chooses the model and the profile and is responsible
 * for both; FaceBlur checks the values only technically (ranges here; the model's inputs and outputs when it is
 * loaded; every output when it runs). It does not judge whether a model, a profile or a threshold suits the app's
 * photos.
 *
 * Plain statement of the residual risk: a well-formed model with a wrongly declared profile (for example the wrong
 * colour order, value scaling or box format) can find no faces and still report success. FaceBlur cannot see this
 * from the file or from the output. Check a setup with your own test image and known face boxes.
 *
 * Immutable and thread-safe.
 *
 * @throws FaceMaskException.InvalidConfiguration when a value is outside its range (the exception names it).
 */
public class DetectorProfile
@JvmOverloads
@Throws(FaceMaskException::class)
constructor(
    /** The layout of the model's input and output. */
    public val format: DetectionFormat,
    /** Model input width in pixels, 128 to 1,280; a multiple of 32 for [DetectionFormat.YuNetLayout]. */
    public val inputWidth: Int,
    /** Model input height in pixels, 128 to 1,280; a multiple of 32 for [DetectionFormat.YuNetLayout]. */
    public val inputHeight: Int,
    /**
     * Score threshold, required, finite and strictly between 0 and 1. A box is kept when its probability is at or
     * above it. A higher threshold masks fewer faces. A lower threshold never removes or shrinks a mask of a call
     * that succeeds, pixel for pixel: every pixel masked at a higher threshold is also masked at a lower one. To keep
     * this, a face that crosses a tile border can get part masks next to its own mask, so the mask plan
     * ([Detection.toMaskPlan], [MaskResult.masks]) can hold more masks than [Detection.faces] has faces. Very low
     * thresholds slow processing and can mask most of the photo; beyond the validated range a call can fail
     * ([FaceMaskException.ModelOutputInvalid]) instead. A photo can have at most 65,536 masks: a threshold far below
     * the model's useful range can produce more, and the call then fails safely with
     * [FaceMaskException.ModelOutputInvalid] and `TOO_MANY_MASKS`, whose message gives the mask count and this
     * threshold and says that the threshold is very likely too low for this model; check it with faceblur-eval. In
     * the same way, the duplicate removal inside each detector pass may compare at most 240,000,000 pairs of boxes per
     * photo; a threshold far too low can need more, and the call then fails safely with `TOO_MANY_NMS_PAIR_CHECKS`,
     * whose message gives the pair checks reached, this threshold and the same advice. If
     * the model filters scores inside its graph, faces below that cut-off are lost even when this threshold is lower,
     * and FaceBlur cannot detect this.
     */
    public val scoreThreshold: Float,
    /**
     * Required: the SHA-256 of the model you approved, 64 hexadecimal digits in either case (stored in lower case);
     * get it with `shasum -a 256 model.onnx`. A Java caller passing `null` gets Kotlin's `NullPointerException` at
     * this call, before anything is read.
     */
    expectedSha256: String,
    /** Smallest mask width as a multiple of the box width for this model's boxes: 1.6 (default and minimum) to 3.0. */
    public val minMaskExpandX: Float = DEFAULT_MIN_EXPAND_X,
    /** Smallest mask height as a multiple of the box height for this model's boxes: 1.8 (default and minimum) to 3.0. */
    public val minMaskExpandY: Float = DEFAULT_MIN_EXPAND_Y,
) {
    /**
     * The SHA-256 of the model you approved, in lower case. [DetectorModel.load] hashes the bytes right after the
     * size check and refuses a model with another hash before any part of it is parsed or reaches a runtime
     * ([FaceMaskException.ModelIntegrity]). The recommended pattern is to bundle the model with the app and keep
     * this value hard-coded in the app's code, not next to the model.
     */
    public val expectedSha256: String = expectedSha256.lowercase()

    init {
        val side = MIN_INPUT_SIDE..MAX_INPUT_SIDE
        if (inputWidth !in side) throw FaceMaskException.InvalidConfiguration("inputWidth", "$side")
        if (inputHeight !in side) throw FaceMaskException.InvalidConfiguration("inputHeight", "$side")
        if (format is DetectionFormat.YuNetLayout) {
            if (inputWidth % YUNET_ALIGN != 0) {
                throw FaceMaskException.InvalidConfiguration("inputWidth", "a multiple of $YUNET_ALIGN for YuNetLayout")
            }
            if (inputHeight % YUNET_ALIGN != 0) {
                throw FaceMaskException.InvalidConfiguration(
                    "inputHeight",
                    "a multiple of $YUNET_ALIGN for YuNetLayout",
                )
            }
        }
        if (!(scoreThreshold > 0f && scoreThreshold < 1f)) {
            throw FaceMaskException.InvalidConfiguration("scoreThreshold", "finite, greater than 0 and less than 1")
        }
        if (!(minMaskExpandX >= DEFAULT_MIN_EXPAND_X && minMaskExpandX <= MAX_EXPAND)) {
            throw FaceMaskException.InvalidConfiguration("minMaskExpandX", "$DEFAULT_MIN_EXPAND_X..$MAX_EXPAND")
        }
        if (!(minMaskExpandY >= DEFAULT_MIN_EXPAND_Y && minMaskExpandY <= MAX_EXPAND)) {
            throw FaceMaskException.InvalidConfiguration("minMaskExpandY", "$DEFAULT_MIN_EXPAND_Y..$MAX_EXPAND")
        }
        if (!SHA256_PATTERN.matches(this.expectedSha256)) {
            throw FaceMaskException.InvalidConfiguration("expectedSha256", "64 hexadecimal digits")
        }
    }

    /** True when [other] is a [DetectorProfile] with the same values (the hash is stored in lower case). */
    override fun equals(other: Any?): Boolean = other is DetectorProfile &&
        format == other.format &&
        inputWidth == other.inputWidth &&
        inputHeight == other.inputHeight &&
        scoreThreshold.toBits() == other.scoreThreshold.toBits() &&
        minMaskExpandX.toBits() == other.minMaskExpandX.toBits() &&
        minMaskExpandY.toBits() == other.minMaskExpandY.toBits() &&
        expectedSha256 == other.expectedSha256

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int = listOf(
        format,
        inputWidth,
        inputHeight,
        scoreThreshold.toBits(),
        minMaskExpandX.toBits(),
        minMaskExpandY.toBits(),
        expectedSha256,
    ).hashCode()

    /** Debug text with every value. */
    override fun toString(): String =
        "DetectorProfile(format=$format, inputWidth=$inputWidth, inputHeight=$inputHeight, " +
            "scoreThreshold=$scoreThreshold, expectedSha256=$expectedSha256, minMaskExpandX=$minMaskExpandX, " +
            "minMaskExpandY=$minMaskExpandY)"

    /**
     * Builder for Java callers. [format], the input size, [scoreThreshold] and [expectedSha256] have no default:
     * [build] throws [FaceMaskException.InvalidConfiguration] naming the first one that was not set.
     */
    public class Builder {
        private var format: DetectionFormat? = null
        private var inputWidth: Int? = null
        private var inputHeight: Int? = null
        private var scoreThreshold: Float? = null
        private var minMaskExpandX = DEFAULT_MIN_EXPAND_X
        private var minMaskExpandY = DEFAULT_MIN_EXPAND_Y
        private var expectedSha256: String? = null

        /** Sets [DetectorProfile.format]. */
        public fun format(value: DetectionFormat): Builder = apply { format = value }

        /** Sets [DetectorProfile.inputWidth] and [DetectorProfile.inputHeight]. */
        public fun inputSize(width: Int, height: Int): Builder = apply {
            inputWidth = width
            inputHeight = height
        }

        /** Sets [DetectorProfile.scoreThreshold]. */
        public fun scoreThreshold(value: Float): Builder = apply { scoreThreshold = value }

        /** Sets [DetectorProfile.minMaskExpandX]. */
        public fun minMaskExpandX(value: Float): Builder = apply { minMaskExpandX = value }

        /** Sets [DetectorProfile.minMaskExpandY]. */
        public fun minMaskExpandY(value: Float): Builder = apply { minMaskExpandY = value }

        /** Sets [DetectorProfile.expectedSha256] (required; Java `null` is a `NullPointerException` here). */
        public fun expectedSha256(value: String): Builder = apply { expectedSha256 = value }

        /** Builds and validates the profile. */
        @Throws(FaceMaskException::class)
        public fun build(): DetectorProfile = DetectorProfile(
            format ?: throw FaceMaskException.InvalidConfiguration("format", "required"),
            inputWidth ?: throw FaceMaskException.InvalidConfiguration("inputWidth", "required"),
            inputHeight ?: throw FaceMaskException.InvalidConfiguration("inputHeight", "required"),
            scoreThreshold ?: throw FaceMaskException.InvalidConfiguration("scoreThreshold", "required"),
            expectedSha256 ?: throw FaceMaskException.InvalidConfiguration("expectedSha256", "required"),
            minMaskExpandX,
            minMaskExpandY,
        )
    }

    private companion object {
        const val MIN_INPUT_SIDE = 128
        const val MAX_INPUT_SIDE = 1280
        const val YUNET_ALIGN = 32
        const val DEFAULT_MIN_EXPAND_X = 1.6f
        const val DEFAULT_MIN_EXPAND_Y = 1.8f
        const val MAX_EXPAND = 3.0f
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/**
 * The layout of a model's output, decoded by FaceBlur code (there are no custom decoders). Landmarks in a model's
 * output are ignored. Scores must be probabilities: a value outside [0, 1] (beyond a tolerance of 1e-6) or a
 * non-finite value fails the pass ([FaceMaskException.ModelOutputInvalid]); a model with logit outputs is therefore an
 * error, not a model whose faces silently disappear.
 */
public sealed class DetectionFormat {
    /**
     * The layout of a face-detection model in YuNet layout: outputs `cls_8`, `obj_8`, `bbox_8` and the same for
     * strides 16 and 32 (`kps_*` outputs are optional and ignored); input BGR, values 0 to 255, NCHW;
     * score = sqrt(cls × obj).
     * Duplicate boxes inside one pass are removed at IoU 0.3.
     */
    public class YuNetLayout public constructor() : DetectionFormat() {
        /** All instances are equal. */
        override fun equals(other: Any?): Boolean = other is YuNetLayout

        /** Hash code consistent with [equals]. */
        override fun hashCode(): Int = 1

        /** Debug text. */
        override fun toString(): String = "YuNetLayout"
    }

    /**
     * Decoded detections: N rows of K values. Each row holds four box values starting at [boxColumn] and one face
     * probability at [scoreColumn], in [boxesOutput] or in [scoresOutput]. K must be static in the model.
     *
     * @throws FaceMaskException.InvalidConfiguration when a value is outside its range (the exception names it).
     */
    public class GenericDetections
    @JvmOverloads
    @Throws(FaceMaskException::class)
    constructor(
        /** Name of the output that holds the boxes. */
        public val boxesOutput: String,
        /** Index of the first of the four box values in a row (0 or more). */
        public val boxColumn: Int,
        /** Name of the output that holds the scores, or `null` when the scores are in [boxesOutput]. */
        public val scoresOutput: String?,
        /** Index of the face probability in a row of the scores output (0 or more). */
        public val scoreColumn: Int,
        /** Rows ([RowLayout.ROWS]) or columns ([RowLayout.COLUMNS]) of the output tensors. */
        public val rows: RowLayout,
        /** How the four box values are written. */
        public val boxFormat: BoxFormat,
        /** Whether box values are input-tensor pixels or fractions of the input tensor's width and height. */
        public val coordinates: CoordinateSpace,
        /** Channel order of the input tensor. */
        public val colorOrder: ColorOrder,
        /** Memory layout of the input tensor. */
        public val tensorLayout: TensorLayout,
        /** How pixel values become input values. */
        public val scaling: PixelScaling,
        /**
         * True when the model removes duplicate boxes inside its graph; FaceBlur then skips its own removal inside
         * one pass (the merge across passes always runs, so this can only keep more boxes). If such a model also
         * filters scores inside its graph, faces below that cut-off are lost even when the profile's threshold is
         * lower, and FaceBlur cannot detect this.
         */
        public val nmsInGraph: Boolean = false,
    ) : DetectionFormat() {
        init {
            if (boxesOutput.isEmpty()) throw FaceMaskException.InvalidConfiguration("boxesOutput", "a non-empty name")
            if (scoresOutput != null && scoresOutput.isEmpty()) {
                throw FaceMaskException.InvalidConfiguration("scoresOutput", "a non-empty name or null")
            }
            if (boxColumn < 0) throw FaceMaskException.InvalidConfiguration("boxColumn", ">= 0")
            if (scoreColumn < 0) throw FaceMaskException.InvalidConfiguration("scoreColumn", ">= 0")
        }

        /** True when [other] is a [GenericDetections] with the same values. */
        override fun equals(other: Any?): Boolean = other is GenericDetections &&
            boxesOutput == other.boxesOutput &&
            boxColumn == other.boxColumn &&
            scoresOutput == other.scoresOutput &&
            scoreColumn == other.scoreColumn &&
            rows == other.rows &&
            boxFormat == other.boxFormat &&
            coordinates == other.coordinates &&
            colorOrder == other.colorOrder &&
            tensorLayout == other.tensorLayout &&
            scaling == other.scaling &&
            nmsInGraph == other.nmsInGraph

        /** Hash code consistent with [equals]. */
        override fun hashCode(): Int = listOf(
            boxesOutput,
            boxColumn,
            scoresOutput,
            scoreColumn,
            rows,
            boxFormat,
            coordinates,
            colorOrder,
            tensorLayout,
            scaling,
            nmsInGraph,
        ).hashCode()

        /** Debug text with every value. */
        override fun toString(): String =
            "GenericDetections(boxesOutput=$boxesOutput, boxColumn=$boxColumn, scoresOutput=$scoresOutput, " +
                "scoreColumn=$scoreColumn, rows=$rows, boxFormat=$boxFormat, coordinates=$coordinates, " +
                "colorOrder=$colorOrder, tensorLayout=$tensorLayout, scaling=$scaling, nmsInGraph=$nmsInGraph)"
    }
}

/**
 * How a pixel value v (0 to 255) of channel c becomes an input value: (v / [divisor] − [mean]\[c]) / [std]\[c].
 * Channel index c is the position in the tensor the model receives, after the colour order is applied (c = 0 is red
 * for [ColorOrder.RGB], blue for [ColorOrder.BGR]). The arrays are copied; later changes to them change nothing.
 *
 * @throws FaceMaskException.InvalidConfiguration when [divisor] is not positive, an array does not have three
 * values, a mean is not finite or a standard deviation is not positive.
 */
public class PixelScaling
@Throws(FaceMaskException::class)
constructor(
    divisor: Float,
    mean: FloatArray,
    std: FloatArray,
) {
    /** The divisor, finite and greater than 0. */
    public val divisor: Float = divisor

    /** The three means (read-only copy). */
    public val mean: List<Float> = mean.toList().readOnly()

    /** The three standard deviations (read-only copy). */
    public val std: List<Float> = std.toList().readOnly()

    init {
        if (!(divisor.isFinite() && divisor > 0f)) {
            throw FaceMaskException.InvalidConfiguration("divisor", "finite, greater than 0")
        }
        if (this.mean.size != CHANNELS || this.mean.any { !it.isFinite() }) {
            throw FaceMaskException.InvalidConfiguration("mean", "three finite values")
        }
        if (this.std.size != CHANNELS || this.std.any { !(it.isFinite() && it > 0f) }) {
            throw FaceMaskException.InvalidConfiguration("std", "three finite values greater than 0")
        }
    }

    /** True when [other] is a [PixelScaling] with the same values. */
    override fun equals(other: Any?): Boolean = other is PixelScaling &&
        divisor.toBits() == other.divisor.toBits() &&
        mean.map { it.toBits() } == other.mean.map { it.toBits() } &&
        std.map { it.toBits() } == other.std.map { it.toBits() }

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int = listOf(
        divisor.toBits(),
        mean.map {
            it.toBits()
        },
        std.map { it.toBits() },
    ).hashCode()

    /** Debug text with every value. */
    override fun toString(): String = "PixelScaling(divisor=$divisor, mean=$mean, std=$std)"

    private companion object {
        const val CHANNELS = 3
    }
}

/** How detections are laid out in an output tensor. */
public enum class RowLayout {
    /** One detection per row: shape [N, K] or [1, N, K]. */
    ROWS,

    /** One detection per column: shape [K, N] or [1, K, N]. */
    COLUMNS,
}

/** How the four box values of a detection are written. */
public enum class BoxFormat {
    /** Left, top, right, bottom. */
    XYXY,

    /** Left, top, width, height. */
    XYWH,

    /** Centre x, centre y, width, height. */
    CXCYWH,
}

/** The unit of box values. */
public enum class CoordinateSpace {
    /** Pixels of the model input tensor. */
    INPUT_PIXELS,

    /** Fractions of the input tensor's width (x values) and height (y values), not of the letterboxed content. */
    NORMALIZED,
}

/** Channel order of the input tensor. */
public enum class ColorOrder {
    /** Red, green, blue. */
    RGB,

    /** Blue, green, red. */
    BGR,
}

/** Memory layout of the input tensor. */
public enum class TensorLayout {
    /** [1, 3, height, width]. */
    NCHW,

    /** [1, height, width, 3]. */
    NHWC,
}
