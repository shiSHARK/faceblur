// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.randomPixels
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Privacy properties of the opaque mask styles (ST-007, PRIV-005, PRIV-006, HANDOVER 6.6 #5) with the library
 * defaults, including large faces (whose fills are solved on a coarser grid) and overlapping faces.
 */
class MaskPrivacyTest {
    private val w = 640
    private val h = 520

    private fun face(l: Float, t: Float, r: Float, b: Float) = DetectedFace(RectF(l, t, r, b))

    private val faces =
        listOf(
            face(150f, 110f, 450f, 430f), // large: min mask radius > 96 px, fills use the coarse grid
            face(420f, 60f, 560f, 230f), // overlaps the large one
            face(580f, 420f, 700f, 560f), // crosses the right and bottom border
            face(8f, 6f, 40f, 44f), // small, near the top-left corner
        )

    /**
     * A view of [base] that returns a DIFFERENT pseudo-random ARGB value (colour AND alpha) on every read of a pixel
     * that lies inside any of [masks] and has not been written yet (an original face pixel). Writes go to [base].
     */
    private class PoisonedFacePixels(
        private val base: IntArrayPixels,
        private val masks: List<MaskGeometry>,
        seed: Int,
    ) : MutablePixelImage {
        override val width = base.width
        override val height = base.height
        private val written = BooleanArray(width * height)
        private var s = seed or 1
        var poisonedReads = 0
            private set

        private fun next(): Int {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            return s
        }

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
            base.readPixels(x, y, w, h, dst)
            for (yy in 0 until h) {
                for (xx in 0 until w) {
                    val px = x + xx
                    val py = y + yy
                    if (!written[py * width + px] && masks.any { it.containsPixel(px, py) }) {
                        val i = yy * w + xx
                        dst[i] = next()
                        poisonedReads++
                    }
                }
            }
        }

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            base.writePixels(x, y, w, h, src)
            for (yy in 0 until h) for (xx in 0 until w) written[(y + yy) * width + x + xx] = true
        }
    }

    /**
     * Runs [paint] on the clean image and on two poisoned views; returns true when all three outputs are identical,
     * i.e. the output (colour and alpha) does not depend on any original pixel value inside any mask.
     */
    private fun independentOfFacePixels(masks: List<MaskGeometry>, paint: (MutablePixelImage) -> Unit): Boolean {
        val clean = IntArrayPixels(w, h, gradientPixels(w, h)).also { paint(it) }
        val outputs =
            listOf(11, 12).map { seed ->
                val base = IntArrayPixels(w, h, gradientPixels(w, h))
                val view = PoisonedFacePixels(base, masks, seed)
                paint(view)
                base.data
            }
        return outputs.all { it.contentEquals(clean.data) }
    }

    @Test
    fun everyOpaqueStyleIsIndependentOfFacePixels() {
        for (style in MaskStyle.entries) {
            for (shape in MaskShape.entries) {
                for (feather in listOf(0f, 0.10f, 0.5f)) {
                    val cfg = MaskSettings(style = style, shape = shape, feather = feather)
                    val masks = faces.map { MaskPainter.maskFor(it, cfg) }
                    assertTrue(
                        independentOfFacePixels(masks) { MaskPainter.apply(it, faces, cfg) },
                        "$style/$shape/feather $feather output depends on face pixels",
                    )
                }
            }
        }
    }

    @Test
    fun gradientOvalWithSurroundingMeanCentreIsIndependentOfFacePixels() {
        val cfg = MaskSettings(style = MaskStyle.GRADIENT_OVAL, centerColorSource = CenterColorSource.SURROUNDING_MEAN)
        val masks = faces.map { MaskPainter.maskFor(it, cfg) }
        assertTrue(independentOfFacePixels(masks) { MaskPainter.apply(it, faces, cfg) })
    }

    @Test
    fun theFillsReadAroundTheMasks() {
        // Guards the harness: the poisoned view really is consulted, and the fills read pixels next to the masks.
        val cfg = MaskSettings()
        val masks = faces.map { MaskPainter.maskFor(it, cfg) }
        val view = PoisonedFacePixels(IntArrayPixels(w, h, gradientPixels(w, h)), masks, 3)
        MaskPainter.apply(view, faces, cfg)
        assertTrue(view.poisonedReads > 0, "the composite reads the mask area, so poison must be served")
    }

    /** Negative control (ST-007 AC3): a blur reads the face, and the harness must detect it. */
    @Test
    fun harnessDetectsABlurThatReadsTheFace() {
        val cfg = MaskSettings()
        val masks = faces.map { MaskPainter.maskFor(it, cfg) }
        assertFalse(independentOfFacePixels(masks) { img -> testOnlyBoxBlur(img, masks, radius = 6) })
    }

    /** Negative control: even one face pixel used for the colour of a solid mask is detected. */
    @Test
    fun harnessDetectsASolidFillThatSamplesOneFacePixel() {
        val cfg = MaskSettings(style = MaskStyle.SOLID)
        val masks = faces.map { MaskPainter.maskFor(it, cfg) }
        assertFalse(
            independentOfFacePixels(masks) { img ->
                val m = masks[0]
                val one = IntArray(1)
                img.readPixels(m.cx.toInt(), m.cy.toInt(), 1, 1, one)
                MaskPainter.applyMasks(img, masks, cfg.copy(color = one[0]))
            },
        )
    }

    /**
     * Negative control: a painter that keeps the face's own alpha inside the masks (the bench behaviour) is
     * detected, because the poisoned reads vary the alpha byte.
     */
    @Test
    fun harnessDetectsAMaskThatKeepsTheFaceAlpha() {
        val cfg = MaskSettings()
        val masks = faces.map { MaskPainter.maskFor(it, cfg) }
        assertFalse(
            independentOfFacePixels(masks) { img ->
                val before = IntArray(img.width * img.height)
                img.readPixels(0, 0, img.width, img.height, before)
                MaskPainter.apply(img, faces, cfg)
                val after = IntArray(img.width * img.height)
                img.readPixels(0, 0, img.width, img.height, after)
                for (i in after.indices) {
                    if (masks.any { it.containsPixel(i % img.width, i / img.width) }) {
                        after[i] = (before[i] and 0xFF000000.toInt()) or (after[i] and 0xFFFFFF)
                    }
                }
                img.writePixels(0, 0, img.width, img.height, after)
            },
        )
    }

    /**
     * ST-007 AC7: on canvases with alpha 0x80, 0x00 and a random alpha per pixel, every pixel inside a
     * mask has alpha 255 for every style, shape and feather; pixels beyond the soft edge keep their alpha.
     */
    @Test
    fun everyPixelInsideAMaskIsFullyOpaque() {
        val rgb = gradientPixels(w, h)
        val noise = randomPixels(w, h, 21)
        val canvases =
            listOf(
                "alpha 0x80" to IntArray(w * h) { (rgb[it] and 0xFFFFFF) or (0x80 shl 24) },
                "alpha 0x00" to IntArray(w * h) { rgb[it] and 0xFFFFFF },
                "random alpha" to IntArray(w * h) { (rgb[it] and 0xFFFFFF) or ((noise[it] and 0xFF) shl 24) },
            )
        for ((name, canvas) in canvases) {
            for (style in MaskStyle.entries) {
                for (shape in MaskShape.entries) {
                    for (feather in listOf(0f, 0.10f, 0.5f)) {
                        val cfg = MaskSettings(style = style, shape = shape, feather = feather)
                        val masks = faces.map { MaskPainter.maskFor(it, cfg) }
                        val img = IntArrayPixels(w, h, canvas.copyOf())
                        MaskPainter.apply(img, faces, cfg)
                        var inside = 0
                        for (y in 0 until h) {
                            for (x in 0 until w) {
                                val a = img.data[y * w + x] ushr 24
                                if (masks.any { it.containsPixel(x, y) }) {
                                    inside++
                                    assertEquals(0xFF, a, "$name $style/$shape/$feather ($x,$y) inside")
                                } else if (masks.all { beyondSoftEdge(it, feather, x, y) }) {
                                    assertEquals(canvas[y * w + x] ushr 24, a, "$name $style/$shape/$feather ($x,$y)")
                                }
                            }
                        }
                        assertTrue(inside > 50_000)
                    }
                }
            }
        }
    }

    private fun beyondSoftEdge(m: MaskGeometry, feather: Float, x: Int, y: Int): Boolean =
        m.normalizedRadius(x + 0.5f, y + 0.5f) >= 1f + maxOf(feather, 1f / minOf(m.rx, m.ry)) + 1e-4f

    /** In the soft edge the alpha moves towards 255 and never increases with r (no hard step at r = 1). */
    @Test
    fun alphaInTheSoftEdgeFallsMonotonicallyFromOpaqueToTheSource() {
        val rgb = gradientPixels(w, h)
        for (source in listOf(0x00, 0x80)) {
            for (style in MaskStyle.entries) {
                val f = face(200f, 100f, 400f, 340f)
                val cfg = MaskSettings(style = style)
                val img = IntArrayPixels(w, h, IntArray(w * h) { (rgb[it] and 0xFFFFFF) or (source shl 24) })
                MaskPainter.apply(img, listOf(f), cfg)
                val m = MaskPainter.maskFor(f, cfg)
                val band = ArrayList<Pair<Float, Int>>()
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val r = m.normalizedRadius(x + 0.5f, y + 0.5f)
                        if (r > 1f && r < 1.1f) band += r to (img.data[y * w + x] ushr 24)
                    }
                }
                val sorted = band.sortedBy { it.first }
                assertTrue(sorted.first().second > source + 0x60, "near r = 1 the edge is close to opaque")
                for (i in 1 until sorted.size) {
                    assertTrue(sorted[i].second <= sorted[i - 1].second, "$style alpha rises at r=${sorted[i].first}")
                }
            }
        }
    }

    /**
     * Opacity invariant of the default style (ADR 0002 decision 10), checked against the independent formula of
     * the measured ellipse
     * (r = hypot((x - cx) / (0.8 w), (y - cy) / (0.9 h)), cy = box centre - 0.08 h; outward band 0.10 for this
     * box size): r <= 1 is fully opaque (alpha 255); beyond the band nothing changes (colour and alpha); inside the
     * band the alpha only moves towards opaque. Returns the violations (empty = invariant holds).
     */
    private fun gradientOvalViolations(out: IntArray, canvas: IntArray, box: RectF): List<String> {
        val bw = (box.right - box.left).toDouble()
        val bh = (box.bottom - box.top).toDouble()
        val cx = (box.left + box.right) / 2.0
        val cy = (box.top + box.bottom) / 2.0 - 0.08 * bh
        val found = ArrayList<String>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = hypot((x + 0.5 - cx) / (0.8 * bw), (y + 0.5 - cy) / (0.9 * bh))
                if (abs(r - 1.0) < 1e-6 || abs(r - 1.10) < 1e-6) continue
                val o = out[y * w + x]
                val c = canvas[y * w + x]
                when {
                    r <= 1.0 -> if (o ushr 24 != 0xFF) found += "($x,$y) inside with alpha ${o ushr 24}"
                    r > 1.10 -> if (o != c) found += "($x,$y) beyond the soft edge changed"
                    else -> if (o ushr 24 < c ushr 24) found += "($x,$y) soft edge more transparent than the photo"
                }
            }
        }
        return found
    }

    @Test
    fun defaultGradientOvalIsOpaqueExactlyOnTheMeasuredEllipseWithAColourGradientOnly() {
        val box = RectF(200f, 100f, 400f, 340f)
        val rgb = gradientPixels(w, h)
        for (source in listOf(0x00, 0x80, 0xFF)) {
            for (centre in CenterColorSource.entries) {
                val cfg = MaskSettings(centerColorSource = centre)
                assertEquals(MaskStyle.GRADIENT_OVAL, cfg.style, "default style")
                val canvas = IntArray(w * h) { (rgb[it] and 0xFFFFFF) or (source shl 24) }
                val img = IntArrayPixels(w, h, canvas.copyOf())
                MaskPainter.apply(img, listOf(DetectedFace(box)), cfg)
                assertEquals(emptyList(), gradientOvalViolations(img.data, canvas, box), "alpha $source $centre")
                // The gradient is a colour gradient: opaque pixels inside the ellipse carry many different colours.
                val m = MaskPainter.maskFor(DetectedFace(box), cfg)
                val inside = (0 until w * h).filter { m.containsPixel(it % w, it / w) }.map { img.data[it] }
                assertTrue(inside.all { it ushr 24 == 0xFF })
                if (source == 0x00) {
                    // Nothing visible around the oval to sample: a solid oval in `color`.
                    assertTrue(inside.all { it == cfg.color }, "alpha 0 surroundings: solid color")
                } else {
                    assertTrue(inside.map { it and 0xFFFFFF }.toSet().size > 50, "colour varies inside the oval")
                }
            }
        }
    }

    /** Negative control for the opacity-invariant check: one planted translucent pixel inside the ellipse is caught. */
    @Test
    fun theGradientOvalCheckCatchesAPlantedTranslucentPixel() {
        val box = RectF(200f, 100f, 400f, 340f)
        val canvas = gradientPixels(w, h)
        val img = IntArrayPixels(w, h, canvas.copyOf())
        MaskPainter.apply(img, listOf(DetectedFace(box)), MaskSettings())
        assertEquals(emptyList(), gradientOvalViolations(img.data, canvas, box))
        val centre = 300 + 200 * w
        val planted = img.data.copyOf().also { it[centre] = (it[centre] and 0xFFFFFF) or (0xFE shl 24) }
        assertEquals(1, gradientOvalViolations(planted, canvas, box).size)
        val outside = img.data.copyOf().also { it[5 + 5 * w] = it[5 + 5 * w] xor 1 }
        assertEquals(1, gradientOvalViolations(outside, canvas, box).size)
    }

    /** A box blur of every mask's bounding block (test code only: the library has no blur). */
    private fun testOnlyBoxBlur(img: MutablePixelImage, masks: List<MaskGeometry>, radius: Int) {
        for (m in masks) {
            val l = (m.cx - m.rx).toInt().coerceIn(0, img.width)
            val t = (m.cy - m.ry).toInt().coerceIn(0, img.height)
            val r = (m.cx + m.rx).toInt().coerceIn(0, img.width)
            val b = (m.cy + m.ry).toInt().coerceIn(0, img.height)
            val bw = r - l
            val bh = b - t
            if (bw <= 0 || bh <= 0) continue
            val src = IntArray(bw * bh)
            img.readPixels(l, t, bw, bh, src)
            val out = IntArray(bw * bh)
            for (y in 0 until bh) {
                for (x in 0 until bw) {
                    var g = 0
                    var n = 0
                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            val xx = x + dx
                            val yy = y + dy
                            if (xx in 0 until bw && yy in 0 until bh) {
                                g += (src[yy * bw + xx] shr 8) and 0xFF
                                n++
                            }
                        }
                    }
                    out[y * bw + x] = (0xFF shl 24) or ((g / n) shl 8)
                }
            }
            img.writePixels(l, t, bw, bh, out)
        }
    }

    /**
     * ST-007 AC1 with the default geometry, against an independent formula: r = hypot((x - cx) / (0.8 w),
     * (y - cy) / (0.9 h)) with cy = box centre - 0.08 h. r ≤ 1 is pure mask colour, r > 1.10 is the input, and in
     * the feather band the mask's share never increases with r.
     */
    @Test
    fun defaultGeometryMatchesTheSpecifiedEllipseAndOutwardFeather() {
        val bg = 0xFF204060.toInt()
        val colour = 0xFFE0C0A0.toInt()
        val box = face(200f, 100f, 400f, 340f) // w 200, h 240: min radius 160 px, so the 0.10 feather applies
        val img = IntArrayPixels(w, h, IntArray(w * h) { bg })
        val cfg = MaskSettings(style = MaskStyle.SOLID, color = colour)
        MaskPainter.apply(img, listOf(box), cfg)
        val engineMask = MaskPainter.maskFor(box, cfg)
        val bw = 200.0
        val bh = 240.0
        val cx = 300.0
        val cy = 220.0 - 0.08 * bh
        val band = ArrayList<Pair<Float, Int>>()
        var inside = 0
        var outside = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = hypot((x + 0.5 - cx) / (0.8 * bw), (y + 0.5 - cy) / (0.9 * bh))
                // Pixels within 1e-6 of a threshold are ambiguous between float and double arithmetic; none are skipped
                // in practice, the guard only keeps the oracle honest.
                if (abs(r - 1.0) < 1e-6 || abs(r - 1.10) < 1e-6) continue
                val p = img.data[y * w + x]
                when {
                    r <= 1.0 -> {
                        assertEquals(colour, p, "($x,$y) r=$r inside the mask")
                        inside++
                    }

                    r > 1.10 -> {
                        assertEquals(bg, p, "($x,$y) r=$r beyond the feather")
                        outside++
                    }

                    // Ordered by the engine's own radius, so float/double rounding cannot reorder near-equal radii.
                    else -> band += engineMask.normalizedRadius(x + 0.5f, y + 0.5f) to ((p shr 8) and 0xFF)
                }
            }
        }
        assertTrue(inside > 70_000 && outside > 150_000 && band.size > 1_000, "$inside / $outside / ${band.size}")
        // Green channel moves from the mask colour (0xC0) towards the background (0x40): never back up with r.
        val sorted = band.sortedBy { it.first }
        for (i in 1 until sorted.size) {
            assertTrue(sorted[i].second <= sorted[i - 1].second, "opacity rises at r=${sorted[i].first}")
        }
    }

    @Test
    fun smallFacesStillGetAtLeastAOnePixelAntiAliasedEdgeAndNothingBeyondIt() {
        // min radius 10.8 px → the band is max(0.10, 1 / 10.8) = 0.10; at 6.75 px it widens to 1 / 6.75 = 0.148.
        val bg = 0xFF204060.toInt()
        for ((side, band) in listOf(16f to 0.10f, 10f to 1f / 6.75f)) {
            val img = IntArrayPixels(w, h, IntArray(w * h) { bg })
            val f = face(100f, 100f, 100f + side, 100f + side)
            val cfg = MaskSettings(style = MaskStyle.SOLID, color = 0xFFFFFFFF.toInt())
            MaskPainter.apply(img, listOf(f), cfg)
            val m = MaskPainter.maskFor(f, cfg)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val r = m.normalizedRadius(x + 0.5f, y + 0.5f)
                    if (r >= 1f + band + 1e-4f) assertEquals(bg, img.data[y * w + x], "side $side ($x,$y) r=$r")
                    if (r <= 1f) assertEquals(0xFFFFFFFF.toInt(), img.data[y * w + x], "side $side ($x,$y) r=$r")
                }
            }
        }
    }

    /** ST-007 AC4: the opaque part of the default mask covers at least 99 % of the face box. */
    @Test
    fun defaultMaskCoversAtLeast99PercentOfTheBox() {
        val cfg = MaskSettings()
        for (box in listOf(face(100f, 100f, 300f, 340f), face(50f, 50f, 150f, 150f), face(10f, 10f, 70f, 90f))) {
            val m = MaskPainter.maskFor(box, cfg)
            var covered = 0
            var total = 0
            for (y in box.rect.top.toInt() until box.rect.bottom.toInt()) {
                for (x in box.rect.left.toInt() until box.rect.right.toInt()) {
                    total++
                    if (m.containsPixel(x, y)) covered++
                }
            }
            assertTrue(covered >= 0.99 * total, "${box.rect}: $covered of $total")
        }
    }

    /**
     * Overlapping faces: every fill is computed from the original pixels, so a face's fill does not change when the
     * other face is composited first. Only pixels that both masks (including feather) reach depend on the order
     * (the later composite wins there).
     */
    @Test
    fun overlappingFacesAreFilledFromTheOriginalPixelsInEitherOrder() {
        val pair = listOf(face(100f, 100f, 260f, 300f), face(220f, 120f, 380f, 320f))
        for (style in MaskStyle.entries) {
            val cfg = MaskSettings(style = style)
            val a = IntArrayPixels(w, h, gradientPixels(w, h)).also { MaskPainter.apply(it, pair, cfg) }
            val b = IntArrayPixels(w, h, gradientPixels(w, h)).also { MaskPainter.apply(it, pair.reversed(), cfg) }
            val masks = pair.map { MaskPainter.maskFor(it, cfg) }
            var compared = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val reached = masks.count { it.normalizedRadius(x + 0.5f, y + 0.5f) < 1.11f }
                    if (reached <= 1) {
                        assertEquals(a.data[y * w + x], b.data[y * w + x], "$style ($x,$y)")
                        compared++
                    }
                }
            }
            assertTrue(compared > w * h / 2)
        }
    }

    @Test
    fun maskStylesAreExactlyTheThreeOpaqueStyles() {
        assertContentEquals(
            listOf(MaskStyle.GRADIENT_OVAL, MaskStyle.SMOOTH_FILL, MaskStyle.SOLID),
            MaskStyle.entries,
        )
        for (s in MaskStyle.entries) {
            for (word in listOf("BLUR", "PIXEL", "MOSAIC", "COSMETIC", "GAUSS", "TRANSPAR", "PARTIAL")) {
                assertFalse(word in s.name, "${s.name} contains $word")
            }
        }
    }
}
