// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PipelineControllerTest {
    private class FakePipeline : PipelineEngine<String> {
        val calls = mutableListOf<String>()
        var failure: Throwable? = null
        var duringRun: () -> Unit = {}
        var cancelSeen = false
        val outcome = PipelineOutcome(2, 3, "picture")

        override fun run(source: String, output: OutputOption, cancelled: () -> Boolean): PipelineOutcome {
            calls += "$source:${output.name}"
            duringRun()
            cancelSeen = cancelled()
            failure?.let { throw it }
            return outcome
        }
    }

    private val engine = FakePipeline()
    private val queue = ArrayDeque<Runnable>()
    private val controller = PipelineController(engine, DirectExecutor) { queue.add(it) }

    @Test
    fun `a run goes idle, running, done and hands over the outcome`() {
        val seen = mutableListOf<PipelineState>()
        controller.setListener { seen += it }
        controller.start("p", OutputOption.LONG_SIDE_2048)
        assertIs<PipelineState.Running>(controller.state)
        queue.removeFirst().run()
        val done = assertIs<PipelineState.Done>(controller.state)
        assertSame(engine.outcome, done.outcome)
        assertEquals(listOf("p:LONG_SIDE_2048"), engine.calls)
        assertEquals(3, seen.size) // idle on attach, running, done
    }

    @Test
    fun `a second start while running is ignored`() {
        controller.start("a", OutputOption.SAME_SIZE)
        controller.start("b", OutputOption.SAME_SIZE)
        assertEquals(listOf("a:SAME_SIZE"), engine.calls)
    }

    @Test
    fun `a failure of the engine is shown and an unknown exception becomes the generic failure`() {
        engine.failure = DemoFailure(FailureKind.IMAGE_TOO_LARGE)
        controller.start("p", OutputOption.SAME_SIZE)
        queue.removeFirst().run()
        assertEquals(FailureKind.IMAGE_TOO_LARGE, assertIs<PipelineState.Failed>(controller.state).failure.kind)
        controller.reset()
        engine.failure = IllegalStateException("x")
        controller.start("p", OutputOption.SAME_SIZE)
        queue.removeFirst().run()
        assertEquals(FailureKind.OTHER, assertIs<PipelineState.Failed>(controller.state).failure.kind)
    }

    @Test
    fun `cancel reaches the engine`() {
        engine.duringRun = { controller.cancelRun() }
        controller.start("p", OutputOption.SAME_SIZE)
        assertTrue(engine.cancelSeen)
    }

    @Test
    fun `cancel when nothing runs does nothing`() {
        controller.cancelRun()
        assertIs<PipelineState.Idle>(controller.state)
    }

    @Test
    fun `leaving the screen while a run goes on drops its result`() {
        controller.start("p", OutputOption.SAME_SIZE)
        controller.reset()
        assertIs<PipelineState.Idle>(controller.state)
        queue.removeFirst().run()
        assertIs<PipelineState.Idle>(controller.state)
    }

    @Test
    fun `reset after a result goes back to the start`() {
        controller.start("p", OutputOption.SAME_SIZE)
        queue.removeFirst().run()
        controller.reset()
        assertIs<PipelineState.Idle>(controller.state)
    }
}
