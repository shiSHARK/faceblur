// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.eval

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.jvm.Fixtures
import io.github.shishark.faceblur.jvm.JvmTestRuntime
import io.github.shishark.faceblur.jvm.OrtModelRuntime
import io.github.shishark.faceblur.jvm.TestModels
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The exact threshold sweep (DX-MA-001, ST-053 AC2): every threshold equals a full rerun with it in the profile. */
class EvalSweepTest {
    @BeforeTest
    fun install() = JvmTestRuntime.install()

    /** Four lossless reference fixtures as one 1,280 x 1,280 photo: tile passes, cut faces and the merge all run. */
    private fun mosaic(): IntArrayPixels {
        val parts = listOf(
            "pd/pd-p01-s035-x01339-y00967.png",
            "pd/pd-p07-s026-x00183-y00018.png",
            "images/face-0029247-s0625-x0000-y0000.png",
            "pd/pd-p06-s035-x00145-y00379.png",
        ).map { Fixtures.image(it) }
        val out = IntArrayPixels(1280, 1280)
        for ((i, p) in parts.withIndex()) {
            val ox = (i % 2) * 640
            val oy = (i / 2) * 640
            for (y in 0 until 640) p.data.copyInto(out.data, (oy + y) * 1280 + ox, y * 640, y * 640 + 640)
        }
        return out
    }

    @Test
    fun eachThresholdEqualsABruteForceRerunAndTheModelRunsOncePerPass() {
        val thresholds = listOf(0.7f, 0.3f, 0.5f)
        val img = mosaic()
        ThresholdSweep(TestModels.yunet(0.5f), thresholds, FaceMaskerConfig(), OrtModelRuntime()).use { sweep ->
            assertEquals(listOf(0.3f, 0.5f, 0.7f), sweep.thresholds)
            val creationRuns = sweep.modelRuns
            assertEquals(3, creationRuns, "one contract run per threshold")
            val results = sweep.run(img).map { it.getOrThrow() }
            val passes = results.first().timings.passes
            assertTrue(passes > 1, "the mosaic is tiled")
            assertEquals(creationRuns + passes, sweep.modelRuns, "the model ran once per pass, not once per threshold")
            val counts = ArrayList<Int>()
            for ((i, t) in sweep.thresholds.withIndex()) {
                FaceMasker(TestModels.yunet(t)).use { brute ->
                    val d = brute.detect(img)
                    assertEquals(d.faces, results[i].faces, "threshold $t: boxes and scores bit-identical")
                    assertEquals(d.toMaskPlan().masks, results[i].toMaskPlan().masks, "threshold $t: same masks")
                    counts += d.faces.size
                }
            }
            assertTrue(counts[0] > counts[2], "the thresholds differ in what they find: $counts")
            // A second image reuses the instance with a fresh recording.
            val again = sweep.run(img).map { it.getOrThrow().faces }
            assertEquals(results.map { it.faces }, again)
        }
    }

    /** One face (score 0.9) and, on photo inputs only, a row with score 0.6 whose box is not finite. */
    private class BrokenRowRunner(private val brokenTopFace: Boolean = false) : TensorRunner by OneFaceRunner() {
        override fun run(input: FloatArray): Map<String, FloatArray> {
            val m = OneFaceRunner().run(input)
            if (input.all { it == input[0] }) return m // the contract run on grey stays valid
            val idx = 10 * 20 + 10
            m.getValue("cls_32")[idx] = 0.6f
            m.getValue("obj_32")[idx] = 0.6f
            m.getValue("bbox_32")[idx * 4] = Float.NaN
            if (brokenTopFace) m.getValue("bbox_32")[(4 * 20 + 4) * 4] = Float.NaN
            return m
        }
    }

