// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.MemoryRule
import io.github.shishark.faceblur.mask.ReservedBytes
import io.github.shishark.faceblur.mask.StageObserver
import io.github.shishark.faceblur.mask.StageSeams
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stream form's bounded read (ADR 0015 decision 4; ADR 0005 decision 1): format first, at most 64 MiB, every
 * growth through the memory check, the buffer released before the mask stage reserves its working area, and nothing
 * written on any failure.
 */
class BoundedReadTest {
    private val fakes = FileModeFakes()
    private val sinkWrites = ArrayList<ByteArray>()
    private val sink = StreamSink { sinkWrites += it }
    private lateinit var masker: FaceMasker
    private val limit = MAX_STREAM_SOURCE_BYTES

    /** A heap probe with a fixed maximum and nothing in use. */
    private class Budget(private val bytes: Long) : MemoryProbe {
        override val rule = MemoryRule.HEAP_REMAINING

        override fun figure(): Long = bytes
    }

    /** Records every growth and its release. */
    private class Growths : BoundedRead.Observer {
        val admitted = ArrayList<Long>()
        var released = 0L
        var held = 0L
        var maxHeld = 0L

        override fun admitted(bytes: Long) {
            admitted += bytes
            held += bytes
            maxHeld = maxOf(maxHeld, held)
        }

        override fun released(bytes: Long) {
            released += bytes
            held -= bytes
        }
    }

    @BeforeTest
    fun setUp() {
        FaceBlurRuntime.overrideDefaults(PlatformDefaults.NONE)
        FaceBlurRuntime.installImageCodec(fakes.codec)
        FaceBlurRuntime.installTargetWriter(fakes.writer)
        masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
    }

    @AfterTest
    fun tearDown() {
        masker.close()
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        FaceBlurRuntime.overrideDefaults(null)
        assertEquals(0L, ReservedBytes.current(), "every reservation is released")
    }

    private fun stream(reader: StreamReader, options: FileOptions = FileOptions(maxLongSide = 0)) =
        masker.maskStream(reader, sink, options, null, CallContext(null))

    @Test
    fun aStreamThatIsNotAnImageFailsAfterItsFirstBytes() {
        val text = CountingStream(10_000_000, "GIF89a-not-supported".encodeToByteArray())
        assertFailsWith<FaceMaskException.UnsupportedImage> { stream(text) }
        assertTrue(text.position <= ImageFormat.SNIFF_BYTES, "read ${text.position} bytes before the format check")
        assertEquals(ImageFormat.SNIFF_BYTES.toLong(), text.position)
        // Shorter than the signature: also UnsupportedImage, never an index error.
        assertFailsWith<FaceMaskException.UnsupportedImage> { stream(CountingStream(2)) }
        assertFailsWith<FaceMaskException.UnsupportedImage> { stream(CountingStream(0)) }
        assertTrue(fakes.opened.isEmpty(), "the codec is never reached")
        assertTrue(sinkWrites.isEmpty())
    }

    @Test
    fun aStreamOfExactlyTheLimitIsReadInFull() {
        val s = CountingStream(limit)
        val growths = Growths()
        val bytes = BoundedRead.read(s, null, observer = growths).bytes
        assertEquals(limit, bytes.size.toLong())
        assertEquals(limit, s.position)
        for (at in listOf(0L, 3L, 64L, 65_535L, 65_536L, 1_000_003L, limit - 1)) {
            assertEquals(s.byteAt(at), bytes[at.toInt()], "byte $at")
        }
        assertEquals(growths.admitted.sum(), growths.released, "every growth leaves the count")
        assertTrue(growths.admitted.all { it <= limit }, "no growth beyond the limit")
        assertEquals(limit, growths.admitted.last(), "the joined array has exactly the stream's size")
    }

    @Test
    fun oneByteMoreThanTheLimitIsSourceTooLargeAndNothingIsWritten() {
        val s = CountingStream(limit + 1)
        val e = assertFailsWith<FaceMaskException.SourceTooLarge> { stream(s) }
        assertEquals(67_108_864L, e.limitBytes)
        assertEquals(limit + 1, s.position, "read the limit and one byte more, no further")
        val text = e.message.orEmpty()
        assertTrue("67,108,864" in text && "file (path) or Uri form" in text, text)
        assertFalse('/' in text, text)
        assertTrue(sinkWrites.isEmpty(), "zero bytes written to the output")
        assertTrue(fakes.opened.isEmpty(), "nothing decoded")
        // A much longer stream stops at the same point.
        val long = CountingStream(limit * 4)
        assertFailsWith<FaceMaskException.SourceTooLarge> { BoundedRead.read(long, null) }
        assertEquals(limit + 1, long.position)
    }

