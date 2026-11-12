// SPDX-License-Identifier: Apache-2.0
package docs.snippets.android

import android.content.Context
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import io.github.shishark.faceblur.AndroidOutputTargets
import io.github.shishark.faceblur.FileResult
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.PlanMasker
import io.github.shishark.faceblur.UprightBox
import io.github.shishark.faceblur.maskFile
import io.github.shishark.faceblur.planFile

/** Boxes from YOUR detector, in the frame of the stored (unrotated) image of [rawWidth] x [rawHeight] pixels. */
class RawFaces(val rawWidth: Int, val rawHeight: Int, val boxes: List<RectF>)

/** Your own detector. It reads the file itself and may decode without applying the orientation tag. */
fun interface RawFaceDetector {
    fun detect(context: Context, source: Uri): RawFaces
}

/** The orientation value 1..8 of the file; a missing or undefined tag is 1. */
fun readOrientation(context: Context, source: Uri): Int {
    val value = context.contentResolver.openInputStream(source)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
    } ?: 1
    return if (value in 1..8) value else 1
}

/** Masks the faces of an external detector without loading any FaceBlur model. Call off the main thread. */
fun maskWithExternalDetector(
    context: Context,
    detector: RawFaceDetector,
    source: Uri,
    target: Uri,
): FileResult {
    val planMasker = PlanMasker()
    val empty = planMasker.planFile(context, source) // first: binds the plan to the decoded pixels
    val raw = detector.detect(context, source) // then: your detector reads the file
    val orientation = readOrientation(context, source)
    val masks = raw.boxes.map { box ->
        // The plan's frame is the upright frame; convert, then scale to the plan's working size.
        val upright = UprightBox.fromRaw(
            box.left, box.top, box.right, box.bottom, raw.rawWidth, raw.rawHeight, orientation,
        )
        val sx = empty.width.toFloat() / upright.imageWidth
        val sy = empty.height.toFloat() / upright.imageHeight
        Mask.manual(upright.left * sx, upright.top * sy, upright.right * sx, upright.bottom * sy, planMasker.masking)
    }
    // Detects nothing itself: a face your detector missed stays visible. SourceChanged means the file changed.
    return planMasker.maskFile(context, source, AndroidOutputTargets.newFile(target), empty.withMasks(masks))
}
