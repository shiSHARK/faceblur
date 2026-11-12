// SPDX-License-Identifier: Apache-2.0
@file:JvmName("FaceMaskerAndroid")

package io.github.shishark.faceblur

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.github.shishark.faceblur.android.AndroidSources
import io.github.shishark.faceblur.android.BitmapReadPixels
import io.github.shishark.faceblur.android.BitmapRules
import io.github.shishark.faceblur.android.GainMaps
import io.github.shishark.faceblur.android.InPlaceBitmap
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.StreamReader
import io.github.shishark.faceblur.filemode.StreamSink
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// The Android forms of in-memory mode (ADR 0002 decision 3, ADR 0003). Java: FaceMaskerAndroid.detect(masker, bitmap),
// FaceMaskerAndroid.applyMasks(masker, bitmap, plan), FaceMaskerAndroid.maskInPlace(masker, bitmap). They run the common
// in-memory calls on the caller's bitmap through getPixels/setPixels, with the bitmap rules and the gain-map rule of
// ADR 0003.
//
// The Android forms of file mode (ADR 0015 decisions 3 and 6). Java: FaceMaskerAndroid.detectFile(masker, context, uri),
// FaceMaskerAndroid.maskFile(masker, context, uri, target), FaceMaskerAndroid.maskJpeg(masker, input, output),
// FaceMaskerAndroid.planFile(planMasker, context, uri), FaceMaskerAndroid.maskFile(planMasker, context, uri, target, plan).
// They run the same common orchestration as FaceMasker.detectFile / maskFile (same order of checks, same errors, the
// same rule that nothing is written on any failure); only reading and writing differ.

/**
 * In-memory mode: finds the faces in [bitmap]; coordinates are pixels of [bitmap] (top-left origin). Blocks; call off
 * the main thread. Never writes to [bitmap] and never changes its gain map.
 *
 * [bitmap] must not be recycled, must not be `HARDWARE` and must be `ARGB_8888`; it may be immutable. The pixels are
 * read with `getPixels` (non-premultiplied sRGB ARGB, whatever the bitmap's colour space). [Detection.hasGainMap] is
 * true when the bitmap carries an HDR gain map (Android 14+), which [maskInPlace] and [applyMasks] remove.
 *
 * For photos, pass the bitmap at a long side of at least 3,500 px (or its own size if smaller) and scale it down
 * further only after masking: detection quality depends on the face size in pixels. Each side may be at most
 * 65,535 px. Outside the library's guarantee: drawing your own effect from these boxes instead of [applyMasks], and
 * scaling a photo below the detection size before calling FaceBlur.
 *
 * @throws FaceMaskException.UnsupportedImage before any work when [bitmap] is recycled (`RECYCLED`), `HARDWARE`
 * (`HARDWARE`) or not `ARGB_8888` (`CONFIG`); the message starts with that reason and names the fix. The library never
 * copies the bitmap: decode with `ImageDecoder.ALLOCATOR_SOFTWARE`, or copy it yourself with
 * `bitmap.copy(Bitmap.Config.ARGB_8888, true)` (one full-size allocation, about 75 MB at 5,000 × 3,750 px).
 * Other errors as [FaceMasker.detect].
 */
@JvmOverloads
@Throws(FaceMaskException::class)
public fun FaceMasker.detect(bitmap: Bitmap, cancellation: CancellationToken? = null): Detection {
    BitmapRules.check(bitmap, write = false)
    val gainMap = GainMaps.present(bitmap)
    val found = detect(BitmapReadPixels(bitmap), cancellation)
    if (!gainMap) return found
    return Detection(
        found.width,
        found.height,
        found.faces,
        found.timings,
        true,
        found.masking,
        found.warnings,
        found.parts,
        found.covers,
    )
}

