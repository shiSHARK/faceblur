// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import java.util.concurrent.Executor

/** Runs the work at once on the calling thread, and posts at once: the controller becomes a plain function. */
object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

/** Records every call. Behaviour is set by the test; nothing here is a mock of the library. */
class FakeEngine : Engine<String> {
    val calls = mutableListOf<String>()
    val threads = mutableListOf<Thread>()

    var model = ModelCandidate("model.onnx", 1_234L, "ab".repeat(32), Any())
    var readFailure: DemoFailure? = null
    var loadFailure: DemoFailure? = null
    var analyseFailure: DemoFailure? = null
    var saveFailure: DemoFailure? = null
    var faces = listOf(FaceView(0, Box(100f, 100f, 200f, 220f), 0.93f))
    var masks = listOf(item(0, Origin.DETECTED))
    var width = 1_000
    var height = 800

    /** What the last save call was asked to write. */
    var savedMasks: List<MaskItem>? = null
    var savedTarget: String? = null
    var savedOutput: OutputOption? = null

    /** Called inside analyse/save, to let a test press Cancel while the work runs. */
    var duringWork: () -> Unit = {}
    var cancelSeenInSave = false

    override fun readModel(source: String): ModelCandidate {
        record("readModel")
        readFailure?.let { throw it }
        return model
    }

    override fun loadModel(candidate: ModelCandidate) {
        record("loadModel:${candidate.sha256}")
        loadFailure?.let { throw it }
    }

    override fun analyse(source: String, output: OutputOption, cancelled: () -> Boolean): Analysis {
        record("analyse:$source:${output.name}")
        duringWork()
        if (cancelled()) throw DemoFailure(FailureKind.CANCELLED)
        analyseFailure?.let { throw it }
        return Analysis(width, height, faces, masks, "preview", Any())
    }

    override fun manualMask(analysis: Analysis, id: Int, box: Box): MaskItem {
        record("manualMask")
        return MaskItem(
            id,
            Origin.ADDED,
            MaskForm.ELLIPSE,
            box.centerX,
            box.centerY,
            box.width / 2f,
            box.height / 2f,
            Any(),
        )
    }

    override fun save(
        source: String,
        target: String,
        analysis: Analysis,
        masks: List<MaskItem>,
        output: OutputOption,
        cancelled: () -> Boolean,
    ): SaveOutcome {
        record("save:$source:$target")
        savedMasks = masks
        savedTarget = target
        savedOutput = output
        duringWork()
        cancelSeenInSave = cancelled()
        saveFailure?.let { throw it }
        return SaveOutcome(analysis.faces.size, masks.size, "result")
    }

    private fun record(call: String) {
        calls += call
        threads += Thread.currentThread()
    }

    companion object {
        fun item(id: Int, origin: Origin) = MaskItem(id, origin, MaskForm.ELLIPSE, 150f, 150f, 80f, 90f, Any())
    }
}
