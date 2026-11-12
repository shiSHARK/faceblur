#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# The checks of the CI jobs, one named step each. .github/workflows/ci.yml runs every step with
# `sh scripts/ci-steps.sh <step>` and scripts/ci-local.sh runs the same steps in the same order, so the CI checks and
# the local merge gate cannot drift apart. Tool installs, caches, uploads and the path filters stay in the workflow.
#
# Usage: scripts/ci-steps.sh <step>            run one step (exit code of the check)
#        scripts/ci-steps.sh --list <job>      print the steps of a job in order (gates | build | verification)
# Environment: REF and BEFORE (CI: github.ref and github.event.before) choose the diff base of the range checks:
# the previous main tip on a push to main, otherwise the merge base with origin/main. FACEBLUR_GITLEAKS, OPENGREP and
# OSV_SCANNER name the scanners (data admission, SAST, gate self-tests, OSV); a missing scanner fails its step.
# FACEBLUR_LOCAL_FIXTURES names the maintainer's local fixtures folder (reference test model and test images;
# outside every repository). The local merge gate ALWAYS sets it and runs every step in full. GitHub Actions (or
# FACEBLUR_PUBLIC_CI=1 for a public-CI-like local run) has no such folder: the steps that need it print "NOT RUN" with
# the reason and the tests that need it are excluded by name (config/local-fixture-tests.txt). Neither set = error.
set -eu

GATES="data-admission dco spdx licence-index api-wording wording internal-ids internal-figures sast write-sites dependency-verification
osv-build-tooling gate-self-tests ort-telemetry ort-options ci-step-list"
BUILD="build-main publish-local publication-contents consumer-manifest guide-snippets osv-runtime-gate licence-notices runtime-deps test-results local-fixture-tests large-heap-switch eval-smoke
privacy-crosscheck changed-lines-coverage test-model-controls artifact-paths model-leak reports-paths"
VERIFICATION="verification-check verification-results verification-reports-paths"

# The Gradle tasks of the build job (one list for CI and the local gate).
BUILD_TASKS="jvmTest testAndroidHostTest checkKotlinAbi lint ktlintCheck koverVerify
koverXmlReport compileKotlinIosArm64 compileKotlinIosSimulatorArm64 dokkaGeneratePublicationHtml
:samples:android:check :samples:android:assembleRelease"

# Only with the local fixtures (build job, ahead of the other tasks): the derived test models and their pins.
LOCAL_BUILD_TASKS=":faceblur:deriveTestModels"

ZERO=0000000000000000000000000000000000000000

diff_base() {
    if [ "${REF:-}" = "refs/heads/main" ] && [ -n "${BEFORE:-}" ] && [ "$BEFORE" != "$ZERO" ] &&
        git cat-file -e "$BEFORE^{commit}" 2>/dev/null; then
        echo "$BEFORE"
    else
        git merge-base origin/main HEAD
    fi
}

# fixtures_mode: "local" (FACEBLUR_LOCAL_FIXTURES names a folder with MANIFEST.sha256) or "public" (GitHub Actions, or
# FACEBLUR_PUBLIC_CI=1 without the variable). Anything else is an error: a gate run must not lose its local checks silently.
fixtures_mode() {
    if [ -n "${FACEBLUR_LOCAL_FIXTURES:-}" ]; then
        if [ ! -f "$FACEBLUR_LOCAL_FIXTURES/MANIFEST.sha256" ]; then
            echo "::error::FACEBLUR_LOCAL_FIXTURES does not name a local fixtures folder (MANIFEST.sha256 missing)" >&2
            exit 2
        fi
        echo local
    elif [ "${GITHUB_ACTIONS:-}" = true ] || [ "${FACEBLUR_PUBLIC_CI:-}" = 1 ]; then
        echo public
    else
        echo "::error::FACEBLUR_LOCAL_FIXTURES is not set and this is not public CI (GITHUB_ACTIONS, FACEBLUR_PUBLIC_CI=1)" >&2
        exit 2
    fi
}

