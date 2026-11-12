// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.FaceMasker
import io.github.shishark.faceblur.FaceMaskerConfig
import io.github.shishark.faceblur.FileOptions
import io.github.shishark.faceblur.FileResult
import io.github.shishark.faceblur.OneFaceRunner
import io.github.shishark.faceblur.detect.TensorRunner
import io.github.shishark.faceblur.detect.testYuNetModel
import io.github.shishark.faceblur.filemode.CallContext
import io.github.shishark.faceblur.filemode.StreamReader
import io.github.shishark.faceblur.filemode.StreamSink
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateDeviceCMYK
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapByteOrder16Big
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringByDeletingLastPathComponent
import platform.Foundation.writeToFile
import platform.ImageIO.CGImageDestinationAddAuxiliaryDataInfo
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationAddImageAndMetadata
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageMetadataCreateMutable
import platform.ImageIO.CGImageMetadataSetValueWithPath
import platform.ImageIO.CGImageSourceCopyAuxiliaryDataInfoAtIndex
import platform.ImageIO.CGImageSourceCopyPropertiesAtIndex
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetCount
import platform.ImageIO.kCGImageAuxiliaryDataInfoData
import platform.ImageIO.kCGImageAuxiliaryDataInfoDataDescription
import platform.ImageIO.kCGImageAuxiliaryDataInfoMetadata
import platform.ImageIO.kCGImageAuxiliaryDataTypeDepth
import platform.ImageIO.kCGImageAuxiliaryDataTypeDisparity
import platform.ImageIO.kCGImageAuxiliaryDataTypeHDRGainMap
import platform.ImageIO.kCGImageAuxiliaryDataTypeISOGainMap
import platform.ImageIO.kCGImageAuxiliaryDataTypePortraitEffectsMatte
import platform.ImageIO.kCGImageAuxiliaryDataTypeSemanticSegmentationGlassesMatte
import platform.ImageIO.kCGImageAuxiliaryDataTypeSemanticSegmentationHairMatte
import platform.ImageIO.kCGImageAuxiliaryDataTypeSemanticSegmentationSkinMatte
import platform.ImageIO.kCGImageAuxiliaryDataTypeSemanticSegmentationSkyMatte
import platform.ImageIO.kCGImageAuxiliaryDataTypeSemanticSegmentationTeethMatte
import platform.ImageIO.kCGImageDestinationLossyCompressionQuality
import platform.ImageIO.kCGImagePropertyExifDictionary
import platform.ImageIO.kCGImagePropertyExifUserComment
import platform.ImageIO.kCGImagePropertyGPSDictionary
import platform.ImageIO.kCGImagePropertyGPSLatitude
import platform.ImageIO.kCGImagePropertyGPSLatitudeRef
import platform.ImageIO.kCGImagePropertyGPSLongitude
import platform.ImageIO.kCGImagePropertyGPSLongitudeRef
import platform.ImageIO.kCGImagePropertyIPTCDictionary
import platform.ImageIO.kCGImagePropertyOrientation
import platform.ImageIO.kCGImagePropertyTIFFDictionary
import platform.ImageIO.kCGImagePropertyTIFFImageDescription
import platform.ImageIO.kCGImagePropertyTIFFMake
import platform.ImageIO.kCGImagePropertyTIFFModel

/** Canary strings planted in the metadata of generated inputs; none may occur in any output (ST-029 AC2). */
internal object Canary {
    const val EXIF = "FBCANARY-EXIF-USERCOMMENT"
    const val DESCRIPTION = "FBCANARY-TIFF-DESCRIPTION"
    const val MAKE = "FBCANARY-MAKE"
    const val MODEL = "FBCANARY-MODEL"
    const val XMP = "FBCANARY-XMP-CREATOR"
    val ALL = listOf(EXIF, DESCRIPTION, MAKE, MODEL, XMP)
}

/** A runner that sees no face (the one-face runner with all scores at 0). */
internal class NoFaceRunner : TensorRunner by OneFaceRunner() {
    private val inner = OneFaceRunner()

    override fun run(input: FloatArray): Map<String, FloatArray> =
        inner.run(input).also { m -> m.keys.filter { it.startsWith("cls_") }.forEach { m.getValue(it).fill(0f) } }
}

