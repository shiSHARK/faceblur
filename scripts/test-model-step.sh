#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Negative and positive controls for the test-model fixtures and the model-leak gates (ADR 0012 "Leak check",
# SEC-MA-003, SEC-006). The library ships no model; the reference model and its derived models are test fixtures.
#   N1  local copy with the pinned size but wrong bytes            -> fetchYunetModel fails "SHA-256 mismatch"
#   N2  local copy with the wrong size                              -> fetchYunetModel fails "size"
#   N3  local copy that does not exist                              -> fetchYunetModel fails "source missing"
#   P1  a verified local copy                                       -> accepted, derived models built and pinned
#   N4a one flipped byte in build/test-models/                      -> fetchYunetModel fails "SHA-256 mismatch"
#   N4b the same, fetch task excluded (-x)                          -> the JVM tests' own pin check fails
#   N5  edited derived model and a stray file in derived/           -> the next run restores the genuine set
#   N6  local build cache enabled                                   -> fetch and derive run every time, never
#                                                                      FROM-CACHE, and store no cache entry
#   N7' model-leak check (scripts/check-model-leak.py) on planted artifacts: the reference model inside a deflated
#       entry of a nested archive, a compiled class holding the model as base64 chunks (the shape of the removed
#       embedding step), UTF-16 base64 (Kotlin/Native string form), the SHA-256 hex, a derived model in a tar.gz,
#       a hex dump of a derived model, .onnx/.ort entry names -> each fails; a clean archive passes; a corrupt
#       archive, a missing fixture and an empty directory are errors (fail closed)
#   N7g G1 (NMS in the graph): a copy inside a jar and its SHA-256 hex alone -> each fails and names G1.onnx; the
#       same hex passes when G1 is left out of the fixture list (the signal comes from G1's own fixture)
#   N8  a derived-model pin changed (G0 and G1), and each deliberately wrong builder -> deriveTestModels fails
#   N9  a reference fixture whose SHA-256 differs from the pin, fetch excluded -> the derive tool refuses it
#   F1  fuzz harness: a planted crash is reported with seed, round and replay arguments (exit 1)
#   F2  fuzz harness: the same seed gives the same result (replay); a short run passes and logs its seed
# The controls N1-N9 need the maintainer's local fixtures (FACEBLUR_LOCAL_FIXTURES; the local merge gate always sets it):
# they run on a COPY of that folder in a temporary directory (the pins are changed in the copy, never in the original),
# and the verified model is copied once at the start and reused; build/test-models/ is restored from the local fixtures
# at the end. Without the folder (public CI) only F1 and F2 run (the fuzz harness on the synthetic model the project
# builds itself) and the line "N1-N9 NOT RUN" says so.
#
# Usage: scripts/test-model-step.sh        Exit codes: 0 all controls behaved as expected, 1 otherwise.
# Needs: a JDK (javac, jar) on PATH for the planted compiled class; python3.
set -u

model_dir=build/test-models
derived_dir=$model_dir/derived
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM
if [ -n "${FACEBLUR_LOCAL_FIXTURES:-}" ]; then
    mode=local
    lf=$work/local-fixtures
    cp -R "$FACEBLUR_LOCAL_FIXTURES" "$lf"
    props=$lf/yunet/yunet.properties
    name=$(sed -n 's/^fileName=//p' "$props")
    size=$(sed -n 's/^size=//p' "$props")
    cp "$props" "$work/props.orig"
    # The copy's manifest must list the changed pins, or the build refuses the copy before the control is reached.
    remanifest() {
        (cd "$lf" && find . -type f ! -name MANIFEST.sha256 | sed 's|^\./||' | sort |
            while read -r f; do shasum -a 256 "$f"; done > MANIFEST.sha256)
    }
    restore() {
        cmp -s "$work/props.orig" "$props" || cp "$work/props.orig" "$props"
        remanifest
    }
else
    mode=public
    lf=""
fi
log="$work/gradle.log"
fail=0
count=0
if [ "$mode" = local ]; then G="./gradlew --console=plain -Pfaceblur.localFixtures=$lf"; else G="./gradlew --console=plain"; fi

pass() { count=$((count + 1)); echo "test-model-step: $1 PASS - $2"; }
failed() {
    echo "test-model-step: $1 FAIL - $2"
    grep -E "What went wrong|YuNet|test-model-tool|FAILED|rror" "$log" | head -5
    fail=1
}

