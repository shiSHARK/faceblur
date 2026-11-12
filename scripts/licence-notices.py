#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Licence notices of the artifacts (ADR 0008): the index, the runtime dependencies and the published files.

licenses/INDEX.txt lists the third-party components, their licence texts (files in licenses/) and the artifact kinds
(aar, klib, xcframework, swift) that carry the texts. Subcommands:

  check [--lockfile FILE] [--repo DIR]
      Always: the index is well-formed; every text it names exists and is not empty; no text in licenses/ is unused;
      LICENSE exists; NOTICE exists and starts with the product name and a copyright line.
      --lockfile (faceblur/build/osv/runtime.lockfile, from `./gradlew :faceblur:writeRuntimeLockfile`): every
      resolved runtime module has an index row for its group:artifact with the SAME version (a version change needs
      its texts re-checked), and every Gradle row still matches a resolved module (no stale row).
      --repo (a local Maven repository, build/local-maven): the AAR (classes.jar), the root jar and the sources and
      javadoc jars of every published target carry META-INF/io.github.shishark.faceblur/{LICENSE,NOTICE,
      licenses/INDEX.txt,licenses/<text>} with the bytes of the repository files and the texts of their kind.
  stage KIND OUTDIR
      Copies LICENSE, NOTICE, licenses/INDEX.txt and the texts of KIND into OUTDIR (used for the XCFramework zip).

