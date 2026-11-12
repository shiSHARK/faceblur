// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.DetectionSettings
import io.github.shishark.faceblur.detect.ModelDetector
import io.github.shishark.faceblur.detect.OutputCheckFailed
import io.github.shishark.faceblur.detect.TiledFaceDetector
import io.github.shishark.faceblur.filemode.BoundedRead
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileModeRun
import io.github.shishark.faceblur.filemode.FileModeSteps
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.StreamReader
import io.github.shishark.faceblur.filemode.StreamSink
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.image.PixelDetectionImage
import io.github.shishark.faceblur.internal.model.Warnings
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.RuntimeOptionFailed
import io.github.shishark.faceblur.internal.runtime.SessionRejected
import io.github.shishark.faceblur.mask.StageSeams
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.jvm.JvmOverloads
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * Detects faces with the app's model ([DetectorModel]) and covers them with opaque masks (data minimisation: masks
 * never use any pixel of the face they cover).
 *
 * Every method blocks; call it off the main thread. One call at a time per instance: a second call that enters while
 * one is running throws [FaceMaskException.ConcurrentUse] (it does not wait). For parallel work, create one instance
 * per worker from the same [DetectorModel]. Creating an instance opens one runtime session from the model's verified
 * bytes, compares the runtime's view of the model's inputs and outputs with the profile and runs one contract pass on
 * a uniform grey input; create it once per worker (off the main thread), reuse it, and [close] it when done. Calls
 * after [close] throw [FaceMaskException.Closed]. A Java caller passing `null` for the model gets Kotlin's
 * `NullPointerException` before any work.
 *
 * In-memory mode: [detect] reads an image, [applyMasks] writes a (possibly user-edited) [MaskPlan], [maskInPlace]
 * does both. These calls never decode, encode or touch metadata; the app decides what it encodes afterwards.
 *
 * File mode: [detectFile] and [maskFile] read an image file (JPEG, HEIC or PNG), decode it once at the working size
 * of [FileOptions], and [maskFile] writes a new JPEG rebuilt from the masked pixels to an [OutputTarget]; nothing is
 * written on any failure or cancellation. Platforms add their own forms (Android: content URIs and streams).
 *
 * Detected faces get masks whose expansion is, per axis, the larger of the profile's minimum
 * ([DetectorProfile.minMaskExpandX]/`Y`) and [MaskingConfig.expandX]/`Y`; [Detection.masking] carries it.
 *
 * @throws FaceMaskException.RuntimeUnavailable when no ONNX Runtime binding is installed for this platform or a
 * required session option cannot be set.
 * @throws FaceMaskException.ModelRejected when the runtime refuses the model, its view of the inputs and outputs
 * differs from the model file, or the contract pass fails (codes `RUNTIME_REJECTED`, `IO_MISMATCH`,
 * `CONTRACT_OUTPUT`, `CONTRACT_VALUES`, `TIMEOUT`).
 */
