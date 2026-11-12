// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.filemode.ImageFingerprint

/**
 * Time spent in each step of one call, in milliseconds. `null` means the call did not perform that step (in-memory
 * calls never decode or encode; [gcMs] is reported on iOS only). [totalMs] is the call's own work; the wall time a
 * caller sees is `totalMs` plus `gcMs` (if any) plus wrapper overhead, so performance measurements use a wall clock.
 */
public class Timings internal constructor(
    /**
     * Decoding the source file (file mode only): opening it, reading its header, the target pre-check of `maskFile`,
     * decoding the pixels with the orientation applied and, where a plan is bound or checked, the fingerprint of the
     * decoded pixels.
     */
    public val decodeMs: Double?,
    /**
     * Scaling the masked image down to a smaller output (file mode only); `null` when the call did not scale. The
     * orientation is applied while decoding (part of [decodeMs]).
     */
    public val orientScaleMs: Double?,
    /** Detection, all passes; 0 when the call did not detect (applying a plan). */
    public val detectMs: Double,
    /** Detector passes: 1 global pass plus one per tile; 0 when the call did not detect. */
    public val passes: Int,
    /** Writing the masks; 0 when the call wrote none (detect only). */
    public val maskMs: Double,
    /** Encoding the output, rebuilding and checking it (file mode only; `null` for `detectFile`). */
    public val encodeMs: Double?,
    /** The call's own work. */
    public val totalMs: Double,
    /** Garbage collection after the call (iOS only). */
    public val gcMs: Double?,
) {
    /** True when [other] is a [Timings] with the same values. */
    override fun equals(other: Any?): Boolean = other is Timings &&
        decodeMs == other.decodeMs &&
        orientScaleMs == other.orientScaleMs &&
        detectMs == other.detectMs &&
        passes == other.passes &&
        maskMs == other.maskMs &&
        encodeMs == other.encodeMs &&
        totalMs == other.totalMs &&
        gcMs == other.gcMs

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int =
        listOf(decodeMs, orientScaleMs, detectMs, passes, maskMs, encodeMs, totalMs, gcMs).hashCode()

    /** Debug text with every value. */
    override fun toString(): String =
        "Timings(decodeMs=$decodeMs, orientScaleMs=$orientScaleMs, detectMs=$detectMs, passes=$passes, " +
            "maskMs=$maskMs, encodeMs=$encodeMs, totalMs=$totalMs, gcMs=$gcMs)"
}

/**
 * Result of detection: the faces found in an image of [width] × [height] pixels, in that image's pixel coordinates
 * (top-left origin). Nothing was written to the image.
 *
 * A detection from `detectFile` is bound to the decoded pixels of the file (file mode): the plans built from it
 * ([toMaskPlan]) carry that binding, and `maskFile` refuses them for a file whose pixels changed since
 * ([FaceMaskException.SourceChanged]). The binding is not part of [equals], [hashCode] or [toString]. A detection from
 * the in-memory `detect` has no file binding; it records the view of the pixels it read (the orientation of an iOS
 * buffer wrapper, upright for every other image), and its plans are applied only through the same view (see
 * [FaceMasker.applyMasks]).
 */
