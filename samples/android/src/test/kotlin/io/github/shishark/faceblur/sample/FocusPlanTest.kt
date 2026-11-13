// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FocusPlanTest {
    private fun snap(
        screen: Screen = Screen.HOME,
        placing: Boolean = false,
        status: Status = Status.ModelReady,
        undoEmpty: Boolean = true,
    ) = FocusSnapshot(screen, placing, status, undoEmpty)

    private fun after(before: FocusSnapshot?, now: FocusSnapshot) = FocusPlan.after(before, now)

    @Test
    fun `nothing moves on the first state or when nothing changed`() {
        assertNull(after(null, snap()))
        val same = snap()
        assertNull(after(same, same.copy()))
    }

    @Test
    fun `a new screen puts the focus on its heading`() {
        assertEquals(FocusTarget.REVIEW_HEADING, after(snap(), snap(Screen.REVIEW)))
        assertEquals(FocusTarget.RESULT_HEADING, after(snap(Screen.REVIEW), snap(Screen.RESULT)))
        assertEquals(FocusTarget.START_HEADING, after(snap(Screen.RESULT), snap(Screen.HOME)))
    }

    @Test
    fun `starting and ending a placement move the focus off the control that vanished or was disabled`() {
        val review = snap(Screen.REVIEW)
        assertEquals(FocusTarget.PLACING_HEADING, after(review, review.copy(placing = true, status = Status.Placing)))
        assertEquals(FocusTarget.ADD_MASK, after(review.copy(placing = true), review.copy(status = Status.Added(2))))
    }

    @Test
    fun `a run that starts goes to Cancel and a run that ends without a screen change goes to the status line`() {
        assertEquals(FocusTarget.CANCEL, after(snap(status = Status.ModelReady), snap(status = Status.Detecting)))
        assertEquals(
            FocusTarget.STATUS,
            after(snap(status = Status.Detecting), snap(status = Status.CancelledNothingChanged)),
        )
        val review = snap(Screen.REVIEW)
        assertEquals(FocusTarget.CANCEL, after(review, review.copy(status = Status.Saving)))
        assertEquals(
            FocusTarget.STATUS,
            after(
                review.copy(status = Status.Saving),
                review.copy(status = Status.Failed(DemoFailure(FailureKind.IO))),
            ),
        )
    }

    @Test
    fun `removing a mask or undoing the last change moves the focus to the counts`() {
        val review = snap(Screen.REVIEW, status = Status.Detected(1, 1))
        assertEquals(FocusTarget.COUNTS, after(review, review.copy(status = Status.Removed(0))))
        assertEquals(FocusTarget.COUNTS, after(review, review.copy(status = Status.Undone(1), undoEmpty = true)))
        assertNull(after(review, review.copy(status = Status.Undone(1), undoEmpty = false)))
        assertNull(after(review, review.copy(status = Status.Detected(2, 2))))
    }
}
