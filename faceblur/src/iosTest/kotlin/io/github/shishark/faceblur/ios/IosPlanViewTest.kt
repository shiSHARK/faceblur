// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.toLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A plan from `detect` on an oriented buffer wrapper is bound to that view. `applyMasks` through another
 * orientation (also the form without one, which is orientation 1) is refused with `MaskPlanMismatch` before anything
 * is written; through the same orientation it masks as before. Plans made with the public `MaskPlan` constructor keep
 * the size-only rule. Every pair of the 8 orientations, on a square and a non-square image (on a square image every
 * orientation has the same size, so only the view binding can refuse).
 */
class IosPlanViewTest {
    private val padding = 8
    private val layout = IosPixelLayout.BGRA_NONE_SKIP

    /** A stored buffer of [w] × [h] with row padding, every pixel opaque and distinct enough to show any write. */
    private class Buffer(val w: Int, val h: Int, val bpr: Int) {
        val p: CPointer<ByteVar> = nativeHeap.allocArray(bpr * h)

        init {
            refill()
        }

        fun refill() {
            for (y in 0 until h) {
                for (x in 0 until bpr) {
                    p[y * bpr + x] =
                        if (x % 4 == 3 && x < w * 4) 0xFF.toByte() else ((x * 7 + y * 13) and 0xFF).toByte()
                }
            }
        }

        fun bytes() = ByteArray(bpr * h) { p[it] }

        fun free() = nativeHeap.free(p.rawValue)
    }

    private fun buffer(uw: Int, uh: Int, o: Int): Buffer {
        val w = if (o >= 5) uh else uw
        val h = if (o >= 5) uw else uh
        return Buffer(w, h, w * 4 + padding)
    }

    private fun Buffer.view(o: Int?) = if (o == null) {
        FaceBlurIos.wrapRgba(p.toLong(), w, h, bpr, layout)
    } else {
        FaceBlurIos.wrapRgba(p.toLong(), w, h, bpr, layout, o)
    }

    private fun refused(masker: FaceMasker, plan: MaskPlan, target: Buffer, o: Int?, what: String) {
        val before = target.bytes()
        val e = assertFailsWith<FaceMaskException.MaskPlanMismatch>(what) { masker.applyMasks(target.view(o), plan) }
        assertEquals(FaceMaskException.MaskPlanMismatch.OTHER_VIEW, e.message, what)
        assertContentEquals(before, target.bytes(), "the buffer is byte-identical: $what")
    }

    private fun everyPair(uw: Int, uh: Int) {
        IosFiles.masker().use { masker ->
            // The stored buffers, one per orientation, reused while nothing is written to them.
            val targets = (1..8).associateWith { buffer(uw, uh, it) }
            try {
                for (d in 1..8) {
                    val source = targets.getValue(d)
                    val plan = masker.detect(source.view(d)).toMaskPlan()
                    assertEquals(listOf(uw, uh), listOf(plan.width, plan.height), "upright plan size d=$d")
                    assertTrue(plan.masks.isNotEmpty(), "the fixed face gives a mask d=$d")
                    val edited = plan.withMasks(plan.masks)
                    for (a in 1..8) {
                        val target = targets.getValue(a)
                        if (a == d) continue
                        refused(masker, plan, target, a, "plan of d=$d through a=$a ($uw x $uh)")
                        refused(masker, edited, target, a, "edited plan of d=$d through a=$a ($uw x $uh)")
                    }
                    if (d != 1) {
                        refused(masker, plan, targets.getValue(1), null, "plan of d=$d, without orientation")
                    }

                    // The same orientation masks as before: the same pixels as maskInPlace on a fresh copy.
                    val result = masker.applyMasks(source.view(d), plan)
                    assertEquals(plan.masks, result.masks, "masks d=$d")
                    val masked = source.bytes()
                    source.refill()
                    assertFalse(masked.contentEquals(source.bytes()), "the matching apply wrote masks d=$d")
                    masker.maskInPlace(source.view(d))
                    assertContentEquals(source.bytes(), masked, "the same pixels as maskInPlace d=$d")
                    source.refill()
                }
            } finally {
                targets.values.forEach { it.free() }
            }
        }
    }

    @Test
    fun everyOrientationPairOnANonSquareImage() = everyPair(640, 480)

    @Test
    fun everyOrientationPairOnASquareImage() = everyPair(480, 480)

    @Test
    fun theFormWithoutAnOrientationAcceptsAPlanFromOrientation1() {
        IosFiles.masker().use { masker ->
            val b = buffer(640, 480, 1)
            try {
                val plan = masker.detect(b.view(1)).toMaskPlan()
                assertEquals(plan.masks, masker.applyMasks(b.view(null), plan).masks)
                b.refill()
                val plain = masker.detect(b.view(null)).toMaskPlan()
                assertEquals(plain.masks, masker.applyMasks(b.view(1), plain).masks)
            } finally {
                b.free()
            }
        }
    }

    @Test
    fun aPlanFromThePublicConstructorKeepsTheSizeOnlyRule() {
        IosFiles.masker().use { masker ->
            val targets = (1..8).associateWith { buffer(480, 480, it) }
            try {
                val masks = masker.detect(targets.getValue(6).view(6)).toMaskPlan().masks
                val unbound = MaskPlan(480, 480, masks)
                for (a in 1..8) {
                    val t = targets.getValue(a)
                    val before = t.bytes()
                    assertEquals(masks, masker.applyMasks(t.view(a), unbound).masks, "a=$a")
                    assertFalse(before.contentEquals(t.bytes()), "masked a=$a")
                }
                // An edited unbound plan stays unbound.
                val t = targets.getValue(3)
                t.refill()
                masker.applyMasks(t.view(3), unbound.withMasks(masks))
                // The size rule is unchanged: a plan of the stored size of a turned non-square view is refused.
                val wide = buffer(640, 480, 6)
                try {
                    val before = wide.bytes()
                    val e = assertFailsWith<FaceMaskException.MaskPlanMismatch> {
                        masker.applyMasks(wide.view(6), MaskPlan(wide.w, wide.h, masks))
                    }
                    assertTrue(e.message!!.contains("${wide.w} x ${wide.h}"), e.message)
                    assertContentEquals(before, wide.bytes())
                } finally {
                    wide.free()
                }
            } finally {
                targets.values.forEach { it.free() }
            }
        }
    }

    @Test
    fun aPlanFromAnotherImageTypeIsTheUprightView() {
        IosFiles.masker().use { masker ->
            val plan = masker.detect(IntArrayPixels(480, 480, gradientPixels(480, 480))).toMaskPlan()
            val upright = buffer(480, 480, 1)
            val turned = buffer(480, 480, 6)
            try {
                refused(masker, plan, turned, 6, "plan of a common image through orientation 6")
                assertEquals(plan.masks, masker.applyMasks(upright.view(1), plan).masks)
                // And back: a plan from an oriented buffer is refused on an image that has no orientation of its own.
                val turnedPlan = masker.detect(turned.view(6)).toMaskPlan()
                val px = IntArrayPixels(480, 480, gradientPixels(480, 480))
                val before = px.data.copyOf()
                assertFailsWith<FaceMaskException.MaskPlanMismatch> { masker.applyMasks(px, turnedPlan) }
                assertContentEquals(before, px.data)
            } finally {
                upright.free()
                turned.free()
            }
        }
    }
}
