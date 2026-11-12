// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.internal.model.OnnxPreParse
import io.github.shishark.faceblur.jvm.PreParseDifferential.OracleView
import io.github.shishark.faceblur.jvm.PreParseDifferential.Sample
import io.github.shishark.faceblur.testmodel.HostileModels
import io.github.shishark.faceblur.testmodel.Onnx
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parts of the pre-parse differential that run without the external protobuf oracle: the generator's predictions
 * against the walker (fixed seed), and the comparison's negative controls (planted disagreements must be found).
 */
class PreParseDifferentialTest {
    /** Every generated model gets the verdict the generator predicts from what it planted. */
    @Test
    fun generatedModelsGetTheWalkerVerdictTheGeneratorPredicts() {
        val generator = ModelGenerator(PreParseDifferential.DEFAULT_SEED)
        val kinds = HashMap<String, Int>()
        repeat(3_000) { k ->
            val g = generator.next()
            val verdict = PreParseDifferential.walker(g.bytes)
            val kind = when {
                g.strict.isNotEmpty() -> {
                    assertTrue(verdict.code != null, "model $k ${g.strict}: accepted")
                    for (s in g.strict) kinds[s] = (kinds[s] ?: 0) + 1
                    "refused (strict)"
                }

                g.visibleExternal -> {
                    assertEquals(HostileModels.EXTERNAL_DATA, verdict.code, "model $k")
                    "external"
                }

                else -> {
                    assertEquals(null, verdict.code, "model $k")
                    "accepted"
                }
            }
            kinds[kind] = (kinds[kind] ?: 0) + 1
        }
        // The generator keeps producing every kind (a generator that stops planting would pass vacuously).
        for (kind in listOf("accepted", "external", "refused (strict)") + STRICT_KINDS) {
            assertTrue((kinds[kind] ?: 0) >= 10, "too few '$kind': $kinds")
        }
        assertTrue((kinds["accepted"] ?: 0) >= 600 && (kinds["external"] ?: 0) >= 600, "$kinds")
    }

    @Test
    fun theComparisonFindsEveryPlantedDisagreement() {
        val accepted = Onnx.yunetModel()
        val w = PreParseDifferential.walker(accepted)
        val sample = Sample("file", "control", accepted)
        val truthful = OracleView(parsesDefault = true, parsesDeep = true, external = false, unknown = 0, io = w.io)
        assertEquals(
            emptyList(),
            PreParseDifferential.compare(sample, w, listOf(truthful, truthful)).map {
                it.property
            },
        )
        // P1: protobuf sees external data in a model the walker accepted (in the second schema only).
        val external = OracleView(true, true, external = true, unknown = 0, io = w.io)
        assertEquals(
            listOf("P1"),
            PreParseDifferential.compare(sample, w, listOf(truthful, external)).map {
                it.property
            },
        )
        // P1 also when protobuf parses only with the raised nesting limit.
        val deepOnly = OracleView(parsesDefault = false, parsesDeep = true, external = true, unknown = 0, io = null)
        assertEquals(listOf("P1"), PreParseDifferential.compare(sample, w, listOf(deepOnly)).map { it.property })
        // P2: another description.
        val otherIo = OracleView(true, true, false, 0, w.io!!.replace("ir=8", "ir=9"))
        assertEquals(listOf("P2"), PreParseDifferential.compare(sample, w, listOf(otherIo)).map { it.property })
        // P4: an unknown field in a reference file; protobuf blind to a planted external tensor.
        val file = Sample("file", "file", accepted, wellFormedFile = true)
        val unknown = OracleView(true, true, false, unknown = 1, io = w.io)
        assertEquals(listOf("P4"), PreParseDifferential.compare(file, w, listOf(unknown)).map { it.property })
        val hostile = HostileModels.all().first { it.label == "external position: graph initializer" }
        val hostileSample = Sample("hostile", hostile.label, hostile.bytes, hostile.expected, isHostile = true)
        val hw = PreParseDifferential.walker(hostile.bytes)
        val blind = OracleView(true, true, external = false, unknown = 0, io = "x")
        assertEquals(listOf("P4"), PreParseDifferential.compare(hostileSample, hw, listOf(blind)).map { it.property })
        // P3: a generated model the walker refuses although protobuf sees nothing (no strictness planted).
        val plain = generatedWhere { it.strict.isEmpty() && !it.visibleExternal }
        val gs = Sample("generated", "g", plain.bytes, generated = plain)
        val pw = PreParseDifferential.walker(plain.bytes)
        val ok = OracleView(true, true, false, 0, pw.io)
        assertEquals(emptyList(), PreParseDifferential.compare(gs, pw, listOf(ok)).map { it.property })
        val refusedWalker = PreParseDifferential.WalkerVerdict("NOT_ONNX", null)
        assertEquals(listOf("P3"), PreParseDifferential.compare(gs, refusedWalker, listOf(ok)).map { it.property })
        // Protobuf sees external data the generator did not plant: P3, and P1 as the walker accepted.
        assertEquals(
            listOf("P1", "P3"),
            PreParseDifferential.compare(gs, pw, listOf(OracleView(true, true, true, 0, pw.io))).map { it.property }
                .distinct().sorted(),
        )
    }

    /** The run reports a planted disagreement and counts every model; the walker never throws (P0). */
    @Test
    fun aRunWithAPlantedOracleReportsTheDisagreement() {
        val samples = PreParseDifferential.samples(
            7,
            listOf("synthetic" to Onnx.yunetModel()),
            mutants = 300,
            generated = 300,
        )
        var planted = 0
        val tally = PreParseDifferential.run(samples) { bytes ->
            val w = PreParseDifferential.walker(bytes)
            val lie = w.code == null && planted++ == 5
            listOf(OracleView(true, true, external = lie, unknown = 0, io = w.io))
        }
        val properties = tally.findings.map { it.property }
        assertTrue("P1" in properties, tally.report())
        assertTrue("P0" !in properties, tally.report())
        val counted = tally.rows.values.sumOf { it[0] }
        assertEquals(samples.count(), counted)
        assertTrue(counted > HostileModels.all().size + 800)
        assertTrue(tally.report().startsWith("group | models"))
    }

    @Test
    fun theDescriptionUsesTheOracleFormatAndTheSamplesAreReproducible() {
        val d = PreParseDifferential.describe(OnnxPreParse.parse(Onnx.yunetModel(640, 480)))
        assertTrue(d.startsWith("ir=8;ops=:13;in=696e707574/T/1/1,3,480,640;out=636c735f38/T/1/1,4800,1|"), d)
        val oneOf = PreParseDifferential.describe(OnnxPreParse.parse(HostileModels.contractOneof()))
        assertTrue(oneOf.contains(";in=78/N|79/T/1/3;"), oneOf)
        fun digest(seed: Long) = MessageDigest.getInstance("SHA-256").apply {
            for (s in PreParseDifferential.samples(seed, emptyList(), mutants = 200, generated = 200)) update(s.bytes)
        }.digest().toList()
        assertEquals(digest(11), digest(11))
        assertTrue(digest(11) != digest(12))
    }

    private fun generatedWhere(predicate: (ModelGenerator.Model) -> Boolean): ModelGenerator.Model {
        val generator = ModelGenerator(1)
        while (true) generator.next().let { if (predicate(it)) return it }
    }

    private companion object {
        val STRICT_KINDS = listOf(
            ModelGenerator.DUPLICATE,
            ModelGenerator.GROUP,
            ModelGenerator.WRONG_WIRE,
            ModelGenerator.LOCATION_NOT_VISIBLE,
            ModelGenerator.DEPTH,
        )
    }
}
