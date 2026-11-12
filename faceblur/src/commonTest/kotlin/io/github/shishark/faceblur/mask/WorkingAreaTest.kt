// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.FaceBox
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The working area of ADR 0013 decision 6: reserved in full in step 1 through the allocator, exactly the computed
 * size, all zero when step 2 starts, refused by the memory pre-check without allocating, and an out-of-memory error
 * inside the reservation (the last-resort backstop) mapped to `NotEnoughMemory` with the image untouched.
 */
class WorkingAreaTest {
    @AfterTest
    fun countIsBackToZero() {
        assertEquals(0L, ReservedBytes.current(), "the process-wide count returns to 0 after every call")
    }

    private val w = 301
    private val h = 197

    private fun plan(seed: Int, n: Int = 25) = randomMasks(Random(seed), w, h, n)

    private fun run(
        style: MaskStyle,
        masks: List<MaskGeometry>,
        allocator: RecordingAllocator = RecordingAllocator(),
        probe: MemoryProbe? = null,
        image: RecordingImage = RecordingImage(stageImage(w, h, 3)),
        checkCancelled: () -> Unit = {},
    ): Triple<RecordingImage, RecordingAllocator, StepRecorder> {
        val steps = StepRecorder(allocator)
        MaskEngine.apply(image, masks, settings(style), checkCancelled, StageSeams(allocator, probe, steps))
        return Triple(image, allocator, steps)
    }

    @Test
    fun theReservationIsExactlyTheComputedSizeAllInStepOneAndAllZeroAtStepTwo() {
        for (style in MaskStyle.entries) {
            for (seed in 1..6) {
                val masks = plan(seed)
                val (img, alloc, steps) = run(style, masks)
                val layout = steps.area?.layout ?: error("reserved")
                assertEquals(layout.bytes, alloc.bytes, "$style $seed: reserved bytes equal the size of step 1")
                assertEquals(
                    MaskEngine.workingAreaBytes(w, h, masks, settings(style)),
                    alloc.bytes,
                    "$style $seed: the sizing function gives the same figure",
                )
                assertTrue(alloc.requests.all { it.step == 1 }, "$style $seed: every allocation in step 1")
                assertEquals(true, steps.zeroAfterReserve, "$style $seed: the touch leaves every array zero")
                assertEquals(true, steps.zeroAtStep2, "$style $seed: every work array is zero when step 2 starts")
                assertEquals(0, img.sizeViolations, "$style $seed: exact w × h arrays")
                assertEquals(listOf(1, 2, 3, 4), steps.steps.map { it.first })
            }
        }
    }

    @Test
    fun everyArrayPassedToTheImageHasExactlyItsBlockSizeForEveryRunLength() {
        // Image sizes that are not multiples of 64 (partial tiles at both edges) and plans whose write rectangles
        // produce every run length from 1 to 64.
        for ((iw, ih) in listOf(130 to 70, 65 to 129, 1 to 1, 64 to 64, 200 to 3)) {
            for (style in MaskStyle.entries) {
                val masks = randomMasks(Random(iw * 31 + ih), iw, ih, 60, maxRadius = 20f)
                val img = RecordingImage(stageImage(iw, ih, 5))
                MaskEngine.apply(img, masks, settings(style))
                assertEquals(0, img.sizeViolations, "$iw x $ih $style")
            }
        }
        val all = HashSet<Int>()
        for (seed in 1..40) {
            val img = RecordingImage(stageImage(w, h, seed))
            MaskEngine.apply(img, randomMasks(Random(seed), w, h, 30, maxRadius = 30f), settings(MaskStyle.SOLID))
            assertEquals(0, img.sizeViolations)
            all += img.runLengths
        }
        assertEquals((1..64).toSet(), all.filter { it in 1..64 }.toSet(), "every run length 1..64 was used")
    }

    @Test
    fun aForcedAllocationFailureGivesNotEnoughMemoryWithTheImageUntouched() {
        for (style in MaskStyle.entries) {
            val masks = plan(11, 8)
            val requests = run(style, masks).second.requests.size
            val points = (listOf(1, requests / 2, requests) + (1..requests)).distinct()
            for (at in points) {
                val oom = OutOfMemoryError("planted")
                val image = RecordingImage(stageImage(w, h, 3))
                val before = checksum(image.base.data)
                val e = assertFailsWith<CoreFailure.NotEnoughMemory>("$style request $at") {
                    run(style, masks, RecordingAllocator(at) { oom }, image = image)
                }
                assertSame(oom, e.cause, "the out-of-memory error is the cause")
                assertEquals(0, image.calls, "$style request $at: no read, no write")
                assertEquals(before, checksum(image.base.data))
            }
        }
    }