# not_run <step> <what>: the explicit public-CI line for a step that needs the local fixtures.
not_run() {
    echo "ci-steps: $1 NOT RUN in public CI - $2 (the maintainer's local merge gate runs it in full)"
}

paths_of_existing() { # paths_of_existing <dir...>: prints the directories that exist
    for d in "$@"; do
        if [ -d "$d" ]; then printf '%s\n' "$d"; fi
    done
}

# own_trust_run <consumer dir> <command...>: runs the command. If FACEBLUR_OWN_PUBLICATION_TRUST names a helper script
# (the maintainer's merge gate, or a CI job that opts in), the consumer build's dependency-verification metadata gets,
# for this run only, exact SHA-256 entries for the artifacts of the library's own local publication that the previous
# step just built (group io.github.shishark, the current VERSION_NAME only, no group trust), and the committed file is
# restored afterwards. The helper prints every coordinate and hash. Without the variable nothing changes and the
# committed metadata alone decides.
own_trust_run() {
    otr_dir=$1
    shift
    if [ -z "${FACEBLUR_OWN_PUBLICATION_TRUST:-}" ]; then
        "$@"
        return
    fi
    otr_meta=$otr_dir/gradle/verification-metadata.xml
    otr_version=$(sed -n 's/^VERSION_NAME=//p' gradle.properties | head -1)
    python3 -I "$FACEBLUR_OWN_PUBLICATION_TRUST" add --maven-dir build/local-maven --version "$otr_version" "$otr_meta" || return 1
    otr_code=0
    "$@" || otr_code=$?
    python3 -I "$FACEBLUR_OWN_PUBLICATION_TRUST" restore "$otr_meta"
    return $otr_code
}

