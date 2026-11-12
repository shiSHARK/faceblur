// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.isFatal
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The mask engine before ADR 0013 (two-phase groups), kept verbatim in the tests as the reference for the equality
 * tests of the bounded stage. Only names were changed (Legacy prefix). Not used by the library.
 */

/**
 * The OTHER face masks near one face. Fills must never sample a pixel inside any face's opaque mask: the own mask is
 * excluded by its radius test, these by [contains] (rectangle pre-check, then the exact radius test).
 */
internal class LegacyOtherMasks(private val masks: List<PreparedMask>, private val rects: List<IRect>) {
    private val n = masks.size

    fun contains(x: Int, y: Int): Boolean {
        for (j in 0 until n) {
            val r = rects[j]
            if (x < r.left || x >= r.right || y < r.top || y >= r.bottom) continue
            if (masks[j].radius(x, y) <= 1f) return true
        }
        return false
    }
}

/**
 * Region bookkeeping, grouping of overlapping faces and the final outward-feathered composite. The fills
 * themselves live in [LegacyGradientOvalEffect] and [LegacySmoothFillEffect]; SOLID is a constant colour.
 */
internal object LegacyMaskEngine {

    fun apply(image: MutablePixelImage, masks: List<MaskGeometry>, config: MaskSettings) {
        val iw = image.width
        val ih = image.height
        // All checks run before the first call into the caller's image.
        if (iw < 0 || ih < 0 || iw.toLong() * ih > Int.MAX_VALUE) throw CoreFailure.ImageTooLarge(iw, ih)
        val drawn = masks.map { PreparedMask.usable(it) }
        if (iw == 0 || ih == 0) return
        val prepared = drawn.map { PreparedMask(it, config) }
        val writes = ArrayList<IRect>()
        val reads = ArrayList<IRect>()
        val active = ArrayList<PreparedMask>()
        for (m in prepared) {
            val w = m.extent(1f + m.featherN, iw, ih)
            if (w.isEmpty) continue
            active += m
            writes += w
            reads += readRect(m, w, config, iw, ih)
        }
        if (active.isEmpty()) return
        // Opaque-mask rectangles of ALL masks (also ones whose write area is empty) for the privacy exclusion.
        val maskRects = prepared.map { it.extent(1f, iw, ih) }

        val started = BooleanArray(1)
        try {
            paintGroups(image, prepared, active, writes, reads, maskRects, config, started)
        } catch (e: Throwable) {
            // Before the first write the image is untouched and the error passes on as it is. After it, the image
            // may be partly masked: report that (the image must be discarded), never a plain internal error.
            if (!started[0] || isFatal(e) || e is CoreFailure.WriteNotApplied) throw e
            throw CoreFailure.WriteInterrupted(e)
        }
    }

    private fun paintGroups(
        image: MutablePixelImage,
        prepared: List<PreparedMask>,
        active: List<PreparedMask>,
        writes: List<IRect>,
        reads: List<IRect>,
        maskRects: List<IRect>,
        config: MaskSettings,
        started: BooleanArray,
    ) {
        for (group in groups(reads)) {
            // Phase 1: every effect of the group from the original pixels.
            val effects = group.map { i ->
                val others = prepared.indices.filter { j ->
                    prepared[j] !== active[i] &&
                        maskRects[j].intersects(reads[i])
                }
                val neighbours = LegacyOtherMasks(others.map { prepared[it] }, others.map { maskRects[it] })
                computeEffect(image, active[i], writes[i], reads[i], neighbours, config)
            }
            // Phase 2: composite in list order.
            for ((k, i) in group.withIndex()) composite(image, active[i], writes[i], effects[k], started)
        }
    }

    private fun readRect(m: PreparedMask, write: IRect, config: MaskSettings, iw: Int, ih: Int): IRect =
        when (config.style) {
            MaskStyle.SOLID -> write
            MaskStyle.GRADIENT_OVAL -> m.extent(1f + max(m.featherN, m.ringN), iw, ih).union(write)
            MaskStyle.SMOOTH_FILL -> m.extent(1f + m.featherN + m.ringN, iw, ih).union(write)
        }

