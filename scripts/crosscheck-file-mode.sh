#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Runs the independent output privacy checker (verification/jpeg-privacy, ST-042) on the JPEG outputs that the
# library's JVM file-mode tests write with the real JDK encoder pipeline. The checker is never linked into the
# library; it runs as its own program.
#
# The tests write the outputs and manifest.tsv into the output directory (the Gradle test task empties it first).
# Manifest line: <file name><TAB><checker policy><TAB>pass|fail<TAB><sorted finding codes, comma-separated, or ->
# The step FAILS when
#   - the manifest differs in any line from the committed expected list scripts/file-mode-crosscheck-expected.txt
#     (same columns; compared after sorting): a test that stops writing an output, or a looser policy, fails;
#   - the manifest is missing or has no entry, or an entry is malformed;
#   - a manifest entry has no file, or any other entry exists in the directory (any name, extension, letter case,
#     subdirectory or link): the set of outputs is exact;
#   - an output marked pass is rejected by the checker;
#   - an output marked fail is accepted, or rejected with codes other than exactly the expected ones (so NOT_JPEG,
#     IO_ERROR or a different defect never count as the planted detection);
#   - no output is marked pass, or none is marked fail.
#
# Usage: scripts/crosscheck-file-mode.sh <checker launcher> [outputs dir] [expected list]
#   checker launcher: verification/jpeg-privacy/build/install/jpeg-privacy/bin/jpeg-privacy
#                     (`./gradlew -p verification :jpeg-privacy:installDist`)
#   outputs dir: the directory the Gradle jvmTest task defines (default below; the build file is authoritative)
# Exit codes: 0 all as expected, 1 a mismatch, 2 usage error. Prints the synthetic test-output names only.
set -u

checker=${1:-}
dir=${2:-faceblur/build/file-mode-crosscheck}
expected=${3:-$(dirname "$0")/file-mode-crosscheck-expected.txt}
[ -f "$expected" ] || { echo "crosscheck-file-mode: ERROR - expected list not found: $expected" >&2; exit 2; }
[ -x "$checker" ] || { echo "crosscheck-file-mode: ERROR - checker launcher not executable: $checker" >&2; exit 2; }
[ -d "$dir" ] || { echo "crosscheck-file-mode: FAIL - no output directory $dir (did the JVM tests run?)" >&2; exit 1; }
manifest="$dir/manifest.tsv"
[ -f "$manifest" ] || { echo "crosscheck-file-mode: FAIL - no manifest.tsv in the output directory" >&2; exit 1; }

problems=0
problem() {
    echo "crosscheck-file-mode: FAIL - $1" >&2
    problems=$((problems + 1))
}
tab=$(printf '\t')

# 0. The manifest must equal the committed expected list.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
grep -v '^#' "$expected" | sed '/^$/d' | LC_ALL=C sort > "$work/want"
sed '/^$/d' "$manifest" | LC_ALL=C sort > "$work/have"
LC_ALL=C comm -23 "$work/want" "$work/have" > "$work/missing"
LC_ALL=C comm -13 "$work/want" "$work/have" > "$work/extra"
while IFS="$tab" read -r name rest; do
    problem "$name: expected line missing from the test manifest or changed (expected list: $rest)"
done < "$work/missing"
while IFS="$tab" read -r name rest; do
    problem "$name: test manifest line not in the expected list ($rest)"
done < "$work/extra"

# 1. Exact set: every directory entry (recursive, any type) other than the manifest must be a regular file named in
#    the manifest at the top level.
entries=$(cd "$dir" && find . -mindepth 1 ! -name manifest.tsv | sed 's#^\./##' | sort)
listed=$(cut -f1 "$manifest" | sort)
for e in $entries; do
    if [ -L "$dir/$e" ] || [ ! -f "$dir/$e" ]; then
        problem "$e: not a regular file"
    elif ! printf '%s\n' "$listed" | grep -qxF -- "$e"; then
        problem "$e: not in the manifest"
    fi
done

# 2. Every manifest entry: file present, checker verdict and codes as expected.
pass_count=0
fail_count=0
entries_count=0
while IFS="$tab" read -r name policy verdict codes extra; do
    [ -n "$name" ] || continue
    entries_count=$((entries_count + 1))
    if [ -z "$policy" ] || [ -z "$codes" ] || [ -n "${extra:-}" ]; then
        problem "manifest line for $name is malformed"
        continue
    fi
    case "$name" in */* | .*) problem "$name: manifest names must be plain file names"; continue ;; esac
    if [ ! -f "$dir/$name" ] || [ -L "$dir/$name" ]; then
        problem "$name: listed in the manifest but not written"
        continue
    fi
    result=$("$checker" "--policy=$policy" "$dir/$name" 2>&1)
    status=$?
    case "$verdict:$status" in
        pass:0)
            pass_count=$((pass_count + 1))
            echo "crosscheck-file-mode: $name ($policy) accepted: $result"
            ;;
        fail:1)
            got=$(printf '%s\n' "$result" | sed -n 's/.*codes=\([A-Z_,]*\).*/\1/p' | tr ',' '\n' | sort | paste -sd, -)
            if [ "$got" = "$codes" ]; then
                fail_count=$((fail_count + 1))
                echo "crosscheck-file-mode: $name ($policy) rejected with exactly the expected codes: $codes"
            else
                problem "$name: rejected with codes '$got', expected exactly '$codes' ($result)"
            fi
            ;;
        *)
            problem "$name ($policy): expected $verdict, checker exit $status: $result"
            ;;
    esac
done < "$manifest"

[ "$entries_count" -gt 0 ] || problem "the manifest has no entries"
[ "$pass_count" -gt 0 ] || problem "zero library outputs were accepted"
[ "$fail_count" -gt 0 ] || problem "no planted output was rejected with its expected codes (negative control missing)"
if [ "$problems" -gt 0 ]; then
    echo "crosscheck-file-mode: FAIL - $problems problem(s); $pass_count outputs accepted, $fail_count planted outputs rejected as expected" >&2
    exit 1
fi
echo "crosscheck-file-mode: PASS - $pass_count library outputs accepted by the independent checker, $fail_count planted outputs rejected with their expected codes"
