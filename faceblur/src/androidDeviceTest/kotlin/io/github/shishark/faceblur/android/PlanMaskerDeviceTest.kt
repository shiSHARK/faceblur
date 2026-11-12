// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR 0015 decision 6 on the phone: `PlanMasker` (masks from the app's own detector) works without loading ONNX
 * Runtime: no session is opened through the runtime slot and ONNX Runtime's native library is not mapped. Both checks run
 * in a process of their own ([OrtProbeProvider]); the instrumentation process has loaded ONNX Runtime for other tests,
 * so it cannot be the subject. The positive control shows that the same probe counts the session and sees the native
 * library once a `FaceMasker` is created.
 */
@RunWith(AndroidJUnit4::class)
class PlanMaskerDeviceTest {
    private fun call(authority: String): Bundle {
        DeviceFixtures.logHeader()
        val r = DeviceFixtures.context.contentResolver.call(OrtProbeProvider.uri(authority), "run", null, null)
        val results = assertNotNull(r, "the probe process answered")
        val before = mapped(results, OrtProbeProvider.BEFORE)
        val after = mapped(results, OrtProbeProvider.AFTER)
        val sessions = results.getInt(OrtProbeProvider.SESSIONS)
        Log.i(DeviceFixtures.TAG, "probe $authority: library before=$before after=$after sessions=$sessions")
        assertNotEquals(Process.myPid(), results.getInt(OrtProbeProvider.PID), "the probe ran in another process")
        assertEquals("", results.getString(OrtProbeProvider.ERROR), "the work of the probe process succeeded")
        return results
    }

    private fun mapped(results: Bundle, prefix: String): Boolean =
        results.getBoolean(prefix + OrtProbeProvider.NATIVE_LIBRARY)

    @Test
    fun planMaskerMasksAFileWithoutLoadingOnnxRuntime() {
        val r = call(PlanMaskerProcessProvider.AUTHORITY)
        assertTrue(r.getInt(PlanMaskerProcessProvider.WROTE) > 0, "a masked file was written")
        val core = r.getInt(PlanMaskerProcessProvider.MASK_CORE)
        val color = r.getInt(PlanMaskerProcessProvider.MASK_COLOR)
        for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)) {
            assertTrue(abs(channel(core) - channel(color)) <= 12, "the mask core has the mask colour")
        }
        assertEquals(0, r.getInt(OrtProbeProvider.SESSIONS), "PlanMasker opened no runtime session")
        assertFalse(mapped(r, OrtProbeProvider.BEFORE), "no ONNX Runtime library before")
        assertFalse(mapped(r, OrtProbeProvider.AFTER), "no ONNX Runtime library after PlanMasker")
    }

    @Test
    fun theProbeSeesOnnxRuntimeInAProcessThatCreatedAFaceMasker() {
        val r = call(OrtControlProcessProvider.AUTHORITY)
        assertFalse(mapped(r, OrtProbeProvider.BEFORE), "a fresh process has no ONNX Runtime library")
        assertEquals(1, r.getInt(OrtProbeProvider.SESSIONS), "the FaceMasker opened one session through the slot")
        assertTrue(mapped(r, OrtProbeProvider.AFTER), "after a FaceMasker the probe sees the ONNX Runtime library")
    }
}
