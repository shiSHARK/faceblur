// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.FakeSession
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.RuntimeOptionFailed
import io.github.shishark.faceblur.internal.runtime.SessionRejected
import io.github.shishark.faceblur.internal.runtime.SessionTensor
import io.github.shishark.faceblur.internal.runtime.SessionTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * After a run timeout the next call opens a replacement session. Every way this can fail other than a time limit is
 * reported to that call as `ModelOutputInvalid` with the finding `RUN_FAILED` (a time limit is `RUN_TIMEOUT`, covered
 * by [HardRunLimitTest]): the runtime refusing the model, a failed required option, any other runtime error, a model
 * check that finds a problem. The error is never chained (the runtime's text can hold paths), the image is untouched,
 * and the call after it opens a new session and masks correctly.
 */
class ReplacementSessionFailureTest {
    private val sessions = ArrayList<ModelSession>()
    private var opens = 0

    /** Session 0 reports a run timeout from its first pass; [replacement] decides what opening session n >= 1 does. */
    private fun runtime(replacement: (Int) -> ModelSession): ModelRuntime = ModelRuntime { model, _ ->
        val n = opens++
        val session = if (n == 0) timingOutSession(model) else replacement(n)
        session.also { sessions += it }
    }

    /** A healthy session until it is armed by the contract run: every later run reports a timeout from the runtime. */
    private fun timingOutSession(model: DetectorModel): ModelSession {
        val inner = FakeSession(OneFaceRunner(), model)
        return object : ModelSession by inner {
            var runs = 0

            override fun run(
                input: FloatArray,
                inputShape: LongArray,
                outputNames: List<String>,
                maxElements: Long,
                timeoutMillis: Long,
            ): Map<String, PassOutput> {
                // Run 1 is the contract run at creation; the first pass of a call is run 2.
                if (++runs >= 2) throw SessionTimeout()
                return inner.run(input, inputShape, outputNames, maxElements, timeoutMillis)
            }
        }
    }

    private fun healthy(): ModelSession = FakeSession(OneFaceRunner(), testYuNetModel())

    private fun image() = IntArrayPixels(640, 480, gradientPixels(640, 480))

    /** Drives the masker to the replacement: the first call times out, the second call's replacement fails. */
    private fun assertReplacementFailsWithRunFailed(replacement: (Int) -> ModelSession) {
        val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), runtime(replacement))
        val img = image()
        val before = img.data.copyOf()
        val first = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
        assertEquals(listOf("RUN_TIMEOUT"), first.findings, "the run the runtime stopped")

        val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
        assertEquals(listOf("RUN_FAILED"), e.findings, "a replacement that cannot be opened is not a timeout")
        assertNull(e.cause, "the runtime's error is never chained")
        assertContentEquals(before, img.data, "nothing was written into the image")
        assertEquals(2, opens, "the failed replacement was tried once")

        // The failure does not stay: the next call opens a new session and gives the output of a normal run.
        openingFails = false
        val result = masker.maskInPlace(img)
        assertEquals(3, opens, "the next call tried again")
        val reference = image()
        val expected = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner())).use {
            it.maskInPlace(reference)
        }
        assertEquals(expected.masks, result.masks)
        assertContentEquals(reference.data, img.data, "the same output as a normal run")
        masker.close()
    }

    /** True while the replacement under test fails; the test switches it off before the recovery call. */
    private var openingFails = true

    private fun failingThenHealthy(failure: () -> ModelSession): (Int) -> ModelSession = {
        if (openingFails) failure() else healthy()
    }

    @Test
    fun aReplacementTheRuntimeRefusesIsRunFailed() = assertReplacementFailsWithRunFailed(
        failingThenHealthy { throw SessionRejected() },
    )

    @Test
    fun aReplacementWhoseRequiredOptionCannotBeSetIsRunFailed() = assertReplacementFailsWithRunFailed(
        failingThenHealthy { throw RuntimeOptionFailed() },
    )

    @Test
    fun aReplacementThatFailsWithAnyOtherRuntimeErrorIsRunFailedWithoutItsText() = assertReplacementFailsWithRunFailed(
        failingThenHealthy { throw IllegalStateException("/private/path/to/model.onnx could not be opened") },
    )

    @Test
    fun aReplacementThatFailsTheModelCheckWithoutATimeoutIsRunFailed() = assertReplacementFailsWithRunFailed(
        failingThenHealthy {
            // Opens, but reports no outputs: the runtime view of the model does not match the model file.
            val inner = FakeSession(OneFaceRunner(), testYuNetModel())
            object : ModelSession by inner {
                override val outputs = emptyList<SessionTensor>()
            }
        },
    )

    @Test
    fun aFailedReplacementIsClosedWhenItWasOpenedAndRefused() {
        // The model check fails after the session exists: the library closes it (nothing is left open).
        var closes = 0
        val masker = FaceMasker.forTest(
            testYuNetModel(),
            FaceMaskerConfig(),
            runtime {
                val inner = FakeSession(OneFaceRunner(), testYuNetModel())
                object : ModelSession by inner {
                    override val outputs = emptyList<SessionTensor>()

                    override fun close() {
                        closes++
                        inner.close()
                    }
                }
            },
        )
        val img = image()
        assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
        val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) }
        assertEquals(listOf("RUN_FAILED"), e.findings)
        assertEquals(1, closes, "the replacement that failed its check was closed")
        assertTrue(sessions.size == 2)
        masker.close()
    }
}
