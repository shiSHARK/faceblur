// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FileOptions

/** Width and height in pixels. */
internal data class Size(val width: Int, val height: Int) {
    val longSide: Int get() = maxOf(width, height)
    val pixels: Long get() = width.toLong() * height
}

/**
 * The sizes of one file-mode call (ADR 0005 decision 1, step 2): [source] with the orientation applied, the
 * [working] image that detection and masking run on, and the [output] that is encoded. Coordinates returned by file
 * mode refer to [working].
 */
internal data class SizePlan(val source: Size, val working: Size, val output: Size) {
    /** True when the decoder must produce a smaller image than the source. */
    val decodeReduced: Boolean get() = working != source

    /** True when the masked working image is scaled down before encoding. */
    val scaleAfterMasking: Boolean get() = output != working
}

/** Size arithmetic of file mode; computed from the header before any pixel is allocated. */
internal object FileSizing {
    /**
     * Source size with the EXIF [orientation] applied: orientations 5 to 8 swap width and height. Any other value
     * (including invalid ones, which decoders treat as 1) keeps them.
     */
    fun oriented(width: Int, height: Int, orientation: Int): Size =
        if (orientation in 5..8) Size(height, width) else Size(width, height)

    /**
     * [size] reduced so that its long side is [maxLongSide] (0, or a value at or above the long side, keeps it).
     * The short side follows the aspect ratio, rounded half up in exact integer arithmetic, and is at least 1 px.
     */
    fun reduce(size: Size, maxLongSide: Int): Size {
        val long = size.longSide
        if (maxLongSide <= 0 || maxLongSide >= long) return size
        fun scaled(side: Int): Int = maxOf(1L, (side.toLong() * maxLongSide + long / 2) / long).toInt()
        val landscape = size.width >= size.height
        return if (landscape) Size(maxLongSide, scaled(size.height)) else Size(scaled(size.width), maxLongSide)
    }

    /**
     * Plans one call from the header, before anything is decoded. Throws [FaceMaskException.ImageTooLarge] when the
     * source exceeds the fixed 200 MP ceiling, a side of the working image exceeds 65,535 px (reachable with
     * `maxLongSide = 0` or above 65,535; checked for every value), or the working image exceeds
     * [FileOptions.maxDecodedPixels]; it never reduces the working image below the detection floor to fit the limit.
     * Throws [FaceMaskException.UnsupportedImage] for a header without pixels.
     */
    fun plan(headerWidth: Int, headerHeight: Int, orientation: Int, options: FileOptions): SizePlan {
        val empty = headerWidth <= 0 || headerHeight <= 0
        if (empty) throw FaceMaskException.UnsupportedImage("the header declares no pixels")
        val source = oriented(headerWidth, headerHeight, orientation)
        if (source.pixels > FileOptions.SOURCE_PIXEL_CEILING) {
            throw FaceMaskException.ImageTooLarge(
                source.width,
                source.height,
                FileOptions.SOURCE_PIXEL_CEILING,
                "the fixed source ceiling of ${FileOptions.SOURCE_PIXEL_CEILING} pixels",
            )
        }
        val working = reduce(source, options.maxLongSide)
        if (working.longSide > FaceMasker.MAX_SIDE) {
            throw FaceMaskException.ImageTooLarge(
                working.width,
                working.height,
                Int.MAX_VALUE.toLong(), // the same report as FaceMasker's side check
                "${FaceMasker.SIDE_LIMIT} (the working image; set maxLongSide)",
            )
        }
        if (working.pixels > options.maxDecodedPixels) {
            throw FaceMaskException.ImageTooLarge(
                working.width,
                working.height,
                options.maxDecodedPixels.toLong(),
                "maxDecodedPixels = ${options.maxDecodedPixels} pixels (the working size follows maxLongSide and is " +
                    "never reduced to fit)",
            )
        }
        return SizePlan(source, working, reduce(working, options.maxOutputLongSide))
    }
}
