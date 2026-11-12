// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.mask.MaskStageLimits

/**
 * Every error FaceBlur reports. Messages are fixed English text plus parameter names, dimensions or counts; they never
 * contain pixel data, file names, paths or face coordinates.
 *
 * FaceBlur fails closed: it never returns an image in which a detected or requested face was silently left unmasked
 * or only partly masked. If `applyMasks` or `maskInPlace` throws a [FaceMaskException], the image is untouched, with
 * two exceptions: [MaskWriteFailed] (discard the image) and [Cancelled] requested during the final copy (the image is
 * fully masked). If such a call ends with any other error (for example an `OutOfMemoryError` on Android), discard the
 * image.
 *
 * More subclasses (decoding, encoding, main-thread use) arrive with the platform file-mode adapters. Errors caused
 * by the app's model or profile are [ModelRejected], [ModelOutputInvalid] and [ModelIntegrity]; [Internal] is
 * reserved for faults of the library itself.
 */
public sealed class FaceMaskException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** A configuration value, mask or plan is outside its allowed range. [parameter] is the public property name. */
    public class InvalidConfiguration internal constructor(
        /** The public name of the rejected property, for example `"expandX"`. */
        public val parameter: String,
        allowed: String,
    ) : FaceMaskException("Invalid value for '$parameter': allowed $allowed")

    /**
     * The image is too large: more pixels than [limit], or a side above 65,535 px (JPEG's own maximum per side), or
     * a negative size. The message names the limit that was exceeded.
     */
    public class ImageTooLarge internal constructor(
        /** Width of the rejected image in pixels. */
        public val width: Int,
        /** Height of the rejected image in pixels. */
        public val height: Int,
        /**
         * Largest accepted pixel count. When a side exceeded 65,535 px this is the largest pixel count any image may
         * have (2,147,483,647) and the message names the per-side limit instead.
         */
        public val limit: Long,
        exceeded: String = "the limit of $limit pixels",
    ) : FaceMaskException("Image of $width x $height pixels exceeds $exceeded")

    /** The source is not a JPEG, HEIC or PNG image (recognised from its first bytes), or cannot be read as one. */
    public class UnsupportedImage internal constructor(reason: String) :
        FaceMaskException("Unsupported image: $reason")

    /**
     * The [OutputTarget] cannot be used for this call; nothing was written and the target is untouched. Examples: a
     * [OutputTarget.PlatformTarget] passed to the common path form, [OutputTarget.OverwriteSource] for a source that
     * cannot be replaced in one step (an Android content URI), an existing file at [OutputTarget.NewFile.path] without
     * `replaceExisting`, or a location the platform does not allow writing to. Targets that can be checked without
     * the image are refused before the source is decoded. The message names the reason and, where one exists, the
     * form or target to use instead; it never contains a path or URI.
     */
    public class UnsupportedTarget internal constructor(reason: String) :
        FaceMaskException("Unsupported target: $reason")

    /**
     * A source read from a stream (the stream form of file mode, for example Android `maskJpeg`) is larger than
     * [limitBytes]. The stream form is the only form that FaceBlur reads into memory, so it reads at most
     * [limitBytes] (64 MiB, fixed) after the format check; nothing was decoded and nothing was written (the output
     * stream is untouched). Use the file (path) or `Uri` form for larger inputs: the platform decoder reads those
     * from the file or the provider, and this limit does not apply to them. The message names the limit and those
     * forms; it never contains a path or the size of the source.
     */
    public class SourceTooLarge internal constructor(
        /** The largest stream source in bytes (67,108,864 = 64 MiB in this version). */
        public val limitBytes: Long,
    ) : FaceMaskException(
        "Source stream exceeds the limit of ${grouped(limitBytes)} bytes for streams; use the file (path) or Uri " +
            "form for larger inputs",
    )

    /**
     * The encoder output could not be parsed, or the output failed the library's own check of its structure and
     * metadata; nothing was written. The message is "Output validation failed: " followed by the [findings] codes
     * joined by commas; neither carries image data, metadata values, offsets or paths.
     */
    public class OutputValidationFailed internal constructor(findings: List<String>) :
        FaceMaskException("Output validation failed: ${findings.distinct().joinToString(",")}") {
        /**
         * The finding codes, each at most once, in the order they were found, never empty. The 0.1.0 codes are
         * `NOT_JPEG`, `STRUCTURE`, `TRUNCATED`, `MISSING_EOI`, `TRAILING_BYTES`, `UNEXPECTED_MARKER`,
         * `FORBIDDEN_SEGMENT`, `SEGMENT_ORDER`, `MISSING_EXIF`, `DUPLICATE_EXIF`, `EXIF_TOO_LARGE`, `EXIF_INVALID`,
         * `EXIF_TAG_NOT_ALLOWED`, `ORIENTATION_NOT_1`, `FRAME` and `NO_SCAN`. A code is never renamed, removed or
         * given another meaning without a breaking-change entry in the changelog, but new codes may be added in any
         * release: treat every code, known or not, as a failure and do not match the list exhaustively. The codes
         * are for logs and support only.
         */
        public val findings: List<String> = findings.distinct().readOnly()
    }

    /**
     * The [MaskPlan] does not fit the image; nothing was written. Either it was made for another image size than the
     * image it is applied to (in file mode: the working size), or, in file mode only, it has no binding to a decoded
     * file: it was built with the public [MaskPlan] constructor instead of coming from `detectFile` or
     * [PlanMasker.planFile] (edited with [MaskPlan.withMasks]). The second case is refused before the source is
     * opened. In-memory mode also refuses a plan from `detect` applied through another view of the pixels than the
     * detection read (on iOS: the buffer wrapped with another orientation; see [MaskPlan]). Both are faults in the code that builds the plan, so the same call fails again until that code changes.
     */
    public class MaskPlanMismatch private constructor(message: String) : FaceMaskException(message) {
        internal constructor(planWidth: Int, planHeight: Int, imageWidth: Int, imageHeight: Int) :
            this("Mask plan for $planWidth x $planHeight pixels applied to a $imageWidth x $imageHeight image")

        internal companion object {
            /** The fixed text of a file-mode plan without a binding (ADR 0015 decision 6). */
            const val UNBOUND: String =
                "The mask plan is not bound to a file; in file mode use a plan from detectFile or planFile, edited " +
                    "with withMasks. Nothing was written."

            /** A file-mode plan without a binding. */
            fun unbound(): MaskPlanMismatch = MaskPlanMismatch(UNBOUND)

            /** The fixed text of an in-memory plan applied through another view than its detection read. */
            const val OTHER_VIEW: String =
                "The mask plan was made from another view of the pixels (another orientation); apply it with the " +
                    "orientation of the detect call that made it. Nothing was written."

            /** An in-memory plan from `detect` applied through another view (orientation). */
            fun otherView(): MaskPlanMismatch = MaskPlanMismatch(OTHER_VIEW)
        }
    }

    /**
     * File mode: the source's decoded pixels differ from those the [MaskPlan] was made for (the file was replaced,
     * edited, re-encoded or turned by another app after `detectFile` or [PlanMasker.planFile]). Nothing was masked
     * or written, and the target is untouched. The message is fixed text, with no path, size or value, and nothing is
     * attached to it (no cause).
     *
     * What to do: run `detectFile` again on the current file (with masks from your own detector: [PlanMasker.planFile]
     * and your detector again) and, if your app shows the masks to the user for review, show the new result again
     * before masking. Do not reuse the old masks on the new file.
     */
    public class SourceChanged internal constructor() : FaceMaskException(MESSAGE) {
        internal companion object {
            /** The fixed text (ADR 0015 decision 6). */
            const val MESSAGE: String =
                "The source image changed after the mask plan was made; nothing was written. Make the plan again " +
                    "for the current file and review it before masking."
        }
    }

    /**
     * The model's SHA-256 differs from [DetectorProfile.expectedSha256]. It is checked before the model is parsed;
     * no runtime session was created.
     */
    public class ModelIntegrity internal constructor(reason: String, cause: Throwable? = null) :
        FaceMaskException("Model integrity check failed: $reason", cause)

    /**
     * Reading failed: the model (for example the file does not exist or cannot be read), or in file mode the source
     * image file, URI or stream. The message never contains the path or URI.
     */
    public class IoFailed internal constructor(reason: String) : FaceMaskException("I/O failed: $reason")

    /**
     * The model file or the model was refused while it was loaded ([DetectorModel.load]) or while an instance was
     * created ([FaceMasker]). The message is "Model rejected: " followed by the [findings] codes joined by commas; it
     * never contains a path, a file name or model content.
     */
    public class ModelRejected internal constructor(findings: List<String>) :
        FaceMaskException("Model rejected: ${findings.distinct().joinToString(",")}") {
        /**
         * The finding codes, each at most once, in the order they were found, never empty. The 0.1.0 codes are
         * `EMPTY`, `TOO_LARGE`, `NOT_REGULAR_FILE`, `NOT_ONNX`, `NESTING_TOO_DEEP`, `EXTERNAL_DATA`, `INPUT_COUNT`,
         * `INPUT_TYPE`, `INPUT_SHAPE`, `OUTPUT_MISSING`, `OUTPUT_TYPE`, `OUTPUT_SHAPE`, `RUNTIME_REJECTED`,
         * `IO_MISMATCH`, `CONTRACT_OUTPUT`, `CONTRACT_VALUES` and `TIMEOUT`. A code is never renamed, removed or given
         * another meaning without a breaking-change entry in the changelog, but new codes may be added in any release:
         * treat every code, known or not, as a failure and do not match the list exhaustively.
         */
        public val findings: List<String> = findings.distinct().readOnly()
    }

    /**
     * A detector pass produced output that fails the per-pass checks (types; shapes, which must have the rank and
     * every static dimension the model file declares; finite values, probabilities, box edges and bounds; caps), or
     * the pass failed in the runtime. Nothing was written: an in-memory image and a
     * file target are unchanged. A broken box is never dropped and a list is never truncated; the call fails instead.
     * The message is "Model output invalid: " followed by the [findings] codes joined by commas; it never contains
     * coordinates, a path or model content. With `TOO_MANY_MASKS` a sentence follows that names the number of masks
     * of the plan and the profile's score threshold (and, for the summed working area, that area and its limit in
     * pixels) and says that the threshold is very likely too low for this model; check it with faceblur-eval. With
     * `TOO_MANY_NMS_PAIR_CHECKS` a sentence of the same kind follows: the number of box pair checks the in-pass
     * duplicate removal reached (one above the limit of 240,000,000 per photo, where it stopped), the profile's score
     * threshold and the same advice.
     */
    public class ModelOutputInvalid internal constructor(findings: List<String>, detail: String? = null) :
        FaceMaskException(
            "Model output invalid: ${findings.distinct().joinToString(",")}" + (detail?.let { ". $it" } ?: ""),
        ) {
        /**
         * The finding codes, each at most once, in the order they were found, never empty. The 0.1.0 codes are
         * `RUN_FAILED`, `RUN_TIMEOUT`, `OUTPUT_TYPE`, `OUTPUT_SHAPE`, `NON_FINITE`, `SCORE_RANGE`, `BOX_INVERTED`,
         * `BOX_BOUNDS`, `TOO_MANY_ROWS`, `TOO_MANY_ELEMENTS`, `TOO_MANY_DETECTIONS`, `TOO_MANY_MERGE_CANDIDATES`
         * (either bound of the merge across passes: the number of candidates or the merge work),
         * `TOO_MANY_NMS_PAIR_CHECKS` (the in-pass duplicate removal compared more than 240,000,000 pairs of boxes in
         * one photo) and `TOO_MANY_MASKS`
         * (either per-image mask limit: more than 65,536 masks, or a summed mask working area above 32 times the
         * image area, at least 32 times 1,048,576 pixels). The same stability rules as for [ModelRejected.findings]
         * apply.
         */
        public val findings: List<String> = findings.distinct().readOnly()
    }

    /** No ONNX Runtime binding is available on this platform or in this process (see the integration guide). */
    public class RuntimeUnavailable internal constructor(reason: String, cause: Throwable? = null) :
        FaceMaskException("ONNX Runtime unavailable: $reason", cause)

    /**
     * A second call entered the same [FaceMasker] while a call was running. Use one instance per worker thread;
     * the running call is not affected.
     */
    public class ConcurrentUse internal constructor() :
        FaceMaskException("FaceMasker is already in use by another call; use one instance per worker")

    /** The [FaceMasker] was closed. */
    public class Closed internal constructor() : FaceMaskException("FaceMasker is closed")

    /**
     * The call was cancelled through its [CancellationToken]. If cancellation was seen before the finished masks were
     * copied into the image (during detection or while the masks were computed), the image is untouched. If it was
     * requested during that final copy, every mask of the plan was copied first (the image is fully masked) and then
     * this error is thrown. An image is never half-masked by a cancellation.
     */
    public class Cancelled internal constructor() : FaceMaskException("Cancelled")

    /**
     * Your image failed while the finished masks were copied into it: its `writePixels` or `readPixels` threw, or it
     * did not return the written pixels. The image may be partly masked and must be discarded.
     */
    public class MaskWriteFailed internal constructor(cause: Throwable? = null) :
        FaceMaskException("Writing masks into the image failed after it started; discard the image", cause)

    /**
     * The call needed more memory than was available, and the image was not masked; it is untouched. Do not publish,
     * upload or share the image or the original instead, as if it were masked. Retry later, with less work running
     * in parallel, or with a smaller image.
     */
    public class NotEnoughMemory internal constructor(cause: Throwable? = null) :
        FaceMaskException("Not enough memory to mask the image; it was not masked", cause)

    /**
     * An unexpected failure of the library itself (a FaceBlur bug), not of the model or its profile: those are
     * [ModelRejected], [ModelOutputInvalid] and [ModelIntegrity]. Discard the image.
     */
    public class Internal internal constructor(reason: String, cause: Throwable? = null) :
        FaceMaskException("Internal error: $reason", cause)
}