    /** Connected components of overlapping read rectangles, each sorted by index, ordered by first index. */
    private fun groups(rects: List<IRect>): List<List<Int>> {
        val n = rects.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (rects[i].intersects(rects[j])) {
                    val a = find(i)
                    val b = find(j)
                    if (a != b) parent[max(a, b)] = min(a, b)
                }
            }
        }
        val byRoot = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until n) byRoot.getOrPut(find(i)) { ArrayList() } += i
        return byRoot.values.toList()
    }

    private fun computeEffect(
        image: MutablePixelImage,
        m: PreparedMask,
        write: IRect,
        read: IRect,
        neighbours: LegacyOtherMasks,
        config: MaskSettings,
    ): IntArray = when (config.style) {
        MaskStyle.SOLID -> IntArray(write.area).also { it.fill(config.color or (0xFF shl 24)) }

        MaskStyle.GRADIENT_OVAL ->
            LegacyGradientOvalEffect.compute(readBlock(image, read), read, write, m, neighbours, config)

        MaskStyle.SMOOTH_FILL ->
            LegacySmoothFillEffect.compute(readBlock(image, read), read, write, m, neighbours, config)
    }

    private fun readBlock(image: MutablePixelImage, read: IRect): IntArray {
        val src = IntArray(read.area)
        image.readPixels(read.left, read.top, read.width, read.height, src)
        return src
    }

    /**
     * Outward feather: r ≤ 1 → exactly the effect with alpha 255; feather band → smoothstep blend of the
     * colour and of the alpha towards 255; beyond the band nothing is written back changed. Every written
     * pixel with alpha 255 is read back and compared, so an image that drops or alters writes fails.
     */
    private fun composite(
        image: MutablePixelImage,
        m: PreparedMask,
        write: IRect,
        effect: IntArray,
        started: BooleanArray,
    ) {
        val w = write.width
        val h = write.height
        val buf = IntArray(w * h)
        image.readPixels(write.left, write.top, w, h, buf)
        val outer = 1f + m.featherN
        for (yy in 0 until h) {
            val y = write.top + yy
            val dy = (y + 0.5f - m.cy) / m.ry
            val row = yy * w
            for (xx in 0 until w) {
                val dx = (write.left + xx + 0.5f - m.cx) / m.rx
                val r = if (m.ellipse) sqrt(dx * dx + dy * dy) else max(abs(dx), abs(dy))
                if (r >= outer) continue
                val i = row + xx
                val o = buf[i]
                val e = effect[i]
                if (r <= 1f) {
                    buf[i] = OPAQUE or (e and 0xFFFFFF)
                } else {
                    val a = (m.alpha(r) * 256f + 0.5f).toInt()
                    if (a <= 0) continue
                    val oA = o ushr 24
                    val na = oA + (((255 - oA) * a + 128) shr 8)
                    buf[i] = (na shl 24) or blendColour(o, oA, e, a)
                }
            }
        }
        started[0] = true
        image.writePixels(write.left, write.top, w, h, buf)
        verifyWritten(image, write, buf)
    }

    /**
     * Colour of a soft-edge pixel: the opaque fill [e] with weight [a] / 256 over the photo pixel [o] (alpha [oA]).
     * Opaque photo pixels use the benchmark's straight blend (bit-identical results). Translucent photo pixels are
     * composited "source over" in premultiplied form, so a colour that the photo hid under a low alpha is not
     * revealed when the soft edge raises the alpha: at alpha 0 the result is exactly the fill colour.
     */
    private fun blendColour(o: Int, oA: Int, e: Int, a: Int): Int {
        val oR = (o shr 16) and 0xFF
        val oG = (o shr 8) and 0xFF
        val oB = o and 0xFF
        val eR = (e shr 16) and 0xFF
        val eG = (e shr 8) and 0xFF
        val eB = e and 0xFF
        if (oA == 0xFF) {
            val nr = oR + (((eR - oR) * a + 128) shr 8)
            val ng = oG + (((eG - oG) * a + 128) shr 8)
            val nb = oB + (((eB - oB) * a + 128) shr 8)
            return (nr shl 16) or (ng shl 8) or nb
        }
        val wf = 255 * a
        val wp = oA * (256 - a)
        val den = wf + wp
        val nr = (eR * wf + oR * wp + den / 2) / den
        val ng = (eG * wf + oG * wp + den / 2) / den
        val nb = (eB * wf + oB * wp + den / 2) / den
        return (nr shl 16) or (ng shl 8) or nb
    }

    /**
     * Reads the block back and compares every pixel written with alpha 255 (all pixels inside the mask). Pixels with
     * a lower alpha are skipped, because platform images that store premultiplied values cannot round-trip them
     * exactly.
     */
    private fun verifyWritten(image: MutablePixelImage, write: IRect, written: IntArray) {
        val back = IntArray(written.size)
        image.readPixels(write.left, write.top, write.width, write.height, back)
        for (i in written.indices) {
            val p = written[i]
            if (p ushr 24 == 0xFF && back[i] != p) throw CoreFailure.WriteNotApplied()
        }
    }

    private const val OPAQUE: Int = 0xFF shl 24
}

