// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.jvm

import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MaskPlan
import io.github.shishark.faceblur.MaskShape
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.model.ModelLoader
import io.github.shishark.faceblur.internal.model.OnnxDescription
import io.github.shishark.faceblur.internal.model.OnnxPreParse
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PassOutput
import io.github.shishark.faceblur.randomPixels
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ST-002 walking skeleton 1: the public `FaceMasker` on the JVM with ONNX Runtime JVM and the reference model
 * fixture loaded through `DetectorModel.load` (SHA-256 pinned), on the registered single-face fixture.
 */
class PipelineSkeletonTest {
    private val fixture = "pd/pd-p06-single-face-s100-x00200-y01600.png"
    private val model by lazy { TestModels.yunet() }

    @BeforeTest
    fun install() = JvmTestRuntime.install()

    @AfterTest
    fun uninstall() = FaceBlurRuntime.uninstall()

    /**
     * Pixels the default mask of [box] may change: r < 1 + band with the independent formula (0.8 w, 0.9 h,
     * centre moved up by 0.08 h; band = max(0.10, 1 / smaller radius)).
     */
    private fun mayChange(x: Int, y: Int, box: io.github.shishark.faceblur.FaceBox): Boolean {
        val w = (box.right - box.left).toDouble()
        val h = (box.bottom - box.top).toDouble()
        val rx = 0.8 * w
        val ry = 0.9 * h
        val cx = (box.left + box.right) / 2.0
        val cy = (box.top + box.bottom) / 2.0 - 0.08 * h
        val r = hypot((x + 0.5 - cx) / rx, (y + 0.5 - cy) / ry)
        return r < 1.0 + maxOf(0.10, 1.0 / minOf(rx, ry)) + 1e-6
    }