    @Test
    fun otherErrorsFromTheAllocatorPassUnchanged() {
        class PlantedError : Error("planted")
        for (failure in listOf<Throwable>(PlantedError(), IllegalStateException("planted"))) {
            val image = RecordingImage(stageImage(w, h, 3))
            val thrown = assertFailsWith<Throwable> {
                run(MaskStyle.SOLID, plan(2), RecordingAllocator(2) { failure }, image = image)
            }
            assertSame(failure, thrown, "rethrown unchanged, not NotEnoughMemory")
            assertEquals(0, image.calls)
        }
    }

    /**
     * The backstop matches the platform's out-of-memory class by its exact qualified name: an error with the same
     * simple name in another package, and a subclass of the real one, pass unchanged (a simple-name, suffix or
     * `is`-check would map them to `NotEnoughMemory`), at the first request (tile tables) and at a later one.
     */
    @Test
    fun onlyTheExactOutOfMemoryClassIsMapped() {
        for (at in listOf(1, 3)) {
            for (failure in listOf<Throwable>(
                io.github.shishark.faceblur.mask.planted.OutOfMemoryError(),
                OutOfMemorySubclass(),
            )) {
                assertTrue(!isOutOfMemory(failure), "${failure::class.simpleName} is not an exact match")
                val image = RecordingImage(stageImage(w, h, 3))
                val thrown = assertFailsWith<Throwable> {
                    run(MaskStyle.SOLID, plan(2), RecordingAllocator(at) { failure }, image = image)
                }
                assertSame(failure, thrown, "request $at: rethrown unchanged, not NotEnoughMemory")
                assertEquals(0, image.calls)
            }
        }
        assertTrue(isOutOfMemory(OutOfMemoryError("planted")), "the platform's own error is mapped")
    }

    @Test
    fun anOverflowingSizeIsNotEnoughMemoryWithoutAllocating() {
        val huge = Long.MAX_VALUE / 4
        val slots = AreaLayout.SlotCounts(huge, 0, 0, 0)
        assertFailsWith<CoreFailure.NotEnoughMemory> { AreaLayout.sizeOf(1, 1, slots, 64, 64, 0, 0) }
        assertFailsWith<CoreFailure.NotEnoughMemory> {
            AreaLayout.sizeOf(Long.MAX_VALUE / 2, 1, AreaLayout.SlotCounts(0, 0, 0, 0), 64, 64, 0, 0)
        }
        assertFailsWith<CoreFailure.NotEnoughMemory> { AreaLayout.scratchBytes(MaskStyle.GRADIENT_OVAL, huge, 0, 0) }
        assertFailsWith<CoreFailure.NotEnoughMemory> { checkedAdd(Long.MAX_VALUE, 1) }
        assertFailsWith<CoreFailure.NotEnoughMemory> { checkedMul(-1, 1) }
        assertEquals(6L, checkedMul(2, 3))
    }

