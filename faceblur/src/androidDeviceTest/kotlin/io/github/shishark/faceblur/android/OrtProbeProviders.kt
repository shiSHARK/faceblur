// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Process
import io.github.shishark.faceblur.DetectionFormat
import io.github.shishark.faceblur.DetectorModel
import io.github.shishark.faceblur.DetectorProfile
import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.Mask
import io.github.shishark.faceblur.MetadataPolicy
import io.github.shishark.faceblur.ModelSource
import io.github.shishark.faceblur.OutputTarget
import io.github.shishark.faceblur.PlanMasker
import io.github.shishark.faceblur.filemode.OutputValidator
import io.github.shishark.faceblur.internal.runtime.FaceBlurRuntime
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.maskFile
import io.github.shishark.faceblur.planFile
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

/**
 * The harness of [PlanMaskerDeviceTest] (ADR 0015 decision 6): providers of the device-test APK that run in their OWN
 * processes (`android:process` in the device-test manifest), so the native libraries other device tests load in the
 * instrumentation process cannot be seen. Each installs a runtime that counts its sessions and delegates to the
 * production Android runtime, and reports, before and after its work, whether ONNX Runtime's native library is in the
 * process's memory maps (ONNX Runtime's Java classes load it in their static initialiser, so a process that never
 * initialised ONNX Runtime has none). The libraries are loaded straight from the APK, so the maps name the APK, not the
 * library: the probe matches the APK path and the offset of the library's entry (MEASURED on the test phone: a name
 * match alone found nothing after a `FaceMasker` was created). `ClassLoader.findLoadedClass` is NOT used: on ART it looks the name up in the
 * APK's dex files and loads the class (MEASURED on the test phone: every name reported loaded in a fresh process).
 * Not exported; called only by the test app itself through `ContentResolver.call`.
 */
abstract class OrtProbeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    /** The work of this process; the probe runs before and after it. */
    protected abstract fun work(results: Bundle)

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val results = Bundle()
        results.putInt(PID, Process.myPid())
        val sessions = AtomicInteger()
        FaceBlurRuntime.install(
            ModelRuntime { model, threads ->
                sessions.incrementAndGet()
                AndroidModelRuntime.open(model, threads)
            },
        )
        results.putBoolean(BEFORE + NATIVE_LIBRARY, nativeLibraryMapped())
        try {
            work(results)
            results.putString(ERROR, "")
        } catch (e: Throwable) {
            results.putString(ERROR, e.javaClass.name)
        } finally {
            FaceBlurRuntime.uninstall()
        }
        results.putBoolean(AFTER + NATIVE_LIBRARY, nativeLibraryMapped())
        results.putInt(SESSIONS, sessions.get())
        return results
    }

    /**
     * True when a memory mapping of this process is an ONNX Runtime library: either by its file name (libraries
     * extracted at install) or, for libraries loaded straight from the APK (the default packaging, where the maps show
     * only the APK's path), by the APK path and the file offset of the library's stored entry in the APK.
     */
    private fun nativeLibraryMapped(): Boolean {
        val maps = File("/proc/self/maps").readLines()
        if (maps.any { it.contains(NATIVE_LIBRARY) }) return true
        val apk = context!!.applicationInfo.sourceDir
        val offsets = storedEntryOffsets(File(apk)) { it.startsWith("lib/") && it.contains("/$NATIVE_LIBRARY") }
        check(offsets.isNotEmpty()) { "the APK holds no ONNX Runtime library entry" }
        return maps.any { line ->
            val f = line.trim().split(Regex("\\s+"))
            f.size >= 6 && f[5] == apk && f[2].toLongOrNull(16) in offsets
        }
    }

    /** The file offsets of the data of the APK's stored (uncompressed) entries whose names match [wanted]. */
    private fun storedEntryOffsets(apk: File, wanted: (String) -> Boolean): Set<Long> =
        RandomAccessFile(apk, "r").use { f ->
            val tail = minOf(f.length(), 65_557L)
            val buf = ByteArray(tail.toInt())
            f.seek(f.length() - tail)
            f.readFully(buf)
            val end = (buf.size - 22 downTo 0).first { le32(buf, it) == 0x06054b50L }
            var at = le32(buf, end + 16)
            val entries = le16(buf, end + 10)
            val out = HashSet<Long>()
            repeat(entries) {
                val h = ByteArray(46)
                f.seek(at)
                f.readFully(h)
                check(le32(h, 0) == 0x02014b50L) { "not a central directory entry" }
                val nameLength = le16(h, 28)
                val name = ByteArray(nameLength).also { f.readFully(it) }.decodeToString()
                if (le16(h, 10) == 0 && wanted(name)) {
                    val local = le32(h, 42)
                    val l = ByteArray(30)
                    f.seek(local)
                    f.readFully(l)
                    out += local + 30 + le16(l, 26) + le16(l, 28)
                }
                at += 46 + nameLength + le16(h, 30) + le16(h, 32)
            }
            out
        }

    private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Long = le16(b, at).toLong() or (le16(b, at + 2).toLong() shl 16)

    protected val dir: File get() = File(context!!.filesDir, "faceblur-scratch/${javaClass.simpleName}")

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0

    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    companion object {
        const val PID = "pid"
        const val ERROR = "error"
        const val BEFORE = "before:"
        const val AFTER = "after:"

        /** Sessions opened through the runtime slot during the work. */
        const val SESSIONS = "sessions"

        /** The ONNX Runtime libraries of onnxruntime-android (`libonnxruntime.so`, `libonnxruntime4j_jni.so`). */
        const val NATIVE_LIBRARY = "libonnxruntime"

        fun uri(authority: String): Uri = Uri.parse("content://$authority")
    }
}

