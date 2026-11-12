#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Phone-test logs of the Android device tests: format, checks and the list files they depend on.

A phone-test log is written by scripts/phone-device-tests.sh for ONE commit: the device-test APK is built from that
checkout, run on the configured physical phone with the instrumentation runner (no filters, no retries) and the raw
runner output is stored unmodified with a machine-checkable header. This module checks such a log; the changed-lines
coverage gate (scripts/diff-coverage.py) accepts it as the evidence for the lines of the phone-only files
(config/coverage/phone-only.txt) only when `verify` passes. The log is behavioural evidence, not coverage.

`verify` fails the log unless ALL of these hold (every rule is read from the commit under test, never the work tree;
a list that RELAXES a rule counts only with the entries the base has too - see "Lists" below):
  - format line, every header field, a 40-hex commit and tree equal to `git rev-parse <rev>` and `<rev>^{tree}`;
  - serial and model equal FACEBLUR_PHONE_SERIAL / FACEBLUR_PHONE_MODEL (both required), no emulator property
    (ro.kernel.qemu, ro.boot.qemu, ro.hardware goldfish/ranchu, emulator-* serial);
  - the APK was built in this run (build exit 0, the file did not exist before); its SHA-256 is recorded by the
    producer, not verified here (the APK is not kept);
  - fixtures = exactly the files of config/device-tests/fixtures.txt at the commit, in that order, as
    `<file name>:<SHA-256>`: the model with the fileName and sha256 pinned in the maintainer's local fixtures
    (yunet/yunet.properties of the folder named by FACEBLUR_LOCAL_FIXTURES, which must also list the model with that
    SHA-256 in its MANIFEST.sha256), every `local` file with its SHA-256 in that MANIFEST.sha256, every tracked file
    with its SHA-256 in config/data-admission/register.tsv (so the log alone shows that no photo was placed);
  - install and instrument commands are exactly the canonical ones (no -e filter, no other runner, no retry option);
  - the SHA-256 of the stored runner output equals raw_sha256 (consistency, not tamper-proofing);
  - the runner output: final INSTRUMENTATION_CODE -1, `OK (N tests)`, no failure, error, ignored or skipped test (one
    exception, below), every test
    started exactly once (a second start is a retry and fails), N = numtests = number of @Test methods in
    faceblur/src/androidDeviceTest of the commit, no @Ignore in those sources; an assumption skip is accepted ONLY
    for a test of config/device-tests/expected-skips.txt and only when the header's api_level is below the minimum
    level listed there (an ignored test is never accepted);
  - every test of config/device-tests/required-tests.txt is present and passed (or an accepted expected skip), every
    test of config/device-tests/expected-skips.txt is present, and passed + accepted skips = the @Test count; the
    result reports "N passed, K expected skips (names)" and names each skipped test's other environment as NOT RUN;
  - the log names no shared-storage path (/sdcard, /storage/, DCIM): no photo data is involved.

Lists: a range cannot relax its own gate. The phone-only list (which lines may leave the 80 %) and the expected skips
(which skips are accepted) take effect as base AND rev: an entry counts only if both have it, and a skip uses the
stricter minimum API level of the two (a skip is accepted only BELOW its minimum, so that is the LOWER value). A new
entry or a raised minimum therefore takes effect only after its approved change is merged. Lists that only make the gate stricter (required tests, fixtures) are read from rev. `verify` uses
--base, default `git merge-base origin/main <rev>`; a base that does not resolve fails the log.

Usage:
  phone_test_log.py verify --log <file> [--rev HEAD] [--base <rev>]
                                                            exit 0 valid, 1 not valid (reasons printed), 2 usage/IO
  phone_test_log.py needed --base <rev> [--rev HEAD]        exit 0 a log is required for base..rev (also when the
                                                            base is empty or does not resolve: fail closed),
                                                            1 not required, 2 any other error
  phone_test_log.py count-tests [--rev HEAD]                prints the number of @Test methods (exit 1 on @Ignore)
  phone_test_log.py lists [--rev HEAD]                      checks the format of the three list files (exit 0/1)
