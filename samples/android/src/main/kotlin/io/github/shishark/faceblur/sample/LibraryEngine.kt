// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.github.shishark.faceblur.AndroidOutputTargets
import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskOrigin
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.detectFile
import io.github.shishark.faceblur.maskFile
import io.github.shishark.faceblur.maskInPlace
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt

/** What the engine keeps of a detect-only run: the library's plan and the masking it was made with. */
private class LibraryAnalysis(val plan: MaskPlan, val masking: MaskingConfig)

/**
 * The only class of the demo that calls FaceBlur. It uses the public API: `DetectorModel.load` with the hash the user
 * confirmed, `FaceMasker.detectFile` (detect only), `MaskPlan.withMasks` for the edited list and `maskFile` with an
 * `AndroidOutputTargets.newFile` target (file mode). It keeps no copy of the photo and writes nothing itself.
 */
class LibraryEngine(context: Context) : Engine<Uri> {
    private val appContext = context.applicationContext
    private var masker: FaceMasker? = null

    override fun readModel(source: Uri): ModelCandidate {
        val bytes = try {
            appContext.contentResolver.openInputStream(source)?.use { readLimited(it) }
                ?: throw DemoFailure(FailureKind.MODEL_UNREADABLE)
        } catch (e: java.io.IOException) {
            throw DemoFailure(FailureKind.MODEL_UNREADABLE)
        } catch (e: SecurityException) {
            throw DemoFailure(FailureKind.MODEL_UNREADABLE)
        }
        return ModelCandidate(displayName(source), bytes.size.toLong(), sha256Hex(bytes), bytes)
    }

    override fun loadModel(candidate: ModelCandidate) {
        val bytes = candidate.handle as ByteArray
        // The demo's profile for a model with the YuNet output layout. The model, its profile and the threshold are
        // the app's responsibility; 0.5 is the example value of the guide, not a recommendation.
        val profile = DetectorProfile(
            format = DetectionFormat.YuNetLayout(),
            inputWidth = MODEL_INPUT,
            inputHeight = MODEL_INPUT,
            scoreThreshold = SCORE_THRESHOLD,
            expectedSha256 = candidate.sha256, // the hash the person confirmed on screen
        )
        try {
            val model = DetectorModel.load(ModelSource.fromBytes(bytes), profile)
            // Two runtime threads: the main thread keeps room to draw and to answer input while a photo is processed.
            val next = FaceMasker(model, FaceMaskerConfig(onnxThreads = ONNX_THREADS))
            masker?.close()
            masker = next
        } catch (e: FaceMaskException) {
            throw failureOf(e)
        }
    }

    override fun analyse(source: Uri, output: OutputOption, cancelled: () -> Boolean): Analysis {
        val active = masker ?: throw DemoFailure(FailureKind.OTHER)
        try {
            val detection = active.detectFile(appContext, source, optionsFor(output), token(cancelled))
            val plan = detection.toMaskPlan()
            val faces = detection.faces.mapIndexed { i, f ->
                FaceView(i, Box(f.left, f.top, f.right, f.bottom), f.score)
            }
            val masks = plan.masks.mapIndexed { i, mask -> itemOf(i, mask) }
            return Analysis(
                detection.width,
                detection.height,
                faces,
                masks,
                decode(source),
                LibraryAnalysis(plan, detection.masking),
            )
        } catch (e: FaceMaskException) {
            throw failureOf(e)
        }
    }

    override fun manualMask(analysis: Analysis, id: Int, box: Box): MaskItem {
        val masking = (analysis.handle as LibraryAnalysis).masking
        try {
            return itemOf(id, Mask.manual(box.left, box.top, box.right, box.bottom, masking))
        } catch (e: FaceMaskException) {
            throw failureOf(e)
        }
    }

    override fun save(
        source: Uri,
        target: Uri,
        analysis: Analysis,
        masks: List<MaskItem>,
        output: OutputOption,
        cancelled: () -> Boolean,
    ): SaveOutcome {
        val active = masker ?: throw DemoFailure(FailureKind.OTHER)
        val plan = (analysis.handle as LibraryAnalysis).plan.withMasks(masks.map { it.handle as Mask })
        val result = cleanUpAfterFailure(target, { removeDocument(appContext.contentResolver, it) }) {
            active.maskFile(
                appContext,
                source,
                AndroidOutputTargets.newFile(target),
                optionsFor(output),
                plan,
                token(cancelled),
            )
        }
        // A call with a plan detects nothing: the face count is the one of the detect-only run.
        return SaveOutcome(analysis.faces.size, result.masks.size, decode(target))
    }

    /**
     * The in-memory pipeline (ST-023) with the masker of this engine: the library's in-memory mode on a `Bitmap`
     * (`maskInPlace`), no file mode.
     */
    fun bitmapPipeline(watermark: String): PipelineEngine<Uri> = BitmapPipeline(
        watermark,
        { ImageDecoder.createSource(appContext.contentResolver, it) },
    ) { bitmap, token ->
        val active = masker ?: throw DemoFailure(FailureKind.OTHER)
        val result = active.maskInPlace(bitmap, token)
        MaskCounts(result.faces.size, result.masks.size)
    }

