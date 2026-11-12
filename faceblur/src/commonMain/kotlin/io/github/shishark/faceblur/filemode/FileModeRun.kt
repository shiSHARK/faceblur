// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.Detection
import io.github.shishark.faceblur.FaceBox
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.FileResult
import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.OutputFormat
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PixelImage
import io.github.shishark.faceblur.Timings
import io.github.shishark.faceblur.detect.OutputCheckFailed
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.isFatal
import io.github.shishark.faceblur.toPublicWithoutCause
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * What the orchestration needs from the `FaceMasker` that runs the call: the same internal detection and mask
 * functions as in-memory mode (ADR 0002 decision 7), and the call's cancellation check.
 */
internal class FileModeSteps(
    /** `detect` on the working image (cancellation before detection and between passes). */
    val detect: (PixelImage) -> Detection,
    /** The bounded mask stage on the working image; returns its time. `fromModel`: the plan came from [detect]. */
    val mask: (image: MutablePixelImage, plan: MaskPlan, fromModel: Boolean) -> Double,
    /** Throws `FaceMaskException.Cancelled` when cancellation was requested. */
    val checkCancelled: () -> Unit,
)

/**
 * The common file-mode orchestration (ADR 0015 decision 4; ADR 0005 decision 1). Steps, in this order:
 *
 * 1. The platform's file-mode parts: without a codec (and, for masking, a writer) the call fails with
 *    `RuntimeUnavailable` before the source is opened ([codec], [writer]).
 * 2. Target refusals that need no I/O, then a plan without a binding, before the source is opened ([refuseEarly]).
 * 3. Open the source: the codec checks the format from the first bytes and reads the header only.
 * 4. Plan the sizes from the header ([FileSizing.plan]: 200 MP source ceiling, 65,535 px per side,
 *    `maxDecodedPixels` on the working size); nothing is decoded yet.
 * 5. The writer's target pre-check ([TargetWriter.check]) and the plan-size check (`MaskPlanMismatch`).
 * 6. Cancellation check; decode once at the working size; close the opened source and release a stream buffer;
 *    check the decoded size.
 * 7. The fingerprint of the working image ([ImageFingerprint], ADR 0015 decision 6), with a cancellation check
 *    before and after it: `detectFile` binds its detection to it and `planFile` its empty plan ([plan] ends here);
 *    `maskFile` with a plan refuses other pixels with `SourceChanged`. Without a plan none is computed.
 * 8. Detect (no plan) and mask through the same functions as in-memory mode.
 * 9. Scale down (only to a smaller output), encode, rebuild and validate ([JpegOutput.finish]).
 * 10. Last cancellation check; write once; build the result.
 *
 * Nothing is written on any failure or cancellation: the only write is step 10's, after every check. Platform code
 * only reads, decodes, scales, encodes and writes. Every size a platform part returns (the decoded image, the scaled
 * image and the frame of the encoded JPEG) is checked against the plan, and a mismatch fails closed (`Internal`)
 * before anything is written.
 *
 * Errors carry fixed text only ([withoutDetails]): no exception of a platform part is attached to what the call
 * throws, neither as cause nor as suppressed exception, because its text can contain a path or a URI.
 */
internal object FileModeRun {
    // Every member that takes or returns a seam type is synthetic on the JVM and Android ([HiddenFromJava]); the JVM
    // boundary test checks it.

    /** The platform's decoder; `RuntimeUnavailable` where file mode has no parts (the JVM; iOS until its stories). */
    @HiddenFromJava
    fun codec(): ImageCodec = FaceBlurRuntime.imageCodec() ?: throw unavailable()

    /** The platform's writer; `RuntimeUnavailable` as [codec]. */
    @HiddenFromJava
    fun writer(): TargetWriter = FaceBlurRuntime.targetWriter() ?: throw unavailable()

    private fun unavailable() = FaceMaskException.RuntimeUnavailable(
        "file mode is not available on this platform (no image decoder or file writer is installed)",
    )

    /**
     * The error rule of every file-mode call (ADR 0002 decision 9): what the call throws carries fixed text and no
     * attached exception. Platform parts throw `FaceMaskException`s with fixed reasons (see the seam); anything else
     * they throw, and a `FaceMaskException` that carries a cause or suppressed exceptions (a platform part that broke
     * that rule), becomes `Internal` without the original, so no platform text (a path, a URI) reaches the app through
     * the cause chain. Core failures are mapped without their cause. The JVM's fatal errors pass unchanged.
     */
    @HiddenFromJava
    fun withoutDetails(e: Throwable): Throwable = when {
        isFatal(e) -> e

        e is CoreFailure -> e.toPublicWithoutCause()

        e.cause != null || e.suppressedExceptions.isNotEmpty() ->
            FaceMaskException.Internal(if (e is FaceMaskException) "a platform part failed" else "unexpected failure")

        e is FaceMaskException || e is OutputCheckFailed -> e

        else -> FaceMaskException.Internal("unexpected failure")
    }

