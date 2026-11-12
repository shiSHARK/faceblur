#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Path filters for CI. Prints three lines for $GITHUB_OUTPUT:
#   code=true|false          false only if EVERY file changed on the branch is documentation (`*.md` anywhere or
#                            under `docs/`, except the compiled guide snippets under `docs/guides/snippets/`); the
#                            Gradle jobs are then skipped.
#   verification=true|false  true if the verification build exists (verification/settings.gradle.kts) and a file
#                            under verification/, the library sources (faceblur/), the build configuration
#                            (gradle/, *.gradle.kts, gradle.properties, gradlew), the CI workflow or the artifact
#                            path scripts changed on the branch.
#   ci_config=true|false     true if CI or build configuration changed on the branch: .github/, scripts/, gradle/
#                            (version catalog, dependency verification metadata, wrapper) at any depth, a Gradle
#                            script (*.gradle.kts incl. settings), gradle.properties, gradlew*. A push to a branch
#                            other than main then runs the build and verification jobs without a manual start.
# On refs/heads/main nothing is ever skipped. On any other ref the WHOLE branch is compared with its merge
# base with origin/main (not only the latest push), so a branch is skipped only if all its changes are docs.
# Fails safe: any doubt (manual run, no merge base, git error, no changed files) prints true for both
# (verification still only when the build exists; ci_config true). The gates job runs on every push regardless.
#
# Usage: scripts/ci-changes.sh <event name> <ref> <head sha> [base ref, default origin/main]
#   e.g. scripts/ci-changes.sh push refs/heads/feat/x HEAD
# Needs the full history (actions/checkout fetch-depth: 0).
set -eu

event=${1:-}
ref=${2:-}
head=${3:-HEAD}
base_ref=${4:-origin/main}

has_verification=false
if git cat-file -e "$head:verification/settings.gradle.kts" 2>/dev/null; then has_verification=true; fi

# result <code> <verification> <ci_config> <reason>
result() {
    echo "ci-changes: $4" >&2
    echo "code=$1"
    if [ "$has_verification" = true ]; then echo "verification=$2"; else echo "verification=false"; fi
    echo "ci_config=$3"
    exit 0
}

[ "$event" = "workflow_dispatch" ] && result true true true "manual run: build everything"
[ "$ref" = "refs/heads/main" ] && result true true true "main: never skipped"
base=$(git merge-base "$base_ref" "$head" 2>/dev/null || true)
if [ -z "$base" ]; then result true true true "no merge base with $base_ref: build everything"; fi

files=$(git -c core.quotePath=false diff --name-only "$base" "$head") ||
    result true true true "git diff failed: build everything"
if [ -z "$files" ]; then result true true true "no changed files on this branch: build everything"; fi

code=false
verification=false
ci_config=false
count=0
for f in $files; do
    case "$f" in
        .github/* | scripts/* | gradle/* | */gradle/* | *.gradle.kts | gradle.properties | */gradle.properties | \
            gradlew | gradlew.bat) ci_config=true ;;
    esac
    count=$((count + 1))
    case "$f" in
        # The guide snippets are compiled by the build job (step guide-snippets): a change is never docs-only.
        docs/guides/snippets/*) code=true ;;
        *.md | docs/*) ;;
        *) code=true ;;
    esac
    case "$f" in
        *.md | docs/*) ;;
        verification/* | faceblur/* | gradle/* | *.gradle.kts | gradle.properties | gradlew | gradlew.bat | \
            .github/workflows/ci.yml | scripts/check-artifact-paths.*) verification=true ;;
    esac
done
if [ "$code" = false ]; then
    result false false false "all $count files changed on this branch are documentation: Gradle jobs skipped"
fi
result true "$verification" "$ci_config" "code or configuration changed ($count files on this branch; CI/build configuration: $ci_config)"
