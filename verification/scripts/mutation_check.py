#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Hand-written mutation check of the privacy checker (repeatable).

Applies one mutant at a time to the checker sources (or the build files), runs the suite, and records the
names of the tests that FAIL (read from the JUnit XML, not from console text). A mutant is KILLED when at
least one test fails; COMPILE-ERROR means the mutant is invalid (not counted as killed); SURVIVED means no
test noticed. The original file is always restored, and `git diff` of the verification folder must be empty
afterwards (checked at the end when run inside a git work tree).

usage: python3 -I verification/scripts/mutation_check.py [--out FILE.tsv] [MUTANT-ID ...]
Runs from any directory; needs JDK 17 and network access to the Maven caches the build already uses.
Mutants whose id starts with PX- or BD-PX mutate the pixel oracle (module pixel-oracle), all others the JPEG checker.
Exit code 0 only if at least one mutant ran, every given id exists, every mutant that ran was KILLED, and the suites
are green after the files are restored (1 = survivor or invalid mutant, 3 = baseline red, 4 = files not restored,
5 = no mutant ran, 6 = unknown id).
"""
import glob
import hashlib
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
VERIF = os.path.dirname(HERE)
REPO = os.path.dirname(VERIF)
MAIN = os.path.join(VERIF, "jpeg-privacy/src/main/kotlin/io/github/shishark/faceblur/verification/jpeg")
PIXEL_MAIN = os.path.join(VERIF, "pixel-oracle/src/main/kotlin/io/github/shishark/faceblur/verification/pixel")
BUILD_FILE = os.path.join(VERIF, "jpeg-privacy/build.gradle.kts")
PIXEL_BUILD_FILE = os.path.join(VERIF, "pixel-oracle/build.gradle.kts")
METADATA = os.path.join(VERIF, "gradle/verification-metadata.xml")
RESULTS = {
    "jpeg-privacy": os.path.join(VERIF, "jpeg-privacy/build/test-results/test"),
    "pixel-oracle": os.path.join(VERIF, "pixel-oracle/build/test-results/test"),
}

# (id, description, file, old text (first occurrence), new text)
SOURCE_MUTANTS = [
    ("MC-01", "GPS IFD pointer never reported", "ExifInspector.kt",
     "GPS_IFD_POINTER -> if (!allowsGpsIfd(policy)) {", "GPS_IFD_POINTER -> if (false) {"),
    ("MC-02", "bytes after EOI never reported", "PrivacyChecker.kt",
     'fail(FindingCode.TRAILING_BYTES,', 'if (false) fail(FindingCode.TRAILING_BYTES,'),
    ("MC-03", "Orientation value not compared with 1", "ExifInspector.kt",
     "if (value != 1) {", "if (value != value) {"),
    ("MC-04", "IFD1 (thumbnail) not reported", "ExifInspector.kt",
     "report(\n                    FindingCode.EXIF_THUMBNAIL_IFD1,", "if (false) report(\n                    FindingCode.EXIF_THUMBNAIL_IFD1,"),
    ("MC-05", "XMP header not recognised (body text still is)", "PrivacyChecker.kt",
     'startsWith(payload, size, "http://ns.adobe.com/xap/1.0/") ||', "false ||"),
    ("MC-06", "required tag missing ignored", "ExifInspector.kt",
     "if (required !in tags) {", "if (false) {"),
    ("MC-07", "EXIF size limit raised by 100000", "PrivacyChecker.kt",
     "if (total > options.maxExifSegmentBytes) {", "if (total > options.maxExifSegmentBytes + 100000) {"),
    ("MC-08", "embedded JPEG signature not searched", "PrivacyChecker.kt",
     "if (kind !in CODING_KINDS && contains(payload, size, JPEG_SIGNATURE)) {", "if (false) {"),
    ("MC-09", "ICC copy from the source not detected", "PrivacyChecker.kt",
     "if (source != null && source.size == to - from && sha256Hex(source) == result.sha256) {", "if (false) {"),
    ("MC-10", "canaries not searched", "PrivacyChecker.kt",
     "            searchCanaries()\n", "            Unit\n"),
    ("MC-11", "unreferenced EXIF bytes ignored", "ExifInspector.kt",
     "if (!covered[i] && data[tiffStart + i].toInt() != 0) nonZero++", "if (false) nonZero++"),
    ("MC-12", "EXIF value validators skipped", "ExifInspector.kt",
     "val problem = spec.validate?.invoke(RawValue(valueOffset, count.toInt()))", "val problem: String? = null"),
    ("MC-13", "second SOI not reported", "PrivacyChecker.kt",
     "fail(FindingCode.SECOND_SOI,", "if (false) fail(FindingCode.SECOND_SOI,"),
    ("MC-14", "MPF segment not reported", "PrivacyChecker.kt",
     "SegmentKind.MPF -> fail(\n                FindingCode.MPF_PRESENT,", "SegmentKind.MPF -> if (false) fail(\n                FindingCode.MPF_PRESENT,"),
    ("MC-15", "COM segment not reported", "PrivacyChecker.kt",
     "SegmentKind.COM -> fail(FindingCode.COM_PRESENT,", "SegmentKind.COM -> if (false) fail(FindingCode.COM_PRESENT,"),
    ("MC-16", "IPTC APP13 not reported", "PrivacyChecker.kt",
     "SegmentKind.IPTC -> fail(FindingCode.IPTC_APP13,", "SegmentKind.IPTC -> if (false) fail(FindingCode.IPTC_APP13,"),
    ("MC-17", "missing EXIF not reported", "PrivacyChecker.kt",
     "if (options.requireExif && exifCount == 0) {", "if (false) {"),
    ("MC-18", "EXIF value offset bound check removed", "ExifInspector.kt",
     "if (offset < 8 || offset + byteCount > tiffLength) {", "if (false) {"),
    ("MC-19", "EXIF entry count bound check removed", "ExifInspector.kt",
     "if (end > tiffLength) throw ExifMalformed", "if (false) throw ExifMalformed"),
    ("MC-20", "MakerNote tag not reported", "ExifInspector.kt",
     "if (tag == MAKER_NOTE) {", "if (false) {"),
    ("MC-21", "duplicate EXIF tag not reported", "ExifInspector.kt",
     "if (!seen.add(tag)) {", "if (false) {"),
    ("MC-22", "non-allowlisted EXIF tag accepted", "ExifInspector.kt",
     "} else if (!gatedAndRefused) {", "} else if (false) {"),
    ("MC-23", "missing EOI not reported", "PrivacyChecker.kt",
     'fail(FindingCode.NO_EOI, "file ends without an EOI marker", pos)', "Unit"),
    # Mutants from the code review (M-A .. M-J)
    ("RV-A", "non-zero bytes after an inline EXIF value not reported", "ExifInspector.kt",
     "if (hidden) {", "if (false) {"),
    ("RV-B", "EXIF limit raised by 100 bytes", "PrivacyChecker.kt",
     "if (total > options.maxExifSegmentBytes) {", "if (total > options.maxExifSegmentBytes + 100) {"),
    ("RV-C", "NO_SCAN never reported", "PrivacyChecker.kt",
     "if (scans == 0) fail(FindingCode.NO_SCAN,", "if (false) fail(FindingCode.NO_SCAN,"),
    ("RV-D", "MULTIPLE_FRAMES never reported", "PrivacyChecker.kt",
     "if (frames > 1) fail(FindingCode.MULTIPLE_FRAMES,", "if (false) fail(FindingCode.MULTIPLE_FRAMES,"),
    ("RV-E", "any marker inside scan data other than EOI skipped", "PrivacyChecker.kt",
     "else -> return p", "else -> if (next == 0xD9) return p else p += 2"),
    ("RV-F", "GPS minutes range not checked", "ExifInspector.kt",
     "m >= 60 ||", ""),
    ("RV-G", "JFIF thumbnail dimensions not checked", "PrivacyChecker.kt",
     " || u8(payload + 12) != 0 || u8(payload + 13) != 0", ""),
    ("RV-H", "unknown EXIF value type accepted silently", "ExifInspector.kt",
     'report(FindingCode.EXIF_VALUE_INVALID, Severity.FAIL, "$tag has an unknown value type")', "Unit"),
    ("RV-I", "stray RST/TEM markers outside scan data accepted", "PrivacyChecker.kt",
     'fail(FindingCode.UNEXPECTED_MARKER, "marker ${hex(marker)} outside entropy data", markerPos)', "Unit"),
    ("RV-J", "second ICC segment accepted", "PrivacyChecker.kt",
     "if (iccCount > 1) fail(FindingCode.ICC_MULTIPLE_SEGMENTS,", "if (false) fail(FindingCode.ICC_MULTIPLE_SEGMENTS,"),
    # Fix round
    ("FX-01", "marker cap removed (B-01)", "PrivacyChecker.kt",
     "if (++markers > PrivacyChecker.MAX_MARKERS) {", "if (++markers < 0) {"),
    ("FX-02", "findings cap removed (B-01)", "PrivacyChecker.kt",
     "if (findings.size >= PrivacyChecker.MAX_FINDINGS) {", "if (findings.size < 0) {"),
    ("FX-03", "withRequiredFrom accepts an unparseable input (M-01)", "Policy.kt",
     "require(!report.isUnparseable) {", "require(true) {"),
    ("FX-04", "frame header length not checked (F-02)", "CodingSegments.kt",
     "if (count !in 1..MAX_COMPONENTS || size != 6 + 3 * count) {", "if (false) {"),
    ("FX-05", "scan header length not checked (F-02)", "CodingSegments.kt",
     "if (count !in 1..MAX_COMPONENTS || size != 4 + 2 * count) {", "if (false) {"),
    ("FX-06", "restart interval length not checked (F-02)", "CodingSegments.kt",
     "if (size != 2) throw", "if (false) throw"),
    ("FX-07", "table defined twice before the first scan accepted (F-02)", "CodingSegments.kt",
     "if (scansSoFar == 0 && key in defined) {", "if (false) {"),
    ("FX-08", "unused quantisation table accepted (F-02)", "CodingSegments.kt",
     "if (unusedQuant.isNotEmpty()) {", "if (false) {"),
    ("FX-09", "unused Huffman table accepted (F-02)", "CodingSegments.kt",
     "if (scans > 0 && unusedHuffman.isNotEmpty()) {", "if (false) {"),
    ("FX-10", "table definition cap removed (F-02)", "CodingSegments.kt",
     "if (total > limit) throw", "if (false) throw"),
    ("FX-11", "Huffman symbol count not bounded (F-02)", "CodingSegments.kt",
     "if (symbols >\n                MAX_SYMBOLS\n            ) {", "if (false) {"),
    ("FX-12", "ICC not searched for an embedded JPEG (F-01)", "PrivacyChecker.kt",
     "if (kind !in CODING_KINDS && contains(", "if (kind !in CODING_KINDS && kind != SegmentKind.ICC && contains("),
    ("FX-13", "ICC reviewed-hash list ignored (F-01)", "PrivacyChecker.kt",
     "result.sha256 !in options.allowedIccSha256", "false"),
    ("FX-14", "ICC bytes outside any tag accepted (F-01)", "IccInspector.kt",
     "if (hidden > 0) {", "if (false) {"),
    ("FX-15", "ICC header reserved bytes not checked (F-01)", "IccInspector.kt",
     "for (i in RESERVED_FROM until HEADER_SIZE) {", "for (i in 0 until 0) {"),
    ("FX-16", "UTF-16 canaries not searched", "PrivacyChecker.kt",
     "text.toByteArray(Charsets.UTF_16BE), text.toByteArray(Charsets.UTF_16LE)", "canary, canary"),
    ("FX-17", "assertIccIdenticalAcrossOutputs accepts fewer than two reports", "PrivacyChecker.kt",
     "require(reports.size >= 2)", "require(true)"),
    ("FX-18", "incomplete source ICC chunks accepted", "PrivacyChecker.kt",
     "chunks.size == count &&", "true ||"),
    ("FX-19", "EXIF pointer type/count not checked", "ExifInspector.kt",
     "if ((type != 4 && type != 13) || valueCount != 1L) {", "if (false) {"),
    ("FX-20", "calendar not checked (day always 1..31)", "ExifInspector.kt",
     "day in 1..java.time.YearMonth.of(year, month).lengthOfMonth()", "day in 1..31"),
    ("FX-21", "GPS altitude / error bound removed", "ExifInspector.kt",
     "MAX_METRES = 100000.0", "MAX_METRES = 1.0e15"),
    ("FX-22", "CLI prints the file name (F-03)", "Main.kt",
     'out.println("PASS $label segments=', 'out.println("PASS ${Path.of(file).fileName} segments='),
    ("FX-23", "CLI does not catch I/O errors (F-03)", "Main.kt",
     "} catch (e: IOException) {", "} catch (e: java.io.EOFException) {"),
    ("FX-24", "file size cap ignored", "PrivacyChecker.kt",
     "if (size > options.maxFileBytes) {", "if (false) {"),
    ("FX-25", "Orientation tag missing not reported", "ExifInspector.kt",
     "if (orientation == null) {", "if (false) {"),
    ("FX-26", "ICC measuring mode always on", "PrivacyChecker.kt",
     "if (!options.measureIccOnly &&", "if (false &&"),
    ("FX-27", "decoder warnings ignored", "PrivacyChecker.kt",
     "} else if (result.warnings.isNotEmpty()) {", "} else if (false) {"),
    ("FX-28", "decoder failure ignored", "PrivacyChecker.kt",
     "if (result.failure != null) {", "if (false) {"),
    ("FX-29", "tables defined after the last scan accepted", "CodingSegments.kt",
     "definitionScanIndex.any { it == scans }", "definitionScanIndex.any { false }"),
    ("FX-30", "scan naming an unknown component accepted", "CodingSegments.kt",
     "if (frameSeen && id !in components) {", "if (false) {"),
    ("FX-31", "DQT precision and id not checked", "CodingSegments.kt",
     "if (precision > 1 ||\n                id > 3\n            ) {", "if (false) {"),
    ("FX-32", "DHT class and id not checked", "CodingSegments.kt",
     "if (tableClass > 1 ||\n                id > 3\n            ) {", "if (false) {"),
    ("FX-33", "ICC tag offset inside the header or table accepted", "IccInspector.kt",
     "if (offset < tableEnd || offset + size > length) {", "if (offset + size > length) {"),
    ("FX-34", "ICC chunk count consistency not checked", "PrivacyChecker.kt",
     "&& chunks.all { it.second == count }", ""),
    ("FX-35", "--measure-icc changes the verdict (it must only add the hash)", "Main.kt",
     "allowedIccSha256 = iccHashes,\n    )", "allowedIccSha256 = iccHashes,\n        measureIccOnly = measure,\n    )"),
    ("FX-37", "the sub-sampled decode is not done (large images pass unchecked)", "PrivacyChecker.kt",
     "val result = JdkDecodeCheck.run(data, subsampling)", "if (subsampling > 1) return\n        val result = JdkDecodeCheck.run(data, subsampling)"),
    ("FX-38", "images above every decode limit pass", "PrivacyChecker.kt",
     "if (pixels > options.maxSubsampledDecodePixels) {", "if (false) {"),
]

PIXEL_MUTANTS = [
    ("PX-01", "pixel-corner radius instead of pixel-centre radius", "MaskEllipse.kt",
     "(x + 0.5 - cx) / rx", "(x - cx) / rx"),
    ("PX-02", "no shift up by 8 % of the height", "MaskEllipse.kt",
     "top + height / 2 - SHIFT_UP * height", "top + height / 2"),
    ("PX-03", "horizontal expansion 1.55 instead of 1.6", "MaskEllipse.kt",
     "EXPAND_X: Double = 1.6", "EXPAND_X: Double = 1.55"),
    ("PX-04", "soft edge not widened to one pixel for small masks", "MaskEllipse.kt",
     "max(feather, 1.0 / smallerSemiAxis)", "feather"),
    ("PX-05", "soft edge 0.20 instead of 0.10", "MaskEllipse.kt",
     "SOFT_EDGE: Double = 0.10", "SOFT_EDGE: Double = 0.20"),
    ("PX-06", "unchangedOutside does not compare", "PixelOracle.kt",
     "if (input[x, y] != output[x, y]) bad.hit(x, y)", "if (false) bad.hit(x, y)"),
    ("PX-07", "insideOpaque does not check alpha", "PixelOracle.kt",
     "if ((output[x, y] ushr 24) != 0xFF) bad.hit(x, y)", "if (false) bad.hit(x, y)"),
    ("PX-08", "insideColourField does not compare", "PixelOracle.kt",
     "if (channelDelta(output[x, y], expected(x, y)) > maxChannelDelta) bad.hit(x, y)", "if (false) bad.hit(x, y)"),
    ("PX-10", "alpha 255 allowed far beyond r = 1 (weight bound 0.05 -> 0.5)", "PixelOracle.kt",
     "FULL_WEIGHT_MAX_T: Double = 0.05", "FULL_WEIGHT_MAX_T: Double = 0.5"),
    ("PX-11", "soft-edge opacity may rise by 40 levels (tolerance 1 -> 40)", "PixelOracle.kt",
     "ROUNDING_LEVELS: Int = 1", "ROUNDING_LEVELS: Int = 40"),
    ("PX-12", "identicalBytes ignores equal-length differences", "PixelOracle.kt",
     "if (i !=\n                null\n            ) {", "if (false) {"),
    ("PX-13", "pair precondition: difference outside the masks accepted", "PixelOracle.kt",
     "if (zones.at(x, y) != Zones.INSIDE) {", "if (false) {"),
    ("PX-14", "pair precondition: MCU margin not checked", "PixelOracle.kt",
     "} else if (mcuSize != null) {", "} else if (mcuSize != null && false) {"),
    ("PX-15", "identical inputs accepted as a pair", "PixelOracle.kt",
     "if (differing == 0)", "if (false)"),
    ("PX-16", "differential outputs not compared", "PixelOracle.kt",
     "if (channelDelta(outputA[x, y], outputB[x, y]) > maxChannelDelta) bad.hit(x, y)", "if (false) bad.hit(x, y)"),
    ("PX-17", "MCU margin only on one side", "PixelOracle.kt",
     "val x0 = (bx - 1) * size", "val x0 = bx * size"),
    ("PX-18", "masked area position not checked", "PixelOracle.kt",
     "abs(measuredCx - expected.cx) > positionTolerancePx ||", "false ||"),
    ("PX-19", "masked area size not checked", "PixelOracle.kt",
     "abs(measuredRx - expected.rx) > semiAxisTolerancePx ||", "false ||"),
    ("PX-20", "buffer after an error not compared", "PixelOracle.kt",
     "if (before[x, y] != after[x, y]) bad.hit(x, y)", "if (false) bad.hit(x, y)"),
    ("PX-21", "partly masked result accepted", "PixelOracle.kt",
     "if (partlyApplied > 0 || (fullyApplied > 0 && notApplied > 0)) {", "if (false) {"),
    ("PX-22", "a check that judged nothing passes", "PixelOracle.kt",
     "if (judged == 0L) add(", "if (false) add("),
    ("PX-23", "images of different size are compared", "PixelOracle.kt",
     "if (a.width == b.width && a.height == b.height) return true", "if (true) return true"),
    ("PX-24", "boundary pixels (r = 1) are judged as inside", "PixelOracle.kt",
     "r <= 1.0 - eps -> INSIDE", "r <= 1.0 -> INSIDE"),
    ("PX-25", "soft edge reaches 5 radii further", "PixelOracle.kt",
     "r <= outer - eps -> SOFT", "r <= outer + 5.0 -> SOFT"),
    ("PX-26", "decoder pixel limit removed", "Raster.kt",
     "w.toLong() * h > MAX_PIXELS", "false"),
    ("PX-27", "decoder failures are not turned into UndecodableImageException", "Raster.kt",
     "} catch (e: IOException) {", "} catch (e: java.io.EOFException) {"),
    ("PX-28", "oracle imports library code", "Raster.kt",
     "package io.github.shishark.faceblur.verification.pixel\n",
     "package io.github.shishark.faceblur.verification.pixel\n\n// import io.github.shishark.faceblur.mask.MaskEngine\n"),
    ("PX-29", "outward-only check does not compare alpha", "PixelOracle.kt",
     "if ((output[x, y] ushr 24) < (input[x, y] ushr 24)) lowered.hit(x, y)", "if (false) lowered.hit(x, y)"),
    ("PX-30", "a missing soft edge passes", "PixelOracle.kt",
     "if (c.judged > 0 &&\n            changed == 0\n        ) {", "if (false) {"),
    ("PX-31", "insideOpaque also judges pixels whose input alpha is already 255", "PixelOracle.kt",
     "z == Zones.INSIDE && (input[x, y] ushr 24) != 0xFF", "z == Zones.INSIDE"),
    ("PX-32", "a face within the colour tolerance is accepted", "PixelOracle.kt",
     "if (c.judged > 0 && indistinguishable > maxIndistinguishableFraction * c.judged) {", "if (false) {"),
    ("PX-33", "Exact fidelity accepted on a lossy output", "PixelOracle.kt",
     "z == Zones.OUTSIDE && fidelity is Fidelity.Exact && source[x, y] != output[x, y]", "false"),
    ("PX-34", "lossy survival threshold ignored (everything counts as masked)", "PixelOracle.kt",
     "<= fidelity.survivalThreshold", "<= fidelity.survivalThreshold - 1000"),
    ("PX-35", "nothing applied is not reported on the success path", "PixelOracle.kt",
     "} else if (notApplied > 0) {", "} else if (false) {"),
    ("PX-36", "masking after a failed call is not reported", "PixelOracle.kt",
     "if (fullyApplied > 0 || partlyApplied > 0) {", "if (false) {"),
    ("PX-37", "a one-pixel pair is accepted", "PixelOracle.kt",
     "} else if (c.findings.isEmpty() && insideCount > 0 &&\n            strongInside.toDouble() / insideCount < minDifferingFraction", "} else if (false"),
    ("PX-38", "a ring over an untouched face is accepted", "PixelOracle.kt",
     "changedInside.toDouble() / insideTotal < minCoverage", "false"),
    ("PX-39", "alpha not 0 beyond the soft edge accepted", "PixelOracle.kt",
     "if (alpha != 0) beyond.hit(x, y)", "if (false) beyond.hit(x, y)"),
    ("PX-40", "alpha below 255 inside accepted by the weight bounds", "PixelOracle.kt",
     "if (alpha != 0xFF) notOpaque.hit(x, y)", "if (false) notOpaque.hit(x, y)"),
    ("PX-41", "full alpha beyond the bound not reported", "PixelOracle.kt",
     "if (alpha == 0xFF && (r - 1.0) / m.softEdgeWidth > FULL_WEIGHT_MAX_T) outOfBounds.hit(x, y)", "Unit"),
    ("PX-42", "Exact survival judged on ARGB (alpha forced to 255 counts as masking)", "PixelOracle.kt",
     "is Fidelity.Exact -> equalRgb(source, output, x, y) &&", "is Fidelity.Exact -> (source[x, y] == output[x, y]) &&"),
    ("PX-43", "lossy texture scan not run", "PixelOracle.kt",
     "if (fidelity is Fidelity.Lossy) scanTexture(fidelity, zones)", "Unit"),
    ("PX-44", "texture correlation threshold ignored", "PixelOracle.kt",
     "if (correlation >= f.minCorrelation) {", "if (false) {"),
    ("PX-45", "flat lossy windows not reported", "PixelOracle.kt",
     "survival.flatWindows.toDouble() / survival.windows > fidelity.maxFlatFraction", "false"),
    ("PX-46", "colour independence evidence ignored by requireMasked", "PixelOracle.kt",
     "report += when (independence) {", "if (false) report += when (independence) {"),
    ("PX-47", "output texture deviation floor ignored", "PixelOracle.kt",
     "if (varO < f.minOutputStdDev * f.minOutputStdDev) continue", "Unit"),
    ("PX-48", "reference texture deviation floor ignored", "PixelOracle.kt",
     "if (varR < f.minReferenceStdDev * f.minReferenceStdDev) {", "if (false) {"),
    ("PX-49", "requireMasked judges the opaque core with the input-dependent check (opaque inputs fail)", "PixelOracle.kt",
     "unchangedOutside(input, output, masks) + insideAlphaIs255(output, masks) +", "unchangedOutside(input, output, masks) + insideOpaque(input, output, masks) +"),
    ("PX-50", "band in pixels: slack of 50 px", "PixelOracle.kt",
     "BAND_PIXEL_CENTRE_SLACK: Double = 0.5", "BAND_PIXEL_CENTRE_SLACK: Double = 50.0"),
    ("PX-51", "a pixel changed beyond the band is accepted", "PixelOracle.kt",
     "if (!within) beyondBand.hit(x, y)", "if (false) beyondBand.hit(x, y)"),
    ("PX-52", "feather outside 0..0.5 accepted", "MaskEllipse.kt",
     "require(feather in 0.0..MAX_FEATHER)", "require(true)"),
    ("PX-53", "band width taken along the smaller semi-axis", "MaskEllipse.kt",
     "softEdgeWidth * max(rx, ry)", "softEdgeWidth * min(rx, ry)"),
    ("PX-54", "distance to the ellipse wrong on the minor axis", "MaskEllipse.kt",
     "return abs(y1 - e1)", "return abs(y1 - e1) + 100.0"),
    ("PX-55", "semi-axis below 1 px not raised (horizontal)", "MaskEllipse.kt",
     "public val rx: Double = max(rx, MIN_RADIUS)", "public val rx: Double = rx"),
    ("PX-56", "semi-axis below 1 px not raised (vertical)", "MaskEllipse.kt",
     "public val ry: Double = max(ry, MIN_RADIUS)", "public val ry: Double = ry"),
    ("PX-57", "second input of the pair ignored when judging survival", "PixelOracle.kt",
     "(second == null || equalRgb(second.first, second.second, x, y) || !equalRgb(output, second.second, x, y))", "true"),
    ("PX-58", "requireMasked does not hand the pair to the survival check", "PixelOracle.kt",
     "insideDiffersFromSource(input, output, masks, fidelity, pair = pair)", "insideDiffersFromSource(input, output, masks, fidelity)"),
    ("PX-59", "a match is excused when only the second output is masked (asymmetric pair)", "PixelOracle.kt",
     "|| !equalRgb(output, second.second, x, y))", ")"),
    ("PX-60", "excused matches are never limited", "PixelOracle.kt",
     "if (c.judged > 0 && excusedCount > MAX_EXCUSED_FRACTION * c.judged) {", "if (false) {"),
    ("PX-61", "a pair sharing the output buffer is accepted", "PixelOracle.kt",
     "val shared = rasters.indices.any { i ->", "val shared = false && rasters.indices.any { i ->"),
    ("PX-62", "a colour-field tolerance is accepted in exact mode", "PixelOracle.kt",
     "if (fidelity is Fidelity.Exact && independence.tolerance != 0) {", "if (false) {"),
    ("PX-63", "a pair tolerance is accepted in exact mode", "PixelOracle.kt",
     "if (fidelity is Fidelity.Exact && independence.maxChannelDelta != 0) {", "if (false) {"),
    ("PX-64", "a pair differing by one level counts as differing", "PixelOracle.kt",
     "channelDelta(inputA[x, y], inputB[x, y]) >= minInputDifference", "true"),
    ("PX-65", "the oracle-built pair differs weakly", "PixelOracle.kt",
     "channel + 64 + random.nextInt(128)", "channel + 1 + random.nextInt(2)"),
    ("PX-67", "a feather other than the library's is accepted", "PixelOracle.kt",
     "masks.any { it.feather != libraryFeather }", "false"),
    ("PX-68", "requireMaskedBy does not copy the masker's result (shared buffer aliasing)", "PixelOracle.kt",
     "val outputA = masker(original.copy()).copy()", "val outputA = masker(original.copy())"),
    ("PX-69", "requireMaskedBy hands the original to the masker", "PixelOracle.kt",
     "val outputA = masker(original.copy()).copy()", "val outputA = masker(original).copy()"),
    ("PX-70", "a scaled sliver keeps the raised radius", "MaskEllipse.kt",
     "givenRx * factor", "rx * factor"),
    ("PX-71", "the farthest changed pixel is reported without any mask", "PixelOracle.kt",
     "if (nearest != Double.MAX_VALUE) c.maxDistance", "if (true) c.maxDistance"),
    ("PX-72", "two wrappers over one pixel array are not seen as one buffer", "PixelOracle.kt",
     "rasters[i] === rasters[j] ||\n                            rasters[i].argb === rasters[j].argb", "rasters[i] === rasters[j]"),
    ("PX-73", "the output may share the input's pixel array", "PixelOracle.kt",
     "if (output === input || output.argb === input.argb) {", "if (output === input) {"),
    ("PX-74", "the 32-level floor of the pair can be lowered", "PixelOracle.kt",
     "if (independence.minInputDifference < DEFAULT_MIN_INPUT_DIFFERENCE ||", "if (false ||"),
    ("PX-75", "the share floor of the pair can be lowered", "PixelOracle.kt",
     "independence.minDifferingFraction < fractionFloor", "false"),
    ("PX-76", "the alpha-0 variant (bounded weight, opacity core) is not judged", "PixelOracle.kt",
     "report += softEdgeWeightBounds(zero, outputZero, masks) + insideOpaque(zero, outputZero, masks)", "Unit"),
    ("PX-77", "the control call on an input with noise beyond the soft edges is not judged", "PixelOracle.kt",
     "if (!followed.passed) {", "if (false) {"),
    ("PX-78", "the control input is not changed beyond the soft edges", "PixelOracle.kt",
     "if (zones.at(x, y) == Zones.OUTSIDE) out.argb[y * input.width + x] = random.nextInt()", "Unit"),
    ("PX-79", "the lossy share floor is zero", "PixelOracle.kt",
     "LOSSY_MIN_DIFFERING_FRACTION: Double = 0.10", "LOSSY_MIN_DIFFERING_FRACTION: Double = 0.0"),
    ("PX-80", "the caller-supplied lossy masked verdict becomes public", "PixelOracle.kt",
     "internal fun requireMaskedLossy(", "public fun requireMaskedLossy("),
    ("PX-81", "the lossy pair tolerance has no ceiling", "PixelOracle.kt",
     "independence.maxChannelDelta > LOSSY_MAX_PAIR_TOLERANCE", "false"),
    ("PX-82", "the lossy pair tolerance ceiling is raised", "PixelOracle.kt",
     "LOSSY_MAX_PAIR_TOLERANCE: Int = 8", "LOSSY_MAX_PAIR_TOLERANCE: Int = 255"),
    ("PX-83", "requireMaskedLossyBy accepts a translucent input", "PixelOracle.kt",
     "if (translucent > 0) {", "if (false) {"),
    ("PX-84", "requireMaskedLossyBy does not compare the far blocks of the masked and unmasked outputs", "PixelOracle.kt",
     "if (!farEqual(e, far, outputA, referenceA, pairTolerance)) {", "if (false) {"),
    ("PX-85", "requireMaskedLossyBy does not check that the masker follows its argument", "PixelOracle.kt",
     "if (!farEqual(e, far, outputN, referenceN, pairTolerance) ||", "if (false &&"),
    ("PX-86", "requireMaskedLossyBy does not check that the unmasked pipeline follows its argument", "PixelOracle.kt",
     "if (!farEqual(e, far, referenceA, referenceB, pairTolerance) ||", "if (false &&"),
    ("PX-87", "the reference correlation rule never fires", "PixelOracle.kt",
     ") < LOSSY_MIN_REFERENCE_CORRELATION", ") < -2.0"),
    ("PX-88", "the built lossy pair does not differ", "PixelOracle.kt",
     "val inputB = blockPairInputFor(original, safe, seed)", "val inputB = original.copy()"),
    ("PX-89", "masks without a safe block are not reported", "PixelOracle.kt",
     "if (safe.isEmpty()) {", "if (false) {"),
    ("PX-90", "an image without far blocks is not reported", "PixelOracle.kt",
     "if (far.isEmpty()) {", "if (false) {"),
    ("PX-91", "requireMaskedLossyBy does not check its pair tolerance", "PixelOracle.kt",
     "if (pairTolerance !in 0..LOSSY_BY_MAX_PAIR_TOLERANCE) {", "if (false) {"),
    ("PX-92", "requireMaskedLossyBy accepts no masks", "PixelOracle.kt",
     "if (masks.isEmpty()) c.add(OracleCode.INVALID_EVIDENCE, \"no masks", "if (false) c.add(OracleCode.INVALID_EVIDENCE, \"no masks"),
    ("PX-93", "the far blocks need no margin beyond the soft edge", "PixelOracle.kt",
     "if (blockHasMargin(zones, bx, by, mcuSize, Zones.OUTSIDE)) {", "if (zones.at(bx * mcuSize, by * mcuSize) == Zones.OUTSIDE) {"),
    ("PX-94", "the block pair changes alpha too", "PixelOracle.kt",
     "var p = input[x, y] and OPAQUE", "var p = 0"),
    ("PX-95", "requireMaskedLossyBy becomes the caller-supplied lossy entry again", "PixelOracle.kt",
     "        val references = encoded.map { decodeFor(c, \"unmasked output\", unmaskedPipeline(it.copyOf())) }",
     "        val references = outputs.map { it?.copy() }"),
    ("PX-96", "the public lossy pair tolerance ceiling is restored to 8", "PixelOracle.kt",
     "LOSSY_BY_MAX_PAIR_TOLERANCE: Int = 0", "LOSSY_BY_MAX_PAIR_TOLERANCE: Int = 8"),
    ("PX-97", "requireMaskedLossyBy uses the internal ceiling of 8 again", "PixelOracle.kt",
     "if (pairTolerance !in 0..LOSSY_BY_MAX_PAIR_TOLERANCE) {", "if (pairTolerance !in 0..LOSSY_MAX_PAIR_TOLERANCE) {"),
    ("PX-98", "requireMaskedBy accepts a lossy fidelity", "PixelOracle.kt",
     "if (fidelity !is Fidelity.Exact || pairTolerance != 0) {", "if (pairTolerance != 0) {"),
    ("PX-99", "requireMaskedBy accepts any fidelity and pair tolerance", "PixelOracle.kt",
     "if (fidelity !is Fidelity.Exact || pairTolerance != 0) {", "if (false) {"),
]

# Build-level mutants: (id, description, file, old, new, gradle args, text the failure output must contain)
BUILD_MUTANTS = [
    ("BD-01", "a third-party dependency is added to the checker", BUILD_FILE,
     "dependencies {\n", 'dependencies {\n    implementation("com.google.guava:guava:33.7.2-jre")\n',
     [":jpeg-privacy:verifyCheckerIndependence", "--dependency-verification=off"], "Unexpected runtime dependencies"),
    ("BD-02", "a file dependency is added to the test classpath", BUILD_FILE,
     "dependencies {\n", 'dependencies {\n    testImplementation(files("planted.jar"))\n',
     [":jpeg-privacy:verifyCheckerIndependence", "--dependency-verification=off"], "other projects, included builds or files"),
    ("BD-05", "a file dependency is added to the checker", BUILD_FILE,
     "dependencies {\n", 'dependencies {\n    implementation(files("planted.jar"))\n',
     [":jpeg-privacy:verifyCheckerIndependence", "--dependency-verification=off"], "[file-dependency]"),
    ("BD-03", "an unverified dependency is added (dependency verification on)", BUILD_FILE,
     "dependencies {\n", 'dependencies {\n    implementation("com.google.guava:guava:33.7.2-jre")\n',
     [":jpeg-privacy:compileKotlin"], "Dependency verification failed"),
    ("BD-PX-01", "the pixel oracle depends on the JPEG checker", PIXEL_BUILD_FILE,
     "dependencies {\n", 'dependencies {\n    implementation(project(":jpeg-privacy"))\n',
     [":pixel-oracle:verifyCheckerIndependence", "--dependency-verification=off"], "Unexpected runtime dependencies"),
    ("BD-04", "a recorded checksum is altered", METADATA,
     '<artifact name="kotlin-stdlib-2.4.21.jar">\n            <sha256 value="',
     '<artifact name="kotlin-stdlib-2.4.21.jar">\n            <sha256 value="0000',
     [":jpeg-privacy:compileKotlin", "--rerun-tasks"], "Dependency verification failed"),
]


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def snapshot():
    """SHA-256 of every file of the verification folder that is not a build output."""
    result = {}
    for root, dirs, files in os.walk(VERIF):
        dirs[:] = [d for d in dirs if d not in ("build", ".gradle", ".kotlin")]
        for name in files:
            path = os.path.join(root, name)
            with open(path, "rb") as f:
                result[os.path.relpath(path, VERIF)] = hashlib.sha256(f.read()).hexdigest()
    return result


def gradle(args):
    cmd = [os.path.join(REPO, "gradlew"), "-p", VERIF, "--console=plain", "--no-build-cache"] + args
    p = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True, timeout=900)
    return p.returncode, p.stdout + p.stderr


def module_of(mutant_id):
    return "pixel-oracle" if mutant_id.startswith("PX-") else "jpeg-privacy"


def failing_tests(module):
    names = []
    for f in sorted(glob.glob(os.path.join(RESULTS[module], "TEST-*.xml"))):
        for case in ET.parse(f).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                names.append(case.get("classname").rsplit(".", 1)[-1] + "." + case.get("name"))
    return names


def run_source_mutant(mutant):
    mid, desc, name, old, new = mutant
    module = module_of(mid)
    path = os.path.join(PIXEL_MAIN if module == "pixel-oracle" else MAIN, name)
    original = read(path)
    if old not in original:
        return mid, desc, "PATTERN-NOT-FOUND", []
    write(path, original.replace(old, new, 1))
    try:
        code, out = gradle(["cleanTest", f":{module}:test", "--continue"])
        failed = failing_tests(module)
        if code != 0 and not failed:
            return mid, desc, "COMPILE-ERROR" if ("e: file:" in out or "Kotlin compiler" in out) else "OTHER-FAILURE", []
        return mid, desc, "KILLED" if failed else "SURVIVED", failed
    finally:
        write(path, original)


def run_build_mutant(mutant):
    mid, desc, path, old, new, args, expected = mutant
    original = read(path)
    if old not in original:
        return mid, desc, "PATTERN-NOT-FOUND", []
    write(path, original.replace(old, new, 1))
    try:
        code, out = gradle(args)
        killed = code != 0 and expected in out
        return mid, desc, "KILLED" if killed else "SURVIVED", ["gradle: " + expected] if killed else []
    finally:
        write(path, original)


def main(argv):
    out_file = None
    ids = []
    it = iter(argv)
    for a in it:
        if a == "--out":
            out_file = next(it)
        else:
            ids.append(a)
    sources = SOURCE_MUTANTS + PIXEL_MUTANTS
    known = {m[0] for m in sources} | {m[0] for m in BUILD_MUTANTS}
    unknown = [i for i in ids if i not in known]
    if unknown:
        print("unknown mutant id(s): " + ", ".join(unknown))
        return 6
    before = snapshot()
    code, out = gradle(["cleanTest", "test"])
    if code != 0:
        print("baseline suites are not green; fix them before mutating\n" + out[-2000:])
        return 3
    results = []
    for m in sources:
        if not ids or m[0] in ids:
            results.append(run_source_mutant(m))
            print(results[-1][0], results[-1][2], len(results[-1][3]), flush=True)
    for m in BUILD_MUTANTS:
        if not ids or m[0] in ids:
            results.append(run_build_mutant(m))
            print(results[-1][0], results[-1][2], flush=True)
    code, _ = gradle(["cleanTest", "test"])
    print("suites after restoring all files:", "green" if code == 0 else "NOT GREEN")
    changed = sorted(k for k, v in snapshot().items() if before.get(k) != v) + sorted(set(before) - set(snapshot()))
    lines = [
        "\t".join([mid, desc, status, str(len(tests)), " | ".join(tests)]) for mid, desc, status, tests in results
    ]
    text = "id\tdescription\tstatus\tfailing tests\tnames of failing tests\n" + "\n".join(lines) + "\n"
    if out_file:
        write(out_file, text)
    print(text)
    killed = sum(1 for r in results if r[2] == "KILLED")
    print(f"{killed} of {len(results)} mutants killed")
    if changed:
        print("ERROR: files differ from the state before the run: " + ", ".join(changed))
        return 4
    print("all files restored byte for byte")
    if not results:
        print("ERROR: no mutant ran")
        return 5
    return 0 if killed == len(results) and code == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
