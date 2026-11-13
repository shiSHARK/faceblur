// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The screen with a test engine (the library is covered by its own tests): what the person sees and can press. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityTest {
    private val engine = UriFakeEngine()
    private lateinit var controller: DemoController<Uri>
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication() as DemoApp
        controller = DemoController(engine, DirectExecutor) { it.run() }
        app.controller = controller
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    private fun <T : View> find(id: Int): T = activity.findViewById(id)

    /** Robolectric does not lay the list out by itself: do it, so that the header and the rows exist as views. */
    private fun laidOutList(): ListView = find<ListView>(R.id.review_list).also {
        it.measure(
            View.MeasureSpec.makeMeasureSpec(1_000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(5_000, View.MeasureSpec.EXACTLY),
        )
        it.layout(0, 0, 1_000, 5_000)
    }

    private fun header(): View = laidOutList().getChildAt(0) ?: error("header not laid out")

    private fun toReview() {
        controller.chooseModel(Uri.parse("content://m/model"))
        controller.useModel()
        controller.choosePhoto(Uri.parse("content://p/photo"))
    }

    private fun allViews(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(allViews(root.getChildAt(i)))
    }

    @Test
    fun `before a model is loaded the screen says choose a model first and the photo steps are off`() {
        assertEquals("Choose a model first", find<TextView>(R.id.status).text.toString())
        assertFalse(find<Button>(R.id.choose_photo).isEnabled)
        assertTrue(find<Button>(R.id.choose_model).isEnabled)
        assertEquals(View.VISIBLE, find<View>(R.id.home).visibility)
        assertEquals(View.GONE, find<View>(R.id.model_confirm).visibility)
    }

    @Test
    fun `the pipeline screen is offered only once a model is in use`() {
        assertFalse(find<Button>(R.id.open_pipeline).isEnabled)
        toReview()
        controller.reset()
        assertTrue(find<Button>(R.id.open_pipeline).isEnabled)
        controller.setOutput(OutputOption.LONG_SIDE_2048)
        find<Button>(R.id.open_pipeline).performClick()
        val started = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(PipelineActivity::class.java.name, started.component?.className)
        assertEquals("LONG_SIDE_2048", started.getStringExtra(PipelineActivity.EXTRA_OUTPUT))
    }

    @Test
    fun `the model confirmation shows name, size and the hash in four lines of sixteen`() {
        controller.chooseModel(Uri.parse("content://m/model"))
        assertEquals(View.VISIBLE, find<View>(R.id.model_confirm).visibility)
        assertEquals("File name: model.onnx", find<TextView>(R.id.model_name).text.toString())
        val hashViews = listOf(R.id.model_hash_1, R.id.model_hash_2, R.id.model_hash_3, R.id.model_hash_4)
            .map { find<TextView>(it) }
        val lines = hashViews.map { it.text.toString() }
        assertEquals(4, lines.size)
        assertEquals(
            "SHA-256 line 2 of 4: " + lines[1].toCharArray().joinToString(" "),
            hashViews[1].contentDescription,
        )
        assertTrue(lines.all { it.length == 16 })
        assertFalse(find<Button>(R.id.choose_photo).isEnabled)
        find<Button>(R.id.use_model).performClick()
        assertTrue(find<Button>(R.id.choose_photo).isEnabled)
        assertEquals(View.GONE, find<View>(R.id.model_confirm).visibility)
    }

    @Test
    fun `cancelling the confirmation loads nothing`() {
        controller.chooseModel(Uri.parse("content://m/model"))
        find<Button>(R.id.cancel_model).performClick()
        assertFalse(find<Button>(R.id.choose_photo).isEnabled)
        assertFalse(engine.calls.any { it.startsWith("loadModel") })
    }

    @Test
    fun `a detected photo shows counts, the list of masks and the limits copy`() {
        toReview()
        assertEquals(View.VISIBLE, find<View>(R.id.review_screen).visibility)
        assertEquals(View.GONE, find<View>(R.id.home).visibility)
        val list = find<ListView>(R.id.review_list)
        assertEquals(1, list.count - list.headerViewsCount - list.footerViewsCount)
        assertEquals("1 face detected, 1 mask", header().findViewById<TextView>(R.id.counts).text.toString())
        assertTrue(find<ImageView>(R.id.preview).contentDescription.toString().contains("1 face"))
        assertTrue(
            allViews(find(R.id.root)).filterIsInstance<TextView>().any {
                it.text.startsWith("Detection can miss faces")
            },
        )
    }

    @Test
    fun `keep unmasked removes the row, undo brings it back and the status announces it`() {
        toReview()
        val list = laidOutList()
        val row = list.getChildAt(list.headerViewsCount)
        val remove = row.findViewById<Button>(R.id.row_remove)
        assertEquals("Keep unmasked", remove.text.toString())
        assertEquals("Keep mask number 1 unmasked", remove.contentDescription.toString())
        remove.performClick()
        assertTrue(find<TextView>(R.id.status).text.startsWith("Mask kept unmasked"))
        assertEquals(View.VISIBLE, header().findViewById<View>(R.id.no_masks).visibility)
        header().findViewById<Button>(R.id.undo).performClick()
        assertTrue(find<TextView>(R.id.status).text.startsWith("Last change undone"))
        assertEquals(View.GONE, header().findViewById<View>(R.id.no_masks).visibility)
    }

    @Test
    fun `adding a mask with the buttons and saving writes the edited list and shows the result`() {
        toReview()
        val h = header()
        h.findViewById<Button>(R.id.add_mask).performClick()
        assertEquals(View.VISIBLE, h.findViewById<View>(R.id.placing).visibility)
        assertFalse(h.findViewById<Button>(R.id.add_mask).isEnabled)
        listOf(R.id.move_left, R.id.move_right, R.id.move_up, R.id.move_down, R.id.smaller, R.id.larger).forEach {
            h.findViewById<Button>(it).performClick()
        }
        h.findViewById<Button>(R.id.place_mask).performClick()
        assertEquals("2 masks", h.findViewById<TextView>(R.id.counts).text.toString().substringAfter(", "))
        h.findViewById<Button>(R.id.add_mask).performClick()
        h.findViewById<Button>(R.id.cancel_placing).performClick()
        assertEquals(View.GONE, h.findViewById<View>(R.id.placing).visibility)

        controller.save(Uri.parse("content://t/target"))
        assertEquals(2, engine.savedMasks?.size)
        assertEquals(View.VISIBLE, find<View>(R.id.result).visibility)
        assertTrue(find<ImageView>(R.id.result_image).contentDescription.toString().startsWith("Saved photo."))
        assertTrue(find<TextView>(R.id.status).text.startsWith("Saved."))
        find<Button>(R.id.result_again).performClick()
        assertEquals(View.VISIBLE, find<View>(R.id.home).visibility)
    }

    @Test
    fun `the limit error shows the library text unmodified and no try again`() {
        engine.analyseFailure = DemoFailure(FailureKind.TOO_MANY_MASKS, "Model output invalid: TOO_MANY_MASKS. details")
        controller.chooseModel(Uri.parse("content://m/model"))
        controller.useModel()
        controller.choosePhoto(Uri.parse("content://p/photo"))
        assertEquals(View.VISIBLE, find<View>(R.id.error_block).visibility)
        assertEquals(
            "Model output invalid: TOO_MANY_MASKS. details",
            find<TextView>(R.id.error_details).text.toString(),
        )
        assertEquals(View.GONE, find<View>(R.id.retry).visibility)
        assertTrue(find<Button>(R.id.choose_photo).isEnabled)
    }

    @Test
    fun `not enough memory keeps try again and choose photo`() {
        engine.analyseFailure = DemoFailure(FailureKind.NOT_ENOUGH_MEMORY)
        controller.chooseModel(Uri.parse("content://m/model"))
        controller.useModel()
        controller.choosePhoto(Uri.parse("content://p/photo"))
        assertEquals(View.VISIBLE, find<View>(R.id.retry).visibility)
        assertEquals(View.GONE, find<View>(R.id.error_details).visibility)
        engine.analyseFailure = null
        find<Button>(R.id.retry).performClick()
        assertEquals(View.VISIBLE, find<View>(R.id.review_screen).visibility)
        assertEquals(View.GONE, find<View>(R.id.error_block).visibility)
    }

    private fun secure() = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test
    fun `the window is secure exactly while a photo is shown`() {
        val queue = ArrayDeque<Runnable>()
        controller = DemoController(engine, DirectExecutor) { queue.add(it) }
        (RuntimeEnvironment.getApplication() as DemoApp).controller = controller
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertFalse(secure(), "home, no photo")
        controller.chooseModel(Uri.parse("content://m/model"))
        queue.removeFirst().run()
        assertFalse(secure(), "model confirmation shows no photo")
        controller.useModel()
        queue.removeFirst().run()
        controller.choosePhoto(Uri.parse("content://p/photo"))
        assertFalse(secure(), "detecting: no photo on the screen yet")
        queue.removeFirst().run()
        assertTrue(find<View>(R.id.review_screen).visibility == View.VISIBLE)
        assertTrue(secure(), "reviewing")
        controller.save(Uri.parse("content://t/target"))
        assertTrue(secure(), "saving")
        queue.removeFirst().run()
        assertEquals(View.VISIBLE, find<View>(R.id.result).visibility)
        assertTrue(secure(), "result")
        find<Button>(R.id.result_again).performClick()
        assertEquals(View.VISIBLE, find<View>(R.id.home).visibility)
        assertFalse(secure(), "back on the home screen")
    }

    @Test
    fun `starting over from the review clears the secure flag`() {
        toReview()
        assertTrue(secure())
        controller.reset()
        assertFalse(secure())
    }

    @Test
    fun `a busy run shows cancel and the model loading hint is not a cancel button`() {
        val queue = ArrayDeque<Runnable>()
        controller = DemoController(engine, DirectExecutor) { queue.add(it) }
        (RuntimeEnvironment.getApplication() as DemoApp).controller = controller
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        controller.chooseModel(Uri.parse("content://m/model"))
        assertEquals(View.VISIBLE, find<View>(R.id.busy_row).visibility)
        assertEquals(View.VISIBLE, find<View>(R.id.busy_hint).visibility)
        assertFalse(find<Button>(R.id.cancel_run).isEnabled)
        queue.removeFirst().run()
        controller.useModel()
        queue.removeFirst().run()
        controller.choosePhoto(Uri.parse("content://p/photo"))
        assertEquals(View.VISIBLE, find<View>(R.id.busy_row).visibility)
        assertTrue(find<Button>(R.id.cancel_run).isEnabled)
        find<Button>(R.id.cancel_run).performClick()
        assertFalse(find<Button>(R.id.cancel_run).isEnabled)
    }

    @Test
    fun `every button is at least 48dp high, every image has a description and the status is a live region`() {
        toReview()
        val min = 48 * activity.resources.displayMetrics.density
        val views = allViews(find(R.id.root)).toList()
        for (b in views.filterIsInstance<Button>()) {
            assertTrue(
                b.minHeight >= min.toInt() - 1 || b.minimumHeight >= min.toInt() - 1,
                "touch target of ${b.text}",
            )
            assertTrue(b.text.isNotBlank() || !b.contentDescription.isNullOrBlank())
        }
        for (i in views.filterIsInstance<ImageView>()) assertNotNull(i.contentDescription, "image description")
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, find<TextView>(R.id.status).accessibilityLiveRegion)
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            find<View>(R.id.overlay).importantForAccessibility,
        )
    }

    @Test
    fun `body text uses the primary text colour and the three screens carry pane titles`() {
        val primary = activity.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary)).use {
            it.getColorStateList(0)?.defaultColor
        }
        assertEquals(primary, find<TextView>(R.id.limits_copy).textColors.defaultColor)
        assertEquals(primary, find<TextView>(R.id.model_note).textColors.defaultColor)
        assertEquals("Start screen", find<View>(R.id.home).accessibilityPaneTitle)
        assertEquals("Review screen", find<View>(R.id.review_screen).accessibilityPaneTitle)
        assertEquals("Result screen", find<View>(R.id.result).accessibilityPaneTitle)
    }

    @Test
    fun `the preview stays outside the scrolling list so it is on screen while a mask is placed`() {
        toReview()
        val list = find<ListView>(R.id.review_list)
        val preview = find<View>(R.id.preview)
        var parent = preview.parent
        while (parent != null && parent !== list) parent = parent.parent
        assertEquals(null, parent, "the preview must not scroll with the list")
        // The Move and Resize buttons are in the list header: the preview and they share the screen.
        assertEquals(R.id.review_screen, (list.parent as View).id)
        assertEquals(R.id.review_screen, (preview.parent.parent.parent as View).id)
    }

    @Test
    fun `moving and resizing say what changed and where the mask is, and the preview description follows`() {
        toReview()
        val h = header()
        h.findViewById<Button>(R.id.add_mask).performClick()
        assertTrue(find<ImageView>(R.id.preview).contentDescription.toString().contains("A new mask is being placed"))
        val before = find<TextView>(R.id.status).text.toString()
        h.findViewById<Button>(R.id.move_right).performClick()
        val moved = find<TextView>(R.id.status).text.toString()
        assertTrue(moved.startsWith("Mask moved. Centre 52% across and 50% down"), moved)
        assertTrue(before != moved)
        h.findViewById<Button>(R.id.larger).performClick()
        assertTrue(find<TextView>(R.id.status).text.startsWith("Mask size changed. Centre 52% across"))
        repeat(60) { h.findViewById<Button>(R.id.move_up).performClick() }
        assertTrue(find<TextView>(R.id.status).text.startsWith("The mask cannot move further that way. Centre"))
        repeat(60) { h.findViewById<Button>(R.id.smaller).performClick() }
        assertTrue(find<TextView>(R.id.status).text.startsWith("The mask cannot be resized further."))
        assertTrue(find<ImageView>(R.id.preview).contentDescription.toString().contains("Centre"))
    }

    /**
     * Records the TYPE_VIEW_FOCUSED events these views receive (what moves a screen reader's focus). The framework
     * itself sends one more when a button first becomes visible and takes the input focus, so [distinctRuns] drops
     * immediate repeats.
     */
    private fun trackFocus(vararg ids: Int): MutableList<Int> {
        val got = mutableListOf<Int>()
        val h = header()
        for (id in ids) {
            val view = h.findViewById<View>(id) ?: find(id)
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun sendAccessibilityEvent(host: View, eventType: Int) {
                    if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) got += host.id
                }
            }
        }
        return got
    }

    private fun List<Int>.distinctRuns(): List<Int> = fold(emptyList()) { acc, id ->
        if (acc.lastOrNull() ==
            id
        ) {
            acc
        } else {
            acc + id
        }
    }

    private fun idle() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test
    fun `focus follows the screen, the placing panel and the controls that disappear`() {
        val focused = trackFocus(
            R.id.title,
            R.id.review_heading,
            R.id.result_heading,
            R.id.placing_heading,
            R.id.add_mask,
            R.id.counts,
            R.id.cancel_run,
            R.id.status,
        )
        idle()
        assertTrue(focused.isEmpty(), "nothing moves on the first state")
        toReview()
        idle()
        assertEquals(listOf(R.id.cancel_run, R.id.review_heading), focused.distinctRuns())
        focused.clear()

        val h = header()
        h.findViewById<Button>(R.id.add_mask).performClick()
        idle()
        assertEquals(listOf(R.id.placing_heading), focused.distinctRuns())
        focused.clear()
        h.findViewById<Button>(R.id.move_left).performClick()
        idle()
        // A move is announced by the status line and the focus stays on the button. (The framework may send its own
        // event for the disabled "Add a mask" button; the screen sends none.)
        assertTrue(focused.all { it == R.id.add_mask }, "a move must not move the focus: $focused")
        h.findViewById<Button>(R.id.place_mask).performClick()
        idle()
        assertEquals(listOf(R.id.add_mask), focused.distinctRuns())
        focused.clear()

        val list = laidOutList()
        list.getChildAt(list.headerViewsCount).findViewById<Button>(R.id.row_remove).performClick()
        idle()
        // The framework may add its own event for the re-enabled "Add a mask" button; the screen's is the last one.
        assertEquals(R.id.counts, focused.last())
        focused.clear()

        controller.save(Uri.parse("content://t/target"))
        idle()
        assertEquals(listOf(R.id.cancel_run, R.id.result_heading), focused.distinctRuns())
        focused.clear()
        find<Button>(R.id.result_again).performClick()
        idle()
        assertEquals(listOf(R.id.title), focused.distinctRuns())
    }

    @Test
    fun `after a failed detection the focus goes to the status line that says why`() {
        val focused = trackFocus(R.id.status, R.id.cancel_run)
        engine.analyseFailure = DemoFailure(FailureKind.IO)
        controller.chooseModel(Uri.parse("content://m/model"))
        controller.useModel()
        controller.choosePhoto(Uri.parse("content://p/photo"))
        idle()
        assertEquals(listOf(R.id.cancel_run, R.id.status), focused.distinctRuns())
        assertEquals(View.VISIBLE, find<View>(R.id.error_block).visibility)
    }

    @Test
    fun `an error is said once by the status line and not a second time by the error block`() {
        engine.analyseFailure = DemoFailure(FailureKind.TOO_MANY_MASKS, "details")
        controller.chooseModel(Uri.parse("content://m/model"))
        controller.useModel()
        controller.choosePhoto(Uri.parse("content://p/photo"))
        val sentence = find<TextView>(R.id.status).text.toString()
        val sentences = allViews(find(R.id.root)).filterIsInstance<TextView>()
            .filter { it.visibility == View.VISIBLE && it.text.toString() == sentence }
        assertEquals(1, sentences.count())
    }

    @Test
    fun `the overlay draws faces, masks and the mask being placed`() {
        val overlay = OverlayView(activity)
        overlay.layout(0, 0, 400, 300)
        val item = MaskItem(0, Origin.DETECTED, MaskForm.ELLIPSE, 100f, 100f, 40f, 50f, Any())
        val rect = MaskItem(1, Origin.ADDED, MaskForm.RECTANGLE, 300f, 200f, 40f, 30f, Any())
        overlay.scene = OverlayScene(
            800,
            600,
            listOf(FaceView(0, Box(60f, 50f, 140f, 150f), 0.9f)),
            listOf(item, rect),
            Box(10f, 10f, 80f, 80f),
        )
        overlay.draw(Canvas(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)))
        overlay.scene = null
        overlay.draw(Canvas(Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)))
    }
}

/** The fake engine of the controller tests, with Uri handles. */
class UriFakeEngine : Engine<Uri> {
    private val inner = FakeEngine()
    val calls get() = inner.calls
    val savedMasks get() = inner.savedMasks
    var analyseFailure: DemoFailure?
        get() = inner.analyseFailure
        set(value) {
            inner.analyseFailure = value
        }

    override fun readModel(source: Uri) = inner.readModel(source.toString())

    override fun loadModel(candidate: ModelCandidate) = inner.loadModel(candidate)

    override fun analyse(source: Uri, output: OutputOption, cancelled: () -> Boolean) =
        inner.analyse(source.toString(), output, cancelled)

    override fun manualMask(analysis: Analysis, id: Int, box: Box) = inner.manualMask(analysis, id, box)

    override fun save(
        source: Uri,
        target: Uri,
        analysis: Analysis,
        masks: List<MaskItem>,
        output: OutputOption,
        cancelled: () -> Boolean,
    ) = inner.save(source.toString(), target.toString(), analysis, masks, output, cancelled)
}