/**
 * In-memory mode: writes every mask of [plan] into [bitmap], in place; coordinates are pixels of [bitmap]. Blocks; call
 * off the main thread. Does not touch metadata or encode: your encoder decides what is written to a file (encode from
 * the bitmap; never copy APPn segments such as MPF, XMP or an EXIF thumbnail from the source file).
 *
 * [bitmap] must not be recycled and must be a mutable `ARGB_8888` bitmap in the sRGB colour space (decode with
 * `ImageDecoder.ALLOCATOR_SOFTWARE`, `isMutableRequired = true` and `setTargetColorSpace` sRGB; file mode decodes the
 * same way). The library exchanges non-premultiplied sRGB ARGB values with it (`getPixels`/`setPixels`; the bitmap
 * itself stays premultiplied), reads every written pixel back, and forces alpha to 255 inside every mask, also where the
 * bitmap's own alpha was lower. Pixels outside every mask are not written.
 *
 * Gain map (Android 14+): if [bitmap] carries an HDR gain map, this call removes it (`setGainmap(null)`) after its last
 * cancellation check and immediately before the first mask pixel is written, and also when [plan] is empty, so a later
 * encode cannot write the unmasked faces through it; [MaskResult.gainMapRemoved] reports it. HDR rendering of the photo
 * is lost; a call that throws before writing leaves the gain map in place.
 *
 * If this call throws a `FaceMaskException`, the bitmap is untouched, with two exceptions. `MaskWriteFailed`: your
 * bitmap failed while the finished masks were copied into it; discard it. `Cancelled` requested during that final
 * copy: it is reported after the copy, and the bitmap is fully masked. If the call ends with any other error (for
 * example an `OutOfMemoryError`), discard the bitmap.
 *
 * Extra memory: the working area of [FaceMasker.applyMasks], reserved from the Java heap that remains before any pixel
 * is written (the bitmap's own pixels are not copied). It grows with the number and the size of the masks; in the
 * worst case (masks over almost the whole bitmap) it is up to 4.3 bytes per pixel plus 32 MiB, about 141 MB at
 * 25 megapixels. N calls in parallel need up to N times this; at most 2 masking calls in parallel on phones is
 * recommended. If it does not fit, the call throws `NotEnoughMemory` and the bitmap is untouched.
 *
 * Apply a plan only to the bitmap it was made for: this call checks the size, not the pixels.
 *
 * @throws FaceMaskException.UnsupportedImage before any work when [bitmap] is recycled (`RECYCLED`), `HARDWARE`
 * (`HARDWARE`), not `ARGB_8888` or not sRGB (`CONFIG`; another colour space would not read back exactly) or immutable
 * (`IMMUTABLE`); the message starts with that reason and names the fix. The library never copies the bitmap: copy it yourself with `bitmap.copy(Bitmap.Config.ARGB_8888, true)`
 * (one full-size allocation, about 75 MB at 5,000 × 3,750 px) and continue with the copy.
 * @throws FaceMaskException.NotEnoughMemory when the working area cannot be reserved; the bitmap is untouched. Never
 * publish the original instead. Other errors as [FaceMasker.applyMasks].
 */
@JvmOverloads
@Throws(FaceMaskException::class)
public fun FaceMasker.applyMasks(bitmap: Bitmap, plan: MaskPlan, cancellation: CancellationToken? = null): MaskResult {
    BitmapRules.check(bitmap, write = true)
    val image = InPlaceBitmap(bitmap)
    val result = applyMasks(image, plan, cancellation)
    image.finish()
    return result.withGainMapRemoved(image.gainMapRemoved)
}

/**
 * In-memory mode: detects the faces in [bitmap] and covers each with an opaque mask, in place; coordinates are pixels
 * of [bitmap]. Blocks; call off the main thread. Does not touch metadata or encode: your encoder decides what is
 * written to a file (encode from the bitmap; never copy APPn segments such as MPF, XMP or an EXIF thumbnail from the
 * source file). Equivalent to [detect] followed by [applyMasks] with `detection.toMaskPlan()`; see
 * [FaceMasker.maskInPlace] for the one difference in the error type of the mask limits.
 *
 * [bitmap], its pixel format, the alpha inside the masks and its gain map (removed, also when no face is found): as
 * [applyMasks]. The same pixels give the same masks as file mode, which decodes into the same kind of bitmap.
 *
 * If this call throws a `FaceMaskException`, the bitmap is untouched, with two exceptions. `MaskWriteFailed`: your
 * bitmap failed while the finished masks were copied into it; discard it. `Cancelled` requested during that final
 * copy: it is reported after the copy, and the bitmap is fully masked. If the call ends with any other error (for
 * example an `OutOfMemoryError`), discard the bitmap.
 *
 * Extra memory: as [applyMasks] (worst case up to 4.3 bytes per pixel plus 32 MiB, about 141 MB at 25 megapixels),
 * plus the detector's own buffers (one model input at a time). At most 2 masking calls in parallel on phones is recommended.
 *
 * For photos, pass the bitmap at a long side of at least 3,500 px (or its own size if smaller) and scale it down
 * further only after this call. Outside the library's guarantee: encoding or showing the bitmap before this call has
 * returned, scaling a photo below the detection size before calling FaceBlur, keeping a bitmap after
 * [FaceMaskException.MaskWriteFailed] or an error that is not a `FaceMaskException`, and publishing the original after
 * [FaceMaskException.NotEnoughMemory] (the bitmap was not masked).
 *
 * @throws FaceMaskException.UnsupportedImage before any work, as [applyMasks].
 * @throws FaceMaskException.NotEnoughMemory when the working area cannot be reserved; the bitmap is untouched. Other
 * errors as [FaceMasker.maskInPlace].
 */
