// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Kotlin side of the iOS bridge (ADR 0010 decisions 1 and 4, "Output contract" and "Contract" verification) with
 * fake glues that speak the real address/table protocol. No runtime is needed.
 */
class IosBridgeContractTest {
    private fun factory(make: () -> FakeBridge) = FakeFactory(make)

    private fun masker(f: FakeFactory) = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), IosModelRuntime(f))

    private fun image() = IntArrayPixels(640, 480, gradientPixels(640, 480))

    @Test
    fun aWorkingGlueDetectsAndReceivesTheVerifiedModelBytes() {
        val model = testYuNetModel()
        val f = factory { FakeBridge(OneFaceRunner()) }
        val m = FaceMasker.forTest(model, FaceMaskerConfig(), IosModelRuntime(f))
        val bridge = f.made.single()
        assertEquals(model.info.sha256, bridge.openedSha256, "the glue got exactly the verified bytes")
        assertContentEquals(longArrayOf(1, 3, 640, 640), bridge.shape)
        assertEquals("1.20.0", m.modelInfo.runtimeVersion)
        assertEquals("CPU", m.modelInfo.executionProvider)
        val found = m.detect(image())
        assertEquals(1, found.faces.size)
        assertTrue(bridge.runs >= 2, "contract run + a pass")
        m.close()
        m.close()
        assertEquals(1, bridge.closes, "closed exactly once")
        assertFailsWith<FaceMaskException.Closed> { m.detect(image()) }
    }

    @Test
    fun openStatusCodesMapToTheDocumentedExceptions() {
        fun rejected(status: Int) = assertFailsWith<FaceMaskException.ModelRejected> {
            masker(factory { FakeBridge(OneFaceRunner(), openStatus = status) })
        }.findings
        assertEquals(listOf("RUNTIME_REJECTED"), rejected(1))
        assertEquals(listOf("IO_MISMATCH"), rejected(6))
        assertFailsWith<FaceMaskException.RuntimeUnavailable> {
            masker(factory { FakeBridge(OneFaceRunner(), openStatus = 7) })
        }
        for (status in listOf(4, 5, 99)) {
            val f = factory { FakeBridge(OneFaceRunner(), openStatus = status) }
            assertFailsWith<FaceMaskException.Internal> { masker(f) }
            assertEquals(1, f.made.single().closes, "a failed open closes the glue")
        }
    }

    @Test
    fun aWrongRuntimeVersionIsRefusedBeforeTheModelReachesTheGlue() {
        val f = factory { FakeBridge(OneFaceRunner(), version = "1.24.0") }
        val e = assertFailsWith<FaceMaskException.RuntimeUnavailable> { masker(f) }
        assertTrue(e.message!!.contains("1.20.0"))
        assertEquals(0, f.made.single().opens)
        assertEquals(1, f.made.single().closes)
    }

    @Test
    fun faultsInTheContractRunRejectTheModel() {
        for (fault in listOf(Fault.WRITE_NOTHING, Fault.SKIP_ONE, Fault.FLOAT64, Fault.STATUS_2)) {
            val f = factory { FakeBridge(OneFaceRunner(), fault = fault) }
            val e = assertFailsWith<FaceMaskException.ModelRejected>(fault.name) { masker(f) }
            assertTrue(e.findings.single().startsWith("CONTRACT_"), "${fault.name}: ${e.findings}")
        }
    }

    @Test
    fun everyOutputTableFaultInAPassFailsTheCallWithoutAResult() {
        val expected =
            mapOf(
                Fault.WRITE_NOTHING to "OUTPUT_SHAPE",
                Fault.WRITE_SHORT to "OUTPUT_SHAPE",
                Fault.LEAVE_SENTINEL to "OUTPUT_SHAPE",
                Fault.SKIP_ONE to "OUTPUT_SHAPE",
                Fault.FLOAT64 to "OUTPUT_TYPE",
                Fault.STATUS_3_FLOAT64 to "OUTPUT_TYPE",
                Fault.RANK_FIVE to "OUTPUT_SHAPE",
                Fault.WRONG_DIMS to "OUTPUT_SHAPE",
                Fault.OVER_CAPACITY to "OUTPUT_SHAPE",
                Fault.STATUS_2 to "RUN_FAILED",
            )
        for ((fault, code) in expected) {
            val m = masker(factory { FakeBridge(OneFaceRunner(), fault = fault, faultFrom = 2) })
            val img = image()
            val before = img.data.copyOf()
            val e = assertFailsWith<FaceMaskException.ModelOutputInvalid>(fault.name) { m.maskInPlace(img) }
            assertTrue(code in e.findings, "${fault.name}: ${e.findings}")
            assertContentEquals(before, img.data, "${fault.name}: no pixel written")
            m.close()
        }
    }

    @Test
    fun glueFaultsAreInternalErrorsAndNeverOtherExceptions() {
        for (fault in listOf(Fault.STATUS_4, Fault.STATUS_5)) {
            val m = masker(factory { FakeBridge(OneFaceRunner(), fault = fault, faultFrom = 2) })
            assertFailsWith<FaceMaskException.Internal>(fault.name) { m.detect(image()) }
            m.close()
        }
        // A glue that throws: only a FaceMaskException leaves the public call (ADR 0011 decision 8 rule 6).
        val f = factory { FakeBridge(OneFaceRunner(), throwAtOpen = true) }
        assertFailsWith<FaceMaskException.Internal> { masker(f) }
        assertEquals(1, f.made.single().closes)
        val throwing = masker(factory { FakeBridge(OneFaceRunner(), fault = Fault.THROW, faultFrom = 2) })
        val e = assertFailsWith<FaceMaskException> { throwing.detect(image()) }
        assertTrue(e is FaceMaskException.ModelOutputInvalid, "$e")
        throwing.close()
        val failingFactory = FakeFactory { throw IllegalStateException("factory fault") }
        assertFailsWith<FaceMaskException.Internal> { masker(failingFactory) }
    }

    @Test
    fun theTableLayoutConstantIsNineLongs() {
        assertEquals(9, OnnxBridge.OUTPUT_ROW_LONGS)
    }
}
