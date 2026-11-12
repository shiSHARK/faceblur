// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import android.net.Uri
import io.github.shishark.faceblur.android.AndroidTargetValue

/**
 * Output targets for `Uri`s, used with the forms of `maskFile` that take a `Context` (Java:
 * `FaceMaskerAndroid.maskFile(masker, context, uri, target)`). Creating a target checks and opens nothing and holds
 * no `Context`; the target is checked when the call runs. `toString()` names the kind of target only, never the URI.
 *
 * Supported schemes: `content:` (for example a document from `ACTION_CREATE_DOCUMENT` or a `MediaStore` item the app
 * may write) and `file:` (a file-system path, written exactly like [OutputTarget.NewFile]). Any other scheme is
 * refused with [FaceMaskException.UnsupportedTarget] when the call runs.
 */
public object AndroidOutputTargets {
    /**
     * A new, EMPTY document at [uri]. The provider must let the app read and write it (FaceBlur reads the written
     * content back and compares it). A non-empty target is refused with [FaceMaskException.UnsupportedTarget] before
     * anything is opened for writing, and is untouched. Providers offer no exclusive open: content that another app
     * writes into the document between that check and FaceBlur's write is replaced (truncated); give the document to
     * no other writer while the call runs. A `file:` URI behaves like `OutputTarget.NewFile(path)`: an existing file is
     * never replaced, and the new file can hold an incomplete file of masked output until the call returns or after a
     * process kill (see `OutputTarget.NewFile`).
     */
    @JvmStatic
    public fun newFile(uri: Uri): OutputTarget = OutputTarget.PlatformTarget.create(AndroidTargetValue(uri, false))

    /**
     * EXPLICIT OPT-IN: replaces the content of the existing document at [uri], also the source itself. A content URI
     * cannot be replaced in one step: the masked JPEG is complete in memory first, the target is opened with `"wt"`
     * (write and truncate), written once, truncated to the output's length and read back for a byte-by-byte
     * comparison; on a failure while it is open it is truncated to 0 bytes where possible. A process kill, a provider
     * failure or an I/O error inside that window can still leave the target empty, damaged or holding the start of the
     * masked output followed by bytes of the original, which can include original pixels; the original cannot be
     * restored. AFTER ANY ERROR FROM A CALL WITH A CONTENT-URI TARGET, TREAT THE TARGET AS UNMASKED AND DO NOT SHARE
     * IT. The provider must let the app read and write the document.
     *
     * A `file:` URI is replaced in one step, exactly like `OutputTarget.NewFile(path, replaceExisting = true)`: a
     * symbolic link, a file with other hard links or anything that is not a regular file is refused.
     */
    @JvmStatic
    public fun overwriteNonAtomic(uri: Uri): OutputTarget =
        OutputTarget.PlatformTarget.create(AndroidTargetValue(uri, true))
}
