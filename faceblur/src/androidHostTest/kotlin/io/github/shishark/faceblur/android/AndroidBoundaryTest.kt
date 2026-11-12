// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.android

import io.github.shishark.faceblur.PlanMasker
import io.github.shishark.faceblur.PlatformTargetValue
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.FileSource
import io.github.shishark.faceblur.filemode.ImageCodec
import io.github.shishark.faceblur.filemode.ImageFingerprint
import io.github.shishark.faceblur.filemode.OpenedImage
import io.github.shishark.faceblur.filemode.PlatformCallValue
import io.github.shishark.faceblur.filemode.PlatformSourceValue
import io.github.shishark.faceblur.filemode.StreamReader
import io.github.shishark.faceblur.filemode.StreamSink
import io.github.shishark.faceblur.filemode.TargetWriter
import io.github.shishark.faceblur.filemode.WorkingImage
import io.github.shishark.faceblur.internal.runtime.ModelDigest
import io.github.shishark.faceblur.internal.runtime.ModelFileReader
import io.github.shishark.faceblur.internal.runtime.ModelRuntime
import io.github.shishark.faceblur.internal.runtime.ModelSession
import io.github.shishark.faceblur.internal.runtime.PlatformDefaults
import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.StageAllocator
import io.github.shishark.faceblur.mask.StageObserver
import io.github.shishark.faceblur.mask.StageSeams
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Android variant of the JVM boundary scan: Java code must not reach the Android binding's seams. Scans every
 * class of the library's Android main output (the classes the AAR ships, not the tests), loaded without initialising
 * it. A plain JUnit test: reflection only, no Android runtime needed.
 */
class AndroidBoundaryTest {
    /**
     * Parameter types through which Java code could supply model bytes or a buffer for the runtime, a runtime, a
     * session, a reader, an environment or a function that decides what the runtime receives or when a pass stops.
     */
    private val dangerous: Set<Class<*>> =
        setOf(
            Function0::class.java,
            Function1::class.java,
            Function2::class.java,
            ByteBuffer::class.java,
            ModelRuntime::class.java,
            ModelSession::class.java,
            ModelFileReader::class.java,
            DescriptorCalls::class.java,
            FileCalls::class.java,
            ProcessEnvironment::class.java,
            MemoryProbe::class.java,
        )

    /** Return types through which Java code could obtain model bytes or a buffer the runtime reads. */
    private val dangerousReturns: Set<Class<*>> = setOf(ByteArray::class.java, ByteBuffer::class.java)

    /** The seam types of the JVM scan (ADR 0015): no Java-callable member may take, return or expose one. */
    private val seamTypes: Set<Class<*>> =
        setOf(
            PlatformDefaults::class.java,
            PlatformTargetValue::class.java,
            PlatformSourceValue::class.java,
            PlatformCallValue::class.java,
            CallContext::class.java,
            FileSource::class.java,
            FileSource.Path::class.java,
            FileSource.Bytes::class.java,
            FileSource.Platform::class.java,
            ImageFingerprint::class.java,
            ImageCodec::class.java,
            OpenedImage::class.java,
            WorkingImage::class.java,
            TargetWriter::class.java,
            StreamReader::class.java,
            StreamSink::class.java,
            MemoryProbe::class.java,
            ModelDigest::class.java,
            ModelRuntime::class.java,
            ModelFileReader::class.java,
            StageSeams::class.java,
            StageAllocator::class.java,
            StageObserver::class.java,
        )

    private fun javaCallable(c: Class<*>): List<Executable> =
        (c.declaredConstructors.toList<Executable>() + c.declaredMethods)
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    private fun name(c: Class<*>, e: Executable) =
        "${c.name.substringAfterLast('.')}.${if (e is Constructor<*>) "<init>" else e.name}"

    /** Java-callable members of [classes] that take a [dangerous] type or return a [dangerousReturns] type. */
    private fun dangerousMembers(classes: List<Class<*>>): List<String> = classes.flatMap { c ->
        javaCallable(c)
            .filter { m ->
                m.parameterTypes.any { it in dangerous } ||
                    (m is Method && m.returnType in dangerousReturns)
            }
            .filterNot { implementsAnInterface(c, it) }
            .map { name(c, it) }
    }

