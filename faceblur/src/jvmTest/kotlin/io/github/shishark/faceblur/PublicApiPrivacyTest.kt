// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur

import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The public API offers no reversible, transparent or partial style or setting (PRIV-000, FR-005 (1)/(2), ST-007 AC6)
 * and no "anonymi…" wording (ADR 0002 decision 1).
 *
 * Reads the reviewed ABI dumps. `checkKotlinAbi` (part of `check`) fails when a dump differs from the compiled
 * public API, so together the two checks cover the real public surface of every target. Runs with the module
 * directory as the working directory (Gradle's default for test tasks).
 */
class PublicApiPrivacyTest {
    private val dumps = listOf("api/jvm/faceblur.api", "api/android/faceblur.api", "api/faceblur.klib.api")

    private val opaqueStyles = setOf("GRADIENT_OVAL", "SMOOTH_FILL", "SOLID")

    /** Words that must not appear in any public symbol (checked in the ABI dumps, not in KDoc). */
    private val forbidden =
        listOf("blur", "pixelat", "mosaic", "cosmetic", "gauss", "transparen", "opacity", "alpha", "anonymi")

    private fun dump(path: String): String {
        val file = File(path)
        assertTrue(file.isFile, "ABI dump $path missing (working directory ${File(".").absoluteFile.name})")
        return file.readText()
    }

    /** Problems found in one dump text: forbidden words, and a style list that is not exactly the opaque styles. */
    private fun problems(path: String, text: String): List<String> {
        val found = ArrayList<String>()
        // The product, package and module are called "faceblur"; every other occurrence of "blur" is a finding.
        val symbols = text.replace(Regex("face ?blur", RegexOption.IGNORE_CASE), "")
        for (word in forbidden) if (symbols.contains(word, ignoreCase = true)) found += "$path contains '$word'"
        val entries = styleEntries(path, text)
        if (entries != opaqueStyles) found += "$path MaskStyle entries $entries"
        return found
    }

    /** MaskStyle entries as listed in a klib dump (`enum entry X`) or a JVM/Android dump (static fields). */
    private fun styleEntries(path: String, text: String): Set<String> = if (path.endsWith(".klib.api")) {
        Regex("""enum entry (\w+) // io\.github\.shishark\.faceblur/MaskStyle\.""").findAll(text)
    } else {
        Regex("""public static final field (\w+) Lio/github/shishark/faceblur/MaskStyle;""").findAll(text)
    }.map { it.groupValues[1] }.toSet()

    @Test
    fun abiDumpsHaveExactlyTheOpaqueStylesAndNoWeakeningWords() {
        for (path in dumps) assertEquals(emptyList(), problems(path, dump(path)))
    }

    /** Negative control: the same check fails on dumps with a planted fourth style or an alpha/opacity member. */
    @Test
    fun theDumpCheckDetectsPlantedEntriesAndMembers() {
        val jvm = dump("api/jvm/faceblur.api")
        val klib = dump("api/faceblur.klib.api")
        val fourthJvm =
            jvm.replace(
                "public static final field SOLID Lio/github/shishark/faceblur/MaskStyle;",
                "public static final field SOLID Lio/github/shishark/faceblur/MaskStyle;\n" +
                    "\tpublic static final field OUTLINE Lio/github/shishark/faceblur/MaskStyle;",
            )
        assertTrue(problems("api/jvm/faceblur.api", fourthJvm).any { "OUTLINE" in it })
        val fourthKlib =
            klib.replace(
                "    enum entry SOLID // io.github.shishark.faceblur/MaskStyle.SOLID|null[0]",
                "    enum entry SOLID // io.github.shishark.faceblur/MaskStyle.SOLID|null[0]\n" +
                    "    enum entry OUTLINE // io.github.shishark.faceblur/MaskStyle.OUTLINE|null[0]",
            )
        assertTrue(problems("api/faceblur.klib.api", fourthKlib).any { "OUTLINE" in it })
        for (member in listOf("public final fun getMaskOpacity ()F", "public final fun getAlpha ()I")) {
            assertFalse(problems("api/jvm/faceblur.api", "$jvm\n$member\n").isEmpty(), member)
        }
    }

    @Test
    fun maskStyleConstantsAtRuntimeAreTheOpaqueStyles() {
        val names = MaskStyle::class.java.enumConstants.map { it.name }
        assertContentEquals(listOf("GRADIENT_OVAL", "SMOOTH_FILL", "SOLID"), names)
    }

    @Test
    fun mainSourcesNeverSayAnonymise() {
        val roots = File("src").listFiles { f -> f.isDirectory && f.name.endsWith("Main") }.orEmpty().toList()
        assertTrue(roots.any { it.name == "commonMain" }, "source roots not found: $roots")
        val offenders =
            roots
                .flatMap { root ->
                    root.walk().filter { it.isFile && (it.extension == "kt" || it.extension == "swift") }.toList()
                }
                .filter { it.readText().contains("anonymi", ignoreCase = true) }
                .map { it.name }
        assertTrue(offenders.isEmpty(), "files with 'anonymi': $offenders")
    }

    /**
     * Java code cannot construct a FaceBox. The constructor is private and the factory synthetic (Java source
     * cannot call synthetic members). The JVM keeps a public static `Companion` field for the internal companion;
     * it exposes no callable member, which this test also asserts.
     */
    @Test
    fun faceBoxHasNoJavaCallableConstructorOrFactory() {
        val cls = FaceBox::class.java
        fun javaCallable(m: java.lang.reflect.Member) = !m.isSynthetic && Modifier.isPublic(m.modifiers)
        val ctors = (listOf(cls) + cls.declaredClasses).flatMap {
            it.declaredConstructors.toList()
        }.filter(::javaCallable)
        assertTrue(ctors.isEmpty(), "Java-callable constructors: $ctors")
        val factories =
            cls.declaredClasses
                .flatMap { it.declaredMethods.toList() }
                .filter { javaCallable(it) && it.returnType == cls }
        assertTrue(factories.isEmpty(), "Java-callable factories: $factories")
        val statics = cls.declaredMethods.filter {
            javaCallable(it) && Modifier.isStatic(it.modifiers) &&
                it.returnType == cls
        }
        assertTrue(statics.isEmpty(), "Java-callable static factories: $statics")
    }
}
