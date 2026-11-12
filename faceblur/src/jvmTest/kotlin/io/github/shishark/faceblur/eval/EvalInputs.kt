// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.eval

import io.github.shishark.faceblur.BoxFormat
import io.github.shishark.faceblur.ColorOrder
import io.github.shishark.faceblur.CoordinateSpace
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.PixelScaling
import io.github.shishark.faceblur.RowLayout
import io.github.shishark.faceblur.TensorLayout
import io.github.shishark.faceblur.jvm.Json

/** One ground-truth face: a box in pixels of the image as displayed (x, y = top-left corner). */
internal data class GtFace(val x: Float, val y: Float, val w: Float, val h: Float, val ignore: Boolean = false)

/** One image of the ground truth; [width]/[height] 0 = not given (boxes are used as they are). */
internal data class GtImage(val file: String, val width: Int, val height: Int, val faces: List<GtFace>)

/**
 * The ground-truth file of faceblur-eval, format `faceblur-gt` version 1 (documented in the tool's README):
 * ```
 * { "format": "faceblur-gt", "version": 1,
 *   "images": [ { "file": "photo1.jpg", "width": 4032, "height": 3024,
 *                 "faces": [ { "x": 100, "y": 200, "w": 80, "h": 100, "ignore": false } ] } ] }
 * ```
 * `file` is a path relative to the image folder. Coordinates are pixels of the image as displayed (JPEG orientation
 * applied), origin top-left. `width`/`height` are optional: when given and different from the decoded size, the boxes
 * are scaled. `ignore: true` marks a face that counts neither as found nor as missed (for example a face on a poster);
 * a detection on it is not a false mask. Every image of the folder that should be measured must be listed, also
 * images without faces (`"faces": []`). Errors name the entry's index, never its file name.
 */
internal object GroundTruthFile {
    const val FORMAT = "faceblur-gt"

    fun parse(text: String): List<GtImage> {
        val root = Json.parse(text) as? Map<*, *> ?: throw EvalInputError("ground truth: a JSON object is required")
        if (root["format"] != FORMAT) throw EvalInputError("ground truth: \"format\" must be \"$FORMAT\"")
        if ((root["version"] as? Number)?.toDouble() != 1.0) throw EvalInputError("ground truth: \"version\" must be 1")
        val images = root["images"] as? List<*> ?: throw EvalInputError("ground truth: \"images\" array missing")
        val seen = HashSet<String>()
        return images.mapIndexed { i0, e ->
            val i = i0 + 1 // numbered from 1, like the tool's per-image lines
            val im = e as? Map<*, *> ?: throw EvalInputError("ground truth: image $i is not an object")
            val file = im["file"] as? String
            if (file.isNullOrEmpty() || file.startsWith("/") || file.split('/', '\\').any { it == ".." }) {
                throw EvalInputError("ground truth: image $i needs a relative \"file\" inside the folder")
            }
            if (!seen.add(file)) throw EvalInputError("ground truth: image $i is listed twice")
            val faces = (im["faces"] as? List<*> ?: throw EvalInputError("ground truth: image $i has no \"faces\""))
                .mapIndexed { j, f -> face(f as? Map<*, *>, "image $i face ${j + 1}") }
            GtImage(file, size(im["width"], "image $i width"), size(im["height"], "image $i height"), faces)
        }
    }

    private fun size(v: Any?, what: String): Int {
        if (v == null) return 0
        val d = (v as? Number)?.toDouble() ?: throw EvalInputError("ground truth: $what is not a number")
        if (d < 0 || d != kotlin.math.floor(d) || d > Int.MAX_VALUE) throw EvalInputError("ground truth: $what invalid")
        return d.toInt()
    }

    private fun face(f: Map<*, *>?, what: String): GtFace {
        if (f == null) throw EvalInputError("ground truth: $what is not an object")
        fun num(k: String): Float = (f[k] as? Number)?.toFloat()?.takeIf { it.isFinite() }
            ?: throw EvalInputError("ground truth: $what needs a finite \"$k\"")
        val face = GtFace(num("x"), num("y"), num("w"), num("h"), f["ignore"] == true)
        if (face.w <= 0f || face.h <= 0f) throw EvalInputError("ground truth: $what needs w > 0 and h > 0")
        return face
    }
}

