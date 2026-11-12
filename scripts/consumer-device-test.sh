#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Device test of the PUBLISHED Android library (local only, never in CI): builds the repository-local publication,
# builds the consumer app verification/android-consumer (C1, debug) and its instrumented test against that publication
# (the library comes only from build/local-maven, never as a project dependency), installs both on ONE device, places
# the fixtures in the consumer app's private storage (the pinned reference test model and one synthetic face of the
# maintainer's local fixtures only: never a photo), runs the test with the instrumentation runner (no filters, no retries),
# uninstalls both apps (which deletes their data) and writes a log. No screenshot is taken, nothing is read from or
# written to shared storage, nothing is uploaded.
#
# Usage:  FACEBLUR_CONSUMER_SERIAL=<serial> sh scripts/consumer-device-test.sh <log>
#   The serial is given by the caller (a physical test phone the caller holds the device lock for, or emulator-NNNN).
#   <log> must not exist yet.
# Environment (optional): ANDROID_HOME / ANDROID_SDK_ROOT for adb when it is not on PATH.
# Exit codes: 0 PASS (the runner reports OK for the one test), 1 FAIL, 2 usage/environment, 3 BLOCKED (device absent).
set -u

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
cd "$root" || exit 2

say() { echo "consumer-device-test: $*"; }

[ $# -eq 1 ] || { say "usage: FACEBLUR_CONSUMER_SERIAL=... sh scripts/consumer-device-test.sh <log>" >&2; exit 2; }
log=$1
[ ! -e "$log" ] || { say "ERROR - the log file exists already (evidence is never overwritten)" >&2; exit 2; }
serial=${FACEBLUR_CONSUMER_SERIAL:-}
case "$serial" in
    "" | *[!A-Za-z0-9-]*) say "ERROR - FACEBLUR_CONSUMER_SERIAL must name the device" >&2; exit 2 ;;
esac

