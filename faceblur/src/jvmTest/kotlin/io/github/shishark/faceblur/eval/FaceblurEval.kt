// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.eval

import io.github.shishark.faceblur.Detection
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.geometry.RectF
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.jvm.JvmModelFileReader
import io.github.shishark.faceblur.jvm.OrtModelRuntime
import java.awt.BasicStroke
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.io.PrintStream
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * faceblur-eval (DX-MA-001): measures a model and its profile on the developer's own annotated photos. Results go to
 * the developer only; FaceBlur publishes no claim from them. The tool has no network code, uploads nothing, prints no
 * file name unless asked (`--show-names`) and writes images only when asked (`--overlays`).
 */
internal object FaceblurEval {
    const val USAGE = """faceblur-eval: measure a face-detection model and its profile on your own annotated photos.

Required:
  --model FILE        the ONNX model
  --profile FILE      the profile JSON (format, input size, score threshold, expected SHA-256, ...)
  --images DIR        the image folder (JPEG or PNG)
  --truth FILE        the ground-truth JSON (format "faceblur-gt", version 1)
Optional:
  --thresholds LIST   comma-separated score thresholds to sweep, each in (0, 1); the profile's threshold is always
                      included
  --match-iou X       IoU for matching a detection to a face (default 0.5)
  --coverage X        fraction of a face box that the masks must cover to count as protected (default 0.8)
  --per-image         print one line per image (by index)
  --show-names        add the file names to the per-image lines (off by default: names can be confidential)
  --overlays DIR      write one overlay image per photo (masked photo, face boxes: green protected, red missed,
                      grey ignored) at the profile's threshold, named by index; off by default
  --json FILE         also write the totals as JSON (no file names)
"""

    @JvmStatic
    fun main(args: Array<String>) {
        // ONNX Runtime can send its start-up event before code can switch telemetry off: only the variable stops it.
        if (!telemetryOff(System.getenv("ORT_DISABLE_TELEMETRY"))) {
            System.err.println("faceblur-eval: set ORT_DISABLE_TELEMETRY=1 before starting the tool")
            exitProcess(2)
        }
        val code =
            try {
                run(args, System.out, OrtModelRuntime())
            } catch (e: EvalInputError) {
                System.err.println("faceblur-eval: ${e.message}")
                2
            } catch (e: ReplayMismatch) {
                System.err.println("faceblur-eval: tool fault, no result: ${e.message}")
                1
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Throwable) {
                // Never the message or the stack trace: they can contain paths and file names.
                System.err.println("faceblur-eval: internal error (${e::class.simpleName})")
                1
            }
        exitProcess(code)
    }

    /** True only when the telemetry variable is exactly "1" (the value ONNX Runtime reads at start-up). */
    fun telemetryOff(value: String?): Boolean = value == "1"