/**
 * GRADIENT_OVAL — solid centre colour that turns into the colours surrounding the oval.
 *
 * Working in normalized elliptical coordinates (r, θ), r = 1 on the mask boundary:
 * 1. **Ring sampling** — every pixel with 1 < r ≤ 1 + ringWidth that is inside the image and NOT inside any face
 *    mask goes into one of 36…128 angular bins (≈ one bin per 4 px of perimeter). Each bin's colour is a
 *    **robust** average: the median luma of the bin (from a 64-level histogram) anchors Cauchy weights
 *    1 / (1 + ((L - median) / s)²), so thin bright details (stars, specular highlights, a strand of hair) do not
 *    lift the colour above what the eye perceives as the background — a plain mean produced a light halo
 *    around ovals on dark textured backgrounds. Averages are taken on sRGB values (perceptual).
 *    Empty bins (image border, neighbouring faces) are filled by circular linear interpolation.
 *    The bins are then smoothed circularly with a compact Gaussian (σ ≈ 4°) → boundary colour B(θ).
 * 2. **Harmonic extension** — instead of stretching each angle's colour straight to the centre (which gives a
 *    "sunburst" of radial streaks), B is extended into the disc with the Poisson kernel
 *    P_r(φ) = (1-r²) / (1 - 2r·cos φ + r²): the k-th angular frequency decays like rᵏ towards the centre, exactly
 *    like the solution of Laplace's equation (a soap film spanned by the ring colours). H(1, θ) = B(θ) and H(0) =
 *    mean of B. (Using the heavy-tailed Poisson kernel itself to smooth the boundary, i.e. ρ = 0.9·r, leaked ~12 %
 *    of the far side's colours into the rim — a visible brown rim on black backgrounds.)
 * 3. **Solid centre** — r ≤ innerSolidFraction is the centre colour (FIXED colour, or mean of B = H(0) for
 *    SURROUNDING_MEAN). Between innerSolidFraction and 1 the colour moves from the centre colour to H(r, θ) with a
 *    smoothstep over the normalized AREA of the band ((r²-r₀²)/(1-r₀²)) rather than the radius, so the transition
 *    sits in the outer part and the oval reads as a solid shape instead of a blur.
 *    All averaging (bins, smoothing, harmonic extension, centre) is done on sRGB values so that the centre and
 *    H agree; only this final centre → H interpolation is done in linear light (monotonic per channel, so no ring
 *    can appear; averaging in linear light let bright details dominate and produced a light ring inside the oval).
 *    The field is tabulated (32 radial levels × bins), evaluated on a grid (every pixel, or every k px for big
 *    faces) and bilinearly interpolated, then converted back to sRGB with a LUT.
 * 4. Outside the mask the effect continues with the boundary colour and the composite feathers it into the photo.
 * No pixel inside any mask is ever read.
 */
internal object LegacyGradientOvalEffect {
    private const val LEVELS = 32
    private const val BOUNDARY_SMOOTH_DEG = 4f
    private const val HIST = 64
    private const val TWO_PI = (2.0 * PI).toFloat()

    fun binCount(m: PreparedMask): Int {
        val perimeter = TWO_PI * sqrt((m.rx * m.rx + m.ry * m.ry) / 2f)
        return (perimeter / 4f).roundToInt().coerceIn(36, 128)
    }

    private fun luma(p: Int): Int = (54 * ((p shr 16) and 0xFF) + 183 * ((p shr 8) and 0xFF) + 19 * (p and 0xFF)) shr 8

