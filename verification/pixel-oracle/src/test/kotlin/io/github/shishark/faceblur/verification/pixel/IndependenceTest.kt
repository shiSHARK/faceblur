// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.pixel

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** ST-045 AC1: the oracle's sources use no library code and no code of the ST-042 checker. */
class IndependenceTest {
    private val forbidden = listOf(
        Regex("""import\s+io\.github\.shishark\.faceblur\.(?!verification\.pixel)"""),
        Regex("""io\.github\.shishark\.faceblur\.verification\.jpeg"""),
        Regex("""import\s+io\.github\.shishark\.faceblur\.verification\.(?!pixel)"""),
    )

    /** Returns the offending lines of [source]. */
    private fun violations(source: String): List<String> =
        source.lines().filter { line -> forbidden.any { it.containsMatchIn(line) } }

    @Test
    fun theScannerFlagsForbiddenImports() {
        assertEquals(1, violations("import io.github.shishark.faceblur.mask.MaskEngine").size)
        assertEquals(1, violations("import io.github.shishark.faceblur.geometry.MaskGeometry").size)
        assertEquals(1, violations("val x = io.github.shishark.faceblur.verification.jpeg.PrivacyChecker").size)
        assertEquals(1, violations("import io.github.shishark.faceblur.verification.jpeg.PrivacyChecker").size)
        assertEquals(
            0,
            violations("import io.github.shishark.faceblur.verification.pixel.Raster\nimport kotlin.math.max").size,
        )
    }

    @Test
    fun noSourceOfTheOracleOrItsFixturesUsesLibraryOrCheckerCode() {
        val roots = listOf("src/main/kotlin", "src/testFixtures/kotlin", "src/test/kotlin").map { File(it) }
        val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(files.size >= 5, "the scan found ${files.size} files; it must run in the module directory")
        val offending = files.filter { f ->
            // This test file names the forbidden strings on purpose.
            f.name != "IndependenceTest.kt" && violations(f.readText()).isNotEmpty()
        }
        assertEquals(emptyList(), offending.map { it.name })
    }
}
