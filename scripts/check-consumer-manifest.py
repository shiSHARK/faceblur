#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Checks the manifest an app really gets (ADR 0007, amended), from two independent sources of one build:
  * the merged manifest XML written by the Android Gradle Plugin (processReleaseManifest), and
  * the binary manifest inside the APK, as printed by `aapt2 dump xmltree --file AndroidManifest.xml <apk>`, with
    `aapt2 dump permissions <apk>` as a second reading of the permissions.
The manifest merger's report (manifest-merger-*-report.txt) is never read: it describes decisions, not the result.

For each source (both must agree with the expectation):
  - ONNX Runtime's telemetry starter (<provider android:name="ai.onnxruntime.TelemetryInitializer">) is absent or
    present as given by --starter;
  - androidx.startup's InitializationProvider is present and still holds the --initializer meta-data entry
    (android:value="androidx.startup"): the removal must not touch other startup initializers;
  - the set of <uses-permission>/<uses-permission-sdk-23> names equals --permissions exactly (comma-separated, may be
    empty).

Usage: check-consumer-manifest.py --merged <xml> --xmltree <txt> --dump-permissions <txt> --starter absent|present
                                  --initializer <class> --permissions <a,b,...>
Exit codes: 0 every expectation holds, 1 a difference (named), 2 usage/IO error. Standard library only.
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
STARTER = "ai.onnxruntime.TelemetryInitializer"
STARTUP = "androidx.startup.InitializationProvider"
E_RE = re.compile(r"^(\s*)E: ([A-Za-z0-9_-]+) \(line=\d+\)\s*$")
ATTR_RE = re.compile(r'^\s*A: http://schemas\.android\.com/apk/res/android:([A-Za-z]+)\(0x[0-9a-f]+\)="([^"]*)"')


class Node:
    def __init__(self, tag: str):
        self.tag, self.attrs, self.children = tag, {}, []


def from_xml(path: str) -> Node:
    def conv(el) -> Node:
        n = Node(el.tag)
        n.attrs = {k[len(A):]: v for k, v in el.attrib.items() if k.startswith(A)}
        n.children = [conv(c) for c in el]
        return n
    return conv(ET.parse(path).getroot())


def from_xmltree(path: str) -> Node:
    """The element tree of `aapt2 dump xmltree` output (elements by indentation, android: attributes only)."""
    root, stack, last = None, [], None
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            m = E_RE.match(line)
            if m:
                depth, node = len(m.group(1)), Node(m.group(2))
                while stack and stack[-1][0] >= depth:
                    stack.pop()
                if stack:
                    stack[-1][1].children.append(node)
                elif root is None:
                    root = node
                else:
                    raise ValueError("more than one root element")
                stack.append((depth, node))
                last = node
                continue
            m = ATTR_RE.match(line)
            if m and last is not None:
                last.attrs[m.group(1)] = m.group(2)
    if root is None or root.tag != "manifest":
        raise ValueError("no manifest element")
    return root


def check(root: Node, label: str, a) -> list:
    bad = []
    app = [c for c in root.children if c.tag == "application"]
    comps = [c for x in app for c in x.children]
    starters = [c for c in comps if c.tag == "provider" and c.attrs.get("name") == STARTER]
    if a.starter == "absent" and starters:
        bad.append(f"{label}: {STARTER} is PRESENT (expected absent)")
    if a.starter == "present" and not starters:
        bad.append(f"{label}: {STARTER} is absent (expected present)")
    startup = [c for c in comps if c.tag == "provider" and c.attrs.get("name") == STARTUP]
    if len(startup) != 1:
        bad.append(f"{label}: {len(startup)} {STARTUP} providers (expected exactly one)")
    else:
        meta = {(m.attrs.get("name"), m.attrs.get("value")) for m in startup[0].children if m.tag == "meta-data"}
        if (a.initializer, "androidx.startup") not in meta:
            bad.append(f"{label}: startup initializer {a.initializer} missing from {STARTUP}")
    perms = {c.attrs.get("name") for c in root.children if c.tag in ("uses-permission", "uses-permission-sdk-23")}
    if perms != a.expected:
        bad.append(f"{label}: permissions {sorted(perms)} != expected {sorted(a.expected)}")
    return bad


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--merged", required=True)
    ap.add_argument("--xmltree", required=True)
    ap.add_argument("--dump-permissions", required=True)
    ap.add_argument("--starter", choices=("absent", "present"), required=True)
    ap.add_argument("--initializer", required=True)
    ap.add_argument("--permissions", required=True)
    a = ap.parse_args()
    a.expected = {p for p in a.permissions.split(",") if p}
    try:
        merged = from_xml(a.merged)
        apk = from_xmltree(a.xmltree)
        with open(a.dump_permissions, encoding="utf-8") as fh:
            dumped = set(re.findall(r"^uses-permission(?:-sdk-23)?: name='([^']+)'", fh.read(), re.M))
    except (OSError, ET.ParseError, ValueError) as e:
        print(f"check-consumer-manifest: ERROR - cannot read the inputs ({type(e).__name__}: {e})", file=sys.stderr)
        return 2
    bad = check(merged, "merged manifest", a) + check(apk, "APK manifest (aapt2 xmltree)", a)
    if dumped != a.expected:
        bad.append(f"APK (aapt2 dump permissions): {sorted(dumped)} != expected {sorted(a.expected)}")
    for b in bad:
        print(f"check-consumer-manifest: FAIL - {b}")
    if bad:
        return 1
    print(f"check-consumer-manifest: PASS - starter {a.starter}, {STARTUP} with {a.initializer}, permissions "
          f"{sorted(a.expected) or 'none'} (merged manifest and APK)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
