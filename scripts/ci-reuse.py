#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Tree-hash reuse for CI on main: may a job be skipped because it already passed on the SAME tree?

A job may be skipped only if, within --max-age-hours, a completed run of the same workflow file in this repository
(event push or workflow_dispatch, not a fork, not the current run) whose head commit has exactly the tree --tree
(`git rev-parse HEAD^{tree}`; never compared by commit ID, so squash and merge commits with an identical tree match)
contains a job with exactly that name that completed with conclusion "success" for that head commit, and no completed
run of that tree in the window has that job concluded "failure", "timed_out" or "startup_failure" in ANY attempt
(jobs are listed with filter=all, so a failure hidden by a re-run is doubt too). Cancelled or skipped jobs are
ignored. A re-run of the current run (--run-attempt > 1) never reuses: its own earlier failure would be invisible. The tree also fixes the workflow file and every
script, so the reused job ran the same steps on the same sources.

FAIL-SAFE: any doubt prints false for every job and exits 0: no token, an API or network error, an unexpected
response, a missing field, no match, a job summary that cannot be written. The outputs are printed last, after the
summary. Only a usage error exits 2. The token is read from GH_TOKEN and never printed.

Output (for $GITHUB_OUTPUT), one pair per --job KEY=NAME, in the order given:
  reuse-KEY=true|false
  reuse-KEY-run=<run id of the reused run, empty when false>
A short report goes to stderr and, when GITHUB_STEP_SUMMARY is set, to the job summary.

Usage: ci-reuse.py --repo OWNER/REPO --workflow-path .github/workflows/ci.yml --tree <40-hex tree> \
           --exclude-run <run id> --run-attempt <n> --max-age-hours 24 --job KEY=NAME [--job KEY=NAME ...] \
           [--fake-api DIR] [--now YYYY-MM-DDTHH:MM:SSZ]
  --fake-api DIR (self-tests only): runs.json answers the first page of the run list, jobs-<run id>.json the job
  list of a run (only when asked with filter=all); any other request fails like a network error.
