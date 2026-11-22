// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.FakeSession
import io.github.shishark.faceblur.detect.Limits
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.filemode.FileModeFakes
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.AbandonedRuns
import io.github.shishark.faceblur.internal.runtime.DeadlineSession
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.internal.runtime.SessionTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A model run that never returns (it blocks without CPU) ends the call at its limit with the existing
 * timeout error; nothing of that run is used again, the next call gets a new session and correct output, file mode
 * writes nothing, a blocked contract run fails creation with `TIMEOUT`, and stuck runs are bounded (fail closed).
 */
class HardRunLimitTest {
    private val release = Release()
    private val sessions = ArrayList<BlockingSession>()

    /** Opens [BlockingSession]s over the one-face runner; session number i (0-based) blocks from run [blockFrom] (i). */
    private fun runtime(blockFrom: (Int) -> Int) = ModelRuntime { model, _ ->
        BlockingSession(FakeSession(OneFaceRunner(), model), release, blockFrom(sessions.size)).also { sessions += it }
    }

    private fun image() = IntArrayPixels(640, 480, gradientPixels(640, 480))

    /** Short limits for the tests that do not check the production values (contract run 1 s, passes 300 ms). */
    private val short = RunLimits(contractMillis = 1_000L, minPassMillis = 300L)

    @AfterTest
    fun cleanUp() {
        release.open()
        assertNoAbandonedRunsLeft()
    }

