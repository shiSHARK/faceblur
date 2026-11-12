#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Planted-violation tests for the script gates of ST-009 (negative + positive controls for each):
#   SPDX (LIC-001) · DCO (LIC-006) · API-dump wording (DX-009) · wording lint hook (DOC-008)
#   SAST corpus (PRIV-014, PRIV-004; scripts/test-sast-probes.py) · artifact paths (SEC-006)
#   diff coverage (NFR-QUAL-001) · test-result presence · path filters (docs-only, main never skipped)
#   tree-hash reuse on main (scripts/ci-reuse.py: same tree only, fail-safe on any doubt)
#   CI configuration filter (ci_config) · CI and local merge gate run the same steps (both directions; failing or
#   empty lists) · local gate results snapshot and log verification (scripts/ci-local-results.py)
#   dependency verification switch · publication without the jvm variant (ADR 0002)
#   phone-only coverage list and phone-test logs · AAR manifest · merged-consumer manifest (scripts/test-phone-gates.py)
#   ONNX Runtime telemetry switched off in code · ONNX Runtime locked session options (allow-listed keys, never-calls)
#   licence notices (index, runtime dependencies against the index, notices inside the published files)
#   runtime dependencies of the Android artifact against the allow-list (no networking library) · library write sites
#   internal-data figures (wording near a number; allow-list, stale rule, messages, hooks)
#   pre-push hook: DCO, internal IDs and figures over the pushed range, tree checks at the pushed tip, deletions
#   data-admission guard: pattern matching and reviewed line exceptions (config/data-admission/test_guard.py)
# Each runs in a throw-away git repository under a temporary directory; nothing in this repository is changed.
#
# Usage: OPENGREP=<binary> OSV_SCANNER=<binary> FACEBLUR_GITLEAKS=<binary> scripts/test-gates.sh (without them
#        those controls FAIL as NOT RUN; the OSV controls query the OSV database over the network)
# Exit codes: 0 every control behaved as expected, 1 otherwise.
set -u

here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
fail=0
pass=0
# Test identities and home paths, assembled at run time so that this file contains none (data admission).
at=@
test_email="gate-test${at}example.invalid"
other_email="else${at}example.invalid"
U=Users
u=users
H=home

# expect <id> <expected exit code> <command...>   (run inside the current directory)
expect() {
    id=$1
    want=$2
    shift 2
    "$@" > "$work/out.txt" 2>&1
    got=$?
    if [ "$got" -eq "$want" ]; then
        pass=$((pass + 1))
        echo "test-gates: $id PASS (exit $got)"
    else
        fail=$((fail + 1))
        echo "test-gates: $id FAIL (exit $got, expected $want):"
        sed 's/^/    /' "$work/out.txt" | head -8
    fi
}

# expect_reason <id> <expected exit code> <text the output must contain> <command...>: the named reason, not only the
# exit code (a control that fails for another reason fails).
expect_reason() {
    id=$1
    want=$2
    reason=$3
    shift 3
    "$@" > "$work/out.txt" 2>&1
    got=$?
    if [ "$got" -eq "$want" ] && grep -qF -- "$reason" "$work/out.txt"; then
        pass=$((pass + 1))
        echo "test-gates: $id PASS (exit $got, '$reason')"
    else
        fail=$((fail + 1))
        echo "test-gates: $id FAIL (exit $got, expected $want with '$reason'):"
        sed 's/^/    /' "$work/out.txt" | head -8
    fi
}

# expect_output <id> <expected stdout, lines joined by a space> <command...>
expect_output() {
    id=$1
    want=$2
    shift 2
    got=$("$@" 2>/dev/null | tr '\n' ' ' | sed 's/ $//')
    if [ "$got" = "$want" ]; then
        pass=$((pass + 1))
        echo "test-gates: $id PASS ($got)"
    else
        fail=$((fail + 1))
        echo "test-gates: $id FAIL (got '$got', expected '$want')"
    fi
}

new_repo() {
    rm -rf "$work/repo" "$work/origin"
    mkdir -p "$work/repo"
    cd "$work/repo" || exit 1
    git init -q -b main .
    git config user.name "Gate Test"
    git config user.email "$test_email"
    git config commit.gpgsign false
}

# --- SPDX -------------------------------------------------------------------------------------------------
new_repo
printf 'package x\n' > Bad.kt
printf '// SPDX-License-Identifier: Apache-2.0\npackage x\n' > Good.kt
git add . && git commit -q -s -m "t"
expect SPDX-missing 1 sh "$here/check-spdx.sh"
git rm -q Bad.kt && git commit -q -s -m "t2"
expect SPDX-positive 0 sh "$here/check-spdx.sh"
printf 'package x\n' > "Caf$(printf '\303\251').kt" && git add . && git commit -q -s -m "t-na"
expect SPDX-non-ascii-file-name-checked 1 sh "$here/check-spdx.sh"
git rm -q "Caf$(printf '\303\251').kt" && git commit -q -s -m "t-na2"
printf '\n\n\n\n\n// SPDX-License-Identifier: Apache-2.0\npackage x\n' > Late.kt && git add . && git commit -q -s -m "t3"
expect SPDX-on-line-6 1 sh "$here/check-spdx.sh"

# --- DCO --------------------------------------------------------------------------------------------------
new_repo
echo a > a.txt && git add a.txt && git commit -q -s -m "signed"
base=$(git rev-parse HEAD)
expect DCO-positive 0 sh "$here/check-dco.sh" "$base~0"
echo b > b.txt && git add b.txt && git commit -q -m "unsigned"
expect DCO-negative 1 sh "$here/check-dco.sh" "$base..HEAD"
printf 'msg\n\nSigned-off-by: Someone Else <%s>\n' "$other_email" > "$work/msg"
expect DCO-wrong-signer 1 sh "$here/check-dco.sh" --message "$work/msg"
printf 'msg\n\nSigned-off-by: Gate Test <%s>\n' "$test_email" > "$work/msg"
expect DCO-message-positive 0 sh "$here/check-dco.sh" --message "$work/msg"

# --- pre-commit hook with the wording configuration -----------------------------------------------------------------
# The real hook runs in a scratch repository; only the data-admission step is replaced by a stub (it needs the
# maintainer's local pattern list and is tested by its own suite). Commits go through the hook (core.hooksPath).
new_repo
mkdir -p .githooks scripts config/data-admission config/wording
cp "$here/../.githooks/pre-commit" "$here/../.githooks/commit-msg" .githooks/
cp "$here/check-spdx.sh" "$here/check-wording.sh" "$here/check-internal-ids.py" "$here/check-dco.sh" scripts/
cp "$here/check-internal-figures.py" "$here/internal-figures-terms.txt" scripts/
printf '#!/bin/sh\n# SPDX-License-Identifier: Apache-2.0\nexit 0\n' > config/data-admission/check.sh
git config core.hooksPath .githooks
printf '# Guide\nThe library masks faces.\n' > README.md
git add .
expect_commit_base=1
git commit -q -s -m "base without a term list" > "$work/out.txt" 2>&1 || expect_commit_base=0
if [ "$expect_commit_base" -ne 1 ]; then
    fail=$((fail + 1)); echo "test-gates: Hook-base-commit FAIL"; sed 's/^/    /' "$work/out.txt" | head -5
fi
expect_commit() { # expect_commit <id> <expected exit> [<text the rejection must contain>] : commits what is staged
    git commit -q -s -m "$1" > "$work/out.txt" 2>&1
    got=$?
    reason_ok=1
    if [ "$2" -ne 0 ] && [ -n "${3:-}" ] && ! grep -qF -- "$3" "$work/out.txt"; then reason_ok=0; fi
    if [ "$reason_ok" -eq 1 ] && { [ "$got" -eq 0 ] && [ "$2" -eq 0 ] || { [ "$got" -ne 0 ] && [ "$2" -ne 0 ]; }; }; then
        pass=$((pass + 1))
        echo "test-gates: $1 PASS (commit exit $got)"
    else
        fail=$((fail + 1))
        echo "test-gates: $1 FAIL (commit exit $got, expected $([ "$2" -eq 0 ] && echo 0 || echo "non-zero with '${3:-}'")):"
        sed 's/^/    /' "$work/out.txt" | head -8
    fi
}
printf '# test list (not the legal list)\nanonymi[sz]\n' > config/wording/terms.txt
printf 'An exact allowed line.\n' > config/wording/allow.txt
git add config/wording
expect_commit Hook-commit-adds-wording-config 0
printf '# test list (not the legal list)\nanonymi[sz]\nmasks faces\n' > config/wording/terms.txt
git add config/wording/terms.txt
expect_commit Hook-staged-term-list-is-applied-to-all-files 1 "forbidden claim wording in README.md"
git reset -q && git checkout -q -- config/wording/terms.txt
printf 'The library anonymises photos.\n' >> README.md
git add README.md
printf '# Guide\nThe library masks faces.\n' > README.md
expect_commit Hook-checks-staged-not-working-copy 1 "forbidden claim wording in README.md"
git reset -q && git checkout -q -- README.md
printf 'The library anonymises photos.\n' >> README.md
printf 'An exact allowed line.\nThe library anonymises photos.\n' > config/wording/allow.txt
git add README.md config/wording/allow.txt
expect_commit Hook-staged-allow-list-is-applied 0
printf '# test list\nanonymi[sz\n' > config/wording/terms.txt
git add config/wording/terms.txt
expect_commit Hook-invalid-term-list-rejected 1 "invalid extended regular expression"
git reset -q && git checkout -q -- config/wording/terms.txt
printf 'package x\n' > Bad.kt
git add Bad.kt
expect_commit Hook-spdx-still-checked 1 "missing 'SPDX-License-Identifier: Apache-2.0'"
git reset -q && rm -f Bad.kt
# The committed term list cannot be removed, renamed or emptied (the hook runs the lint with --require).
git rm -q config/wording/terms.txt
expect_commit Hook-term-list-deletion-rejected 1 "cannot be removed or renamed"
git reset -q && git checkout -q -- config/wording/terms.txt
git mv config/wording/terms.txt config/wording/terms-old.txt
expect_commit Hook-term-list-rename-rejected 1 "cannot be removed or renamed"
git reset -q && rm -f config/wording/terms-old.txt && git checkout -q -- config/wording/terms.txt
printf '# all entries removed\n' > config/wording/terms.txt
git add config/wording/terms.txt
expect_commit Hook-term-list-emptied-rejected 1 "has no entries"
git reset -q && git checkout -q -- config/wording/terms.txt
git rm -q config/wording/allow.txt
expect_commit Hook-allow-list-removal-allowed 0
# The internal-ID gate is wired into both hooks (the IDs are assembled at run time).
hid="D-$(printf '%03d' 42)"
printf '# Notes\nSee %s.\n' "$hid" > NOTES.md
git add NOTES.md
expect_commit Hook-pre-commit-runs-id-gate 1 "check-internal-ids: NOTES.md:2"
git reset -q && rm -f NOTES.md
cafe="caf$(printf '\303\251')"
printf '# Notes\nSee %s.\n' "$hid" > "$cafe.md"
git add "$cafe.md"
expect_commit Hook-pre-commit-non-ascii-name-checked 1 "check-internal-ids: $cafe.md:2: internal ID at column"
git reset -q && rm -f "$cafe.md"
printf '# Notes\nClean.\n' > "$cafe.md"
git add "$cafe.md"
expect_commit Hook-pre-commit-non-ascii-name-clean-passes 0
echo x > plain.txt && git add plain.txt
git commit -q -s -m "change for $hid" > "$work/out.txt" 2>&1
got=$?
if [ "$got" -ne 0 ] && grep -q "check-internal-ids: commit message" "$work/out.txt"; then
    pass=$((pass + 1)); echo "test-gates: Hook-commit-msg-runs-id-gate PASS (commit exit $got)"
else
    fail=$((fail + 1)); echo "test-gates: Hook-commit-msg-runs-id-gate FAIL (commit exit $got)"
    sed 's/^/    /' "$work/out.txt" | head -5
fi
git reset -q && rm -f plain.txt
# The internal-figure gate is wired into both hooks (the wording is assembled at run time).
src_words="internal photo"" sets"
printf '# Notes\nThe limit keeps a margin over the %s: at most 37 per photo.\n' "$src_words" > FIG.md
git add FIG.md
expect_commit Hook-pre-commit-runs-figure-gate 1 "check-internal-figures: FIG.md:1-2"
git reset -q && rm -f FIG.md
echo y > plain.txt && git add plain.txt
git commit -q -s -m "limit from the $src_words: 37 per photo" > "$work/out.txt" 2>&1
got=$?
if [ "$got" -ne 0 ] && grep -q "check-internal-figures: commit message" "$work/out.txt"; then
    pass=$((pass + 1)); echo "test-gates: Hook-commit-msg-runs-figure-gate PASS (commit exit $got)"
else
    fail=$((fail + 1)); echo "test-gates: Hook-commit-msg-runs-figure-gate FAIL (commit exit $got)"
    sed 's/^/    /' "$work/out.txt" | head -5
fi
git reset -q && rm -f plain.txt
# A staged change of the term list is applied to every tracked file, not only to the staged ones.
printf 'Masks per photo: 12.\n' > COUNT.md
git add COUNT.md
expect_commit Hook-figure-gate-clean-file-passes 0
printf '%s\n' 'masks\s+per\s+photo' >> scripts/internal-figures-terms.txt
git add scripts/internal-figures-terms.txt
expect_commit Hook-staged-figure-terms-applied-to-all-files 1 "check-internal-figures: COUNT.md:1-1"
git reset -q && git checkout -q -- scripts/internal-figures-terms.txt
git rm -q scripts/internal-figures-terms.txt
expect_commit Hook-figure-term-list-removal-rejected 1 "internal-figures-terms.txt is missing"
git reset -q && git checkout -q -- scripts/internal-figures-terms.txt
# Scope: the same public-text set as CI; a script that quotes a forbidden phrase is out of scope, a doc is not.
printf '#!/bin/sh\n# SPDX-License-Identifier: Apache-2.0\necho "it anonymises"\n' > probe.sh
git add probe.sh
expect_commit Hook-wording-scope-excludes-scripts 0
mkdir -p docs && printf 'It anonymises photos.\n' > docs/probe.txt
git add docs/probe.txt
expect_commit Hook-wording-scope-includes-docs 1 "forbidden claim wording in docs/probe.txt"
git reset -q && rm -rf docs
# KDoc in production sources is public API documentation: a claim there is rejected; test sources are out of scope.
mkdir -p faceblur/src/commonMain/kotlin/x faceblur/src/commonTest/kotlin/x
printf '// SPDX-License-Identifier: Apache-2.0\npackage x\n\n/** Anonymises every photo. */\nfun f() = 1\n' \
    > faceblur/src/commonMain/kotlin/x/Claim.kt
