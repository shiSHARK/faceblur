// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.OutputFormat
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.mask.ReservedBytes
import io.github.shishark.faceblur.mask.StageObserver
import io.github.shishark.faceblur.mask.StageSeams
import io.github.shishark.faceblur.toPublic
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
import kotlin.test.fail

/**
 * The common file-mode orchestration with fake platform parts (ADR 0015 decision 4, ADR 0005 decision 1): the order of
 * the steps, nothing written on any failure or cancellation, the plan-size check, the target refusals, the result
 * fields, platforms without file-mode parts, and the agreement with in-memory mode.
 */
class FileModeRunTest {
    private val fakes = FileModeFakes()
    private val source = "/photos/canary-holiday.jpg"
    private val sourceSize = FileOptions(maxLongSide = 0)

    /** Records each detector pass after the masker was created (creation runs one contract pass). */
    private class RecordingRunner(private val events: MutableList<String>) : TensorRunner {
        private val inner = OneFaceRunner()
        var recording = false
        var passes = 0
        override val inputWidth get() = inner.inputWidth
        override val inputHeight get() = inner.inputHeight

        override fun initialize() = inner.initialize()

        override fun close() = inner.close()

        override fun run(input: FloatArray): Map<String, FloatArray> {
            if (recording) {
                if (passes == 0) events += "detect"
                passes++
            }
            return inner.run(input)
        }
    }

    private val runner = RecordingRunner(fakes.events)
    private lateinit var masker: FaceMasker

