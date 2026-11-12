#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Checks a local Maven repository produced by `./gradlew publishAllPublicationsToLocalRepoRepository`:
#   - no jvm publication (ADR 0002 decision 11: the jvm variant is not published in 0.x);
#   - the root module metadata (faceblur-*.module) lists no jvm variant and the variants of android, iosArm64 and
#     iosSimulatorArm64;
#   - no model file (*.onnx) as a separate artifact (the library ships no model, ADR 0012; the content of every
#     artifact is checked by scripts/check-model-leak.py);
#   - the manifest of the published Android AAR (ADR 0007, amended): FaceBlur declares no permission and no component
#     of its own. If the AAR's POM depends on onnxruntime-android, the manifest removes exactly ONE entry, ONNX
#     Runtime's telemetry starter, by its own name: `<provider android:name="ai.onnxruntime.TelemetryInitializer"
#     tools:node="remove">` (attributes android:name, android:authorities, tools:node only; no tools:selector, no
#     other tools: attribute anywhere, no other element than uses-sdk and application). Without that dependency the
#     application element holds nothing. The manifest of that onnxruntime-android AAR is re-read too (from the Gradle
#     cache, or FACEBLUR_ORT_AAR): it must still declare the starter as a standalone provider and no other component,
#     and no permission other than INTERNET and ACCESS_NETWORK_STATE, so an ONNX Runtime update that adds or moves a
#     component fails here instead of slipping past the removal. The merged result in an app is checked separately
#     (scripts/check-consumer-manifest.sh).
# Usage: scripts/check-publication.sh [repo dir]   (default build/local-maven)
# Environment (optional): FACEBLUR_ORT_AAR = the onnxruntime-android AAR to re-read (default: the Gradle cache under
#                         GRADLE_USER_HOME or ~/.gradle, version from the POM)
# Exit codes: 0 ok, 1 a check failed, 2 usage/IO error.
set -eu
repo=${1:-build/local-maven}
base="$repo/io/github/shishark"
[ -d "$base/faceblur" ] || { echo "check-publication: ERROR - no publication under $base/faceblur" >&2; exit 2; }
exec python3 -I - "$base" <<'PY'
import glob, json, os, sys, zipfile
import xml.etree.ElementTree as ET
base = sys.argv[1]
bad = []
if glob.glob(os.path.join(base, "faceblur-jvm*")):
    bad.append("a faceblur-jvm publication exists")
modules = [m for m in glob.glob(os.path.join(base, "faceblur", "*", "*.module"))]
if not modules:
    print("check-publication: ERROR - no root .module file", file=sys.stderr)
    sys.exit(2)
for m in modules:
    with open(m, encoding="utf-8") as fh:
        variants = json.load(fh).get("variants", [])
    platforms = {v.get("attributes", {}).get("org.jetbrains.kotlin.platform.type") for v in variants}
    names = " ".join(v.get("name", "") for v in variants)
    if "jvm" in platforms:
        bad.append(f"{os.path.basename(m)} lists a jvm variant")
    for need in ("android", "iosArm64", "iosSimulatorArm64"):
        if need not in names:
            bad.append(f"{os.path.basename(m)} lacks the {need} variants")
if glob.glob(os.path.join(base, "**", "*.onnx"), recursive=True):
    bad.append("a .onnx file is published")

