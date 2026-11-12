// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextsTest {
    private val res = RuntimeEnvironment.getApplication().resources
    private val texts = Texts(res)

    @Test
    fun `faces and masks are two separate numbers for zero one and many`() {
        assertEquals("0 faces detected, 0 masks", texts.counts(0, 0))
        assertEquals("1 face detected, 1 mask", texts.counts(1, 1))
        assertEquals("1 face detected, 3 masks", texts.counts(1, 3))
        assertEquals("5 faces detected, 2 masks", texts.counts(5, 2))
    }

    @Test
    fun `every status and every failure has a text`() {
        val failure = DemoFailure(FailureKind.OTHER)
        val statuses = listOf(
            Status.ChooseModelFirst, Status.ModelReading, Status.ModelConfirm, Status.ModelLoading, Status.ModelReady,
            Status.ModelFailed(failure), Status.Detecting, Status.Detected(1, 1), Status.Removed(0), Status.Added(2),
            Status.Undone(1), Status.Placing, Status.PlacingCancelled, Status.Saving, Status.Saved(1, 1),
            Status.TooLateToCancel, Status.CancelledNothingChanged, Status.Failed(failure),
        )
        for (s in statuses) assertTrue(texts.status(s).isNotBlank(), s.toString())
        for (k in FailureKind.entries) assertTrue(texts.failureTitle(DemoFailure(k)).isNotBlank(), k.name)
    }

    @Test
    fun `the fixed strings carry the agreed wording`() {
        assertEquals("Choose a model first", texts.status(Status.ChooseModelFirst))
        assertEquals("Cancelled. Nothing was changed.", texts.status(Status.CancelledNothingChanged))
        assertEquals("Too late to cancel. The masks were applied.", texts.status(Status.TooLateToCancel))
        assertTrue(res.getString(R.string.limits_copy).startsWith("Detection can miss faces"))
    }

    @Test
    fun `the limit errors have a fixed body and the other errors only a leftover note when needed`() {
        assertNotNull(texts.failureBody(DemoFailure(FailureKind.TOO_MANY_MASKS)))
        assertNotNull(texts.failureBody(DemoFailure(FailureKind.TOO_MANY_PAIR_CHECKS)))
        assertNull(texts.failureBody(DemoFailure(FailureKind.IO)))
        assertNotNull(texts.failureBody(DemoFailure(FailureKind.IO, leftover = true)))
    }

    @Test
    fun `a mask row says where the mask is for a person who cannot see the overlay`() {
        val item = MaskItem(3, Origin.ADDED, MaskForm.RECTANGLE, 500f, 400f, 100f, 80f, Any())
        assertEquals("Mask 2: added by you", texts.maskTitle(2, item))
        assertEquals(
            "Centre 50% across and 50% down, 20% wide, 20% high. Shape: rectangle",
            texts.maskDetail(item, 1_000, 800),
        )
        val face = MaskItem(0, Origin.DETECTED, MaskForm.ELLIPSE, 500f, 400f, 100f, 80f, Any())
        assertEquals("Mask 1: from a detected face", texts.maskTitle(1, face))
        assertTrue(texts.maskDetail(face, 1_000, 800).endsWith("Shape: ellipse"))
    }

    @Test
    fun `scores are listed and capped`() {
        assertNull(texts.scores(emptyList()))
        val one = listOf(FaceView(0, Box(0f, 0f, 1f, 1f), 0.934f))
        assertEquals("Scores: 1: 0.93", texts.scores(one))
        val many = (0 until 22).map { FaceView(it, Box(0f, 0f, 1f, 1f), 0.5f) }
        val text = assertNotNull(texts.scores(many))
        assertTrue(text.endsWith("and 2 more faces"))
        assertFalse(text.contains("21:"))
    }
}
