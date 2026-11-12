// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The built AAR must carry the consumer R8/ProGuard rule that keeps ONNX Runtime's Java API. Without it, an app that
 * minifies its release build aborts when the first session is opened (the native library looks up
 * `ai.onnxruntime.NodeInfo.<init>` and other members by name, and R8 has removed them). The test opens the AAR that
 * the build produced (system property `faceblur.aar`), not the source rules file, so a lost wiring in the build is
 * caught as well as a lost rule.
 */
class AarConsumerRulesTest {
    private fun aar(): File {
        val path = System.getProperty("faceblur.aar")
        assertNotNull(path, "system property faceblur.aar is not set")
        val file = File(path)
        assertTrue(file.isFile, "the AAR was not built: $path")
        return file
    }

    private fun proguardTxt(): String {
        ZipFile(aar()).use { zip ->
            val entry = zip.getEntry("proguard.txt")
            assertNotNull(entry, "the AAR has no proguard.txt: consumer R8 rules are not packaged")
            return zip.getInputStream(entry).bufferedReader().readText()
        }
    }

    /** The rules without comments, with all white space collapsed to single blanks. */
    private fun normalisedRules(text: String): String {
        val withoutComments = text.lineSequence().map { it.substringBefore('#') }.joinToString(" ")
        return withoutComments.trim().replace(Regex("\\s+"), " ")
    }

    @Test
    fun aarCarriesTheOnnxRuntimeKeepRule() {
        val rules = normalisedRules(proguardTxt())
        assertTrue(
            rules.contains("-keep class ai.onnxruntime.** { *; }"),
            "proguard.txt of the AAR lacks the ONNX Runtime keep rule; rules found: $rules",
        )
    }

    @Test
    fun aarRulesHoldNoDisablingFlags() {
        // The consumer rules must not switch off shrinking, optimisation or obfuscation for the whole app.
        val rules = normalisedRules(proguardTxt())
        for (flag in listOf("-dontshrink", "-dontoptimize", "-dontobfuscate", "-keep class ** ")) {
            assertTrue(!rules.contains(flag), "consumer rules must not contain $flag")
        }
    }

    @Test
    fun normalisationIgnoresCommentsAndLayout() {
        val text = "# -keep class ai.onnxruntime.** { *; }\n-keep   class ai.onnxruntime.**\n  { *; }  # trailing\n"
        assertEquals("-keep class ai.onnxruntime.** { *; }", normalisedRules(text))
    }
}