@JvmOverloads
@Throws(FaceMaskException::class)
public fun FaceMasker.maskInPlace(bitmap: Bitmap, cancellation: CancellationToken? = null): MaskResult {
    BitmapRules.check(bitmap, write = true)
    val image = InPlaceBitmap(bitmap)
    val result = maskInPlace(image, cancellation)
    image.finish()
    return result.withGainMapRemoved(image.gainMapRemoved)
}

private fun MaskResult.withGainMapRemoved(removed: Boolean): MaskResult =
    if (!removed) this else MaskResult(width, height, faces, masks, timings, true, warnings)

/**
 * File mode for a `Uri`: finds the faces in the image at [source] and writes nothing. Blocks; call off the main
 * thread.
 *
 * [source] is a `content:` URI (read through [context]'s `ContentResolver` for this call only; the `Context` is not
 * kept) or a `file:` URI (read like the path form). The image is decoded exactly as [maskFile] decodes it for the
 * same [options]; see [FaceMasker.detectFile] for the coordinates and the use of `toMaskPlan()`.
 *
 * @throws FaceMaskException.IoFailed when [source] cannot be read or has another scheme (the message never contains
 * the URI). Other errors as [FaceMasker.detectFile].
 */
@JvmOverloads
@Throws(FaceMaskException::class)
public fun FaceMasker.detectFile(
    context: Context,
    source: Uri,
    options: FileOptions = FileOptions(),
    cancellation: CancellationToken? = null,
): Detection =
    detectSource(AndroidSources.of(source), options, cancellation, AndroidSources.call(context.contentResolver))

/**
 * File mode for a `Uri`: decodes the image at [source], covers its faces with opaque masks and writes a new JPEG,
 * rebuilt from pixels without the source's metadata, to [target]. Blocks; call off the main thread.
 *
 * [source] is a `content:` or `file:` URI (see [detectFile]). [target] is one of:
 * - [OutputTarget.NewFile] (a path; an existing file is replaced only with `replaceExisting`). Android does not let
 *   apps create hard links, also in their own storage, so without `replaceExisting` the new file is created
 *   exclusively at the path and written there: until the call returns, and after a process kill, it can hold an
 *   incomplete file of masked output (never anything of the source); on a reported failure it is removed. A retry
 *   with the same `NewFile` then fails with [FaceMaskException.UnsupportedTarget] until the app removes that file.
 *   Do not treat "the file exists" as "the call finished": use the call's result;
 * - [OutputTarget.OverwriteSource]: for a `file:` source the file is replaced in one step; a `content:` source is
 *   refused with [FaceMaskException.UnsupportedTarget] before it is opened (use
 *   [AndroidOutputTargets.overwriteNonAtomic]);
 * - [AndroidOutputTargets.newFile] (an empty document) or [AndroidOutputTargets.overwriteNonAtomic] (the explicit
 *   opt-in to replace a document's content, which is not atomic: after ANY error from a call with a content-URI
 *   target, treat the target as unmasked and do not share it).
 *
 * NOTHING IS WRITTEN on any failure before the single write, and every check runs before it; see
 * [FaceMasker.maskFile] for the steps, [plan] and the errors.
 *
 * @throws FaceMaskException.UnsupportedTarget when [target] cannot be used (also: a content-URI target that is not
 * empty for `newFile`, cannot be read back, is not a file, or refuses `"wt"`).
 * @throws FaceMaskException.IoFailed when [source] cannot be read, or the target cannot be written or does not hold
 * exactly the written output afterwards. Other errors as [FaceMasker.maskFile].
 */
@JvmOverloads
@Throws(FaceMaskException::class)
public fun FaceMasker.maskFile(
    context: Context,
    source: Uri,
    target: OutputTarget,
    options: FileOptions = FileOptions(),
    plan: MaskPlan? = null,
    cancellation: CancellationToken? = null,
): FileResult = maskSource(
    AndroidSources.of(source),
    target,
    options,
    plan,
    cancellation,
    AndroidSources.call(context.contentResolver),
)

