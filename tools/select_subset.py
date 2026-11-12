#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Selects the committed compact subset from a full fixture build and copies it byte-identically.

Rule (yield only; never uses any library or benchmark result):
  1. for every (source, scale) of the strict set: the crop with the most reviewed faces fully inside
     (MANIFEST.json), ties -> most OpenCV boxes >= 0.5 (yunet_reference.json of the full set) -> file name;
  2. plus the single-face crop;
  3. plus every crop holding a reference candidate in [0.495, 0.505) (the borderline band of ADR 0009).
Writes the selected PNGs and a subset MANIFEST.json into <out_dir>.
Input NOT in the repository: <full_dir> = a full build of build_pd_fixtures.py plus its yunet_reference.json.
Usage: python3 -I select_subset.py <full_dir> <out_dir>
"""
import hashlib
import json
import os
import shutil
import sys

full, out = sys.argv[1:3]
os.makedirs(out, exist_ok=True)
man = json.load(open(os.path.join(full, "MANIFEST.json")))
ref = {i["file"]: i for i in json.load(open(os.path.join(full, "yunet_reference.json")))["images"]}
files = [f for f in man["files"] if "file" in f]
chosen = {}
groups = {}
for f in files:
    if f["role"] == "strict":
        groups.setdefault((f["source"], f["scale"]), []).append(f)
for key in sorted(groups):
    best = sorted(groups[key], key=lambda f: (-f["reviewed_faces_fully_inside"],
                                              -ref[f["file"]]["boxesAtThreshold"], f["file"]))[0]
    chosen[best["file"]] = "best yield for " + f"{key[0]} scale {key[1]}"
for f in files:
    if f["role"] == "single-face":
        chosen[f["file"]] = "single-face crop (walking-skeleton test)"
    if any(0.495 <= c["score"] < 0.505 for c in ref[f["file"]]["faces"]):
        chosen[f["file"]] = chosen.get(f["file"], "") + ("; " if f["file"] in chosen else "") + \
            "borderline candidate in [0.495, 0.505)"
sub = []
for f in files:
    if f["file"] in chosen:
        src = os.path.join(full, f["file"])
        dst = os.path.join(out, f["file"])
        shutil.copyfile(src, dst)
        h = hashlib.sha256(open(dst, "rb").read()).hexdigest()
        if h != f["sha256"]:
            sys.exit(f"copy differs: {f['file']}")
        sub.append(dict(f, selected_because=chosen[f["file"]]))
m2 = {k: v for k, v in man.items() if k != "files"}
m2["subset_selector"] = "select_subset.py"
m2["subset_rule"] = ("Compact subset: per (source, scale) the crop with the most reviewed faces fully inside "
                     "(ties: most OpenCV boxes >= 0.5, then file name); plus the single-face crop; "
                     "plus every crop with "
                     "an OpenCV candidate in [0.495, 0.505). Selection uses only the manifest and the OpenCV "
                     "reference, never a library or bench result.")
m2["comparison_rule"] = ("The reference test compares library and OpenCV boxes and landmarks clamped to the image "
                         "bounds ([0, width] x [0, height]) identically on both sides, as in ADR 0009; "
                         "yunet_reference.json stores the raw OpenCV values.")
m2["files"] = sorted(sub, key=lambda f: f["file"])
with open(os.path.join(out, "MANIFEST.json"), "w") as fp:
    json.dump(m2, fp, indent=1)
for f in m2["files"]:
    print(f"{f['file']}  {f['selected_because']}")
print(f"selected {len(sub)} files, {sum(f['bytes'] for f in sub) / 1e6:.2f} MB")
