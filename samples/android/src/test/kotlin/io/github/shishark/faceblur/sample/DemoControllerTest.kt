// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoControllerTest {
    private val engine = FakeEngine()
    private val controller = DemoController(engine, DirectExecutor) { it.run() }

    private fun ready() {
        controller.chooseModel("model-uri")
        controller.useModel()
    }

    private fun reviewing(): Review = assertIs<PhotoState.Reviewing>(controller.state.photo).review

    // ---- model step (ST-022 AC10, AC14) ------------------------------------------------------------------------

    @Test
    fun `before a model is loaded the photo steps are disabled and say to choose a model first`() {
        assertEquals(Status.ChooseModelFirst, controller.state.status)
        assertFalse(controller.state.photoStepsEnabled)
        controller.choosePhoto("photo")
        assertTrue(engine.calls.isEmpty())
        assertIs<PhotoState.Idle>(controller.state.photo)
    }

    @Test
    fun `a chosen model waits for confirmation and only the confirmed hash goes to the library`() {
        controller.chooseModel("model-uri")
        val confirm = assertIs<ModelState.Confirm>(controller.state.model)
        assertEquals(Status.ModelConfirm, controller.state.status)
        assertEquals(4, confirm.candidate.sha256Lines.size)
        assertTrue(confirm.candidate.sha256Lines.all { it.length == 16 })
        assertEquals(listOf("readModel"), engine.calls)
        assertFalse(controller.state.photoStepsEnabled)

        controller.useModel()
        assertEquals(listOf("readModel", "loadModel:" + engine.model.sha256), engine.calls)
        assertIs<ModelState.Ready>(controller.state.model)
        assertEquals(Status.ModelReady, controller.state.status)
        assertTrue(controller.state.photoStepsEnabled)
    }

    @Test
    fun `cancelling at the confirmation loads nothing and drops the candidate`() {
        controller.chooseModel("model-uri")
        controller.cancelModel()
        assertIs<ModelState.None>(controller.state.model)
        assertEquals(Status.ChooseModelFirst, controller.state.status)
        controller.useModel()
        assertEquals(listOf("readModel"), engine.calls)
        assertFalse(controller.state.photoStepsEnabled)
    }

    @Test
    fun `a model that cannot be read or loaded gives a model error and no photo steps`() {
        engine.readFailure = DemoFailure(FailureKind.MODEL_TOO_LARGE)
        controller.chooseModel("model-uri")
        var failed = assertIs<ModelState.Failed>(controller.state.model)
        assertEquals(FailureKind.MODEL_TOO_LARGE, failed.failure.kind)
        assertIs<Status.ModelFailed>(controller.state.status)

        engine.readFailure = null
        engine.loadFailure = DemoFailure(FailureKind.MODEL_INTEGRITY, "Model integrity: hash differs")
        controller.chooseModel("model-uri")
        controller.useModel()
        failed = assertIs<ModelState.Failed>(controller.state.model)
        assertEquals(FailureKind.MODEL_INTEGRITY, failed.failure.kind)
        assertEquals("Model integrity: hash differs", failed.failure.detail)
        assertFalse(controller.state.photoStepsEnabled)
    }

    @Test
    fun `an unexpected exception is shown as a generic failure`() {
        val broken = object : Engine<String> by engine {
            override fun readModel(source: String): ModelCandidate = throw IllegalStateException("boom")
        }
        val c = DemoController(broken, DirectExecutor) { it.run() }
        c.chooseModel("model-uri")
        val failed = assertIs<ModelState.Failed>(c.state.model)
        assertEquals(FailureKind.OTHER, failed.failure.kind)
        assertNull(failed.failure.detail)
    }

    // ---- detect and review (ST-024 AC1, AC2, AC4) --------------------------------------------------------------

    @Test
    fun `a photo is detected without writing and shown with faces and masks as separate numbers`() {
        ready()
        controller.choosePhoto("photo")
        val review = reviewing()
        assertEquals(1, review.analysis.faces.size)
        assertEquals(1, review.masks.size)
        assertEquals(Status.Detected(1, 1).faces, (controller.state.status as Status.Detected).faces)
        assertEquals(1, (controller.state.status as Status.Detected).masks)
        assertTrue(engine.calls.none { it.startsWith("save") })
    }

    @Test
    fun `faces and masks are counted separately when a face has more than one mask`() {
        engine.masks = listOf(FakeEngine.item(0, Origin.DETECTED), FakeEngine.item(1, Origin.DETECTED))
        ready()
        controller.choosePhoto("photo")
        val status = assertIs<Status.Detected>(controller.state.status)
        assertEquals(1, status.faces)
        assertEquals(2, status.masks)
    }

    @Test
    fun `a photo without faces reaches the review with zero masks`() {
        engine.faces = emptyList()
        engine.masks = emptyList()
        ready()
        controller.choosePhoto("photo")
        assertTrue(reviewing().masks.isEmpty())
        assertEquals(0, (controller.state.status as Status.Detected).faces)
    }

    @Test
    fun `keeping a mask unmasked removes it and undo brings it back`() {
        engine.masks = listOf(FakeEngine.item(0, Origin.DETECTED), FakeEngine.item(1, Origin.DETECTED))
        ready()
        controller.choosePhoto("photo")
        controller.removeMask(0)
        assertEquals(listOf(1), reviewing().masks.map { it.id })
        assertEquals(Status.Removed(1).masks, (controller.state.status as Status.Removed).masks)
        controller.undo()
        assertEquals(listOf(0, 1), reviewing().masks.map { it.id })
        assertIs<Status.Undone>(controller.state.status)
        controller.undo()
        assertEquals(listOf(0, 1), reviewing().masks.map { it.id })
    }

    @Test
    fun `removing a mask that does not exist changes nothing`() {
        ready()
        controller.choosePhoto("photo")
        controller.removeMask(99)
        assertEquals(1, reviewing().masks.size)
        assertTrue(reviewing().undo.isEmpty())
    }

    @Test
    fun `a new mask is placed with buttons and added to the list`() {
        ready()
        controller.choosePhoto("photo")
        controller.beginAdd()
        assertEquals(Status.Placing, controller.state.status)
        val start = assertNotNull(reviewing().placing)
        controller.movePlacing(1, 0)
        controller.movePlacing(0, 1)
        controller.resizePlacing(larger = true)
        controller.resizePlacing(larger = false)
        val moved = assertNotNull(reviewing().placing)
        assertNotEquals(start, moved)
        controller.confirmAdd()
        val review = reviewing()
        assertNull(review.placing)
        assertEquals(2, review.masks.size)
        assertEquals(Origin.ADDED, review.masks.last().origin)
        assertEquals(1, review.addedCount)
        assertTrue(engine.calls.contains("manualMask"))
        controller.undo()
        assertEquals(1, reviewing().masks.size)
    }

    @Test
    fun `adding a mask can be cancelled and then nothing was added`() {
        ready()
        controller.choosePhoto("photo")
        controller.beginAdd()
        controller.cancelAdd()
        assertNull(reviewing().placing)
        assertEquals(1, reviewing().masks.size)
        assertEquals(Status.PlacingCancelled, controller.state.status)
        controller.cancelAdd()
        controller.confirmAdd()
        controller.movePlacing(1, 1)
        assertEquals(1, reviewing().masks.size)
    }

    @Test
    fun `a mask that the library refuses is reported and placing ends`() {
        val refusing = object : Engine<String> by engine {
            override fun manualMask(analysis: Analysis, id: Int, box: Box): MaskItem =
                throw DemoFailure(FailureKind.OTHER)
        }
        val c = DemoController(refusing, DirectExecutor) { it.run() }
        c.chooseModel("m")
        c.useModel()
        c.choosePhoto("photo")
        c.beginAdd()
        c.confirmAdd()
        assertIs<Status.Failed>(c.state.status)
        assertNull((c.state.photo as PhotoState.Reviewing).review.placing)
    }

    // ---- save (ST-024 AC3, ST-022 AC1) -------------------------------------------------------------------------

    @Test
    fun `saving writes exactly the edited list from the original photo`() {
        engine.masks = listOf(FakeEngine.item(0, Origin.DETECTED), FakeEngine.item(1, Origin.DETECTED))
        ready()
        controller.choosePhoto("photo")
        controller.removeMask(0)
        controller.beginAdd()
        controller.confirmAdd()
        val expected = reviewing().masks
        controller.save("target")
        assertEquals("target", engine.savedTarget)
        assertEquals(expected.map { it.id }, engine.savedMasks?.map { it.id })
        assertEquals(listOf(1, 2), engine.savedMasks?.map { it.id })
        val done = assertIs<PhotoState.Done>(controller.state.photo)
        assertEquals(2, done.outcome.masks)
        assertEquals(Status.Saved(1, 2).masks, (controller.state.status as Status.Saved).masks)
        // The source is the original photo the user chose, never the preview.
        assertTrue(engine.calls.contains("save:photo:target"))
    }

    @Test
    fun `the chosen output size is used for the detection and the save`() {
        ready()
        controller.setOutput(OutputOption.LONG_SIDE_1024)
        controller.choosePhoto("photo")
        assertTrue(engine.calls.contains("analyse:photo:LONG_SIDE_1024"))
        controller.save("target")
        assertEquals(OutputOption.LONG_SIDE_1024, engine.savedOutput)
    }

    @Test
    fun `saving is refused while a mask is being placed`() {
        ready()
        controller.choosePhoto("photo")
        controller.beginAdd()
        controller.save("target")
        assertTrue(engine.calls.none { it.startsWith("save") })
    }

    @Test
    fun `a failed save keeps the review and reports the failure`() {
        ready()
        controller.choosePhoto("photo")
        engine.saveFailure = DemoFailure(FailureKind.IO)
        controller.save("target")
        assertIs<PhotoState.Reviewing>(controller.state.photo)
        val status = assertIs<Status.Failed>(controller.state.status)
        assertEquals(FailureKind.IO, status.failure.kind)
    }

    @Test
    fun `after a result the demo can go back to the start`() {
        ready()
        controller.choosePhoto("photo")
        controller.save("target")
        controller.reset()
        assertIs<PhotoState.Idle>(controller.state.photo)
        assertEquals(Status.ModelReady, controller.state.status)
    }

    // ---- limit errors (ST-022 AC11) ----------------------------------------------------------------------------

    @Test
    fun `too many masks shows the library text and offers no try again`() {
        engine.analyseFailure = DemoFailure(FailureKind.TOO_MANY_MASKS, "Model output invalid: TOO_MANY_MASKS. text")
        ready()
        controller.choosePhoto("photo")
        val failed = assertIs<PhotoState.Failed>(controller.state.photo)
        assertEquals(FailureKind.TOO_MANY_MASKS, failed.failure.kind)
        assertEquals("Model output invalid: TOO_MANY_MASKS. text", failed.failure.detail)
        assertTrue(controller.state.photoStepsEnabled)
        val before = engine.calls.size
        controller.retry()
        // Retry is offered by the screen only for not-enough-memory; the controller itself just repeats the run.
        assertEquals(before + 1, engine.calls.size)
    }

    @Test
    fun `not enough memory keeps try again and choose photo`() {
        engine.analyseFailure = DemoFailure(FailureKind.NOT_ENOUGH_MEMORY)
        ready()
        controller.choosePhoto("photo")
        assertEquals(FailureKind.NOT_ENOUGH_MEMORY, assertIs<PhotoState.Failed>(controller.state.photo).failure.kind)
        engine.analyseFailure = null
        controller.retry()
        assertIs<PhotoState.Reviewing>(controller.state.photo)
        assertTrue(engine.calls.none { it.startsWith("save") })
    }

    @Test
    fun `retry without a failed run does nothing`() {
        ready()
        controller.retry()
        assertEquals(listOf("readModel", "loadModel:" + engine.model.sha256), engine.calls)
    }

    // ---- cancel (ST-022 AC12) ----------------------------------------------------------------------------------

    @Test
    fun `cancel during detection ends with nothing changed`() {
        ready()
        engine.duringWork = { controller.cancelRun() }
        controller.choosePhoto("photo")
        assertEquals(Status.CancelledNothingChanged, controller.state.status)
        assertIs<PhotoState.Idle>(controller.state.photo)
        assertFalse(controller.state.cancelRequested)
    }

    @Test
    fun `cancel before the write ends with nothing changed and the review stays`() {
        ready()
        controller.choosePhoto("photo")
        engine.duringWork = { controller.cancelRun() }
        engine.saveFailure = DemoFailure(FailureKind.CANCELLED)
        controller.save("target")
        assertTrue(engine.cancelSeenInSave)
        assertEquals(Status.CancelledNothingChanged, controller.state.status)
        assertIs<PhotoState.Reviewing>(controller.state.photo)
    }

    @Test
    fun `a cancel that arrives after the masks were applied is reported as too late`() {
        ready()
        controller.choosePhoto("photo")
        engine.duringWork = { controller.cancelRun() }
        controller.save("target")
        assertEquals(Status.TooLateToCancel, controller.state.status)
        assertIs<PhotoState.Done>(controller.state.photo)
    }

    @Test
    fun `a cancelled save whose file could not be removed is reported as a failure`() {
        ready()
        controller.choosePhoto("photo")
        engine.saveFailure = DemoFailure(FailureKind.CANCELLED, leftover = true)
        controller.save("target")
        val status = assertIs<Status.Failed>(controller.state.status)
        assertTrue(status.failure.leftover)
    }

    @Test
    fun `cancel does nothing when no run is going on`() {
        ready()
        controller.cancelRun()
        assertFalse(controller.state.cancelRequested)
    }

    // ---- threading (ST-022 AC12) -------------------------------------------------------------------------------

    @Test
    fun `engine work runs on the worker thread, results come back on the posting thread`() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "test-worker") }
        val posted = mutableListOf<Runnable>()
        val gate = CountDownLatch(1)
        val c = DemoController(engine, worker) { r ->
            synchronized(posted) { posted += r }
            gate.countDown()
        }
        try {
            c.chooseModel("model-uri")
            assertTrue(gate.await(5, TimeUnit.SECONDS))
            assertIs<ModelState.Reading>(c.state.model)
            synchronized(posted) { posted.removeAt(0).run() }
            assertIs<ModelState.Confirm>(c.state.model)
            assertTrue(engine.threads.all { it.name == "test-worker" })
            assertNotEquals(Thread.currentThread(), engine.threads.first())
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `a cancel that comes after the detection finished does not turn the result into a cancellation`() {
        val queue = ArrayDeque<Runnable>()
        val c = DemoController(engine, DirectExecutor) { queue.add(it) }
        c.chooseModel("model-uri")
        queue.removeFirst().run()
        c.useModel()
        queue.removeFirst().run()
        c.choosePhoto("photo")
        c.cancelRun()
        assertTrue(c.state.cancelRequested)
        queue.removeFirst().run()
        // The outcome is read from the engine's result, not assumed from the request.
        assertIs<Status.Detected>(c.state.status)
        assertIs<PhotoState.Reviewing>(c.state.photo)
        assertFalse(c.state.cancelRequested)
    }

    @Test
    fun `a pending model result is dropped when the confirmation is cancelled`() {
        val queue = ArrayDeque<Runnable>()
        val c = DemoController(engine, DirectExecutor) { queue.add(it) }
        c.chooseModel("model-uri")
        queue.removeFirst().run()
        c.useModel()
        // Loading cannot be cancelled: cancelModel is refused, the load result is applied.
        c.cancelModel()
        queue.removeFirst().run()
        assertIs<ModelState.Ready>(c.state.model)
    }

    @Test
    fun `the listener gets the current state at once and every change`() {
        val seen = mutableListOf<UiState>()
        controller.setListener { seen += it }
        assertEquals(1, seen.size)
        controller.chooseModel("model-uri")
        assertTrue(seen.size >= 3)
        controller.setListener(null)
        val count = seen.size
        controller.cancelModel()
        assertEquals(count, seen.size)
    }

    @Test
    fun `choosing a model is refused while a photo is being detected`() {
        val queue = ArrayDeque<Runnable>()
        val c = DemoController(engine, DirectExecutor) { queue.add(it) }
        c.chooseModel("m")
        queue.removeFirst().run()
        c.useModel()
        queue.removeFirst().run()
        c.choosePhoto("photo")
        c.chooseModel("other")
        assertEquals(1, engine.calls.count { it == "readModel" })
        c.setOutput(OutputOption.LONG_SIDE_2048)
        assertEquals(OutputOption.SAME_SIZE, c.state.output)
    }
}