git add faceblur/src/commonMain/kotlin/x/Claim.kt
expect_commit Hook-wording-kdoc-claim-rejected 1 "forbidden claim wording in faceblur/src/commonMain/kotlin/x/Claim.kt"
sh "$here/check-wording.sh" --require > "$work/out.txt" 2>&1
got=$?
if [ "$got" -eq 1 ] && grep -q "forbidden claim wording in faceblur/src/commonMain/kotlin/x/Claim.kt" "$work/out.txt"; then
    pass=$((pass + 1)); echo "test-gates: Wording-ci-run-flags-kdoc-claim PASS"
else
    fail=$((fail + 1)); echo "test-gates: Wording-ci-run-flags-kdoc-claim FAIL"; sed 's/^/    /' "$work/out.txt" | head -5
fi
git reset -q && rm -f faceblur/src/commonMain/kotlin/x/Claim.kt
printf '// SPDX-License-Identifier: Apache-2.0\npackage x\n\n// asserts that output is never described as anonymised\n' \
    > faceblur/src/commonTest/kotlin/x/ClaimTest.kt
git add faceblur/src/commonTest/kotlin/x/ClaimTest.kt
expect_commit Hook-wording-scope-excludes-tests 0
mkdir -p samples/android/src/main Sources/FaceBlur
printf '<resources><string name="t">Anonymised in one tap</string></resources>\n' > samples/android/src/main/strings.xml
git add samples/android/src/main/strings.xml
expect_commit Hook-wording-scope-includes-samples 1 "forbidden claim wording in samples/android/src/main/strings.xml"
git reset -q && rm -rf samples
printf '// SPDX-License-Identifier: Apache-2.0\n/// Anonymises faces.\npublic struct S {}\n' > Sources/FaceBlur/S.swift
git add Sources/FaceBlur/S.swift
expect_commit Hook-wording-scope-includes-swift-sources 1 "forbidden claim wording in Sources/FaceBlur/S.swift"
git reset -q && rm -rf Sources

# --- API dump wording ---------------------------------------------------------------------------------------
new_repo
mkdir -p faceblur/api
printf 'public final class io/github/shishark/faceblur/FaceAnonymizer {\n}\n' > faceblur/api/faceblur.api
expect API-wording-negative 1 sh "$here/check-api-wording.sh"
printf 'public final class io/github/shishark/faceblur/FaceAnonymiser {\n}\n' > faceblur/api/faceblur.api
expect API-wording-negative-uk 1 sh "$here/check-api-wording.sh"
printf 'public final class io/github/shishark/faceblur/FaceMasker {\n}\n' > faceblur/api/faceblur.api
expect API-wording-positive 0 sh "$here/check-api-wording.sh"

# --- Wording lint hook ----------------------------------------------------------------------------------------
new_repo
printf '# Guide\nThe library anonymises photos.\n' > README.md
git add . && git commit -q -s -m "t"
expect Wording-not-configured 0 sh "$here/check-wording.sh"
expect Wording-not-configured-required 1 sh "$here/check-wording.sh" --require
mkdir -p config/wording
printf '# test list (not the legal list)\nanonymi[sz]\n' > config/wording/terms.txt
expect Wording-negative 1 sh "$here/check-wording.sh"
printf 'The library anonymises photos.\n' > config/wording/allow.txt
expect Wording-allow-list 0 sh "$here/check-wording.sh"
printf 'Another line that anonymizes faces.\n' >> README.md
expect Wording-allow-list-is-exact 1 sh "$here/check-wording.sh"
printf 'An unreadable doc.\n' > Unreadable.md && git add Unreadable.md && chmod 000 Unreadable.md
expect Wording-unreadable-file-is-an-error 2 sh "$here/check-wording.sh" Unreadable.md
chmod 644 Unreadable.md

# --- Data-admission guard: confidential-pattern matching and reviewed line exceptions (own suite) ------------------
# config/data-admission/test_guard.py builds its pattern list at run time from random tokens (no confidential data).
if [ -n "${FACEBLUR_GITLEAKS:-}" ]; then
    expect Guard-pattern-exceptions-suite 0 python3 -I "$here/../config/data-admission/test_guard.py"
else
    fail=$((fail + 1))
    echo "test-gates: data-admission guard suite NOT RUN - set FACEBLUR_GITLEAKS to the gitleaks binary"
fi

# --- SAST corpus -----------------------------------------------------------------------------------------------
if [ -n "${OPENGREP:-}" ]; then
    expect SAST-corpus 0 python3 -I "$here/test-sast-probes.py"
else
    fail=$((fail + 1))
    echo "test-gates: SAST NOT RUN - set OPENGREP to the opengrep binary"
fi

# --- Artifact paths (incl. the security review probes p1-p11 except p5 "~/", which names no user) ----------------
new_repo
mkdir -p art
printf 'clean\n' > art/a.txt
expect Artifact-paths-positive 0 sh "$here/check-artifact-paths.sh" art
probe_paths() { # probe_paths <id> <content> : expects exit 1
    rm -rf "$work/probe" && mkdir -p "$work/probe"
    printf '%s\n' "$2" > "$work/probe/f.txt"
    expect "Artifact-paths-$1" 1 sh "$here/check-artifact-paths.sh" "$work/probe"
}
probe_paths home "source: /$U/somebody/project/File.kt"
probe_paths private-tmp "/private/tmp/build-x/somebody/File.kt"
probe_paths tmp "/tmp/somebody-work/File.kt"
probe_paths dash-encoded "/private/tmp/claude-501/-$U-somebody-proj/File.kt"
probe_paths root "/root/project/File.kt"
probe_paths lower-case "/$u/somebody/File.kt"
probe_paths file-url "file:///$U/somebody/x"
probe_paths linux-home "/$H/runner/work/x/File.kt"
rm -rf "$work/probe" && mkdir -p "$work/probe/d"
printf '/%s/somebody/File.kt\n' "$U" > "$work/probe/d/inner.txt"
gzip -c "$work/probe/d/inner.txt" > "$work/probe/x.txt.gz"
tar -czf "$work/probe/x.tgz" -C "$work/probe/d" inner.txt
(cd "$work/probe/d" && zip -q inner.zip inner.txt && zip -q ../outer.jar inner.zip)
rm -rf "$work/probe/d"
mkdir -p "$work/p2" && cp "$work/probe/x.txt.gz" "$work/p2/"
expect Artifact-paths-gz 1 sh "$here/check-artifact-paths.sh" "$work/p2"
rm -rf "$work/p2" && mkdir -p "$work/p2" && cp "$work/probe/x.tgz" "$work/p2/"
expect Artifact-paths-tgz 1 sh "$here/check-artifact-paths.sh" "$work/p2"
rm -rf "$work/p2" && mkdir -p "$work/p2" && cp "$work/probe/outer.jar" "$work/p2/"
expect Artifact-paths-nested-zip 1 sh "$here/check-artifact-paths.sh" "$work/p2"
rm -rf "$work/p2" && mkdir -p "$work/p2"
printf '/%s/somebody/File.kt' "$U" | python3 -I -c 'import sys; sys.stdout.buffer.write(sys.stdin.read().encode("utf-16-le"))' \
    > "$work/p2/utf16.bin"
expect Artifact-paths-utf16 1 sh "$here/check-artifact-paths.sh" "$work/p2"
printf 'PK\003\004 broken' > "$work/p2/broken.jar"
expect Artifact-paths-unreadable-archive 2 sh "$here/check-artifact-paths.sh" "$work/p2/broken.jar"

# --- Diff coverage ----------------------------------------------------------------------------------------------
new_repo
mkdir -p faceblur/src/commonMain/kotlin/x faceblur/src/iosMain/kotlin/x
printf 'package x\n' > faceblur/src/commonMain/kotlin/x/A.kt
git add . && git commit -q -s -m "base"
base=$(git rev-parse HEAD)
printf 'package x\n\nfun a(v: Int): Int {\n    val w = v + 1\n    return w\n}\nfun b() = 1\n' \
    > faceblur/src/commonMain/kotlin/x/A.kt
git add . && git commit -q -s -m "change"
report() { # report <covered instructions for lines 4,5,7>
    printf '<?xml version="1.0"?><report name="t"><package name="x"><sourcefile name="A.kt">' > "$work/kover.xml"
    printf '<line nr="3" mi="0" ci="1"/><line nr="4" mi="0" ci="%s"/><line nr="5" mi="0" ci="%s"/>' "$1" "$2" \
        >> "$work/kover.xml"
    printf '<line nr="7" mi="0" ci="%s"/></sourcefile></package></report>\n' "$3" >> "$work/kover.xml"
}
report 1 0 0
expect Diff-coverage-negative 1 python3 -I "$here/diff-coverage.py" --report "$work/kover.xml" --base "$base"
report 1 1 1
expect Diff-coverage-positive 0 python3 -I "$here/diff-coverage.py" --report "$work/kover.xml" --base "$base"
printf 'package x\n\nfun i() = 2\n' > faceblur/src/iosMain/kotlin/x/I.kt
git add . && git commit -q -s -m "ios"
python3 -I "$here/diff-coverage.py" --report "$work/kover.xml" --base "$base" > "$work/out.txt" 2>&1
if grep -q '^::warning file=faceblur/src/iosMain/kotlin/x/I.kt::' "$work/out.txt"; then
    pass=$((pass + 1)); echo "test-gates: Diff-coverage-ios-not-measured-warning PASS"
else
    fail=$((fail + 1)); echo "test-gates: Diff-coverage-ios-not-measured-warning FAIL"
fi
mkdir -p faceblur/src/jvmMain/kotlin/x
printf 'package x\n\nfun j() = 3\n' > faceblur/src/jvmMain/kotlin/x/A.kt
git add . && git commit -q -s -m "same key"
expect Diff-coverage-ambiguous-key 1 python3 -I "$here/diff-coverage.py" --report "$work/kover.xml" --base "$base"

# --- Test results present -----------------------------------------------------------------------------------------
new_repo
mkdir -p r/jvmTest r/iosSimulatorArm64Test r/skipped
printf '<testsuite name="t" tests="2" failures="0" errors="0" skipped="0"/>\n' > r/jvmTest/TEST-a.xml
expect Test-results-positive 0 python3 -I "$here/check-test-results.py" r jvmTest
expect Test-results-missing 1 python3 -I "$here/check-test-results.py" r jvmTest iosSimulatorArm64Test
printf '<testsuite name="t" tests="0" failures="0" errors="0" skipped="0"/>\n' > r/iosSimulatorArm64Test/TEST-b.xml
expect Test-results-empty 1 python3 -I "$here/check-test-results.py" r iosSimulatorArm64Test
printf '<testsuite name="t" tests="3" failures="0" errors="0" skipped="3"/>\n' > r/skipped/TEST-c.xml
expect Test-results-all-skipped 1 python3 -I "$here/check-test-results.py" r skipped

# --- ONNX Runtime telemetry switched off in code (security review of ST-002) ------------------------------------------
new_repo
d=faceblur/src/jvmTest/kotlin/x
mkdir -p "$d" faceblur/src/androidMain/kotlin/x
good='val off = System.getenv("ORT_DISABLE_TELEMETRY") == "1"
fun env(): OrtEnvironment {
    check(off) { "ORT_DISABLE_TELEMETRY must be 1" }
    val e = OrtEnvironment.getEnvironment()
    e.setTelemetry(false)
    return e
}'
printf 'package x\n%s\n' "$good" > "$d/Runner.kt"
expect ORT-telemetry-positive 0 python3 -I "$here/check-ort-telemetry.py" faceblur/src
printf 'package x\nfun env() = OrtEnvironment.getEnvironment()\n' > "$d/Runner.kt"
expect ORT-telemetry-no-switch 1 python3 -I "$here/check-ort-telemetry.py" faceblur/src
printf 'package x\nfun env() = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }\n' > "$d/Runner.kt"
expect ORT-telemetry-jvm-without-env-check 1 python3 -I "$here/check-ort-telemetry.py" faceblur/src
printf 'package x\n%s\nfun g(e: OrtEnvironment) { try { e.setTelemetry(false) } catch (_: Exception) {\n    // ignored\n} }\n' \
    "$good" > "$d/Runner.kt"
expect ORT-telemetry-empty-catch 1 python3 -I "$here/check-ort-telemetry.py" faceblur/src
printf 'package x\n// OrtEnvironment.getEnvironment() is not called here\nfun f() = 1\n' > "$d/Runner.kt"
printf 'package x\nfun env() = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }\n' \
    > faceblur/src/androidMain/kotlin/x/AndroidRunner.kt
expect ORT-telemetry-android-switch-only 0 python3 -I "$here/check-ort-telemetry.py" faceblur/src

