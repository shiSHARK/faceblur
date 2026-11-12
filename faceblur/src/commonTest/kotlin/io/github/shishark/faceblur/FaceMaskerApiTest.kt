// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.YUNET_STRIDES
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A runner that "sees" one face (stride 32, row 4, column 4: about 64 × 64 px at (112, 112)), or NaN scores. */
internal class OneFaceRunner(var nan: Boolean = false) : TensorRunner {
    override val inputWidth = 640
    override val inputHeight = 640
    var runs = 0
    var closed = 0

    override fun initialize() = Unit

    override fun close() {
        closed++
    }

    override fun run(input: FloatArray): Map<String, FloatArray> {
        runs++
        val m = HashMap<String, FloatArray>()
        for (s in YUNET_STRIDES) {
            val n = (640 / s) * (640 / s)
            m["cls_$s"] = FloatArray(n)
            m["obj_$s"] = FloatArray(n)
            m["bbox_$s"] = FloatArray(n * 4)
        }
        val idx = 4 * 20 + 4
        m.getValue("cls_32")[idx] = if (nan) Float.NaN else 0.9f
        m.getValue("obj_32")[idx] = 0.9f
        m.getValue("bbox_32")[idx * 4 + 2] = ln(2f)
        m.getValue("bbox_32")[idx * 4 + 3] = ln(2f)
        return m
    }
}

class FaceMaskerApiTest {
    private fun masker(runner: OneFaceRunner = OneFaceRunner(), config: FaceMaskerConfig = FaceMaskerConfig()) =
        FaceMasker.forTest(testYuNetModel(), config, RunnerRuntime(runner))

    private fun image() = IntArrayPixels(640, 480, gradientPixels(640, 480))

    /** A token that answers "cancelled" from the [from]-th read on (1-based). */
    private class CountingToken(private val from: Int) : CancellationToken {
        var reads = 0

        override val isCancellationRequested: Boolean
            get() = ++reads >= from
    }

    @Test
    fun publicDefaultsAndBounds() {
        val m = MaskingConfig()
        assertEquals(MaskStyle.GRADIENT_OVAL, m.style)
        assertEquals(MaskShape.ELLIPSE, m.shape)
        assertEquals(1.6f, m.expandX)
        assertEquals(1.8f, m.expandY)
        assertEquals(0.10f, m.feather)
        assertEquals(0xFF7F7F7F.toInt(), m.color)
        assertEquals(0, FaceMaskerConfig().onnxThreads)
        assertEquals(m, FaceMaskerConfig().masking)
        val bad =
            listOf<Pair<String, () -> Any>>(
                "expandX" to { MaskingConfig(expandX = 1.59f) },
                "expandX" to { MaskingConfig(expandX = 3.01f) },
                "expandX" to { MaskingConfig(expandX = Float.NaN) },
                "expandY" to { MaskingConfig(expandY = 1.79f) },
                "expandY" to { MaskingConfig(expandY = Float.POSITIVE_INFINITY) },
                "feather" to { MaskingConfig(feather = -0.01f) },
                "feather" to { MaskingConfig(feather = 0.51f) },
                "color" to { MaskingConfig(color = 0xFE7F7F7F.toInt()) },
                "onnxThreads" to { FaceMaskerConfig(onnxThreads = -1) },
                "onnxThreads" to { FaceMaskerConfig(onnxThreads = 5) },
            )
        for ((parameter, make) in bad) {
            val e = assertFailsWith<FaceMaskException.InvalidConfiguration>(parameter) { make() }
            assertEquals(parameter, e.parameter)
        }
        // Controls: the bounds themselves are accepted, and the builders give equal objects.
        MaskingConfig(expandX = 3f, expandY = 3f, feather = 0.5f)
        MaskingConfig(feather = 0f, shape = MaskShape.RECTANGLE, style = MaskStyle.SOLID)
        FaceMaskerConfig(onnxThreads = 4)
        val built = MaskingConfig.Builder().style(
            MaskStyle.SOLID,
        ).shape(MaskShape.RECTANGLE).expandX(2f).expandY(2f).feather(0.2f).color(-1).build()
        assertEquals(MaskingConfig(MaskStyle.SOLID, MaskShape.RECTANGLE, 2f, 2f, 0.2f, -1), built)
        assertEquals(built.hashCode(), MaskingConfig(MaskStyle.SOLID, MaskShape.RECTANGLE, 2f, 2f, 0.2f, -1).hashCode())
        assertEquals(FaceMaskerConfig(built, 4), FaceMaskerConfig.Builder().masking(built).onnxThreads(4).build())
        assertNotEquals(FaceMaskerConfig(), FaceMaskerConfig(onnxThreads = 1))
        assertTrue(FaceMaskerConfig().toString().contains("GRADIENT_OVAL"))
    }