/** Maps an internal core failure to the public error (ADR 0002 decision 9). */
internal fun CoreFailure.toPublic(): FaceMaskException = when (this) {
    is CoreFailure.InvalidSetting -> FaceMaskException.InvalidConfiguration(parameter, "see the KDoc of $parameter")

    is CoreFailure.ImageTooLarge -> FaceMaskException.ImageTooLarge(width, height, Int.MAX_VALUE.toLong())

    is CoreFailure.WriteNotApplied -> FaceMaskException.MaskWriteFailed()

    is CoreFailure.WriteInterrupted -> FaceMaskException.MaskWriteFailed(cause)

    is CoreFailure.MaskWorkLimit -> FaceMaskException.InvalidConfiguration("masks", MASK_LIMITS)

    is CoreFailure.NotEnoughMemory -> FaceMaskException.NotEnoughMemory(cause)

    is CoreFailure.InvalidDetection,
    is CoreFailure.InvalidFaceBox,
    is CoreFailure.InvalidMask,
    -> FaceMaskException.Internal(message ?: "core failure", this)
}

/**
 * As [toPublic], but nothing is attached: file mode's rule (the masked image there is a platform buffer, whose
 * exceptions can carry platform text such as a path). The same type and message as [toPublic], without a cause.
 */
internal fun CoreFailure.toPublicWithoutCause(): FaceMaskException = when (this) {
    is CoreFailure.WriteInterrupted -> FaceMaskException.MaskWriteFailed()

    is CoreFailure.NotEnoughMemory -> FaceMaskException.NotEnoughMemory()

    is CoreFailure.InvalidDetection,
    is CoreFailure.InvalidFaceBox,
    is CoreFailure.InvalidMask,
    -> FaceMaskException.Internal(message ?: "core failure")

    is CoreFailure.InvalidSetting,
    is CoreFailure.ImageTooLarge,
    is CoreFailure.WriteNotApplied,
    is CoreFailure.MaskWorkLimit,
    -> toPublic()
}

