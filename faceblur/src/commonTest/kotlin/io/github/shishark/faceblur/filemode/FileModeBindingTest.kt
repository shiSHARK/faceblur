// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlanMasker
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.mask.ReservedBytes
import io.github.shishark.faceblur.mask.StageObserver
import io.github.shishark.faceblur.mask.StageSeams
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A plan from `detectFile` is bound to the decoded image (ADR 0015 decision 6), with fake platform parts: a changed
 * source is refused with `SourceChanged` before the mask stage, a plan without a binding with `MaskPlanMismatch`
 * before the source is opened, `withMasks` keeps the binding, and the binding never shows in text or equality.
 */
class FileModeBindingTest {
    private val fakes = FileModeFakes()
    private val source = "/photos/canary-holiday.jpg"
    private val options = FileOptions(maxLongSide = 0)
    private lateinit var masker: FaceMasker

    private val observer = object : StageObserver {
        override fun step(step: Int, work: Long) {
            if (step == 1) fakes.events += "mask"
        }
    }

    @BeforeTest
    fun setUp() {
        FaceBlurRuntime.overrideDefaults(PlatformDefaults.NONE)
        FaceBlurRuntime.installImageCodec(fakes.codec)
        FaceBlurRuntime.installTargetWriter(fakes.writer)
        masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
        masker.useStageSeamsForTest(StageSeams(observer = observer))
    }

    @AfterTest
    fun tearDown() {
        masker.close()
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        FaceBlurRuntime.overrideDefaults(null)
        assertEquals(0L, ReservedBytes.current(), "every reservation is released")
    }

    private fun target() = OutputTarget.NewFile("/out/a.jpg")

    private fun assertNothingWritten() {
        assertTrue(fakes.writes.isEmpty(), "nothing written: ${fakes.events}")
        assertFalse("write" in fakes.events, fakes.events.toString())
        assertEquals(0, fakes.openImages, "every image is closed: ${fakes.events}")
    }

    /** The error carries the fixed text and nothing else: no cause, no suppressed exception, no path. */
    private fun assertFixedText(e: Throwable, text: String) {
        assertEquals(text, e.message)
        assertNull(e.cause)
        assertTrue(e.suppressedExceptions.isEmpty())
        assertFalse(e.toString().contains("canary"), e.toString())
    }

    @Test
    fun aPlanFromDetectFileIsBoundAndAcceptedOnTheUnchangedSource() {
        val detection = masker.detectFile(source, options)
        assertNotNull(detection.fingerprint, "detectFile binds its detection")
        val plan = detection.toMaskPlan()
        assertNotNull(plan.fingerprint, "toMaskPlan copies the binding")
        assertSame(plan.fingerprint, detection.toMaskPlan(MaskingConfig(expandX = 2f)).fingerprint)
        fakes.events.clear()
        val r = masker.maskFile(source, target(), options, plan)
        assertEquals(1, fakes.writes.size)
        assertEquals(plan.masks, r.masks)
        assertTrue("mask" in fakes.events)
        // In-memory detection has no binding.
        assertNull(masker.detect(IntArrayPixels(64, 48)).fingerprint)
    }

    /**
     * One pixel changed between the calls (same size), inside a mask and far outside every mask, and the same with a
     * plan without masks: `SourceChanged`, the mask stage is not entered, nothing is written.
     */
    @Test
    fun aChangedPixelAnywhereIsRefusedWithSourceChangedAndNothingIsWritten() {
        val plan = masker.detectFile(source, options).toMaskPlan()
        assertTrue(plan.masks.isNotEmpty(), "the fake model finds its face")
        val m = plan.masks.first()
        val inside = m.centerY.toInt() * 800 + m.centerX.toInt()
        val far = 599 * 800 + 799
        assertTrue(far != inside)
        for ((name, at) in listOf("inside a mask" to inside, "far outside every mask" to far)) {
            for (p in listOf(plan, plan.withMasks(emptyList()))) {
                fakes.events.clear()
                fakes.pixelEdit = { it[at] = it[at] xor 1 }
                val e = assertFailsWith<FaceMaskException.SourceChanged>(name) {
                    masker.maskFile(source, target(), options, p)
                }
                assertFixedText(e, FaceMaskException.SourceChanged.MESSAGE)
                assertFalse("mask" in fakes.events, "$name: the mask stage is not entered: ${fakes.events}")
                assertFalse(fakes.events.any { it.startsWith("encode") }, fakes.events.toString())
                assertNothingWritten()
            }
        }
        // Control: the unchanged source is accepted with the same plan.
        fakes.pixelEdit = null
        masker.maskFile(source, target(), options, plan)
        assertEquals(1, fakes.writes.size)
    }

