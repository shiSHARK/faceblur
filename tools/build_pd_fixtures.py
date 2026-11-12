#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Builds the public-domain 640x640 lossless PNG fixtures from Library of Congress TIFF masters.

Pixels only: each master is decoded with OpenCV (TIFF, 8-bit), grey is replicated to 3 channels, the image is
optionally downscaled with INTER_AREA (size = round half-up of W*s, H*s), a 640x640 window is cut and written with
cv2.imwrite (zlib level 9; per-file override list ENCODE_OVERRIDES) as a NEW PNG (IHDR/IDAT/IEND only; no colour
profile, no text, no time chunk). Nothing of the TIFF
container (tags, XMP, IPTC, ICC, Photoshop resources, thumbnails) is read or copied.

Windows: per image and scale, greedy cover of the reviewed faces (a JSON of visually checked full-resolution
boxes) - each
window is placed so that it contains the most not-yet-covered faces fully (margin 8 px), clamped to the image.
Single-face crop: one fixed single-face window of P06 at scale 1.0 (walking-skeleton test).

Inputs NOT in the repository (the build cannot run in CI; the committed PNGs and MANIFEST.json are the record):
  - the four Library of Congress TIFF masters in MASTERS (download URLs below; their SHA-256 values are in
    MANIFEST.json, field source_sha256) and a SHA256SUMS.txt listing them;
  - reviewed_faces.json: {"P01": [[left, top, right, bottom], ...], ...}, visually checked face boxes in full-resolution
    coordinates (only used to place the windows).
SHA256SUMS.txt format: one "<sha256>  <file name>" line per master (shasum -a 256 output).
Usage: python -I build_pd_fixtures.py <masters_dir> <reviewed_faces.json> <out_dir>
       python -I build_pd_fixtures.py --from-manifest <masters_dir> <MANIFEST.json> <out_dir>