    fun compute(
        src: IntArray,
        read: IRect,
        write: IRect,
        m: PreparedMask,
        others: LegacyOtherMasks,
        config: MaskSettings,
    ): IntArray {
        val bins = binCount(m)
        // Big faces: sample every `step`-th ring pixel and evaluate the (smooth) colour field on a k-spaced grid.
        // Grid spacing; never wider than the region, so index arithmetic stays far inside the Int range.
        val k = max(1, floor(min(m.rx, m.ry) / 48f).toInt()).coerceAtMost(max(1, max(read.width, read.height)))
        val step = max(1, k / 2)
        val ringOuter = 1f + m.ringN
        val binScale = bins / TWO_PI
        val pi = PI.toFloat()
        val rw = read.width
        val lin = Srgb.toLinear

        // Pass 1: usable ring samples (bin, colour) + luma histogram per bin.
        var sBin = IntArray(1024)
        var sPix = IntArray(1024)
        var ns = 0
        val hist = IntArray(bins * HIST)
        val cnt = IntArray(bins)
        var y = read.top
        while (y < read.bottom) {
            val dy = (y + 0.5f - m.cy) / m.ry
            val row = (y - read.top) * rw - read.left
            var x = read.left
            while (x < read.right) {
                val dx = (x + 0.5f - m.cx) / m.rx
                val r = if (m.ellipse) sqrt(dx * dx + dy * dy) else max(abs(dx), abs(dy))
                // Pixels with alpha 0 are not sampled: their colour is hidden in the photo.
                if (r > 1f && r <= ringOuter && src[row + x] ushr 24 != 0 && !others.contains(x, y)) {
                    var b = ((fastAtan2(dy, dx) + pi) * binScale).toInt()
                    if (b >= bins) {
                        b = bins - 1
                    } else if (b < 0) {
                        b = 0
                    }
                    val p = src[row + x]
                    if (ns == sBin.size) {
                        sBin = sBin.copyOf(ns * 2)
                        sPix = sPix.copyOf(ns * 2)
                    }
                    sBin[ns] = b
                    sPix[ns] = p
                    ns++
                    hist[b * HIST + (luma(p) shr 2)]++
                    cnt[b]++
                }
                x += step
            }
            y += step
        }
        val fixed = config.color
        val fixedR = lin[(fixed shr 16) and 0xFF]
        val fixedG = lin[(fixed shr 8) and 0xFF]
        val fixedB = lin[fixed and 0xFF]
        if (cnt.all { it == 0 }) {
            // Nothing visible around the face (whole image, or surrounded by other masks): flat fill.
            val c = Srgb.encodeRgb(fixedR, fixedG, fixedB)
            return IntArray(write.area).also { it.fill(c) }
        }
        // Robust centre (median luma) and scale (median absolute deviation) per bin.
        val med = FloatArray(bins)
        val scale = FloatArray(bins)
        for (b in 0 until bins) {
            if (cnt[b] == 0) continue
            val h0 = b * HIST
            val half = (cnt[b] + 1) / 2
            var acc = 0
            var level = 0
            while (level < HIST - 1 && acc + hist[h0 + level] < half) acc += hist[h0 + level++]
            val mL = level * 4f + 2f
            med[b] = mL
            // MAD from the histogram.
            val dev = IntArray(HIST)
            for (l in 0 until HIST) if (hist[h0 + l] > 0) dev[abs(l - level)] += hist[h0 + l]
            acc = 0
            var d = 0
            while (d < HIST - 1 && acc + dev[d] < half) acc += dev[d++]
            scale[b] = max(6f, 1.5f * (d * 4f))
        }
        // Pass 2: Cauchy-weighted sRGB mean per bin.
        val sr = FloatArray(bins)
        val sg = FloatArray(bins)
        val sb = FloatArray(bins)
        val sw = FloatArray(bins)
        for (i in 0 until ns) {
            val b = sBin[i]
            val p = sPix[i]
            val z = (luma(p) - med[b]) / scale[b]
            val w = 1f / (1f + z * z)
            sr[b] += w * ((p shr 16) and 0xFF)
            sg[b] += w * ((p shr 8) and 0xFF)
            sb[b] += w * (p and 0xFF)
            sw[b] += w
        }
        for (b in 0 until bins) {
            if (cnt[b] > 0) {
                val inv = 1f / sw[b]
                sr[b] *= inv
                sg[b] *= inv
                sb[b] *= inv
            }
        }
        fillCircular(sr, cnt)
        fillCircular(sg, cnt)
        fillCircular(sb, cnt)
        val sigmaBins = BOUNDARY_SMOOTH_DEG / 360f * bins
        smoothCircular(sr, sigmaBins)
        smoothCircular(sg, sigmaBins)
        smoothCircular(sb, sigmaBins)

        val cR: Float
        val cG: Float
        val cB: Float
        if (config.centerColorSource == CenterColorSource.FIXED) {
            cR = fixedR
            cG = fixedG
            cB = fixedB
        } else {
            cR = srgbToLinear(sr.average().toFloat())
            cG = srgbToLinear(sg.average().toFloat())
            cB =
                srgbToLinear(sb.average().toFloat())
        }

        // Colour table over (radial level, bin), linear light.
        val r0 = config.innerSolidFraction.coerceIn(0f, 0.98f)
        val n = LEVELS * bins
        val tr = FloatArray(n)
        val tg = FloatArray(n)
        val tb = FloatArray(n)
        val kernel = FloatArray(bins)
        for (l in 0 until LEVELS) {
            val f = l.toFloat() / (LEVELS - 1)
            val rl = r0 + (1f - r0) * f
            val u = ((rl * rl - r0 * r0) / (1f - r0 * r0)).coerceIn(0f, 1f)
            val t = u * u * (3f - 2f * u)
            poissonKernel(rl, kernel)
            val base = l * bins
            for (j in 0 until bins) {
                var hr = 0f
                var hg = 0f
                var hb = 0f
                var idx = j
                for (d in 0 until bins) {
                    val w = kernel[d]
                    hr += w * sr[idx]
                    hg += w * sg[idx]
                    hb += w * sb[idx]
                    idx++
                    if (idx == bins) idx = 0
                }
                tr[base + j] = cR + (srgbToLinear(hr) - cR) * t
                tg[base + j] = cG + (srgbToLinear(hg) - cG) * t
                tb[base + j] = cB + (srgbToLinear(hb) - cB) * t
            }
        }

        // Evaluate the field on grid points spaced k px apart (k = 1: every pixel), then interpolate.
        val ww = write.width
        val wh = write.height
        val gw = (ww - 1) / k + 2
        val gh = (wh - 1) / k + 2
        val grid = IntArray(gw * gh)
        val centre = Srgb.encodeRgb(cR, cG, cB)
        val outer = 1f + m.featherN + k / min(m.rx, m.ry)
        val uScale = bins / TWO_PI
        val lScale = (LEVELS - 1) / (1f - r0)
        for (gy in 0 until gh) {
            val dy = (write.top + gy * k + 0.5f - m.cy) / m.ry
            for (gx in 0 until gw) {
                val dx = (write.left + gx * k + 0.5f - m.cx) / m.rx
                val r = if (m.ellipse) sqrt(dx * dx + dy * dy) else max(abs(dx), abs(dy))
                if (r <= r0) {
                    grid[gy * gw + gx] = centre
                    continue
                }
                val u = (fastAtan2(dy, dx) + pi) * uScale - 0.5f
                val uf = floor(u)
                val fj = u - uf
                var j0 = uf.toInt()
                if (j0 < 0) {
                    j0 += bins
                } else if (j0 >= bins) {
                    j0 -= bins
                }
                val j1 = if (j0 + 1 == bins) 0 else j0 + 1
                val lf = (min(r, 1f) - r0) * lScale
                val l0 = min(lf.toInt(), LEVELS - 2)
                val fl = lf - l0
                val a = l0 * bins
                val b = a + bins
                val w00 = (1f - fl) * (1f - fj)
                val w01 = (1f - fl) * fj
                val w10 = fl * (1f - fj)
                val w11 = fl * fj
                grid[gy * gw + gx] = if (r >= outer) {
                    centre
                } else {
                    Srgb.encodeRgb(
                        tr[a + j0] * w00 + tr[a + j1] * w01 + tr[b + j0] * w10 + tr[b + j1] * w11,
                        tg[a + j0] * w00 + tg[a + j1] * w01 + tg[b + j0] * w10 + tg[b + j1] * w11,
                        tb[a + j0] * w00 + tb[a + j1] * w01 + tb[b + j0] * w10 + tb[b + j1] * w11,
                    )
                }
            }
        }
        if (k == 1) {
            val out = IntArray(write.area)
            for (yy in 0 until wh) grid.copyInto(out, yy * ww, yy * gw, yy * gw + ww)
            return out
        }
        return upsampleGrid(grid, gw, k, ww, wh)
    }

