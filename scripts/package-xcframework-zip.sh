#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Builds the XCFramework release zip with the notices at its root (ADR 0008): FaceBlurCore.xcframework, LICENSE,
# NOTICE and licenses/ (the texts that licenses/INDEX.txt assigns to the xcframework kind). The zip root keeps the
# .xcframework directory first-level, as Swift Package Manager expects for a binary target.
# Usage: sh scripts/package-xcframework-zip.sh <folder holding FaceBlurCore.xcframework> <output zip>
# Run from the repository root on macOS (zip, swift). Prints the SwiftPM checksum of the zip on the last line.
# Exit codes: 0 ok, 1 a step failed, 2 usage error.
set -eu
[ $# -eq 2 ] || { echo "usage: sh scripts/package-xcframework-zip.sh <folder> <output zip>" >&2; exit 2; }
folder=$1
out=$2
[ -d "$folder/FaceBlurCore.xcframework" ] || { echo "package-xcframework-zip: no FaceBlurCore.xcframework in $folder" >&2; exit 2; }
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT INT TERM
cp -R "$folder/FaceBlurCore.xcframework" "$stage/"
python3 -I scripts/licence-notices.py stage xcframework "$stage"
# The order of the slices in the XCFramework's Info.plist is not stable from one build to the next (the binaries
# are byte-identical). Sort them by library identifier, so that the same sources always give the same zip.
python3 -I - "$stage/FaceBlurCore.xcframework/Info.plist" <<'PY'
import plistlib
import sys

path = sys.argv[1]
with open(path, "rb") as f:
    plist = plistlib.load(f)
plist["AvailableLibraries"] = sorted(plist["AvailableLibraries"], key=lambda lib: lib["LibraryIdentifier"])
with open(path, "wb") as f:
    plistlib.dump(plist, f, fmt=plistlib.FMT_XML, sort_keys=True)
PY
# Deterministic zip: the same staged files always give the same bytes and the same checksum. Every file gets the fixed
# modification time 1980-01-01 (the earliest zip date), entries are sorted in byte order, and `zip -X` drops the extra
# attributes (owner, access and creation times); no directory entries, no extended attributes or resource forks (no
# __MACOSX folder). Symbolic links would be stored as links (-y); an iOS XCFramework has none.
TZ=UTC find "$stage" -exec touch -h -t 198001010000 {} +
rm -f "$out"
case $out in /*) abs_out=$out ;; *) abs_out=$(pwd)/$out ;; esac
(cd "$stage" && find . -type f -o -type l | sed 's|^\./||' | LC_ALL=C sort | TZ=UTC zip -X -D -y -q -@ "$abs_out")
swift package compute-checksum "$out"
