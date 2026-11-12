// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.net.Uri
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlanMasker
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.maskFile
import io.github.shishark.faceblur.planFile
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android `Context` + `Uri` forms of [PlanMasker] (ADR 0015 decision 6) on the host: the Android codec and writer
 * on the host's real file system ([HostFiles]) are the only platform parts; the platform defaults have NO runtime and
 * NO model reader, so a call that needed ONNX Runtime would fail with `RuntimeUnavailable`. The device test
 * `PlanMaskerDeviceTest` checks on the phone that ONNX Runtime is not loaded.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PlanMaskerAndroidTest {
    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()
    private lateinit var dir: File

    @Before
    fun setUp() {
        FaceBlurRuntime.overrideDefaults(
            PlatformDefaults(
                imageCodec = AndroidImageCodec.using(HostFiles),
                targetWriter = AndroidTargetWriter.using(HostFiles),
            ),
        )
        dir = Files.createTempDirectory("faceblur-plan").toFile()
    }

    @After
    fun tearDown() {
        FaceBlurRuntime.overrideDefaults(null)
        dir.deleteRecursively()
    }

    private fun write(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    @Test
    fun aFileUriIsPlannedAndMaskedWithTheAppsBoxesWithoutARuntime() {
        val source = TestImages.jpeg(640, 480)
        val input = write("in.jpg", source)
        val out = File(dir, "out.jpg")
        val planMasker = PlanMasker()
        val empty = planMasker.planFile(context, Uri.fromFile(input))
        assertEquals(640 to 480, empty.width to empty.height, "the working size")
        assertEquals(emptyList(), empty.masks, "nothing is detected")
        assertFalse(out.exists(), "planFile writes nothing")
        val plan = empty.withMasks(listOf(Mask.manual(200f, 120f, 360f, 300f, planMasker.masking)))
        val result = planMasker.maskFile(context, Uri.fromFile(input), OutputTarget.NewFile(out.path), plan)
        assertEquals(emptyList(), result.faces, "PlanMasker detects nothing")
        assertEquals(plan.masks, result.masks)
        // The reference is made after the PlanMasker calls, with a fake runner (no ONNX Runtime in either case).
        val unmasked = PixelChecks.decode(source)
        val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
        val reference = try {
            PixelChecks.reference(unmasked) { masker.applyMasks(it, MaskPlan(it.width, it.height, plan.masks)) }
        } finally {
            masker.close()
        }
        TestImages.assertMaskedFile(out.readBytes(), reference, unmasked, result.masks)
        assertEquals(emptyList(), dir.list()!!.filter { it.contains(".faceblur-") }, "no temporary file is left")
        // The Context + Uri form and the common path form give the same plan for the same file and options.
        assertEquals(empty, planMasker.planFile(input.path))
        // Without a runtime, a FaceMasker cannot be made: the route above really ran without one.
        assertFailsWith<FaceMaskException.RuntimeUnavailable> { FaceMasker(testYuNetModel()) }
    }

    @Test
    fun aSourceChangedAfterPlanFileIsRefusedAndNothingIsWritten() {
        val input = write("in.jpg", TestImages.jpeg(320, 240))
        val out = File(dir, "out.jpg")
        val planMasker = PlanMasker()
        val plan = planMasker.planFile(context, Uri.fromFile(input))
            .withMasks(listOf(Mask.manual(10f, 10f, 100f, 100f)))
        // Same size, other pixels.
        val other = TestImages.picture(320, 240).apply { eraseColor(0xFF204080.toInt()) }
        input.outputStream().use { assertTrue(other.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it)) }
        assertFailsWith<FaceMaskException.SourceChanged> {
            planMasker.maskFile(context, Uri.fromFile(input), OutputTarget.NewFile(out.path), plan)
        }
        assertFalse(out.exists(), "nothing is written")
        // A plan without a binding is refused before the source is opened.
        val unbound = MaskPlan(320, 240, plan.masks)
        assertFailsWith<FaceMaskException.MaskPlanMismatch> {
            planMasker.maskFile(
                context,
                Uri.fromFile(File(dir, "missing.jpg")),
                OutputTarget.NewFile(out.path),
                unbound,
            )
        }
        assertFalse(out.exists())
        // Another scheme is refused with fixed text.
        val e = assertFailsWith<FaceMaskException.IoFailed> {
            planMasker.planFile(context, Uri.parse("https://example.invalid/a.jpg"))
        }
        assertFalse(e.message.orEmpty().contains("example.invalid"))
        assertTrue(e.cause == null)
    }
}
