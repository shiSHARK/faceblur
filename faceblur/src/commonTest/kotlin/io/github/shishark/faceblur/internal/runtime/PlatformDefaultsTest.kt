// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.internal.runtime

import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlatformTargetValue
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.detect.yunetProfile
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.ImageCodec
import io.github.shishark.faceblur.filemode.TargetWriter
import io.github.shishark.faceblur.mask.FixedProbe
import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.MemoryRule
import io.github.shishark.faceblur.mask.StageSeams
import io.github.shishark.faceblur.testmodel.Onnx
import io.github.shishark.faceblur.testmodel.pinnedTo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * ADR 0015 decision 1: every registry lookup returns the explicitly installed value, otherwise the platform default;
 * the test switch replaces or turns off the defaults. The fake defaults stand in for a platform's (the Android actual
 * is filled by the Android binding); each test restores the registry.
 */
class PlatformDefaultsTest {
    private val runtime = RunnerRuntime(OneFaceRunner())
    private val files = ModelFileReader { _, _ -> Onnx.yunetModel() }
    private val probe = FixedProbe(MemoryRule.HEAP_REMAINING, 1L)
    private val digest = ModelDigest { ByteArray(32) }
    private val codec = ImageCodec { _, _ -> throw FaceMaskException.UnsupportedImage("fake") }
    private val writer = NoOpWriter()
    private val fake = PlatformDefaults(runtime, files, probe, digest, codec, writer)

    private var saved: Saved? = null

    @BeforeTest
    fun save() {
        FaceBlurRuntime.overrideDefaults(PlatformDefaults.NONE)
        saved = Saved.take()
        FaceBlurRuntime.overrideDefaults(null)
    }

    @AfterTest
    fun restore() {
        FaceBlurRuntime.overrideDefaults(null)
        saved?.put()
    }

    @Test
    fun withNothingInstalledEveryLookupReturnsThePlatformDefault() {
        Saved.clear()
        FaceBlurRuntime.overrideDefaults(fake)
        assertSame(runtime, FaceBlurRuntime.runtime())
        assertSame(files, FaceBlurRuntime.modelFiles())
        assertSame(probe, FaceBlurRuntime.memoryProbe())
        assertSame(digest, FaceBlurRuntime.digest())
        assertSame(codec, FaceBlurRuntime.imageCodec())
        assertSame(writer, FaceBlurRuntime.targetWriter())
    }

    @Test
    fun anExplicitInstallationWinsAndRemovingItGoesBackToTheDefault() {
        Saved.clear()
        FaceBlurRuntime.overrideDefaults(fake)
        val runtime2 = RunnerRuntime(OneFaceRunner())
        val files2 = ModelFileReader { _, _ -> ByteArray(0) }
        val probe2 = FixedProbe(MemoryRule.AVAILABLE_ON_SIMULATOR, 1L)
        val digest2 = ModelDigest { ByteArray(32) }
        val codec2 = ImageCodec { _, _ -> throw FaceMaskException.UnsupportedImage("fake 2") }
        val writer2 = NoOpWriter()
        FaceBlurRuntime.install(runtime2)
        FaceBlurRuntime.installModelFiles(files2)
        FaceBlurRuntime.installMemoryProbe(probe2)
        FaceBlurRuntime.installDigest(digest2)
        FaceBlurRuntime.installImageCodec(codec2)
        FaceBlurRuntime.installTargetWriter(writer2)
        assertSame(runtime2, FaceBlurRuntime.runtime())
        assertSame(files2, FaceBlurRuntime.modelFiles())
        assertSame(probe2, FaceBlurRuntime.memoryProbe())
        assertSame(digest2, FaceBlurRuntime.digest())
        assertSame(codec2, FaceBlurRuntime.imageCodec())
        assertSame(writer2, FaceBlurRuntime.targetWriter())

        Saved.clear()
        assertSame(runtime, FaceBlurRuntime.runtime())
        assertSame(files, FaceBlurRuntime.modelFiles())
        assertSame(probe, FaceBlurRuntime.memoryProbe())
        assertSame(digest, FaceBlurRuntime.digest())
        assertSame(codec, FaceBlurRuntime.imageCodec())
        assertSame(writer, FaceBlurRuntime.targetWriter())
    }

    @Test
    fun theTestSwitchTurnsTheDefaultsOffAndNullRestoresThePlatformsOwn() {
        Saved.clear()
        FaceBlurRuntime.overrideDefaults(fake)
        FaceBlurRuntime.overrideDefaults(PlatformDefaults.NONE)
        assertNull(FaceBlurRuntime.runtime())
        assertNull(FaceBlurRuntime.modelFiles())
        assertNull(FaceBlurRuntime.memoryProbe())
        assertNull(FaceBlurRuntime.digest())
        assertNull(FaceBlurRuntime.imageCodec())
        assertNull(FaceBlurRuntime.targetWriter())

        FaceBlurRuntime.overrideDefaults(null)
        val platform = platformDefaults()
        assertSame(platform, platformDefaults(), "the actual returns the same value on every call")
        assertSame(platform.runtime, FaceBlurRuntime.runtime())
        assertSame(platform.modelFiles, FaceBlurRuntime.modelFiles())
        assertSame(platform.memoryProbe, FaceBlurRuntime.memoryProbe())
        assertSame(platform.digest, FaceBlurRuntime.digest())
        assertSame(platform.imageCodec, FaceBlurRuntime.imageCodec())
        assertSame(platform.targetWriter, FaceBlurRuntime.targetWriter())
    }

