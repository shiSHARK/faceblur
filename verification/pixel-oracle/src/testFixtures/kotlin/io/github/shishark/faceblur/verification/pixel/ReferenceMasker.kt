// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.pixel

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Defects planted into [ReferenceMasker]; each one is the story's ST-045 AC2 list entry of the same name. */
public enum class PlantedPixelDefect {
    NONE,
    BLUR_INSTEAD_OF_MASK,
    ALPHA_254_AT_THE_EDGE,
    OPAQUE_ONE_ROW_LARGER,
    OPAQUE_ONE_ROW_SMALLER,
    ONE_PIXEL_CHANGED_OUTSIDE,
    DEPENDS_ON_INPUT_INSIDE,
    SKIPPED_MASK_ENTRY,
    SKIPPED_EVERY_MASK,
    SOFT_EDGE_OPACITY_RISES_OUTWARD,
    NO_SOFT_EDGE,
    ALPHA_LOWERED_IN_SOFT_EDGE,

    /** The face colours stay and only the alpha is forced to 255 (an alpha-only check passes it). */
    FACE_KEPT_ALPHA_255,

    /** The face is mixed 50 % with grey and made opaque (an alpha-only check passes it). */
    FACE_MIXED_WITH_GREY,
}

/**
 * A small reference masker written for the self-test only (it is NOT the library): opaque colour field inside r <= 1
 * that depends on the position alone, outward blend over the input in the soft edge, input untouched beyond it. With a
 * [PlantedPixelDefect] it misbehaves in exactly one way. It uses the oracle's own [MaskEllipse], so a geometry bug
 * would be invisible to the self-test; the geometry is pinned separately by `GeometryAndComparisonTest`.
 */
public object ReferenceMasker {
    /** The expected colour field of the clean masker: a position-only gradient, alpha 0xFF. */
    public fun colourField(x: Int, y: Int): Int =
        (0xFF shl 24) or (((x * 5 + y) and 0xFF) shl 16) or (((x + y * 3) and 0xFF) shl 8) or
            ((x * 2 + y * 7 + 40) and 0xFF)

    public fun apply(
        input: Raster,
        masks: List<MaskEllipse>,
        defect: PlantedPixelDefect = PlantedPixelDefect.NONE,
        fill: (Int, Int) -> Int = ::colourField,
    ): Raster {
        val out = input.copy()
        val applied = when (defect) {
            PlantedPixelDefect.SKIPPED_MASK_ENTRY -> masks.filterIndexed { i, _ -> i != 1 }
            PlantedPixelDefect.SKIPPED_EVERY_MASK -> emptyList()
            else -> masks
        }
        val insideMean = if (defect == PlantedPixelDefect.DEPENDS_ON_INPUT_INSIDE) meanInside(input, applied) else 0
        for (m in applied) {
            val grow = when (defect) {
                PlantedPixelDefect.OPAQUE_ONE_ROW_LARGER -> 1.0
                PlantedPixelDefect.OPAQUE_ONE_ROW_SMALLER -> -1.0
                else -> 0.0
            }
            val limit = 1.0 + grow / m.smallerSemiAxis
            for (y in 0 until input.height) {
                for (x in 0 until input.width) {
                    val r = m.radius(x, y)
                    val i = y * input.width + x
                    when {
                        r <= limit -> out.argb[i] = insidePixel(input, x, y, r, defect, insideMean, fill)

                        // The rim that the SMALLER defect leaves unmasked stays as it was.
                        r <= 1.0 -> Unit

                        r <= 1.0 + m.softEdgeWidth && defect != PlantedPixelDefect.BLUR_INSTEAD_OF_MASK &&
                            defect != PlantedPixelDefect.NO_SOFT_EDGE -> {
                            val t = ((r - 1.0) / m.softEdgeWidth).coerceIn(0.0, 1.0)
                            val weight = if (defect ==
                                PlantedPixelDefect.SOFT_EDGE_OPACITY_RISES_OUTWARD
                            ) {
                                t
                            } else {
                                1.0 - t * t * (3 - 2 * t)
                            }
                            out.argb[i] = lerp(out.argb[i], fill(x, y), weight)
                            if (defect == PlantedPixelDefect.ALPHA_LOWERED_IN_SOFT_EDGE) {
                                val lowered = max(0, (input[x, y] ushr 24) - 40)
                                out.argb[i] = (lowered shl 24) or (out.argb[i] and 0x00FFFFFF)
                            }
                        }
                    }
                }
            }
        }
        if (defect == PlantedPixelDefect.ONE_PIXEL_CHANGED_OUTSIDE) {
            val i = (input.height - 1) * input.width + (input.width - 1)
            out.argb[i] = out.argb[i] xor 0x00010101
        }
        return out
    }