# --- ONNX Runtime locked session options (ADR 0012 decision 4) -------------------------------------------------------
new_repo
d=faceblur/src/jvmTest/kotlin/x
mkdir -p "$d" Sources/G
ort_ok='fun options(): OrtSession.SessionOptions {
    val o = OrtSession.SessionOptions()
    o.addConfigEntry("session.load_model_format", "ONNX")
    o.addConfigEntry("session.allow_released_opsets_only", "1")
    o.addConfigEntry(
        "session.intra_op.allow_spinning",
        "0",
    )
    o.addXnnpack(mapOf("intra_op_num_threads" to "1"))
    // o.registerCustomOpLibrary("x") and "session.use_ort_model_bytes_directly" in comments are ignored
    /* o.addNnapi() */
    return o
}'
ort_plant() { # ort_plant <id> <expected exit> <extra Kotlin line> : the line is added to the clean runner
    printf 'package x\n%s\nfun extra(o: OrtSession.SessionOptions) {\n    %s\n}\n' "$ort_ok" "$3" > "$d/Runner.kt"
    expect "$1" "$2" python3 -I "$here/check-ort-options.py" faceblur
}
printf 'package x\n%s\n' "$ort_ok" > "$d/Runner.kt"
expect ORT-options-positive 0 python3 -I "$here/check-ort-options.py" faceblur
git add . && git commit -q -s -m "t"
expect ORT-options-positive-tracked-files 0 python3 -I "$here/check-ort-options.py"
ort_plant ORT-options-planted-key 1 'o.addConfigEntry("session.use_ort_model_bytes_directly", "1")'
ort_plant ORT-options-planted-key-in-map 1 'val m = mapOf("ep.context_enable" to "1")'
ort_plant ORT-options-external-initializer-folder-key 1 'val k = "session.model_external_initializers_file_folder_path"'
ort_plant ORT-options-released-opsets-zero 1 'o.addConfigEntry("session.allow_released_opsets_only", "0")'
ort_plant ORT-options-load-format-ort 1 'o.addConfigEntry("session.load_model_format", "ORT")'
ort_plant ORT-options-non-literal-key 1 'for ((k, v) in extra) o.addConfigEntry(k, v)'
ort_plant ORT-options-template-key 1 'o.addConfigEntry("session.${name}", "1")'
ort_plant ORT-options-custom-op-library 1 'o.registerCustomOpLibrary("libx.so")'
ort_plant ORT-options-custom-ops-function 1 'o.registerCustomOpsUsingFunction("RegisterCustomOps")'
ort_plant ORT-options-extensions 1 'o.enableOrtExtensionsCustomOps()'
ort_plant ORT-options-optimized-model-path 1 'o.setOptimizedModelFilePath("x.onnx")'
ort_plant ORT-options-profiling 1 'o.enableProfiling("p")'
ort_plant ORT-options-external-initializers 1 'o.addExternalInitializers(mapOf())'
ort_plant ORT-options-add-initializer 1 'o.addInitializer("w", t)'
ort_plant ORT-options-nnapi 1 'o.addNnapi()'
ort_plant ORT-options-coreml-kotlin 1 'o.addCoreML()'
ort_plant ORT-options-qnn 1 'o.addQnn(mapOf())'
ort_plant ORT-options-cuda 1 'o.addCUDA(0)'
ort_plant ORT-options-generic-provider 1 'o.appendExecutionProvider("XNNPACK", mapOf())'
ort_plant ORT-options-string-not-a-key 0 'val s = "session ended"; val u = "https://example.invalid/ep.html"'
rm "$d/Runner.kt"
printf '// SPDX-License-Identifier: Apache-2.0\nfunc o(_ s: ORTSessionOptions) throws {\n    try s.addConfigEntry(withKey: "session.load_model_format", value: "ONNX")\n    try s.appendXnnpackExecutionProvider(with: x)\n}\n' > Sources/G/Glue.swift
expect ORT-options-swift-positive 0 python3 -I "$here/check-ort-options.py" Sources
printf '// SPDX-License-Identifier: Apache-2.0\nfunc o(_ s: ORTSessionOptions) throws {\n    try s.appendCoreMLExecutionProvider(with: x)\n}\n' > Sources/G/Glue.swift
expect ORT-options-swift-coreml 1 python3 -I "$here/check-ort-options.py" Sources
printf '// SPDX-License-Identifier: Apache-2.0\nfunc o(_ s: ORTSessionOptions) throws {\n    try s.registerCustomOpsUsingFunctionPointer(p)\n}\n' > Sources/G/Glue.swift
expect ORT-options-swift-custom-ops-pointer 1 python3 -I "$here/check-ort-options.py" Sources
printf '// SPDX-License-Identifier: Apache-2.0\nfunc o(_ s: ORTSessionOptions) throws {\n    try s.addConfigEntry(withKey: "session.allow_released_opsets_only", value: "0")\n}\n' > Sources/G/Glue.swift
expect ORT-options-swift-wrong-value 1 python3 -I "$here/check-ort-options.py" Sources
rm Sources/G/Glue.swift
printf '// SPDX-License-Identifier: Apache-2.0\nvoid o(ORTSessionOptions *s) { NSError *e; [s addConfigEntryWithKey:@"session.allow_released_opsets_only" value:@"1" error:&e]; }\n' > Sources/G/Glue.m
expect ORT-options-objc-positive 0 python3 -I "$here/check-ort-options.py" Sources
printf '// SPDX-License-Identifier: Apache-2.0\nvoid o(ORTSessionOptions *s) { NSError *e; [s addConfigEntryWithKey:@"session.use_ort_model_bytes_for_initializers" value:@"1" error:&e]; }\n' > Sources/G/Glue.m
expect ORT-options-objc-planted-key 1 python3 -I "$here/check-ort-options.py" Sources

# --- Internal-data figures: wording near a number (files, messages, allow-list) --------------------------------------
# The source wording is assembled at run time so that this file itself passes the gate. Probe IDs name the case only.
new_repo
mkdir -p scripts
cp "$here/check-internal-figures.py" "$here/internal-figures-terms.txt" scripts/
w_sets="internal photo"" sets"
w_on="meas""ured on"
w_room="head""room"
w_test="test"" set"
w_ours="our"" photos"
w_high="high""est"
w_priv="private image"" set"
w_bench="bench app"" runs"
printf '# Notes\nA plain number: 42 apples.\n' > notes.md
git add . && git commit -q -s -m "clean"
expect Figures-clean-tree 0 python3 -I scripts/check-internal-figures.py --tree
figs() { # figs <id> <expected exit> <content> : note.md with that content (staged, not committed), tree mode
    printf '%s\n' "$3" > note.md
    git add note.md
    expect "Figures-$1" "$2" python3 -I scripts/check-internal-figures.py --tree
    git rm -q --cached note.md && rm -f note.md
}
nl='
'
figs same-line 1 "The largest value $w_on the photos was 37 per photo."
figs number-two-lines-below 1 "Value from the $w_sets.${nl}Second line.${nl}It was 37."
figs number-two-lines-above 1 "It was 37.${nl}Second line.${nl}Value from the $w_sets."
figs number-three-lines-away 0 "Value from the $w_sets.${nl}A.${nl}B.${nl}It was 37."
figs term-margin 1 "Keeps 4 x $w_room."
figs term-eval-collection 1 "On the $w_test: 0.93."
figs term-ours 1 "On $w_ours the count is 12."
figs term-top 1 "The $w_high count was 12."
figs term-private 1 "From a $w_priv: 12."
figs term-bench 1 "The $w_bench gave 12."
figs non-breaking-hyphen 1 "On the test$(printf '\342\200\221')set: 12."
figs no-break-space 1 "Value meas""ured$(printf '\302\240')on the phones: 12."
figs no-wording 0 "The limit is 65,536 masks per image."
figs references-are-not-numbers 0 "See ADR 0012 decision 6, section 4.2 and step 3 (the $w_sets)."
figs list-marker-is-not-a-number 0 "1. The $w_sets"
figs heading-number-is-not-a-number 0 "### 6. Limits with $w_room"
figs identifiers-are-not-numbers 0 "The $w_sets use sha256, int32, ST-002 and UTF-8."
figs kotlin-keywords-are-not-wording 0 "internal const val A = 3${nl}internal data class P(val x: Int = 1)${nl}var n = 0${nl}    private set"
# Binary files are skipped (data admission checks them).
printf 'The %s: 37\000\n' "$w_sets" > blob.bin
git add blob.bin
expect Figures-binary-file-skipped 0 python3 -I scripts/check-internal-figures.py --tree
git rm -q --cached blob.bin && rm -f blob.bin
# The output names path, lines and block hash, never the text or the number.
printf 'The maximum %s was 987654.\n' "$w_on" > note.md
git add note.md
python3 -I scripts/check-internal-figures.py --tree > "$work/out.txt" 2>&1
got=$?
if [ "$got" -eq 1 ] && grep -q "note.md:1-1" "$work/out.txt" && ! grep -q "987654" "$work/out.txt" \
    && ! grep -q "$w_on" "$work/out.txt"; then
    pass=$((pass + 1)); echo "test-gates: Figures-output-hides-text PASS"
else
    fail=$((fail + 1)); echo "test-gates: Figures-output-hides-text FAIL (exit $got)"
fi
# Allow-list: a reviewed block passes; any change inside it fails; stale entries fail in tree mode only.
key=$(python3 -I scripts/check-internal-figures.py --tree --print-keys 2>&1 | grep '^check-internal-figures: allow-list key: note.md' | cut -f2)
printf '# test allow-list\nnote.md\t%s\tpermanent\treviewed in the test\n' "$key" > scripts/internal-figures-allow.txt
expect Figures-allow-listed-block-passes 0 python3 -I scripts/check-internal-figures.py --tree
expect Figures-allow-listed-block-files-mode 0 python3 -I scripts/check-internal-figures.py --files note.md
printf 'The maximum %s was 987654.\nAnd 12 more.\n' "$w_on" > note.md
git add note.md
expect Figures-number-added-to-allow-listed-block 1 python3 -I scripts/check-internal-figures.py --tree
printf 'The maximum %s was 987654.\n' "$w_on" > note.md
git add note.md
printf 'note.md\t%s\ttemporary\treword pending\n' "$key" > scripts/internal-figures-allow.txt
expect Figures-temporary-entry-passes 0 python3 -I scripts/check-internal-figures.py --tree
expect Figures-temporary-entry-fails-no-temporary 1 python3 -I scripts/check-internal-figures.py --tree --no-temporary
git rm -q --cached note.md && rm -f note.md
expect Figures-stale-entry-fails 1 python3 -I scripts/check-internal-figures.py --tree
expect Figures-stale-rule-tree-mode-only 0 python3 -I scripts/check-internal-figures.py --files notes.md
printf 'note.md\t%s\tpermanent\n' "$key" > scripts/internal-figures-allow.txt
expect Figures-malformed-allow-line 2 python3 -I scripts/check-internal-figures.py --tree
printf 'note.md\t%s\tforever\treviewed\n' "$key" > scripts/internal-figures-allow.txt
expect Figures-unknown-entry-kind 2 python3 -I scripts/check-internal-figures.py --tree
printf 'note.md\t%s\tpermanent\tr\nnote.md\t%s\tpermanent\tr\n' "$key" "$key" > scripts/internal-figures-allow.txt
expect Figures-duplicate-entry 2 python3 -I scripts/check-internal-figures.py --tree
rm -f scripts/internal-figures-allow.txt
# The term list is required and validated (fail closed).
cp scripts/internal-figures-terms.txt "$work/terms.keep"
printf '# no entries\n' > scripts/internal-figures-terms.txt
expect Figures-empty-term-list 2 python3 -I scripts/check-internal-figures.py --tree
printf 'meas(ured\n' > scripts/internal-figures-terms.txt
expect Figures-invalid-term 2 python3 -I scripts/check-internal-figures.py --tree
rm -f scripts/internal-figures-terms.txt
expect Figures-missing-term-list 2 python3 -I scripts/check-internal-figures.py --tree
cp "$work/terms.keep" scripts/internal-figures-terms.txt
expect Figures-unreadable-file 2 python3 -I scripts/check-internal-figures.py --files missing.md
# Commit messages: the hook mode and the CI range (commits before the gate's cut-off date are not checked).
printf 'fix: lower the limit\n\nThe largest value %s the phones was 37.\n' "$w_on" > "$work/msg"
expect Figures-message-flagged 1 python3 -I scripts/check-internal-figures.py --message "$work/msg"
printf 'fix: lower the limit\n\n# The largest value %s the phones was 37.\n' "$w_on" > "$work/msg"
expect Figures-message-comment-lines-ignored 0 python3 -I scripts/check-internal-figures.py --message "$work/msg"
printf 'fix: lower the limit to 37\n' > "$work/msg"
expect Figures-message-clean 0 python3 -I scripts/check-internal-figures.py --message "$work/msg"
echo a > a.txt && git add a.txt && git commit -q -s -m "limit from $w_ours: 37"
expect Figures-range-flags-new-message 1 python3 -I scripts/check-internal-figures.py --range HEAD~1..HEAD
echo b > b.txt && git add b.txt
GIT_COMMITTER_DATE="2026-11-01T12:00:00Z" git commit -q -s -m "limit from $w_ours: 37"
expect Figures-range-skips-messages-before-cutoff 0 python3 -I scripts/check-internal-figures.py --range HEAD~1..HEAD
expect Figures-range-includes-earlier-commits 1 python3 -I scripts/check-internal-figures.py --range HEAD~2..HEAD

# --- Internal process IDs and workspace paths (files, messages, baseline) -------------------------------------------
# IDs are assembled at run time so that this file itself passes the gate.
new_repo
mkdir -p scripts
cp "$here/check-internal-ids.py" scripts/
dec="D-$(printf '%03d' 42)"
que="Q-$(printf '%03d' 7)"
fnd="SR-$(printf '%02d' 3)"
asg="M1-DO-$(printf '%d' 9)"
short="m-$(printf '%d' 3)"
wpath="manage""ment/PLAN.md"
rel_wpath="../manage""ment/PLAN.md"
logpath="logs/code-""reviewer/x.txt"
scratch="Scratch""Pad/x"
legalpath="docs/le""gal/brief.md"
slpath="security-""local/x.txt"
rg="RG-$(printf '%d' 10)"
nbdash="D$(printf '\342\200\221')$(printf '%03d' 42)"
minus="Q$(printf '\342\210\222')$(printf '%03d' 7)"
printf '// SPDX-License-Identifier: Apache-2.0\n// Covers FR-001, PRIV-004, ST-009 and DR-001 (ADR 0006).\nfun f(a: IntArray, m: Int) = a[m-1]\n' > Ok.kt
git add . && git commit -q -s -m "clean"
expect IDs-requirement-ids-and-code-allowed 0 python3 -I scripts/check-internal-ids.py --tree
# Probe labels are neutral words, never the planted value, so the CI log of this script holds no ID or path.
plant() { # plant <label> <value> : the value in a tracked note must fail the gate
    printf '# Note (%s)\n' "$2" > note.md
    git add note.md
    expect "IDs-planted-$1" 1 python3 -I scripts/check-internal-ids.py --tree
}
plant decision-number "$dec"
plant question-number "$que"
plant review-finding-label "$fnd"
plant assignment-label "$asg"
plant workspace-path "$wpath"
plant relative-workspace-path "$rel_wpath"
plant role-log-path "$logpath"
plant scratch-folder "$scratch"
plant legal-docs-path "$legalpath"
plant release-gate-item "$rg"
plant non-breaking-dash "$nbdash"
plant minus-sign "$minus"
# The gate's own failure output names file, line, column and kind but never repeats the matched text.
printf '# Note (%s, %s, %s)\n' "$dec" "$wpath" "$fnd" > note.md
git add note.md
python3 -I scripts/check-internal-ids.py --tree > "$work/out.txt" 2>&1
got=$?
if [ "$got" -eq 1 ] && grep -qF "note.md:1: internal ID at column" "$work/out.txt" \
    && grep -qF "workspace path at column" "$work/out.txt" \
    && ! grep -qF -e "$dec" -e "$wpath" -e "$fnd" "$work/out.txt"; then
    pass=$((pass + 1)); echo "test-gates: IDs-output-never-repeats-match PASS"
else
    fail=$((fail + 1)); echo "test-gates: IDs-output-never-repeats-match FAIL (exit $got; output not shown, it may hold the probe)"
