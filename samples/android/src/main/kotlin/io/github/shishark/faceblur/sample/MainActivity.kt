// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView

/**
 * The demo screen. It shows what the controller says and forwards taps; it holds no state of its own. Photo and
 * model are chosen with system pickers (the photo picker where the system has it, the document picker otherwise),
 * the result is written to a document the person creates with the system "save as" screen. Nothing is logged.
 */
class MainActivity : Activity() {
    private lateinit var controller: DemoController<Uri>
    private lateinit var texts: Texts
    private lateinit var header: View
    private lateinit var adapter: MaskAdapter

    private enum class Screen { HOME, REVIEW, RESULT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        controller = (application as DemoApp).controller
        texts = Texts(resources)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val side = (SIDE_PADDING_DP * resources.displayMetrics.density).toInt()
            view.setPadding(bars.left + side, bars.top, bars.right + side, bars.bottom)
            WindowInsets.CONSUMED
        }
        setUpHome()
        setUpReview()
        findViewById<Button>(R.id.result_again).setOnClickListener { controller.reset() }
        findViewById<Button>(R.id.retry).setOnClickListener { controller.retry() }
        findViewById<Button>(R.id.cancel_run).setOnClickListener { controller.cancelRun() }
    }

    override fun onStart() {
        super.onStart()
        controller.setListener { render(it) }
    }

    override fun onStop() {
        controller.setListener(null)
        super.onStop()
    }

    // ---- set-up -----------------------------------------------------------------------------------------------

    private fun setUpHome() {
        findViewById<Button>(R.id.choose_model).setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQUEST_MODEL,
            )
        }
        findViewById<Button>(R.id.use_model).setOnClickListener { controller.useModel() }
        findViewById<Button>(R.id.cancel_model).setOnClickListener { controller.cancelModel() }
        findViewById<Button>(R.id.choose_photo).setOnClickListener { pickPhoto(REQUEST_PHOTO) }
        findViewById<Button>(R.id.open_pipeline).setOnClickListener {
            startActivity(
                Intent(this, PipelineActivity::class.java)
                    .putExtra(PipelineActivity.EXTRA_OUTPUT, controller.state.output.name),
            )
        }
        findViewById<RadioGroup>(R.id.output_group).setOnCheckedChangeListener { _, id ->
            controller.setOutput(
                when (id) {
                    R.id.output_2048 -> OutputOption.LONG_SIDE_2048
                    R.id.output_1024 -> OutputOption.LONG_SIDE_1024
                    else -> OutputOption.SAME_SIZE
                },
            )
        }
    }

    private fun setUpReview() {
        val inflater = LayoutInflater.from(this)
        val list = findViewById<ListView>(R.id.review_list)
        header = inflater.inflate(R.layout.review_header, list, false)
        val footer = inflater.inflate(R.layout.review_footer, list, false)
        list.addHeaderView(header, null, false)
        list.addFooterView(footer, null, false)
        adapter = MaskAdapter(inflater)
        list.adapter = adapter
        header.findViewById<Button>(R.id.add_mask).setOnClickListener { controller.beginAdd() }
        header.findViewById<Button>(R.id.undo).setOnClickListener { controller.undo() }
        header.findViewById<Button>(R.id.move_left).setOnClickListener { controller.movePlacing(-1, 0) }
        header.findViewById<Button>(R.id.move_right).setOnClickListener { controller.movePlacing(1, 0) }
        header.findViewById<Button>(R.id.move_up).setOnClickListener { controller.movePlacing(0, -1) }
        header.findViewById<Button>(R.id.move_down).setOnClickListener { controller.movePlacing(0, 1) }
        header.findViewById<Button>(R.id.smaller).setOnClickListener { controller.resizePlacing(larger = false) }
        header.findViewById<Button>(R.id.larger).setOnClickListener { controller.resizePlacing(larger = true) }
        header.findViewById<Button>(R.id.place_mask).setOnClickListener { controller.confirmAdd() }
        header.findViewById<Button>(R.id.cancel_placing).setOnClickListener { controller.cancelAdd() }
        footer.findViewById<Button>(R.id.apply_save).setOnClickListener { saveAs() }
        footer.findViewById<Button>(R.id.review_again).setOnClickListener { controller.reset() }
    }

    // ---- system pickers ---------------------------------------------------------------------------------------

    /** A new, empty document: the library's file mode writes the masked JPEG into it. */
    private fun saveAs() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_TITLE, getString(R.string.save_file_name)),
            REQUEST_SAVE,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_MODEL -> controller.chooseModel(uri)
            REQUEST_PHOTO -> controller.choosePhoto(uri)
            REQUEST_SAVE -> controller.save(uri)
        }
    }

    // ---- rendering --------------------------------------------------------------------------------------------

    private fun render(state: UiState) {
        val status = findViewById<TextView>(R.id.status)
        val text = texts.status(state.status)
        if (status.text.toString() != text) status.text = text

        val modelBusy = state.model is ModelState.Reading || state.model is ModelState.Loading
        val runBusy = state.photo is PhotoState.Detecting || state.photo is PhotoState.Saving
        findViewById<View>(R.id.busy_row).visibility = visible(modelBusy || runBusy)
        findViewById<Button>(R.id.cancel_run).isEnabled = runBusy && !state.cancelRequested
        findViewById<View>(R.id.busy_hint).visibility = visible(modelBusy)

        renderError(state)
        renderHome(state, modelBusy || runBusy)
        renderReview(state)
        renderResult(state)

        val screen = when (state.photo) {
            is PhotoState.Reviewing, is PhotoState.Saving -> Screen.REVIEW
            is PhotoState.Done -> Screen.RESULT
            else -> Screen.HOME
        }
        // While a photo is on the screen, Android must not keep a snapshot of it (recent apps, screenshots, casting).
        setPhotoShown(screen != Screen.HOME)
        findViewById<View>(R.id.home).visibility = visible(screen == Screen.HOME)
        findViewById<View>(R.id.review_list).visibility = visible(screen == Screen.REVIEW)
        findViewById<View>(R.id.result).visibility = visible(screen == Screen.RESULT)
    }

    private fun renderError(state: UiState) {
        val failure = when (val s = state.status) {
            is Status.Failed -> s.failure
            is Status.ModelFailed -> s.failure
            else -> null
        }
        val block = findViewById<View>(R.id.error_block)
        block.visibility = visible(failure != null)
        if (failure == null) return
        findViewById<TextView>(R.id.error_title).text = texts.failureTitle(failure)
        val body = texts.failureBody(failure)
        findViewById<TextView>(R.id.error_body).apply {
            text = body
            visibility = visible(body != null)
        }
        val details = failure.detail
        findViewById<TextView>(R.id.error_details).apply {
            text = details
            visibility = visible(details != null)
        }
        findViewById<View>(R.id.error_details_label).visibility = visible(details != null)
        // The two limit errors have no "Try again" (it would fail the same way); not-enough-memory keeps it.
        findViewById<View>(R.id.retry).visibility = visible(
            failure.kind == FailureKind.NOT_ENOUGH_MEMORY && state.photo is PhotoState.Failed,
        )
    }

    private fun renderHome(state: UiState, busy: Boolean) {
        val confirm = state.model as? ModelState.Confirm
        findViewById<View>(R.id.model_confirm).visibility = visible(confirm != null)
        if (confirm != null) {
            val candidate = confirm.candidate
            findViewById<TextView>(R.id.model_name).text = getString(R.string.model_name, candidate.name)
            findViewById<TextView>(R.id.model_size).text =
                getString(R.string.model_size, candidate.sizeBytes.toString())
            findViewById<TextView>(R.id.model_hash).apply {
                text = candidate.sha256Lines.joinToString("\n")
                contentDescription = getString(R.string.model_hash_description, candidate.sha256Lines.joinToString(" "))
            }
        }
        findViewById<Button>(R.id.choose_model).apply {
            isEnabled = !busy && confirm == null
            text = if (state.model is ModelState.Ready) {
                getString(R.string.model_ready, state.model.name)
            } else {
                getString(R.string.choose_model)
            }
        }
        findViewById<Button>(R.id.choose_photo).isEnabled = state.photoStepsEnabled
        findViewById<Button>(R.id.open_pipeline).isEnabled = state.photoStepsEnabled
        val group = findViewById<RadioGroup>(R.id.output_group)
        for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = state.photoStepsEnabled
    }

    private fun renderReview(state: UiState) {
        val review = when (val p = state.photo) {
            is PhotoState.Reviewing -> p.review
            is PhotoState.Saving -> p.review
            else -> return
        }
        val saving = state.photo is PhotoState.Saving
        val a = review.analysis
        val counts = texts.counts(a.faces.size, review.masks.size)
        header.findViewById<ImageView>(R.id.preview).apply {
            setImageBitmap(a.preview as? Bitmap)
            contentDescription = getString(R.string.preview_description, counts)
        }
        header.findViewById<OverlayView>(R.id.overlay).scene =
            OverlayScene(a.width, a.height, a.faces, review.masks, review.placing)
        header.findViewById<TextView>(R.id.counts).text = counts
        val scores = texts.scores(a.faces)
        header.findViewById<TextView>(R.id.scores).apply {
            text = scores
            visibility = visible(scores != null)
        }
        header.findViewById<View>(R.id.no_masks).visibility = visible(review.masks.isEmpty())
        val placing = review.placing != null
        header.findViewById<View>(R.id.placing).visibility = visible(placing)
        header.findViewById<Button>(R.id.add_mask).isEnabled = !placing && !saving
        header.findViewById<Button>(R.id.undo).isEnabled = review.undo.isNotEmpty() && !saving
        findViewById<Button>(R.id.apply_save).isEnabled = !placing && !saving
        findViewById<Button>(R.id.review_again).isEnabled = !saving
        adapter.set(review.masks, a.width, a.height, enabled = !saving)
    }

    private fun renderResult(state: UiState) {
        val done = state.photo as? PhotoState.Done ?: return
        val counts = texts.counts(done.outcome.faces, done.outcome.masks)
        findViewById<ImageView>(R.id.result_image).apply {
            setImageBitmap(done.outcome.preview as? Bitmap)
            contentDescription = getString(R.string.result_description, counts)
        }
        findViewById<TextView>(R.id.result_counts).text = counts
    }

    private fun setPhotoShown(shown: Boolean) {
        if (shown) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun visible(show: Boolean) = if (show) View.VISIBLE else View.GONE

    /** One row per mask: title, where it is and one button to take it out. */
    private inner class MaskAdapter(private val inflater: LayoutInflater) : BaseAdapter() {
        private var masks: List<MaskItem> = emptyList()
        private var width = 1
        private var height = 1
        private var enabled = true

        fun set(masks: List<MaskItem>, width: Int, height: Int, enabled: Boolean) {
            this.masks = masks
            this.width = width
            this.height = height
            this.enabled = enabled
            notifyDataSetChanged()
        }

        override fun getCount() = masks.size

        override fun getItem(position: Int) = masks[position]

        override fun getItemId(position: Int) = masks[position].id.toLong()

        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: inflater.inflate(R.layout.row_mask, parent, false)
            val item = masks[position]
            val number = position + 1
            row.findViewById<TextView>(R.id.row_title).text = texts.maskTitle(number, item)
            row.findViewById<TextView>(R.id.row_detail).text = texts.maskDetail(item, width, height)
            row.findViewById<Button>(R.id.row_remove).apply {
                isEnabled = enabled
                if (item.origin == Origin.DETECTED) {
                    text = getString(R.string.keep_unmasked)
                    contentDescription = getString(R.string.keep_unmasked_description, number.toString())
                } else {
                    text = getString(R.string.remove_mask)
                    contentDescription = getString(R.string.remove_added_description, number.toString())
                }
                setOnClickListener { controller.removeMask(item.id) }
            }
            return row
        }
    }

    private companion object {
        const val REQUEST_MODEL = 1
        const val REQUEST_PHOTO = 2
        const val REQUEST_SAVE = 3
        const val SIDE_PADDING_DP = 16
    }
}
