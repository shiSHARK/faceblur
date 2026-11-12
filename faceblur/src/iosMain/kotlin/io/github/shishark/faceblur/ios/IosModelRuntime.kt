// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.TensorLayout
import io.github.shishark.faceblur.detect.Limits
import io.github.shishark.faceblur.internal.model.OnnxDescription
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.internal.runtime.RuntimeOptionFailed
import io.github.shishark.faceblur.internal.runtime.SessionFailure
import io.github.shishark.faceblur.internal.runtime.SessionRejected
import io.github.shishark.faceblur.internal.runtime.SessionRunFailed
import io.github.shishark.faceblur.internal.runtime.SessionTensor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import platform.posix.memset

/** Bridge status codes (ADR 0010 decision 4). */
internal object BridgeStatus {
    const val OK = 0
    const val NOT_CREATED = 1
    const val RUN_FAILED = 2
    const val OUTPUT_INVALID = 3
    const val NAMES_DIFFER = 6
    const val OPTION_FAILED = 7
}

/**
 * The iOS [ModelRuntime]: one glue instance per session. The verified model bytes reach the runtime only from library
 * memory, by address and length, for the duration of `open` (privacy by construction: no model file, no cache, no
 * optimised-model output is ever written by the library or the glue).
 */
internal class IosModelRuntime(private val factory: OnnxBridgeFactory) : ModelRuntime {
    override fun open(model: DetectorModel, threads: Int): ModelSession {
        val bridge = try {
            factory.create()
        } catch (e: Throwable) {
            throw boundaryFailure(e)
        }
        try {
            return openWith(bridge, model, threads)
        } catch (e: Throwable) {
            try {
                bridge.close()
            } catch (_: Throwable) {
                // The first failure is the one reported; close() of a failed glue has nothing left to release.
            }
            throw boundaryFailure(e)
        }
    }

    /** Library failures pass unchanged; anything else (a faulty glue) becomes [FaceMaskException.Internal]. */
    private fun boundaryFailure(e: Throwable): Throwable = if (e is FaceMaskException ||
        e is SessionFailure
    ) {
        e
    } else {
        FaceMaskException.Internal("the ONNX Runtime bridge failed", e)
    }

    private fun openWith(bridge: OnnxBridge, model: DetectorModel, threads: Int): ModelSession {
        if (bridge.runtimeVersion() != FaceBlurIos.REQUIRED_RUNTIME_VERSION) {
            throw FaceMaskException.RuntimeUnavailable(
                "FaceBlur requires ONNX Runtime ${FaceBlurIos.REQUIRED_RUNTIME_VERSION} on iOS",
            )
        }
        val d = model.description
        val input = d.inputs.single()
        val profile = model.profile
        val layout = (profile.format as? DetectionFormat.GenericDetections)?.tensorLayout ?: TensorLayout.NCHW
        val h = profile.inputHeight.toLong()
        val w = profile.inputWidth.toLong()
        val shape = if (layout == TensorLayout.NCHW) longArrayOf(1, 3, h, w) else longArrayOf(1, h, w, 3)
        val outputNames = d.outputs.map { it.name }
        val bytes = model.verifiedBytes()
        val status =
            if (bytes.isEmpty()) {
                BridgeStatus.NOT_CREATED
            } else {
                bytes.usePinned { pinned ->
                    bridge.open(
                        pinned.addressOf(0).toLong(),
                        bytes.size.toLong(),
                        threads,
                        input.name,
                        shape,
                        outputNames,
                    )
                }
            }
        if (status != BridgeStatus.OK) {
            when (status) {
                BridgeStatus.NOT_CREATED -> throw SessionRejected()
                BridgeStatus.NAMES_DIFFER -> throw FaceMaskException.ModelRejected(listOf("IO_MISMATCH"))
                BridgeStatus.OPTION_FAILED -> throw RuntimeOptionFailed()
                else -> throw FaceMaskException.Internal("the ONNX Runtime bridge returned status $status at open")
            }
        }
        return IosModelSession(bridge, d.inputs.map { SessionTensor(it.name, null, null) }, outputNames, shape, d)
    }
}

/**
 * A session behind one glue instance. The output table and the output buffers are allocated once per session and
 * freed in [close] (after the bridge is closed, ADR 0010 decision 1 release order). The common `FaceMasker` calls
 * [run] one at a time and defers [close] until a running call returns.
 */
