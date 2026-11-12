// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.ImageCodec
import io.github.shishark.faceblur.filemode.ImageFormat
import io.github.shishark.faceblur.filemode.OpenedImage
import io.github.shishark.faceblur.filemode.PhotoMetadata
import io.github.shishark.faceblur.filemode.Size
import io.github.shishark.faceblur.filemode.WorkingImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.WeakHashMap

/**
 * The Android decoder and encoder of file mode (ADR 0015 decision 4; ADR 0005 decision 1 steps 1-3, 5, 6) on
 * `ImageDecoder` and `Bitmap.compress`:
 * - path sources are opened by [PathSourceRules] (regular files only, non-blocking open, type checked on the
 *   descriptor) and decoded from that descriptor; their device and inode are recorded for `OverwriteSource`
 *   ([SourceIdentities]); stream sources are decoded from the call's buffer; `content:` sources through the call's
 *   `ContentResolver`;
 * - the format comes from the first bytes ([ImageFormat.sniff]); the decoder's own format must agree, so a source
 *   that is not a JPEG, HEIC (HEVC) or PNG image is refused as `UnsupportedImage`;
 * - [OpenedImage.size] is the decoder's header size, which has the orientation applied; [OpenedImage.decode]
 *   decodes once at the working size (reduced in the decoder), software memory, mutable, sRGB, `ARGB_8888`; the
 *   gain map is dropped; a picture with transparency is drawn onto opaque white; another configuration (16-bit, wide
 *   gamut) is redrawn into 8-bit sRGB;
 * - [WorkingImage.pixels] is the live bitmap that [WorkingImage.scaledTo] and [WorkingImage.encodeJpeg] read; the gain
 *   map is cleared on the bitmap that is encoded.
 * Errors are `FaceMaskException`s with fixed text and nothing attached; `close()` drops every reference to the
 * source, its buffer and the decoder objects.
 */
internal class AndroidImageCodec<D> private constructor(
    private val calls: FileCalls<D>,
    private val contentSource: (ContentResolver, Uri) -> ImageDecoder.Source,
) : ImageCodec {
    @HiddenFromJava
    override fun open(source: FileSource, call: CallContext): OpenedImage = when (source) {
        is FileSource.Path -> openPath(source, source.path)

        is FileSource.Bytes -> DecoderImage.open(
            ImageDecoder.createSource(ByteBuffer.wrap(source.bytes)),
            source.bytes,
        ) {
        }

        is FileSource.Platform -> {
            val value = source.value as? ContentSource
                ?: throw FaceMaskException.UnsupportedImage("the source was not made by the Android forms")
            openContent(AndroidSources.resolver(call), value.uri)
        }
    }

    private fun openPath(source: FileSource, path: String): OpenedImage {
        val file = PathSourceRules.open(path, calls)
        try {
            val opened = DecoderImage.open(calls.decoderSource(file.fd), file.head) {
                PathSourceRules.closeQuietly(file.fd, calls)
            }
            SourceIdentities.record(source, SourceIdentity(file.device, file.inode))
            return opened
        } catch (t: Throwable) {
            PathSourceRules.closeQuietly(file.fd, calls)
            throw t
        }
    }

    private fun openContent(resolver: ContentResolver, uri: Uri): OpenedImage {
        val head = ByteArray(ImageFormat.SNIFF_BYTES)
        val n =
            try {
                (resolver.openInputStream(uri) ?: throw IOException()).use { input ->
                    var n = 0
                    while (n < head.size) {
                        val got = input.read(head, n, head.size - n)
                        if (got < 0) break
                        n += got
                    }
                    n
                }
            } catch (e: IOException) {
                throw unreadable()
            } catch (e: SecurityException) {
                throw unreadable()
            } catch (e: IllegalArgumentException) {
                throw unreadable()
            }
        return DecoderImage.open(contentSource(resolver, uri), head.copyOf(n)) {}
    }

    private fun unreadable() = FaceMaskException.IoFailed("the source could not be read")

    companion object {
        /** The production codec on `android.system.Os` descriptors. */
        @get:HiddenFromJava
        val DEFAULT: ImageCodec = AndroidImageCodec(OsDescriptors, ImageDecoder::createSource)

        /**
         * A codec on other [calls] and another decoder source for content URIs (host tests: Robolectric's decoder
         * reads no provider descriptor).
         */
        @HiddenFromJava
        fun <D> using(
            calls: FileCalls<D>,
            contentSource: (ContentResolver, Uri) -> ImageDecoder.Source = ImageDecoder::createSource,
        ): ImageCodec = AndroidImageCodec(calls, contentSource)
    }
}