# expect_failure <id> <expected text> <gradle args...>
expect_failure() {
    id=$1
    text=$2
    shift 2
    if $G "$@" > "$log" 2>&1; then
        failed "$id" "the build passed, expected a failure"
    elif grep -q -e "$text" "$log"; then
        pass "$id" "build failed with: $(grep -m1 -e "$text" "$log" | sed 's/^ *//' | cut -c1-150)"
    else
        failed "$id" "the build failed, but not with '$text'"
    fi
}

flip_byte() {
    python3 -I -c 'import sys; p=sys.argv[1]; b=bytearray(open(p,"rb").read()); b[1000]^=0xFF; open(p,"wb").write(b)' "$1"
}

local_ok=""
if [ "$mode" = local ]; then
# 0. A verified copy from the normally configured source (cache, local copy or the pinned URL), used from here on.
if ! $G :faceblur:fetchYunetModel > "$log" 2>&1; then
    failed P0 "the configured model source was not accepted"
    exit 1
fi
cp "$model_dir/$name" "$work/verified.onnx"
local_ok="-Pfaceblur.yunet.localFile=$work/verified.onnx"

rm -rf "$model_dir"
head -c "$size" /dev/zero > "$work/wrong-bytes.onnx"
expect_failure N1 "SHA-256 mismatch" :faceblur:fetchYunetModel "-Pfaceblur.yunet.localFile=$work/wrong-bytes.onnx"
head -c 1000 /dev/zero > "$work/wrong-size.onnx"
expect_failure N2 "size 1000 bytes" :faceblur:fetchYunetModel "-Pfaceblur.yunet.localFile=$work/wrong-size.onnx"
expect_failure N3 "source missing" :faceblur:fetchYunetModel "-Pfaceblur.yunet.localFile=$work/does-not-exist.onnx"
[ ! -e "$model_dir/$name" ] || failed N1-N3 "a rejected source left a model in $model_dir"

if $G :faceblur:deriveTestModels "$local_ok" > "$log" 2>&1 && [ -s "$model_dir/$name" ] \
    && grep -q "test-model-tool: PASS - source and 4 derived models match their pins" "$log"; then
    pass P1 "verified local copy accepted, 4 derived models built and matching their pins"
    mkdir -p "$work/genuine"
    cp "$derived_dir"/*.onnx "$work/genuine/"
else
    failed P1 "the verified copy was not accepted or the derived models did not match their pins"
fi

flip_byte "$model_dir/$name"
expect_failure N4a "SHA-256 mismatch" :faceblur:fetchYunetModel "$local_ok"
expect_failure N4b "theFixturePinEqualsTheBuildPinAndTheLoadedBytes.*FAILED" :faceblur:jvmTest \
    --tests "*PipelineSkeletonTest.theFixturePinEqualsTheBuildPinAndTheLoadedBytes" -x :faceblur:fetchYunetModel "$local_ok"
if grep -q "Task :faceblur:fetchYunetModel" "$log"; then failed N4b "the fetch task ran although excluded"; fi
# N9: the derive tool checks its source against the pin itself, also when the fetch step is skipped.
expect_failure N9 "reference fixture $name: SHA-256 mismatch" :faceblur:deriveTestModels -x :faceblur:fetchYunetModel "$local_ok"
rm -rf "$model_dir"
$G :faceblur:deriveTestModels "$local_ok" > "$log" 2>&1 || failed restore "could not restore the fixtures"

# N5: an edited derived model and a stray file are replaced by the genuine set in the next run.
flip_byte "$derived_dir/G2.onnx"
printf 'stray' > "$derived_dir/stray.onnx"
if $G :faceblur:deriveTestModels "$local_ok" > "$log" 2>&1 && [ ! -e "$derived_dir/stray.onnx" ] \
    && cmp -s "$derived_dir/G2.onnx" "$work/genuine/G2.onnx"; then
    pass N5 "edited derived model replaced by the genuine file, stray file removed"
else
    failed N5 "an edited derived model or a stray file survived"
fi

# N6: a local build cache is enabled; the fixture tasks must run every time and never store or restore an entry.
cat > "$work/cache.init.gradle" <<EOF
settingsEvaluated { s -> s.buildCache { local { directory = new File("$work/cache") } } }
EOF
ok6=1
for _ in 1 2; do
    if ! $G --build-cache -I "$work/cache.init.gradle" :faceblur:deriveTestModels "$local_ok" > "$log" 2>&1; then
        ok6=0
    fi
    if grep -q -E "(fetchYunetModel|deriveTestModels) (FROM-CACHE|UP-TO-DATE)" "$log"; then ok6=0; fi
    grep -q "^> Task :faceblur:deriveTestModels$" "$log" || ok6=0
done
[ -d "$work/cache" ] || ok6=0
if [ "$ok6" -eq 1 ]; then
    pass N6 "fetch and derive executed in both runs with the build cache on (never FROM-CACHE or UP-TO-DATE)"
else
    failed N6 "a fixture task was cached or skipped"
fi

# --- N7': the model-leak check on planted artifacts -----------------------------------------------------------------
fixtures_no_g1="--fixture $model_dir/$name --fixture $derived_dir/G0.onnx --fixture $derived_dir/G2.onnx"
fixtures_no_g1="$fixtures_no_g1 --fixture $derived_dir/G3.onnx"
fixtures="$fixtures_no_g1 --fixture $derived_dir/G1.onnx"
leak() { # leak <id> <expected exit> <target...>
    id=$1
    want=$2
    shift 2
    # shellcheck disable=SC2086 # the fixture options are split on purpose
    python3 -I scripts/check-model-leak.py $fixtures "$@" > "$work/leak.txt" 2>&1
    got=$?
    if [ "$got" -eq "$want" ]; then
        pass "$id" "exit $got: $(grep -m1 -E 'PASS|ERROR|: .*(window|SHA-256|name)' "$work/leak.txt" | sed 's/^ *//' | cut -c1-140)"
    else
        failed "$id" "exit $got, expected $want"
        head -5 "$work/leak.txt"
    fi
}
p="$work/plant"
mkdir -p "$p"
python3 -I - "$p" "$model_dir/$name" "$derived_dir/G2.onnx" "$derived_dir/G3.onnx" <<'PY'
import base64, io, os, sys, tarfile, zipfile
out, ref, g2, g3 = sys.argv[1:]
ref_b = open(ref, "rb").read()
def zbytes(entries):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries:
            z.writestr(name, data)
    return buf.getvalue()
