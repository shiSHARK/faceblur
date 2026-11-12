// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.OutputFormat
import io.github.shishark.faceblur.filemode.JpegOutput
import io.github.shishark.faceblur.filemode.Size
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapInfo
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCreateWithData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.kCGImageDestinationLossyCompressionQuality
import platform.posix.memcpy

/**
 * The bytes of a JPEG made by `FaceBlurIos.encodeJpeg`: checked by the library's output check, held in the Kotlin
 * heap (no native or Objective-C memory), so they are released with this object.
 */
public class EncodedJpeg internal constructor(private val bytes: ByteArray) {
    /** The length of the JPEG in bytes. */
    public val byteCount: Int get() = bytes.size

    /** A copy of the JPEG bytes. */
    public fun toByteArray(): ByteArray = bytes.copyOf()

    /**
     * Copies the JPEG bytes in one memory copy to [address], which has room for [capacity] bytes (for example the
     * storage of a Swift `Data` of [byteCount] bytes). Not named `copyTo`: Objective-C reserves the `copy` prefix.
     *
     * @throws FaceMaskException.InvalidConfiguration (`address`) when [address] is 0 or negative, (`capacity`) when
     * [capacity] is below [byteCount]. Nothing is written then.
     */
    @Throws(FaceMaskException::class)
    public fun exportTo(address: Long, capacity: Long): Unit = FaceBlurIos.boundary {
        if (capacity < bytes.size) throw FaceMaskException.InvalidConfiguration("capacity", "at least byteCount")
        val target = if (address > 0L && address <= Long.MAX_VALUE - capacity) address.toCPointer<ByteVar>() else null
        if (target == null) throw FaceMaskException.InvalidConfiguration("address", "a valid, non-null address")
        bytes.usePinned { memcpy(target, it.addressOf(0), bytes.size.convert()) }
    }
}

/**
 * The in-memory JPEG output of `FaceBlurIos.encodeJpeg` (ADR 0011, the JPEG helper): the encoder of file mode on a
 * no-copy image of the caller's pixels, then the common rebuild and output check ([JpegOutput.finish]) with
 * `StripAll`. Nothing is written to disk.
 */
internal object IosJpeg {
    /** JPEG frames store each side in 16 bits. */
    private const val MAX_SIDE = 65_535

    /** The platform encoder: pixels only, no metadata; returns the encoder's file. Replaced in tests only. */
    internal fun interface Encoder {
        fun encode(
            base: CPointer<ByteVar>,
            width: Int,
            height: Int,
            bytesPerRow: Long,
            bgra: Boolean,
            quality: Int,
        ): ByteArray
    }

    fun encode(
        address: Long,
        width: Int,
        height: Int,
        bytesPerRow: Int,
        layout: IosPixelLayout,
        quality: Int,
        encoder: Encoder = Encoder(::encodePixels),
    ): EncodedJpeg {
        val q = OutputFormat.Jpeg(quality).quality
        // The description and alpha rules of wrapRgba, one check pass; the wrapper itself is not used for encoding.
        IosBufferPixels.wrap(address, width, height, bytesPerRow, layout)
        if (width > MAX_SIDE || height > MAX_SIDE) {
            throw FaceMaskException.UnsupportedImage("a JPEG side can have at most 65,535 pixels")
        }
        val base = address.toCPointer<ByteVar>()
            ?: throw FaceMaskException.InvalidConfiguration("address", "a valid, non-null buffer address")
        val bgra = layout == IosPixelLayout.BGRA_PREMULTIPLIED || layout == IosPixelLayout.BGRA_NONE_SKIP
        val encoded = encoder.encode(base, width, height, bytesPerRow.toLong(), bgra, q)
        val finished = JpegOutput.finish(encoded, null, MetadataPolicy.StripAll, Size(width, height))
        return EncodedJpeg(finished.bytes)
    }

    /**
     * ImageIO's JPEG encoder on a no-copy CGImage over the pixels (never `CGBitmapContextCreateImage`), declared
     * opaque (`noneSkip`), with the quality as the only property: no metadata, no profile dictionary, no auxiliary
     * image. The image and the output data are released here.
     */
    fun encodePixels(
        base: CPointer<ByteVar>,
        width: Int,
        height: Int,
        bytesPerRow: Long,
        bgra: Boolean,
        quality: Int,
    ): ByteArray {
        val image = view(base, width, height, bytesPerRow, bgra)
        val out = CFDataCreateMutable(null, 0) ?: run {
            CGImageRelease(image)
            throw encoderFailed()
        }
        try {
            val type = IosCf.cfString("public.jpeg")
            val dest = CGImageDestinationCreateWithData(out, type, 1uL, null)
            CFRelease(type)
            dest ?: throw encoderFailed()
            val props = IosCf.dictionary(kCGImageDestinationLossyCompressionQuality to IosCf.number(quality / 100.0))
            try {
                CGImageDestinationAddImage(dest, image, props)
                if (!CGImageDestinationFinalize(dest)) throw encoderFailed()
            } finally {
                CFRelease(props)
                CFRelease(dest)
            }
            val n = CFDataGetLength(out)
            val p = CFDataGetBytePtr(out) ?: throw encoderFailed()
            if (n <= 0 || n > Int.MAX_VALUE) throw encoderFailed()
            return p.reinterpret<ByteVar>().readBytes(n.toInt())
        } finally {
            CFRelease(out)
            CGImageRelease(image)
        }
    }

    /**
     * The bytes the pixels occupy, exactly the extent `wrapRgba` validates: every row but the last with its padding,
     * the last row only up to its last pixel (a caller's buffer may end there).
     */
    internal fun extent(width: Int, height: Int, bytesPerRow: Long): Long = bytesPerRow * (height - 1) + width * 4L

    /** A CGImage over the pixels without a copy; released by the caller. */
    private fun view(base: CPointer<ByteVar>, width: Int, height: Int, bytesPerRow: Long, bgra: Boolean): CGImageRef {
        val provider = CGDataProviderCreateWithData(null, base, extent(width, height, bytesPerRow).convert(), null)
            ?: throw encoderFailed()
        val info: CGBitmapInfo =
            if (bgra) {
                CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value or kCGBitmapByteOrder32Little
            } else {
                CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value
            }
        val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
        try {
            return CGImageCreate(
                width.convert(),
                height.convert(),
                8u,
                32u,
                bytesPerRow.convert(),
                space,
                info,
                provider,
                null,
                false,
                CGColorRenderingIntent.kCGRenderingIntentDefault,
            ) ?: throw encoderFailed()
        } finally {
            CGColorSpaceRelease(space)
            CGDataProviderRelease(provider)
        }
    }

    fun encoderFailed() = FaceMaskException.Internal("the JPEG encoder failed")
}