/**
 * The device and inode of the file each running path call decoded, keyed by the call's own [FileSource] object (one
 * per call, compared by identity) and dropped with it. The writer reads it for `OverwriteSource` (ADR 0005 decision 2).
 */
internal object SourceIdentities {
    private val known = WeakHashMap<FileSource, SourceIdentity>()

    @HiddenFromJava
    fun record(source: FileSource, identity: SourceIdentity) {
        synchronized(known) { known[source] = identity }
    }

    @HiddenFromJava
    fun of(source: FileSource): SourceIdentity? = synchronized(known) { known[source] }
}

/** What the decoder's header pass found: the oriented size and the decoder's MIME type. */
private class Header(val width: Int, val height: Int, val mimeType: String)

/** Ends the header pass inside the decoder's header callback; carries nothing. */
private object HeaderRead : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/** The decoder's header of a later pass differs from the first one: the source changed while it was read. */
private object SourceMoved : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this
}

/** An opened source on `ImageDecoder`: header read, nothing decoded. One use, one thread. */
private class DecoderImage private constructor(
    override val format: ImageFormat,
    private var source: ImageDecoder.Source?,
    private val header: Header,
    private var onClose: (() -> Unit)?,
) : OpenedImage {
    override val size: Size = Size(header.width, header.height)

    override val hasGainMap: Boolean by lazy { probeGainMap() }

    /** Other metadata policies than `StripAll` are not available on Android yet; never a silent drop. */
    override val metadata: PhotoMetadata?
        get() = throw FaceMaskException.InvalidConfiguration(
            "metadata",
            "StripAll (other policies are not available on Android yet)",
        )

    @HiddenFromJava
    override fun decode(working: Size): WorkingImage {
        val bitmap = decodeBitmap(working, null)
        return try {
            AndroidWorkingImage(Bitmaps.opaqueSrgb(bitmap))
        } catch (t: Throwable) {
            bitmap.recycle()
            throw t
        }
    }

    /**
     * Android 14+ only: a JPEG or HEIC can carry an HDR gain map, which the header does not report. A small decode
     * (1/8 per side) tells; the working image's decode drops the map in every case.
     */
    private fun probeGainMap(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (format == ImageFormat.PNG) return false
        var found = false
        decodeBitmap(null, GAIN_MAP_PROBE_SAMPLE) { found = it.hasGainmap() }.recycle()
        return found
    }

    /**
     * Decodes at [working] (or, without it, with [sampleSize]); refuses a header that differs from the one read at
     * open. [inspect] sees the decoded bitmap before its gain map is cleared; every returned bitmap has none.
     */
    private fun decodeBitmap(working: Size?, sampleSize: Int?, inspect: (Bitmap) -> Unit = {}): Bitmap {
        val src = source ?: throw FaceMaskException.Internal("the source was used after it was closed")
        val bitmap =
            try {
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    if (info.size.width != header.width || info.size.height != header.height ||
                        info.mimeType != header.mimeType
                    ) {
                        throw SourceMoved
                    }
                    if (working != null && (working.width != header.width || working.height != header.height)) {
                        decoder.setTargetSize(working.width, working.height)
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
                    decoder.isUnpremultipliedRequired = false
                    decoder.setTargetColorSpace(Bitmaps.SRGB)
                    if (sampleSize != null) decoder.setTargetSampleSize(sampleSize)
                }
            } catch (e: SourceMoved) {
                throw FaceMaskException.IoFailed("the source changed while it was read")
            } catch (e: IOException) {
                throw undecodable()
            } catch (e: IllegalArgumentException) {
                throw undecodable()
            } catch (e: IllegalStateException) {
                throw undecodable()
            } catch (e: SecurityException) {
                throw FaceMaskException.IoFailed("the source could not be read")
            }
        try {
            inspect(bitmap)
        } finally {
            Bitmaps.clearGainmap(bitmap)
        }
        return bitmap
    }

    override fun close() {
        source = null
        val c = onClose
        onClose = null
        c?.invoke()
    }

    companion object {
        /** The sample size of the gain-map probe. */
        private const val GAIN_MAP_PROBE_SAMPLE = 8

        private val MIME_TYPES: Map<ImageFormat, Set<String>> =
            mapOf(
                ImageFormat.JPEG to setOf("image/jpeg"),
                ImageFormat.PNG to setOf("image/png"),
                ImageFormat.HEIC to setOf("image/heif", "image/heic"),
            )

        /**
         * Reads the format from [head] and the header through [source]; [onClose] runs once when the image is closed
         * (and here when opening fails).
         */
        @HiddenFromJava
        fun open(source: ImageDecoder.Source, head: ByteArray, onClose: () -> Unit): OpenedImage {
            try {
                val format = ImageFormat.sniff(head)
                val header = header(source)
                if (header.mimeType !in MIME_TYPES.getValue(format)) {
                    throw FaceMaskException.UnsupportedImage("only JPEG, HEIC and PNG sources are supported")
                }
                return DecoderImage(format, source, header, onClose)
            } catch (t: Throwable) {
                onClose()
                throw t
            }
        }

        /** The header only: the decoder's callback ends the pass before any pixel is allocated. */
        private fun header(source: ImageDecoder.Source): Header {
            var found: Header? = null
            try {
                ImageDecoder.decodeBitmap(source) { _, info, _ ->
                    found = Header(info.size.width, info.size.height, info.mimeType)
                    throw HeaderRead
                }
            } catch (e: HeaderRead) {
                Unit
            } catch (e: IOException) {
                throw undecodable()
            } catch (e: IllegalArgumentException) {
                throw undecodable()
            } catch (e: IllegalStateException) {
                throw undecodable()
            } catch (e: SecurityException) {
                throw FaceMaskException.IoFailed("the source could not be read")
            }
            return found ?: throw undecodable()
        }

        fun undecodable() = FaceMaskException.UnsupportedImage("the image could not be decoded")
    }
}

