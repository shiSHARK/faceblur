// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

sealed interface ModelState {
    data object None : ModelState
    data object Reading : ModelState
    class Confirm(val candidate: ModelCandidate) : ModelState
    data object Loading : ModelState
    class Ready(val name: String) : ModelState
    class Failed(val failure: DemoFailure) : ModelState
}

/** The review of one photo: the masks that will be applied, the undo history and a mask being placed. */
class Review(
    val analysis: Analysis,
    val masks: List<MaskItem>,
    val undo: List<List<MaskItem>> = emptyList(),
    val placing: Box? = null,
    val nextId: Int = analysis.masks.size,
) {
    val addedCount: Int get() = masks.count { it.origin == Origin.ADDED }
}

sealed interface PhotoState {
    data object Idle : PhotoState
    data object Detecting : PhotoState
    class Reviewing(val review: Review) : PhotoState
    class Saving(val review: Review) : PhotoState
    class Done(val outcome: SaveOutcome) : PhotoState
    class Failed(val failure: DemoFailure) : PhotoState
}

/** What the status line says. The activity maps these to strings; nothing here is user data. */
sealed interface Status {
    data object ChooseModelFirst : Status
    data object ModelReading : Status
    data object ModelConfirm : Status
    data object ModelLoading : Status
    data object ModelReady : Status
    class ModelFailed(val failure: DemoFailure) : Status
    data object Detecting : Status
    class Detected(val faces: Int, val masks: Int) : Status
    class Removed(val masks: Int) : Status
    class Added(val masks: Int) : Status
    class Undone(val masks: Int) : Status
    data object Placing : Status
    data object PlacingCancelled : Status
    data object Saving : Status
    class Saved(val faces: Int, val masks: Int) : Status
    data object TooLateToCancel : Status
    data object CancelledNothingChanged : Status
    class Failed(val failure: DemoFailure) : Status
}

data class UiState(
    val model: ModelState = ModelState.None,
    val output: OutputOption = OutputOption.SAME_SIZE,
    val photo: PhotoState = PhotoState.Idle,
    val status: Status = Status.ChooseModelFirst,
    /** True while a cancellable run (detecting or saving) is going on. */
    val cancelRequested: Boolean = false,
) {
    val photoStepsEnabled: Boolean
        get() = model is ModelState.Ready && photo !is PhotoState.Detecting && photo !is PhotoState.Saving
}

/**
 * The demo's state machine. All public functions are called on the main thread; work runs on [executor] and its
 * results come back through [post] (the main thread). Nothing here touches the library or Android types: the
 * [engine] does. The photo itself is never changed: masks are applied by the library from the source file when the
 * user saves, so every undo starts from the original pixels (the "pristine copy" is the source file).
 */
