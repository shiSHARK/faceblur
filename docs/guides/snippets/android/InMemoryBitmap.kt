// SPDX-License-Identifier: Apache-2.0
package docs.snippets.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.maskInPlace
import java.io.OutputStream

/** Decodes into a mutable, software, sRGB ARGB_8888 bitmap: the kind the in-memory masking calls accept. */
fun decodeForMasking(context: Context, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = true
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        // Very large photos: decode smaller, but keep the long side at 3,500 px or more.
        val longSide = maxOf(info.size.width, info.size.height)
        if (longSide >= 2 * 3_500) decoder.setTargetSampleSize(longSide / 3_500)
    }

/** Masks [bitmap] in place, then encodes it. Encode only after maskInPlace has returned; false: discard [output]. */
fun maskAndEncode(masker: FaceMasker, bitmap: Bitmap, output: OutputStream): Boolean {
    masker.maskInPlace(bitmap) // throws FaceMaskException; on any exception do not encode, show or share the bitmap
    return bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
}
