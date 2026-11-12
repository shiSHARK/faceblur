#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Phone-test log of the Android device tests for the checked-out commit (local only, never in CI).
#
# Builds the device-test APK from this checkout (`:faceblur:assembleAndroidDeviceTest`, the old APK is deleted first
# so the one measured is built in this run), installs it on the ONE physical test phone named by
# FACEBLUR_PHONE_SERIAL, places the fixtures of config/device-tests/fixtures.txt in the test app's private storage
# (the pinned reference test model and registered files from testdata/ only: never a photo; plus the synthetic HEIC
# fixture that `:faceblur:generateHeicFixture` generates into build/test-fixtures/heic/ at test time, macOS only, SHA-256
# checked by that task), runs every device test
# with the instrumentation runner (no filters, no retries), uninstalls the test app (which deletes its data) and
# writes the log: a header (commit, tree, device, APK SHA-256, exact commands, @Test count of the sources), the raw
# runner output unmodified, then the result of scripts/phone_test_log.py verify. No screenshot is ever taken, no file
# is read from or written to the phone's shared storage, and nothing is uploaded.
#
# Usage:  FACEBLUR_PHONE_SERIAL=<serial> FACEBLUR_PHONE_MODEL=<ro.product.model> sh scripts/phone-device-tests.sh <log>
#   The serial and the model are given by the caller (the local merge gate); this repository names no device.
#   Emulators are refused (serial emulator-*, qemu properties). <log> must not exist yet.
# Environment (optional): ANDROID_HOME / ANDROID_SDK_ROOT for adb when it is not on PATH.
# Exit codes: 0 PASS (log valid), 1 FAIL (build, install, a test or the log check failed), 2 usage/environment,
#             3 BLOCKED (the test phone is not connected or not authorised), 4 NOT APPLICABLE (no device tests in
#             this revision).
set -u

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)
cd "$root" || exit 2

say() { echo "phone-device-tests: $*"; }

[ $# -eq 1 ] || { say "usage: FACEBLUR_PHONE_SERIAL=... FACEBLUR_PHONE_MODEL=... sh scripts/phone-device-tests.sh <log>" >&2; exit 2; }
log=$1
[ ! -e "$log" ] || { say "ERROR - the log file exists already (evidence is never overwritten)" >&2; exit 2; }
serial=${FACEBLUR_PHONE_SERIAL:-}
model_want=${FACEBLUR_PHONE_MODEL:-}
case "$serial" in
    "" | emulator-* | *[!A-Za-z0-9]*) say "ERROR - FACEBLUR_PHONE_SERIAL must name the physical test phone (no emulator)" >&2; exit 2 ;;
esac
[ -n "$model_want" ] || { say "ERROR - FACEBLUR_PHONE_MODEL must name the model of the test phone" >&2; exit 2; }
# The device tests need the reference model and local images: fixtures of the maintainer's local gate, outside the
# repository (the build verifies them against the folder's MANIFEST.sha256 first).
[ -n "${FACEBLUR_LOCAL_FIXTURES:-}" ] && [ -d "$FACEBLUR_LOCAL_FIXTURES" ] ||
    { say "ERROR - FACEBLUR_LOCAL_FIXTURES must name the local fixtures folder" >&2; exit 2; }

if [ -z "$(git ls-tree -r --name-only HEAD -- faceblur/src/androidDeviceTest)" ]; then
    say "NOT APPLICABLE - this revision has no device tests (faceblur/src/androidDeviceTest)"
    exit 4
fi
if [ -n "$(git status --porcelain)" ]; then
    say "ERROR - the checkout has changes or untracked files: the log is for a commit, not a work tree" >&2
    exit 2
fi

adb_bin=$(command -v adb 2>/dev/null || true)
for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [ -z "$adb_bin" ] && [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then adb_bin=$sdk/platform-tools/adb; fi
done
[ -n "$adb_bin" ] || { say "ERROR - adb not found (PATH, ANDROID_HOME or ANDROID_SDK_ROOT)" >&2; exit 2; }
# Every call addresses the one phone by its serial; adb never reads this script's input (it would consume the loops'
# input lists).
phone() { "$adb_bin" -s "$serial" "$@" < /dev/null; }
prop() { phone shell getprop "$1" 2>/dev/null | tr -d '\r'; }

state=$(phone get-state 2>/dev/null | tr -d '\r' || true)
if [ "$state" != device ]; then
    say "BLOCKED - the test phone is not connected or not authorised (state: ${state:-none})"
    exit 3
fi
model=$(prop ro.product.model)
manufacturer=$(prop ro.product.manufacturer)
release=$(prop ro.build.version.release)
api=$(prop ro.build.version.sdk)
qemu_props="ro.kernel.qemu=$(prop ro.kernel.qemu);ro.boot.qemu=$(prop ro.boot.qemu);ro.hardware=$(prop ro.hardware)"
case "$qemu_props" in
    *qemu=1* | *hardware=goldfish | *hardware=ranchu) say "ERROR - the device is an emulator: not accepted" >&2; exit 2 ;;