    @Test
    fun theHeapCheckRefusesOneByteBelowWithOneCollectionAndProceedsExactlyAtTheWorkingArea() {
        for (style in MaskStyle.entries) {
            val masks = plan(21)
            val need = MaskEngine.workingAreaBytes(w, h, masks, settings(style))
            val max = need + 10_000
            // Remaining heap one byte below the working area: refused by the check after exactly one collection.
            val image = RecordingImage(stageImage(w, h, 3))
            val before = checksum(image.base.data)
            val alloc = RecordingAllocator()
            val short = FixedProbe(MemoryRule.HEAP_REMAINING, max, inUse = longArrayOf(10_001))
            val e = assertFailsWith<CoreFailure.NotEnoughMemory> { run(style, masks, alloc, short, image) }
            assertNull(e.cause, "$style: the check refused, not the catch")
            assertEquals(1, short.collections, "$style: exactly one collection request")
            assertTrue(alloc.onlyTileTables(w, h), "$style: nothing allocated but the two tile tables")
            assertEquals(0, image.calls, "$style: no read, no write")
            assertEquals(before, checksum(image.base.data))
            // Exactly at the working area: proceeds without a collection.
            val exact = FixedProbe(MemoryRule.HEAP_REMAINING, max, inUse = longArrayOf(10_000))
            run(style, masks, probe = exact)
            assertEquals(0, exact.collections)
            // Below before the collection, enough after it: proceeds. The first figure is read by the check of the
            // tile tables (which fit), the second by the check of the rest, the third after the collection.
            val freed = FixedProbe(MemoryRule.HEAP_REMAINING, max, inUse = longArrayOf(10_001, 10_001, 9_000))
            run(style, masks, probe = freed)
            assertEquals(1, freed.collections)
            // A working area above the heap maximum: refused at once, no collection.
            val tooBig = FixedProbe(MemoryRule.HEAP_REMAINING, need - 1)
            val alloc2 = RecordingAllocator()
            assertNull(assertFailsWith<CoreFailure.NotEnoughMemory> { run(style, masks, alloc2, tooBig) }.cause)
            assertEquals(0, tooBig.collections)
            assertTrue(alloc2.onlyTileTables(w, h), "$style: nothing allocated but the two tile tables")
        }
    }

