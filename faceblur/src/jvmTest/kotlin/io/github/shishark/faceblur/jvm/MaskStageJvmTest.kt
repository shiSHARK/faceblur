// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.MaskStyle
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.mask.LegacyMaskEngine
import io.github.shishark.faceblur.mask.MaskEngine
import io.github.shishark.faceblur.mask.MaskGeometry
import io.github.shishark.faceblur.mask.MaskSettings
import io.github.shishark.faceblur.mask.MaskStageLimits
import io.github.shishark.faceblur.mask.PreparedMask
import io.github.shishark.faceblur.mask.RecordingAllocator
import io.github.shishark.faceblur.mask.SmoothFill
import io.github.shishark.faceblur.mask.StageObserver
import io.github.shishark.faceblur.mask.StageSeams
import io.github.shishark.faceblur.mask.randomMasks
import io.github.shishark.faceblur.mask.stageImage
import java.io.File
import java.lang.management.ManagementFactory
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** JVM-only checks of the bounded mask stage (ADR 0013): allocation in steps 2 and 3, source and KDoc gates. */
class MaskStageJvmTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** Reads the calling thread's allocated bytes at each step boundary; allocates nothing itself. */
    private inner class AllocationProbe : StageObserver {
        val at = LongArray(5)
        private val id = Thread.currentThread().id

        override fun step(step: Int, work: Long) {
            at[step] = threads.getThreadAllocatedBytes(id)
        }
    }

    private fun allocations(w: Int, h: Int, masks: List<MaskGeometry>, style: MaskStyle): Pair<Long, Long> {
        val image = IntArrayPixels(w, h, stageImage(w, h, 3).data)
        val probe = AllocationProbe()
        MaskEngine.apply(image, masks, MaskSettings(style = style), {}, StageSeams(observer = probe, probe = null))
        return (probe.at[3] - probe.at[2]) to (probe.at[4] - probe.at[3])
    }

    /**
     * Step 3 allocates nothing; step 2 only a few small objects of a fixed size (the fill contexts of the call),
     * the same for a 1 MP and a 4 MP plan (the 25 MP plan is in the large-image task). Measured after a warm-up, so
     * class loading and JIT compilation are not counted. Allowance for step 2: 2 KiB (three fill objects and their
     * fields; measured 176 bytes on JDK 17 for every style and size). Allowance for step 3: 0 bytes.
     */
    @Test
    fun stepThreeAllocatesNothingAndStepTwoOnlyAFixedSmallAmount() {
        for (style in MaskStyle.entries) {
            repeat(3) { allocations(500, 400, randomMasks(Random(it), 500, 400, 20, 120f), style) }
            for ((w, h) in listOf(1_000 to 1_000, 2_000 to 2_000)) {
                val masks = listOf(MaskGeometry(w / 2f, h / 2f, w * 0.49f, h * 0.49f, MaskShape.ELLIPSE)) +
                    randomMasks(Random(w), w, h, 30, 150f)
                val (step2, step3) = allocations(w, h, masks, style)
                println("ALLOC $style ${w}x$h: step 2 $step2 bytes, step 3 $step3 bytes")
                assertTrue(step2 <= 2_048, "$style ${w}x$h: step 2 allocated $step2 bytes")
                assertEquals(0L, step3, "$style ${w}x$h: step 3 allocated $step3 bytes")
            }
        }
    }

    @Test
    fun aStackOverflowErrorFromTheAllocatorIsRethrownUnchanged() {
        val error = StackOverflowError("planted")
        val image = IntArrayPixels(200, 100, stageImage(200, 100, 1).data)
        val before = image.data.copyOf()
        val thrown = assertFailsWith<StackOverflowError> {
            MaskEngine.apply(
                image,
                randomMasks(Random(1), 200, 100, 5),
                MaskSettings(),
                {},
                StageSeams(RecordingAllocator(2) { error }, null),
            )
        }
        assertSame(error, thrown)
        assertContentEquals(before, image.data)
    }

    @Test
    fun anOutOfMemoryErrorFromTheImageDuringTheComputationPassesTheFaceMaskerUnchanged() {
        val masker = FaceMasker.forTest(
            io.github.shishark.faceblur.detect.testYuNetModel(),
            FaceMaskerConfig(),
            io.github.shishark.faceblur.detect.RunnerRuntime(io.github.shishark.faceblur.OneFaceRunner()),
        )
        val base = IntArrayPixels(640, 480, stageImage(640, 480, 2).data)
        val before = base.data.copyOf()
        // The reads of detection alone; the mask stage's first read is the next one.
        val counter = io.github.shishark.faceblur.mask.RecordingImage(IntArrayPixels(640, 480, before.copyOf()))
        masker.detect(counter)
        val detectionReads = counter.reads
        var reads = 0
        val image = object : MutablePixelImage {
            override val width = 640
            override val height = 480

            override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
                if (++reads == detectionReads + 1) throw OutOfMemoryError("planted")
                base.readPixels(x, y, w, h, dst)
            }

            override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) = error("no write expected")
        }
        masker.use { assertFailsWith<OutOfMemoryError> { it.maskInPlace(image) } }
        assertContentEquals(before, base.data)
    }

    @Test
    fun theBoundedStageEqualsTheOldEngineOnA4MegapixelImage() {
        val w = 2_000
        val h = 2_000
        val img = stageImage(w, h, 11)
        val plans = listOf(
            listOf(MaskGeometry(1_000f, 1_000f, 990f, 990f, MaskShape.ELLIPSE)),
            listOf(MaskGeometry(1_000f, 1_000f, 990f, 990f, MaskShape.RECTANGLE)),
            randomMasks(Random(4), w, h, 40, 300f),
        )
        for (style in MaskStyle.entries) {
            for (plan in plans) {
                val s = MaskSettings(style = style)
                val old = img.copy()
                val new = img.copy()
                LegacyMaskEngine.apply(old, plan, s)
                MaskEngine.apply(new, plan, s)
                assertContentEquals(old.data, new.data, "$style ${plan.size} masks")
            }
        }
    }

    // ---- Gates on the source ---------------------------------------------------------------------------------------

    private val maskDir = File("src/commonMain/kotlin/io/github/shishark/faceblur/mask")

    /**
     * The stage's files (with the helpers of step 2 in PreparedMask.kt); StageAllocator.kt is the only file allowed to
     * create arrays, and the colour tables (Srgb.kt) are created once, before any reservation.
     */
    private val stageFiles = listOf("MaskEngine.kt", "Fills.kt", "WorkingArea.kt", "MemoryRule.kt", "PreparedMask.kt")

    private val forbidden = Regex(
        listOf(
            """\b(Int|Long|Float|Double|Byte|Short|Char|Boolean)Array\s*\(""",
            """\b(arrayOf|arrayOfNulls|emptyArray|intArrayOf|longArrayOf|floatArrayOf|doubleArrayOf|byteArrayOf)\b""",
            """\bArray\s*\(""",
            """\.copyOf(Range)?\s*\(""",
            """\.(toIntArray|toLongArray|toFloatArray|toByteArray|toTypedArray|toList|toMutableList|toSet)\s*\(""",
            """\b(ArrayList|HashMap|HashSet|LinkedHashMap|ArrayDeque|mutableListOf|listOf|setOf|mapOf|buildList)\b""",
            """\.(map|filter|flatMap|sorted|sortedBy|plus|chunked|windowed|zip)\s*[({]""",
        ).joinToString("|"),
    )

    /** The code of [text] without comments and string literals (KDoc may name the array types). */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex(""""(\\.|[^"\\])*""""), "\"\"")

    private fun violations(text: String): List<String> =
        code(text).lines().mapIndexedNotNull { i, line -> forbidden.find(line)?.let { "line ${i + 1}: ${it.value}" } }

    @Test
    fun theStageFilesCreateNoArrayOutsideTheAllocator() {
        for (name in stageFiles) {
            val file = File(maskDir, name)
            assertTrue(file.isFile, "$name exists")
            assertEquals(emptyList(), violations(file.readText()), "$name: array creation outside the allocator")
        }
        // The allocator itself does create arrays (the gate would see them), and planted lines are found.
        assertTrue(violations(File(maskDir, "StageAllocator.kt").readText()).isNotEmpty())
        for (planted in listOf(
            "val a = IntArray(4)",
            "val b = x.copyOf(3)",
            "val c = arrayOfNulls<IntArray>(2)",
            "val d = list.map { it }",
            "val e = ArrayList<Int>()",
            "val f = FloatArray (n)",
        )) {
            assertTrue(violations(planted).isNotEmpty(), "planted: $planted")
        }
        assertEquals(emptyList(), violations("// IntArray(4) in a comment\nval s = \"IntArray(4)\""))
    }

    private val faceMasker = File("src/commonMain/kotlin/io/github/shishark/faceblur/FaceMasker.kt")

    /** The KDoc block right before the declaration that starts with [declaration]. */
    private fun kdocBefore(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        assertTrue(at > 0, "declaration $declaration found")
        val end = source.lastIndexOf("*/", at)
        val start = source.lastIndexOf("/**", end)
        return source.substring(start, end).lines().joinToString(" ") { it.trim().removePrefix("*").trim() }
    }

    private fun mb(bytes: Long) = String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)

    /** The in-place calls' statement on masking time; it names no figure and promises no documentation figure. */
    private val timeStatement =
        "Masking time depends on the image and the phone; slower phones take longer. Run masking off the main " +
            "thread and offer cancellation."

    @Test
    fun theInPlaceKdocStatesTheWorstCaseMemoryTheContractAndNoTypicalFigure() {
        val source = faceMasker.readText()
        val typical = MaskStageLimits.TYPICAL_BYTES.map { mb(it) }
        val factor = MaskStageLimits.MAX_MASK_AREA_FACTOR
        for (declaration in listOf("public fun applyMasks(", "public fun maskInPlace(")) {
            val doc = kdocBefore(source, declaration)
            for (phrase in listOf(
                "up to 4.3 bytes per image pixel plus 32 MiB, about 141 MB for a 25-megapixel image, whose own " +
                    "pixels take 100 MB",
                "The fixed part is not reserved on every call: it is sized from the plan",
                "The working area is needed per call, not per instance: N calls running in parallel need up to N " +
                    "times this",
                "together with the reservations of the other running calls",
                "If the working area does not fit in the memory that is available now, the call throws " +
                    "`NotEnoughMemory` and the image is untouched.",
                "If the call ends with an error that is not a `FaceMaskException` (for example an " +
                    "`OutOfMemoryError` on Android), discard the image.",
                "If this call throws a `FaceMaskException`, the image is untouched, with two exceptions.",
                "It grows with the number and the size of the masks; in the worst case (masks over almost the whole image)",
                "At most 2 masking calls in parallel on phones is recommended (see the documentation).",
            )) {
                assertTrue(doc.contains(phrase), "$declaration KDoc lacks: $phrase")
            }
            // No typical-photo memory figure in the public KDoc (only the limits above are stated).
            for (figure in typical) assertFalse(doc.contains(figure), "$declaration KDoc: no typical figure $figure")
            assertFalse(doc.contains("for the typical photo"), "$declaration KDoc: no typical-photo sizing")
            // The time statement carries no figure: the values live in the documentation.
            assertTrue(doc.contains(timeStatement), "$declaration KDoc lacks the time statement")
            assertFalse(doc.contains("<recommended"), "$declaration KDoc: no placeholder left")
        }
        assertTrue(
            kdocBefore(source, "public fun applyMasks(").contains(
                "when the masks' summed working area exceeds $factor times the image area (at least $factor " +
                    "times 1,048,576 pixels); nothing is read or written.",
            ),
            "applyMasks @throws names the area limit",
        )
        assertTrue(
            kdocBefore(source, "public fun maskInPlace(").contains(
                "or a summed working area above $factor times the image area), this call throws",
            ),
            "maskInPlace names the area limit",
        )
        val exceptions = File("src/commonMain/kotlin/io/github/shishark/faceblur/FaceMaskException.kt").readText()
        assertTrue(
            kdocBefore(exceptions.substringAfter("public class ModelOutputInvalid"), "public val findings").contains(
                "a summed mask working area above $factor times the image area, at least $factor times 1,048,576 " +
                    "pixels)",
            ),
            "TOO_MANY_MASKS KDoc names the area limit",
        )
        val nem = kdocBefore(exceptions, "public class NotEnoughMemory")
        assertTrue(
            nem.contains(
                "The call needed more memory than was available, and the image was not masked; it is untouched. Do " +
                    "not publish, upload or share the image or the original instead, as if it were masked. Retry " +
                    "later, with less work running in parallel, or with a smaller image.",
            ),
            "NotEnoughMemory KDoc",
        )
        // The worst-case figure in the text equals the caps' bound at 6,000 × 4,166 px.
        val n = 6_000L * 4_166
        val bound = MaskStageLimits.WORST_CASE_BYTES_PER_PIXEL * n + MaskStageLimits.WORST_CASE_FIXED_BYTES
        assertEquals("141", String.format(Locale.ROOT, "%.0f", bound / 1e6))
    }

    /** The largest SMOOTH_FILL base grid over the public fixtures' plans stays below the cell cap (recorded). */
    @Test
    fun theLargestSmoothFillGridOnThePublicFixturesStaysBelowTheCap() {
        var largest = 0L
        var masks = 0
        val masking = MaskingConfig(style = MaskStyle.SMOOTH_FILL)
        val s = masking.toSettings()
        for (img in YuNetReferenceStrictTest.images) {
            val pixels = YuNetReferenceStrictTest.pixels(img)
            val plan = YuNetReferenceStrictTest.masker.detect(pixels).toMaskPlan(masking)
            for (mask in plan.masks) {
                val m = PreparedMask(PreparedMask.usable(mask.toGeometry()), s)
                val write = m.extent(1f + m.featherN, pixels.width, pixels.height)
                if (write.isEmpty) continue
                val read = m.extent(1f + m.featherN + m.ringN, pixels.width, pixels.height).union(write)
                val k = SmoothFill.spacing(m.rx, m.ry, read.width, read.height)
                largest = maxOf(largest, SmoothFill.cells(read.width, read.height, k))
                masks++
            }
        }
        println(
            "SMOOTH_FILL cells: largest base grid $largest over $masks masks of ${YuNetReferenceStrictTest.images.size} fixtures",
        )
        assertTrue(masks > 0)
        assertTrue(largest < MaskStageLimits.MAX_SMOOTH_FILL_CELLS, "largest $largest")
    }

    @Test
    fun theWorkingAreaOfTheFixturesIsReportedForTheRecord() {
        val masking = MaskingConfig()
        var largest = 0L
        for (img in YuNetReferenceStrictTest.images) {
            val pixels = YuNetReferenceStrictTest.pixels(img)
            val plan = YuNetReferenceStrictTest.masker.detect(pixels).toMaskPlan(masking)
            val bytes = MaskEngine.workingAreaBytes(
                pixels.width,
                pixels.height,
                plan.masks.map { it.toGeometry() },
                masking.toSettings(),
            )
            largest = maxOf(largest, bytes)
        }
        println("WORKING AREA on the public fixtures: largest ${mb(largest)}")
        assertTrue(largest > 0)
    }
}
