// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.internal.model.DeclaredTensor
import io.github.shishark.faceblur.internal.model.OnnxDescription
import io.github.shishark.faceblur.internal.model.OnnxPreParse
import io.github.shishark.faceblur.testmodel.HostileModels
import io.github.shishark.faceblur.testmodel.Onnx
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.SplittableRandom
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * The differential check of the pre-parse walker against a full protobuf parser of onnx-ml.proto (ADR 0012
 * "Verification", differential oracle). The oracle is an external process (a full protobuf runtime with message
 * classes built from the pinned schema files; not part of the repository or of the build) that answers one line per
 * model, see [OracleView]. Properties checked for every model:
 * - **P1 (security):** when the oracle parses the model (at any nesting depth) and finds a tensor with a non-zero
 *   `data_location` or an `external_data` entry anywhere, the walker has refused it.
 * - **P2 (description):** when the walker accepts and the oracle parses with the runtime's default nesting limit, the
 *   walker's description (IR version, opset imports, the first two inputs that are not initializers, every output)
 *   equals the oracle's, for every schema version.
 * - **P3 (generated models):** the generator's record of what protobuf sees matches the oracle; without a deliberate
 *   strictness construction the walker accepts exactly the models the oracle parses, and refuses with
 *   `EXTERNAL_DATA` exactly those in which the oracle finds external data.
 * - **P4 (oracle sanity):** the oracle finds the external data that the hostile models plant where protobuf can see
 *   it, and no unknown field in the reference fixture and the derived models.
 * - **P0:** the walker never throws anything but `ModelRejected`.
 *
 * Models: the hostile set ([HostileModels]), the model files given (reference fixture, derived models), fixed-seed
 * byte mutants of all of them, and generated models ([ModelGenerator]) with and without byte mutations. Output never
 * contains model bytes or paths.
 */
internal object PreParseDifferential {
    /** The walker's verdict: the finding code, or null when accepted, with its description in the oracle format. */
    class WalkerVerdict(val code: String?, val io: String?)

    /** One schema's answer: parses (default and raised nesting limit), external data seen, unknown fields, io. */
    class OracleView(
        val parsesDefault: Boolean,
        val parsesDeep: Boolean,
        val external: Boolean,
        val unknown: Int,
        val io: String?,
    )

    /** A model of the run and what is known about it in advance. */
    class Sample(
        val group: String,
        val label: String,
        val bytes: ByteArray,
        /** Hostile set: the walker's expected code (null = accepted); not set for other groups. */
        val hostileExpected: String? = null,
        val isHostile: Boolean = false,
        /** Generated models (unmutated): the generator's record. */
        val generated: ModelGenerator.Model? = null,
        /** Reference fixture and derived models: no unknown field expected. */
        val wellFormedFile: Boolean = false,
    )

    class Finding(val property: String, val label: String, val detail: String)

    fun walker(bytes: ByteArray): WalkerVerdict = try {
        WalkerVerdict(null, describe(OnnxPreParse.parse(bytes)))
    } catch (e: FaceMaskException.ModelRejected) {
        WalkerVerdict(e.findings.joinToString(","), null)
    }

    /** The walker's description in the oracle's format (names as hex of their UTF-8 bytes). */
    fun describe(d: OnnxDescription): String {
        fun hex(s: String) = s.encodeToByteArray().joinToString("") { "%02x".format(it) }
        fun tensor(t: DeclaredTensor): String {
            if (!t.isTensor) return "${hex(t.name)}/N"
            val dims = t.dims?.joinToString(",") { it?.toString() ?: "?" } ?: "-"
            return "${hex(t.name)}/T/${t.elementType}/$dims"
        }
        return "ir=${d.irVersion};ops=" + d.opsetImports.entries.joinToString(",") { "${hex(it.key)}:${it.value}" } +
            ";in=" + d.inputs.joinToString("|") { tensor(it) } + ";out=" + d.outputs.joinToString("|") { tensor(it) }
    }

    fun parseOracleLine(line: String): List<OracleView> = line.split(" | ").map { part ->
        val f = part.split(" ", limit = 5)
        require(f.size == 5) { "oracle answer not understood" }
        OracleView(f[0] == "1", f[1] == "1", f[2] == "1", f[3].toInt(), f[4].takeIf { it != "!" })
    }

    /** Hostile models whose external data protobuf cannot see (dropped enum values; a later 0 wins). */
    private val PROTOBUF_INVISIBLE = setOf(
        "location: unknown data_location value",
        "location: negative data_location",
        "location: a second data_location occurrence",
    )

    /** Hostile models refused for another reason in which protobuf sees external data (merged duplicate). */
    private val PROTOBUF_VISIBLE_ELSEWHERE = setOf("duplicate singular: second graph with an external initializer")

