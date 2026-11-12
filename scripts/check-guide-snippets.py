#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Judges the guide-snippet build (verification/android-snippets, scripts/check-guide-snippets.sh).

Checks, each reported by name; any failure makes the exit code 1:
  FILES        every entry of the snippet directory is a Kotlin file, a Java file or the one no-network manifest
               (a file of another kind would be shown in a guide without being compiled by anything);
  CLASSES      every Kotlin and Java file produced the classes it declares: for each file the declared top-level
               classes, interfaces and objects, the file facade (`<File>Kt`, or the @file:JvmName) when it has
               top-level functions, and the Java public class must exist as .class files under the build directory
               (a file the build did not compile fails; a file from which nothing can be derived fails too);
  PERMISSIONS  the app built with the no-network manifest declares neither INTERNET nor ACCESS_NETWORK_STATE, in the
               merged manifest XML and in `aapt2 dump permissions` of the APK; the control (plain manifest) declares
               exactly both there (proves that the check can fail);
  TESTS        the result file of the Java example's cancellation test has both tests, none skipped, failed or
               errored.
Usage: check-guide-snippets.py --snippets DIR --classes DIR --manifest-file NAME --merged XML --permissions TXT
                               --control-merged XML --control-permissions TXT --test-results XML
Exit codes: 0 all checks pass, 1 a check failed (named), 2 usage or IO error. Standard library only.
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
NETWORK = {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"}
CANCEL_TESTS = {
    "cancellingTheFutureStopsTheRunningCallAndNothingIsWritten",
    "withoutTheCancelTheSameCallWritesTheTarget",
}
PACKAGE = re.compile(r"^package\s+([\w.]+)", re.M)
KT_TYPE = re.compile(r"^(?:(?:public|internal|private|data|sealed|abstract|open|enum|annotation|value|fun)\s+)*"
                     r"(?:class|interface|object)\s+(\w+)", re.M)
KT_FUN = re.compile(r"^(?:(?:public|internal|private|inline|suspend|operator|tailrec|infix|external)\s+)*fun\s+"
                    r"(?!interface\b)", re.M)
KT_JVM_NAME = re.compile(r'^@file:JvmName\("(\w+)"\)', re.M)
JAVA_CLASS = re.compile(r"^public\s+(?:final\s+|abstract\s+)*(?:class|interface|enum|record)\s+(\w+)", re.M)


def expected_classes(path: str, text: str) -> list:
    """Class files (package path + name) that compiling [path] must produce; empty if nothing can be derived."""
    pkg = PACKAGE.search(text)
    prefix = pkg.group(1).replace(".", "/") + "/" if pkg else ""
    if path.endswith(".java"):
        return [prefix + m + ".class" for m in JAVA_CLASS.findall(text)]
    names = list(KT_TYPE.findall(text))
    if KT_FUN.search(text):
        jvm = KT_JVM_NAME.search(text)
        stem = os.path.basename(path)[: -len(".kt")]
        names.append(jvm.group(1) if jvm else stem[:1].upper() + stem[1:] + "Kt")
    return [prefix + n + ".class" for n in names]


def check_files_and_classes(snippets: str, classes: str, manifest_file: str) -> list:
    problems = []
    built = []
    for root, _dirs, files in os.walk(classes):
        built += [os.path.join(root, f) for f in files if f.endswith(".class")]
    entries = sorted(os.listdir(snippets))
    sources = 0
    for name in entries:
        path = os.path.join(snippets, name)
        if name == manifest_file and os.path.isfile(path):
            continue
        if not (os.path.isfile(path) and name.endswith((".kt", ".java"))):
            problems.append(f"FILES: '{name}' in the snippet directory is neither a Kotlin file, a Java file nor "
                            f"the manifest: extend the gate before adding it")
            continue
        sources += 1
        with open(path, encoding="utf-8") as fh:
            wanted = expected_classes(path, fh.read())
        if not wanted:
            problems.append(f"CLASSES: nothing can be derived that '{name}' compiles to")
        for rel in wanted:
            if not any(b.endswith("/" + rel) for b in built):
                problems.append(f"CLASSES: '{name}' was not compiled: no {rel}")
    if sources == 0:
        problems.append("FILES: no Kotlin or Java snippet found")
    if not any(n.endswith(".kt") for n in entries) or not any(n.endswith(".java") for n in entries):
        problems.append("FILES: the snippet directory must hold both Kotlin and Java snippets")
    if manifest_file not in entries:
        problems.append(f"FILES: the manifest snippet '{manifest_file}' is missing")
    return problems


def permissions_of_xml(path: str) -> set:
    names = set()
    for el in ET.parse(path).getroot().iter():
        if el.tag in ("uses-permission", "uses-permission-sdk-23"):
            names.add(el.attrib.get(ANDROID + "name", ""))
    return names


def permissions_of_dump(path: str) -> set:
    with open(path, encoding="utf-8") as fh:
        return set(re.findall(r"^uses-permission(?:-sdk-23)?: name='([^']+)'", fh.read(), re.M))


def check_permissions(merged: str, dump: str, control_merged: str, control_dump: str) -> list:
    problems = []
    for what, names in (("merged manifest", permissions_of_xml(merged)), ("APK (aapt2)", permissions_of_dump(dump))):
        bad = sorted(names & NETWORK)
        if bad:
            problems.append(f"PERMISSIONS: the {what} of the app with the guide's manifest still declares {bad}")
    for what, names in (("merged manifest", permissions_of_xml(control_merged)),
                        ("APK (aapt2)", permissions_of_dump(control_dump))):
        if names & NETWORK != NETWORK:
            problems.append(f"PERMISSIONS: the control {what} does not declare both network permissions "
                            f"(found {sorted(names & NETWORK)}): the check cannot detect them")
    return problems


def check_tests(path: str) -> list:
    root = ET.parse(path).getroot()
    names = set()
    problems = []
    for case in root.iter("testcase"):
        names.add(case.attrib.get("name", ""))
        for tag in ("failure", "error", "skipped"):
            if case.find(tag) is not None:
                problems.append(f"TESTS: {case.attrib.get('name')} has a <{tag}> result")
    if names != CANCEL_TESTS:
        problems.append(f"TESTS: expected exactly {sorted(CANCEL_TESTS)}, the result file has {sorted(names)}")
    return problems


def main(argv: list) -> int:
    ap = argparse.ArgumentParser()
    for arg in ("snippets", "classes", "manifest-file", "merged", "permissions", "control-merged",
                "control-permissions", "test-results"):
        ap.add_argument("--" + arg, required=True)
    try:
        a = ap.parse_args(argv)
        problems = check_files_and_classes(a.snippets, a.classes, a.manifest_file)
        problems += check_permissions(a.merged, a.permissions, a.control_merged, a.control_permissions)
        problems += check_tests(a.test_results)
    except (OSError, ET.ParseError) as e:
        print(f"check-guide-snippets: ERROR - {type(e).__name__}: {e}")
        return 2
    for p in problems:
        print(f"check-guide-snippets: FAIL - {p}")
    if problems:
        return 1
    print("check-guide-snippets: PASS - every snippet compiled, the guide's manifest removes both network "
          "permissions (the control keeps them), the cancellation tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
