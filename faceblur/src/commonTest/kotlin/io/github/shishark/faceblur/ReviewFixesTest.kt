// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.mask.MaskGeometry
import io.github.shishark.faceblur.mask.MaskPainter
import io.github.shishark.faceblur.mask.MaskSettings
import io.github.shishark.faceblur.mask.PreparedMask
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Regression tests for fail-closed behaviour, bounds and error typing found in the ST-002 reviews. */
class ReviewFixesTest {
    private val w = 64
    private val h = 48

    private fun masker(runner: TensorRunner = OneFaceRunner()) =
        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(runner))

    private fun image() = IntArrayPixels(w, h, randomPixels(w, h, 3))

    /** Masks beyond the supported range are refused when they are created, with the property named. */
    @Test
    fun hugeMasksAreRefusedOnCreationWithTheParameterNamed() {
        val cases =
            listOf<Pair<String, () -> Mask>>(
                "radiusX" to { Mask.exact(MaskShape.ELLIPSE, 10f, 10f, 3e9f, 3e9f) },
                "radiusX" to { Mask.exact(MaskShape.RECTANGLE, 10f, 10f, 1e30f, 1e30f) },
                "radiusY" to { Mask.exact(MaskShape.ELLIPSE, 10f, 10f, 5f, 4_194_305f) },
                "centerX" to { Mask.exact(MaskShape.ELLIPSE, 2e9f, 10f, 5f, 5f) },
                "centerY" to { Mask.exact(MaskShape.ELLIPSE, 10f, -2e9f, 5f, 5f) },
                "left" to { Mask.manual(-2e9f, -2e9f, 2e9f, 2e9f) },
                "left" to { Mask.manual(-3e38f, -3e38f, 3e38f, 3e38f) },
                "right" to { Mask.manual(-4e6f, -4e6f, 4e6f, 4e6f) }, // edges in range, expanded mask too large
                "left" to { Mask.manual(Float.NaN, 0f, 1f, 1f) },
            )
        for ((parameter, make) in cases) {
            val e = assertFailsWith<FaceMaskException.InvalidConfiguration>(parameter) { make() }
            assertEquals(parameter, e.parameter)
        }
        // Controls: the largest accepted values are accepted.
        Mask.exact(MaskShape.ELLIPSE, -4_194_304f, 4_194_304f, 4_194_304f, 4_194_304f)
        Mask.manual(-1_000_000f, -1_000_000f, 1_000_000f, 1_000_000f)
    }

    /** Root cause: the extent of a mask larger than the Int range covers the whole image, never nothing. */
    @Test
    fun theExtentOfAnyMaskIsComputedWithoutIntOverflow() {
        for (r in listOf(3e9f, 1e30f, Float.MAX_VALUE)) {
            val m = PreparedMask(MaskGeometry(32f, 24f, r, r, MaskShape.ELLIPSE), MaskSettings())
            val e = m.extent(1.1f, w, h)
            assertEquals(listOf(0, 0, w, h), listOf(e.left, e.top, e.right, e.bottom), "radius $r")
        }
        val far = PreparedMask(MaskGeometry(-2e9f, 24f, 5f, 5f, MaskShape.ELLIPSE), MaskSettings()).extent(1f, w, h)
        assertTrue(far.isEmpty, "a mask far outside the image touches nothing")
        // The engine refuses out-of-range geometry with a typed error and never calls into the image.
        var calls = 0
        val img =
            object : MutablePixelImage {
                override val width = w
                override val height = h

                override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
                    calls++
                }

                override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
                    calls++
                }
            }
        assertFailsWith<CoreFailure.InvalidMask> {
            MaskPainter.applyMasks(img, listOf(MaskGeometry(32f, 24f, 3e9f, 3e9f, MaskShape.ELLIPSE)), MaskSettings())
        }
        assertEquals(0, calls)
    }

    /** The largest accepted mask covers the whole image opaquely, for every style and both shapes. */
    @Test
    fun theLargestAcceptedMaskMasksTheWholeImage() {
        for (style in MaskStyle.entries) {
            for (shape in MaskShape.entries) {
                val img = IntArrayPixels(w, h, IntArray(w * h) { 0x00123456 })
                val mask = Mask.exact(shape, 32f, 24f, 4_194_304f, 4_194_304f)
                masker().use { it.applyMasks(img, MaskPlan(w, h, listOf(mask)), null) }
                assertTrue(img.data.all { it ushr 24 == 0xFF }, "$style/$shape: every pixel is opaque mask")
            }
        }
        // A large centre offset with a radius that still reaches into the image: every pixel with r <= 1 is masked.
        val img = IntArrayPixels(w, h, IntArray(w * h) { 0x00123456 })
        val mask = Mask.exact(MaskShape.ELLIPSE, -4_000_000f, 24f, 4_000_040f, 4_000_000f)
        masker().use { it.applyMasks(img, MaskPlan(w, h, listOf(mask))) }
        var inside = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x + 0.5 - mask.centerX) / mask.radiusX
                val dy = (y + 0.5 - mask.centerY) / mask.radiusY
                if (dx * dx + dy * dy <= 1.0 - 1e-6) {
                    inside++
                    assertEquals(0xFF, img.data[y * w + x] ushr 24, "($x,$y)")
                }
            }
        }
        assertTrue(inside > 1000, "$inside pixels inside")
    }

    /** Once writing has started, any failure is MaskWriteFailed (discard the image), with the cause kept. */
    @Test
    fun aFailureAfterWritingStartedIsMaskWriteFailed() {
        class Faulty(val failWriteNumber: Int, val failReads: Boolean = false) : MutablePixelImage {
            val base = IntArrayPixels(640, 480, gradientPixels(640, 480))
            var writes = 0
            override val width = 640
            override val height = 480

            override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
                if (failReads) throw IllegalStateException("read failed")
                base.readPixels(x, y, w, h, dst)
            }

            override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
                if (++writes == failWriteNumber) throw IllegalStateException("write failed")
                base.writePixels(x, y, w, h, src)
            }
        }
        val plan = MaskPlan(640, 480, listOf(Mask.manual(50f, 50f, 150f, 170f), Mask.manual(400f, 200f, 500f, 330f)))
        for (n in 1..2) {
            val img = Faulty(failWriteNumber = n)
            val e =
                assertFailsWith<FaceMaskException.MaskWriteFailed>("write $n") {
                    masker().use { it.applyMasks(img, plan) }
                }
            assertEquals("write failed", e.cause?.message)
        }
        // Control: a failure before the first write leaves the image untouched and is an internal error.
        val untouched = Faulty(failWriteNumber = 99, failReads = true)
        val before = untouched.base.data.copyOf()
        assertFailsWith<FaceMaskException.Internal> { masker().use { it.applyMasks(untouched, plan) } }
        assertContentEquals(before, untouched.base.data)
        assertEquals(0, untouched.writes)
    }

    /** Each side is limited to 65,535 px, checked before any call into the image. */
    @Test
    fun extremeSidesAreRefusedBeforeAnyCallback() {
        var calls = 0
        fun virtual(iw: Int, ih: Int) = object : MutablePixelImage {
            override val width = iw
            override val height = ih

            override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
                calls++
            }

            override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
                calls++
            }
        }
        masker().use { m ->
            for ((iw, ih) in listOf(Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE, 65_536 to 10, 10 to 65_536)) {
                val e = assertFailsWith<FaceMaskException.ImageTooLarge>("$iw x $ih") { m.detect(virtual(iw, ih)) }
                assertTrue(e.message!!.contains("65535"))
                assertFailsWith<FaceMaskException.ImageTooLarge> {
                    m.applyMasks(virtual(iw, ih), MaskPlan(iw, ih, emptyList()))
                }
            }
            assertEquals(0, calls)
            // Control: the largest side is accepted.
            assertEquals(1, m.detect(IntArrayPixels(65_535, 1)).timings.passes.coerceAtMost(1))
        }
    }

    /** Unexpected Throwables (not only Exceptions) become typed errors. */
    @Test
    fun unexpectedThrowablesBecomeTypedErrors() {
        for (t in listOf<Throwable>(NotImplementedError("x"), AssertionError("x"), IllegalStateException("x"))) {
            var failing = false
            val runner =
                object : TensorRunner by OneFaceRunner() {
                    override fun run(input: FloatArray): Map<String, FloatArray> =
                        if (failing) throw t else OneFaceRunner().run(input)
                }
            val m = masker(runner)
            failing = true
            // A failure during a pass is the model's or runtime's: RUN_FAILED, never chained (its text could hold paths).
            val e =
                assertFailsWith<FaceMaskException.ModelOutputInvalid>("${t::class}") {
                    m.use { it.detect(IntArrayPixels(64, 48)) }
                }
            assertEquals(listOf("RUN_FAILED"), e.findings)
            assertEquals(null, e.cause)
            val failingFactory = ModelRuntime { _, _ -> throw t }
            val r =
                assertFailsWith<FaceMaskException.RuntimeUnavailable> {
                    FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), failingFactory)
                }
            assertEquals(null, r.cause)
        }
    }

    /** Every bound is checked with the next representable value outside it. */
    @Test
    fun boundsHoldAtTheNextRepresentableValue() {
        val bad =
            listOf<Pair<String, () -> Any>>(
                "expandX" to { MaskingConfig(expandX = 1.6f.nextDown()) },
                "expandX" to { MaskingConfig(expandX = 3.0f.nextUp()) },
                "expandY" to { MaskingConfig(expandY = 1.8f.nextDown()) },
                "expandY" to { MaskingConfig(expandY = 3.0f.nextUp()) },
                "feather" to { MaskingConfig(feather = 0f.nextDown()) },
                "feather" to { MaskingConfig(feather = 0.5f.nextUp()) },
                "radiusX" to { Mask.exact(MaskShape.ELLIPSE, 0f, 0f, 4_194_304f.nextUp(), 1f) },
                "centerX" to { Mask.exact(MaskShape.ELLIPSE, (-4_194_304f).nextDown(), 0f, 1f, 1f) },
            )
        for ((parameter, make) in bad) {
            assertEquals(
                parameter,
                assertFailsWith<FaceMaskException.InvalidConfiguration>(parameter) {
                    make()
                }.parameter,
            )
        }
        // -0.0 equals 0.0 and is accepted as feather 0 (documented choice); the bounds themselves are accepted.
        assertTrue(MaskingConfig(feather = -0f).feather == 0f)
        MaskingConfig(expandX = 1.6f, expandY = 1.8f, feather = 0.5f)
        MaskingConfig(expandX = 3f, expandY = 3f, feather = 0f)
    }

    /** ST-008 AC5 and the AC1 timings: 200 sequential calls on one instance, then close; passes = 1 + tiles. */
    @Test
    fun twoHundredCallsReuseOneSessionAndPassesCountTheTiles() {
        val runner = OneFaceRunner()
        var created = 0
        val m =
            FaceMasker.forTest(
                testYuNetModel(),
                FaceMaskerConfig(),
                ModelRuntime { model, _ -> RunnerRuntime(runner).open(model, 0).also { created++ } },
            )
        repeat(200) { assertEquals(1, m.detect(IntArrayPixels(64, 48)).timings.passes) }
        assertEquals(1, created)
        assertEquals(201, runner.runs, "one contract run at creation, then one pass per call")
        assertEquals(5, m.detect(IntArrayPixels(1000, 800)).timings.passes, "1 global pass + 4 tiles of 640 px")
        m.close()
        assertFailsWith<FaceMaskException.Closed> { m.detect(IntArrayPixels(64, 48)) }
        assertEquals(1, runner.closed)
    }

    /** Mask.toString carries no coordinates. */
    @Test
    fun maskTextHasNoCoordinates() {
        val text = Mask.exact(MaskShape.ELLIPSE, 123.25f, 456.5f, 7.75f, 8.5f).toString()
        assertEquals("Mask(shape=ELLIPSE, origin=MANUAL)", text)
        assertTrue(listOf("123", "456", "7.75", "8.5").none { it in text })
    }
}