/** How [FaceMaskException.InvalidConfiguration] names the per-image mask limits (ADR 0013 decision 2). */
internal val MASK_LIMITS: String =
    "at most ${grouped(MaskStageLimits.MAX_MASKS_PER_IMAGE.toLong())} masks, and a summed mask working area of at " +
        "most ${MaskStageLimits.MAX_MASK_AREA_FACTOR} times max(image pixels, " +
        "${grouped(MaskStageLimits.MIN_AREA_BASE)})"

/**
 * The actionable texts of the per-image mask limits (ADR 0012 decision 7, ADR 0013 decision 2) and of the in-pass NMS
 * work bound. Numbers only: the mask or pair-check count, the score threshold and pixel areas, never a coordinate, a
 * path or image content.
 */
internal object MaskLimitText {
    private const val ADVICE =
        "the score threshold is very likely too low for this model; check it with faceblur-eval."

    /** `TOO_MANY_MASKS` from the count limit: [masks] masks of the model's plan at [threshold], above [limit]. */
    fun count(masks: Long, limit: Int, threshold: Float): String =
        "${masks(masks)} at score threshold $threshold ${verb(masks, "exceeds", "exceed")} the limit of " +
            "${grouped(limit.toLong())}: $ADVICE"

    /** `TOO_MANY_MASKS` from the work limit: the summed working [area] of [masks] masks above [limit]. */
    fun area(masks: Int, threshold: Float, area: Long, limit: Long): String =
        "${masks(masks.toLong())} at score threshold $threshold ${verb(masks.toLong(), "has", "have")} a summed " +
            "working area of ${grouped(area)} pixels, above the limit of ${grouped(limit)}: $ADVICE"

