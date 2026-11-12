#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Model-leak check (ADR 0012 "Leak check", SEC-MA-003): no published artifact contains a test-model fixture.

The library ships no face-detection model. The reference model and the models derived from it are test fixtures
only. This check scans every given file and directory, and every entry of every archive inside them to any depth
(zip-based: .jar .aar .klib .zip .apk .aab and any entry that starts with a zip header; gzip; tar), for these
signals of each fixture given with --fixture:
  - three 64-byte windows of the file (near its start, middle and end; each with at least 24 distinct byte values),
    as raw bytes;
  - the same windows base64-encoded (standard and URL-safe alphabet) at all three byte alignments, and as lower- and
    upper-case hex, each also in UTF-16LE and UTF-16BE (Kotlin/Native stores string constants as UTF-16);
  - the SHA-256 of the fixture as lower- and upper-case hex (also UTF-16LE/BE);
  - any file or archive entry whose name ends in .onnx or .ort (case-insensitive).
Containers are scanned as raw bytes too, not only their entries.
Fail closed: a missing or empty fixture, an artifact path that does not exist, nothing to scan, an archive that
cannot be read completely, or nesting deeper than 8 levels is an error (exit 2).
Output names the artifact (inside archives: archive!entry), the fixture's file name and the kind of signal, never the
matching bytes.

