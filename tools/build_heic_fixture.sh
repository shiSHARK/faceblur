#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Builds the synthetic HEIC device-test fixture (macOS only: it needs ImageIO through swiftc). The picture is a
# generated pattern, not a photo; it shows no face. The file carries CANARY metadata (strings starting with FBCANARY
# in EXIF, TIFF, XMP, plus GPS values and orientation 6) so that the device test can prove it does not reach the
# output. The HEVC encoder of the Mac is not guaranteed to be bit-identical across macOS versions: the committed file
# is the record, the SHA-256 is printed for the data register row.
# Usage: sh tools/build_heic_fixture.sh <out_dir>      Output: <out_dir>/synthetic-pattern-canary-256x192.heic
set -eu
here=$(cd "$(dirname "$0")" && pwd)
[ $# -eq 1 ] || { echo "usage: sh tools/build_heic_fixture.sh <out_dir>" >&2; exit 2; }
out=$1
mkdir -p "$out"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
python3 -I "$here/heic_fixture_png.py" "$work/pattern.png"
swiftc -O "$here/heic_fixture.swift" -o "$work/heic_fixture"
"$work/heic_fixture" "$work/pattern.png" "$out/synthetic-pattern-canary-256x192.heic"
shasum -a 256 "$out/synthetic-pattern-canary-256x192.heic"
wc -c < "$out/synthetic-pattern-canary-256x192.heic"