    /** Bilinear interpolation of packed sRGB grid values (spacing [k]) to a [ww]×[wh] block. */
    private fun upsampleGrid(grid: IntArray, gw: Int, k: Int, ww: Int, wh: Int): IntArray {
        val out = IntArray(ww * wh)
        val inv = 1f / k
        for (yy in 0 until wh) {
            val gy = yy / k
            val ty = (yy - gy * k) * inv
            for (xx in 0 until ww) {
                val gx = xx / k
                val tx = (xx - gx * k) * inv
                val p00 = grid[gy * gw + gx]
                val p01 = grid[gy * gw + gx + 1]
                val p10 = grid[(gy + 1) * gw + gx]
                val p11 = grid[(gy + 1) * gw + gx + 1]
                val w00 = (1f - tx) * (1f - ty)
                val w01 = tx * (1f - ty)
                val w10 = (1f - tx) * ty
                val w11 = tx * ty
                var v = 0xFF shl 24
                for (sh in intArrayOf(16, 8, 0)) {
                    val c = ((p00 shr sh) and 0xFF) * w00 + ((p01 shr sh) and 0xFF) * w01 +
                        ((p10 shr sh) and 0xFF) * w10 + ((p11 shr sh) and 0xFF) * w11
                    v = v or (clamp255((c + 0.5f).toInt()) shl sh)
                }
                out[yy * ww + xx] = v
            }
        }
        return out
    }

