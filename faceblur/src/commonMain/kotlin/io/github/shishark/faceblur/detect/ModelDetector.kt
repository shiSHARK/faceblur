// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.detect

import io.github.shishark.faceblur.BoxFormat
import io.github.shishark.faceblur.ColorOrder
import io.github.shishark.faceblur.CoordinateSpace
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.PixelScaling
import io.github.shishark.faceblur.RowLayout
import io.github.shishark.faceblur.TensorLayout
import io.github.shishark.faceblur.geometry.DetectedFace
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.internal.model.ModelLoader
import io.github.shishark.faceblur.internal.model.YuNetShapes
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.SessionTimeout
import io.github.shishark.faceblur.isFatal
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A failed per-pass check: the codes of `ModelOutputInvalid` (ADR 0012 decision 5). [maskCount] and [maskLimit]:
 * with `TOO_MANY_MASKS` from the merge, the number of masks of the plan and the limit it exceeded (for the actionable
 * message, ADR 0012 decision 7). [pairChecks] and [pairCheckLimit]: with `TOO_MANY_NMS_PAIR_CHECKS`, the photo's
 * in-pass NMS pair checks when it stopped (one above the limit) and that limit, for the same kind of message.
 */
internal class OutputCheckFailed(
    val findings: List<String>,
    val maskCount: Long? = null,
    val maskLimit: Int = 0,
    val pairChecks: Long? = null,
    val pairCheckLimit: Long = 0L,
) : RuntimeException("model output check failed")

/**
 * One detector pass with the app's model (ADR 0012 decisions 2 and 5): letterbox (top-left, black padding, never
 * upscaled), the input tensor the profile declares, one runtime run, the output checks, the format's decoder and the
 * in-pass NMS at IoU 0.3 (skipped only for [DetectionFormat.GenericDetections.nmsInGraph]).
 *
 * Every check runs before any box is returned: a non-finite value the decoder reads, a score outside [0, 1] beyond
 * 1e-6, a box whose edges are not strictly ordered before clipping, a box beyond the technical bounds, an output
 * type that is not float32, an output whose runtime shape differs from the shape the model file declares (rank,
 * and every static dimension: batch, K, and N when static) or that the format cannot read, or a cap breach throws
 * [OutputCheckFailed]. Nothing is dropped, clamped or truncated. Not safe for concurrent use (one call at a time per
 * instance).
 */
