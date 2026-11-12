#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ONNX Runtime session options grep gate (ADR 0012 decision 4, "Locked session options"; SEC-MA-001).

The runtime bindings (Android/JVM runner, iOS glue) set exactly the locked session options. This gate scans every
source file (Kotlin, Java, Swift, Objective-C, C/C++; production AND test code; comments ignored, string literals
kept) and fails on:
  1. a call that sets a session or run configuration entry (addConfigEntry, addConfigEntry(withKey:), the
     Objective-C addConfigEntryWithKey:, the C/C++ AddConfigEntry / AddSessionConfigEntry / AddRunConfigEntry)
     whose key is not a string literal on the allow-list below, or whose value is not the literal required for that
     key (a key or value that is not a literal cannot be checked and fails);
  2. any string literal that looks like an ONNX Runtime configuration key (prefix session., ep., optimization.,
     mlas., memory.) and is not on the allow-list, wherever it appears (this also catches keys passed through a
     variable or a map);
  3. any call in the "never" column: custom operators (registerCustomOpLibrary, registerCustomOpsUsingFunction,
     registerCustomOpsUsingFunctionPointer, enableOrtExtensionsCustomOps and their C/C++ forms), an optimised-model
     path, profiling, external or added initializers, and every execution provider other than XNNPACK (and the CPU
     fallback): NNAPI, Core ML, QNN, CUDA, TensorRT, ROCm, DirectML, OpenVINO, oneDNN, ACL, Arm NN, TVM, MIGraphX,
     CANN, WebGPU, SNPE, Vitis AI, Azure, JS and the generic append-execution-provider forms.
Allow-list (key -> the only accepted value):
  session.load_model_format = ONNX · session.allow_released_opsets_only = 1 · session.intra_op.allow_spinning = 0
Changing the allow-list or the "never" list is a change of ADR 0012 and needs its review.

Usage: check-ort-options.py [root]   (default: the git-tracked files of the current repository; with a root, every
       file under it)
Exit codes: 0 clean, 1 a violation (file:line and rule named), 2 usage or read error.
"""
import os
import re
import subprocess
import sys

ALLOWED = {
    "session.load_model_format": "ONNX",
    "session.allow_released_opsets_only": "1",
    "session.intra_op.allow_spinning": "0",
}
SOURCE_EXT = (".kt", ".kts", ".java", ".swift", ".m", ".mm", ".h", ".c", ".cc", ".cpp", ".hpp")
KEY_LIKE = re.compile(r"^(?:session|ep|optimization|mlas|memory)\.[A-Za-z0-9_.]+$")
# Calls that set a configuration entry; group 1 is the argument text up to the closing parenthesis (one level).
SET_CALL = re.compile(
    r"\b(?:addConfigEntry|addConfigEntryWithKey|AddConfigEntry|AddSessionConfigEntry|AddRunConfigEntry|"
    r"addSessionConfigEntry|addRunConfigEntry)\s*(?:\(|:)")
NEVER = [
    (re.compile(r"\b(?:registerCustomOpLibrary|registerCustomOpsUsingFunction(?:Pointer)?|"
                r"enableOrtExtensionsCustomOps|RegisterCustomOps\w*|RegisterCustomOpsLibrary\w*|"
                r"EnableOrtCustomOps|AddCustomOpDomain|CustomOpDomain)\b"), "custom operators"),
    (re.compile(r"\b(?:setOptimizedModelFilePath|SetOptimizedModelFilePath|optimizedModelFilePath)\b"),
     "optimised-model file path"),
    (re.compile(r"\b(?:enableProfiling|EnableProfiling|setProfiling\w*|profileFilePrefix|"
                r"ProfilingEnabled|enable_profiling)\b"), "profiling"),
    (re.compile(r"\b(?:addExternalInitializers|AddExternalInitializers\w*|addInitializer|AddInitializer|"
                r"addExternalInitializersFromFilesInMemory)\b"), "external or added initializers"),
    (re.compile(r"\badd(?:CUDA|Cuda|ROCM|Rocm|Dnnl|OpenVINO|Tensorrt|TensorRT|Nnapi|NNAPI|Tvm|DirectML|ACL|ArmNN|"
                r"CoreML|Qnn|QNN|WebGPU|MIGraphX|CANN|Cann|Snpe|SNPE|VitisAI|Azure|JS)\w*\s*\("),
     "execution provider other than XNNPACK"),
    (re.compile(r"\b(?:append|Append)(?!Xnnpack)\w*ExecutionProvider\w*\b"),
     "execution provider other than XNNPACK (or the generic append form)"),
    (re.compile(r"\bOrtSessionOptionsAppendExecutionProvider\w*\b"), "execution provider other than XNNPACK"),
]


def strip_comments(text: str) -> str:
    """Removes // and /* */ comments; string and character literals are kept (with their positions)."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(text[i:j])
            i = j
        elif c == '"' or c == "'":
            j = i + 1
            while j < n and text[j] != c and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("\n" * text.count("\n", i, j))  # keep line numbers
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


