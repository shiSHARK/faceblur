// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.OutputFormat
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlanMasker
import io.github.shishark.faceblur.detect.OutputCheckFailed
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.MemoryRule
import io.github.shishark.faceblur.mask.ReservedBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `PlanMasker` (ADR 0015 decision 6) with fake platform parts: `planFile` decodes like `detectFile` and returns an
 * empty bound plan of the working size, detecting and writing nothing; `maskFile` writes exactly the plan's masks
 * under the rules of `FaceMasker.maskFile`, byte for byte as `FaceMasker` writes the same plan.
 */
class PlanMaskerTest {
    private val fakes = FileModeFakes()
    private val source = "/photos/canary-holiday.jpg"
    private val options = FileOptions(maxLongSide = 0)
    private val solid = MaskingConfig(style = MaskStyle.SOLID, color = 0xFF204060.toInt())

    /**
     * The memory probe of the mask stage, through the registry as in production: step 1 of the stage reads it before
     * anything else (it reads it more than once), so a run of reads is recorded as one event `mask`. [available] is
     * what it reports.
     */
    private var available = Long.MAX_VALUE / 4

    private val probe = object : MemoryProbe {
        override val rule = MemoryRule.HEAP_REMAINING

        override fun figure(): Long {
            if (fakes.events.lastOrNull() != "mask") fakes.events += "mask"
            return available
        }
    }

    @BeforeTest
    fun setUp() {
        FaceBlurRuntime.overrideDefaults(PlatformDefaults.NONE)
        FaceBlurRuntime.installImageCodec(fakes.codec)
        FaceBlurRuntime.installTargetWriter(fakes.writer)
        FaceBlurRuntime.installMemoryProbe(probe)
    }

    @AfterTest
    fun tearDown() {
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        FaceBlurRuntime.installMemoryProbe(null)
        FaceBlurRuntime.overrideDefaults(null)
        assertEquals(0L, ReservedBytes.current(), "every reservation is released")
    }

    /** [writeAttempted]: the writer was called and failed (its event is recorded before it throws). */
    private fun assertNothingWritten(writeAttempted: Boolean = false) {
        assertTrue(fakes.writes.isEmpty(), "nothing written: ${fakes.events}")
        if (!writeAttempted) assertFalse("write" in fakes.events, fakes.events.toString())
        assertEquals(0, fakes.openImages, "every image is closed: ${fakes.events}")
    }

    @Test
    fun theDefaultConstructorUsesTheDefaultMasking() {
        assertEquals(MaskingConfig(), PlanMasker().masking)
        assertSame(solid, PlanMasker(solid).masking)
    }

    @Test
    fun planFileReturnsAnEmptyBoundPlanOfTheWorkingSizeAndDetectsAndWritesNothing() {
        fakes.size = Size(7000, 1000)
        val plan = PlanMasker().planFile(source)
        assertEquals(3500, plan.width, "default options: the working size")
        assertEquals(500, plan.height)
        assertTrue(plan.masks.isEmpty())
        assertNotNull(plan.fingerprint)
        assertEquals(listOf("open", "decode 3500x500", "close opened", "close working"), fakes.events)
        assertNothingWritten()
        // maxLongSide = 0: the source size. A source turned by 90 degrees (the codec reports the upright size).
        fakes.events.clear()
        fakes.size = Size(600, 800)
        val upright = PlanMasker().planFile(source, options)
        assertEquals(600, upright.width)
        assertEquals(800, upright.height)
        assertEquals(listOf("open", "decode 600x800", "close opened", "close working"), fakes.events)
        // planFile never reads metadata and never asks the writer.
        assertEquals(0, fakes.metadataReads)
    }

