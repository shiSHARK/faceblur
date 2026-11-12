// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceBlurInfo
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The ONNX Runtime glue seen from the library (ADR 0010 decision 1). An integration seam for the published glue
 * only: Swift apps get it from the `faceblur-swift` package, KMP apps copy the unmodified template. The glue is
 * in-process code and is trusted (ADR 0010 decision 5).
 *
 * Only primitives cross per pass: buffers are passed as addresses and byte counts and are owned by the library.
 * Status codes: 0 success, 1 session not created, 2 run failed, 3 output missing, of the wrong type or too large,
 * 4 not open, 5 invalid arguments, 6 input or output names differ, 7 a required session option could not be set.
 */
public interface OnnxBridge {
    /**
     * Opens a session from the verified model bytes at [modelAddress] ([modelByteCount] bytes, owned by the library and
     * valid only during this call) and checks that the session's input and output names equal [inputName] and
     * [outputNames]. The glue keeps no reference to the model memory after returning and writes nothing to disk: no
     * copy of the model, no optimised model, no profile. Returns 0 or a status code.
     */
    public fun open(
        modelAddress: Long,
        modelByteCount: Long,
        threads: Int,
        inputName: String,
        inputShape: LongArray,
        outputNames: List<String>,
    ): Int

    /**
     * Runs one pass. [inputAddress] holds [inputByteCount] bytes of float32 values in the shape given at [open];
     * [outputTableAddress] is the output table (one row of [OUTPUT_ROW_LONGS] little-endian Int64 values per output
     * name given at [open]). Returns 0 or a status code.
     */
    public fun run(inputAddress: Long, inputByteCount: Long, outputTableAddress: Long): Int

    /** The linked runtime's version, read from the runtime. */
    public fun runtimeVersion(): String

    /** "XNNPACK" or "CPU". */
    public fun executionProvider(): String

    public fun close()

    public companion object {
        /**
         * Int64 values per output-table row: buffer address, capacity in bytes, bytes written, ONNX element type,
         * rank, four dimensions.
         */
        public const val OUTPUT_ROW_LONGS: Int = 9
    }
}

/** Creates one [OnnxBridge] per `FaceMasker`. */
public fun interface OnnxBridgeFactory {
    public fun create(): OnnxBridge
}

/** iOS registration of the ONNX Runtime glue (ADR 0010 decision 3). */
@OptIn(ExperimentalAtomicApi::class)
public object FaceBlurIos {
    private val registered = AtomicReference<OnnxBridgeFactory?>(null)

    /** The ONNX Runtime version the library requires on iOS. */
    internal const val REQUIRED_RUNTIME_VERSION: String = "1.20.0"

    /** The library's version. */
    public val libraryVersion: String get() = FaceBlurInfo.VERSION

    /**
     * Registers [factory] once per process, before any `FaceMasker` is created. Returns true if [factory] is the
     * registered one afterwards (also when it was already registered), false if a different factory was registered
     * earlier.
     */
    public fun registerOnnxRuntime(factory: OnnxBridgeFactory): Boolean {
        if (registered.compareAndSet(null, factory)) {
            FaceBlurRuntime.install(IosModelRuntime(factory))
            return true
        }
        return registered.load() === factory
    }

    /**
     * A caller-owned 8-bit, four-channel pixel buffer as a [MutablePixelImage] for `FaceMasker.detect`,
     * `maskInPlace` and `applyMasks` (ADR 0004). [address] is the first byte of the top row; rows are [bytesPerRow]
     * bytes apart; [layout] gives the byte order and alpha form. The same as the form with an orientation, with
     * orientation 1 (the stored pixels are upright).
     *
     * No pixel is copied and no Objective-C object is created: the returned image reads and writes the buffer
     * directly, so it is valid only while the buffer is, and only for the duration of the call it is passed to. The
     * caller keeps the buffer alive, locked (a `CVPixelBuffer`'s base address) and untouched by other code during
     * that call; wrap the buffer again for every call. Every pixel the library writes gets alpha (or the ignored
     * byte) 255.
     *
     * The alpha bytes are checked here, once, over every pixel row (ADR 0011 decision 4a): a premultiplied layout
     * needs every pixel opaque (alpha 255; an image with transparency is refused, never repaired: flatten it first),
     * and a skip layout needs every fourth byte to be 0 or 255. A caller without meaningful alpha declares a skip
     * layout.
     *
     * @throws FaceMaskException.InvalidConfiguration when the address is 0, a size is not positive, [bytesPerRow] is
     * below `width × 4`, or the buffer's extent overflows the address space. Nothing is read or written then.
     * @throws FaceMaskException.UnsupportedImage when the alpha bytes break the rule above. Nothing is written then.
     */
    @Throws(FaceMaskException::class)
    public fun wrapRgba(
        address: Long,
        width: Int,
        height: Int,
        bytesPerRow: Int,
        layout: IosPixelLayout,
    ): MutablePixelImage = boundary { IosBufferPixels.wrap(address, width, height, bytesPerRow, layout) }