    /** Runs the tool; returns the exit code (0 done, 2 usage or input error). */
    fun run(args: Array<String>, out: PrintStream, runtime: ModelRuntime): Int {
        val a = Args.parse(args) ?: run {
            out.print(USAGE)
            return 2
        }
        val profile =
            try {
                ProfileFile.parse(readText(a.profile, "profile"))
            } catch (e: FaceMaskException) {
                throw EvalInputError("profile refused: ${e::class.simpleName}: ${e.message}")
            }
        val truth = GroundTruthFile.parse(readText(a.truth, "ground truth"))
        val folder = a.images.canonicalFile
        if (!folder.isDirectory) throw EvalInputError("the image folder does not exist")
        FaceBlurRuntime.installModelFiles(JvmModelFileReader)
        val model =
            try {
                DetectorModel.load(ModelSource.fromFile(a.model.path), profile)
            } catch (e: FaceMaskException) {
                out.println("Model refused: ${e::class.simpleName}: ${e.message}")
                return 1
            }
        val thresholds = (a.thresholds + profile.scoreThreshold).distinct().sorted()
        thresholds.forEach { if (!(it > 0f && it < 1f)) throw EvalInputError("thresholds must lie in (0, 1)") }
        val sweep =
            try {
                ThresholdSweep(model, thresholds, FaceMaskerConfig(), runtime)
            } catch (e: FaceMaskException) {
                out.println("Masker creation refused the model: ${e::class.simpleName}: ${e.message}")
                return 1
            }
        sweep.use { s ->
            printContract(out, s)
            val totals = MutableList(thresholds.size) { Totals() }
            var unreadable = 0
            val overlayIndex = thresholds.indexOf(profile.scoreThreshold)
            a.overlays?.mkdirs()
            for ((index, gt) in truth.withIndex()) {
                val img = load(folder, gt.file)
                if (img == null) {
                    // Fail closed: the photo stays in the totals and its faces count as missed.
                    unreadable++
                    val faces = gt.faces.count { !it.ignore }
                    for (t in thresholds.indices) totals[t] = totals[t].unreadable(faces)
                    out.println("image ${index + 1}: unreadable")
                    continue
                }
                val boxes = gtBoxes(gt, img.width, img.height)
                val ignored = gt.faces.map { it.ignore }
                val results = s.run(img)
                val counts = results.map { r -> r.map { count(it, boxes, ignored, a) } }
                for (t in thresholds.indices) {
                    totals[t] = counts[t].fold({ totals[t] + it }, { totals[t].failed(ignored.count { !it }) })
                }
                if (a.perImage) {
                    val name = if (a.showNames) " ${gt.file}" else ""
                    val c = counts[overlayIndex]
                    val text = c.fold(
                        {
                            "faces ${it.gtFaces} protected ${it.protectedFaces} found ${it.truePositives} false ${it.falsePositives}"
                        },
                        { "error ${(it as FaceMaskException)::class.simpleName}" },
                    )
                    out.println("image ${index + 1}$name: $text")
                }
                val overlayDir = a.overlays
                val shown = results[overlayIndex].getOrNull()
                if (overlayDir != null && shown != null) {
                    try {
                        writeOverlay(
                            File(overlayDir, "overlay-%04d.png".format(index + 1)),
                            img,
                            shown,
                            s,
                            boxes,
                            ignored,
                            a,
                        )
                    } catch (e: java.io.IOException) {
                        throw EvalInputError("the overlay of image ${index + 1} could not be written")
                    }
                }
            }
            printTotals(out, thresholds, totals, unreadable, profile.scoreThreshold)
            try {
                a.json?.writeText(json(thresholds, totals, unreadable, a))
            } catch (e: java.io.IOException) {
                throw EvalInputError("the JSON file could not be written")
            }
        }
        return 0
    }

    private fun count(d: Detection, gt: List<RectF>, ignored: List<Boolean>, a: Args): ImageCounts = EvalMetrics.image(
        gt,
        ignored,
        d.faces.map { RectF(it.left, it.top, it.right, it.bottom) },
        d.toMaskPlan().masks.map { it.toGeometry() },
        a.matchIou,
        a.coverage,
    )

    /** The ground-truth boxes in pixels of the decoded, oriented image (scaled when the file gives another size). */
    fun gtBoxes(gt: GtImage, width: Int, height: Int): List<RectF> {
        val sx = if (gt.width > 0) width.toFloat() / gt.width else 1f
        val sy = if (gt.height > 0) height.toFloat() / gt.height else 1f
        return gt.faces.map { RectF(it.x * sx, it.y * sy, (it.x + it.w) * sx, (it.y + it.h) * sy) }
    }

    private fun readText(f: File, what: String): String =
        if (f.isFile) f.readText() else throw EvalInputError("the $what file does not exist")

    /** The image [relative] to [folder], decoded with its JPEG orientation applied; null when it cannot be read. */
    private fun load(folder: File, relative: String): IntArrayPixels? = try {
        val f = File(folder, relative).canonicalFile
        if (!f.path.startsWith(folder.path + File.separator) || !f.isFile) {
            null
        } else {
            val bytes = f.readBytes()
            ImageIO.read(bytes.inputStream())?.let { ExifOrientation.apply(it, ExifOrientation.read(bytes)) }
        }
    } catch (e: java.io.IOException) {
        // Never reported with its message: it names the file.
        null
    } catch (e: SecurityException) {
        null
    } catch (e: RuntimeException) {
        // Some ImageIO decoders throw unchecked exceptions on malformed files.
        null
    }