fi
# File and directory NAMES are checked too, and a matching path is never printed (index + path hash instead).
git rm -q -f note.md
plant_path() { # plant_path <label> <path> : a tracked file at that path (clean content) must fail the gate
    mkdir -p "$(dirname "$2")" && printf 'clean\n' > "$2"
    git add "$2"
    expect "IDs-planted-$1" 1 python3 -I scripts/check-internal-ids.py --tree
    git rm -q -f "$2"
}
plant_path id-in-file-name "notes-$dec.md"
plant_path id-in-directory-name "dir-$que/a.md"
plant_path workspace-folder-in-path "$slpath"
# Non-ASCII names are listed NUL-separated and opened as they are (a quoted name was skipped before).
cafe="caf$(printf '\303\251')"
printf '# Note (%s)\n' "$dec" > "$cafe-notes.md"
git add "$cafe-notes.md"
expect IDs-planted-id-in-non-ascii-named-file 1 python3 -I scripts/check-internal-ids.py --tree
git rm -q -f "$cafe-notes.md"
plant_path non-ascii-dash-in-file-name "notes-$nbdash.md"
plant_path non-ascii-name-and-id-in-name "$cafe-$dec.md"
# A listed file that cannot be read fails closed (exit 2), in both modes.
expect IDs-unreadable-listed-file-fails 2 python3 -I scripts/check-internal-ids.py --files no-such-file.md
printf 'clean\n' > gone.md && git add gone.md && rm -f gone.md
expect IDs-tracked-file-missing-fails 2 python3 -I scripts/check-internal-ids.py --tree
git rm -q --cached gone.md
printf 'clean\n' > "notes-$dec.md"
expect IDs-files-mode-id-in-file-name 1 python3 -I scripts/check-internal-ids.py --files "notes-$dec.md"
git add "notes-$dec.md"
python3 -I scripts/check-internal-ids.py --tree > "$work/out.txt" 2>&1
got=$?
if [ "$got" -eq 1 ] && grep -qF "internal ID in the path" "$work/out.txt" && grep -qF "(path SHA-256 " "$work/out.txt" \
    && ! grep -qF -e "$dec" -e "notes-" "$work/out.txt"; then
    pass=$((pass + 1)); echo "test-gates: IDs-path-never-repeated PASS"
else
    fail=$((fail + 1)); echo "test-gates: IDs-path-never-repeated FAIL (exit $got; output not shown, it may hold the probe)"
fi
git rm -q -f "notes-$dec.md"
# The column is that of the original line (two ligature characters before the probe expand under NFKC).
printf '%s%s %s\n' "$(printf '\357\254\201')" "$(printf '\357\254\201')" "$dec" > lig.md
python3 -I scripts/check-internal-ids.py --files lig.md > "$work/out.txt" 2>&1
if grep -qF "lig.md:1: internal ID at column 4" "$work/out.txt"; then
    pass=$((pass + 1)); echo "test-gates: IDs-column-of-original-line PASS"
else
    fail=$((fail + 1)); echo "test-gates: IDs-column-of-original-line FAIL"
fi
rm -f lig.md
# git error text quoting a revision argument is redacted.
python3 -I scripts/check-internal-ids.py --range "no-such-$dec..HEAD" > "$work/out.txt" 2>&1
got=$?
if [ "$got" -eq 2 ] && grep -qF "git rev-list failed" "$work/out.txt" && ! grep -qF "$dec" "$work/out.txt"; then
    pass=$((pass + 1)); echo "test-gates: IDs-git-error-redacted PASS"
else
    fail=$((fail + 1)); echo "test-gates: IDs-git-error-redacted FAIL (exit $got; output not shown, it may hold the probe)"
fi
printf '// SPDX-License-Identifier: Apache-2.0\nfun g() = 1 // review %s\n' "$short" > note.md
git add note.md
expect IDs-short-label-in-comment 1 python3 -I scripts/check-internal-ids.py --tree
expect IDs-files-mode-rejects 1 python3 -I scripts/check-internal-ids.py --files note.md
git rm -q -f note.md
expect IDs-files-mode-clean 0 python3 -I scripts/check-internal-ids.py --files Ok.kt
range_base=$(git rev-parse HEAD)
echo r > r.txt && git add r.txt && git commit -q -s -m "clean change"
expect IDs-range-clean 0 python3 -I scripts/check-internal-ids.py --range "$range_base..HEAD"
echo s > s.txt && git add s.txt && git commit -q -s -m "change for $dec"
expect IDs-range-rejects-message 1 python3 -I scripts/check-internal-ids.py --range "$range_base..HEAD"
git reset -q --hard HEAD~1
git checkout -q -b side && echo t > t.txt && git add t.txt && git commit -q -s -m "side change"
git checkout -q main && git merge -q --no-ff side -m "Merge side for $que"
expect IDs-range-rejects-merge-message 1 python3 -I scripts/check-internal-ids.py --range "$range_base..HEAD"
git reset -q --hard "$range_base"
printf 'feat: x\n\nRefs: ST-009, %s\n' "$dec" > "$work/msg"
expect IDs-commit-message-rejected 1 python3 -I scripts/check-internal-ids.py --message "$work/msg"
printf 'feat: x\n\nRefs: ST-009, PRIV-004\n' > "$work/msg"
expect IDs-commit-message-clean 0 python3 -I scripts/check-internal-ids.py --message "$work/msg"
# Baseline: a pinned line passes unchanged; an edited line fails; adding entries fails.
printf '// SPDX-License-Identifier: Apache-2.0\n// legacy note (%s)\nfun h() = 2\n' "$dec" > Legacy.kt
digest=$(printf '// legacy note (%s)' "$dec" | python3 -I -c 'import hashlib,sys; print(hashlib.sha256(sys.stdin.read().encode()).hexdigest())')
printf '# baseline\nLegacy.kt\t%s\n' "$digest" > scripts/internal-id-baseline.txt
git add . && git commit -q -s -m "baseline"
base=$(git rev-parse HEAD)
expect IDs-baseline-pinned-line-passes 0 python3 -I scripts/check-internal-ids.py --tree
printf '// SPDX-License-Identifier: Apache-2.0\n// legacy note, edited (%s)\nfun h() = 2\n' "$dec" > Legacy.kt
git add Legacy.kt
expect IDs-baseline-edited-line-fails 1 python3 -I scripts/check-internal-ids.py --tree
git checkout -q -- Legacy.kt 2>/dev/null; git reset -q --hard
printf 'Other.kt\t%s\n' "$digest" >> scripts/internal-id-baseline.txt
expect IDs-baseline-may-only-shrink 1 python3 -I scripts/check-internal-ids.py --baseline-shrinks-only "$base"
git checkout -q -- scripts/internal-id-baseline.txt

# --- Wording allow-list must not keep stale lines; test-folder detection by source set ----------------------------------
new_repo
mkdir -p config/wording faceblur/src/commonMain/kotlin/x/TestPatterns
printf 'anonymi[sz]\n' > config/wording/terms.txt
printf 'The library anonymises photos.\n' > README.md
printf 'The library anonymises photos.\n' > config/wording/allow.txt
git add . && git commit -q -s -m "list"
expect Wording-allow-line-in-use 0 sh "$here/check-wording.sh" --require
printf 'The library anonymises photos.\nA line that no longer exists, anonymised.\n' > config/wording/allow.txt
expect Wording-stale-allow-line-fails 1 sh "$here/check-wording.sh" --require
git checkout -q -- config/wording/allow.txt
printf '// SPDX-License-Identifier: Apache-2.0\n/** Anonymises. */\nfun t() = 1\n' > faceblur/src/commonMain/kotlin/x/TestPatterns/T.kt
git add faceblur
expect Wording-library-folder-named-Test-in-scope 1 sh "$here/check-wording.sh" --require

# --- Path filters (docs-only via merge base, main never skipped, verification build) ----------------------------------
new_repo
echo a > a.kt && git add . && git commit -q -s -m "base"
git branch -q -f origin-main-ref HEAD
git update-ref refs/remotes/origin/main HEAD
git checkout -q -b feat
mkdir -p docs && echo d > docs/x.md && echo r > README.md && git add . && git commit -q -s -m "docs"
expect_output Docs-only-branch-skip "code=false verification=false ci_config=false" sh "$here/ci-changes.sh" push refs/heads/feat HEAD
expect_output Docs-only-main-never-skipped "code=true verification=false ci_config=true" sh "$here/ci-changes.sh" push refs/heads/main HEAD
expect_output Docs-only-dispatch "code=true verification=false ci_config=true" sh "$here/ci-changes.sh" workflow_dispatch refs/heads/feat HEAD
# A code commit followed by a docs-only push must still build (whole branch vs merge base).
git checkout -q -b feat2 origin/main
echo b > b.kt && git add . && git commit -q -s -m "code"
mkdir -p docs && echo e > docs/y.md
git add . && git commit -q -s -m "docs after code"
expect_output Docs-after-code-builds "code=true verification=false ci_config=false" sh "$here/ci-changes.sh" push refs/heads/feat2 HEAD
# A change of a compiled guide snippet is never docs-only (the build job compiles it); other guide files still are.
new_repo
echo a > a.kt && mkdir -p docs/guides/snippets/android && echo g > docs/guides/guide.md
echo j > docs/guides/snippets/android/J.java && git add . && git commit -q -s -m "base"
git update-ref refs/remotes/origin/main HEAD
git checkout -q -b snip1
echo h >> docs/guides/guide.md && git add . && git commit -q -s -m "guide text"
expect_output Snippets-guide-text-docs-only "code=false verification=false ci_config=false" sh "$here/ci-changes.sh" push refs/heads/snip1 HEAD
git checkout -q -b snip2 origin/main
echo k >> docs/guides/snippets/android/J.java && git add . && git commit -q -s -m "snippet"
expect_output Snippets-change-builds "code=true verification=false ci_config=false" sh "$here/ci-changes.sh" push refs/heads/snip2 HEAD
# Verification build present: runs for verification/ or library changes, not for unrelated scripts.
new_repo
mkdir -p verification faceblur/src scripts
echo s > verification/settings.gradle.kts && echo a > faceblur/src/A.kt && echo t > scripts/t.sh
git add . && git commit -q -s -m "base"
git update-ref refs/remotes/origin/main HEAD
git checkout -q -b f1
echo u > scripts/t.sh && git add . && git commit -q -s -m "script"
expect_output Verification-skip "code=true verification=false ci_config=true" sh "$here/ci-changes.sh" push refs/heads/f1 HEAD
git checkout -q -b f2 origin/main
echo b > faceblur/src/A.kt && git add . && git commit -q -s -m "library"
expect_output Verification-library "code=true verification=true ci_config=false" sh "$here/ci-changes.sh" push refs/heads/f2 HEAD
git checkout -q -b f3 origin/main
echo c > verification/C.kt && git add . && git commit -q -s -m "verification"
expect_output Verification-own "code=true verification=true ci_config=false" sh "$here/ci-changes.sh" push refs/heads/f3 HEAD
expect_output Verification-main "code=true verification=true ci_config=true" sh "$here/ci-changes.sh" push refs/heads/main HEAD
# CI or build configuration changed on a branch: the build and verification jobs run on the push (ci_config).
for cfg in .github/workflows/ci.yml gradle/libs.versions.toml verification/gradle/verification-metadata.xml \
    verification/sub/build.gradle.kts settings.gradle.kts gradle.properties; do
    git checkout -q -b "cfg-$(echo "$cfg" | tr '/.' '--')" origin/main
    mkdir -p "$(dirname "$cfg")" && echo x >> "$cfg" && git add . && git commit -q -s -m "config"
    expect_output "Config-change-$cfg" "code=true verification=true ci_config=true" \
        sh "$here/ci-changes.sh" push "refs/heads/cfg" HEAD
done
# Every step of scripts/ci-steps.sh is run by exactly one step of the CI workflow (CI and the local gate cannot drift).
for job in gates build verification; do
    for step in $(sh "$here/ci-steps.sh" --list "$job"); do
        n=$(grep -c "^        run: sh scripts/ci-steps.sh $step\$" "$here/../.github/workflows/ci.yml")
        expect "CI-runs-step-$job/$step" 0 test "$n" -eq 1
    done
done
expect CI-steps-unknown-step 2 sh "$here/ci-steps.sh" no-such-step
# --- Local merge gate: step lists in both directions, failing or empty lists, results header -------------------------
expect CI-step-list-positive 0 python3 -I "$here/check-ci-steps.py" "$here/.."
gate_copy() { # a copy of the gate scripts and the workflow; the planted change is made in the copy
    rm -rf "$work/gc"
    mkdir -p "$work/gc/scripts" "$work/gc/.github/workflows"
    cp "$here/ci-steps.sh" "$here/check-ci-steps.py" "$here/ci-local.sh" "$here/ci-local-results.py" "$work/gc/scripts/"
    cp "$here/../.github/workflows/ci.yml" "$work/gc/.github/workflows/"
}
gate_plant() { # gate_plant <file in the copy> <sed expression>
    sed -i.bak "$2" "$work/gc/$1" && rm -f "$work/gc/$1.bak"
}
gate_copy
expect CI-step-list-copy-positive 0 python3 -I "$work/gc/scripts/check-ci-steps.py" "$work/gc"
gate_copy
gate_plant .github/workflows/ci.yml 's|^        run: sh scripts/ci-steps.sh spdx$|        run: sh scripts/check-spdx.sh|'
expect CI-step-list-inline-check-in-workflow 1 python3 -I "$work/gc/scripts/check-ci-steps.py" "$work/gc"
gate_copy
gate_plant .github/workflows/ci.yml 's|^        run: sh scripts/ci-steps.sh dco$|        run: sh scripts/ci-steps.sh dco-extra|'
expect CI-step-list-workflow-step-not-listed 1 python3 -I "$work/gc/scripts/check-ci-steps.py" "$work/gc"
gate_copy
gate_plant .github/workflows/ci.yml '/^        run: sh scripts\/ci-steps.sh wording$/d'
expect CI-step-list-listed-step-not-in-workflow 1 python3 -I "$work/gc/scripts/check-ci-steps.py" "$work/gc"
gate_copy
gate_plant scripts/ci-steps.sh 's/^    --list)$/    --list-renamed)/'
expect CI-step-list-list-fails 2 python3 -I "$work/gc/scripts/check-ci-steps.py" "$work/gc"
expect CI-local-list-fails-no-pass 2 sh "$work/gc/scripts/ci-local.sh"
gate_copy
gate_plant scripts/ci-steps.sh 's/^            build) echo $BUILD ;;$/            build) ;;/'
expect CI-step-list-empty-list 2 python3 -I "$work/gc/scripts/check-ci-steps.py" "$work/gc"
expect CI-local-empty-list-no-pass 2 sh "$work/gc/scripts/ci-local.sh"
# Results of the build, copied right after it, are the ones counted; failures and disagreements are refused.
snap="$work/snap"
rm -rf "$snap" && mkdir -p "$snap/faceblur/jvmTest" "$snap/verification/m1/test"
printf '<testsuite name="a" tests="3" failures="0" errors="0" skipped="0"/>\n' > "$snap/faceblur/jvmTest/TEST-a.xml"
printf '<testsuite name="b" tests="2" failures="0" errors="0" skipped="1"/>\n' > "$snap/verification/m1/test/TEST-b.xml"
expect_output Results-count-positive "ci-local: results faceblur:jvmTest: tests=3 failures=0 errors=0 skipped=0 ci-local: results verification:m1:test: tests=2 failures=0 errors=0 skipped=1" \
    python3 -I "$here/ci-local-results.py" count "$snap"
