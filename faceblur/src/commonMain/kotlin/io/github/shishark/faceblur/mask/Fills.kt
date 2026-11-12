// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.mask

import io.github.shishark.faceblur.CoreFailure
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
 * The fills of the mask stage, computed tile by tile in the reserved working area (ADR 0013 decision 6): no fill
 * holds a buffer of its mask's area, and nothing here allocates an array (source gate). Every value is the same
 * function of the same inputs as in the engine before ADR 0013, evaluated in the same order where order matters
 * (the float sums of the ring samples), so the output is bit-identical (equality tests against a copy of that engine).
 */

/** Shared access of the fills to the call's image, working area, coverage map and work counter. */
internal class FillContext(
    private val image: io.github.shishark.faceblur.PixelImage,
    val area: WorkingArea,
    val work: StageWork,
    val config: MaskSettings,
    private val tilesX: Int,
) {
    /** Reads [len] (1..64) original pixels of row [y] from [x0] into the reserved run buffer of exactly that length. */
    fun readRun(x0: Int, y: Int, len: Int): IntArray {
        val buf = area.runRead[len] ?: throw CoreFailure.InvalidMask("no run buffer")
        image.readPixels(x0, y, len, 1, buf)
        work.add(len)
        return buf
    }

    /** True when the centre of pixel ([x], [y]) lies inside the opaque mask of any mask of the plan. */
    fun covered(x: Int, y: Int): Boolean {
        work.add(1)
        val slot = area.coverageTable[(y ushr TILE_SHIFT) * tilesX + (x ushr TILE_SHIFT)]
        if (slot == 0) return false
        return (area.coverage[(slot - 1) * TILE + (y and TILE_MASK)] ushr (x and TILE_MASK)) and 1L != 0L
    }

    /** Mask [i]'s stored values (see [MaskEngine]). */
    fun cx(i: Int) = area.maskFloats[i * AreaLayout.MASK_FLOATS]

    fun cy(i: Int) = area.maskFloats[i * AreaLayout.MASK_FLOATS + 1]

    fun rx(i: Int) = area.maskFloats[i * AreaLayout.MASK_FLOATS + 2]

    fun ry(i: Int) = area.maskFloats[i * AreaLayout.MASK_FLOATS + 3]

    fun feather(i: Int) = area.maskFloats[i * AreaLayout.MASK_FLOATS + 4]

    fun ring(i: Int) = area.maskFloats[i * AreaLayout.MASK_FLOATS + 5]

    fun ellipse(i: Int) = area.maskFlags[i].toInt() and FLAG_ELLIPSE != 0

    /** Rectangle value [k] of mask [i]: 0-3 write, 4-7 read, 8-11 opaque (left, top, right, bottom). */
    fun rect(i: Int, k: Int) = area.maskInts[i * AreaLayout.MASK_INTS + k]

    companion object {
        const val TILE = MaskStageLimits.TILE
        const val TILE_SHIFT = 6
        const val TILE_MASK = TILE - 1
        const val FLAG_ELLIPSE = 1
        const val FLAG_ACTIVE = 2
        const val WRITE = 0
        const val READ = 4
        const val OPAQUE = 8
    }
}

/**
 * GRADIENT_OVAL — solid centre colour that turns into the colours surrounding the oval.
 *
 * Working in normalized elliptical coordinates (r, θ), r = 1 on the mask boundary:
 * 1. **Ring sampling** — every pixel with 1 < r ≤ 1 + ringWidth that is inside the image and NOT inside any face
 *    mask goes into one of 36…128 angular bins (≈ one bin per 4 px of perimeter). Each bin's colour is a
 *    **robust** average: the median luma of the bin (from a 64-level histogram) anchors Cauchy weights
 *    1 / (1 + ((L - median) / s)²), so thin bright details (stars, specular highlights, a strand of hair) do not
 *    lift the colour above what the eye perceives as the background. Averages are taken on sRGB values (perceptual).
 *    The ring is read twice in the same order: once for the histograms, once for the weighted means.
 *    Empty bins (image border, neighbouring faces) are filled by circular linear interpolation.
 *    The bins are then smoothed circularly with a compact Gaussian (σ ≈ 4°) → boundary colour B(θ).
 * 2. **Harmonic extension** — B is extended into the disc with the Poisson kernel P_r(φ) = (1-r²) / (1 - 2r·cos φ + r²):
 *    the k-th angular frequency decays like rᵏ towards the centre, like the solution of Laplace's equation.
 * 3. **Solid centre** — r ≤ innerSolidFraction is the centre colour (FIXED colour, or mean of B for
 *    SURROUNDING_MEAN). Between innerSolidFraction and 1 the colour moves from the centre colour to H(r, θ) with a
 *    smoothstep over the normalized AREA of the band; this interpolation is done in linear light. The field is
 *    tabulated (32 radial levels × bins), evaluated on a grid (every pixel, or every k px for big faces) per shadow
 *    tile and bilinearly interpolated.
 * 4. Outside the mask the effect continues with the boundary colour and the composite feathers it into the photo.
 * No pixel inside any mask is ever read.
 */