Run from the repository root. Exit codes: 0 ok, 1 a check failed, 2 usage or read error.
"""
import argparse
import glob
import io
import os
import re
import shutil
import sys
import zipfile

KINDS = ("aar", "klib", "xcframework", "swift")
INDEX = os.path.join("licenses", "INDEX.txt")
PREFIX = "META-INF/io.github.shishark.faceblur/"
# Published artifact folders (io/github/shishark/<folder>) and the kind of notices they carry.
PUBLICATION_KINDS = {
    "faceblur": "klib",
    "faceblur-android": "aar",
    "faceblur-iosarm64": "klib",
    "faceblur-iossimulatorarm64": "klib",
}
GRADLE_MODULE = re.compile(r"^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+\*?$")
SPM_MODULE = re.compile(r"^spm:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class Row:
    def __init__(self, number, columns):
        self.number = number
        self.component, self.version, self.licence = columns[0], columns[1], columns[2]
        self.files = columns[3].split(";")
        self.modules = columns[4].split(";")
        self.kinds = columns[5].split(";")

    def matches(self, group_artifact):
        for module in self.modules:
            if module.endswith("*"):
                if group_artifact.startswith(module[:-1]):
                    return True
            elif module == group_artifact:
                return True
        return False

    def gradle_modules(self):
        return [m for m in self.modules if GRADLE_MODULE.match(m)]


def read_bytes(path):
    with open(path, "rb") as fh:
        return fh.read()


def load_index(errors):
    rows = []
    with open(INDEX, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    for number, line in enumerate(lines, 1):
        if not line.strip() or line.startswith("#"):
            continue
        columns = line.split("\t")
        if len(columns) != 6 or any(not c.strip() for c in columns):
            errors.append(f"{INDEX}:{number}: expected 6 non-empty tab-separated columns")
            continue
        row = Row(number, columns)
        for kind in row.kinds:
            if kind not in KINDS:
                errors.append(f"{INDEX}:{number}: unknown artifact kind '{kind}' (use {', '.join(KINDS)})")
        for module in row.modules:
            if module != "-" and not (GRADLE_MODULE.match(module) or SPM_MODULE.match(module)):
                errors.append(f"{INDEX}:{number}: module '{module}' is not group:artifact, spm:owner/repo or -")
        rows.append(row)
    if not rows:
        errors.append(f"{INDEX}: no component rows")
    return rows


def check_files(rows, errors):
    used = set()
    for row in rows:
        for name in row.files:
            used.add(name)
            path = os.path.join("licenses", name)
            if "/" in name or name == "INDEX.txt":
                errors.append(f"{INDEX}:{row.number}: '{name}' must be a plain file name in licenses/")
            elif not os.path.isfile(path):
                errors.append(f"{INDEX}:{row.number}: {row.component}: licence text licenses/{name} is missing")
            elif not read_bytes(path).strip():
                errors.append(f"{INDEX}:{row.number}: {row.component}: licence text licenses/{name} is empty")
    for entry in sorted(os.listdir("licenses")):
        if entry != "INDEX.txt" and entry not in used:
            errors.append(f"licenses/{entry} is not named by any row of {INDEX}")
    if not os.path.isfile("LICENSE") or not read_bytes("LICENSE").strip():
        errors.append("LICENSE is missing or empty")
    if not os.path.isfile("NOTICE"):
        errors.append("NOTICE is missing")
    else:
        lines = read_bytes("NOTICE").decode("utf-8", "replace").splitlines()
        if not lines or lines[0].strip() != "FaceBlur":
            errors.append("NOTICE does not start with the product name 'FaceBlur'")
        if not any(re.match(r"^Copyright \d{4}(-\d{4})? \S", line) for line in lines[:5]):
            errors.append("NOTICE has no 'Copyright <year> <holder>' line at the top")


def check_lockfile(rows, lockfile, errors):
    modules = {}
    with open(lockfile, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            coordinate = line.split("=", 1)[0]
            parts = coordinate.split(":")
            if len(parts) != 3:
                errors.append(f"{lockfile}: unreadable line")
                continue
            modules[(parts[0] + ":" + parts[1], parts[2])] = True
    if not modules:
        errors.append(f"{lockfile}: no runtime dependencies")
        return
    for (group_artifact, version) in sorted(modules):
        matching = [r for r in rows if r.matches(group_artifact)]
        if not matching:
            errors.append(f"runtime dependency {group_artifact}:{version} has no row in {INDEX}")
        elif not any(r.version == version for r in matching):
            errors.append(
                f"runtime dependency {group_artifact} resolves to {version}, {INDEX} has "
                f"{'/'.join(sorted({r.version for r in matching}))}: re-check the texts and update the row"
            )
    resolved = {ga for (ga, _v) in modules}
    for row in rows:
        if row.gradle_modules() and not any(row.matches(ga) for ga in resolved):
            errors.append(f"{INDEX}:{row.number}: {row.component} matches no resolved runtime dependency (stale row)")


def expected_entries(rows, kind):
    names = sorted({f for r in rows if kind in r.kinds for f in r.files})
    expected = {
        PREFIX + "LICENSE": read_bytes("LICENSE"),
        PREFIX + "NOTICE": read_bytes("NOTICE"),
        PREFIX + "licenses/INDEX.txt": read_bytes(INDEX),
    }
    for name in names:
        expected[PREFIX + "licenses/" + name] = read_bytes(os.path.join("licenses", name))
    return expected


def compare_zip(label, data, expected, errors):
    try:
        zf = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        errors.append(f"{label}: not a zip file")
        return
    with zf:
        names = set(zf.namelist())
        for entry, content in expected.items():
            if entry not in names:
                errors.append(f"{label}: {entry} is missing")
            elif zf.read(entry) != content:
                errors.append(f"{label}: {entry} differs from the repository file")


def check_repo(rows, repo, errors):
    base = os.path.join(repo, "io", "github", "shishark")
    if not os.path.isdir(base):
        errors.append(f"{repo}: no publication under io/github/shishark")
        return
    seen = set()
    for folder in sorted(os.listdir(base)):
        folder_path = os.path.join(base, folder)
        if not os.path.isdir(folder_path):
            continue
        kind = PUBLICATION_KINDS.get(folder)
        if kind is None:
            errors.append(f"publication {folder} has no notice rule in scripts/licence-notices.py")
            continue
        expected = expected_entries(rows, kind)
        sources = glob.glob(os.path.join(folder_path, "*", "*-sources.jar"))
        javadocs = glob.glob(os.path.join(folder_path, "*", "*-javadoc.jar"))
        if not sources or not javadocs:
            errors.append(f"publication {folder}: sources or javadoc jar missing")
        for path in sources + javadocs:
            compare_zip(f"{folder}/{os.path.basename(path)}", read_bytes(path), expected, errors)
            seen.add(folder)
        if folder == "faceblur-android":
            aars = glob.glob(os.path.join(folder_path, "*", "*.aar"))
            if not aars:
                errors.append("publication faceblur-android: no AAR")
            for path in aars:
                with zipfile.ZipFile(path) as aar:
                    if "classes.jar" not in aar.namelist():
                        errors.append(f"{os.path.basename(path)}: no classes.jar")
                        continue
                    compare_zip(f"{os.path.basename(path)}!classes.jar", aar.read("classes.jar"), expected, errors)
        if folder == "faceblur":
            roots = [
                p for p in glob.glob(os.path.join(folder_path, "*", "faceblur-*.jar"))
                if not p.endswith(("-sources.jar", "-javadoc.jar"))
            ]
            if not roots:
                errors.append("publication faceblur: no root jar")
            for path in roots:
                compare_zip(os.path.basename(path), read_bytes(path), expected, errors)
    for folder in PUBLICATION_KINDS:
        if folder not in seen:
            errors.append(f"publication {folder} not found under {repo}")


def stage(rows, kind, outdir):
    if kind not in KINDS:
        print(f"licence-notices: unknown kind '{kind}'", file=sys.stderr)
        return 2
    expected = expected_entries(rows, kind)
    if len(expected) <= 3:
        print(f"licence-notices: {INDEX} lists no text for kind '{kind}'", file=sys.stderr)
        return 1
    for entry, content in expected.items():
        relative = entry[len(PREFIX):]
        target = os.path.join(outdir, relative)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as fh:
            fh.write(content)
    shutil.copymode("LICENSE", os.path.join(outdir, "LICENSE"))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    check = sub.add_parser("check")
    check.add_argument("--lockfile")
    check.add_argument("--repo")
    st = sub.add_parser("stage")
    st.add_argument("kind")
    st.add_argument("outdir")
    args = parser.parse_args()
    if not os.path.isfile(INDEX):
        print(f"licence-notices: ERROR - {INDEX} not found (run from the repository root)", file=sys.stderr)
        return 2
    errors = []
    rows = load_index(errors)
    if args.command == "stage":
        if errors:
            print("\n".join("licence-notices: FAIL - " + e for e in errors), file=sys.stderr)
            return 1
        return stage(rows, args.kind, args.outdir)
    try:
        check_files(rows, errors)
        if args.lockfile:
            check_lockfile(rows, args.lockfile, errors)
        if args.repo:
            check_repo(rows, args.repo, errors)
    except OSError as exc:
        print(f"licence-notices: ERROR - {exc}", file=sys.stderr)
        return 2
    if errors:
        print("\n".join("licence-notices: FAIL - " + e for e in errors), file=sys.stderr)
        return 1
    print(f"licence-notices: PASS ({len(rows)} components"
          f"{', lockfile' if args.lockfile else ''}{', publication' if args.repo else ''})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
