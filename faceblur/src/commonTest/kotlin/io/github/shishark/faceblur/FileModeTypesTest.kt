// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.MAX_STREAM_SOURCE_BYTES
import io.github.shishark.faceblur.filemode.PlatformCallValue
import io.github.shishark.faceblur.filemode.PlatformSourceValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The public file-mode types of ADR 0015 decisions 2 and 3 and the seam's source type: values, equality, read-only
 * lists, and texts that never show a path or URI.
 */
class FileModeTypesTest {
    private val canary = "/canary/secret-holiday-name.jpg"

    @Test
    fun newFileKeepsItsValuesDefaultsToNoReplaceAndNeverShowsThePath() {
        val plain = OutputTarget.NewFile(canary)
        assertEquals(canary, plain.path)
        assertFalse(plain.replaceExisting, "no replacement unless asked for")
        val replace = OutputTarget.NewFile(canary, replaceExisting = true)
        assertTrue(replace.replaceExisting)

        assertEquals(OutputTarget.NewFile(canary), plain)
        assertEquals(OutputTarget.NewFile(canary).hashCode(), plain.hashCode())
        assertNotEquals<OutputTarget>(replace, plain)
        assertNotEquals<OutputTarget>(OutputTarget.NewFile("/other.jpg"), plain)
        assertNotEquals<Any>("NewFile", plain)

        assertEquals("NewFile(replaceExisting=false)", plain.toString())
        assertEquals("NewFile(replaceExisting=true)", replace.toString())
    }

    @Test
    fun overwriteSourceIsOneValue() {
        assertSame(OutputTarget.OverwriteSource, OutputTarget.OverwriteSource)
        assertEquals("OverwriteSource", OutputTarget.OverwriteSource.toString())
    }

    @Test
    fun aPlatformTargetComparesItsValueAndShowsOnlyTheKind() {
        val uri = "content://canary/secret-holiday-name.jpg"
        val a = OutputTarget.PlatformTarget.create(FakeTargetValue("newFile", uri))
        val same = OutputTarget.PlatformTarget.create(FakeTargetValue("newFile", uri))
        val other = OutputTarget.PlatformTarget.create(FakeTargetValue("overwriteNonAtomic", uri))
        assertEquals(same, a)
        assertEquals(same.hashCode(), a.hashCode())
        assertNotEquals(other, a)
        assertNotEquals<Any>(FakeTargetValue("newFile", uri), a)
        assertEquals("PlatformTarget(newFile)", a.toString())
        assertFalse("canary" in other.toString() || "secret" in other.toString(), other.toString())
    }

    @Test
    fun theTargetHierarchyHasExactlyTheThreeCommonKinds() {
        val targets = listOf(
            OutputTarget.NewFile(canary),
            OutputTarget.OverwriteSource,
            OutputTarget.PlatformTarget.create(FakeTargetValue("newFile", "u")),
        )
        // An exhaustive `when` without `else`: compiles only while these are all the direct subclasses.
        val kinds = targets.map {
            when (it) {
                is OutputTarget.NewFile -> "file"
                OutputTarget.OverwriteSource -> "source"
                is OutputTarget.PlatformTarget -> "platform"
            }
        }
        assertEquals(listOf("file", "source", "platform"), kinds)
        for (t in targets) assertFalse("canary" in t.toString(), t.toString())
    }

    @Test
    fun unsupportedTargetIsAFaceMaskExceptionWithAFixedPrefix() {
        val e: FaceMaskException = FaceMaskException.UnsupportedTarget("the path form cannot write a platform target")
        assertIs<FaceMaskException.UnsupportedTarget>(e)
        assertEquals("Unsupported target: the path form cannot write a platform target", e.message)
        assertEquals(null, e.cause)
    }

