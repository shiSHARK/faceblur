// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.content.res.Resources
import java.util.Locale
import kotlin.math.roundToInt

/** Maps the controller's state to the strings of the screen. Kept apart from the activity so it can be tested. */
class Texts(private val res: Resources) {
    fun counts(faces: Int, masks: Int): String = res.getString(
        R.string.counts_line,
        res.getQuantityString(R.plurals.faces_detected, faces, faces),
        res.getQuantityString(R.plurals.masks_count, masks, masks),
    )

    fun status(status: Status): String = when (status) {
        Status.ChooseModelFirst -> res.getString(R.string.choose_model_first)
        Status.ModelReading -> res.getString(R.string.status_model_reading)
        Status.ModelConfirm -> res.getString(R.string.status_model_confirm)
        Status.ModelLoading -> res.getString(R.string.status_model_loading)
        Status.ModelReady -> res.getString(R.string.status_model_ready)
        is Status.ModelFailed -> failureTitle(status.failure)
        Status.Detecting -> res.getString(R.string.status_detecting)
        is Status.Detected -> res.getString(R.string.status_detected, counts(status.faces, status.masks))
        is Status.Removed -> res.getString(R.string.status_removed, masks(status.masks))
        is Status.Added -> res.getString(R.string.status_added, masks(status.masks))
        is Status.Undone -> res.getString(R.string.status_undone, masks(status.masks))
        Status.Placing -> res.getString(R.string.status_placing)
        Status.PlacingCancelled -> res.getString(R.string.status_placing_cancelled)
        Status.Saving -> res.getString(R.string.status_saving)
        is Status.Saved -> res.getString(R.string.status_saved, masks(status.masks))
        Status.TooLateToCancel -> res.getString(R.string.cancel_late_status)
        Status.CancelledNothingChanged -> res.getString(R.string.cancelled_status)
        is Status.Failed -> failureTitle(status.failure)
    }

    fun masks(n: Int): String = res.getQuantityString(R.plurals.masks_count, n, n)

    fun failureTitle(failure: DemoFailure): String {
        val id = when (failure.kind) {
            FailureKind.MODEL_TOO_LARGE -> R.string.error_model_toolarge_title
            FailureKind.MODEL_UNREADABLE -> R.string.error_model_unreadable_title
            FailureKind.MODEL_INTEGRITY -> R.string.error_model_integrity_title
            FailureKind.MODEL_REJECTED -> R.string.error_model_rejected_title
            FailureKind.TOO_MANY_MASKS -> R.string.error_toomanymasks_title
            FailureKind.TOO_MANY_PAIR_CHECKS -> R.string.error_toomanypairs_title
            FailureKind.NOT_ENOUGH_MEMORY -> R.string.error_oom_title
            FailureKind.IMAGE_TOO_LARGE -> R.string.error_image_toolarge_title
            FailureKind.UNSUPPORTED_IMAGE -> R.string.error_unsupported_image_title
            FailureKind.SOURCE_CHANGED -> R.string.error_source_changed_title
            FailureKind.TARGET_REFUSED -> R.string.error_target_refused_title
            FailureKind.IO -> R.string.error_io_title
            FailureKind.CANCELLED -> R.string.cancelled_status
            FailureKind.OTHER -> R.string.error_other_title
        }
        return res.getString(id)
    }

    /** The fixed explanation under the title, only for the two limit errors. */
    fun failureBody(failure: DemoFailure): String? = when (failure.kind) {
        FailureKind.TOO_MANY_MASKS -> res.getString(R.string.error_toomanymasks_body)
        FailureKind.TOO_MANY_PAIR_CHECKS -> res.getString(R.string.error_toomanypairs_body)
        else -> if (failure.leftover) res.getString(R.string.error_leftover) else null
    }

    fun maskTitle(position: Int, item: MaskItem): String = res.getString(
        if (item.origin ==
            Origin.DETECTED
        ) {
            R.string.mask_row_detected
        } else {
            R.string.mask_row_added
        },
        position,
    )

    /** Where the mask is, in percent of the image: a screen-reader user cannot see the overlay. */
    fun maskDetail(item: MaskItem, imageWidth: Int, imageHeight: Int): String {
        val shape = res.getString(
            if (item.form == MaskForm.RECTANGLE) R.string.mask_row_shape_rectangle else R.string.mask_row_shape_ellipse,
        )
        val position = res.getString(
            R.string.mask_row_position,
            percent(item.centerX, imageWidth),
            percent(item.centerY, imageHeight),
            percent(item.radiusX * 2, imageWidth),
            percent(item.radiusY * 2, imageHeight),
        )
        return "$position. $shape"
    }

    fun scores(faces: List<FaceView>): String? {
        if (faces.isEmpty()) return null
        val shown = faces.take(MAX_SCORES).joinToString(", ") {
            "${it.index + 1}: " + String.format(Locale.ROOT, "%.2f", it.score)
        }
        val text = if (faces.size > MAX_SCORES) {
            res.getQuantityString(R.plurals.faces_scores_more, faces.size - MAX_SCORES, shown, faces.size - MAX_SCORES)
        } else {
            shown
        }
        return res.getString(R.string.faces_scores, text)
    }

    private fun percent(value: Float, total: Int): Int = (value / total * HUNDRED).roundToInt()

    private companion object {
        const val MAX_SCORES = 20
        const val HUNDRED = 100f
    }
}
