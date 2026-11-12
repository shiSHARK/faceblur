// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import docs.snippets.android.JavaAsync
import io.github.shishark.faceblur.AndroidOutputTargets
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.FileResult
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.RunnerRuntime
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.maskFile
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Java example of the Android guide (`docs/guides/snippets/android/JavaAsync.java`, compiled from that very file
 * as a Java test source by `compileGuideJavaSnippets`): cancelling the CompletableFuture must stop the running call
 * through the FaceBlur CancellationToken, because `CompletableFuture.cancel()` alone does not stop a running task.
 *
 * The model session is a fake that holds the call inside the model run until the test lets it go, so the future is
 * cancelled while the call is running. The control test runs the identical sequence without the cancel and must write
 * the target: the only difference between the two is the cancellation, so a missing output after the cancel comes from
 * the token and not from the setup. Robolectric with native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class JavaAsyncCancellationTest {
    /** Serves the files of a test directory by the last path segment of the content URI. */
    class SnippetProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = File(root, uri.lastPathSegment ?: throw FileNotFoundException())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
        }

        override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null

        override fun getType(uri: Uri): String = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0

        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

        companion object {
            lateinit var root: File
        }
    }

    /**
     * Sees one face like [OneFaceRunner]. Once [armed] (after the masker has passed its start-up contract run) it holds
     * every model run until [release] (bounded wait, so a bug cannot hang).
     */
    private class HoldingRunner : TensorRunner {
        private val face = OneFaceRunner()

        @Volatile
        var armed = false
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        override val inputWidth = face.inputWidth
        override val inputHeight = face.inputHeight

        override fun initialize() = face.initialize()

        override fun close() = face.close()

        override fun run(input: FloatArray): Map<String, FloatArray> {
            if (armed) {
                started.countDown()
                check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the test never released the model run" }
            }
            return face.run(input)
        }
    }

    private companion object {
        const val WAIT_SECONDS = 30L
        const val AUTHORITY = "io.github.shishark.faceblur.test.snippetprovider"
    }

    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()
    private lateinit var dir: File
    private lateinit var runner: HoldingRunner
    private lateinit var masker: FaceMasker
    private lateinit var executor: ExecutorService

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("faceblur-snippet").toFile()
        SnippetProvider.root = dir
        Robolectric.setupContentProvider(SnippetProvider::class.java, AUTHORITY)
        // Robolectric's decoder reads no provider descriptor: the content source is decoded from the provider's bytes
        // (read through the same ContentResolver); everything else is the production codec and writer.
        FaceBlurRuntime.installImageCodec(
            AndroidImageCodec.using(HostFiles) { resolver, uri ->
                ImageDecoder.createSource(ByteBuffer.wrap(resolver.openInputStream(uri)!!.use { it.readBytes() }))
            },
        )
        File(dir, "source.jpg").writeBytes(TestImages.jpeg(320, 240))
        runner = HoldingRunner()
        masker = FaceMasker.forTest(testYuNetModel(), FaceMaskerConfig(), RunnerRuntime(runner))
        runner.armed = true
        executor = Executors.newSingleThreadExecutor()
    }

    @After
    fun tearDown() {
        FaceBlurRuntime.installImageCodec(null)
        runner.release.countDown()
        executor.shutdownNow()
        masker.close()
        dir.deleteRecursively()
    }

    private fun uri(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")

    private fun emptyTarget(name: String): File = File(dir, name).apply { writeBytes(ByteArray(0)) }

    private fun awaitWorkerEnd() {
        executor.shutdown()
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "the worker did not finish")
    }

    /** Waits until the call runs inside the model; a call that ended early is reported with its exception. */
    private fun awaitInModel(future: CompletableFuture<FileResult>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (!runner.started.await(50, TimeUnit.MILLISECONDS)) {
            if (future.isDone) {
                val failure = runCatching { future.get() }.exceptionOrNull()
                throw AssertionError("the call ended before the model run: $failure")
            }
            assertTrue(System.nanoTime() < deadline, "the call never reached the model")
        }
    }

    @Test
    fun cancellingTheFutureStopsTheRunningCallAndNothingIsWritten() {
        val target = emptyTarget("target.jpg")
        val future = JavaAsync.maskAsync(masker, context, uri("source.jpg"), uri("target.jpg"), executor)
        awaitInModel(future)

        assertTrue(future.cancel(true))
        assertTrue(future.isCancelled)
        runner.release.countDown()
        awaitWorkerEnd()

        assertFailsWith<CancellationException> { future.get() }
        assertEquals(0L, target.length(), "a cancelled call must not write the target")
        // The call ended and released the masker: it takes the next call (a second call during the first would throw).
        val second = emptyTarget("second.jpg")
        masker.maskFile(context, uri("source.jpg"), AndroidOutputTargets.newFile(uri("second.jpg")))
        assertTrue(second.length() > 0L)
    }

    @Test
    fun withoutTheCancelTheSameCallWritesTheTarget() {
        val target = emptyTarget("target.jpg")
        val future = JavaAsync.maskAsync(masker, context, uri("source.jpg"), uri("target.jpg"), executor)
        awaitInModel(future)

        runner.release.countDown()
        val result = future.get(WAIT_SECONDS, TimeUnit.SECONDS)
        awaitWorkerEnd()

        assertEquals(1, result.faces.size)
        assertTrue(target.length() > 0L, "the control call must write the target")
    }
}
