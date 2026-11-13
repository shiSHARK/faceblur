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
# The consumer build verifies its dependencies (verification/android-consumer/gradle/verification-metadata.xml), and the
# artifacts of a release version are rebuilt on every run, so no checksum of them can be committed. Like the merge gate
# and scripts/ci-steps.sh (own_trust_run), this script therefore needs FACEBLUR_OWN_PUBLICATION_TRUST (the helper
# own_publication_trust.py of the maintainer's tooling). For the consumer build and the dependency listing only, it adds
# exact per-file SHA-256 entries of the io.github.shishark artifacts at the strict X.Y.Z VERSION_NAME just published to
# build/local-maven to a throw-away change of that metadata file (no group trust, no other group or version, nothing
# committed), prints every coordinate and hash, restores the file on every exit and FAILs if the file differs from its
# state before the run or a throw-away copy is left. Without the variable the script refuses to run (exit 2).
#
# Usage:  FACEBLUR_OWN_PUBLICATION_TRUST=<helper> FACEBLUR_CONSUMER_SERIAL=<serial> sh scripts/consumer-device-test.sh <log>
#   The serial is given by the caller (a physical test phone the caller holds the device lock for, or emulator-NNNN).
#   <log> must not exist yet.
# Environment (optional): ANDROID_HOME / ANDROID_SDK_ROOT for adb when it is not on PATH.
#   FACEBLUR_CONSUMER_DRY_RUN=1: no device is used and no adb is needed; the script stops after the consumer build (publish,
#   trust step, consumer APKs, trust restore and clean check), writes no log and never reports PASS (exit 0 prints DRY RUN).
# Exit codes: 0 PASS (the runner reports OK for the one test), 1 FAIL, 2 usage/environment, 3 BLOCKED (device absent).
set -u

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
cd "$root" || exit 2

say() { echo "consumer-device-test: $*"; }

dry=${FACEBLUR_CONSUMER_DRY_RUN:-0}
[ $# -eq 1 ] || { say "usage: FACEBLUR_OWN_PUBLICATION_TRUST=... FACEBLUR_CONSUMER_SERIAL=... sh scripts/consumer-device-test.sh <log>" >&2; exit 2; }
log=$1
[ ! -e "$log" ] || { say "ERROR - the log file exists already (evidence is never overwritten)" >&2; exit 2; }
serial=${FACEBLUR_CONSUMER_SERIAL:-}
if [ "$dry" != 1 ]; then
    case "$serial" in
        "" | *[!A-Za-z0-9-]*) say "ERROR - FACEBLUR_CONSUMER_SERIAL must name the device" >&2; exit 2 ;;
    esac
fi
trust=${FACEBLUR_OWN_PUBLICATION_TRUST:-}
[ -n "$trust" ] && [ -f "$trust" ] ||
    { say "ERROR - FACEBLUR_OWN_PUBLICATION_TRUST must name the own-publication trust helper (the consumer build cannot verify the library's own publication without it)" >&2; exit 2; }

if [ "$dry" != 1 ]; then
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
else
    dev() { :; }   # dry run: no device is addressed
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
meta=$consumer/gradle/verification-metadata.xml
trust_active=no
meta_before=$(shasum -a 256 "$meta" 2> /dev/null | cut -d' ' -f1)
[ -n "$meta_before" ] || { say "ERROR - no $meta" >&2; exit 2; }
[ -z "$(find verification -name '*.gate-original' 2> /dev/null | head -1)" ] ||
    { say "ERROR - a throw-away verification-metadata copy (.gate-original) from an earlier run exists; not restored" >&2; exit 2; }
# Strict release version only (the helper refuses anything else too).
case "$version" in
    "" | *[!0-9.]* | .* | *. | *..*) say "ERROR - VERSION_NAME '$version' is not a strict X.Y.Z" >&2; exit 2 ;;
esac
[ "$(echo "$version" | awk -F. '{print NF}')" = 3 ] || { say "ERROR - VERSION_NAME '$version' is not a strict X.Y.Z" >&2; exit 2; }
untrust() {
    if [ "$trust_active" = yes ]; then
        python3 -I "$trust" restore "$meta" || true
        trust_active=no
    fi
}
cleanup() {
    untrust
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
# Own-publication trust: exact SHA-256 entries of the artifacts just published, for the consumer build only.
python3 -I "$trust" add --maven-dir build/local-maven --version "$version" "$meta" > "$work/trust.txt" 2>&1 || {
    say "FAIL - own-publication trust step refused:"
    cat "$work/trust.txt"
    exit 1
}
trust_active=yes
cat "$work/trust.txt"
trust_lines=$(grep -c '^own-publication-trust: io.github.shishark:' "$work/trust.txt")
[ "$trust_lines" -gt 0 ] || { say "FAIL - the trust step listed no io.github.shishark artifact"; exit 1; }
rm -rf "$consumer/app/build/outputs/apk"
./gradlew -p "$consumer" --console=plain -q -Pcfg=C1 :app:assembleDebug :app:assembleDebugAndroidTest > "$work/build.txt" 2>&1 || {
    say "FAIL - the consumer build:"
    tail -30 "$work/build.txt"
    exit 1
}
./gradlew -p "$consumer" --console=plain -q -Pcfg=C1 :app:dependencies --configuration debugRuntimeClasspath 2> /dev/null |
    grep -E 'io.github.shishark:faceblur' > "$work/resolved.txt" || true
# The committed metadata must be exactly as before the run and no throw-away copy may be left.
untrust
[ "$(shasum -a 256 "$meta" | cut -d' ' -f1)" = "$meta_before" ] &&
    [ -z "$(find verification -name '*.gate-original' 2> /dev/null | head -1)" ] ||
    { say "FAIL - $meta differs from its state before the run or a throw-away copy is left"; exit 1; }
app_apk=$consumer/app/build/outputs/apk/debug/app-debug.apk
test_apk=$consumer/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[ -f "$app_apk" ] && [ -f "$test_apk" ] || { say "FAIL - the consumer APKs were not built"; exit 1; }

if [ "$dry" = 1 ]; then
    say "DRY RUN - publish, trust step (restored, metadata unchanged) and the consumer build were done; no device step was run, no log written, this is not a PASS"
    exit 0
fi
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