    @Test
    fun theReadIsExactAtEveryChunkBoundary() {
        val small = 300_000L
        for (size in listOf(3L, 64L, 65L, 65_535L, 65_536L, 65_537L, 196_608L, 196_609L, small - 1, small)) {
            val s = CountingStream(size)
            val b = BoundedRead.read(s, null, limit = small).bytes
            assertEquals(size, b.size.toLong(), "size $size")
            for (at in listOf(0L, size / 2, size - 1)) assertEquals(s.byteAt(at), b[at.toInt()], "size $size at $at")
        }
        assertFailsWith<FaceMaskException.SourceTooLarge> { BoundedRead.read(CountingStream(small + 1), null, small) }
        // A limit below the format check's first bytes still checks the format first.
        assertEquals(10, BoundedRead.read(CountingStream(10), null, limit = 10).bytes.size)
        assertFailsWith<FaceMaskException.SourceTooLarge> { BoundedRead.read(CountingStream(11), null, limit = 10) }
    }

    @Test
    fun aReaderThatBreaksTheReadContractFailsClosed() {
        for (answer in listOf(0, -2, Int.MAX_VALUE)) {
            var first = true
            val broken = StreamReader { buffer, offset, length ->
                if (first) {
                    first = false
                    CountingStream.JPEG_HEAD.copyInto(buffer, offset, 0, minOf(length, CountingStream.JPEG_HEAD.size))
                    minOf(length, CountingStream.JPEG_HEAD.size)
                } else {
                    answer
                }
            }
            assertFailsWith<FaceMaskException.IoFailed>("answer $answer") { stream(broken) }
        }
        // The reader's own failure (the platform maps read errors) passes unchanged.
        val failing = StreamReader { _, _, _ -> throw FaceMaskException.IoFailed("the input stream could not be read") }
        assertFailsWith<FaceMaskException.IoFailed> { stream(failing) }
        assertTrue(sinkWrites.isEmpty())
    }

    @Test
    fun aBufferThatDoesNotFitTheMemoryCheckFailsWithNotEnoughMemory() {
        masker.useStageSeamsForTest(StageSeams(probe = Budget(1L shl 20)))
        val s = CountingStream(4L shl 20)
        assertFailsWith<FaceMaskException.NotEnoughMemory> { stream(s) }
        assertTrue(s.position < (4L shl 20), "refused while growing, not after reading everything")
        assertTrue(sinkWrites.isEmpty())
        assertTrue(fakes.opened.isEmpty())
        // The join (twice the source for a moment) is checked as well.
        val growths = Growths()
        assertFailsWith<CoreFailure.NotEnoughMemory> {
            BoundedRead.read(CountingStream(700_000), Budget(1_100_000L), observer = growths)
        }
        assertEquals(983_040L, growths.admitted.sum(), "every chunk was admitted, the join was refused")
        assertEquals(growths.admitted.sum(), growths.released)
    }

    /** The buffer and its reservation are released once the image is decoded, before the mask stage reserves. */
    @Test
    fun theBufferIsReleasedBeforeTheMaskStageReservesItsWorkingArea() {
        val atReserve = ArrayList<String>()
        masker.useStageSeamsForTest(
            StageSeams(
                observer = object : StageObserver {
                    override fun step(step: Int, work: Long) {
                        if (step != 1) return
                        val source = fakes.opened.single()
                        atReserve +=
                            "released=${(source as FileSource.Bytes).isReleased} reserved=${ReservedBytes.current()}"
                    }
                },
            ),
        )
        val r = stream(CountingStream(1_000_000))
        assertEquals(listOf("released=true reserved=0"), atReserve)
        assertEquals(1, sinkWrites.size, "written once")
        assertEquals(sinkWrites.single().size.toLong(), r.outputBytes)
        assertEquals(
            listOf("open", "decode 800x600", "close opened", "encode working q90", "close working"),
            fakes.events,
        )
        assertTrue(r.faces.isNotEmpty())
        // On a failure after the read the buffer is released too.
        fakes.fail = "open"
        fakes.opened.clear()
        assertFailsWith<FaceMaskException.Internal> { stream(CountingStream(1_000)) }
        assertTrue((fakes.opened.single() as FileSource.Bytes).isReleased)
        fakes.fail = null
    }

