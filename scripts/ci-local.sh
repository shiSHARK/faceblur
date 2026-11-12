#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Local merge gate: runs on a developer machine every check of the CI jobs `gates` and `build` ("Model, JVM + Android
# checks, iOS compile, docs") and, when verification/ exists, the job `verification`, in the CI order. The checks
# themselves are the steps of scripts/ci-steps.sh, the same file the workflow runs, so the two cannot drift apart.
# Run it from the root of a clean checkout of the commit to be merged (a fresh worktree; build outputs from an
# earlier run would be reused by Gradle). Every step runs even after a failure; the exit code is 1 if any failed.
#
# CI-only (accepted, not run here; what covers them):
#   - scanner installs with SHA-256 checks: here the scanners must be given and their versions must equal the pins
#     in .github/workflows/ci.yml (checked below; a missing scanner or another version fails the gate);
#   - Gradle wrapper validation by gradle/actions/setup-gradle: here the wrapper jar must be unchanged against
#     origin/main, otherwise the gate fails and CI must validate the new wrapper;
#   - JDK setup (CI: Temurin 17): here the Gradle toolchain of the build selects the JDK;
#   - caches, artifact uploads, the path filters (scripts/ci-changes.sh; here every job runs), the tree-hash reuse on
#     main and the branch-push summary: none of them is a check.
#
# Environment: FACEBLUR_GITLEAKS, OPENGREP, OSV_SCANNER = scanner binaries (required). ANDROID_HOME or
# ANDROID_SDK_ROOT (or local.properties) for the Android checks. CI=true is set here like on the runner, so the
# switches that CI refuses are refused here too. The OSV steps query the OSV database over the network.
# FACEBLUR_PHONE_TEST_LOG (optional): phone-test log of this commit (scripts/phone-device-tests.sh); without it the
# changed lines of phone-only files count in the changed-lines coverage gate like every other line.
# Exit codes: 0 every step passed, 1 a step failed, 2 the environment is not usable (nothing or not all was run).
set -u

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
cd "$root" || exit 2
export CI=true
export ORT_DISABLE_TELEMETRY=1

# The step lists must be readable, non-empty and equal (in order) to the ci-steps.sh steps of the CI workflow, and the
# workflow must run no inline check outside them (scripts/check-ci-steps.py). Otherwise nothing runs.
if ! python3 -I scripts/check-ci-steps.py .; then
    echo "ci-local: RESULT FAIL - the step lists of scripts/ci-steps.sh and .github/workflows/ci.yml are not usable"
    exit 2
fi

problem=0
need() { # need <description> <ok 0|1>
    if [ "$2" -ne 0 ]; then
        echo "ci-local: ENVIRONMENT FAIL - $1"
        problem=1
    fi
}
pin() { sed -n "s/^  $1: \"\\(.*\\)\"/\\1/p" .github/workflows/ci.yml; }
version_ok() { # version_ok <binary> <pin> : the version output names exactly the pinned version (whole word)
    [ -n "$1" ] && [ -x "$1" ] && [ -n "$2" ] || return 1
    re="(^|[^0-9.])v?$(printf '%s' "$2" | sed 's/\./\\./g')([^0-9.]|\$)"
    "$1" --version 2>/dev/null | grep -Eq "$re" && return 0
    "$1" version 2>/dev/null | grep -Eq "$re"
}

for v in GITLEAKS OPENGREP OSV_SCANNER; do
    [ -n "$(pin "${v}_VERSION")" ] || need "no ${v}_VERSION pin in .github/workflows/ci.yml" 1
done
# The status is saved before `need` runs: a command substitution in its arguments would reset $?.
version_ok "${FACEBLUR_GITLEAKS:-}" "$(pin GITLEAKS_VERSION)"
ok=$?
need "FACEBLUR_GITLEAKS must name gitleaks $(pin GITLEAKS_VERSION)" "$ok"
version_ok "${OPENGREP:-}" "$(pin OPENGREP_VERSION)"
ok=$?
need "OPENGREP must name opengrep $(pin OPENGREP_VERSION)" "$ok"
version_ok "${OSV_SCANNER:-}" "$(pin OSV_SCANNER_VERSION)"
ok=$?
need "OSV_SCANNER must name osv-scanner $(pin OSV_SCANNER_VERSION)" "$ok"
export FACEBLUR_GITLEAKS OPENGREP OSV_SCANNER
git rev-parse --verify -q origin/main > /dev/null
ok=$?
need "origin/main is needed for the diff base (fetch it)" "$ok"
if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] && ! grep -q '^sdk.dir=' local.properties 2>/dev/null; then
    need "no Android SDK (ANDROID_HOME, ANDROID_SDK_ROOT or local.properties sdk.dir)" 1