internal class GradientOvalFill(private val c: FillContext) {
    /** True when nothing around the mask is visible: the mask gets the flat colour [flatColour]. */
    var flat = false
        private set
    var flatColour = 0
        private set

    private var cx = 0f
    private var cy = 0f
    private var rx = 0f
    private var ry = 0f
    private var ellipse = true
    private var writeLeft = 0
    private var writeTop = 0
    private var k = 1
    private var bins = 0
    private var r0 = 0f
    private var centre = 0
    private var outer = 0f
    private var uScale = 0f
    private var lScale = 0f
    private var invK = 1f

    // The grid points of the current tile (k > 1): origin and row length in [WorkingArea.grid].
    private var gridX0 = 0
    private var gridY0 = 0
    private var gridW = 0

    /** Computes the colour table of mask [i] from the original pixels of its read rectangle. */
    fun prepare(i: Int) {
        val a = c.area
        val work = c.work
        cx = c.cx(i)
        cy = c.cy(i)
        rx = c.rx(i)
        ry = c.ry(i)
        ellipse = c.ellipse(i)
        writeLeft = c.rect(i, FillContext.WRITE)
        writeTop = c.rect(i, FillContext.WRITE + 1)
        val readL = c.rect(i, FillContext.READ)
        val readT = c.rect(i, FillContext.READ + 1)
        val readR = c.rect(i, FillContext.READ + 2)
        val readB = c.rect(i, FillContext.READ + 3)
        bins = binCount(rx, ry)
        // Big faces: sample every `step`-th ring pixel and evaluate the (smooth) colour field on a k-spaced grid.
        k = max(1, floor(min(rx, ry) / 48f).toInt()).coerceAtMost(max(1, max(readR - readL, readB - readT)))
        invK = 1f / k
        val step = max(1, k / 2)
        val n = bins
        val hist = a.hist
        val cnt = a.count
        for (j in 0 until n * HIST) {
            work.add(1)
            hist[j] = 0
        }
        for (b in 0 until n) {
            work.add(1)
            cnt[b] = 0
        }
        // Pass 1: luma histogram per bin.
        sampleRing(i, readL, readT, readR, readB, step, firstPass = true)
        val config = c.config
        val lin = Srgb.toLinear
        val fixed = config.color
        val fixedR = lin[(fixed shr 16) and 0xFF]
        val fixedG = lin[(fixed shr 8) and 0xFF]
        val fixedB = lin[fixed and 0xFF]
        var any = false
        for (b in 0 until n) {
            work.add(1)
            if (cnt[b] != 0) any = true
        }
        if (!any) {
            // Nothing visible around the face (whole image, or surrounded by other masks): flat fill.
            flat = true
            flatColour = Srgb.encodeRgb(fixedR, fixedG, fixedB)
            return
        }
        flat = false
        robustCentres()
        val sr = a.sumR
        val sg = a.sumG
        val sb = a.sumB
        val sw = a.sumW
        for (b in 0 until n) {
            work.add(1)
            sr[b] = 0f
            sg[b] = 0f
            sb[b] = 0f
            sw[b] = 0f
        }
        // Pass 2: Cauchy-weighted sRGB mean per bin, over the same samples in the same order.
        sampleRing(i, readL, readT, readR, readB, step, firstPass = false)
        for (b in 0 until n) {
            work.add(1)
            if (cnt[b] > 0) {
                val inv = 1f / sw[b]
                sr[b] *= inv
                sg[b] *= inv
                sb[b] *= inv
            }
        }
        fillCircular(sr)
        fillCircular(sg)
        fillCircular(sb)
        val sigmaBins = BOUNDARY_SMOOTH_DEG / 360f * n
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
            cR = srgbToLinear(average(sr).toFloat())
            cG = srgbToLinear(average(sg).toFloat())
            cB = srgbToLinear(average(sb).toFloat())
        }
        table(cR, cG, cB)
        centre = Srgb.encodeRgb(cR, cG, cB)
        outer = 1f + c.feather(i) + k / min(rx, ry)
        uScale = n / TWO_PI
        lScale = (LEVELS - 1) / (1f - r0)
    }

    /**
     * One pass over the ring samples, row by row along the read rectangle (every `step`-th row and column), the rows
     * read in runs of at most 64 pixels. Pass 1 fills the histograms, pass 2 the weighted sums.
     */
    private fun sampleRing(i: Int, readL: Int, readT: Int, readR: Int, readB: Int, step: Int, firstPass: Boolean) {
        val a = c.area
        val work = c.work
        val ringOuter = 1f + c.ring(i)
        val binScale = bins / TWO_PI
        val n = bins
        val hist = a.hist
        val cnt = a.count
        val med = a.median
        val scale = a.scale
        val sr = a.sumR
        val sg = a.sumG
        val sb = a.sumB
        val sw = a.sumW
        var y = readT
        while (y < readB) {
            val dy = (y + 0.5f - cy) / ry
            var x0 = readL
            while (x0 < readR) {
                val len = min(MaskStageLimits.MAX_RUN, readR - x0)
                val row = c.readRun(x0, y, len)
                // The first sample column at or after x0.
                var x = readL + (x0 - readL + step - 1) / step * step
                while (x < x0 + len) {
                    work.add(1)
                    val dx = (x + 0.5f - cx) / rx
                    val r = if (ellipse) sqrt(dx * dx + dy * dy) else max(abs(dx), abs(dy))
                    val p = row[x - x0]
                    // Pixels with alpha 0 are not sampled: their colour is hidden in the photo.
                    if (r > 1f && r <= ringOuter && p ushr 24 != 0 && !c.covered(x, y)) {
                        var b = ((fastAtan2(dy, dx) + PI_F) * binScale).toInt()
                        if (b >= n) {
                            b = n - 1
                        } else if (b < 0) {
                            b = 0
                        }
                        if (firstPass) {
                            hist[b * HIST + (luma(p) shr 2)]++
                            cnt[b]++
                        } else {
                            val z = (luma(p) - med[b]) / scale[b]
                            val w = 1f / (1f + z * z)
                            sr[b] += w * ((p shr 16) and 0xFF)
                            sg[b] += w * ((p shr 8) and 0xFF)
                            sb[b] += w * (p and 0xFF)
                            sw[b] += w
                        }
                    }
                    x += step
                }
                x0 += len
            }
            y += step
        }
    }

    /** Robust centre (median luma) and scale (median absolute deviation) per bin, from the histograms. */
    private fun robustCentres() {
        val a = c.area
        val work = c.work
        val hist = a.hist
        val cnt = a.count
        val dev = a.deviation
        for (b in 0 until bins) {
            work.add(1)
            if (cnt[b] == 0) continue
            val h0 = b * HIST
            val half = (cnt[b] + 1) / 2
            var acc = 0
            var level = 0
            while (level < HIST - 1 && acc + hist[h0 + level] < half) {
                work.add(1)
                acc += hist[h0 + level++]
            }
            a.median[b] = level * 4f + 2f
            for (l in 0 until HIST) {
                work.add(1)
                dev[l] = 0
            }
            for (l in 0 until HIST) {
                work.add(1)
                if (hist[h0 + l] > 0) dev[abs(l - level)] += hist[h0 + l]
            }
            acc = 0
            var d = 0
            while (d < HIST - 1 && acc + dev[d] < half) {
                work.add(1)
                acc += dev[d++]
            }
            a.scale[b] = max(6f, 1.5f * (d * 4f))
        }
    }

    /** Colour table over (radial level, bin), linear light. */
    private fun table(cR: Float, cG: Float, cB: Float) {
        val a = c.area
        val work = c.work
        val n = bins
        val sr = a.sumR
        val sg = a.sumG
        val sb = a.sumB
        val kernel = a.kernel
        r0 = c.config.innerSolidFraction.coerceIn(0f, 0.98f)
        for (l in 0 until LEVELS) {
            val f = l.toFloat() / (LEVELS - 1)
            val rl = r0 + (1f - r0) * f
            val u = ((rl * rl - r0 * r0) / (1f - r0 * r0)).coerceIn(0f, 1f)
            val t = u * u * (3f - 2f * u)
            poissonKernel(rl)
            val base = l * n
            for (j in 0 until n) {
                // One unit per kernel step of this table entry.
                work.add(n)
                var hr = 0f
                var hg = 0f
                var hb = 0f
                var idx = j
                for (d in 0 until n) {
                    val w = kernel[d]
                    hr += w * sr[idx]
                    hg += w * sg[idx]
                    hb += w * sb[idx]
                    idx++
                    if (idx == n) idx = 0
                }
                a.tableR[base + j] = cR + (srgbToLinear(hr) - cR) * t
                a.tableG[base + j] = cG + (srgbToLinear(hg) - cG) * t
                a.tableB[base + j] = cB + (srgbToLinear(hb) - cB) * t
            }
        }
    }

    /**
     * Prepares the pixels [xa]..[xb) × [ya]..[yb) of one shadow tile (inside the write rectangle): for a grid
     * spacing above 1, the grid points they need, with a border of one point, go into [WorkingArea.grid].
     */
    fun prepareTile(xa: Int, ya: Int, xb: Int, yb: Int) {
        if (flat || k == 1) return
        val gxa = (xa - writeLeft) / k
        val gya = (ya - writeTop) / k
        val gxb = (xb - 1 - writeLeft) / k + 1
        val gyb = (yb - 1 - writeTop) / k + 1
        gridX0 = gxa
        gridY0 = gya
        gridW = gxb - gxa + 1
        val grid = c.area.grid
        for (gy in gya..gyb) {
            for (gx in gxa..gxb) grid[(gy - gya) * gridW + (gx - gxa)] = gridValue(gx, gy)
        }
    }

    /** The fill colour of pixel ([x], [y]) of the write rectangle (opaque ARGB). */
    fun value(x: Int, y: Int): Int {
        if (flat) return flatColour
        val xx = x - writeLeft
        val yy = y - writeTop
        if (k == 1) return gridValue(xx, yy)
        c.work.add(1)
        val grid = c.area.grid
        val gy = yy / k
        val ty = (yy - gy * k) * invK
        val gx = xx / k
        val tx = (xx - gx * k) * invK
        val row0 = (gy - gridY0) * gridW + (gx - gridX0)
        val row1 = row0 + gridW
        val p00 = grid[row0]
        val p01 = grid[row0 + 1]
        val p10 = grid[row1]
        val p11 = grid[row1 + 1]
        val w00 = (1f - tx) * (1f - ty)
        val w01 = tx * (1f - ty)
        val w10 = (1f - tx) * ty
        val w11 = tx * ty
        var v = 0xFF shl 24
        v = v or (channel(p00, p01, p10, p11, w00, w01, w10, w11, 16) shl 16)
        v = v or (channel(p00, p01, p10, p11, w00, w01, w10, w11, 8) shl 8)
        return v or channel(p00, p01, p10, p11, w00, w01, w10, w11, 0)
    }

    private fun channel(
        p00: Int,
        p01: Int,
        p10: Int,
        p11: Int,
        w00: Float,
        w01: Float,
        w10: Float,
        w11: Float,
        sh: Int,
    ): Int {
        val v = ((p00 shr sh) and 0xFF) * w00 + ((p01 shr sh) and 0xFF) * w01 +
            ((p10 shr sh) and 0xFF) * w10 + ((p11 shr sh) and 0xFF) * w11
        return clamp255((v + 0.5f).toInt())
    }

    /** The colour field at grid point ([gx], [gy]) (spacing k from the write rectangle's top-left pixel). */
    private fun gridValue(gx: Int, gy: Int): Int {
        c.work.add(1)
        val a = c.area
        val dy = (writeTop + gy * k + 0.5f - cy) / ry
        val dx = (writeLeft + gx * k + 0.5f - cx) / rx
        val r = if (ellipse) sqrt(dx * dx + dy * dy) else max(abs(dx), abs(dy))
        if (r <= r0) return centre
        val n = bins
        val u = (fastAtan2(dy, dx) + PI_F) * uScale - 0.5f
        val uf = floor(u)
        val fj = u - uf
        var j0 = uf.toInt()
        if (j0 < 0) {
            j0 += n
        } else if (j0 >= n) {
            j0 -= n
        }
        val j1 = if (j0 + 1 == n) 0 else j0 + 1
        val lf = (min(r, 1f) - r0) * lScale
        val l0 = min(lf.toInt(), LEVELS - 2)
        val fl = lf - l0
        val ia = l0 * n
        val ib = ia + n
        val w00 = (1f - fl) * (1f - fj)
        val w01 = (1f - fl) * fj
        val w10 = fl * (1f - fj)
        val w11 = fl * fj
        if (r >= outer) return centre
        val tr = a.tableR
        val tg = a.tableG
        val tb = a.tableB
        return Srgb.encodeRgb(
            tr[ia + j0] * w00 + tr[ia + j1] * w01 + tr[ib + j0] * w10 + tr[ib + j1] * w11,
            tg[ia + j0] * w00 + tg[ia + j1] * w01 + tg[ib + j0] * w10 + tg[ib + j1] * w11,
            tb[ia + j0] * w00 + tb[ia + j1] * w01 + tb[ib + j0] * w10 + tb[ib + j1] * w11,
        )
    }

    /** Mean of the first [bins] values, summed in double in index order (as `FloatArray.average`). */
    private fun average(v: FloatArray): Double {
        var sum = 0.0
        for (b in 0 until bins) {
            c.work.add(1)
            sum += v[b]
        }
        return sum / bins
    }

    /** Circular Gaussian smoothing of the first [bins] values with [sigma] in bins (no-op below 0.3). */
    private fun smoothCircular(v: FloatArray, sigma: Float) {
        if (sigma < 0.3f) return
        val n = bins
        val rad = min(n / 2, ceil(3f * sigma).toInt())
        val w = c.area.gauss
        c.work.add(2 * rad + 1 + n)
        for (d in 0..rad) w[d] = exp(-(d * d) / (2f * sigma * sigma))
        var tail = 0.0
        for (d in 1..rad) tail += w[d].toDouble()
        val norm = w[0] + 2f * tail.toFloat()
        val src = c.area.smoothCopy
        v.copyInto(src, 0, 0, n)
        for (j in 0 until n) {
            c.work.add(2 * rad + 1)
            var acc = w[0] * src[j]
            for (d in 1..rad) acc += w[d] * (src[(j + d) % n] + src[(j - d + n) % n])
            v[j] = acc / norm
        }
    }

    /** Discrete Poisson kernel for [rho] into the first [bins] entries (normalized to sum 1). */
    private fun poissonKernel(rho: Float) {
        val n = bins
        val out = c.area.kernel
        c.work.add(2 * n)
        if (rho >= 0.999f) {
            for (d in 0 until n) out[d] = 0f
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

    /** Fills bins without samples by circular linear interpolation between the nearest bins with samples. */
    private fun fillCircular(v: FloatArray) {
        val n = bins
        val cnt = c.area.count
        var filled = 0
        var first = -1
        for (b in 0 until n) {
            c.work.add(1)
            if (cnt[b] > 0) {
                filled++
                if (first < 0) first = b
            }
        }
        if (filled == 0 || filled == n) return
        if (filled == 1) {
            c.work.add(n)
            val value = v[first]
            for (b in 0 until n) v[b] = value
            return
        }
        var a = first
        do {
            var b = (a + 1) % n
            while (cnt[b] <= 0) {
                c.work.add(1)
                b = (b + 1) % n
            }
            val gap = (b - a + n) % n
            for (s in 1 until gap) {
                c.work.add(1)
                val t = s.toFloat() / gap
                v[(a + s) % n] = v[a] + (v[b] - v[a]) * t
            }
            a = b
        } while (a != first)
    }

    /** sRGB value 0..255 (fractional) → linear light, interpolating the 8-bit table. */
    private fun srgbToLinear(v: Float): Float {
        val cl = v.coerceIn(0f, 255f)
        val i = min(cl.toInt(), 254)
        val f = cl - i
        return Srgb.toLinear[i] + (Srgb.toLinear[i + 1] - Srgb.toLinear[i]) * f
    }

    companion object {
        const val LEVELS = AreaLayout.LEVELS
        const val HIST = AreaLayout.HIST
        private const val BOUNDARY_SMOOTH_DEG = 4f
        private const val TWO_PI = (2.0 * PI).toFloat()
        private const val PI_F = PI.toFloat()

        /** Angular bins of a mask with radii [rx], [ry]: about one per 4 px of perimeter, 36 to 128. */
        fun binCount(rx: Float, ry: Float): Int {
            val perimeter = TWO_PI * sqrt((rx * rx + ry * ry) / 2f)
            return (perimeter / 4f).roundToInt().coerceIn(36, 128)
        }

        private fun luma(p: Int): Int =
            (54 * ((p shr 16) and 0xFF) + 183 * ((p shr 8) and 0xFF) + 19 * (p and 0xFF)) shr 8
    }
}

/**
 * SMOOTH_FILL — fills the mask from its surroundings only (a membrane / harmonic fill).
 *
 * Known pixels: inside the image, outside EVERY face mask, and outside this mask's feather band (so the fill is
 * smooth in the band and the composite blends it into the photo). The solve runs on a grid of k×k blocks
 * (k ≈ min radius / 48, raised when the base grid would exceed [MaskStageLimits.MAX_SMOOTH_FILL_CELLS]; block value
 * = mean of its known pixels) and is bilinearly upsampled per shadow tile. Pull: a pyramid of weighted 2×2 means of
 * the known cells up to 1×1. Push: from coarse to fine, unknown cells start from the bilinear upsample of the coarser
 * level and get a few Gauss-Seidel Laplace iterations with the known cells fixed. Works on sRGB values. Never reads a
 * pixel inside any mask.
 */
internal class SmoothFill(private val c: FillContext) {
    /** True when no known pixel exists around the mask: it gets the flat colour [flatColour]. */
    var flat = false
        private set
    var flatColour = 0
        private set

    private var readLeft = 0
    private var readTop = 0
    private var w0 = 1
    private var h0 = 1
    private var invK = 1f

    /** Solves mask [i]'s grid from the original pixels of its read rectangle. */
    fun prepare(i: Int) {
        val a = c.area
        val work = c.work
        val cx = c.cx(i)
        val cy = c.cy(i)
        val rx = c.rx(i)
        val ry = c.ry(i)
        val ellipse = c.ellipse(i)
        readLeft = c.rect(i, FillContext.READ)
        readTop = c.rect(i, FillContext.READ + 1)
        val rw = c.rect(i, FillContext.READ + 2) - readLeft
        val rh = c.rect(i, FillContext.READ + 3) - readTop
        val bandOuter = 1f + c.feather(i)
        val k = spacing(rx, ry, rw, rh, work)
        invK = 1f / k
        w0 = (rw + k - 1) / k
        h0 = (rh + k - 1) / k
        val levels = levels(w0, h0, a.levelWidth, a.levelHeight, a.levelOffset)
        work.add(levels)
        val total = a.levelOffset[levels - 1] + a.levelWidth[levels - 1] * a.levelHeight[levels - 1]
        val lr = a.levelR
        val lg = a.levelG
        val lb = a.levelB
        val lw = a.levelW
        for (j in 0 until total) {
            work.add(1)
            lr[j] = 0f
            lg[j] = 0f
            lb[j] = 0f
            lw[j] = 0f
        }
        val step = max(1, k / 3) // ≥ 9 samples per k×k block
        var y = 0
        while (y < rh) {
            val py = readTop + y
            val brow = (y / k) * w0
            val dy = (py + 0.5f - cy) / ry
            var x0 = 0
            while (x0 < rw) {
                val len = min(MaskStageLimits.MAX_RUN, rw - x0)
                val row = c.readRun(readLeft + x0, py, len)
                var x = (x0 + step - 1) / step * step
                while (x < x0 + len) {
                    work.add(1)
                    val px = readLeft + x
                    val dx = (px + 0.5f - cx) / rx
                    val r = if (ellipse) sqrt(dx * dx + dy * dy) else max(abs(dx), abs(dy))
                    val p = row[x - x0]
                    // Pixels with alpha 0 are not sampled: their colour is hidden in the photo.
                    if (r > bandOuter && p ushr 24 != 0 && !c.covered(px, py)) {
                        val bi = brow + x / k
                        lr[bi] += ((p shr 16) and 0xFF).toFloat()
                        lg[bi] += ((p shr 8) and 0xFF).toFloat()
                        lb[bi] += (p and 0xFF).toFloat()
                        lw[bi] += 1f
                    }
                    x += step
                }
                x0 += len
            }
            y += step
        }
        for (j in 0 until w0 * h0) {
            work.add(1)
            val cnt = lw[j]
            if (cnt > 0f) {
                val inv = 1f / cnt
                lr[j] *= inv
                lg[j] *= inv
                lb[j] *= inv
            }
        }
        // Pull.
        for (l in 1 until levels) pull(l - 1, l)
        if (lw[a.levelOffset[levels - 1]] <= 0f) {
            flat = true
            flatColour = c.config.color or (0xFF shl 24)
            return
        }
        flat = false
        // Push + relax.
        for (l in levels - 2 downTo 0) push(l, l + 1, if (l == 0 && k == 1) 3 else 5)
    }

    /** The fill colour of pixel ([x], [y]) of the write rectangle (opaque ARGB). */
    fun value(x: Int, y: Int): Int {
        if (flat) return flatColour
        c.work.add(1)
        val a = c.area
        val fy = ((y - readTop) + 0.5f) * invK - 0.5f
        val y0 = floor(fy).toInt().coerceIn(0, h0 - 1)
        val y1 = min(y0 + 1, h0 - 1)
        val ty = (fy - y0).coerceIn(0f, 1f)
        val fx = ((x - readLeft) + 0.5f) * invK - 0.5f
        val x0 = floor(fx).toInt().coerceIn(0, w0 - 1)
        val x1 = min(x0 + 1, w0 - 1)
        val tx = (fx - x0).coerceIn(0f, 1f)
        val ia = y0 * w0 + x0
        val ib = y0 * w0 + x1
        val ic = y1 * w0 + x0
        val id = y1 * w0 + x1
        val wa = (1f - tx) * (1f - ty)
        val wb = tx * (1f - ty)
        val wc = (1f - tx) * ty
        val wd = tx * ty
        val br = a.levelR
        val bg = a.levelG
        val bb = a.levelB
        val r = br[ia] * wa + br[ib] * wb + br[ic] * wc + br[id] * wd
        val g = bg[ia] * wa + bg[ib] * wb + bg[ic] * wc + bg[id] * wd
        val bl = bb[ia] * wa + bb[ib] * wb + bb[ic] * wc + bb[id] * wd
        return (0xFF shl 24) or (clamp255((r + 0.5f).toInt()) shl 16) or
            (clamp255((g + 0.5f).toInt()) shl 8) or clamp255((bl + 0.5f).toInt())
    }

    private fun pull(fine: Int, coarse: Int) {
        val a = c.area
        val cw = a.levelWidth[fine]
        val ch = a.levelHeight[fine]
        val co = a.levelOffset[fine]
        val pw = a.levelWidth[coarse]
        val ph = a.levelHeight[coarse]
        val po = a.levelOffset[coarse]
        val lr = a.levelR
        val lg = a.levelG
        val lb = a.levelB
        val lw = a.levelW
        for (y in 0 until ph) {
            for (x in 0 until pw) {
                c.work.add(4)
                var sw = 0f
                var sr = 0f
                var sg = 0f
                var sb = 0f
                for (dy in 0..1) {
                    val cy = 2 * y + dy
                    if (cy >= ch) continue
                    for (dx in 0..1) {
                        val cx = 2 * x + dx
                        if (cx >= cw) continue
                        val i = co + cy * cw + cx
                        val w = lw[i]
                        if (w <= 0f) continue
                        sw += w
                        sr += w * lr[i]
                        sg += w * lg[i]
                        sb += w * lb[i]
                    }
                }
                if (sw > 0f) {
                    val i = po + y * pw + x
                    val inv = 1f / sw
                    lr[i] = sr * inv
                    lg[i] = sg * inv
                    lb[i] = sb * inv
                    lw[i] = sw
                }
            }
        }
    }

    private fun push(fineLevel: Int, coarseLevel: Int, iterations: Int) {
        val a = c.area
        val work = c.work
        val w = a.levelWidth[fineLevel]
        val h = a.levelHeight[fineLevel]
        val fo = a.levelOffset[fineLevel]
        val cw = a.levelWidth[coarseLevel]
        val ch = a.levelHeight[coarseLevel]
        val co = a.levelOffset[coarseLevel]
        val r = a.levelR
        val g = a.levelG
        val b = a.levelB
        val wt = a.levelW
        val unknown = a.unknown
        val flags = a.unknownFlags
        var count = 0
        for (i in 0 until w * h) {
            work.add(1)
            if (wt[fo + i] <= 0f) unknown[count++] = i
        }
        if (count == 0) return
        // Initial guess: bilinear upsample of the coarser level.
        for (n in 0 until count) {
            work.add(1)
            val i = unknown[n]
            val x = i % w
            val y = i / w
            val fx = (x + 0.5f) * 0.5f - 0.5f
            val fy = (y + 0.5f) * 0.5f - 0.5f
            val x0 = floor(fx).toInt().coerceIn(0, cw - 1)
            val y0 = floor(fy).toInt().coerceIn(0, ch - 1)
            val x1 = min(x0 + 1, cw - 1)
            val y1 = min(y0 + 1, ch - 1)
            val tx = (fx - x0).coerceIn(0f, 1f)
            val ty = (fy - y0).coerceIn(0f, 1f)
            val ia = co + y0 * cw + x0
            val ib = co + y0 * cw + x1
            val ic = co + y1 * cw + x0
            val id = co + y1 * cw + x1
            val wa = (1f - tx) * (1f - ty)
            val wb = tx * (1f - ty)
            val wc = (1f - tx) * ty
            val wd = tx * ty
            r[fo + i] = r[ia] * wa + r[ib] * wb + r[ic] * wc + r[id] * wd
            g[fo + i] = g[ia] * wa + g[ib] * wb + g[ic] * wc + g[id] * wd
            b[fo + i] = b[ia] * wa + b[ib] * wb + b[ic] * wc + b[id] * wd
        }
        // Gauss-Seidel Laplace relaxation on the unknown cells (alternating sweep direction).
        for (n in 0 until count) {
            work.add(1)
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
        for (it in 0 until iterations) {
            val forward = it % 2 == 0
            for (k2 in 0 until count) {
                work.add(1)
                val n = if (forward) k2 else count - 1 - k2
                val i = fo + unknown[n]
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
                var cnt = 0
                if (f and 1 != 0) {
                    sr += r[i - 1]
                    sg += g[i - 1]
                    sb += b[i - 1]
                    cnt++
                }
                if (f and 2 != 0) {
                    sr += r[i + 1]
                    sg += g[i + 1]
                    sb += b[i + 1]
                    cnt++
                }
                if (f and 4 != 0) {
                    sr += r[i - w]
                    sg += g[i - w]
                    sb += b[i - w]
                    cnt++
                }
                if (f and 8 != 0) {
                    sr += r[i + w]
                    sg += g[i + w]
                    sb += b[i + w]
                    cnt++
                }
                if (cnt > 0) {
                    val inv = 1f / cnt
                    r[i] = sr * inv
                    g[i] = sg * inv
                    b[i] = sb * inv
                }
            }
        }
    }

    companion object {
        /** The usual grid spacing (≈ 48 cells across the smaller radius), raised until the base grid fits the cap. */
        fun spacing(rx: Float, ry: Float, rw: Int, rh: Int, work: StageWork? = null): Int {
            val usual = max(1, floor(min(rx, ry) / 48f).toInt()).coerceAtMost(max(1, max(rw, rh)))
            if (cells(rw, rh, usual) <= MaskStageLimits.MAX_SMOOTH_FILL_CELLS) return usual
            // The cell count does not grow with the spacing, and at max(rw, rh) it is 1: binary search.
            var lo = usual
            var hi = max(1, max(rw, rh))
            while (hi - lo > 1) {
                work?.add(1)
                val mid = lo + (hi - lo) / 2
                if (cells(rw, rh, mid) <= MaskStageLimits.MAX_SMOOTH_FILL_CELLS) hi = mid else lo = mid
            }
            return hi
        }

        /** Base cells of a [rw] × [rh] read rectangle at spacing [k]. */
        fun cells(rw: Int, rh: Int, k: Int): Long = ((rw + k - 1L) / k) * ((rh + k - 1L) / k)

        /**
         * The pyramid of a [w] × [h] base grid: each level halves the sides, rounding up, until 1 × 1. Fills the
         * level sizes and offsets (in cells) and returns the number of levels.
         */
        fun levels(w: Int, h: Int, width: IntArray, height: IntArray, offset: IntArray): Int {
            var lw = w
            var lh = h
            var off = 0
            var n = 0
            while (true) {
                width[n] = lw
                height[n] = lh
                offset[n] = off
                off += lw * lh
                n++
                if (lw <= 1 && lh <= 1) return n
                lw = (lw + 1) / 2
                lh = (lh + 1) / 2
            }
        }

        /** The cells of all levels of a [w] × [h] base grid (no arrays). */
        fun pyramidCells(w: Int, h: Int, work: StageWork? = null): Long {
            var lw = w.toLong()
            var lh = h.toLong()
            var total = 0L
            while (true) {
                work?.add(1)
                total += lw * lh
                if (lw <= 1L && lh <= 1L) return total
                lw = (lw + 1) / 2
                lh = (lh + 1) / 2
            }
        }
    }
}