# --- Android AAR manifest -------------------------------------------------------------------------------------------
A = "{http://schemas.android.com/apk/res/android}"
T = "{http://schemas.android.com/tools}"
STARTER = "ai.onnxruntime.TelemetryInitializer"
COMPONENTS = ("activity", "activity-alias", "service", "receiver", "provider")
ORT_PERMISSIONS = {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"}


def manifest_of(aar):
    with zipfile.ZipFile(aar) as z:
        return ET.fromstring(z.read("AndroidManifest.xml"))


def ort_dependency(pom):
    """Version of onnxruntime-android in the POM's dependencies, or None."""
    ns = "{http://maven.apache.org/POM/4.0.0}"
    root = ET.parse(pom).getroot()
    for dep in root.iter(f"{ns}dependency"):
        if (dep.findtext(f"{ns}groupId"), dep.findtext(f"{ns}artifactId")) == \
                ("com.microsoft.onnxruntime", "onnxruntime-android"):
            return dep.findtext(f"{ns}version") or "?"
    return None


def check_library_manifest(root, ort, label):
    for el in root.iter():
        for k in el.attrib:
            if k.startswith(T) and not (el.tag == "provider" and k == f"{T}node"):
                bad.append(f"{label}: tools attribute {k[len(T):]} on <{el.tag}> (only the starter removal may use tools:node)")
    for child in root:
        if child.tag not in ("uses-sdk", "application"):
            bad.append(f"{label}: <{child.tag}> declared (no permission, feature, query or other element allowed)")
    apps = root.findall("application")
    if len(apps) > 1:
        bad.append(f"{label}: more than one <application>")
    children = [c for a in apps for c in a]
    for a in apps:
        if a.attrib:
            bad.append(f"{label}: <application> has attributes ({', '.join(sorted(a.attrib))})")
    if ort is None:
        if children:
            bad.append(f"{label}: <application> holds {len(children)} element(s) although onnxruntime-android is not a dependency")
        return
    removal = [c for c in children if c.tag == "provider" and c.get(f"{A}name") == STARTER]
    others = [c for c in children if c not in removal]
    for c in others:
        bad.append(f"{label}: <{c.tag} {c.get(f'{A}name', '')}> declared (FaceBlur declares no component)")
    if len(removal) != 1:
        bad.append(f"{label}: {len(removal)} entries for {STARTER}, exactly one removal required")
        return
    p = removal[0]
    if p.get(f"{T}node") != "remove":
        bad.append(f"{label}: the {STARTER} entry is not tools:node=\"remove\"")
    extra = set(p.attrib) - {f"{A}name", f"{A}authorities", f"{T}node"}
    if extra:
        bad.append(f"{label}: the starter removal carries other attributes ({len(extra)})")
    if len(p):
        bad.append(f"{label}: the starter removal has child elements")


def check_ort_manifest(root, label):
    perms = {e.get(f"{A}name") for e in root if e.tag.startswith("uses-permission")}
    if perms - ORT_PERMISSIONS:
        bad.append(f"{label}: permissions beyond INTERNET/ACCESS_NETWORK_STATE: {sorted(perms - ORT_PERMISSIONS)}")
    comps = [(e.tag, e.get(f"{A}name")) for a in root.findall("application") for e in a if e.tag in COMPONENTS]
    if comps != [("provider", STARTER)]:
        bad.append(f"{label}: components {comps}; expected exactly the standalone provider {STARTER} "
                   "(re-review the library manifest removal for this ONNX Runtime version)")
    meta = [e for a in root.findall("application") for e in a.iter("meta-data")]
    if meta:
        bad.append(f"{label}: {len(meta)} <meta-data> entries (e.g. androidx.startup); the removal by name may not fit")


aars = sorted(a for a in glob.glob(os.path.join(base, "faceblur-android", "*", "faceblur-android-*.aar"))
              if not a.endswith(("-sources.aar", "-javadoc.aar")))
if not aars:
    bad.append("no faceblur-android AAR published")
checked = []
for aar in aars:
    label = os.path.basename(aar)
    pom = aar[:-len(".aar")] + ".pom"
    if not os.path.isfile(pom):
        bad.append(f"{label}: no POM next to the AAR")
        continue
    try:
        ort = ort_dependency(pom)
        root = manifest_of(aar)
    except (OSError, KeyError, zipfile.BadZipFile, ET.ParseError) as e:
        bad.append(f"{label}: AAR or POM unreadable ({type(e).__name__})")
        continue
    check_library_manifest(root, ort, label)
    if ort is not None:
        given = os.environ.get("FACEBLUR_ORT_AAR")
        if given:
            ort_aars = [given]
        else:
            home = os.environ.get("GRADLE_USER_HOME") or os.path.join(os.path.expanduser("~"), ".gradle")
            ort_aars = glob.glob(os.path.join(home, "caches", "modules-2", "files-2.1", "com.microsoft.onnxruntime",
                                              "onnxruntime-android", ort, "*", f"onnxruntime-android-{ort}.aar"))
        if not ort_aars:
            bad.append(f"onnxruntime-android {ort} AAR not found to re-read its manifest (run the build first)")
        for o in ort_aars:
            try:
                check_ort_manifest(manifest_of(o), f"onnxruntime-android-{ort}.aar")
            except (OSError, KeyError, zipfile.BadZipFile, ET.ParseError) as e:
                bad.append(f"onnxruntime-android {ort} AAR unreadable ({type(e).__name__})")
    checked.append(f"{label} ({'removes only the ONNX Runtime starter' if ort else 'declares nothing'})")

if bad:
    for b in bad:
        print(f"check-publication: FAIL - {b}", file=sys.stderr)
    sys.exit(1)
print(f"check-publication: PASS - {len(modules)} root module file(s): android + iOS variants, no jvm, no model file; "
      f"AAR manifest: {'; '.join(checked)}")
PY
