#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# SAST gate (ST-009 AC9/AC10, threat model: static analysis): opengrep with the project rules in scripts/sast/rules.yml.
# - Inline suppressions are disabled (--disable-nosem).
# - The scan must cover the source files: if the roots contain library sources (faceblur/src/*Main/**/*.kt or
#   Sources/**/*.swift) and opengrep reports fewer scanned files than that, the gate fails (no silent path mismatch).
# - Findings of the "review" rules (logging, process/dynamic code, file I/O) pass only with an entry in
#   scripts/sast/allowlist.txt that names rule, path and the SHA-256 of the exact (trimmed) line. Network rules
#   never pass.
#
# Usage: scripts/run-sast.sh [--print-hashes] [root...]   (default root: faceblur/src)
#   OPENGREP=<path to opengrep binary> (default: opengrep on PATH). CI installs a pinned, checksum-verified build.
#   --print-hashes: also print the allow-list key of every finding (for preparing a review), still exits 1.
# Writes build/reports/sast/opengrep.json. Exit codes: 0 clean, 1 findings, 2 tool/usage error.
set -eu

print_hashes=0
if [ "${1:-}" = "--print-hashes" ]; then
    print_hashes=1
    shift
fi
[ "$#" -gt 0 ] || set -- faceblur/src
bin=${OPENGREP:-opengrep}
out=build/reports/sast
mkdir -p "$out"

for root in "$@"; do
    [ -e "$root" ] || { echo "run-sast: ERROR - scan root not found: $root" >&2; exit 2; }
done
expected=$(find "$@" -type f \( -path '*/faceblur/src/*Main/*.kt' -o -path 'faceblur/src/*Main/*.kt' \
    -o -path '*Sources/*.swift' \) | wc -l | tr -d ' ')

status=0
"$bin" scan --config scripts/sast/rules.yml --disable-nosem --json --quiet --output "$out/opengrep.json" "$@" \
    || status=$?
# opengrep exits 0 whether or not findings exist (no --error); anything else is a tool error.
if [ "$status" -ne 0 ]; then
    echo "run-sast: ERROR - opengrep exited with $status" >&2
    exit 2
fi
exec python3 -I - "$out/opengrep.json" scripts/sast/allowlist.txt "$expected" "$print_hashes" <<'PY'
import hashlib, json, sys
report, allow_file, expected, print_hashes = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4] == "1"
REVIEW_RULES = ("faceblur-no-logging", "faceblur-no-process-or-dynamic-code", "faceblur-file-io-review",
                "faceblur-native-file-io-review", "faceblur-swift-no-logging",
                "faceblur-swift-process-or-dynamic-code", "faceblur-swift-file-io-review")
with open(report, encoding="utf-8") as fh:
    data = json.load(fh)
allowed = set()
with open(allow_file, encoding="utf-8") as fh:
    for n, line in enumerate(fh, 1):
        line = line.rstrip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 4 or len(parts[2]) != 64 or not parts[3].strip():
            print(f"run-sast: ERROR - allowlist.txt line {n}: expected rule, path, sha256, review reference",
                  file=sys.stderr)
            sys.exit(2)
        if parts[0] not in REVIEW_RULES:
            print(f"run-sast: ERROR - allowlist.txt line {n}: rule {parts[0]} cannot have exceptions", file=sys.stderr)
            sys.exit(2)
        allowed.add((parts[0], parts[1], parts[2]))
if data.get("errors"):
    for e in data["errors"]:
        print(f"run-sast: ERROR - opengrep: {e.get('type')} {str(e.get('message', ''))[:200]}", file=sys.stderr)
    sys.exit(2)
scanned = len(data.get("paths", {}).get("scanned", []))
if scanned < expected:
    print(f"run-sast: ERROR - opengrep scanned {scanned} files but the roots contain {expected} source files "
          "(include paths do not match)", file=sys.stderr)
    sys.exit(2)
cache = {}
findings = 0
for r in data.get("results", []):
    rule = r["check_id"].rsplit(".", 1)[-1]
    path = r["path"]
    nr = r["start"]["line"]
    if path not in cache:
        with open(path, encoding="utf-8", errors="replace") as fh:
            cache[path] = fh.read().splitlines()
    text = cache[path][nr - 1].strip() if nr - 1 < len(cache[path]) else ""
    key = hashlib.sha256(text.encode("utf-8")).hexdigest()
    if rule in REVIEW_RULES and (rule, path, key) in allowed:
        print(f"run-sast: allowed by review: {rule} {path}:{nr}")
        continue
    findings += 1
    msg = " ".join(r["extra"]["message"].split())
    print(f"run-sast: {rule} {path}:{nr} - {msg}", file=sys.stderr)
    if print_hashes:
        print(f"run-sast: allow-list key: {rule}\t{path}\t{key}\t<review reference>", file=sys.stderr)
if findings:
    print(f"run-sast: FAIL - {findings} finding(s) ({scanned} files scanned)", file=sys.stderr)
    sys.exit(1)
print(f"run-sast: PASS - 0 findings ({scanned} files scanned, {expected} library source files)")
PY
