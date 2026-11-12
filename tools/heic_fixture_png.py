#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Writes the source pattern of the synthetic HEIC test fixture as a PNG (IHDR/IDAT/IEND only).

256 x 192 pixels: a smooth red/green gradient with, in the stored frame, a 64 x 64 block of 4 px red/blue squares at
x 96..159, y 64..127 (the "texture block" the device test masks). No face, no photo, no third-party content.
Usage: python3 -I tools/heic_fixture_png.py <out.png>
"""
import struct
import sys
import zlib

WIDTH, HEIGHT = 256, 192
BLOCK = (96, 64, 160, 128)  # left, top, right, bottom in the stored frame


def pixel(x: int, y: int) -> bytes:
    left, top, right, bottom = BLOCK
    if left <= x < right and top <= y < bottom:
        return bytes((230, 40, 40)) if ((x // 4 + y // 4) & 1) else bytes((20, 20, 200))
    return bytes((255 * x // WIDTH, 255 * y // HEIGHT, 128))


def chunk(kind: bytes, data: bytes) -> bytes:
    body = kind + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


def main() -> int:
    if len(sys.argv) != 2:
        sys.stderr.write("usage: heic_fixture_png.py <out.png>\n")
        return 2
    raw = b"".join(b"\x00" + b"".join(pixel(x, y) for x in range(WIDTH)) for y in range(HEIGHT))
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    with open(sys.argv[1], "wb") as out:
        out.write(png)
    return 0


if __name__ == "__main__":
    sys.exit(main())
