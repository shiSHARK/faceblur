// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.internal.model.ModelLoader
import io.github.shishark.faceblur.internal.runtime.ModelFileReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * The JVM read rules of ADR 0012 decision 4, in test sources (the JVM target is not published, and library code has
 * no file access outside reviewed places; a JVM tool that needs it moves it to jvmMain with a reviewed entry): the last path
 * component must be a regular file and not a symbolic link (`NOFOLLOW_LINKS`), it is opened without following links,
 * at most `maxBytes` + 1 bytes are read, and the file key must be the same before and after the read. A file that
 * grows or shrinks during the read is refused: within the cap, the bytes read must equal the size before the read and
 * the size after it (beyond the cap, the `maxBytes` + 1 bytes read give `TOO_LARGE`). The remaining check-to-open race
 * is a documented residual of the unpublished JVM target. Messages never contain the path.
 */
internal object JvmModelFileReader : ModelFileReader {
    /**
     * Test seam: called after every read call of the loop with the bytes read so far and whether that call found
     * the end of the file, so a test can grow, shrink or replace the file at a known point of the read.
     */
    @Volatile
    internal var afterReadCall: ((total: Long, endOfFile: Boolean) -> Unit)? = null

    override fun read(path: String, maxBytes: Long): ByteArray {
        val p: Path =
            try {
                Paths.get(path)
            } catch (e: InvalidPathException) {
                throw FaceMaskException.IoFailed("the model path is not valid")
            }
        val before =
            try {
                Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: NoSuchFileException) {
                throw FaceMaskException.IoFailed("the model file does not exist")
            } catch (e: IOException) {
                throw FaceMaskException.IoFailed("the model file could not be read")
            }
        if (!before.isRegularFile) throw FaceMaskException.ModelRejected(listOf(ModelLoader.NOT_REGULAR_FILE))
        val bytes =
            try {
                Files.newByteChannel(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    val out = ByteArrayOutputStream()
                    val chunk = ByteBuffer.allocate(CHUNK)
                    val limit = maxBytes + 1
                    var total = 0L
                    while (total < limit) {
                        chunk.clear()
                        chunk.limit(minOf(CHUNK.toLong(), limit - total).toInt())
                        val n = channel.read(chunk)
                        if (n > 0) {
                            out.write(chunk.array(), 0, n)
                            total += n
                        }
                        afterReadCall?.invoke(total, n < 0)
                        if (n < 0) break
                    }
                    out.toByteArray()
                }
            } catch (e: IOException) {
                throw FaceMaskException.IoFailed("the model file could not be read")
            }
        val after =
            try {
                Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: IOException) {
                throw FaceMaskException.IoFailed("the model file changed while it was read")
            }
        if (!after.isRegularFile || before.fileKey() != after.fileKey()) {
            throw FaceMaskException.IoFailed("the model file changed while it was read")
        }
        // Within the cap the bytes must be one state of the file: as many as it had before and after the read.
        val read = bytes.size.toLong()
        if (read <= maxBytes && (read != before.size() || after.size() != before.size())) {
            throw FaceMaskException.IoFailed("the model file changed while it was read")
        }
        return bytes
    }

    private const val CHUNK = 64 * 1024
}