    /**
     * The refusals that need no I/O, before the source is opened (ADR 0015 decisions 2, 4 and 6): a
     * [OutputTarget.PlatformTarget] without a platform context (the common path form) or whose value is not the
     * platform's own type; [OutputTarget.OverwriteSource] for a source that cannot be replaced in one step (a content
     * URI, or a stream); a [OutputTarget.NewFile] at the source path without `replaceExisting` (lexical comparison of
     * the absolute, normalised paths; the writer's no-replace publication stays the authoritative guard); then a
     * [plan] without a binding to a decoded file (`MaskPlanMismatch` with its fixed text).
     */
    @HiddenFromJava
    fun refuseEarly(
        source: FileSource,
        target: OutputTarget,
        plan: MaskPlan?,
        call: CallContext,
        writer: TargetWriter,
    ) {
        refuseTarget(source, target, call, writer)
        if (plan != null && plan.fingerprint == null) throw FaceMaskException.MaskPlanMismatch.unbound()
    }

    private fun refuseTarget(source: FileSource, target: OutputTarget, call: CallContext, writer: TargetWriter) {
        when (target) {
            is OutputTarget.PlatformTarget -> {
                if (call.platform == null) {
                    throw FaceMaskException.UnsupportedTarget(
                        "a platform target needs the platform form of this call (on Android the form that takes " +
                            "a Context)",
                    )
                }
                if (!writer.accepts(target.value)) {
                    throw FaceMaskException.UnsupportedTarget("the target was not made by this platform's factories")
                }
            }

            OutputTarget.OverwriteSource -> when (source) {
                is FileSource.Path -> Unit

                is FileSource.Platform -> if (!source.value.replaceableInOneStep) {
                    throw FaceMaskException.UnsupportedTarget(
                        "this source cannot be replaced in one step; to replace it anyway, use the explicit " +
                            "opt-in AndroidOutputTargets.overwriteNonAtomic",
                    )
                }

                is FileSource.Bytes -> throw FaceMaskException.UnsupportedTarget(
                    "a stream source has no file to replace",
                )
            }

            is OutputTarget.NewFile -> {
                val same = source is FileSource.Path && !target.replaceExisting &&
                    normalised(writer.absolutePath(target.path)) == normalised(writer.absolutePath(source.path))
                if (same) {
                    throw FaceMaskException.UnsupportedTarget(
                        "the target path is the source path; use OverwriteSource, or NewFile with replaceExisting",
                    )
                }
            }
        }
    }

    /**
     * `detectFile`: steps 1 and 3-8 (no target, nothing written). The [Detection] is in working-image pixels, carries
     * the output size of the plan and is bound to the working image's fingerprint, so its `toMaskPlan()` fits
     * `maskFile` with the same source and options as long as the source is unchanged.
     */
    @HiddenFromJava
    fun detect(
        codec: ImageCodec,
        source: FileSource,
        options: FileOptions,
        call: CallContext,
        steps: FileModeSteps,
        start: TimeSource.Monotonic.ValueTimeMark,
    ): Detection {
        var found: Detection? = null
        var bound: ImageFingerprint? = null
        // detectFile writes nothing, so it never asks the platform for the source's metadata.
        val decoded =
            decoded(codec, source, options, call, steps, readMetadata = false, true, {}) { working, _, print ->
                bound = print
                found = steps.detect(working.pixels)
            }
        val d = requireNotNull(found)
        val t = Timings(
            decoded.decodeMs,
            null,
            d.timings.detectMs,
            d.timings.passes,
            0.0,
            null,
            elapsedMs(start),
            null,
        )
        return Detection(
            d.width,
            d.height,
            d.faces,
            t,
            decoded.hasGainMap,
            d.masking,
            d.warnings,
            d.parts,
            d.covers,
            decoded.sizes.output.width,
            decoded.sizes.output.height,
        ).boundTo(requireNotNull(bound))
    }

