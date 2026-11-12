// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import io.github.shishark.faceblur.HiddenFromJava
import io.github.shishark.faceblur.internal.model.ModelLoader
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.SessionRunFailed
import io.github.shishark.faceblur.internal.runtime.SessionTensor
import io.github.shishark.faceblur.internal.runtime.SessionTimeout
import io.github.shishark.faceblur.isFatal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// The runtime-independent rules of an Android model session (ADR 0012 decisions 4 and 8): the timed pass, the reused
// input buffer and the conversion of the runtime's tensor descriptions and outputs. OrtSessions maps them to ONNX
// Runtime calls and holds nothing else; everything in this file runs in the host tests.

/**
 * Closes a native object after another failure, which stays the one reported: an exception from [close] is dropped,
 * except the JVM's fatal errors ([isFatal]), which pass. OrtSessions calls it with the session's `close`.
 */
@HiddenFromJava
internal fun closeAfterFailure(close: () -> Unit) {
    try {
        close()
    } catch (e: Throwable) {
        if (isFatal(e)) throw e
    }
}

/**
 * One timed pass (ST-049 AC3): starts a [RunTimer] of [timeoutMillis] (0 = no limit) that calls [terminate] at the
 * limit, runs [pass], then calls [release] under the timer's lock (a terminate never overlaps or follows it).
 *
 * A pass that reached its limit is a [SessionTimeout] even if the runtime finished it anyway or [terminate] failed:
 * its outputs are never used. A pass that returns null (the runtime refused it) is a [SessionRunFailed]. An exception
 * thrown by [pass] passes unchanged, after the release.
 */
@HiddenFromJava
internal fun <R : Any> timedPass(timeoutMillis: Long, terminate: () -> Unit, release: () -> Unit, pass: () -> R?): R {
    var terminateFailed = false
    val timer =
        RunTimer.start(timeoutMillis) {
            try {
                terminate()
            } catch (e: Exception) {
                // The run could not be stopped: it still counts as stopped by its limit (fail closed).
                terminateFailed = true
            }
        }
    val result =
        try {
            pass()
        } finally {
            timer.finish(release)
        }
    if (timer.fired || terminateFailed) throw SessionTimeout()
    return result ?: throw SessionRunFailed()
}

/**
 * The input tensor's data of one session, reused between passes while the size stays the same: a direct buffer in
 * native byte order, so the runtime wraps it without a copy. Not thread-safe (one pass at a time per session).
 */
internal class InputBuffer {
    private var data: FloatBuffer? = null

    /** [input] in the reused buffer, position 0, limit = size. */
    fun fill(input: FloatArray): FloatBuffer {
        val buffer =
            data?.takeIf { it.capacity() == input.size }
                ?: ByteBuffer.allocateDirect(
                    input.size * Float.SIZE_BYTES,
                ).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data = buffer
        buffer.clear()
        buffer.put(input)
        buffer.rewind()
        return buffer
    }
}

/**
 * One runtime output as a [PassOutput]: [read] is called only for a float tensor ([isFloat]) with at most
 * [maxElements] elements; any other output carries no data, so the common contract check refuses it.
 */
@HiddenFromJava
internal fun passOutput(
    typeName: String,
    shape: LongArray,
    isFloat: Boolean,
    elements: Long,
    maxElements: Long,
    read: () -> FloatArray,
): PassOutput = PassOutput(onnxCode(typeName), shape, if (isFloat && elements in 0..maxElements) read() else null)

/** The runtime's description of one input or output; dynamic dimensions (negative) become null, as in the model file. */
internal fun sessionTensor(name: String, typeName: String?, shape: LongArray?): SessionTensor =
    SessionTensor(name, typeName?.let { onnxCode(it) }, shape?.map { if (it < 0) null else it })

/**
 * The ONNX `TensorProto.DataType` code of a runtime element type, from the Java enum's name (the enum's own numbers
 * differ from ONNX's); -1 when the name is unknown.
 */
internal fun onnxCode(enumName: String): Int {
    val name = enumName.removePrefix("ONNX_TENSOR_ELEMENT_DATA_TYPE_")
    return (0..MAX_ONNX_TYPE_CODE).firstOrNull { ModelLoader.elementTypeName(it) == name } ?: -1
}

private const val MAX_ONNX_TYPE_CODE = 32
