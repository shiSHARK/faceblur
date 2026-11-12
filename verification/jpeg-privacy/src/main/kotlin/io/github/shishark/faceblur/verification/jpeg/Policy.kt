// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

/** The IFD an EXIF tag lives in. */
public enum class ExifIfd { IFD0, EXIF, GPS, INTEROP, IFD1 }

/** A tag in an IFD. */
public data class ExifTag(val ifd: ExifIfd, val id: Int) {
    override fun toString(): String = "${ifd.name}:${hex(id)}"
}

/**
 * The metadata the output may carry (ADR 0005 decision 3). The tables below are this checker's own copy of
 * the closed tag list, so a change in the library cannot silently change what the checker accepts.
 */
public sealed interface MetadataPolicy {
    /** Default policy: exactly one EXIF segment whose IFD0 holds only Orientation = 1. */
    public data object StripAll : MetadataPolicy

    /**
     * Opt-in policy: the named groups may appear (fresh values of the closed tag list). [requiredTags] are the
     * allowlisted tags the INPUT carried; the output must contain them (failure when missing). An allowlisted
     * tag that is absent and not required is reported as information only. [withRequiredFrom] fills it in.
     */
    public data class KeepAllowlist(
        val gps: Boolean = false,
        val dateTime: Boolean = false,
        val camera: Boolean = false,
        val requiredTags: Set<ExifTag> = emptySet(),
    ) : MetadataPolicy {
        init {
            require(gps || dateTime || camera) { "an allowlist must name at least one group" }
        }

        /**
         * Returns this policy with [requiredTags] = allowlisted tags that the [input] file carries.
         *
         * Throws [IllegalArgumentException] if the input's EXIF cannot be read completely: the expected tags
         * cannot be derived then, and silently expecting fewer tags would turn the check into a pass.
         */
        public fun withRequiredFrom(input: ByteArray): KeepAllowlist {
            val everything = KeepAllowlist(gps = true, dateTime = true, camera = true)
            val report = PrivacyChecker.check(input, CheckOptions(policy = everything, requireExif = false))
            require(!report.isUnparseable) {
                "cannot derive the expected tags: the input is unparseable (" + report.failureCodes.joinToString() + ")"
            }
            return copy(
                requiredTags = report.exifTags.filter {
                    it in allowedTags(this) && it.id != ORIENTATION
                }.toSet(),
            )
        }
    }
}

/** What the checker is asked to assert. Defaults are the strict ADR 0005 shape. */
public class CheckOptions(
    public val policy: MetadataPolicy = MetadataPolicy.StripAll,
    /** Byte strings that must not occur anywhere in the output (unique canaries planted in the input). */
    public val canaries: List<ByteArray> = emptyList(),
    /** Colour profile of the source file; the output must not carry it (source profiles are never copied). */
    public val sourceIccProfile: ByteArray? = null,
    /**
     * SHA-256 values (lower-case hex) of the reviewed ICC profiles, the platform encoders' sRGB profiles. An ICC
     * profile whose hash is not in this set fails (`ICC_NOT_ON_ALLOWLIST`), so with the default empty set any
     * ICC profile fails: a profile can carry free text, so only a reviewed profile is private. Tests that expect
     * an ICC segment must pass the reviewed hash; `measureIccOnly` is for obtaining that hash.
     */
    public val allowedIccSha256: Set<String> = emptySet(),
    /**
     * Only for measuring the hash of a platform encoder's profile (ICC structure is still checked and the hash is
     * in the report): skips the reviewed-list check. Never set it in a privacy test.
     */
    public val measureIccOnly: Boolean = false,
    /** Largest EXIF segment including marker and length field (ADR 0005 decision 6). */
    public val maxExifSegmentBytes: Int = 1024,
    /** ADR 0005 decision 3: the output always carries one EXIF segment (Orientation = 1). */
    public val requireExif: Boolean = true,
    /** ADR 0005 decision 1: JFIF, EXIF, ICC, then the coding segments. */
    public val enforceSegmentOrder: Boolean = true,
    /**
     * Decode the image with the JDK decoder after the structural checks pass; any decoder warning (for example data
     * between the last block and the EOI marker) or failure is a finding. Large images are decoded sub-sampled; an
     * image too large even for that fails with `DECODE_SKIPPED`. Switch it off explicitly or not at all.
     */
    public val decodeCheck: Boolean = true,
    /** Images with more pixels are decoded with sub-sampling (the decoder still reads all the data) to bound memory. */
    public val maxDecodePixels: Long = 36_000_000L,
    /** Images with more pixels are not decoded at all; that is a FAILURE (`DECODE_SKIPPED`), not a silent pass. */
    public val maxSubsampledDecodePixels: Long = 256_000_000L,
    public val allowJfif: Boolean = true,
    public val allowIcc: Boolean = true,
    /** Files above this size are reported as unparseable instead of being read. */
    public val maxFileBytes: Long = 64L * 1024 * 1024,
)

internal const val ORIENTATION = 0x0112
internal const val EXIF_IFD_POINTER = 0x8769
internal const val GPS_IFD_POINTER = 0x8825
internal const val INTEROP_IFD_POINTER = 0xA005
internal const val MAKER_NOTE = 0x927C

internal fun hex(id: Int): String = "0x" + id.toString(16).uppercase().padStart(4, '0')

private val CAMERA_TAGS = setOf(0x010F, 0x0110)
private val DATE_TIME_TAGS = setOf(0x9003, 0x9004, 0x9011, 0x9012, 0x9291)
private val GPS_TAGS = setOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x10, 0x11, 0x1D, 0x1F)

/** Tags the policy allows in the output, pointer tags excluded (those are decided per IFD). */
internal fun allowedTags(policy: MetadataPolicy): Set<ExifTag> {
    val tags = mutableSetOf(ExifTag(ExifIfd.IFD0, ORIENTATION))
    if (policy is MetadataPolicy.KeepAllowlist) {
        if (policy.camera) CAMERA_TAGS.mapTo(tags) { ExifTag(ExifIfd.IFD0, it) }
        if (policy.dateTime) DATE_TIME_TAGS.mapTo(tags) { ExifTag(ExifIfd.EXIF, it) }
        if (policy.gps) GPS_TAGS.mapTo(tags) { ExifTag(ExifIfd.GPS, it) }
    }
    return tags
}

internal fun allowsExifIfd(policy: MetadataPolicy): Boolean = policy is MetadataPolicy.KeepAllowlist && policy.dateTime

internal fun allowsGpsIfd(policy: MetadataPolicy): Boolean = policy is MetadataPolicy.KeepAllowlist && policy.gps
