// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.filemode

import io.github.shishark.faceblur.MetadataPolicy

/** An unsigned EXIF RATIONAL: numerator and denominator in 0..2³²−1, denominator > 0. */
internal class Rational(val numerator: Long, val denominator: Long) {
    val isValid: Boolean get() = numerator in 0..U32_MAX && denominator in 1..U32_MAX
    val value: Double get() = numerator.toDouble() / denominator.toDouble()

    override fun equals(other: Any?): Boolean =
        other is Rational && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = (numerator * 31 + denominator).hashCode()

    override fun toString(): String = "$numerator/$denominator"

    companion object {
        const val U32_MAX: Long = 0xFFFF_FFFFL
    }
}

/** GPS values exactly as the source has them (never rounded or coarsened). Built only through [PhotoMetadata.of]. */
internal class GpsMetadata internal constructor(
    val latitudeRef: Char,
    val latitude: List<Rational>,
    val longitudeRef: Char,
    val longitude: List<Rational>,
    val altitudeRef: Int?,
    val altitude: Rational?,
    val timeStamp: List<Rational>?,
    val dateStamp: String?,
    val horizontalError: Rational?,
    val imgDirectionRef: Char?,
    val imgDirection: Rational?,
)

/**
 * The photo metadata file mode may write, as typed values that are validated one by one (ADR 0005 decision 3; never
 * raw bytes). A value that fails its check is dropped, never repaired or truncated; dropping a value never weakens
 * protection. Platform readers fill it from the source; [restrictTo] then keeps only the groups the policy names.
 */
