#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fails unless every named test task produced JUnit XML results with at least one executed test and no failure.

Guards against a test task that ends "successful" without having run anything, e.g. the iOS simulator test task
on a first build (a NoSuchFileException was printed and no result XML was written).

Usage: check-test-results.py <results root> <task> [<task>...]
       e.g. check-test-results.py faceblur/build/test-results jvmTest testAndroidHostTest
Exit codes: 0 ok, 1 missing/empty/failed results, 2 usage error. Standard library only.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    root, tasks = sys.argv[1], sys.argv[2:]
    bad = 0
    for task in tasks:
        files = sorted(glob.glob(os.path.join(root, task, "*.xml")))
        tests = failures = errors = skipped = 0
        for f in files:
            try:
                el = ET.parse(f).getroot()
            except ET.ParseError as e:
                print(f"check-test-results: {task}: unreadable result file {os.path.basename(f)}: {e}",
                      file=sys.stderr)
                bad += 1
                continue
            suites = [el] if el.tag == "testsuite" else list(el.iter("testsuite"))
            for s in suites:
                tests += int(s.get("tests", "0"))
                failures += int(s.get("failures", "0"))
                errors += int(s.get("errors", "0"))
                skipped += int(s.get("skipped", "0"))
        if not files:
            print(f"check-test-results: FAIL - {task}: no JUnit XML result files", file=sys.stderr)
            bad += 1
        elif tests == 0:
            print(f"check-test-results: FAIL - {task}: result files contain 0 tests", file=sys.stderr)
            bad += 1
        elif skipped >= tests:
            print(f"check-test-results: FAIL - {task}: all {tests} tests were skipped", file=sys.stderr)
            bad += 1
        elif failures or errors:
            print(f"check-test-results: FAIL - {task}: {failures} failures, {errors} errors", file=sys.stderr)
            bad += 1
        else:
            print(f"check-test-results: {task}: {tests} tests, {skipped} skipped, 0 failures "
                  f"({len(files)} result files)")
    if bad:
        return 1
    print("check-test-results: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
