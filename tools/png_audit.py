#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Independent PNG metadata audit (no image library): signature, every chunk with its CRC, IHDR fields, bytes after
IEND. PASS only if the chunks are exactly IHDR, IDAT..., IEND (no iCCP/sRGB/gAMA/cHRM/tEXt/zTXt/iTXt/eXIf/tIME/pHYs
or any other chunk), every CRC is valid, nothing follows IEND, and IHDR is 640x640, 8-bit, colour type 2 (RGB),
no interlace. Usage: python3 -I png_audit.py <dir>"""
import glob
import os
import struct
import sys
import zlib

ok_all = True
for f in sorted(glob.glob(os.path.join(sys.argv[1], "*.png"))):
    b = open(f, "rb").read()
    problems = []
    if b[:8] != b"\x89PNG\r\n\x1a\n":
        problems.append("bad signature")
    p, chunks, ihdr = 8, [], None
    while p + 12 <= len(b):
        n, t = struct.unpack(">I4s", b[p:p + 8])
        if p + 12 + n > len(b):
            problems.append(f"truncated chunk {t!r}")
            break
        data = b[p + 8:p + 8 + n]
        crc = struct.unpack(">I", b[p + 8 + n:p + 12 + n])[0]
        if zlib.crc32(t + data) & 0xFFFFFFFF != crc:
            problems.append(f"CRC {t!r}")
        chunks.append(t.decode("latin1"))
        if t == b"IHDR":
            if n == 13:
                ihdr = struct.unpack(">IIBBBBB", data)
            else:
                problems.append(f"IHDR length {n}")
        p += 12 + n
        if t == b"IEND":
            break
    trailing = len(b) - p
    kinds = sorted(set(chunks))
    order_ok = chunks and chunks[0] == "IHDR" and chunks[-1] == "IEND" and set(chunks[1:-1]) == {"IDAT"}
    if not order_ok:
        problems.append(f"chunks {kinds}")
    if trailing:
        problems.append(f"{trailing} bytes after IEND")
    if ihdr != (640, 640, 8, 2, 0, 0, 0):
        problems.append(f"IHDR {ihdr}")
    ok_all &= not problems
    print(f"{os.path.basename(f)}: {'PASS' if not problems else 'FAIL ' + '; '.join(problems)} "
          f"chunks={kinds} IDAT={chunks.count('IDAT')} IHDR={ihdr} trailing={trailing}")
print("ALL PASS" if ok_all else "AUDIT FAILED")
sys.exit(0 if ok_all else 1)