    @Test
    fun theMessageOfSourceChangedIsTheFixedText() {
        assertEquals(
            "The source image changed after the mask plan was made; nothing was written. Make the plan again for " +
                "the current file and review it before masking.",
            FaceMaskException.SourceChanged().message,
        )
        assertEquals(
            "The mask plan is not bound to a file; in file mode use a plan from detectFile or planFile, edited with " +
                "withMasks. Nothing was written.",
            FaceMaskException.MaskPlanMismatch.unbound().message,
        )
    }

    @Test
    fun theBindingIsNotPartOfTextOrEquality() {
        val detection = masker.detectFile(source, options)
        val bound = detection.toMaskPlan()
        val unbound = MaskPlan(bound.width, bound.height, bound.masks)
        assertEquals(unbound, bound, "equal plans with and without a binding compare equal")
        assertEquals(unbound.hashCode(), bound.hashCode())
        assertEquals(unbound.toString(), bound.toString())
        assertFalse(bound.toString().contains("ImageFingerprint"))
        assertFalse(detection.toString().contains("ImageFingerprint"))
        assertEquals("ImageFingerprint", assertNotNull(bound.fingerprint).toString())
        // A detection compares equal to the same detection without the binding.
        val plain = masker.detect(IntArrayPixels(64, 48))
        val boundPlain = plain.boundTo(assertNotNull(bound.fingerprint))
        assertEquals(plain, boundPlain)
        assertEquals(plain.hashCode(), boundPlain.hashCode())
        assertEquals(plain.toString(), boundPlain.toString())
        assertSame(bound.fingerprint, boundPlain.toMaskPlan().fingerprint)
    }

    @Test
    fun withMasksKeepsTheSizeAndTheBindingAndLeavesTheReceiverUnchanged() {
        val plan = masker.detectFile(source, options).toMaskPlan()
        val before = plan.masks
        val added = Mask.manual(10f, 10f, 60f, 70f, MaskingConfig())
        val edited = plan.withMasks(listOf(added))
        assertEquals(800, edited.width)
        assertEquals(600, edited.height)
        assertSame(plan.fingerprint, edited.fingerprint)
        assertEquals(listOf(added), edited.masks)
        assertEquals(before, plan.masks, "the receiver is unchanged")
        val r = masker.maskFile(source, target(), options, edited)
        assertEquals(listOf(added), r.masks)
        assertEquals(1, fakes.writes.size)
        // The count limit as for the constructor.
        val tooMany = List(65_537) { added }
        val e = assertFailsWith<FaceMaskException.InvalidConfiguration> { plan.withMasks(tooMany) }
        assertEquals("masks", e.parameter)
        assertEquals(65_536, plan.withMasks(tooMany.dropLast(1)).masks.size)
        // On a plan without a binding, withMasks returns a plan without one.
        assertNull(MaskPlan(800, 600, emptyList()).withMasks(listOf(added)).fingerprint)
    }

