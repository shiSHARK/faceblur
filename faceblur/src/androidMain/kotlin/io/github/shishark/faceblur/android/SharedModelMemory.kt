// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.os.SharedMemory
import android.system.OsConstants
import io.github.shishark.faceblur.HiddenFromJava
import java.nio.ByteBuffer

/**
 * The model bytes outside the Java heap (ADR 0012 decision 8): an anonymous shared-memory region, never a file, never
 * shared with another process (its descriptor is closed right after mapping), mapped read-only before the runtime
 * sees it. The mapping stays valid after the descriptor is closed and is released when the buffer is collected
 * (ASSUMPTION for API 30+, from the `SharedMemory` documentation; the device test drops the model and keeps using its
 * masker).
 *
 * Phone-only (Robolectric's `SharedMemory` cannot run on the host JDK): this file holds only the `SharedMemory` calls;
 * the choice and the fallback are in [ModelBuffer] (host-tested).
 */
internal object SharedModelMemory {
    /**
     * [bytes] in a read-only shared-memory mapping, or null when the region cannot be made read-only.
     *
     * @throws android.system.ErrnoException when the region cannot be created or mapped.
     */
    @HiddenFromJava
    fun readOnlyCopy(bytes: ByteArray): ByteBuffer? {
        val region = SharedMemory.create(null, bytes.size)
        try {
            val writable = region.mapReadWrite()
            try {
                writable.put(bytes)
            } finally {
                SharedMemory.unmap(writable)
            }
            if (!region.setProtect(OsConstants.PROT_READ)) return null
            return region.mapReadOnly()
        } finally {
            region.close()
        }
    }
}