fi
wrapper=gradle/wrapper/gradle-wrapper.jar
if git cat-file -e "origin/main:$wrapper" 2>/dev/null &&
    [ "$(git rev-parse "origin/main:$wrapper")" != "$(git rev-parse "HEAD:$wrapper" 2>/dev/null)" ]; then
    need "$wrapper differs from origin/main: the wrapper validation runs only in CI" 1
fi
# The CI-only relaxation of the changed-lines gate for phone-only files (no phone on GitHub's runners) never applies
# to the local gate.
if [ -n "${GITHUB_ACTIONS:-}" ]; then
    need "GITHUB_ACTIONS is set: the local gate does not run as GitHub Actions" 1
fi
# The local gate runs EVERY test: the ones that need the maintainer's local fixtures (reference model, local images) too.
# Without the folder (or with a file missing or changed) the gate does not run at all; there is no "skipped" outcome.
if [ -z "${FACEBLUR_LOCAL_FIXTURES:-}" ] || [ ! -f "${FACEBLUR_LOCAL_FIXTURES:-}/MANIFEST.sha256" ]; then
    need "FACEBLUR_LOCAL_FIXTURES must name the local fixtures folder (with MANIFEST.sha256)" 1
elif ! (cd "$FACEBLUR_LOCAL_FIXTURES" && shasum -a 256 -c MANIFEST.sha256 > /dev/null 2>&1); then
    need "the local fixtures differ from their MANIFEST.sha256 (a file is missing or changed)" 1
fi
if [ -n "${FACEBLUR_PHONE_TEST_LOG:-}" ] && [ ! -f "$FACEBLUR_PHONE_TEST_LOG" ]; then
    need "FACEBLUR_PHONE_TEST_LOG names no file" 1
fi
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    need "the checkout has uncommitted changes: the gate checks a commit, not a working tree" 1
fi
if [ "$problem" -ne 0 ]; then
    echo "ci-local: RESULT FAIL - environment not usable, no check was run"
    exit 2
fi

failed=""
steps=0
snap=build/ci-local/results
rm -rf "$snap"
mkdir -p "$snap"
# Results are copied right after the steps that produce them: later negative controls rerun single tests and
# overwrite build/test-results.
snapshot() { # snapshot <step>
    case "$1" in
        build-main)
            if [ -d faceblur/build/test-results ]; then cp -R faceblur/build/test-results "$snap/faceblur"; fi ;;
        verification-check)
            for d in verification/*/build/test-results; do
                [ -d "$d" ] || continue
                m=${d#verification/}
                m=${m%%/*}
                mkdir -p "$snap/verification"
                cp -R "$d" "$snap/verification/$m"
            done ;;
    esac
}
report() { # report <job/step> <exit code> <start seconds>
    steps=$((steps + 1))
    if [ "$2" -eq 0 ]; then
        echo "ci-local: --- $1 PASS ($(($(date +%s) - $3)) s)"
    else
        echo "ci-local: --- $1 FAIL ($(($(date +%s) - $3)) s)"
        failed="$failed $1"
    fi
}
run_job() { # run_job <job> : a list that fails or is empty is a FAIL, never a silent pass
    if ! list=$(sh scripts/ci-steps.sh --list "$1") || [ -z "$list" ]; then
        echo "ci-local: --- $1/step-list FAIL (the list is not readable or empty)"
        failed="$failed $1/step-list"
        return
    fi
    for step in $list; do
        echo "ci-local: === $1/$step"
        start=$(date +%s)
        sh scripts/ci-steps.sh "$step"
        code=$?
        snapshot "$step"
        report "$1/$step" "$code" "$start"
    done
}

echo "ci-local: commit $(git rev-parse HEAD) tree $(git rev-parse 'HEAD^{tree}') base $(git merge-base origin/main HEAD)"
run_job gates
run_job build
if [ -f verification/settings.gradle.kts ]; then
    run_job verification
else
    echo "ci-local: verification/ does not exist in this revision: nothing to run"
fi

echo "ci-local: === results/snapshot"
start=$(date +%s)
python3 -I scripts/ci-local-results.py count "$snap"
report results/snapshot $? "$start"

if [ -n "$failed" ]; then
    echo "ci-local: RESULT FAIL - steps=$steps failed:$failed"
    exit 1
fi
if [ "$steps" -le 1 ]; then
    echo "ci-local: RESULT FAIL - steps=$steps: no check ran"
    exit 1
fi
echo "ci-local: RESULT PASS - steps=$steps (every step of the gates, build and verification jobs, and the results)"