internal class ModelDetector(
    private val session: ModelSession,
    private val profile: DetectorProfile,
    /** Exact element count per output recorded by the contract run (static outputs only); empty before it ran. */
    private val recordedElements: Map<String, Long> = emptyMap(),
    /**
     * The dimensions the model file declares for each output the format reads (`null` = symbolic). Every pass, the
     * contract run included, requires the runtime shape to have the same rank and every static dimension
     * (`OUTPUT_SHAPE`). Empty only in unit tests of the other checks.
     */
    private val declaredOutputs: Map<String, List<Long?>> = emptyMap(),
    /** Per-pass run timeout in milliseconds (0 = none). */
    var timeoutMillis: Long = 0L,
) : FaceDetectorEngine {
    override val inputWidth: Int = profile.inputWidth
    override val inputHeight: Int = profile.inputHeight
    override var stageClock: StageClock? = null

    private val format = profile.format
    private val outputNames: List<String> = ModelLoader.requiredOutputs(profile).map { it.name }
    private val inputShape: LongArray =
        if (layout() == TensorLayout.NCHW) {
            longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
        } else {
            longArrayOf(1, inputHeight.toLong(), inputWidth.toLong(), 3)
        }
    private var spareInput: FloatArray? = null

    /** Number of in-pass NMS runs (diagnostics for tests: zero when the graph removes duplicates). */
    var inPassNmsRuns: Int = 0
        private set

    /** The in-pass NMS work of the current photo and its bound (reset by [beginPhoto]). */
    private val nmsBudget = NmsWork(Limits.MAX_NMS_PAIR_CHECKS_PER_PHOTO)

    override fun beginPhoto(maxNmsWork: Long) {
        nmsBudget.reset(maxNmsWork)
    }

    override val nmsWork: Long get() = nmsBudget.used

    override fun detect(image: DetectionImage, minScore: Float, checkCancelled: () -> Unit): List<DetectedFace> {
        val iw = image.width
        val ih = image.height
        if (iw <= 0 || ih <= 0) return emptyList()
        var src = image
        try {
            val argb =
                timed(stageClock, "model.pixels") {
                    if (iw > inputWidth || ih > inputHeight) {
                        val s = min(inputWidth.toDouble() / iw, inputHeight.toDouble() / ih)
                        src = image.cropScaled(0, 0, iw, ih, max(1, floor(max(iw, ih) * s + 1e-6).toInt()))
                    }
                    src.readArgb()
                }
            if (src.width > inputWidth || src.height > inputHeight) {
                throw IllegalStateException("scaled image does not fit the model input")
            }
            val input = takeInput(inputWidth * inputHeight * 3)
            timed(stageClock, "model.tensor") { fillInput(argb, src.width, src.height, input) }
            val outputs = timed(stageClock, "model.inference") { runPass(input) }
            spareInput = input
            val inputBoxes = timed(stageClock, "model.decode") { decode(outputs, minScore, checkCancelled) }
            val sx = iw.toFloat() / src.width
            val sy = ih.toFloat() / src.height
            return inputBoxes.map { it.mapped(0f, 0f, sx, sy) }
        } finally {
            if (src !== image) src.release()
        }
    }

    /** Runs one pass on a uniform mid-grey input (the contract run at creation); returns the raw outputs. */
    fun runUniformGrey(): Map<String, PassOutput> {
        val grey = IntArray(inputWidth * inputHeight) { GREY }
        val input = FloatArray(inputWidth * inputHeight * 3)
        fillInput(grey, inputWidth, inputHeight, input)
        return runPass(input)
    }

    /** The checked, decoded boxes of [outputs] in input-tensor pixels (the contract run and every pass). */
    fun decode(outputs: Map<String, PassOutput>, minScore: Float, checkCancelled: () -> Unit): List<DetectedFace> {
        val data = checkOutputs(outputs)
        return when (val f = format) {
            is DetectionFormat.YuNetLayout -> nms(decodeYuNet(data, minScore), checkCancelled)

            is DetectionFormat.GenericDetections -> {
                val rows = decodeGeneric(f, data, outputs, minScore)
                if (f.nmsInGraph) rows.also { checkDetectionCap(it.size) } else nms(rows, checkCancelled)
            }
        }
    }

    override fun close() = session.close()

    private fun runPass(input: FloatArray): Map<String, PassOutput> = try {
        session.run(input, inputShape, outputNames, Limits.MAX_ELEMENTS, timeoutMillis)
    } catch (e: SessionTimeout) {
        throw OutputCheckFailed(listOf(RUN_TIMEOUT))
    } catch (e: Throwable) {
        if (isFatal(e) || e is FaceMaskException) throw e
        // The runtime's exception never leaves (its text can contain paths): only the code.
        throw OutputCheckFailed(listOf(RUN_FAILED))
    }

    private fun layout(): TensorLayout =
        (profile.format as? DetectionFormat.GenericDetections)?.tensorLayout ?: TensorLayout.NCHW

    private fun takeInput(size: Int): FloatArray {
        val b = spareInput
        spareInput = null
        return if (b != null && b.size == size) b else FloatArray(size)
    }

    /**
     * Writes [argb] ([w] × [h], fits the input) at the top left of [dst] in the profile's layout, channel order and
     * scaling; the rest is black (pixel value 0, scaled like any pixel).
     */
    fun fillInput(argb: IntArray, w: Int, h: Int, dst: FloatArray) {
        val generic = format as? DetectionFormat.GenericDetections
        val bgr = generic == null || generic.colorOrder == ColorOrder.BGR
        val nchw = layout() == TensorLayout.NCHW
        val scaling = generic?.scaling ?: IDENTITY
        val div = scaling.divisor
        val mean = FloatArray(3) { scaling.mean[it] }
        val std = FloatArray(3) { scaling.std[it] }
        val plane = inputWidth * inputHeight
        for (c in 0 until 3) {
            val black = (0f / div - mean[c]) / std[c]
            if (nchw) {
                dst.fill(black, c * plane, (c + 1) * plane)
            } else {
                var i = c
                while (i < dst.size) {
                    dst[i] = black
                    i += 3
                }
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = argb[y * w + x]
                val o = y * inputWidth + x
                for (c in 0 until 3) {
                    // Channel c of the tensor: blue first for BGR, red first for RGB.
                    val shift = if (bgr) 8 * c else 16 - 8 * c
                    val v = ((p ushr shift) and 0xFF).toFloat()
                    val value = (v / div - mean[c]) / std[c]
                    if (nchw) dst[c * plane + o] = value else dst[o * 3 + c] = value
                }
            }
        }
    }

    // ---- Output checks ----------------------------------------------------------------------------------------

    /** Checks names, types, shapes, element counts and caps; returns the data by name. */
    private fun checkOutputs(outputs: Map<String, PassOutput>): Map<String, FloatArray> {
        val findings = LinkedHashSet<String>()
        val data = HashMap<String, FloatArray>()
        if (outputs.keys != outputNames.toSet()) findings += OUTPUT_SHAPE
        for (name in outputNames) {
            val out = outputs[name] ?: continue
            if (out.elementType != ModelLoader.FLOAT) {
                findings += OUTPUT_TYPE
                continue
            }
            val elements = elementCount(out.shape)
            if (elements == null) {
                findings += OUTPUT_SHAPE
                continue
            }
            if (elements > Limits.MAX_ELEMENTS) {
                findings += TOO_MANY_ELEMENTS
                continue
            }
            val values = out.data
            if (values == null || values.size.toLong() != elements) {
                findings += OUTPUT_SHAPE
                continue
            }
            val recorded = recordedElements[name]
            if (recorded != null && recorded != elements) findings += OUTPUT_SHAPE
            val declared = declaredOutputs[name]
            if (declared != null && !asDeclared(out.shape, declared)) findings += OUTPUT_SHAPE
            data[name] = values
        }
        if (findings.isEmpty()) checkShapes(outputs, findings)
        if (findings.isNotEmpty()) throw OutputCheckFailed(findings.toList())
        return data
    }

    private fun checkShapes(outputs: Map<String, PassOutput>, findings: MutableSet<String>) {
        when (val f = format) {
            is DetectionFormat.YuNetLayout -> for (s in YuNetShapes.STRIDES) {
                val n = YuNetShapes.anchors(profile, s).toLong()
                for ((name, k) in listOf("cls_$s" to 1L, "obj_$s" to 1L, "bbox_$s" to 4L)) {
                    val shape = outputs.getValue(name).shape
                    if (!(shape.size == 3 && shape[0] == 1L && shape[1] == n && shape[2] == k)) findings += OUTPUT_SHAPE
                }
            }

            is DetectionFormat.GenericDetections -> {
                val boxes = genericGeometry(outputs.getValue(f.boxesOutput).shape, f.rows)
                val scores = f.scoresOutput?.let { genericGeometry(outputs.getValue(it).shape, f.rows) } ?: boxes
                if (boxes == null || scores == null) {
                    findings += OUTPUT_SHAPE
                    return
                }
                if (boxes.n > Limits.MAX_ROWS || scores.n > Limits.MAX_ROWS) findings += TOO_MANY_ROWS
                // Long arithmetic: a column index near Int.MAX_VALUE must not overflow into a passing comparison.
                val boxesTooNarrow = f.boxColumn.toLong() + 3L >= boxes.k
                if (boxes.n != scores.n || boxesTooNarrow || f.scoreColumn.toLong() >= scores.k) {
                    findings += OUTPUT_SHAPE
                }
            }
        }
    }

    /** True when [shape] has the rank of [declared] and equals it in every static dimension. */
    private fun asDeclared(shape: LongArray, declared: List<Long?>): Boolean =
        shape.size == declared.size && declared.indices.all { declared[it] == null || declared[it] == shape[it] }

    /** N rows of K values of a generic output ([N, K], [1, N, K], or the column forms). */
    private class Geometry(val n: Long, val k: Long)

    private fun genericGeometry(shape: LongArray, rows: RowLayout): Geometry? {
        val core =
            when {
                shape.size == 2 -> shape[0] to shape[1]
                shape.size == 3 && shape[0] == 1L -> shape[1] to shape[2]
                else -> return null
            }
        return if (rows == RowLayout.ROWS) Geometry(core.first, core.second) else Geometry(core.second, core.first)
    }

    // ---- Decoders ---------------------------------------------------------------------------------------------

    /**
     * The decode of a face-detection model in YuNet layout: score = sqrt(cls × obj), box centre from the grid cell
     * plus the offset, size from exp(). Both values first pass the probability check and are then each limited to
     * [0, 1] before the square root, exactly as the reference decoder of that layout does. This is numeric hygiene
     * for reference parity, not score clamping: it acts only on values inside the ±1e-6 tolerance band, keeps the
     * score in [0, 1], and moves a score by at most 1e-6 against the formula without the limit wherever that is
     * defined, so it can only matter for a score within 1e-6 of the threshold, and there it follows the reference
     * decoder.
     */
    private fun decodeYuNet(data: Map<String, FloatArray>, minScore: Float): List<DetectedFace> {
        val out = ArrayList<DetectedFace>()
        for (stride in YuNetShapes.STRIDES) {
            val cols = inputWidth / stride
            val rows = inputHeight / stride
            val cls = data.getValue("cls_$stride")
            val obj = data.getValue("obj_$stride")
            val bbox = data.getValue("bbox_$stride")
            val s = stride.toFloat()
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val idx = r * cols + c
                    val clsScore = probability(cls[idx])
                    val objScore = probability(obj[idx])
                    val score = sqrt(clsScore * objScore).coerceAtMost(1f)
                    if (score < minScore) continue
                    val dx = finite(bbox[idx * 4])
                    val dy = finite(bbox[idx * 4 + 1])
                    val dw = finite(bbox[idx * 4 + 2])
                    val dh = finite(bbox[idx * 4 + 3])
                    val cx = (c + dx) * s
                    val cy = (r + dy) * s
                    val w = exp(dw) * s
                    val h = exp(dh) * s
                    val x1 = cx - w / 2f
                    val y1 = cy - h / 2f
                    out += checkedBox(x1, y1, x1 + w, y1 + h, score)
                }
            }
        }
        return out
    }

    private fun decodeGeneric(
        f: DetectionFormat.GenericDetections,
        data: Map<String, FloatArray>,
        outputs: Map<String, PassOutput>,
        minScore: Float,
    ): List<DetectedFace> {
        val boxes = data.getValue(f.boxesOutput)
        val scores = data.getValue(f.scoresOutput ?: f.boxesOutput)
        val bg =
            genericGeometry(outputs.getValue(f.boxesOutput).shape, f.rows)
                ?: throw OutputCheckFailed(listOf(OUTPUT_SHAPE))
        val sg = genericGeometry(outputs.getValue(f.scoresOutput ?: f.boxesOutput).shape, f.rows)
            ?: throw OutputCheckFailed(listOf(OUTPUT_SHAPE))
        val n = bg.n.toInt()
        val bk = bg.k.toInt()
        val sk = sg.k.toInt()
        val byRows = f.rows == RowLayout.ROWS
        fun at(values: FloatArray, k: Int, row: Int, col: Int) =
            if (byRows) values[row * k + col] else values[col * n + row]
        val xScale = if (f.coordinates == CoordinateSpace.NORMALIZED) inputWidth.toFloat() else 1f
        val yScale = if (f.coordinates == CoordinateSpace.NORMALIZED) inputHeight.toFloat() else 1f
        val out = ArrayList<DetectedFace>()
        for (row in 0 until n) {
            val score = probability(at(scores, sk, row, f.scoreColumn))
            if (score < minScore) continue
            val a = finite(at(boxes, bk, row, f.boxColumn)) * xScale
            val b = finite(at(boxes, bk, row, f.boxColumn + 1)) * yScale
            val c = finite(at(boxes, bk, row, f.boxColumn + 2)) * xScale
            val d = finite(at(boxes, bk, row, f.boxColumn + 3)) * yScale
            out +=
                when (f.boxFormat) {
                    BoxFormat.XYXY -> checkedBox(a, b, c, d, score)

                    BoxFormat.XYWH -> checkedBox(a, b, a + c, b + d, score)

                    BoxFormat.CXCYWH -> {
                        val left = a - c / 2f
                        val top = b - d / 2f
                        checkedBox(left, top, left + c, top + d, score)
                    }
                }
        }
        return out
    }

    /**
     * A probability the decoder read: finite and in [0, 1] within 1e-6 (else `NON_FINITE` / `SCORE_RANGE`), then
     * limited to [0, 1]; the limit acts only inside the tolerance band.
     */
    private fun probability(v: Float): Float {
        if (!v.isFinite()) throw OutputCheckFailed(listOf(NON_FINITE))
        if (v < -SCORE_TOLERANCE || v > 1f + SCORE_TOLERANCE) throw OutputCheckFailed(listOf(SCORE_RANGE))
        return v.coerceIn(0f, 1f)
    }

    private fun finite(v: Float): Float = if (v.isFinite()) v else throw OutputCheckFailed(listOf(NON_FINITE))

    /** A box in input pixels: finite, strictly ordered before any clipping, within the technical bounds. */
    private fun checkedBox(left: Float, top: Float, right: Float, bottom: Float, score: Float): DetectedFace {
        if (!(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())) {
            throw OutputCheckFailed(listOf(NON_FINITE))
        }
        if (!(right > left && bottom > top)) throw OutputCheckFailed(listOf(BOX_INVERTED))
        val w = inputWidth.toFloat()
        val h = inputHeight.toFloat()
        val inX = left >= -2f * w && right <= 3f * w
        val inY = top >= -2f * h && bottom <= 3f * h
        if (!(inX && inY && right - left <= 5f * w && bottom - top <= 5f * h)) {
            throw OutputCheckFailed(listOf(BOX_BOUNDS))
        }
        return DetectedFace(RectF(left, top, right, bottom), score)
    }

    private fun nms(faces: List<DetectedFace>, checkCancelled: () -> Unit): List<DetectedFace> {
        inPassNmsRuns++
        // The kept list only grows, so the cap is applied as soon as it is passed (same result, less work).
        return InPassNms.nmsLikeOpenCv(
            faces,
            IN_PASS_NMS_IOU,
            checkCancelled,
            Limits.MAX_DETECTIONS_PER_PASS,
            nmsBudget,
        )
    }

    private fun checkDetectionCap(count: Int) {
        if (count > Limits.MAX_DETECTIONS_PER_PASS) throw OutputCheckFailed(listOf(TOO_MANY_DETECTIONS))
    }

    companion object {
        /** IoU threshold of the in-pass NMS (the default of the YuNet layout). */
        const val IN_PASS_NMS_IOU: Float = 0.3f

        /** Tolerance of the probability check. */
        const val SCORE_TOLERANCE: Float = 1e-6f

        /** Mid-grey ARGB of the contract run. */
        const val GREY: Int = 0xFF808080.toInt()

        private val IDENTITY = PixelScaling(1f, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))

        const val RUN_FAILED = "RUN_FAILED"
        const val RUN_TIMEOUT = "RUN_TIMEOUT"
        const val OUTPUT_TYPE = "OUTPUT_TYPE"
        const val OUTPUT_SHAPE = "OUTPUT_SHAPE"
        const val NON_FINITE = "NON_FINITE"
        const val SCORE_RANGE = "SCORE_RANGE"
        const val BOX_INVERTED = "BOX_INVERTED"
        const val BOX_BOUNDS = "BOX_BOUNDS"
        const val TOO_MANY_ROWS = "TOO_MANY_ROWS"
        const val TOO_MANY_ELEMENTS = "TOO_MANY_ELEMENTS"
        const val TOO_MANY_DETECTIONS = "TOO_MANY_DETECTIONS"
        const val TOO_MANY_MERGE_CANDIDATES = "TOO_MANY_MERGE_CANDIDATES"
        const val TOO_MANY_MASKS = "TOO_MANY_MASKS"
        const val TOO_MANY_NMS_PAIR_CHECKS = "TOO_MANY_NMS_PAIR_CHECKS"

        /** The product of [shape], or null when a dimension is negative or the product overflows. */
        fun elementCount(shape: LongArray): Long? {
            var n = 1L
            for (d in shape) {
                if (d < 0L) return null
                if (d != 0L && n > Long.MAX_VALUE / d) return null
                n *= d
            }
            return n
        }
    }
}