    /**
     * `planFile`: steps 1, 3, 4, 6 and 7 with the decoder settings of `detectFile` (no detection, no target, nothing
     * written). Returns an empty plan of the working size bound to the working image's fingerprint, in the frame of
     * every file-mode plan (upright after the orientation tag, top-left origin).
     */
    @HiddenFromJava
    fun plan(
        codec: ImageCodec,
        source: FileSource,
        options: FileOptions,
        call: CallContext,
        steps: FileModeSteps,
    ): MaskPlan {
        var bound: ImageFingerprint? = null
        val decoded = decoded(codec, source, options, call, steps, readMetadata = false, true, {}) { _, _, print ->
            bound = print
        }
        val working = decoded.sizes.working
        return MaskPlan.bound(working.width, working.height, emptyList(), requireNotNull(bound), null)
    }

    /**
     * `maskFile` and the stream form: steps 3-10 (the caller ran steps 1 and 2). [checkTarget] is the writer's target
     * pre-check (step 5; nothing for the stream form) and [write] the single write (step 10). [plan] `null` detects;
     * otherwise the plan is applied instead of detection: it must have the working size, and the working image must
     * have the plan's fingerprint (`SourceChanged` otherwise, before the mask stage).
     */
    @HiddenFromJava
    fun mask(
        codec: ImageCodec,
        source: FileSource,
        options: FileOptions,
        plan: MaskPlan?,
        call: CallContext,
        steps: FileModeSteps,
        start: TimeSource.Monotonic.ValueTimeMark,
        checkTarget: () -> Unit,
        write: (ByteArray) -> Unit,
    ): FileResult {
        var masked: Masked? = null
        val check: (SizePlan) -> Unit = { sizes ->
            checkTarget()
            if (plan != null && (plan.width != sizes.working.width || plan.height != sizes.working.height)) {
                throw FaceMaskException.MaskPlanMismatch(
                    plan.width,
                    plan.height,
                    sizes.working.width,
                    sizes.working.height,
                )
            }
        }
        val readMetadata = options.metadata is MetadataPolicy.KeepAllowlist
        val decoded =
            decoded(codec, source, options, call, steps, readMetadata, plan != null, check) { w, sizes, print ->
                masked = maskAndEncode(w, sizes, options, plan, print, steps)
            }
        val m = requireNotNull(masked)
        val encodeMark = TimeSource.Monotonic.markNow()
        val output = JpegOutput.finish(m.encoded, decoded.metadata, options.metadata, decoded.sizes.output)
        val encodeMs = m.encodeMs + elapsedMs(encodeMark)
        steps.checkCancelled()
        write(output.bytes)
        val t = Timings(
            decoded.decodeMs,
            m.scaleMs,
            m.detectMs,
            m.passes,
            m.maskMs,
            encodeMs,
            elapsedMs(start),
            null,
        )
        return FileResult(
            decoded.sizes.working.width,
            decoded.sizes.working.height,
            decoded.sizes.source.width,
            decoded.sizes.source.height,
            // The size of the written frame, checked against the plan by JpegOutput.finish.
            output.size.width,
            output.size.height,
            output.bytes.size.toLong(),
            m.faces,
            m.plan.masks,
            t,
            decoded.hasGainMap,
            m.warnings,
        )
    }

    /**
     * What step 6 found out about the source; the working image itself lives only inside [decoded]'s block, and so
     * does its fingerprint (it is handed to the block, never stored here: this class's members are public in JVM
     * bytecode, and the JVM boundary test scans every class for members with the fingerprint type).
     */
    private class Decoded(
        val sizes: SizePlan,
        val hasGainMap: Boolean,
        val metadata: PhotoMetadata?,
        val decodeMs: Double,
    )

    private class Masked(
        val plan: MaskPlan,
        val faces: List<FaceBox>,
        val warnings: List<String>,
        val detectMs: Double,
        val passes: Int,
        val maskMs: Double,
        val scaleMs: Double?,
        val encoded: ByteArray,
        val encodeMs: Double,
    )

