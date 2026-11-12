// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import kotlin.jvm.JvmOverloads

/** Format of the file that file mode writes. JPEG is the only format; the type is sealed so that it can grow. */
public sealed class OutputFormat {
    /**
     * JPEG with the given [quality] (1 to 100, default 90). The file is always rebuilt from pixels.
     *
     * @throws FaceMaskException.InvalidConfiguration when [quality] is outside 1..100.
     */
    public class Jpeg
    @JvmOverloads
    @Throws(FaceMaskException::class)
    constructor(
        /** Encoder quality from 1 to 100. */
        public val quality: Int = 90,
    ) : OutputFormat() {
        init {
            if (quality !in 1..100) throw FaceMaskException.InvalidConfiguration("quality", "1..100")
        }

        /** True when [other] is a [Jpeg] with the same quality. */
        override fun equals(other: Any?): Boolean = other is Jpeg && quality == other.quality

        /** Hash code consistent with [equals]. */
        override fun hashCode(): Int = quality

        /** Debug text. */
        override fun toString(): String = "Jpeg(quality=$quality)"
    }
}

/**
 * Which photo metadata file mode writes. The output is always rebuilt from pixels: the orientation is baked into the
 * pixels and written as 1, and nothing that can carry pixels (thumbnails, MakerNote, XMP, gain maps, depth maps,
 * mattes or other images) is ever written.
 */
public sealed class MetadataPolicy {
    /** The default: no metadata except Orientation = 1. */
    public object StripAll : MetadataPolicy() {
        /** Debug text. */
        override fun toString(): String = "StripAll"
    }

    /**
     * Opt-in: keep the named groups of the source's metadata, re-written as fresh, validated values (never copied as
     * bytes). GPS is kept exactly as the source has it, or not at all. At least one group must be named.
     *
     * Each group keeps personal data in the output and works against data minimisation: masks cover faces, but these
     * fields can still tell where and when a photo was taken and with which device. Name a group only when the user
     * asked to keep it.
     *
     * @throws FaceMaskException.InvalidConfiguration when no group is named.
     */
    public class KeepAllowlist
    @JvmOverloads
    @Throws(FaceMaskException::class)
    constructor(
        /**
         * Keep the GPS position, altitude, time stamp, accuracy and direction (exact source values). The position is
         * the exact place where the photo was taken, often a home or workplace; keep it only when the user asked for
         * it.
         */
        public val gps: Boolean = false,
        /**
         * Keep the original and digitised date and time with their offsets and sub-seconds. They tell when the photo
         * was taken and, through the offset, the time zone; combined with other photos or the location they can
         * identify a person or a routine.
         */
        public val dateTime: Boolean = false,
        /**
         * Keep the camera make and model. They can link photos to one device and, through that, to its owner,
         * especially for rare models.
         */
        public val camera: Boolean = false,
    ) : MetadataPolicy() {
        init {
            if (!gps && !dateTime && !camera) {
                throw FaceMaskException.InvalidConfiguration("metadata", "an allowlist naming at least one group")
            }
        }

        /** True when [other] keeps the same groups. */
        override fun equals(other: Any?): Boolean =
            other is KeepAllowlist && gps == other.gps && dateTime == other.dateTime && camera == other.camera

        /** Hash code consistent with [equals]. */
        override fun hashCode(): Int = (if (gps) 1 else 0) + (if (dateTime) 2 else 0) + (if (camera) 4 else 0)

        /** Debug text. */
        override fun toString(): String = "KeepAllowlist(gps=$gps, dateTime=$dateTime, camera=$camera)"
    }
}

/**
 * Settings of one file-mode call.
 *
 * Detection and masking run on the **working image**: the source with its orientation applied, reduced to a long
 * side of [maxLongSide] when it is larger. The working image is never smaller than 3,500 px on the long side (or the
 * source's size if that is smaller), because detection quality depends on the face size in pixels. A smaller file is
 * made by [maxOutputLongSide], which scales the already masked image down. The working image may have at most
 * [maxDecodedPixels] pixels; a larger one fails with [FaceMaskException.ImageTooLarge] instead of being detected at a
 * smaller size.
 *
 * @throws FaceMaskException.InvalidConfiguration when a value is outside its range (the exception names it).
 */