    fun compare(s: Sample, w: WalkerVerdict, views: List<OracleView>): List<Finding> {
        val out = ArrayList<Finding>()
        val refused = w.code != null
        for ((i, v) in views.withIndex()) {
            val schema = "schema $i"
            if (v.parsesDeep && v.external && !refused) {
                out += Finding("P1", s.label, "$schema: protobuf sees external data, the walker accepted")
            }
            if (!refused && v.parsesDefault && v.io != w.io) {
                out += Finding("P2", s.label, "$schema: walker ${w.io} / protobuf ${v.io}")
            }
            if (s.isHostile) {
                val expected = (s.hostileExpected == HostileModels.EXTERNAL_DATA && s.label !in PROTOBUF_INVISIBLE) ||
                    s.label in PROTOBUF_VISIBLE_ELSEWHERE
                if (v.parsesDeep && v.external != expected) {
                    out += Finding("P4", s.label, "$schema: protobuf external ${v.external}, expected $expected")
                }
            }
            if (s.wellFormedFile && (!v.parsesDefault || v.unknown != 0)) {
                out += Finding("P4", s.label, "$schema: parses ${v.parsesDefault}, unknown fields ${v.unknown}")
            }
            val g = s.generated ?: continue
            if (!v.parsesDeep) {
                out += Finding("P3", s.label, "$schema: protobuf does not parse a generated model")
                continue
            }
            if (v.external != g.visibleExternal) {
                out +=
                    Finding("P3", s.label, "$schema: protobuf external ${v.external}, generator ${g.visibleExternal}")
            }
            if (g.strict.isEmpty() && g.deepest <= NEAR_DEPTH_LIMIT) {
                val expectedCode = if (v.external) HostileModels.EXTERNAL_DATA else null
                if (w.code != expectedCode || !v.parsesDefault) {
                    out += Finding(
                        "P3",
                        s.label,
                        "$schema: walker ${w.code ?: "accepted"}, protobuf parses ${v.parsesDefault} external ${v.external}",
                    )
                }
            }
        }
        if (s.generated != null && s.generated.strict.isNotEmpty() && !refused) {
            out += Finding("P3", s.label, "walker accepted a model built to be refused (${s.generated.strict})")
        }
        return out
    }

    /** Generated models nested this deep are left out of P3's accept/refuse equivalence (protobuf's own limit). */
    const val NEAR_DEPTH_LIMIT = 90

    /** The same mutation operators as the nightly crash-freedom fuzz harness. */
    fun mutate(base: ByteArray, r: SplittableRandom): ByteArray {
        var m = base.clone()
        val n = 1 + r.nextInt(8)
        var i = 0
        while (i < n && m.isNotEmpty()) {
            when (r.nextInt(9)) {
                0 -> m[r.nextInt(m.size)] = r.nextInt(256).toByte()

                1 -> m[r.nextInt(m.size)] = 0x80.toByte()

                2 -> {
                    val at = r.nextInt(m.size)
                    m[at] = (m[at].toInt() xor (1 shl r.nextInt(3))).toByte()
                }

                3 -> m[r.nextInt(m.size)] = 0xFF.toByte()

                4 -> m = m.copyOf(r.nextInt(m.size))

                5 -> {
                    val at = r.nextInt(m.size)
                    val len = minOf(m.size - at, 1 + r.nextInt(16))
                    for (k in 0 until len) m[at + k] = r.nextInt(256).toByte()
                }

                6 -> {
                    val from = r.nextInt(m.size)
                    val len = minOf(m.size - from, 1 + r.nextInt(64))
                    val at = r.nextInt(m.size + 1)
                    m = m.copyOfRange(0, at) + m.copyOfRange(from, from + len) + m.copyOfRange(at, m.size)
                }

                7 -> {
                    val at = r.nextInt(m.size)
                    val len = minOf(m.size - at, 1 + r.nextInt(64))
                    m = m.copyOfRange(0, at) + m.copyOfRange(at + len, m.size)
                }

                else -> {
                    val at = r.nextInt(m.size)
                    m[at] = (m[at] + if (r.nextBoolean()) 1 else -1).toByte()
                }
            }
            i++
        }
        return m
    }

    /**
     * The models of one run, in a fixed order for a [seed]: the hostile set, the [files], [mutants] byte mutants
     * (bases in turn: every file, then every hostile model), [generated] generated models and as many mutants of them.
     */
    fun samples(seed: Long, files: List<Pair<String, ByteArray>>, mutants: Int, generated: Int): Sequence<Sample> =
        sequence {
            val hostile = HostileModels.all()
            for (c in hostile) yield(Sample("hostile", c.label, c.bytes, c.expected, isHostile = true))
            for ((name, bytes) in files) yield(Sample("file", name, bytes, wellFormedFile = true))
            val bases = files + hostile.map { it.label to it.bytes } + ("synthetic YuNet layout" to Onnx.yunetModel())
            for (k in 0 until mutants) {
                val (name, bytes) = bases[k % bases.size]
                if (bytes.isEmpty()) continue
                yield(Sample("byte mutant", "mutant $k of $name", mutate(bytes, SplittableRandom(seed * 31 + k))))
            }
            val generator = ModelGenerator(seed)
            for (k in 0 until generated) {
                val g = generator.next()
                yield(Sample("generated", "generated $k", g.bytes, generated = g))
                if (g.bytes.isNotEmpty()) {
                    val m = mutate(g.bytes, SplittableRandom(seed * 37 + k))
                    yield(Sample("generated mutant", "mutant of generated $k", m))
                }
            }
        }