    @Test
    fun aThresholdThatFailsIsReportedAndTheOthersStayExact() {
        val img = IntArrayPixels(640, 640, gradientPixels(640, 640))
        ThresholdSweep(testYuNetModel(), listOf(0.5f, 0.7f), FaceMaskerConfig(), RunnerRuntime(BrokenRowRunner())).use {
            val (low, high) = it.run(img)
            assertEquals(1, high.getOrThrow().faces.size, "0.7 skips the broken 0.6 row")
            val e = low.exceptionOrNull() as FaceMaskException.ModelOutputInvalid
            assertEquals(listOf("NON_FINITE"), e.findings)
            assertEquals(2 + 1, it.modelRuns, "the failed replay did not rerun the model")
        }
        // The recording run fails too: the other thresholds rerun the model (still exact, just slower).
        ThresholdSweep(
            testYuNetModel(),
            listOf(0.5f, 0.7f),
            FaceMaskerConfig(),
            RunnerRuntime(BrokenRowRunner(true)),
        ).use {
            val results = it.run(img)
            assertTrue(results.all { r -> r.exceptionOrNull() is FaceMaskException.ModelOutputInvalid })
            assertEquals(2 + 2, it.modelRuns)
        }
    }

    @Test
    fun aReplayMismatchThroughAFaceMaskerStopsTheSweep() {
        val a = IntArrayPixels(640, 640, gradientPixels(640, 640))
        val other = IntArrayPixels(640, 640, IntArray(640 * 640) { 0xFF000000.toInt() or (it * 7919) })
        val tiled = IntArrayPixels(1280, 1280, gradientPixels(1280, 1280))
        // Fake runtime: the library hides the replay's error behind RUN_FAILED; the sweep must still stop.
        for (replayed in listOf(other, tiled)) {
            ThresholdSweep(
                testYuNetModel(),
                listOf(0.5f, 0.7f),
                FaceMaskerConfig(),
                RunnerRuntime(OneFaceRunner()),
            ) { replayed }.use { sweep ->
                assertFailsWith<ReplayMismatch> { sweep.run(a) }
            }
        }
        // The real reference model through the public masker path, same rule.
        ThresholdSweep(TestModels.yunet(0.5f), listOf(0.3f, 0.5f), FaceMaskerConfig(), OrtModelRuntime()) { other }
            .use { sweep -> assertFailsWith<ReplayMismatch> { sweep.run(a) } }
        // Control: the same seam returning the image itself gives a normal result.
        ThresholdSweep(testYuNetModel(), listOf(0.5f, 0.7f), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner())) { it }
            .use { sweep -> assertTrue(sweep.run(a).all { it.isSuccess }) }
    }

    @Test
    fun theReplayRefusesAnythingButTheRecordedPasses() {
        val replay = ReplayRuntime(RunnerRuntime(OneFaceRunner()))
        val session = replay.open(testYuNetModel(), 0)
        val a = FloatArray(640 * 640 * 3) { 1f }
        val b = FloatArray(640 * 640 * 3) { 2f }
        val shape = longArrayOf(1, 3, 640, 640)
        val names = session.outputs.map { it.name }
        replay.record()
        val recorded = session.run(a, shape, names, Long.MAX_VALUE, 0)
        replay.stop()
        replay.replay()
        assertTrue(recorded === session.run(a, shape, names, Long.MAX_VALUE, 0), "the stored outputs are returned")
        assertFailsWith<IllegalStateException> { session.run(a, shape, names, Long.MAX_VALUE, 0) }
        replay.replay()
        assertFailsWith<IllegalStateException> { session.run(b, shape, names, Long.MAX_VALUE, 0) }
        replay.replay()
        assertFailsWith<IllegalStateException> { replay.finishReplay(allUsed = true) }
        replay.replay()
        replay.finishReplay(allUsed = false)
        assertEquals(1, replay.modelRuns)
        session.close()
        replay.close()
        assertFailsWith<IllegalArgumentException> {
            ThresholdSweep(testYuNetModel(), emptyList(), FaceMaskerConfig(), RunnerRuntime(OneFaceRunner()))
        }
    }
}