public class FileOptions
@JvmOverloads
@Throws(FaceMaskException::class)
constructor(
    /** Long side of the working image: 0 (the source size) or at least 3,500 px. Default 3,500; never upscales. */
    public val maxLongSide: Int = DEFAULT_MAX_LONG_SIDE,
    /** Long side of the written file: 0 (the working size, default) or at least 64 px; never upscales. */
    public val maxOutputLongSide: Int = 0,
    /** Largest working image in pixels: 13,000,000 to 100,000,000 (default 25,000,000). */
    public val maxDecodedPixels: Int = DEFAULT_MAX_DECODED_PIXELS,
    /** Output format; default JPEG quality 90. */
    public val format: OutputFormat = OutputFormat.Jpeg(),
    /** Metadata to keep; default [MetadataPolicy.StripAll]. */
    public val metadata: MetadataPolicy = MetadataPolicy.StripAll,
) {
    init {
        if (maxLongSide != 0 && maxLongSide < DETECTION_FLOOR) {
            throw FaceMaskException.InvalidConfiguration("maxLongSide", "0 or at least $DETECTION_FLOOR")
        }
        if (maxOutputLongSide != 0 && maxOutputLongSide < MIN_OUTPUT_LONG_SIDE) {
            throw FaceMaskException.InvalidConfiguration("maxOutputLongSide", "0 or at least $MIN_OUTPUT_LONG_SIDE")
        }
        if (maxDecodedPixels !in MIN_DECODED_PIXELS..MAX_DECODED_PIXELS) {
            throw FaceMaskException.InvalidConfiguration(
                "maxDecodedPixels",
                "$MIN_DECODED_PIXELS..$MAX_DECODED_PIXELS",
            )
        }
    }

    /** True when [other] has the same values. */
    override fun equals(other: Any?): Boolean = other is FileOptions &&
        maxLongSide == other.maxLongSide &&
        maxOutputLongSide == other.maxOutputLongSide &&
        maxDecodedPixels == other.maxDecodedPixels &&
        format == other.format &&
        metadata == other.metadata

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int = listOf(maxLongSide, maxOutputLongSide, maxDecodedPixels, format, metadata).hashCode()

    /** Debug text with every value. */
    override fun toString(): String = "FileOptions(maxLongSide=$maxLongSide, maxOutputLongSide=$maxOutputLongSide, " +
        "maxDecodedPixels=$maxDecodedPixels, format=$format, metadata=$metadata)"

    /** Builder for Java callers; starts from the defaults. */
    public class Builder {
        private var maxLongSide = DEFAULT_MAX_LONG_SIDE
        private var maxOutputLongSide = 0
        private var maxDecodedPixels = DEFAULT_MAX_DECODED_PIXELS
        private var format: OutputFormat = OutputFormat.Jpeg()
        private var metadata: MetadataPolicy = MetadataPolicy.StripAll

        /** Sets [FileOptions.maxLongSide]. */
        public fun maxLongSide(value: Int): Builder = apply { maxLongSide = value }

        /** Sets [FileOptions.maxOutputLongSide]. */
        public fun maxOutputLongSide(value: Int): Builder = apply { maxOutputLongSide = value }

        /** Sets [FileOptions.maxDecodedPixels]. */
        public fun maxDecodedPixels(value: Int): Builder = apply { maxDecodedPixels = value }

        /** Sets [FileOptions.format]. */
        public fun format(value: OutputFormat): Builder = apply { format = value }

        /** Sets [FileOptions.metadata]. */
        public fun metadata(value: MetadataPolicy): Builder = apply { metadata = value }

        /** Builds and validates the options. */
        @Throws(FaceMaskException::class)
        public fun build(): FileOptions =
            FileOptions(maxLongSide, maxOutputLongSide, maxDecodedPixels, format, metadata)
    }

    internal companion object {
        /** File mode never detects on fewer pixels than this on the long side (unless the source is smaller). */
        const val DETECTION_FLOOR = 3_500
        const val DEFAULT_MAX_LONG_SIDE = 3_500
        const val MIN_OUTPUT_LONG_SIDE = 64
        const val DEFAULT_MAX_DECODED_PIXELS = 25_000_000
        const val MIN_DECODED_PIXELS = 13_000_000
        const val MAX_DECODED_PIXELS = 100_000_000

        /** Fixed ceiling for the source header (camera photos reach about 200 MP); not configurable. */
        const val SOURCE_PIXEL_CEILING = 200_000_000L
    }
}