    /** Counts per group: models, walker accepts, oracle parses / sees external data (first schema), findings. */
    class Tally {
        val rows = LinkedHashMap<String, IntArray>()
        val codes = LinkedHashMap<String, Int>()
        val findings = ArrayList<Finding>()

        fun add(s: Sample, w: WalkerVerdict, views: List<OracleView>, found: List<Finding>) {
            val row = rows.getOrPut(s.group) { IntArray(7) }
            val v = views.first()
            row[0]++
            if (w.code == null) row[1]++
            if (v.parsesDefault) row[2]++
            if (v.parsesDeep) row[3]++
            if (v.parsesDeep && v.external) row[4]++
            if (w.code == null && v.parsesDefault) row[5]++
            row[6] += found.size
            val cell = "${s.group} | walker ${w.code ?: "accepts"} | protobuf " +
                when {
                    !v.parsesDeep -> "fails"
                    v.external -> "parses, external data"
                    !v.parsesDefault -> "parses only beyond the default depth"
                    else -> "parses"
                }
            codes[cell] = (codes[cell] ?: 0) + 1
            findings += found
        }

        fun report(): String = buildString {
            appendLine(
                "group | models | walker accepts | protobuf parses (default depth) | (any depth) | " +
                    "protobuf sees external data | both accept (P2 compared) | findings",
            )
            for ((g, r) in rows) appendLine("$g | " + r.joinToString(" | "))
            appendLine()
            for ((cell, n) in codes) appendLine("$cell | $n")
            appendLine()
            val byProperty = findings.groupingBy { it.property }.eachCount().toSortedMap()
            appendLine("findings: ${findings.size} $byProperty")
            for (f in findings.take(MAX_LISTED)) appendLine("  ${f.property} ${f.label}: ${f.detail}")
        }
    }

    private const val MAX_LISTED = 200

    fun run(samples: Sequence<Sample>, oracle: (ByteArray) -> List<OracleView>): Tally {
        val tally = Tally()
        for (s in samples) {
            val w = try {
                walker(s.bytes)
            } catch (e: Throwable) {
                tally.findings += Finding("P0", s.label, "walker threw ${e::class.simpleName}")
                continue
            }
            val views = oracle(s.bytes)
            tally.add(s, w, views, compare(s, w, views))
        }
        return tally
    }

    /** The oracle process: frames out (4-byte big-endian length, bytes), one answer line back per frame. */
    class ProcessOracle(command: List<String>) : AutoCloseable {
        private val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val toOracle = DataOutputStream(process.outputStream.buffered())
        private val fromOracle = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        val header: String = fromOracle.readLine() ?: error("the oracle did not start")

        fun judge(bytes: ByteArray): List<OracleView> {
            toOracle.writeInt(bytes.size)
            toOracle.write(bytes)
            toOracle.flush()
            return parseOracleLine(fromOracle.readLine() ?: error("the oracle stopped"))
        }

        override fun close() {
            toOracle.close()
            if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    /**
     * `--seed <n> --mutants <n> --generated <n> --oracle <command...> -- <model file>...`; exit 0 when no finding,
     * 1 otherwise. The oracle command is, for example, `python -I onnx_oracle.py onnx-ml-1.16.1.proto ...`.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val split = args.indexOf("--")
        val options = if (split < 0) args.toList() else args.take(split)
        val files = if (split < 0) emptyList() else args.drop(split + 1)
        fun value(name: String): String? = options.indexOf(name).takeIf { it >= 0 }?.let { options[it + 1] }
        val seed = value("--seed")?.toLong() ?: DEFAULT_SEED
        val mutants = value("--mutants")?.toInt() ?: 20_000
        val generated = value("--generated")?.toInt() ?: 20_000
        val command = options.drop(options.indexOf("--oracle") + 1)
        require("--oracle" in options && command.isNotEmpty()) { "--oracle <command...> is required (last option)" }
        val models = files.map { File(it).name to File(it).readBytes() }
        println(
            "pre-parse differential: seed $seed, mutants $mutants, generated $generated, files ${models.map {
                it.first
            }}",
        )
        val tally = ProcessOracle(command).use { oracle ->
            println("oracle: ${oracle.header}")
            run(samples(seed, models, mutants, generated), oracle::judge)
        }
        print(tally.report())
        exitProcess(if (tally.findings.isEmpty()) 0 else 1)
    }

    const val DEFAULT_SEED: Long = 20261103
}