Usage: check-model-leak.py --fixture <file> [--fixture <file>...] <artifact file-or-directory>...
Exit codes: 0 clean, 1 a model signal was found, 2 usage error or unreadable input. Standard library only.
"""
import base64
import gzip
import hashlib
import io
import os
import sys
import tarfile
import zipfile

WINDOW = 64
MIN_DISTINCT = 24
ZIP_EXT = (".jar", ".aar", ".klib", ".zip", ".apk", ".aab")
TAR_EXT = (".tgz", ".tar.gz", ".tar")
MODEL_EXT = (".onnx", ".ort")
MAX_DEPTH = 8
MAX_BYTES = 1024 * 1024 * 1024


class Unreadable(Exception):
    pass


def pick_window(data: bytes, start: int, step: int) -> int:
    """Offset of the first window from [start] (moving by [step]) with at least MIN_DISTINCT byte values."""
    pos = start
    while 0 <= pos <= len(data) - WINDOW:
        if len(set(data[pos:pos + WINDOW])) >= MIN_DISTINCT:
            return pos
        pos += step
    raise ValueError("no window with enough distinct byte values")


def signals(name: str, data: bytes) -> list:
    """(needle bytes, description) pairs for one fixture."""
    if len(data) < 3 * WINDOW:
        raise ValueError("fixture too small for three windows")
    offsets = [
        pick_window(data, 0, 1),
        pick_window(data, len(data) // 2 - WINDOW // 2, 1),
        pick_window(data, len(data) - WINDOW, -1),
    ]
    out = []
    texts = []
    for label, off in zip(("start", "middle", "end"), offsets):
        w = data[off:off + WINDOW]
        out.append((w, f"{name}: {label} window, raw bytes"))
        for align in range(3):
            part = w[align:]
            part = part[:len(part) - len(part) % 3]
            texts.append((base64.b64encode(part), f"{name}: {label} window, base64 (alignment {align})"))
            texts.append((base64.urlsafe_b64encode(part), f"{name}: {label} window, base64url (alignment {align})"))
        texts.append((w.hex().encode(), f"{name}: {label} window, hex"))
        texts.append((w.hex().upper().encode(), f"{name}: {label} window, HEX"))
    sha = hashlib.sha256(data).hexdigest()
    texts.append((sha.encode(), f"{name}: SHA-256 hex"))
    texts.append((sha.upper().encode(), f"{name}: SHA-256 HEX"))
    for needle, what in texts:
        text = needle.decode("ascii")
        out.append((needle, what))
        out.append((text.encode("utf-16-le"), what + " (UTF-16LE)"))
        out.append((text.encode("utf-16-be"), what + " (UTF-16BE)"))
    return out


def scan_bytes(name: str, data: bytes, depth: int, needles: list, found: list, counter: list) -> None:
    counter[0] += 1
    low_name = name.lower()
    if depth > MAX_DEPTH:
        raise Unreadable(f"{name}: archives nested deeper than {MAX_DEPTH} levels")
    entry_name = low_name.rsplit("!", 1)[-1]
    if entry_name.endswith(MODEL_EXT):
        found.append((name, "file name ends in .onnx or .ort"))
    for needle, what in needles:
        if needle in data:
            found.append((name, what))
    if low_name.endswith(ZIP_EXT) or (data[:4] == b"PK\x03\x04" and not low_name.endswith(".class")):
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as zf:
                for info in zf.infolist():
                    if info.filename.lower().rstrip("/").endswith(MODEL_EXT):
                        found.append((f"{name}!{info.filename}", "entry name ends in .onnx or .ort"))
                    if info.is_dir():
                        continue
                    if info.file_size > MAX_BYTES:
                        raise Unreadable(f"{name}!{info.filename}: larger than {MAX_BYTES} bytes")
                    scan_bytes(f"{name}!{info.filename}", zf.read(info), depth + 1, needles, found, counter)
            return
        except (zipfile.BadZipFile, RuntimeError, NotImplementedError, EOFError, ValueError) as e:
            if low_name.endswith(ZIP_EXT):
                raise Unreadable(f"{name}: cannot unpack ({e.__class__.__name__})")
    if low_name.endswith(TAR_EXT) or data[257:262] == b"ustar":
        try:
            with tarfile.open(fileobj=io.BytesIO(data), mode="r:*") as tf:
                for member in tf.getmembers():
                    if member.name.lower().endswith(MODEL_EXT):
                        found.append((f"{name}!{member.name}", "entry name ends in .onnx or .ort"))
                    if not member.isfile():
                        continue
                    fh = tf.extractfile(member)
                    if fh is None:
                        raise Unreadable(f"{name}!{member.name}: unreadable member")
                    scan_bytes(f"{name}!{member.name}", fh.read(), depth + 1, needles, found, counter)
            return
        except (tarfile.TarError, EOFError, OSError) as e:
            if low_name.endswith(TAR_EXT):
                raise Unreadable(f"{name}: cannot unpack ({e.__class__.__name__})")
    if low_name.endswith(".gz") or data[:2] == b"\x1f\x8b":
        try:
            inner = gzip.decompress(data)
        except (OSError, EOFError) as e:
            if low_name.endswith(".gz"):
                raise Unreadable(f"{name}: cannot decompress ({e.__class__.__name__})")
        else:
            scan_bytes(name[:-3] if low_name.endswith(".gz") else name + "!gunzip", inner, depth + 1, needles,
                       found, counter)


def main(argv: list) -> int:
    fixtures = []
    targets = []
    it = iter(argv)
    for a in it:
        if a == "--fixture":
            f = next(it, None)
            if f is None:
                print("check-model-leak: --fixture needs a file", file=sys.stderr)
                return 2
            fixtures.append(f)
        else:
            targets.append(a)
    if not fixtures or not targets:
        print("usage: check-model-leak.py --fixture <file> [--fixture <file>...] <artifact file-or-directory>...",
              file=sys.stderr)
        return 2
    needles = []
    for f in fixtures:
        try:
            with open(f, "rb") as fh:
                data = fh.read()
            needles.extend(signals(os.path.basename(f), data))
        except (OSError, ValueError) as e:
            print(f"check-model-leak: ERROR - fixture {os.path.basename(f)} unusable ({e.__class__.__name__}: "
                  f"{e if isinstance(e, ValueError) else 'cannot read'})", file=sys.stderr)
            return 2
    # Identical needles (shared parts of the source and its derived models) are searched once.
    unique = {}
    for needle, what in needles:
        unique.setdefault(needle, what)
    needles = list(unique.items())
    found: list = []
    counter = [0]
    files = 0
    try:
        for top in targets:
            if not os.path.exists(top):
                print(f"check-model-leak: ERROR - not found: {top}", file=sys.stderr)
                return 2
            paths = [top] if os.path.isfile(top) else sorted(
                os.path.join(d, f) for d, _, fs in os.walk(top) for f in fs)
            for p in paths:
                if os.path.islink(p):
                    if p.lower().endswith(MODEL_EXT):
                        found.append((p, "file name ends in .onnx or .ort"))
                    continue
                try:
                    if os.path.getsize(p) > MAX_BYTES:
                        raise Unreadable(f"{p}: larger than {MAX_BYTES} bytes")
                    with open(p, "rb") as fh:
                        data = fh.read()
                except OSError as e:
                    raise Unreadable(f"{p}: {e.__class__.__name__}")
                files += 1
                scan_bytes(p, data, 0, needles, found, counter)
    except Unreadable as e:
        print(f"check-model-leak: ERROR - not scanned completely: {e}", file=sys.stderr)
        return 2
    if files == 0:
        print("check-model-leak: ERROR - no files to scan (a check that scans nothing proves nothing)", file=sys.stderr)
        return 2
    if found:
        print("check-model-leak: FAIL - model signals found:", file=sys.stderr)
        for name, what in found:
            print(f"  {name}: {what}", file=sys.stderr)
        return 1
    print(f"check-model-leak: PASS - {files} files, {counter[0]} files and archive entries scanned for "
          f"{len(needles)} signals of {len(fixtures)} fixture(s) and .onnx/.ort names; nothing found")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