printf '<testsuite name="c" tests="1" failures="1" errors="0" skipped="0"/>\n' > "$snap/faceblur/jvmTest/TEST-c.xml"
expect Results-count-failure 1 python3 -I "$here/ci-local-results.py" count "$snap"
rm -rf "$snap" && mkdir -p "$snap"
expect Results-count-empty 1 python3 -I "$here/ci-local-results.py" count "$snap"
gate_log() { # gate_log <header jvmTest tests> <header jvmTest failures> <declared steps>
    {
        echo "ci-local: --- gates/dco PASS (1 s)"
        echo "check-test-results: jvmTest: 350 tests, 0 skipped, 0 failures (53 result files)"
        echo "check-test-results: test: 137 tests, 0 skipped, 0 failures (4 result files)"
        echo "ci-local: results faceblur:jvmTest: tests=$1 failures=$2 errors=0 skipped=0"
        echo "ci-local: results verification:jpeg-privacy:test: tests=137 failures=0 errors=0 skipped=0"
        echo "ci-local: --- results/snapshot PASS (0 s)"
        echo "ci-local: RESULT PASS - steps=$3 (every step)"
    } > "$work/gate.log"
}
gate_log 350 0 2
expect Results-log-positive 0 python3 -I "$here/ci-local-results.py" verify-log "$work/gate.log"
gate_log 1 1 2
expect Results-log-overwritten-results-refused 1 python3 -I "$here/ci-local-results.py" verify-log "$work/gate.log"
gate_log 349 0 2
expect Results-log-disagrees-with-check 1 python3 -I "$here/ci-local-results.py" verify-log "$work/gate.log"
gate_log 350 0 3
expect Results-log-step-count-mismatch 1 python3 -I "$here/ci-local-results.py" verify-log "$work/gate.log"
printf 'ci-local: RESULT PASS - steps=0 (every step)\n' > "$work/gate.log"
expect Results-log-zero-steps 1 python3 -I "$here/ci-local-results.py" verify-log "$work/gate.log"

# --- Tree-hash reuse on main (scripts/ci-reuse.py): skip only on a clear earlier success, fail-safe otherwise -----------
# Fake API answers in $work/api. reuse_runs writes runs.json from "id:event:tree:minutes-ago[:path[:head repo]]" specs
# (head sha = "a" * 40; tree NULL = head_commit null; minutes BAD = unparsable created_at); reuse_jobs <run id> writes
# the job list from "name|conclusion[|head sha[|status]]" specs (one entry per job attempt).
api="$work/api"
tree_a=$(printf 'a%.0s' $(seq 40))
tree_b=$(printf 'b%.0s' $(seq 40))
reuse_runs() {
    rm -rf "$api" && mkdir -p "$api"
    python3 -I -c '
import json, sys
from datetime import datetime, timedelta
now = datetime(2026, 11, 3, 12, 0, 0)
runs = []
for spec in sys.argv[2:]:
    p = spec.split(":")
    path = p[4] if len(p) > 4 and p[4] else ".github/workflows/ci.yml"
    head = p[5] if len(p) > 5 else "o/r"
    created = "yesterday" if p[3] == "BAD" else (now - timedelta(minutes=int(p[3]))).strftime("%Y-%m-%dT%H:%M:%SZ")
    runs.append({"id": int(p[0]), "event": p[1], "status": "completed", "path": path, "created_at": created,
                 "repository": {"full_name": "o/r"}, "head_repository": {"full_name": head},
                 "head_sha": "a" * 40, "head_commit": None if p[2] == "NULL" else {"tree_id": p[2]}})
json.dump({"total_count": len(runs), "workflow_runs": runs}, open(sys.argv[1], "w"))
' "$api/runs.json" "$@"
}
reuse_jobs() {
    run=$1
    shift
    python3 -I -c '
import json, sys
jobs = []
for spec in sys.argv[2:]:
    p = spec.split("|")
    jobs.append({"name": p[0], "status": p[3] if len(p) > 3 else "completed",
                 "conclusion": p[1] or None, "head_sha": p[2] if len(p) > 2 and p[2] else "a" * 40})
json.dump({"total_count": len(jobs), "jobs": jobs}, open(sys.argv[1], "w"))
' "$api/jobs-$run.json" "$@"
}
reuse() { # reuse [extra args] : decision for the jobs "Heavy" (key build) and "Verify" (key verification)
    python3 -I "$here/ci-reuse.py" --repo o/r --workflow-path .github/workflows/ci.yml --tree "$tree_a" \
        --exclude-run 999 --run-attempt 1 --max-age-hours 24 --job build=Heavy --job verification=Verify --fake-api "$api" \
        --now 2026-11-03T12:00:00Z "$@"
}
reuse_runs 11:workflow_dispatch:"$tree_a":60
reuse_jobs 11 "Gates|success" "Heavy|success" "Verify|success"
expect_output Reuse-same-tree "reuse-build=true reuse-build-run=11 reuse-verification=true reuse-verification-run=11" reuse
reuse_runs 11:workflow_dispatch:"$tree_b":60
reuse_jobs 11 "Heavy|success" "Verify|success"
expect_output Reuse-other-tree "reuse-build=false reuse-build-run= reuse-verification=false reuse-verification-run=" reuse
reuse_runs 11:push:"$tree_a":60
reuse_jobs 11 "Heavy|failure" "Verify|success"
expect_output Reuse-job-failed "reuse-build=false reuse-build-run= reuse-verification=true reuse-verification-run=11" reuse
reuse_runs 11:push:"$tree_a":60
reuse_jobs 11 "Heavy|skipped" "Verify|cancelled"
expect_output Reuse-skipped-cancelled "reuse-build=false reuse-build-run= reuse-verification=false reuse-verification-run=" reuse
reuse_runs 12:push:"$tree_a":30 11:workflow_dispatch:"$tree_a":60
reuse_jobs 12 "Heavy|failure" "Verify|cancelled"
reuse_jobs 11 "Heavy|success" "Verify|success"
expect_output Reuse-newer-failure-is-doubt "reuse-build=false reuse-build-run= reuse-verification=true reuse-verification-run=11" reuse
reuse_runs 11:workflow_dispatch:"$tree_a":1500
reuse_jobs 11 "Heavy|success" "Verify|success"
expect_output Reuse-too-old "reuse-build=false reuse-build-run= reuse-verification=false reuse-verification-run=" reuse
reuse_runs 999:push:"$tree_a":1 13:push:"$tree_a":5:.github/workflows/fuzz.yml 14:push:"$tree_a":5::fork/r 15:schedule:"$tree_a":5
for r in 999 13 14 15; do reuse_jobs "$r" "Heavy|success" "Verify|success"; done
expect_output Reuse-own-run-other-workflow-fork-event "reuse-build=false reuse-build-run= reuse-verification=false reuse-verification-run=" reuse
reuse_runs 11:workflow_dispatch:"$tree_a":60
reuse_jobs 11 "Heavy|success|$tree_b" "Verify|success"
expect_output Reuse-job-head-mismatch "reuse-build=false reuse-build-run= reuse-verification=true reuse-verification-run=11" reuse
reuse_runs 11:workflow_dispatch:"$tree_a":60
expect_output Reuse-api-error-runs-all "reuse-build=false reuse-build-run= reuse-verification=false reuse-verification-run=" reuse
expect Reuse-api-error-exit-0 0 reuse
reuse_runs 11:workflow_dispatch:not-a-tree:60
reuse_jobs 11 "Heavy|success" "Verify|success"
expect_output Reuse-bad-response-runs-all "reuse-build=false reuse-build-run= reuse-verification=false reuse-verification-run=" reuse
expect_output Reuse-no-token-runs-all "reuse-build=false reuse-build-run=" env -u GH_TOKEN python3 -I "$here/ci-reuse.py" \
    --repo o/r --workflow-path .github/workflows/ci.yml --tree "$tree_a" --exclude-run 1 --run-attempt 1 \
    --max-age-hours 24 --job build=Heavy
expect Reuse-usage-error 2 python3 -I "$here/ci-reuse.py" --repo o/r --tree "$tree_a"
# Review follow-ups: re-runs, every attempt, more cases that must fail safe.
reuse_runs 11:workflow_dispatch:"$tree_a":60
reuse_jobs 11 "Heavy|failure" "Heavy|success" "Verify|success"
expect_output Reuse-earlier-attempt-failed "reuse-build=false reuse-build-run= reuse-verification=true reuse-verification-run=11" reuse
reuse_jobs 11 "Heavy|success" "Verify|success"
expect_output Reuse-rerun-of-main-never-reuses "reuse-build=false reuse-build-run= reuse-verification=false reuse-verification-run=" \
    python3 -I "$here/ci-reuse.py" --repo o/r --workflow-path .github/workflows/ci.yml --tree "$tree_a" --exclude-run 999 \
    --run-attempt 2 --max-age-hours 24 --job build=Heavy --job verification=Verify --fake-api "$api" --now 2026-11-03T12:00:00Z
reuse_jobs 11 "Heavy|success" "Verify||$tree_b|in_progress"
expect_output Reuse-job-still-running "reuse-build=true reuse-build-run=11 reuse-verification=false reuse-verification-run=" reuse
reuse_runs 11:workflow_dispatch:NULL:60
reuse_jobs 11 "Heavy|success" "Verify|success"
expect_output Reuse-null-head-commit "reuse-build=false reuse-build-run= reuse-verification=false reuse-verification-run=" reuse
reuse_runs 11:workflow_dispatch:"$tree_a":BAD
reuse_jobs 11 "Heavy|success" "Verify|success"
expect_output Reuse-bad-timestamp "reuse-build=false reuse-build-run= reuse-verification=false reuse-verification-run=" reuse
# shellcheck disable=SC2046 # one spec per word
reuse_runs $(for r in $(seq 30 50); do echo "$r:push:$tree_a:5"; done)
for r in $(seq 30 50); do reuse_jobs "$r" "Heavy|success" "Verify|success"; done
expect_output Reuse-too-many-candidates "reuse-build=false reuse-build-run= reuse-verification=false reuse-verification-run=" reuse
reuse_runs 11:workflow_dispatch:"$tree_a":60
reuse_jobs 11 "Heavy|success" "Verify|success"
mkdir -p "$work/summary-dir"
expect_output Reuse-summary-unwritable "reuse-build=false reuse-build-run= reuse-verification=false reuse-verification-run=" \
    env GITHUB_STEP_SUMMARY="$work/summary-dir" python3 -I "$here/ci-reuse.py" --repo o/r \
    --workflow-path .github/workflows/ci.yml --tree "$tree_a" --exclude-run 999 --run-attempt 1 --max-age-hours 24 \
    --job build=Heavy --job verification=Verify --fake-api "$api" --now 2026-11-03T12:00:00Z

# --- Dependency verification must stay on --------------------------------------------------------------------------
new_repo
mkdir -p gradle sub/gradle
echo 'rootProject.name = "x"' > settings.gradle.kts
printf '<verification-metadata>\n<configuration>\n<verify-metadata>true</verify-metadata>\n</configuration>\n</verification-metadata>\n' \
    > gradle/verification-metadata.xml
echo 'org.gradle.caching=true' > gradle.properties
git add . && git commit -q -s -m "base"
expect Depverify-positive 0 sh "$here/check-dependency-verification.sh"
printf 'org.gradle.%s.%s=lenient\n' dependency verification >> gradle.properties && git add . && git commit -q -s -m "lenient"
expect Depverify-property-switch 1 sh "$here/check-dependency-verification.sh"
git revert --no-edit HEAD > /dev/null
expect Depverify-reverted 0 sh "$here/check-dependency-verification.sh"
mkdir -p .github/workflows && printf 'run: ./gradlew build --%s-%s=off\n' dependency verification > .github/workflows/ci.yml
git add . && git commit -q -s -m "cli switch"
expect Depverify-cli-switch 1 sh "$here/check-dependency-verification.sh"
git rm -q .github/workflows/ci.yml && git commit -q -s -m "rm"
echo 'rootProject.name = "sub"' > sub/settings.gradle.kts && git add . && git commit -q -s -m "second build"
expect Depverify-build-without-metadata 1 sh "$here/check-dependency-verification.sh"

# --- pre-push hook: the CI fast checks over the pushed range and tip -----------------------------------------------
# The real hook pushes to a local bare repository; only the data-admission step is replaced by a stub that blocks while
# the file "deny" exists (the real guard has its own suite). The test commits are made with the hooks path emptied, so
# the per-commit hooks do not run: this is what a clone without core.hooksPath (or a skipped hook) would produce.
new_repo
git init -q --bare -b main "$work/origin"
mkdir -p .githooks scripts config/data-admission config/wording
cp "$here/../.githooks/pre-push" .githooks/
cp "$here/check-wording.sh" "$here/check-internal-ids.py" "$here/check-dco.sh" "$here/check-internal-figures.py" \
    "$here/internal-figures-terms.txt" scripts/
printf '#!/bin/sh\n# SPDX-License-Identifier: Apache-2.0\nif [ -e "%s/deny" ]; then echo "stub: blocked" >&2; exit 1; fi\nexit 0\n' \
    "$work" > config/data-admission/check.sh
