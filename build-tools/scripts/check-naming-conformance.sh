#!/usr/bin/env bash
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
# Compares the bash and PowerShell naming helpers against one expected literal.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "$SCRIPT_DIR/lib/naming.sh"

PREFIX="Payanam"
PLATFORM="Android"
VERSION="1.0.0"
SHA="abc1234"
TYPE="debug"
TIMESTAMP="20261026_101500"
EXPECTED="Payanam_android_1.0.0_abc1234_debug_20261026_101500"

BASH_NAME="$(payanam_generated_name "$PREFIX" "$PLATFORM" "$VERSION" "$SHA" "$TYPE" "$TIMESTAMP")"
PS_NAME="$(
    pwsh -NoProfile -NonInteractive -Command \
        "& { . '$SCRIPT_DIR/lib/naming.ps1'; Get-PayanamGeneratedName -Prefix '$PREFIX' -Platform '$PLATFORM' -Version '$VERSION' -Sha '$SHA' -Type '$TYPE' -Timestamp '$TIMESTAMP' }"
)" || true

STATUS=0
[[ "$BASH_NAME" == "$EXPECTED" ]] || STATUS=1
[[ "$PS_NAME" == "$EXPECTED" ]] || STATUS=1

if ((STATUS != 0)); then
    echo "NAMING CONFORMANCE FAILED" >&2
    echo "  expected: $EXPECTED" >&2
    echo "  bash:     $BASH_NAME" >&2
    echo "  pwsh:     $PS_NAME" >&2
    exit 1
fi

echo "naming conformance: $BASH_NAME"