class DemoController<U>(
    private val engine: Engine<U>,
    private val executor: Executor,
    private val post: (Runnable) -> Unit,
) {
    var state: UiState = UiState()
        private set

    private var listener: ((UiState) -> Unit)? = null
    private var generation = 0
    private var cancel = AtomicBoolean(false)
    private var source: U? = null

    fun setListener(l: ((UiState) -> Unit)?) {
        listener = l
        l?.invoke(state)
    }

    private fun update(newState: UiState) {
        state = newState
        listener?.invoke(newState)
    }

    // ---- model step -------------------------------------------------------------------------------------------

    fun chooseModel(file: U) {
        if (state.model is ModelState.Reading || state.model is ModelState.Loading) return
        if (state.photo is PhotoState.Detecting || state.photo is PhotoState.Saving) return
        val gen = ++generation
        update(state.copy(model = ModelState.Reading, status = Status.ModelReading))
        executor.execute {
            val outcome = attempt { engine.readModel(file) }
            post {
                if (gen != generation) return@post
                outcome.fold(
                    onSuccess = { update(state.copy(model = ModelState.Confirm(it), status = Status.ModelConfirm)) },
                    onFailure = { modelFailed(it) },
                )
            }
        }
    }

    /** "Use this model": only now the confirmed hash goes on to the library. */
    fun useModel() {
        val confirm = state.model as? ModelState.Confirm ?: return
        val gen = ++generation
        update(state.copy(model = ModelState.Loading, status = Status.ModelLoading))
        executor.execute {
            val outcome = attempt { engine.loadModel(confirm.candidate) }
            post {
                if (gen != generation) return@post
                outcome.fold(
                    onSuccess = {
                        update(
                            state.copy(
                                model = ModelState.Ready(confirm.candidate.name),
                                photo = PhotoState.Idle,
                                status = Status.ModelReady,
                            ),
                        )
                    },
                    onFailure = { modelFailed(it) },
                )
            }
        }
    }

    /** Every cancel path loads nothing and drops the bytes that were read. */
    fun cancelModel() {
        if (state.model !is ModelState.Confirm) return
        generation++
        update(state.copy(model = ModelState.None, status = Status.ChooseModelFirst))
    }

    private fun modelFailed(error: Throwable) {
        val failure = error.asFailure()
        update(state.copy(model = ModelState.Failed(failure), status = Status.ModelFailed(failure)))
    }

    // ---- photo step -------------------------------------------------------------------------------------------

    fun setOutput(option: OutputOption) {
        if (!state.photoStepsEnabled && state.model is ModelState.Ready) return
        update(state.copy(output = option))
    }

    fun choosePhoto(photo: U) {
        if (!state.photoStepsEnabled) return
        source = photo
        val option = state.output
        val token = AtomicBoolean(false)
        cancel = token
        val gen = ++generation
        update(state.copy(photo = PhotoState.Detecting, status = Status.Detecting, cancelRequested = false))
        executor.execute {
            val outcome = attempt { engine.analyse(photo, option) { token.get() } }
            post {
                if (gen != generation) return@post
                outcome.fold(
                    onSuccess = {
                        val review = Review(it, it.masks)
                        update(
                            state.copy(
                                photo = PhotoState.Reviewing(review),
                                status = Status.Detected(it.faces.size, it.masks.size),
                                cancelRequested = false,
                            ),
                        )
                    },
                    onFailure = { photoFailed(it, PhotoState.Idle) },
                )
            }
        }
    }

    /** "Try again" after a failed detection (for example not enough memory): the same photo, the same settings. */
    fun retry() {
        val photo = source ?: return
        if (state.photo is PhotoState.Failed) choosePhoto(photo)
    }

    /** Cancel during Detecting or Saving; it only asks, the outcome comes from the library's result. */
    fun cancelRun() {
        if (state.photo !is PhotoState.Detecting && state.photo !is PhotoState.Saving) return
        cancel.set(true)
        update(state.copy(cancelRequested = true))
    }

    // ---- review: edit list (buttons, no gestures) ------------------------------------------------------------

    fun removeMask(id: Int) {
        val review = reviewing() ?: return
        val remaining = review.masks.filterNot { it.id == id }
        if (remaining.size == review.masks.size) return
        setReview(Review(review.analysis, remaining, review.undo + listOf(review.masks), review.placing, review.nextId))
        update(state.copy(status = Status.Removed(remaining.size)))
    }

    fun undo() {
        val review = reviewing() ?: return
        if (review.undo.isEmpty()) return
        val previous = review.undo.last()
        setReview(Review(review.analysis, previous, review.undo.dropLast(1), review.placing, review.nextId))
        update(state.copy(status = Status.Undone(previous.size)))
    }

    fun beginAdd() {
        val review = reviewing() ?: return
        val a = review.analysis
        setReview(
            Review(a, review.masks, review.undo, Placement.initial(a.width, a.height), review.nextId),
        )
        update(state.copy(status = Status.Placing))
    }

    fun movePlacing(stepsX: Int, stepsY: Int) = changePlacing { box, a ->
        Placement.move(box, stepsX, stepsY, a.width, a.height)
    }

    fun resizePlacing(larger: Boolean) = changePlacing { box, a ->
        val factor = if (larger) Placement.SIZE_FACTOR else 1f / Placement.SIZE_FACTOR
        Placement.resize(box, factor, a.width, a.height)
    }

    private fun changePlacing(change: (Box, Analysis) -> Box) {
        val review = reviewing() ?: return
        val box = review.placing ?: return
        setReview(
            Review(
                review.analysis,
                review.masks,
                review.undo,
                change(box, review.analysis),
                review.nextId,
            ),
        )
    }

    fun confirmAdd() {
        val review = reviewing() ?: return
        val box = review.placing ?: return
        val outcome = attempt { engine.manualMask(review.analysis, review.nextId, box) }
        outcome.fold(
            onSuccess = { item ->
                val masks = review.masks + item
                setReview(Review(review.analysis, masks, review.undo + listOf(review.masks), null, review.nextId + 1))
                update(state.copy(status = Status.Added(masks.size)))
            },
            onFailure = {
                setReview(Review(review.analysis, review.masks, review.undo, null, review.nextId))
                update(state.copy(status = Status.Failed(it.asFailure())))
            },
        )
    }

    fun cancelAdd() {
        val review = reviewing() ?: return
        if (review.placing == null) return
        setReview(Review(review.analysis, review.masks, review.undo, null, review.nextId))
        update(state.copy(status = Status.PlacingCancelled))
    }

    private fun reviewing(): Review? = (state.photo as? PhotoState.Reviewing)?.review

    private fun setReview(review: Review) {
        update(state.copy(photo = PhotoState.Reviewing(review)))
    }

    // ---- save -------------------------------------------------------------------------------------------------

    /** "Apply and save": [target] is a new, empty document the user chose. */
    fun save(target: U) {
        val review = reviewing() ?: return
        if (review.placing != null) return
        val photo = source ?: return
        val option = state.output
        val token = AtomicBoolean(false)
        cancel = token
        val gen = ++generation
        update(state.copy(photo = PhotoState.Saving(review), status = Status.Saving, cancelRequested = false))
        executor.execute {
            val outcome = attempt {
                engine.save(photo, target, review.analysis, review.masks, option) { token.get() }
            }
            val asked = token.get()
            post {
                if (gen != generation) return@post
                outcome.fold(
                    onSuccess = {
                        // The outcome comes from the library's result, not from the cancel request.
                        update(
                            state.copy(
                                photo = PhotoState.Done(it),
                                status = if (asked) Status.TooLateToCancel else Status.Saved(it.faces, it.masks),
                                cancelRequested = false,
                            ),
                        )
                    },
                    onFailure = { saveFailed(it, review) },
                )
            }
        }
    }

    private fun saveFailed(error: Throwable, review: Review) {
        val failure = error.asFailure()
        if (failure.kind == FailureKind.CANCELLED && !failure.leftover) {
            update(
                state.copy(
                    photo = PhotoState.Reviewing(review),
                    status = Status.CancelledNothingChanged,
                    cancelRequested = false,
                ),
            )
        } else {
            update(
                state.copy(
                    photo = PhotoState.Reviewing(review),
                    status = Status.Failed(failure),
                    cancelRequested = false,
                ),
            )
        }
    }

    private fun photoFailed(error: Throwable, next: PhotoState) {
        val failure = error.asFailure()
        if (failure.kind == FailureKind.CANCELLED) {
            update(state.copy(photo = next, status = Status.CancelledNothingChanged, cancelRequested = false))
        } else {
            update(
                state.copy(
                    photo = PhotoState.Failed(failure),
                    status = Status.Failed(failure),
                    cancelRequested = false,
                ),
            )
        }
    }

    /** Back to the start screen after a result or an error. */
    fun reset() {
        if (state.photo is PhotoState.Detecting || state.photo is PhotoState.Saving) return
        generation++
        source = null
        update(
            state.copy(
                photo = PhotoState.Idle,
                status = if (state.model is ModelState.Ready) Status.ModelReady else Status.ChooseModelFirst,
                cancelRequested = false,
            ),
        )
    }

    private fun Throwable.asFailure(): DemoFailure = this as? DemoFailure ?: DemoFailure(FailureKind.OTHER)
}

/** Like `runCatching`, but an `Error` (for example an OutOfMemoryError) is not caught: only exceptions are reported. */
internal inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: Exception) {
    Result.failure(e)
}