/**
 * Stream form: reads the image from [input], covers its faces with opaque masks and writes the finished JPEG
 * (rebuilt from pixels, without the source's metadata) to [output] in one write after every check. Blocks; call off
 * the main thread. Neither stream is closed; [output] is flushed.
 *
 * The stream is read into memory: the first bytes must show a JPEG, HEIC or PNG ([FaceMaskException.UnsupportedImage]
 * otherwise, after those bytes only), then at most 64 MiB (67,108,864 bytes) are read; a longer stream fails with
 * [FaceMaskException.SourceTooLarge] before anything is decoded (use [maskFile] with a path or a `Uri` for larger
 * inputs). The buffer is held until the image is decoded and counts in the memory check of parallel calls; for
 * sources near the limit run one stream call at a time.
 *
 * On any failure nothing is written to [output]. If [output] itself fails while the bytes are written, it may hold
 * part of the masked JPEG (never anything of the source); making that atomic is the caller's part.
 *
 * @throws FaceMaskException.IoFailed when [input] cannot be read or [output] cannot be written, also when either
 * stream throws a `RuntimeException` (the message never contains the stream's text, and the stream's exception is
 * not attached). Other errors as [FaceMasker.maskFile].
 */
@JvmOverloads
@Throws(FaceMaskException::class)
public fun FaceMasker.maskJpeg(
    input: InputStream,
    output: OutputStream,
    options: FileOptions = FileOptions(),
    cancellation: CancellationToken? = null,
): FileResult = maskStream(
    StreamReader { buffer, offset, length ->
        try {
            input.read(buffer, offset, length)
        } catch (e: IOException) {
            throw FaceMaskException.IoFailed("the source stream could not be read")
        } catch (e: RuntimeException) {
            throw FaceMaskException.IoFailed("the source stream could not be read")
        }
    },
    StreamSink { bytes ->
        try {
            output.write(bytes)
            output.flush()
        } catch (e: IOException) {
            throw FaceMaskException.IoFailed("the output stream could not be written")
        } catch (e: RuntimeException) {
            throw FaceMaskException.IoFailed("the output stream could not be written")
        }
    },
    options,
    cancellation,
    CallContext(null),
)

/**
 * [PlanMasker.planFile] for a `Uri`: decodes the image at [source] exactly as [FaceMasker.detectFile] for a `Uri` does
 * for the same [options], detects nothing, writes nothing and returns an empty plan of the working size, bound to the
 * decoded pixels. Loads no model and no ONNX Runtime. Blocks; call off the main thread.
 *
 * [source] is a `content:` URI (read through [context]'s `ContentResolver` for this call only; the `Context` is not
 * kept) or a `file:` URI (read like the path form). See [PlanMasker.planFile] for the frame your detector's boxes
 * must be converted to; let your detector read the file after this call.
 *
 * @throws FaceMaskException.IoFailed when [source] cannot be read or has another scheme (the message never contains
 * the URI). Other errors as [PlanMasker.planFile].
 */
@JvmOverloads
@Throws(FaceMaskException::class)
public fun PlanMasker.planFile(
    context: Context,
    source: Uri,
    options: FileOptions = FileOptions(),
    cancellation: CancellationToken? = null,
): MaskPlan = planSource(AndroidSources.of(source), options, cancellation, AndroidSources.call(context.contentResolver))

/**
 * [PlanMasker.maskFile] for a `Uri`: writes the masks of [plan] (from [planFile] or [FaceMasker.detectFile] for this
 * [source] and [options], edited with [MaskPlan.withMasks]) into the image at [source] and writes a new JPEG,
 * rebuilt from pixels without the source's metadata, to [target]. DETECTS NOTHING: faces outside the plan's masks stay
 * visible. Loads no model and no ONNX Runtime. Blocks; call off the main thread.
 *
 * [source] and [target] are those of [FaceMasker.maskFile] for a `Uri` (also its notes on `NewFile` and on content-URI
 * targets: after ANY error from a call with a content-URI target, treat the target as unmasked and do not share it).
 * A file that changed after [planFile] is refused with [FaceMaskException.SourceChanged]; nothing is written.
 *
 * @throws FaceMaskException.IoFailed when [source] cannot be read, or the target cannot be written or does not hold
 * exactly the written output afterwards. Other errors as [PlanMasker.maskFile].
 */
@JvmOverloads
@Throws(FaceMaskException::class)
public fun PlanMasker.maskFile(
    context: Context,
    source: Uri,
    target: OutputTarget,
    plan: MaskPlan,
    options: FileOptions = FileOptions(),
    cancellation: CancellationToken? = null,
): FileResult = maskSource(
    AndroidSources.of(source),
    target,
    plan,
    options,
    cancellation,
    AndroidSources.call(context.contentResolver),
)