    /** sRGB value 0..255 (fractional) → linear light, interpolating the 8-bit table. */
    private fun srgbToLinear(v: Float): Float {
        val c = v.coerceIn(0f, 255f)
        val i = min(c.toInt(), 254)
        val f = c - i
        return Srgb.toLinear[i] + (Srgb.toLinear[i + 1] - Srgb.toLinear[i]) * f
    }

    /** Circular Gaussian smoothing of [v] with [sigma] in bins (no-op below 0.3). */
    fun smoothCircular(v: FloatArray, sigma: Float) {
        if (sigma < 0.3f) return
        val n = v.size
        val rad = min(n / 2, ceil(3f * sigma).toInt())
        val w = FloatArray(rad + 1) { d -> exp(-(d * d) / (2f * sigma * sigma)) }
        val norm = w[0] + 2f * (1..rad).sumOf { w[it].toDouble() }.toFloat()
        val src = v.copyOf()
        for (j in 0 until n) {
            var acc = w[0] * src[j]
            for (d in 1..rad) acc += w[d] * (src[(j + d) % n] + src[(j - d + n) % n])
            v[j] = acc / norm
        }
    }

    /** Discrete Poisson kernel for [rho] (normalized to sum 1); rho = 0 → plain mean, rho → 1 → identity. */
    fun poissonKernel(rho: Float, out: FloatArray) {
        val n = out.size
        if (rho >= 0.999f) {
            out.fill(0f)
            out[0] = 1f
            return
        }
        val rr = rho * rho
        var sum = 0f
        for (d in 0 until n) {
            val v = (1f - rr) / (1f - 2f * rho * cos(TWO_PI * d / n) + rr)
            out[d] = v
            sum += v
        }
        val inv = 1f / sum
        for (d in 0 until n) out[d] *= inv
    }

    /** Fills bins with cnt == 0 by circular linear interpolation between the nearest filled bins. */
    fun fillCircular(v: FloatArray, cnt: IntArray) {
        val n = v.size
        val filled = (0 until n).filter { cnt[it] > 0 }
        if (filled.isEmpty() || filled.size == n) return
        if (filled.size == 1) {
            v.fill(v[filled[0]])
            return
        }
        for (k in filled.indices) {
            val a = filled[k]
            val b = filled[(k + 1) % filled.size]
            val gap = (b - a + n) % n
            if (gap <= 1) continue
            for (s in 1 until gap) {
                val t = s.toFloat() / gap
                v[(a + s) % n] = v[a] + (v[b] - v[a]) * t
            }
        }
    }
}

/**
 * SMOOTH_FILL — fills the mask from its surroundings only (a membrane / harmonic fill).
 *
 * Known pixels: inside the image, outside EVERY face mask, and outside this mask's feather band (so the fill is
 * smooth in the band and the composite blends it into the photo). The solve runs on a grid of k×k blocks
 * (k ≈ min radius / 48; block value = mean of its known pixels) and is bilinearly upsampled. Pull: a pyramid of
 * weighted 2×2 means of the known cells up to 1×1. Push: from coarse to fine, unknown pixels start from the bilinear upsample of the coarser
 * level and get a few Gauss-Seidel Laplace iterations with the known pixels fixed (a cascadic multigrid, close to
 * the true harmonic interpolation of the boundary). Works on sRGB values: in linear light the bright neck below a
 * chin dominated the membrane and left a glow. Never reads a pixel inside any mask.
 */
internal object LegacySmoothFillEffect {
    private class Level(val w: Int, val h: Int) {
        val r = FloatArray(w * h)
        val g = FloatArray(w * h)
        val b = FloatArray(w * h)
        val wt = FloatArray(w * h)
    }

