#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Test results and step count of the local merge gate (scripts/ci-local.sh).

count <snapshot dir>
    Counts the JUnit XML results that ci-local.sh copied right after the build steps (later negative controls rerun
    single tests and overwrite build/test-results, so the live folders are not the build's results). Prints one line
    per task: `ci-local: results <task>: tests=N failures=F errors=E skipped=S`. Exit 1 if there is no result, a task
    with 0 tests, an unreadable file, or any failure or error.

verify-log <ci-local output file>
    Accepts a ci-local.sh output only if: the RESULT line is PASS with steps=N, N > 0 and N equal to the number of
    PASS step lines, no step line is FAIL; there are results lines, all with tests > 0 and 0 failures and 0 errors; and
    the counts agree with the check-test-results lines of the same run (library tasks by name; the verification
    modules' `test` tasks as a multiset). Prints the per-task table. Exit 0 accepted, 1 not accepted, 2 unreadable.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

RESULT_LINE = re.compile(r"^ci-local: results (\S+): tests=(\d+) failures=(\d+) errors=(\d+) skipped=(\d+)$")
CHECK_LINE = re.compile(r"^check-test-results: (\S+): (\d+) tests, ")
STEP_PASS = re.compile(r"^ci-local: --- \S+ PASS ")
STEP_FAIL = re.compile(r"^ci-local: --- \S+ FAIL ")
RESULT_PASS = re.compile(r"^ci-local: RESULT PASS - steps=(\d+)\b")


def count(snapshot: str) -> int:
    rows = {}
    bad = 0
    for f in sorted(glob.glob(os.path.join(snapshot, "**", "*.xml"), recursive=True)):
        rel = os.path.relpath(os.path.dirname(f), snapshot)  # e.g. faceblur/jvmTest, verification/jpeg-privacy/test
        parts = rel.split(os.sep)
        task = (":".join(parts[:-1]) + ":" + parts[-1]) if len(parts) > 1 else parts[0]
        r = rows.setdefault(task, [0, 0, 0, 0])
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            print(f"ci-local-results: FAIL - {task}: unreadable result file {os.path.basename(f)}")
            bad += 1
            continue
        for s in [root] if root.tag == "testsuite" else root.findall("testsuite"):
            for i, k in enumerate(("tests", "failures", "errors", "skipped")):
                r[i] += int(s.get(k, "0") or 0)
    if not rows:
        print("ci-local-results: FAIL - no JUnit XML results in the snapshot")
        return 1
    for task in sorted(rows):
        t, f, e, s = rows[task]
        print(f"ci-local: results {task}: tests={t} failures={f} errors={e} skipped={s}")
        if t == 0 or f or e:
            print(f"ci-local-results: FAIL - {task}: {t} tests, {f} failures, {e} errors")
            bad += 1
    return 1 if bad else 0


def verify_log(path: str) -> int:
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
    except OSError as e:
        print(f"ci-local-results: cannot read the log ({type(e).__name__})")
        return 2
    problems = []
    passes = sum(1 for line in lines if STEP_PASS.match(line))
    fails = [line for line in lines if STEP_FAIL.match(line)]
    declared = [int(m.group(1)) for m in (RESULT_PASS.match(line) for line in lines) if m]
    if fails:
        problems.append(f"{len(fails)} step(s) FAIL")
    if len(declared) != 1:
        problems.append("no single 'RESULT PASS - steps=N' line")
    elif declared[0] <= 0 or declared[0] != passes:
        problems.append(f"RESULT declares {declared[0]} steps, {passes} PASS step lines")
    results = {}
    for line in lines:
        m = RESULT_LINE.match(line)
        if m:
            results[m.group(1)] = tuple(int(x) for x in m.groups()[1:])
    if not results:
        problems.append("no results lines (snapshot not counted)")
    for task, (t, f, e, s) in sorted(results.items()):
        print(f"  {task}: tests={t} failures={f} errors={e} skipped={s}")
        if t == 0 or f or e:
            problems.append(f"{task}: {t} tests, {f} failures, {e} errors")
    checked = [(m.group(1), int(m.group(2))) for m in (CHECK_LINE.match(line) for line in lines) if m]
    lib = {}
    ver = []
    for task, n in checked:
        if task == "test":
            ver.append(n)
        else:
            lib.setdefault(task, n)
    for task, n in lib.items():
        mine = results.get(f"faceblur:{task}")
        if mine is None or mine[0] != n:
            problems.append(f"faceblur:{task}: header {mine[0] if mine else 'missing'} tests, check-test-results {n}")
    mine_ver = sorted(v[0] for k, v in results.items() if k.startswith("verification:") and k.endswith(":test"))
    if sorted(ver) != mine_ver:
        problems.append(f"verification test counts: header {mine_ver}, check-test-results {sorted(ver)}")
    if not lib:
        problems.append("no check-test-results lines for the library tasks")
    for p in problems:
        print(f"ci-local-results: NOT ACCEPTED - {p}")
    if problems:
        return 1
    print(f"ci-local-results: ACCEPTED - {declared[0]} steps passed, results agree with check-test-results")
    return 0


def main(argv: list) -> int:
    if len(argv) == 2 and argv[0] == "count":
        return count(argv[1])
    if len(argv) == 2 and argv[0] == "verify-log":
        return verify_log(argv[1])
    print("usage: ci-local-results.py count <snapshot dir> | verify-log <ci-local output>", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
