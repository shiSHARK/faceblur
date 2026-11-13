// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

enum class Screen { HOME, REVIEW, RESULT }

/** Where a screen reader's focus should go after a state change, because the control that had it went away. */
enum class FocusTarget {
    START_HEADING,
    REVIEW_HEADING,
    RESULT_HEADING,
    PLACING_HEADING,
    ADD_MASK,
    COUNTS,
    STATUS,
    CANCEL,
}

/** The parts of the screen state that decide the focus. [status] is compared by identity: a new object is a new event. */
data class FocusSnapshot(val screen: Screen, val placing: Boolean, val status: Status, val undoEmpty: Boolean)

/**
 * The focus rules of the demo (WCAG 2.4.3). When a screen changes, a control disappears or is disabled while it is
 * focused, a screen reader would otherwise lose its place or stay on a stale control. Nothing moves on the first
 * state and nothing moves when only text changes: the status line is a live region and announces by itself.
 */
object FocusPlan {
    fun after(before: FocusSnapshot?, now: FocusSnapshot): FocusTarget? {
        if (before == null) return null
        if (now.screen != before.screen) {
            return when (now.screen) {
                Screen.HOME -> FocusTarget.START_HEADING
                Screen.REVIEW -> FocusTarget.REVIEW_HEADING
                Screen.RESULT -> FocusTarget.RESULT_HEADING
            }
        }
        val newStatus = now.status !== before.status
        return when {
            now.placing && !before.placing -> FocusTarget.PLACING_HEADING
            !now.placing && before.placing -> FocusTarget.ADD_MASK
            !newStatus -> null
            now.status is Status.Detecting || now.status is Status.Saving -> FocusTarget.CANCEL
            before.status is Status.Detecting || before.status is Status.Saving -> FocusTarget.STATUS
            now.status is Status.Removed -> FocusTarget.COUNTS
            now.status is Status.Undone && now.undoEmpty -> FocusTarget.COUNTS
            else -> null
        }
    }
}