    fun compute(
        src: IntArray,
        read: IRect,
        write: IRect,
        m: PreparedMask,
        others: LegacyOtherMasks,
        config: MaskSettings,
    ): IntArray {
        val rw = read.width
        val rh = read.height
        val bandOuter = 1f + m.featherN
        // The fill is smooth by construction, so it is solved on a k×k-downsampled grid (≈ 48 cells across the
        // smaller mask radius) and bilinearly upsampled: O(area / k²) instead of O(area) for big faces.
        // Grid spacing; never wider than the region, so index arithmetic stays far inside the Int range.
        val k = max(1, floor(min(m.rx, m.ry) / 48f).toInt()).coerceAtMost(max(1, max(read.width, read.height)))
        val w0 = (rw + k - 1) / k
        val h0 = (rh + k - 1) / k
        val base = Level(w0, h0)
        val step = max(1, k / 3) // ≥ 9 samples per k×k block
        var y = 0
        while (y < rh) {
            val py = read.top + y
            val brow = (y / k) * w0
            var x = 0
            while (x < rw) {
                val px = read.left + x
                // Pixels with alpha 0 are not sampled: their colour is hidden in the photo.
                if (m.radius(px, py) > bandOuter && src[y * rw + x] ushr 24 != 0 && !others.contains(px, py)) {
                    val p = src[y * rw + x]
                    val bi = brow + x / k
                    base.r[bi] += ((p shr 16) and 0xFF).toFloat()
                    base.g[bi] += ((p shr 8) and 0xFF).toFloat()
                    base.b[bi] += (p and 0xFF).toFloat()
                    base.wt[bi] += 1f
                }
                x += step
            }
            y += step
        }
        for (i in 0 until w0 * h0) {
            val c = base.wt[i]
            if (c > 0f) {
                val inv = 1f / c
                base.r[i] *= inv
                base.g[i] *= inv
                base.b[i] *= inv
            }
        }
        // Pull.
        val levels = arrayListOf(base)
        while (levels.last().w > 1 || levels.last().h > 1) levels += pull(levels.last())
        if (levels.last().wt[0] <= 0f) {
            val c = config.color or (0xFF shl 24)
            return IntArray(write.area).also { it.fill(c) }
        }
        // Push + relax.
        for (l in levels.size - 2 downTo 0) push(levels[l], levels[l + 1], if (l == 0 && k == 1) 3 else 5)

        val ww = write.width
        val out = IntArray(write.area)
        val invK = 1f / k
        for (yy in 0 until write.height) {
            val fy = ((write.top + yy - read.top) + 0.5f) * invK - 0.5f
            val y0 = floor(fy).toInt().coerceIn(0, h0 - 1)
            val y1 = min(y0 + 1, h0 - 1)
            val ty = (fy - y0).coerceIn(0f, 1f)
            for (xx in 0 until ww) {
                val fx = ((write.left + xx - read.left) + 0.5f) * invK - 0.5f
                val x0 = floor(fx).toInt().coerceIn(0, w0 - 1)
                val x1 = min(x0 + 1, w0 - 1)
                val tx = (fx - x0).coerceIn(0f, 1f)
                val a = y0 * w0 + x0
                val b = y0 * w0 + x1
                val c = y1 * w0 + x0
                val d = y1 * w0 + x1
                val wa = (1f - tx) * (1f - ty)
                val wb = tx * (1f - ty)
                val wc = (1f - tx) * ty
                val wd = tx * ty
                val r = base.r[a] * wa + base.r[b] * wb + base.r[c] * wc + base.r[d] * wd
                val g = base.g[a] * wa + base.g[b] * wb + base.g[c] * wc + base.g[d] * wd
                val bl = base.b[a] * wa + base.b[b] * wb + base.b[c] * wc + base.b[d] * wd
                out[yy * ww + xx] = (0xFF shl 24) or (clamp255((r + 0.5f).toInt()) shl 16) or
                    (clamp255((g + 0.5f).toInt()) shl 8) or clamp255((bl + 0.5f).toInt())
            }
        }
        return out
    }

