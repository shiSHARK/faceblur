#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# LIC-001: every tracked source file carries `SPDX-License-Identifier: Apache-2.0` in its first 5 lines.
# Source files: Kotlin, Kotlin scripts, Java, Swift, Objective-C/C/C++, shell, Python, workflow YAML, git hooks
# and other tracked executables with a shebang. The Gradle-generated launchers gradlew and gradlew.bat are the only
# exceptions (they carry the Gradle project's own Apache-2.0 header; gradlew has the SPDX line further down).
#
# Usage: scripts/check-spdx.sh [file...]   (no arguments: every file tracked by git)
# Exit codes: 0 all present, 1 at least one file lacks the line (each named), 2 usage/IO error.
set -eu

EXCEPTIONS="gradlew gradlew.bat"
LINE='SPDX-License-Identifier: Apache-2.0'

if [ "$#" -gt 0 ]; then
    list=$(printf '%s\n' "$@")
else
    list=$(git -c core.quotePath=false ls-files) || exit 2
fi

missing=0
checked=0
# Paths may contain spaces: iterate line by line.
printf '%s\n' "$list" | {
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        [ -f "$f" ] || continue
        case " $EXCEPTIONS " in *" $f "*) continue ;; esac
        is_source=0
        case "$f" in
            *.kt | *.kts | *.java | *.swift | *.m | *.mm | *.h | *.c | *.cpp | *.sh | *.py) is_source=1 ;;
            .github/workflows/*.yml | .github/workflows/*.yaml | .github/*.yml) is_source=1 ;;
            .githooks/*) is_source=1 ;;
            *)
                if [ -x "$f" ] && head -c 2 "$f" 2>/dev/null | grep -q '^#!'; then is_source=1; fi
                ;;
        esac
        [ "$is_source" -eq 1 ] || continue
        checked=$((checked + 1))
        if ! head -n 5 "$f" | grep -q "$LINE"; then
            echo "check-spdx: missing '$LINE' in the first 5 lines: $f" >&2
            missing=$((missing + 1))
        fi
    done
    if [ "$missing" -gt 0 ]; then
        echo "check-spdx: FAIL - $missing of $checked source files without the SPDX line (LIC-001)" >&2
        exit 1
    fi
    echo "check-spdx: PASS - $checked source files carry the SPDX line"
}