    /**
     * True when [e] implements a method of one of [c]'s interfaces: Java reaches it through the interface whatever its
     * own flag, so hiding it would protect nothing (as in the JVM scan, Java may implement or call a seam interface;
     * what matters is that no Java-callable member installs or hands out an implementation).
     */
    private fun implementsAnInterface(c: Class<*>, e: Executable): Boolean = e is Method &&
        allInterfaces(c).any { i ->
            i.methods.any {
                it.name == e.name &&
                    it.parameterTypes.contentEquals(e.parameterTypes)
            }
        }

    private fun allInterfaces(c: Class<*>): Set<Class<*>> = (
        c.interfaces.flatMap {
            allInterfaces(it) + it
        } + (c.superclass?.let { allInterfaces(it) } ?: emptySet())
        ).toSet()

    /** Java-callable members and fields of [classes] that take, return or are of a [seamTypes] type. */
    private fun seamUses(classes: List<Class<*>>): List<String> = classes.flatMap { c ->
        val members = javaCallable(c)
            .filter { m -> m.parameterTypes.any { it in seamTypes } || (m is Method && m.returnType in seamTypes) }
            .map { name(c, it) }
        val fields = c.declaredFields
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && it.type in seamTypes }
            .map { "${c.name.substringAfterLast('.')}.${it.name}" }
        members + fields
    }

    /** Every class of the library's Android main output (a classes jar or a directory). */
    private fun androidMainClasses(): List<Class<*>> {
        val location = File(AndroidModelRuntime::class.java.protectionDomain.codeSource.location.toURI())
        val names =
            if (location.isDirectory) {
                Files.walk(location.toPath()).use { paths ->
                    paths.map { location.toPath().relativize(it).toString() }.filter { it.endsWith(".class") }.toList()
                }
            } else {
                ZipFile(location).use { zip -> zip.entries().toList().map { it.name }.filter { it.endsWith(".class") } }
            }
        val loader = AndroidModelRuntime::class.java.classLoader
        return names.map { it.removeSuffix(".class").replace('/', '.').replace('\\', '.') }
            .filter { it.startsWith("io.github.shishark.faceblur.") }
            .sorted()
            .map { Class.forName(it, false, loader) }
    }

    @Test
    fun javaCannotReachTheAndroidBindingSeams() {
        val all = androidMainClasses()
        val android = all.filter { it.name.startsWith("io.github.shishark.faceblur.android.") }
        assertTrue(
            listOf(
                AndroidModelRuntime::class.java,
                OrtSessions::class.java,
                AndroidImageCodec::class.java,
                AndroidTargetWriter::class.java,
                ContentUriWriter::class.java,
                PathWriteRules::class.java,
                PathSourceRules::class.java,
                OsDescriptors::class.java,
                SourceIdentities::class.java,
                BitmapRules::class.java,
                BitmapReadPixels::class.java,
                InPlaceBitmap::class.java,
                GainMaps::class.java,
            ).all { it in android },
            "the scan sees the binding, the file-mode and the in-memory parts",
        )
        assertEquals(emptyList(), dangerousMembers(android).sorted(), "Java-callable Android members taking a seam")
        // The exemption covers exactly the model-file reader's interface method (it reads a file Java could read
        // itself; installing a reader is synthetic).
        val exempt = android.flatMap { c ->
            javaCallable(c).filter { m ->
                (m.parameterTypes.any { it in dangerous } || (m is Method && m.returnType in dangerousReturns)) &&
                    implementsAnInterface(c, m)
            }.map { name(c, it) }
        }
        assertEquals(listOf("AndroidModelFileReader.read"), exempt.sorted())
        // The masks-without-a-model route and its Android forms are in the scanned output (ADR 0015 decision 6).
        assertTrue(
            PlanMasker::class.java in all && Class.forName("io.github.shishark.faceblur.FaceMaskerAndroid") in all,
            "the scan sees PlanMasker and the Android file class",
        )
        val scanned = all.filter { c -> seamTypes.none { it == c || c.enclosingClass == it } }
        assertTrue(scanned.size > android.size, "the seam scan covers the whole Android main output")
        assertEquals(emptyList(), seamUses(scanned).sorted(), "Java-callable members using a file-mode or binding seam")
        // The platform defaults entry on Android is synthetic as well.
        val defaults = Class.forName(
            "io.github.shishark.faceblur.internal.runtime.PlatformDefaults_androidKt",
            false,
            javaClass.classLoader,
        )
        assertTrue(
            defaults.declaredMethods.single {
                it.name == "platformDefaults"
            }.isSynthetic,
            "platformDefaults() is synthetic",
        )
        // Negative controls: the same filters report planted members.
        assertEquals(
            listOf(
                "AndroidBoundaryTest\$Planted.<init>",
                "AndroidBoundaryTest\$Planted.buffer",
                "AndroidBoundaryTest\$Planted.bytes",
                "AndroidBoundaryTest\$Planted.open",
            ),
            dangerousMembers(listOf(Planted::class.java)).sorted(),
        )
        assertEquals(listOf("AndroidBoundaryTest\$Planted.codec"), seamUses(listOf(Planted::class.java)))
    }

    @Suppress("unused")
    internal class Planted(stop: () -> Unit) {
        fun open(buffer: ByteBuffer, sessions: (ByteBuffer, Int) -> Any): Int = buffer.hashCode() + sessions.hashCode()

        fun bytes(): ByteArray = ByteArray(1)

        fun buffer(): ByteBuffer = ByteBuffer.allocate(1)

        fun codec(): ImageCodec? = null
    }

    /**
     * The public Android file-mode and in-memory API as Java sees it (ADR 0015 decisions 3 and 6, ADR 0002 decision 3):
     * static methods of the file class `FaceMaskerAndroid` with every `@JvmOverloads` arity, and the static factories of `AndroidOutputTargets`; none of
     * them takes or returns a seam type (checked by the scan above).
     */
    @Test
    fun javaSeesTheAndroidFileModeFormsAsStaticMethods() {
        val c = Class.forName("io.github.shishark.faceblur.FaceMaskerAndroid")
        val statics = c.declaredMethods
            .filter { Modifier.isStatic(it.modifiers) && Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { "${it.name}/${it.parameterCount}" }
            .sorted()
        assertEquals(
            listOf(
                "applyMasks/3", "applyMasks/4",
                "detect/2", "detect/3",
                "detectFile/3", "detectFile/4", "detectFile/5",
                "maskFile/4", "maskFile/5", "maskFile/5", "maskFile/6", "maskFile/6", "maskFile/7", "maskFile/7",
                "maskInPlace/2", "maskInPlace/3",
                "maskJpeg/3", "maskJpeg/4", "maskJpeg/5",
                "planFile/3", "planFile/4", "planFile/5",
            ),
            statics,
        )
        // The PlanMasker forms take the PlanMasker first (Java: FaceMaskerAndroid.planFile(planMasker, context, uri)).
        val planForms = c.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) && !it.isSynthetic &&
                it.parameterTypes.firstOrNull() == PlanMasker::class.java
        }.map { "${it.name}/${it.parameterCount}" }.sorted()
        assertEquals(
            listOf("maskFile/5", "maskFile/6", "maskFile/7", "planFile/3", "planFile/4", "planFile/5"),
            planForms,
        )
        // The in-memory forms take the FaceMasker and then the caller's Bitmap (Java:
        // FaceMaskerAndroid.maskInPlace(masker, bitmap)); nothing else of the class takes a Bitmap.
        val bitmapForms = c.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) && !it.isSynthetic &&
                it.parameterTypes.any { p -> p == android.graphics.Bitmap::class.java }
        }
        assertEquals(
            listOf("applyMasks/3", "applyMasks/4", "detect/2", "detect/3", "maskInPlace/2", "maskInPlace/3"),
            bitmapForms.map { "${it.name}/${it.parameterCount}" }.sorted(),
        )
        for (m in bitmapForms) {
            assertEquals(
                listOf<Class<*>>(
                    io.github.shishark.faceblur.FaceMasker::class.java,
                    android.graphics.Bitmap::class.java,
                ),
                m.parameterTypes.take(2),
                m.name,
            )
        }
        val targets = io.github.shishark.faceblur.AndroidOutputTargets::class.java
        for (name in listOf("newFile", "overwriteNonAtomic")) {
            assertTrue(Modifier.isStatic(targets.getMethod(name, android.net.Uri::class.java).modifiers), name)
        }
    }
}
