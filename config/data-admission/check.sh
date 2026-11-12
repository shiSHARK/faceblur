#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Thin wrapper for the data-admission guard. Usage: see README.md in this directory.
# Exit codes: 0 pass, 1 blocked, 2 guard error (treat as blocked).
set -u
here=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 2
command -v python3 >/dev/null 2>&1 || { echo "data-admission: python3 not found (treated as BLOCK)" >&2; exit 2; }
exec python3 -I "$here/guard.py" "$@"