public class Detection private constructor(
    /** Width of the analysed image in pixels. */
    public val width: Int,
    /** Height of the analysed image in pixels. */
    public val height: Int,
    faces: List<FaceBox>,
    /** Time per step. */
    public val timings: Timings,
    /** True when the source carried an HDR gain map (Android 14+ file mode only; always false here). */
    public val hasGainMap: Boolean,
    /**
     * The [MaskingConfig] of the [FaceMasker] that produced this detection, with the expansion raised to the model
     * profile's minimum where that is larger; [toMaskPlan] uses it.
     */
    public val masking: MaskingConfig,
    warnings: List<String>,
    /** Part masks of merged faces (internal; drawn by [toMaskPlan]), grouped by face in face order. */
    internal val parts: List<FacePart>,
    /** Indexes into [faces] of the faces drawn as one rectangle cover (internal; drawn by [toMaskPlan]). */
    internal val covers: Set<Int>,
    /**
     * Width of the image a file-mode call writes for this source and these options: equal to [width] in in-memory
     * mode, smaller after `detectFile` when `FileOptions.maxOutputLongSide` scales the output down. Coordinates and
     * the plan stay in pixels of [width] × [height]; a preview of the output maps them with `outputWidth / width`.
     */
    public val outputWidth: Int,
    /** Height of the image a file-mode call writes; equal to [height] in in-memory mode (see [outputWidth]). */
    public val outputHeight: Int,
    /** The binding to the decoded file (file mode only; ADR 0015 decision 6). Synthetic on the JVM and Android. */
    @get:HiddenFromJava internal val fingerprint: ImageFingerprint?,
    /**
     * The view of the pixels the in-memory `detect` read ([PlanView.viewOf]); `null` in file mode. Synthetic on the
     * JVM and Android.
     */
    @get:HiddenFromJava internal val view: Int?,
) {
    /** A detection without a file binding (in-memory mode with [view], and file mode before [boundTo]). */
    internal constructor(
        width: Int,
        height: Int,
        faces: List<FaceBox>,
        timings: Timings,
        hasGainMap: Boolean,
        masking: MaskingConfig,
        warnings: List<String>,
        parts: List<FacePart> = emptyList(),
        covers: Set<Int> = emptySet(),
        outputWidth: Int = width,
        outputHeight: Int = height,
        view: Int? = null,
    ) : this(
        width,
        height,
        faces,
        timings,
        hasGainMap,
        masking,
        warnings,
        parts,
        covers,
        outputWidth,
        outputHeight,
        null,
        view,
    )

    /**
     * This detection bound to [fingerprint], the decoded image of `detectFile` (ADR 0015 decision 6). Synthetic on the
     * JVM and Android ([HiddenFromJava]), so Java code cannot bind a detection to anything.
     */
    @HiddenFromJava
    internal fun boundTo(fingerprint: ImageFingerprint): Detection = Detection(
        width,
        height,
        faces,
        timings,
        hasGainMap,
        masking,
        warnings,
        parts,
        covers,
        outputWidth,
        outputHeight,
        fingerprint,
        view,
    )

    /**
     * Non-blocking warning codes for this call's working size (the long side of the analysed image), usually empty.
     * The 0.1.0 code is `SMALL_INPUT`: medium-size faces may be missed between the tile passes and the whole-photo
     * pass; measure with faceblur-eval. New codes may be added in any release; read-only.
     */
    public val warnings: List<String> = warnings.readOnly()

    /**
     * The detected faces (final merged boxes, one per face), in pixels of the analysed image; read-only. The mask
     * plan ([toMaskPlan]) can hold more masks than this list has faces (part masks).
     */
    public val faces: List<FaceBox> = faces.readOnly()

    /**
     * The plan built with [masking]: exactly what `maskInPlace` would write. It holds one mask per face and, for a
     * face that crosses a tile border, may hold part masks of that face right after the face's own mask: earlier,
     * smaller states of the merged box whose masks stick out of the face's mask. They keep the rule that a lower
     * score threshold never removes or shrinks a mask, so a plan can hold more masks than [faces] has entries. An
     * editor that removes one face's mask should remove its part masks too; [Mask.fromFace] alone builds only the
     * face's own mask.
     *
     * A face whose box grew in more than 32 merge steps (usually only at very low score thresholds or with unusual
     * model output) gets no part masks: it is covered by one [MaskShape.RECTANGLE] mask, whatever shape [masking]
     * asks for. The rectangle holds the face's expanded mask and every earlier state, snapped outwards to whole
     * pixels; it is about 27 % larger than the face's ellipse.
     *
     * After `detectFile` the plan is bound to the file's decoded pixels: `maskFile` accepts it (also edited with
     * [MaskPlan.withMasks]) only for the same, unchanged pixels. After the in-memory `detect` the plan is bound to the
     * view the detection read: [FaceMasker.applyMasks] accepts it (also edited) only through the same view.
     */
    @Throws(FaceMaskException::class)
    public fun toMaskPlan(): MaskPlan = toMaskPlan(masking)

    /**
     * The plan built with another [masking] on purpose; the same masks as [toMaskPlan], part masks included, and the
     * same binding.
     */
    @Throws(FaceMaskException::class)
    public fun toMaskPlan(masking: MaskingConfig): MaskPlan {
        val masks = ArrayList<Mask>(faces.size + parts.size)
        var p = 0
        for ((i, face) in faces.withIndex()) {
            masks += if (i in covers) Mask.cover(face, masking) else Mask.fromFace(face, masking)
            while (p < parts.size && parts[p].face == i) masks += Mask.fromFace(parts[p++].box, masking)
        }
        return MaskPlan.bound(width, height, masks, fingerprint, view)
    }

    /** True when [other] is a [Detection] with the same values (the file binding is not compared). */
    override fun equals(other: Any?): Boolean = other is Detection &&
        width == other.width &&
        height == other.height &&
        faces == other.faces &&
        timings == other.timings &&
        hasGainMap == other.hasGainMap &&
        masking == other.masking &&
        warnings == other.warnings &&
        parts == other.parts &&
        covers == other.covers &&
        outputWidth == other.outputWidth &&
        outputHeight == other.outputHeight

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int =
        listOf(width, height, faces, timings, hasGainMap, masking, warnings, parts, covers, outputWidth, outputHeight)
            .hashCode()

    /** Debug text with the sizes, the number of faces and the timings (no coordinates). */
    override fun toString(): String = "Detection(width=$width, height=$height, outputWidth=$outputWidth, " +
        "outputHeight=$outputHeight, faces=${faces.size}, timings=$timings)"
}

/**
 * Result of writing masks into an image of [width] × [height] pixels. Every mask in [masks] was written fully opaque.
 */
