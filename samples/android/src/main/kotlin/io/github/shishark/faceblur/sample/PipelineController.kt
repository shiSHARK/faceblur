// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** What one run of the in-memory pipeline reports. [picture] is the picture decoded from the encoded result. */
class PipelineOutcome(val faces: Int, val masks: Int, val picture: Any?)

/** The in-memory pipeline of ST-023; runs off the main thread. Nothing is written anywhere. */
interface PipelineEngine<U> {
    fun run(source: U, output: OutputOption, cancelled: () -> Boolean): PipelineOutcome
}

sealed interface PipelineState {
    data object Idle : PipelineState
    data object Running : PipelineState
    class Done(val outcome: PipelineOutcome) : PipelineState
    class Failed(val failure: DemoFailure) : PipelineState
}

/** State of the pipeline screen. Called on the main thread; the work runs on [executor], results come back by [post]. */
class PipelineController<U>(
    private val engine: PipelineEngine<U>,
    private val executor: Executor,
    private val post: (Runnable) -> Unit,
) {
    var state: PipelineState = PipelineState.Idle
        private set

    private var listener: ((PipelineState) -> Unit)? = null
    private var generation = 0
    private var cancel = AtomicBoolean(false)

    fun setListener(l: ((PipelineState) -> Unit)?) {
        listener = l
        l?.invoke(state)
    }

    private fun update(newState: PipelineState) {
        state = newState
        listener?.invoke(newState)
    }

    fun start(source: U, output: OutputOption) {
        if (state is PipelineState.Running) return
        val gen = ++generation
        val flag = AtomicBoolean(false).also { cancel = it }
        update(PipelineState.Running)
        executor.execute {
            val outcome = attempt { engine.run(source, output) { flag.get() } }
            post {
                if (gen != generation) return@post
                update(
                    outcome.fold(
                        onSuccess = { PipelineState.Done(it) },
                        onFailure = { PipelineState.Failed(it as? DemoFailure ?: DemoFailure(FailureKind.OTHER)) },
                    ),
                )
            }
        }
    }

    /** Asks the running pipeline to stop; the result is a "cancelled" failure and nothing is kept. */
    fun cancelRun() {
        if (state is PipelineState.Running) cancel.set(true)
    }

    /**
     * Back to the start. A run that is still going is told to stop and its result is dropped, so that nothing of it
     * stays in memory after the screen is left.
     */
    fun reset() {
        cancel.set(true)
        generation++
        update(PipelineState.Idle)
    }
}
