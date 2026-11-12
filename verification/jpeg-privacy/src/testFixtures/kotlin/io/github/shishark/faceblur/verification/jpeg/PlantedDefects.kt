// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

/** A generated file with one planted defect, the options to check it with, and the exact codes expected. */
public class PlantedDefect(
    public val name: String,
    public val bytes: ByteArray,
    public val options: CheckOptions,
    public val expectedFailures: Set<FindingCode>,
)

/** The self-test catalogue (ST-042 AC1): one planted defect per file, plus the clean files that must pass. */
public object PlantedDefects {
    public const val CANARY: String = "FBCANARY-7f3a91c2d4e5"
    private val canaries = listOf(CANARY.toByteArray())

    private fun gpsBlock(t: TiffBuilder): MutableList<Entry> = mutableListOf(
        t.ascii(0x01, "N"),
        t.rational(0x02, 48L to 1L, 8L to 1L, 3000L to 100L),
        t.ascii(0x03, "E"),
        t.rational(0x04, 11L to 1L, 34L to 1L, 1500L to 100L),
    )

    private fun strip(configure: (TiffBuilder) -> Unit): ByteArray {
        val t = TiffBuilder()
        t.ifd0.add(t.orientation(1))
        configure(t)
        return t.build()
    }

    /** A strip-all file whose EXIF segment (marker and length included) is exactly [total] bytes. */
    private fun exifOfSize(total: Int): ByteArray {
        val t = TiffBuilder()
        t.ifd0.add(t.orientation(1))
        val base = JpegFixtures.app1Exif(t.build()).size
        t.zeroPadding = total - base
        return JpegBuilder().exif(t.build()).build()
    }

    /** An EXIF file whose TIFF bytes are changed by [patch] (given the TIFF offset in the file and the file). */
    private fun tiffPatched(tiff: ByteArray, patch: (Int, ByteArray) -> Unit): ByteArray {
        val file = JpegBuilder().exif(tiff).build()
        val marker = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
        val at = (0..file.size - marker.size).first { i -> marker.indices.all { file[i + it] == marker[it] } }
        patch(at + marker.size, file)
        return file
    }

    private fun withExif(tiff: ByteArray, configure: JpegBuilder.() -> Unit = {}): ByteArray =
        JpegBuilder().exif(tiff).apply(configure).build()

    /** Files in the correct strip-all shape; the checker must report nothing for each. */
    public fun clean(): List<PlantedDefect> {
        val none = emptySet<FindingCode>()
        val srgbLike = IccFixtures.validSrgb()
        return listOf(
            PlantedDefect("clean-strip-all", JpegBuilder().strippedExif().build(), CheckOptions(), none),
            PlantedDefect(
                "clean-strip-all-big-endian",
                JpegBuilder().strippedExif(littleEndian = false).build(),
                CheckOptions(),
                none,
            ),
            PlantedDefect(
                "clean-strip-all-no-jfif",
                JpegBuilder().strippedExif().apply {
                    includeJfif = false
                }.build(),
                CheckOptions(),
                none,
            ),
            PlantedDefect(
                "clean-with-platform-style-icc",
                JpegBuilder().strippedExif().apply { afterJfif.add(JpegFixtures.icc(srgbLike)) }.build(),
                CheckOptions(allowedIccSha256 = setOf(IccFixtures.sha256(srgbLike))),
                none,
            ),
            PlantedDefect(
                "clean-progressive-jpeg",
                JpegBuilder(JpegFixtures.syntheticJpeg(progressive = true)).strippedExif().build(),
                CheckOptions(),
                none,
            ),
            PlantedDefect("clean-exif-segment-of-exactly-1024-bytes", exifOfSize(1024), CheckOptions(), none),
            PlantedDefect(
                "clean-with-restart-interval-zero",
                JpegBuilder().strippedExif().apply {
                    beforeScan.add(JpegFixtures.segment(0xDD, byteArrayOf(0, 0)))
                }.build(),
                CheckOptions(),
                none,
            ),
        )
    }