    @Test
    fun theRemainingHeapNeverOverflowsAndIsNeverNegative() {
        assertEquals(Long.MAX_VALUE - 10, remainingHeap(Long.MAX_VALUE, 10, 0))
        assertEquals(0L, remainingHeap(100, 60, 50), "in use plus the count above the maximum: 0")
        assertEquals(0L, remainingHeap(0, Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0L, remainingHeap(100, 200, 0))
        assertEquals(40L, remainingHeap(100, 50, 10))
        // A heap maximum of Long.MAX_VALUE ("no inherent limit") lets the call through.
        run(MaskStyle.SOLID, plan(5), probe = FixedProbe(MemoryRule.HEAP_REMAINING, Long.MAX_VALUE))
    }

    @Test
    fun theHeapCheckSubtractsTheBytesOtherCallsHaveReserved() {
        val masks = plan(22)
        val need = MaskEngine.workingAreaBytes(w, h, masks, settings(MaskStyle.SOLID))
        // Another call holds 1,000 bytes: a heap that fits this call alone no longer fits.
        ReservedBytes.admit(1_000, null)
        try {
            val alloc = RecordingAllocator()
            val probe = FixedProbe(MemoryRule.HEAP_REMAINING, need + 999)
            assertFailsWith<CoreFailure.NotEnoughMemory> { run(MaskStyle.SOLID, masks, alloc, probe) }
            assertTrue(alloc.onlyTileTables(w, h), "nothing allocated but the two tile tables")
            run(MaskStyle.SOLID, masks, probe = FixedProbe(MemoryRule.HEAP_REMAINING, need + 1_000))
        } finally {
            ReservedBytes.release(1_000)
        }
    }

    @Test
    fun theBackstopStillMapsAnAllocationFailureAfterThePassedCheck() {
        val oom = OutOfMemoryError("planted")
        val image = RecordingImage(stageImage(w, h, 3))
        val probe = FixedProbe(MemoryRule.HEAP_REMAINING, Long.MAX_VALUE)
        val e = assertFailsWith<CoreFailure.NotEnoughMemory> {
            run(MaskStyle.GRADIENT_OVAL, plan(6), RecordingAllocator(4) { oom }, probe, image)
        }
        assertSame(oom, e.cause)
        assertEquals(0, image.calls)
        assertEquals(0L, ReservedBytes.current())
    }

    @Test
    fun theIosRulesUseTheMarginOneCollectionAndTheBuildTargetForAFigureOfZero() {
        val masks = plan(23)
        val need = MaskEngine.workingAreaBytes(w, h, masks, settings(MaskStyle.SOLID)) +
            MaskStageLimits.AVAILABLE_MEMORY_MARGIN
        for (rule in listOf(MemoryRule.AVAILABLE_ON_DEVICE, MemoryRule.AVAILABLE_ON_SIMULATOR)) {
            // One byte short: refused after one collection.
            val short = FixedProbe(rule, need - 1)
            val image = RecordingImage(stageImage(w, h, 3))
            assertFailsWith<CoreFailure.NotEnoughMemory> { run(MaskStyle.SOLID, masks, probe = short, image = image) }
            assertEquals(1, short.collections, "$rule: one collection before refusing")
            assertEquals(0, image.calls)
            // Below, then enough after the collection: proceeds (tile-table check, check of the rest, after it).
            val after = FixedProbe(rule, need - 1, need - 1, need)
            run(MaskStyle.SOLID, masks, probe = after)
            assertEquals(1, after.collections)
            // Exactly the requirement: proceeds without a collection.
            val exact = FixedProbe(rule, need)
            run(MaskStyle.SOLID, masks, probe = exact)
            assertEquals(0, exact.collections)
        }
        // A figure of 0: refused on the device target, skipped on the simulator target.
        val image = RecordingImage(stageImage(w, h, 3))
        assertFailsWith<CoreFailure.NotEnoughMemory> {
            run(MaskStyle.SOLID, masks, probe = FixedProbe(MemoryRule.AVAILABLE_ON_DEVICE, 0), image = image)
        }
        assertEquals(0, image.calls, "device, figure 0: no read, no write")
        val sim = run(MaskStyle.SOLID, masks, probe = FixedProbe(MemoryRule.AVAILABLE_ON_SIMULATOR, 0)).first
        assertTrue(sim.writes > 0, "simulator, figure 0: the call proceeds")
    }

    @Test
    fun theCountReturnsToZeroAfterSuccessEachErrorAndCancellation() {
        val masks = plan(24)
        run(MaskStyle.GRADIENT_OVAL, masks)
        assertEquals(0L, ReservedBytes.current())
        assertFailsWith<CoreFailure.NotEnoughMemory> {
            run(MaskStyle.GRADIENT_OVAL, masks, RecordingAllocator(3) { OutOfMemoryError("planted") })
        }
        assertEquals(0L, ReservedBytes.current())
        assertFailsWith<CoreFailure.NotEnoughMemory> {
            run(MaskStyle.GRADIENT_OVAL, masks, probe = FixedProbe(MemoryRule.HEAP_REMAINING, 1))
        }
        assertEquals(0L, ReservedBytes.current())
        class Stop : RuntimeException()
        assertFailsWith<Stop> { run(MaskStyle.GRADIENT_OVAL, masks, checkCancelled = { throw Stop() }) }
        assertEquals(0L, ReservedBytes.current())
        val failing = RecordingImage(stageImage(w, h, 3)).apply { failWriteAt = 1 }
        assertFailsWith<CoreFailure.WriteInterrupted> { run(MaskStyle.GRADIENT_OVAL, masks, image = failing) }
        assertEquals(0L, ReservedBytes.current())
    }

    /**
     * The probe a platform binding installs is the one the stage uses, and without one there is no pre-check
     * (SR-L10: visible, not silent): with a refusing probe installed, a `FaceMasker` call without test seams gets
     * `NotEnoughMemory` from the check; with none installed the same call proceeds and its bytes are only counted.
     * Every binding therefore asserts its installed probe and rule in its own test (JVM: `MemoryProbeBindingTest`).
     */
    @Test
    fun theInstalledProbeIsTheStagesCheckAndWithoutOneThereIsNone() {
        val previous = FaceBlurRuntime.memoryProbe()
        val plan = MaskPlan(w, h, listOf(Mask.exact(MaskShape.ELLIPSE, 100f, 90f, 40f, 50f)))
        try {
            FaceBlurRuntime.installMemoryProbe(FixedProbe(MemoryRule.HEAP_REMAINING, 1))
            assertSame(MemoryRule.HEAP_REMAINING, StageSeams().probe?.rule, "the stage reads the installed probe")
            val refused = RecordingImage(stageImage(w, h, 3))
            masker().use { m ->
                val e = assertFailsWith<FaceMaskException.NotEnoughMemory> { m.applyMasks(refused, plan) }
                assertNull(e.cause, "refused by the installed probe's check")
            }
            assertEquals(0, refused.calls)
            FaceBlurRuntime.installMemoryProbe(null)
            // No probe at all, also on a platform with a default probe.
            FaceBlurRuntime.overrideDefaults(PlatformDefaults.NONE)
            assertNull(StageSeams().probe, "no probe installed: no pre-check")
            val counted = RecordingImage(stageImage(w, h, 3))
            masker().use { it.applyMasks(counted, plan) }
            assertTrue(counted.writes > 0, "without a probe the call proceeds (only the count and the catch remain)")
        } finally {
            FaceBlurRuntime.overrideDefaults(null)
            FaceBlurRuntime.installMemoryProbe(previous)
        }
    }

    private fun masker() = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))

    @Test
    fun noReservationWhenNoMaskReachesTheImage() {
        val outside = listOf(MaskGeometry(-500f, -500f, 10f, 10f, MaskShape.ELLIPSE))
        val (img, alloc, steps) = run(MaskStyle.SMOOTH_FILL, outside)
        assertEquals(0, alloc.requests.size)
        assertEquals(0, img.calls)
        assertNull(steps.area)
        assertEquals(0L, MaskEngine.workingAreaBytes(w, h, outside, settings(MaskStyle.SOLID)))
    }

    /** Reference: the distinct tiles of the rectangles [rects], split into the four size classes of [w] × [h]. */
    private fun tileClasses(rects: List<IRect>, w: Int, h: Int): List<Int> {
        val tilesX = (w + 63) / 64
        val seen = HashSet<Int>()
        for (r in rects) {
            if (r.isEmpty) continue
            for (ty in r.top / 64..(r.bottom - 1) / 64) {
                for (tx in r.left / 64..(r.right - 1) / 64) {
                    seen +=
                        ty * tilesX + tx
                }
            }
        }
        val classes = IntArray(4)
        for (t in seen) {
            val partX = w % 64 != 0 && t % tilesX == w / 64
            val partY = h % 64 != 0 && t / tilesX == h / 64
            classes[(if (partX) 1 else 0) + (if (partY) 2 else 0)]++
        }
        return classes.toList()
    }

    /**
     * ADR 0013 decision 6: the shadow slots are exactly the union of the tiles the write rectangles touch (per size
     * class), the coverage slots exactly the union of the tiles the opaque rectangles touch, for overlapping,
     * stacked and edge-crossing plans on an image with partial tiles at both edges.
     */
    @Test
    fun theSlotsAreExactlyTheUnionOfTheTouchedTiles() {
        val stacked = List(40) { MaskGeometry(150f + it % 3, 100f + it % 5, 30f + it, 36f, MaskShape.ELLIPSE) }
        for (style in MaskStyle.entries) {
            for (masks in listOf(plan(31), plan(32, 120), stacked)) {
                val (_, _, steps) = run(style, masks)
                val layout = steps.area?.layout ?: error("reserved")
                val prepared = masks.map { PreparedMask(PreparedMask.usable(it), settings(style)) }
                val write = tileClasses(prepared.map { it.extent(1f + it.featherN, w, h) }, w, h)
                val opaque = tileClasses(prepared.map { it.extent(1f, w, h) }, w, h).sum()
                val slots = listOf(layout.fullSlots, layout.rightSlots, layout.bottomSlots, layout.cornerSlots)
                assertEquals(write, slots, "$style: shadow slots per class")
                assertEquals(opaque, layout.coverageSlots, "$style: coverage slots")
                assertTrue(layout.shadowSlots < masks.size * 4, "$style: fewer slots than the per-mask sum")
            }
        }
    }

    /**
     * ADR 0013 verification "exact union of tiles": a 600 px face with k = 0, 4, 16 and 32 nested earlier states as
     * part masks (each inside the face's box, sharing its left and top edges, as the merge keeps them), 25 MP, default
     * masking. The parts touch no tile the face does not touch, so they add only their per-mask arrays; and identical
     * stacked masks reserve the tiles of one.
     */
    @Test
    fun aFaceWithNestedPartMasksReservesOnlyItsOwnTiles() {
        val iw = 6_000
        val ih = 4_166
        val masking = MaskingConfig()
        val settings = masking.toSettings()
        val l = 2_700f
        val t = 1_800f
        val face = Mask.fromFace(FaceBox.create(l, t, l + 600f, t + 600f, 0.9f), masking).toGeometry()
        val perMask = (AreaLayout.MASK_FLOATS * 4 + AreaLayout.MASK_INTS * 4 + 1).toLong()
        val alone = MaskEngine.workingAreaBytes(iw, ih, listOf(face), settings)
        for (k in listOf(0, 4, 16, 32)) {
            val parts = (1..k).map { j ->
                Mask.fromFace(FaceBox.create(l, t, l + 600f - 10f * j, t + 600f - 8f * j, 0.9f), masking).toGeometry()
            }
            val bytes = MaskEngine.workingAreaBytes(iw, ih, listOf(face) + parts, settings)
            assertEquals(alone + k * perMask, bytes, "$k nested parts add only their per-mask arrays")
            // The former bound of 6,000,000 bytes (set at expansion 1.35 / 1.55) scaled by the mask area of 1.6 / 1.8.
            val bound = 6_000_000.0 * (1.6 * 1.8) / (1.35 * 1.55)
            assertTrue(bytes < bound, "$k parts: a few megabytes ($bytes bytes, bound $bound)")
        }
        val stacked = MaskEngine.workingAreaBytes(iw, ih, List(40) { face }, settings)
        assertEquals(alone + 39 * perMask, stacked, "40 identical masks reserve the tiles of one")
    }

    /**
     * The worst-case statement of the in-place KDoc, from the caps: the working area of one mask over a whole 25 MP
     * image stays within 4.3 bytes per pixel plus 32 MiB for every style, and 6,000 × 4,166 gives about 141 MB.
     */
    @Test
    fun theDocumentedWorstCaseHoldsForAWholeImageMaskAndMatchesTheText() {
        val iw = 6_000
        val ih = 4_166
        val n = iw.toLong() * ih
        val bound = MaskStageLimits.WORST_CASE_BYTES_PER_PIXEL * n + MaskStageLimits.WORST_CASE_FIXED_BYTES
        assertEquals(141, (bound / 1e6).toInt(), "about 141 MB at 25 MP")
        assertEquals(100, (n * 4 / 1e6).roundToInt(), "the image's own pixels take about 100 MB")
        for (style in MaskStyle.entries) {
            for (shape in MaskShape.entries) {
                val whole = listOf(MaskGeometry(iw / 2f, ih / 2f, iw / 2f + 1, ih / 2f + 1, shape))
                val bytes = MaskEngine.workingAreaBytes(iw, ih, whole, MaskSettings(style = style, shape = shape))
                assertTrue(bytes <= bound, "$style $shape: $bytes > $bound")
            }
        }
        // The fixed part at its caps: 65,536 masks at 128 bytes, the SMOOTH_FILL scratch at the cell cap.
        val cap = MaskStageLimits.MAX_SMOOTH_FILL_CELLS.toLong()
        val scratch = AreaLayout.scratchBytes(MaskStyle.SMOOTH_FILL, 0, cap, 2 * cap + AreaLayout.MAX_PYRAMID_LEVELS)
        assertTrue(scratch <= (18.5 * 1024 * 1024).toLong() + 4096, "SMOOTH_FILL scratch at the cap: $scratch")
        val perMask = AreaLayout.MASK_FLOATS * 4 + AreaLayout.MASK_INTS * 4 + 1
        assertTrue(perMask <= 128, "at most 128 bytes per mask ($perMask)")
        val goScratch = AreaLayout.scratchBytes(MaskStyle.GRADIENT_OVAL, 128, 0, 0)
        assertTrue(goScratch < 128 * 1024, "GRADIENT_OVAL scratch under 128 KiB ($goScratch)")
    }

    /**
     * A pinned sizing sample: a 12 MP photo with three faces of square boxes, default masking, computed exactly by
     * the library's sizing. A change of the sizing that moves it fails here; then the constants are updated. The
     * figures are not published: the KDoc and the guides state the worst case only.
     */
    @Test
    fun theTypicalFiguresAreTheLibrarysOwnSizing() {
        val masking = MaskingConfig()
        for ((i, side) in MaskStageLimits.TYPICAL_FACE_SIDES.withIndex()) {
            val masks = listOf(1_000f, 2_000f, 3_000f).map { cx ->
                val box = FaceBox.create(cx - side / 2, 1_500f - side / 2, cx + side / 2, 1_500f + side / 2, 0.9f)
                Mask.fromFace(box, masking).toGeometry()
            }
            val bytes = MaskEngine.workingAreaBytes(4_000, 3_000, masks, masking.toSettings())
            assertEquals(MaskStageLimits.TYPICAL_BYTES[i], bytes, "$side px faces")
        }
    }
}

/** A subclass of the platform's out-of-memory error: not an exact class match, so it passes unchanged. */
private class OutOfMemorySubclass : OutOfMemoryError("planted subclass")
