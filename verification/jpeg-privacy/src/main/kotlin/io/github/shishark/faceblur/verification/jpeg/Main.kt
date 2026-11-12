// SPDX-License-Identifier: Apache-2.0
package io.github.shishark.faceblur.verification.jpeg

import java.io.IOException
import java.io.PrintStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """usage: jpeg-privacy [--policy=strip-all|allowlist:gps,datetime,camera] [--no-exif-required]
                    [--icc-sha256=<hex>]... [--measure-icc] <file>...
Checks JPEG output files against the FaceBlur metadata policy.
Output per file: its position (file #1 is the first argument), PASS or FAIL, counts and finding codes. It never
prints file names, directories, offsets or metadata values (names of outputs can be confidential).
An ICC profile fails unless its SHA-256 is given with --icc-sha256. --measure-icc only adds the profile hash to
the line (to obtain the hash of a platform encoder's profile); the verdict and the exit code do not change, so an
unreviewed profile is still FAIL.
Exit code 0 = every file passed, 1 = a file failed, was unparseable or could not be read, 2 = usage error."""

/** Command line for files pulled from a phone or simulator: `./gradlew :jpeg-privacy:run --args="<file>..."`. */
public fun main(args: Array<String>) {
    exitProcess(runCli(args, System.out, System.err))
}

/** Returns the exit code: 0 all passed, 1 a file failed, 2 usage error. */
internal fun runCli(args: Array<String>, out: PrintStream, err: PrintStream): Int {
    var policy: MetadataPolicy = MetadataPolicy.StripAll
    var requireExif = true
    var measure = false
    val iccHashes = mutableSetOf<String>()
    val files = mutableListOf<String>()
    for (arg in args) {
        when {
            arg == "--policy=strip-all" -> policy = MetadataPolicy.StripAll

            arg.startsWith("--policy=allowlist:") -> {
                val groups = arg.removePrefix("--policy=allowlist:").split(',').map { it.trim() }.toSet()
                if (groups.isEmpty() || !groups.all { it in setOf("gps", "datetime", "camera") }) return usage(err)
                policy = MetadataPolicy.KeepAllowlist("gps" in groups, "datetime" in groups, "camera" in groups)
            }

            arg == "--no-exif-required" -> requireExif = false

            arg == "--measure-icc" -> measure = true

            arg.startsWith("--icc-sha256=") -> {
                val hash = arg.removePrefix("--icc-sha256=").lowercase()
                if (!Regex("[0-9a-f]{64}").matches(hash)) return usage(err)
                iccHashes.add(hash)
            }

            arg.startsWith("--") -> return usage(err)

            else -> files.add(arg)
        }
    }
    if (files.isEmpty()) return usage(err)
    val options = CheckOptions(
        policy = policy,
        requireExif = requireExif,
        allowedIccSha256 = iccHashes,
    )
    var failed = false
    for ((index, file) in files.withIndex()) {
        val label = "file #${index + 1}"
        try {
            val report = PrivacyChecker.checkFile(Path.of(file), options)
            val icc = if (measure) report.iccSha256?.let { " icc_sha256=$it" }.orEmpty() else ""
            if (report.passed) {
                out.println("PASS $label segments=${report.segments.size}$icc")
            } else {
                failed = true
                val codes = report.failureCodes.joinToString(",")
                out.println("FAIL $label failures=${report.failures.size} codes=$codes$icc")
            }
        } catch (e: IOException) {
            // The exception message contains the path; print the class of the problem only.
            failed = true
            out.println("FAIL $label IO_ERROR (${e::class.simpleName})")
        } catch (e: InvalidPathException) {
            failed = true
            out.println("FAIL $label IO_ERROR (${e::class.simpleName})")
        }
    }
    return if (failed) 1 else 0
}

private fun usage(err: PrintStream): Int {
    err.println(USAGE)
    return 2
}
