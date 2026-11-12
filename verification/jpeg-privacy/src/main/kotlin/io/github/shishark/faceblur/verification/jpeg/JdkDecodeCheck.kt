// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO
import javax.imageio.event.IIOReadWarningListener

/**
 * Decodes the image with the JDK's JPEG decoder (raw raster, no colour conversion) and collects its warnings. The
 * marker walk cannot see data hidden between the last block and the EOI marker ; the decoder
 * notices it ("extraneous bytes before marker"). Any warning or failure is a finding.
 */
internal object JdkDecodeCheck {
    class Result(val warnings: List<String>, val failure: String?)

    /** [subsampling] 1 decodes every pixel; n > 1 keeps every nth pixel of every nth row (the decoder still reads all of the data). */
    fun run(data: ByteArray, subsampling: Int = 1): Result {
        val warnings = mutableListOf<String>()
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(data)).use { stream ->
                val readers = ImageIO.getImageReadersByFormatName("jpeg")
                if (!readers.hasNext()) return Result(warnings, "no JPEG decoder in this JDK")
                val reader = readers.next()
                try {
                    reader.input = stream
                    reader.addIIOReadWarningListener(IIOReadWarningListener { _, message -> warnings.add(message) })
                    val param = reader.defaultReadParam
                    if (subsampling > 1) param.setSourceSubsampling(subsampling, subsampling, 0, 0)
                    reader.readRaster(0, param)
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: IOException) {
            return Result(warnings, e::class.simpleName ?: "IOException")
        } catch (e: RuntimeException) {
            return Result(warnings, e::class.simpleName ?: "RuntimeException")
        }
        return Result(warnings, null)
    }
}
