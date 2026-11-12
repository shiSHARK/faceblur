#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# OSV gate on what reaches users: fails on ANY published advisory in
#   - the library's resolved runtime classpaths (faceblur/build/osv/runtime.lockfile from
#     `./gradlew :faceblur:writeRuntimeLockfile`; Android, JVM and iOS klib dependencies), and
#   - the POMs of the published variants in a local Maven repository (if given).
# Build-only tooling is reported separately (gates job), not here.
# Usage: scripts/osv-runtime-gate.sh <runtime.lockfile> [local maven repo]
#   OSV_SCANNER=<osv-scanner binary> (default: osv-scanner on PATH)
# Exit codes: 0 no advisories, 1 advisories found, 2 usage or scanner error.
set -u
bin=${OSV_SCANNER:-osv-scanner}
lock=${1:-}
repo=${2:-}
[ -s "$lock" ] || { echo "osv-runtime-gate: ERROR - runtime lockfile missing or empty" >&2; exit 2; }
grep -q '=' "$lock" || { echo "osv-runtime-gate: ERROR - lockfile has no dependency entries" >&2; exit 2; }

worst=0
scan() { # scan <osv -L argument> <label>
    "$bin" scan source -L "$1"
    status=$?
    case "$status" in
        0) echo "osv-runtime-gate: $2: no advisories" ;;
        1) echo "osv-runtime-gate: $2: ADVISORIES FOUND" >&2; [ "$worst" -lt 1 ] && worst=1 ;;
        *) echo "osv-runtime-gate: ERROR - osv-scanner exit $status on $2" >&2; worst=2 ;;
    esac
}
scan "gradle.lockfile:$lock" "runtime classpaths"
if [ -n "$repo" ]; then
    found=0
    for pom in "$repo"/io/github/shishark/*/*/*.pom; do
        [ -f "$pom" ] || continue
        found=1
        scan "pom.xml:$pom" "published $(basename "$pom")"
    done
    [ "$found" -eq 1 ] || { echo "osv-runtime-gate: ERROR - no published POM under $repo" >&2; worst=2; }
fi
case "$worst" in
    0) echo "osv-runtime-gate: PASS" ;;
    1) echo "osv-runtime-gate: FAIL - a runtime or published dependency has a known advisory" >&2 ;;
esac
exit "$worst"
