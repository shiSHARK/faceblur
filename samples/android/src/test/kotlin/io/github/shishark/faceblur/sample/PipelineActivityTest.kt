// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pipeline screen with a test engine: states, the secure window and the accessibility basics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PipelineActivityTest {
    private class Engine : PipelineEngine<Uri> {
        val calls = mutableListOf<Pair<Uri, OutputOption>>()
        var failure: DemoFailure? = null

        override fun run(source: Uri, output: OutputOption, cancelled: () -> Boolean): PipelineOutcome {
            calls += source to output
            failure?.let { throw it }
            return PipelineOutcome(2, 2, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        }
    }

    private val engine = Engine()
    private val queue = ArrayDeque<Runnable>()
    private lateinit var pipeline: PipelineController<Uri>
    private lateinit var activity: PipelineActivity

    @Before
    fun setUp() {
        pipeline = PipelineController(engine, DirectExecutor) { queue.add(it) }
        (RuntimeEnvironment.getApplication() as DemoApp).pipeline = pipeline
        activity = Robolectric.buildActivity(
            PipelineActivity::class.java,
            Intent().putExtra(PipelineActivity.EXTRA_OUTPUT, OutputOption.LONG_SIDE_1024.name),
        ).setup().get()
    }

    private fun <T : View> find(id: Int): T = activity.findViewById(id)

    /** Presses "Choose photo" and lets the system picker return [uri] (null: the person went back). */
    private fun pick(uri: String?, resultCode: Int = android.app.Activity.RESULT_OK) {
        find<Button>(R.id.pipeline_choose).performClick()
        val shadow = shadowOf(activity)
        val picker = shadow.nextStartedActivityForResult
        shadow.receiveResult(picker.intent, resultCode, uri?.let { Intent().setData(Uri.parse(it)) })
    }

    @Test
    fun `the window is secure and the idle screen offers the photo choice`() {
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertEquals("Choose a photo to run the pipeline", find<TextView>(R.id.pipeline_status).text.toString())
        assertEquals(View.VISIBLE, find<View>(R.id.pipeline_choose).visibility)
        assertEquals(View.GONE, find<View>(R.id.pipeline_result).visibility)
        assertTrue(find<TextView>(R.id.pipeline_steps).text.contains("encode to JPEG once"))
    }

    @Test
    fun `a chosen photo runs with the size choice from the main screen and shows the result`() {
        pick("content://p/photo")
        assertEquals(View.VISIBLE, find<View>(R.id.pipeline_busy).visibility)
        assertFalse(find<Button>(R.id.pipeline_choose).isEnabled)
        queue.removeFirst().run()
        assertEquals(OutputOption.LONG_SIDE_1024, engine.calls.single().second)
        assertEquals(View.VISIBLE, find<View>(R.id.pipeline_result).visibility)
        assertEquals(View.GONE, find<View>(R.id.pipeline_busy).visibility)
        assertEquals("2 faces detected, 2 masks", find<TextView>(R.id.pipeline_counts).text.toString())
        assertTrue(find<TextView>(R.id.pipeline_status).text.endsWith("Nothing was saved."))
        assertTrue(find<ImageView>(R.id.pipeline_image).contentDescription.toString().startsWith("Pipeline result."))
        assertTrue(
            find<TextView>(R.id.pipeline_status).accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE,
        )
        find<Button>(R.id.pipeline_again).performClick()
        assertEquals(View.GONE, find<View>(R.id.pipeline_result).visibility)
        assertEquals(View.VISIBLE, find<View>(R.id.pipeline_choose).visibility)
    }

    @Test
    fun `a failure is told in words and offers to start over`() {
        engine.failure = DemoFailure(FailureKind.NOT_ENOUGH_MEMORY)
        pick("content://p/photo")
        queue.removeFirst().run()
        assertTrue(find<TextView>(R.id.pipeline_status).text.startsWith("Not enough memory"))
        assertEquals(View.VISIBLE, find<View>(R.id.pipeline_again).visibility)
        assertEquals(View.GONE, find<View>(R.id.pipeline_result).visibility)
    }

    @Test
    fun `cancel is available while running, buttons are at least 48dp and images have descriptions`() {
        pick("content://p/photo")
        assertTrue(find<Button>(R.id.pipeline_cancel).isEnabled)
        find<Button>(R.id.pipeline_cancel).performClick()
        queue.removeFirst().run()
        val min = (48 * activity.resources.displayMetrics.density).toInt() - 1
        listOf(R.id.pipeline_choose, R.id.pipeline_cancel, R.id.pipeline_again).forEach {
            val button = find<Button>(it)
            assertTrue(button.minHeight >= min || button.minimumHeight >= min, "touch target of button $it")
        }
        assertTrue(!find<ImageView>(R.id.pipeline_image).contentDescription.isNullOrBlank())
    }

    @Test
    fun `going back from the picker or a result without a photo starts nothing`() {
        pick(null, android.app.Activity.RESULT_CANCELED)
        pick(null)
        assertTrue(engine.calls.isEmpty())
        assertEquals(View.GONE, find<View>(R.id.pipeline_busy).visibility)
    }

    @Test
    fun `finishing the screen drops the result`() {
        val controller = Robolectric.buildActivity(PipelineActivity::class.java).setup()
        pipeline.start(Uri.parse("content://p/photo"), OutputOption.SAME_SIZE)
        queue.removeFirst().run()
        assertTrue(pipeline.state is PipelineState.Done)
        controller.get().finish()
        controller.pause().stop().destroy()
        assertTrue(pipeline.state is PipelineState.Idle)
    }
}
