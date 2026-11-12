#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Guide snippets as an Android consumer compiles them. Builds verification/android-snippets (a standalone Gradle build;
# the library comes only from the local publication build/local-maven) twice, release, not minified: with the guide's
# AndroidManifest-no-network.xml as the app manifest, and with a plain manifest (CONTROL). The sources of the app are
# the files of docs/guides/snippets/android/ (Kotlin and Java); scripts/check-guide-snippets.py then checks that every
# file was compiled, that the no-network manifest leaves neither INTERNET nor ACCESS_NETWORK_STATE in the merged
# manifest and in `aapt2 dump permissions` of the APK (and that the control keeps both), and that the cancellation
# tests of the Java example (Android host tests, :faceblur:testAndroidHostTest) ran and passed.
# Run after `./gradlew publishAllPublicationsToLocalRepoRepository` and the Android host tests.
# Usage: scripts/check-guide-snippets.sh        Environment: ANDROID_HOME / ANDROID_SDK_ROOT (or local.properties)
# Exit codes: 0 pass, 1 a check failed, 2 usage/environment error.
set -u

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
cd "$root" || exit 2
consumer=verification/android-snippets
snippets=docs/guides/snippets/android
say() { echo "check-guide-snippets: $*"; }

[ -f "$consumer/settings.gradle.kts" ] || { say "ERROR - $consumer does not exist" >&2; exit 2; }
[ -d "$snippets" ] || { say "ERROR - $snippets does not exist" >&2; exit 2; }
version=$(sed -n 's/^VERSION_NAME=//p' gradle.properties | head -1)
[ -n "$version" ] || { say "ERROR - no VERSION_NAME in gradle.properties" >&2; exit 2; }
ls "$root"/build/local-maven/io/github/shishark/faceblur/"$version"/faceblur-*.pom > /dev/null 2>&1 ||
    { say "ERROR - no faceblur $version publication under build/local-maven (publish first)" >&2; exit 2; }
tests=faceblur/build/test-results/testAndroidHostTest/TEST-io.github.shishark.faceblur.android.JavaAsyncCancellationTest.xml
[ -f "$tests" ] || { say "FAIL - no result file of the Java example's cancellation test ($tests): the Android host tests did not run it" >&2; exit 1; }
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)}}
aapt2=$(ls -d "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
if [ -z "$aapt2" ] || [ ! -x "$aapt2" ]; then say "ERROR - no aapt2 in the Android SDK build-tools" >&2; exit 2; fi
say "aapt2 $("$aapt2" version 2>&1 | head -1) from build-tools $(basename "$(dirname "$aapt2")")"

merged=$consumer/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml
apk=$consumer/app/build/outputs/apk/release/app-release-unsigned.apk
work=$(mktemp -d)
# The build's own Gradle reports (configuration cache) hold absolute paths and are never uploaded; they are removed so
# the report path checks of the verification job stay meaningful.
trap 'rm -rf "$work" "$consumer/build/reports"' EXIT
trap 'exit 130' INT TERM

rm -rf "$consumer/app/build"
for choice in snippet plain; do
    rm -f "$merged" "$apk"
    if ! ./gradlew -p "$consumer" --console=plain -q -Pmanifest="$choice" :app:assembleRelease > "$work/$choice-build.txt" 2>&1 ||
        [ ! -f "$merged" ] || [ ! -f "$apk" ]; then
        say "FAIL - the snippet build ($choice manifest) failed or produced no manifest/APK:"
        tail -30 "$work/$choice-build.txt"
        exit 1
    fi
    cp "$merged" "$work/$choice.xml"
    if ! "$aapt2" dump permissions "$apk" > "$work/$choice.permissions" 2>&1; then
        say "ERROR - aapt2 could not read the $choice APK" >&2
        exit 2
    fi
done

python3 -I scripts/check-guide-snippets.py --snippets "$snippets" --classes "$consumer/app/build" \
    --manifest-file AndroidManifest-no-network.xml --merged "$work/snippet.xml" \
    --permissions "$work/snippet.permissions" --control-merged "$work/plain.xml" \
    --control-permissions "$work/plain.permissions" --test-results "$tests"