    /**
     * Steps 3, 4, 6, 7 and, through [beforeDecode], 5; then [block] on the working image, which is closed afterwards.
     * The opened source is closed and a stream buffer released before [block] runs, so the mask stage never runs
     * while the call holds the source's buffer. [readMetadata]: ask the platform for the source's metadata (only
     * when the output keeps some of it). [fingerprint]: compute the working image's fingerprint (step 7), with a
     * cancellation check before and after it; its time is part of the decode time.
     */
    private inline fun decoded(
        codec: ImageCodec,
        source: FileSource,
        options: FileOptions,
        call: CallContext,
        steps: FileModeSteps,
        readMetadata: Boolean,
        fingerprint: Boolean,
        beforeDecode: (SizePlan) -> Unit,
        block: (WorkingImage, SizePlan, ImageFingerprint?) -> Unit,
    ): Decoded {
        val mark = TimeSource.Monotonic.markNow()
        val opened = codec.open(source, call)
        val sizes: SizePlan
        val hasGainMap: Boolean
        val metadata: PhotoMetadata?
        val working: WorkingImage
        try {
            // The opened size has the orientation applied already.
            sizes = FileSizing.plan(opened.size.width, opened.size.height, 1, options)
            beforeDecode(sizes)
            steps.checkCancelled()
            hasGainMap = opened.hasGainMap
            // Read only when the policy keeps something: StripAll never asks the platform for metadata.
            metadata = if (readMetadata) opened.metadata else null
            working = opened.decode(sizes.working)
        } catch (t: Throwable) {
            closeAfterFailure(opened)
            throw t
        }
        val print: ImageFingerprint?
        try {
            opened.close()
            if (source is FileSource.Bytes) source.release()
            requireSize(working.pixels, sizes.working, "the decoder")
            print = if (fingerprint) {
                steps.checkCancelled()
                ImageFingerprint.of(working.pixels).also { steps.checkCancelled() }
            } else {
                null
            }
        } catch (t: Throwable) {
            closeAfterFailure(working)
            throw t
        }
        val result = Decoded(sizes, hasGainMap, metadata, elapsedMs(mark))
        closing(working) { block(it, sizes, print) }
        return result
    }

    /**
     * Steps 8 and 9 up to the encoder output, on the open working image; [print] is its fingerprint (computed when a
     * plan is [given]).
     */
    private fun maskAndEncode(
        working: WorkingImage,
        sizes: SizePlan,
        options: FileOptions,
        given: MaskPlan?,
        print: ImageFingerprint?,
        steps: FileModeSteps,
    ): Masked {
        // A plan is applied only to the pixels it was made for; a plan without a binding never gets here (step 2).
        if (given != null && !requireNotNull(print).matches(given.fingerprint)) throw FaceMaskException.SourceChanged()
        val found = if (given == null) steps.detect(working.pixels) else null
        val plan = given ?: requireNotNull(found).toMaskPlan()
        val maskMs = steps.mask(working.pixels, plan, found != null)
        val quality = when (val format = options.format) {
            is OutputFormat.Jpeg -> format.quality
        }
        var scaleMs: Double? = null
        var encodeMark = TimeSource.Monotonic.markNow()
        val encoded = if (sizes.scaleAfterMasking) {
            closing(working.scaledTo(sizes.output)) { scaled ->
                requireSize(scaled.pixels, sizes.output, "the scaler")
                scaleMs = elapsedMs(encodeMark)
                encodeMark = TimeSource.Monotonic.markNow()
                scaled.encodeJpeg(quality)
            }
        } else {
            working.encodeJpeg(quality)
        }
        return Masked(
            plan,
            found?.faces ?: emptyList(),
            found?.warnings ?: emptyList(),
            found?.timings?.detectMs ?: 0.0,
            found?.timings?.passes ?: 0,
            maskMs,
            scaleMs,
            encoded,
            elapsedMs(encodeMark),
        )
    }

    /** Fails closed when a platform part returned another size than planned. */
    private fun requireSize(image: PixelImage, expected: Size, part: String) {
        if (image.width != expected.width || image.height != expected.height) {
            throw FaceMaskException.Internal("$part returned another size than planned")
        }
    }

    /**
     * [block] on [resource], then closes it. Unlike `use`, a close failure after a failure of [block] is dropped,
     * not attached as a suppressed exception (its text could carry a path); the first failure is reported.
     */
    private inline fun <R : AutoCloseable, T> closing(resource: R, block: (R) -> T): T {
        val result = try {
            block(resource)
        } catch (t: Throwable) {
            closeAfterFailure(resource)
            throw t
        }
        resource.close()
        return result
    }

    /**
     * Closes [resource] after another failure, which is the one reported: a close failure never replaces it and is
     * not attached to it. Only the JVM's fatal errors pass.
     */
    private fun closeAfterFailure(resource: AutoCloseable) {
        try {
            resource.close()
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
        }
    }

    private fun elapsedMs(mark: TimeSource.Monotonic.ValueTimeMark): Double =
        mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
}

/**
 * [path] normalised lexically: empty and `.` components dropped, `..` removes the component before it (never above
 * the root), one `/` between components; a leading `/` is kept. No file-system lookup.
 */
internal fun normalised(path: String): String {
    val parts = ArrayList<String>()
    for (p in path.split('/')) {
        when {
            p.isEmpty() || p == "." -> Unit
            p == ".." && parts.isNotEmpty() && parts.last() != ".." -> parts.removeAt(parts.lastIndex)
            p == ".." && path.startsWith('/') -> Unit
            else -> parts += p
        }
    }
    return (if (path.startsWith('/')) "/" else "") + parts.joinToString("/")
}
