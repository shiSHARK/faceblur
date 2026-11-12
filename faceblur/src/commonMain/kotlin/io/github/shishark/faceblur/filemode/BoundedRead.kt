// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.CoreFailure
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.ReservedBytes
import io.github.shishark.faceblur.mask.isOutOfMemory

/**
 * The stream form's bounded read (ADR 0015 decision 4, ADR 0005 decision 1): the only place where FaceBlur reads a
 * whole source into memory. Common code over the platform's [StreamReader], so every rule is host-tested:
 *
 * 1. Read the first [ImageFormat.SNIFF_BYTES] bytes (fewer only at the end of the stream) and check the format
 *    ([ImageFormat.sniff]) before anything else is buffered: anything that is not JPEG, HEIC or PNG fails with
 *    `FaceMaskException.UnsupportedImage` after its first bytes.
 * 2. Read the rest in growing chunks, at most [MAX_STREAM_SOURCE_BYTES] in total (the whole stream, trailing data
 *    included). One more byte fails with `FaceMaskException.SourceTooLarge(limit)` before any decoding. A new chunk
 *    is allocated only after one more byte was read, so a stream that ends at a chunk boundary allocates no unused
 *    chunk. The call's cancellation is checked once per chunk and before the join.
 * 3. Join the chunks into one array for the decoder (for a moment about twice the source size, then once).
 *
 * Every growth (each chunk and the joined array) is admitted through the mask stage's memory check
 * ([ReservedBytes.admit], ADR 0013 decision 6) with the process-wide count of the other running calls, before it is
 * allocated; a growth that does not fit fails with `NotEnoughMemory` without allocating it. As in the mask stage, the
 * admitted bytes leave the count once the array is allocated and touched (one write per 4 KiB page, so that it shows
 * in the platform's figures also where a zeroed allocation is not resident until written). The buffer is released
 * by the orchestration once the image is decoded ([FileSource.Bytes.release]).
 */
internal object BoundedRead {
    /** The first chunk; each further chunk doubles up to [MAX_CHUNK]. */
    const val FIRST_CHUNK: Int = 64 * 1024

    /** The largest chunk. */
    const val MAX_CHUNK: Int = 4 * 1024 * 1024

    /**
     * Reads the whole stream of [reader] into a [FileSource.Bytes], under the rules above, with [probe] (the mask
     * stage's memory probe; `null`: the growths are only counted). [limit] is [MAX_STREAM_SOURCE_BYTES] outside
     * tests. [observer] (tests) sees every admitted growth and its release. [checkCancelled] is the call's
     * cancellation check (throws `FaceMaskException.Cancelled`); nothing needs undoing then, no output exists yet.
     *
     * @throws FaceMaskException.UnsupportedImage when the first bytes are not a supported format.
     * @throws FaceMaskException.SourceTooLarge when the stream holds more than [limit] bytes.
     * @throws FaceMaskException.IoFailed when [reader] breaks the read contract (it maps its own read failures).
     * @throws CoreFailure.NotEnoughMemory when a growth does not fit (mapped to `NotEnoughMemory` by the caller).
     * @throws FaceMaskException.Cancelled when [checkCancelled] reports cancellation.
     */
    @HiddenFromJava
    fun read(
        reader: StreamReader,
        probe: MemoryProbe?,
        limit: Long = MAX_STREAM_SOURCE_BYTES,
        observer: Observer? = null,
        checkCancelled: () -> Unit = {},
    ): FileSource.Bytes {
        val chunks = ArrayList<ByteArray>()
        var total = 0L
        var chunkSize = FIRST_CHUNK.toLong()
        var current = grow(minOf(chunkSize, limit).toInt(), total, probe, observer)
        chunks += current
        // Step 1: the first bytes only, then the format check before anything else is buffered.
        var filled = fill(reader, current, 0, minOf(ImageFormat.SNIFF_BYTES, current.size))
        ImageFormat.sniff(current.copyOf(filled))
        var ended = filled < minOf(ImageFormat.SNIFF_BYTES, current.size)
        // Step 2: the rest in chunks, at most [limit] bytes in total.
        while (!ended) {
            if (filled < current.size) {
                val n = fill(reader, current, filled, current.size)
                ended = n < current.size
                filled = n
                continue
            }
            // The chunk is full: one more byte decides whether the stream goes on.
            total += filled
            filled = 0
            checkCancelled()
            val next = readOne(reader) ?: break
            if (total >= limit) throw FaceMaskException.SourceTooLarge(limit)
            chunkSize = minOf(chunkSize * 2, MAX_CHUNK.toLong())
            current = grow(minOf(chunkSize, limit - total).toInt(), total, probe, observer)
            chunks += current
            current[0] = next
            filled = 1
        }
        total += filled
        checkCancelled()
        // Step 3: one array of exactly the stream's size for the decoder.
        val whole = if (chunks.size == 1 && chunks[0].size.toLong() == total) {
            chunks[0]
        } else {
            join(chunks, total.toInt(), probe, observer)
        }
        chunks.clear()
        return FileSource.Bytes(whole)
    }

    /** Test hook: every admitted growth ([admitted]) and its removal from the count ([released]). */
    interface Observer {
        fun admitted(bytes: Long) {}

        fun released(bytes: Long) {}
    }

    /** Fills [buffer] from [from] up to [to] or the end of the stream; returns the new fill level. */
    private fun fill(reader: StreamReader, buffer: ByteArray, from: Int, to: Int): Int {
        var at = from
        while (at < to) {
            val n = reader.read(buffer, at, to - at)
            if (n == -1) return at
            if (n < 1 || n > to - at) throw FaceMaskException.IoFailed("the stream broke its read contract")
            at += n
        }
        return at
    }

    /** The next byte of the stream, or `null` at its end. */
    private fun readOne(reader: StreamReader): Byte? {
        val one = ByteArray(1)
        return if (fill(reader, one, 0, 1) == 1) one[0] else null
    }

    private fun join(chunks: List<ByteArray>, size: Int, probe: MemoryProbe?, observer: Observer?): ByteArray {
        val whole = grow(size, chunks.sumOf { it.size.toLong() }, probe, observer)
        var at = 0
        for (c in chunks) {
            val n = minOf(c.size, size - at)
            c.copyInto(whole, at, 0, n)
            at += n
        }
        return whole
    }

    /**
     * Admits [size] bytes ([held]: the bytes this call already holds), allocates and touches them; the admitted
     * bytes leave the count once the array is touched. An out-of-memory error of the allocation itself (memory taken
     * by another thread after the check) becomes [CoreFailure.NotEnoughMemory], as the mask stage's backstop does.
     */
    private fun grow(size: Int, held: Long, probe: MemoryProbe?, observer: Observer?): ByteArray {
        val bytes = size.toLong()
        ReservedBytes.admit(bytes, probe, held)
        observer?.admitted(bytes)
        try {
            return ByteArray(size).also { touch(it) }
        } catch (t: Throwable) {
            if (!isOutOfMemory(t)) throw t
            throw CoreFailure.NotEnoughMemory(t)
        } finally {
            ReservedBytes.release(bytes)
            observer?.released(bytes)
        }
    }

    /** One write per page, as the mask stage touches its working area. */
    private fun touch(a: ByteArray) {
        var i = 0
        while (i < a.size) {
            a[i] = 0
            i += PAGE_BYTES
        }
    }

    private const val PAGE_BYTES = 4_096
}
