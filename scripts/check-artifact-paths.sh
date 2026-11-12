#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# Fails if build outputs embed absolute build-machine paths (SEC-006). Thin wrapper around
# scripts/check-artifact-paths.py (archives to any depth, gzip/tar, UTF-16, case-insensitive; see that file).
# Usage: scripts/check-artifact-paths.sh <file-or-directory>...
# Exit codes: 0 clean, 1 absolute paths found, 2 usage error or unreadable/unpackable input.
set -eu
exec python3 -I "$(dirname "$0")/check-artifact-paths.py" "$@"
