// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileModeRun
import io.github.shishark.faceblur.filemode.FileModeSteps
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.mask.StageSeams
import kotlin.jvm.JvmOverloads
import kotlin.time.TimeSource

/**
 * File mode with masks from your app's own detector, without a model: no [DetectorModel], no [FaceMasker] and no ONNX
 * Runtime are needed or used.
 *
 * IT DETECTS NOTHING. It masks exactly the masks your app gives it; every face outside your boxes stays visible in the
 * output, and [FileResult.faces] is empty.
 *
 * The route: [planFile] decodes the file like [FaceMasker.detectFile] and returns an empty plan of the working size,
 * bound to the decoded pixels; your detector reads the file AFTER [planFile]; you convert its boxes into the plan's
 * frame (see [planFile]) and add them with [Mask.manual] and [MaskPlan.withMasks]; [maskFile] writes them with this
 * instance's [masking] under the same rules as [FaceMasker.maskFile] (opaque masks, nothing written on any failure,
 * no source metadata unless [FileOptions.metadata] keeps some). A file that changes between the two calls is refused
 * with [FaceMaskException.SourceChanged]. Do not add masks saved earlier (for example before your app's process
 * ended) to a new plan without detecting again or showing them to the user on the current image.
 *
 * It holds only its immutable [masking]: no session, no native resource, nothing to close. It is thread-safe;
 * parallel calls are allowed, and each reserves its mask working area through the same process-wide memory check as
 * every other masking call. Every method blocks; call it off the main thread.
 */