Standard library only.
"""
import argparse
import hashlib
import os
import re
import subprocess
import sys

FORMAT_LINE = "faceblur-phone-test-log 1"
RAW_BEGIN = "--- instrumentation output (unmodified) ---"
RAW_END = "--- end of instrumentation output ---"
PHONE_ONLY = "config/coverage/phone-only.txt"
PHONE_ONLY_HEADER = ("# SPDX-License-Identifier: Apache-2.0", "# faceblur phone-only coverage list, format 1")
PHONE_ONLY_MAX = 8
PHONE_ONLY_RE = re.compile(r"^faceblur/src/androidMain/kotlin/[A-Za-z0-9_/]+\.kt$")
REQUIRED = "config/device-tests/required-tests.txt"
REQUIRED_HEADER = ("# SPDX-License-Identifier: Apache-2.0", "# faceblur required phone tests, format 1")
SKIPS = "config/device-tests/expected-skips.txt"
SKIPS_HEADER = ("# SPDX-License-Identifier: Apache-2.0", "# faceblur expected assumption skips, format 1")
TEST_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_.]*\.[A-Za-z_][A-Za-z0-9_]*::[A-Za-z_][A-Za-z0-9_]*$")
FIXTURES = "config/device-tests/fixtures.txt"
LOCAL_ENV = "FACEBLUR_LOCAL_FIXTURES"
MODEL_PIN = "yunet/yunet.properties"  # inside the local fixtures folder
REGISTER = "config/data-admission/register.tsv"
# The evidence machinery itself: a change to it is exercised against the phone before it is merged.
PHONE_TOOLING = ("scripts/phone_test_log.py", "scripts/phone-device-tests.sh", "scripts/diff-coverage.py")
DEVICE_TEST_DIR = "faceblur/src/androidDeviceTest/"
TEST_ANNOTATION_RE = re.compile(r"^[ \t]*@(?:org\.junit\.)?Test\b(?![.\w])", re.M)
IGNORE_RE = re.compile(r"^[ \t]*@(?:org\.junit\.)?Ignore\b", re.M)
TEST_PACKAGE = "io.github.shishark.faceblur.test"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
APK = "faceblur/build/outputs/apk/androidTest/faceblur-androidTest.apk"
BUILD_COMMAND = "./gradlew --console=plain :faceblur:fetchYunetModel :faceblur:assembleAndroidDeviceTest"
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
SERIAL_RE = re.compile(r"^[A-Za-z0-9]+$")
SHARED_STORAGE_RE = re.compile(r"/sdcard|/storage/|DCIM", re.I)
HEADER_FIELDS = ("commit", "tree", "serial", "model", "manufacturer", "android_release", "api_level", "qemu_props",
                 "emulator", "start", "end", "build_command", "build_exit", "apk", "apk_built_in_this_run",
                 "apk_sha256", "test_package", "runner", "install_command", "instrument_command", "instrument_exit",
                 "fixtures", "source_test_methods", "raw_sha256")


class ListError(Exception):
    pass


def git(*args: str) -> str:
    res = subprocess.run(["git", *args], capture_output=True, text=True)
    if res.returncode != 0:
        raise OSError(f"git {' '.join(args[:2])} failed: {res.stderr.strip()}")
    return res.stdout


def show(rev: str, path: str):
    """File text at rev, or None if the file does not exist there."""
    res = subprocess.run(["git", "show", f"{rev}:{path}"], capture_output=True, text=True)
    return res.stdout if res.returncode == 0 else None


def entries(text: str, header: tuple, name: str) -> list:
    lines = text.splitlines()
    if tuple(lines[:2]) != header:
        raise ListError(f"{name}: the first two lines must be exactly: {' / '.join(header)}")
    return [(i + 1, ln) for i, ln in enumerate(lines) if i >= 2 and ln.strip() and not ln.lstrip().startswith("#")]


def parse_phone_only(text: str) -> list:
    """Paths of the phone-only list; ListError if the format is not exact."""
    paths = []
    for nr, ln in entries(text, PHONE_ONLY_HEADER, PHONE_ONLY):
        parts = ln.split("\t")
        if len(parts) != 2 or not PHONE_ONLY_RE.match(parts[0]) or not parts[1].strip():
            raise ListError(f"{PHONE_ONLY}:{nr}: expected '<faceblur/src/androidMain/kotlin/...kt><TAB><reason>'")
        if parts[0] in paths:
            raise ListError(f"{PHONE_ONLY}:{nr}: duplicate entry")
        paths.append(parts[0])
    if len(paths) > PHONE_ONLY_MAX:
        raise ListError(f"{PHONE_ONLY}: {len(paths)} entries, at most {PHONE_ONLY_MAX} (the list is fixed and short)")
    return paths


def parse_required(text: str) -> list:
    """Required test names in order; ListError if the format is not exact."""
    names = []
    for nr, ln in entries(text, REQUIRED_HEADER, REQUIRED):
        if not TEST_NAME_RE.match(ln.strip()) or ln != ln.strip():
            raise ListError(f"{REQUIRED}:{nr}: expected '<class>::<method>'")
        if ln in names:
            raise ListError(f"{REQUIRED}:{nr}: duplicate test")
        names.append(ln)
    if not names:
        raise ListError(f"{REQUIRED}: no required test")
    return names


def parse_skips(text: str) -> dict:
    """{test name: (minimum API level, other environment)}; ListError if the format is not exact."""
    skips = {}
    for nr, ln in entries(text, SKIPS_HEADER, SKIPS):
        parts = ln.split("\t")
        if (len(parts) != 4 or not TEST_NAME_RE.match(parts[0]) or not parts[1].isdigit()
                or not parts[2].strip() or not parts[3].strip()):
            raise ListError(f"{SKIPS}:{nr}: expected '<class>::<method><TAB><minimum API level><TAB><other "
                            "environment><TAB><reason>'")
        if parts[0] in skips:
            raise ListError(f"{SKIPS}:{nr}: duplicate test")
        skips[parts[0]] = (int(parts[1]), parts[2].strip())
    return skips


def load_tests(rev: str):
    """(required names, expected skips) at rev; every expected skip must be a required test."""
    names = load_list(rev, REQUIRED, parse_required)
    skips = load_list(rev, SKIPS, parse_skips)
    for t in skips:
        if t not in names:
            raise ListError(f"{SKIPS}: {t} is not in {REQUIRED}")
    return names, skips


def effective_phone_only(base: str, rev: str):
    """(paths in both lists, paths only in rev's list). rev's list must be well-formed (ListError); a base without a
    well-formed list relaxes nothing."""
    head = load_list(rev, PHONE_ONLY, parse_phone_only)
    text = show(base, PHONE_ONLY)
    try:
        old = parse_phone_only(text) if text is not None else []
    except ListError:
        old = []
    return [p for p in head if p in old], [p for p in head if p not in old]


def effective_skips(base: str, skips: dict) -> dict:
    """Rows of rev's expected skips that the base has too, with the STRICTER minimum API level of the two. A skip is
    accepted only BELOW the minimum, so the stricter value is the lower one: a range that raises a minimum gets no
    relief, a range that lowers one is stricter at once."""
    text = show(base, SKIPS)
    try:
        old = parse_skips(text) if text is not None else {}
    except ListError:
        old = {}
    return {t: (min(m, old[t][0]), env) for t, (m, env) in skips.items() if t in old}


def exists(rev: str, path: str) -> bool:
    """True if path is a file at rev (works for binary files, unlike show)."""
    return subprocess.run(["git", "cat-file", "-e", f"{rev}:{path}"], capture_output=True).returncode == 0


def resolve(rev: str):
    """Full commit id of rev, or None when it is empty or does not resolve."""
    if not rev.strip():
        return None
    res = subprocess.run(["git", "rev-parse", "--verify", "-q", f"{rev}^{{commit}}"], capture_output=True, text=True)
    return res.stdout.strip() if res.returncode == 0 and res.stdout.strip() else None


def local_fixtures():
    """(pins, manifest) of the local fixtures folder named by FACEBLUR_LOCAL_FIXTURES; ListError when it is unusable.

    The model and the `local` files are not in the repository: their SHA-256 come from the folder's MANIFEST.sha256
    (`<sha256>  <relative path>`) and its model pins; the build verifies every listed file against that manifest."""
    folder = os.environ.get(LOCAL_ENV, "")
    if not folder or not os.path.isdir(folder):
        raise ListError(f"{LOCAL_ENV} must name the local fixtures folder (the device-test model and local files)")
    try:
        with open(os.path.join(folder, MODEL_PIN), encoding="utf-8") as fh:
            pins = dict(ln.split("=", 1) for ln in fh.read().splitlines() if "=" in ln and not ln.startswith("#"))
        with open(os.path.join(folder, "MANIFEST.sha256"), encoding="utf-8") as fh:
            manifest = {}
            for ln in fh.read().splitlines():
                m = re.match(r"^([0-9a-f]{64})  (\S.*)$", ln)
                if m:
                    manifest[m.group(2)] = m.group(1)
    except OSError as e:
        raise ListError(f"the local fixtures folder is incomplete ({type(e).__name__})")
    return pins, manifest


def expected_fixtures(rev: str):
    """`name:sha256` of every fixture of the fixtures list at rev, in order; ListError when it cannot be derived."""
    text = show(rev, FIXTURES)
    if text is None:
        raise ListError(f"{FIXTURES} does not exist at {rev}")
    register = {}
    for ln in (show(rev, REGISTER) or "").splitlines():
        parts = ln.split("\t")
        if len(parts) >= 2:
            register[parts[0]] = parts[1]
    out = []
    for ln in text.splitlines():
        parts = ln.split()
        if not parts or parts[0].startswith("#"):
            continue
        if len(parts) != 2:
            raise ListError(f"{FIXTURES}: expected '<kind> <value>': {ln.strip()[:80]}")
        kind, value = parts
        if kind == "model":
            pins, manifest = local_fixtures()
            if value != pins.get("fileName") or not HEX64.match(pins.get("sha256", "")):
                raise ListError(f"{FIXTURES}: model {value} is not the fileName pinned in the local fixtures")
            if manifest.get(f"yunet/{value}") != pins["sha256"]:
                raise ListError(f"{FIXTURES}: the local manifest does not list model {value} with its pinned SHA-256")
            out.append(f"{value}:{pins['sha256']}")
        elif kind == "local":
            _, manifest = local_fixtures()
            if not value.startswith("images/") or not HEX64.match(manifest.get(value, "")):
                raise ListError(f"{FIXTURES}: {value} is not an images/ file listed in the local manifest")
            out.append(f"{value.rsplit('/', 1)[-1]}:{manifest[value]}")
        elif kind == "tracked":
            if not value.startswith("testdata/") or not exists(rev, value) or not HEX64.match(register.get(value, "")):
                raise ListError(f"{FIXTURES}: {value} is not a tracked, registered file under testdata/")
            out.append(f"{value.rsplit('/', 1)[-1]}:{register[value]}")
        else:
            raise ListError(f"{FIXTURES}: unknown kind {kind}")
    if not out:
        raise ListError(f"{FIXTURES} names no fixture")
    return out


def load_list(rev: str, path: str, parser):
    text = show(rev, path)
    if text is None:
        raise ListError(f"{path} does not exist at {rev}")
    return parser(text)


def count_tests(rev: str):
    """(number of @Test methods, files with @Ignore) in the device-test sources at rev."""
    files = [p for p in git("ls-tree", "-r", "--name-only", rev, "--", DEVICE_TEST_DIR).splitlines()
             if p.endswith((".kt", ".java"))]
    total, ignored = 0, []
    for p in files:
        text = show(rev, p) or ""
        total += len(TEST_ANNOTATION_RE.findall(text))
        if IGNORE_RE.search(text):
            ignored.append(p)
    return total, ignored


def parse_instrumentation(raw: str):
    """(status blocks [(dict, code)], result dict, final code or None, problems)."""
    blocks, result, final, problems = [], {}, None, []
    cur, key, target = {}, None, None
    for line in raw.splitlines():
        m = re.match(r"^INSTRUMENTATION_STATUS: ([A-Za-z_]+)=(.*)$", line)
        if m:
            cur[m.group(1)] = m.group(2)
            key, target = m.group(1), cur
            continue
        m = re.match(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)$", line)
        if m:
            blocks.append((cur, int(m.group(1))))
            cur, key, target = {}, None, None
            continue
        m = re.match(r"^INSTRUMENTATION_RESULT: ([A-Za-z_]+)=(.*)$", line)
        if m:
            result[m.group(1)] = m.group(2)
            key, target = m.group(1), result
            continue
        m = re.match(r"^INSTRUMENTATION_CODE: (-?\d+)$", line)
        if m:
            final = int(m.group(1))
            key, target = None, None
            continue
        if line.startswith(("INSTRUMENTATION_FAILED", "INSTRUMENTATION_ABORTED")) or "Process crashed" in line:
            problems.append(f"runner reported: {line.strip()[:120]}")
            continue
        if key is not None and target is not None:
            target[key] += "\n" + line
    if cur:
        problems.append("runner output ends inside a status block (truncated run)")
    return blocks, result, final, problems


def read_log(path: str):
    """(header text, raw runner output bytes) or (None, None) if the raw section is incomplete."""
    with open(path, "rb") as fh:
        data = fh.read()
    begin = ("\n" + RAW_BEGIN + "\n").encode()
    end = ("\n" + RAW_END + "\n").encode()
    b = data.find(begin)
    e = data.find(end)
    if b < 0 or e < 0 or e < b:
        return None, None
    return data[:b].decode("utf-8", errors="replace"), data[b + len(begin):e + 1]


def verify(log_path: str, rev: str, base: str = None):
    """(problems, summary lines) of the log for rev; an empty problem list = valid. base: see "Lists" above."""
    bad = []
    try:
        head, raw_bytes = read_log(log_path)
    except OSError as e:
        return [f"cannot read the log: {e.strerror}"], []
    if head is None:
        return ["the log has no complete instrumentation output section"], []
    raw = raw_bytes.decode("utf-8", errors="replace").replace("\r\n", "\n")
    lines = head.splitlines()
    if not lines or lines[0] != FORMAT_LINE:
        return [f"first line is not '{FORMAT_LINE}'"], []
    h = {}
    for ln in lines[1:]:
        if "=" in ln:
            k, v = ln.split("=", 1)
            if k in h:
                bad.append(f"header field {k} appears twice")
            h[k] = v
    missing = [f for f in HEADER_FIELDS if f not in h]
    if missing:
        return bad + [f"header fields missing: {', '.join(missing)}"], []

    commit = git("rev-parse", "--verify", f"{rev}^{{commit}}").strip()
    tree = git("rev-parse", f"{rev}^{{tree}}").strip()
    if not HEX40.match(h["commit"]) or h["commit"] != commit:
        bad.append(f"commit {h['commit'][:12]} is not the commit under test {commit[:12]}")
    if not HEX40.match(h["tree"]) or h["tree"] != tree:
        bad.append(f"tree {h['tree'][:12]} is not the tree under test {tree[:12]}")

    want_serial = os.environ.get("FACEBLUR_PHONE_SERIAL", "")
    want_model = os.environ.get("FACEBLUR_PHONE_MODEL", "")
    if not want_serial or not want_model:
        bad.append("FACEBLUR_PHONE_SERIAL and FACEBLUR_PHONE_MODEL must name the test phone")
    elif h["serial"] != want_serial or h["model"] != want_model:
        bad.append("the log is from another device than the configured test phone")
    props = dict(p.split("=", 1) for p in h["qemu_props"].split(";") if "=" in p)
    emulator = (h["serial"].startswith("emulator-") or props.get("ro.kernel.qemu") == "1"
                or props.get("ro.boot.qemu") == "1" or props.get("ro.hardware", "") in ("goldfish", "ranchu")
                or not SERIAL_RE.match(h["serial"]))
    if emulator or h["emulator"] != "no" or set(props) != {"ro.kernel.qemu", "ro.boot.qemu", "ro.hardware"}:
        bad.append("emulator (or unknown device properties): not accepted")
    if not h["api_level"].isdigit():
        bad.append("api_level is not a number")
    api = int(h["api_level"]) if h["api_level"].isdigit() else 0

    if h["build_command"] != BUILD_COMMAND or h["build_exit"] != "0":
        bad.append("the device-test APK was not built with the canonical command, or the build failed")
    if h["apk"] != APK or h["apk_built_in_this_run"] != "yes" or not HEX64.match(h["apk_sha256"]):
        bad.append("APK path, fresh build or SHA-256 not as required")
    if h["test_package"] != TEST_PACKAGE or h["runner"] != RUNNER:
        bad.append("test package or runner is not the device-test APK's")
    serial = h["serial"]
    if h["install_command"] != f"adb -s {serial} install -t {APK}":
        bad.append("install command is not the canonical one")
    if h["instrument_command"] != f"adb -s {serial} shell am instrument -r -w {TEST_PACKAGE}/{RUNNER}":
        bad.append("instrument command is not the canonical one (no filters, other runners or options accepted)")
    if h["instrument_exit"] != "0":
        bad.append(f"instrument command exit {h['instrument_exit']}")
    if hashlib.sha256(raw_bytes).hexdigest() != h["raw_sha256"]:
        bad.append("raw_sha256 does not match the stored runner output")
    if SHARED_STORAGE_RE.search(head + raw):
        bad.append("the log names a shared-storage path; only private test storage is allowed")

    try:
        names, listed_skips = load_tests(rev)
        fixtures = expected_fixtures(rev)
    except ListError as e:
        return bad + [str(e)], []
    if base is None:
        res = subprocess.run(["git", "merge-base", "origin/main", rev], capture_output=True, text=True)
        base = res.stdout.strip() if res.returncode == 0 else ""
    if resolve(base) is None:
        return bad + [f"the base {base!r} does not resolve: the expected skips cannot be checked against it"], []
    skips = effective_skips(base, listed_skips)
    if h["fixtures"] != ",".join(fixtures):
        bad.append(f"fixtures are not exactly those of {FIXTURES} at the commit ({len(fixtures)} expected, with the "
                   f"SHA-256 of {MODEL_PIN} / {REGISTER})")
    expected, ignored = count_tests(rev)
    if ignored:
        bad.append(f"@Ignore in the device-test sources ({len(ignored)} file(s)): ignored tests are not accepted")
    if expected == 0:
        bad.append("no @Test method in the device-test sources of the commit")
    if h["source_test_methods"] != str(expected):
        bad.append(f"source_test_methods {h['source_test_methods']} != {expected} @Test methods at the commit")

    blocks, result, final, problems = parse_instrumentation(raw)
    bad += problems
    if final != -1:
        bad.append(f"final INSTRUMENTATION_CODE {final} (the runner must finish with -1)")
    stream = result.get("stream", "")
    m = re.search(r"^OK \((\d+) tests?\)$", stream, re.M)
    if not m or int(m.group(1)) != expected:
        bad.append(f"runner summary is not 'OK ({expected} tests)'")
    if "FAILURES!!!" in stream:
        bad.append("runner summary reports failures")
    numtests = {b.get("numtests") for b, _ in blocks}
    if numtests != {str(expected)}:
        bad.append(f"numtests {sorted(x for x in numtests if x)} != {expected}")
    outcome, stacks, starts = {}, {}, {}
    for b, code in blocks:
        cls, test = b.get("class"), b.get("test")
        if not cls or not test:
            continue
        name = f"{cls}::{test}"
        if code == 1:
            starts[name] = starts.get(name, 0) + 1
        elif name in outcome:
            bad.append(f"{name}: more than one result (retry)")
        else:
            outcome[name] = code
            stacks[name] = b.get("stack", "")
    for name, n in starts.items():
        if n != 1:
            bad.append(f"{name}: started {n} times (retries are not accepted)")
        if name not in outcome:
            bad.append(f"{name}: started, no result")
    labels = {0: "passed", -1: "error", -2: "failed", -3: "ignored", -4: "skipped (assumption)"}
    accepted = []
    for name, code in sorted(outcome.items()):
        if code == 0:
            continue
        if code == -4 and name in skips:
            minimum = skips[name][0]
            if api >= minimum:
                bad.append(f"{name}: skipped at API {api}, which is not below its minimum {minimum} "
                           "(a test that should run was skipped)")
            elif "AssumptionViolatedException" not in stacks[name]:
                bad.append(f"{name}: skipped without an AssumptionViolatedException")
            else:
                accepted.append(name)
            continue
        note = ""
        if code == -4:
            note = " (not an expected skip" + (": its row is not in the base, it takes effect after the approved change"
                                               " is merged" if name in listed_skips else "") + ")"
        bad.append(f"{name}: {labels.get(code, f'status {code}')}{note}")
    for name in listed_skips:
        if name not in outcome:
            bad.append(f"expected-skip test missing from the log: {name}")
    passed = sum(1 for c in outcome.values() if c == 0)
    if len(outcome) != expected:
        bad.append(f"{len(outcome)} test results, {expected} @Test methods at the commit")
    if passed + len(accepted) != expected:
        bad.append(f"{passed} passed + {len(accepted)} expected skips != {expected} @Test methods at the commit")
    for name in names:
        if name not in outcome:
            bad.append(f"required test missing: {name}")
        elif outcome[name] != 0 and name not in accepted:
            bad.append(f"required test not passed: {name}")
    summary = [f"{passed} passed, {len(accepted)} expected skips"
               + (f" ({', '.join(accepted)})" if accepted else "") + f" of {expected} @Test methods at API {api}"]
    for name in accepted:
        summary.append(f"NOT RUN here: {name} - its requirement needs a passing run on: {skips[name][1]}")
    return bad, summary


def needed(base: str, rev: str):
    """(required, reason). Required when base..rev touches a phone-only file (of either list), the device tests, the
    list files or the phone tooling, and whenever the base is empty or does not resolve (fail closed)."""
    if resolve(base) is None:
        return True, f"the base {base!r} is empty or does not resolve (fail closed)"
    changed = git("diff", "--name-only", "--no-renames", f"{base}...{rev}").splitlines()
    listed = set()
    for r in (base, rev):
        text = show(r, PHONE_ONLY)
        if text is not None:
            try:
                listed.update(parse_phone_only(text))
            except ListError:
                return True, f"{PHONE_ONLY} at {r} is malformed"
    hits = [p for p in changed if p in listed or p.startswith(DEVICE_TEST_DIR) or p == PHONE_ONLY
            or p.startswith("config/device-tests/") or p in PHONE_TOOLING]
    return bool(hits), (f"{len(hits)} changed file(s), first {hits[0]}" if hits else "")


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("verify")
    v.add_argument("--log", required=True)
    v.add_argument("--rev", default="HEAD")
    v.add_argument("--base")
    n = sub.add_parser("needed")
    n.add_argument("--base", required=True)
    n.add_argument("--rev", default="HEAD")
    c = sub.add_parser("count-tests")
    c.add_argument("--rev", default="HEAD")
    li = sub.add_parser("lists")
    li.add_argument("--rev", default="HEAD")
    a = ap.parse_args()
    try:
        if a.cmd == "verify":
            bad, summary = verify(a.log, a.rev, a.base)
            for b in bad:
                print(f"phone-test-log: NOT VALID - {b}")
            if bad:
                return 1
            print(f"phone-test-log: VALID for {git('rev-parse', a.rev).strip()[:12]}")
            for line in summary:
                print(f"phone-test-log: {line}")
            return 0
        if a.cmd == "needed":
            req, why = needed(a.base, a.rev)
            print(f"phone-test-log: required ({why})" if req else "phone-test-log: not required")
            return 0 if req else 1
        if a.cmd == "count-tests":
            total, ignored = count_tests(a.rev)
            print(total)
            return 1 if ignored else 0
        load_list(a.rev, PHONE_ONLY, parse_phone_only)
        load_tests(a.rev)
        expected_fixtures(a.rev)
        print("phone-test-log: list files OK")
        return 0
    except ListError as e:
        print(f"phone-test-log: FAIL - {e}", file=sys.stderr)
        return 1
    except OSError as e:
        print(f"phone-test-log: ERROR - {e}", file=sys.stderr)
        return 2
    except Exception as e:  # any internal error is exit 2, never a "not required" / "valid" answer
        print(f"phone-test-log: ERROR - internal error {type(e).__name__}: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
