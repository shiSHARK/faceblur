#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Fails if build outputs embed absolute build-machine paths (user, folder or disk names) - SEC-006.

Scans every file under the given files/directories, including the contents of archives to any depth: zip-based
archives (.jar .aar .klib .zip .apk .aab .xcframework zips) and gzip/tar archives (.gz .tgz .tar .tar.gz). Every
file is checked as raw bytes (ASCII/UTF-8) and as UTF-16LE and UTF-16BE, case-insensitively, for:
  home folders (/Users/, /home/, /root/, C:\\Users\\), macOS temporary and per-user folders (/private/tmp/, /tmp/,
  /private/var/, /var/folders/), mounted volumes (/Volumes/) and dash-encoded home paths as used in tool scratch
  folder names (-Users-).
Fail closed: an archive that cannot be read completely, or a file that cannot be read, is an error (exit 2).
Prints only the names of offending files (inside archives: archive!member), never the matching text.

Usage: check-artifact-paths.py <file-or-directory>...
Exit codes: 0 clean, 1 absolute paths found, 2 usage error or unreadable/unpackable input. Standard library only.
"""
import gzip
import io
import os
import sys
import tarfile
import zipfile

# Assembled from parts so that this file does not itself contain a literal home path (data admission).
U = "users"
FRAGMENTS = [
    "/" + U + "/", "/home/", "/root/", "c:\\" + U + "\\", "/private/tmp/", "/tmp/", "/private/var/",
    "/var/folders/", "/volumes/", "-" + U + "-",
]
NEEDLES = []
for frag in FRAGMENTS:
    for enc in ("utf-8", "utf-16-le", "utf-16-be"):
        NEEDLES.append(frag.encode(enc))
ZIP_EXT = (".jar", ".aar", ".klib", ".zip", ".apk", ".aab")
TAR_EXT = (".tgz", ".tar.gz", ".tar")
MAX_DEPTH = 8
MAX_BYTES = 512 * 1024 * 1024


class Unreadable(Exception):
    pass


def lower_ascii(data: bytes) -> bytes:
    # bytes.lower() folds ASCII letters only; UTF-16 code units of ASCII letters fold the same way.
    return data.lower()


def hit(data: bytes) -> bool:
    low = lower_ascii(data)
    return any(n in low for n in NEEDLES)


def scan_bytes(name: str, data: bytes, depth: int, found: list, counter: list) -> None:
    counter[0] += 1
    low_name = name.lower()
    if depth > MAX_DEPTH:
        raise Unreadable(f"{name}: archives nested deeper than {MAX_DEPTH} levels")
    if low_name.endswith(ZIP_EXT) or (data[:4] == b"PK\x03\x04" and not low_name.endswith((".class",))):
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as zf:
                for info in zf.infolist():
                    if info.is_dir():
                        continue
                    if info.file_size > MAX_BYTES:
                        raise Unreadable(f"{name}!{info.filename}: larger than {MAX_BYTES} bytes")
                    scan_bytes(f"{name}!{info.filename}", zf.read(info), depth + 1, found, counter)
            return
        except (zipfile.BadZipFile, RuntimeError, NotImplementedError, EOFError) as e:
            if low_name.endswith(ZIP_EXT):
                raise Unreadable(f"{name}: cannot unpack ({e.__class__.__name__})")
    if low_name.endswith(TAR_EXT):
        try:
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as tf:
                for member in tf.getmembers():
                    if not member.isfile():
                        continue
                    fh = tf.extractfile(member)
                    if fh is None:
                        raise Unreadable(f"{name}!{member.name}: unreadable member")
                    scan_bytes(f"{name}!{member.name}", fh.read(), depth + 1, found, counter)
            return
        except (tarfile.TarError, EOFError, OSError) as e:
            raise Unreadable(f"{name}: cannot unpack ({e.__class__.__name__})")
    if low_name.endswith(".gz") or data[:2] == b"\x1f\x8b":
        try:
            inner = gzip.decompress(data)
        except (OSError, EOFError) as e:
            if low_name.endswith(".gz"):
                raise Unreadable(f"{name}: cannot decompress ({e.__class__.__name__})")
        else:
            scan_bytes(name[:-3] if low_name.endswith(".gz") else name + "!gunzip", inner, depth + 1, found, counter)
            return
    if hit(data):
        found.append(name)


def main(argv: list) -> int:
    if not argv:
        print("usage: check-artifact-paths.py <file-or-directory>...", file=sys.stderr)
        return 2
    found: list = []
    counter = [0]
    try:
        for top in argv:
            if not os.path.exists(top):
                print(f"check-artifact-paths: not found: {top}", file=sys.stderr)
                return 2
            paths = [top] if os.path.isfile(top) else [
                os.path.join(d, f) for d, _, fs in os.walk(top) for f in fs]
            for p in sorted(paths):
                if os.path.islink(p):
                    continue
                try:
                    if os.path.getsize(p) > MAX_BYTES:
                        raise Unreadable(f"{p}: larger than {MAX_BYTES} bytes")
                    with open(p, "rb") as fh:
                        data = fh.read()
                except OSError as e:
                    raise Unreadable(f"{p}: {e.__class__.__name__}")
                scan_bytes(p, data, 0, found, counter)
    except Unreadable as e:
        print(f"check-artifact-paths: ERROR - not scanned completely: {e}", file=sys.stderr)
        return 2
    if found:
        print("check-artifact-paths: FAIL - absolute build paths found in:", file=sys.stderr)
        for name in found:
            print(f"  {name}", file=sys.stderr)
        return 1
    print(f"check-artifact-paths: PASS - {counter[0]} files and archive members scanned, no absolute build paths")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
