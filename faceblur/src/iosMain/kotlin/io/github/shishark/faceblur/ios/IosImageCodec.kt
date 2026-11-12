// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.ImageCodec
import io.github.shishark.faceblur.filemode.ImageFormat
import io.github.shishark.faceblur.filemode.OpenedImage
import io.github.shishark.faceblur.filemode.PhotoMetadata
import io.github.shishark.faceblur.filemode.Size
import io.github.shishark.faceblur.filemode.WorkingImage
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.mask.ReservedBytes
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Accelerate.kvImageHighQualityResampling
import platform.Accelerate.kvImageNoError
import platform.Accelerate.vImageScale_ARGB8888
import platform.Accelerate.vImage_Buffer
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.CoreGraphics.kCGInterpolationHigh
import platform.ImageIO.CGImageSourceCopyAuxiliaryDataInfoAtIndex
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateThumbnailAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceCreateWithURL
import platform.ImageIO.CGImageSourceGetCount
import platform.ImageIO.CGImageSourceGetPrimaryImageIndex
import platform.ImageIO.CGImageSourceGetStatus
import platform.ImageIO.CGImageSourceGetStatusAtIndex
import platform.ImageIO.CGImageSourceGetType
import platform.ImageIO.CGImageSourceRef
import platform.ImageIO.kCGImageAuxiliaryDataTypeHDRGainMap
import platform.ImageIO.kCGImageAuxiliaryDataTypeISOGainMap
import platform.ImageIO.kCGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyPixelHeight
import platform.ImageIO.kCGImagePropertyPixelWidth
import platform.ImageIO.kCGImageSourceCreateThumbnailFromImageAlways
import platform.ImageIO.kCGImageSourceCreateThumbnailWithTransform
import platform.ImageIO.kCGImageSourceShouldCache
import platform.ImageIO.kCGImageSourceShouldCacheImmediately
import platform.ImageIO.kCGImageSourceThumbnailMaxPixelSize
import platform.ImageIO.kCGImageStatusComplete
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.fstat
import platform.posix.stat

/**
 * The iOS decoder and encoder of file mode (ADR 0015 decision 4; ADR 0005 decision 1 steps 1-3, 5, 6) on ImageIO,
 * CoreGraphics and vImage. Stateless; every call makes its own Core Foundation objects and releases them.
 * - A path source must name a regular file; its first bytes are read through the read-only model-file reader
 *   ([IosModelFileReader]: `O_RDONLY | O_NOFOLLOW | O_CLOEXEC`, so a symbolic link as the last path component is
 *   refused) for the format check ([ImageFormat.sniff]), and the device and inode of that descriptor are recorded
 *   for the writer ([IosSourceIdentities]); ImageIO then reads the file by its URL, read-only, and after decoding
 *   the path must still name the same file (`IoFailed` otherwise). A stream
 *   source is decoded from a copy of its bytes in a `CFData`, released with the opened image.
 * - The format from the first bytes must agree with ImageIO's own type of the source (JPEG, HEIC/HEIF, PNG), so
 *   anything else is refused as `UnsupportedImage`; only the primary image is used, and a source ImageIO does not
 *   read completely (truncated) is refused before decoding.
 * - [OpenedImage.size] has the EXIF/HEIF orientation applied; [OpenedImage.decode] decodes once through the ImageIO
 *   thumbnail API ("from image always", "with transform", the working long side as the maximum pixel size), so the
 *   orientation is baked in and a large source is reduced inside the decoder; the image is drawn onto opaque white
 *   in a library-owned 8-bit sRGB buffer of exactly the working size and released right after drawing.
 * - Auxiliary images (depth, disparity, mattes, gain maps) and metadata are never decoded, drawn or encoded: the
 *   encoder gets a CGImage made from the masked pixels only ([WorkingImage.encodeJpeg]) and only the quality
 *   property, and the common rebuild (`JpegOutput.finish`) writes the output's segments.
 * Errors are `FaceMaskException`s with fixed text and nothing attached.
 */