public class MaskResult internal constructor(
    /** Width of the masked image in pixels. */
    public val width: Int,
    /** Height of the masked image in pixels. */
    public val height: Int,
    /** Faces detected by this call (`maskInPlace`); empty when a given plan was applied (`applyMasks`). */
    public val faces: List<FaceBox>,
    /**
     * The masks that were written, in order. After detection (`maskInPlace`) these are the face masks and the part
     * masks of merged faces, so there can be more masks than [faces] (see [Detection.toMaskPlan]).
     */
    public val masks: List<Mask>,
    /** Time per step. */
    public val timings: Timings,
    /** True when an HDR gain map was removed (Android 14+ only; always false here). */
    public val gainMapRemoved: Boolean,
    warnings: List<String>,
) {
    /**
     * Non-blocking warning codes for this call's working size, usually empty; always empty when no detection ran
     * (a given plan was applied). See [Detection.warnings]. Read-only.
     */
    public val warnings: List<String> = warnings.readOnly()

    /** True when [other] is a [MaskResult] with the same values. */
    override fun equals(other: Any?): Boolean = other is MaskResult &&
        width == other.width &&
        height == other.height &&
        faces == other.faces &&
        masks == other.masks &&
        timings == other.timings &&
        gainMapRemoved == other.gainMapRemoved &&
        warnings == other.warnings

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int = listOf(width, height, faces, masks, timings, gainMapRemoved, warnings).hashCode()

    /** Debug text with the size, the counts and the timings (no coordinates). */
    override fun toString(): String =
        "MaskResult(width=$width, height=$height, faces=${faces.size}, masks=${masks.size}, timings=$timings)"
}

/**
 * Result of a file-mode `maskFile` call: the masked JPEG was written to the target, [outputBytes] bytes, rebuilt from
 * pixels with no source metadata except what `FileOptions.metadata` names.
 *
 * Three sizes, all with the orientation applied: the source ([sourceWidth] × [sourceHeight], before any scaling), the
 * working image that detection and masking ran on ([width] × [height]; the coordinates of [faces] and [masks] refer to
 * it, and a plan for `maskFile` must have this size) and the written file ([outputWidth] × [outputHeight]). The file
 * was scaled when the output size differs from the source size.
 */
public class FileResult internal constructor(
    /** Width of the working image in pixels (detection, masks and coordinates). */
    public val width: Int,
    /** Height of the working image in pixels. */
    public val height: Int,
    /** Width of the source in pixels, orientation applied, before any scaling. */
    public val sourceWidth: Int,
    /** Height of the source in pixels, orientation applied, before any scaling. */
    public val sourceHeight: Int,
    /** Width of the written file in pixels. */
    public val outputWidth: Int,
    /** Height of the written file in pixels. */
    public val outputHeight: Int,
    /** Size of the written file in bytes. */
    public val outputBytes: Long,
    faces: List<FaceBox>,
    masks: List<Mask>,
    /** Time per step. */
    public val timings: Timings,
    /** True when the source carried an HDR gain map; it was not written (Android 14+ only; otherwise false). */
    public val gainMapRemoved: Boolean,
    warnings: List<String>,
) {
    /**
     * Faces detected by this call, in pixels of the working image; empty when a given plan was applied. Read-only.
     */
    public val faces: List<FaceBox> = faces.readOnly()

    /**
     * The masks that were written, in order, in pixels of the working image (see [MaskResult.masks]); read-only.
     */
    public val masks: List<Mask> = masks.readOnly()

    /** Non-blocking warning codes for this call's working size, usually empty (see [Detection.warnings]). Read-only. */
    public val warnings: List<String> = warnings.readOnly()

    /** True when [other] is a [FileResult] with the same values. */
    override fun equals(other: Any?): Boolean = other is FileResult &&
        width == other.width &&
        height == other.height &&
        sourceWidth == other.sourceWidth &&
        sourceHeight == other.sourceHeight &&
        outputWidth == other.outputWidth &&
        outputHeight == other.outputHeight &&
        outputBytes == other.outputBytes &&
        faces == other.faces &&
        masks == other.masks &&
        timings == other.timings &&
        gainMapRemoved == other.gainMapRemoved &&
        warnings == other.warnings

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int = listOf(
        width,
        height,
        sourceWidth,
        sourceHeight,
        outputWidth,
        outputHeight,
        outputBytes,
        faces,
        masks,
        timings,
        gainMapRemoved,
        warnings,
    ).hashCode()

    /** Debug text with the sizes, the counts and the timings (no coordinates, no path). */
    override fun toString(): String = "FileResult(width=$width, height=$height, sourceWidth=$sourceWidth, " +
        "sourceHeight=$sourceHeight, outputWidth=$outputWidth, outputHeight=$outputHeight, " +
        "outputBytes=$outputBytes, faces=${faces.size}, masks=${masks.size}, timings=$timings)"
}

/** One part mask of a merged face: the box of an earlier state of face number [face] of a [Detection]. */
internal class FacePart(val face: Int, val box: FaceBox) {
    override fun equals(other: Any?): Boolean = other is FacePart && face == other.face && box == other.box

    override fun hashCode(): Int = 31 * face + box.hashCode()
}