internal class PhotoMetadata private constructor(
    val make: String?,
    val model: String?,
    val dateTimeOriginal: String?,
    val dateTimeDigitized: String?,
    val offsetTimeOriginal: String?,
    val offsetTimeDigitized: String?,
    val subSecTimeOriginal: String?,
    val gps: GpsMetadata?,
) {
    val hasCamera: Boolean get() = make != null || model != null
    val hasDateTime: Boolean
        get() =
            dateTimeOriginal != null || dateTimeDigitized != null || offsetTimeOriginal != null ||
                offsetTimeDigitized != null || subSecTimeOriginal != null

    /** Only the groups that [policy] names; `null` when nothing remains (and always for [MetadataPolicy.StripAll]). */
    fun restrictTo(policy: MetadataPolicy): PhotoMetadata? {
        if (policy !is MetadataPolicy.KeepAllowlist) return null
        val kept =
            PhotoMetadata(
                make = make.takeIf { policy.camera },
                model = model.takeIf { policy.camera },
                dateTimeOriginal = dateTimeOriginal.takeIf { policy.dateTime },
                dateTimeDigitized = dateTimeDigitized.takeIf { policy.dateTime },
                offsetTimeOriginal = offsetTimeOriginal.takeIf { policy.dateTime },
                offsetTimeDigitized = offsetTimeDigitized.takeIf { policy.dateTime },
                subSecTimeOriginal = subSecTimeOriginal.takeIf { policy.dateTime },
                gps = gps.takeIf { policy.gps },
            )
        return kept.takeIf { it.hasCamera || it.hasDateTime || it.gps != null }
    }

    companion object {
        const val MAX_TEXT = 64

        // ASCII digits only: `[0-9]`, never `\d`, whose meaning (Unicode digits or not) differs between platforms.
        private val DATE_TIME = Regex("""([0-9]{4}):([0-9]{2}):([0-9]{2}) ([0-9]{2}):([0-9]{2}):([0-9]{2})""")
        private val DATE = Regex("""([0-9]{4}):([0-9]{2}):([0-9]{2})""")
        private val OFFSET = Regex("""[+-]([0-9]{2}):([0-9]{2})""")
        private val SUB_SEC = Regex("""[0-9]{1,9}""")

        /**
         * Validates every value independently and keeps the valid ones. Strings longer than the limits are rejected
         * before any pattern matching, so a 5 MB value costs one length check.
         */
        fun of(
            make: String? = null,
            model: String? = null,
            dateTimeOriginal: String? = null,
            dateTimeDigitized: String? = null,
            offsetTimeOriginal: String? = null,
            offsetTimeDigitized: String? = null,
            subSecTimeOriginal: String? = null,
            latitudeRef: Char? = null,
            latitude: List<Rational>? = null,
            longitudeRef: Char? = null,
            longitude: List<Rational>? = null,
            altitudeRef: Int? = null,
            altitude: Rational? = null,
            gpsTimeStamp: List<Rational>? = null,
            gpsDateStamp: String? = null,
            horizontalError: Rational? = null,
            imgDirectionRef: Char? = null,
            imgDirection: Rational? = null,
        ): PhotoMetadata {
            val gps =
                if (latitudeRef == null || latitude == null || longitudeRef == null || longitude == null) {
                    null
                } else if (latitudeRef !in setOf('N', 'S') || longitudeRef !in setOf('E', 'W') ||
                    !validDms(latitude, 90.0) || !validDms(longitude, 180.0)
                ) {
                    null
                } else {
                    val altitudeOk = altitudeRef in setOf(0, 1) && altitude != null && altitude.isValid
                    val directionOk =
                        imgDirectionRef in setOf('T', 'M') && imgDirection != null && imgDirection.isValid &&
                            imgDirection.value < 360.0
                    GpsMetadata(
                        latitudeRef = latitudeRef,
                        latitude = latitude.toList(),
                        longitudeRef = longitudeRef,
                        longitude = longitude.toList(),
                        altitudeRef = altitudeRef.takeIf { altitudeOk },
                        altitude = altitude.takeIf { altitudeOk },
                        timeStamp = gpsTimeStamp?.takeIf { validTime(it) }?.toList(),
                        dateStamp = gpsDateStamp?.takeIf { validDate(it) },
                        horizontalError = horizontalError?.takeIf { it.isValid },
                        imgDirectionRef = imgDirectionRef.takeIf { directionOk },
                        imgDirection = imgDirection.takeIf { directionOk },
                    )
                }
            return PhotoMetadata(
                make = make?.takeIf { validText(it) },
                model = model?.takeIf { validText(it) },
                dateTimeOriginal = dateTimeOriginal?.takeIf { validDateTime(it) },
                dateTimeDigitized = dateTimeDigitized?.takeIf { validDateTime(it) },
                offsetTimeOriginal = offsetTimeOriginal?.takeIf { validOffset(it) },
                offsetTimeDigitized = offsetTimeDigitized?.takeIf { validOffset(it) },
                subSecTimeOriginal = subSecTimeOriginal?.takeIf { it.length <= 9 && SUB_SEC.matches(it) },
                gps = gps,
            )
        }

        /** Printable ASCII (0x20..0x7E), 1 to 64 characters. */
        fun validText(s: String): Boolean = s.length in 1..MAX_TEXT && s.all { it.code in 0x20..0x7E }

        fun validDateTime(s: String): Boolean {
            if (s.length != 19) return false
            val m = DATE_TIME.matchEntire(s) ?: return false
            val v = m.groupValues.drop(1).map { it.toInt() }
            return validDay(v[0], v[1], v[2]) && v[3] in 0..23 && v[4] in 0..59 && v[5] in 0..60
        }

        fun validDate(s: String): Boolean {
            if (s.length != 10) return false
            val m = DATE.matchEntire(s) ?: return false
            val v = m.groupValues.drop(1).map { it.toInt() }
            return validDay(v[0], v[1], v[2])
        }

        /** A real calendar day (Gregorian leap years): 2024:02:29 is valid, 2026:02:29 and 2026:04:31 are not. */
        private fun validDay(year: Int, month: Int, day: Int): Boolean {
            if (month !in 1..12) return false
            val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
            val days = when (month) {
                2 -> if (leap) 29 else 28
                4, 6, 9, 11 -> 30
                else -> 31
            }
            return day in 1..days
        }

        fun validOffset(s: String): Boolean {
            if (s.length != 6) return false
            val m = OFFSET.matchEntire(s) ?: return false
            return m.groupValues[1].toInt() in 0..14 && m.groupValues[2].toInt() in 0..59
        }

        /** Degrees, minutes, seconds: three valid rationals, minutes and seconds below 60, total ≤ [maxDegrees]. */
        fun validDms(dms: List<Rational>?, maxDegrees: Double): Boolean {
            if (dms == null || dms.size != 3 || dms.any { !it.isValid }) return false
            val (d, m, s) = dms.map { it.value }
            // Rationals are non-negative, so the total bound also bounds the degrees.
            return m < 60.0 && s < 60.0 && d + m / 60.0 + s / 3600.0 <= maxDegrees
        }

        fun validTime(t: List<Rational>): Boolean {
            if (t.size != 3 || t.any { !it.isValid }) return false
            return t[0].value < 24.0 && t[1].value < 60.0 && t[2].value < 61.0
        }
    }
}
