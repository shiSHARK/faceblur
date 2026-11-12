#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Planted-violation tests (negative + positive controls) of the phone-only coverage list, the phone-test log
checker (scripts/phone_test_log.py), the changed-lines gate with phone-only files (scripts/diff-coverage.py), the
AAR manifest gate (scripts/check-publication.sh) and the merged-manifest checker (scripts/check-consumer-manifest.py).

Every case runs in a throw-away git repository or directory under a temporary directory with synthetic inputs (no
device, no build, no photo); nothing in this repository is changed. Called by scripts/test-gates.sh.
Usage: test-phone-gates.py      Exit codes: 0 every control behaved as expected, 1 otherwise.
"""
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SERIAL, MODEL = "TESTSERIAL01", "TestPhone"
PKG, RUNNER = "io.github.shishark.faceblur.test", "androidx.test.runner.AndroidJUnitRunner"
APK = "faceblur/build/outputs/apk/androidTest/faceblur-androidTest.apk"
LISTED = "faceblur/src/androidMain/kotlin/x/android/Glue.kt"
results = {"pass": 0, "fail": 0}


def expect(label: str, want: int, cmd: list, cwd: str, env_extra=None, must_contain=None):
    env = dict(os.environ)
    env.update({"FACEBLUR_PHONE_SERIAL": SERIAL, "FACEBLUR_PHONE_MODEL": MODEL})
    env.pop("GITHUB_ACTIONS", None)
    if env_extra:
        env.update(env_extra)
    res = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True)
    out = res.stdout + res.stderr
    ok = res.returncode == want and (must_contain is None or must_contain in out)
    results["pass" if ok else "fail"] += 1
    print(f"test-phone-gates: {label} {'PASS' if ok else 'FAIL'} (exit {res.returncode}, expected {want})")
    if not ok:
        for line in out.splitlines()[:10]:
            print(f"    {line}")


def git(repo: str, *args: str) -> str:
    return subprocess.run(["git", *args], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()


def write(repo: str, path: str, text: str):
    full = os.path.join(repo, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8") as fh:
        fh.write(text)


PHONE_HEADER = "# SPDX-License-Identifier: Apache-2.0\n# faceblur phone-only coverage list, format 1\n"
REQ_HEADER = "# SPDX-License-Identifier: Apache-2.0\n# faceblur required phone tests, format 1\n"
SKIP_ROW = "x.android.DeviceTest::gain\t34\tan emulator of API 34 or later\tthe subject exists from API 34\n"
SKIP_HEADER = "# SPDX-License-Identifier: Apache-2.0\n# faceblur expected assumption skips, format 1\n"
TESTS = [("x.android.DeviceTest", "a"), ("x.android.DeviceTest", "b"), ("x.android.DeviceTest", "gain")]
PHONE_LIST = PHONE_HEADER + "# comment\n" + LISTED + "\tplatform calls only\n"
SKIP_LIST = SKIP_HEADER + "# comment\n" + SKIP_ROW
FIXTURES = "m.onnx:" + "b" * 64 + ",l.png:" + "9" * 64 + ",t.png:" + "c" * 64
KEEP = object()


def new_repo(root: str, base_phone=PHONE_LIST, base_skips=SKIP_LIST, head_phone=KEEP, head_skips=KEEP) -> str:
    """Base commit with the list files (None = absent), then a change commit. HEAD keeps the base lists unless
    head_phone / head_skips give other text: that is a list change inside the range under test."""
    repo = os.path.join(root, "repo")
    shutil.rmtree(repo, ignore_errors=True)
    os.makedirs(repo)
    git(repo, "init", "-q", "-b", "main")
    git(repo, "config", "user.name", "Gate Test")
    git(repo, "config", "user.email", "gate-test" + "@" + "example.invalid")
    git(repo, "config", "commit.gpgsign", "false")
    write(repo, "faceblur/src/commonMain/kotlin/x/A.kt", "package x\n")
    write(repo, LISTED, "package x.android\n")
    for path, text in (("config/coverage/phone-only.txt", base_phone), ("config/device-tests/expected-skips.txt",
                                                                       base_skips)):
        if text is not None:
            write(repo, path, text)
    write(repo, "config/device-tests/required-tests.txt", REQ_HEADER + "x.android.DeviceTest::a\nx.android.DeviceTest::b\n"
          "x.android.DeviceTest::gain\n")
    write(repo, "config/device-tests/fixtures.txt", "# SPDX-License-Identifier: Apache-2.0\n# fixtures\n"
          "model m.onnx\nlocal images/l.png\ntracked testdata/t.png\n")
    # The model and the local files are fixtures of the maintainer's local gate, outside the repository.
    local = os.path.join(root, "local-fixtures")
    write(local, "yunet/yunet.properties", "# pin\nfileName=m.onnx\nsha256=" + "b" * 64 + "\n")
    write(local, "MANIFEST.sha256", "b" * 64 + "  yunet/m.onnx\n" + "9" * 64 + "  images/l.png\n")
    os.environ["FACEBLUR_LOCAL_FIXTURES"] = local
    os.makedirs(os.path.join(repo, "testdata"), exist_ok=True)
    with open(os.path.join(repo, "testdata", "t.png"), "wb") as fh:  # binary like a real fixture (not valid UTF-8)
        fh.write(b"\x89PNG\r\n\x1a\n\xff\xfe")
    write(repo, "config/data-admission/register.tsv", "testdata/t.png\t" + "c" * 64 + "\tsynthetic\n")
    write(repo, "faceblur/src/androidDeviceTest/kotlin/x/android/DeviceTest.kt",
          "package x.android\n\nclass DeviceTest {\n    @Test\n    fun a() {}\n\n    @Test\n    fun b() {}\n\n"
          "    @Test\n    fun gain() {}\n}\n")
    git(repo, "add", ".")
    git(repo, "commit", "-q", "-m", "base")
    for path, text in (("config/coverage/phone-only.txt", head_phone), ("config/device-tests/expected-skips.txt",
                                                                       head_skips)):
        if text is not KEEP:
            write(repo, path, text)
    # Changed lines: 4 in the common file (covered), 4 in the listed file (not covered by host tests).
    write(repo, "faceblur/src/commonMain/kotlin/x/A.kt", "package x\n\nfun a(v: Int): Int {\n    val w = v + 1\n"
          "    return w\n}\nfun b() = 1\n")
    write(repo, LISTED, "package x.android\n\nfun g(v: Int): Int {\n    val w = v + 2\n    return w\n}\nfun h() = 2\n")
    git(repo, "add", ".")
    git(repo, "commit", "-q", "-m", "change")
    return repo


def kover(path: str):
    lines = '<line nr="3" mi="0" ci="1"/><line nr="4" mi="0" ci="1"/><line nr="5" mi="0" ci="1"/><line nr="7" mi="0" ci="1"/>'
    miss = '<line nr="3" mi="1" ci="0"/><line nr="4" mi="1" ci="0"/><line nr="5" mi="1" ci="0"/><line nr="7" mi="1" ci="0"/>'
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(f'<?xml version="1.0"?><report name="t"><package name="x"><sourcefile name="A.kt">{lines}</sourcefile>'
                 f'</package><package name="x/android"><sourcefile name="Glue.kt">{miss}</sourcefile></package></report>\n')


def raw_output(outcomes: list, numtests: int, summary: str, final: str = "-1", retry: str = "") -> str:
    out = []
    for i, (cls, test, code) in enumerate(outcomes, 1):
        starts = 2 if f"{cls}#{test}" == retry else 1
        for _ in range(starts):
            out += [f"INSTRUMENTATION_STATUS: class={cls}", f"INSTRUMENTATION_STATUS: current={i}",
                    "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner", f"INSTRUMENTATION_STATUS: numtests={numtests}",
                    "INSTRUMENTATION_STATUS: stream=", f"INSTRUMENTATION_STATUS: test={test}",
                    "INSTRUMENTATION_STATUS_CODE: 1"]
        out += [f"INSTRUMENTATION_STATUS: class={cls}", f"INSTRUMENTATION_STATUS: current={i}",
                "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner", f"INSTRUMENTATION_STATUS: numtests={numtests}"]
        if code == -4:
            out += ["INSTRUMENTATION_STATUS: stack=org.junit.AssumptionViolatedException: not on this level", ""]
        out += ["INSTRUMENTATION_STATUS: stream=.", f"INSTRUMENTATION_STATUS: test={test}",
                f"INSTRUMENTATION_STATUS_CODE: {code}"]
    out += ["INSTRUMENTATION_RESULT: stream=", "", "Time: 0.5", "", summary, "", "", f"INSTRUMENTATION_CODE: {final}"]
    return "\n".join(out) + "\n"


def make_log(repo: str, path: str, raw: str = None, api: str = "31", **over):
    commit = git(repo, "rev-parse", "HEAD")
    tree = git(repo, "rev-parse", "HEAD^{tree}")
    if raw is None:
        raw = raw_output([(c, t, 0 if t != "gain" else -4) for c, t in TESTS], 3, "OK (3 tests)")
    serial = over.pop("serial", SERIAL)
    h = {
        "commit": commit, "tree": tree, "serial": serial, "model": MODEL, "manufacturer": "Test",
        "android_release": "12", "api_level": api, "qemu_props": "ro.kernel.qemu=;ro.boot.qemu=;ro.hardware=qcom",
        "emulator": "no", "start": "2026-01-01T00:00:00Z", "end": "2026-01-01T00:01:00Z",
        "build_command": "./gradlew --console=plain :faceblur:fetchYunetModel :faceblur:assembleAndroidDeviceTest",
        "build_exit": "0", "apk": APK, "apk_built_in_this_run": "yes", "apk_sha256": "a" * 64,
        "test_package": PKG, "runner": RUNNER, "install_command": f"adb -s {serial} install -t {APK}",
        "instrument_command": f"adb -s {serial} shell am instrument -r -w {PKG}/{RUNNER}", "instrument_exit": "0",
        "fixtures": FIXTURES, "source_test_methods": "3",
        "raw_sha256": hashlib.sha256(raw.encode()).hexdigest(),
    }
    tamper = over.pop("tamper_raw", None)
    h.update(over)
    h = {k: v for k, v in h.items() if v is not None}
    body = ["faceblur-phone-test-log 1"] + [f"{k}={v}" for k, v in h.items()]
    body += ["--- instrumentation output (unmodified) ---", (tamper or raw).rstrip("\n"),
             "--- end of instrumentation output ---", "result=PASS"]
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(body) + "\n")


def phone_cases(work: str):
    repo = new_repo(work)
    base = git(repo, "rev-parse", "HEAD~1")
    log = os.path.join(work, "phone.log")
    pt = [sys.executable, "-I", os.path.join(HERE, "phone_test_log.py")]

    def v(label, want, contains=None, env=None, **kw):
        make_log(repo, log, **kw)
        expect(label, want, pt + ["verify", "--log", log, "--base", base], repo, env, contains)

    v("Phone-log-positive", 0, "2 passed, 1 expected skips (x.android.DeviceTest::gain) of 3")
    v("Phone-log-positive-names-other-environment", 0, "NOT RUN here: x.android.DeviceTest::gain")
    v("Phone-log-other-commit", 1, "not the commit under test", commit="0" * 40)
    v("Phone-log-other-tree", 1, "not the tree under test", tree="1" * 40)
    v("Phone-log-other-device", 1, "another device", serial="OTHERSERIAL9")
    v("Phone-log-emulator-serial", 1, "emulator", serial="emulator-5554")
    v("Phone-log-emulator-props", 1, "emulator", qemu_props="ro.kernel.qemu=1;ro.boot.qemu=;ro.hardware=ranchu")
    v("Phone-log-no-device-config", 1, "must name the test phone", env={"FACEBLUR_PHONE_SERIAL": ""})
    v("Phone-log-filter", 1, "instrument command",
      instrument_command=f"adb -s {SERIAL} shell am instrument -r -w -e class x.android.DeviceTest {PKG}/{RUNNER}")
    v("Phone-log-reused-apk", 1, "fresh build", apk_built_in_this_run="no")
    v("Phone-log-build-failed", 1, "build failed", build_exit="1")
    v("Phone-log-hand-edited", 1, "raw_sha256",
      tamper_raw=raw_output([(c, t, 0) for c, t in TESTS], 3, "OK (3 tests)"))
    v("Phone-log-missing-field", 1, "header fields missing", fixtures=None)
    failed = raw_output([("x.android.DeviceTest", "a", 0), ("x.android.DeviceTest", "b", -2),
                         ("x.android.DeviceTest", "gain", -4)], 3, "FAILURES!!!\nTests run: 3,  Failures: 1")
    v("Phone-log-failed-test", 1, "failed", raw=failed)
    ignored = raw_output([("x.android.DeviceTest", "a", 0), ("x.android.DeviceTest", "b", -3),
                          ("x.android.DeviceTest", "gain", -4)], 3, "OK (3 tests)")
    v("Phone-log-ignored-test", 1, "ignored", raw=ignored)
    v("Phone-log-retry", 1, "retries", raw=raw_output([(c, t, 0 if t != "gain" else -4) for c, t in TESTS], 3,
                                                       "OK (3 tests)", retry="x.android.DeviceTest#b"))
    v("Phone-log-skip-at-allowed-api", 0, api="31")
    unlisted = raw_output([("x.android.DeviceTest", "a", 0), ("x.android.DeviceTest", "b", -4),
                           ("x.android.DeviceTest", "gain", -4)], 3, "OK (3 tests)")
    v("Phone-log-unlisted-skip", 1, "x.android.DeviceTest::b: skipped (assumption) (not an expected skip)", raw=unlisted)
    listed_ignored = raw_output([("x.android.DeviceTest", "a", 0), ("x.android.DeviceTest", "b", 0),
                                 ("x.android.DeviceTest", "gain", -3)], 3, "OK (3 tests)")
    no_assumption = raw_output([("x.android.DeviceTest", "a", 0), ("x.android.DeviceTest", "b", 0),
                                ("x.android.DeviceTest", "gain", -4)], 3, "OK (3 tests)").replace(
        "org.junit.AssumptionViolatedException: not on this level", "java.lang.RuntimeException: other")
    v("Phone-log-skip-without-assumption", 1, "without an AssumptionViolatedException", raw=no_assumption)
    absent = raw_output([("x.android.DeviceTest", "a", 0), ("x.android.DeviceTest", "b", 0)], 3, "OK (3 tests)")
    v("Phone-log-expected-skip-absent", 1, "expected-skip test missing from the log: x.android.DeviceTest::gain",
      raw=absent)
    v("Phone-log-listed-test-ignored", 1, "x.android.DeviceTest::gain: ignored", raw=listed_ignored)
    v("Phone-log-listed-skip-fails-as-test", 1, "x.android.DeviceTest::gain: failed",
      raw=raw_output([("x.android.DeviceTest", "a", 0), ("x.android.DeviceTest", "b", 0),
                      ("x.android.DeviceTest", "gain", -2)], 3, "FAILURES!!!"))
    v("Phone-log-skip-at-or-above-level", 1, "not below its minimum 34", api="34")
    v("Phone-log-gain-passes-above-level", 0, api="35",
      raw=raw_output([(c, t, 0) for c, t in TESTS], 3, "OK (3 tests)"))
    short = raw_output([("x.android.DeviceTest", "a", 0), ("x.android.DeviceTest", "gain", -4)], 2, "OK (2 tests)")
    v("Phone-log-fewer-tests-than-sources", 1, "@Test methods", raw=short, source_test_methods="2")
    v("Phone-log-runner-crash", 1, "final INSTRUMENTATION_CODE",
      raw=raw_output([(c, t, 0 if t != "gain" else -4) for c, t in TESTS], 3, "OK (3 tests)", final="0"))
    v("Phone-log-shared-storage-path", 1, "shared-storage", fixtures="/sdcard/x.jpg:" + "b" * 64)
    v("Phone-log-fixture-other-hash", 1, "fixtures are not exactly those", fixtures=FIXTURES.replace("c" * 64, "d" * 64))
    v("Phone-log-fixture-local-other-hash", 1, "fixtures are not exactly those", fixtures=FIXTURES.replace("9" * 64, "8" * 64))
    v("Phone-log-fixture-extra-file", 1, "fixtures are not exactly those", fixtures=FIXTURES + ",p.jpg:" + "e" * 64)
    v("Phone-log-fixture-missing", 1, "fixtures are not exactly those", fixtures="m.onnx:" + "b" * 64)
    make_log(repo, log)
    expect("Phone-log-base-unresolvable", 1, pt + ["verify", "--log", log, "--base", "deadbeef"], repo,
           must_contain="does not resolve")
    expect("Phone-log-default-base-without-origin-main", 1, pt + ["verify", "--log", log], repo,
           must_contain="does not resolve")
    # A required test renamed in the sources and the run (count still equal): the list catches it.
    write(repo, "faceblur/src/androidDeviceTest/kotlin/x/android/DeviceTest.kt",
          "package x.android\n\nclass DeviceTest {\n    @Test\n    fun a() {}\n\n    @Test\n    fun renamed() {}\n\n"
          "    @Test\n    fun gain() {}\n}\n")
    git(repo, "commit", "-q", "-am", "rename")
    renamed = raw_output([("x.android.DeviceTest", "a", 0), ("x.android.DeviceTest", "renamed", 0),
                          ("x.android.DeviceTest", "gain", -4)], 3, "OK (3 tests)")
    v("Phone-log-required-test-missing", 1, "required test missing: x.android.DeviceTest::b", raw=renamed)
    write(repo, "faceblur/src/androidDeviceTest/kotlin/x/android/DeviceTest.kt",
          "package x.android\n\nclass DeviceTest {\n    @Test\n    fun a() {}\n\n    @Ignore\n    @Test\n    fun b() {}\n\n"
          "    @Test\n    fun gain() {}\n}\n")
    git(repo, "commit", "-q", "-am", "ignore")
    v("Phone-log-ignore-annotation", 1, "@Ignore")

    # Expected skips are base AND HEAD: a row added in the range, or a minimum raised in the range, gives no relief;
    # a minimum lowered in the range (stricter) applies at once.
    def skips_repo(base_skips, head_skips):
        r = new_repo(work, base_skips=base_skips, head_skips=head_skips)
        return r, git(r, "rev-parse", "HEAD~1")

    for label, want, base_text, head_text, api, contains in (
        ("Expected-skip-row-added-in-range-not-accepted", 1, SKIP_HEADER, SKIP_LIST, "31",
         "x.android.DeviceTest::gain: skipped (assumption) (not an expected skip: its row is not in the base"),
        ("Expected-skip-file-added-in-range-not-accepted", 1, None, SKIP_LIST, "31", "its row is not in the base"),
        ("Expected-skip-minimum-raised-in-range-not-accepted", 1, SKIP_HEADER + SKIP_ROW.replace("\t34\t", "\t32\t"),
         SKIP_LIST, "33", "not below its minimum 32"),
        ("Expected-skip-minimum-lowered-in-range-applies", 1, SKIP_LIST,
         SKIP_HEADER + SKIP_ROW.replace("\t34\t", "\t32\t"), "33", "not below its minimum 32"),
        ("Expected-skip-minimum-lowered-in-range-positive", 0, SKIP_LIST,
         SKIP_HEADER + SKIP_ROW.replace("\t34\t", "\t32\t"), "31", "1 expected skips"),
        ("Expected-skip-in-base-and-head-positive", 0, SKIP_LIST, SKIP_LIST, "31", "1 expected skips"),
    ):
        repo, base = skips_repo(base_text, head_text)
        make_log(repo, log, api=api)
        expect(label, want, pt + ["verify", "--log", log, "--base", base], repo, must_contain=contains)

    # needed: base..HEAD touches the listed file -> required; a docs-only range -> not required; any doubt about the
    # base -> required (fail closed); an internal error -> exit 2, never "not required".
    repo = new_repo(work)
    base = git(repo, "rev-parse", "HEAD~1")
    expect("Phone-log-needed-listed-file", 0, pt + ["needed", "--base", base], repo, must_contain="required (")
    head = git(repo, "rev-parse", "HEAD")
    write(repo, "README.md", "docs\n")
    git(repo, "add", ".")
    git(repo, "commit", "-q", "-m", "docs")
    expect("Phone-log-not-needed-docs-only", 1, pt + ["needed", "--base", head], repo, must_contain="not required")
    expect("Phone-lists-positive", 0, pt + ["lists"], repo)
    expect("Phone-log-needed-empty-base", 0, pt + ["needed", "--base", ""], repo, must_contain="empty or does not resolve")
    expect("Phone-log-needed-unresolvable-base", 0, pt + ["needed", "--base", "deadbeef"], repo,
           must_contain="empty or does not resolve")
    expect("Phone-log-needed-git-error-exit-2", 2, pt + ["needed", "--base", head, "--rev", "no-such-rev"], repo,
           must_contain="ERROR")
    for label, path in (("device-test-source", "faceblur/src/androidDeviceTest/kotlin/x/android/More.kt"),
                        ("device-test-config", "config/device-tests/required-tests.txt"),
                        ("checker", "scripts/phone_test_log.py"), ("producer", "scripts/phone-device-tests.sh"),
                        ("coverage-gate", "scripts/diff-coverage.py")):
        tip = git(repo, "rev-parse", "HEAD")
        full = os.path.join(repo, path)
        old = open(full, encoding="utf-8").read() if os.path.exists(full) else ""
        write(repo, path, old + "# change\n")
        git(repo, "add", ".")
        git(repo, "commit", "-q", "-m", label)
        expect(f"Phone-log-needed-{label}", 0, pt + ["needed", "--base", tip], repo, must_contain=path)

    # Changed-lines gate: listed lines leave the threshold only with a valid log for HEAD.
    repo = new_repo(work)
    base = git(repo, "rev-parse", "HEAD~1")
    report = os.path.join(work, "kover.xml")
    kover(report)
    dc = [sys.executable, "-I", os.path.join(HERE, "diff-coverage.py"), "--report", report, "--base", base,
          "--phone-only", "config/coverage/phone-only.txt"]
    expect("Phone-only-without-log-fails", 1, dc, repo, must_contain="4/8 changed lines covered = 50.0 %")
    make_log(repo, log)
    expect("Phone-only-with-valid-log-passes", 0, dc + ["--phone-log", log], repo, must_contain="PHONE-TESTED")
    make_log(repo, log, commit="0" * 40)
    expect("Phone-only-with-stale-log-fails", 1, dc + ["--phone-log", log], repo, must_contain="NOT VALID")
    ci = {"GITHUB_ACTIONS": "true"}
    expect("Phone-only-ci-not-measured-names-file", 0, dc + ["--ci-phone-not-measured"], repo, ci,
           must_contain=f"::warning file={LISTED}::{LISTED}: 4 phone-only changed lines NOT MEASURED")
    expect("Phone-only-ci-not-measured-summary-names-file", 0, dc + ["--ci-phone-not-measured"], repo, ci,
           must_contain=f"::warning::4 phone-only changed lines NOT MEASURED here (no phone) in {LISTED} (4)")
    expect("Phone-only-ci-flag-refused-outside-actions", 2, dc + ["--ci-phone-not-measured"], repo,
           must_contain="GitHub Actions only")
    expect("Phone-only-both-figures", 1, dc, repo, must_contain="without the phone-only files 4/4 = 100.0 %")
    for label, text, contains in (
        ("Phone-only-list-bad-header", "# faceblur phone-only list\n" + LISTED + "\tr\n", "first two lines must be"),
        ("Phone-only-list-no-reason", PHONE_HEADER + LISTED + "\n", "expected '<faceblur/src/androidMain"),
        ("Phone-only-list-outside-androidMain", PHONE_HEADER + "faceblur/src/commonMain/kotlin/x/A.kt\tr\n",
         "expected '<faceblur/src/androidMain"),
        ("Phone-only-list-duplicate", PHONE_HEADER + LISTED + "\tr\n" + LISTED + "\tr\n", "duplicate entry"),
        ("Phone-only-list-too-long", PHONE_HEADER + "".join(
            f"faceblur/src/androidMain/kotlin/x/F{i}.kt\tr\n" for i in range(9)), "at most 8"),
    ):
        write(repo, "config/coverage/phone-only.txt", text)
        git(repo, "commit", "-q", "-am", label)
        expect(label, 1, dc, repo, must_contain=contains)
    write(repo, "config/coverage/phone-only.txt", PHONE_HEADER + LISTED + "\tr\n")
    write(repo, "config/device-tests/required-tests.txt", REQ_HEADER + "x.android.DeviceTest::a\n")
    git(repo, "commit", "-q", "-am", "expected skip of a test that is not required")
    expect("Expected-skip-not-a-required-test", 1, pt + ["lists"], repo,
           must_contain="is not in config/device-tests/required-tests.txt")
    write(repo, "config/device-tests/required-tests.txt", REQ_HEADER + "x.android.DeviceTest::gain\n")
    write(repo, "config/device-tests/expected-skips.txt", SKIP_HEADER + "x.android.DeviceTest::gain\t34\n")
    git(repo, "commit", "-q", "-am", "expected skip without environment and reason")
    expect("Expected-skips-bad-format", 1, pt + ["lists"], repo, must_contain="<minimum API level>")
    write(repo, "config/device-tests/expected-skips.txt", "# skips\n" + SKIP_ROW)
    git(repo, "commit", "-q", "-am", "expected skips without header")
    expect("Expected-skips-bad-header", 1, pt + ["lists"], repo, must_contain="first two lines must be")
    write(repo, "config/device-tests/expected-skips.txt", SKIP_HEADER + SKIP_ROW.replace("::", "#"))
    git(repo, "commit", "-q", "-am", "pattern-like name")
    expect("Expected-skips-name-not-class-method", 1, pt + ["lists"], repo, must_contain="expected '<class>::<method>")
    write(repo, "config/device-tests/expected-skips.txt", SKIP_HEADER + SKIP_ROW)
    git(repo, "commit", "-q", "-am", "valid again")
    expect("Expected-skips-restored-positive", 0, pt + ["lists"], repo)

    # A range cannot add its own file to the phone-only list: with a valid log its lines still count.
    for label, base_phone in (("Phone-only-entry-added-in-range", PHONE_HEADER), ("Phone-only-list-added-in-range", None)):
        repo = new_repo(work, base_phone=base_phone, head_phone=PHONE_LIST)
        base = git(repo, "rev-parse", "HEAD~1")
        make_log(repo, log)
        dc = [sys.executable, "-I", os.path.join(HERE, "diff-coverage.py"), "--report", report, "--base", base,
              "--phone-only", "config/coverage/phone-only.txt"]
        expect(f"{label}-counts-normally", 1, dc + ["--phone-log", log], repo,
               must_contain="4/8 changed lines covered = 50.0 %")
        expect(f"{label}-warns", 1, dc + ["--phone-log", log], repo,
               must_contain=f"{LISTED} is in the phone-only list of HEAD but not of the base")
        expect(f"{label}-no-ci-relief", 1, dc + ["--ci-phone-not-measured"], repo, {"GITHUB_ACTIONS": "true"},
               must_contain="4/8 changed lines covered = 50.0 %")
    # The expected-skips file is in the list-change warning set.
    repo = new_repo(work, head_skips=SKIP_LIST + "# reworded\n")
    base = git(repo, "rev-parse", "HEAD~1")
    make_log(repo, log)
    dc = [sys.executable, "-I", os.path.join(HERE, "diff-coverage.py"), "--report", report, "--base", base,
          "--phone-only", "config/coverage/phone-only.txt"]
    expect("Expected-skips-change-warns", 0, dc + ["--phone-log", log], repo,
           must_contain="::warning file=config/device-tests/expected-skips.txt::")


def producer_cases(work: str):
    """phone-device-tests.sh refusals that need no device (they exit before adb is looked up)."""
    sh = ["sh", os.path.join(HERE, "phone-device-tests.sh")]
    existing = os.path.join(work, "exists.log")
    write(work, "exists.log", "x\n")
    fresh = os.path.join(work, "new.log")
    expect("Producer-no-argument", 2, sh, work, must_contain="usage")
    expect("Producer-log-exists", 2, sh + [existing], work, must_contain="exists already")
    for label, serial in (("emulator-serial", "emulator-5554"), ("network-serial", "192.168.0.2:5555"),
                          ("empty-serial", "")):
        expect(f"Producer-{label}", 2, sh + [fresh], work, {"FACEBLUR_PHONE_SERIAL": serial},
               must_contain="must name the physical test phone")
    expect("Producer-no-model", 2, sh + [fresh], work, {"FACEBLUR_PHONE_MODEL": ""}, must_contain="FACEBLUR_PHONE_MODEL")
    if os.path.exists(fresh):
        results["fail"] += 1
        print("test-phone-gates: Producer-wrote-no-log FAIL (a refused run created the log)")


def aar(path: str, manifest: str):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("AndroidManifest.xml", manifest)
        z.writestr("classes.jar", b"")


NS = ('xmlns:android="http://schemas.android.com/apk/res/android" '
      'xmlns:tools="http://schemas.android.com/tools" package="io.github.shishark.faceblur"')
REMOVAL = ('<provider android:name="ai.onnxruntime.TelemetryInitializer" '
           'android:authorities="${applicationId}.onnxruntime_telemetry_initializer" tools:node="remove" />')
ORT_MANIFEST = ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="ai.onnxruntime">'
                '<uses-sdk android:minSdkVersion="24" />'
                '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />'
                '<uses-permission android:name="android.permission.INTERNET" />'
                '<application><provider android:name="ai.onnxruntime.TelemetryInitializer" '
                'android:authorities="${applicationId}.onnxruntime_telemetry_initializer" android:exported="false" '
                'android:initOrder="100" /></application></manifest>')


def publication_cases(work: str):
    repo = os.path.join(work, "lm")
    m = os.path.join(repo, "io", "github", "shishark")
    variants = ('{"variants":[{"name":"androidApiElements-published"},{"name":"iosArm64ApiElements-published"},'
                '{"name":"iosSimulatorArm64ApiElements-published"}]}')
    write(m, "faceblur/0.1.0/faceblur-0.1.0.module", variants)
    lib = os.path.join(m, "faceblur-android", "0.1.0", "faceblur-android-0.1.0")
    ort_aar = os.path.join(work, "ort", "onnxruntime-android-1.31.0.aar")
    aar(ort_aar, ORT_MANIFEST)
    dep = ('<dependencies><dependency><groupId>com.microsoft.onnxruntime</groupId>'
           '<artifactId>onnxruntime-android</artifactId><version>1.31.0</version></dependency></dependencies>')
    env = {"FACEBLUR_ORT_AAR": ort_aar}
    cmd = ["sh", os.path.join(HERE, "check-publication.sh"), repo]

    def case(label, want, manifest_app, ort=True, extra="", contains=None, env_over=None):
        pom = f'<project xmlns="http://maven.apache.org/POM/4.0.0">{dep if ort else ""}</project>'
        write(os.path.dirname(lib), os.path.basename(lib) + ".pom", pom)
        aar(lib + ".aar", f'<manifest {NS}><uses-sdk android:minSdkVersion="30" />{extra}{manifest_app}</manifest>')
        expect(label, want, cmd, work, env_over or env, contains)

    case("AAR-manifest-ort-removal-positive", 0, f"<application>{REMOVAL}</application>")
    case("AAR-manifest-no-ort-empty-positive", 0, "", ort=False)
    case("AAR-manifest-no-ort-but-removal", 1, f"<application>{REMOVAL}</application>", ort=False,
         contains="although onnxruntime-android is not a dependency")
    case("AAR-manifest-removal-missing", 1, "<application />", contains="exactly one removal required")
    case("AAR-manifest-permission", 1, f"<application>{REMOVAL}</application>",
         extra='<uses-permission android:name="android.permission.INTERNET" />', contains="<uses-permission> declared")
    case("AAR-manifest-permission-removal", 1, f"<application>{REMOVAL}</application>",
         extra='<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />',
         contains="tools attribute node on <uses-permission>")
    case("AAR-manifest-own-component", 1,
         f'<application>{REMOVAL}<service android:name="io.github.shishark.faceblur.S" /></application>',
         contains="<service io.github.shishark.faceblur.S> declared")
    case("AAR-manifest-selector", 1, "<application>" + REMOVAL.replace(
        'tools:node="remove"', 'tools:node="remove" tools:selector="com.microsoft.onnxruntime"') + "</application>",
         contains="tools attribute selector on <provider>")
    case("AAR-manifest-not-remove", 1, "<application>" + REMOVAL.replace('tools:node="remove"', 'tools:node="merge"')
         + "</application>", contains='is not tools:node="remove"')
    case("AAR-manifest-other-name", 1, "<application>" + REMOVAL.replace(
        "ai.onnxruntime.TelemetryInitializer", "ai.onnxruntime.Other") + "</application>",
         contains="<provider ai.onnxruntime.Other> declared")
    case("AAR-manifest-application-replace", 1, f'<application tools:node="replace">{REMOVAL}</application>',
         contains="<application> has attributes")
    aar(ort_aar, ORT_MANIFEST.replace("</application>", '<receiver android:name="ai.onnxruntime.R" /></application>'))
    case("AAR-manifest-ort-new-component", 1, f"<application>{REMOVAL}</application>", contains="re-review")
    aar(ort_aar, ORT_MANIFEST)
    case("AAR-manifest-ort-aar-missing", 1, f"<application>{REMOVAL}</application>",
         env_over={"FACEBLUR_ORT_AAR": os.path.join(work, "none.aar")}, contains="onnxruntime-android 1.31.0 AAR unreadable")
    os.remove(lib + ".aar")
    expect("AAR-manifest-no-aar", 1, cmd, work, env, must_contain="no faceblur-android AAR published")


def xmltree(perms: list, components: list) -> str:
    """Text in the layout of `aapt2 dump xmltree --file AndroidManifest.xml` (only what the checker reads)."""
    a = "http://schemas.android.com/apk/res/android"
    out = [f"N: android={a} (line=2)", "  E: manifest (line=2)", '    A: package="x" (Raw: "x")']
    for p in perms:
        out += ["      E: uses-permission (line=3)", f'        A: {a}:name(0x01010003)="{p}" (Raw: "{p}")']
    out.append("      E: application (line=4)")
    for name, meta in components:
        out += ["          E: provider (line=5)", f'            A: {a}:name(0x01010003)="{name}" (Raw: "{name}")']
        for mname in meta:
            out += ["              E: meta-data (line=6)",
                    f'                A: {a}:name(0x01010003)="{mname}" (Raw: "{mname}")',
                    f'                A: {a}:value(0x01010024)="androidx.startup" (Raw: "androidx.startup")']
    return "\n".join(out) + "\n"


def merged(perms: list, components: list) -> str:
    body = "".join(f'<uses-permission android:name="{p}" />' for p in perms) + "<application>"
    for name, meta in components:
        body += f'<provider android:name="{name}">' + "".join(
            f'<meta-data android:name="{m}" android:value="androidx.startup" />' for m in meta) + "</provider>"
    return f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x">{body}</application></manifest>'


def consumer_cases(work: str):
    d = os.path.join(work, "consumer")
    os.makedirs(d, exist_ok=True)
    perms = ["android.permission.ACCESS_NETWORK_STATE", "android.permission.INTERNET"]
    startup = ("androidx.startup.InitializationProvider", ["androidx.lifecycle.ProcessLifecycleInitializer"])
    starter = ("ai.onnxruntime.TelemetryInitializer", [])
    cmd = [sys.executable, "-I", os.path.join(HERE, "check-consumer-manifest.py"), "--merged", "m.xml", "--xmltree",
           "t.txt", "--dump-permissions", "p.txt", "--initializer", "androidx.lifecycle.ProcessLifecycleInitializer",
           "--permissions", ",".join(perms), "--starter"]

    def case(label, want, xml_parts, tree_parts, dump_perms, starter_expect="absent"):
        write(d, "m.xml", merged(*xml_parts))
        write(d, "t.txt", xmltree(*tree_parts))
        write(d, "p.txt", "package: x\n" + "".join(f"uses-permission: name='{p}'\n" for p in dump_perms))
        expect(label, want, cmd + [starter_expect], d)

    ok = (perms, [startup])
    case("Consumer-manifest-positive", 0, ok, ok, perms)
    case("Consumer-manifest-starter-in-merged", 1, (perms, [startup, starter]), ok, perms)
    case("Consumer-manifest-starter-in-apk", 1, ok, (perms, [startup, starter]), perms)
    case("Consumer-manifest-startup-removed", 1, (perms, []), (perms, []), perms)
    case("Consumer-manifest-initializer-removed", 1, (perms, [(startup[0], [])]), (perms, [(startup[0], [])]), perms)
    case("Consumer-manifest-extra-permission", 1, (perms + ["android.permission.CAMERA"], [startup]),
         (perms + ["android.permission.CAMERA"], [startup]), perms + ["android.permission.CAMERA"])
    case("Consumer-manifest-permission-missing", 1, (perms[:1], [startup]), (perms[:1], [startup]), perms[:1])
    case("Consumer-manifest-dump-disagrees", 1, ok, ok, perms[:1])
    case("Consumer-manifest-control-present", 0, (perms, [startup, starter]), (perms, [startup, starter]), perms,
         "present")


def main() -> int:
    work = tempfile.mkdtemp()
    try:
        phone_cases(work)
        producer_cases(work)
        publication_cases(work)
        consumer_cases(work)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    print(f"test-phone-gates: {results['pass']} passed, {results['fail']} failed")
    return 0 if results["fail"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
