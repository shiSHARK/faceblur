// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import io.github.shishark.faceblur.FaceMaskException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A document provider that records delete calls (the part of a provider the demo uses); it can be told to refuse. */
class RecordingProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == DELETE_METHOD) {
            if (refuse) throw IllegalStateException("refused")
            deleted += extras!!.getParcelable<Uri>(DELETE_URI_EXTRA)!!
        }
        return Bundle()
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0

    companion object {
        // The framework's own names for the delete call (hidden constants of DocumentsContract).
        const val DELETE_METHOD = "android:deleteDocument"
        const val DELETE_URI_EXTRA = "uri"
        val deleted = mutableListOf<Uri>()
        var refuse = false
    }
}

/**
 * The privacy-relevant part of "save": after a failed save into a new document, that document is removed, unless the
 * library refused a non-empty target (then the file was not created by the demo and is left alone).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SaveCleanupTest {
    private val target = Uri.parse("content://faceblur.test.documents/document/1")

    private fun fail(type: Class<out FaceMaskException>, removed: MutableList<Uri>, removeResult: Boolean = true) =
        assertFailsWith<DemoFailure> {
            cleanUpAfterFailure<Unit>(target, {
                removed += it
                removeResult
            }) { throw libraryException(type) }
        }

    @Test
    fun `any library failure removes the document the save created`() {
        listOf(
            FaceMaskException.IoFailed::class.java,
            FaceMaskException.Cancelled::class.java,
            FaceMaskException.NotEnoughMemory::class.java,
            FaceMaskException.UnsupportedImage::class.java,
            FaceMaskException.SourceChanged::class.java,
        ).forEach { type ->
            val removed = mutableListOf<Uri>()
            val failure = fail(type, removed)
            assertEquals(listOf(target), removed, type.simpleName)
            assertFalse(failure.leftover, type.simpleName)
        }
    }

    @Test
    fun `a failure that is not a library error removes it too`() {
        val removed = mutableListOf<Uri>()
        val failure = assertFailsWith<DemoFailure> {
            cleanUpAfterFailure<Unit>(target, {
                removed += it
                true
            }) { throw IllegalStateException("x") }
        }
        assertEquals(listOf(target), removed)
        assertEquals(FailureKind.OTHER, failure.kind)
    }

    @Test
    fun `a refused non-empty target is not deleted`() {
        val removed = mutableListOf<Uri>()
        val failure = fail(FaceMaskException.UnsupportedTarget::class.java, removed)
        assertTrue(removed.isEmpty())
        assertEquals(FailureKind.TARGET_REFUSED, failure.kind)
        assertFalse(failure.leftover)
    }

    @Test
    fun `when the document cannot be removed the failure says a file is left over`() {
        val removed = mutableListOf<Uri>()
        val failure = fail(FaceMaskException.IoFailed::class.java, removed, removeResult = false)
        assertEquals(listOf(target), removed)
        assertTrue(failure.leftover)
    }

    @Test
    fun `a successful save removes nothing`() {
        val removed = mutableListOf<Uri>()
        assertEquals(
            7,
            cleanUpAfterFailure(target, {
                removed += it
                true
            }) { 7 },
        )
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `removeDocument asks the provider to delete the document and reports a refusal`() {
        val authority = "faceblur.test.documents"
        Robolectric.buildContentProvider(RecordingProvider::class.java).create(authority)
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        RecordingProvider.deleted.clear()
        RecordingProvider.refuse = false
        assertTrue(removeDocument(resolver, target))
        assertEquals(listOf(target), RecordingProvider.deleted)
        RecordingProvider.refuse = true
        assertFalse(removeDocument(resolver, target))
        RecordingProvider.refuse = false
    }
}
