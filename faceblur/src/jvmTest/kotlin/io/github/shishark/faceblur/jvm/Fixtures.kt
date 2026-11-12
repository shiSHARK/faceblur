// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.model.Sha256
import java.awt.image.BufferedImage
import java.io.File
import java.util.Collections
import javax.imageio.ImageIO

/**
 * Loads registered fixtures from the repository's `testdata/` and, in the maintainer's local gate only, from the
 * verified local fixtures (ST-002 AC3). Every opened path is recorded, and a path that resolves outside its folder is
 * refused, so a test cannot read photos from anywhere else on the machine. Tests run with the module directory as the
 * working directory (Gradle's default). Paths starting with `images/` or `yunet/` name the local fixtures (`build/local-fixtures/`,
 * copied and hash-checked by the build's syncLocalFixtures task, system property `faceblur.localFixturesDir`); a build
 * without them excludes the tests that need them (config/local-fixture-tests.txt), and a test that is not excluded
 * fails here instead of passing silently.
 */
internal object Fixtures {
    val testdata: File = File("../testdata").canonicalFile

    /** The verified local fixtures, or null in a build without them. */
    val localFixtures: File? = System.getProperty("faceblur.localFixturesDir")?.let { File(it).canonicalFile }

    /** Canonical paths of every file opened through this loader in this JVM. */
    val opened: MutableList<String> = Collections.synchronizedList(ArrayList())

    /** True when [path] lies inside `testdata/` or, in the local gate, inside the verified local fixtures. */
    fun isRegisteredPath(path: String): Boolean =
        listOfNotNull(testdata, localFixtures).any { path.startsWith(it.path + File.separator) }

    /** The file [relative] to `testdata/` (or to the local fixtures for `images/...` and `yunet/...`), refused if it leaves its folder. */
    fun file(relative: String): File {
        val base = if (relative.startsWith("images/") || relative.startsWith("yunet/")) {
            checkNotNull(localFixtures) { "local fixtures are not available in this build: $relative" }
        } else {
            testdata
        }
        val f = File(base, relative).canonicalFile
        require(f.path.startsWith(base.path + File.separator)) { "fixture path outside its folder refused" }
        require(f.isFile) { "fixture missing: $relative" }
        opened += f.path
        return f
    }

    /**
     * The reference data of all registered images: the public file `testdata/yunet_reference.json` and, when the local
     * fixtures are present, the entries of `images/reference.json` appended to its `images` list.
     */
    @Suppress("UNCHECKED_CAST")
    fun referenceData(): Map<String, Any?> {
        val reference = Json.parse(file("yunet_reference.json").readText()) as Map<String, Any?>
        if (localFixtures == null) return reference
        val local = Json.parse(file("images/reference.json").readText()) as Map<String, Any?>
        val merged = LinkedHashMap(reference)
        merged["images"] = (reference["images"] as List<Any?>) + (local["images"] as List<Any?>)
        return merged
    }

    fun bytes(relative: String): ByteArray = file(relative).readBytes()

    /** Decodes a PNG fixture losslessly to non-premultiplied ARGB ints. */
    fun image(relative: String): IntArrayPixels {
        val bytes = bytes(relative)
        val img = ImageIO.read(bytes.inputStream()) ?: error("not a decodable image: $relative")
        // Only 8-bit sRGB colour images decode to identical pixels on every side (no grey or linear conversion).
        require(
            img.type in setOf(BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_4BYTE_ABGR),
        ) {
            "fixture colour type not supported: $relative"
        }
        val w = img.width
        val h = img.height
        val data = IntArray(w * h)
        img.getRGB(0, 0, w, h, data, 0, w)
        return IntArrayPixels(w, h, data)
    }

    fun sha256(relative: String): String = Sha256.hex(bytes(relative))
}

/** Minimal JSON reader for the committed reference data (objects, arrays, strings, numbers, booleans, null). */
internal class Json private constructor(private val s: String) {
    private var i = 0

    companion object {
        fun parse(text: String): Any? = Json(text).run {
            value().also {
                skip()
                require(i == s.length) { "trailing data" }
            }
        }
    }

    private fun skip() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): Any? {
        skip()
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
        }
    }

    private fun literal(word: String, v: Any?): Any? {
        require(s.startsWith(word, i)) { "bad literal at $i" }
        i += word.length
        return v
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++
        skip()
        if (s[i] == '}') return m.also { i++ }
        while (true) {
            skip()
            val k = str()
            skip()
            require(s[i] == ':') { "expected ':' at $i" }
            i++
            m[k] = value()
            skip()
            if (s[i] == ',') {
                i++
                continue
            }
            require(s[i] == '}') { "expected '}' at $i" }
            i++
            return m
        }
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        i++
        skip()
        if (s[i] == ']') return l.also { i++ }
        while (true) {
            l += value()
            skip()
            if (s[i] == ',') {
                i++
                continue
            }
            require(s[i] == ']') { "expected ']' at $i" }
            i++
            return l
        }
    }

    private fun str(): String {
        require(s[i] == '"') { "expected string at $i" }
        i++
        val b = StringBuilder()
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (val e = s[i]) {
                    'n' -> b.append('\n')

                    't' -> b.append('\t')

                    'u' -> {
                        b.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                        i += 4
                    }

                    else -> b.append(e)
                }
            } else {
                b.append(s[i])
            }
            i++
        }
        i++
        return b.toString()
    }

    private fun num(): Double {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        return s.substring(start, i).toDouble()
    }
}