def dirs(*names):
    for n in names:
        os.makedirs(os.path.join(out, n), exist_ok=True)
dirs("clean", "nested", "utf16", "sha", "derived", "hexdump", "names-ort", "names-onnx", "corrupt", "empty")
filler = bytes(range(256)) * 64
# A clean artifact: classes, text and binary filler; must pass.
open(os.path.join(out, "clean", "lib.aar"), "wb").write(zbytes([
    ("classes.jar", zbytes([("a/B.class", filler), ("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")])),
    ("R.txt", b"int id x 0x7f000001\n")]))
# The full reference model renamed, deflated, inside a jar inside an aar.
open(os.path.join(out, "nested", "lib.aar"), "wb").write(zbytes([
    ("classes.jar", zbytes([("io/x/res/data.bin", ref_b), ("a/B.class", filler)]))]))
# Base64 of the model as UTF-16LE (how Kotlin/Native keeps string constants), inside a klib.
open(os.path.join(out, "utf16", "lib.klib"), "wb").write(zbytes([
    ("default/ir/strings.knt", base64.b64encode(ref_b[1:]).decode().encode("utf-16-le"))]))
# Only the SHA-256 of the model, upper case, in a sources jar.
import hashlib
open(os.path.join(out, "sha", "lib-sources.jar"), "wb").write(zbytes([
    ("commonMain/Pin.kt", ("const val P = \"" + hashlib.sha256(ref_b).hexdigest().upper() + "\"\n").encode())]))
# A derived model inside a tar.gz.
buf = io.BytesIO()
with tarfile.open(fileobj=buf, mode="w:gz") as t:
    data = open(g2, "rb").read()
    info = tarfile.TarInfo("pkg/blob")
    info.size = len(data)
    t.addfile(info, io.BytesIO(data))