printf '# test list (not the legal list)\nanonymi[sz]\n' > config/wording/terms.txt
git config core.hooksPath .githooks
git remote add origin "$work/origin"
hc() { git -c core.hooksPath=/dev/null commit -q "$@"; } # a commit without the per-commit hooks
printf '# Guide\nThe library masks faces.\n' > README.md
git add . && hc -s -m "base"
pid="D-$(printf '%03d' 42)"
pfig="internal photo"" sets"
expect_push() { # expect_push <id> <0|1> <text the output must contain when refused> <git push arguments...>
    id=$1
    want=$2
    text=$3
    shift 3
    git push -q "$@" > "$work/out.txt" 2>&1
    got=$?
    reason_ok=1
    if [ "$want" -ne 0 ] && [ -n "$text" ] && ! grep -qF -- "$text" "$work/out.txt"; then reason_ok=0; fi
    if [ "$reason_ok" -eq 1 ] && { [ "$got" -eq 0 ] && [ "$want" -eq 0 ] || { [ "$got" -ne 0 ] && [ "$want" -ne 0 ]; }; }; then
        pass=$((pass + 1))
        echo "test-gates: $id PASS (push exit $got)"
    else
        fail=$((fail + 1))
        echo "test-gates: $id FAIL (push exit $got, expected $([ "$want" -eq 0 ] && echo 0 || echo "non-zero with '$text'")):"
        sed 's/^/    /' "$work/out.txt" | head -12
    fi
}
back_to_remote() { git reset -q --hard origin/main && git clean -q -fd; }
expect_push Prepush-clean-new-branch-passes 0 "" origin main
echo ok > a.txt && git add a.txt && hc -s -m "clean change"
expect_push Prepush-clean-update-passes 0 "" origin main
# DCO: an unsigned commit in the pushed range.
echo b > b.txt && git add b.txt && hc -m "unsigned"
expect_push Prepush-unsigned-commit-refused 1 "pre-push: FAILED - DCO sign-off" origin main
back_to_remote
# Internal ID in a pushed commit message (the message is the only place; the tree is clean).
echo c > c.txt && git add c.txt && hc -s -m "change for $pid"
expect_push Prepush-internal-id-in-message-refused 1 "pre-push: FAILED - internal IDs in commit messages" origin main
back_to_remote
# Internal figures in a pushed commit message.
echo d > d.txt && git add d.txt && hc -s -m "limit from the $pfig: 37 per photo"
expect_push Prepush-internal-figure-in-message-refused 1 "pre-push: FAILED - internal figures in commit messages" origin main
back_to_remote
# Tree checks at the pushed tip. The file is removed from the working tree afterwards: the tip is checked, not the
# working tree.
printf '# Notes\nSee %s.\n' "$pid" > NOTES.md
git add NOTES.md && hc -s -m "notes"
rm -f NOTES.md
expect_push Prepush-internal-id-in-tip-tree-refused 1 "pre-push: FAILED - internal IDs in the tree" origin main
back_to_remote
printf 'The limit keeps a margin over the %s: at most 37 per photo.\n' "$pfig" > FIG.md
git add FIG.md && hc -s -m "figures"
rm -f FIG.md
expect_push Prepush-internal-figure-in-tip-tree-refused 1 "pre-push: FAILED - internal figures in the tree" origin main
back_to_remote
mkdir -p docs && printf 'It anonymises photos.\n' > docs/probe.txt
git add docs/probe.txt && hc -s -m "docs"
rm -rf docs
expect_push Prepush-wording-in-tip-tree-refused 1 "pre-push: FAILED - wording lint on the tree" origin main
back_to_remote
# A tip without the committed term list fails closed (the lint runs with --require).
git rm -q config/wording/terms.txt && hc -s -m "drop the term list"
expect_push Prepush-missing-term-list-refused 1 "pre-push: FAILED - wording lint on the tree" origin main
back_to_remote
# New ref: every commit that is on no remote is checked (an unsigned one, then one with an internal ID).
git checkout -q -b feat
echo e > e.txt && git add e.txt && hc -s -m "first"
echo f > f.txt && git add f.txt && hc -m "second, unsigned"
expect_push Prepush-new-branch-unsigned-commit-refused 1 "pre-push: FAILED - DCO sign-off" origin feat
git reset -q --hard HEAD~1
echo g > g.txt && git add g.txt && hc -s -m "second for $pid"
expect_push Prepush-new-branch-internal-id-refused 1 "pre-push: FAILED - internal IDs in commit messages" origin feat
git reset -q --hard HEAD~1
echo h > h.txt && git add h.txt && hc -s -m "second, clean"
expect_push Prepush-new-branch-clean-passes 0 "" origin feat
# A branch deletion passes (nothing is pushed).
expect_push Prepush-branch-deletion-passes 0 "" origin :feat
# The data-admission guard runs first and blocks on its own.
git checkout -q main
echo i > i.txt && git add i.txt && hc -s -m "clean again"
: > "$work/deny"
expect_push Prepush-data-admission-first-refuses 1 "stub: blocked" origin main
rm -f "$work/deny"
expect_push Prepush-clean-after-data-admission-passes 0 "" origin main
# The temporary worktrees of the tip checks are gone.
expect_output Prepush-no-worktree-left 1 sh -c 'git worktree list | wc -l | tr -d " "'

# --- Publication without the jvm variant ----------------------------------------------------------------------------
new_repo
m=lm/io/github/shishark
mkdir -p "$m/faceblur/0.1.0" "$m/faceblur-android/0.1.0"
variants='{"name":"androidApiElements-published","attributes":{"org.jetbrains.kotlin.platform.type":"androidJvm"}},{"name":"iosArm64ApiElements-published","attributes":{"org.jetbrains.kotlin.platform.type":"native"}},{"name":"iosSimulatorArm64ApiElements-published","attributes":{"org.jetbrains.kotlin.platform.type":"native"}}'
printf '{"variants":[%s]}\n' "$variants" > "$m/faceblur/0.1.0/faceblur-0.1.0.module"
# An Android AAR that declares nothing (its manifest cases are in scripts/test-phone-gates.py).
printf '<project xmlns="http://maven.apache.org/POM/4.0.0"/>\n' > "$m/faceblur-android/0.1.0/faceblur-android-0.1.0.pom"
python3 -I -c 'import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("AndroidManifest.xml", "<manifest package=\"x\"><application /></manifest>")' \
    "$m/faceblur-android/0.1.0/faceblur-android-0.1.0.aar"
expect Publication-positive 0 sh "$here/check-publication.sh" lm
printf '{"variants":[%s,{"name":"jvmApiElements-published","attributes":{"org.jetbrains.kotlin.platform.type":"jvm"}}]}\n' \
    "$variants" > "$m/faceblur/0.1.0/faceblur-0.1.0.module"
expect Publication-jvm-variant 1 sh "$here/check-publication.sh" lm
printf '{"variants":[%s]}\n' "$variants" > "$m/faceblur/0.1.0/faceblur-0.1.0.module"
mkdir -p "$m/faceblur-jvm/0.1.0"
expect Publication-jvm-artifact 1 sh "$here/check-publication.sh" lm

# --- Phone-only coverage list, phone-test logs, AAR manifest, merged-consumer manifest (own suite) ------------------
# Synthetic logs, reports, AARs and manifests in temporary repositories; no device, no build.
expect Phone-and-manifest-gates-suite 0 python3 -I "$here/test-phone-gates.py"

# --- Guide snippets gate (scripts/check-guide-snippets.py): planted defects and the positive case --------------------
gs="$work/gs"
rm -rf "$gs"
mkdir -p "$gs/snip" "$gs/build/docs/snippets/android"
printf 'package docs.snippets.android\n\nclass Svc\n\nfun f() = 1\n' > "$gs/snip/Tools.kt"
printf 'package docs.snippets.android;\n\npublic final class Jv {}\n' > "$gs/snip/Jv.java"
printf '<manifest/>\n' > "$gs/snip/AndroidManifest-no-network.xml"
for c in Svc ToolsKt Jv; do : > "$gs/build/docs/snippets/android/$c.class"; done
android_ns='xmlns:android="http://schemas.android.com/apk/res/android"'
printf '<manifest %s><application/></manifest>\n' "$android_ns" > "$gs/ok.xml"
printf 'package: x\n' > "$gs/ok.txt"
printf '<manifest %s><uses-permission android:name="android.permission.INTERNET"/><uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/><application/></manifest>\n' \
    "$android_ns" > "$gs/both.xml"
printf "package: x\nuses-permission: name='android.permission.ACCESS_NETWORK_STATE'\nuses-permission: name='android.permission.INTERNET'\n" > "$gs/both.txt"
printf "package: x\nuses-permission: name='android.permission.INTERNET'\n" > "$gs/one.txt"
printf '<testsuite><testcase name="cancellingTheFutureStopsTheRunningCallAndNothingIsWritten"/><testcase name="withoutTheCancelTheSameCallWritesTheTarget"/></testsuite>\n' > "$gs/tests.xml"
snippet_gate() { # snippet_gate <merged> <dump> <control merged> <control dump> <tests> [snippet dir]
    python3 -I "$here/check-guide-snippets.py" --snippets "${6:-$gs/snip}" --classes "$gs/build" \
        --manifest-file AndroidManifest-no-network.xml --merged "$1" --permissions "$2" --control-merged "$3" \
        --control-permissions "$4" --test-results "$5"
}
expect Snippets-gate-positive 0 snippet_gate "$gs/ok.xml" "$gs/ok.txt" "$gs/both.xml" "$gs/both.txt" "$gs/tests.xml"
expect Snippets-gate-merged-manifest-keeps-internet 1 snippet_gate "$gs/both.xml" "$gs/ok.txt" "$gs/both.xml" "$gs/both.txt" "$gs/tests.xml"
expect Snippets-gate-apk-keeps-a-permission 1 snippet_gate "$gs/ok.xml" "$gs/one.txt" "$gs/both.xml" "$gs/both.txt" "$gs/tests.xml"
expect Snippets-gate-control-cannot-fail 1 snippet_gate "$gs/ok.xml" "$gs/ok.txt" "$gs/ok.xml" "$gs/ok.txt" "$gs/tests.xml"
printf '<testsuite><testcase name="withoutTheCancelTheSameCallWritesTheTarget"/></testsuite>\n' > "$gs/tests-one.xml"
expect Snippets-gate-cancel-test-missing 1 snippet_gate "$gs/ok.xml" "$gs/ok.txt" "$gs/both.xml" "$gs/both.txt" "$gs/tests-one.xml"
printf '<testsuite><testcase name="cancellingTheFutureStopsTheRunningCallAndNothingIsWritten"><skipped/></testcase><testcase name="withoutTheCancelTheSameCallWritesTheTarget"/></testsuite>\n' > "$gs/tests-skipped.xml"
expect Snippets-gate-cancel-test-skipped 1 snippet_gate "$gs/ok.xml" "$gs/ok.txt" "$gs/both.xml" "$gs/both.txt" "$gs/tests-skipped.xml"
printf '<testsuite><testcase name="cancellingTheFutureStopsTheRunningCallAndNothingIsWritten"><failure/></testcase><testcase name="withoutTheCancelTheSameCallWritesTheTarget"/></testsuite>\n' > "$gs/tests-failed.xml"
expect Snippets-gate-cancel-test-failed 1 snippet_gate "$gs/ok.xml" "$gs/ok.txt" "$gs/both.xml" "$gs/both.txt" "$gs/tests-failed.xml"
rm "$gs/build/docs/snippets/android/ToolsKt.class"
expect Snippets-gate-file-facade-not-compiled 1 snippet_gate "$gs/ok.xml" "$gs/ok.txt" "$gs/both.xml" "$gs/both.txt" "$gs/tests.xml"
: > "$gs/build/docs/snippets/android/ToolsKt.class"
rm "$gs/build/docs/snippets/android/Jv.class"
expect Snippets-gate-java-class-not-compiled 1 snippet_gate "$gs/ok.xml" "$gs/ok.txt" "$gs/both.xml" "$gs/both.txt" "$gs/tests.xml"
: > "$gs/build/docs/snippets/android/Jv.class"
printf 'text\n' > "$gs/snip/Notes.txt"
expect Snippets-gate-unknown-file-kind 1 snippet_gate "$gs/ok.xml" "$gs/ok.txt" "$gs/both.xml" "$gs/both.txt" "$gs/tests.xml"
rm "$gs/snip/Notes.txt"
printf 'package docs.snippets.android\n\nval x = 1\n' > "$gs/snip/Nothing.kt"
expect Snippets-gate-nothing-derivable 1 snippet_gate "$gs/ok.xml" "$gs/ok.txt" "$gs/both.xml" "$gs/both.txt" "$gs/tests.xml"
rm "$gs/snip/Nothing.kt"

# --- Licence notices (ADR 0008): index, runtime dependencies, published files ---------------------------------------
# A small fixture: two components, a runtime lockfile, and a local Maven repository whose AAR (classes.jar), root jar
# and sources/javadoc jars carry the notices. Each defect is planted in a fresh copy of it.
cat > "$work/lic-fixture.py" <<'PYEND'
import io
import os
import sys
import zipfile

root = sys.argv[1]


def w(path, data):
    full = os.path.join(root, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "wb" if isinstance(data, bytes) else "w") as fh:
        fh.write(data)


files = {
    "LICENSE": "Apache text\n",
    "NOTICE": "FaceBlur\nCopyright 2026 the test authors\n",
    "licenses/a.txt": "licence text a\n",
    "licenses/b.txt": "licence text b\n",
    "licenses/INDEX.txt": "# header\nLib A\t1.0\tApache-2.0\ta.txt\tcom.example:liba\taar;klib;xcframework\n"
                          "Lib B\t2.0\tMIT\tb.txt\tcom.example:libb*\tklib\n",
}
for k, v in files.items():
    w(k, v)
w("runtime.lockfile", "# generated\ncom.example:liba:1.0=androidRuntimeClasspath\n"
                      "com.example:libb-ios:2.0=iosArm64CompileKlibraries\n")
P = "META-INF/io.github.shishark.faceblur/"


def entries(kind):
    e = {P + "LICENSE": files["LICENSE"], P + "NOTICE": files["NOTICE"],
         P + "licenses/INDEX.txt": files["licenses/INDEX.txt"], P + "licenses/a.txt": files["licenses/a.txt"]}
    if kind == "klib":
        e[P + "licenses/b.txt"] = files["licenses/b.txt"]
    return e


def jar(kind):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("x/Y.class", "c")
        for k, v in entries(kind).items():
            z.writestr(k, v)
    return buf.getvalue()


base = "local-maven/io/github/shishark/"
for folder, kind in (("faceblur", "klib"), ("faceblur-android", "aar"), ("faceblur-iosarm64", "klib"),
                     ("faceblur-iossimulatorarm64", "klib")):
    d = base + folder + "/1.0/"
    w(d + folder + "-1.0-sources.jar", jar(kind))
    w(d + folder + "-1.0-javadoc.jar", jar(kind))
    if folder == "faceblur":
        w(d + "faceblur-1.0.jar", jar(kind))
    if folder == "faceblur-android":
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("classes.jar", jar("aar"))
            z.writestr("AndroidManifest.xml", "<manifest/>")
        w(d + "faceblur-android-1.0.aar", buf.getvalue())
PYEND
# lic_zip <zip> <name=content...>: replaces the zip with one holding the given entries (planted defect).
cat > "$work/lic-zip.py" <<'PYEND'
import io
import sys
import zipfile

path = sys.argv[1]
entries = [a.split("=", 1) for a in sys.argv[2:]]
with zipfile.ZipFile(path, "w") as z:
    for name, content in entries:
        z.writestr(name, content)
PYEND
lic_new() {
    rm -rf "$work/lic"
    mkdir -p "$work/lic"
    python3 -I "$work/lic-fixture.py" "$work/lic"
    cd "$work/lic" || exit 1
}
lic_check() { python3 -I "$here/licence-notices.py" check "$@"; }
lic_new
expect Licence-positive-index 0 lic_check
expect Licence-positive-lockfile-and-publication 0 lic_check --lockfile runtime.lockfile --repo local-maven
mkdir -p stage
expect Licence-stage-xcframework 0 python3 -I "$here/licence-notices.py" stage xcframework stage
if [ -f stage/LICENSE ] && [ -f stage/NOTICE ] && [ -f stage/licenses/a.txt ] && [ ! -e stage/licenses/b.txt ]; then
    pass=$((pass + 1)); echo "test-gates: Licence-stage-xcframework-content PASS"
