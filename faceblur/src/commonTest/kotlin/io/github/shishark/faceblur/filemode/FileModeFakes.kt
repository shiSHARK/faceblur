// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlatformTargetValue
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels

/**
 * Fake platform parts of file mode (ADR 0015 decision 4) for the common orchestration tests. Every call is recorded in
 * [events] in order; [fail] makes the named step throw [failure] (a step name as recorded, for example `decode`).
 */
internal class FileModeFakes {
    val events = ArrayList<String>()
    var fail: String? = null

    /** A second step that throws [failure] (for a failure followed by a failing close). */
    var failAlso: String? = null
    var failure: () -> Throwable = { IllegalStateException("planted failure") }

    /** The size the codec reports (orientation applied). */
    var size = Size(800, 600)
    var hasGainMap = false
    var metadata: PhotoMetadata? = null
    var metadataReads = 0

    /** Overrides of the sizes the platform parts return (to test the fail-closed size checks). */
    var decodedSize: Size? = null
    var scaledSize: Size? = null

    /** What the encoder returns for an image of the given size (default: a JPEG whose frame has that size). */
    var encoded: (Size) -> ByteArray = { JpegTestData.encoderOutput(it) }

    /** When set, every pixel write into a decoded or scaled image throws this (a failing platform buffer). */
    var pixelWriteFailure: (() -> Throwable)? = null

    /** Changes the decoded pixels (row-major ARGB of the decoded size) before the codec returns them. */
    var pixelEdit: ((IntArray) -> Unit)? = null

    /** Called before every pixel read of a decoded or scaled image; [pixelReads] counts the reads. */
    var onRead: (() -> Unit)? = null
    var pixelReads = 0

    /** The sources the codec was given, and the pixels the encoder saw (a copy per encode). */
    val opened = ArrayList<FileSource>()
    val encodedPixels = ArrayList<IntArray>()
    val writes = ArrayList<ByteArray>()
    var openImages = 0

    private fun step(name: String) {
        events += name
        if (fail == name || failAlso == name) throw failure()
    }

    val codec = ImageCodec { source, _ ->
        opened += source
        step("open")
        openImages++
        val headerSize = size
        val gainMap = hasGainMap
        val sourceMetadata = metadata
        object : OpenedImage {
            override val format = ImageFormat.JPEG
            override val size = headerSize
            override val hasGainMap = gainMap
            override val metadata: PhotoMetadata?
                get() {
                    metadataReads++
                    return sourceMetadata
                }

            override fun decode(working: Size): WorkingImage {
                step("decode ${working.width}x${working.height}")
                val s = decodedSize ?: working
                val data = gradientPixels(s.width, s.height)
                pixelEdit?.invoke(data)
                return Working(Pixels(IntArrayPixels(s.width, s.height, data)), "working")
            }

            override fun close() {
                openImages--
                step("close opened")
            }
        }
    }

    /** A platform buffer: [base]'s pixels; writes throw [pixelWriteFailure] when it is set. */
    inner class Pixels(val base: IntArrayPixels) : MutablePixelImage {
        override val width get() = base.width
        override val height get() = base.height

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
            pixelReads++
            onRead?.invoke()
            base.readPixels(x, y, w, h, dst)
        }

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            pixelWriteFailure?.let { throw it() }
            base.writePixels(x, y, w, h, src)
        }
    }

    inner class Working(override val pixels: Pixels, private val name: String) : WorkingImage {
        init {
            openImages++
        }

        override fun scaledTo(output: Size): WorkingImage {
            step("scale ${output.width}x${output.height}")
            val s = scaledSize ?: output
            return Working(Pixels(IntArrayPixels(s.width, s.height)), "scaled")
        }

        override fun encodeJpeg(quality: Int): ByteArray {
            step("encode $name q$quality")
            encodedPixels += pixels.base.data.copyOf()
            return encoded(Size(pixels.width, pixels.height))
        }

        override fun close() {
            openImages--
            step("close $name")
        }
    }

    val writer = object : TargetWriter {
        override fun check(target: OutputTarget, source: FileSource, call: CallContext) = step("check")

        override fun accepts(value: PlatformTargetValue): Boolean = value is OwnTargetValue

        override fun absolutePath(path: String): String = if (path.startsWith("/")) path else "/cwd/$path"

        override fun write(target: OutputTarget, source: FileSource, bytes: ByteArray, call: CallContext) {
            step("write")
            writes += bytes
        }
    }

    /** The platform's own target value (accepted by [writer]). */
    class OwnTargetValue(override val kind: String = "newFile") : PlatformTargetValue

    /** A value the platform did not make (as a Java app could write one). */
    class ForeignTargetValue : PlatformTargetValue {
        override val kind = "foreign"
    }

    /** A platform source; [replaceableInOneStep] as given. */
    class Source(override val replaceableInOneStep: Boolean, override val kind: String = "content") :
        PlatformSourceValue

    /** A platform call value (the platform forms' context for one call). */
    object Call : PlatformCallValue {
        override val kind = "test"
    }
}

/** A stream of [size] bytes that starts with [head] and continues with a byte pattern; counts what was read. */
internal class CountingStream(private val size: Long, private val head: ByteArray = JPEG_HEAD) : StreamReader {
    var position = 0L
        private set
    var reads = 0
        private set

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        reads++
        if (position >= size) return -1
        val n = minOf(length.toLong(), size - position).toInt()
        for (i in 0 until n) buffer[offset + i] = byteAt(position + i)
        position += n
        return n
    }

    fun byteAt(at: Long): Byte = if (at < head.size) head[at.toInt()] else (at * 31 + 7).toByte()

    companion object {
        val JPEG_HEAD = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    }
}

/** [MutablePixelImage] pixels as one array (for comparisons). */
internal fun MutablePixelImage.snapshot(): IntArray {
    val out = IntArray(width * height)
    readPixels(0, 0, width, height, out)
    return out
}
