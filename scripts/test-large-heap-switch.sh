#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Controls for the own-heap test tasks of faceblur/build.gradle.kts (jvmLargeHeapTest, jvmRealHeapTest):
#   S1  under CI the local skip switch -Pfaceblur.largeHeapTests=false is refused (configuration fails)
#   S2  the same switch given as the system property -Dorg.gradle.project.faceblur.largeHeapTests is refused too
#   S3  under CI, jvmTest is finalized by both tasks (they are in the task graph of `jvmTest`)
#   S4  without CI, the switch still works locally (neither task in the graph)
# Uses --dry-run, so no test runs; each control only configures the build (a few seconds with a warm daemon).
# Every form of a Gradle project property reaches the build through the same provider, so the guard covers them all.
# The environment form ORG_GRADLE_PROJECT_faceblur.largeHeapTests is not used here: a name with a dot is dropped by
# dash (/bin/sh on Ubuntu runners) when it starts gradlew, so on Linux it never reaches Gradle (the tasks then run).
# The second line of defence, scripts/check-test-results.py over both tasks in CI, catches a run that skipped them
# in any other way (for example with -x).
#
# Usage: scripts/test-large-heap-switch.sh   (from the repository root)
# Exit codes: 0 every control behaved as expected, 1 otherwise.
set -u

out=$(mktemp)
trap 'rm -f "$out"' EXIT INT TERM
fail=0
pass=0
switch=faceblur.largeHeapTests

result() { # result <id> <ok 0|1> <detail>
    if [ "$2" -eq 0 ]; then
        pass=$((pass + 1))
        echo "test-large-heap-switch: $1 PASS ($3)"
    else
        fail=$((fail + 1))
        echo "test-large-heap-switch: $1 FAIL ($3):"
        sed 's/^/    /' "$out" | tail -15
    fi
}

# refused <id> <env assignment...> -- <gradle args...>: the build must fail with the switch message.
refused() {
    id=$1
    shift
    env "$@" > "$out" 2>&1
    got=$?
    if [ "$got" -ne 0 ] && grep -q "is a local-only switch" "$out"; then
        result "$id" 0 "configuration refused, exit $got"
    else
        result "$id" 1 "exit $got, refusal message missing"
    fi
}

# graph <id> <expected count of the two tasks> <env arguments...> [-- <gradle arguments...>]: dry run of jvmTest,
# counts the own-heap tasks in the task graph.
graph() {
    id=$1
    want=$2
    shift 2
    envargs=""
    while [ "$#" -gt 0 ] && [ "$1" != "--" ]; do
        envargs="$envargs $1"
        shift
    done
    [ "$#" -gt 0 ] && shift
    # shellcheck disable=SC2086 # envargs holds fixed words without spaces
    env $envargs ./gradlew --dry-run "$@" :faceblur:jvmTest > "$out" 2>&1
    got=$?
    n=$(grep -c -E '^:faceblur:(jvmLargeHeapTest|jvmRealHeapTest) SKIPPED$' "$out")
    if [ "$got" -eq 0 ] && [ "$n" -eq "$want" ] && grep -q '^:faceblur:jvmTest SKIPPED$' "$out"; then
        result "$id" 0 "$n of 2 own-heap tasks in the graph"
    else
        result "$id" 1 "exit $got, $n of 2 own-heap tasks in the graph, expected $want"
    fi
}

refused S1-switch-refused-under-CI CI=true ./gradlew --dry-run "-P$switch=false" :faceblur:jvmTest
refused S2-system-property-refused-under-CI CI=true ./gradlew --dry-run "-Dorg.gradle.project.$switch=false" \
    :faceblur:jvmTest
graph S3-CI-runs-both-tasks 2 CI=true
graph S4-local-switch-skips-both 0 -u CI -- "-P$switch=false"

echo "test-large-heap-switch: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