    private fun pull(c: Level): Level {
        val p = Level((c.w + 1) / 2, (c.h + 1) / 2)
        for (y in 0 until p.h) {
            for (x in 0 until p.w) {
                var sw = 0f
                var sr = 0f
                var sg = 0f
                var sb = 0f
                for (dy in 0..1) {
                    val cy = 2 * y + dy
                    if (cy >= c.h) continue
                    for (dx in 0..1) {
                        val cx = 2 * x + dx
                        if (cx >= c.w) continue
                        val i = cy * c.w + cx
                        val w = c.wt[i]
                        if (w <= 0f) continue
                        sw += w
                        sr += w * c.r[i]
                        sg += w * c.g[i]
                        sb += w * c.b[i]
                    }
                }
                if (sw > 0f) {
                    val i = y * p.w + x
                    val inv = 1f / sw
                    p.r[i] = sr * inv
                    p.g[i] = sg * inv
                    p.b[i] = sb * inv
                    p.wt[i] = sw
                }
            }
        }
        return p
    }

    private fun push(fine: Level, coarse: Level, iterations: Int) {
        var count = 0
        for (i in 0 until fine.w * fine.h) if (fine.wt[i] <= 0f) count++
        if (count == 0) return
        val unknown = IntArray(count)
        var k = 0
        for (i in 0 until fine.w * fine.h) if (fine.wt[i] <= 0f) unknown[k++] = i
        // Initial guess: bilinear upsample of the coarser level.
        val cw = coarse.w
        val ch = coarse.h
        for (i in unknown) {
            val x = i % fine.w
            val y = i / fine.w
            val fx = (x + 0.5f) * 0.5f - 0.5f
            val fy = (y + 0.5f) * 0.5f - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, cw - 1)
            val y0 = floor(fy).toInt().coerceIn(0, ch - 1)
            val x1 = min(x0 + 1, cw - 1)
            val y1 = min(y0 + 1, ch - 1)
            val tx = (fx - x0).coerceIn(0f, 1f)
            val ty = (fy - y0).coerceIn(0f, 1f)
            val a = y0 * cw + x0
            val b = y0 * cw + x1
            val c = y1 * cw + x0
            val d = y1 * cw + x1
            val wa = (1f - tx) * (1f - ty)
            val wb = tx * (1f - ty)
            val wc = (1f - tx) * ty
            val wd = tx * ty
            fine.r[i] = coarse.r[a] * wa + coarse.r[b] * wb + coarse.r[c] * wc + coarse.r[d] * wd
            fine.g[i] = coarse.g[a] * wa + coarse.g[b] * wb + coarse.g[c] * wc + coarse.g[d] * wd
            fine.b[i] = coarse.b[a] * wa + coarse.b[b] * wb + coarse.b[c] * wc + coarse.b[d] * wd
        }
        // Gauss-Seidel Laplace relaxation on the unknown pixels (alternating sweep direction). Neighbour
        // availability is precomputed so that the common interior case has no bounds checks or divisions.
        val w = fine.w
        val h = fine.h
        val flags = ByteArray(count)
        for (n in 0 until count) {
            val i = unknown[n]
            val x = i % w
            val y = i / w
            var f = 0
            if (x > 0) f = f or 1
            if (x < w - 1) f = f or 2
            if (y > 0) f = f or 4
            if (y < h - 1) f = f or 8
            flags[n] = f.toByte()
        }
        val r = fine.r
        val g = fine.g
        val b = fine.b
        for (it in 0 until iterations) {
            val forward = it % 2 == 0
            for (k2 in 0 until count) {
                val n = if (forward) k2 else count - 1 - k2
                val i = unknown[n]
                val f = flags[n].toInt()
                if (f == 15) {
                    r[i] = (r[i - 1] + r[i + 1] + r[i - w] + r[i + w]) * 0.25f
                    g[i] = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w]) * 0.25f
                    b[i] = (b[i - 1] + b[i + 1] + b[i - w] + b[i + w]) * 0.25f
                    continue
                }
                var sr = 0f
                var sg = 0f
                var sb = 0f
                var c = 0
                if (f and 1 != 0) {
                    sr += r[i - 1]
                    sg += g[i - 1]
                    sb += b[i - 1]
                    c++
                }
                if (f and 2 != 0) {
                    sr += r[i + 1]
                    sg += g[i + 1]
                    sb += b[i + 1]
                    c++
                }
                if (f and 4 != 0) {
                    sr += r[i - w]
                    sg += g[i - w]
                    sb += b[i - w]
                    c++
                }
                if (f and 8 != 0) {
                    sr += r[i + w]
                    sg += g[i + w]
                    sb += b[i + w]
                    c++
                }
                if (c > 0) {
                    val inv = 1f / c
                    r[i] = sr * inv
                    g[i] = sg * inv
                    b[i] = sb * inv
                }
            }
        }
    }
}