open(os.path.join(out, "derived", "pkg.tar.gz"), "wb").write(buf.getvalue())
# A hex dump of another derived model in a text file.
open(os.path.join(out, "hexdump", "notes.txt"), "w").write(open(g3, "rb").read().hex())
# Model file names only (random content).
open(os.path.join(out, "names-ort", "lib.zip"), "wb").write(zbytes([("x/Model.ORT", os.urandom(512))]))
open(os.path.join(out, "names-onnx", "detector.onnx"), "wb").write(os.urandom(512))
# A corrupt jar (fail closed).
open(os.path.join(out, "corrupt", "broken.jar"), "wb").write(b"PK\x03\x04" + os.urandom(200))
PY
# A compiled class that holds the model as base64 chunks of 49,152 characters (the shape of the removed embedding
# step), in classes.jar inside an aar.
python3 -I - "$model_dir/$name" "$p/EmbeddedProbe.java" <<'PY'
import base64, sys
b64 = base64.b64encode(open(sys.argv[1], "rb").read()).decode()
chunks = [b64[i:i + 49152] for i in range(0, len(b64), 49152)]
with open(sys.argv[2], "w") as fh:
    fh.write("final class EmbeddedProbe {\n    static String[] chunks() {\n        return new String[] {\n")
    for c in chunks:
        fh.write('            "' + c + '",\n')
    fh.write("        };\n    }\n}\n")
PY
mkdir -p "$p/class/out" "$p/class/aar"
if javac -nowarn -d "$p/class/out" "$p/EmbeddedProbe.java" > "$work/javac.txt" 2>&1 \
    && (cd "$p/class/out" && jar cf ../aar/classes.jar EmbeddedProbe.class) \
    && (cd "$p/class/aar" && jar cf ../lib.aar classes.jar) && rm -rf "$p/class/out" "$p/class/aar"; then
    leak N7-compiled-class-base64-chunks 1 "$p/class"
else
    failed N7-compiled-class-base64-chunks "could not build the planted class (javac/jar on PATH?)"
    head -3 "$work/javac.txt"
fi
leak N7-clean-archive-passes 0 "$p/clean"
leak N7-model-in-deflated-nested-entry 1 "$p/nested"
leak N7-utf16-base64 1 "$p/utf16"
leak N7-sha256-hex 1 "$p/sha"
leak N7-derived-model-in-tar-gz 1 "$p/derived"
leak N7-derived-model-hex-dump 1 "$p/hexdump"
leak N7-ort-entry-name 1 "$p/names-ort"
leak N7-onnx-file-name 1 "$p/names-onnx"
leak N7-corrupt-archive-is-an-error 2 "$p/corrupt"
leak N7-empty-directory-is-an-error 2 "$p/empty"
python3 -I scripts/check-model-leak.py --fixture "$work/does-not-exist.onnx" "$p/clean" > "$work/leak.txt" 2>&1
if [ $? -eq 2 ]; then pass N7-missing-fixture-is-an-error "exit 2"; else failed N7-missing-fixture-is-an-error "not exit 2"; fi

# N7g: G1 (NMS in the graph). A copy inside a jar, and its SHA-256 hex alone, must fail and name G1.onnx; the hex
# must pass without G1's fixture, so the catch comes from G1's own signals (not only the source windows it shares).
python3 -I - "$p" "$derived_dir/G1.onnx" <<'PY'
import hashlib, io, os, sys, zipfile
out, g1 = sys.argv[1:]
g1_b = open(g1, "rb").read()
for d in ("g1-copy", "g1-sha"):
    os.makedirs(os.path.join(out, d), exist_ok=True)
def zbytes(entries):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries:
            z.writestr(name, data)
    return buf.getvalue()
open(os.path.join(out, "g1-copy", "lib.jar"), "wb").write(zbytes([("io/x/res/blob.bin", g1_b)]))
open(os.path.join(out, "g1-sha", "lib-sources.jar"), "wb").write(zbytes([
    ("commonMain/Pin.kt", ("const val P = \"" + hashlib.sha256(g1_b).hexdigest() + "\"\n").encode())]))
