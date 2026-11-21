// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import kotlin.math.abs

/**
 * The demo app on the phone, end to end, on one registered public fixture photo (a pre-1900 photograph with one face,
 * `testdata/pd/pd-p06-single-face-s100-x00200-y01600.png`) and the pinned reference model. The system pickers are
 * replaced by their result: each tap on a picker button is answered with the `content:` URI that the picker would
 * return, so the demo's own `onActivityResult` path runs (nothing is called around the screen). The URIs belong to
 * [FixtureProvider] of the test APK, which serves the files that scripts/phone-device-tests.sh placed in the test
 * package's private storage. No photo of any other source is accepted: the fixture and the model are checked by
 * SHA-256 before the demo sees them.
 *
 * The test asserts that the save succeeds, that the one face found is covered by exactly one mask, that the export is a
 * JPEG without EXIF location data, XMP, a second image or a gain map (and with an opaque mask), and that the app left
 * nothing behind: no new file anywhere in its own storage, only the export in the provider's folder. It removes the
 * export at the end.
 */
class DemoPhoneTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val resolver = app.contentResolver
    private var activity: MainActivity? = null

    @After
    fun cleanUp() {
        runCatching { resolver.call(FixtureProvider.base(), FixtureProvider.REMOVE, EXPORT_NAME, null) }
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        instrumentation.runOnMainSync { (app.applicationContext as DemoApp).controller.reset() }
    }

    @Test
    fun maskAndSaveTheFixturePhotoLeavesOnlyTheExport() {
        val provided = resolver.call(FixtureProvider.base(), FixtureProvider.LIST, null, null)
        val model = only(provided?.getStringArray(FixtureProvider.INPUT_DIR), ".onnx")
        val photo = only(provided?.getStringArray(FixtureProvider.INPUT_DIR), ".png")
        assertEquals("fixture photo (only the registered public fixture is accepted)", PHOTO_NAME, photo)
        assertEquals("the photo fixture", PHOTO_SHA256, sha256(FixtureProvider.uri(FixtureProvider.INPUT_DIR, photo)))
        assertEquals("the model fixture", MODEL_SHA256, sha256(FixtureProvider.uri(FixtureProvider.INPUT_DIR, model)))
        assertEquals("export folder at the start", emptyList<String>(), provided?.getStringArray("out")?.toList())
        resolver.call(FixtureProvider.base(), FixtureProvider.PREPARE, EXPORT_NAME, null)
        val export = FixtureProvider.uri(FixtureProvider.OUTPUT_DIR, EXPORT_NAME)
        val before = storage()

        val screen = launch()
        val controller = (app.applicationContext as DemoApp).controller

        // Model step: the picker answers with the model; the person confirms the hash the demo shows.
        pick(documentFilter(Intent.ACTION_OPEN_DOCUMENT, "*/*"), FixtureProvider.INPUT_DIR, model) {
            click(screen, R.id.choose_model)
        }
        waitFor("the model to be read") { controller.state.model is ModelState.Confirm }
        val shown = onMain { (controller.state.model as ModelState.Confirm).candidate }
        assertEquals("the hash the demo asks the person to confirm", MODEL_SHA256, shown.sha256)
        click(screen, R.id.use_model)
        waitFor("the model to load") {
            controller.state.model is ModelState.Ready ||
                controller.state.model is ModelState.Failed
        }
        val loaded = onMain { controller.state.model }
        assertTrue("the model loaded: $loaded", loaded is ModelState.Ready)

        // Photo step: the picker answers with the fixture photo; the demo detects and proposes masks.
        pick(photoPickerFilter(), FixtureProvider.INPUT_DIR, photo) { click(screen, R.id.choose_photo) }
        waitFor("the photo to be analysed") {
            controller.state.photo is PhotoState.Reviewing || controller.state.photo is PhotoState.Failed
        }
        val analysed = onMain { controller.state.photo }
        val reviewing = analysed as? PhotoState.Reviewing
        assertNotNull("the photo was analysed: $analysed", reviewing)
        val review = reviewing!!.review
        assertEquals("faces found in the fixture", EXPECTED_FACES, review.analysis.faces.size)
        assertEquals("masks proposed for the fixture", EXPECTED_MASKS, review.masks.size)
        val texts = Texts(screen.resources)
        assertEquals(texts.counts(EXPECTED_FACES, EXPECTED_MASKS), onMain { textOf(screen, R.id.counts) })

        // Save step: the picker for a new document answers with the (empty) export file.
        pick(documentFilter(Intent.ACTION_CREATE_DOCUMENT, "image/jpeg"), FixtureProvider.OUTPUT_DIR, EXPORT_NAME) {
            click(screen, R.id.apply_save, scrollToFooter = true)
        }
        waitFor("the save to finish") {
            controller.state.photo is PhotoState.Done ||
                controller.state.photo is PhotoState.Failed
        }
        val saved = onMain { controller.state }
        val done = saved.photo as? PhotoState.Done
        assertNotNull("the save succeeded: ${saved.photo} / ${saved.status}", done)
        assertEquals("faces reported after the save", EXPECTED_FACES, done!!.outcome.faces)
        assertEquals("masks applied by the save", EXPECTED_MASKS, done.outcome.masks)
        waitFor("the result screen") {
            textOf(screen, R.id.result_counts) == texts.counts(EXPECTED_FACES, EXPECTED_MASKS)
        }

        // The export: a JPEG with no metadata beyond the allowed shape and no gain map; the face is covered.
        val bytes = resolver.openInputStream(export)!!.use { it.readBytes() }
        val audit = JpegAudit.audit(bytes)
        assertTrue("the export is not private: ${audit.problems} in ${audit.segments}", audit.problems.isEmpty())
        assertCovered(bytes, review)
        val provided2 = resolver.call(FixtureProvider.base(), FixtureProvider.LIST, null, null)
        assertEquals(
            "only the export is in the export folder",
            listOf(EXPORT_NAME),
            provided2?.getStringArray("out")?.toList(),
        )
        assertArrayEquals("the fixtures are unchanged", provided?.getStringArray("in"), provided2?.getStringArray("in"))

        // Nothing left behind: the app's own storage holds exactly what it held before the run.
        assertEquals("files in the app's storage after the run", before, storage())
    }

    // ---- the screen ---------------------------------------------------------------------------------------------

    private fun launch(): MainActivity {
        val intent = Intent(Intent.ACTION_MAIN).setClassName(app.packageName, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = instrumentation.startActivitySync(intent) as MainActivity
        activity = started
        instrumentation.waitForIdleSync()
        return started
    }

    /** Taps the button with [id]; the footer of the review list is scrolled into view first when [scrollToFooter]. */
    private fun click(screen: Activity, id: Int, scrollToFooter: Boolean = false) {
        if (scrollToFooter) {
            val list = screen.findViewById<ListView>(R.id.review_list)
            waitFor("the review list") { list.adapter != null }
            instrumentation.runOnMainSync { list.setSelection(list.count - 1) }
            instrumentation.waitForIdleSync()
        }
        waitFor("the button $id") {
            val button = screen.findViewById<Button>(id)
            button != null && button.isEnabled && button.isShown
        }
        instrumentation.runOnMainSync { screen.findViewById<Button>(id).performClick() }
    }

    /** The text of the view with [id]; only on the main thread (inside [waitFor] or [onMain]). */
    private fun textOf(screen: Activity, id: Int): String = screen.findViewById<TextView>(id)?.text?.toString() ?: ""

    private fun <T> onMain(block: () -> T): T {
        var value: Any? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /**
     * Answers the next picker started with [filter] with the URI of [name] in [folder] instead of showing it, then runs
     * [tap] (the tap that starts the picker) and waits until the picker was asked.
     */
    private fun pick(filter: IntentFilter, folder: String, name: String, tap: () -> Unit) {
        val result = Instrumentation.ActivityResult(
            Activity.RESULT_OK,
            Intent().setData(FixtureProvider.uri(folder, name)),
        )
        val monitor = instrumentation.addMonitor(filter, result, true)
        try {
            tap()
            // A blocked start creates no activity, so the monitor is asked how often it answered.
            waitFor("the picker to be started") { monitor.hits >= 1 }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    /** The system photo picker where the demo uses it, else the document picker (see `Activity.pickPhoto`). */
    private fun photoPickerFilter(): IntentFilter {
        val photoPicker = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU ||
            android.os.ext.SdkExtensions.getExtensionVersion(android.os.Build.VERSION_CODES.R) >= PICKER_EXTENSION
        return if (photoPicker) {
            IntentFilter(MediaStore.ACTION_PICK_IMAGES).apply { addDataType("image/*") }
        } else {
            documentFilter(Intent.ACTION_OPEN_DOCUMENT, "image/*")
        }
    }

    /** The system document picker's intent: an action, the openable category and a type (an intent must match all). */
    private fun documentFilter(action: String, type: String) = IntentFilter(action).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        addDataType(type)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < end) {
            var ok = false
            instrumentation.runOnMainSync { ok = condition() }
            if (ok) return
            Thread.sleep(POLL_MS)
        }
        fail("timed out waiting for $what")
    }

    // ---- the evidence -------------------------------------------------------------------------------------------

    /** Every regular file of the app's own storage (internal and external) with its size: nothing may be added. */
    private fun storage(): List<String> {
        val roots = listOfNotNull(app.dataDir, *app.getExternalFilesDirs(null), *app.externalCacheDirs)
        return roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile }.map { "${it.relativeTo(root)} ${it.length()}" }.toList()
        }.sorted()
    }

    /** The mask is opaque: the middle of the face box is one flat colour in the export. */
    private fun assertCovered(jpeg: ByteArray, review: Review) {
        val image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertNotNull("the export decodes", image)
        assertEquals("width", review.analysis.width, image.width)
        assertEquals("height", review.analysis.height, image.height)
        val face = review.analysis.faces.single().box
        val cx = ((face.left + face.right) / 2).toInt()
        val cy = ((face.top + face.bottom) / 2).toInt()
        val rx = ((face.right - face.left) / INNER_DIVISOR).toInt().coerceAtLeast(1)
        val ry = ((face.bottom - face.top) / INNER_DIVISOR).toInt().coerceAtLeast(1)
        val centre = image.getPixel(cx, cy)
        var worst = 0
        for (y in cy - ry..cy + ry) {
            for (x in cx - rx..cx + rx) {
                val p = image.getPixel(x, y)
                val d = maxOf(
                    abs(((p shr RED) and BYTE) - ((centre shr RED) and BYTE)),
                    abs(((p shr GREEN) and BYTE) - ((centre shr GREEN) and BYTE)),
                    abs((p and BYTE) - (centre and BYTE)),
                )
                worst = maxOf(worst, d)
            }
        }
        android.util.Log.i("DemoPhoneTest", "mask interior: largest colour step $worst")
        assertTrue("the face is covered by one flat colour (largest step $worst)", worst <= FLAT_TOLERANCE)
    }

    private fun only(names: Array<String>?, suffix: String): String {
        val found = names.orEmpty().filter { it.endsWith(suffix) }
        assertEquals("fixtures named *$suffix in ${names?.toList()}", 1, found.size)
        return found.single()
    }

    private fun sha256(uri: Uri): String = MessageDigest.getInstance("SHA-256")
        .digest(resolver.openInputStream(uri)!!.use { it.readBytes() })
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PHOTO_NAME = "pd-p06-single-face-s100-x00200-y01600.png"
        const val PHOTO_SHA256 = "b1377fec3446c8baf4189fbb8c155759ac6c052a6b000fe202e5409c8b8ce77d"
        const val MODEL_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"
        const val EXPORT_NAME = "export.jpg"
        const val EXPECTED_FACES = 1
        const val EXPECTED_MASKS = 1
        const val WAIT_MS = 120_000L
        const val POLL_MS = 100L
        const val PICKER_EXTENSION = 2
        const val INNER_DIVISOR = 6
        const val FLAT_TOLERANCE = 16
        const val RED = 16
        const val GREEN = 8
        const val BYTE = 0xFF
    }
}