STRING = re.compile(r'@?"((?:[^"\\\n]|\\.)*)"')


def literal(arg: str):
    """The content of [arg] if it is exactly one string literal (Kotlin/Java/Swift/C, ObjC @"..."), else None."""
    m = re.fullmatch(r'\s*(?:withKey\s*:\s*|value\s*:\s*)?@?"((?:[^"\\\n$]|\\.)*)"\s*', arg)
    return m.group(1) if m else None


def call_arguments(code: str, start: int) -> list:
    """Top-level arguments of the call whose '(' or ':' is just before [start] (Kotlin/Java/Swift/C/ObjC)."""
    if code[start - 1] == ":":
        # Objective-C: [o addConfigEntryWithKey:@"k" value:@"v" error:&e]
        end = code.find("]", start)
        seg = code[start:end if end >= 0 else len(code)]
        parts = re.split(r"\s+\w+\s*:", seg)
        return [p.strip() for p in parts]
    depth = 0
    args = []
    cur = []
    i = start
    in_str = None
    while i < len(code):
        c = code[i]
        if in_str:
            cur.append(c)
            if c == "\\":
                cur.append(code[i + 1] if i + 1 < len(code) else "")
                i += 2
                continue
            if c == in_str:
                in_str = None
        elif c in "\"'":
            in_str = c
            cur.append(c)
        elif c in "([{":
            depth += 1
            cur.append(c)
        elif c in ")]}":
            if depth == 0:
                args.append("".join(cur).strip())
                return args
            depth -= 1
            cur.append(c)
        elif c == "," and depth == 0:
            args.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    args.append("".join(cur).strip())
    return args


def check_file(path: str, rel: str) -> list:
    try:
        with open(path, encoding="utf-8") as fh:
            code = strip_comments(fh.read())
    except (OSError, UnicodeDecodeError) as e:
        raise RuntimeError(f"{rel}: cannot read ({e.__class__.__name__})")
    problems = []

    def line_of(pos: int) -> int:
        return code.count("\n", 0, pos) + 1

    for m in SET_CALL.finditer(code):
        args = call_arguments(code, m.end())
        key = literal(args[0]) if args else None
        value = literal(args[1]) if len(args) > 1 else None
        where = f"{rel}:{line_of(m.start())}"
        if key is None:
            problems.append(f"{where}: configuration key is not a string literal (cannot be checked)")
        elif key not in ALLOWED:
            problems.append(f"{where}: configuration key '{key}' is not on the allow-list")
        elif value is None:
            problems.append(f"{where}: value of '{key}' is not a string literal (cannot be checked)")
        elif value != ALLOWED[key]:
            problems.append(f"{where}: '{key}' must be set to '{ALLOWED[key]}', not '{value}'")
    for m in STRING.finditer(code):
        text = m.group(1)
        if KEY_LIKE.match(text) and text not in ALLOWED:
            problems.append(f"{rel}:{line_of(m.start())}: configuration key literal '{text}' is not on the allow-list")
    for pattern, what in NEVER:
        for m in pattern.finditer(code):
            problems.append(f"{rel}:{line_of(m.start())}: never-call '{m.group(0).rstrip('( ')}' ({what})")
    return problems


def tracked_files() -> list:
    out = subprocess.run(["git", "ls-files", "-z"], capture_output=True, check=True).stdout
    return [p for p in out.decode("utf-8", "surrogateescape").split("\0") if p]


def main(argv: list) -> int:
    if len(argv) > 1:
        print("usage: check-ort-options.py [root]", file=sys.stderr)
        return 2
    try:
        if argv:
            if not os.path.isdir(argv[0]):
                print(f"check-ort-options: ERROR - no directory {argv[0]}", file=sys.stderr)
                return 2
            files = sorted(os.path.join(d, f) for d, _, fs in os.walk(argv[0]) for f in fs)
        else:
            files = tracked_files()
    except (subprocess.CalledProcessError, OSError) as e:
        print(f"check-ort-options: ERROR - cannot list files ({e.__class__.__name__})", file=sys.stderr)
        return 2
    problems = []
    checked = 0
    try:
        for path in files:
            if not path.endswith(SOURCE_EXT) or os.path.islink(path) or not os.path.isfile(path):
                continue
            checked += 1
            problems.extend(check_file(path, os.path.relpath(path)))
    except RuntimeError as e:
        print(f"check-ort-options: ERROR - {e}", file=sys.stderr)
        return 2
    if problems:
        for p in problems:
            print(f"check-ort-options: {p}", file=sys.stderr)
        print(f"check-ort-options: FAIL - {len(problems)} violation(s) of the locked session options (ADR 0012)",
              file=sys.stderr)
        return 1
    print(f"check-ort-options: PASS - {checked} source files: only allow-listed configuration keys with their "
          "required values, no never-call")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
