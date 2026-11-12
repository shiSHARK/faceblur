#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# LIC-006: every commit carries a DCO sign-off, `Signed-off-by: <author name> <author e-mail>` (git commit -s).
# The sign-off must name the commit author exactly. Merge commits are checked too.
#
# Usage:
#   scripts/check-dco.sh <rev-range>          e.g. origin/main..HEAD (CI, pre-push)
#   scripts/check-dco.sh --message <file>     a commit message file (commit-msg hook); the author is taken from
#                                             GIT_AUTHOR_NAME/GIT_AUTHOR_EMAIL or `git var GIT_AUTHOR_IDENT`
# Exit codes: 0 all signed, 1 a commit/message without a matching sign-off, 2 usage/git error.
# Output names commits by abbreviated SHA only (no names or e-mail addresses are printed).
set -eu

usage() {
    echo "usage: $0 <rev-range> | --message <file>" >&2
    exit 2
}

[ "$#" -ge 1 ] || usage

# has_signoff <message file> <name> <email>
has_signoff() {
    expected="Signed-off-by: $2 <$3>"
    grep -qxF "$expected" "$1"
}

if [ "$1" = "--message" ]; then
    [ "$#" -eq 2 ] || usage
    msg=$2
    [ -r "$msg" ] || { echo "check-dco: cannot read the message file" >&2; exit 2; }
    ident=$(git var GIT_AUTHOR_IDENT) || exit 2
    name=${GIT_AUTHOR_NAME:-$(printf '%s\n' "$ident" | sed -e 's/ <.*$//')}
    email=${GIT_AUTHOR_EMAIL:-$(printf '%s\n' "$ident" | sed -e 's/^.*<//' -e 's/>.*$//')}
    if has_signoff "$msg" "$name" "$email"; then
        echo "check-dco: PASS - message signed off by its author"
        exit 0
    fi
    echo "check-dco: FAIL - the commit message has no 'Signed-off-by:' line for the commit author. Use 'git commit -s' (LIC-006)." >&2
    exit 1
fi

[ "$#" -eq 1 ] || usage
range=$1
commits=$(git rev-list "$range") || { echo "check-dco: invalid revision range" >&2; exit 2; }

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT INT TERM
bad=0
count=0
for c in $commits; do
    count=$((count + 1))
    git log -1 --format=%B "$c" > "$tmp"
    name=$(git log -1 --format=%an "$c")
    email=$(git log -1 --format=%ae "$c")
    if ! has_signoff "$tmp" "$name" "$email"; then
        echo "check-dco: commit $(git rev-parse --short "$c") has no 'Signed-off-by:' line matching its author" >&2
        bad=$((bad + 1))
    fi
done
if [ "$bad" -gt 0 ]; then
    echo "check-dco: FAIL - $bad of $count commits without a matching sign-off (LIC-006)" >&2
    exit 1
fi
echo "check-dco: PASS - $count commits signed off by their authors"
