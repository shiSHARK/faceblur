// SPDX-License-Identifier: Apache-2.0
package docs.snippets.android

import android.content.Context
import android.net.Uri
import io.github.shishark.faceblur.AndroidOutputTargets
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FileResult
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.maskFile
import io.github.shishark.faceblur.maskJpeg
import java.io.InputStream
import java.io.OutputStream

/** Path form: a new file; an existing file at [targetPath] is NOT replaced (UnsupportedTarget). */
fun maskPath(masker: FaceMasker, sourcePath: String, targetPath: String): FileResult =
    masker.maskFile(sourcePath, OutputTarget.NewFile(targetPath))

/** Uri form: [target] is an empty document, for example from ACTION_CREATE_DOCUMENT. */
fun maskUri(masker: FaceMasker, context: Context, source: Uri, target: Uri): FileResult =
    masker.maskFile(context, source, AndroidOutputTargets.newFile(target))

/** Stream form: the whole source is read into memory, at most 64 MiB. Neither stream is closed. */
fun maskStream(masker: FaceMasker, input: InputStream, output: OutputStream): FileResult? = try {
    masker.maskJpeg(input, output)
} catch (e: FaceMaskException.SourceTooLarge) {
    // Nothing was decoded and nothing was written to the output stream. Use the path or Uri form for this source.
    // null means not masked: do not use or publish the original.
    null
}
