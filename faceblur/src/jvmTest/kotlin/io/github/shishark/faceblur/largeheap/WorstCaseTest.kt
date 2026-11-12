// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.largeheap

import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.mask.LegacyMaskEngine
import io.github.shishark.faceblur.mask.MaskEngine
import io.github.shishark.faceblur.mask.MaskGeometry
import io.github.shishark.faceblur.mask.MaskSettings
import io.github.shishark.faceblur.mask.MaskStageLimits
import io.github.shishark.faceblur.mask.PreparedMask
import io.github.shishark.faceblur.mask.RecordingAllocator
import io.github.shishark.faceblur.mask.StageObserver
import io.github.shishark.faceblur.mask.StageSeams
import io.github.shishark.faceblur.mask.StepRecorder
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The worst case of ADR 0013 decision 6 at the default pixel limit: a 25 MP image (6,000 × 4,166 px) masked almost
 * everywhere, for every style and both shapes. Runs in its own Gradle task (`jvmLargeHeapTest`, 2 GiB heap, one
 * fork): the old engine alone holds about five image-sized arrays for a whole-image mask.
 */
class WorstCaseTest {
    private val w = 6_000
    private val h = 4_166
    private val n = w.toLong() * h

    private fun image(): IntArrayPixels {
        val data = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            (0xFF shl 24) or ((x * 7 and 0xFF) shl 16) or ((y * 3 and 0xFF) shl 8) or ((x xor y) and 0xFF)
        }
        return IntArrayPixels(w, h, data)
    }

    private fun whole(shape: MaskShape) = listOf(MaskGeometry(w / 2f, h / 2f, w * 0.5f, h * 0.5f, shape))

    /** A grid of overlapping masks (both shapes) whose opaque parts cover at least 95 % of the pixels. */
    private fun many(): List<MaskGeometry> {
        val out = ArrayList<MaskGeometry>()
        val step = 200f
        var i = 0
        var y = 0f
        while (y <= h) {
            var x = 0f
            while (x <= w) {
                val shape = if (i++ % 2 == 0) MaskShape.ELLIPSE else MaskShape.RECTANGLE
                out += MaskGeometry(x, y, step * 0.75f, step * 0.75f, shape)
                x += step
            }
            y += step
        }
        return out
    }

    private fun coverage(masks: List<MaskGeometry>, s: MaskSettings): Double {
        val inside = BooleanArray(w * h)
        for (g in masks) {
            val m = PreparedMask(PreparedMask.usable(g), s)
            val e = m.extent(1f, w, h)
            for (y in e.top until e.bottom) {
                for (x in e.left until e.right) {
                    if (m.radius(x, y) <=
                        1f
                    ) {
                        inside[y * w + x] = true
                    }
                }
            }
        }
        return inside.count { it }.toDouble() / n
    }

    @Test
    fun theWorstCaseStaysWithinTheDocumentedBoundIsReservedInStepOneAndEqualsTheOldEngine() {
        val bound = MaskStageLimits.WORST_CASE_BYTES_PER_PIXEL * n + MaskStageLimits.WORST_CASE_FIXED_BYTES
        val source = image()
        val plans = MaskShape.entries.map { "whole image, $it" to whole(it) } + listOf("many masks" to many())
        for (style in MaskStyle.entries) {
            for ((label, plan) in plans) {
                val s = MaskSettings(style = style)
                if (label == "many masks") assertTrue(coverage(plan, s) >= 0.95, "$label covers at least 95 %")
                val alloc = RecordingAllocator()
                val steps = StepRecorder(alloc)
                val new = IntArrayPixels(w, h, source.data.copyOf())
                MaskEngine.apply(new, plan, s, {}, StageSeams(alloc, null, steps))
                val layout = steps.area?.layout ?: error("reserved")
                println("WORST $style $label: reserved ${alloc.bytes} bytes (bound ${bound.toLong()})")
                assertEquals(layout.bytes, alloc.bytes, "$style $label: reserved = computed")
                assertTrue(alloc.bytes <= bound, "$style $label: ${alloc.bytes} > $bound")
                assertTrue(alloc.requests.all { it.step == 1 }, "$style $label: every allocation in step 1")
                assertEquals(true, steps.zeroAfterReserve)
                val old = IntArrayPixels(w, h, source.data.copyOf())
                LegacyMaskEngine.apply(old, plan, s)
                assertContentEquals(old.data, new.data, "$style $label: equal to the old engine")
            }
        }
    }

    /** Step 2 allocates the same fixed small amount at 25 MP as at 1 MP (see MaskStageJvmTest); step 3 nothing. */
    @Test
    fun stepTwoAndThreeAllocationsDoNotGrowWithTheArea() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        val at = LongArray(5)
        val probe = object : StageObserver {
            override fun step(step: Int, work: Long) {
                at[step] = threads.getThreadAllocatedBytes(id)
            }
        }
        val img = image()
        for (style in MaskStyle.entries) {
            repeat(2) {
                MaskEngine.apply(
                    IntArrayPixels(500, 400),
                    whole(MaskShape.ELLIPSE).map {
                        it.copy(cx = 250f, cy = 200f, rx = 240f, ry = 190f)
                    },
                    MaskSettings(style = style),
                )
            }
            MaskEngine.apply(img, whole(MaskShape.ELLIPSE), MaskSettings(style = style), {
            }, StageSeams(observer = probe, probe = null))
            val step2 = at[3] - at[2]
            val step3 = at[4] - at[3]
            println("ALLOC25 $style: step 2 $step2 bytes, step 3 $step3 bytes")
            assertTrue(step2 <= 2_048, "$style: step 2 allocated $step2 bytes")
            assertEquals(0L, step3, "$style: step 3 allocated $step3 bytes")
        }
    }
}