    private fun optionsFor(output: OutputOption) = FileOptions(maxOutputLongSide = output.maxOutputLongSide)

    private fun token(cancelled: () -> Boolean) = object : CancellationToken {
        override val isCancellationRequested: Boolean get() = cancelled()
    }

    /** A picture for the screen: decoded upright (the platform decoder applies the orientation) and shrunk. */
    private fun decode(uri: Uri): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(appContext.contentResolver, uri)) { decoder, info, _ ->
            val longSide = max(info.size.width, info.size.height)
            if (longSide > PREVIEW_LONG_SIDE) {
                val s = PREVIEW_LONG_SIDE.toFloat() / longSide
                decoder.setTargetSize(
                    max(1, (info.size.width * s).roundToInt()),
                    max(1, (info.size.height * s).roundToInt()),
                )
            }
        }
    } catch (e: java.io.IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    private fun displayName(uri: Uri): String = try {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: RuntimeException) {
        null
    } ?: "model.onnx"

    private companion object {
        const val MODEL_INPUT = 640
        const val ONNX_THREADS = 2
        const val SCORE_THRESHOLD = 0.5f
        const val PREVIEW_LONG_SIDE = 1_600
    }
}

/**
 * Runs [work], the save into the new document [target]. After any error the target counts as not masked: [remove]
 * takes out the document the save created, and the failure says so if that did not work. The one exception is
 * `UnsupportedTarget`: the library refuses a non-empty target before it writes and leaves it untouched, so a file that
 * was already there is not the demo's to delete.
 */
internal fun <T> cleanUpAfterFailure(target: Uri, remove: (Uri) -> Boolean, work: () -> T): T = try {
    work()
} catch (e: RuntimeException) {
    val untouched = e is FaceMaskException.UnsupportedTarget
    throw failureOf(e, leftover = !untouched && !remove(target))
}

/** Deletes the document the system created for the save; false if the provider refuses or fails. */
internal fun removeDocument(resolver: ContentResolver, target: Uri): Boolean = try {
    DocumentsContract.deleteDocument(resolver, target)
} catch (e: Exception) {
    false
}

private fun itemOf(id: Int, mask: Mask): MaskItem = MaskItem(
    id = id,
    origin = if (mask.origin == MaskOrigin.DETECTED) Origin.DETECTED else Origin.ADDED,
    form = if (mask.shape == MaskShape.RECTANGLE) MaskForm.RECTANGLE else MaskForm.ELLIPSE,
    centerX = mask.centerX,
    centerY = mask.centerY,
    radiusX = mask.radiusX,
    radiusY = mask.radiusY,
    handle = mask,
)

private const val BUFFER = 8_192

/** Reads at most the library's model size limit; a longer file is refused without reading the rest. */
internal fun readLimited(input: java.io.InputStream, limit: Long = DetectorModel.DEFAULT_MAX_MODEL_BYTES): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > limit) throw DemoFailure(FailureKind.MODEL_TOO_LARGE)
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Maps a library error to what the screen explains. The detail is the library's own message, unmodified. */
internal fun failureOf(e: Exception, leftover: Boolean = false): DemoFailure {
    val detail = (e as? FaceMaskException)?.message
    return when (e) {
        is FaceMaskException.Cancelled -> DemoFailure(FailureKind.CANCELLED, leftover = leftover)

        is FaceMaskException.NotEnoughMemory -> DemoFailure(FailureKind.NOT_ENOUGH_MEMORY, leftover = leftover)

        is FaceMaskException.ModelOutputInvalid -> when {
            "TOO_MANY_MASKS" in e.findings -> DemoFailure(FailureKind.TOO_MANY_MASKS, detail, leftover)

            "TOO_MANY_NMS_PAIR_CHECKS" in e.findings ->
                DemoFailure(FailureKind.TOO_MANY_PAIR_CHECKS, detail, leftover)

            else -> DemoFailure(FailureKind.OTHER, detail, leftover)
        }

        is FaceMaskException.ModelIntegrity -> DemoFailure(FailureKind.MODEL_INTEGRITY, detail, leftover)

        is FaceMaskException.ModelRejected -> DemoFailure(FailureKind.MODEL_REJECTED, detail, leftover)

        is FaceMaskException.ImageTooLarge,
        is FaceMaskException.SourceTooLarge,
        -> DemoFailure(FailureKind.IMAGE_TOO_LARGE, detail, leftover)

        is FaceMaskException.UnsupportedImage -> DemoFailure(FailureKind.UNSUPPORTED_IMAGE, detail, leftover)

        is FaceMaskException.SourceChanged -> DemoFailure(FailureKind.SOURCE_CHANGED, detail, leftover)

        is FaceMaskException.UnsupportedTarget -> DemoFailure(FailureKind.TARGET_REFUSED, detail, leftover)

        is FaceMaskException.IoFailed -> DemoFailure(FailureKind.IO, detail, leftover)

        else -> DemoFailure(FailureKind.OTHER, detail, leftover)
    }
}