    /** planFile -> withMasks (manual masks) -> maskFile writes once; the masks' opaque cores have the mask colour. */
    @Test
    fun anEditedPlanIsWrittenOnceWithOpaqueMasks() {
        val planMasker = PlanMasker(solid)
        val plan = planMasker.planFile(source, options)
        val masks = listOf(
            Mask.manual(100f, 100f, 200f, 220f, solid),
            Mask.manual(500f, 300f, 640f, 460f, solid),
        )
        fakes.events.clear()
        val r = planMasker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), plan.withMasks(masks), options)
        assertEquals(
            listOf(
                "open",
                "check",
                "decode 800x600",
                "close opened",
                "mask",
                "encode working q90",
                "close working",
                "write",
            ),
            fakes.events,
        )
        assertEquals(1, fakes.writes.size)
        assertTrue(r.faces.isEmpty(), "PlanMasker detects nothing")
        assertEquals(masks, r.masks)
        assertEquals(0, r.timings.passes)
        assertEquals(0.0, r.timings.detectMs)
        assertEquals(800, r.width)
        assertEquals(800, r.outputWidth)
        assertEquals(600, r.outputHeight)
        val pixels = fakes.encodedPixels.single()
        for (m in masks) {
            val at = m.centerY.toInt() * 800 + m.centerX.toInt()
            assertEquals(0xFF204060.toInt(), pixels[at], "the opaque core has the mask colour")
        }
        // A pixel far from both masks is unchanged.
        assertEquals(gradientPixels(800, 600)[10], pixels[10])
    }

    /**
     * The same plan with the same MaskingConfig: PlanMasker and FaceMasker write identical files, and each accepts a
     * bound plan made by the other's first call.
     */
    @Test
    fun planMaskerAndFaceMaskerWriteTheSamePlanIdentically() {
        for (masking in listOf(MaskingConfig(), MaskingConfig(style = MaskStyle.SMOOTH_FILL, feather = 0.3f))) {
            val masker = FaceMasker.forTest(
                testYuNetModel(),
                FaceMaskerConfig(masking = masking),
                RunnerRuntime(OneFaceRunner()),
            )
            try {
                val planMasker = PlanMasker(masking)
                val fromPlanFile = planMasker.planFile(source, FileOptions(0, 400))
                    .withMasks(listOf(Mask.manual(120f, 80f, 260f, 260f, masking)))
                val fromDetectFile = masker.detectFile(source, FileOptions(0, 400)).toMaskPlan()
                for (plan in listOf(fromPlanFile, fromDetectFile)) {
                    fakes.writes.clear()
                    fakes.encodedPixels.clear()
                    val a = planMasker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), plan, FileOptions(0, 400))
                    val b = masker.maskFile(source, OutputTarget.NewFile("/out/b.jpg"), FileOptions(0, 400), plan)
                    assertEquals(2, fakes.writes.size)
                    assertContentEquals(fakes.writes[0], fakes.writes[1], "byte-identical files")
                    assertContentEquals(fakes.encodedPixels[0], fakes.encodedPixels[1], "identical masked pixels")
                    assertEquals(a.masks, b.masks)
                    assertEquals(400, a.outputWidth)
                    assertEquals(a.outputBytes, b.outputBytes)
                }
            } finally {
                masker.close()
            }
        }
    }

    @Test
    fun aChangedSourceBetweenPlanFileAndMaskFileIsRefused() {
        val planMasker = PlanMasker()
        val plan = planMasker.planFile(source, options).withMasks(listOf(Mask.manual(10f, 10f, 90f, 90f)))
        fakes.events.clear()
        fakes.pixelEdit = { it[123_456] = it[123_456] xor 0x00010000 }
        val e = assertFailsWith<FaceMaskException.SourceChanged> {
            planMasker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), plan, options)
        }
        assertEquals(FaceMaskException.SourceChanged.MESSAGE, e.message)
        assertNull(e.cause)
        assertFalse("mask" in fakes.events, "the mask stage is not entered: ${fakes.events}")
        assertNothingWritten()
        // Another size is a plan-size mismatch, found from the header before decoding.
        fakes.pixelEdit = null
        fakes.size = Size(801, 600)
        fakes.events.clear()
        assertFailsWith<FaceMaskException.MaskPlanMismatch> {
            planMasker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), plan, options)
        }
        assertFalse(fakes.events.any { it.startsWith("decode") })
        assertNothingWritten()
    }

    @Test
    fun withoutFileModePartsBothCallsFailClosedBeforeTheSourceIsOpened() {
        val plan = PlanMasker().planFile(source, options)
        FaceBlurRuntime.installImageCodec(null)
        fakes.events.clear()
        assertFailsWith<FaceMaskException.RuntimeUnavailable> { PlanMasker().planFile(source, options) }
        assertFailsWith<FaceMaskException.RuntimeUnavailable> {
            PlanMasker().maskFile(source, OutputTarget.NewFile("/out/a.jpg"), plan, options)
        }
        FaceBlurRuntime.installImageCodec(fakes.codec)
        FaceBlurRuntime.installTargetWriter(null)
        assertFailsWith<FaceMaskException.RuntimeUnavailable> {
            PlanMasker().maskFile(source, OutputTarget.NewFile("/out/a.jpg"), plan, options)
        }
        assertTrue(fakes.events.isEmpty(), fakes.events.toString())
        assertNothingWritten()
    }

    /** Platform failures reach the app as fixed-text errors without anything attached; library errors pass. */
    @Test
    fun errorsFollowTheFileModeRule() {
        val plan = PlanMasker().planFile(source, options)
        for (step in listOf("open", "decode 800x600", "close opened", "check", "encode working q90", "write")) {
            fakes.events.clear()
            fakes.fail = step
            fakes.failure = { IllegalStateException("/secret-canary-path/$step") }
            val e = assertFailsWith<FaceMaskException.Internal>(step) {
                if (step == "check" || step.startsWith("encode") || step == "write") {
                    PlanMasker().maskFile(source, OutputTarget.NewFile("/out/a.jpg"), plan, options)
                } else {
                    PlanMasker().planFile(source, options)
                }
            }
            assertNull(e.cause, step)
            assertTrue(e.suppressedExceptions.isEmpty(), step)
            assertFalse(e.toString().contains("canary"), e.toString())
            assertNothingWritten(writeAttempted = step == "write")
        }
        // A typed platform error without attachments passes unchanged.
        val io = FaceMaskException.IoFailed("the file could not be read")
        fakes.fail = "open"
        fakes.failure = { io }
        assertSame(io, assertFailsWith<FaceMaskException.IoFailed> { PlanMasker().planFile(source, options) })
        // A platform error with something attached loses its type.
        fakes.failure = { FaceMaskException.Internal("x", IllegalStateException("/secret-canary-path")) }
        val wrapped = assertFailsWith<FaceMaskException.Internal> { PlanMasker().planFile(source, options) }
        assertNull(wrapped.cause)
        // An exception that is not a FaceMaskException and not a platform failure (a library bug) is Internal too.
        fakes.failure = { OutputCheckFailed(listOf("X")) }
        assertNull(assertFailsWith<FaceMaskException.Internal> { PlanMasker().planFile(source, options) }.cause)
        fakes.fail = null
    }

    /** The mask stage's own limits and memory check apply as for FaceMasker; nothing is written. */
    @Test
    fun theMaskStageLimitsAndTheMemoryCheckApply() {
        val plan = PlanMasker().planFile(source, options)
        val many = plan.withMasks(List(100) { Mask.manual(0f, 0f, 800f, 600f) })
        val e = assertFailsWith<FaceMaskException.InvalidConfiguration> {
            PlanMasker().maskFile(source, OutputTarget.NewFile("/out/a.jpg"), many, options)
        }
        assertEquals("masks", e.parameter)
        assertNothingWritten()
        available = 1L
        val one = plan.withMasks(listOf(Mask.manual(10f, 10f, 90f, 90f)))
        val oom = assertFailsWith<FaceMaskException.NotEnoughMemory> {
            PlanMasker().maskFile(source, OutputTarget.NewFile("/out/a.jpg"), one, options)
        }
        assertNull(oom.cause)
        assertNothingWritten()
    }

    @Test
    fun cancellationAtAnyPointWritesNothing() {
        val plan = PlanMasker().planFile(source, options).withMasks(listOf(Mask.manual(10f, 10f, 90f, 90f)))
        var cancelled = 0
        var from = 1
        while (true) {
            fakes.events.clear()
            fakes.writes.clear()
            var reads = 0
            val token = object : CancellationToken {
                override val isCancellationRequested get() = ++reads >= from
            }
            try {
                PlanMasker().maskFile(source, OutputTarget.NewFile("/out/a.jpg"), plan, FileOptions(0, 400), token)
                assertEquals(1, fakes.writes.size)
                break
            } catch (e: FaceMaskException.Cancelled) {
                cancelled++
                assertNothingWritten()
            }
            from++
            assertTrue(from < 200, "the call completes once the token stays clear")
        }
        // Before opening, before decoding, before and after the fingerprint, in the mask stage, before the write.
        assertTrue(cancelled >= 5, "cancelled at $cancelled points")
        val always = object : CancellationToken {
            override val isCancellationRequested = true
        }
        fakes.events.clear()
        assertFailsWith<FaceMaskException.Cancelled> { PlanMasker().planFile(source, options, always) }
        assertTrue(fakes.events.isEmpty(), "cancelled before the source is opened")
    }

    @Test
    fun theOutputFormatAndTheScaleDownFollowTheOptions() {
        fakes.size = Size(7000, 1000)
        val o = FileOptions(maxOutputLongSide = 700, format = OutputFormat.Jpeg(77))
        val plan = PlanMasker().planFile(source, o).withMasks(listOf(Mask.manual(100f, 100f, 300f, 300f)))
        fakes.events.clear()
        val r = PlanMasker().maskFile(source, OutputTarget.NewFile("/out/a.jpg"), plan, o)
        assertTrue("scale 700x100" in fakes.events && "encode scaled q77" in fakes.events, fakes.events.toString())
        assertEquals(3500, r.width)
        assertEquals(7000, r.sourceWidth)
        assertEquals(700, r.outputWidth)
        assertEquals(100, r.outputHeight)
        assertNotNull(r.timings.orientScaleMs)
    }
}
