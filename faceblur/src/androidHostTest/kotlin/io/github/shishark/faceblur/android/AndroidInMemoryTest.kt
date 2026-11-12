// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Gainmap
import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskResult
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.applyMasks
import io.github.shishark.faceblur.detect
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.MemoryRule
import io.github.shishark.faceblur.mask.ReservedBytes
import io.github.shishark.faceblur.mask.StageAllocator
import io.github.shishark.faceblur.mask.StageSeams
import io.github.shishark.faceblur.maskInPlace
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android in-memory mode (`detect`, `applyMasks`, `maskInPlace` on a `Bitmap`; ST-015, FR-IM-001..003, FR-IM-008,
 * FR-IM-009) on the host with Robolectric native graphics (real Skia bitmaps) and a fake model session that reports one
 * face. Pixel-level checks: every pixel inside a mask (normalised radius <= 1, the library's own definition) is opaque
 * and changed, every pixel beyond the soft edge of every mask is exactly the original, and the whole bitmap equals the
 * common route on a plain pixel array (the oracle).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AndroidInMemoryTest {
    private lateinit var runner: OneFaceRunner
    private lateinit var masker: FaceMasker

    @Before
    fun createMasker() {
        runner = OneFaceRunner()
        masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(runner))
    }

    @After
    fun closeMasker() {
        masker.close()
        assertEquals(0L, ReservedBytes.current(), "no working area stays reserved")
    }

    /** A plain pixel array as a [MutablePixelImage]: the common route the oracle runs on. */
    private class ArrayImage(override val width: Int, override val height: Int, val px: IntArray) : MutablePixelImage {
        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
            for (r in 0 until h) px.copyInto(dst, r * w, (y + r) * width + x, (y + r) * width + x + w)
        }

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            for (r in 0 until h) src.copyInto(px, (y + r) * width + x, r * w, r * w + w)
        }
    }

    /** The fake model with no face: every score 0. */
    private class NoFaceRunner : TensorRunner by OneFaceRunner() {
        override fun run(input: FloatArray): Map<String, FloatArray> =
            OneFaceRunner().run(input).mapValues { (k, v) -> if (k.startsWith("cls_")) FloatArray(v.size) else v }
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also {
        b.getPixels(it, 0, b.width, 0, 0, b.width, b.height)
    }

    private fun withGainMap(b: Bitmap): Bitmap = b.apply {
        gainmap = Gainmap(Bitmap.createBitmap(16, 12, Bitmap.Config.ALPHA_8))
        assertTrue(hasGainmap(), "precondition: the bitmap carries a gain map")
    }

    /** Largest normalised radius at which a mask can still change a pixel: the soft edge plus a 2 px band. */
    private fun outerRadius(m: Mask): Float = 1f + FaceMaskerConfig().masking.feather + 2f / minOf(m.radiusX, m.radiusY)

    /**
     * The pixel rules for [after] against [before] and [masks]: inside every mask alpha 255 and changed; beyond every
     * mask's soft edge exactly the original. Returns the number of inside pixels checked.
     */
    private fun assertMaskedPixels(before: IntArray, after: IntArray, width: Int, masks: List<Mask>): Int {
        assertTrue(masks.isNotEmpty(), "the fake model reports a face")
        val geometries = masks.map { it to it.toGeometry() }
        var inside = 0
        var changed = 0
        for (i in before.indices) {
            val x = i % width
            val y = i / width
            val r = geometries.minOf { (_, g) -> g.normalizedRadius(x + 0.5f, y + 0.5f) }
            if (r <= 1f) {
                inside++
                if (after[i] != before[i]) changed++
                assertEquals(0xFF, after[i] ushr 24, "alpha 255 inside a mask at ($x, $y) (FR-IM-008)")
            } else if (geometries.all { (m, g) -> g.normalizedRadius(x + 0.5f, y + 0.5f) > outerRadius(m) }) {
                assertEquals(before[i], after[i], "outside every mask the pixel is unchanged at ($x, $y)")
            }
        }
        assertTrue(inside > 1000, "the check covered the inside of the masks ($inside px)")
        // A fill value can equal a source pixel by chance, so "changed" is counted, not required per pixel; the exact
        // values are checked against the common route (the oracle) by the callers.
        assertTrue(changed >= inside * 0.95, "the masks changed the inside ($changed of $inside px)")
        return inside
    }

    @Test
    fun maskInPlaceMasksTheSameBitmapOpaqueInsideAndUnchangedOutside() {
        val bitmap = TestImages.picture(640, 480)
        val before = pixels(bitmap)
        val result = masker.maskInPlace(bitmap)
        assertEquals(640 to 480, result.width to result.height)
        assertEquals(1, result.faces.size)
        assertFalse(result.gainMapRemoved, "no gain map, nothing removed")
        assertMaskedPixels(before, pixels(bitmap), 640, result.masks)
        // Oracle: the common route on a plain pixel array gives exactly the same pixels and masks.
        val oracle = ArrayImage(640, 480, before.copyOf())
        val expected = masker.maskInPlace(oracle)
        assertEquals(expected.masks, result.masks)
        assertContentEquals(oracle.px, pixels(bitmap), "the bitmap holds exactly the common route's pixels")
    }

    @Test
    fun alphaInsideMasksIsForcedTo255AndTranslucentPixelsOutsideAreNotTouched() {
        // Alpha 0 in the left half, 100 below, 255 elsewhere; the fake face lies across these regions.
        val bitmap = TestImages.picture(640, 480) { x, y ->
            if (x < 160) {
                0
            } else if (y > 160) {
                100
            } else {
                255
            }
        }
        val before = pixels(bitmap)
        assertTrue(before.any { it ushr 24 == 0 } && before.any { it ushr 24 == 100 }, "control: translucent pixels")
        val result = masker.maskInPlace(bitmap)
        val after = pixels(bitmap)
        assertMaskedPixels(before, after, 640, result.masks)
        val g = result.masks.map { it.toGeometry() }
        val insideLow = before.indices.count { i ->
            before[i] ushr 24 < 255 && g.any { it.containsPixel(i % 640, i / 640) }
        }
        assertTrue(insideLow > 100, "the masks cover source pixels with alpha below 255 ($insideLow)")
        val oracle = ArrayImage(640, 480, before.copyOf())
        masker.maskInPlace(oracle)
        // Inside the masks the colours equal the oracle; outside nothing was written, so getPixels is unchanged.
        for (i in before.indices) {
            if (g.any { it.containsPixel(i % 640, i / 640) }) assertEquals(oracle.px[i], after[i], "pixel $i")
        }
    }

    @Test
    fun applyMasksWritesThePlanAndDetectReadsAnImmutableBitmapWithoutWriting() {
        val source = TestImages.picture(640, 480)
        val immutable = source.copy(Bitmap.Config.ARGB_8888, false)
        assertFalse(immutable.isMutable)
        val before = pixels(immutable)
        val found = masker.detect(immutable)
        assertEquals(1, found.faces.size)
        assertFalse(found.hasGainMap)
        assertContentEquals(before, pixels(immutable), "detect never writes")
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val manual = Mask.manual(400f, 300f, 520f, 420f)
        val plan = found.toMaskPlan().withMasks(found.toMaskPlan().masks + manual)
        val result = masker.applyMasks(bitmap, plan)
        assertEquals(plan.masks, result.masks)
        assertTrue(result.faces.isEmpty())
        assertMaskedPixels(before, pixels(bitmap), 640, result.masks)
        val oracle = ArrayImage(640, 480, before.copyOf())
        masker.applyMasks(oracle, plan)
        assertContentEquals(oracle.px, pixels(bitmap), "the bitmap holds exactly the common route's pixels")
        // detect(bitmap) equals detect on the same pixels through the common route.
        assertEquals(masker.detect(ArrayImage(640, 480, before.copyOf())).faces, found.faces)
    }

    @Test
    fun unsupportedBitmapsAreRefusedBeforeAnyWorkWithTheirReason() {
        fun refused(reason: String, write: Boolean, bitmap: Bitmap) {
            val before = if (bitmap.isRecycled) null else pixelsOrNull(bitmap)
            val runs = runner.runs
            val calls: List<() -> Unit> =
                if (write) {
                    listOf({ masker.maskInPlace(bitmap) }, { masker.applyMasks(bitmap, MaskPlan(8, 8, emptyList())) })
                } else {
                    listOf({ masker.detect(bitmap) }, { masker.maskInPlace(bitmap) })
                }
            for (call in calls) {
                val e = assertFailsWith<FaceMaskException.UnsupportedImage> { call() }
                assertTrue(e.message.orEmpty().startsWith("Unsupported image: $reason: "), e.message)
            }
            assertEquals(runs, runner.runs, "no detector pass ran ($reason)")
            if (before != null) assertContentEquals(before, pixelsOrNull(bitmap), "the bitmap is unchanged ($reason)")
        }
        for (config in listOf(Bitmap.Config.RGB_565, Bitmap.Config.RGBA_F16, Bitmap.Config.RGBA_1010102)) {
            refused("CONFIG", write = false, Bitmap.createBitmap(64, 48, config))
        }
        refused("CONFIG", write = false, Bitmap.createBitmap(64, 48, Bitmap.Config.ALPHA_8))
        val immutable = TestImages.picture(64, 48).copy(Bitmap.Config.ARGB_8888, false)
        refused("IMMUTABLE", write = true, immutable)
        refused("RECYCLED", write = false, TestImages.picture(64, 48).apply { recycle() })
        // The message names the fix: the decode settings and the one documented copy.
        val e = assertFailsWith<FaceMaskException.UnsupportedImage> { masker.maskInPlace(immutable) }
        assertTrue(e.message.orEmpty().contains("ImageDecoder.ALLOCATOR_SOFTWARE"), e.message)
        assertTrue(e.message.orEmpty().contains("bitmap.copy(Bitmap.Config.ARGB_8888, true)"), e.message)
        // The immutable bitmap is fine for detect.
        assertEquals(64 to 48, masker.detect(immutable).let { it.width to it.height })
    }

    private fun pixelsOrNull(b: Bitmap): IntArray? = if (b.config == Bitmap.Config.ARGB_8888) pixels(b) else null

    @Test
    fun theGainMapIsRemovedBeforeWritingAlsoWithoutFacesAndKeptByDetect() {
        val bitmap = withGainMap(TestImages.picture(640, 480))
        val found = masker.detect(bitmap)
        assertTrue(found.hasGainMap, "detect reports the gain map")
        assertTrue(bitmap.hasGainmap(), "detect leaves it in place")
        val masked = masker.maskInPlace(bitmap)
        assertTrue(masked.gainMapRemoved)
        assertFalse(bitmap.hasGainmap(), "maskInPlace removed it")
        // An empty plan removes it too.
        val empty = withGainMap(TestImages.picture(64, 48))
        val before = pixels(empty)
        val r = masker.applyMasks(empty, MaskPlan(64, 48, emptyList()))
        assertTrue(r.gainMapRemoved)
        assertFalse(empty.hasGainmap())
        assertContentEquals(before, pixels(empty), "an empty plan writes no pixel")
        // No face found: removed as well.
        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(NoFaceRunner())).use { none ->
            val noFace = withGainMap(TestImages.picture(640, 480))
            val result: MaskResult = none.maskInPlace(noFace)
            assertTrue(result.faces.isEmpty() && result.masks.isEmpty())
            assertTrue(result.gainMapRemoved)
            assertFalse(noFace.hasGainmap())
        }
    }

    @Test
    fun aCancelledCallLeavesTheBitmapAndItsGainMapUntouched() {
        val bitmap = withGainMap(TestImages.picture(640, 480))
        val before = pixels(bitmap)
        val cancelled = object : CancellationToken {
            override val isCancellationRequested = true
        }
        assertFailsWith<FaceMaskException.Cancelled> { masker.maskInPlace(bitmap, cancelled) }
        assertFailsWith<FaceMaskException.Cancelled> {
            masker.applyMasks(bitmap, MaskPlan(640, 480, emptyList()), cancelled)
        }
        assertFailsWith<FaceMaskException.Cancelled> {
            masker.applyMasks(bitmap, MaskPlan(640, 480, listOf(Mask.manual(10f, 10f, 200f, 200f))), cancelled)
        }
        assertTrue(bitmap.hasGainmap(), "the gain map stays")
        assertContentEquals(before, pixels(bitmap))
    }

    @Test
    fun aCancellationDuringTheFinalCopyIsReportedAfterItWithTheBitmapFullyMasked() {
        val bitmap = withGainMap(TestImages.picture(640, 480))
        val before = pixels(bitmap)
        val mask = Mask.manual(200f, 150f, 400f, 330f)
        val probe = mask.centerX.toInt() to mask.centerY.toInt()
        // Requested as soon as the first masked pixel reached the bitmap, i.e. inside the final copy.
        val token = object : CancellationToken {
            override val isCancellationRequested: Boolean
                get() = bitmap.getPixel(probe.first, probe.second) != before[probe.second * 640 + probe.first]
        }
        assertFailsWith<FaceMaskException.Cancelled> {
            masker.applyMasks(bitmap, MaskPlan(640, 480, listOf(mask)), token)
        }
        assertFalse(bitmap.hasGainmap(), "the copy started, so the gain map was removed first")
        val oracle = ArrayImage(640, 480, before.copyOf())
        masker.applyMasks(oracle, MaskPlan(640, 480, listOf(mask)))
        assertContentEquals(oracle.px, pixels(bitmap), "fully masked")
    }

    @Test
    fun notEnoughHeapForTheWorkingAreaLeavesTheBitmapUntouched() {
        val bitmap = withGainMap(TestImages.picture(640, 480))
        val before = pixels(bitmap)
        val full = object : MemoryProbe {
            override val rule = MemoryRule.HEAP_REMAINING

            override fun figure(): Long = 64L * 1024 * 1024

            override fun inUse(): Long = 64L * 1024 * 1024
        }
        masker.useStageSeamsForTest(StageSeams(probe = full))
        assertFailsWith<FaceMaskException.NotEnoughMemory> { masker.maskInPlace(bitmap) }
        assertFailsWith<FaceMaskException.NotEnoughMemory> {
            masker.applyMasks(bitmap, MaskPlan(640, 480, listOf(Mask.manual(0f, 0f, 640f, 480f))))
        }
        assertContentEquals(before, pixels(bitmap), "untouched")
        assertTrue(bitmap.hasGainmap(), "the gain map stays")
        // A forced allocation failure of the working area (the last-resort backstop) is NotEnoughMemory as well.
        var allocations = 0
        val failing = object : StageAllocator {
            private fun fail(): Nothing {
                allocations++
                throw OutOfMemoryError("forced by the test")
            }

            override fun ints(size: Int): IntArray = fail()

            override fun longs(size: Int): LongArray = fail()

            override fun floats(size: Int): FloatArray = fail()

            override fun bytes(size: Int): ByteArray = fail()

            override fun intTable(size: Int): Array<IntArray?> = fail()
        }
        masker.useStageSeamsForTest(StageSeams(allocator = failing))
        assertFailsWith<FaceMaskException.NotEnoughMemory> { masker.maskInPlace(bitmap) }
        assertTrue(allocations > 0, "control: the allocator was asked")
        assertContentEquals(before, pixels(bitmap), "untouched")
        assertTrue(bitmap.hasGainmap(), "the gain map stays")
        // The real Android heap probe admits the same call.
        masker.useStageSeamsForTest(StageSeams(probe = AndroidHeapProbe))
        assertTrue(masker.maskInPlace(bitmap).gainMapRemoved)
    }

    @Test
    fun aPlanAboveTheMaskWorkLimitIsRefusedWithTheBitmapUntouched() {
        val bitmap = withGainMap(TestImages.picture(640, 480))
        val before = pixels(bitmap)
        // 200 masks over the whole image: their summed working area (200 x 307,200 px) is above
        // 32 x max(image, 1,048,576 px) = 33,554,432 px.
        val whole = Mask.exact(MaskShape.RECTANGLE, 320f, 240f, 2000f, 2000f)
        val plan = MaskPlan(640, 480, List(200) { whole })
        val e = assertFailsWith<FaceMaskException.InvalidConfiguration> { masker.applyMasks(bitmap, plan) }
        assertEquals("masks", e.parameter)
        assertFailsWith<FaceMaskException.MaskPlanMismatch> {
            masker.applyMasks(bitmap, MaskPlan(641, 480, emptyList()))
        }
        assertContentEquals(before, pixels(bitmap))
        assertTrue(bitmap.hasGainmap())
    }

    @Test
    fun aWholeBitmapMaskIsWrittenCompletelyThroughTheAdapter() {
        val bitmap = TestImages.picture(1024, 768) { x, _ -> if (x < 100) 30 else 255 }
        val before = pixels(bitmap)
        val plan = MaskPlan(1024, 768, listOf(Mask.exact(MaskShape.RECTANGLE, 512f, 384f, 512f, 384f)))
        val result = masker.applyMasks(bitmap, plan)
        val after = pixels(bitmap)
        assertTrue(after.all { it ushr 24 == 0xFF }, "every pixel is opaque")
        val oracle = ArrayImage(1024, 768, before.copyOf())
        masker.applyMasks(oracle, plan)
        assertContentEquals(oracle.px, after)
        assertEquals(plan.masks, result.masks)
    }

    /**
     * A bitmap in another colour space than sRGB is refused for the masking calls before any work: `setPixels` converts
     * from sRGB, and an opaque mask pixel does not always read back unchanged, so the copy would stop half way
     * (`MaskWriteFailed`). `detect` reads it (sRGB values from `getPixels`).
     */
    @Test
    fun aBitmapOutsideSrgbIsRefusedForMaskingAndReadByDetect() {
        for (named in listOf(ColorSpace.Named.DISPLAY_P3, ColorSpace.Named.LINEAR_SRGB, ColorSpace.Named.ADOBE_RGB)) {
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888, true, ColorSpace.get(named))
            bitmap.setPixels(pixels(TestImages.picture(640, 480)), 0, 640, 0, 0, 640, 480)
            val before = pixels(bitmap)
            val runs = runner.runs
            for (call in listOf<() -> Unit>(
                { masker.maskInPlace(bitmap) },
                { masker.applyMasks(bitmap, MaskPlan(640, 480, listOf(Mask.manual(10f, 10f, 200f, 200f)))) },
            )) {
                val e = assertFailsWith<FaceMaskException.UnsupportedImage> { call() }
                assertTrue(
                    e.message.orEmpty().startsWith("Unsupported image: CONFIG: the bitmap's colour space"),
                    e.message,
                )
                assertTrue(e.message.orEmpty().contains("setTargetColorSpace"), e.message)
            }
            assertEquals(runs, runner.runs, "no detector pass ran")
            assertContentEquals(before, pixels(bitmap), "untouched ($named)")
            assertEquals(1, masker.detect(bitmap).faces.size, "detect reads it ($named)")
        }
        // Negative control: without the rule the common copy into a Display P3 bitmap fails after it started.
        val p3 = Bitmap.createBitmap(
            640,
            480,
            Bitmap.Config.ARGB_8888,
            true,
            ColorSpace.get(ColorSpace.Named.DISPLAY_P3),
        )
        p3.setPixels(pixels(TestImages.picture(640, 480)), 0, 640, 0, 0, 640, 480)
        assertFailsWith<FaceMaskException.MaskWriteFailed> { masker.maskInPlace(BitmapPixels(p3)) }
    }
}