    /** Cancelled once [cancelled] returns true. */
    private class When(private val cancelled: () -> Boolean) : CancellationToken {
        override val isCancellationRequested: Boolean get() = cancelled()
    }

    @Test
    fun aReleasedBufferCannotBeReadAgain() {
        val b = FileSource.Bytes(ByteArray(3))
        assertFalse(b.isReleased)
        b.release()
        b.release()
        assertTrue(b.isReleased)
        assertFailsWith<FaceMaskException.Internal> { b.bytes }
        assertEquals("FileSource.Bytes(size=3)", b.toString())
    }

    /** Cancellation after the whole stream was read (in the orchestration, once the source is open) writes nothing. */
    @Test
    fun cancellationOrAFailureAfterTheReadWritesNothingToTheStream() {
        val s = CountingStream(1_000)
        val afterOpen = When { fakes.opened.isNotEmpty() }
        assertFailsWith<FaceMaskException.Cancelled> {
            masker.maskStream(s, sink, FileOptions(maxLongSide = 0), afterOpen, CallContext(null))
        }
        assertEquals(1_000L, s.position, "the stream was read in full")
        assertEquals(listOf("open", "close opened"), fakes.events, "cancelled after the header, before decoding")
        assertTrue((fakes.opened.single() as FileSource.Bytes).isReleased)
        fakes.encoded = { ByteArray(10) }
        assertFailsWith<FaceMaskException.OutputValidationFailed> { stream(CountingStream(1_000)) }
        assertTrue(sinkWrites.isEmpty())
    }

    /**
     * The read itself can be cancelled: the call's cancellation is checked once per chunk (before the next byte is
     * read) and before the join, so a slow stream does not hold the call for the whole 64 MiB.
     */
    @Test
    fun cancellationIsCheckedWhileTheStreamIsRead() {
        val s = CountingStream(10_000_000)
        val afterFirstChunk = When { s.position >= BoundedRead.FIRST_CHUNK }
        assertFailsWith<FaceMaskException.Cancelled> {
            masker.maskStream(s, sink, FileOptions(maxLongSide = 0), afterFirstChunk, CallContext(null))
        }
        assertEquals(BoundedRead.FIRST_CHUNK.toLong(), s.position, "stopped after the first chunk, no byte more")
        assertTrue(fakes.opened.isEmpty(), "nothing decoded")
        assertTrue(sinkWrites.isEmpty())
        // One check per full chunk and one before the join: 64 + 128 + 256 + 512 KiB full, the rest in the fifth.
        var checks = 0
        val b = BoundedRead.read(CountingStream(1_000_000), null, checkCancelled = { checks++ }).bytes
        assertEquals(1_000_000, b.size)
        assertEquals(5, checks)
        // Cancelled at the last check (before the join): nothing is joined.
        val growths = Growths()
        var n = 0
        assertFailsWith<FaceMaskException.Cancelled> {
            BoundedRead.read(CountingStream(1_000_000), null, observer = growths, checkCancelled = {
                if (++n == 5) throw FaceMaskException.Cancelled()
            })
        }
        assertEquals(5, growths.admitted.size, "five chunks, no joined array")
        assertEquals(growths.admitted.sum(), growths.released)
    }

    /** A stream that ends exactly at a chunk boundary allocates no further chunk (one byte is read ahead). */
    @Test
    fun aStreamThatEndsAtAChunkBoundaryAllocatesNoUnusedChunk() {
        val one = Growths()
        assertEquals(65_536, BoundedRead.read(CountingStream(65_536), null, observer = one).bytes.size)
        assertEquals(listOf(65_536L), one.admitted, "one chunk, used as it is")
        val two = Growths()
        assertEquals(196_608, BoundedRead.read(CountingStream(196_608), null, observer = two).bytes.size)
        assertEquals(listOf(65_536L, 131_072L, 196_608L), two.admitted, "two chunks and the join")
        // One byte more: the next chunk exists and holds that byte.
        val three = Growths()
        val s = CountingStream(196_609)
        val b = BoundedRead.read(s, null, observer = three).bytes
        assertEquals(listOf(65_536L, 131_072L, 262_144L, 196_609L), three.admitted)
        assertEquals(s.byteAt(196_608), b[196_608])
    }
}