internal class IosModelSession(
    private val bridge: OnnxBridge,
    override val inputs: List<SessionTensor>,
    private val names: List<String>,
    inputShape: LongArray,
    description: OnnxDescription,
) : ModelSession {
    override val runtimeVersion: String = bridge.runtimeVersion()
    override val executionProvider: String = bridge.executionProvider()
    override val outputs: List<SessionTensor> = names.map { SessionTensor(it, null, null) }

    private val inputElements: Long = inputShape.fold(1L) { a, v -> a * v }
    private val rows = names.size
    private val table: CPointer<LongVar> = nativeHeap.allocArray(rows * OnnxBridge.OUTPUT_ROW_LONGS)

    /** Static outputs: their exact size (capped); dynamic outputs: the element cap of ADR 0012 decision 6. */
    private val staticBytes: LongArray =
        LongArray(rows) { i ->
            val dims = description.outputs.getOrNull(i)?.dims
            if (dims != null && dims.all { it != null && it >= 0 }) {
                minOf(dims.fold(1L) { a, v -> a * v!! }, Limits.MAX_ELEMENTS) * 4
            } else {
                -1L
            }
        }
    private val capacities: LongArray = LongArray(rows) {
        if (staticBytes[it] >=
            0
        ) {
            staticBytes[it]
        } else {
            Limits.MAX_ELEMENTS * 4
        }
    }

    /** Bytes the previous pass wrote per row: the region a dynamic row's NaN fill covers. */
    private val previous: LongArray = LongArray(rows) { if (staticBytes[it] >= 0) staticBytes[it] else 0L }
    private val buffers: List<CPointer<ByteVar>> =
        List(rows) { nativeHeap.allocArray<ByteVar>(maxOf(capacities[it], 1L)) }
    private var closed = false

    override fun run(
        input: FloatArray,
        inputShape: LongArray,
        outputNames: List<String>,
        maxElements: Long,
        timeoutMillis: Long,
    ): Map<String, PassOutput> {
        if (closed) throw FaceMaskException.Internal("the session is closed")
        if (input.size.toLong() != inputElements || !inputShape.contentEquals(expectedShape(inputShape))) {
            throw FaceMaskException.Internal("the input does not have the shape given at open")
        }
        prepareTable()
        val status =
            input.usePinned { pinned ->
                bridge.run(pinned.addressOf(0).toLong(), inputElements * 4, table.toLong())
            }
        when (status) {
            BridgeStatus.OK, BridgeStatus.OUTPUT_INVALID -> Unit
            BridgeStatus.RUN_FAILED -> throw SessionRunFailed()
            else -> throw FaceMaskException.Internal("the ONNX Runtime bridge returned status $status in a pass")
        }
        val out = LinkedHashMap<String, PassOutput>()
        for (name in outputNames) {
            val r = names.indexOf(name)
            if (r < 0) continue
            out[name] = readRow(r, status == BridgeStatus.OK, maxElements)
        }
        return out
    }

    /** The shape at open is fixed per session; the common detector passes the same values every pass. */
    private fun expectedShape(given: LongArray): LongArray = if (given.fold(1L) { a, v -> a * v } ==
        inputElements
    ) {
        given
    } else {
        LongArray(0)
    }

    /** Per pass: every row gets its buffer and capacity, "bytes written" = −1 and the buffer filled with NaN. */
    private fun prepareTable() {
        for (r in 0 until rows) {
            val b = r * OnnxBridge.OUTPUT_ROW_LONGS
            table[b] = buffers[r].toLong()
            table[b + 1] = capacities[r]
            table[b + 2] = -1L
            for (k in 3 until OnnxBridge.OUTPUT_ROW_LONGS) table[b + k] = 0L
            // 0xFFFFFFFF is a NaN in float32: a region the glue leaves unwritten fails the non-finite check.
            if (previous[r] > 0) memset(buffers[r], 0xFF, previous[r].convert())
        }
    }

    private fun readRow(r: Int, ok: Boolean, maxElements: Long): PassOutput {
        val b = r * OnnxBridge.OUTPUT_ROW_LONGS
        val written = table[b + 2]
        val type = table[b + 3].toInt()
        val rawRank = table[b + 4]
        val rank = rawRank.coerceIn(0L, 4L).toInt()
        val shape = LongArray(rank) { table[b + 5 + it] }
        val elements = elementsOf(shape)
        val valid =
            ok && type == FLOAT && rawRank in 0L..4L && elements != null && elements <= maxElements &&
                written == elements * 4 && written <= capacities[r] &&
                (staticBytes[r] < 0 || written == staticBytes[r])
        if (!valid) {
            return PassOutput(
                type,
                if (rawRank >
                    4
                ) {
                    LongArray(rawRank.coerceAtMost(8L).toInt())
                } else {
                    shape
                },
                null,
            )
        }
        previous[r] = written
        val n = elements.toInt() // valid ⇒ elements ≤ maxElements (≤ Limits.MAX_ELEMENTS, an Int)
        val data = FloatArray(n)
        if (n > 0) data.usePinned { memcpy(it.addressOf(0), buffers[r], written.convert()) }
        return PassOutput(type, shape, data)
    }

    override fun close() {
        if (closed) return
        closed = true
        bridge.close()
        for (r in 0 until rows) {
            memset(buffers[r], 0, maxOf(capacities[r], 1L).convert())
            nativeHeap.free(buffers[r].rawValue)
        }
        nativeHeap.free(table.rawValue)
    }

    private companion object {
        const val FLOAT = 1

        /** The element count, or `null` for a negative dimension or an overflow. */
        fun elementsOf(shape: LongArray): Long? {
            var n = 1L
            for (d in shape) {
                if (d < 0) return null
                if (d != 0L && n > Long.MAX_VALUE / d) return null
                n *= d
            }
            return n
        }
    }
}
