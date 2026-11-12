#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Gradle dependency verification must stay on:
#   1. every Gradle build in the repository (each tracked settings.gradle.kts) has gradle/verification-metadata.xml
#      next to it with <verify-metadata>true</verify-metadata>;
#   2. no tracked build, workflow, hook or script file switches verification to lenient/off: the Gradle property
#      org.gradle.dependency.verification or the command-line option for it must not appear (this file excepted).
# Usage: scripts/check-dependency-verification.sh     Exit codes: 0 ok, 1 violation (file named), 2 git error.
set -eu

# The forbidden tokens are assembled so that this file does not trip its own check when other tools grep for them.
prop="dependency""\\.verification"
opt="--dependency""-verification"
fail=0

settings=$(git -c core.quotePath=false ls-files -- 'settings.gradle.kts' '*/settings.gradle.kts' 'settings.gradle' '*/settings.gradle') || exit 2
[ -n "$settings" ] || { echo "check-dependency-verification: ERROR - no Gradle settings file tracked" >&2; exit 2; }
for s in $settings; do
    dir=$(dirname "$s")
    meta="$dir/gradle/verification-metadata.xml"
    [ "$dir" = "." ] && meta="gradle/verification-metadata.xml"
    if ! git ls-files --error-unmatch "$meta" >/dev/null 2>&1; then
        echo "check-dependency-verification: build '$dir' has no tracked $meta" >&2
        fail=1
    elif ! grep -q '<verify-metadata>true</verify-metadata>' "$meta"; then
        echo "check-dependency-verification: $meta does not set <verify-metadata>true</verify-metadata>" >&2
        fail=1
    fi
done

files=$(git -c core.quotePath=false ls-files -- '*gradle.properties' '*.gradle.kts' '*.gradle' '.github/*' '.githooks/*' 'scripts/*' \
    'gradlew' 'gradlew.bat' '*/gradlew') || exit 2
for f in $files; do
    [ "$f" = "scripts/check-dependency-verification.sh" ] && continue
    [ -f "$f" ] || continue
    if grep -n -E "$prop|$opt" "$f" > /dev/null 2>&1; then
        lines=$(grep -n -E "$prop|$opt" "$f" | cut -d: -f1 | tr '\n' ' ')
        echo "check-dependency-verification: $f line(s) $lines change the dependency verification mode" >&2
        fail=1
    fi
done

if [ "$fail" -ne 0 ]; then
    echo "check-dependency-verification: FAIL" >&2
    exit 1
fi
echo "check-dependency-verification: PASS - every Gradle build verifies dependencies; no switch found"