    private fun insidePixel(
        input: Raster,
        x: Int,
        y: Int,
        r: Double,
        defect: PlantedPixelDefect,
        mean: Int,
        fill: (Int, Int) -> Int,
    ): Int = when (defect) {
        PlantedPixelDefect.BLUR_INSTEAD_OF_MASK -> boxBlur(input, x, y, 2)

        PlantedPixelDefect.ALPHA_254_AT_THE_EDGE -> {
            if (r >= 0.95) (fill(x, y) and 0x00FFFFFF) or (0xFE shl 24) else fill(x, y)
        }

        PlantedPixelDefect.DEPENDS_ON_INPUT_INSIDE -> fill(x, y) xor (mean and 0x00FFFFFF)

        PlantedPixelDefect.FACE_KEPT_ALPHA_255 -> (0xFF shl 24) or (input[x, y] and 0x00FFFFFF)

        PlantedPixelDefect.FACE_MIXED_WITH_GREY -> lerp(input[x, y], 0xFF808080.toInt(), 0.5) or (0xFF shl 24)

        else -> fill(x, y)
    }

    private fun meanInside(input: Raster, masks: List<MaskEllipse>): Int {
        var sum = 0L
        var n = 0
        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                if (masks.any { it.radius(x, y) <= 1.0 }) {
                    sum += input[x, y] and 0x00FFFFFF
                    n++
                }
            }
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    private fun boxBlur(input: Raster, x: Int, y: Int, radius: Int): Int {
        val sums = IntArray(4)
        var n = 0
        for (yy in max(0, y - radius)..min(input.height - 1, y + radius)) {
            for (xx in max(0, x - radius)..min(input.width - 1, x + radius)) {
                val p = input[xx, yy]
                for (c in 0 until 4) sums[c] += (p ushr (24 - 8 * c)) and 0xFF
                n++
            }
        }
        return (0 until 4).fold(0) { acc, c -> acc or ((sums[c] / n) shl (24 - 8 * c)) }
    }

    private fun lerp(from: Int, to: Int, w: Double): Int {
        var result = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            result = result or ((a + (b - a) * w).roundToInt().coerceIn(0, 255) shl shift)
        }
        return result
    }
}

/** Generators and codecs for the self-test; all deterministic. */
public object PixelFixtures {
    /** Noise canvas with varied alpha including 0; [constantAlpha] gives a canvas with one alpha everywhere. */
    public fun noiseCanvas(width: Int, height: Int, seed: Long, constantAlpha: Int? = null): Raster {
        val rnd = Random(seed)
        val alphas = intArrayOf(0, 17, 128, 200, 255)
        return Raster.of(width, height) { _, _ ->
            val alpha = constantAlpha ?: alphas[rnd.nextInt(alphas.size)]
            (alpha shl 24) or (rnd.nextInt(0x1000000))
        }
    }

    /** The same canvas with every pixel whose centre has r <= 1 - margin of some mask replaced by new noise. */
    public fun perturbInside(
        input: Raster,
        masks: List<MaskEllipse>,
        seed: Long,
        margin: Double = MaskEllipse.BOUNDARY_EPSILON,
        opaque: Boolean = false,
    ): Raster {
        val rnd = Random(seed)
        val out = input.copy()
        for (y in 0 until input.height) {
            for (x in 0 until input.width) {
                if (masks.any { it.radius(x, y) <= 1.0 - margin }) {
                    out.argb[y * input.width + x] =
                        ((if (opaque) 0xFF else rnd.nextInt(256)) shl 24) or rnd.nextInt(0x1000000)
                }
            }
        }
        return out
    }

    /** Replaces only whole [blocks] with new opaque noise (for lossy pairs). */
    public fun perturbBlocks(input: Raster, blocks: List<Block>, seed: Long): Raster {
        val rnd = Random(seed)
        val out = input.copy()
        for (b in blocks) {
            for (y in b.y until b.y + b.size) {
                for (x in b.x until b.x + b.size) {
                    out.argb[y * input.width + x] = (0xFF shl 24) or rnd.nextInt(0x1000000)
                }
            }
        }
        return out
    }

    public fun png(raster: Raster): ByteArray {
        val image = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, raster.width, raster.height, raster.argb, 0, raster.width)
        val out = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", out)) { "no PNG writer" }
        return out.toByteArray()
    }

    /** Baseline JPEG of the RGB part of [raster] (alpha is dropped by the format). */
    public fun jpeg(raster: Raster, quality: Float = 0.9f): ByteArray {
        val image = BufferedImage(raster.width, raster.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, raster.width, raster.height, raster.argb, 0, raster.width)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam
        param.compressionMode = ImageWriteParam.MODE_EXPLICIT
        param.compressionQuality = quality
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