    /** Every planted defect of ST-042 AC1 plus the further segment and ICC cases. */
    @Suppress("LongMethod")
    public fun defects(): List<PlantedDefect> {
        val out = mutableListOf<PlantedDefect>()
        fun add(name: String, bytes: ByteArray, expected: Set<FindingCode>, options: CheckOptions = CheckOptions()) {
            out.add(PlantedDefect(name, bytes, options, expected))
        }

        val withCanaries = CheckOptions(canaries = canaries)
        add("gps-block", withExif(strip { t -> t.gps = gpsBlock(t) }), setOf(FindingCode.GPS_NOT_ALLOWED))
        add(
            "exif-thumbnail-ifd1-with-embedded-jpeg",
            withExif(strip { t -> t.thumbnail = JpegFixtures.syntheticJpeg(8, 8) }),
            setOf(FindingCode.EXIF_THUMBNAIL_IFD1, FindingCode.EMBEDDED_JPEG_IN_SEGMENT),
            // The size limit is lifted so that only the thumbnail defect is under test here.
            CheckOptions(maxExifSegmentBytes = 65535),
        )
        add(
            "exif-ifd1-without-thumbnail-data",
            withExif(strip { t -> t.ifd1 = mutableListOf(t.short(0x0103, 6)) }),
            setOf(FindingCode.EXIF_THUMBNAIL_IFD1),
        )
        add(
            "xmp-packet",
            JpegBuilder().strippedExif().apply {
                afterJfif.add(JpegFixtures.app1Xmp())
            }.build(),
            setOf(FindingCode.XMP_PRESENT),
        )
        add(
            "xmp-header-without-xmpmeta-body",
            JpegBuilder().strippedExif().apply {
                afterJfif.add(JpegFixtures.segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000$CANARY".toByteArray()))
            }.build(),
            setOf(FindingCode.XMP_PRESENT),
        )
        add(
            "extended-xmp",
            JpegBuilder().strippedExif().apply {
                afterJfif.add(
                    JpegFixtures.segment(0xE1, "http://ns.adobe.com/xmp/extension/\u0000$CANARY".toByteArray()),
                )
            }.build(),
            setOf(FindingCode.XMP_PRESENT),
        )
        add(
            "iptc-app13",
            JpegBuilder().strippedExif().apply {
                afterJfif.add(JpegFixtures.app13Iptc())
            }.build(),
            setOf(FindingCode.IPTC_APP13),
        )
        add(
            "comment",
            JpegBuilder().strippedExif().apply {
                afterJfif.add(JpegFixtures.com())
            }.build(),
            setOf(FindingCode.COM_PRESENT),
        )
        add(
            "makernote-canary-in-exif-subifd",
            withExif(strip { t -> t.exif = mutableListOf(t.undefined(0x927C, "MAKERNOTE-$CANARY".toByteArray())) }),
            setOf(FindingCode.EXIF_SUBIFD_NOT_ALLOWED, FindingCode.MAKERNOTE_PRESENT, FindingCode.CANARY_FOUND),
            withCanaries,
        )
        add(
            "makernote-canary-in-ifd0",
            withExif(strip { t -> t.ifd0.add(t.undefined(0x927C, "MAKERNOTE-$CANARY".toByteArray())) }),
            setOf(FindingCode.MAKERNOTE_PRESENT, FindingCode.CANARY_FOUND),
            withCanaries,
        )
        val canaryIcc = IccFixtures.profile("ICC-$CANARY")
        val canaryHash = IccFixtures.sha256(canaryIcc)
        val iccBuilder = { JpegBuilder().strippedExif().apply { afterJfif.add(JpegFixtures.icc(canaryIcc)) }.build() }
        fun withProfile(profile: ByteArray, options: CheckOptions) =
            JpegBuilder().strippedExif().apply { afterJfif.add(JpegFixtures.icc(profile)) }.build() to options
        add(
            "icc-canary-description",
            iccBuilder(),
            setOf(FindingCode.CANARY_FOUND),
            CheckOptions(canaries = canaries, allowedIccSha256 = setOf(canaryHash)),
        )
        add(
            "icc-copied-from-source",
            iccBuilder(),
            setOf(FindingCode.ICC_COPIED_FROM_SOURCE),
            CheckOptions(sourceIccProfile = canaryIcc, allowedIccSha256 = setOf(canaryHash)),
        )
        add(
            "icc-without-reviewed-list-free-text-description",
            iccBuilder(),
            setOf(FindingCode.ICC_NOT_ON_ALLOWLIST),
        )
        add(
            "icc-not-on-reviewed-list",
            iccBuilder(),
            setOf(FindingCode.ICC_NOT_ON_ALLOWLIST),
            CheckOptions(allowedIccSha256 = setOf("0".repeat(64))),
        )
        add(
            "icc-not-allowed",
            iccBuilder(),
            setOf(FindingCode.ICC_NOT_ALLOWED),
            CheckOptions(allowIcc = false, allowedIccSha256 = setOf(canaryHash)),
        )
        val cmyk = IccFixtures.profile("cmyk", "CMYK")
        val cmykFile = withProfile(cmyk, CheckOptions(allowedIccSha256 = setOf(IccFixtures.sha256(cmyk))))
        add("icc-colour-space-not-rgb", cmykFile.first, setOf(FindingCode.ICC_NOT_RGB), cmykFile.second)
        add(
            "icc-two-segments",
            JpegBuilder().strippedExif().apply {
                afterJfif.add(JpegFixtures.icc(IccFixtures.profile("a")))
                afterJfif.add(JpegFixtures.icc(IccFixtures.profile("b")))
            }.build(),
            setOf(FindingCode.ICC_MULTIPLE_SEGMENTS),
            CheckOptions(allowedIccSha256 = setOf(IccFixtures.sha256(IccFixtures.profile("a")))),
        )
        add(
            "icc-chunk-1-of-2",
            JpegBuilder().strippedExif().apply {
                afterJfif.add(JpegFixtures.icc(IccFixtures.profile("a"), chunk = 1, chunks = 2))
            }.build(),
            setOf(FindingCode.ICC_MALFORMED),
        )
        val wrongSize = IccFixtures.profile("a").also { it[3] = (it[3] + 4).toByte() }
        val wrongSizeFile =
            withProfile(wrongSize, CheckOptions(allowedIccSha256 = setOf(IccFixtures.sha256(wrongSize))))
        add("icc-size-field-wrong", wrongSizeFile.first, setOf(FindingCode.ICC_MALFORMED), wrongSizeFile.second)
        val jpegInIcc = IccFixtures.profileWithUnreferencedBytes("a", JpegFixtures.syntheticJpeg(8, 8))
        val jpegInIccFile =
            withProfile(jpegInIcc, CheckOptions(allowedIccSha256 = setOf(IccFixtures.sha256(jpegInIcc))))
        add(
            "icc-with-embedded-jpeg",
            jpegInIccFile.first,
            setOf(FindingCode.ICC_MALFORMED, FindingCode.EMBEDDED_JPEG_IN_SEGMENT),
            jpegInIccFile.second,
        )
        val textInIcc = IccFixtures.profileWithUnreferencedBytes("a", "owner: $CANARY".toByteArray())
        val textInIccFile =
            withProfile(textInIcc, CheckOptions(allowedIccSha256 = setOf(IccFixtures.sha256(textInIcc))))
        add(
            "icc-with-free-text-outside-any-tag",
            textInIccFile.first,
            setOf(FindingCode.ICC_MALFORMED),
            textInIccFile.second,
        )
        val reserved = IccFixtures.profile("a").also { it[110] = 0x41 }
        val reservedFile = withProfile(reserved, CheckOptions(allowedIccSha256 = setOf(IccFixtures.sha256(reserved))))
        add("icc-header-reserved-bytes-used", reservedFile.first, setOf(FindingCode.ICC_MALFORMED), reservedFile.second)
        val v4 = IccFixtures.profileWithMultiLocalizedDescription(CANARY)
        val v4File =
            withProfile(v4, CheckOptions(canaries = canaries, allowedIccSha256 = setOf(IccFixtures.sha256(v4))))
        add("icc-v4-description-holds-utf16-canary", v4File.first, setOf(FindingCode.CANARY_FOUND), v4File.second)
        add(
            "mpf-segment-and-second-image-after-eoi",
            JpegBuilder().strippedExif().apply {
                afterJfif.add(JpegFixtures.mpf())
                trailing = JpegFixtures.syntheticJpeg(16, 16)
            }.build(),
            setOf(FindingCode.MPF_PRESENT, FindingCode.TRAILING_BYTES, FindingCode.SECOND_IMAGE),
        )
        add(
            "byte-after-eoi",
            JpegBuilder().strippedExif().apply {
                trailing = byteArrayOf(0)
            }.build(),
            setOf(FindingCode.TRAILING_BYTES),
        )
        add(
            "canary-after-eoi",
            JpegBuilder().strippedExif().apply { trailing = CANARY.toByteArray() }.build(),
            setOf(FindingCode.TRAILING_BYTES, FindingCode.CANARY_FOUND),
            withCanaries,
        )
        add(
            "second-soi",
            JpegBuilder().strippedExif().apply { rawBeforeScan = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) }.build(),
            setOf(FindingCode.SECOND_SOI),
        )
        add(
            "second-orientation-tag",
            withExif(
                strip { t ->
                    t.ifd0.add(t.orientation(1))
                },
            ),
            setOf(FindingCode.EXIF_DUPLICATE_TAG),
        )
        add(
            "second-exif-segment",
            JpegBuilder().strippedExif().strippedExif().build(),
            setOf(FindingCode.DUPLICATE_EXIF),
        )
        add(
            "extra-exif-tags",
            withExif(
                strip { t ->
                    t.ifd0.add(t.ascii(0x010F, "ACME"))
                    t.ifd0.add(t.ascii(0x0131, "Software 1.0"))
                    t.ifd0.add(t.ascii(0x0132, "2026:01:02 03:04:05"))
                },
            ),
            setOf(FindingCode.EXIF_TAG_NOT_ALLOWED),
        )
        add(
            "exif-segment-above-size-limit",
            withExif(
                strip { t ->
                    t.zeroPadding = 1100
                },
            ),
            setOf(FindingCode.EXIF_TOO_LARGE),
        )
        add("no-app1-at-all", JpegBuilder().build(), setOf(FindingCode.EXIF_MISSING))
        add(
            "orientation-6",
            withExif(
                strip { t ->
                    t.ifd0.clear()
                    t.ifd0.add(t.orientation(6))
                },
            ),
            setOf(FindingCode.ORIENTATION_NOT_1),
        )
        add("orientation-tag-absent", withExif(TiffBuilder().build()), setOf(FindingCode.ORIENTATION_MISSING))
        add(
            "exif-sub-ifd",
            withExif(strip { t -> t.exif = mutableListOf(t.ascii(0x9003, "2026:01:02 03:04:05")) }),
            setOf(FindingCode.EXIF_SUBIFD_NOT_ALLOWED),
        )
        add(
            "exif-bytes-hidden-in-unreferenced-area",
            withExif(strip { t -> t.unreferenced = "hidden-$CANARY".toByteArray() }),
            setOf(FindingCode.EXIF_UNREFERENCED_BYTES, FindingCode.CANARY_FOUND),
            withCanaries,
        )
        add(
            "jfxx-extension",
            JpegBuilder().strippedExif().apply {
                afterJfif.add(
                    JpegFixtures.segment(
                        0xE0,
                        "JFXX\u0000".toByteArray() + byteArrayOf(0x10, 0),
                    ),
                )
            }.build(),
            setOf(FindingCode.JFXX_THUMBNAIL),
        )
        add(
            "jfif-with-thumbnail",
            JpegBuilder().strippedExif().apply {
                includeJfif = false
                val payload = "JFIF\u0000".toByteArray() + byteArrayOf(1, 2, 0, 0, 1, 0, 1, 2, 2) + ByteArray(12)
                beforeJfif.add(JpegFixtures.segment(0xE0, payload))
            }.build(),
            setOf(FindingCode.JFIF_THUMBNAIL),
        )
        add(
            "second-jfif",
            JpegBuilder().strippedExif().apply {
                val payload = "JFIF\u0000".toByteArray() + byteArrayOf(1, 2, 0, 0, 1, 0, 1, 0, 0)
                afterJfif.add(0, JpegFixtures.segment(0xE0, payload))
            }.build(),
            setOf(FindingCode.DUPLICATE_APP0),
        )
        add(
            "adobe-app14",
            JpegBuilder().strippedExif().apply {
                afterJfif.add(
                    JpegFixtures.segment(
                        0xEE,
                        "Adobe".toByteArray() + ByteArray(7),
                    ),
                )
            }.build(),
            setOf(FindingCode.APP_NOT_ALLOWED),
        )
        add(
            "unknown-app2",
            JpegBuilder().strippedExif().apply {
                afterJfif.add(
                    JpegFixtures.segment(
                        0xE2,
                        "FPXR\u0000".toByteArray() + ByteArray(8),
                    ),
                )
            }.build(),
            setOf(FindingCode.APP_NOT_ALLOWED),
        )
        add(
            "exif-after-coding-segments",
            JpegBuilder().apply {
                val t = TiffBuilder()
                t.ifd0.add(t.orientation(1))
                beforeScan.add(JpegFixtures.app1Exif(t.build()))
            }.build(),
            setOf(FindingCode.SEGMENT_ORDER),
        )
        add(
            "unexpected-marker-dnl",
            JpegBuilder().strippedExif().apply {
                beforeScan.add(JpegFixtures.segment(0xDC, byteArrayOf(0, 8)))
            }.build(),
            setOf(FindingCode.UNEXPECTED_MARKER),
        )
        add("missing-eoi", JpegBuilder().strippedExif().apply { dropEoi = true }.build(), setOf(FindingCode.NO_EOI))

        // --- coding segments, markers and structure
        fun patched(configure: JpegBuilder.() -> Unit): ByteArray =
            JpegBuilder().strippedExif().apply(configure).build()
        fun table(info: Int, size: Int, fill: Int = 3) = byteArrayOf(info.toByte()) + ByteArray(size) { fill.toByte() }
        add("scan-is-missing", patched { omitScan = true }, setOf(FindingCode.NO_SCAN))
        add(
            "frame-header-is-missing",
            patched { coding.removeAll { (it[1].toInt() and 0xFF) in 0xC0..0xC2 } },
            setOf(FindingCode.NO_FRAME),
        )
        add("second-frame-header", patched { beforeScan.add(frameHeader()) }, setOf(FindingCode.MULTIPLE_FRAMES))
        add(
            "xmp-between-scan-data-and-eoi",
            patched { beforeEoi = JpegFixtures.app1Xmp() },
            setOf(FindingCode.XMP_PRESENT),
        )
        add(
            "second-soi-between-scan-data-and-eoi",
            patched { beforeEoi = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) },
            setOf(FindingCode.SECOND_SOI),
        )
        add(
            "stray-restart-marker-before-scan",
            patched { rawBeforeScan = byteArrayOf(0xFF.toByte(), 0xD0.toByte()) },
            setOf(FindingCode.UNEXPECTED_MARKER),
        )
        add(
            "stray-tem-marker-before-scan",
            patched { rawBeforeScan = byteArrayOf(0xFF.toByte(), 0x01) },
            setOf(FindingCode.UNEXPECTED_MARKER),
        )
        add(
            "unused-quantisation-table",
            patched { beforeScan.add(JpegFixtures.segment(0xDB, table(0x03, 64))) },
            setOf(FindingCode.CODING_TABLE_REDUNDANT),
        )
        add(
            "quantisation-table-defined-twice-before-the-first-scan",
            patched { beforeScan.add(coding.first { (it[1].toInt() and 0xFF) == 0xDB }.copyOf()) },
            setOf(FindingCode.CODING_TABLE_REDUNDANT),
        )
        add(
            "unused-huffman-table",
            patched { beforeScan.add(JpegFixtures.segment(0xC4, byteArrayOf(0x13) + ByteArray(16))) },
            setOf(FindingCode.CODING_TABLE_REDUNDANT),
        )
        add(
            "two-thousand-redundant-quantisation-tables",
            patched {
                val dqt = JpegFixtures.segment(0xDB, table(0x00, 64))
                repeat(2000) { beforeScan.add(dqt) }
            },
            setOf(FindingCode.CODING_TABLE_REDUNDANT, FindingCode.UNPARSEABLE),
        )
        add(
            "quantisation-tables-redefined-endlessly-after-the-scan",
            patched {
                val dqt = JpegFixtures.segment(0xDB, table(0x00, 64))
                beforeEoi = (1..40).fold(ByteArray(0)) { acc, _ -> acc + dqt }
            },
            setOf(FindingCode.UNPARSEABLE),
        )
        add(
            "bytes-hidden-inside-the-entropy-coded-segment",
            patched { beforeEoi = "HIDDEN-$CANARY".toByteArray() },
            setOf(FindingCode.DECODER_WARNING),
        )
        add(
            "bytes-hidden-found-by-the-sub-sampled-decode",
            patched { beforeEoi = "HIDDEN-$CANARY".toByteArray() },
            setOf(FindingCode.DECODER_WARNING),
            CheckOptions(maxDecodePixels = 100),
        )
        add(
            "image-above-every-decode-limit",
            patched { },
            setOf(FindingCode.DECODE_SKIPPED),
            CheckOptions(maxDecodePixels = 10, maxSubsampledDecodePixels = 100),
        )
        add(
            "frame-precision-the-decoder-rejects",
            patched {
                val i = coding.indexOfFirst { (it[1].toInt() and 0xFF) in 0xC0..0xC2 }
                coding[i] = coding[i].copyOf().also { it[4] = 12 } // sample precision 12 bits
            },
            setOf(FindingCode.DECODER_REJECTED),
        )
        add(
            "quantisation-table-defined-after-the-last-scan",
            patched { beforeEoi = JpegFixtures.segment(0xDB, table(0x00, 64)) },
            setOf(FindingCode.CODING_TABLE_REDUNDANT),
        )
        add(
            "huffman-table-defined-after-the-last-scan",
            patched { beforeEoi = JpegFixtures.segment(0xC4, byteArrayOf(0x00) + ByteArray(16)) },
            setOf(FindingCode.CODING_TABLE_REDUNDANT),
        )
        add(
            "scan-names-a-component-the-frame-does-not-have",
            patched { scan = scan.copyOf().also { it[5] = 99 } },
            setOf(FindingCode.UNPARSEABLE),
        )
        add(
            "quantisation-precision-field-out-of-range",
            patched { beforeScan.add(JpegFixtures.segment(0xDB, table(0x20, 192))) },
            setOf(FindingCode.UNPARSEABLE),
        )
        add(
            "quantisation-table-id-out-of-range",
            patched { beforeScan.add(JpegFixtures.segment(0xDB, table(0x04, 64))) },
            setOf(FindingCode.UNPARSEABLE),
        )
        add(
            "huffman-table-class-out-of-range",
            patched { beforeScan.add(JpegFixtures.segment(0xC4, byteArrayOf(0x20) + ByteArray(16))) },
            setOf(FindingCode.UNPARSEABLE),
        )
        // Entry 0 points into the header; entry 1 still covers the data.
        val shifted = IccFixtures.profileWithTwoTags("a").also { it[139] = 128.toByte() }
        val shiftedFile = withProfile(shifted, CheckOptions(allowedIccSha256 = setOf(IccFixtures.sha256(shifted))))
        add(
            "icc-tag-data-offset-inside-the-header",
            shiftedFile.first,
            setOf(FindingCode.ICC_MALFORMED),
            shiftedFile.second,
        )
        add(
            "complete-jpeg-inside-a-quantisation-segment",
            patched { beforeScan.add(JpegFixtures.segment(0xDB, JpegFixtures.syntheticJpeg(8, 8))) },
            setOf(FindingCode.UNPARSEABLE),
        )
        add(
            "quantisation-segment-runs-past-its-table",
            patched { beforeScan.add(JpegFixtures.segment(0xDB, table(0x02, 64) + ByteArray(5))) },
            setOf(FindingCode.UNPARSEABLE),
        )
        add(
            "huffman-table-with-more-than-256-symbols",
            patched {
                beforeScan.add(
                    JpegFixtures.segment(
                        0xC4,
                        byteArrayOf(0x00) + ByteArray(16) { 20 } + ByteArray(320),
                    ),
                )
            },
            setOf(FindingCode.UNPARSEABLE),
        )
        add(
            "frame-header-longer-than-its-components",
            patched {
                val i = coding.indexOfFirst { (it[1].toInt() and 0xFF) in 0xC0..0xC2 }
                val sof = coding[i]
                val longer = sof + ByteArray(17)
                val len = ((sof[2].toInt() and 0xFF) shl 8 or (sof[3].toInt() and 0xFF)) + 17
                longer[2] = (len shr 8).toByte()
                longer[3] = len.toByte()
                coding[i] = longer
            },
            setOf(FindingCode.UNPARSEABLE),
        )
        add(
            "scan-header-longer-than-its-components",
            patched {
                val len = ((scan[2].toInt() and 0xFF) shl 8 or (scan[3].toInt() and 0xFF)) + 10
                val headerEnd = 2 + ((scan[2].toInt() and 0xFF) shl 8 or (scan[3].toInt() and 0xFF))
                val patchedScan =
                    scan.copyOfRange(0, headerEnd) + ByteArray(10) + scan.copyOfRange(headerEnd, scan.size)
                patchedScan[2] = (len shr 8).toByte()
                patchedScan[3] = len.toByte()
                scan = patchedScan
            },
            setOf(FindingCode.UNPARSEABLE),
        )
        add(
            "restart-interval-segment-of-wrong-length",
            patched { beforeScan.add(JpegFixtures.segment(0xDD, byteArrayOf(0, 8, 0, 0))) },
            setOf(FindingCode.UNPARSEABLE),
        )
        add(
            "jfif-header-of-14-bytes-announcing-a-thumbnail",
            JpegBuilder().strippedExif().apply {
                includeJfif = false
                val payload = "JFIF\u0000".toByteArray() + byteArrayOf(1, 2, 0, 0, 1, 0, 1, 1, 1)
                beforeJfif.add(JpegFixtures.segment(0xE0, payload))
            }.build(),
            setOf(FindingCode.JFIF_THUMBNAIL),
        )
        add("not-a-jpeg-at-all", "GIF89a-not-a-jpeg".toByteArray(), setOf(FindingCode.NOT_JPEG))
        val clean = JpegBuilder().strippedExif().build()
        add(
            "file-cut-inside-the-coding-segments",
            clean.copyOf(clean.indexOfFirst { it == 0xDB.toByte() } + 20),
            setOf(FindingCode.UNPARSEABLE),
        )