    private fun printContract(out: PrintStream, s: ThresholdSweep) {
        val info = s.reportMasker.modelInfo
        val m = info.model
        out.println("Contract report")
        out.println("  model SHA-256      ${m.sha256}")
        out.println("  model size         ${m.sizeBytes} bytes, IR version ${m.irVersion}, opsets ${m.opsetImports}")
        m.inputs.forEach { out.println("  input              ${it.name} ${it.elementType} ${it.shape}") }
        m.outputs.forEach { out.println("  output             ${it.name} ${it.elementType} ${it.shape}") }
        out.println("  warnings           ${if (m.warnings.isEmpty()) "none" else m.warnings.joinToString()}")
        out.println("  runtime            ONNX Runtime ${info.runtimeVersion}, ${info.executionProvider}")
        out.println("  FaceBlur           ${info.faceBlurVersion}")
    }

    private fun printTotals(
        out: PrintStream,
        thresholds: List<Float>,
        totals: List<Totals>,
        unreadable: Int,
        profileT: Float,
    ) {
        val t0 = totals.first()
        out.println()
        out.println(
            "Images ${t0.images} (not readable $unreadable: their faces count as missed), " +
                "with faces ${t0.imagesWithFaces}, faces ${t0.gtFaces}",
        )
        out.println("threshold  protected        photos fully protected  recall   precision  false masks/photo  errors")
        for ((i, t) in thresholds.withIndex()) {
            val x = totals[i]
            out.println(
                String.format(
                    Locale.ROOT,
                    "%-9s  %5d (%6.2f %%)  %5d of %-5d           %6.4f   %6.4f     %8.4f           %d",
                    fmt(t) + if (t == profileT) "*" else "",
                    x.protectedFaces, 100 * x.protection, x.imagesFullyProtected, x.imagesWithFaces,
                    x.recall, x.precision, x.falseMasksPerImage, x.failedImages,
                ),
            )
        }
        out.println("* the profile's threshold. Results are for you only; FaceBlur publishes no claim from them.")
    }

    private fun json(thresholds: List<Float>, totals: List<Totals>, unreadable: Int, a: Args): String =
        thresholds.indices.joinToString(
            ",\n    ",
            "{\n  \"unreadableImages\": $unreadable,\n  " +
                "\"matchIou\": ${a.matchIou},\n  \"coverage\": ${a.coverage},\n  \"thresholds\": [\n    ",
            "\n  ]\n}\n",
        ) { i ->
            val x = totals[i]
            "{\"threshold\": ${fmt(
                thresholds[i],
            )}, \"images\": ${x.images}, \"imagesWithFaces\": ${x.imagesWithFaces}, " +
                "\"imagesFullyProtected\": ${x.imagesFullyProtected}, \"faces\": ${x.gtFaces}, " +
                "\"protectedFaces\": ${x.protectedFaces}, \"truePositives\": ${x.truePositives}, " +
                "\"falseMasks\": ${x.falsePositives}, \"failedImages\": ${x.failedImages}, " +
                "\"unreadableImages\": ${x.unreadableImages}}"
        }

    private fun fmt(t: Float): String = t.toString()

    private fun writeOverlay(
        file: File,
        img: IntArrayPixels,
        d: Detection,
        s: ThresholdSweep,
        gt: List<RectF>,
        ignored: List<Boolean>,
        a: Args,
    ) {
        val masked = img.copy()
        s.reportMasker.applyMasks(masked, d.toMaskPlan())
        val geometries = d.toMaskPlan().masks.map { it.toGeometry() }
        val bi = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        bi.setRGB(0, 0, img.width, img.height, masked.data, 0, img.width)
        val g = bi.createGraphics()
        try {
            g.stroke = BasicStroke(maxOf(2f, maxOf(img.width, img.height) / 500f))
            for ((i, r) in gt.withIndex()) {
                g.color = when {
                    ignored[i] -> Color.GRAY
                    EvalMetrics.covered(r, geometries) >= a.coverage -> Color.GREEN
                    else -> Color.RED
                }
                g.drawRect(r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt())
            }
        } finally {
            g.dispose()
        }
        ImageIO.write(bi, "png", file)
    }

    /** Parsed command line. */
    class Args(
        val model: File,
        val profile: File,
        val images: File,
        val truth: File,
        val thresholds: List<Float>,
        val matchIou: Float,
        val coverage: Float,
        val perImage: Boolean,
        val showNames: Boolean,
        val overlays: File?,
        val json: File?,
    ) {
        companion object {
            /** Null when a required option is missing or an option is unknown. */
            fun parse(args: Array<String>): Args? {
                val v = HashMap<String, String>()
                val flags = HashSet<String>()
                var i = 0
                while (i < args.size) {
                    when (val k = args[i]) {
                        "--per-image", "--show-names" -> flags += k

                        "--model", "--profile", "--images", "--truth", "--thresholds", "--match-iou", "--coverage",
                        "--overlays", "--json",
                        -> v[k] = args.getOrNull(++i) ?: return null

                        else -> return null
                    }
                    i++
                }
                fun file(k: String) = v[k]?.let(::File)
                fun fraction(k: String, default: Float): Float {
                    val x = v[k]?.toFloatOrNull() ?: if (k in v) throw EvalInputError("$k needs a number") else default
                    if (!(x > 0f && x <= 1f)) throw EvalInputError("$k must lie in (0, 1]")
                    return x
                }
                val thresholds = v["--thresholds"]?.split(',')?.map {
                    it.trim().toFloatOrNull() ?: throw EvalInputError("--thresholds needs numbers")
                }.orEmpty()
                return Args(
                    file("--model") ?: return null,
                    file("--profile") ?: return null,
                    file("--images") ?: return null,
                    file("--truth") ?: return null,
                    thresholds,
                    fraction("--match-iou", 0.5f),
                    fraction("--coverage", 0.8f),
                    "--per-image" in flags,
                    "--show-names" in flags,
                    file("--overlays"),
                    file("--json"),
                )
            }
        }
    }
}