/**
 * Runs the masks-without-a-model route (path form and the `Context` + `Uri` forms) on a synthetic picture, with the
 * platform defaults and no `FaceMasker`, and checks the written file. Results: [WROTE] (bytes of the masked file) and
 * [MASK_CORE] (the decoded colour at the centre of the mask, ARGB).
 */
class PlanMaskerProcessProvider : OrtProbeProvider() {
    override fun work(results: Bundle) {
        val d = dir.apply {
            deleteRecursively()
            mkdirs()
        }
        val picture = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFFFFFF.toInt()) }
        val input = File(d, "in.jpg")
        input.outputStream().use { check(picture.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        val planMasker = PlanMasker()
        val pathPlan = planMasker.planFile(input.path)
        val plan = planMasker.planFile(context!!, Uri.fromFile(input))
        check(plan == pathPlan && plan.width == 320 && plan.height == 240 && plan.masks.isEmpty())
        val masked = plan.withMasks(listOf(Mask.manual(100f, 60f, 220f, 180f, planMasker.masking)))
        val out = File(d, "out.jpg")
        val result = planMasker.maskFile(context!!, Uri.fromFile(input), OutputTarget.NewFile(out.path), masked)
        check(result.faces.isEmpty() && result.masks == masked.masks)
        val bytes = out.readBytes()
        check(OutputValidator.findings(bytes, MetadataPolicy.StripAll).isEmpty())
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        results.putInt(WROTE, bytes.size)
        results.putInt(MASK_CORE, decoded.getPixel(160, 120))
        results.putInt(MASK_COLOR, planMasker.masking.color)
        d.deleteRecursively()
    }

    companion object {
        const val AUTHORITY = "io.github.shishark.faceblur.test.planmasker"
        const val WROTE = "wrote"
        const val MASK_CORE = "maskCore"
        const val MASK_COLOR = "maskColor"
    }
}

/**
 * Positive control: creates a `FaceMasker` from the model fixture, so the same probe must count one session and see
 * ONNX Runtime's native library.
 */
class OrtControlProcessProvider : OrtProbeProvider() {
    override fun work(results: Bundle) {
        val model = File(context!!.filesDir, "faceblur-fixtures/${DeviceFixtures.YUNET_FILE}")
        val profile =
            DetectorProfile(DetectionFormat.YuNetLayout(), 640, 640, 0.5f, expectedSha256 = DeviceFixtures.YUNET_SHA256)
        FaceMasker(DetectorModel.load(ModelSource.fromFile(model.path), profile)).close()
    }

    companion object {
        const val AUTHORITY = "io.github.shishark.faceblur.test.ortcontrol"
    }
}