    /** The stream cap is 64 MiB; the error keeps the limit and its message names it and the forms to use instead. */
    @Test
    fun sourceTooLargeNamesTheLimitAndTheFormsForLargerInputsAndNoPath() {
        assertEquals(67_108_864L, MAX_STREAM_SOURCE_BYTES)
        val e: FaceMaskException = FaceMaskException.SourceTooLarge(MAX_STREAM_SOURCE_BYTES)
        assertIs<FaceMaskException.SourceTooLarge>(e)
        assertEquals(67_108_864L, e.limitBytes)
        assertEquals(
            "Source stream exceeds the limit of 67,108,864 bytes for streams; use the file (path) or Uri form for " +
                "larger inputs",
            e.message,
        )
        assertEquals(null, e.cause)
        val text = e.message.orEmpty()
        assertFalse('/' in text || '\\' in text, "no path or URI: $text")
        // Boundary values of the Long keep their exact value and the fixed grouping.
        assertEquals(Long.MAX_VALUE, FaceMaskException.SourceTooLarge(Long.MAX_VALUE).limitBytes)
        assertTrue(
            "9,223,372,036,854,775,807 bytes" in FaceMaskException.SourceTooLarge(Long.MAX_VALUE).message.orEmpty(),
        )
        assertTrue("limit of 0 bytes" in FaceMaskException.SourceTooLarge(0L).message.orEmpty())
    }

    /** The per-call context holds its platform value for the call and shows only the platform's kind. */
    @Test
    fun aCallContextHoldsItsPlatformValueAndShowsOnlyTheKind() {
        val pathForm = CallContext(null)
        assertNull(pathForm.platform)
        assertEquals("CallContext(none)", pathForm.toString())
        val value = object : PlatformCallValue {
            override val kind = "android"

            override fun toString() = canary
        }
        val platformForm = CallContext(value)
        assertSame(value, platformForm.platform)
        assertEquals("CallContext(android)", platformForm.toString())
    }

    @Test
    fun detectionOutputSizeDefaultsToTheWorkingSizeAndTakesPartInEquality() {
        val inMemory = detection(640, 480)
        assertEquals(640, inMemory.outputWidth)
        assertEquals(480, inMemory.outputHeight)

        val scaled = detection(640, 480, outputWidth = 320, outputHeight = 240)
        assertEquals(640, scaled.width)
        assertEquals(480, scaled.height)
        assertEquals(320, scaled.outputWidth)
        assertEquals(240, scaled.outputHeight)
        assertEquals(detection(640, 480, outputWidth = 320, outputHeight = 240), scaled)
        assertEquals(detection(640, 480, outputWidth = 320, outputHeight = 240).hashCode(), scaled.hashCode())
        assertNotEquals(inMemory, scaled)
        assertNotEquals(detection(640, 480, outputWidth = 320, outputHeight = 239), scaled)
        assertEquals(
            "Detection(width=640, height=480, outputWidth=320, outputHeight=240, faces=1, timings=$TIMINGS)",
            scaled.toString(),
        )
        // The plan stays in working-image pixels, whatever the output size.
        assertEquals(640, scaled.toMaskPlan().width)
        assertEquals(480, scaled.toMaskPlan().height)
    }

    @Test
    fun fileResultKeepsEveryFieldHasValueEqualityAndReadOnlyLists() {
        val faces = mutableListOf(BOX)
        val masks = mutableListOf(Mask.fromFace(BOX, MaskingConfig()))
        val warnings = mutableListOf("SMALL_INPUT")
        val r = fileResult(faces, masks, warnings)
        assertEquals(3500, r.width)
        assertEquals(2625, r.height)
        assertEquals(4000, r.sourceWidth)
        assertEquals(3000, r.sourceHeight)
        assertEquals(1000, r.outputWidth)
        assertEquals(750, r.outputHeight)
        assertEquals(123_456L, r.outputBytes)
        assertEquals(TIMINGS, r.timings)
        assertTrue(r.gainMapRemoved)
        assertEquals(listOf(BOX), r.faces)
        assertEquals(1, r.masks.size)
        assertEquals(listOf("SMALL_INPUT"), r.warnings)

        faces.clear()
        masks.clear()
        warnings.clear()
        assertEquals(1, r.faces.size, "the result keeps its own copy")
        assertEquals(1, r.masks.size)
        assertEquals(1, r.warnings.size)

        val same =
            fileResult(mutableListOf(BOX), mutableListOf(Mask.fromFace(BOX, MaskingConfig())), listOf("SMALL_INPUT"))
        assertEquals(same, r)
        assertEquals(same.hashCode(), r.hashCode())
        assertNotEquals(fileResult(listOf(BOX), listOf(), listOf("SMALL_INPUT")), r)
        assertNotEquals(fileResult(listOf(BOX), same.masks, listOf("SMALL_INPUT"), outputBytes = 1L), r)
        assertNotEquals(fileResult(listOf(BOX), same.masks, listOf("SMALL_INPUT"), gainMap = false), r)
        assertNotEquals<Any>("FileResult", r)

        assertEquals(
            "FileResult(width=3500, height=2625, sourceWidth=4000, sourceHeight=3000, outputWidth=1000, " +
                "outputHeight=750, outputBytes=123456, faces=1, masks=1, timings=$TIMINGS)",
            r.toString(),
        )
    }