adb_bin=$(command -v adb 2>/dev/null || true)
for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [ -z "$adb_bin" ] && [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then adb_bin=$sdk/platform-tools/adb; fi
done
[ -n "$adb_bin" ] || { say "ERROR - adb not found (PATH, ANDROID_HOME or ANDROID_SDK_ROOT)" >&2; exit 2; }
# Every call addresses the one device by its serial; adb never reads this script's input.
dev() { "$adb_bin" -s "$serial" "$@" < /dev/null; }
prop() { dev shell getprop "$1" 2>/dev/null | tr -d '\r'; }

state=$(dev get-state 2>/dev/null | tr -d '\r' || true)
if [ "$state" != device ]; then
    say "BLOCKED - the device is not connected or not authorised (state: ${state:-none})"
    exit 3
fi

app=io.github.shishark.faceblur.consumer
test_pkg=$app.test
runner=androidx.test.runner.AndroidJUnitRunner
consumer=verification/android-consumer
version=$(sed -n 's/^VERSION_NAME=//p' gradle.properties | head -1)
[ -n "$version" ] || { say "ERROR - no VERSION_NAME in gradle.properties" >&2; exit 2; }
work=$(mktemp -d)
remote=/data/local/tmp/faceblur-consumer-$$
installed=0
cleanup() {
    if [ "$installed" -eq 1 ]; then
        dev uninstall "$test_pkg" > /dev/null 2>&1 || true
        dev uninstall "$app" > /dev/null 2>&1 || true
    fi
    dev shell rm -rf "$remote" > /dev/null 2>&1 || true
    rm -rf "$work" "$consumer/build/reports"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Fixtures: the pinned reference model and one synthetic image of the maintainer's local fixtures (outside the
# repository; the build verifies every file against their MANIFEST.sha256 and copies them to build/local-fixtures/).
[ -n "${FACEBLUR_LOCAL_FIXTURES:-}" ] && [ -d "$FACEBLUR_LOCAL_FIXTURES" ] ||
    { say "ERROR - FACEBLUR_LOCAL_FIXTURES must name the local fixtures folder" >&2; exit 2; }
./gradlew --console=plain -q :faceblur:fetchYunetModel > "$work/fetch.txt" 2>&1 || { say "FAIL - fetchYunetModel"; tail -20 "$work/fetch.txt"; exit 1; }
local_dir=build/local-fixtures
model_name=$(sed -n 's/^fileName=//p' "$local_dir/yunet/yunet.properties")
model_pin=$(sed -n 's/^sha256=//p' "$local_dir/yunet/yunet.properties")
model=build/test-models/$model_name
face_rel=images/face-0003542-s0625-x0000-y0000.png
face=$local_dir/$face_rel
[ "$(shasum -a 256 "$model" 2> /dev/null | cut -d' ' -f1)" = "$model_pin" ] || { say "FAIL - the model is not the pinned test model"; exit 1; }
face_sum=$(shasum -a 256 "$face" 2> /dev/null | cut -d' ' -f1)
if ! awk -v p="$face_rel" -v s="$face_sum" '$2 == p && $1 == s { f = 1 } END { exit !f }' "$local_dir/MANIFEST.sha256"; then
    say "FAIL - the face fixture is not a local file with its manifest SHA-256"
    exit 1
fi

start=$(date -u +%Y-%m-%dT%H:%M:%SZ)
say "publishing the library to build/local-maven and building the consumer app and its test"
./gradlew --console=plain -q publishAllPublicationsToLocalRepoRepository > "$work/publish.txt" 2>&1 || { say "FAIL - publish"; tail -20 "$work/publish.txt"; exit 1; }
aar=$(ls build/local-maven/io/github/shishark/faceblur-android/"$version"/faceblur-android-*.aar 2> /dev/null | sort | tail -1)
[ -n "$aar" ] || { say "FAIL - no faceblur-android $version AAR under build/local-maven"; exit 1; }
rm -rf "$consumer/app/build/outputs/apk"
./gradlew -p "$consumer" --console=plain -q -Pcfg=C1 :app:assembleDebug :app:assembleDebugAndroidTest > "$work/build.txt" 2>&1 || {
    say "FAIL - the consumer build:"
    tail -30 "$work/build.txt"
    exit 1
}
./gradlew -p "$consumer" --console=plain -q -Pcfg=C1 :app:dependencies --configuration debugRuntimeClasspath 2> /dev/null |
    grep -E 'io.github.shishark:faceblur' > "$work/resolved.txt" || true
app_apk=$consumer/app/build/outputs/apk/debug/app-debug.apk
test_apk=$consumer/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[ -f "$app_apk" ] && [ -f "$test_apk" ] || { say "FAIL - the consumer APKs were not built"; exit 1; }

dev uninstall "$test_pkg" > /dev/null 2>&1 || true
dev uninstall "$app" > /dev/null 2>&1 || true
dev install "$app_apk" > "$work/install.txt" 2>&1 && dev install -t "$test_apk" >> "$work/install.txt" 2>&1 || {
    say "FAIL - install:"
    cat "$work/install.txt"
    exit 1
}
installed=1
dev shell mkdir -p "$remote" > /dev/null 2>&1
dev shell run-as "$app" mkdir -p files/faceblur-fixtures > /dev/null 2>&1
fixtures=""
for src in "$model" "$face"; do
    name=$(basename "$src")
    want=$(shasum -a 256 "$src" | cut -d' ' -f1)
    dev push "$src" "$remote/$name" > /dev/null 2>&1 &&
        dev shell chmod 644 "$remote/$name" > /dev/null 2>&1 &&
        dev shell run-as "$app" cp "$remote/$name" "files/faceblur-fixtures/$name" > /dev/null 2>&1
    got=$(dev shell run-as "$app" sha256sum "files/faceblur-fixtures/$name" 2> /dev/null | tr -d '\r' | cut -d' ' -f1)
    [ "$got" = "$want" ] || { say "FAIL - fixture $name was not placed intact"; exit 1; }
    fixtures="$fixtures${fixtures:+,}$name:$got"
done
dev shell rm -rf "$remote" > /dev/null 2>&1 || true

say "running the consumer device test on API $(prop ro.build.version.sdk)"
dev shell am instrument -r -w "$test_pkg/$runner" > "$work/raw.txt" 2>&1
instrument_exit=$?
end=$(date -u +%Y-%m-%dT%H:%M:%SZ)
verdict=FAIL
if [ "$instrument_exit" -eq 0 ] && grep -q '^OK (1 test)$' "$work/raw.txt" && grep -q '^INSTRUMENTATION_CODE: -1$' "$work/raw.txt" &&
    ! grep -qE '^(FAILURES!!!|INSTRUMENTATION_FAILED)' "$work/raw.txt"; then
    verdict=PASS
fi
{
    echo "faceblur-consumer-device-test-log 1"
    echo "result=$verdict"
    echo "commit=$(git rev-parse HEAD)"
    echo "tree=$(git rev-parse 'HEAD^{tree}')"
    echo "worktree_clean=$([ -z "$(git status --porcelain)" ] && echo yes || echo no)"
    echo "model=$(prop ro.product.model)"
    echo "manufacturer=$(prop ro.product.manufacturer)"
    echo "android_release=$(prop ro.build.version.release)"
    echo "api_level=$(prop ro.build.version.sdk)"
    echo "emulator=$([ -n "$(prop ro.kernel.qemu)" ] && echo yes || echo no)"
    echo "start=$start"
    echo "end=$end"
    echo "library_version=$version"
    echo "aar=$(basename "$aar")"
    echo "aar_sha256=$(shasum -a 256 "$aar" | cut -d' ' -f1)"
    echo "app_apk_sha256=$(shasum -a 256 "$app_apk" | cut -d' ' -f1)"
    echo "test_apk_sha256=$(shasum -a 256 "$test_apk" | cut -d' ' -f1)"
    echo "resolved (debugRuntimeClasspath of the consumer app):"
    sed 's/^/  /' "$work/resolved.txt"
    echo "fixtures=$fixtures"
    echo "instrument_command=adb -s <device> shell am instrument -r -w $test_pkg/$runner"
    echo "instrument_exit=$instrument_exit"
    echo "--- instrumentation output (unmodified) ---"
    cat "$work/raw.txt"
    echo "--- end of instrumentation output ---"
} > "$log"
if [ "$verdict" = PASS ]; then
    say "PASS - log $log"
    exit 0
fi
say "FAIL - log $log"
exit 1
