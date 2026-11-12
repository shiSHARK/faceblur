// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlatformTargetValue

// The internal seam between the common file-mode orchestration and the platform (ADR 0015 decision 4). The common
// code runs the steps of ADR 0005 decision 1 in order; platform code only reads, decodes, scales, encodes and writes.
// Implementations never put a path, URI, file name or platform exception text into anything they throw: they throw
// FaceMaskException subclasses with fixed reasons and nothing attached (no cause, no suppressed exception). The
// orchestration enforces the attachment part (FileModeRun.withoutDetails): any other exception, and a
// FaceMaskException with something attached, ends the call as Internal without the original, so a platform part that
// does not map its failures loses its error type. Sources, targets and defaults hold no platform context: the
// per-call platform context ([CallContext]) is passed to every codec and writer call and lives only for that call.

/**
 * The largest source the stream form reads into memory: 64 MiB (67,108,864 bytes), the whole stream including any
 * trailing data. Fixed, not configurable in this version; above it the call fails with
 * `FaceMaskException.SourceTooLarge(MAX_STREAM_SOURCE_BYTES)` before any decoding (ADR 0015 decision 4). The path
 * and platform (`Uri`) forms are not read into a FaceBlur buffer and are not capped by it.
 */
internal const val MAX_STREAM_SOURCE_BYTES: Long = 64L * 1024L * 1024L

/** Where a file-mode call reads its image. `toString()` names the kind only, never a path or URI. */
internal sealed class FileSource {
    /** A file-system path (the common `detectFile` / `maskFile` forms). */
    class Path(val path: String) : FileSource() {
        override fun toString(): String = "FileSource.Path"
    }

    /**
     * Bytes already in memory: the stream form's buffer, read once by [BoundedRead]. Never copied or changed. The
     * orchestration [release]s it as soon as the image is decoded (and the [OpenedImage] closed), before the mask
     * stage reserves its working area, so that one call never holds both (ADR 0015 decision 4). A codec must not keep
     * [bytes] beyond [OpenedImage.close].
     */
    class Bytes(bytes: ByteArray) : FileSource() {
        private var held: ByteArray? = bytes
        private val size = bytes.size

        /** The bytes; after [release] reading them fails closed with `FaceMaskException.Internal`. */
        val bytes: ByteArray
            get() = held ?: throw FaceMaskException.Internal("the stream buffer was read after its release")

        /** True after [release]. */
        val isReleased: Boolean get() = held == null

        /** Drops this source's reference to the buffer; idempotent. */
        fun release() {
            held = null
        }

        override fun toString(): String = "FileSource.Bytes(size=$size)"
    }

    /**
     * A platform source (Android: a content or file URI). It holds the URI only; the means to read it (Android: the
     * `ContentResolver`) arrives with each call through [CallContext].
     */
    class Platform(val value: PlatformSourceValue) : FileSource() {
        override fun toString(): String = "FileSource.Platform(${value.kind})"
    }
}

/**
 * The platform part of a [FileSource.Platform]; Android implements it with the URI only. It never holds a platform
 * context (no `Context`, no `ContentResolver`): that comes with each call through [CallContext].
 */
internal interface PlatformSourceValue {
    /** A short name of the kind of source (for example `content`), for `toString()`; never a path or URI. */
    val kind: String

    /**
     * True when [OutputTarget.OverwriteSource] can replace this source in one step (a file URI). False for a
     * content URI: the orchestration then refuses `OverwriteSource` with `UnsupportedTarget` before decoding
     * (ADR 0005 decision 4).
     */
    val replaceableInOneStep: Boolean
}

/** Opens sources; one per platform (Android `ImageDecoder`, iOS ImageIO). Stateless and thread-safe. */
internal fun interface ImageCodec {
    /**
     * Reads the format from the first bytes of [source] ([ImageFormat.sniff], never from a name or MIME type) and
     * the header, and allocates no pixel buffer. Throws `FaceMaskException.UnsupportedImage` for anything that is
     * not a JPEG, HEIC or PNG the platform can decode, and `FaceMaskException.IoFailed` when the source cannot be
     * read. A platform source is read through [call]'s platform value (Android: its `ContentResolver`); the codec
     * and the result never keep [call] beyond the call that opened it. The caller closes the result.
     */
    fun open(source: FileSource, call: CallContext): OpenedImage
}

/** A source whose header was read; nothing is decoded yet. One use, one thread. */
internal interface OpenedImage : AutoCloseable {
    /** The format found in the first bytes. */
    val format: ImageFormat

    /** The source size with its orientation applied (orientations 5 to 8 swap width and height). */
    val size: Size

    /** True when the source carries an HDR gain map (Android 14+); it is never decoded into or written to output. */
    val hasGainMap: Boolean

    /**
     * The source's photo metadata that `MetadataPolicy.KeepAllowlist` may keep, read as checked values, or `null`
     * when it has none or the platform reads none. The orchestration restricts it to the policy's groups.
     */
    val metadata: PhotoMetadata?