    @BeforeTest
    fun setUp() {
        FaceBlurRuntime.overrideDefaults(PlatformDefaults.NONE)
        FaceBlurRuntime.installImageCodec(fakes.codec)
        FaceBlurRuntime.installTargetWriter(fakes.writer)
        masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(runner))
        masker.useStageSeamsForTest(
            StageSeams(
                observer = object : StageObserver {
                    override fun step(step: Int, work: Long) {
                        if (step == 1) fakes.events += "mask"
                    }
                },
            ),
        )
        runner.recording = true
    }

    @AfterTest
    fun tearDown() {
        masker.close()
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        FaceBlurRuntime.overrideDefaults(null)
        assertEquals(0L, ReservedBytes.current(), "every reservation is released")
    }

    private fun assertNothingWrittenAndAllClosed() {
        assertTrue(fakes.writes.isEmpty(), "nothing written: ${fakes.events}")
        assertFalse("write" in fakes.events, fakes.events.toString())
        assertEquals(0, fakes.openImages, "every opened and decoded image is closed: ${fakes.events}")
    }

    @Test
    fun maskFileRunsTheStepsInOrderAndWritesOnceAtTheEnd() {
        val r = masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        assertEquals(
            listOf(
                "open",
                "check",
                "decode 800x600",
                "close opened",
                "detect",
                "mask",
                "encode working q90",
                "close working",
                "write",
            ),
            fakes.events,
        )
        assertEquals(1, fakes.writes.size)
        assertEquals(0, fakes.openImages)
        assertTrue(r.faces.isNotEmpty(), "the fake model finds its face")
        assertNull(r.timings.orientScaleMs, "not scaled: the orientation is applied while decoding")
    }

    @Test
    fun aSmallerOutputIsScaledAfterMaskingAndBothImagesAreClosedBeforeTheWrite() {
        val r = masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), FileOptions(0, 400, format = jpeg(77)))
        assertEquals(
            listOf(
                "open",
                "check",
                "decode 800x600",
                "close opened",
                "detect",
                "mask",
                "scale 400x300",
                "encode scaled q77",
                "close scaled",
                "close working",
                "write",
            ),
            fakes.events,
        )
        assertEquals(800, r.width)
        assertEquals(600, r.height)
        assertEquals(400, r.outputWidth)
        assertEquals(300, r.outputHeight)
        assertTrue(r.timings.orientScaleMs!! >= 0.0)
    }

    @Test
    fun aLargerSourceIsDecodedAtTheWorkingSizeAndTheResultCarriesEveryField() {
        fakes.size = Size(7000, 1000)
        fakes.hasGainMap = true
        val r = masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), FileOptions(maxOutputLongSide = 700))
        assertTrue("decode 3500x500" in fakes.events, fakes.events.toString())
        assertTrue("scale 700x100" in fakes.events, fakes.events.toString())
        assertEquals(3500, r.width)
        assertEquals(500, r.height)
        assertEquals(7000, r.sourceWidth)
        assertEquals(1000, r.sourceHeight)
        assertEquals(700, r.outputWidth)
        assertEquals(100, r.outputHeight)
        assertEquals(fakes.writes.single().size.toLong(), r.outputBytes)
        assertTrue(r.gainMapRemoved)
        assertEquals(r.faces.size, r.masks.size, "one mask per face for the fake model's single face")
        assertTrue(r.faces.isNotEmpty())
        assertEquals(masker.detectFile(source, FileOptions(maxOutputLongSide = 700)).warnings, r.warnings)
        val t = r.timings
        assertNotNull(t.decodeMs)
        assertNotNull(t.orientScaleMs)
        assertNotNull(t.encodeMs)
        assertNull(t.gcMs)
        assertTrue(t.passes > 1, "global pass plus tiles")
        val decodeMs = assertNotNull(t.decodeMs)
        assertTrue(t.detectMs >= 0.0 && t.maskMs >= 0.0 && t.totalMs >= decodeMs)
        // The written bytes are the rebuilt output: SOI and the library's fixed JFIF APP0, never the encoder's.
        val written = fakes.writes.single()
        assertContentEquals(JpegIds.FIXED_JFIF_APP0, written.copyOfRange(2, 2 + JpegIds.FIXED_JFIF_APP0.size))
        assertTrue(OutputValidator.findings(written, MetadataPolicy.StripAll).isEmpty())
        assertEquals(Size(700, 100), OutputValidator.requireValid(written, MetadataPolicy.StripAll), "written frame")
    }

    @Test
    fun detectFileDecodesAsMaskFileAndReturnsWorkingCoordinatesWithTheOutputSize() {
        fakes.size = Size(7000, 1000)
        fakes.hasGainMap = true
        val d = masker.detectFile(source, FileOptions(maxOutputLongSide = 700))
        assertEquals(listOf("open", "decode 3500x500", "close opened", "detect", "close working"), fakes.events)
        assertEquals(3500, d.width)
        assertEquals(500, d.height)
        assertEquals(700, d.outputWidth)
        assertEquals(100, d.outputHeight)
        assertTrue(d.hasGainMap)
        assertNotNull(d.timings.decodeMs)
        assertNull(d.timings.encodeMs)
        assertNull(d.timings.orientScaleMs, "detectFile never scales")
        assertEquals(0.0, d.timings.maskMs)
        assertTrue(d.timings.passes > 1)
        assertEquals(3500, d.toMaskPlan().width)
        assertEquals(0, fakes.openImages)
        assertTrue(fakes.writes.isEmpty())
    }

    /** ADR 0002 decision 7: the same pixels give the same boxes and the same masked pixels as in-memory mode. */
    @Test
    fun fileModeAgreesWithInMemoryModeOnTheWorkingImage() {
        val r = masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        val image = IntArrayPixels(800, 600, gradientPixels(800, 600))
        val inMemory = masker.maskInPlace(image)
        assertEquals(inMemory.faces, r.faces)
        assertEquals(inMemory.masks, r.masks)
        assertContentEquals(image.data, fakes.encodedPixels.single())
    }

    /** The edit flow: detectFile -> toMaskPlan -> maskFile(plan) applies the plan without detecting again. */
    @Test
    fun aGivenPlanIsAppliedInsteadOfDetectionAndGivesTheSameOutputAsDetection() {
        val plain = masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        val plan = masker.detectFile(source, sourceSize).toMaskPlan()
        fakes.events.clear()
        runner.passes = 0
        val withPlan = masker.maskFile(source, OutputTarget.NewFile("/out/b.jpg"), sourceSize, plan)
        assertEquals(0, runner.passes, "no detection with a plan")
        assertFalse("detect" in fakes.events)
        assertTrue(withPlan.faces.isEmpty(), "faces are empty when a plan was applied")
        assertTrue(withPlan.warnings.isEmpty())
        assertEquals(plan.masks, withPlan.masks)
        assertEquals(plain.masks, withPlan.masks)
        assertEquals(0, withPlan.timings.passes)
        assertEquals(0.0, withPlan.timings.detectMs)
        assertContentEquals(fakes.encodedPixels[0], fakes.encodedPixels[1])
    }

    @Test
    fun aPlanOfAnotherSizeFailsBeforeDecodingAndWritesNothing() {
        // A bound plan (from detectFile) of a 799 x 600 source, applied to the 800 x 600 source.
        fakes.size = Size(799, 600)
        val wrong = masker.detectFile(source, sourceSize).toMaskPlan()
            .withMasks(listOf(Mask.manual(10f, 10f, 50f, 50f, MaskingConfig())))
        fakes.size = Size(800, 600)
        fakes.events.clear()
        val e = assertFailsWith<FaceMaskException.MaskPlanMismatch> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize, wrong)
        }
        assertEquals("Mask plan for 799 x 600 pixels applied to a 800 x 600 image", e.message)
        assertEquals(listOf("open", "check", "close opened"), fakes.events)
        assertNothingWrittenAndAllClosed()
        // The plan refers to the working size, not the source size.
        fakes.size = Size(7000, 1000)
        val sourceSizePlan = masker.detectFile(source, FileOptions(maxLongSide = 0)).toMaskPlan()
        assertEquals(7000, sourceSizePlan.width)
        fakes.events.clear()
        assertFailsWith<FaceMaskException.MaskPlanMismatch> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), FileOptions(), sourceSizePlan)
        }
        assertFalse(fakes.events.any { it.startsWith("decode") })
        assertNothingWrittenAndAllClosed()
    }

    /** Every step that can fail: the call throws, nothing is written and every image is closed. */
    @Test
    fun nothingIsWrittenWhenAnyStepFails() {
        val steps = listOf(
            "open",
            "check",
            "decode 800x600",
            "close opened",
            "scale 400x300",
            "encode scaled q90",
            "close scaled",
            "close working",
        )
        for (step in steps) {
            fakes.events.clear()
            fakes.fail = step
            val failure = FaceMaskException.UnsupportedTarget("planted at $step")
            fakes.failure = { failure }
            val thrown = assertFailsWith<FaceMaskException>(step) {
                masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), FileOptions(0, 400))
            }
            assertEquals<Throwable>(failure, thrown, step)
            assertNothingWrittenAndAllClosed()
        }
        fakes.fail = null
    }

    @Test
    fun aDetectionFailureWritesNothing() {
        val nanRunner = OneFaceRunner()
        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(nanRunner)).use { bad ->
            nanRunner.nan = true // after creation: the contract pass saw finite values
            assertFailsWith<FaceMaskException.ModelOutputInvalid> {
                bad.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), FileOptions(0, 400))
            }
        }
        assertNothingWrittenAndAllClosed()
        assertFalse(fakes.events.any { it.startsWith("encode") })
    }

    @Test
    fun aMaskStageFailureWritesNothing() {
        // 100 masks over the whole image exceed the summed-area limit (32 x max(image area, 1,048,576 px)): refused
        // before any pixel is read or written.
        val many = (1..100).map { Mask.manual(0f, 0f, 800f, 600f, MaskingConfig()) }
        val plan = masker.detectFile(source, sourceSize).toMaskPlan().withMasks(many)
        assertFailsWith<FaceMaskException.InvalidConfiguration> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize, plan)
        }
        assertNothingWrittenAndAllClosed()
    }

    @Test
    fun platformPartsThatReturnAnotherSizeFailClosed() {
        fakes.decodedSize = Size(801, 600)
        assertFailsWith<FaceMaskException.Internal> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        }
        assertNothingWrittenAndAllClosed()
        assertFalse("detect" in fakes.events, "no detection on an image of the wrong size")
        fakes.decodedSize = null
        fakes.scaledSize = Size(400, 301)
        fakes.events.clear()
        assertFailsWith<FaceMaskException.Internal> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), FileOptions(0, 400))
        }
        assertNothingWrittenAndAllClosed()
        assertFalse(fakes.events.any { it.startsWith("encode") }, "nothing encoded from a wrong-size image")
    }

    /**
     * The encoder is a platform part too: a valid JPEG of another size than the plan's output (for example the
     * working image encoded instead of the scaled one) fails closed and is never written; the result's output size is
     * the written frame's.
     */
    @Test
    fun anEncodedFrameOfAnotherSizeThanPlannedIsNeverWritten() {
        val cases = listOf(
            sourceSize to { s: Size -> JpegTestData.encoderOutput(Size(s.width + 1, s.height)) },
            sourceSize to { s: Size -> JpegTestData.encoderOutput(Size(s.height, s.width)) },
            FileOptions(0, 400) to { _: Size -> JpegTestData.encoderOutput(Size(800, 600)) }, // the unscaled image
            FileOptions(0, 400) to { _: Size -> JpegTestData.encoderOutput() }, // 16 x 16
        )
        for ((options, encoder) in cases) {
            fakes.events.clear()
            fakes.encoded = encoder
            val e = assertFailsWith<FaceMaskException.Internal> {
                masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), options)
            }
            assertEquals("Internal error: the encoder returned another size than planned", e.message)
            assertTrue(fakes.events.any { it.startsWith("encode") }, fakes.events.toString())
            assertNothingWrittenAndAllClosed()
        }
        // Stream form: nothing handed to the sink either.
        val sunk = ArrayList<ByteArray>()
        assertFailsWith<FaceMaskException.Internal> {
            masker.maskStream(CountingStream(1_000), { sunk += it }, sourceSize, null, CallContext(null))
        }
        assertTrue(sunk.isEmpty())
        // Control: the planned size passes, and the result reports the written frame.
        fakes.encoded = { JpegTestData.encoderOutput(it) }
        val r = masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), FileOptions(0, 400))
        val frame = OutputValidator.requireValid(fakes.writes.single(), MetadataPolicy.StripAll)
        assertEquals(Size(400, 300), frame)
        assertEquals(frame, Size(r.outputWidth, r.outputHeight))
    }

    @Test
    fun encoderOutputThatFailsTheCheckIsNeverWritten() {
        fakes.encoded = { JpegTestData.SOI + JpegTestData.coding() } // no EOI
        assertFailsWith<FaceMaskException.OutputValidationFailed> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        }
        assertNothingWrittenAndAllClosed()
    }

    @Test
    fun aFailedCloseAfterAFailureIsDroppedAndNeverReplacesTheFirstError() {
        fakes.decodedSize = Size(1, 1)
        fakes.fail = "close working"
        val e = assertFailsWith<FaceMaskException.Internal> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        }
        assertTrue("another size" in e.message.orEmpty(), e.message)
        assertTrue(e.suppressedExceptions.isEmpty(), "the close failure is not attached")
        assertTrue(fakes.writes.isEmpty())
        fakes.fail = "close opened"
        fakes.decodedSize = null
        fakes.events.clear()
        fakes.failure = { FaceMaskException.IoFailed("planted") }
        assertFailsWith<FaceMaskException.IoFailed> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        }
        assertEquals(listOf("open", "check", "decode 800x600", "close opened", "close working"), fakes.events)
        fakes.fail = "close opened"
        fakes.events.clear()
        fakes.size = Size(70_000, 10)
        fakes.failure = { IllegalStateException("planted close failure") }
        assertFailsWith<FaceMaskException.ImageTooLarge> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        }
        assertTrue(fakes.writes.isEmpty())
        fakes.fail = null
    }

    /** Cancellation at every read of the token: either the call completes and writes once, or it writes nothing. */
    @Test
    fun cancellationAtAnyPointWritesNothing() {
        var cancelled = 0
        var from = 1
        while (true) {
            fakes.events.clear()
            fakes.writes.clear()
            val token = CountingToken(from)
            try {
                masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), FileOptions(0, 400), null, token)
                assertEquals(1, fakes.writes.size)
                break
            } catch (e: FaceMaskException.Cancelled) {
                cancelled++
                assertNothingWrittenAndAllClosed()
            }
            from++
            assertTrue(from < 500, "the call completes once the token stays clear")
        }
        assertTrue(cancelled >= 4, "before decoding, between passes, in the mask stage, before the write: $cancelled")
        // The last read before the write: cancelled there, the encoder ran but nothing was written.
        fakes.events.clear()
        fakes.writes.clear()
        assertFailsWith<FaceMaskException.Cancelled> {
            masker.maskFile(
                source,
                OutputTarget.NewFile("/out/a.jpg"),
                FileOptions(0, 400),
                null,
                CountingToken(from - 1),
            )
        }
        assertTrue("encode scaled q90" in fakes.events, fakes.events.toString())
        assertNothingWrittenAndAllClosed()
        // detectFile honours cancellation as well.
        fakes.events.clear()
        assertFailsWith<FaceMaskException.Cancelled> { masker.detectFile(source, sourceSize, CountingToken(1)) }
        assertTrue(fakes.events.isEmpty(), "cancelled before the source is opened")
    }

    private class CountingToken(private val from: Int) : CancellationToken {
        var reads = 0

        override val isCancellationRequested: Boolean
            get() = ++reads >= from
    }

    @Test
    fun headerChecksRunBeforeAnythingIsDecodedOrChecked() {
        val cases = listOf(
            Size(20_000, 10_001) to FileOptions(), // above the 200 MP source ceiling
            Size(70_000, 100) to FileOptions(maxLongSide = 0), // a side above 65,535 px
            Size(6_000, 5_000) to FileOptions(maxLongSide = 0), // 30 MP above the 25 MP default
        )
        for ((size, options) in cases) {
            fakes.size = size
            fakes.events.clear()
            assertFailsWith<FaceMaskException.ImageTooLarge> {
                masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), options)
            }
            assertEquals(listOf("open", "close opened"), fakes.events, "$size")
            assertNothingWrittenAndAllClosed()
        }
        // The same 30 MP source within a raised limit is decoded at full size (stopped there: no 120 MB test image).
        fakes.size = Size(6_000, 5_000)
        fakes.events.clear()
        fakes.fail = "decode 6000x5000"
        assertFailsWith<FaceMaskException.Internal> {
            masker.detectFile(source, FileOptions(maxLongSide = 0, maxDecodedPixels = 30_000_000))
        }
        assertEquals(listOf("open", "decode 6000x5000", "close opened"), fakes.events)
        fakes.fail = null
    }

    @Test
    fun anUnsupportedSourceFailsAtOpenBeforeTheTargetIsChecked() {
        fakes.fail = "open"
        fakes.failure = { FaceMaskException.UnsupportedImage("only JPEG, HEIC and PNG sources are supported") }
        assertFailsWith<FaceMaskException.UnsupportedImage> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        }
        assertEquals(listOf("open"), fakes.events)
        assertNothingWrittenAndAllClosed()
        fakes.fail = null
    }

    /** ADR 0015 decision 1: without file-mode parts both calls fail closed before the source is opened. */
    @Test
    fun withoutFileModePartsBothCallsFailClosedBeforeTheSourceIsOpened() {
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        val d = assertFailsWith<FaceMaskException.RuntimeUnavailable> { masker.detectFile(source) }
        assertTrue("file mode is not available on this platform" in d.message.orEmpty(), d.message)
        assertFailsWith<FaceMaskException.RuntimeUnavailable> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"))
        }
        assertFailsWith<FaceMaskException.RuntimeUnavailable> {
            masker.maskStream(CountingStream(100), { fail("no write") }, FileOptions(), null, CallContext(null))
        }
        // A decoder without a writer: detection works, masking still fails before the source is opened.
        FaceBlurRuntime.installImageCodec(fakes.codec)
        assertFailsWith<FaceMaskException.RuntimeUnavailable> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        }
        assertTrue(fakes.opened.isEmpty(), "the source is never opened")
        masker.detectFile(source, sourceSize)
        assertEquals(1, fakes.opened.size)
        assertTrue(fakes.writes.isEmpty())
    }

    @Test
    fun theCallsUseThePlatformDefaultsWhenNothingIsInstalled() {
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        FaceBlurRuntime.overrideDefaults(PlatformDefaults(imageCodec = fakes.codec, targetWriter = fakes.writer))
        masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        assertEquals(1, fakes.writes.size)
    }

    @Test
    fun aPlatformTargetOnThePathFormIsRefusedBeforeTheSourceIsOpened() {
        val target = OutputTarget.PlatformTarget.create(FileModeFakes.OwnTargetValue())
        val e = assertFailsWith<FaceMaskException.UnsupportedTarget> { masker.maskFile(source, target, sourceSize) }
        assertTrue("Context" in e.message.orEmpty(), e.message)
        assertTrue(fakes.opened.isEmpty(), "zero opens")
        assertNothingWrittenAndAllClosed()
    }

    @Test
    fun aPlatformTargetNotMadeByThePlatformIsRefusedBeforeTheSourceIsOpened() {
        val call = CallContext(FileModeFakes.Call)
        val platformSource = FileSource.Platform(FileModeFakes.Source(replaceableInOneStep = false))
        val foreign = OutputTarget.PlatformTarget.create(FileModeFakes.ForeignTargetValue())
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskSource(platformSource, foreign, sourceSize, null, null, call)
        }
        assertTrue(fakes.opened.isEmpty())
        assertNothingWrittenAndAllClosed()
        // The platform's own target passes on to the writer.
        val own = OutputTarget.PlatformTarget.create(FileModeFakes.OwnTargetValue())
        masker.maskSource(platformSource, own, sourceSize, null, null, call)
        assertEquals(1, fakes.writes.size)
    }

    @Test
    fun overwriteSourceIsRefusedForSourcesThatCannotBeReplacedInOneStep() {
        val call = CallContext(FileModeFakes.Call)
        val content = FileSource.Platform(FileModeFakes.Source(replaceableInOneStep = false))
        val e = assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskSource(content, OutputTarget.OverwriteSource, sourceSize, null, null, call)
        }
        assertTrue("overwriteNonAtomic" in e.message.orEmpty(), e.message)
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskSource(
                FileSource.Bytes(ByteArray(4)),
                OutputTarget.OverwriteSource,
                sourceSize,
                null,
                null,
                call,
            )
        }
        assertTrue(fakes.opened.isEmpty())
        assertNothingWrittenAndAllClosed()
        // A file URI (replaceable in one step) and a path are allowed.
        val file = FileSource.Platform(FileModeFakes.Source(replaceableInOneStep = true, kind = "file"))
        masker.maskSource(file, OutputTarget.OverwriteSource, sourceSize, null, null, call)
        masker.maskFile(source, OutputTarget.OverwriteSource, sourceSize)
        assertEquals(2, fakes.writes.size)
    }

    @Test
    fun aNewFileAtTheSourcePathNeedsReplaceExisting() {
        for (spelling in listOf(
            source,
            "/photos/./canary-holiday.jpg",
            "/photos/x/../canary-holiday.jpg",
            "//photos//canary-holiday.jpg",
        )) {
            val e = assertFailsWith<FaceMaskException.UnsupportedTarget>(spelling) {
                masker.maskFile(source, OutputTarget.NewFile(spelling), sourceSize)
            }
            assertTrue("OverwriteSource" in e.message.orEmpty(), e.message)
            assertFalse("canary" in e.message.orEmpty(), "no path in the message")
        }
        // A relative spelling is made absolute by the platform first.
        assertFailsWith<FaceMaskException.UnsupportedTarget> {
            masker.maskFile("/cwd/in.jpg", OutputTarget.NewFile("in.jpg"), sourceSize)
        }
        assertTrue(fakes.opened.isEmpty())
        assertNothingWrittenAndAllClosed()
        masker.maskFile(source, OutputTarget.NewFile(source, replaceExisting = true), sourceSize)
        masker.maskFile(source, OutputTarget.NewFile("/photos/canary-holiday-masked.jpg"), sourceSize)
        // A platform source never matches a path.
        val content = FileSource.Platform(FileModeFakes.Source(replaceableInOneStep = false))
        masker.maskSource(
            content,
            OutputTarget.NewFile(source),
            sourceSize,
            null,
            null,
            CallContext(FileModeFakes.Call),
        )
        assertEquals(3, fakes.writes.size)
    }

    @Test
    fun pathsAreNormalisedLexically() {
        assertEquals("/a/c", normalised("/a/b/../c"))
        assertEquals("/a/b", normalised("//a/./b/"))
        assertEquals("/", normalised("/.."))
        assertEquals("/b", normalised("/../b"))
        assertEquals("../b", normalised("../b"))
        assertEquals("../../b", normalised("../x/../../b"))
        assertEquals("a", normalised("a/b/.."))
        assertEquals("", normalised("."))
    }

    @Test
    fun metadataIsReadOnlyWhenThePolicyKeepsSomething() {
        masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        assertEquals(0, fakes.metadataReads, "StripAll never asks the platform for metadata")
        fakes.metadata = PhotoMetadata.of(make = "CanaryMake", model = "CanaryModel")
        val keep = FileOptions(maxLongSide = 0, metadata = MetadataPolicy.KeepAllowlist(camera = true))
        masker.maskFile(source, OutputTarget.NewFile("/out/b.jpg"), keep)
        assertEquals(1, fakes.metadataReads)
        val written = fakes.writes.last()
        assertTrue(OutputValidator.findings(written, keep.metadata).isEmpty())
        assertTrue(written.decodeToString().contains("CanaryMake"), "the allowlisted group is written")
        val stripped = fakes.writes.first()
        assertFalse(stripped.decodeToString().contains("Canary"))
        // detectFile writes nothing, so it never asks for the metadata, whatever the policy.
        masker.detectFile(source, keep)
        assertEquals(1, fakes.metadataReads)
    }

    @Test
    fun theCallIsGuardedLikeTheInMemoryCalls() {
        masker.close()
        assertFailsWith<FaceMaskException.Closed> { masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg")) }
        assertFailsWith<FaceMaskException.Closed> { masker.detectFile(source) }
        assertTrue(fakes.opened.isEmpty())
        // A platform part's unexpected exception becomes Internal with a fixed message and nothing attached.
        masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
        fakes.fail = "open"
        fakes.failure = { IllegalStateException("/secret/path.jpg") }
        val e = assertFailsWith<FaceMaskException.Internal> { masker.detectFile(source) }
        assertEquals("Internal error: unexpected failure", e.message)
        assertDetailFree(e)
        fakes.fail = null
    }

    /** Fails if [e] carries a cause, a suppressed exception or [SECRET] anywhere in its text or stack trace. */
    private fun assertDetailFree(e: Throwable, what: String = "") {
        assertNull(e.cause, "$what: no cause")
        assertTrue(e.suppressedExceptions.isEmpty(), "$what: no suppressed exception: ${e.suppressedExceptions}")
        assertFalse(SECRET in e.toString(), "$what: $e")
        assertFalse(SECRET in e.stackTraceToString(), "$what: the platform text is in the stack trace")
    }

    /**
     * A platform part that does not map its failure (its text carries a path): at every step, before and after
     * another failure (a close), and in the stream form, the call throws Internal with a fixed message and no cause
     * or suppressed exception, so no platform text reaches the app (also not through a logged stack trace).
     */
    @Test
    fun noPlatformExceptionTextReachesTheAppThroughTheThrownException() {
        val steps = listOf(
            "open",
            "check",
            "decode 800x600",
            "close opened",
            "scale 400x300",
            "encode scaled q90",
            "close scaled",
            "close working",
            "write",
        )
        for (step in steps) {
            fakes.events.clear()
            fakes.writes.clear()
            fakes.fail = step
            fakes.failure = { IllegalStateException("$SECRET/$step.jpg") }
            val e = assertFailsWith<FaceMaskException.Internal>(step) {
                masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), FileOptions(0, 400))
            }
            assertEquals("Internal error: unexpected failure", e.message, step)
            assertDetailFree(e, step)
            assertTrue(fakes.writes.isEmpty(), step)
            assertEquals(0, fakes.openImages, step)
        }
        // detectFile.
        fakes.fail = "decode 800x600"
        val detected = assertFailsWith<FaceMaskException.Internal> { masker.detectFile(source, sourceSize) }
        assertDetailFree(detected, "detect")
        // A close that fails after another failure (here a wrong decoded size; before: an IoFailed of the platform):
        // the first failure is reported with nothing attached.
        fakes.fail = "close working"
        fakes.decodedSize = Size(1, 1)
        val sized = assertFailsWith<FaceMaskException.Internal> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        }
        assertTrue("another size" in sized.message.orEmpty(), sized.message)
        assertDetailFree(sized, "close after a failure")
        fakes.decodedSize = null
        val primary = FaceMaskException.IoFailed("the source could not be read")
        var calls = 0
        fakes.failure = { if (calls++ == 0) primary else IllegalStateException("$SECRET/close.jpg") }
        fakes.fail = "decode 800x600"
        fakes.failAlso = "close opened"
        assertSame(primary, assertFailsWith<FaceMaskException.IoFailed> { masker.detectFile(source, sourceSize) })
        assertEquals(2, calls, "the decode failed, then the close")
        assertDetailFree(primary, "a mapped failure passes unchanged")
        fakes.failAlso = null
        fakes.fail = "open"
        // A platform FaceMaskException with something attached broke the seam rule: Internal, nothing attached.
        val attached = listOf(
            FaceMaskException.Internal("planted", IllegalStateException("$SECRET/cause.jpg")),
            FaceMaskException.IoFailed("planted").apply { addSuppressed(IllegalStateException("$SECRET/s.jpg")) },
        )
        for (planted in attached) {
            fakes.failure = { planted }
            val e = assertFailsWith<FaceMaskException.Internal> { masker.detectFile(source) }
            assertEquals("Internal error: a platform part failed", e.message)
            assertDetailFree(e, "planted $planted")
        }
        // The platform buffer fails while the masks are copied into it: MaskWriteFailed without the cause.
        fakes.fail = null
        fakes.events.clear()
        fakes.writes.clear()
        fakes.pixelWriteFailure = { IllegalStateException("$SECRET/pixels.jpg") }
        val write = assertFailsWith<FaceMaskException.MaskWriteFailed> {
            masker.maskFile(source, OutputTarget.NewFile("/out/a.jpg"), sourceSize)
        }
        assertDetailFree(write, "mask write")
        assertNothingWrittenAndAllClosed()
        fakes.pixelWriteFailure = null
        // Stream form: the reader and the sink.
        val reader = StreamReader { _, _, _ -> throw IllegalStateException("$SECRET/stream") }
        val read = assertFailsWith<FaceMaskException.Internal> {
            masker.maskStream(reader, { fail("no write") }, sourceSize, null, CallContext(null))
        }
        assertDetailFree(read, "stream reader")
        val sink = StreamSink { throw IllegalStateException("$SECRET/sink") }
        val sunk = assertFailsWith<FaceMaskException.Internal> {
            masker.maskStream(CountingStream(1_000), sink, sourceSize, null, CallContext(null))
        }
        assertDetailFree(sunk, "stream sink")
    }

    /** Every core failure gets the same public type and message as in-memory mode, without the cause. */
    @Test
    fun coreFailuresAreMappedWithoutTheirCause() {
        val cause = IllegalStateException("$SECRET/core.jpg")
        val failures = listOf(
            CoreFailure.InvalidSetting("expandX", "0 to 1"),
            CoreFailure.InvalidFaceBox("not finite"),
            CoreFailure.InvalidMask("inverted"),
            CoreFailure.InvalidDetection("not finite"),
            CoreFailure.ImageTooLarge(70_000, 10),
            CoreFailure.MaskWorkLimit(10L, 5L),
            CoreFailure.NotEnoughMemory(cause),
            CoreFailure.WriteInterrupted(cause),
            CoreFailure.WriteNotApplied(),
        )
        for (f in failures) {
            val chained = f.toPublic()
            val plain = FileModeRun.withoutDetails(f)
            assertEquals(chained::class, plain::class, "$f")
            assertEquals(chained.message, plain.message, "$f")
            assertDetailFree(plain, "$f")
        }
    }

    private fun jpeg(quality: Int) = OutputFormat.Jpeg(quality)

    private companion object {
        /** The platform text that must never reach the app (a stand-in for a user's photo path). */
        const val SECRET = "/secret-canary-path"
    }
}
