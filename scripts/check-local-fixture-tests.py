#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The tests that need the maintainer's local fixtures (config/local-fixture-tests.txt) ran, or are reported as not run.

The list has one entry per line: `<test task> <class>` or `<test task> <class>.<method>` (the class is fully
qualified; the method is the test name of the JUnit XML result). Lines starting with # and blank lines are ignored.
The build excludes exactly these entries by name when no local fixtures are set (public CI).

  --require  <results root> <list>   local merge gate: every entry must match at least one executed test case in
                                     <results root>/<task>/*.xml, and none of the matched cases may have failed,
                                     errored or been skipped. Prints the case count per task and the total.
  --excluded <results root> <list>   public CI: prints that the entries were NOT RUN; fails when a matched case
                                     appears in the results anyway (the exclusion would be broken) or the list is empty.

Exit codes: 0 ok, 1 a violation (printed), 2 usage / unreadable list. Standard library only.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET


def read_list(path):
    entries = []
    with open(path, encoding="utf-8") as fh:
        for n, line in enumerate(fh, 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split(None, 1)
            if len(parts) != 2:
                raise ValueError(f"line {n}: expected '<test task> <class>[.<method>]'")
            entries.append((parts[0], parts[1].strip()))
    if not entries:
        raise ValueError("the list names no test")
    return entries


def cases(root, task):
    """[(classname, name, outcome)] of every test case of the task; outcome in passed|failed|skipped."""
    out = []
    for f in sorted(glob.glob(os.path.join(root, task, "*.xml"))):
        tree = ET.parse(f).getroot()
        for tc in tree.iter("testcase"):
            if tc.find("failure") is not None or tc.find("error") is not None:
                outcome = "failed"
            elif tc.find("skipped") is not None:
                outcome = "skipped"
            else:
                outcome = "passed"
            out.append((tc.get("classname", ""), tc.get("name", ""), outcome))
    return out


def matches(entry, case):
    """The entry names the class, or the class and the test method (a trailing `[target]` of the result name is ignored)."""
    method = re.sub(r"\[[^\]]*\]$", "", case[1])
    return entry == case[0] or entry == f"{case[0]}.{method}"


def main(argv):
    if len(argv) != 3 or argv[0] not in ("--require", "--excluded"):
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        return 2
    mode, root, listing = argv
    try:
        entries = read_list(listing)
    except (OSError, ValueError) as e:
        print(f"local-fixture-tests: cannot read the list ({e})", file=sys.stderr)
        return 2
    tasks = sorted({t for t, _ in entries})
    results = {}
    for t in tasks:
        try:
            results[t] = cases(root, t)
        except ET.ParseError as e:
            print(f"local-fixture-tests: FAIL - unreadable result file of {t}: {e}")
            return 1
    bad = 0
    if mode == "--excluded":
        for t, entry in entries:
            hit = [c for c in results[t] if matches(entry, c)]
            if hit:
                print(f"local-fixture-tests: FAIL - {t}: {entry} was excluded but {len(hit)} case(s) are in the results")
                bad += 1
        print(f"local-fixture-tests: NOT RUN in public CI - {len(entries)} entries ({', '.join(tasks)}) need the "
              f"maintainer's local fixtures and are excluded by name; the local merge gate runs them")
        return 1 if bad else 0
    total = 0
    per_task = {}
    for t, entry in entries:
        hit = [c for c in results[t] if matches(entry, c)]
        if not hit:
            print(f"local-fixture-tests: FAIL - {t}: {entry} matched no executed test case")
            bad += 1
            continue
        for c in hit:
            if c[2] != "passed":
                print(f"local-fixture-tests: FAIL - {t}: {c[0]}.{c[1]} is {c[2]}")
                bad += 1
        total += len(hit)
        per_task[t] = per_task.get(t, 0) + len(hit)
    for t in tasks:
        print(f"local-fixture-tests: {t}: {per_task.get(t, 0)} test cases of the list ran")
    if bad:
        return 1
    print(f"local-fixture-tests: PASS - {len(entries)} entries, {total} test cases ran and passed, none skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
