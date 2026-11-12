// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.internal.model.Sha256
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import platform.posix.memcpy

/** How a [FakeBridge] misbehaves from its [FakeBridge.faultFrom]-th pass on (1 = the contract run at creation). */
internal enum class Fault {
    NONE,
    WRITE_NOTHING,
    WRITE_SHORT,
    LEAVE_SENTINEL,
    SKIP_ONE,
    FLOAT64,
    RANK_FIVE,
    WRONG_DIMS,
    OVER_CAPACITY,
    STATUS_2,
    STATUS_3_FLOAT64,
    STATUS_4,
    STATUS_5,
    THROW,
}

/**
 * A glue written in Kotlin that speaks the real protocol: it reads the model and the input tensor from the addresses
 * it is given and writes the [runner]'s outputs into the output table, in the row order given at `open`.
 */
internal class FakeBridge(
    private val runner: TensorRunner,
    private val fault: Fault = Fault.NONE,
    private val faultFrom: Int = 1,
    private val openStatus: Int = 0,
    private val version: String = FaceBlurIos.REQUIRED_RUNTIME_VERSION,
    private val throwAtOpen: Boolean = false,
) : OnnxBridge {
    var openedSha256: String? = null
    var opens = 0
    var runs = 0
    var closes = 0
    var shape: LongArray? = null
    private var names: List<String> = emptyList()

    override fun open(
        modelAddress: Long,
        modelByteCount: Long,
        threads: Int,
        inputName: String,
        inputShape: LongArray,
        outputNames: List<String>,
    ): Int {
        opens++
        if (throwAtOpen) throw IllegalStateException("glue fault")
        val copy = ByteArray(modelByteCount.toInt())
        copy.usePinned { memcpy(it.addressOf(0), modelAddress.toCPointer<ByteVar>(), modelByteCount.convert()) }
        openedSha256 = Sha256.hex(copy)
        shape = inputShape.copyOf()
        names = outputNames
        return openStatus
    }

    override fun run(inputAddress: Long, inputByteCount: Long, outputTableAddress: Long): Int {
        runs++
        val active = if (runs >= faultFrom) fault else Fault.NONE
        when (active) {
            Fault.STATUS_2 -> return 2
            Fault.STATUS_4 -> return 4
            Fault.STATUS_5 -> return 5
            Fault.THROW -> throw IllegalStateException("glue fault")
            else -> Unit
        }
        val src = inputAddress.toCPointer<FloatVar>()!!
        val input = FloatArray((inputByteCount / 4).toInt()) { src[it] }
        val all = runner.run(input)
        val table = outputTableAddress.toCPointer<LongVar>()!!
        var status = 0
        for ((row, name) in names.withIndex()) {
            val b = row * OnnxBridge.OUTPUT_ROW_LONGS
            // A real runtime fills every output; the runners give only the ones the detector reads.
            val data = all[name] ?: zeros(name)
            if (data == null || (active == Fault.SKIP_ONE && row == 0)) {
                status = 3
                continue
            }
            val shape = shapeOf(name, data.size)
            table[b + 3] = if (active == Fault.FLOAT64 || active == Fault.STATUS_3_FLOAT64) 11L else 1L
            table[b + 4] = if (active == Fault.RANK_FIVE) 5L else shape.size.toLong()
            for (k in 0 until 4) table[b + 5 + k] = if (k < shape.size) shape[k] else 0L
            if (active == Fault.WRONG_DIMS) table[b + 6] = shape[1] + 1
            if (active == Fault.STATUS_3_FLOAT64) {
                status = 3
                continue
            }
            if (active == Fault.WRITE_NOTHING) continue
            val bytes = data.size.toLong() * 4
            val n = if (active == Fault.WRITE_SHORT) data.size / 2 else data.size
            val dst = table[b].toCPointer<FloatVar>()!!
            for (i in 0 until n) dst[i] = data[i]
            if (active == Fault.LEAVE_SENTINEL) continue
            if (active == Fault.WRITE_SHORT) {
                table[b + 2] = n * 4L
                continue
            }
            if (active == Fault.OVER_CAPACITY) {
                // Reports twice the elements (more than the buffer holds) and claims to have written them all.
                table[b + 6] = shape[1] * 2
                table[b + 2] = bytes * 2
                continue
            }
            table[b + 2] = bytes
        }
        return status
    }

    /** A zero tensor for a YuNet output name (`kps_<stride>`: ten values per anchor), or `null` for other names. */
    private fun zeros(name: String): FloatArray? {
        val stride = name.substringAfter('_').toIntOrNull() ?: return null
        val anchors = (640 / stride) * (640 / stride)
        return if (name.startsWith("kps_")) FloatArray(anchors * 10) else null
    }

    private fun shapeOf(name: String, size: Int): LongArray = when {
        name.startsWith("bbox_") -> longArrayOf(1, size / 4L, 4)
        name.startsWith("kps_") -> longArrayOf(1, size / 10L, 10)
        else -> longArrayOf(1, size.toLong(), 1)
    }

    override fun runtimeVersion(): String = version

    override fun executionProvider(): String = "CPU"

    override fun close() {
        closes++
    }
}

/** A factory that hands out one prepared [FakeBridge] per creation and remembers them. */
internal class FakeFactory(private val make: () -> FakeBridge) : OnnxBridgeFactory {
    val made = ArrayList<FakeBridge>()

    override fun create(): OnnxBridge = make().also { made += it }
}