    /** Each field takes part in equality on its own. */
    @Test
    fun fileResultEqualityComparesEverySize() {
        val base = FileResultValues()
        val variants = listOf(
            base.copy(width = 1),
            base.copy(height = 1),
            base.copy(sourceWidth = 1),
            base.copy(sourceHeight = 1),
            base.copy(outputWidth = 1),
            base.copy(outputHeight = 1),
            base.copy(timings = Timings(1.0, null, 0.0, 0, 0.0, null, 1.0, null)),
            base.copy(warnings = emptyList()),
            base.copy(faces = emptyList()),
        )
        for (v in variants) assertNotEquals(base.build(), v.build(), v.toString())
        assertEquals(base.build(), base.build())
    }

    @Test
    fun fileSourcesNeverShowAPathOrUri() {
        assertEquals("FileSource.Path", FileSource.Path(canary).toString())
        assertEquals(canary, FileSource.Path(canary).path)
        val bytes = ByteArray(7)
        val b = FileSource.Bytes(bytes)
        assertSame(bytes, b.bytes, "never copied")
        assertEquals("FileSource.Bytes(size=7)", b.toString())
        val value = object : PlatformSourceValue {
            override val kind = "content"
            override val replaceableInOneStep = false
        }
        val p = FileSource.Platform(value)
        assertSame(value, p.value)
        assertEquals("FileSource.Platform(content)", p.toString())
    }

    private class FakeTargetValue(override val kind: String, val uri: String) : PlatformTargetValue {
        override fun equals(other: Any?): Boolean = other is FakeTargetValue && kind == other.kind && uri == other.uri

        override fun hashCode(): Int = 31 * kind.hashCode() + uri.hashCode()
    }

    // One FileResult per set of values; `copy` changes one field at a time.
    internal data class FileResultValues(
        val width: Int = 3500,
        val height: Int = 2625,
        val sourceWidth: Int = 4000,
        val sourceHeight: Int = 3000,
        val outputWidth: Int = 1000,
        val outputHeight: Int = 750,
        val timings: Timings = TIMINGS,
        val warnings: List<String> = listOf("SMALL_INPUT"),
        val faces: List<FaceBox> = listOf(BOX),
    ) {
        fun build() = FileResult(
            width,
            height,
            sourceWidth,
            sourceHeight,
            outputWidth,
            outputHeight,
            1L,
            faces,
            emptyList(),
            timings,
            false,
            warnings,
        )
    }

    private fun fileResult(
        faces: List<FaceBox>,
        masks: List<Mask>,
        warnings: List<String>,
        outputBytes: Long = 123_456L,
        gainMap: Boolean = true,
    ) = FileResult(3500, 2625, 4000, 3000, 1000, 750, outputBytes, faces, masks, TIMINGS, gainMap, warnings)

    private fun detection(w: Int, h: Int, outputWidth: Int = w, outputHeight: Int = h) = Detection(
        width = w,
        height = h,
        faces = listOf(BOX),
        timings = TIMINGS,
        hasGainMap = false,
        masking = MaskingConfig(),
        warnings = emptyList(),
        outputWidth = outputWidth,
        outputHeight = outputHeight,
    )

    private companion object {
        val BOX: FaceBox = FaceBox.create(100f, 100f, 200f, 220f, 0.9f)
        val TIMINGS = Timings(1.0, 2.0, 3.0, 4, 5.0, 6.0, 21.0, null)
    }
}