    @Test
    fun aPassThatNeverReturnsEndsTheCallAtItsLimitAndTheNextCallGetsANewSession() {
        // The first session blocks in its first pass after the contract run; later sessions never block.
        val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), runtime { if (it == 0) 2 else NEVER })
        val img = image()
        val before = img.data.copyOf()
        var e: FaceMaskException.ModelOutputInvalid? = null
        val took = millisOf { e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.maskInPlace(img) } }
        assertEquals(listOf("RUN_TIMEOUT"), e?.findings)
        val limit = sessions[0].blockedLimit
        assertTrue(limit >= Limits.MIN_RUN_TIMEOUT_MILLIS, "the production pass limit applies: $limit")
        assertTrue(took >= limit && took <= limit + TIMEOUT_MARGIN_MILLIS, "ended at the limit: $took ms, $limit ms")
        assertContentEquals(before, img.data, "nothing was written into the image")
        assertEquals(1, sessions.size)
        assertEquals(0, sessions[0].closes, "the stuck session is left to its pass thread")
        assertEquals(1, AbandonedRuns.count())

        val result = masker.maskInPlace(img)
        assertEquals(2, sessions.size, "the next call opened a new session")
        assertEquals(2, sessions[0].runs, "the stuck session never ran again")
        val reference = image()
        val expected = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner())).use {
            it.maskInPlace(reference)
        }
        assertEquals(expected.masks, result.masks)
        assertEquals(1, result.masks.size)
        assertContentEquals(reference.data, img.data, "the same output as a normal run")

        // The stuck run returns at last: its session is closed by its own pass thread and its output discarded.
        release.open()
        assertTrue(sessions[0].awaitClosed(10_000L), "the abandoned session is closed when its run returns")
        assertNoAbandonedRunsLeft()
        masker.close()
        assertEquals(1, sessions[1].closes)
        assertEquals(1, sessions[0].closes, "closing the masker does not touch the abandoned session")
    }

    @Test
    fun aContractRunThatNeverReturnsFailsCreationWithTimeoutWithinItsLimit() {
        var e: FaceMaskException.ModelRejected? = null
        val took = millisOf {
            e = assertFailsWith<FaceMaskException.ModelRejected> {
                FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), runtime { 1 }, runLimits = short)
            }
        }
        assertEquals(listOf("TIMEOUT"), e?.findings)
        assertEquals(short.contractMillis, sessions[0].blockedLimit)
        assertTrue(took <= short.contractMillis + TIMEOUT_MARGIN_MILLIS, "ended at the limit: $took ms")
        assertEquals(0, sessions[0].closes, "the stuck session is not closed under its running pass")
        release.open()
        assertTrue(sessions[0].awaitClosed(10_000L))
        assertNoAbandonedRunsLeft()
    }

    @Test
    fun fileModeWritesNothingOnATimeoutAndTheNextCallWritesTheNormalOutput() {
        val fakes = FileModeFakes()
        FaceBlurRuntime.overrideDefaults(PlatformDefaults.NONE)
        FaceBlurRuntime.installImageCodec(fakes.codec)
        FaceBlurRuntime.installTargetWriter(fakes.writer)
        try {
            val options = FileOptions(maxLongSide = 0)
            val target = OutputTarget.NewFile("/out/a.jpg")
            val masker =
                FaceMasker.forTest(
                    testYuNetModel(),
                    FaceMaskerConfig(),
                    runtime { if (it == 0) 2 else NEVER },
                    runLimits = short,
                )
            val took = millisOf {
                val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> {
                    masker.maskFile("/photos/a.jpg", target, options)
                }
                assertEquals(listOf("RUN_TIMEOUT"), e.findings)
            }
            val limit = sessions[0].blockedLimit
            assertTrue(took <= limit + TIMEOUT_MARGIN_MILLIS, "ended at the limit: $took ms, $limit ms")
            assertTrue(fakes.writes.isEmpty(), "nothing written: ${fakes.events}")
            assertFalse("write" in fakes.events, fakes.events.toString())
            assertEquals(0, fakes.openImages, "every image is closed: ${fakes.events}")

            masker.maskFile("/photos/a.jpg", target, options)
            assertEquals(1, fakes.writes.size)
            val masked = fakes.encodedPixels.last()
            masker.close()
            FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner())).use {
                it.maskFile("/photos/a.jpg", target, options)
            }
            assertEquals(2, fakes.writes.size)
            assertContentEquals(fakes.encodedPixels.last(), masked, "the same pixels as a normal run")
            assertContentEquals(fakes.writes[1], fakes.writes[0])
        } finally {
            FaceBlurRuntime.installImageCodec(null)
            FaceBlurRuntime.installTargetWriter(null)
            FaceBlurRuntime.overrideDefaults(null)
        }
    }

    @Test
    fun stuckRunsAreBoundedAndAtTheBoundEveryNewRunFailsClosed() {
        // Each call on this masker opens a new session whose first pass blocks: one more stuck run per call.
        val masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), runtime { 2 }, runLimits = short)
        repeat(AbandonedRuns.MAX) {
            val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.detect(image()) }
            assertEquals(listOf("RUN_TIMEOUT"), e.findings)
        }
        assertEquals(AbandonedRuns.MAX, AbandonedRuns.count())
        val opened = sessions.size
        // At the bound no run starts: the call fails at once (its new session is refused before its contract run and
        // closed), and so does a creation.
        val took = millisOf {
            val e = assertFailsWith<FaceMaskException.ModelOutputInvalid> { masker.detect(image()) }
            assertEquals(listOf("RUN_TIMEOUT"), e.findings)
        }
        assertTrue(took < short.minPassMillis, "refused without waiting: $took ms")
        val healthy = runtime { NEVER }
        val e = assertFailsWith<FaceMaskException.ModelRejected> {
            FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), healthy, runLimits = short)
        }
        assertEquals(listOf("TIMEOUT"), e.findings)
        assertEquals(2, sessions.size - opened)
        assertTrue(sessions.drop(opened).all { it.runs == 0 && it.closes == 1 }, "no run started; closed at once")

        release.open()
        assertNoAbandonedRunsLeft()
        assertTrue(sessions.take(AbandonedRuns.MAX).all { it.closes == 1 }, "every stuck session was closed")
        masker.close()
    }

    @Test
    fun aTimeoutReportedByTheRuntimeRetiresTheSessionAndClosesItAtOnce() {
        var closes = 0
        var runs = 0
        val inner = object : ModelSession by FakeSession(OneFaceRunner(), testYuNetModel()) {
            override fun run(
                input: FloatArray,
                inputShape: LongArray,
                outputNames: List<String>,
                maxElements: Long,
                timeoutMillis: Long,
            ): Map<String, PassOutput> {
                runs++
                throw SessionTimeout()
            }

            override fun close() {
                closes++
            }
        }
        val session = DeadlineSession(inner)
        assertFailsWith<SessionTimeout> { session.run(FloatArray(1), longArrayOf(1), emptyList(), 1L, 5_000L) }
        assertTrue(session.retired)
        assertEquals(1, closes, "closed at once: its run has ended")
        assertFailsWith<SessionTimeout> { session.run(FloatArray(1), longArrayOf(1), emptyList(), 1L, 5_000L) }
        assertEquals(1, runs, "a retired session never runs again")
        session.close()
        assertEquals(1, closes)
        assertEquals(0, AbandonedRuns.count())
    }

    @Test
    fun aRunThatEndsAfterItsDeadlineIsATimeoutAndItsOutputIsDiscarded() {
        val model = testYuNetModel()
        val stuck = BlockingSession(FakeSession(OneFaceRunner(), model), release, 1)
        val session = DeadlineSession(stuck)
        val took = millisOf {
            assertFailsWith<SessionTimeout> {
                session.run(FloatArray(640 * 640 * 3), longArrayOf(1, 3, 640, 640), listOf("cls_8"), 1L shl 22, 200L)
            }
        }
        assertTrue(took in 200L..200L + TIMEOUT_MARGIN_MILLIS, "$took ms")
        assertTrue(session.retired)
        session.close()
        assertEquals(0, stuck.closes, "close of the retired session leaves the inner one to its pass thread")
        assertEquals(1, AbandonedRuns.count())
        release.open()
        assertTrue(stuck.awaitClosed(10_000L))
        assertNoAbandonedRunsLeft()
        assertEquals(1, stuck.runs)
    }

    @Test
    fun aRunWithinItsLimitPassesItsOutputAndErrorsUnchanged() {
        val model = testYuNetModel()
        val plain = FakeSession(OneFaceRunner(), model)
        val session = DeadlineSession(FakeSession(OneFaceRunner(), model))
        val input = FloatArray(640 * 640 * 3)
        val shape = longArrayOf(1, 3, 640, 640)
        val names = listOf("cls_8", "bbox_8")
        val want = plain.run(input, shape, names, 1L shl 22, 0L)
        val got = session.run(input, shape, names, 1L shl 22, 5_000L)
        assertEquals(want.keys, got.keys)
        for (k in want.keys) assertContentEquals(want.getValue(k).data, got.getValue(k).data)
        assertFalse(session.retired)
        val failing = DeadlineSession(
            object : ModelSession by plain {
                override fun run(
                    input: FloatArray,
                    inputShape: LongArray,
                    outputNames: List<String>,
                    maxElements: Long,
                    timeoutMillis: Long,
                ): Map<String, PassOutput> = throw IllegalStateException("runtime text")
            },
        )
        assertFailsWith<IllegalStateException> { failing.run(input, shape, names, 1L shl 22, 5_000L) }
        assertFalse(failing.retired, "only a timeout retires a session")
    }

    private companion object {
        const val NEVER = Int.MAX_VALUE
    }
}