internal val IosImageCodec: ImageCodec = ImageCodec { source, _ ->
    when (source) {
        is FileSource.Path -> IosOpenedImage.ofPath(source)
        is FileSource.Bytes -> IosOpenedImage.ofBytes(source.bytes)
        is FileSource.Platform -> throw FaceMaskException.UnsupportedImage("the source was not made by the iOS forms")
    }
}

/** An opened ImageIO source: header read, nothing decoded. One use, one thread. */
internal class IosOpenedImage private constructor(
    override val format: ImageFormat,
    private var imageSource: CGImageSourceRef?,
    private val index: ULong,
    override val size: Size,
    private val path: String?,
    private val identity: IosFileIdentity?,
) : OpenedImage {
    override val hasGainMap: Boolean by lazy {
        val src = live()
        val types = listOf(kCGImageAuxiliaryDataTypeHDRGainMap, kCGImageAuxiliaryDataTypeISOGainMap)
        types.any { type ->
            val info = CGImageSourceCopyAuxiliaryDataInfoAtIndex(src, index, type)
            if (info != null) CFRelease(info)
            info != null
        }
    }

    /** Other metadata policies than `StripAll` are not available on iOS yet; never a silent drop. */
    override val metadata: PhotoMetadata?
        get() = throw FaceMaskException.InvalidConfiguration(
            "metadata",
            "StripAll (other policies are not available on iOS yet)",
        )

    private fun live(): CGImageSourceRef =
        imageSource ?: throw FaceMaskException.Internal("the source was used after it was closed")

    override fun decode(working: Size): WorkingImage {
        val src = live()
        val longSide = maxOf(working.width, working.height)
        val options = IosCf.dictionary(
            kCGImageSourceCreateThumbnailFromImageAlways to IosCf.TRUE,
            kCGImageSourceCreateThumbnailWithTransform to IosCf.TRUE,
            kCGImageSourceShouldCacheImmediately to IosCf.TRUE,
            kCGImageSourceThumbnailMaxPixelSize to IosCf.number(longSide),
        )
        val image =
            try {
                CGImageSourceCreateThumbnailAtIndex(src, index, options)
            } finally {
                CFRelease(options)
            }
        image ?: throw undecodable()
        val decoded =
            try {
                IosWorkingImage.drawn(image, working)
            } finally {
                CGImageRelease(image)
            }
        // ImageIO read the file by its path: it must still be the file whose first bytes were checked and whose
        // identity the writer uses for OverwriteSource (ADR 0005 decision 2).
        if (path != null && identityOfPath(path) != identity) {
            decoded.close()
            throw FaceMaskException.IoFailed("the source changed while it was read")
        }
        return decoded
    }

    override fun close() {
        val src = imageSource
        imageSource = null
        if (src != null) CFRelease(src)
    }

    internal companion object {
        /** ImageIO's types of the accepted formats. */
        private val TYPES: Map<ImageFormat, Set<String>> = mapOf(
            ImageFormat.JPEG to setOf("public.jpeg"),
            ImageFormat.PNG to setOf("public.png"),
            ImageFormat.HEIC to setOf("public.heic", "public.heif"),
        )

        fun ofPath(source: FileSource.Path): OpenedImage {
            val path = source.path
            requireRegularFile(path)
            var identity: IosFileIdentity? = null
            val head =
                try {
                    IosModelFileReader.read(path, ImageFormat.SNIFF_BYTES - 1L) { fd -> identity = identityOfFd(fd) }
                } catch (e: FaceMaskException.ModelRejected) {
                    throw notRegular()
                } catch (e: FaceMaskException) {
                    throw unreadable()
                }
            val format = ImageFormat.sniff(head)
            val url = IosCf.fileUrl(path) ?: throw unreadable()
            val options = IosCf.dictionary(kCGImageSourceShouldCache to IosCf.FALSE)
            val src =
                try {
                    CGImageSourceCreateWithURL(url, options)
                } finally {
                    CFRelease(options)
                    CFRelease(url)
                }
            val known = identity ?: throw unreadable()
            val opened = opened(format, src ?: throw undecodable(), path, known)
            IosSourceIdentities.record(source, known)
            return opened
        }

        fun ofBytes(bytes: ByteArray): OpenedImage {
            val format = ImageFormat.sniff(bytes.copyOf(minOf(bytes.size, ImageFormat.SNIFF_BYTES)))
            val data = bytes.usePinned { p ->
                CFDataCreate(null, p.addressOf(0).reinterpret(), bytes.size.convert())
            } ?: throw FaceMaskException.Internal("the source buffer could not be wrapped")
            val options = IosCf.dictionary(kCGImageSourceShouldCache to IosCf.FALSE)
            val src =
                try {
                    CGImageSourceCreateWithData(data, options)
                } finally {
                    CFRelease(options)
                    CFRelease(data)
                }
            return opened(format, src ?: throw undecodable(), null, null)
        }

        /** Checks [src] against [format] and reads the oriented header size; releases [src] on failure. */
        private fun opened(
            format: ImageFormat,
            src: CGImageSourceRef,
            path: String?,
            identity: IosFileIdentity?,
        ): OpenedImage {
            try {
                val type = IosCf.string(CGImageSourceGetType(src))
                if (type == null || type !in TYPES.getValue(format)) {
                    throw FaceMaskException.UnsupportedImage("only JPEG, HEIC and PNG sources are supported")
                }
                if (CGImageSourceGetStatus(src) != kCGImageStatusComplete || CGImageSourceGetCount(src) < 1uL) {
                    throw undecodable()
                }
                val index = CGImageSourceGetPrimaryImageIndex(src)
                if (CGImageSourceGetStatusAtIndex(src, index) != kCGImageStatusComplete) throw undecodable()
                val props = CGImageSourceCopyPropertiesAtIndex(src, index, null) ?: throw undecodable()
                val size =
                    try {
                        val w = IosCf.long(props, kCGImagePropertyPixelWidth) ?: throw undecodable()
                        val h = IosCf.long(props, kCGImagePropertyPixelHeight) ?: throw undecodable()
                        val orientation = IosCf.long(props, kCGImagePropertyOrientation) ?: 1L
                        if (w < 1 || h < 1 || w > Int.MAX_VALUE || h > Int.MAX_VALUE) throw undecodable()
                        if (orientation in 5L..8L) Size(h.toInt(), w.toInt()) else Size(w.toInt(), h.toInt())
                    } finally {
                        CFRelease(props)
                    }
                return IosOpenedImage(format, src, index, size, path, identity)
            } catch (t: Throwable) {
                CFRelease(src)
                throw t
            }
        }

        /** `stat` (following a link) before anything is opened: a FIFO or device would block or misbehave. */
        private fun requireRegularFile(path: String) {
            val regular = memScoped {
                val st = alloc<stat>()
                if (stat(path, st.ptr) != 0) throw unreadable()
                (st.st_mode.toInt() and S_IFMT) == S_IFREG
            }
            if (!regular) throw notRegular()
        }

        /** The device and inode of the open descriptor [fd] (`fstat`). */
        private fun identityOfFd(fd: Int): IosFileIdentity = memScoped {
            val st = alloc<stat>()
            if (fstat(fd, st.ptr) != 0) throw unreadable()
            IosFileIdentity(st.st_dev.toLong(), st.st_ino.toLong())
        }

        /** The device and inode of the file at [path] now (`stat`), or `null` when it cannot be read. */
        fun identityOfPath(path: String): IosFileIdentity? = memScoped {
            val st = alloc<stat>()
            if (stat(path, st.ptr) != 0) null else IosFileIdentity(st.st_dev.toLong(), st.st_ino.toLong())
        }

        private fun notRegular() = FaceMaskException.UnsupportedImage("the source is not a regular file")

        private fun unreadable() = FaceMaskException.IoFailed("the source file could not be read")

        fun undecodable() = FaceMaskException.UnsupportedImage("the image could not be decoded")
    }
}

