// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlanMasker
import io.github.shishark.faceblur.applyMasks
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.maskInPlace
import io.github.shishark.faceblur.maskJpeg
import io.github.shishark.faceblur.verification.pixel.Fidelity
import io.github.shishark.faceblur.verification.pixel.MaskEllipse
import io.github.shishark.faceblur.verification.pixel.OracleCode
import io.github.shishark.faceblur.verification.pixel.OracleReport
import io.github.shishark.faceblur.verification.pixel.PixelDecoder
import io.github.shishark.faceblur.verification.pixel.PixelOracle
import io.github.shishark.faceblur.verification.pixel.Raster
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The INDEPENDENT pixel oracle (verification/pixel-oracle, ST-045, PRIV-023) on real Android-encoded file-mode output:
 * the oracle jar is on this test classpath only; it holds no library code, and the mask region it judges is derived
 * from the face box by the oracle itself (FR-004), never from the library's mask geometry.
 *
 * Every positive test pairs the two public verdicts for the SAME mask configuration (pairing rule of
 * verification/README.md): `requireMaskedLossyBy` on the written JPEG (file mode: path or stream, JPEG or PNG input)
 * and `requireMaskedBy`, exact, on the in-memory mode of the same masks (pair tolerance 0 in both). The lossy verdict
 * cannot show a single surviving pixel; the exact one can. Plans are fixed: boxes of the OpenCV reference
 * (testdata/yunet_reference.json) for the public-domain photos, a fixed box for the synthetic picture, or the fixed
 * faces of the fake detector for the stream form.
 *
 * Negative controls must FAIL with their reason: an unmasked re-encode, a mask shifted by one pixel, a 1/32 face mix.
 * Fixtures: a seeded synthetic picture and `testdata/pd` (registered public-domain photos); nothing else is read. On
 * the photos the exact verdict judges the photo itself; the lossy verdict judges two-colour noise of the same size with
 * the same plan, because it cannot judge a grey, blurred face (see `noise`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AndroidPixelOracleTest {
    private lateinit var masker: FaceMasker
    private lateinit var noFace: FaceMasker
    private lateinit var dir: File

    @Before
    fun setUp() {
        FaceBlurRuntime.installImageCodec(AndroidImageCodec.using(HostFiles))
        FaceBlurRuntime.installTargetWriter(AndroidTargetWriter.using(HostFiles))
        masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
        noFace = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(NoFaceRunner()))
        dir = Files.createTempDirectory("faceblur-oracle").toFile()
    }

    @After
    fun tearDown() {
        masker.close()
        noFace.close()
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        dir.deleteRecursively()
    }

    /** The fake model with no face: every score 0 (the same pipeline with no mask, for the stream form). */
    private class NoFaceRunner : TensorRunner by OneFaceRunner() {
        override fun run(input: FloatArray): Map<String, FloatArray> =
            OneFaceRunner().run(input).mapValues { (k, v) -> if (k.startsWith("cls_")) FloatArray(v.size) else v }
    }

    /** A face box in pixels of the working image (left, top, width, height), as the reference files give them. */
    private data class Box(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height

        fun shifted(dx: Int) = Box(left + dx, top, width, height)
    }

    private enum class Source(val ext: String) { PNG("png"), JPEG("jpg") }

    private companion object {
        /**
         * Fixed before the first run (never widened after a result): a pixel survives when it is within this many
         * levels of the unmasked pipeline's output in every channel. 0, as in the oracle's own lossy tests.
         */
        const val SURVIVAL = 0

        /** The feather of the library configuration under test (asserted against `MaskingConfig()` in a test). */
        const val FEATHER = 0.10

        /** Quality of the intermediate JPEG sources and of the control encodes. */
        const val QUALITY = 92

        /**
         * Fixed boxes of the synthetic 640 x 480 picture (face-sized, one block-safe region each). The mask of FR-004
         * (1.6 / 1.8) plus its soft edge must lie inside the picture: the lossy verdict cannot judge the rows next to
         * the picture edge (asserted in `theNoisePlanMasksLieInsideThePicture`). The earlier box (180, 110, 240, 270)
         * reached 19.6 px above the picture at this size.
         */
        val NOISE_PLAN = listOf(Box(200, 140, 200, 230))
    }

    // ---- inputs ----

    /**
     * Seeded two-colour noise, alpha 255: each pixel is red-orange (215, 60, 60) or cyan (60, 200, 200) at random. A
     * texture that a JPEG keeps and a mask must remove, and a picture that the lossy verdict can judge. The library's
     * default fill is smooth and taken from the colours around the mask, about the MEAN of the surroundings (a neutral
     * grey of luma about 132 here), so a face pixel can equal the fill by chance wherever the unmasked output has
     * pixels near that mean (observed: a few to a few dozen "surviving" pixels per mask on RGB noise, luma noise and
     * grey photos, each a false FAIL that depends on the seed). Here the two colours are far from the mean in chroma
     * and 26 levels from it in luma (106 and 158), and the 2 x 2 chroma averaging of a JPEG turns a mixed cell into
     * a neutral grey of luma 106 or 158, never 132. The luma gap is only 52 because a JPEG block that holds the rim of
     * a mask spills the neighbouring texture into the masked part (observed: +-20 levels at a luma gap of 130, which
     * the lossy verdict reports as surviving texture at the tip of an ellipse). This is the oracle's own guidance:
     * inputs far from the mask colours.
     */
    private fun noise(width: Int, height: Int, seed: Long): Raster {
        val random = Random(seed)
        val orange = (0xFF shl 24) or (215 shl 16) or (60 shl 8) or 60
        val cyan = (0xFF shl 24) or (60 shl 16) or (200 shl 8) or 200
        return Raster(width, height, IntArray(width * height) { if (random.nextBoolean()) orange else cyan })
    }

    /**
     * The fixed plan of every public-domain photo, read from the OpenCV reference file (testdata/yunet_reference.json),
     * not from the library: the faces with score 0.5 or more that do not cross the border and are at least 60 px wide
     * (a 16 x 16 block with a one block margin must fit inside a mask), widest first, at most four per photo, as
     * rounded boxes.
     */
    private fun photoPlans(): Map<String, List<Box>> {
        val reference = File("../testdata/yunet_reference.json").canonicalFile
        require(reference.path.startsWith(File("../testdata").canonicalPath + File.separator)) { "outside testdata" }
        val images = JSONObject(reference.readText()).getJSONArray("images")
        val plans = linkedMapOf<String, List<Box>>()
        for (i in 0 until images.length()) {
            val image = images.getJSONObject(i)
            val file = image.getString("file")
            if (!file.startsWith("pd/")) continue
            val faces = image.getJSONArray("faces")
            val boxes = (0 until faces.length()).map { faces.getJSONObject(it) }
                .filter { it.getDouble("score") >= 0.5 && !it.getBoolean("crossesBorder") && it.getDouble("w") >= 60.0 }
                .sortedByDescending { it.getDouble("w") }
                .take(4)
                .map {
                    val left = Math.round(it.getDouble("x")).toInt()
                    val top = Math.round(it.getDouble("y")).toInt()
                    Box(
                        left,
                        top,
                        Math.round(it.getDouble("x") + it.getDouble("w")).toInt() - left,
                        Math.round(it.getDouble("y") + it.getDouble("h")).toInt() - top,
                    )
                }
            if (boxes.isNotEmpty()) plans[file.removePrefix("pd/")] = boxes
        }
        return plans
    }

    private fun photo(name: String): Raster {
        val file = File("../testdata/pd", name).canonicalFile
        require(file.path.startsWith(File("../testdata/pd").canonicalPath + File.separator)) { "outside testdata/pd" }
        return PixelDecoder.decode(file.readBytes())
    }

    // ---- conversions (the oracle's raster and the platform bitmap) ----

    /**
     * The raster as a mutable bitmap with NOT-premultiplied storage, so that translucent pixels (the oracle's alpha-0
     * and noise variants) survive `setPixels` / `getPixels` unchanged: the verdict must judge the library, not the
     * premultiplication of this adapter.
     */
    private fun bitmapOf(raster: Raster): Bitmap =
        Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888).also {
            it.isPremultiplied = false
            it.setPixels(raster.argb, 0, raster.width, 0, 0, raster.width, raster.height)
        }

    private fun rasterOf(bitmap: Bitmap): Raster {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Raster(bitmap.width, bitmap.height, pixels)
    }

    private fun jpegOf(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it))
    }.toByteArray()

    /**
     * The source file the library is given for the oracle's PNG [png]: the PNG itself, or a JPEG of it (the Android
     * encoder; for [orientation] 6 the picture is stored turned counter-clockwise with an EXIF Orientation = 6 tag, so
     * the library has to turn it back). The same transformation is used for the masked and the unmasked pipeline.
     */
    private fun sourceBytes(png: ByteArray, source: Source, orientation: Int = 1): ByteArray {
        if (source == Source.PNG) return png
        val upright = BitmapFactory.decodeByteArray(png, 0, png.size)
        if (orientation == 1) return jpegOf(upright)
        check(orientation == 6) { "only orientation 6 is built here" }
        val stored = Bitmap.createBitmap(
            upright,
            0,
            0,
            upright.width,
            upright.height,
            Matrix().apply { postRotate(270f) },
            false,
        )
        return TestImages.withOrientation(jpegOf(stored), 6)
    }

    // ---- masks: the oracle's region from the box (FR-004), the library's Mask from the same box ----

    private fun ellipses(boxes: List<Box>): List<MaskEllipse> = boxes.map {
        MaskEllipse.fromFaceBox(
            it.left.toDouble(),
            it.top.toDouble(),
            (it.right - it.left).toDouble(),
            (it.bottom - it.top).toDouble(),
            FEATHER,
        )
    }

    private fun masksOf(boxes: List<Box>, masking: MaskingConfig): List<Mask> =
        boxes.map { Mask.manual(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat(), masking) }

    // ---- the pipelines under test ----

    /**
     * File mode on a PATH with a fixed plan: oracle PNG in, JPEG file out. An empty [boxes] is the same pipeline with
     * no mask (the unmasked reference). Only the written file's bytes are returned; the folder is emptied again.
     */
    private fun pathPipeline(boxes: List<Box>, source: Source, orientation: Int = 1): (ByteArray) -> ByteArray =
        { png ->
            val input = File(dir, "in.${source.ext}").apply { writeBytes(sourceBytes(png, source, orientation)) }
            val out = File(dir, "out.jpg")
            val planMasker = PlanMasker()
            val plan = planMasker.planFile(input.path).let { it.withMasks(masksOf(boxes, planMasker.masking)) }
            planMasker.maskFile(input.path, OutputTarget.NewFile(out.path), plan)
            out.readBytes().also {
                assertTrue(input.delete() && out.delete())
                assertEquals(emptyList(), dir.list()!!.toList(), "no other file is left")
            }
        }

    /** File mode on a STREAM (the fake detector's fixed face; [with] = [noFace] is the same pipeline with no mask). */
    private fun streamPipeline(with: FaceMasker, source: Source): (ByteArray) -> ByteArray = { png ->
        val out = ByteArrayOutputStream()
        with.maskJpeg(ByteArrayInputStream(sourceBytes(png, source)), out)
        out.toByteArray()
    }

    /** In-memory mode (exact) with the same [boxes] as a fixed plan, through the Android bitmap forms. */
    private fun planInMemory(boxes: List<Box>): (Raster) -> Raster = { raster ->
        val bitmap = bitmapOf(raster)
        masker.applyMasks(bitmap, MaskPlan(raster.width, raster.height, masksOf(boxes, MaskingConfig())))
        rasterOf(bitmap)
    }

    /** In-memory mode (exact) with the fake detector's face, as the stream form. */
    private fun detectInMemory(): (Raster) -> Raster = { raster ->
        val bitmap = bitmapOf(raster)
        masker.maskInPlace(bitmap)
        rasterOf(bitmap)
    }

    /** The fixed faces of the fake detector in a [width] x [height] picture, as boxes (read once from a stream run). */
    private fun detectorBoxes(width: Int, height: Int): List<Box> {
        val png = ByteArrayOutputStream().also {
            assertTrue(bitmapOf(noise(width, height, 1)).compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val result = masker.maskJpeg(ByteArrayInputStream(png.toByteArray()), ByteArrayOutputStream())
        return result.faces.map {
            val left = Math.round(it.left)
            val top = Math.round(it.top)
            Box(left, top, Math.round(it.right) - left, Math.round(it.bottom) - top)
        }
    }

    // ---- verdicts ----

    private class Verdicts(val lossy: OracleReport, val exact: OracleReport)

    /** The paired verdicts for one mask configuration: both are always computed, both public, pair tolerance 0. */
    private fun verdicts(
        input: Raster,
        boxes: List<Box>,
        lossy: (ByteArray) -> ByteArray,
        unmasked: (ByteArray) -> ByteArray,
        exact: (Raster) -> Raster,
    ): Verdicts {
        val masks = ellipses(boxes)
        return Verdicts(
            PixelOracle.requireMaskedLossyBy(input, masks, SURVIVAL, lossy, unmasked, FEATHER, pairTolerance = 0),
            PixelOracle.requireMaskedBy(input, masks, Fidelity.Exact, exact, FEATHER, pairTolerance = 0),
        )
    }

    private fun assertBothPass(label: String, v: Verdicts) {
        assertTrue(v.lossy.passed, "$label, lossy: ${v.lossy.summary()}")
        assertTrue(v.lossy.pixelsJudged > 0, "$label, lossy: the verdict judged no pixel")
        assertTrue(v.exact.passed, "$label, exact: ${v.exact.summary()}")
        assertTrue(v.exact.pixelsJudged > 0, "$label, exact: the verdict judged no pixel")
    }

    private fun assertFails(label: String, report: OracleReport, vararg codes: OracleCode) {
        println("control $label: ${report.codes}")
        assertTrue(!report.passed, "$label: the control must fail, but: ${report.summary()}")
        assertTrue(
            codes.any { it in report.codes },
            "$label: expected one of ${codes.toList()}, got ${report.codes}: ${report.summary()}",
        )
    }

    // ---- positive tests ----

    @Test
    fun theLibraryDefaultsAreTheOraclesAssumptions() {
        assertEquals(FEATHER, MaskingConfig().feather.toString().toDouble(), 0.0, "the feather under test")
        assertEquals(MaskingConfig(), PlanMasker().masking, "path and in-memory forms use the same masking")
        val boxes = detectorBoxes(1280, 960)
        assertTrue(boxes.isNotEmpty() && boxes.all { it.right > it.left && it.bottom > it.top }, "faces: $boxes")
        println("fake detector faces at 1280 x 960: $boxes")
    }

    @Test
    fun theNoisePlanMasksLieInsideThePicture() {
        for (m in ellipses(NOISE_PLAN)) {
            val reachX = m.rx + m.maxBandWidthPx
            val reachY = m.ry + m.maxBandWidthPx
            assertTrue(m.cx - reachX >= 0 && m.cx + reachX <= 640, "x extent of $m")
            assertTrue(m.cy - reachY >= 0 && m.cy + reachY <= 480, "y extent: ${m.cy - reachY} .. ${m.cy + reachY}")
        }
    }

    @Test
    fun filePathPngAndJpegInputsAreMaskedAndPairedWithTheExactInMemoryVerdict() {
        val input = noise(640, 480, 11)
        for (source in Source.values()) {
            val v = verdicts(
                input,
                NOISE_PLAN,
                pathPipeline(NOISE_PLAN, source),
                pathPipeline(emptyList(), source),
                planInMemory(NOISE_PLAN),
            )
            assertBothPass("path, ${source.name} input", v)
        }
    }

    @Test
    fun fileStreamPngAndJpegInputsAreMaskedAndPairedWithTheExactInMemoryVerdict() {
        // 1280 x 960: the fake detector's face scales with the picture; the lossy pair needs a mask that holds enough
        // whole 16 x 16 blocks (at 640 x 480 it holds 2 of them, 7.6 % of the pixels inside, below the 10 % floor).
        val input = noise(1280, 960, 12)
        val box = detectorBoxes(1280, 960)
        for (source in Source.values()) {
            val v = verdicts(
                input,
                box,
                streamPipeline(masker, source),
                streamPipeline(noFace, source),
                detectInMemory(),
            )
            assertBothPass("stream, ${source.name} input", v)
        }
    }

    @Test
    fun everyPublicDomainPhotoWithItsFixedPlanIsMaskedByAPathAndPairedWithTheExactVerdict() {
        val failures = mutableListOf<String>()
        val plans = photoPlans()
        assertTrue(plans.size >= 8, "the reference gives usable plans for most photos: ${plans.keys}")
        for ((name, boxes) in plans) {
            val photo = photo(name)
            // The exact verdict on the photo itself (in-memory mode, the same masks).
            val asIs = PixelOracle.requireMaskedBy(photo, ellipses(boxes), Fidelity.Exact, planInMemory(boxes), FEATHER)
            println("$name, ${boxes.size} masks, photo as it is, exact: ${asIs.summary().take(200)}")
            if (!asIs.passed || asIs.pixelsJudged == 0L) failures += "$name exact on the photo"
            // The file-mode output, lossy + exact, on two-level noise of the photo's size with the same plan: the lossy
            // verdict cannot judge a grey, blurred face (see [noise]); the geometry of the plan is the photo's.
            val input = noise(photo.width, photo.height, seed = 7)
            for (source in Source.values()) {
                val v = verdicts(
                    input,
                    boxes,
                    pathPipeline(boxes, source),
                    pathPipeline(emptyList(), source),
                    planInMemory(boxes),
                )
                val label = "$name, ${source.name} input, ${boxes.size} masks, noise of the photo size"
                println("$label: lossy ${v.lossy.summary().take(300)} | exact ${v.exact.summary().take(200)}")
                if (!v.lossy.passed || !v.exact.passed) failures += label
            }
        }
        assertEquals(emptyList(), failures, "photos whose verdicts did not pass (see the test output)")
    }

    /** EXIF Orientation = 6: the plan is in the turned (upright) frame and the mask must land there. */
    @Test
    fun exifOrientationSixPutsTheMaskAtTheRotatedPosition() {
        val upright = noise(480, 640, 13)
        val boxes = listOf(Box(100, 170, 280, 300))
        val v = verdicts(
            upright,
            boxes,
            pathPipeline(boxes, Source.JPEG, orientation = 6),
            pathPipeline(emptyList(), Source.JPEG, orientation = 6),
            planInMemory(boxes),
        )
        assertBothPass("orientation 6", v)
        // The same plan in the STORED (unturned) frame is a different place: the verdict must not pass there, so the
        // test above is about the position and not only about "something is masked".
        val storedFrame = listOf(Box(170, 100, 300, 280))
        val wrong = PixelOracle.requireMaskedLossyBy(
            upright,
            ellipses(boxes),
            SURVIVAL,
            pathPipeline(storedFrame, Source.JPEG, orientation = 6),
            pathPipeline(emptyList(), Source.JPEG, orientation = 6),
            FEATHER,
        )
        assertTrue(!wrong.passed, "a mask in the stored frame is not at the rotated position: ${wrong.summary()}")
    }

    // ---- negative controls: each must FAIL ----

    @Test
    fun controlAnUnmaskedReEncodeFails() {
        val input = noise(640, 480, 21)
        val v = verdicts(
            input,
            NOISE_PLAN,
            pathPipeline(emptyList(), Source.PNG),
            pathPipeline(emptyList(), Source.PNG),
            { it.copy() },
        )
        assertFails("lossy, unmasked re-encode", v.lossy, OracleCode.EQUALS_SOURCE_INSIDE, OracleCode.NOT_MASKED)
        assertFails("exact, unmasked", v.exact, OracleCode.EQUALS_SOURCE_INSIDE)
    }

    @Test
    fun controlAMaskShiftedByOnePixelFails() {
        val input = noise(640, 480, 22)
        val shifted = NOISE_PLAN.map { it.shifted(1) }
        val v = verdicts(
            input,
            NOISE_PLAN,
            pathPipeline(shifted, Source.PNG),
            pathPipeline(emptyList(), Source.PNG),
            planInMemory(shifted),
        )
        // The exact verdict sees the one-pixel crescent of face that the shifted mask leaves.
        assertFails("exact, 1 px shifted", v.exact, OracleCode.NOT_OPAQUE_INSIDE)
        // The lossy verdict is documented not to prove pixel-exact coverage (a one-pixel crescent of face is below its
        // floor): its result is recorded in the test output, not asserted either way.
        println("1 px shifted, lossy verdict: passed=${v.lossy.passed} ${v.lossy.codes}")
    }

    @Test
    fun controlAOneThirtySecondFaceMixFails() {
        val input = noise(640, 480, 23)
        val boxes = NOISE_PLAN
        val inside = ellipses(boxes)

        // A leaking masker: the library's in-memory masks, then 1/32 of the face colours put back inside r <= 1.
        fun leak(masked: Raster, source: Raster): Raster = Raster(
            masked.width,
            masked.height,
            IntArray(masked.argb.size) { i ->
                val x = i % masked.width
                val y = i / masked.width
                if (inside.none { it.radius(x, y) <= 1.0 }) {
                    masked.argb[i]
                } else {
                    var p = 0xFF shl 24
                    for (shift in intArrayOf(16, 8, 0)) {
                        val m = (masked.argb[i] ushr shift) and 0xFF
                        val s = (source.argb[i] ushr shift) and 0xFF
                        p = p or (((31 * m + s + 16) / 32) shl shift)
                    }
                    p
                }
            },
        )
        val leakingJpeg: (ByteArray) -> ByteArray = { png ->
            val source = PixelDecoder.decode(png)
            jpegOf(bitmapOf(leak(planInMemory(boxes)(source), source)))
        }
        val plainJpeg: (ByteArray) -> ByteArray = { png -> jpegOf(bitmapOf(PixelDecoder.decode(png))) }
        val v = verdicts(input, boxes, leakingJpeg, plainJpeg) { source -> leak(planInMemory(boxes)(source), source) }
        assertFails("lossy, 1/32 mix", v.lossy, OracleCode.DEPENDS_ON_MASKED_INPUT)
        assertFails("exact, 1/32 mix", v.exact, OracleCode.DEPENDS_ON_MASKED_INPUT)
        // Teeth of the control itself: without the leak the same pipeline passes both verdicts.
        val honestJpeg: (ByteArray) -> ByteArray = { png ->
            val source = PixelDecoder.decode(png)
            jpegOf(bitmapOf(planInMemory(boxes)(source)))
        }
        assertBothPass("control without the leak", verdicts(input, boxes, honestJpeg, plainJpeg, planInMemory(boxes)))
    }
}