public class PlanMasker
@JvmOverloads
constructor(
    /** The style, shape, feather and colour the masks are written with (their expansion is in each [Mask]). */
    public val masking: MaskingConfig = MaskingConfig(),
) {
    private val settings = masking.toSettings()

    /**
     * Decodes the image file at [source] (a file-system path) exactly as [FaceMasker.detectFile] and
     * [FaceMasker.maskFile] do for the same [options] (format from the first bytes, header checks, one decode at the
     * working size with the orientation applied), detects nothing and writes nothing. Returns an empty plan whose
     * [MaskPlan.width] and [MaskPlan.height] are the working size, bound to the decoded pixels. Blocks; call off the
     * main thread.
     *
     * THE FRAME OF YOUR BOXES. The plan's coordinates are pixels of the working image: the file turned upright by its
     * orientation tag (as a photo viewer shows it), top-left origin, scaled to the working size. A detector that
     * decodes the file without applying the tag (Android's `BitmapFactory`, a raw `CGImageSource` image without the
     * transform) gives boxes in the stored, unrotated frame; used as they are, the masks land in the wrong place while
     * [maskFile] reports success, so the faces stay visible. Convert such boxes with [UprightBox.fromRaw] (or let the
     * detector decode upright, for example Android's `ImageDecoder`), then scale them by
     * `plan.width / imageWidth` and `plan.height / imageHeight` and build the masks with [Mask.manual] and this
     * instance's [masking].
     *
     * @throws FaceMaskException.RuntimeUnavailable when this platform has no file-mode support installed; the source
     * is not opened.
     * @throws FaceMaskException.UnsupportedImage when the file is not a JPEG, HEIC or PNG image the platform decodes.
     * @throws FaceMaskException.ImageTooLarge when the source exceeds 200,000,000 pixels, or the working image exceeds
     * 65,535 px per side or [FileOptions.maxDecodedPixels]; nothing is decoded.
     * @throws FaceMaskException.IoFailed when the file cannot be read (the message never contains the path).
     * @throws FaceMaskException.Cancelled when [cancellation] was requested.
     */
    @JvmOverloads
    @Throws(FaceMaskException::class)
    public fun planFile(
        source: String,
        options: FileOptions = FileOptions(),
        cancellation: CancellationToken? = null,
    ): MaskPlan = planSource(FileSource.Path(source), options, cancellation, CallContext(null))

    /**
     * Writes the masks of [plan] (from [planFile] or `FaceMasker.detectFile` for this [source] and [options], edited
     * with [MaskPlan.withMasks]) into the image file at [source] and writes a new JPEG, rebuilt from pixels without the
     * source's metadata (see [FileOptions.metadata]), to [target]. Detects nothing: faces outside the plan's masks
     * stay visible. Blocks; call off the main thread.
     *
     * The steps, refusals, output rules and the result are those of [FaceMasker.maskFile] with a plan, and the masks
     * are written with the style, shape rule, feather and colour of [masking], exactly as [FaceMasker.maskFile] writes
     * the same plan with the same [MaskingConfig]. [FileResult.faces] is empty; [FileResult.masks] are the plan's.
     * NOTHING IS WRITTEN on any failure or cancellation.
     *
     * @throws FaceMaskException.UnsupportedTarget when [target] cannot be used for this call (as for
     * [FaceMasker.maskFile]).
     * @throws FaceMaskException.MaskPlanMismatch when [plan] has no binding to a file (it was built with the public
     * [MaskPlan] constructor; refused before the source is opened), or does not have the working size. Fix the code
     * that builds the plan.
     * @throws FaceMaskException.SourceChanged when the file's decoded pixels differ from those [plan] was made for;
     * nothing was masked or written. Call [planFile] and run your detector again on the current file and, if your app
     * shows the masks to the user for review, show the new result again before masking.
     * @throws FaceMaskException.InvalidConfiguration (`masks`) when the masks' summed working area exceeds 32 times
     * the image area (at least 32 times 1,048,576 pixels).
     * @throws FaceMaskException.NotEnoughMemory when the mask working area cannot be reserved; nothing was written.
     * @throws FaceMaskException.OutputValidationFailed when the encoded file fails the library's check.
     * @throws FaceMaskException.Cancelled when [cancellation] was requested; nothing was written. Other errors as
     * [planFile].
     */
    @JvmOverloads
    @Throws(FaceMaskException::class)
    public fun maskFile(
        source: String,
        target: OutputTarget,
        plan: MaskPlan,
        options: FileOptions = FileOptions(),
        cancellation: CancellationToken? = null,
    ): FileResult = maskSource(FileSource.Path(source), target, plan, options, cancellation, CallContext(null))

    /**
     * [planFile] for every source form (ADR 0015 decision 4): the common path form passes a path source and a
     * [CallContext] without a platform value; the platform forms (Android: the `Context` forms) pass their own source
     * and a context made for this call only. Synthetic on the JVM and Android ([HiddenFromJava]).
     */
    @HiddenFromJava
    internal fun planSource(
        source: FileSource,
        options: FileOptions,
        cancellation: CancellationToken?,
        call: CallContext,
    ): MaskPlan = fileCall {
        val codec = FileModeRun.codec()
        checkCancelled(cancellation)
        FileModeRun.plan(codec, source, options, call, steps(cancellation))
    }

    /** [maskFile] for every source form; see [planSource]. Synthetic on the JVM and Android ([HiddenFromJava]). */
    @HiddenFromJava
    internal fun maskSource(
        source: FileSource,
        target: OutputTarget,
        plan: MaskPlan,
        options: FileOptions,
        cancellation: CancellationToken?,
        call: CallContext,
    ): FileResult = fileCall {
        val start = TimeSource.Monotonic.markNow()
        val codec = FileModeRun.codec()
        val writer = FileModeRun.writer()
        FileModeRun.refuseEarly(source, target, plan, call, writer)
        checkCancelled(cancellation)
        FileModeRun.mask(
            codec,
            source,
            options,
            plan,
            call,
            steps(cancellation),
            start,
            checkTarget = { writer.check(target, source, call) },
            write = { writer.write(target, source, it, call) },
        )
    }

    /** The file-mode steps without a detector: [FileModeRun] calls `detect` only without a plan, which never happens. */
    private fun steps(cancellation: CancellationToken?) = FileModeSteps(
        detect = { throw FaceMaskException.Internal("PlanMasker detects nothing") },
        mask = { image, plan, _ ->
            MaskWrite.write(
                image,
                plan,
                settings,
                { checkCancelled(cancellation) },
                StageSeams(),
            ) { e -> MaskWrite.planWorkLimit(plan, e) }
        },
        checkCancelled = { checkCancelled(cancellation) },
    )

    /**
     * File mode's error rule ([FileModeRun.withoutDetails]): fixed text, nothing attached; anything that is not a
     * `FaceMaskException` becomes `Internal`. The JVM's fatal errors pass unchanged.
     */
    private inline fun <T> fileCall(block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        val mapped = FileModeRun.withoutDetails(e)
        val passes = mapped is FaceMaskException || isFatal(mapped)
        throw if (passes) mapped else FaceMaskException.Internal("unexpected failure")
    }

    private fun checkCancelled(cancellation: CancellationToken?) {
        if (cancellation?.isCancellationRequested == true) throw FaceMaskException.Cancelled()
    }
}
