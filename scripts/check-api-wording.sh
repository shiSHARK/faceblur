#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# DX-009 / ST-009 AC4: no public type, member or package in the ABI dumps contains "anonymi[sz]" (the library
# masks faces; it does not anonymise). Case-insensitive.
#
# Usage: scripts/check-api-wording.sh [dump...]   (default: every *.api file under faceblur/api/)
# Exit codes: 0 clean, 1 forbidden word found (file and line number named), 2 usage/IO error.
set -eu

if [ "$#" -gt 0 ]; then
    files=$*
else
    files=$(find faceblur/api -type f -name '*.api' | sort)
fi
[ -n "$files" ] || { echo "check-api-wording: no ABI dump found" >&2; exit 2; }

hits=0
for f in $files; do
    [ -r "$f" ] || { echo "check-api-wording: cannot read $f" >&2; exit 2; }
    lines=$(grep -n -i -E 'anonymi[sz]' "$f" | cut -d: -f1 | tr '\n' ' ' || true)
    for line in $lines; do
        echo "check-api-wording: forbidden word 'anonymi[sz]e' in $f line $line" >&2
        hits=$((hits + 1))
    done
done
if [ "$hits" -gt 0 ]; then
    echo "check-api-wording: FAIL - $hits line(s) in the public ABI contain 'anonymi[sz]e' (DX-009)" >&2
    exit 1
fi
echo "check-api-wording: PASS - no 'anonymi[sz]e' in the public ABI dumps"
