// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** What the overlay draws, in working-image pixels. */
class OverlayScene(
    val imageWidth: Int,
    val imageHeight: Int,
    val faces: List<FaceView>,
    val masks: List<MaskItem>,
    val placing: Box?,
)

/**
 * Draws over the preview: solid yellow boxes with a number for the detected faces, the masks that will be applied as
 * dark shapes with a dashed white outline and their number, and the mask being placed as a thick dashed outline with
 * the word "New". Numbers and line styles carry the information, not colour alone. The view is decoration only
 * (hidden from accessibility); the same information is in the list below it.
 */
class OverlayView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var scene: OverlayScene? = null
        set(value) {
            field = value
            refit()
            invalidate()
        }

    private var fit: ImageFit? = null
    private val tmp = RectF()

    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val boxOutline = stroke(Color.BLACK, 5f)
    private val boxPaint = stroke(Color.rgb(255, 214, 0), 3f)
    private val maskFill = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val maskDark = stroke(Color.BLACK, 5f)
    private val maskEdge = stroke(Color.WHITE, 2f).apply { pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f) }
    private val placingOutline = stroke(Color.BLACK, 8f)
    private val placingEdge = stroke(Color.parseColor("#00E5FF"), 5f).apply {
        pathEffect = DashPathEffect(floatArrayOf(22f, 12f), 0f)
    }
    private val labelBack = Paint().apply { color = Color.BLACK }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = LABEL_SP * scaledDensity
    }

    private fun stroke(argb: Int, dp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        style = Paint.Style.STROKE
        strokeWidth = dp * density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        refit()
    }

    private fun refit() {
        fit = scene?.let { ImageFit(it.imageWidth, it.imageHeight, width, height) }
    }

    override fun onDraw(canvas: Canvas) {
        val s = scene ?: return
        val fit = fit ?: return
        s.masks.forEachIndexed { i, mask ->
            val r = rect(fit, mask.bounds)
            if (mask.form == MaskForm.RECTANGLE) {
                canvas.drawRect(r, maskFill)
                canvas.drawRect(r, maskDark)
                canvas.drawRect(r, maskEdge)
            } else {
                canvas.drawOval(r, maskFill)
                canvas.drawOval(r, maskDark)
                canvas.drawOval(r, maskEdge)
            }
            label(canvas, "M${i + 1}", r.left, r.top)
        }
        for (face in s.faces) {
            val r = rect(fit, face.box)
            canvas.drawRect(r, boxOutline)
            canvas.drawRect(r, boxPaint)
            label(canvas, "F${face.index + 1}", r.left, r.bottom - labelText.textSize - LABEL_PAD * density)
        }
        s.placing?.let {
            val r = rect(fit, it)
            canvas.drawRect(r, placingOutline)
            canvas.drawRect(r, placingEdge)
            label(canvas, resources.getString(R.string.placing_heading), r.left, r.top)
        }
    }

    private fun rect(fit: ImageFit, box: Box): RectF {
        val v = fit.toView(box)
        tmp.set(v.left, v.top, v.right, v.bottom)
        return tmp
    }

    private fun label(canvas: Canvas, text: String, x: Float, y: Float) {
        val pad = LABEL_PAD * density
        val w = labelText.measureText(text)
        val h = labelText.textSize
        canvas.drawRect(x, y, x + w + 2 * pad, y + h + 2 * pad, labelBack)
        canvas.drawText(text, x + pad, y + h + pad - labelText.descent(), labelText)
    }

    private companion object {
        const val LABEL_SP = 12f
        const val LABEL_PAD = 3f
    }
}