    @Test
    fun maskFactoriesUseTheMeasuredGeometryAndRejectInvalidBoxes() {
        val box = FaceBox.create(100f, 100f, 300f, 340f, 0.9f)
        val m = Mask.fromFace(box)
        assertEquals(200f, m.centerX)
        assertEquals(220f - 0.08f * 240f, m.centerY)
        assertEquals(200f * 1.6f / 2f, m.radiusX)
        assertEquals(240f * 1.8f / 2f, m.radiusY)
        assertEquals(MaskOrigin.DETECTED, m.origin)
        val manual = Mask.manual(100f, 100f, 300f, 340f)
        assertEquals(MaskOrigin.MANUAL, manual.origin)
        assertEquals(m.radiusX, manual.radiusX)
        assertEquals(MaskShape.RECTANGLE, Mask.fromFace(box, MaskingConfig(shape = MaskShape.RECTANGLE)).shape)
        // Each error names the public parameter that was rejected (ADR 0002 decision 9).
        val invalidManual =
            listOf(
                "left" to listOf(Float.NaN, 0f, 1f, 1f),
                "right" to listOf(5f, 0f, 1f, 1f),
                "right" to listOf(0f, 0f, 0f, 1f),
                "bottom" to listOf(0f, 0f, 1f, Float.POSITIVE_INFINITY),
                "bottom" to listOf(0f, 3f, 1f, 3f),
            )
        for ((parameter, args) in invalidManual) {
            val e =
                assertFailsWith<FaceMaskException.InvalidConfiguration>("$args") {
                    Mask.manual(args[0], args[1], args[2], args[3])
                }
            assertEquals(parameter, e.parameter, "$args")
        }
        assertEquals(
            "radiusX",
            assertFailsWith<FaceMaskException.InvalidConfiguration> {
                Mask.exact(MaskShape.ELLIPSE, 1f, 1f, 0f, 1f)
            }.parameter,
        )
        assertEquals(
            "radiusY",
            assertFailsWith<FaceMaskException.InvalidConfiguration> {
                Mask.exact(MaskShape.ELLIPSE, 1f, 1f, 1f, Float.NaN)
            }.parameter,
        )
        assertEquals(
            "centerX",
            assertFailsWith<FaceMaskException.InvalidConfiguration> {
                Mask.exact(MaskShape.ELLIPSE, Float.NaN, 1f, 1f, 1f)
            }.parameter,
        )
        assertEquals(
            "box",
            assertFailsWith<FaceMaskException.InvalidConfiguration> {
                Mask.fromFace(FaceBox.create(5f, 5f, 5f, 9f, 1f))
            }.parameter,
        )
        assertFailsWith<FaceMaskException.InvalidConfiguration> { MaskPlan(-1, 10, emptyList()) }
        val exact = Mask.exact(MaskShape.ELLIPSE, 10f, 10f, 0.25f, 4f)
        assertEquals(exact, Mask.exact(MaskShape.ELLIPSE, 10f, 10f, 0.25f, 4f))
        assertTrue(exact.toString().startsWith("Mask("))
        assertEquals(MaskPlan(4, 4, listOf(exact)), MaskPlan(4, 4, listOf(exact)))
        assertEquals(MaskPlan(4, 4, listOf(exact)).hashCode(), MaskPlan(4, 4, listOf(exact)).hashCode())
        assertEquals("MaskPlan(width=4, height=4, masks=1)", MaskPlan(4, 4, listOf(exact)).toString())
    }

    @Test
    fun detectReturnsTheFaceInImagePixelsAndWritesNothing() {
        val img = image()
        val before = img.data.copyOf()
        val d = masker().use { it.detect(img) }
        assertContentEquals(before, img.data)
        assertEquals(1, d.faces.size)
        assertEquals(640, d.width)
        assertEquals(480, d.height)
        assertEquals(480, d.outputHeight)
        assertEquals(1, d.timings.passes)
        assertFalse(d.hasGainMap)
        assertEquals(MaskingConfig(), d.masking)
        assertEquals(1, d.toMaskPlan().masks.size)
        assertEquals(MaskShape.RECTANGLE, d.toMaskPlan(MaskingConfig(shape = MaskShape.RECTANGLE)).masks.single().shape)
        assertEquals(d, d)
        assertTrue(d.toString().startsWith("Detection(width=640"))
    }

    @Test
    fun maskInPlaceEqualsDetectThenApplyMasks() {
        val a = image()
        val b = image()
        val r1 = masker().use { it.maskInPlace(a) }
        masker().use { m -> m.applyMasks(b, m.detect(b).toMaskPlan()) }
        assertContentEquals(a.data, b.data)
        assertEquals(1, r1.faces.size)
        assertEquals(1, r1.masks.size)
        assertEquals(1, r1.timings.passes)
        assertFalse(r1.gainMapRemoved)
        assertTrue(r1.toString().startsWith("MaskResult(width=640"))
        assertEquals(r1, r1)
        assertEquals(r1.hashCode(), r1.hashCode())
    }

