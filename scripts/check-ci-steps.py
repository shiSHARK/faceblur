#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The CI workflow and the local merge gate run the same checks (both directions).

For each job gates, build and verification of .github/workflows/ci.yml:
  - every step with `run:` is either exactly `run: sh scripts/ci-steps.sh <step>` or one of the CI-only steps
    named in CI_ONLY below (tool installs, path filter, branch-push summary, tree-hash reuse lookup, presence probe);
    any other inline `run:` step fails (a check that would run in CI but not locally);
  - the ci-steps.sh steps of the job, in workflow order, equal `sh scripts/ci-steps.sh --list <job>`;
  - the list is not empty, and `--list` succeeds.
Steps with `uses:` (checkout, setup, caches, uploads) are not checks and are not compared.

Usage: check-ci-steps.py [repository root, default .]
Exit codes: 0 consistent, 1 a mismatch (named), 2 the workflow or the step list cannot be read.
"""
import os
import re
import subprocess
import sys

JOBS = ("gates", "build", "verification")
CI_ONLY = {
    "gates": {
        "Path filters (docs-only branches, verification build)",
        "Branch push without CI/build changes - build and verification only on manual dispatch",
        "Tree-hash reuse on main (same tree already passed; fail-safe)",
        "Install gitleaks (pinned, checksum verified)",
        "Install opengrep and osv-scanner (pinned, checksum verified)",
    },
    "build": {"Install osv-scanner (pinned, checksum verified)"},
    "verification": {"Verification build present"},
}
STEP_RUN = re.compile(r"^sh scripts/ci-steps\.sh ([a-z0-9-]+)$")


def workflow_steps(text: str) -> dict:
    """{job: [(step name, run value or None)]} for the jobs in JOBS (line-based; the workflow's own layout)."""
    jobs = {}
    job = None
    in_jobs = False
    step = None
    for line in text.splitlines():
        if line.startswith("jobs:"):
            in_jobs = True
            continue
        if not in_jobs:
            continue
        m = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if m:
            job = m.group(1)
            jobs.setdefault(job, [])
            step = None
            continue
        if job is None:
            continue
        if line.startswith("      - "):
            step = {"name": None, "run": None}
            jobs[job].append(step)
            line = "        " + line[len("      - "):]
        if step is None:
            continue
        m = re.match(r"^        (name|run): ?(.*)$", line)
        if m:
            step[m.group(1)] = m.group(2).strip()
    return {j: [(s["name"], s["run"]) for s in jobs.get(j, [])] for j in JOBS}


def main(argv: list) -> int:
    root = argv[0] if argv else "."
    wf = os.path.join(root, ".github", "workflows", "ci.yml")
    try:
        with open(wf, encoding="utf-8") as f:
            steps = workflow_steps(f.read())
    except OSError as e:
        print(f"check-ci-steps: cannot read the workflow ({type(e).__name__})")
        return 2
    bad = 0
    for job in JOBS:
        try:
            res = subprocess.run(["sh", os.path.join(root, "scripts", "ci-steps.sh"), "--list", job],
                                 cwd=root, capture_output=True, text=True, timeout=60)
        except (OSError, subprocess.SubprocessError) as e:
            print(f"check-ci-steps: FAIL - `ci-steps.sh --list {job}` could not run ({type(e).__name__})")
            return 2
        listed = res.stdout.split()
        if res.returncode != 0 or not listed:
            print(f"check-ci-steps: FAIL - `ci-steps.sh --list {job}` failed (exit {res.returncode}) or is empty")
            return 2
        used = []
        for name, run in steps[job]:
            if run is None:
                continue
            m = STEP_RUN.match(run)
            if m:
                used.append(m.group(1))
            elif name not in CI_ONLY[job]:
                print(f"check-ci-steps: FAIL - job {job}: step '{name}' runs an inline command; move the check to "
                      f"scripts/ci-steps.sh or (if it is not a check) add it to the CI-only list")
                bad += 1
        if used != listed:
            print(f"check-ci-steps: FAIL - job {job}: workflow runs {used}, ci-steps.sh --list {job} gives {listed}")
            bad += 1
        else:
            print(f"check-ci-steps: job {job}: {len(used)} steps, workflow and local list equal")
    if bad:
        return 1
    print("check-ci-steps: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