/**
 * A decoded working (or scaled) image in a library-owned buffer: 8-bit sRGB, bytes R, G, B, X (opaque), rows without
 * padding. The masks are written into it through the same adapter as in-memory mode ([IosBufferPixels]); it is
 * scaled with vImage (Lanczos) and encoded from a no-copy CGImage view. Freed by [close].
 */
internal class IosWorkingImage private constructor(
    allocated: CPointer<ByteVar>,
    private val width: Int,
    private val height: Int,
) : WorkingImage {
    private val bytesPerRow = width * 4L
    private var buffer: CPointer<ByteVar>? = allocated

    override val pixels: MutablePixelImage =
        IosBufferPixels(allocated, width, height, bytesPerRow, IosPixelLayout.RGBA_NONE_SKIP)

    private fun live(): CPointer<ByteVar> =
        buffer ?: throw FaceMaskException.Internal("the image was used after it was closed")

    override fun scaledTo(output: Size): WorkingImage {
        val src = live()
        val out = blank(output)
        try {
            val status = memScoped {
                val from = alloc<vImage_Buffer>()
                from.data = src
                from.width = width.convert()
                from.height = height.convert()
                from.rowBytes = bytesPerRow.convert()
                val to = alloc<vImage_Buffer>()
                to.data = out.live()
                to.width = output.width.convert()
                to.height = output.height.convert()
                to.rowBytes = (output.width * 4L).convert()
                vImageScale_ARGB8888(from.ptr, to.ptr, null, kvImageHighQualityResampling.convert())
            }
            if (status != kvImageNoError.toLong()) throw FaceMaskException.Internal("the scaler failed")
            return out
        } catch (t: Throwable) {
            out.close()
            throw t
        }
    }

    override fun encodeJpeg(quality: Int): ByteArray =
        IosJpeg.encodePixels(live(), width, height, bytesPerRow, bgra = false, quality = quality)

    override fun close() {
        val b = buffer
        buffer = null
        if (b != null) nativeHeap.free(b.rawValue)
    }

    internal companion object {
        /**
         * A new opaque white buffer of [size]; its bytes are admitted under the memory probe first (ADR 0005
         * decision 1: the decode peak is checked before the allocation, `NotEnoughMemory`).
         */
        fun blank(size: Size): IosWorkingImage {
            val bytes = size.width.toLong() * size.height * 4L
            val probe = FaceBlurRuntime.memoryProbe()
            ReservedBytes.admit(bytes, probe)
            try {
                val buffer = nativeHeap.allocArray<ByteVar>(bytes)
                val image = IosWorkingImage(buffer, size.width, size.height)
                try {
                    image.fillWhite()
                } catch (t: Throwable) {
                    image.close()
                    throw t
                }
                return image
            } finally {
                ReservedBytes.release(bytes)
            }
        }

        /** [image] drawn onto opaque white at exactly [size] (transparency composited, colour converted to sRGB). */
        fun drawn(image: CGImageRef, size: Size): IosWorkingImage {
            val out = blank(size)
            try {
                out.draw(image)
                return out
            } catch (t: Throwable) {
                out.close()
                throw t
            }
        }
    }

    private fun fillWhite() = withContext { ctx ->
        CGContextSetRGBFillColor(ctx, 1.0, 1.0, 1.0, 1.0)
        CGContextFillRect(ctx, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
    }

    private fun draw(image: CGImageRef) = withContext { ctx ->
        CGContextSetInterpolationQuality(ctx, kCGInterpolationHigh)
        CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
    }

    /** A bitmap context over the buffer for one drawing step; released afterwards (never turned into an image). */
    private inline fun withContext(block: (platform.CoreGraphics.CGContextRef) -> Unit) {
        val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
        val ctx = CGBitmapContextCreate(
            live(),
            width.convert(),
            height.convert(),
            8u,
            bytesPerRow.convert(),
            space,
            CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
        )
        CGColorSpaceRelease(space)
        ctx ?: throw FaceMaskException.Internal("the drawing context could not be created")
        try {
            block(ctx)
        } finally {
            CGContextRelease(ctx)
        }
    }
}