    /**
     * `TOO_MANY_NMS_PAIR_CHECKS`: the in-pass duplicate removal of one photo stopped at [pairChecks] pair checks
     * (one above [limit]) at [threshold]. The same advice as the mask limits: such work comes from far too many boxes.
     */
    fun nmsPairChecks(pairChecks: Long, limit: Long, threshold: Float): String =
        "${if (pairChecks == 1L) "1 box pair check" else "${grouped(pairChecks)} box pair checks"} of the " +
            "duplicate removal at score threshold $threshold ${verb(pairChecks, "exceeds", "exceed")} the limit of " +
            "${grouped(limit)}: $ADVICE"

    /** `InvalidConfiguration("masks")` for an app's plan of [masks] masks (count limit). */
    fun planCount(masks: Int): String = "$MASK_LIMITS; the plan has ${masks(masks.toLong())}"

    /** `InvalidConfiguration("masks")` for an app's plan whose summed working [area] is above [limit]. */
    fun planArea(masks: Int, area: Long, limit: Long): String =
        "$MASK_LIMITS; the plan has ${masks(masks.toLong())} with a summed working area of ${grouped(area)} " +
            "pixels, above the limit of ${grouped(limit)}"

    private fun masks(n: Long): String = if (n == 1L) "1 mask" else "${grouped(n)} masks"

    /** The verb agreeing with a subject of [n] things: [singular] for exactly one, else [plural]. */
    private fun verb(n: Long, singular: String, plural: String): String = if (n == 1L) singular else plural
}

/** [value] with a comma between groups of three digits (fixed English format on every platform). */
internal fun grouped(value: Long): String {
    val digits = if (value < 0) (-value).toString() else value.toString()
    val out = StringBuilder()
    for (i in digits.indices) {
        if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
        out.append(digits[i])
    }
    return if (value < 0) "-$out" else out.toString()
}
