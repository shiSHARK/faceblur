// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.shishark.faceblur

import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.internal.model.Sha256
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.ln

/**
 * Helpers of the golden-output tests: fixed digests of masked pixels, detector inputs and face boxes, recorded on the
 * code before a change that must not change any output, and compared exactly afterwards. Pixel arrays and tensors
 * are hashed with 64-bit FNV-1a (cheap on every test platform); encoded files and the combined lists with SHA-256.
 */
internal object Golden {
    private const val FNV_OFFSET: Long = -0x340d631b7bdddcdbL // 14695981039346656037 as a signed Long
    private const val FNV_PRIME: Long = 0x100000001b3L

    /** FNV-1a over the first [size] ints of [values] (each int as one 32-bit step). */
    fun fnv(values: IntArray, size: Int = values.size): String {
        var h = FNV_OFFSET
        for (i in 0 until size) h = (h xor (values[i].toLong() and 0xFFFFFFFFL)) * FNV_PRIME
        return h.toULong().toString(16).padStart(16, '0')
    }

    /** FNV-1a over the raw bits of [values]. */
    fun fnv(values: FloatArray): String {
        var h = FNV_OFFSET
        for (v in values) h = (h xor (v.toRawBits().toLong() and 0xFFFFFFFFL)) * FNV_PRIME
        return h.toULong().toString(16).padStart(16, '0')
    }

    fun sha256(bytes: ByteArray): String = Sha256.hex(bytes)

    /** SHA-256 of the lines of [parts] joined with newlines (the combined digest of a list). */
    fun combined(parts: List<String>): String = Sha256.hex(parts.joinToString("\n").encodeToByteArray())

    /** Every box exactly: the raw bits of left, top, right, bottom and score, in the order given. */
    fun boxes(faces: List<FaceBox>): String = faces.joinToString(";") {
        listOf(it.left, it.top, it.right, it.bottom, it.score).joinToString(",") { v -> v.toRawBits().toString(16) }
    }

    /** The synthetic picture of the Android device test and its fixed plan (also in GoldenOutputDeviceTest). */
    const val DEVICE_W: Int = 1000
    const val DEVICE_H: Int = 750
    const val DEVICE_SEED: Int = 21

    fun devicePlan(): MaskPlan = MaskPlan(
        DEVICE_W,
        DEVICE_H,
        listOf(
            Mask.exact(MaskShape.ELLIPSE, 420f, 330f, 260f, 300f),
            Mask.exact(MaskShape.RECTANGLE, 820f, 160f, 120f, 90f),
            Mask.exact(MaskShape.ELLIPSE, 980f, 730f, 70f, 80f),
        ),
    )

    /**
     * A deterministic opaque picture with smooth areas, edges and fine noise (closer to a photo than pure noise, so
     * every fill style has structure to work with).
     */
    fun photoLike(w: Int, h: Int, seed: Int): IntArray {
        var s = seed or 1
        return IntArray(w * h) { i ->
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            val x = i % w
            val y = i / w
            val n = (s and 0x1F) - 16
            val r = (x * 255 / maxOf(1, w - 1) + n).coerceIn(0, 255)
            val g = (y * 255 / maxOf(1, h - 1) - n).coerceIn(0, 255)
            val b = (if ((x / 23 + y / 17) % 2 == 0) 60 else 190) + n / 2
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b.coerceIn(0, 255)
        }
    }
}

/**
 * A YuNet-layout runner whose one face depends on the whole tensor it receives: the FNV-1a hash of every input float
 * picks the stride-32 cell (one of the inner cells) and the score; each input's hash is appended to [inputs]. Any
 * change of a detector input therefore changes the faces, the masks and every output, and shows in [inputs].
 */
internal class TensorFaceRunner(override val inputWidth: Int = 640, override val inputHeight: Int = 640) :
    TensorRunner {
    private val recorded = RecordedInputs()

    /** The digests of every input, in the order the runs started (two sessions can run it at the same time). */
    val inputs: List<String> get() = recorded.list

    override fun initialize() = Unit

    override fun close() = Unit

    override fun run(input: FloatArray): Map<String, FloatArray> {
        val hash = Golden.fnv(input)
        recorded.add(hash)
        val mix = hash.toULong(16).toLong().let { (it xor (it ushr 29)).toInt() }
        val m = HashMap<String, FloatArray>()
        for (s in intArrayOf(8, 16, 32)) {
            val n = (inputWidth / s) * (inputHeight / s)
            m["cls_$s"] = FloatArray(n)
            m["obj_$s"] = FloatArray(n)
            m["bbox_$s"] = FloatArray(n * 4)
        }
        val cols = inputWidth / 32
        val rows = inputHeight / 32
        // An inner cell when the grid has one, so the 64 px box mostly lies inside the input.
        val cx = if (cols > 2) 1 + ((mix ushr 8) and 0xFFFF) % (cols - 2) else 0
        val cy = if (rows > 2) 1 + (mix and 0xFF) % (rows - 2) else 0
        val cell = cy * cols + cx
        val score = 0.8f + ((mix ushr 24) and 0x3F) / 420f
        m.getValue("cls_32")[cell] = score
        m.getValue("obj_32")[cell] = score
        m.getValue("bbox_32")[cell * 4 + 2] = ln(2f)
        m.getValue("bbox_32")[cell * 4 + 3] = ln(2f)
        return m
    }
}

/** A list of input digests that runs on two pass threads can append to at the same time. */
internal class RecordedInputs {
    private val values = AtomicReference<List<String>>(emptyList())

    val list: List<String> get() = values.load()

    fun add(value: String) {
        while (true) {
            val before = values.load()
            if (values.compareAndSet(before, before + value)) return
        }
    }
}