    /**
     * Decodes the image once at [working] (planned with [FileSizing.plan] from [size]): orientation applied, sRGB,
     * opaque, non-premultiplied ARGB_8888, gain map dropped. The result has exactly [working] as its size; the
     * orchestration checks it and fails closed otherwise. Throws `FaceMaskException.UnsupportedImage` when the
     * platform cannot decode the image.
     */
    fun decode(working: Size): WorkingImage
}

/** A decoded working image, owned by the call that decoded it. One thread. */
internal interface WorkingImage : AutoCloseable {
    /** The pixels, through the same platform adapter as in-memory mode; the masks are written through it. */
    val pixels: MutablePixelImage

    /**
     * A new image of [output] made from the (already masked) pixels with a filtering scaler; this image stays
     * valid and is closed by its owner. Called only when [output] is smaller than the working size.
     */
    fun scaledTo(output: Size): WorkingImage

    /**
     * The pixels encoded as JPEG at [quality] (1..100) by the platform encoder, with the gain map cleared before
     * encoding. The bytes are then rebuilt and checked by [JpegOutput.finish] before anything is written.
     */
    fun encodeJpeg(quality: Int): ByteArray
}

/** Writes finished output (ADR 0005 decisions 2 and 4); one per platform. Stateless and thread-safe. */
internal interface TargetWriter {
    /**
     * Refuses, before anything is decoded, a [target] this writer can tell it cannot serve for [source], with
     * `FaceMaskException.UnsupportedTarget` (for example an existing file for a `NewFile` without
     * `replaceExisting`, or a non-empty content URI for `AndroidOutputTargets.newFile`). Creates, opens for writing
     * and changes nothing. [write] checks again: the target can change between the two calls. [call] carries the
     * platform context of this call and is not kept.
     */
    fun check(target: OutputTarget, source: FileSource, call: CallContext)

    /**
     * True when [value] is this platform's own [PlatformTargetValue] type (Android: the values that
     * `AndroidOutputTargets` makes). A pure type check without I/O; the orchestration calls it before the source is
     * opened and refuses any other value with `FaceMaskException.UnsupportedTarget` (a Java app can implement the
     * interface; never a `ClassCastException`).
     */
    fun accepts(value: PlatformTargetValue): Boolean

    /**
     * [path] made absolute against the process's current directory, lexically: no file-system lookup, no symbolic
     * link resolved (Android and the JVM: `File(path).absolutePath`). The orchestration normalises the result and
     * compares it with the source path for its early refusal of `NewFile(source path)` without `replaceExisting`;
     * the authoritative guard stays the writer's no-replace publication (ADR 0005 decision 2).
     */
    fun absolutePath(path: String): String

    /**
     * Writes [bytes] (the finished, validated JPEG) to [target] once. On any failure the target is untouched
     * (atomic forms) or, for the non-atomic opt-in, must be treated as unmasked; no temporary file is left behind.
     * Throws `FaceMaskException.UnsupportedTarget` or `FaceMaskException.IoFailed`. [call] carries the platform
     * context of this call (Android: the `ContentResolver` for a content-URI target) and is not kept.
     */
    fun write(target: OutputTarget, source: FileSource, bytes: ByteArray, call: CallContext)
}

/**
 * The platform context of one file-mode call (ADR 0015 decision 4). The platform's own forms (Android: the forms that
 * take a `Context`) make one per call with a [platform] value; the common path form makes one without, which is how
 * the orchestration recognises a [OutputTarget.PlatformTarget] passed without a platform context. It lives only for
 * the duration of the call and is never stored in a default object, a target, a source, a codec, a writer or the
 * `FaceMasker`. `toString()` names the platform value's kind (or `none`), nothing else.
 */
internal class CallContext(
    /** This call's platform value, or `null` for the common path form. */
    val platform: PlatformCallValue?,
) {
    override fun toString(): String = if (platform == null) "CallContext(none)" else "CallContext(${platform.kind})"
}

/**
 * The platform part of a [CallContext]; Android implements it with the `ContentResolver` taken from the caller's
 * `Context` for this call. A platform codec or writer accepts only its own implementation and fails closed with a
 * `FaceMaskException` for any other value (never a `ClassCastException`).
 */
internal interface PlatformCallValue {
    /** A short name of the platform (for example `android`), for `toString()`; never a path, URI or app detail. */
    val kind: String
}

/**
 * The platform's read function of a stream source (Android: `InputStream.read(buffer, offset, length)` of the
 * caller's stream), wrapped by the platform form so that a read failure becomes `FaceMaskException.IoFailed` with a
 * fixed reason. [BoundedRead] calls it on one thread, never with [length] 0.
 */
internal fun interface StreamReader {
    /**
     * Reads at most [length] bytes into [buffer] from [offset]: returns -1 at the end of the stream, otherwise the
     * number of bytes read, 1 to [length] (the `InputStream` contract). Any other value fails the call closed.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * The output of the stream form (Android: the caller's `OutputStream`). Called at most once, with the finished,
 * validated JPEG, after the last cancellation check; never on any failure or cancellation. Atomicity of the stream
 * is the caller's (ADR 0002 decision 4).
 */
internal fun interface StreamSink {
    fun write(bytes: ByteArray)
}