/** A decoded working (or scaled) image on a mutable `ARGB_8888` sRGB bitmap; closed by its owner. */
internal class AndroidWorkingImage(bitmap: Bitmap) : WorkingImage {
    private var bitmap: Bitmap? = bitmap

    /** The live bitmap: the masks are written into it and it is what is scaled and encoded. */
    override val pixels: MutablePixelImage = BitmapPixels(bitmap)

    private fun live(): Bitmap = bitmap ?: throw FaceMaskException.Internal("the image was used after it was closed")

    @HiddenFromJava
    override fun scaledTo(output: Size): WorkingImage {
        val out = Bitmaps.blank(output.width, output.height)
        Canvas(out).drawBitmap(live(), null, Rect(0, 0, output.width, output.height), Paint(Paint.FILTER_BITMAP_FLAG))
        return AndroidWorkingImage(out)
    }

    @HiddenFromJava
    override fun encodeJpeg(quality: Int): ByteArray = Bitmaps.encodeJpeg(live(), quality)

    override fun close() {
        bitmap?.recycle()
        bitmap = null
    }
}

/** The bitmap rules of file mode, host-tested. */
internal object Bitmaps {
    val SRGB: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

    /** A new, mutable, opaque `ARGB_8888` sRGB bitmap. */
    fun blank(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, false, SRGB)

    /**
     * [bitmap] as a mutable, opaque `ARGB_8888` sRGB bitmap: returned as it is when it already is one; otherwise drawn
     * onto opaque white in a new bitmap (transparency composited, 16-bit and wide-gamut configurations redrawn) and
     * [bitmap] is recycled.
     */
    fun opaqueSrgb(bitmap: Bitmap): Bitmap {
        val cs = bitmap.colorSpace
        val ready = bitmap.config == Bitmap.Config.ARGB_8888 && (cs == null || cs.isSrgb) && bitmap.isMutable &&
            !bitmap.hasAlpha()
        if (ready) return bitmap
        val out = blank(bitmap.width, bitmap.height)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        bitmap.recycle()
        return out
    }

    /** JPEG pixels of [bitmap] at [quality]; the gain map is dropped first (it would be written as an MPF image). */
    @HiddenFromJava
    fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        clearGainmap(bitmap)
        val estimate = (bitmap.width.toLong() * bitmap.height / 5).coerceIn(64L * 1024, 32L * 1024 * 1024).toInt()
        val out = ByteArrayOutputStream(estimate)
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
            throw FaceMaskException.Internal("the JPEG encoder failed")
        }
        return out.toByteArray()
    }

    /** Android 14+: removes the HDR gain map, a second picture of the unmasked scene. */
    fun clearGainmap(bitmap: Bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()) {
            bitmap.gainmap = null
        }
    }
}
