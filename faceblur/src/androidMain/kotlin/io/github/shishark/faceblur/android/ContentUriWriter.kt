// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.HiddenFromJava
import java.io.IOException
import java.io.OutputStream

/**
 * The content-URI writer (ADR 0005 decision 4). There is no atomic replace for a content URI, so:
 * 1. the finished bytes are complete in memory before the target is opened (the orchestration's single write);
 * 2. probe: the target is opened `"r"`; it must be readable (for the read-back), a regular file (`statSize` >= 0),
 *    and empty unless [overwrite] (`AndroidOutputTargets.overwriteNonAtomic`); nothing is opened for writing;
 * 3. open `"wt"` (write and truncate; never `"w"`, whose truncation is provider-defined), which must again be a
 *    regular file;
 * 4. write every byte, truncate to the length explicitly, `fsync`, check the size, close (an error of `close()` fails
 *    the call: some providers store the data only then);
 * 5. read-back on a new `"r"` descriptor: same size, every byte equal, nothing after the end;
 * 6. a failure while the write descriptor is open (any throwable, also an `Error`) first truncates the target to
 *    0 bytes (best effort), so it is never left with masked bytes followed by the rest of the original; the first
 *    error is reported.
 * After any error from a call with a content-URI target, the target must be treated as unmasked (KDoc of the
 * targets). Messages are fixed and never contain the URI; platform exceptions are never chained.
 */
internal object ContentUriWriter {
    /** Step 2: the refusals before anything is opened for writing. */
    fun check(resolver: ContentResolver, uri: Uri, overwrite: Boolean) {
        val size = probe(resolver, uri)
        if (size > 0 && !overwrite) {
            throw FaceMaskException.UnsupportedTarget(
                "the target is not empty; AndroidOutputTargets.overwriteNonAtomic replaces existing content",
            )
        }
    }

    /**
     * Steps 2 to 6. [put] writes the bytes to the open stream (a test seam: host tests plant a failure inside the open
     * window; production writes them in one call).
     */
    @HiddenFromJava
    fun write(
        resolver: ContentResolver,
        uri: Uri,
        overwrite: Boolean,
        bytes: ByteArray,
        put: (OutputStream, ByteArray) -> Unit = { out, b -> out.write(b) },
    ) {
        check(resolver, uri, overwrite)
        val pfd =
            try {
                resolver.openFileDescriptor(uri, "wt")
            } catch (e: Exception) {
                null
            } ?: throw FaceMaskException.UnsupportedTarget("the provider did not open the target for writing (\"wt\")")
        if (pfd.statSize < 0) {
            closeQuietly(pfd)
            throw notRegular()
        }
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        val channel = out.channel
        var open = true
        try {
            put(out, bytes)
            channel.truncate(bytes.size.toLong())
            channel.force(true)
            if (channel.size() != bytes.size.toLong()) throw stored()
            open = false
            out.close()
        } catch (e: IOException) {
            throw cleanedUp(open, out, stored())
        } catch (e: FaceMaskException) {
            throw cleanedUp(open, out, e)
        } catch (e: RuntimeException) {
            throw cleanedUp(open, out, stored())
        } catch (t: Throwable) {
            // An Error (out of memory, for example) also truncates and closes first; it then passes unchanged.
            cleanedUp(open, out, stored())
            throw t
        }
        readBack(resolver, uri, bytes)
    }

    /** Step 6: truncates and closes a still open write descriptor, then returns [error] to be thrown. */
    private fun cleanedUp(
        open: Boolean,
        out: ParcelFileDescriptor.AutoCloseOutputStream,
        error: FaceMaskException,
    ): FaceMaskException {
        if (open) {
            try {
                out.channel.truncate(0)
            } catch (e: Exception) {
                Unit
            }
            try {
                out.close()
            } catch (e: Exception) {
                Unit
            }
        }
        return error
    }

    /** Opens the target `"r"` and returns its size; refuses an unreadable target or one that is not a file. */
    private fun probe(resolver: ContentResolver, uri: Uri): Long {
        val pfd =
            try {
                resolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                null
            } ?: throw FaceMaskException.UnsupportedTarget(
                "the target cannot be opened for reading; a content-URI target must be readable for the check " +
                    "after writing",
            )
        val size = pfd.statSize
        closeQuietly(pfd)
        if (size < 0) throw notRegular()
        return size
    }

    /** Step 5: the stored content must be exactly [expected]. */
    private fun readBack(resolver: ContentResolver, uri: Uri, expected: ByteArray) {
        val pfd =
            try {
                resolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                null
            } ?: throw unverified()
        val same =
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    pfd.statSize == expected.size.toLong() && sameContent(input, expected)
                }
            } catch (e: IOException) {
                false
            } catch (e: RuntimeException) {
                false
            }
        if (!same) throw unverified()
    }

    private fun sameContent(input: java.io.InputStream, expected: ByteArray): Boolean {
        val buffer = ByteArray(64 * 1024)
        var at = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return at == expected.size
            if (at + n > expected.size) return false
            for (i in 0 until n) if (buffer[i] != expected[at + i]) return false
            at += n
        }
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor) {
        try {
            pfd.close()
        } catch (e: IOException) {
            Unit
        }
    }

    private fun notRegular() = FaceMaskException.UnsupportedTarget("the target is not a regular file")

    private fun stored() = FaceMaskException.IoFailed(
        "the target could not be written; treat it as unmasked and do not share it",
    )

    // A failed read-back check is reported as an I/O failure; there is no separate error type for it.
    private fun unverified() = FaceMaskException.IoFailed(
        "the target does not hold exactly the written output; treat it as unmasked and do not share it",
    )
}