    /**
     * The buffer of the other `wrapRgba` form ([width] × [height] stored pixels), seen upright: [orientation] (1 to 8,
     * the EXIF and `CGImagePropertyOrientation` numbering: 1 upright, 2 mirrored horizontally, 3 turned 180 degrees,
     * 4 mirrored vertically, 5 transposed, 6 turned 90 degrees clockwise, 7 transversed, 8 turned 270 degrees
     * clockwise) says how a viewer turns the stored pixels to show them. Detection runs on the upright view, and every
     * coordinate of the call (faces, masks, a plan) is in it: its size is [width] × [height] for orientations 1 to 4
     * and [height] × [width] for 5 to 8. Masks are written through the same view, so they land on the stored pixels
     * that a viewer shows under them; the buffer itself is not turned. No pixel is copied. The other rules are those
     * of the form without an orientation.
     *
     * @throws FaceMaskException.InvalidConfiguration as the other form, and (`orientation`) for a value outside 1 to
     * 8. Nothing is read or written then.
     * @throws FaceMaskException.UnsupportedImage as the other form.
     */
    @Throws(FaceMaskException::class)
    public fun wrapRgba(
        address: Long,
        width: Int,
        height: Int,
        bytesPerRow: Int,
        layout: IosPixelLayout,
        orientation: Int,
    ): MutablePixelImage = boundary { IosBufferPixels.wrap(address, width, height, bytesPerRow, layout, orientation) }

    /**
     * Encodes the caller-owned buffer (described as for [wrapRgba], stored pixels as they are, orientation 1) as a
     * JPEG in memory, through the same steps as file mode's output: the platform encoder gets a no-copy image of the
     * pixels only (no metadata, no colour profile, no auxiliary image) and the [quality] (1 to 100); the library then
     * rebuilds the file from the encoder's coding segments with its own fixed JFIF and EXIF headers (orientation 1,
     * no other entry) and runs the fail-closed output check of file mode (metadata policy `StripAll`) on the result.
     * Nothing is written to disk and no pixel of the buffer is changed. Blocking: call it off the main thread.
     *
     * The alpha rules of [wrapRgba] apply: a premultiplied layout with any pixel that is not opaque is refused.
     *
     * @throws FaceMaskException.InvalidConfiguration as [wrapRgba], and (`quality`) outside 1 to 100.
     * @throws FaceMaskException.UnsupportedImage as [wrapRgba], or when a side is above 65,535 pixels (the JPEG
     * limit).
     * @throws FaceMaskException.OutputValidationFailed when the encoder's output does not pass the output check; no
     * bytes are returned then.
     * @throws FaceMaskException.Internal when the platform encoder fails.
     */
    @Throws(FaceMaskException::class)
    public fun encodeJpeg(
        address: Long,
        width: Int,
        height: Int,
        bytesPerRow: Int,
        layout: IosPixelLayout,
        quality: Int,
    ): EncodedJpeg = boundary { IosJpeg.encode(address, width, height, bytesPerRow, layout, quality) }

    /**
     * The model bytes at [address] ([byteCount] bytes) as a [ModelSource]: the bytes are copied once, now, into a
     * library-owned array, so the caller's memory (for example a Swift `Data`) may be released when this returns.
     * Size, SHA-256 and structure are checked by `DetectorModel.load` as for `ModelSource.fromBytes`.
     *
     * @throws FaceMaskException.InvalidConfiguration when [address] is 0 or [byteCount] is outside 1 to
     * 2,147,483,639 (the range of `DetectorModel.load`'s size limit). Nothing is read then.
     */
    @Throws(FaceMaskException::class)
    public fun modelSourceFromMemory(address: Long, byteCount: Long): ModelSource = boundary {
        if (byteCount < 1L || byteCount > MAX_MODEL_BYTES) {
            throw FaceMaskException.InvalidConfiguration("byteCount", "1 to $MAX_MODEL_BYTES")
        }
        val source = if (address > 0L && address <= Long.MAX_VALUE - byteCount) address.toCPointer<ByteVar>() else null
        if (source == null) throw FaceMaskException.InvalidConfiguration("address", "a valid, non-null address")
        val bytes = ByteArray(byteCount.toInt())
        bytes.usePinned { memcpy(it.addressOf(0), source, byteCount.convert()) }
        ModelSource.fromBytes(bytes)
    }

    /** The largest array `DetectorModel.load` accepts (its `maxModelBytes` range). */
    private const val MAX_MODEL_BYTES: Long = 2_147_483_639L

    /**
     * ADR 0011 decision 8 rule 6: only a [FaceMaskException] may leave a public iOS entry point (any other Kotlin
     * exception ends the process when it reaches Objective-C); everything else becomes [FaceMaskException.Internal].
     */
    internal inline fun <T> boundary(block: () -> T): T = try {
        block()
    } catch (e: FaceMaskException) {
        throw e
    } catch (e: Throwable) {
        throw FaceMaskException.Internal("unexpected failure in an iOS entry point", e)
    }
}