public class FaceMasker private constructor(
    /** The settings of this instance. */
    public val config: FaceMaskerConfig,
    private val profile: DetectorProfile,
    private val opened: OpenedSession,
    private val limits: DetectionSettings,
) : AutoCloseable {
    /** Creates a masker for [model] with [config]. */
    @JvmOverloads
    @Throws(FaceMaskException::class)
    public constructor(model: DetectorModel, config: FaceMaskerConfig = FaceMaskerConfig()) :
        this(config, model.profile, openSession(model, config, FaceBlurRuntime.runtime()), defaultLimits(model))

    /**
     * Report of this instance: the model report plus the runtime version read from the runtime, the execution provider
     * actually used and the FaceBlur version. FaceBlur never logs it.
     */
    public val modelInfo: MaskerModelInfo = opened.info

    private val session: ModelSession = opened.session
    private val detector = opened.detector

    /** The masking of detected faces: the instance's [MaskingConfig] with at least the profile's expansion. */
    private val effectiveMasking = MaskingConfig(
        style = config.masking.style,
        shape = config.masking.shape,
        expandX = maxOf(config.masking.expandX, profile.minMaskExpandX),
        expandY = maxOf(config.masking.expandY, profile.minMaskExpandY),
        feather = config.masking.feather,
        color = config.masking.color,
    )
    private val maskSettings = effectiveMasking.toSettings()
    private val busy = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val runnerReleased = AtomicBoolean(false)

    /**
     * Finds the faces in [image]; coordinates are pixels of [image] (top-left origin). Blocks; call off the main
     * thread. Never writes to [image].
     *
     * For photos, pass the image at a long side of at least 3,500 px (or its own size if smaller) and scale it down
     * further only after masking: detection quality depends on the face size in pixels. A custom [PixelImage] is
     * scaled with the library's area-averaging resampler, so its boxes can differ slightly from those of the
     * platform adapters. Each side may be at most 65,535 px.
     *
     * Outside the library's guarantee: drawing your own effect from these boxes instead of [applyMasks], and
     * scaling a photo below the detection size before calling FaceBlur.
     */
    @JvmOverloads
    @Throws(FaceMaskException::class)
    public fun detect(image: PixelImage, cancellation: CancellationToken? = null): Detection =
        guarded { detectNow(image, cancellation, TimeSource.Monotonic.markNow()) }

    /**
     * Optional self-check of your model and profile on your own test image: runs [detect] on [image] and reports, for
     * each face in [expectedFaces] (boxes in pixels of [image]), the best overlap (intersection over union) with any
     * detection, and whether every expected face was found with an overlap of at least [minIoU] (default 0.5).
     * Blocks; call off the main thread. Never writes to [image], never logs; the result goes only to you. FaceBlur
     * never calls it by itself and ships no test image for it.
     *
     * Why it exists: a wrong profile (colour order, pixel scaling, box format, coordinate space, a score threshold
     * too high for this model) can find no faces while creating the masker and masking still report success. FaceBlur
     * cannot tell this from the model file or its output. You are responsible for checking your setup: with this
     * helper, the demos' "Check profile" step, or faceblur-eval on your own photos.
     *
     * @throws FaceMaskException.InvalidConfiguration (`expectedFaces`) when [expectedFaces] is empty; (`minIoU`) when
     * [minIoU] is not greater than 0 or above 1. Other errors as [detect].
     */
    @JvmOverloads
    @Throws(FaceMaskException::class)
    public fun checkProfile(
        image: PixelImage,
        expectedFaces: List<ExpectedFace>,
        minIoU: Float = DEFAULT_MIN_IOU,
        cancellation: CancellationToken? = null,
    ): ProfileCheckResult = guarded {
        // Copied first: a list changed by another thread during the call cannot change what is checked.
        val expected = expectedFaces.toList()
        if (expected.isEmpty()) throw FaceMaskException.InvalidConfiguration("expectedFaces", "at least one face")
        if (!(minIoU > 0f && minIoU <= 1f)) {
            throw FaceMaskException.InvalidConfiguration("minIoU", "greater than 0, at most 1")
        }
        val found = detectNow(image, cancellation, TimeSource.Monotonic.markNow())
        ProfileCheckResult.of(found.faces, expected, minIoU)
    }

    /**
     * Writes every mask of [plan] into [image]; coordinates are pixels of [image]. Blocks; call off the main thread.
     * Does not touch metadata or encode: your encoder decides what is written to a file.
     *
     * All checks (image size, plan size, every mask, the mask limits), every allocation and the computation of every
     * mask run before the first pixel is written; then the finished masks are copied into [image]. If this call
     * throws a `FaceMaskException`, the image is untouched, with two exceptions. `MaskWriteFailed`: your image failed
     * while the finished masks were copied into it; discard it. `Cancelled` requested during that final copy: it is
     * reported after the copy, and the image is fully masked. If the call ends with any other error (for example an
     * `OutOfMemoryError` on Android), discard the image.
     *
     * Extra memory: before it changes any pixel, this call reserves its working area, sized for this call's masks and
     * released when the call returns. It grows with the number and the size of the masks; in the worst case (masks over
     * almost the whole image) it is up to 4.3 bytes per image pixel plus 32 MiB, about 141 MB for a 25-megapixel image,
     * whose own pixels take 100 MB. The fixed part is not
     * reserved on every call: it is sized from the plan (at most 128 bytes per mask, the fill scratch of the largest
     * mask, under 128 KiB for GRADIENT_OVAL and up to 18.5 MiB for SMOOTH_FILL only at its cell cap, and the bits of
     * the partial tiles at the image edges). The working area is needed per call, not per instance: N calls running
     * in parallel need up to N times this (four calls: up to about 4 × 141 MB at 25 megapixels in the worst case).
     * Each call checks the memory that is free now (on Android and the JVM
     * the heap that remains, on iOS the memory the system reports as available) together with the reservations of
     * the other running calls, so parallel calls never count the same free memory twice. At most 2 masking calls in
     * parallel on phones is recommended (see the documentation). If the working area does not fit in the memory that
     * is available now, the call throws `NotEnoughMemory` and the image is untouched. If the call ends
     * with an error that is not a `FaceMaskException` (for example an `OutOfMemoryError` on Android), discard the
     * image.
     *
     * Masking time depends on the image and the phone; slower phones take longer. Run masking off the main thread and
     * offer cancellation.
     *
     * Apply a plan only to the image it was made for: this in-memory call checks the size, not the pixels (your app
     * holds them), and, for a plan from [detect] (also edited with [MaskPlan.withMasks]), the view of the pixels: on
     * iOS the plan must be applied with the orientation of the detect call. File mode binds its plans to the decoded
     * file instead (see [maskFile]).
     *
     * @throws FaceMaskException.MaskPlanMismatch when [plan] was made for another image size, or comes from [detect]
     * through another view of the pixels (another orientation; nothing written).
     * @throws FaceMaskException.InvalidConfiguration (`masks`) when the masks' summed working area exceeds 32 times
     * the image area (at least 32 times 1,048,576 pixels); nothing is read or written.
     * @throws FaceMaskException.NotEnoughMemory when the working area cannot be reserved; the image is untouched.
     * Never publish the original instead.
     */
    @JvmOverloads
    @Throws(FaceMaskException::class)
    public fun applyMasks(
        image: MutablePixelImage,
        plan: MaskPlan,
        cancellation: CancellationToken? = null,
    ): MaskResult = guarded {
        val start = TimeSource.Monotonic.markNow()
        val maskMs = writeNow(image, plan, cancellation, fromModel = false)
        MaskResult(
            image.width,
            image.height,
            emptyList(),
            plan.masks,
            timings(0.0, 0, maskMs, start),
            false,
            emptyList(),
        )
    }

    /**
     * Detects the faces in [image] and covers each with an opaque mask, in place; coordinates are pixels of [image].
     * Blocks; call off the main thread. Does not touch metadata or encode: your encoder decides what is written to a
     * file. Equivalent to [detect] followed by [applyMasks] with `detection.toMaskPlan()`; error rules as there, with
     * one exception: when the masks from the model exceed the per-image mask limits (more than 65,536 masks, or a
     * summed working area above 32 times the image area), this call throws
     * [FaceMaskException.ModelOutputInvalid] with `TOO_MANY_MASKS` instead of `InvalidConfiguration`; its message
     * names the mask count and the profile's score threshold (see [DetectorProfile.scoreThreshold]). The result's
     * [MaskResult.masks] can outnumber its faces: a face that crosses a tile border can also get part masks (see
     * [Detection.toMaskPlan]).
     *
     * If this call throws a `FaceMaskException`, the image is untouched, with two exceptions. `MaskWriteFailed`: your
     * image failed while the finished masks were copied into it; discard it. `Cancelled` requested during that final
     * copy: it is reported after the copy, and the image is fully masked. If the call ends with any other error (for
     * example an `OutOfMemoryError` on Android), discard the image.
     *
     * Extra memory: before it changes any pixel, this call reserves its working area, sized for this call's masks and
     * released when the call returns. It grows with the number and the size of the masks; in the worst case (masks over
     * almost the whole image) it is up to 4.3 bytes per image pixel plus 32 MiB, about 141 MB for a 25-megapixel image,
     * whose own pixels take 100 MB. The fixed part is not
     * reserved on every call: it is sized from the plan (at most 128 bytes per mask, the fill scratch of the largest
     * mask, under 128 KiB for GRADIENT_OVAL and up to 18.5 MiB for SMOOTH_FILL only at its cell cap, and the bits of
     * the partial tiles at the image edges). The working area is needed per call, not per instance: N calls running
     * in parallel need up to N times this (four calls: up to about 4 × 141 MB at 25 megapixels in the worst case).
     * Each call checks the memory that is free now (on Android and the JVM
     * the heap that remains, on iOS the memory the system reports as available) together with the reservations of
     * the other running calls, so parallel calls never count the same free memory twice. At most 2 masking calls in
     * parallel on phones is recommended (see the documentation). If the working area does not fit in the memory that
     * is available now, the call throws `NotEnoughMemory` and the image is untouched. If the call ends
     * with an error that is not a `FaceMaskException` (for example an `OutOfMemoryError` on Android), discard the
     * image. The detector's own buffers come on top (one model input at a time).
     *
     * Masking time depends on the image and the phone; slower phones take longer. Run masking off the main thread and
     * offer cancellation.
     *
     * For photos, pass the image at a long side of at least 3,500 px (or its own size if smaller) and scale it down
     * further only after this call: detection quality depends on the face size in pixels.
     *
     * Outside the library's guarantee: encoding or showing the image before this call has returned, scaling a photo
     * below the detection size before calling FaceBlur, keeping an image after this call ended with
     * [FaceMaskException.MaskWriteFailed] or an error that is not a `FaceMaskException`, and publishing the original
     * after [FaceMaskException.NotEnoughMemory] (the image was not masked).
     */
    @JvmOverloads
    @Throws(FaceMaskException::class)
    public fun maskInPlace(image: MutablePixelImage, cancellation: CancellationToken? = null): MaskResult = guarded {
        val start = TimeSource.Monotonic.markNow()
        val found = detectNow(image, cancellation, start)
        val plan = found.toMaskPlan()
        val maskMs = writeNow(image, plan, cancellation, fromModel = true)
        val t = timings(found.timings.detectMs, found.timings.passes, maskMs, start)
        MaskResult(image.width, image.height, found.faces, plan.masks, t, false, found.warnings)
    }

    /**
     * File mode: finds the faces in the image file at [source] (a file-system path: Android app storage, the JVM, an
     * iOS sandbox path) and writes nothing. Blocks; call off the main thread.
     *
     * The file is decoded exactly as [maskFile] decodes it for the same [options]: the format is recognised from its
     * first bytes (JPEG, HEIC or PNG), the size is checked from the header before any pixel is decoded, and the image
     * is decoded once, with its orientation applied, at the working size ([FileOptions.maxLongSide]). Coordinates are
     * pixels of that working image ([Detection.width] × [Detection.height]); [Detection.outputWidth] and
     * [Detection.outputHeight] give the size [maskFile] would write. So `detection.toMaskPlan()`, edited by the user
     * with [MaskPlan.withMasks], fits `maskFile(source, target, options, plan)` for the same source and options. The
     * detection and its plans are bound to the decoded pixels: [maskFile] refuses them with
     * [FaceMaskException.SourceChanged] if the file's pixels change in between.
     *
     * @throws FaceMaskException.RuntimeUnavailable when this platform has no file-mode support installed; the source
     * is not opened.
     * @throws FaceMaskException.UnsupportedImage when the file is not a JPEG, HEIC or PNG image the platform decodes.
     * @throws FaceMaskException.ImageTooLarge when the source exceeds 200,000,000 pixels, or the working image exceeds
     * 65,535 px per side or [FileOptions.maxDecodedPixels]; nothing is decoded.
     * @throws FaceMaskException.IoFailed when the file cannot be read (the message never contains the path).
     * @throws FaceMaskException.Cancelled when [cancellation] was requested. Other errors as [detect].
     */
    @JvmOverloads
    @Throws(FaceMaskException::class)
    public fun detectFile(
        source: String,
        options: FileOptions = FileOptions(),
        cancellation: CancellationToken? = null,
    ): Detection = detectSource(FileSource.Path(source), options, cancellation, CallContext(null))

    /**
     * File mode: decodes the image file at [source] (a file-system path), covers its faces with opaque masks and
     * writes a new JPEG, rebuilt from pixels without the source's metadata (see [FileOptions.metadata]), to [target].
     * Blocks; call off the main thread.
     *
     * Steps: a [target] that can be refused without reading the source is refused first (a
     * [OutputTarget.PlatformTarget], a [OutputTarget.NewFile] at the source path without `replaceExisting`); then the
     * format is recognised from the first bytes (JPEG, HEIC or PNG); the sizes are checked from the header; a
     * [target] the platform cannot use is refused; then the image is decoded once at the working size
     * ([FileOptions.maxLongSide]), with its orientation applied. With [plan] `null` the faces are detected and
     * masked as [maskInPlace] does; otherwise the [plan] (from [detectFile] or [PlanMasker.planFile] with the same
     * source and options, edited with [MaskPlan.withMasks]) is applied instead of detection, after a check that the
     * decoded pixels are exactly those the plan was made for. The masked image is then scaled down if
     * [FileOptions.maxOutputLongSide] asks for a smaller output, encoded, checked and written once. Coordinates of the
     * result are pixels of the working image.
     *
     * NOTHING IS WRITTEN on any failure or cancellation: every check runs before the single write (also that the
     * encoded JPEG has the planned output size), and the target is untouched. Errors carry fixed text and no attached
     * exception (no cause, no suppressed exception), so no path reaches the app through them. A
     * [OutputTarget.NewFile] or [OutputTarget.OverwriteSource] for a path is written completely next to the target
     * and then moved into place.
     *
     * @throws FaceMaskException.RuntimeUnavailable when this platform has no file-mode support installed; the source
     * is not opened.
     * @throws FaceMaskException.UnsupportedTarget when [target] cannot be used for this call: a
     * [OutputTarget.PlatformTarget] (it needs the platform's form of this call), a [OutputTarget.NewFile] at the
     * source path or at an existing file without `replaceExisting`, or a location the platform cannot write.
     * @throws FaceMaskException.MaskPlanMismatch when [plan] has no binding to a file (it was built with the public
     * [MaskPlan] constructor; refused before the source is opened, after the target refusals that need no I/O), or
     * does not have the working size (nothing is decoded). Fix the code that builds the plan.
     * @throws FaceMaskException.SourceChanged when the file's decoded pixels differ from those [plan] was made for
     * (the file changed after [detectFile] or [PlanMasker.planFile]); nothing was masked or written. Run [detectFile]
     * again on the current file and, if your app shows the masks to the user for review, show the new result again
     * before masking.
     * @throws FaceMaskException.OutputValidationFailed when the encoded file fails the library's check.
     * @throws FaceMaskException.Cancelled when [cancellation] was requested; nothing was written. Other errors as
     * [detectFile], [maskInPlace] and [applyMasks].
     */
    @JvmOverloads
    @Throws(FaceMaskException::class)
    public fun maskFile(
        source: String,
        target: OutputTarget,
        options: FileOptions = FileOptions(),
        plan: MaskPlan? = null,
        cancellation: CancellationToken? = null,
    ): FileResult = maskSource(FileSource.Path(source), target, options, plan, cancellation, CallContext(null))

    /**
     * The file-mode detection of every form (ADR 0015 decision 4): the common path form passes a path source and a
     * [CallContext] without a platform value; the platform forms (Android: the `Context` forms) pass their own source
     * and a context made for this call only. Synthetic on the JVM and Android ([HiddenFromJava]).
     */
    @HiddenFromJava
    internal fun detectSource(
        source: FileSource,
        options: FileOptions,
        cancellation: CancellationToken?,
        call: CallContext,
    ): Detection = fileGuarded {
        val start = TimeSource.Monotonic.markNow()
        val codec = FileModeRun.codec()
        checkCancelled(cancellation)
        FileModeRun.detect(codec, source, options, call, fileSteps(cancellation, start), start)
    }

    /**
     * The file-mode masking of the path and platform forms (ADR 0015 decision 4); see [detectSource]. Synthetic on
     * the JVM and Android ([HiddenFromJava]).
     */
    @HiddenFromJava
    internal fun maskSource(
        source: FileSource,
        target: OutputTarget,
        options: FileOptions,
        plan: MaskPlan?,
        cancellation: CancellationToken?,
        call: CallContext,
    ): FileResult = fileGuarded {
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
            fileSteps(cancellation, start),
            start,
            checkTarget = { writer.check(target, source, call) },
            write = { writer.write(target, source, it, call) },
        )
    }

    /**
     * The stream form (Android `maskJpeg`, ADR 0015 decision 4): reads the stream through [reader] with the bounded
     * read ([BoundedRead]: format first, at most 64 MiB, every growth through the memory check), masks it like
     * [maskSource] and hands the finished JPEG to [sink] once. The buffer is released as soon as the image is
     * decoded, and on every failure. Synthetic on the JVM and Android ([HiddenFromJava]).
     */
    @HiddenFromJava
    internal fun maskStream(
        reader: StreamReader,
        sink: StreamSink,
        options: FileOptions,
        cancellation: CancellationToken?,
        call: CallContext,
    ): FileResult = fileGuarded {
        val start = TimeSource.Monotonic.markNow()
        val codec = FileModeRun.codec()
        checkCancelled(cancellation)
        val probe = maskStageSeams().probe
        val source = BoundedRead.read(reader, probe, checkCancelled = { checkCancelled(cancellation) })
        try {
            val steps = fileSteps(cancellation, start)
            FileModeRun.mask(codec, source, options, null, call, steps, start, checkTarget = {}, write = sink::write)
        } finally {
            source.release()
        }
    }

    /**
     * [guarded] with file mode's error rule ([FileModeRun.withoutDetails]): nothing a file-mode call throws carries a
     * cause or a suppressed exception.
     */
    private inline fun <T> fileGuarded(block: () -> T): T = guarded {
        try {
            block()
        } catch (e: Throwable) {
            throw FileModeRun.withoutDetails(e)
        }
    }

    private fun fileSteps(cancellation: CancellationToken?, start: TimeSource.Monotonic.ValueTimeMark) = FileModeSteps(
        detect = { detectNow(it, cancellation, start) },
        mask = { image, plan, fromModel -> writeNow(image, plan, cancellation, fromModel) },
        checkCancelled = { checkCancelled(cancellation) },
    )

    /**
     * Releases the runtime session. Idempotent and never throws. If a call is running on another thread, that call
     * completes and the session is released when it returns; later calls throw [FaceMaskException.Closed].
     */
    override fun close() {
        closed.store(true)
        releaseIfIdle()
    }

    private fun releaseIfIdle() {
        if (!busy.compareAndSet(expectedValue = false, newValue = true)) return
        try {
            if (runnerReleased.compareAndSet(expectedValue = false, newValue = true)) closeRunner()
        } finally {
            busy.store(false)
        }
    }

    /**
     * Closes the runtime session once. A failure while closing is not reported: [close] never throws, and when the
     * release runs at the end of a call it must not replace that call's result or error. The session is not used
     * again either way. Only the JVM's fatal errors pass (ADR 0002 decision 9); on iOS nothing escapes.
     */
    private fun closeRunner() {
        try {
            session.close()
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
        }
    }

    private fun detectNow(
        image: PixelImage,
        cancellation: CancellationToken?,
        start: TimeSource.Monotonic.ValueTimeMark,
    ): Detection {
        checkSize(image.width, image.height)
        checkCancelled(cancellation)
        val mark = TimeSource.Monotonic.markNow()
        val tiler = TiledFaceDetector(detector, limits, checkCancelled = { checkCancelled(cancellation) })
        val outcome = tiler.detect(PixelDetectionImage(image))
        val faces = outcome.faces.map { it.toFaceBox() }
        val detectMs = mark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
        return Detection(
            image.width,
            image.height,
            faces,
            timings(detectMs, outcome.passes, 0.0, start),
            false,
            effectiveMasking,
            Warnings.forWorkingSide(profile, maxOf(image.width, image.height)),
            outcome.parts.map { FacePart(it.face, it.rect.let { r -> DetectedFace(r).toFaceBox() }) },
            outcome.covers,
            view = PlanView.viewOf(image),
        )
    }

    /**
     * Runs the bounded mask stage (ADR 0013) and returns the time it took. [fromModel]: the plan came from this call's
     * detection, so the per-image mask limits are a model fault (`TOO_MANY_MASKS`), not a caller's configuration.
     */
    private fun writeNow(
        image: MutablePixelImage,
        plan: MaskPlan,
        cancellation: CancellationToken?,
        fromModel: Boolean,
    ): Double = MaskWrite.write(image, plan, maskSettings, { checkCancelled(cancellation) }, maskStageSeams()) { e ->
        if (fromModel) {
            FaceMaskException.ModelOutputInvalid(
                listOf(ModelDetector.TOO_MANY_MASKS),
                MaskLimitText.area(plan.masks.size, limits.minScore, e.area, e.limit),
            )
        } else {
            MaskWrite.planWorkLimit(plan, e)
        }
    }

    private var stageSeamsForTest: StageSeams? = null

    /** Test seam: the mask stage's allocator, memory probe and observer for this instance (production: none). */
    @HiddenFromJava
    internal fun useStageSeamsForTest(seams: StageSeams?) {
        stageSeamsForTest = seams
    }

    private fun maskStageSeams(): StageSeams = stageSeamsForTest ?: StageSeams()

    private fun timings(detectMs: Double, passes: Int, maskMs: Double, start: TimeSource.Monotonic.ValueTimeMark) =
        Timings(
            null,
            null,
            detectMs,
            passes,
            maskMs,
            null,
            start.elapsedNow().toDouble(DurationUnit.MILLISECONDS),
            null,
        )

    private inline fun <T> guarded(block: () -> T): T {
        if (closed.load()) throw FaceMaskException.Closed()
        if (!busy.compareAndSet(expectedValue = false, newValue = true)) {
            // A call racing with close() is told the instance is closed, not that it is in use.
            throw if (closed.load()) FaceMaskException.Closed() else FaceMaskException.ConcurrentUse()
        }
        try {
            if (closed.load()) throw FaceMaskException.Closed()
            return block()
        } catch (e: FaceMaskException) {
            throw e
        } catch (e: CoreFailure) {
            throw e.toPublic()
        } catch (e: OutputCheckFailed) {
            val masks = e.maskCount
            val pairs = e.pairChecks
            val detail =
                when {
                    masks != null -> MaskLimitText.count(masks, e.maskLimit, limits.minScore)
                    pairs != null -> MaskLimitText.nmsPairChecks(pairs, e.pairCheckLimit, limits.minScore)
                    else -> null
                }
            throw FaceMaskException.ModelOutputInvalid(e.findings, detail)
        } catch (e: Throwable) {
            // ADR 0002 decision 9: the JVM's VirtualMachineError family passes unchanged; every other Throwable
            // (also AssertionError, NotImplementedError, linkage errors) becomes Internal.
            if (isFatal(e)) throw e
            throw FaceMaskException.Internal("unexpected failure", e)
        } finally {
            busy.store(false)
            if (closed.load()) releaseIfIdle()
            // ADR 0004 decision 4: iOS collects garbage here ("between photos"); null on Android and the JVM.
            FaceBlurRuntime.afterCall()?.afterCall()
        }
    }

    internal companion object {
        /** Largest image side accepted in pixels (also bounds every per-row and per-column buffer). */
        const val MAX_SIDE: Int = 65_535

        /** How [FaceMaskException.ImageTooLarge] names the per-side limit. */
        const val SIDE_LIMIT: String = "the limit of $MAX_SIDE px per side"

        /** Default minimum overlap of [checkProfile]. */
        const val DEFAULT_MIN_IOU: Float = 0.5f

        /**
         * Test hook (ADR 0009): the boxes of the global pass alone at the threshold [minScore], mapped to [image] and
         * clipped to it, without the cross-pass merge, through this instance's checked session and detector.
         */
        @HiddenFromJava
        internal fun FaceMasker.globalPassForTest(image: PixelImage, minScore: Float): List<FaceBox> = guarded {
            val settings = DetectionSettings(minScore = minScore)
            TiledFaceDetector(detector, settings).globalPassOnly(PixelDetectionImage(image)).map { it.toFaceBox() }
        }

        /**
         * Test seam: another runtime binding and, optionally, lowered caps. Synthetic on the JVM and Android, so Java
         * code cannot call it.
         */
        @HiddenFromJava
        internal fun forTest(
            model: DetectorModel,
            config: FaceMaskerConfig,
            runtime: ModelRuntime?,
            limits: DetectionSettings = defaultLimits(model),
        ): FaceMasker = FaceMasker(config, model.profile, openSession(model, config, runtime), limits)

        private fun defaultLimits(model: DetectorModel) = DetectionSettings(minScore = model.profile.scoreThreshold)

        /** Steps 5-7 of ADR 0012 decision 4: session, runtime view of the inputs and outputs, contract run. */
        private fun openSession(model: DetectorModel, config: FaceMaskerConfig, runtime: ModelRuntime?): OpenedSession {
            val factory =
                runtime ?: throw FaceMaskException.RuntimeUnavailable(
                    "no ONNX Runtime binding is installed for this platform (see the integration guide)",
                )
            val session =
                try {
                    factory.open(model, config.onnxThreads)
                } catch (e: FaceMaskException) {
                    throw e
                } catch (e: SessionRejected) {
                    throw FaceMaskException.ModelRejected(listOf(OpenedSession.RUNTIME_REJECTED))
                } catch (e: RuntimeOptionFailed) {
                    throw FaceMaskException.RuntimeUnavailable("a required session option could not be set")
                } catch (e: Throwable) {
                    if (isFatal(e)) throw e
                    // Runtime exceptions are never chained: their text can contain paths or model names.
                    throw FaceMaskException.RuntimeUnavailable("the runtime session could not be created")
                }
            try {
                return OpenedSession.check(model, session)
            } catch (e: Throwable) {
                try {
                    session.close()
                } catch (closeFailure: Throwable) {
                    if (isFatal(closeFailure)) throw closeFailure
                }
                if (e is FaceMaskException || isFatal(e)) throw e
                throw FaceMaskException.Internal("the model check failed unexpectedly")
            }
        }

        private fun checkSize(width: Int, height: Int) = MaskWrite.checkSize(width, height)

        private fun checkCancelled(cancellation: CancellationToken?) {
            if (cancellation?.isCancellationRequested == true) throw FaceMaskException.Cancelled()
        }
    }
}
