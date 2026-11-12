// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.Detection
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.filemode.ImageFingerprint
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR 0015 Verification, compile-failure test: Java source that tries to read the plan binding of a `Detection` or a
 * `MaskPlan`, to build a plan with one, or to make one, does not compile. Each attempt is compiled on its own against
 * the library's JVM classes with the JDK compiler, and javac's message is checked. Controls: the public API compiles
 * from the same harness, and a non-synthetic internal member with the same name pattern compiles too, so the failures
 * come from the members being synthetic (or private), not from a wrong name or classpath.
 */
class FingerprintJavaCompileTest {
    /** The JVM name of a member of [c] whose Kotlin name starts with [prefix] (internal names carry a module suffix). */
    private fun jvmName(c: Class<*>, prefix: String): String =
        c.declaredMethods.single { it.name.startsWith(prefix) }.name

    private val imports = "import io.github.shishark.faceblur.*;\nimport io.github.shishark.faceblur.filemode.*;\n" +
        "import java.util.List;\n"

    /** Compiles [body] as the body of a static method; returns javac's diagnostics, or null when it compiled. */
    private fun compile(body: String): String? {
        val javac = assertNotNull(ToolProvider.getSystemJavaCompiler(), "the test JVM is a JDK")
        val dir = Files.createTempDirectory("fingerprint-java").toFile()
        try {
            val src = File(dir, "Probe.java")
            src.writeText(
                "$imports\npublic class Probe {\n    static Object probe() throws Exception {\n$body\n    }\n}\n",
            )
            val err = ByteArrayOutputStream()
            val args =
                arrayOf("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", dir.path, src.path)
            val result = javac.run(null, ByteArrayOutputStream(), err, *args)
            return if (result == 0) null else err.toString(Charsets.UTF_8)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun javaSourceCannotReadSetOrMakeAFingerprint() {
        val detectionGetter = jvmName(Detection::class.java, "getFingerprint")
        val planGetter = jvmName(MaskPlan::class.java, "getFingerprint")
        val boundTo = jvmName(Detection::class.java, "boundTo")
        val detectionView = jvmName(Detection::class.java, "getView")
        val planView = jvmName(MaskPlan::class.java, "getView")
        val bound = jvmName(Class.forName("${MaskPlan::class.java.name}\$Companion"), "bound")
        val of = jvmName(ImageFingerprint.Companion::class.java, "of")
        val matches = jvmName(ImageFingerprint::class.java, "matches")
        val attempts = listOf(
            "Detection accessor" to "        Detection d = null;\n        return d.$detectionGetter();" to
                "error: cannot find symbol",
            "MaskPlan accessor" to "        MaskPlan p = null;\n        return p.$planGetter();" to
                "error: cannot find symbol",
            "Detection binding copy" to "        Detection d = null;\n        return d.$boundTo(null);" to
                "error: cannot find symbol",
            "MaskPlan constructor with a fingerprint" to
                "        return new MaskPlan(1, 1, List.<Mask>of(), (ImageFingerprint) null, (Integer) null);" to
                "MaskPlan(int,int,List<Mask>,ImageFingerprint,Integer) has private access in MaskPlan",
            "MaskPlan factory with a fingerprint" to
                "        return MaskPlan.Companion.$bound(1, 1, List.<Mask>of(), null, null);" to
                "error: cannot find symbol",
            "Detection view accessor" to "        Detection d = null;\n        return d.$detectionView();" to
                "error: cannot find symbol",
            "MaskPlan view accessor" to "        MaskPlan p = null;\n        return p.$planView();" to
                "error: cannot find symbol",
            "fingerprint factory" to "        return ImageFingerprint.Companion.$of(null);" to
                "error: cannot find symbol",
            "fingerprint comparison" to "        ImageFingerprint f = null;\n        return f.$matches(f);" to
                "error: cannot find symbol",
            "fingerprint constructor" to "        return new ImageFingerprint(new byte[32]);" to
                "ImageFingerprint(byte[]) has private access in ImageFingerprint",
        )
        for ((pair, expected) in attempts) {
            val (name, body) = pair
            val diagnostics = compile(body)
            assertNotNull(diagnostics, "$name must not compile")
            assertTrue(diagnostics.contains("error: "), "$name: a compile error:\n$diagnostics")
            assertTrue(diagnostics.contains(expected), "$name: expected '$expected' in:\n$diagnostics")
        }
    }

    @Test
    fun controlsThePublicApiAndANonSyntheticInternalMemberCompile() {
        val public = """
            MaskPlan p = new MaskPlan(10, 10, List.<Mask>of());
            MaskPlan edited = p.withMasks(List.of(Mask.manual(1f, 1f, 5f, 5f)));
            PlanMasker m = new PlanMasker();
            PlanMasker m2 = new PlanMasker(new MaskingConfig());
            MaskingConfig c = m2.getMasking();
            UprightBox u = UprightBox.fromRaw(0f, 0f, 1f, 1f, 4, 2, 6);
            FaceMaskException.SourceChanged e = null;
            return List.of(edited, m, c, u.getImageWidth());
        """.trimIndent()
        assertEquals(null, compile(public), "the public API compiles")
        // `parts` is internal but not synthetic: Java can call its mangled getter. The same pattern as the
        // fingerprint getter, so the failures above come from the synthetic flag.
        val parts = jvmName(Detection::class.java, "getParts")
        assertTrue(parts.contains('$'), "an internal getter has a mangled name: $parts")
        assertEquals(null, compile("        Detection d = null;\n        return d.$parts();"), "the control compiles")
    }
}
