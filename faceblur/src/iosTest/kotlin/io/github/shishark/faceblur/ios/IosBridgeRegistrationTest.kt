// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMaskException
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.internal.model.Sha256
import io.github.shishark.faceblur.testmodel.HostileModels
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSTemporaryDirectory
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.open
import platform.posix.unlink
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A glue that only counts: any call proves that a model reached the bridge. */
private class CountingBridge : OnnxBridge {
    override fun open(
        modelAddress: Long,
        modelByteCount: Long,
        threads: Int,
        inputName: String,
        inputShape: LongArray,
        outputNames: List<String>,
    ): Int {
        CountingFactory.opens++
        return 1
    }

    override fun run(inputAddress: Long, inputByteCount: Long, outputTableAddress: Long): Int = 4

    override fun runtimeVersion(): String = FaceBlurIos.REQUIRED_RUNTIME_VERSION

    override fun executionProvider(): String = "CPU"

    override fun close() = Unit
}

private object CountingFactory : OnnxBridgeFactory {
    var creates = 0
    var opens = 0

    override fun create(): OnnxBridge {
        creates++
        return CountingBridge()
    }
}

/**
 * ST-050 AC5 intent: on the iOS target, models with external data are refused by the library's pre-parse in
 * `DetectorModel.load`, from bytes and from a file, before any bridge exists; the registered glue is never called.
 */
@OptIn(ExperimentalForeignApi::class)
class IosBridgeRegistrationTest {
    private fun profileFor(bytes: ByteArray) =
        DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, expectedSha256 = Sha256.hex(bytes))

    private fun writeTemp(bytes: ByteArray): String {
        val path = NSTemporaryDirectory() + "faceblur-test-external.onnx"
        val fd = open(path, O_CREAT or O_WRONLY or O_TRUNC, 0x180)
        check(fd >= 0)
        bytes.usePinned { check(write(fd, it.addressOf(0), bytes.size.convert()).toInt() == bytes.size) }
        close(fd)
        return path
    }

    @Test
    fun externalDataIsRefusedBeforeTheBridge() {
        assertTrue(FaceBlurIos.registerOnnxRuntime(CountingFactory))
        assertTrue(FaceBlurIos.registerOnnxRuntime(CountingFactory), "same factory again")
        assertEquals(false, FaceBlurIos.registerOnnxRuntime { CountingBridge() }, "a different factory is refused")
        val cases = HostileModels.externalAtEveryPosition()
        assertTrue(cases.size >= 10, "cases: ${cases.size}")
        for ((label, bytes) in cases) {
            val fromBytes =
                assertFailsWith<FaceMaskException.ModelRejected>(label) {
                    DetectorModel.load(ModelSource.fromBytes(bytes), profileFor(bytes))
                }
            assertEquals(listOf("EXTERNAL_DATA"), fromBytes.findings, label)
            val path = writeTemp(bytes)
            try {
                val fromFile =
                    assertFailsWith<FaceMaskException.ModelRejected>(label) {
                        DetectorModel.load(ModelSource.fromFile(path), profileFor(bytes))
                    }
                assertEquals(listOf("EXTERNAL_DATA"), fromFile.findings, label)
            } finally {
                unlink(path)
            }
        }
        assertEquals(0, CountingFactory.creates, "bridge created")
        assertEquals(0, CountingFactory.opens, "bridge opened")
    }
}
