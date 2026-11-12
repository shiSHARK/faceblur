// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.eval

import io.github.shishark.faceblur.jvm.Fixtures
import io.github.shishark.faceblur.jvm.Json
import io.github.shishark.faceblur.jvm.OrtModelRuntime
import io.github.shishark.faceblur.jvm.TestModels
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * faceblur-eval end to end on a synthetic folder of registered fixtures (ST-053 AC1, AC3, AC4): the counts are known
 * from the committed OpenCV reference boxes, file names are canaries that must not appear in the default output.
 */
class FaceblurEvalCliTest {
    private val root: File = Files.createTempDirectory("faceblur-eval-test").toFile()
    private val canary = "CANARY-q7x"

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun referenceBox(file: String): List<Float> {
        @Suppress("UNCHECKED_CAST")
        val ref = Fixtures.referenceData()

        @Suppress("UNCHECKED_CAST")
        val face = (
            (ref["images"] as List<Map<String, Any?>>).single {
                it["file"] == file
            }["faces"] as List<Map<String, Any?>>
            ).single()
        return listOf("x", "y", "w", "h").map { (face[it] as Number).toFloat() }
    }

    /**
     * Images: two one-face fixtures (ground truth = the OpenCV box), a grey image with one invented face (missed), a
     * grey image without faces, an unreadable file, all under canary names.
     */
    private fun folder(): File {
        val images = File(root, "images").apply { mkdirs() }
        val faces = listOf("images/face-0003542-s0625-x0000-y0000.png", "images/face-0047684-s0625-x0000-y0000.png")
        faces.forEachIndexed { i, f -> Fixtures.file(f).copyTo(File(images, "$canary-$i.png")) }
        val grey = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(0, 0, 640, 480, IntArray(640 * 480) { 0x808080 }, 0, 640)
        }
        ImageIO.write(grey, "png", File(images, "$canary-2.png"))
        ImageIO.write(grey, "png", File(images, "$canary-3.png"))
        File(images, "$canary-4.png").writeBytes(byteArrayOf(1, 2, 3))
        fun face(b: List<Float>) = """{"x": ${b[0]}, "y": ${b[1]}, "w": ${b[2]}, "h": ${b[3]}}"""
        val gt = """{"format": "faceblur-gt", "version": 1, "images": [
            {"file": "$canary-0.png", "faces": [${face(referenceBox(faces[0]))}]},
            {"file": "$canary-1.png", "faces": [${face(referenceBox(faces[1]))}]},
            {"file": "$canary-2.png", "faces": [{"x": 100, "y": 100, "w": 80, "h": 100}]},
            {"file": "$canary-3.png", "faces": []},
            {"file": "$canary-4.png", "faces": [{"x": 1, "y": 1, "w": 5, "h": 5}]}]}"""
        File(root, "truth.json").writeText(gt)
        File(root, "profile.json").writeText(
            """{"format": "YuNetLayout", "inputWidth": 640, "inputHeight": 640, "scoreThreshold": 0.5,
              "expectedSha256": "${TestModels.YUNET_SHA256}"}""",
        )
        return images
    }

    private fun run(vararg extra: String): Pair<Int, String> {
        folder()
        return rerun(*extra)
    }

    /** Runs the tool on the folder made before. */
    private fun rerun(vararg extra: String): Pair<Int, String> {
        val images = File(root, "images")
        val out = ByteArrayOutputStream()
        val args = arrayOf(
            "--model",
            TestModels.yunetFile.path,
            "--profile",
            File(root, "profile.json").path,
            "--images",
            images.path,
            "--truth",
            File(root, "truth.json").path,
        ) + extra
        val code = FaceblurEval.run(args, PrintStream(out, true, "UTF-8"), OrtModelRuntime())
        return code to out.toString("UTF-8")
    }

    @Test
    fun countsAreTheKnownValuesAndNoFileNameIsPrinted() {
        val json = File(root, "totals.json")
        val overlays = File(root, "overlays")
        val (code, text) = run(
            "--thresholds",
            "0.3,0.95",
            "--per-image",
            "--json",
            json.path,
            "--overlays",
            overlays.path,
        )
        assertEquals(0, code, text)
        assertFalse(text.contains(canary), "default output names no file:\n$text")
        // Contract report (AC3).
        assertTrue(text.contains("model SHA-256      ${TestModels.YUNET_SHA256}"), text)
        assertTrue(text.contains("runtime            ONNX Runtime 1.31.0"), text)
        assertTrue(text.contains("warnings           none"), text)
        assertTrue(
            text.contains("Images 5 (not readable 1: their faces count as missed), with faces 4, faces 4"),
            text,
        )
        assertTrue(text.contains("image 5: unreadable"), text)
        assertTrue(text.contains("image 3: faces 1 protected 0 found 0 false 0"), text)
        @Suppress("UNCHECKED_CAST")
        val rows = (Json.parse(json.readText()) as Map<String, Any?>)["thresholds"] as List<Map<String, Any?>>
        fun row(t: Double) = rows.single { (it["threshold"] as Number).toDouble() == t }
            .filterKeys { it != "threshold" }.mapValues { (it.value as Number).toInt() }
        // 0.3 and 0.5: both fixture faces found and protected, the invented one missed, no false mask.
        // The unreadable photo stays in the totals with its one face missed (fail closed).
        val found = mapOf(
            "images" to 5,
            "imagesWithFaces" to 4,
            "imagesFullyProtected" to 2,
            "faces" to 4,
            "protectedFaces" to 2,
            "truePositives" to 2,
            "falseMasks" to 0,
            "failedImages" to 0,
            "unreadableImages" to 1,
        )
        assertEquals(found, row(0.3))
        assertEquals(found, row(0.5))
        // 0.95: above both faces' scores (0.92 and 0.93 in the reference): nothing found.
        assertEquals(found + mapOf("imagesFullyProtected" to 0, "protectedFaces" to 0, "truePositives" to 0), row(0.95))
        // Overlays (opt-in) are named by index only: one per readable image.
        assertEquals((1..4).map { "overlay-%04d.png".format(it) }, overlays.list()!!.sorted())
        assertTrue(text.contains("0.5*"), "the profile's threshold is marked")
    }

    @Test
    fun aDefaultRunWritesNothingAndAnUnreadableFileIsNeverNamed() {
        val (code, text) = run()
        assertEquals(0, code, text)
        assertEquals(setOf("images", "profile.json", "truth.json"), root.list()!!.toSet(), "no file written")
        assertEquals((0..4).map { "$canary-$it.png" }.toSet(), File(root, "images").list()!!.toSet())
        // A file without read permission (mode 000): counted as unreadable, never named, the run completes.
        val locked = File(root, "images/$canary-0.png")
        locked.setReadable(false, false)
        if (locked.canRead()) {
            println("FaceblurEvalCliTest: mode-000 case not checkable here (the file stays readable for this user)")
        } else {
            val err = ByteArrayOutputStream()
            val oldErr = System.err
            System.setErr(PrintStream(err, true, "UTF-8"))
            val again =
                try {
                    rerun()
                } finally {
                    System.setErr(oldErr)
                }
            assertEquals(0, again.first, again.second)
            assertTrue(again.second.contains("image 1: unreadable"), again.second)
            assertTrue(again.second.contains("(not readable 2:"), again.second)
            assertFalse(again.second.contains(canary) || err.toString("UTF-8").contains(canary), "no file name")
        }
        locked.setReadable(true, false)
    }

    @Test
    fun profileErrorsAreInputErrorsWithoutAStackTrace() {
        folder()
        File(root, "profile.json").writeText(
            """{"format": "YuNetLayout", "inputWidth": 640, "inputHeight": 640, "scoreThreshold": 1.0,
              "expectedSha256": "${TestModels.YUNET_SHA256}"}""",
        )
        val e = assertFailsWith<EvalInputError> { rerun() }
        assertTrue(e.message!!.startsWith("profile refused: InvalidConfiguration"), e.message)
    }

    @Test
    fun namesOnlyWhenAskedAndUsageOnBadArguments() {
        val (code, text) = run("--per-image", "--show-names")
        assertEquals(0, code)
        assertTrue(text.contains("$canary-0.png"), "positive control: --show-names prints names")
        val out = ByteArrayOutputStream()
        assertEquals(2, FaceblurEval.run(arrayOf("--model"), PrintStream(out), OrtModelRuntime()))
        assertTrue(out.toString().startsWith("faceblur-eval:"))
        assertEquals(
            2,
            FaceblurEval.run(arrayOf("--unknown", "x"), PrintStream(ByteArrayOutputStream()), OrtModelRuntime()),
        )
    }
}