    @Test
    fun aPlanForAnotherSizeIsRefusedAndNothingIsWritten() {
        val img = image()
        val before = img.data.copyOf()
        val plan = MaskPlan(640, 481, listOf(Mask.manual(10f, 10f, 50f, 50f)))
        assertFailsWith<FaceMaskException.MaskPlanMismatch> { masker().use { it.applyMasks(img, plan) } }
        assertContentEquals(before, img.data)
        val result = masker().use { it.applyMasks(img, MaskPlan(640, 480, plan.masks)) }
        assertTrue(result.faces.isEmpty(), "control: a matching plan is applied; applyMasks reports no detected faces")
        assertEquals(0, result.timings.passes)
    }

    @Test
    fun cancellationBeforeTheCopyLeavesTheImageUntouched() {
        // Reads 1-2 come before detection and before the pass; the later ones from the mask stage (before it, between
        // masks and right before the final copy). A cancellation seen at any of them leaves the image untouched.
        val reads = CountingToken(Int.MAX_VALUE).also { t -> masker().use { it.maskInPlace(image(), t) } }.reads
        assertTrue(reads >= 5, "the stage reads cancellation before the copy ($reads reads)")
        for (from in 1 until reads) {
            val img = image()
            val before = img.data.copyOf()
            assertFailsWith<FaceMaskException.Cancelled>("read $from") {
                masker().use { it.maskInPlace(img, CountingToken(from)) }
            }
            assertContentEquals(before, img.data, "cancelled at read $from: untouched")
        }
    }

    @Test
    fun cancellationDuringTheCopyCompletesEveryMaskFirst() {
        val expected = image().also { masker().use { m -> m.maskInPlace(it) } }
        // Cancellation is requested from inside the first write into the image: the copy is finished first, then
        // the call reports it, with the image equal to the uncancelled result.
        var requested = false
        val token = object : CancellationToken {
            override val isCancellationRequested: Boolean get() = requested
        }
        val base = image()
        val img = object : MutablePixelImage {
            override val width = base.width
            override val height = base.height
            var writes = 0

            override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) = base.readPixels(x, y, w, h, dst)

            override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
                writes++
                requested = true
                base.writePixels(x, y, w, h, src)
            }
        }
        assertFailsWith<FaceMaskException.Cancelled> { masker().use { it.maskInPlace(img, token) } }
        assertTrue(img.writes > 1, "every run of the copy was written (${img.writes})")
        assertContentEquals(expected.data, base.data, "fully masked, never half-masked")
    }

    @Test
    fun runtimeFaultsAndLostWritesBecomeTypedErrors() {
        // A model fault at creation is refused by the contract run; after creation it fails the pass.
        val atCreation = assertFailsWith<FaceMaskException.ModelRejected> { masker(OneFaceRunner(nan = true)) }
        assertEquals(listOf("CONTRACT_VALUES"), atCreation.findings)
        val runner = OneFaceRunner()
        masker(runner).use { m ->
            runner.nan = true
            val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { m.detect(image()) }
            assertEquals(listOf("NON_FINITE"), e.findings)
        }
        val lossy =
            object : MutablePixelImage {
                val base = image()
                override val width = 640
                override val height = 480

                override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) =
                    base.readPixels(x, y, w, h, dst)

                override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) = Unit
            }
        assertFailsWith<FaceMaskException.MaskWriteFailed> { masker().use { it.maskInPlace(lossy) } }
    }

    @Test
    fun oversizedImagesAreRefusedBeforeAnyCallback() {
        var calls = 0
        val huge =
            object : MutablePixelImage {
                override val width = 50_000
                override val height = 50_000

                override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
                    calls++
                }

                override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
                    calls++
                }
            }
        masker().use { m ->
            val e = assertFailsWith<FaceMaskException.ImageTooLarge> { m.detect(huge) }
            assertEquals(Int.MAX_VALUE.toLong(), e.limit)
            assertFailsWith<FaceMaskException.ImageTooLarge> { m.maskInPlace(huge) }
            assertFailsWith<FaceMaskException.ImageTooLarge> {
                m.applyMasks(huge, MaskPlan(50_000, 50_000, emptyList()))
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun closeIsIdempotentAndLaterCallsFail() {
        val runner = OneFaceRunner()
        val m = masker(runner)
        m.close()
        m.close()
        assertEquals(1, runner.closed)
        assertFailsWith<FaceMaskException.Closed> { m.detect(image()) }
        assertFailsWith<FaceMaskException.Closed> { m.maskInPlace(image()) }
    }

    @Test
    fun aRuntimeThatCannotStartIsReported() {
        val failing = ModelRuntime { _, _ -> throw IllegalStateException("no session") }
        val e =
            assertFailsWith<FaceMaskException.RuntimeUnavailable> {
                FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), failing)
            }
        assertEquals(null, e.cause, "a runtime exception is never chained")
        assertFailsWith<FaceMaskException.RuntimeUnavailable> {
            FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), null)
        }
    }

    @Test
    fun errorMessagesCarryNoCoordinates() {
        val e = assertFailsWith<FaceMaskException.InvalidConfiguration> { Mask.manual(123.25f, 456.5f, 100f, 500f) }
        assertFalse(e.message!!.contains("123.25") || e.message!!.contains("456.5"), e.message)
    }
}