    @Test
    fun ac1DefaultPipelineFindsTheFaceAndChangesOnlyTheMaskRegion() {
        val original = Fixtures.image(fixture)
        val img = IntArrayPixels(original.width, original.height, original.data.copyOf())
        val result = FaceMasker(model).use { it.maskInPlace(img) }
        assertEquals(1, result.faces.size, "one face: ${result.faces}")
        val face = result.faces.single()
        assertTrue(face.score >= 0.5f, "score ${face.score}")
        assertTrue(face.left >= 0f && face.top >= 0f && face.right <= 640f && face.bottom <= 640f, "full-image pixels")
        assertEquals(1, result.masks.size)
        assertEquals(1, result.timings.passes, "a 640 px image needs only the global pass")
        assertTrue(result.timings.detectMs > 0.0 && result.timings.maskMs > 0.0 && result.timings.totalMs > 0.0)
        var changed = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val i = y * img.width + x
                if (img.data[i] != original.data[i]) {
                    changed++
                    assertTrue(mayChange(x, y, face), "pixel ($x,$y) changed outside the mask region")
                }
            }
        }
        assertTrue(changed > 1000, "the mask was written ($changed pixels)")
    }

    @Test
    fun ac1TheBoxMatchesTheOpenCvReference() {
        val img = Fixtures.image(fixture)
        val face = FaceMasker(model).use { it.detect(img) }.faces.single()
        val json = Json.parse(Fixtures.file("yunet_reference.json").readText()) as Map<*, *>
        val entry = (json["images"] as List<*>).map { it as Map<*, *> }.single { it["file"] == fixture }
        val f = (entry["faces"] as List<*>).single() as Map<*, *>
        val x = f["x"] as Double
        val y = f["y"] as Double
        val refBox = Cand(x, y, x + f["w"] as Double, y + f["h"] as Double, f["score"] as Double, DoubleArray(0))
        val mine =
            Cand(
                face.left.toDouble(),
                face.top.toDouble(),
                face.right.toDouble(),
                face.bottom.toDouble(),
                face.score.toDouble(),
                DoubleArray(0),
            )
        assertTrue(mine.iou(refBox) >= 0.99, "IoU ${mine.iou(refBox)}")
    }

    @Test
    fun ac2MaskedPixelsDoNotDependOnTheFaceRegion() {
        val original = Fixtures.image(fixture)
        FaceMasker(model).use { masker ->
            val plan = masker.detect(original).toMaskPlan()
            val geometry = plan.masks.map { it.toTestGeometry() }
            val noise = randomPixels(original.width, original.height, 77)
            val replaced = original.data.copyOf()
            for (y in 0 until original.height) {
                for (x in 0 until original.width) {
                    if (geometry.any { it(x, y) }) replaced[y * original.width + x] = noise[y * original.width + x]
                }
            }
            assertFalse(replaced.contentEquals(original.data), "the face region was replaced")
            val a = IntArrayPixels(original.width, original.height, original.data.copyOf())
            val b = IntArrayPixels(original.width, original.height, replaced)
            masker.applyMasks(a, plan)
            masker.applyMasks(b, plan)
            assertContentEquals(a.data, b.data)
        }
    }

    private fun Mask.toTestGeometry(): (Int, Int) -> Boolean = { x, y ->
        val dx = (x + 0.5 - centerX) / radiusX
        val dy = (y + 0.5 - centerY) / radiusY
        if (shape ==
            MaskShape.ELLIPSE
        ) {
            dx * dx + dy * dy <= 1.0
        } else {
            maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) <= 1.0
        }
    }

    @Test
    fun ac3OnlyRegisteredFixturesAreOpenedAndOtherPathsAreRefused() {
        Fixtures.image(fixture)
        assertTrue(Fixtures.opened.all { Fixtures.isRegisteredPath(it) })
        assertFailsWith<IllegalArgumentException> { Fixtures.file("../gradle/libs.versions.toml") }
        assertFailsWith<IllegalArgumentException> { Fixtures.file("../../etc/hosts") }
    }

    @Test
    fun detectOnlyLeavesTheImageUnchanged() {
        val original = Fixtures.image(fixture)
        val copy = original.data.copyOf()
        val d = FaceMasker(model).use { it.detect(original) }
        assertContentEquals(copy, original.data)
        assertEquals(640, d.width)
        assertEquals(640, d.outputWidth)
        assertEquals(0.0, d.timings.maskMs)
        assertEquals(null, d.timings.decodeMs)
    }

    /**
     * ADR 0012 decision 4 step 3: with a pin, a model whose SHA-256 differs is refused before it is parsed and before
     * any runtime sees it (parser and session counters stay 0); the unchanged model loads and creates exactly one
     * session (control).
     */
    @Test
    fun aPinnedModelIsCheckedBeforeTheParserAndTheRuntimeSeeIt() {
        val good = TestModels.yunetBytes()
        var parsed = 0
        val counting: (ByteArray) -> OnnxDescription = {
            parsed++
            OnnxPreParse.parse(it)
        }
        val runtime = OrtModelRuntime()
        for (offset in listOf(0, good.size / 2, good.size - 1)) {
            val changed = good.copyOf().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertFailsWith<FaceMaskException.ModelIntegrity>("byte $offset") {
                ModelLoader.load(ModelSource.fromBytes(changed), TestModels.yunetProfile(), 52_428_800L, counting)
            }
        }
        assertEquals(0, parsed, "the parser never saw a changed model")
        assertEquals(0, runtime.sessionsOpened)
        val model = ModelLoader.load(ModelSource.fromBytes(good), TestModels.yunetProfile(), 52_428_800L, counting)
        assertEquals(1, parsed)
        FaceMasker.forTest(model, FaceMaskerConfig(), runtime).close()
        assertEquals(1, runtime.sessionsOpened, "control: one session per instance")
    }

    @Test
    fun withoutAnInstalledRuntimeTheConstructorFailsClosed() {
        FaceBlurRuntime.uninstall()
        val e = assertFailsWith<FaceMaskException.RuntimeUnavailable> { FaceMasker(model) }
        assertTrue(e.message!!.contains("no ONNX Runtime binding"))
    }

    @Test
    fun theFixturePinEqualsTheBuildPinAndTheLoadedBytes() {
        val props = Properties().apply { Fixtures.file("yunet/yunet.properties").reader().use { load(it) } }
        assertEquals(props.getProperty("sha256").trim().lowercase(), TestModels.YUNET_SHA256)
        val bytes = TestModels.yunetFile.readBytes()
        assertEquals(props.getProperty("size").trim().toLong(), model.info.sizeBytes)
        val independent =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(independent, model.info.sha256)
        assertEquals(TestModels.YUNET_SHA256, model.info.sha256)
    }

    @Test
    fun aSecondCallOnTheSameInstanceFailsWhileTheFirstRunsAndTwoInstancesWork() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var armed = false
        val blocking =
            ModelRuntime { m, t ->
                val real = OrtModelRuntime().open(m, t)
                object : ModelSession by real {
                    override fun run(
                        input: FloatArray,
                        inputShape: LongArray,
                        outputNames: List<String>,
                        maxElements: Long,
                        timeoutMillis: Long,
                    ): Map<String, PassOutput> {
                        if (!armed) return real.run(input, inputShape, outputNames, maxElements, timeoutMillis)
                        entered.countDown()
                        release.await(30, TimeUnit.SECONDS)
                        return emptyMap()
                    }
                }
            }
        val img = Fixtures.image(fixture)
        FaceMasker.forTest(model, FaceMaskerConfig(), blocking).use { masker ->
            armed = true
            var first: Throwable? = null
            val t = Thread { first = runCatching { masker.detect(img) }.exceptionOrNull() }
            t.start()
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            assertFailsWith<FaceMaskException.ConcurrentUse> { masker.detect(img) }
            release.countDown()
            t.join(30_000)
            // The blocked session returned no outputs: the first call fails the output check, not with ConcurrentUse.
            assertTrue(first is FaceMaskException.ModelOutputInvalid, "first call: $first")
        }
        // Control: two instances on two threads both succeed.
        val results = arrayOfNulls<Int>(2)
        val threads =
            (0..1).map { k ->
                Thread {
                    FaceMasker(model).use { results[k] = it.detect(Fixtures.image(fixture)).faces.size }
                }.also { it.start() }
            }
        threads.forEach { it.join(60_000) }
        assertEquals(listOf<Int?>(1, 1), results.toList())
    }

    @Test
    fun manyCallsReuseOneSessionAndCallsAfterCloseFail() {
        val counting = OrtModelRuntime()
        val masker = FaceMasker.forTest(model, FaceMaskerConfig(), counting)
        val img = Fixtures.image(fixture)
        repeat(20) { assertEquals(1, masker.detect(img).faces.size) }
        assertEquals(1, counting.sessionsOpened)
        masker.close()
        masker.close()
        assertFailsWith<FaceMaskException.Closed> { masker.detect(img) }
        assertFailsWith<FaceMaskException.Closed> {
            masker.applyMasks(IntArrayPixels(640, 640), MaskPlan(640, 640, emptyList()))
        }
    }
}