    /**
     * A plan without a binding (public constructor, and withMasks of one) is refused by every maskFile form with
     * `MaskPlanMismatch` and the fixed text, before the source is opened; a target refusal comes first; in-memory
     * `applyMasks` accepts the same plan.
     */
    @Test
    fun aPlanWithoutABindingIsRefusedBeforeTheSourceIsOpened() {
        val mask = Mask.manual(10f, 10f, 60f, 70f, MaskingConfig())
        val planMasker = PlanMasker()
        for (plan in listOf(
            MaskPlan(800, 600, listOf(mask)),
            MaskPlan(800, 600, emptyList()).withMasks(listOf(mask)),
        )) {
            val calls = listOf<Pair<String, () -> Unit>>(
                "FaceMasker.maskFile" to { masker.maskFile(source, target(), options, plan) },
                "FaceMasker.maskSource" to {
                    masker.maskSource(
                        FileSource.Platform(FileModeFakes.Source(true)),
                        OutputTarget.PlatformTarget.create(FileModeFakes.OwnTargetValue()),
                        options,
                        plan,
                        null,
                        CallContext(FileModeFakes.Call),
                    )
                },
                "PlanMasker.maskFile" to { planMasker.maskFile(source, target(), plan, options) },
                "PlanMasker.maskSource" to {
                    planMasker.maskSource(
                        FileSource.Platform(FileModeFakes.Source(true)),
                        OutputTarget.PlatformTarget.create(FileModeFakes.OwnTargetValue()),
                        plan,
                        options,
                        null,
                        CallContext(FileModeFakes.Call),
                    )
                },
            )
            for ((name, call) in calls) {
                fakes.events.clear()
                val e = assertFailsWith<FaceMaskException.MaskPlanMismatch>(name) { call() }
                assertFixedText(e, FaceMaskException.MaskPlanMismatch.UNBOUND)
                assertTrue(fakes.events.isEmpty(), "$name: nothing opened or checked: ${fakes.events}")
                assertNothingWritten()
            }
            // Before the cancellation check of step 3 as well.
            val cancelled = object : CancellationToken {
                override val isCancellationRequested = true
            }
            assertFailsWith<FaceMaskException.MaskPlanMismatch> {
                masker.maskFile(source, target(), options, plan, cancelled)
            }
            // A target refusal of step 2 is reported before it.
            val platformTarget = OutputTarget.PlatformTarget.create(FileModeFakes.OwnTargetValue())
            assertFailsWith<FaceMaskException.UnsupportedTarget> {
                masker.maskFile(source, platformTarget, options, plan)
            }
            assertFailsWith<FaceMaskException.UnsupportedTarget> {
                planMasker.maskFile(source, OutputTarget.NewFile(source), plan, options)
            }
            assertTrue(fakes.opened.isEmpty(), "zero opens")
            // In-memory applyMasks accepts the same plan.
            val image = IntArrayPixels(800, 600)
            assertEquals(plan.masks, masker.applyMasks(image, plan).masks)
        }
    }

    /** Cancellation requested right before the fingerprint, or while it is computed, writes nothing. */
    @Test
    fun cancellationAtTheFingerprintStepWritesNothing() {
        val plan = masker.detectFile(source, options).toMaskPlan()
        var cancel = false
        val token = object : CancellationToken {
            override val isCancellationRequested get() = cancel
        }
        // Before: requested as soon as the opened source is closed (after the decode, before the fingerprint).
        fakes.events.clear()
        fakes.pixelReads = 0
        fakes.onRead = null
        val beforeCodec = fakes.codec
        FaceBlurRuntime.installImageCodec { s, c ->
            val opened = beforeCodec.open(s, c)
            object : OpenedImage by opened {
                override fun close() {
                    opened.close()
                    cancel = true
                }
            }
        }
        assertFailsWith<FaceMaskException.Cancelled> { masker.maskFile(source, target(), options, plan, token) }
        assertEquals(0, fakes.pixelReads, "cancelled before the fingerprint read a pixel")
        assertNothingWritten()
        // While: requested during the fingerprint's reads; seen by the check right after it.
        FaceBlurRuntime.installImageCodec(fakes.codec)
        cancel = false
        fakes.events.clear()
        fakes.onRead = { cancel = true }
        assertFailsWith<FaceMaskException.Cancelled> { masker.maskFile(source, target(), options, plan, token) }
        assertTrue(fakes.pixelReads > 0, "the fingerprint ran")
        assertFalse("mask" in fakes.events, fakes.events.toString())
        assertNothingWritten()
        // detectFile and planFile honour it the same way.
        cancel = false
        assertFailsWith<FaceMaskException.Cancelled> { masker.detectFile(source, options, token) }
        cancel = false
        assertFailsWith<FaceMaskException.Cancelled> { PlanMasker().planFile(source, options, token) }
        assertEquals(0, fakes.openImages)
    }
}
