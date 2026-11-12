#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Line coverage of CHANGED library code (NFR-QUAL-001, ST-009 AC2): >= threshold or fail.

Kover reports coverage for whole classes; it has no "changed lines only" mode. This script joins
  * the changed lines of production Kotlin sources (faceblur/src/<set>Main/**.kt) between --base and HEAD
    (git diff -U0, added or modified lines only), and
  * the Kover XML report (JaCoCo format: package / sourcefile / line nr, mi = missed, ci = covered instructions).
A changed line counts only if the report lists it (i.e. it has bytecode). Covered = ci > 0.
Changed files the report does not contain at all are listed as NOT MEASURED and emitted as a GitHub `::warning::`
annotation; they are not counted. Kover cannot instrument Kotlin/Native, so changed iosMain/appleMain/nativeMain
code is always NOT MEASURED: the QA lead decides the replacement evidence, and until then every such change
needs tests per acceptance criterion confirmed by the code-reviewer and an explicit note in the handoff.
The report identifies a file by (package, file name). If two measured source sets (common/jvm/android) contain a
file with the same package and name, the lines cannot be attributed: the gate fails (rename one, e.g. X.jvm.kt).
Generated sources (build/) are never in the diff. Exclusions in the Kover configuration apply here too, with
their written reasons in faceblur/build.gradle.kts.

Phone-only files (--phone-only config/coverage/phone-only.txt; a malformed list in HEAD fails): Android files that
hold only platform / ONNX Runtime calls. Only entries that are in the list of BOTH --base and HEAD count: a range
cannot relax its own gate, so an entry added in the range takes effect after its approved change is merged (until
then its lines count normally, with a warning). Their changed lines leave the threshold ONLY when --phone-log names a
phone-test log that scripts/phone_test_log.py accepts for HEAD (same commit and tree, the configured phone, every
device test passed; expected skips also as base AND HEAD); the gate then reports them as phone-tested (behavioural
evidence, not coverage). Without a valid log they count like every other line. --ci-phone-not-measured (refused
unless GITHUB_ACTIONS=true: GitHub Actions has no phone) reports them as NOT MEASURED instead, with one warning per
file naming it and its changed-line count; the local merge gate never uses it. Both figures (with and without the
phone-only files) are always printed.

Usage: diff-coverage.py --report faceblur/build/reports/kover/report.xml --base <rev> [--threshold 80]
                        [--phone-only <list>] [--phone-log <log> | --ci-phone-not-measured]
Exit codes: 0 pass (or nothing measurable changed), 1 below threshold or malformed phone-only list, 2 usage/IO/git
error. Standard library only.
"""
import argparse
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import phone_test_log  # noqa: E402  (scripts/phone_test_log.py, same directory)
import xml.etree.ElementTree as ET

SRC_RE = re.compile(r"^faceblur/src/([A-Za-z0-9]+Main)/kotlin/.+\.kt$")
NATIVE_SETS = ("ios", "apple", "native", "macos", "watchos", "tvos", "linux", "mingw")
HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")
PKG_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)", re.M)


def git(*args: str) -> str:
    res = subprocess.run(["git", *args], capture_output=True, text=True)
    if res.returncode != 0:
        print(f"diff-coverage: git {' '.join(args[:2])} failed: {res.stderr.strip()}", file=sys.stderr)
        sys.exit(2)
    return res.stdout


def changed_lines(base: str) -> dict:
    """{path: set(line numbers in HEAD)} for added/modified lines of production Kotlin files."""
    out = git("diff", "-U0", "--no-color", "--no-ext-diff", "--diff-filter=AMR", f"{base}...HEAD", "--",
              "faceblur/src")
    result: dict = {}
    current = None
    for line in out.splitlines():
        if line.startswith("+++ "):
            path = line[4:]
            path = path[2:] if path.startswith("b/") else None
            current = path if path and SRC_RE.match(path) else None
            continue
        m = HUNK_RE.match(line)
        if m and current:
            start = int(m.group(1))
            count = int(m.group(2)) if m.group(2) is not None else 1
            result.setdefault(current, set()).update(range(start, start + count))
    return result


def load_report(path: str) -> dict:
    """{(package path, file name): {line nr: covered bool}}"""
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as e:
        print(f"diff-coverage: cannot read the Kover XML report: {e}", file=sys.stderr)
        sys.exit(2)
    data: dict = {}
    for pkg in root.iter("package"):
        for sf in pkg.iter("sourcefile"):
            lines = {}
            for ln in sf.iter("line"):
                nr = int(ln.get("nr"))
                lines[nr] = int(ln.get("ci", "0")) > 0
            data[(pkg.get("name"), sf.get("name"))] = lines
    return data


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", required=True)
    ap.add_argument("--base", required=True)
    ap.add_argument("--threshold", type=float, default=80.0)
    ap.add_argument("--phone-only")
    ph = ap.add_mutually_exclusive_group()
    ph.add_argument("--phone-log")
    ph.add_argument("--ci-phone-not-measured", action="store_true")
    a = ap.parse_args()
    if a.ci_phone_not_measured and os.environ.get("GITHUB_ACTIONS") != "true":
        print("diff-coverage: ERROR - the option --ci-phone-not-measured: GitHub Actions only (GITHUB_ACTIONS=true); "
              "the local gate needs a phone-test log", file=sys.stderr)
        return 2

    phone_only: list = []
    phone_new: list = []
    if a.phone_only:
        if a.phone_only != phone_test_log.PHONE_ONLY:
            print(f"diff-coverage: ERROR - --phone-only must be {phone_test_log.PHONE_ONLY}", file=sys.stderr)
            return 2
        text = phone_test_log.show("HEAD", a.phone_only)
        if text is None:
            print(f"diff-coverage: FAIL - the phone-only list {a.phone_only} is not in HEAD", file=sys.stderr)
            return 1
        try:
            phone_only, phone_new = phone_test_log.effective_phone_only(a.base, "HEAD")
        except phone_test_log.ListError as e:
            print(f"diff-coverage: FAIL - {e}", file=sys.stderr)
            return 1

    report = load_report(a.report)
    changes = changed_lines(a.base)

    # (package, file name) -> tracked measured paths, to detect keys the report cannot attribute.
    owners: dict = {}
    for path in git("ls-files", "-z", "--", "faceblur/src").split("\0"):
        m = SRC_RE.match(path)
        if not m or m.group(1).startswith(NATIVE_SETS):
            continue
        pm = PKG_RE.search(git("show", f"HEAD:{path}"))
        k = (pm.group(1).replace(".", "/") if pm else "", path.rsplit("/", 1)[-1])
        owners.setdefault(k, []).append(path)
    ambiguous = []
    total = covered = 0
    phone_total = phone_covered = 0
    unmeasured = []
    no_code = []
    rows = []
    for path in sorted(changes):
        text = git("show", f"HEAD:{path}")
        m = PKG_RE.search(text)
        pkg = m.group(1).replace(".", "/") if m else ""
        key = (pkg, path.rsplit("/", 1)[-1])
        if len(owners.get(key, [])) > 1:
            ambiguous.append((path, owners[key]))
            continue
        lines = report.get(key)
        if lines is None:
            if SRC_RE.match(path).group(1).startswith(NATIVE_SETS):
                unmeasured.append(path)
            else:
                no_code.append(path)
            continue
        measurable = sorted(n for n in changes[path] if n in lines)
        hit = [n for n in measurable if lines[n]]
        missed = [n for n in measurable if not lines[n]]
        total += len(measurable)
        covered += len(hit)
        if path in phone_only:
            phone_total += len(measurable)
            phone_covered += len(hit)
        rows.append((path, len(hit), len(measurable), missed))

    print(f"diff-coverage: base {a.base[:12]}, report {a.report}")
    for path, h, n, missed in rows:
        tail = f" missed lines {missed}" if missed else ""
        mark = " [phone-only]" if path in phone_only else ""
        print(f"  {path}: {h}/{n} changed executable lines covered{mark}{tail}")
    for path in no_code:
        print(f"  {path}: no executable lines in the Kover report (declarations only)")
    for path in unmeasured:
        print(f"  {path}: NOT MEASURED (Kotlin/Native source set, Kover cannot instrument it)")
        print(f"::warning file={path}::changed code NOT MEASURED by Kover; tests per AC + reviewer note required")
    if ambiguous:
        for path, paths in ambiguous:
            print(f"diff-coverage: FAIL - {path}: same package and file name in {len(paths)} measured source sets "
                  f"({', '.join(paths)}); rename one so the report can attribute its lines", file=sys.stderr)
        return 1
    if a.phone_only:
        for p in phone_new:
            print(f"::warning file={a.phone_only}::{p} is in the phone-only list of HEAD but not of the base: its "
                  "changed lines count like all others until the approved list change is merged")
        changed_lists = [p for p in (a.phone_only, phone_test_log.REQUIRED, phone_test_log.SKIPS)
                         if git("diff", "--name-only", f"{a.base}...HEAD", "--", p).strip()]
        for p in changed_lists:
            print(f"::warning file={p}::{p} changed in this range: needs the approvals named in its header")
        absent = [p for p in phone_only if phone_test_log.show("HEAD", p) is None]
        if absent:
            print(f"diff-coverage: note - {len(absent)} phone-only list entr{'y' if len(absent) == 1 else 'ies'} "
                  "not in this revision (nothing to leave out for them)")

    def pct(c: int, t: int) -> str:
        return f"{c}/{t} = {100.0 * c / t:.1f} %" if t else "0/0 (no changed executable lines)"

    rest_total, rest_covered = total - phone_total, covered - phone_covered
    if phone_total:
        print(f"diff-coverage: all changed lines {pct(covered, total)}; without the phone-only files "
              f"{pct(rest_covered, rest_total)}; phone-only changed lines {phone_total} "
              f"({phone_covered} covered by host tests)")
    gate_total, gate_covered = total, covered
    if phone_total and a.phone_log:
        try:
            problems, summary = phone_test_log.verify(a.phone_log, "HEAD", a.base)
        except OSError as e:
            problems, summary = [f"the log could not be checked: {e}"], []
        if problems:
            for b in problems:
                print(f"diff-coverage: phone-test log NOT VALID - {b}")
            print("diff-coverage: the phone-only lines count like every other changed line")
        else:
            gate_total, gate_covered = rest_total, rest_covered
            for line in summary:
                print(f"diff-coverage: phone-test log: {line}")
            print(f"diff-coverage: {phone_total} phone-only changed lines PHONE-TESTED (valid phone-test log for "
                  f"{git('rev-parse', 'HEAD').strip()[:12]}; behavioural evidence, not coverage)")
    elif phone_total and a.ci_phone_not_measured:
        gate_total, gate_covered = rest_total, rest_covered
        files = [(path, n) for path, _, n, _ in rows if path in phone_only and n]
        for path, n in files:
            print(f"::warning file={path}::{path}: {n} phone-only changed lines NOT MEASURED here (no phone); the "
                  "local merge gate requires a valid phone-test log for this commit")
        print(f"::warning::{phone_total} phone-only changed lines NOT MEASURED here (no phone) in "
              + ", ".join(f"{path} ({n})" for path, n in files)
              + "; the local merge gate requires a valid phone-test log for this commit")
    elif phone_total:
        print("diff-coverage: no phone-test log given: the phone-only lines count like every other changed line")

    if gate_total == 0:
        print("diff-coverage: PASS - no changed executable lines in measured production code"
              + (" outside the phone-tested files" if total else "")
              + (" (some files NOT MEASURED, see above)" if unmeasured else ""))
        return 0
    value = 100.0 * gate_covered / gate_total
    verdict = "PASS" if value >= a.threshold else "FAIL"
    print(f"diff-coverage: {verdict} - {gate_covered}/{gate_total} changed lines covered = {value:.1f} % "
          f"(threshold {a.threshold:.0f} %)")
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