/** Test-side helpers: fixtures on disk, ImageIO encoding of generated inputs, inspection of outputs. */
internal object IosFiles {
    /** The repository root: the first parent of the test executable that holds `testdata/yunet_reference.json`. */
    val repoRoot: String by lazy {
        var dir = NSProcessInfo.processInfo.arguments.first().toString()
        repeat(16) {
            dir = (dir as NSString).stringByDeletingLastPathComponent()
            if (NSFileManager.defaultManager.fileExistsAtPath("$dir/testdata/yunet_reference.json")) return@lazy dir
        }
        error("the repository testdata folder was not found above the test executable")
    }

    /**
     * The registered fixtures, sorted: the public set `testdata/pd` and, in the maintainer's local gate only, the
     * verified local images (`build/local-fixtures/images`, copied there by the build before the tests run; a public
     * build removes that folder first). [localCount] says how many of them come from the local folder.
     */
    fun publicFixtures(): List<String> =
        listOf("$repoRoot/testdata/pd", "$repoRoot/build/local-fixtures/images").flatMap { dir ->
            if (!NSFileManager.defaultManager.fileExistsAtPath(dir)) return@flatMap emptyList()

            @Suppress("UNCHECKED_CAST")
            val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null) as List<String>
            names.filter { it.endsWith(".png") }.sorted().map { "$dir/$it" }
        }

    /** The number of fixtures that come from the local fixtures folder (0 in a public build, 12 in the local gate). */
    fun localCount(): Int = publicFixtures().count { it.contains("/build/local-fixtures/") }

    /** A new, empty folder of this test under the process's temporary folder. */
    fun newDir(): String {
        val dir = NSTemporaryDirectory() + "faceblur-test-" + NSUUID().UUIDString
        check(NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null))
        return dir
    }

    fun removeDir(dir: String) {
        NSFileManager.defaultManager.removeItemAtPath(dir, null)
    }

    fun read(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile(path) ?: error("unreadable test file")
        val n = data.length.toInt()
        return if (n == 0) ByteArray(0) else data.bytes!!.reinterpret<ByteVar>().readBytes(n)
    }

    fun store(path: String, bytes: ByteArray) {
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.convert()) }
        check(data.writeToFile(path, true))
    }

    /** The first image of [bytes] decoded by ImageIO, as stored (no orientation applied). */
    fun image(bytes: ByteArray): CGImageRef {
        val data = cfData(bytes)
        val src = CGImageSourceCreateWithData(data, null)
        CFRelease(data)
        val img = CGImageSourceCreateImageAtIndex(src, 0u, null)
        CFRelease(src)
        return img ?: error("not decodable")
    }

    fun cfData(bytes: ByteArray) =
        bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.convert()) }!!

    fun bytesOf(data: platform.CoreFoundation.CFDataRef?): ByteArray {
        val n = CFDataGetLength(data).toInt()
        return CFDataGetBytePtr(data)!!.reinterpret<ByteVar>().readBytes(n)
    }

    /** An opaque sRGB image of [argb] (non-premultiplied ARGB, alpha ignored). */
    fun imageOf(w: Int, h: Int, argb: IntArray): CGImageRef = imageOfRgba(
        w,
        h,
        ByteArray(w * h * 4) {
            val v = argb[it / 4]
            when (it % 4) {
                0 -> (v shr 16).toByte()
                1 -> (v shr 8).toByte()
                2 -> v.toByte()
                else -> 0xFF.toByte()
            }
        },
        alpha = false,
    )

    /** An sRGB image of RGBA bytes; [alpha] = non-premultiplied alpha in the fourth byte, else skipped. */
    fun imageOfRgba(w: Int, h: Int, rgba: ByteArray, alpha: Boolean): CGImageRef {
        val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
        val info = if (alpha) CGImageAlphaInfo.kCGImageAlphaLast else CGImageAlphaInfo.kCGImageAlphaNoneSkipLast
        return create(w, h, 8, 32, w * 4, space, info.value, rgba).also { CGColorSpaceRelease(space) }
    }

    /** A 16-bit-per-channel sRGB RGB image (big-endian), for the odd-input corpus. */
    fun image16(w: Int, h: Int, argb: IntArray): CGImageRef {
        val b = ByteArray(w * h * 6)
        for (i in 0 until w * h) {
            for (c in 0 until 3) {
                val v = (argb[i] shr (16 - 8 * c)) and 0xFF
                b[i * 6 + c * 2] = v.toByte()
                b[i * 6 + c * 2 + 1] = v.toByte()
            }
        }
        val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
        val info = CGImageAlphaInfo.kCGImageAlphaNone.value or kCGBitmapByteOrder16Big
        return create(w, h, 16, 48, w * 6, space, info, b).also { CGColorSpaceRelease(space) }
    }

    /** A CMYK image (device CMYK), for the odd-input corpus. */
    fun imageCmyk(w: Int, h: Int, argb: IntArray): CGImageRef {
        val b = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val r = (argb[i] shr 16) and 0xFF
            val g = (argb[i] shr 8) and 0xFF
            val bl = argb[i] and 0xFF
            b[i * 4] = (255 - r).toByte()
            b[i * 4 + 1] = (255 - g).toByte()
            b[i * 4 + 2] = (255 - bl).toByte()
            b[i * 4 + 3] = 0
        }
        val space = CGColorSpaceCreateDeviceCMYK()
        return create(w, h, 8, 32, w * 4, space, CGImageAlphaInfo.kCGImageAlphaNone.value, b)
            .also { CGColorSpaceRelease(space) }
    }

    private fun create(
        w: Int,
        h: Int,
        bpc: Int,
        bpp: Int,
        bpr: Int,
        space: CGColorSpaceRef?,
        info: UInt,
        bytes: ByteArray,
    ): CGImageRef {
        val data = cfData(bytes)
        val provider = CGDataProviderCreateWithCFData(data)
        CFRelease(data)
        val img = CGImageCreate(
            w.convert(), h.convert(), bpc.convert(), bpp.convert(), bpr.convert(), space, info, provider, null, false,
            CGColorRenderingIntent.kCGRenderingIntentDefault,
        )
        CGDataProviderRelease(provider)
        return img ?: error("image not created")
    }

    /** Planted metadata of a generated input: orientation, EXIF/TIFF canaries, GPS. */
    fun cannedProperties(orientation: Int): CFDictionaryRef {
        val exif = IosCf.dictionary(kCGImagePropertyExifUserComment to IosCf.cfString(Canary.EXIF))
        val tiff = IosCf.dictionary(
            kCGImagePropertyTIFFImageDescription to IosCf.cfString(Canary.DESCRIPTION),
            kCGImagePropertyTIFFMake to IosCf.cfString(Canary.MAKE),
            kCGImagePropertyTIFFModel to IosCf.cfString(Canary.MODEL),
        )
        val gps = IosCf.dictionary(
            kCGImagePropertyGPSLatitude to IosCf.number(48.1371),
            kCGImagePropertyGPSLatitudeRef to IosCf.cfString("N"),
            kCGImagePropertyGPSLongitude to IosCf.number(11.5754),
            kCGImagePropertyGPSLongitudeRef to IosCf.cfString("E"),
        )
        return IosCf.dictionary(
            kCGImagePropertyOrientation to IosCf.number(orientation),
            kCGImagePropertyExifDictionary to exif,
            kCGImagePropertyTIFFDictionary to tiff,
            kCGImagePropertyGPSDictionary to gps,
            kCGImageDestinationLossyCompressionQuality to IosCf.number(0.95),
        )
    }

    /** Auxiliary data that a generated input may carry. */
    enum class Aux { DISPARITY, GAIN_MAP }

    /**
     * [image] encoded by ImageIO as [type] (`public.jpeg`, `public.heic`, `public.png`) with [properties] and, if
     * [xmp], an XMP packet that carries [Canary.XMP]; plus the auxiliary images [aux].
     */
    fun encode(
        image: CGImageRef,
        type: String,
        properties: CFDictionaryRef? = null,
        xmp: Boolean = false,
        aux: Set<Aux> = emptySet(),
    ): ByteArray = encodeOrNull(image, type, properties, xmp, aux, auxMetadata = false)
        ?: encodeOrNull(image, type, properties, xmp, aux, auxMetadata = true)
        ?: error("ImageIO could not encode $type")

    /** [encode] once; `null` when ImageIO refuses the combination. */
    fun encodeOrNull(
        image: CGImageRef,
        type: String,
        properties: CFDictionaryRef?,
        xmp: Boolean,
        aux: Set<Aux>,
        auxMetadata: Boolean,
    ): ByteArray? {
        val out = CFDataCreateMutable(null, 0)
        val t = IosCf.cfString(type)
        val dest = CGImageDestinationCreateWithData(out, t, 1u, null) ?: error("no destination for $type")
        CFRelease(t)
        if (xmp) {
            val meta = CGImageMetadataCreateMutable()
            val path = IosCf.cfString("xmp:CreatorTool")
            val value = IosCf.cfString(Canary.XMP)
            check(CGImageMetadataSetValueWithPath(meta, null, path, value))
            CFRelease(path)
            CFRelease(value)
            CGImageDestinationAddImageAndMetadata(dest, image, meta, properties)
            CFRelease(meta)
        } else {
            CGImageDestinationAddImage(dest, image, properties)
        }
        for (a in aux) CGImageDestinationAddAuxiliaryDataInfo(dest, auxType(a), auxInfo(a, auxMetadata))
        val ok = CGImageDestinationFinalize(dest)
        CFRelease(dest)
        return (if (ok) bytesOf(out) else null).also { CFRelease(out) }
    }

    private fun auxType(a: Aux): CFStringRef? = when (a) {
        Aux.DISPARITY -> kCGImageAuxiliaryDataTypeDisparity
        Aux.GAIN_MAP -> kCGImageAuxiliaryDataTypeHDRGainMap
    }

    /** A small map: 64 x 48, Float16 disparity ('hdis') or 8-bit one-component gain map ('L008'). */
    private fun auxInfo(a: Aux, withMetadata: Boolean): CFDictionaryRef {
        val w = 64
        val h = 48
        val (bpp, format) = if (a == Aux.DISPARITY) 2 to 0x68646973 else 1 to 0x4C303038
        val bytes = ByteArray(w * h * bpp) { 7 } // small, finite Float16 disparity values
        val description = IosCf.dictionary(
            IosCf.cfString("Width") to IosCf.number(w),
            IosCf.cfString("Height") to IosCf.number(h),
            IosCf.cfString("BytesPerRow") to IosCf.number(w * bpp),
            IosCf.cfString("PixelFormat") to IosCf.number(format),
        )
        val pairs = mutableListOf<Pair<CFStringRef?, platform.CoreFoundation.CFTypeRef?>>(
            kCGImageAuxiliaryDataInfoData to cfData(bytes),
            kCGImageAuxiliaryDataInfoDataDescription to description,
        )
        if (withMetadata) pairs += kCGImageAuxiliaryDataInfoMetadata to CGImageMetadataCreateMutable()
        return IosCf.dictionary(*pairs.toTypedArray())
    }

    /** Every auxiliary data type ImageIO knows; the output must carry none (ST-029 AC1). */
    val AUX_TYPES: List<CFStringRef?> = listOf(
        kCGImageAuxiliaryDataTypeDepth,
        kCGImageAuxiliaryDataTypeDisparity,
        kCGImageAuxiliaryDataTypePortraitEffectsMatte,
        kCGImageAuxiliaryDataTypeSemanticSegmentationSkinMatte,
        kCGImageAuxiliaryDataTypeSemanticSegmentationHairMatte,
        kCGImageAuxiliaryDataTypeSemanticSegmentationTeethMatte,
        kCGImageAuxiliaryDataTypeSemanticSegmentationGlassesMatte,
        kCGImageAuxiliaryDataTypeSemanticSegmentationSkyMatte,
        kCGImageAuxiliaryDataTypeHDRGainMap,
        kCGImageAuxiliaryDataTypeISOGainMap,
    )

    /** What ImageIO sees in a file (ST-029 AC1 inspection; the same function flags the inputs). */
    class Inspection(
        val images: Int,
        val auxiliary: Int,
        val gps: Boolean,
        val exifText: Boolean,
        val iptc: Boolean,
        val xmpPacket: Boolean,
        val orientation: Long?,
        val canaries: List<String>,
        val width: Long?,
        val height: Long?,
    ) {
        /** True when the file passes every check of an output. */
        val clean: Boolean
            get() = images == 1 && auxiliary == 0 && !gps && !exifText && !iptc && !xmpPacket &&
                (orientation == null || orientation == 1L) && canaries.isEmpty()

        override fun toString(): String = "Inspection(images=$images, auxiliary=$auxiliary, gps=$gps, " +
            "exifText=$exifText, iptc=$iptc, xmp=$xmpPacket, orientation=$orientation, canaries=$canaries)"
    }

    fun inspect(bytes: ByteArray): Inspection {
        val data = cfData(bytes)
        val src = CGImageSourceCreateWithData(data, null)!!
        CFRelease(data)
        val count = CGImageSourceGetCount(src).toInt()
        var aux = 0
        for (i in 0 until count) {
            for (t in AUX_TYPES) {
                val info = CGImageSourceCopyAuxiliaryDataInfoAtIndex(src, i.convert(), t)
                if (info != null) {
                    aux++
                    CFRelease(info)
                }
            }
        }
        val props = CGImageSourceCopyPropertiesAtIndex(src, 0u, null)
        val gps = props != null && platform.CoreFoundation.CFDictionaryContainsKey(props, kCGImagePropertyGPSDictionary)
        val iptc =
            props != null && platform.CoreFoundation.CFDictionaryContainsKey(props, kCGImagePropertyIPTCDictionary)
        val exifDict = props?.let { platform.CoreFoundation.CFDictionaryGetValue(it, kCGImagePropertyExifDictionary) }

        @Suppress("UNCHECKED_CAST") // the EXIF entry of an ImageIO property dictionary is a dictionary
        val exifText = exifDict != null && platform.CoreFoundation.CFDictionaryContainsKey(
            exifDict as CFDictionaryRef,
            kCGImagePropertyExifUserComment,
        )
        val orientation = IosCf.long(props, kCGImagePropertyOrientation)
        val width = IosCf.long(props, platform.ImageIO.kCGImagePropertyPixelWidth)
        val height = IosCf.long(props, platform.ImageIO.kCGImagePropertyPixelHeight)
        if (props != null) CFRelease(props)
        CFRelease(src)
        val text = bytes.decodeToString()
        return Inspection(
            count,
            aux,
            gps,
            exifText,
            iptc,
            text.contains("http://ns.adobe.com/xap/1.0/") || text.contains("x:xmpmeta"),
            orientation,
            Canary.ALL.filter { text.contains(it) },
            width,
            height,
        )
    }

    /** A masker on the real Kotlin glue adapter with a fake runtime whose runner is [runner]. */
    fun masker(runner: () -> TensorRunner = { OneFaceRunner() }): FaceMasker = FaceMasker.forTest(
        testYuNetModel(),
        FaceMaskerConfig(),
        IosModelRuntime(FakeFactory { FakeBridge(runner()) }),
    )

    /** The internal stream form (ADR 0015 decision 4) on [bytes]; returns the result and the written JPEG. */
    fun maskBytes(
        masker: FaceMasker,
        bytes: ByteArray,
        options: FileOptions = FileOptions(),
    ): Pair<FileResult, ByteArray> {
        var at = 0
        val reader = StreamReader { buffer, offset, length ->
            if (at >= bytes.size) {
                -1
            } else {
                val n = minOf(length, bytes.size - at)
                bytes.copyInto(buffer, offset, at, at + n)
                at += n
                n
            }
        }
        var out: ByteArray? = null
        val sink = StreamSink { out = it.copyOf() }
        val result = masker.maskStream(reader, sink, options, null, CallContext(null))
        return result to (out ?: error("nothing was handed to the sink"))
    }

    /** The decoded size of [bytes] as stored. */
    fun storedSize(bytes: ByteArray): Pair<Int, Int> {
        val img = image(bytes)
        return (CGImageGetWidth(img).toInt() to CGImageGetHeight(img).toInt()).also { CGImageRelease(img) }
    }

    /**
     * A snapshot of the files (path -> size and modification time) under the app-visible folders of this process:
     * tmp, Documents, Library (Caches, Application Support, ...). Names in [ignoredServices] are system services
     * that write into the simulator's shared folders on their own (observed, not FaceBlur); they are matched as a
     * whole path component only.
     */
    fun snapshot(): Map<String, String> {
        val out = HashMap<String, String>()
        val fm = NSFileManager.defaultManager
        val roots = listOf(NSTemporaryDirectory(), NSHomeDirectory() + "/Documents", NSHomeDirectory() + "/Library")
        for (root in roots.map { it.trimEnd('/') }.distinct()) {
            val walker = fm.enumeratorAtPath(root) ?: continue
            while (true) {
                val rel = walker.nextObject()?.toString() ?: break
                if (rel.split('/').any { it in ignoredServices }) continue
                val path = "$root/$rel"
                val attrs = fm.attributesOfItemAtPath(path, null) ?: continue
                // A folder is recorded by its name only: adding a file changes its own date, not its content.
                out[path] = if (attrs["NSFileType"] == "NSFileTypeDirectory") {
                    "folder"
                } else {
                    "${attrs["NSFileSize"]}:${attrs["NSFileModificationDate"]}"
                }
            }
        }
        return out
    }

    val ignoredServices = setOf("com.apple.geod")

    /**
     * Keeps [bytes] for the independent checker (`verification/jpeg-privacy`, run on the host after the tests) in the
     * repository's ignored build folder: outputs under `ios-file-mode-check/outputs`, planted inputs under `inputs`.
     */
    fun keepForChecker(kind: String, name: String, bytes: ByteArray) {
        val dir = "$repoRoot/faceblur/build/ios-file-mode-check/$kind"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        store("$dir/$name", bytes)
    }

    fun diff(before: Map<String, String>, after: Map<String, String>): List<String> =
        (before.keys + after.keys).filter { before[it] != after[it] }.sorted()
}