    @Test
    fun noneHasNoBinding() {
        val none = PlatformDefaults.NONE
        assertNull(none.runtime)
        assertNull(none.modelFiles)
        assertNull(none.memoryProbe)
        assertNull(none.digest)
        assertNull(none.imageCodec)
        assertNull(none.targetWriter)
    }

    /** The factory keeps every value it is given at its own slot; a value left out is `null`. */
    @Test
    fun theFactoryKeepsEachValueAtItsSlotAndLeavesTheOthersNull() {
        assertSame(runtime, fake.runtime)
        assertSame(files, fake.modelFiles)
        assertSame(probe, fake.memoryProbe)
        assertSame(digest, fake.digest)
        assertSame(codec, fake.imageCodec)
        assertSame(writer, fake.targetWriter)
        val onlyWriter = PlatformDefaults(targetWriter = writer)
        assertSame(writer, onlyWriter.targetWriter)
        assertNull(onlyWriter.runtime)
        assertNull(onlyWriter.modelFiles)
        assertNull(onlyWriter.memoryProbe)
        assertNull(onlyWriter.digest)
        assertNull(onlyWriter.imageCodec)
        val empty = PlatformDefaults()
        assertNull(empty.runtime ?: empty.modelFiles ?: empty.memoryProbe ?: empty.digest ?: empty.imageCodec)
        assertNull(empty.targetWriter)
    }

    /** The public entry points reach the defaults without a setup call: `FaceMasker(model)`, `ModelSource.fromFile`. */
    @Test
    fun thePublicEntryPointsUseTheDefaultsWithoutAnySetupCall() {
        Saved.clear()
        FaceBlurRuntime.overrideDefaults(PlatformDefaults.NONE)
        assertFailsWith<FaceMaskException.RuntimeUnavailable> { FaceMasker(testYuNetModel()) }
        assertFailsWith<FaceMaskException.IoFailed> {
            DetectorModel.load(ModelSource.fromFile("model.onnx"), yunetProfile())
        }

        val pinned = yunetProfile().pinnedTo(Onnx.yunetModel())
        FaceBlurRuntime.overrideDefaults(fake)
        // The fake default digest returns 32 zero bytes, so the pin fails: the loader used the default digest.
        assertFailsWith<FaceMaskException.ModelIntegrity> { DetectorModel.load(ModelSource.fromFile("m"), pinned) }

        FaceBlurRuntime.overrideDefaults(PlatformDefaults(runtime, files, probe))
        val model = DetectorModel.load(ModelSource.fromFile("model.onnx"), pinned)
        FaceMasker(model).use { assertEquals(1, runtime.opened, "the default runtime opened the session") }
        assertSame(probe, StageSeams().probe, "the mask stage reads the default probe")
    }

    /** A writer that is only looked up, never called. */
    private class NoOpWriter : TargetWriter {
        override fun check(target: OutputTarget, source: FileSource, call: CallContext) = Unit

        override fun write(target: OutputTarget, source: FileSource, bytes: ByteArray, call: CallContext) = Unit

        override fun accepts(value: PlatformTargetValue): Boolean = false

        override fun absolutePath(path: String): String = path
    }

    /** The explicitly installed values (read with the defaults off), restored after each test. */
    private class Saved(
        val runtime: ModelRuntime?,
        val files: ModelFileReader?,
        val probe: MemoryProbe?,
        val digest: ModelDigest?,
        val codec: ImageCodec?,
        val writer: TargetWriter?,
    ) {
        fun put() {
            runtime?.let { FaceBlurRuntime.install(it) } ?: FaceBlurRuntime.uninstall()
            FaceBlurRuntime.installModelFiles(files)
            FaceBlurRuntime.installMemoryProbe(probe)
            FaceBlurRuntime.installDigest(digest)
            FaceBlurRuntime.installImageCodec(codec)
            FaceBlurRuntime.installTargetWriter(writer)
        }

        companion object {
            fun take() = Saved(
                FaceBlurRuntime.runtime(),
                FaceBlurRuntime.modelFiles(),
                FaceBlurRuntime.memoryProbe(),
                FaceBlurRuntime.digest(),
                FaceBlurRuntime.imageCodec(),
                FaceBlurRuntime.targetWriter(),
            )

            fun clear() = Saved(null, null, null, null, null, null).put()
        }
    }
}