/**
 * Counted work of the in-pass NMS over one photo (all passes): one unit per pair check. Exceeding [max] is
 * `TOO_MANY_NMS_PAIR_CHECKS` (fail closed, the same rule as the merge work bound).
 *
 * The NMS loop counts in a local variable and writes the total back once per run ([commit]), so no field of this
 * object is read or written per pair check: per-pair writes to a heap field made parallel instances slow each other
 * down. [commits] counts those writes for the regression test.
 */
internal class NmsWork(var max: Long) {
    var used: Long = 0L
        private set

    /** Number of [commit] calls since the last [reset]: one per NMS run, whatever its number of pair checks. */
    var commits: Int = 0
        private set

    fun reset(max: Long) {
        used = 0L
        commits = 0
        this.max = max
    }

    /** Stores the running total [used] of the current photo at the end of one NMS run. */
    fun commit(used: Long) {
        this.used = used
        commits++
    }
}

/** The in-pass NMS of OpenCV's `cv::dnn::NMSBoxes` on integer boxes. */
internal object InPassNms {
    /**
     * Stable sort by score (descending), suppress when the IoU of the integer boxes is above [iouThreshold]. More
     * than [maxKept] kept boxes is `TOO_MANY_DETECTIONS`, thrown as soon as the kept list passes it. Every pair check
     * counts one unit of [work] (bounded per photo, cancellation read by work done).
     */
    fun nmsLikeOpenCv(
        faces: List<DetectedFace>,
        iouThreshold: Float,
        checkCancelled: () -> Unit = {},
        maxKept: Int = Int.MAX_VALUE,
        work: NmsWork = NmsWork(Long.MAX_VALUE),
    ): List<DetectedFace> {
        if (faces.size <= 1) {
            if (faces.size > maxKept) throw OutputCheckFailed(listOf(ModelDetector.TOO_MANY_DETECTIONS))
            return faces
        }
        val sorted = faces.sortedByDescending { it.score }
        val n = sorted.size
        val ix = IntArray(n)
        val iy = IntArray(n)
        val iw = IntArray(n)
        val ih = IntArray(n)
        for (i in 0 until n) {
            val r = sorted[i].rect
            ix[i] = r.left.toInt()
            iy[i] = r.top.toInt()
            iw[i] = r.width.toInt()
            ih[i] = r.height.toInt()
        }
        val kept = IntArray(n)
        var count = 0
        // Call-local copies of the photo's work total and bound; written back once, also when the run throws.
        val maxWork = work.max
        var used = work.used
        try {
            for (i in 0 until n) {
                if (i > 0 && i and CANCEL_CHECK_MASK == 0) checkCancelled()
                var keep = true
                for (j in 0 until count) {
                    used++
                    if (used > maxWork) {
                        throw OutputCheckFailed(
                            listOf(ModelDetector.TOO_MANY_NMS_PAIR_CHECKS),
                            pairChecks = used,
                            pairCheckLimit = maxWork,
                        )
                    }
                    if (used and WORK_CANCEL_MASK == 0L) checkCancelled()
                    val k = kept[j]
                    if (intIou(ix[i], iy[i], iw[i], ih[i], ix[k], iy[k], iw[k], ih[k]) > iouThreshold) {
                        keep = false
                        break
                    }
                }
                if (keep) {
                    if (count == maxKept) throw OutputCheckFailed(listOf(ModelDetector.TOO_MANY_DETECTIONS))
                    kept[count++] = i
                }
            }
        } finally {
            work.commit(used)
        }
        return List(count) { sorted[kept[it]] }
    }

    /** Cancellation is checked after every 256 candidates. */
    private const val CANCEL_CHECK_MASK = 0xFF

    /** Cancellation is also checked every 65,536 units of the photo's NMS work. */
    private const val WORK_CANCEL_MASK = 0xFFFFL

    private fun intIou(ax: Int, ay: Int, aw: Int, ah: Int, bx: Int, by: Int, bw: Int, bh: Int): Float {
        val aa = if (aw <= 0 || ah <= 0) 0L else aw.toLong() * ah
        val ba = if (bw <= 0 || bh <= 0) 0L else bw.toLong() * bh
        if (aa + ba <= 0L) return 0f
        val l = max(ax, bx)
        val t = max(ay, by)
        val r = min(ax.toLong() + aw, bx.toLong() + bw)
        val b = min(ay.toLong() + ah, by.toLong() + bh)
        val inter = if (r <= l || b <= t) 0L else (r - l) * (b - t)
        return (inter.toDouble() / (aa + ba - inter).toDouble()).toFloat()
    }
}
