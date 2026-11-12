// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.system.ErrnoException
import android.system.OsConstants
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskingConfig
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.internal.runtime.RuntimeOptionFailed
import io.github.shishark.faceblur.internal.runtime.SessionTensor
import io.github.shishark.faceblur.internal.runtime.platformDefaults
import io.github.shishark.faceblur.mask.MemoryRule
import io.github.shishark.faceblur.mask.ReservedBytes
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Android binding without a setup call (ADR 0015 decision 1, ADR 0007 decision 2, ADR 0013 decision 6): the
 * platform defaults hold the remaining-heap probe, the model-file reader, the runtime and the file-mode codec and
 * writer with NO install call; the runtime sets ORT_DISABLE_TELEMETRY=1 first when it opens a session, and a failure
 * to set it fails closed. The process environment is a recorder here (host tests have no libcore natives); the device test
 * checks the real variable. Robolectric supplies the platform classes (`ErrnoException`, `OsConstants`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidBindingTest {
    private class RecordingEnvironment(private val failSet: Boolean = false, private val readBack: String? = null) :
        ProcessEnvironment {
        val values = HashMap<String, String>()
        val calls = ArrayList<String>()

        override fun set(name: String, value: String) {
            calls += "set $name"
            if (failSet) throw ErrnoException("setenv", OsConstants.EINVAL)
            values[name] = value
        }

        override fun get(name: String): String? {
            calls += "get $name"
            return readBack ?: values[name]
        }
    }

    private var previousEnvironment: ProcessEnvironment = OsEnvironment

    @Before
    fun saveState() {
        previousEnvironment = AndroidBinding.environment
        FaceBlurRuntime.uninstall()
        FaceBlurRuntime.installModelFiles(null)
        FaceBlurRuntime.installMemoryProbe(null)
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        FaceBlurRuntime.overrideDefaults(null)
    }

    @After
    fun restoreState() {
        AndroidBinding.environment = previousEnvironment
        FaceBlurRuntime.uninstall()
        FaceBlurRuntime.installModelFiles(null)
        FaceBlurRuntime.installMemoryProbe(null)
        FaceBlurRuntime.installImageCodec(null)
        FaceBlurRuntime.installTargetWriter(null)
        FaceBlurRuntime.overrideDefaults(null)
    }

    @Test
    fun withoutAnyInstallCallTheDefaultsHoldEveryAndroidBinding() {
        val env = RecordingEnvironment()
        AndroidBinding.environment = env
        // Nothing was installed (see saveState): every lookup reaches the platform defaults.
        assertSame(AndroidHeapProbe, FaceBlurRuntime.memoryProbe(), "the probe is present without any call")
        assertEquals(MemoryRule.HEAP_REMAINING, FaceBlurRuntime.memoryProbe()?.rule)
        assertEquals(Runtime.getRuntime().maxMemory(), FaceBlurRuntime.memoryProbe()?.figure())
        assertSame(AndroidModelFileReader, FaceBlurRuntime.modelFiles())
        assertSame(AndroidModelRuntime, FaceBlurRuntime.runtime())
        assertSame(AndroidImageCodec.DEFAULT, FaceBlurRuntime.imageCodec())
        assertSame(AndroidTargetWriter.DEFAULT, FaceBlurRuntime.targetWriter())
        assertNull(FaceBlurRuntime.digest(), "the common SHA-256 stays in use")
        assertSame(platformDefaults(), platformDefaults(), "the same immutable value on every call")
        // Looking the bindings up sets nothing and touches no ONNX Runtime type; the runtime sets the variable when
        // it opens a session (openingASessionSetsTheVariableFirst... below).
        assertEquals(emptyList(), env.calls)
        // An explicit installation wins over the defaults.
        val probe = HeapRemainingFake()
        FaceBlurRuntime.installMemoryProbe(probe)
        assertSame(probe, FaceBlurRuntime.memoryProbe())
        // The test switch turns the defaults off.
        FaceBlurRuntime.installMemoryProbe(null)
        FaceBlurRuntime.overrideDefaults(PlatformDefaults.NONE)
        try {
            assertNull(FaceBlurRuntime.runtime())
            assertNull(FaceBlurRuntime.imageCodec())
            assertNull(FaceBlurRuntime.targetWriter())
        } finally {
            FaceBlurRuntime.overrideDefaults(null)
        }
        assertSame(AndroidModelRuntime, FaceBlurRuntime.runtime())
    }

    private class HeapRemainingFake : io.github.shishark.faceblur.mask.MemoryProbe {
        override val rule: MemoryRule get() = MemoryRule.HEAP_REMAINING

        override fun figure(): Long = 1L

        override fun inUse(): Long = 0L

        override fun collect() = Unit
    }

    @Test
    fun aVariableThatCannotBeSetFailsClosed() {
        AndroidBinding.environment = RecordingEnvironment(failSet = true)
        assertFailsWith<RuntimeOptionFailed> { AndroidBinding.disableOrtTelemetry() }
    }

    @Test
    fun aVariableThatDoesNotReadBackAsOneFailsClosed() {
        AndroidBinding.environment = RecordingEnvironment(readBack = "0")
        assertFailsWith<RuntimeOptionFailed> { AndroidBinding.disableOrtTelemetry() }
    }

    @Test
    fun creatingAMaskerWithoutTheVariableIsRuntimeUnavailableAndOpensNoSession() {
        // The binding sets the variable before it touches the runtime: when that fails, creation fails closed.
        AndroidBinding.environment = RecordingEnvironment(failSet = true)
        val e =
            assertFailsWith<FaceMaskException.RuntimeUnavailable> {
                FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), AndroidModelRuntime)
            }
        assertTrue(e.message!!.contains("session option"), e.message)
    }

    @Test
    fun theAndroidProbeReportsTheHeapFiguresOfThisProcess() {
        val rt = Runtime.getRuntime()
        assertEquals(rt.maxMemory(), AndroidHeapProbe.figure())
        val inUse = AndroidHeapProbe.inUse()
        assertTrue(inUse > 0 && inUse <= rt.totalMemory(), "in use: $inUse")
        AndroidHeapProbe.collect()
        assertEquals(rt.availableProcessors(), processorCount())
    }

    @Test
    fun openingASessionSetsTheVariableFirstAndHandsEachSessionItsOwnViewOfOneModelBuffer() {
        val env = RecordingEnvironment()
        AndroidBinding.environment = env
        ModelBuffer.sharedMemoryAvailable = false // the host cannot run the shared-memory copy
        try {
            val model = testYuNetModel()
            val seen = ArrayList<ByteBuffer>()
            val factory = { buffer: ByteBuffer, threads: Int ->
                assertEquals("1", env.values["ORT_DISABLE_TELEMETRY"], "set before the runtime is touched")
                assertEquals(3, threads)
                seen += buffer
                FakeSession()
            }
            AndroidModelRuntime.open(model, 3, factory)
            seen[0].get(ByteArray(10)) // a session that moves its view does not move the next one's
            AndroidModelRuntime.open(model, 3, factory)
            assertEquals(2, seen.size)
            assertNotSame(seen[0], seen[1], "one duplicate per session")
            assertEquals(0, seen[1].position())
            assertTrue(seen.all { it.isDirect && it.isReadOnly })
            val bytes = model.verifiedBytes()
            assertEquals(bytes.size, seen[1].remaining())
            assertContentEquals(bytes, ByteArray(bytes.size).also { seen[1].duplicate().get(it) })
        } finally {
            ModelBuffer.sharedMemoryAvailable = true
        }
    }

    @Test
    fun noSessionIsOpenedWhenTheVariableCannotBeSet() {
        AndroidBinding.environment = RecordingEnvironment(failSet = true)
        var opened = false
        assertFailsWith<RuntimeOptionFailed> {
            AndroidModelRuntime.open(testYuNetModel(), 0) { _, _ ->
                opened = true
                FakeSession()
            }
        }
        assertFalse(opened)
    }

    /** A session that is never run. */
    private class FakeSession : ModelSession {
        override val runtimeVersion = "test"
        override val executionProvider = "test"
        override val inputs = emptyList<SessionTensor>()
        override val outputs = emptyList<SessionTensor>()

        override fun run(
            input: FloatArray,
            inputShape: LongArray,
            outputNames: List<String>,
            maxElements: Long,
            timeoutMillis: Long,
        ): Map<String, PassOutput> = error("not run")

        override fun close() = Unit
    }

    /** An image that stores nothing and records writes. */
    private class VirtualImage(override val width: Int, override val height: Int) : MutablePixelImage {
        var writes = 0

        override fun readPixels(x: Int, y: Int, w: Int, h: Int, dst: IntArray) = dst.fill(-0x1000000)

        override fun writePixels(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
            writes++
        }
    }

    @Test
    fun withTheAndroidProbeAWorkingAreaAboveTheHeapIsRefusedBeforeAnyAllocationOrWrite() {
        AndroidBinding.environment = RecordingEnvironment()
        // No install call: the Android probe comes from the platform defaults.
        FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner())).use { masker ->
            // About 2.1 gigapixels covered by one mask: the working area (several bytes per pixel) is far above the
            // heap of the test JVM, so the pre-check refuses it; a caught out-of-memory error would carry a cause.
            val image = VirtualImage(46_000, 46_000)
            val plan =
                MaskPlan(image.width, image.height, listOf(Mask.manual(0f, 0f, 46_000f, 46_000f, MaskingConfig())))
            val e = assertFailsWith<FaceMaskException.NotEnoughMemory> { masker.applyMasks(image, plan) }
            assertNull(e.cause, "refused by the pre-check, not by a caught out-of-memory error")
            assertEquals(0, image.writes, "the image is untouched")
            assertEquals(0L, ReservedBytes.current(), "nothing stays reserved")
        }
    }
}
