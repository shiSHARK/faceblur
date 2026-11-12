#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# DOC-008 wording lint. Rejects forbidden claim wording in the public trees (the scope, defined once below in
# in_scope and used by CI and the pre-commit hook): Markdown files, docs/, the ABI dumps, string resources
# (strings.xml, .strings, .xcstrings), the library's production sources faceblur/src/*Main/ (KDoc becomes the
# public API documentation), the sample apps samples/ and the Swift package sources Sources/ - without test sources
# and without scripts. Binary files are skipped (grep -I).
#
# The term list is owned by the legal-compliance advisor (one entry per line, '#' starts a comment):
#   config/wording/terms.txt   extended regular expressions, matched case-insensitively
#   config/wording/allow.txt   exact lines that may contain a term (negated sentences in the limits section)
# Without terms.txt the lint reports NOT RUN and exits 0, unless --require is given (then it exits 1); CI and
# the pre-commit hook (once the list is committed) use --require.
#
# Usage: scripts/check-wording.sh [--require] [file...]   (no files: every tracked file in scope)
#        scripts/check-wording.sh --scope-filter               (reads paths on stdin, prints those in scope)
# Exit codes: 0 clean or NOT RUN, 1 forbidden wording found (file and line named) or --require without a list,
#             2 usage/IO error.
set -eu

# in_scope: reads paths on stdin, prints the ones the lint covers.
# Test sources are recognised by the source-set directory right after "src/" (commonTest, jvmTest,
# androidHostTest, test, androidTest, ...) or a top-level Swift "Tests/" folder - never by a folder name elsewhere
# that merely contains "Test" (a library package such as .../TestPatterns/ stays in scope).
in_scope() {
    while IFS= read -r f; do
        source_set=$(printf '%s\n' "$f" | sed -n 's#^\(.*/\)\{0,1\}src/\([^/]*\)/.*#\2#p')
        case "$source_set" in
            test | *Test) continue ;;
        esac
        case "$f" in
            Tests/*) continue ;;
        esac
        case "$f" in
            *.md | docs/* | faceblur/api/* | *strings.xml | *.strings | *.xcstrings) printf '%s\n' "$f" ;;
            faceblur/src/*Main/* | samples/* | Sources/*) printf '%s\n' "$f" ;;
        esac
    done
}

if [ "${1:-}" = "--scope-filter" ]; then
    in_scope
    exit 0
fi

TERMS=config/wording/terms.txt
ALLOW=config/wording/allow.txt
require=0
if [ "${1:-}" = "--require" ]; then
    require=1
    shift
fi

if [ ! -f "$TERMS" ]; then
    echo "check-wording: NOT RUN - $TERMS does not exist yet (term list pending from legal, DOC-008)"
    [ "$require" -eq 1 ] && exit 1
    exit 0
fi

full_scan=0
if [ "$#" -gt 0 ]; then
    list=$(printf '%s\n' "$@")
else
    tracked=$(git -c core.quotePath=false ls-files) || exit 2
    list=$(printf '%s\n' "$tracked" | in_scope)
    full_scan=1
fi

patterns=$(mktemp)
grep -v -E '^[[:space:]]*(#|$)' "$TERMS" > "$patterns" || true
[ -s "$patterns" ] || { echo "check-wording: $TERMS has no entries" >&2; exit 2; }
# An invalid expression would make every grep below fail silently (no matches): reject the list instead.
set +e
printf 'x\n' | grep -E -i -f "$patterns" > /dev/null 2>&1
status=$?
set -e
[ "$status" -le 1 ] || { echo "check-wording: $TERMS contains an invalid extended regular expression" >&2; exit 2; }

matches=$(mktemp)
files=$(mktemp)
used=$(mktemp)
trap 'rm -f "$patterns" "$matches" "$files" "$used"' EXIT INT TERM
printf '%s\n' "$list" > "$files"
hits=0
while IFS= read -r f; do
    if [ -z "$f" ] || [ ! -f "$f" ]; then continue; fi
    case "$f" in config/wording/*) continue ;; esac
    # grep: 0 = matches, 1 = none, 2 = error (unreadable file): an error fails the lint instead of passing silently.
    set +e
    grep -I -n -i -E -f "$patterns" "$f" > "$matches"
    status=$?
    set -e
    if [ "$status" -gt 1 ]; then
        echo "check-wording: ERROR - cannot read $f" >&2
        exit 2
    fi
    # Line numbers of matching lines that are not on the allow-list.
    while IFS= read -r hit; do
        body=${hit#*:}
        if [ -f "$ALLOW" ] && grep -qxF -- "$body" "$ALLOW"; then
            printf '%s\n' "$body" >> "$used"
            continue
        fi
        echo "check-wording: forbidden claim wording in $f line ${hit%%:*}" >&2
        hits=$((hits + 1))
    done < "$matches"
done < "$files"
# Full scan only (CI): an allow-list line that no longer excuses any scanned line is stale and fails, so the list
# cannot keep exceptions for text that was removed or changed.
stale=0
if [ "$full_scan" -eq 1 ] && [ -f "$ALLOW" ]; then
    n=0
    while IFS= read -r line || [ -n "$line" ]; do
        n=$((n + 1))
        case "$line" in '' | '#'*) continue ;; esac
        if ! grep -qxF -- "$line" "$used"; then
            echo "check-wording: stale allow-list line $n in $ALLOW (it excuses no scanned line)" >&2
            stale=$((stale + 1))
        fi
    done < "$ALLOW"
fi
if [ "$hits" -gt 0 ] || [ "$stale" -gt 0 ]; then
    echo "check-wording: FAIL - $hits line(s) with forbidden claim wording, $stale stale allow-list line(s) (DOC-008)" >&2
    exit 1
fi
echo "check-wording: PASS"
