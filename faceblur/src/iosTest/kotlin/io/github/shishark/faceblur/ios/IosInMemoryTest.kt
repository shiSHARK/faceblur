// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.CancellationToken
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.MutablePixelImage
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.detect.yunetProfile
import io.github.shishark.faceblur.gradientPixels
import io.github.shishark.faceblur.image.IntArrayPixels
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.MemoryRule
import io.github.shishark.faceblur.testmodel.Onnx
import io.github.shishark.faceblur.testmodel.pinnedTo
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlinx.cinterop.toLong
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSLibraryDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ST-027 / ADR 0004 on the simulator: the in-place path over caller-owned buffers in every accepted layout, with the
 * real Kotlin adapter and the common engine (fake glue), plus the iOS model source from memory and the memory probe.
 */
class IosInMemoryTest {
    private val w = 640
    private val h = 480
    private val bpr = w * 4 + 16 // padding after every row must never be touched

    private fun masker() = FaceMasker.forTest(
        testYuNetModel(),
        FaceMaskerConfig(),
        IosModelRuntime(
            FakeFactory {
                FakeBridge(OneFaceRunner())
            },
        ),
    )

    /**
     * A native buffer holding [argb] in [layout] (colour bytes as given: for opaque pixels the premultiplied and the
     * straight form are the same); padding gets [filler], skip bytes get 0 (a skip layout accepts only 0 or 255 there,
     * ADR 0011 decision 4a), alpha bytes of premultiplied layouts get the pixel's alpha.
     */
    private fun buffer(argb: IntArray, layout: IosPixelLayout, filler: Byte = 0x5A): CPointer<ByteVar> {
        val p = nativeHeap.allocArray<ByteVar>(bpr * h)
        for (i in 0 until bpr * h) p[i] = filler
        val rgba = layout == IosPixelLayout.RGBA_PREMULTIPLIED || layout == IosPixelLayout.RGBA_NONE_SKIP
        val pre = layout == IosPixelLayout.RGBA_PREMULTIPLIED || layout == IosPixelLayout.BGRA_PREMULTIPLIED
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = argb[y * w + x]
                val a = v ushr 24
                fun c(s: Int) = (v shr s) and 0xFF
                val o = y * bpr + x * 4
                p[o + if (rgba) 0 else 2] = c(16).toByte()
                p[o + 1] = c(8).toByte()
                p[o + if (rgba) 2 else 0] = c(0).toByte()
                p[o + 3] = if (pre) a.toByte() else 0
            }
        }
        return p
    }

    private fun bytes(p: CPointer<ByteVar>) = ByteArray(bpr * h) { p[it] }

    private fun wrap(p: CPointer<ByteVar>, layout: IosPixelLayout): MutablePixelImage =
        FaceBlurIos.wrapRgba(p.toLong(), w, h, bpr, layout)

    /** The buffer read back through the adapter (the library's own view). */
    private fun argbOf(p: CPointer<ByteVar>, layout: IosPixelLayout) =
        IntArray(w * h).also { wrap(p, layout).readPixels(0, 0, w, h, it) }

    private fun reference(argb: IntArray): IntArrayPixels =
        IntArrayPixels(w, h, argb.copyOf()).also { img -> masker().use { it.maskInPlace(img) } }

    @Test
    fun opaqueBuffersInEveryLayoutGetExactlyTheCommonEnginesMasks() {
        val src = gradientPixels(w, h)
        val ref = reference(src)
        assertTrue(!ref.data.contentEquals(src), "the reference has a mask")
        val results = ArrayList<IntArray>()
        for (layout in IosPixelLayout.entries) {
            val p = buffer(src, layout)
            try {
                val result = masker().use { it.maskInPlace(wrap(p, layout)) }
                assertEquals(1, result.masks.size, layout.name)
                val out = argbOf(p, layout)
                assertContentEquals(ref.data, out, layout.name)
                results += out
                val b = bytes(p)
                for (y in 0 until h) {
                    for (k in w * 4 until bpr) {
                        assertEquals(
                            0x5A,
                            b[y * bpr + k].toInt(),
                            "padding ${layout.name}",
                        )
                    }
                }
                if (layout == IosPixelLayout.RGBA_NONE_SKIP || layout == IosPixelLayout.BGRA_NONE_SKIP) {
                    // Written pixels (every changed one, and soft-edge pixels whose blend equals the original)
                    // get 0xFF in the ignored byte; pixels the engine never wrote keep the caller's byte.
                    var kept = 0
                    for (i in 0 until w * h) {
                        val v = b[(i / w) * bpr + (i % w) * 4 + 3].toInt() and 0xFF
                        if (ref.data[i] != src[i]) assertEquals(0xFF, v, "skip byte ${layout.name} at $i")
                        assertTrue(v == 0xFF || v == 0, "skip byte ${layout.name} at $i: $v")
                        if (v == 0) kept++
                    }
                    assertTrue(kept > w * h / 2, "most pixels are never written")
                }
            } finally {
                nativeHeap.free(p.rawValue)
            }
        }
        assertTrue(results.all { it.contentEquals(results[0]) }, "RGBA and BGRA give identical results")
    }

    @Test
    fun premultipliedBuffersWithAnyPixelThatIsNotOpaqueAreRefusedUntouched() {
        // ADR 0011 decision 4a (alpha rule of 2026-11-11): reject, never repair. One pixel at alpha 254, a fully
        // transparent buffer and a translucent buffer are refused before detection and before any write.
        val opaque = gradientPixels(w, h)
        val centre = (h / 2) * w + w / 2
        val onePixel = opaque.copyOf().also { it[centre] = (254 shl 24) or (it[centre] and 0x7F7F7F) }
        val lastPixel = opaque.copyOf().also { it[w * h - 1] = 254 shl 24 }
        val transparent = IntArray(w * h)
        val translucent = IntArray(w * h) { (128 shl 24) or ((opaque[it] shr 1) and 0x7F7F7F) }
        for (layout in listOf(IosPixelLayout.RGBA_PREMULTIPLIED, IosPixelLayout.BGRA_PREMULTIPLIED)) {
            for ((name, src) in listOf(
                "alpha 254 at the centre" to onePixel,
                "alpha 254 in the last pixel" to lastPixel,
                "fully transparent" to transparent,
                "translucent" to translucent,
            )) {
                val p = buffer(src, layout)
                try {
                    val before = bytes(p)
                    assertFailsWith<FaceMaskException.UnsupportedImage>("$name ${layout.name}") { wrap(p, layout) }
                    assertContentEquals(before, bytes(p), "buffer unchanged: $name ${layout.name}")
                } finally {
                    nativeHeap.free(p.rawValue)
                }
            }
        }
    }

    @Test
    fun invalidBufferDescriptionsAreRefusedWithTheParameterName() {
        val p = nativeHeap.allocArray<ByteVar>(64)
        try {
            val a = p.toLong()
            fun param(block: () -> Unit) = assertFailsWith<FaceMaskException.InvalidConfiguration> { block() }.parameter
            assertEquals("width", param { FaceBlurIos.wrapRgba(a, 0, 1, 4, IosPixelLayout.RGBA_PREMULTIPLIED) })
            assertEquals("height", param { FaceBlurIos.wrapRgba(a, 1, -1, 4, IosPixelLayout.RGBA_PREMULTIPLIED) })
            assertEquals("bytesPerRow", param { FaceBlurIos.wrapRgba(a, 4, 1, 15, IosPixelLayout.BGRA_NONE_SKIP) })
            assertEquals("address", param { FaceBlurIos.wrapRgba(0L, 4, 1, 16, IosPixelLayout.BGRA_NONE_SKIP) })
            assertEquals("address", param { FaceBlurIos.wrapRgba(-8L, 4, 1, 16, IosPixelLayout.BGRA_NONE_SKIP) })
            assertEquals(
                "address",
                param {
                    FaceBlurIos.wrapRgba(Long.MAX_VALUE - 8, 4, 2, 16, IosPixelLayout.RGBA_NONE_SKIP)
                },
            )
            val img = FaceBlurIos.wrapRgba(a, 4, 4, 16, IosPixelLayout.RGBA_NONE_SKIP)
            assertFailsWith<FaceMaskException.Internal> { img.readPixels(1, 0, 4, 1, IntArray(4)) }
            assertFailsWith<FaceMaskException.Internal> { img.writePixels(0, 3, 1, 2, IntArray(2)) }
            assertFailsWith<FaceMaskException.Internal> { img.readPixels(0, 0, 2, 2, IntArray(3)) }
            assertFailsWith<FaceMaskException.Internal> { img.readPixels(-1, 0, 1, 1, IntArray(1)) }
        } finally {
            nativeHeap.free(p.rawValue)
        }
    }

    @Test
    fun aReusedWrapperIsCheckedAgainOnEveryRead() {
        // ADR 0011 4a at read time: the buffer changes after wrapRgba (a reused wrapper); detection must not see black
        // where an alpha-ignoring viewer sees colour, and nothing may be written.
        for (layout in listOf(IosPixelLayout.RGBA_PREMULTIPLIED, IosPixelLayout.BGRA_PREMULTIPLIED)) {
            val p = buffer(gradientPixels(w, h), layout)
            try {
                val image = wrap(p, layout)
                val o = (h / 2) * bpr + (w / 2) * 4
                for (k in 0 until 3) p[o + k] = 1
                p[o + 3] = 0
                val before = bytes(p)
                masker().use { m ->
                    assertFailsWith<FaceMaskException.UnsupportedImage>("maskInPlace $layout") { m.maskInPlace(image) }
                    assertContentEquals(before, bytes(p), "buffer unchanged after maskInPlace: $layout")
                    assertFailsWith<FaceMaskException.UnsupportedImage>("detect $layout") { m.detect(image) }
                    assertContentEquals(before, bytes(p), "buffer unchanged after detect: $layout")
                }
            } finally {
                nativeHeap.free(p.rawValue)
            }
        }
    }

    @Test
    fun aRefusedWorkingAreaLeavesTheBufferUntouched() {
        val p = buffer(gradientPixels(w, h), IosPixelLayout.BGRA_PREMULTIPLIED)
        val probe =
            object : MemoryProbe {
                override val rule = MemoryRule.AVAILABLE_ON_DEVICE

                override fun figure() = 1L
            }
        try {
            val before = bytes(p)
            FaceBlurRuntime.installMemoryProbe(probe)
            masker().use { m ->
                assertFailsWith<FaceMaskException.NotEnoughMemory> {
                    m.maskInPlace(wrap(p, IosPixelLayout.BGRA_PREMULTIPLIED))
                }
            }
            assertContentEquals(before, bytes(p))
        } finally {
            FaceBlurRuntime.installMemoryProbe(null)
            nativeHeap.free(p.rawValue)
        }
    }

    @Test
    fun aCancelledCallLeavesTheBufferUntouched() {
        val p = buffer(gradientPixels(w, h), IosPixelLayout.RGBA_PREMULTIPLIED)
        try {
            val before = bytes(p)
            val cancelled = object : CancellationToken {
                override val isCancellationRequested = true
            }
            masker().use { m ->
                assertFailsWith<FaceMaskException.Cancelled> {
                    m.maskInPlace(wrap(p, IosPixelLayout.RGBA_PREMULTIPLIED), cancelled)
                }
            }
            assertContentEquals(before, bytes(p))
        } finally {
            nativeHeap.free(p.rawValue)
        }
    }

    /** Names and sizes of every file under the app's writable folders. */
    private fun snapshot(): Map<String, Long> {
        val fm = NSFileManager.defaultManager
        val roots = listOf(NSTemporaryDirectory()) +
            listOf(NSCachesDirectory, NSDocumentDirectory, NSLibraryDirectory).mapNotNull {
                NSSearchPathForDirectoriesInDomains(it, NSUserDomainMask, true).firstOrNull() as? String
            }
        val out = HashMap<String, Long>()
        for (root in roots.distinct()) {
            val e = fm.enumeratorAtPath(root) ?: continue
            while (true) {
                val rel = e.nextObject() as? String ?: break
                val size = (e.fileAttributes?.get("NSFileSize") as? Number)?.toLong() ?: -1L
                out["$root/$rel"] = size
            }
        }
        return out
    }

    @Test
    fun creationAndAnInPlaceCallWriteNoFile() {
        val before = snapshot()
        val p = buffer(gradientPixels(w, h), IosPixelLayout.BGRA_PREMULTIPLIED)
        try {
            masker().use { m ->
                m.detect(wrap(p, IosPixelLayout.BGRA_PREMULTIPLIED))
                m.maskInPlace(wrap(p, IosPixelLayout.BGRA_PREMULTIPLIED))
            }
        } finally {
            nativeHeap.free(p.rawValue)
        }
        assertEquals(before, snapshot(), "no file created, removed or resized")
    }

    @Test
    fun aModelFromMemoryIsCopiedOnceAndLoadsLikeBytes() {
        val model = Onnx.yunetModel(640, 640)
        val p = nativeHeap.allocArray<ByteVar>(model.size)
        try {
            for (i in model.indices) p[i] = model[i]
            val source = FaceBlurIos.modelSourceFromMemory(p.toLong(), model.size.toLong())
            for (i in model.indices) p[i] = 0 // the caller's memory changes after the call
            val profile = yunetProfile().pinnedTo(model)
            val loaded = DetectorModel.load(source, profile)
            assertEquals(
                DetectorModel.load(io.github.shishark.faceblur.ModelSource.fromBytes(model), profile).info.sha256,
                loaded.info.sha256,
            )
            fun param(block: () -> Unit) = assertFailsWith<FaceMaskException.InvalidConfiguration> { block() }.parameter
            assertEquals("address", param { FaceBlurIos.modelSourceFromMemory(0L, 10L) })
            assertEquals("byteCount", param { FaceBlurIos.modelSourceFromMemory(p.toLong(), 0L) })
            assertEquals("byteCount", param { FaceBlurIos.modelSourceFromMemory(p.toLong(), -1L) })
            assertEquals("byteCount", param { FaceBlurIos.modelSourceFromMemory(p.toLong(), 2_147_483_640L) })
        } finally {
            nativeHeap.free(p.rawValue)
        }
    }

    @Test
    fun theIosDefaultsNeedNoSetup() {
        assertSame(IosMemoryProbe, FaceBlurRuntime.memoryProbe())
        assertSame(IosModelFileReader, FaceBlurRuntime.modelFiles())
        assertTrue(IosMemoryProbe.isSimulator(), "these tests run in the simulator")
        assertEquals(MemoryRule.AVAILABLE_ON_SIMULATOR, IosMemoryProbe.rule)
        assertTrue(IosMemoryProbe.figure() >= 0L)
        IosMemoryProbe.collect()
        assertEquals("1.20.0", FaceBlurIos.REQUIRED_RUNTIME_VERSION)
        assertTrue(FaceBlurIos.libraryVersion.isNotEmpty())
    }
}