esac
if [ "$model" != "$model_want" ]; then
    say "ERROR - the connected device is not the test phone (model differs)" >&2
    exit 2
fi

commit=$(git rev-parse HEAD)
tree=$(git rev-parse 'HEAD^{tree}')
pkg=io.github.shishark.faceblur.test
runner=androidx.test.runner.AndroidJUnitRunner
apk=faceblur/build/outputs/apk/androidTest/faceblur-androidTest.apk
build_cmd="./gradlew --console=plain :faceblur:fetchYunetModel :faceblur:assembleAndroidDeviceTest"
install_cmd="adb -s $serial install -t $apk"
instrument_cmd="adb -s $serial shell am instrument -r -w $pkg/$runner"
work=$(mktemp -d)
remote=/data/local/tmp/faceblur-fixtures-$$
installed=0
cleanup() {
    if [ "$installed" -eq 1 ]; then phone uninstall "$pkg" > /dev/null 2>&1 || true; fi
    phone shell rm -rf "$remote" > /dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

start=$(date -u +%Y-%m-%dT%H:%M:%SZ)
say "commit $commit, building the device-test APK"
rm -f "$apk"
./gradlew --console=plain :faceblur:fetchYunetModel :faceblur:assembleAndroidDeviceTest > "$work/build.txt" 2>&1
build_exit=$?
fresh=no
if [ "$build_exit" -eq 0 ] && [ -f "$apk" ]; then fresh=yes; fi
if [ "$fresh" != yes ]; then
    say "FAIL - the device-test APK was not built (exit $build_exit):"
    tail -30 "$work/build.txt"
    exit 1
fi
apk_sha=$(shasum -a 256 "$apk" | cut -d' ' -f1)

# Fixtures: only the pinned reference model, verified local files and registered tracked files under testdata/. The
# build step above copied the local fixtures to build/local-fixtures/ after verifying every file against the manifest.
local_dir=build/local-fixtures
model_name=$(sed -n 's/^fileName=//p' "$local_dir/yunet/yunet.properties")
model_pin=$(sed -n 's/^sha256=//p' "$local_dir/yunet/yunet.properties")
fixtures=""
: > "$work/fixtures.list"
while read -r kind value rest; do
    case "$kind" in "" | "#"*) continue ;; esac
    if [ -n "$rest" ]; then say "FAIL - config/device-tests/fixtures.txt: unexpected text after '$value'"; exit 1; fi
    case "$kind" in
        model)
            src=build/test-models/$value
            if [ "$value" != "$model_name" ] || [ ! -f "$src" ] ||
                [ "$(shasum -a 256 "$src" | cut -d' ' -f1)" != "$model_pin" ]; then
                say "FAIL - fixture model '$value' is not the pinned reference test model"; exit 1
            fi ;;
        local)
            src=$local_dir/$value
            sum=$(shasum -a 256 "$src" 2>/dev/null | cut -d' ' -f1)
            case "$value" in images/*) ;; *) say "FAIL - fixture '$value' is not under images/"; exit 1 ;; esac
            if ! awk -v p="$value" -v s="$sum" '$2 == p && $1 == s { f = 1 } END { exit !f }' "$local_dir/MANIFEST.sha256"; then
                say "FAIL - fixture '$value' is not a local file with its manifest SHA-256"; exit 1
            fi ;;
        tracked)
            src=$value
            sum=$(shasum -a 256 "$src" 2>/dev/null | cut -d' ' -f1)
            case "$src" in testdata/*) ;; *) say "FAIL - fixture '$src' is not under testdata/"; exit 1 ;; esac
            if ! git ls-files --error-unmatch -- "$src" > /dev/null 2>&1 ||
                ! awk -F '\t' -v p="$src" -v s="$sum" '$1 == p && $2 == s { f = 1 } END { exit !f }' \
                    config/data-admission/register.tsv; then
                say "FAIL - fixture '$src' is not a tracked, registered file with its registered SHA-256"; exit 1
            fi ;;
        *) say "FAIL - config/device-tests/fixtures.txt: unknown kind '$kind'"; exit 1 ;;
    esac
    printf '%s\n' "$src" >> "$work/fixtures.list"
done < config/device-tests/fixtures.txt
[ -s "$work/fixtures.list" ] || { say "FAIL - config/device-tests/fixtures.txt names no fixture"; exit 1; }

# Generated fixture (not committed): the build task generates and checks the synthetic HEIC; any failure fails the run.
heic_name=synthetic-pattern-canary-256x192.heic
heic_file=build/test-fixtures/heic/$heic_name
generate_cmd="./gradlew --console=plain :faceblur:generateHeicFixture"
rm -f "$heic_file"
if ! ./gradlew --console=plain :faceblur:generateHeicFixture > "$work/heic.txt" 2>&1 || [ ! -f "$heic_file" ]; then
    say "FAIL - the HEIC test fixture could not be generated (the HEIC device test fails without it):"
    tail -15 "$work/heic.txt"
    exit 1
fi
heic_sha=$(shasum -a 256 "$heic_file" | cut -d' ' -f1)

phone uninstall "$pkg" > /dev/null 2>&1 || true
if ! phone install -t "$apk" > "$work/install.txt" 2>&1; then
    say "FAIL - install failed:"
    cat "$work/install.txt"
    exit 1
fi
installed=1
phone shell mkdir -p "$remote" > /dev/null 2>&1
phone shell run-as "$pkg" mkdir -p files/faceblur-fixtures > /dev/null 2>&1
placed=0
while IFS= read -r src; do
    name=$(basename "$src")
    want=$(shasum -a 256 "$src" | cut -d' ' -f1)
    phone push "$src" "$remote/$name" > /dev/null 2>&1 &&
        phone shell chmod 644 "$remote/$name" > /dev/null 2>&1 &&
        phone shell run-as "$pkg" cp "$remote/$name" "files/faceblur-fixtures/$name" > /dev/null 2>&1
    got=$(phone shell run-as "$pkg" sha256sum "files/faceblur-fixtures/$name" 2>/dev/null | tr -d '\r' | cut -d' ' -f1)
    if [ "$got" != "$want" ]; then say "FAIL - fixture $name was not placed intact in the test app's storage"; exit 1; fi
    fixtures="$fixtures${fixtures:+,}$name:$got"
    placed=$((placed + 1))
done < "$work/fixtures.list"
if [ "$placed" -ne "$(wc -l < "$work/fixtures.list")" ]; then
    say "FAIL - $placed of $(wc -l < "$work/fixtures.list" | tr -d ' ') fixtures placed"
    exit 1
fi
phone push "$heic_file" "$remote/$heic_name" > /dev/null 2>&1 &&
    phone shell chmod 644 "$remote/$heic_name" > /dev/null 2>&1 &&
    phone shell run-as "$pkg" cp "$remote/$heic_name" "files/faceblur-fixtures/$heic_name" > /dev/null 2>&1
heic_got=$(phone shell run-as "$pkg" sha256sum "files/faceblur-fixtures/$heic_name" 2>/dev/null | tr -d '\r' | cut -d' ' -f1)
if [ "$heic_got" != "$heic_sha" ]; then say "FAIL - the HEIC fixture was not placed intact in the test app's storage"; exit 1; fi
phone shell rm -rf "$remote" > /dev/null 2>&1 || true

say "running the device tests on the test phone (API $api)"
phone shell am instrument -r -w "$pkg/$runner" > "$work/raw.txt" 2>&1
instrument_exit=$?
# The stored section ends with a newline (the checker hashes exactly the bytes between the two markers).
if [ -s "$work/raw.txt" ] && [ "$(tail -c 1 "$work/raw.txt" | od -An -c | tr -d ' ')" != '\n' ]; then
    printf '\n' >> "$work/raw.txt"
fi
raw_sha=$(shasum -a 256 "$work/raw.txt" | cut -d' ' -f1)
phone uninstall "$pkg" > /dev/null 2>&1 && installed=0
methods=$(python3 -I scripts/phone_test_log.py count-tests)
end=$(date -u +%Y-%m-%dT%H:%M:%SZ)

{
    echo "faceblur-phone-test-log 1"
    echo "commit=$commit"
    echo "tree=$tree"
    echo "serial=$serial"
    echo "model=$model"
    echo "manufacturer=$manufacturer"
    echo "android_release=$release"
    echo "api_level=$api"
    echo "qemu_props=$qemu_props"
    echo "emulator=no"
    echo "start=$start"
    echo "end=$end"
    echo "build_command=$build_cmd"
    echo "build_exit=$build_exit"
    echo "apk=$apk"
    echo "apk_built_in_this_run=$fresh"
    echo "apk_sha256=$apk_sha"
    echo "test_package=$pkg"
    echo "runner=$runner"
    echo "install_command=$install_cmd"
    echo "instrument_command=$instrument_cmd"
    echo "instrument_exit=$instrument_exit"
    echo "fixtures=$fixtures"
    echo "generated_fixtures=$heic_name:$heic_got"
    echo "generate_command=$generate_cmd"
    echo "source_test_methods=$methods"
    echo "raw_sha256=$raw_sha"
    echo "--- instrumentation output (unmodified) ---"
    cat "$work/raw.txt"
    echo "--- end of instrumentation output ---"
    echo "--- build output (last 15 lines) ---"
    tail -15 "$work/build.txt"
} > "$log"

check=$(python3 -I scripts/phone_test_log.py verify --log "$log" --rev HEAD 2>&1)
code=$?
{
    echo "--- log check (scripts/phone_test_log.py verify) ---"
    echo "$check"
    if [ "$code" -eq 0 ]; then echo "result=PASS"; else echo "result=FAIL"; fi
} >> "$log"
echo "$check"
if [ "$code" -eq 0 ]; then
    say "PASS - log $log"
    exit 0
fi
say "FAIL - log $log"
exit 1
