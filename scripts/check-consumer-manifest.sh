#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Merged-manifest gate (ADR 0007, amended): what an app really gets when it depends on the published library.
# Builds the minimal consumer app verification/android-consumer (a standalone Gradle build; the library comes only
# from the local publication build/local-maven) twice, release, not minified:
#   C1       the app + faceblur-android + an androidx.startup initializer (lifecycle-process). Must hold, in the merged
#            manifest XML AND in the APK's binary manifest (aapt2 dump xmltree / dump permissions): ONNX Runtime's
#            telemetry starter absent, androidx.startup's InitializationProvider with ProcessLifecycleInitializer
#            present, permissions exactly INTERNET + ACCESS_NETWORK_STATE when faceblur-android depends on
#            onnxruntime-android (the library leaves them to the app) and none otherwise.
#   C0       CONTROL: the same app with onnxruntime-android but without FaceBlur. The C1 expectations must FAIL on it
#            (the starter is present), and the starter must be found: proves that the check can fail.
# The manifest merger's report is never read. Run after `./gradlew publishAllPublicationsToLocalRepoRepository`.
# Usage: scripts/check-consumer-manifest.sh        Environment: ANDROID_HOME / ANDROID_SDK_ROOT (or local.properties)
# Exit codes: 0 gate and control behave as required, 1 a check failed, 2 usage/environment error.
set -u

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
cd "$root" || exit 2
consumer=verification/android-consumer
lib=build/local-maven/io/github/shishark/faceblur-android
say() { echo "check-consumer-manifest: $*"; }

[ -f "$consumer/settings.gradle.kts" ] || { say "ERROR - $consumer does not exist" >&2; exit 2; }
# The POM of the version the consumer app resolves (VERSION_NAME of gradle.properties), not the newest file.
version=$(sed -n 's/^VERSION_NAME=//p' gradle.properties | head -1)
[ -n "$version" ] || { say "ERROR - no VERSION_NAME in gradle.properties" >&2; exit 2; }
# A release has faceblur-android-<version>.pom; a SNAPSHOT has timestamped names (the latest sorts last).
pom=$(ls "$lib/$version"/faceblur-android-*.pom 2>/dev/null | sort | tail -1)
[ -n "$pom" ] && [ -f "$pom" ] || { say "ERROR - no faceblur-android $version publication under build/local-maven (publish first)" >&2; exit 2; }
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)}}
aapt2=$(ls -d "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)
if [ -z "$aapt2" ] || [ ! -x "$aapt2" ]; then say "ERROR - no aapt2 in the Android SDK build-tools" >&2; exit 2; fi
say "aapt2 $("$aapt2" version 2>&1 | head -1) from build-tools $(basename "$(dirname "$aapt2")")"

if grep -q '<artifactId>onnxruntime-android</artifactId>' "$pom"; then
    expected="android.permission.ACCESS_NETWORK_STATE,android.permission.INTERNET"
    say "faceblur-android depends on onnxruntime-android: INTERNET + ACCESS_NETWORK_STATE expected in C1"
else
    expected=""
    say "faceblur-android does not depend on onnxruntime-android: no permission expected in C1"
fi
ort_permissions="android.permission.ACCESS_NETWORK_STATE,android.permission.INTERNET"
initializer=androidx.lifecycle.ProcessLifecycleInitializer
merged=$consumer/app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml
apk=$consumer/app/build/outputs/apk/release/app-release-unsigned.apk
work=$(mktemp -d)
# The consumer build's own Gradle reports (configuration cache) hold absolute paths and are never uploaded; they are
# removed so the report path checks of the verification job stay meaningful for the verification build itself.
trap 'rm -rf "$work" "$consumer/build/reports"' EXIT
trap 'exit 130' INT TERM

for cfg in C1 C0; do
    rm -f "$merged" "$apk"
    if ! ./gradlew -p "$consumer" --console=plain -q -Pcfg="$cfg" :app:assembleRelease > "$work/$cfg-build.txt" 2>&1 ||
        [ ! -f "$merged" ] || [ ! -f "$apk" ]; then
        say "FAIL - the consumer build $cfg failed or produced no manifest/APK:"
        tail -30 "$work/$cfg-build.txt"
        exit 1
    fi
    cp "$merged" "$work/$cfg.xml"
    if ! "$aapt2" dump xmltree --file AndroidManifest.xml "$apk" > "$work/$cfg.xmltree" 2>&1 ||
        ! "$aapt2" dump permissions "$apk" > "$work/$cfg.permissions" 2>&1; then
        say "ERROR - aapt2 could not read the $cfg APK" >&2
        exit 2
    fi
done

checker() { # checker <cfg> <starter> <permissions>
    python3 -I scripts/check-consumer-manifest.py --merged "$work/$1.xml" --xmltree "$work/$1.xmltree" \
        --dump-permissions "$work/$1.permissions" --starter "$2" --initializer "$initializer" --permissions "$3"
}
fail=0
say "C1 (app + faceblur-android):"
checker C1 absent "$expected" || fail=1
say "C0 control (ONNX Runtime without FaceBlur), with the C1 expectations - must FAIL:"
checker C0 absent "$expected"
code=$?
if [ "$code" -ne 1 ]; then
    say "FAIL - the C1 expectations did not fail on the control (exit $code): the gate cannot detect the starter"
    fail=1
fi
say "C0 control, starter and ONNX Runtime permissions expected - must PASS:"
checker C0 present "$ort_permissions" || fail=1

if [ "$fail" -ne 0 ]; then
    say "FAIL"
    exit 1
fi
say "PASS - C1 holds in the merged manifest and the APK; the C0 control fails the C1 expectations as it must"
