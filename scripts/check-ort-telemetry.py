#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ONNX Runtime telemetry must be off in the code itself, not only in the Gradle test environment.

Every Kotlin source file (production AND test, any source set) that creates an ONNX Runtime environment
(`OrtEnvironment.getEnvironment` / `OrtEnvironment.getEnvironment(...)`) must, in code (comment lines ignored):
  1. call `setTelemetry(false)` on it, and
  2. in JVM source sets (jvmMain, jvmTest), read the environment variable ORT_DISABLE_TELEMETRY (the runner must
     refuse to create the environment unless it is "1", so IDE runs and tools without the Gradle test environment
     fail closed instead of sending ORT's start-up event), and
  3. not swallow a failure of these calls with an empty `catch (_: Exception)` / `catch (e: Exception) {}` block.
The Gradle test tasks additionally set ORT_DISABLE_TELEMETRY=1 (build.gradle.kts); that stays as a second layer.

Usage: check-ort-telemetry.py [root]   (default faceblur/src)
Exit codes: 0 ok (or no ONNX Runtime environment created anywhere), 1 a file violates a rule (named), 2 usage error.
"""
import os
import re
import sys

CREATE = re.compile(r"\bOrtEnvironment\s*\.\s*getEnvironment\b")
SET_OFF = re.compile(r"\.\s*setTelemetry\s*\(\s*false\s*\)")
ENV_READ = re.compile(r"""\bgetenv\s*\(\s*"ORT_DISABLE_TELEMETRY"\s*\)""")
EMPTY_CATCH = re.compile(r"catch\s*\(\s*\w+\s*:\s*(Exception|Throwable)\s*\)\s*\{\s*\}")


def code_only(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(line.split("//", 1)[0] for line in text.splitlines())


def main(argv: list) -> int:
    root = argv[0] if argv else "faceblur/src"
    if not os.path.isdir(root):
        print(f"check-ort-telemetry: no source root {root}: nothing to check")
        return 0
    bad = 0
    checked = 0
    for d, _, files in os.walk(root):
        for f in sorted(files):
            if not f.endswith(".kt"):
                continue
            path = os.path.join(d, f)
            with open(path, encoding="utf-8") as fh:
                code = code_only(fh.read())
            if not CREATE.search(code):
                continue
            checked += 1
            rel = os.path.relpath(path)
            jvm = re.search(r"/src/jvm(Main|Test)/", "/" + rel.replace(os.sep, "/"))
            if not SET_OFF.search(code):
                print(f"check-ort-telemetry: {rel}: creates an OrtEnvironment without setTelemetry(false)",
                      file=sys.stderr)
                bad += 1
            if jvm and not ENV_READ.search(code):
                print(f"check-ort-telemetry: {rel}: JVM code must check ORT_DISABLE_TELEMETRY before creating the "
                      "environment (fail closed outside the Gradle test environment)", file=sys.stderr)
                bad += 1
            if EMPTY_CATCH.search(re.sub(r"\s+", " ", code)):
                print(f"check-ort-telemetry: {rel}: an empty catch block can hide a failed telemetry switch",
                      file=sys.stderr)
                bad += 1
    if bad:
        print(f"check-ort-telemetry: FAIL - {bad} problem(s)", file=sys.stderr)
        return 1
    print(f"check-ort-telemetry: PASS - {checked} file(s) create an ONNX Runtime environment, all switch telemetry off")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
