// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.internal.model

/** SHA-256 (FIPS 180-4) in common Kotlin; the standard library has no common digest. */
internal object Sha256 {
    private val K: IntArray =
        hexWords(
            "428a2f98 71374491 b5c0fbcf e9b5dba5 3956c25b 59f111f1 923f82a4 ab1c5ed5 " +
                "d807aa98 12835b01 243185be 550c7dc3 72be5d74 80deb1fe 9bdc06a7 c19bf174 " +
                "e49b69c1 efbe4786 0fc19dc6 240ca1cc 2de92c6f 4a7484aa 5cb0a9dc 76f988da " +
                "983e5152 a831c66d b00327c8 bf597fc7 c6e00bf3 d5a79147 06ca6351 14292967 " +
                "27b70a85 2e1b2138 4d2c6dfc 53380d13 650a7354 766a0abb 81c2c92e 92722c85 " +
                "a2bfe8a1 a81a664b c24b8b70 c76c51a3 d192e819 d6990624 f40e3585 106aa070 " +
                "19a4c116 1e376c08 2748774c 34b0bcb5 391c0cb3 4ed8aa4a 5b9cca4f 682e6ff3 " +
                "748f82ee 78a5636f 84c87814 8cc70208 90befffa a4506ceb bef9a3f7 c67178f2",
        )

    private val H0: IntArray = hexWords("6a09e667 bb67ae85 3c6ef372 a54ff53a 510e527f 9b05688c 1f83d9ab 5be0cd19")

    private fun hexWords(text: String): IntArray = text.split(' ').map { it.toLong(16).toInt() }.toIntArray()

    fun hex(data: ByteArray): String = toHex(digest(data))

    /** Lower-case hexadecimal of [digest]. */
    fun toHex(digest: ByteArray): String = digest.joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

    /**
     * The layout of the padded message for [size] bytes, in 64-bit arithmetic: the number of whole 64-byte blocks
     * read straight from the input, and the size of the final padding blocks (64 or 128 bytes) that hold the rest,
     * the 0x80 marker and the 64-bit bit length.
     */
    fun layout(size: Long): Pair<Long, Int> {
        require(size >= 0L)
        val whole = size / 64L
        val rest = (size - whole * 64L).toInt()
        return whole to (if (rest + 9 <= 64) 64 else 128)
    }

    /**
     * SHA-256 of [data], block by block: the whole blocks are read in place and only the final one or two blocks are
     * copied (at most 128 bytes), so hashing needs no second copy of the model.
     */
    fun digest(data: ByteArray): ByteArray {
        val h = H0.copyOf()
        val w = IntArray(64)
        val (whole, tailSize) = layout(data.size.toLong())
        for (block in 0L until whole) compress(h, w, data, (block * 64L).toInt())
        val done = (whole * 64L).toInt()
        val tail = ByteArray(tailSize)
        data.copyInto(tail, 0, done, data.size)
        tail[data.size - done] = 0x80.toByte()
        val bitLength = data.size.toLong() * 8L
        for (i in 0 until 8) tail[tailSize - 1 - i] = (bitLength ushr (8 * i)).toByte()
        for (o in 0 until tailSize step 64) compress(h, w, tail, o)
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (h[i] ushr (24 - 8 * j)).toByte()
        return out
    }

    /**
     * SHA-256 of a message given in parts ([update]), for data that is never held in one array (the image fingerprint
     * of file mode reads the pixels in bands). The same compression function as [digest]; [finish] once, then the
     * hasher is spent. One thread.
     */
    class Hasher {
        private val h = H0.copyOf()
        private val w = IntArray(64)
        private val block = ByteArray(64)
        private var filled = 0
        private var total = 0L
        private var finished = false

        /** Adds [length] bytes of [data] from [offset]. */
        fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
            check(!finished) { "the hasher is spent" }
            require(offset >= 0 && length >= 0 && offset <= data.size - length) { "range outside the data" }
            var at = offset
            var left = length
            total += length.toLong()
            if (filled > 0) {
                val n = minOf(left, 64 - filled)
                data.copyInto(block, filled, at, at + n)
                filled += n
                at += n
                left -= n
                if (filled < 64) return
                compress(h, w, block, 0)
                filled = 0
            }
            while (left >= 64) {
                compress(h, w, data, at)
                at += 64
                left -= 64
            }
            data.copyInto(block, 0, at, at + left)
            filled = left
        }

        /** The 32-byte digest of everything added. */
        fun finish(): ByteArray {
            check(!finished) { "the hasher is spent" }
            finished = true
            val bitLength = total * 8L
            val tailSize = if (filled + 9 <= 64) 64 else 128
            val tail = ByteArray(tailSize)
            block.copyInto(tail, 0, 0, filled)
            tail[filled] = 0x80.toByte()
            for (i in 0 until 8) tail[tailSize - 1 - i] = (bitLength ushr (8 * i)).toByte()
            for (o in 0 until tailSize step 64) compress(h, w, tail, o)
            val out = ByteArray(32)
            for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (h[i] ushr (24 - 8 * j)).toByte()
            return out
        }
    }

    /** One 64-byte block of [msg] at [base] into the state [h] ([w] is scratch space). */
    private fun compress(h: IntArray, w: IntArray, msg: ByteArray, base: Int) {
        for (t in 0 until 16) {
            val o = base + t * 4
            w[t] = ((msg[o].toInt() and 0xFF) shl 24) or ((msg[o + 1].toInt() and 0xFF) shl 16) or
                ((msg[o + 2].toInt() and 0xFF) shl 8) or (msg[o + 3].toInt() and 0xFF)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
            val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }
        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        var f = h[5]
        var g = h[6]
        var hh = h[7]
        for (t in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + K[t] + w[t]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
        h[5] += f
        h[6] += g
        h[7] += hh
    }
}
