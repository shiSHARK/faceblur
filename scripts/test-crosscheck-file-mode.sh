#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Controls for scripts/crosscheck-file-mode.sh with the real checker and copies of the real test outputs. Each
# negative control must fail the step; the real outputs must pass:
#   X1 a bad output (the planted metadata file) under a must-pass name
#   X2 no manifest                       X3 an empty manifest
#   X4a an extra file in upper case      X4b an extra .jpeg file      X4c a file in a subdirectory
#   X5 strip-all.jpg removed (listed in the manifest, not written)
#   X6 a 1-byte garbage file as planted-trailing.jpg (rejected, but not with its expected codes)
#   X7 planted-trailing.jpg replaced by the other planted file (rejected with the wrong codes)
#   X9 strip-all.jpg and its manifest line both removed (a test that stops writing an output)
#   X10 the manifest loosens the policy of strip-all.jpg (allow-list instead of strip-all)
#   X8 the real outputs (positive control)
# Usage: scripts/test-crosscheck-file-mode.sh <checker launcher> [outputs dir]
# Exit codes: 0 every control behaved as expected, 1 otherwise, 2 usage error.
set -u

checker=${1:-}
dir=${2:-faceblur/build/file-mode-crosscheck}
here=$(cd "$(dirname "$0")" && pwd)
if [ ! -x "$checker" ] || [ ! -f "$dir/manifest.tsv" ] || [ ! -f "$dir/strip-all.jpg" ] \
    || [ ! -f "$dir/planted-trailing.jpg" ] || [ ! -f "$dir/planted-unprocessed.jpg" ]; then
    echo "test-crosscheck-file-mode: usage/inputs missing" >&2
    exit 2
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
fail=0
n=0

copy() { # copy <control dir>: a fresh copy of the real outputs
    rm -rf "${work:?}/$1"
    mkdir -p "$work/$1"
    cp -R "$dir"/. "$work/$1/"
    printf '%s' "$work/$1"
}

control() { # control <id> <expected exit> <dir> [<text the failure must contain>]
    sh "$here/crosscheck-file-mode.sh" "$checker" "$3" > "$work/out.txt" 2>&1
    got=$?
    n=$((n + 1))
    if [ "$got" -eq "$2" ] && { [ -z "${4:-}" ] || grep -qF -- "$4" "$work/out.txt"; }; then
        echo "test-crosscheck-file-mode: $1 PASS (exit $got): $(grep -m1 -F -- "${4:-crosscheck-file-mode: PASS}" "$work/out.txt")"
    else
        echo "test-crosscheck-file-mode: $1 FAIL (exit $got, expected $2${4:+ with \"$4\"}):"
        sed 's/^/    /' "$work/out.txt" | tail -6
        fail=1
    fi
}

d=$(copy x1); cp "$dir/planted-unprocessed.jpg" "$d/strip-all.jpg"
control X1-bad-output-under-pass-name 1 "$d" "strip-all.jpg (strip-all): expected pass"
d=$(copy x2); rm "$d/manifest.tsv"
control X2-no-manifest 1 "$d" "no manifest.tsv"
d=$(copy x3); : > "$d/manifest.tsv"
control X3-empty-manifest 1 "$d" "the manifest has no entries"
d=$(copy x4a); cp "$dir/strip-all.jpg" "$d/EXTRA.JPG"
control X4a-extra-upper-case-file 1 "$d" "EXTRA.JPG: not in the manifest"
d=$(copy x4b); cp "$dir/strip-all.jpg" "$d/extra.jpeg"
control X4b-extra-jpeg-extension 1 "$d" "extra.jpeg: not in the manifest"
d=$(copy x4c); mkdir -p "$d/sub"; cp "$dir/strip-all.jpg" "$d/sub/strip-all.jpg"
control X4c-file-in-subdirectory 1 "$d" "sub: not a regular file"
d=$(copy x5); rm "$d/strip-all.jpg"
control X5-strip-all-removed 1 "$d" "strip-all.jpg: listed in the manifest but not written"
d=$(copy x6); printf 'x' > "$d/planted-trailing.jpg"
control X6-one-byte-garbage-planted 1 "$d" "planted-trailing.jpg: rejected with codes"
d=$(copy x7); cp "$dir/planted-unprocessed.jpg" "$d/planted-trailing.jpg"
control X7-planted-wrong-codes 1 "$d" "planted-trailing.jpg: rejected with codes"
d=$(copy x9); rm "$d/strip-all.jpg"; grep -v '^strip-all.jpg' "$dir/manifest.tsv" > "$d/manifest.tsv"
control X9-output-and-manifest-line-removed 1 "$d" "strip-all.jpg: expected line missing from the test manifest"
d=$(copy x10); sed 's/^strip-all.jpg\tstrip-all\t/strip-all.jpg\tallowlist:gps,datetime,camera\t/' "$dir/manifest.tsv" > "$d/manifest.tsv"
control X10-loosened-policy 1 "$d" "strip-all.jpg: test manifest line not in the expected list"
control X8-real-outputs 0 "$dir" "crosscheck-file-mode: PASS"

[ "$fail" -eq 0 ] || { echo "test-crosscheck-file-mode: FAIL"; exit 1; }
echo "test-crosscheck-file-mode: PASS - $((n - 1)) negative controls failed the step with their reasons, the real outputs passed"
