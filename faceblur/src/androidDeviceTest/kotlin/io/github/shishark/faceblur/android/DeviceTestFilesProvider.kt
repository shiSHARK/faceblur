// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * A private provider of files in the test app's own storage (`files/faceblur-scratch/provider/<name>`), so the
 * content-URI forms run through the platform's `ContentResolver` and `ParcelFileDescriptor` modes on the phone without
 * touching its shared storage. [modes] records every open (for "never `w`").
 */
class DeviceTestFilesProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        synchronized(modes) { modes += mode }
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(File(dir(), name), ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0

    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    private fun dir(): File = File(context!!.filesDir, "faceblur-scratch/provider")

    companion object {
        const val AUTHORITY: String = "io.github.shishark.faceblur.test.files"
        val modes: MutableList<String> = ArrayList()

        fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")
    }
}
