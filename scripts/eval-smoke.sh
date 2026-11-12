#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Smoke run of the faceblur-eval Gradle task (:faceblur:faceblurEval) on the maintainer's local fixtures (local merge
# gate only; FACEBLUR_LOCAL_FIXTURES must name the folder, scripts/ci-steps.sh reports the step as NOT RUN in public CI).
#
# Inputs: the pinned reference model fixture (build/test-models/) and the local images (build/local-fixtures/images/),
# both copied and hash-checked by the build, a ground truth built from the local OpenCV reference boxes
# (build/local-fixtures/images/reference.json, faces with score >= 0.7) and a profile with the pinned SHA-256. Work
# files go to build/eval-smoke/ (git-ignored).
#
# Checks: the task runs with ORT_DISABLE_TELEMETRY removed from the caller's environment (so the task itself must set
# it, or the tool refuses to start), exits 0, prints the contract report and the totals, and prints no fixture file
# name (names are off by default). No figures are checked here: the metrics have their own unit and CLI tests.
#
# Usage: scripts/eval-smoke.sh   (from the repository root; extra Gradle options may follow, e.g. --offline)
set -eu

[ -n "${FACEBLUR_LOCAL_FIXTURES:-}" ] && [ -f "$FACEBLUR_LOCAL_FIXTURES/MANIFEST.sha256" ] || {
  echo "eval-smoke: FAIL: FACEBLUR_LOCAL_FIXTURES must name the local fixtures folder"
  exit 1
}
# Verified copies of the local fixtures (the build fails when a file is missing or differs from the manifest).
./gradlew --console=plain -q :faceblur:fetchYunetModel "$@" || { echo "eval-smoke: FAIL: fixture step"; exit 1; }

work=build/eval-smoke
rm -rf "$work"
mkdir -p "$work"

python3 -I - "$work" <<'EOF'
import json, sys
work = sys.argv[1]
ref = json.load(open("build/local-fixtures/images/reference.json", encoding="utf-8"))
images = []
for im in ref["images"]:
    if not im["file"].startswith("images/"):
        continue
    faces = [{k: f[k] for k in ("x", "y", "w", "h")} for f in im["faces"] if f["score"] >= 0.7]
    images.append({"file": im["file"], "width": im["width"], "height": im["height"], "faces": faces})
if not images:
    sys.exit("eval-smoke: no local image entries in the reference file")
json.dump({"format": "faceblur-gt", "version": 1, "images": images},
          open(f"{work}/truth.json", "w", encoding="utf-8"), indent=1)
pin = dict(l.split("=", 1) for l in open("build/local-fixtures/yunet/yunet.properties", encoding="utf-8").read().splitlines()
           if "=" in l and not l.startswith("#"))
json.dump({"format": "YuNetLayout", "inputWidth": 640, "inputHeight": 640, "scoreThreshold": 0.5,
           "expectedSha256": pin["sha256"].strip()}, open(f"{work}/profile.json", "w", encoding="utf-8"))
open(f"{work}/model-file-name.txt", "w", encoding="utf-8").write(pin["fileName"].strip())
EOF

model="build/test-models/$(cat "$work/model-file-name.txt")"
status=0
env -u ORT_DISABLE_TELEMETRY ./gradlew --console=plain "$@" :faceblur:fetchYunetModel :faceblur:faceblurEval \
  --args="--model $model --profile $work/profile.json --images build/local-fixtures --truth $work/truth.json --thresholds 0.4,0.6 --json $work/totals.json" \
  >"$work/output.txt" 2>&1 || status=$?

fail=0
if [ "$status" -ne 0 ]; then
  echo "eval-smoke: FAIL: the task exited with $status"
  fail=1
fi
if grep -q "set ORT_DISABLE_TELEMETRY=1" "$work/output.txt"; then
  echo "eval-smoke: FAIL: the task does not set ORT_DISABLE_TELEMETRY"
  fail=1
fi
for marker in "Contract report" "runtime            ONNX Runtime" "threshold  protected"; do
  if ! grep -q "$marker" "$work/output.txt"; then
    echo "eval-smoke: FAIL: '$marker' missing from the output"
    fail=1
  fi
done
# The totals cover every listed image at each threshold (the two requested ones plus the profile's), none failed.
if ! python3 -I - "$work" <<'PY'
import json, sys
work = sys.argv[1]
n = len(json.load(open(f"{work}/truth.json", encoding="utf-8"))["images"])
t = json.load(open(f"{work}/totals.json", encoding="utf-8"))["thresholds"]
ok = [x["threshold"] for x in t] == [0.4, 0.5, 0.6] and all(x["images"] == n and x["failedImages"] == 0 for x in t)
sys.exit(0 if ok else 1)
PY
then
  echo "eval-smoke: FAIL: the totals do not cover every photo at every threshold without errors"
  fail=1
fi
if grep -q "face-0" "$work/output.txt" "$work/totals.json" 2>/dev/null; then
  echo "eval-smoke: FAIL: a fixture file name appears in the output"
  fail=1
fi
if [ "$fail" -ne 0 ]; then
  sed -n '1,200p' "$work/output.txt"
  exit 1
fi
echo "eval-smoke: PASS (task exit 0, telemetry variable set by the task, totals written, no file names printed)"