"""
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

PAGES = 5  # at most 500 runs inspected
MAX_CANDIDATES = 20  # at most 20 job lists fetched
EVENTS = ("push", "workflow_dispatch")
BAD = ("failure", "timed_out", "startup_failure")
HEX40 = re.compile(r"^[0-9a-f]{40}$")
KEY = re.compile(r"^[a-z][a-z0-9-]*$")
TIME = "%Y-%m-%dT%H:%M:%SZ"


class Doubt(Exception):
    """Anything that is not a clear answer: the caller runs every job."""


def usage(msg: str) -> int:
    print(f"ci-reuse: usage error: {msg}", file=sys.stderr)
    return 2


def parse_args(argv: list) -> dict:
    opts = {"job": []}
    i = 0
    names = ("--repo", "--workflow-path", "--tree", "--exclude-run", "--run-attempt", "--max-age-hours", "--job",
             "--fake-api", "--now")
    while i < len(argv):
        a = argv[i]
        if a not in names or i + 1 >= len(argv):
            raise ValueError(f"unknown option or missing value: {a}")
        v = argv[i + 1]
        if a == "--job":
            key, sep, name = v.partition("=")
            if not sep or not KEY.match(key) or not name:
                raise ValueError(f"--job needs KEY=NAME (KEY lower-case): {v}")
            opts["job"].append((key, name))
        else:
            opts[a[2:]] = v
        i += 2
    for req in ("repo", "workflow-path", "tree", "exclude-run", "run-attempt", "max-age-hours"):
        if req not in opts:
            raise ValueError(f"--{req} is required")
    if not opts["job"]:
        raise ValueError("at least one --job KEY=NAME is required")
    if not HEX40.match(opts["tree"]):
        raise ValueError("--tree must be a 40-hex tree hash")
    return opts


def api_getter(repo: str, fake: str):
    if fake:
        def get(path: str):
            m = re.match(r"^/repos/[^/]+/[^/]+/actions/workflows/[^/]+/runs\?(.*)$", path)
            if m:
                query = urllib.parse.parse_qs(m.group(1))
                if query.get("page", ["1"])[0] != "1":
                    return {"total_count": 0, "workflow_runs": []}
                file = os.path.join(fake, "runs.json")
            else:
                m = re.match(r"^/repos/[^/]+/[^/]+/actions/runs/(\d+)/jobs\?(.*)$", path)
                if not m or urllib.parse.parse_qs(m.group(2)).get("filter") != ["all"]:
                    raise Doubt("fake API: unexpected request")
                file = os.path.join(fake, f"jobs-{m.group(1)}.json")
            try:
                with open(file, encoding="utf-8") as f:
                    return json.load(f)
            except (OSError, ValueError) as e:
                raise Doubt(f"fake API: {type(e).__name__}") from None
        return get

    token = os.environ.get("GH_TOKEN", "")
    if not token:
        raise Doubt("GH_TOKEN is not set")
    base = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
    if not base.startswith("https://"):
        raise Doubt("GITHUB_API_URL is not https")

    def get(path: str):
        req = urllib.request.Request(base + path, headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "faceblur-ci-reuse",
        })
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except Exception as e:  # any network, HTTP or JSON error is doubt (message without the URL or token)
            raise Doubt(f"API request failed ({type(e).__name__})") from None
    return get


def field(obj, *keys):
    for k in keys:
        if not isinstance(obj, dict) or k not in obj:
            raise Doubt(f"API response without '{k}'")
        obj = obj[k]
    return obj


def decide(opts: dict) -> dict:
    """Returns {key: run id} for the jobs that may be reused; raises Doubt on any uncertainty."""
    repo = opts["repo"]
    tree = opts["tree"]
    wf_path = opts["workflow-path"]
    exclude = str(opts["exclude-run"])
    if opts["run-attempt"] != "1":
        raise Doubt(f"re-run (attempt {opts['run-attempt']}): a re-run never reuses")
    try:
        hours = float(opts["max-age-hours"])
    except ValueError:
        raise Doubt("--max-age-hours is not a number") from None
    if not 0 < hours <= 168:
        raise Doubt("--max-age-hours must be in (0, 168]")
    now = datetime.strptime(opts["now"], TIME).replace(tzinfo=timezone.utc) if "now" in opts else \
        datetime.now(timezone.utc)
    since = now - timedelta(hours=hours)
    get = api_getter(repo, opts.get("fake-api", ""))
    wf_file = urllib.parse.quote(os.path.basename(wf_path), safe="")
    created = urllib.parse.quote(">=" + since.strftime(TIME), safe="")

    candidates = []
    for page in range(1, PAGES + 1):
        data = get(f"/repos/{repo}/actions/workflows/{wf_file}/runs?status=completed&per_page=100"
                   f"&created={created}&page={page}")
        runs = field(data, "workflow_runs")
        if not isinstance(runs, list):
            raise Doubt("workflow_runs is not a list")
        for run in runs:
            if str(field(run, "id")) == exclude:
                continue
            if field(run, "status") != "completed":
                continue
            if field(run, "event") not in EVENTS:
                continue
            if str(field(run, "path")).split("@", 1)[0] != wf_path:
                continue
            if field(run, "repository", "full_name") != repo or field(run, "head_repository", "full_name") != repo:
                continue
            started = datetime.strptime(field(run, "created_at"), TIME).replace(tzinfo=timezone.utc)
            if started < since:
                continue
            run_tree = field(run, "head_commit", "tree_id")
            if not isinstance(run_tree, str) or not HEX40.match(run_tree):
                raise Doubt("head_commit.tree_id is not a tree hash")
            if run_tree != tree:
                continue
            head_sha = field(run, "head_sha")
            candidates.append((started, str(field(run, "id")), head_sha))
        if len(runs) < 100:
            break
    else:
        raise Doubt(f"more than {PAGES * 100} runs in the window")

    candidates.sort(reverse=True)  # newest first
    if len(candidates) > MAX_CANDIDATES:
        raise Doubt(f"more than {MAX_CANDIDATES} runs of this tree in the window")
    wanted = dict(opts["job"])
    found = {}
    failed = set()
    for _, run_id, head_sha in candidates:
        data = get(f"/repos/{repo}/actions/runs/{run_id}/jobs?filter=all&per_page=100")
        jobs = field(data, "jobs")
        if not isinstance(jobs, list):
            raise Doubt("jobs is not a list")
        for key, name in wanted.items():
            for job in jobs:
                if field(job, "name") != name or field(job, "status") != "completed":
                    continue
                conclusion = field(job, "conclusion")
                if conclusion in BAD:
                    failed.add(key)
                elif conclusion == "success" and field(job, "head_sha") == head_sha and key not in found:
                    found[key] = run_id
    return {k: v for k, v in found.items() if k not in failed}


def main(argv: list) -> int:
    try:
        opts = parse_args(argv)
    except ValueError as e:
        return usage(str(e))
    try:
        reused = decide(opts)
        reason = None
    except Doubt as e:
        reused = {}
        reason = str(e)
    except Exception as e:  # fail-safe: an unexpected error must never skip a job
        reused = {}
        reason = f"unexpected error ({type(e).__name__})"
    def lines() -> list:
        report = []
        if reason:
            report.append(f"Tree-hash reuse: lookup failed ({reason}); every job runs.")
        for key, name in opts["job"]:
            run_id = reused.get(key, "")
            if run_id:
                report.append(f"Tree-hash reuse: job '{name}' skipped: it passed on the same tree {opts['tree']} in "
                              f"run {run_id}.")
            else:
                report.append(f"Tree-hash reuse: job '{name}' runs (no earlier success on tree {opts['tree']} "
                              f"without doubt).")
        return report

    # The summary is written BEFORE the outputs are printed; if it cannot be written, every job runs.
    summary = os.environ.get("GITHUB_STEP_SUMMARY", "")
    if summary:
        try:
            with open(summary, "a", encoding="utf-8") as f:
                f.write("\n".join(lines()) + "\n")
        except Exception as e:  # fail-safe: never skip a job without a written record
            reused = {}
            reason = f"job summary not writable ({type(e).__name__})"
    if reason:
        print(f"::warning::ci-reuse: lookup failed, every job runs: {reason}", file=sys.stderr)
    for line in lines():
        print(f"ci-reuse: {line}", file=sys.stderr)
    for key, _ in opts["job"]:
        run_id = reused.get(key, "")
        print(f"reuse-{key}={'true' if run_id else 'false'}")
        print(f"reuse-{key}-run={run_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