/** The JPEG EXIF orientation (1 to 8; 1 when absent or unreadable) and its application to decoded pixels. */
internal object ExifOrientation {
    fun read(b: ByteArray): Int {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return 1
        var p = 2
        while (p + 4 <= b.size && b[p] == 0xFF.toByte()) {
            val marker = b[p + 1].toInt() and 0xFF
            val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (marker == 0xDA || len < 2) return 1
            if (marker == 0xE1 && p + 4 + len - 2 <= b.size) orientationInExif(b, p + 4, len - 2)?.let { return it }
            p += 2 + len
        }
        return 1
    }

    private fun orientationInExif(b: ByteArray, start: Int, length: Int): Int? {
        if (length < 14 || String(b, start, 6, Charsets.ISO_8859_1) != "Exif\u0000\u0000") return null
        val t = start + 6
        val end = start + length
        val le = b[t] == 'I'.code.toByte()
        fun u16(o: Int): Int = if (o + 2 > end) {
            -1
        } else if (le) {
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        } else {
            ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        }
        fun u32(o: Int): Long = if (le) {
            (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)
        } else {
            (u16(o).toLong() shl 16) or u16(o + 2).toLong()
        }
        val ifd = u32(t + 4)
        if (ifd < 8 || t + ifd + 2 > end) return null
        val n = u16(t + ifd.toInt())
        for (i in 0 until n) {
            val e = t + ifd.toInt() + 2 + 12 * i
            if (e + 12 > end) return null
            if (u16(e) == 0x0112) return u16(e + 8).takeIf { it in 1..8 }
        }
        return null
    }

    /** The pixels of [img] as displayed for [orientation]. */
    fun apply(img: BufferedImage, orientation: Int): IntArrayPixels {
        val w = img.width
        val h = img.height
        val src = IntArray(w * h)
        img.getRGB(0, 0, w, h, src, 0, w)
        if (orientation !in 2..8) return IntArrayPixels(w, h, src)
        val swap = orientation >= 5
        val ow = if (swap) h else w
        val oh = if (swap) w else h
        val dst = IntArray(ow * oh)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val (dx, dy) = when (orientation) {
                    2 -> (w - 1 - x) to y
                    3 -> (w - 1 - x) to (h - 1 - y)
                    4 -> x to (h - 1 - y)
                    5 -> y to x
                    6 -> (h - 1 - y) to x
                    7 -> (h - 1 - y) to (w - 1 - x)
                    else -> y to (w - 1 - x)
                }
                dst[dy * ow + dx] = src[y * w + x]
            }
        }
        return IntArrayPixels(ow, oh, dst)
    }
}
