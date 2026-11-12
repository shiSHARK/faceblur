// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.sample

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import io.github.shishark.faceblur.FaceMaskException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pipeline steps with a real decoder and encoder (Robolectric native graphics) and a stand-in for the one library
 * call: it records what it was given and covers a known area. The library's own masking is covered by its tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapPipelineTest {
    @get:Rule
    val folder = TemporaryFolder()

    /**
     * The photo as a byte buffer: Robolectric's native decoder does not open file or content sources (the platform
     * ImageDecoder does that on a device); the decoding of the bytes is the real one.
     */
    private fun sourceOf(uri: Uri): ImageDecoder.Source =
        ImageDecoder.createSource(ByteBuffer.wrap(File(uri.path!!).readBytes()))

    /** Mid-grey JPEG, [width] x [height] as stored, with EXIF orientation [orientation]. */
    private fun photo(width: Int, height: Int, orientation: Int = 1): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.rgb(128, 128, 128))
        val file = File(folder.root, "p$width-$height-$orientation.jpg")
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return Uri.fromFile(file)
    }

    private class Seen(var width: Int = 0, var height: Int = 0, var bitmap: Bitmap? = null, var calls: Int = 0)

    /** Covers the centre fifth of the bitmap in black, like a mask would, and reports one face and one mask. */
    private fun pipeline(seen: Seen = Seen(), onMask: (Bitmap) -> Unit = {}) =
        BitmapPipeline("FaceBlur demo", ::sourceOf) { bitmap, _ ->
            seen.calls++
            seen.width = bitmap.width
            seen.height = bitmap.height
            seen.bitmap = bitmap
            onMask(bitmap)
            val c = Canvas(bitmap)
            c.drawRect(
                bitmap.width * 0.4f,
                bitmap.height * 0.4f,
                bitmap.width * 0.6f,
                bitmap.height * 0.6f,
                Paint().apply { color = Color.BLACK },
            )
            MaskCounts(1, 1)
        }

    private fun Bitmap.bright(x: Int, y: Int): Int = getPixel(x, y).let {
        (
            Color.red(it) + Color.green(it) +
                Color.blue(it)
            ) /
            3
    }

    @Test
    fun `the photo is decoded upright, masked, watermarked and encoded exactly once`() {
        val seen = Seen()
        val p = pipeline(seen)
        val outcome = p.run(photo(400, 300, orientation = 6), OutputOption.SAME_SIZE) { false }
        // Orientation 6 stored as 400 x 300 is upright 300 x 400: the mask step already saw it upright.
        assertEquals(300, seen.width)
        assertEquals(400, seen.height)
        assertEquals(1, seen.calls)
        assertEquals(1, p.encodes)
        assertEquals(1, outcome.faces)
        assertEquals(1, outcome.masks)
        val picture = outcome.picture as Bitmap
        assertEquals(300, picture.width)
        assertEquals(400, picture.height)
        assertTrue(picture.bright(150, 200) < 40, "the covered area is black in the encoded result")
        assertTrue(picture.bright(5, 5) in 100..160, "the rest of the photo is kept")
        var lightest = 0
        for (y in 340 until 400) for (x in 0 until 150) lightest = maxOf(lightest, picture.bright(x, y))
        assertTrue(lightest > 200, "the watermark text is drawn at the bottom left")
    }

    @Test
    fun `the library sees the photo before it is scaled down and the result is scaled after`() {
        val seen = Seen()
        val outcome = pipeline(seen).run(photo(1_500, 1_000), OutputOption.LONG_SIDE_1024) { false }
        assertEquals(1_500, seen.width)
        assertEquals(1_000, seen.height)
        val picture = outcome.picture as Bitmap
        assertEquals(1_024, picture.width)
        assertEquals(683, picture.height)
    }

    @Test
    fun `a wide photo is decoded at 3500 px long side at most`() {
        val seen = Seen()
        pipeline(seen).run(photo(4_000, 500), OutputOption.SAME_SIZE) { false }
        assertEquals(3_500, seen.width)
        assertEquals(438, seen.height)
    }

    @Test
    fun `the bitmap given to the library is software, mutable and ARGB 8888`() {
        var config: Bitmap.Config? = null
        var mutable = false
        pipeline {
            config = it.config
            mutable = it.isMutable
        }.run(photo(200, 100), OutputOption.SAME_SIZE) { false }
        assertEquals(Bitmap.Config.ARGB_8888, config)
        assertTrue(mutable)
    }

    @Test
    fun `cancelling before masking stops the run and recycles the working bitmap`() {
        val seen = Seen()
        val failure = assertFailsWith<DemoFailure> {
            pipeline(seen).run(photo(200, 100), OutputOption.SAME_SIZE) { true }
        }
        assertEquals(FailureKind.CANCELLED, failure.kind)
        assertEquals(0, seen.calls)
    }

    @Test
    fun `a library error discards the unmasked bitmap and maps to the screen's failure`() {
        var working: Bitmap? = null
        val p = BitmapPipeline("w", ::sourceOf) { bitmap, _ ->
            working = bitmap
            throw libraryException(FaceMaskException.NotEnoughMemory::class.java)
        }
        val failure = assertFailsWith<DemoFailure> { p.run(photo(200, 100), OutputOption.SAME_SIZE) { false } }
        assertEquals(FailureKind.NOT_ENOUGH_MEMORY, failure.kind)
        assertTrue(assertNotNull(working).isRecycled, "the bitmap that was not masked is not kept")
        assertEquals(0, p.encodes)
    }

    @Test
    fun `an unreadable source and an unsupported image give their own failures`() {
        val missing = Uri.fromFile(File(folder.root, "none.jpg"))
        assertEquals(
            FailureKind.IO,
            assertFailsWith<DemoFailure> {
                pipeline().run(missing, OutputOption.SAME_SIZE) { false }
            }.kind,
        )
        val junk = File(folder.root, "junk.jpg").apply { writeBytes(ByteArray(64) { it.toByte() }) }
        assertEquals(
            FailureKind.UNSUPPORTED_IMAGE,
            assertFailsWith<DemoFailure> {
                pipeline().run(Uri.fromFile(junk), OutputOption.SAME_SIZE) { false }
            }.kind,
        )
    }

    @Test
    fun `nothing is left on disk by a run`() {
        val uri = photo(200, 100)
        val before = folder.root.list().orEmpty().toSet()
        pipeline().run(uri, OutputOption.LONG_SIDE_1024) { false }
        assertEquals(before, folder.root.list().orEmpty().toSet())
        assertFalse(RuntimeEnvironment.getApplication().cacheDir.walkTopDown().any { it.isFile })
        assertFalse(RuntimeEnvironment.getApplication().filesDir.walkTopDown().any { it.isFile })
    }
}
