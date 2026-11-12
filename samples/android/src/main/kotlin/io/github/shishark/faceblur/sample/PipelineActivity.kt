// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/**
 * The in-memory pipeline screen (ST-023): choose a photo, the app decodes, masks the bitmap in memory with the
 * library, scales, draws a watermark and encodes once. The result is shown from memory; nothing is saved. The window
 * carries FLAG_SECURE for the whole life of this screen: the only photo it shows is a result, and the screen never
 * needs to appear in recent apps or screenshots.
 */
class PipelineActivity : Activity() {
    private lateinit var pipeline: PipelineController<android.net.Uri>
    private lateinit var texts: Texts

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_pipeline)
        pipeline = (application as DemoApp).pipeline
        texts = Texts(resources)
        findViewById<View>(R.id.pipeline_root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val side = (SIDE_PADDING_DP * resources.displayMetrics.density).toInt()
            view.setPadding(bars.left + side, bars.top, bars.right + side, bars.bottom)
            WindowInsets.CONSUMED
        }
        findViewById<Button>(R.id.pipeline_choose).setOnClickListener { pickPhoto(REQUEST_PHOTO) }
        findViewById<Button>(R.id.pipeline_cancel).setOnClickListener { pipeline.cancelRun() }
        findViewById<Button>(R.id.pipeline_again).setOnClickListener { pipeline.reset() }
    }

    override fun onStart() {
        super.onStart()
        pipeline.setListener { render(it) }
    }

    override fun onStop() {
        pipeline.setListener(null)
        super.onStop()
    }

    override fun onDestroy() {
        // Leaving the screen discards a finished result; a running pipeline is asked to stop.
        if (isFinishing) pipeline.reset()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_PHOTO || resultCode != RESULT_OK || uri == null) return
        val output = runCatching { OutputOption.valueOf(intent.getStringExtra(EXTRA_OUTPUT).orEmpty()) }
            .getOrDefault(OutputOption.SAME_SIZE)
        pipeline.start(uri, output)
    }

    private fun render(state: PipelineState) {
        val running = state is PipelineState.Running
        val done = state as? PipelineState.Done
        val failed = state as? PipelineState.Failed
        findViewById<TextView>(R.id.pipeline_status).text = when (state) {
            PipelineState.Idle -> getString(R.string.pipeline_status_idle)

            PipelineState.Running -> getString(R.string.pipeline_status_running)

            is PipelineState.Done -> getString(
                R.string.pipeline_status_done,
                texts.counts(state.outcome.faces, state.outcome.masks),
            )

            is PipelineState.Failed -> texts.failureTitle(state.failure)
        }
        findViewById<View>(R.id.pipeline_busy).visibility = visible(running)
        findViewById<Button>(R.id.pipeline_choose).apply {
            visibility = visible(done == null)
            isEnabled = !running
        }
        findViewById<View>(R.id.pipeline_result).visibility = visible(done != null)
        if (done != null) {
            val counts = texts.counts(done.outcome.faces, done.outcome.masks)
            findViewById<ImageView>(R.id.pipeline_image).apply {
                setImageBitmap(done.outcome.picture as? Bitmap)
                contentDescription = getString(R.string.pipeline_result_description, counts)
            }
            findViewById<TextView>(R.id.pipeline_counts).text = counts
        } else {
            findViewById<ImageView>(R.id.pipeline_image).setImageBitmap(null)
        }
        val body = failed?.let { texts.failureBody(it.failure) }
        findViewById<TextView>(R.id.pipeline_error_body).apply {
            text = body
            visibility = visible(body != null)
        }
        findViewById<Button>(R.id.pipeline_again).visibility = visible(done != null || failed != null)
    }

    private fun visible(show: Boolean) = if (show) View.VISIBLE else View.GONE

    companion object {
        /** The name of the size choice (an `OutputOption`) that the main screen hands over. */
        const val EXTRA_OUTPUT = "output"
        private const val REQUEST_PHOTO = 1
        private const val SIDE_PADDING_DP = 16
    }
}