else
    fail=$((fail + 1)); echo "test-gates: Licence-stage-xcframework-content FAIL"
fi
lic_new; rm licenses/a.txt
expect Licence-text-missing 1 lic_check
lic_new; : > licenses/a.txt
expect Licence-text-empty 1 lic_check
lic_new; echo extra > licenses/unused.txt
expect Licence-text-unused 1 lic_check
lic_new; printf 'Lib A\t1.0\tApache-2.0\ta.txt\tcom.example:liba\n' >> licenses/INDEX.txt
expect Licence-index-column-count 1 lic_check
lic_new; printf 'Lib C\t1.0\tMIT\ta.txt\tcom.example:libc\tnowhere\n' >> licenses/INDEX.txt
expect Licence-index-unknown-kind 1 lic_check
lic_new; printf 'FaceBlur\n\nno copyright line\n' > NOTICE
expect Licence-notice-without-copyright 1 lic_check
lic_new; rm NOTICE
expect Licence-notice-missing 1 lic_check
lic_new; printf 'com.example:newdep:3.0=androidRuntimeClasspath\n' >> runtime.lockfile
expect Licence-runtime-dependency-without-row 1 lic_check --lockfile runtime.lockfile
lic_new; sed 's/liba:1.0/liba:1.1/' runtime.lockfile > l2; mv l2 runtime.lockfile
expect Licence-runtime-version-differs 1 lic_check --lockfile runtime.lockfile
lic_new; grep -v 'libb-ios' runtime.lockfile > l2; mv l2 runtime.lockfile
expect Licence-stale-row 1 lic_check --lockfile runtime.lockfile
lic_new; : > runtime.lockfile
expect Licence-empty-lockfile 1 lic_check --lockfile runtime.lockfile
lic_new
python3 -I "$work/lic-zip.py" "$work/inner.jar" "x/Y.class=c"
python3 -I - "$work" <<'PYEND'
import sys
import zipfile

work = sys.argv[1]
aar = "local-maven/io/github/shishark/faceblur-android/1.0/faceblur-android-1.0.aar"
with zipfile.ZipFile(aar, "w") as z:
    z.write(work + "/inner.jar", "classes.jar")
PYEND
expect Licence-aar-without-notices 1 lic_check --repo local-maven
lic_new
python3 -I "$work/lic-zip.py" local-maven/io/github/shishark/faceblur-iosarm64/1.0/faceblur-iosarm64-1.0-sources.jar "a/B.kt=x"
expect Licence-sources-jar-without-notices 1 lic_check --repo local-maven
lic_new
python3 -I "$work/lic-zip.py" local-maven/io/github/shishark/faceblur/1.0/faceblur-1.0-javadoc.jar \
    "META-INF/io.github.shishark.faceblur/LICENSE=something else"
expect Licence-javadoc-jar-different-text 1 lic_check --repo local-maven
lic_new; rm local-maven/io/github/shishark/faceblur/1.0/faceblur-1.0.jar
expect Licence-root-jar-missing 1 lic_check --repo local-maven
lic_new; mkdir -p local-maven/io/github/shishark/faceblur-jvm/1.0
expect Licence-publication-without-rule 1 lic_check --repo local-maven
mkdir -p "$work/lic-empty"
cd "$work/lic-empty" || exit 1
expect Licence-no-index 2 lic_check
cd "$here" || exit 1

# --- Runtime dependencies of the Android artifact (scripts/check-runtime-deps.py, PRIV-014) ----------------------------
# A fixture allow-list (the real format header), a runtime lockfile and a local Maven repository with a POM and module
# metadata; each defect is planted in a fresh copy.
rd_fixture() { # rd_fixture <dir>: a fresh copy, made the current directory
    cd "$work" || exit 1
    rm -rf "$1"
    mkdir -p "$1/repo/io/github/shishark/faceblur-android/1.0"
    head -2 "$here/../config/runtime-deps/android.txt" > "$1/allow.txt"
    printf '# comment\norg.example:core:1.0\tthe core\norg.example:util:2.0\tthe utilities\n' >> "$1/allow.txt"
    printf '# lockfile\norg.example:core:1.0=androidRuntimeClasspath\norg.example:util:2.0=androidRuntimeClasspath\n' \
        > "$1/runtime.lockfile"
    printf 'org.example:other:9.9=jvmRuntimeClasspath\n' >> "$1/runtime.lockfile"
    cat > "$1/repo/io/github/shishark/faceblur-android/1.0/faceblur-android-1.0.pom" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies>
<dependency><groupId>org.example</groupId><artifactId>core</artifactId><version>1.0</version><scope>compile</scope></dependency>
<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4</version><scope>test</scope></dependency>
</dependencies></project>
POM
    printf '{"variants":[{"name":"androidRuntimeElements-published","dependencies":[{"group":"org.example","module":"util","version":{"requires":"2.0"}}]}]}\n' \
        > "$1/repo/io/github/shishark/faceblur-android/1.0/faceblur-android-1.0.module"
    # The root publication: its POM and metadata variant declare the common dependencies; android points elsewhere.
    mkdir -p "$1/repo/io/github/shishark/faceblur/1.0"
    cat > "$1/repo/io/github/shishark/faceblur/1.0/faceblur-1.0.pom" <<'POM'
<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies>
<dependency><groupId>org.example</groupId><artifactId>core</artifactId><version>1.0</version><scope>runtime</scope></dependency>
</dependencies></project>
POM
    printf '{"variants":[{"name":"metadataApiElements","dependencies":[{"group":"org.example","module":"core","version":{"requires":"1.0"}}]},{"name":"androidRuntimeElements-published","available-at":{"module":"faceblur-android"}}]}\n' \
        > "$1/repo/io/github/shishark/faceblur/1.0/faceblur-1.0.module"
    cd "$1" || exit 1
}
rd() { python3 -I "$here/check-runtime-deps.py" --lockfile runtime.lockfile --allow allow.txt --repo repo; }
rd_dir="$work/runtime-deps"
rd_fixture "$rd_dir"
expect RuntimeDeps-positive 0 rd
printf 'org.example:new:1.0=androidRuntimeClasspath\n' >> runtime.lockfile
expect RuntimeDeps-new-module 1 rd
rd_fixture "$rd_dir"
sed 's/core:1.0=/core:1.1=/' runtime.lockfile > l.tmp && mv l.tmp runtime.lockfile
expect RuntimeDeps-version-change 1 rd
rd_fixture "$rd_dir"
printf 'com.squareup.okhttp3:okhttp:4.12.0\tplanted\n' >> allow.txt
printf 'com.squareup.okhttp3:okhttp:4.12.0=androidRuntimeClasspath\n' >> runtime.lockfile
expect RuntimeDeps-network-library-even-listed 1 rd
rd_fixture "$rd_dir"
printf 'io.example:my-telemetry:1.0\tplanted\n' >> allow.txt
printf 'io.example:my-telemetry:1.0=androidRuntimeClasspath\n' >> runtime.lockfile
expect RuntimeDeps-telemetry-name 1 rd
rd_fixture "$rd_dir"
sed 's#<artifactId>core</artifactId><version>1.0</version>#<artifactId>core</artifactId><version>[1.0,2.0)</version>#' \
    repo/io/github/shishark/faceblur-android/1.0/faceblur-android-1.0.pom > p.tmp &&
    mv p.tmp repo/io/github/shishark/faceblur-android/1.0/faceblur-android-1.0.pom
expect RuntimeDeps-pom-range 1 rd
rd_fixture "$rd_dir"
sed 's/"module":"util"/"module":"undeclared"/' repo/io/github/shishark/faceblur-android/1.0/faceblur-android-1.0.module \
    > m.tmp && mv m.tmp repo/io/github/shishark/faceblur-android/1.0/faceblur-android-1.0.module
expect RuntimeDeps-module-metadata-unlisted 1 rd
rd_fixture "$rd_dir"
printf 'org.example:stale:1.0\tno longer used\n' >> allow.txt
expect RuntimeDeps-stale-row 1 rd
rd_fixture "$rd_dir"
printf 'org.example:other:9.9=jvmRuntimeClasspath\n' > runtime.lockfile
expect RuntimeDeps-no-android-entry 1 rd
rd_fixture "$rd_dir"
rm -rf repo/io
expect RuntimeDeps-no-publication 1 rd
rd_fixture "$rd_dir"
sed '1d' allow.txt > a.tmp && mv a.tmp allow.txt
expect RuntimeDeps-header-missing 2 rd
# The root faceblur publication is read too (a consumer that reads only the root POM sees its dependencies).
rd_root="repo/io/github/shishark/faceblur/1.0/faceblur-1.0"
rd_fixture "$rd_dir"
sed 's#</dependencies>#<dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>okhttp</artifactId><version>1.0</version></dependency></dependencies>#' \
    "$rd_root.pom" > p.tmp && mv p.tmp "$rd_root.pom"
expect_reason RuntimeDeps-root-pom-okhttp 1 "faceblur-1.0.pom: declares org.jetbrains.kotlin:okhttp (networking/telemetry" rd
rd_fixture "$rd_dir"
sed 's#</dependencies>#<dependency><groupId>org.example</groupId><artifactId>extra</artifactId><version>1.0</version></dependency></dependencies>#' \
    "$rd_root.pom" > p.tmp && mv p.tmp "$rd_root.pom"
expect_reason RuntimeDeps-root-pom-unlisted 1 "faceblur-1.0.pom: declares org.example:extra:1.0 which is not in the allow-list" rd
rd_fixture "$rd_dir"
sed 's/"module":"core"/"module":"undeclared"/' "$rd_root.module" > m.tmp && mv m.tmp "$rd_root.module"
expect_reason RuntimeDeps-root-module-unlisted 1 "faceblur-1.0.module metadataApiElements: declares org.example:undeclared" rd
rd_fixture "$rd_dir"
rm "$rd_root.pom"
expect_reason RuntimeDeps-root-pom-missing 1 "no faceblur POM and module metadata under the repository" rd
rd_fixture "$rd_dir"
rm -rf repo/io/github/shishark/faceblur
expect_reason RuntimeDeps-root-publication-missing 1 "no faceblur POM and module metadata under the repository" rd
cd "$here" || exit 1

# --- Write sites of the library (scripts/check-write-sites.py, PRIV-005) -----------------------------------------------
# A fixture repository with the writer files, a reviewed allow-list, its classification and one test; each defect is
# planted in a fresh copy.
ws_fixture() { # ws_fixture <dir>: a fresh copy, made the current directory
    cd "$work" || exit 1
    rm -rf "$1"
    a="$1/faceblur/src/androidMain/kotlin/io/github/shishark/faceblur/android"
    mkdir -p "$a" "$1/scripts/sast" "$1/faceblur/src/androidHostTest/kotlin/t"
    printf 'package x\ninternal object OsDescriptors : FileCalls<Int> {\n    fun r() = Os.open(path, flags, 0)\n    fun w() = Os.write(fd, b, 0, n)\n}\n' \
        > "$a/AndroidModelFileReader.kt"
    printf 'package x\nclass PathWriteRules {\n    fun p(calls: FileCalls<Int>) = calls.create(temp, flags, mode)\n}\n' > "$a/PathWriteRules.kt"
    printf 'package x\nclass Codec(private val calls: FileCalls<Int>) : Other {\n    fun s() = Os.stat(path)\n}\n' \
        > "$a/AndroidImageCodec.kt"
    printf 'package t\nclass ProofTest {\n    fun readOnly() {}\n}\n' > "$1/faceblur/src/androidHostTest/kotlin/t/ProofTest.kt"
    head -2 "$here/sast/write-sites.txt" > "$1/scripts/sast/write-sites.txt"
    printf '# reviewed\n' > "$1/scripts/sast/allowlist.txt"
    for spec in "AndroidModelFileReader.kt|Os.open(path, flags, 0)|read-open|test: t.ProofTest::readOnly" \
        "AndroidModelFileReader.kt|Os.write(fd, b, 0, n)|masked-output|write" \
        "AndroidImageCodec.kt|Os.stat(path)|stat|stat"; do
        f=$(echo "$spec" | cut -d'|' -f1); line=$(echo "$spec" | cut -d'|' -f2)
        kind=$(echo "$spec" | cut -d'|' -f3); note=$(echo "$spec" | cut -d'|' -f4)
        h=$(printf '%s' "fun $(echo "$line" | sed 's/^Os.open.*/r() = &/; s/^Os.write.*/w() = &/; s/^Os.stat.*/s() = &/')" | shasum -a 256 | cut -d' ' -f1)
        p="faceblur/src/androidMain/kotlin/io/github/shishark/faceblur/android/$f"
        printf 'faceblur-file-io-review\t%s\t%s\treview\n' "$p" "$h" >> "$1/scripts/sast/allowlist.txt"
        printf 'faceblur-file-io-review\t%s\t%s\t%s\t%s\n' "$p" "$h" "$kind" "$note" >> "$1/scripts/sast/write-sites.txt"
    done
    cd "$1" || exit 1
}
ws() { python3 -I "$here/check-write-sites.py" .; }
ws_dir="$work/write-sites"
ws_fixture "$ws_dir"
expect WriteSites-positive 0 ws
ws_codec="faceblur/src/androidMain/kotlin/io/github/shishark/faceblur/android/AndroidImageCodec.kt"
ws_reader="faceblur/src/androidMain/kotlin/io/github/shishark/faceblur/android/AndroidModelFileReader.kt"
# A new reviewed write line that is not classified.
printf '    fun cache() = Os.rename(a, b)\n' >> "$ws_reader"
printf 'faceblur-file-io-review\t%s\t%s\treview\n' "$ws_reader" \
    "$(printf '%s' 'fun cache() = Os.rename(a, b)' | shasum -a 256 | cut -d' ' -f1)" >> scripts/sast/allowlist.txt
expect WriteSites-unclassified-entry 1 ws
# The same line classified as a read.
printf 'faceblur-file-io-review\t%s\t%s\tread\tplanted\n' "$ws_reader" \
    "$(printf '%s' 'fun cache() = Os.rename(a, b)' | shasum -a 256 | cut -d' ' -f1)" >> scripts/sast/write-sites.txt