        // --- EXIF details
        add("exif-segment-one-byte-over-the-limit", exifOfSize(1025), setOf(FindingCode.EXIF_TOO_LARGE))
        add(
            "non-zero-bytes-behind-an-inline-value",
            withExif(
                strip { t ->
                    t.ifd0.clear()
                    t.ifd0.add(Entry(0x0112, 3, 1, byteArrayOf(1, 0, 0x41, 0x42)))
                },
            ),
            setOf(FindingCode.EXIF_UNREFERENCED_BYTES),
        )
        add(
            "exif-entry-with-unknown-value-type",
            withExif(strip { t -> t.ifd0.add(Entry(0x010F, 99, 1, byteArrayOf(0, 0, 0, 0))) }),
            setOf(FindingCode.EXIF_VALUE_INVALID, FindingCode.EXIF_TAG_NOT_ALLOWED),
        )
        add(
            "exif-interoperability-ifd",
            withExif(
                strip { t ->
                    t.exif = mutableListOf(t.ascii(0x9003, "2026:01:02 03:04:05"))
                    t.interop = mutableListOf(t.ascii(0x0001, "R98"))
                },
            ),
            setOf(FindingCode.EXIF_SUBIFD_NOT_ALLOWED, FindingCode.EXIF_INTEROP_NOT_ALLOWED),
        )
        add(
            "exif-byte-order-mark-is-garbage",
            tiffPatched(strip { }) { tiff, bytes -> bytes[tiff] = 'X'.code.toByte() },
            setOf(FindingCode.EXIF_BAD_HEADER),
        )
        add(
            "exif-ifd0-links-to-itself",
            tiffPatched(strip { }) { tiff, bytes -> bytes[tiff + 8 + 2 + 12] = 8 },
            setOf(FindingCode.EXIF_THUMBNAIL_IFD1, FindingCode.EXIF_UNPARSEABLE),
        )
        add(
            "exif-pointer-with-the-wrong-value-type",
            tiffPatched(strip { t -> t.exif = mutableListOf(t.ascii(0x9003, "2026:01:02 03:04:05")) }) { tiff, bytes ->
                bytes[tiff + 8 + 2 + 12 + 2] = 3
            },
            setOf(FindingCode.EXIF_SUBIFD_NOT_ALLOWED, FindingCode.EXIF_VALUE_INVALID),
        )
        return out
    }
}
