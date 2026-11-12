#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reference data for the 640x640 lossless fixtures (OpenCV cv2.FaceDetectorYN, the reference decoder; ADR 0009).

Every PNG must be exactly 640x640, so OpenCV's input size equals the library's letterbox (no resampling, no
padding on either side). cv2.FaceDetectorYN runs with input size 640x640, score threshold = candidate floor 0.3,
NMS 0.3, top_k 5000; every candidate down to 0.3 is recorded (score, box, 5 landmarks). The script fails if an image
reaches the top_k cap or is not 640x640. Margin-subset membership (no candidate in [0.495, 0.505)) is computed from
this JSON only. Records OpenCV/numpy versions, the model SHA-256 and the comparison rule (boxes clamped to the image).

If <fixtures_dir> holds sub-folders with PNGs (for example pd/ under testdata/), every sub-folder is
processed in name order and "file" is the path relative to <fixtures_dir> (for example "pd/<name>.png"); otherwise
"file" is the bare file name. Inputs: the PNGs (committed) and the reference model (not committed; SHA-256 checked).
Usage: python -I yunet_reference_pd.py <fixtures_dir> <model.onnx> <out.json>
"""
import glob
import hashlib
import json
import os
import sys

import cv2
import numpy

SIDE = 640
FLOOR = 0.3
THRESHOLD = 0.5
NMS = 0.3
TOP_K = 5000
DELTA = 0.005
EXPECTED_MODEL_SHA256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4"


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def main():
    fdir, model, out_json = sys.argv[1:4]
    msha = sha256(model)
    if msha != EXPECTED_MODEL_SHA256:
        sys.exit(f"model SHA-256 mismatch: {msha}")
    det = cv2.FaceDetectorYN.create(model, "", (SIDE, SIDE), FLOOR, NMS, TOP_K)
    out = {"model": os.path.basename(model), "modelSha256": msha, "opencv": cv2.__version__,
           "numpy": numpy.__version__, "inputSide": SIDE, "candidateFloor": FLOOR, "scoreThreshold": THRESHOLD,
           "nmsThreshold": NMS, "topK": TOP_K, "marginDelta": DELTA,
           "comparisonRule": ("Boxes and landmarks are compared clamped to the image bounds ([0, width] x "
                              "[0, height]) identically on both sides, as in ADR 0009; this file stores the raw "
                              "OpenCV values; "
                              "crossesBorder marks candidates whose raw box extends outside the image"),
           "images": []}
    subdirs = sorted(d for d in os.listdir(fdir) if glob.glob(os.path.join(fdir, d, "*.png")))
    files = ([(os.path.join(d, os.path.basename(p)), p) for d in subdirs
              for p in sorted(glob.glob(os.path.join(fdir, d, "*.png")))]
             if subdirs else [(os.path.basename(p), p) for p in sorted(glob.glob(os.path.join(fdir, "*.png")))])
    for rel, f in files:
        img = cv2.imread(f, cv2.IMREAD_COLOR)
        h, w = img.shape[:2]
        if (w, h) != (SIDE, SIDE):
            sys.exit(f"{rel}: {w}x{h}, expected {SIDE}x{SIDE}")
        _, faces = det.detect(img)
        faces = [] if faces is None else faces.tolist()
        if len(faces) >= TOP_K:
            sys.exit(f"{rel}: top_k cap reached")
        faces.sort(key=lambda r: -r[14])
        scores = [r[14] for r in faces]
        out["images"].append({
            "file": rel, "sha256": sha256(f), "width": w, "height": h,
            "inputWidth": SIDE, "inputHeight": SIDE, "candidateCount": len(faces),
            "boxesAtThreshold": sum(1 for s in scores if s >= THRESHOLD),
            "inMarginSubset": not any(THRESHOLD - DELTA <= s < THRESHOLD + DELTA for s in scores),
            "faces": [{"x": r[0], "y": r[1], "w": r[2], "h": r[3], "score": r[14], "landmarks": r[4:14],
                       "crossesBorder": r[0] < 0 or r[1] < 0 or r[0] + r[2] > w or r[1] + r[3] > h}
                      for r in faces],
        })
    with open(out_json, "w") as fp:
        json.dump(out, fp, indent=1)
    ims = out["images"]
    print(f"images={len(ims)} candidates={sum(i['candidateCount'] for i in ims)} "
          f"boxes>=0.5={sum(i['boxesAtThreshold'] for i in ims)} "
          f"marginSubset images={sum(i['inMarginSubset'] for i in ims)} "
          f"boxes>=0.5 in margin subset={sum(i['boxesAtThreshold'] for i in ims if i['inMarginSubset'])} "
          f"candidates in [0.45,0.55)={sum(1 for i in ims for c in i['faces'] if 0.45 <= c['score'] < 0.55)} "
          f"in [0.495,0.505)={sum(1 for i in ims for c in i['faces'] if 0.495 <= c['score'] < 0.505)} "
          f"opencv={cv2.__version__} model={msha[:12]}")


if __name__ == "__main__":
    main()