/**
 * The profile file of faceblur-eval: the values of a `DetectorProfile` as JSON (documented in the tool's README).
 * ```
 * { "format": "YuNetLayout", "inputWidth": 640, "inputHeight": 640, "scoreThreshold": 0.5,
 *   "expectedSha256": "<64 hex digits>", "minMaskExpandX": 1.6, "minMaskExpandY": 1.8 }
 * ```
 * A `GenericDetections` profile adds an object `"generic"` with `boxesOutput`, `boxColumn`, `scoresOutput` (or null),
 * `scoreColumn`, `rows` (ROWS, COLUMNS), `boxFormat` (XYXY, XYWH, CXCYWH), `coordinates` (INPUT_PIXELS, NORMALIZED),
 * `colorOrder` (RGB, BGR), `tensorLayout` (NCHW, NHWC), `scaling` ({ "divisor": 1, "mean": [0, 0, 0],
 * "std": [1, 1, 1] }) and optionally `nmsInGraph`. Every value is checked by the library's own constructors.
 */
internal object ProfileFile {
    fun parse(text: String): DetectorProfile {
        val p = Json.parse(text) as? Map<*, *> ?: throw EvalInputError("profile: a JSON object is required")
        val format =
            when (p["format"]) {
                "YuNetLayout" -> DetectionFormat.YuNetLayout()

                "GenericDetections" -> generic(
                    p["generic"] as? Map<*, *> ?: throw EvalInputError("profile: \"generic\" missing"),
                )

                else -> throw EvalInputError("profile: \"format\" must be YuNetLayout or GenericDetections")
            }
        val sha = p["expectedSha256"] as? String ?: throw EvalInputError("profile: \"expectedSha256\" missing")
        // The library's own defaults apply to the expansions that the file leaves out.
        val base = DetectorProfile(format, int(p, "inputWidth"), int(p, "inputHeight"), float(p, "scoreThreshold"), sha)
        return DetectorProfile(
            format,
            base.inputWidth,
            base.inputHeight,
            base.scoreThreshold,
            sha,
            p["minMaskExpandX"]?.let { float(p, "minMaskExpandX") } ?: base.minMaskExpandX,
            p["minMaskExpandY"]?.let { float(p, "minMaskExpandY") } ?: base.minMaskExpandY,
        )
    }

    private fun generic(g: Map<*, *>): DetectionFormat.GenericDetections {
        val s = g["scaling"] as? Map<*, *> ?: throw EvalInputError("profile: \"generic.scaling\" missing")
        return DetectionFormat.GenericDetections(
            string(g, "boxesOutput"),
            int(g, "boxColumn"),
            optionalString(g, "scoresOutput"),
            int(g, "scoreColumn"),
            enum<RowLayout>(g, "rows"),
            enum<BoxFormat>(g, "boxFormat"),
            enum<CoordinateSpace>(g, "coordinates"),
            enum<ColorOrder>(g, "colorOrder"),
            enum<TensorLayout>(g, "tensorLayout"),
            PixelScaling(float(s, "divisor"), floats(s, "mean"), floats(s, "std")),
            g["nmsInGraph"] == true,
        )
    }

    private fun optionalString(m: Map<*, *>, k: String): String? = when (val v = m[k]) {
        null -> null
        is String -> v
        else -> throw EvalInputError("profile: \"$k\" must be a string or null")
    }

    private fun string(m: Map<*, *>, k: String): String =
        m[k] as? String ?: throw EvalInputError("profile: \"$k\" missing")

    private fun int(m: Map<*, *>, k: String): Int {
        val d = (m[k] as? Number)?.toDouble() ?: throw EvalInputError("profile: \"$k\" missing")
        if (d != kotlin.math.floor(d) || d < Int.MIN_VALUE ||
            d > Int.MAX_VALUE
        ) {
            throw EvalInputError("profile: \"$k\" not an integer")
        }
        return d.toInt()
    }

    private fun float(m: Map<*, *>, k: String): Float =
        (m[k] as? Number)?.toFloat() ?: throw EvalInputError("profile: \"$k\" missing")

    private fun floats(m: Map<*, *>, k: String): FloatArray =
        (m[k] as? List<*>)?.map { (it as? Number)?.toFloat() ?: throw EvalInputError("profile: \"$k\" not numbers") }
            ?.toFloatArray() ?: throw EvalInputError("profile: \"$k\" missing")

    private inline fun <reified E : Enum<E>> enum(m: Map<*, *>, k: String): E {
        val v = string(m, k)
        return enumValues<E>().firstOrNull { it.name == v } ?: throw EvalInputError("profile: \"$k\" unknown value")
    }
}

/** A problem with the tool's inputs; the message never contains a file name. */
internal class EvalInputError(message: String) : RuntimeException(message)