PY
leak_g1() { # leak_g1 <id> <expected exit> <expected text or -> <fixture options> <target>
    # shellcheck disable=SC2086 # the fixture options are split on purpose
    python3 -I scripts/check-model-leak.py $4 "$5" > "$work/leak.txt" 2>&1
    got=$?
    if [ "$got" -ne "$2" ]; then
        failed "$1" "exit $got, expected $2"
        head -5 "$work/leak.txt"
    elif [ "$3" != "-" ] && ! grep -q -e "$3" "$work/leak.txt"; then
        failed "$1" "exit $got, but no '$3' in the report"
    elif [ "$3" = "-" ]; then
        pass "$1" "exit $got: $(grep -m1 PASS "$work/leak.txt" | sed 's/^ *//' | cut -c1-140)"
    else
        pass "$1" "exit $got: $(grep -m1 -e "$3" "$work/leak.txt" | sed 's/^ *//' | cut -c1-140)"
    fi
}
leak_g1 N7g-g1-copy-in-jar 1 "G1.onnx: .*window, raw bytes" "$fixtures" "$p/g1-copy"
leak_g1 N7g-g1-sha256-hex 1 "G1.onnx: SHA-256 hex" "$fixtures" "$p/g1-sha"
leak_g1 N7g-g1-sha256-hex-needs-the-g1-fixture 0 - "$fixtures_no_g1" "$p/g1-sha"

# --- N8: derived-model pins -------------------------------------------------------------------------------------------
wrong_g0=$(sed -n 's/^derived.G0.sha256=//p' "$props" | tr '0123456789abcdef' '123456789abcdef0')
sed -E "s/^derived.G0.sha256=.*/derived.G0.sha256=$wrong_g0/" "$work/props.orig" > "$props"
remanifest
expect_failure N8-changed-pin "derived model G0: SHA-256 mismatch" :faceblur:deriveTestModels "$local_ok"
restore
wrong_g1=$(sed -n 's/^derived.G1.sha256=//p' "$props" | tr '0123456789abcdef' '123456789abcdef0')
sed -E "s/^derived.G1.sha256=.*/derived.G1.sha256=$wrong_g1/" "$work/props.orig" > "$props"
remanifest
expect_failure N8-changed-g1-pin "derived model G1: SHA-256 mismatch" :faceblur:deriveTestModels "$local_ok"
restore
expect_failure N8-builder-grid-offset "builder mutant GRID_OFFSET" :faceblur:deriveTestModels "$local_ok" \
    -Pfaceblur.testModels.builderMutant=GRID_OFFSET
expect_failure N8-builder-swapped-columns "builder mutant SWAPPED_COLUMNS" :faceblur:deriveTestModels "$local_ok" \
    -Pfaceblur.testModels.builderMutant=SWAPPED_COLUMNS
if $G :faceblur:deriveTestModels "$local_ok" > "$log" 2>&1 \
    && grep -q "test-model-tool: PASS - source and 4 derived models match their pins" "$log"; then
    pass N8-right "the genuine builder matches every pin"
else
    failed N8-right "the genuine builder was not accepted"
fi

fi
if [ "$mode" = public ]; then
    echo "test-model-step: N1-N9 NOT RUN in public CI - they need the maintainer's local fixtures (the local merge gate runs them)"
fi

# --- F: fuzz harness ------------------------------------------------------------------------------------------------
expect_failure F1-planted-crash-reported "seed 7 round 5 .*PlantedCrash.*replay: --seed 7 --start-round 5 --max-rounds 1" \
    :faceblur:fuzzOnnxPreParse "$local_ok" -Pfaceblur.fuzz.seed=7 -Pfaceblur.fuzz.seconds=30 \
    -Pfaceblur.fuzz.plantCrashRound=5
r1=""
r2=""
for i in 1 2; do
    if $G :faceblur:fuzzOnnxPreParse "$local_ok" -Pfaceblur.fuzz.seed=7 -Pfaceblur.fuzz.seconds=60 \
        -Pfaceblur.fuzz.maxRounds=3000 > "$log" 2>&1; then
        line=$(grep -m1 "test-model-tool: PASS - fuzz seed 7: 3000 rounds" "$log")
        if [ "$i" -eq 1 ]; then r1=$line; else r2=$line; fi
    fi
done
if [ -n "$r1" ] && [ "$r1" = "$r2" ] && grep -q "test-model-tool: fuzz seed 7 (replay: --seed 7)" "$log"; then
    pass F2-replay "seed logged; two runs of 3,000 rounds with seed 7 agree: ${r1#test-model-tool: }"
else
    failed F2-replay "the fuzz run failed, logged no seed, or two runs with the same seed differed"
fi

if [ "$mode" = local ]; then
    $G :faceblur:deriveTestModels "$local_ok" > "$log" 2>&1 || failed restore "final regeneration failed"
fi

if [ "$fail" -ne 0 ]; then
    echo "test-model-step: FAIL"
    exit 1
fi
echo "test-model-step: PASS - $count controls behaved as expected (negative controls rejected, positive accepted)"