expect WriteSites-write-classified-as-read 1 ws
ws_fixture "$ws_dir"
# A write site in a file that is not a file-mode writer (a cache in the codec).
printf '    fun cache() = Os.write(fd, b, 0, n)\n' >> "$ws_codec"
h=$(printf '%s' 'fun cache() = Os.write(fd, b, 0, n)' | shasum -a 256 | cut -d' ' -f1)
printf 'faceblur-file-io-review\t%s\t%s\treview\n' "$ws_codec" "$h" >> scripts/sast/allowlist.txt
printf 'faceblur-file-io-review\t%s\t%s\tmasked-output\tplanted\n' "$ws_codec" "$h" >> scripts/sast/write-sites.txt
expect WriteSites-write-outside-writer-files 1 ws
ws_fixture "$ws_dir"
sed 's/ProofTest::readOnly/ProofTest::missing/' scripts/sast/write-sites.txt > w.tmp && mv w.tmp scripts/sast/write-sites.txt
expect WriteSites-read-open-without-test 1 ws
ws_fixture "$ws_dir"
sed 's/test: t.ProofTest::readOnly/flags look fine/' scripts/sast/write-sites.txt > w.tmp && mv w.tmp scripts/sast/write-sites.txt
expect WriteSites-read-open-no-test-named 1 ws
ws_fixture "$ws_dir"
sed 's/\tread-open\t/\tread\t/' scripts/sast/write-sites.txt > w.tmp && mv w.tmp scripts/sast/write-sites.txt
expect WriteSites-open-classified-as-plain-read 1 ws
ws_fixture "$ws_dir"
printf '    fun stray(calls: FileCalls<Int>) = calls.link(a, b)\n' >> "$ws_codec"
expect WriteSites-file-call-mutation-outside-path-rules 1 ws
ws_fixture "$ws_dir"
printf 'internal object CacheCalls : FileCalls<Int> {\n}\n' >> "$ws_codec"
expect WriteSites-second-file-calls-implementation 1 ws
ws_fixture "$ws_dir"
printf 'faceblur-file-io-review\t%s\t%s\tstat\tstale\n' "$ws_codec" "$(printf '%s' 'gone' | shasum -a 256 | cut -d' ' -f1)" \
    >> scripts/sast/write-sites.txt
expect WriteSites-stale-row 1 ws
ws_fixture "$ws_dir"
grep -v 'Os.stat' "$ws_codec" > c.tmp && mv c.tmp "$ws_codec"
expect WriteSites-line-gone 1 ws
# ws_add <file> <line> <kind>: (the loops below use ws_case, ws_name, ws_line: ws_fixture sets spec and f)
# a reviewed allow-list entry for <line> (appended to <file>) and its classification.
ws_add() {
    printf '    %s\n' "$2" >> "$1"
    h=$(printf '%s' "$2" | shasum -a 256 | cut -d' ' -f1)
    printf 'faceblur-file-io-review\t%s\t%s\treview\n' "$1" "$h" >> scripts/sast/allowlist.txt
    printf 'faceblur-file-io-review\t%s\t%s\t%s\tplanted\n' "$1" "$h" "$3" >> scripts/sast/write-sites.txt
}
ws_new="faceblur/src/androidMain/kotlin/io/github/shishark/faceblur/android/ProbeWrites.kt"
# A cache copy. The File line is classified path-name and the write is a Kotlin extension call.
ws_fixture "$ws_dir"
ws_add "$ws_codec" 'val f = File(context.cacheDir, "x.jpg")' path-name
printf '    fun c(b: ByteArray) = f.outputStream().use { it.write(b) }\n' >> "$ws_codec"
expect_reason WriteSites-cache-copy 1 "a classified line names an app storage or temporary directory" ws
# The storage directory on a code line that the SAST rule does not report (no reviewed entry) still fails.
for ws_case in "cacheDir|val d = context.cacheDir" "filesDir|val d = context.filesDir" \
    "noBackupFilesDir|val d = context.noBackupFilesDir" "getExternalFilesDir|val d = context.getExternalFilesDir(null)" \
    "externalCacheDir|val d = context.externalCacheDir" "tmpdir|val d = System.getProperty(\"java.io.tmpdir\")" \
    "createTempFile|val t = kotlin.io.path.createTempFile()"; do
    ws_name=$(echo "$ws_case" | cut -d'|' -f1); ws_line=$(echo "$ws_case" | cut -d'|' -f2)
    ws_fixture "$ws_dir"
    printf '    %s\n' "$ws_line" >> "$ws_codec"
    expect_reason "WriteSites-storage-name-$ws_name" 1 "names an app storage or temporary directory" ws
done
# createTempFile classified as the temporary file, even in a writer file: only FileCalls.create next to the target is.
ws_fixture "$ws_dir"
ws_add "faceblur/src/androidMain/kotlin/io/github/shishark/faceblur/android/PathWriteRules.kt" \
    'val t = File.createTempFile("x", ".jpg")' temp-file
expect_reason WriteSites-create-temp-file-as-temp-file 1 "a classified line names an app storage or temporary directory" ws
# Kotlin/Java write calls, descriptor opens with a write mode and a writable shared mapping classified as non-writing.
for ws_case in "outputStream|f.outputStream().use { it.write(b) }" "bufferedWriter|f.bufferedWriter().use { it.write(s) }" \
    "printWriter|f.printWriter().use { it.print(s) }" "writer|f.writer().use { it.write(s) }" \
    "copyTo|f.copyTo(g, overwrite = true)" "copyRecursively|f.copyRecursively(g)" \
    "openAssetFileDescriptor|resolver.openAssetFileDescriptor(uri, \"w\")" \
    "openTypedAssetFileDescriptor|resolver.openTypedAssetFileDescriptor(uri, \"rw\")" \
    "pfd-mode|ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE)" \
    "mmap|Os.mmap(0, n, OsConstants.PROT_WRITE, OsConstants.MAP_SHARED, fd, 0)"; do
    ws_name=$(echo "$ws_case" | cut -d'|' -f1); ws_line=$(echo "$ws_case" | cut -d'|' -f2)
    ws_fixture "$ws_dir"
    ws_add "$ws_codec" "$ws_line" read
    expect_reason "WriteSites-write-call-as-read-$ws_name" 1 "a line that can write is classified 'read'" ws
done
# An open or mapping whose mode comes from the callers, classified as a plain read.
for ws_case in "openAssetFileDescriptor|resolver.openAssetFileDescriptor(uri, mode)" "mmap|Os.mmap(0, n, prot, flags, fd, 0)"; do
    ws_name=$(echo "$ws_case" | cut -d'|' -f1); ws_line=$(echo "$ws_case" | cut -d'|' -f2)
    ws_fixture "$ws_dir"
    ws_add "$ws_codec" "$ws_line" read
    expect_reason "WriteSites-caller-mode-as-read-$ws_name" 1 "an open with flags from its callers must be read-open" ws
done
# Writes through the descriptor layer outside PathWriteRules' `calls`.
ws_fixture "$ws_dir"
printf 'package x\nfun p(b: ByteArray) {\n    val fd = OsDescriptors.create(p, O_WRONLY or O_CREAT, 384)\n}\n' > "$ws_new"
expect_reason WriteSites-os-descriptors-in-new-file 1 "names OsDescriptors outside the files that use it today" ws
ws_fixture "$ws_dir"
printf '    fun c(b: ByteArray) = OsDescriptors.write(fd, b, 0, b.size)\n' >> "$ws_codec"
expect_reason WriteSites-direct-os-descriptors-write 1 "a direct OsDescriptors mutation" ws
ws_fixture "$ws_dir"
printf '    fun c() = OsDescriptors.create(p, flags, 384)\n' >> "$ws_codec"
expect_reason WriteSites-direct-os-descriptors-create 1 "a direct OsDescriptors mutation" ws
ws_fixture "$ws_dir"
printf '    fun o() = calls.open(p, O_RDWR or O_CREAT)\n' >> "$ws_codec"
expect_reason WriteSites-open-with-write-flags 1 "a write open flag outside the path writers (PathWriteRules.kt, IosPathWriter.kt)" ws
ws_fixture "$ws_dir"
printf '    fun stray(files: FileCalls<Int>) = files.link(a, b)\n' >> "$ws_codec"
expect_reason WriteSites-file-calls-other-name 1 "a FileCalls value named 'files'" ws
ws_fixture "$ws_dir"
printf 'package x\nclass Copier(private val calls: FileCalls<Int>)\n' > "$ws_new"
expect_reason WriteSites-file-calls-in-new-file 1 "names FileCalls outside the files that use it today" ws
ws_fixture "$ws_dir"
printf 'package x\nfun r(d: DescriptorCalls<Int>) = d.open(p, 0)\n' > "$ws_new"
expect_reason WriteSites-descriptor-calls-in-new-file 1 "names DescriptorCalls outside the files that use it today" ws
# --- iOS path writer (rules 3, 5, 7 and 9 of scripts/check-write-sites.py; security review 055) ---------------------
ws_iw="faceblur/src/iosMain/kotlin/io/github/shishark/faceblur/ios/IosPathWriter.kt"
ws_ic="faceblur/src/iosMain/kotlin/io/github/shishark/faceblur/ios/IosImageCodec.kt"
ws_in="faceblur/src/iosMain/kotlin/io/github/shishark/faceblur/ios/ProbeWrites.kt"
ws_ios_fixture() { # the Android fixture plus an iOS writer (one classified clean-up call in IosPosixCalls) and a codec
    ws_fixture "$ws_dir"
    mkdir -p "$(dirname "$ws_iw")"
    printf 'package x\nimport platform.posix.O_CREAT\ninternal interface IosWriteCalls {\n    fun drop(path: String)\n}\n' > "$ws_iw"
    printf 'internal object IosPosixCalls : IosWriteCalls {\n    override fun drop(path: String) { platform.posix.unlink(path) }\n}\n' >> "$ws_iw"
    printf 'internal class IosPathWriter(private val calls: IosWriteCalls = IosPosixCalls) {\n    fun w(p: String) = calls.drop(p)\n}\n' >> "$ws_iw"
    printf 'package x\nfun s(p: String) = p.length\n' > "$ws_ic"
    h=$(printf '%s' 'override fun drop(path: String) { platform.posix.unlink(path) }' | shasum -a 256 | cut -d' ' -f1)
    printf 'faceblur-native-file-io-review\t%s\t%s\treview\n' "$ws_iw" "$h" >> scripts/sast/allowlist.txt
    printf 'faceblur-native-file-io-review\t%s\t%s\tcleanup\tremoval\n' "$ws_iw" "$h" >> scripts/sast/write-sites.txt
}
ws_ios_fixture
expect WriteSites-ios-positive 0 ws
# POSIX, Darwin and ImageIO writes classified as a read.
for ws_case in "write|val n = platform.posix.write(fd, p, c)" "link|platform.posix.link(a, b)" \
    "clonefile|platform.darwin.clonefile(a, b, 0u)" "bare-copyfile|copyfile(a, b, null, 0u)" \
    "image-destination-url|val d = CGImageDestinationCreateWithURL(url, t, 1u, null)"; do
    ws_name=$(echo "$ws_case" | cut -d'|' -f1); ws_line=$(echo "$ws_case" | cut -d'|' -f2)
    ws_ios_fixture
    ws_add "$ws_ic" "$ws_line" read
    expect_reason "WriteSites-ios-write-as-read-$ws_name" 1 "a line that can write is classified 'read'" ws
done
ws_ios_fixture
ws_add "$ws_ic" 'platform.posix.rename(a, b)' masked-output
expect_reason WriteSites-ios-write-in-codec 1 "a masked-output site outside the file-mode writer files" ws
ws_ios_fixture
printf 'import platform.posix.unlink\n' >> "$ws_ic"
expect_reason WriteSites-ios-import-mutation 1 "imports a POSIX file mutation by name" ws
ws_ios_fixture
printf 'fun stray(p: String) = platform.posix.unlink(p)\n' >> "$ws_iw"
expect_reason WriteSites-ios-posix-outside-posix-calls 1 "a POSIX file mutation outside the IosPosixCalls object" ws
ws_ios_fixture
printf 'fun stray(p: String) = platform.posix.unlink(p)\n' >> "$ws_ic"
expect_reason WriteSites-ios-posix-in-codec 1 "a POSIX file mutation outside the IosPosixCalls object" ws
ws_ios_fixture
printf 'fun stray(calls: Any, p: String) = calls.drop(p)\n' >> "$ws_ic"
expect_reason WriteSites-ios-writer-call-outside 1 "a writer-call mutation outside IosPathWriter.kt" ws
ws_ios_fixture
printf 'internal object CopyCalls : IosWriteCalls {\n}\n' >> "$ws_iw"
expect_reason WriteSites-ios-second-implementation 1 "CopyCalls implements IosWriteCalls" ws
ws_ios_fixture
printf 'package x\nclass Copier(private val calls: IosWriteCalls)\n' > "$ws_in"
expect_reason WriteSites-ios-seam-in-new-file 1 "names IosWriteCalls outside IosPathWriter.kt" ws
ws_ios_fixture
printf 'fun stray(files: IosWriteCalls, p: String) = files.drop(p)\n' >> "$ws_iw"
expect_reason WriteSites-ios-seam-other-name 1 "an IosWriteCalls value named 'files'" ws
ws_ios_fixture
printf 'fun stray(p: String) = IosPosixCalls.drop(p)\n' >> "$ws_iw"
expect_reason WriteSites-ios-direct-posix-calls 1 "a direct IosPosixCalls mutation" ws
ws_ios_fixture
printf 'fun o(p: String) = platform.posix.open(p, O_WRONLY or O_CREAT, 384)\n' >> "$ws_ic"
expect_reason WriteSites-ios-write-flag-in-codec 1 "a write open flag outside the path writers" ws
for ws_case in "NSTemporaryDirectory|val d = NSTemporaryDirectory()" \
    "NSSearchPath|val d = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)" \
    "TMPDIR|val d = getenv(\"TMPDIR\")" "confstr|confstr(_CS_DARWIN_USER_CACHE_DIR, b, n)" \
    "temporaryDirectory|val d = fm.temporaryDirectory"; do
    ws_name=$(echo "$ws_case" | cut -d'|' -f1); ws_line=$(echo "$ws_case" | cut -d'|' -f2)
    ws_ios_fixture
    printf '    %s\n' "$ws_line" >> "$ws_ic"
    expect_reason "WriteSites-ios-storage-name-$ws_name" 1 "names an app storage or temporary directory" ws
done
cd "$here" || exit 1

# --- OSV gate on runtime classpaths --------------------------------------------------------------------------------
if [ -n "${OSV_SCANNER:-}" ]; then
    new_repo
    printf 'org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0=androidRuntimeClasspath\n' > clean.lockfile
    expect OSV-runtime-positive 0 sh "$here/osv-runtime-gate.sh" clean.lockfile
    cp clean.lockfile bad.lockfile
    printf 'com.google.guava:guava:33.4.0-jre=androidRuntimeClasspath\n' >> bad.lockfile
    expect OSV-runtime-planted-advisory 1 sh "$here/osv-runtime-gate.sh" bad.lockfile
    : > empty.lockfile
    expect OSV-runtime-empty-lockfile 2 sh "$here/osv-runtime-gate.sh" empty.lockfile
else
    fail=$((fail + 1))
    echo "test-gates: OSV NOT RUN - set OSV_SCANNER to the osv-scanner binary"
fi

echo "test-gates: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