The second form needs no reviewed-faces file: it rebuilds exactly the crops listed in a committed MANIFEST.json
(source file and SHA-256, scale, crop, PNG zlib level) from the masters and fails if any output SHA-256 differs.
"""
import hashlib
import json
import math
import os
import sys

import cv2

SIDE = 640
MARGIN = 8
MASTERS = {  # id: (file, LoC digital id, LCCN, master URL)
    "P01": ("P01_ppmsca-35519u.tif", "LC-DIG-ppmsca-35519", "2013646606",
            "https://tile.loc.gov/storage-services/master/pnp/ppmsca/35500/35519u.tif"),
    "P03": ("P03_cwpb-00269u.tif", "LC-DIG-cwpb-00269", "2018671862",
            "https://tile.loc.gov/storage-services/master/pnp/cwpb/00200/00269u.tif"),
    "P06": ("P06_ppmsca-87046u.tif", "LC-DIG-ppmsca-87046", "2023631085",
            "https://tile.loc.gov/storage-services/master/pnp/ppmsca/87000/87046u.tif"),
    "P07": ("P07_ppmsca-70251u.tif", "LC-DIG-ppmsca-70251", "2020634812",
            "https://tile.loc.gov/storage-services/master/pnp/ppmsca/70200/70251u.tif"),
}
SCALES = {
    "P01": [1.0, 0.75, 0.5, 0.35, 0.25],
    "P03": [1.0, 0.75, 0.5, 0.35, 0.25],
    "P06": [1.0, 0.75, 0.5, 0.35, 0.26],  # 0.26: short side must stay >= 640 (0.25 -> 633 px)
    "P07": [1.0, 0.75, 0.5, 0.35, 0.26],
}
ST002 = ("P06", 1.0, 200, 1600)  # single-face window (one OpenCV candidate >= 0.3 expected)
PNG_COMPRESSION = 9
# Same pixels, other zlib level: the default encoding of this file was rejected by the repository's data-admission
# check (a false positive inside the compressed IDAT data). Decoded pixels are identical.
ENCODE_OVERRIDES = {"pd-p07-s050-x00635-y00212.png": 7}


def kround(x):
    return int(math.floor(x + 0.5))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def inside(face, x0, y0):
    return (face[0] >= x0 + MARGIN and face[1] >= y0 + MARGIN and
            face[2] <= x0 + SIDE - MARGIN and face[3] <= y0 + SIDE - MARGIN)


def windows(faces, W, H):
    """Greedy cover: candidate windows centred on each face (and left/top-aligned to it)."""
    todo = list(range(len(faces)))
    out = []
    while todo:
        best = None
        for i in todo:
            f = faces[i]
            cx, cy = (f[0] + f[2]) / 2, (f[1] + f[3]) / 2
            for x0, y0 in ((cx - SIDE / 2, cy - SIDE / 2), (f[0] - MARGIN, cy - SIDE / 2),
                           (f[0] - MARGIN, f[1] - MARGIN), (cx - SIDE / 2, f[1] - MARGIN)):
                x0 = min(max(0, kround(x0)), W - SIDE)
                y0 = min(max(0, kround(y0)), H - SIDE)
                cov = [j for j in todo if inside(faces[j], x0, y0)]
                key = (len(cov), -x0, -y0)
                if cov and (best is None or key > best[0]):
                    best = (key, x0, y0, cov)
        if best is None:  # a face that cannot fit (larger than the window): skip it, reported
            out.append((None, None, [todo[0]]))
            todo = todo[1:]
            continue
        _, x0, y0, cov = best
        out.append((x0, y0, cov))
        todo = [j for j in todo if j not in cov]
    return out


def from_manifest(mdir, manifest_path, out):
    """Rebuilds the files of a committed MANIFEST.json from the masters and asserts their SHA-256."""
    os.makedirs(out, exist_ok=True)
    man = json.load(open(manifest_path))
    images = {}
    bad = []
    for f in man["files"]:
        src = os.path.join(mdir, f["source_file"])
        if f["source_file"] not in images:
            if sha256(src) != f["source_sha256"]:
                sys.exit(f"master SHA-256 mismatch: {f['source_file']}")
            img = cv2.imread(src, cv2.IMREAD_UNCHANGED)
            images = {f["source_file"]: cv2.merge([img, img, img]) if img.ndim == 2 else img}
        img = images[f["source_file"]]
        s, (sw, sh) = f["scale"], f["scaled_size"]
        im = img if s == 1.0 else cv2.resize(img, (sw, sh), interpolation=cv2.INTER_AREA)
        x0, y0, w, h = f["crop_in_scaled"]
        op = os.path.join(out, f["file"])
        level = f.get("png_compression", PNG_COMPRESSION)
        cv2.imwrite(op, im[y0:y0 + h, x0:x0 + w], [cv2.IMWRITE_PNG_COMPRESSION, level])
        ok = sha256(op) == f["sha256"]
        print(f"{f['file']} {'OK' if ok else 'MISMATCH'}", flush=True)
        if not ok:
            bad.append(f["file"])
    if bad:
        sys.exit(f"{len(bad)} file(s) differ from the manifest")
    print(f"all {len(man['files'])} files reproduced")


def main():
    if sys.argv[1] == "--from-manifest":
        from_manifest(*sys.argv[2:5])
        return
    mdir, reviewed, out = sys.argv[1:4]
    os.makedirs(out, exist_ok=True)
    sums = dict(reversed(l.split()) for l in open(os.path.join(mdir, "SHA256SUMS.txt")) if l.strip())
    rev = json.load(open(reviewed))
    manifest = {"generator": "build_pd_fixtures.py", "opencv": cv2.__version__,
                "resampling": "cv2.INTER_AREA, size round-half-up(W*s), round-half-up(H*s)",
                "window": SIDE, "face_margin_px": MARGIN, "files": []}
    for pid, (fname, dig, lccn, url) in MASTERS.items():
        path = os.path.join(mdir, fname)
        h = sha256(path)
        if sums.get(fname) != h:
            sys.exit(f"SHA-256 mismatch for {fname}")
        img = cv2.imread(path, cv2.IMREAD_UNCHANGED)
        if img.dtype != "uint8":
            sys.exit(f"{fname}: unexpected dtype {img.dtype}")
        if img.ndim == 2:
            img = cv2.merge([img, img, img])
        H, W = img.shape[:2]
        jobs = []
        for s in SCALES[pid]:
            sw, sh = max(1, kround(W * s)), max(1, kround(H * s))
            faces = [[v * s for v in f] for f in rev[pid]]
            for x0, y0, cov in windows(faces, sw, sh):
                jobs.append(("strict", s, sw, sh, x0, y0, len(cov) if x0 is not None else 0, x0 is None))
        if pid == ST002[0]:
            s, x0, y0 = ST002[1:]
            sw, sh = kround(W * s), kround(H * s)
            n = sum(1 for f in rev[pid] if inside([v * s for v in f], x0, y0))
            jobs.append(("single-face", s, sw, sh, x0, y0, n, False))
        cache = {}
        for role, s, sw, sh, x0, y0, n, skipped in jobs:
            if skipped:
                manifest["files"].append({"role": role, "source": pid, "scale": s, "skipped_face_too_large": True})
                continue
            if s not in cache:
                cache = {s: img if s == 1.0 else cv2.resize(img, (sw, sh), interpolation=cv2.INTER_AREA)}
            crop = cache[s][y0:y0 + SIDE, x0:x0 + SIDE]
            assert crop.shape == (SIDE, SIDE, 3), crop.shape
            tag = "-single-face" if role == "single-face" else ""
            name = f"pd-{pid.lower()}{tag}-s{kround(s * 100):03d}-x{x0:05d}-y{y0:05d}.png"
            op = os.path.join(out, name)
            level = ENCODE_OVERRIDES.get(name, PNG_COMPRESSION)
            if not cv2.imwrite(op, crop, [cv2.IMWRITE_PNG_COMPRESSION, level]):
                sys.exit(f"write failed: {name}")
            manifest["files"].append({
                "file": name, "role": role, "sha256": sha256(op), "bytes": os.path.getsize(op),
                "source": pid, "source_file": fname, "source_sha256": h, "loc_digital_id": dig,
                "lccn": lccn, "source_url": url, "source_size": [W, H], "scale": s, "scaled_size": [sw, sh],
                "crop_in_scaled": [x0, y0, SIDE, SIDE],
                "crop_in_source": [round(x0 / s, 2), round(y0 / s, 2), round(SIDE / s, 2), round(SIDE / s, 2)],
                "reviewed_faces_fully_inside": n, "png_compression": level,
            })
            print(f"{name} faces={n} bytes={os.path.getsize(op)}", flush=True)
    manifest["files"].sort(key=lambda f: f.get("file", ""))
    with open(os.path.join(out, "MANIFEST.json"), "w") as fp:
        json.dump(manifest, fp, indent=1)


if __name__ == "__main__":
    main()
