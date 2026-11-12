// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.system.ErrnoException
import io.github.shishark.faceblur.HiddenFromJava
import java.nio.ByteBuffer

/**
 * The direct buffer that ONNX Runtime creates sessions from (ADR 0012 decision 8, ST-049 AC1), made once per
 * `DetectorModel` from its verified bytes.
 *
 * On Android a buffer from `ByteBuffer.allocateDirect` is backed by a Java array and counts against the Java heap, so
 * the bytes go to a read-only anonymous shared-memory region instead ([SharedModelMemory]). If the region cannot be
 * created, mapped or made read-only, the bytes go to an `allocateDirect` buffer (same content, only the memory
 * accounting differs).
 */
internal object ModelBuffer {
    /** Seam for tests: false forces the `allocateDirect` path. Synthetic, so Java code cannot change it. */
    @Volatile
    @get:HiddenFromJava
    @set:HiddenFromJava
    internal var sharedMemoryAvailable: Boolean = true

    /**
     * A read-only direct buffer holding exactly [bytes] (position 0, limit = size); [bytes] is not kept. [outsideHeap]
     * makes the shared-memory copy (null or [ErrnoException] = not available); `null` (production) is
     * [SharedModelMemory.readOnlyCopy], host tests pass their own. A copy that
     * is not direct, not read-only, or not exactly [bytes] long from position 0 is not used (the heap-backed buffer
     * below is; the unused mapping is released when it is collected).
     */
    @HiddenFromJava
    fun of(bytes: ByteArray, outsideHeap: ((ByteArray) -> ByteBuffer?)? = null): ByteBuffer {
        if (sharedMemoryAvailable) {
            val region =
                try {
                    if (outsideHeap != null) outsideHeap(bytes) else SharedModelMemory.readOnlyCopy(bytes)
                } catch (e: ErrnoException) {
                    null // the heap-backed direct buffer below
                }
            if (region != null && hasTheShapeOf(region, bytes)) return region
        }
        val direct = ByteBuffer.allocateDirect(bytes.size)
        direct.put(bytes)
        direct.flip()
        return direct.asReadOnlyBuffer()
    }

    private fun hasTheShapeOf(region: ByteBuffer, bytes: ByteArray): Boolean =
        region.isDirect && region.isReadOnly && region.position() == 0 && region.remaining() == bytes.size
}
