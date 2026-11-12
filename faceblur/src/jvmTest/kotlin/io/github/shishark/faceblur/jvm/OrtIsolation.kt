// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlanMasker
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.filemode.FileModeFakes
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.mask.HeapRemainingProbe
import java.io.File
import java.net.URLClassLoader

/**
 * The harness of the "masks without a model" route on the JVM (ADR 0015 Verification): a class loader that refuses
 * and counts every request for an ONNX Runtime class (`ai.onnxruntime.*`, among them `OnnxRuntime`, whose static
 * initialiser loads the native library, and `OrtEnvironment`). [main] runs one scenario in it and prints one line:
 * `requests=<n> loaded=<FaceMasker,DetectorModel classes loaded> result=<...>`. Each scenario runs in its own JVM
 * (started by `PlanMaskerIsolationTest`), so nothing an earlier test loaded can hide or fake a request.
 */
internal object OrtIsolationMain {
    /** Refuses `ai.onnxruntime.*` and records the library classes it defines; parent: the platform loader only. */
    class RefusingLoader(urls: Array<java.net.URL>) : URLClassLoader(urls, getPlatformClassLoader()) {
        val refused = ArrayList<String>()
        val defined = ArrayList<String>()

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("ai.onnxruntime.")) {
                synchronized(refused) { refused += name }
                throw ClassNotFoundException("refused by the test harness")
            }
            return super.loadClass(name, resolve)
        }

        override fun findClass(name: String): Class<*> {
            val c = super.findClass(name)
            if (name.startsWith("io.github.shishark.faceblur.")) synchronized(defined) { defined += name }
            return c
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val urls = System.getProperty("java.class.path").split(File.pathSeparator)
            .map { File(it).toURI().toURL() }.toTypedArray()
        val loader = RefusingLoader(urls)
        val scenario = Class.forName(if (args[0] == "control") CONTROL else PLAN, true, loader)
        val result = scenario.getMethod("run").invoke(null) as String
        val watched = loader.defined.filter { it == FACE_MASKER || it == DETECTOR_MODEL }.sorted()
        println("requests=${loader.refused.size} loaded=${watched.joinToString(",")} result=$result")
    }

    const val PLAN = "io.github.shishark.faceblur.jvm.OrtIsolationPlanScenario"
    const val CONTROL = "io.github.shishark.faceblur.jvm.OrtIsolationControlScenario"
    const val FACE_MASKER = "io.github.shishark.faceblur.FaceMasker"
    const val DETECTOR_MODEL = "io.github.shishark.faceblur.DetectorModel"

    /**
     * The process-wide setup of both scenarios: fake file-mode parts, the JVM heap probe, and in the runtime slot an
     * ONNX Runtime binding that is created only when a session is opened (so its classes are requested then).
     */
    fun install(fakes: FileModeFakes) {
        FaceBlurRuntime.overrideDefaults(
            PlatformDefaults(
                runtime = LazyOrtRuntime,
                memoryProbe = HeapRemainingProbe,
                imageCodec = fakes.codec,
                targetWriter = fakes.writer,
            ),
        )
    }

    /** planFile -> withMasks -> maskFile on a fake source; returns what was written. */
    fun planRoute(fakes: FileModeFakes): String {
        val planMasker = PlanMasker()
        val plan = planMasker.planFile("/photos/a.jpg", FileOptions(maxLongSide = 0))
            .withMasks(listOf(Mask.manual(100f, 100f, 200f, 220f)))
        val r = planMasker.maskFile(
            "/photos/a.jpg",
            OutputTarget.NewFile("/out/a.jpg"),
            plan,
            FileOptions(maxLongSide = 0),
        )
        return "writes=${fakes.writes.size} masks=${r.masks.size} faces=${r.faces.size}"
    }
}

/**
 * The ONNX Runtime binding, created only when a session is opened. A named class, not a lambda: the lambda bootstrap
 * would resolve `open`'s method type and load `DetectorModel` at install time, which the plan scenario checks for.
 */
internal object LazyOrtRuntime : ModelRuntime {
    override fun open(model: DetectorModel, threads: Int): ModelSession = OrtModelRuntime().open(model, threads)
}

/** The route under test: no model, no FaceMasker. */
internal object OrtIsolationPlanScenario {
    @JvmStatic
    fun run(): String {
        val fakes = FileModeFakes()
        OrtIsolationMain.install(fakes)
        return OrtIsolationMain.planRoute(fakes)
    }
}

/** The positive control: the same setup and route, then a FaceMasker, which opens a session through ONNX Runtime. */
internal object OrtIsolationControlScenario {
    @JvmStatic
    fun run(): String {
        val fakes = FileModeFakes()
        OrtIsolationMain.install(fakes)
        val route = OrtIsolationMain.planRoute(fakes)
        val masker = try {
            FaceMasker(testYuNetModel())
            "created"
        } catch (e: FaceMaskException.RuntimeUnavailable) {
            "RuntimeUnavailable"
        }
        return "$route masker=$masker"
    }
}