run_step() {
    case "$1" in
        # --- gates ---------------------------------------------------------------------------------------------------
        data-admission)
            if [ ! -f config/data-admission/check.sh ]; then
                echo "::error::config/data-admission/check.sh is missing; data admission cannot be skipped"
                exit 1
            fi
            sh config/data-admission/check.sh ci --all ;;
        dco) sh scripts/check-dco.sh HEAD ;;
        spdx) sh scripts/check-spdx.sh ;;
        # licenses/INDEX.txt: every named licence text exists, none is unused, LICENSE and NOTICE are in place (ADR 0008).
        licence-index) python3 -I scripts/licence-notices.py check ;;
        api-wording) sh scripts/check-api-wording.sh ;;
        # The term list is required: a missing or empty list fails (DOC-008).
        wording) sh scripts/check-wording.sh --require ;;
        internal-ids)
            base=$(diff_base)
            python3 -I scripts/check-internal-ids.py --tree
            python3 -I scripts/check-internal-ids.py --range "$base..HEAD"
            python3 -I scripts/check-internal-ids.py --baseline-shrinks-only "$base" ;;
        internal-figures)
            base=$(diff_base)
            python3 -I scripts/check-internal-figures.py --tree
            python3 -I scripts/check-internal-figures.py --range "$base..HEAD" ;;
        sast) sh scripts/run-sast.sh ;;
        # Every reviewed file-I/O line is classified; the only write sites are the masked output, its temporary file
        # next to the target and their clean-up, in the file-mode writer files (PRIV-005).
        write-sites) python3 -I scripts/check-write-sites.py . ;;
        dependency-verification) sh scripts/check-dependency-verification.sh ;;
        # Report only: build-tool advisories are recorded; runtime and published dependencies are gated in the build.
        osv-build-tooling)
            [ -n "${OSV_SCANNER:-}" ] || { echo "::error::OSV_SCANNER is not set"; exit 1; }
            status=0
            "$OSV_SCANNER" scan source -L gradle/verification-metadata.xml > osv-build-tooling.txt 2>&1 || status=$?
            cat osv-build-tooling.txt
            rm -f osv-build-tooling.txt
            case "$status" in
                0) echo "OSV build tooling: no advisories" ;;
                1) echo "::warning::OSV: advisories in build-tool dependencies (not shipped; record them), see log" ;;
                *) echo "::error::osv-scanner failed (exit $status)"; exit 1 ;;
            esac ;;
        gate-self-tests) sh scripts/test-gates.sh ;;
        ort-telemetry) python3 -I scripts/check-ort-telemetry.py ;;
        ort-options) python3 -I scripts/check-ort-options.py ;;
        # CI and the local merge gate run the same checks (scripts/check-ci-steps.py, both directions).
        ci-step-list) python3 -I scripts/check-ci-steps.py . ;;
        # --- build ---------------------------------------------------------------------------------------------------
        build-main)
            tasks=$BUILD_TASKS
            [ "$(fixtures_mode)" = local ] && tasks="$LOCAL_BUILD_TASKS $BUILD_TASKS"
            # shellcheck disable=SC2086 # word splitting of the task list is intended
            ./gradlew --stacktrace $tasks ;;
        publish-local) ./gradlew --stacktrace publishAllPublicationsToLocalRepoRepository ;;
        publication-contents) sh scripts/check-publication.sh build/local-maven ;;
        # The manifest an app really gets (merged XML + APK), with the planted control (ONNX Runtime without FaceBlur).
        consumer-manifest) own_trust_run verification/android-consumer sh scripts/check-consumer-manifest.sh ;;
        # Every file of docs/guides/snippets/android/ compiled as an app would (Kotlin + Java, local publication), the
        # guide's no-network manifest merged (neither INTERNET nor ACCESS_NETWORK_STATE; the plain control keeps
        # both), and the Java example's cancellation tests (Android host tests) ran and passed.
        guide-snippets) own_trust_run verification/android-snippets sh scripts/check-guide-snippets.sh ;;
        osv-runtime-gate)
            [ -n "${OSV_SCANNER:-}" ] || { echo "::error::OSV_SCANNER is not set"; exit 1; }
            ./gradlew --stacktrace :faceblur:writeRuntimeLockfile
            sh scripts/osv-runtime-gate.sh faceblur/build/osv/runtime.lockfile build/local-maven ;;
        # Every resolved runtime dependency has an index row with its version, and the published AAR, root jar, sources
        # and javadoc jars carry LICENSE, NOTICE and the texts of their kind (after publish-local and the OSV lockfile).
        licence-notices)
            python3 -I scripts/licence-notices.py check --lockfile faceblur/build/osv/runtime.lockfile \
                --repo build/local-maven ;;
        # The runtime dependencies of the published Android artifact (resolved classpath, POM and module metadata) are
        # exactly the reviewed allow-list; networking or telemetry libraries are never allowed (PRIV-014).
        runtime-deps)
            ./gradlew --stacktrace -q :faceblur:writeRuntimeLockfile
            python3 -I scripts/check-runtime-deps.py --lockfile faceblur/build/osv/runtime.lockfile \
                --allow config/runtime-deps/android.txt --repo build/local-maven ;;
        # jvmLargeHeapTest and jvmRealHeapTest finalize jvmTest (own JVMs with fixed heaps); missing results fail here.
        test-results)
            python3 -I scripts/check-test-results.py faceblur/build/test-results \
                jvmTest testAndroidHostTest jvmLargeHeapTest jvmRealHeapTest ;;
        large-heap-switch) sh scripts/test-large-heap-switch.sh ;;
        eval-smoke)
            if [ "$(fixtures_mode)" = local ]; then
                sh scripts/eval-smoke.sh --stacktrace
            else
                not_run eval-smoke "it evaluates the local reference model on the local images"
            fi ;;
        privacy-crosscheck)
            ./gradlew -p verification --stacktrace :jpeg-privacy:installDist
            checker=verification/jpeg-privacy/build/install/jpeg-privacy/bin/jpeg-privacy
            sh scripts/crosscheck-file-mode.sh "$checker"
            sh scripts/test-crosscheck-file-mode.sh "$checker" ;;
        # Phone-only files (config/coverage/phone-only.txt) leave the 80 % only with a valid phone-test log for this
        # commit (FACEBLUR_PHONE_TEST_LOG, written by scripts/phone-device-tests.sh in the local merge gate). GitHub
        # Actions has no phone: there they are reported as NOT MEASURED; scripts/ci-local.sh refuses GITHUB_ACTIONS.
        changed-lines-coverage)
            base=$(diff_base)
            set -- --report faceblur/build/reports/kover/report.xml --base "$base" --threshold 80 \
                --phone-only config/coverage/phone-only.txt
            if [ -n "${FACEBLUR_PHONE_TEST_LOG:-}" ]; then
                set -- "$@" --phone-log "$FACEBLUR_PHONE_TEST_LOG"
            elif [ "${GITHUB_ACTIONS:-}" = true ]; then
                set -- "$@" --ci-phone-not-measured
            fi
            python3 -I scripts/diff-coverage.py "$@" ;;
        # With the local fixtures: N1-N9 and F1-F2; without them only F1-F2 (the fuzz harness on the synthetic model).
        test-model-controls) sh scripts/test-model-step.sh ;;
        artifact-paths) sh scripts/check-artifact-paths.sh build/local-maven faceblur/build/dokka/html ;;
        model-leak)
            if [ "$(fixtures_mode)" = local ]; then
                dir=build/test-models
                ref="$dir/$(sed -n 's/^fileName=//p' "$FACEBLUR_LOCAL_FIXTURES/yunet/yunet.properties")"
                python3 -I scripts/check-model-leak.py --fixture "$ref" --fixture "$dir/derived/G0.onnx" \
                    --fixture "$dir/derived/G1.onnx" --fixture "$dir/derived/G2.onnx" \
                    --fixture "$dir/derived/G3.onnx" build/local-maven faceblur/build/dokka/html
            else
                not_run model-leak "it looks for the local reference model and its derived models in the artifacts"
            fi ;;
        reports-paths)
            dirs=$(paths_of_existing faceblur/build/reports faceblur/build/test-results)
            if [ -z "$dirs" ]; then
                echo "no reports produced"
                exit 0
            fi
            # shellcheck disable=SC2086 # word splitting of the directory list is intended
            sh scripts/check-artifact-paths.sh $dirs ;;
        # The tests of config/local-fixture-tests.txt need the local fixtures: they must all have run and passed in the
        # local gate (counts are printed), and are reported as NOT RUN in public CI. Reads the results of build-main, so it
        # runs right after test-results (later steps, such as the model-fixture controls, run filtered test tasks).
        local-fixture-tests)
            if [ "$(fixtures_mode)" = local ]; then
                python3 -I scripts/check-local-fixture-tests.py --require faceblur/build/test-results config/local-fixture-tests.txt
            else
                python3 -I scripts/check-local-fixture-tests.py --excluded faceblur/build/test-results config/local-fixture-tests.txt
            fi ;;
        # --- verification build ----------------------------------------------------------------------------------------
        verification-check) ./gradlew -p verification check --stacktrace ;;
        verification-results)
            found=0
            for d in verification/*/build/test-results; do
                [ -d "$d/test" ] || continue
                found=1
                python3 -I scripts/check-test-results.py "$d" test
            done
            [ "$found" -eq 1 ] || { echo "::error::no test results under verification/*/build/test-results/test"; exit 1; } ;;
        verification-reports-paths)
            # shellcheck disable=SC2046 # word splitting of the directory list is intended
            dirs=$(paths_of_existing $(ls -d verification/*/build/reports verification/*/build/test-results 2>/dev/null))
            if [ -z "$dirs" ]; then
                echo "no reports produced"
                exit 0
            fi
            # shellcheck disable=SC2086 # word splitting of the directory list is intended
            sh scripts/check-artifact-paths.sh $dirs ;;
        *) echo "ci-steps: unknown step '$1'" >&2; exit 2 ;;
    esac
}

case "${1:-}" in
    --list)
        # shellcheck disable=SC2086 # one step name per word
        case "${2:-}" in
            gates) echo $GATES ;;
            build) echo $BUILD ;;
            verification) echo $VERIFICATION ;;
            *) echo "ci-steps: --list gates|build|verification" >&2; exit 2 ;;
        esac ;;
    "") echo "usage: scripts/ci-steps.sh <step> | --list <job>" >&2; exit 2 ;;
    *) run_step "$1" ;;
esac
