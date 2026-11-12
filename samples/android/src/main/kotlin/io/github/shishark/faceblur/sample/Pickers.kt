// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.MediaStore

private const val PICKER_EXTENSION = 2

/** The system photo picker where the system offers it, else the document picker filtered to images. */
@Suppress("DEPRECATION")
internal fun Activity.pickPhoto(requestCode: Int) {
    val photoPicker = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= PICKER_EXTENSION
    if (photoPicker) {
        try {
            startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), requestCode)
            return
        } catch (e: ActivityNotFoundException) {
            // Fall through to the document picker.
        }
    }
    startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
        requestCode,
    )
}
