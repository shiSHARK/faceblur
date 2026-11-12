#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Runtime dependencies of the published Android artifact against a reviewed allow-list (PRIV-014).

The library contains no network code and no network dependencies. This gate fails when
  1. a module of the library's resolved androidRuntimeClasspath (the runtime lockfile written by
     `./gradlew :faceblur:writeRuntimeLockfile`) is not a row of the allow-list with the same version;
  2. a dependency declared by the published faceblur-android or root faceblur POM or Gradle module metadata (every
     variant) is not a row of the allow-list with the same version (a declared range or a missing version fails);
  3. any module above, or any allow-list row, names a networking, telemetry, analytics or crash-reporting library
     (NETWORK below): such a module can never be allowed, a row does not help;
  4. an allow-list row is not used by the resolved classpath (stale rows would let a module come back unreviewed);
  5. the lockfile has no androidRuntimeClasspath entry, or the faceblur-android or the root faceblur publication (POM
     and module metadata) is not found (nothing checked).
Changing NETWORK or the rules here is a change of the privacy gates and needs a security review.

Usage: check-runtime-deps.py --lockfile <runtime.lockfile> --allow <allow-list> [--repo <local maven repo>]
Exit codes: 0 clean, 1 a violation (named), 2 usage or read error.
"""
import argparse
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

CONFIGURATION = "androidRuntimeClasspath"
HEADER = ("# SPDX-License-Identifier: Apache-2.0", "# faceblur runtime dependencies of the published Android artifact, format 1")
# group:artifact patterns (regular expressions on "group:artifact") of libraries whose purpose is network access,
# remote telemetry, analytics or crash reporting. Matching is case-insensitive.
NETWORK = [
    r"^com\.squareup\.okhttp3?:", r"^com\.squareup\.okio:", r"^com\.squareup\.retrofit2?:", r"^io\.ktor:",
    r"^com\.android\.volley:", r"^com\.mcxiaoke\.volley:", r"^org\.chromium\.net:", r"^com\.google\.net\.cronet:",
    r"^com\.google\.android\.gms:", r"^com\.google\.firebase:", r"^com\.google\.android\.datatransport:",
    r"^io\.grpc:", r"^io\.netty:", r"^org\.apache\.httpcomponents", r"^commons-httpclient:",
    r"^org\.eclipse\.jetty", r"^com\.github\.bumptech\.glide:", r"^io\.coil-kt", r"^com\.squareup\.picasso:",
    r"^io\.sentry:", r"^com\.bugsnag:", r"^com\.crashlytics", r"^com\.microsoft\.appcenter:", r"^io\.opentelemetry",
    r"^com\.datadoghq:", r"^com\.newrelic", r"^com\.amplitude:", r"^com\.mixpanel", r"^com\.segment",
    r"^com\.google\.android\.play:", r"^com\.apollographql", r"^org\.java-websocket:", r"^com\.neovisionaries:",
    r"^androidx\.browser:", r"^androidx\.webkit:",
    r":[^:]*(okhttp|retrofit|ktor-client|volley|cronet|grpc|websocket|http-?client|telemetry|analytics|crashlytics)",
]
NETWORK_RE = re.compile("|".join(NETWORK), re.IGNORECASE)
COORD = re.compile(r"^([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.+-]+)$")


def fail_usage(msg: str) -> None:
    print(f"check-runtime-deps: ERROR - {msg}", file=sys.stderr)
    sys.exit(2)


def read_allow(path: str) -> dict:
    """{group:artifact: version} of the allow-list."""
    try:
        with open(path, encoding="utf-8") as fh:
            lines = fh.read().splitlines()
    except OSError as e:
        fail_usage(f"allow-list unreadable ({type(e).__name__})")
    if tuple(lines[:2]) != HEADER:
        fail_usage(f"{path}: the first two lines must be the format header")
    rows = {}
    for nr, ln in enumerate(lines[2:], 3):
        if not ln.strip() or ln.startswith("#"):
            continue
        parts = ln.split("\t")
        m = COORD.match(parts[0])
        if len(parts) != 2 or not m or not parts[1].strip():
            fail_usage(f"{path}:{nr}: expected '<group>:<artifact>:<version><TAB><reason>'")
        key = f"{m.group(1)}:{m.group(2)}"
        if key in rows:
            fail_usage(f"{path}:{nr}: duplicate module {key}")
        rows[key] = m.group(3)
    if not rows:
        fail_usage(f"{path}: no rows")
    return rows


def read_lockfile(path: str) -> list:
    """[(group:artifact, version)] of the androidRuntimeClasspath entries."""
    try:
        with open(path, encoding="utf-8") as fh:
            lines = fh.read().splitlines()
    except OSError as e:
        fail_usage(f"lockfile unreadable ({type(e).__name__})")
    out = []
    for ln in lines:
        if not ln.strip() or ln.startswith("#") or "=" not in ln:
            continue
        coord, confs = ln.split("=", 1)
        if CONFIGURATION not in confs.split(","):
            continue
        m = COORD.match(coord.strip())
        if not m:
            fail_usage(f"lockfile entry not group:artifact:version: {coord.strip()}")
        out.append((f"{m.group(1)}:{m.group(2)}", m.group(3)))
    return out


PUBLICATIONS = ("faceblur-android", "faceblur")  # the Android artifact and the root publication that points to it


def published(repo: str) -> list:
    """[(label, group:artifact, version or None)] declared by every POM and .module of PUBLICATIONS in repo."""
    poms, modules = [], []
    for artifact in PUBLICATIONS:
        base = os.path.join(repo, "io", "github", "shishark", artifact)
        p = sorted(glob.glob(os.path.join(base, "*", "*.pom")))
        m = sorted(glob.glob(os.path.join(base, "*", "*.module")))
        if not p or not m:
            print(f"check-runtime-deps: no {artifact} POM and module metadata under the repository", file=sys.stderr)
            sys.exit(1)
        poms += p
        modules += m
    out = []
    ns = "{http://maven.apache.org/POM/4.0.0}"
    for pom in poms:
        try:
            root = ET.parse(pom).getroot()
        except (OSError, ET.ParseError) as e:
            fail_usage(f"{os.path.basename(pom)} unreadable ({type(e).__name__})")
        for dep in root.iter(f"{ns}dependency"):
            scope = dep.findtext(f"{ns}scope") or "compile"
            if scope in ("test", "provided", "system"):
                continue
            out.append((os.path.basename(pom), f"{dep.findtext(f'{ns}groupId')}:{dep.findtext(f'{ns}artifactId')}",
                        dep.findtext(f"{ns}version")))
    for mod in modules:
        try:
            with open(mod, encoding="utf-8") as fh:
                variants = json.load(fh).get("variants", [])
        except (OSError, ValueError) as e:
            fail_usage(f"{os.path.basename(mod)} unreadable ({type(e).__name__})")
        for v in variants:
            for d in v.get("dependencies", []) + v.get("dependencyConstraints", []):
                ver = d.get("version", {})
                exact = ver.get("strictly") or ver.get("requires")
                if set(ver) - {"requires", "strictly", "prefers"}:
                    exact = None
                out.append((f"{os.path.basename(mod)} {v.get('name')}", f"{d.get('group')}:{d.get('module')}", exact))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lockfile", required=True)
    ap.add_argument("--allow", required=True)
    ap.add_argument("--repo")
    a = ap.parse_args()
    allow = read_allow(a.allow)
    resolved = read_lockfile(a.lockfile)
    bad = []
    if not resolved:
        bad.append(f"the lockfile has no {CONFIGURATION} entry (nothing checked)")
    for key, version in sorted(allow.items()):
        if NETWORK_RE.search(key):
            bad.append(f"allow-list row {key} names a networking/telemetry library (never allowed)")
    used = set()
    for key, version in resolved:
        used.add(key)
        if NETWORK_RE.search(key):
            bad.append(f"{CONFIGURATION}: {key}:{version} is a networking/telemetry library (never allowed)")
        elif key not in allow:
            bad.append(f"{CONFIGURATION}: {key}:{version} is not in the allow-list (new runtime dependency)")
        elif allow[key] != version:
            bad.append(f"{CONFIGURATION}: {key}:{version} differs from the allow-listed version {allow[key]}")
    for key in sorted(set(allow) - used):
        if resolved:
            bad.append(f"allow-list row {key} is not resolved any more (remove the stale row)")
    if a.repo:
        for label, key, version in published(a.repo):
            if NETWORK_RE.search(key):
                bad.append(f"{label}: declares {key} (networking/telemetry library, never allowed)")
            elif key not in allow:
                bad.append(f"{label}: declares {key}:{version} which is not in the allow-list")
            elif version != allow[key]:
                bad.append(f"{label}: declares {key} with version {version!r}, allow-listed {allow[key]}")
    for b in bad:
        print(f"check-runtime-deps: {b}", file=sys.stderr)
    if bad:
        print(f"check-runtime-deps: FAIL - {len(bad)} finding(s)", file=sys.stderr)
        return 1
    print(f"check-runtime-deps: PASS - {len(resolved)} {CONFIGURATION} modules"
          f"{' and the published declarations' if a.repo else ''} match {len(allow)} allow-list rows")
    return 0


if __name__ == "__main__":
    sys.exit(main())
