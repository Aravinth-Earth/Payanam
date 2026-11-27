#!/usr/bin/env bash
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
# One focused run, in the order the layers need: setup -> the flows you name -> teardown -> pull.
#
#   ./build-tools/scripts/run-focused-e2e.sh --debug .maestro/sessions/07-settings-cards.yaml
#   ./build-tools/scripts/run-focused-e2e.sh --apk build/outputs/apk/debug/app-debug.apk \
#       .maestro/sessions/07-settings-cards.yaml
#
# SETUP is 01-fresh-setup (pm clear + passphrase + dimensions + debug logging, conditionally + auto-backup).
# TEARDOWN is subflows/export-session-log (Settings -> Debug -> export latest log, then a DB snapshot).
# The pull lands the two layers the verifier reads under build-logs/e2e/<timestamp>/.
set -euo pipefail

APP_ID=io.payanam
APK=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --debug) APP_ID=io.payanam.debug; shift ;;
        --apk)   APK="$2"; shift 2 ;;
        *)       break ;;
    esac
done
if [[ $# -lt 1 ]]; then
    echo "usage: $0 [--debug] [--apk <apk>] <flow> [<flow> ...]" >&2
    exit 2
fi

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
ROOT_NAME=Payanam
[[ "$APP_ID" == *.debug ]] && ROOT_NAME=Payanam-debug

if [[ -n "$APK" ]]; then
    echo "== install: $APK =="
    adb install -r "$APK" >/dev/null
fi

echo "== setup =="
maestro test -e APP_ID="$APP_ID" .maestro/01-fresh-setup.yaml

echo "== target =="
for flow in "$@"; do
    maestro test -e APP_ID="$APP_ID" "$flow"
done

echo "== teardown =="
maestro test -e APP_ID="$APP_ID" .maestro/subflows/export-session-log.yaml

STAMP="$(date +%Y%m%d_%H%M%S)"
OUT="build-logs/e2e/$STAMP"
mkdir -p "$OUT"
echo "== pull =="
adb pull "/sdcard/Documents/$ROOT_NAME/exported-logs" "$OUT/exported-logs" >/dev/null 2>&1 || true
adb pull "/sdcard/Documents/$ROOT_NAME/data/export" "$OUT/backups" >/dev/null 2>&1 || true
echo "artifacts: $OUT"
echo "verify : ./gradlew :tools:e2e-verify:run --args=\"--log $OUT/exported-logs/<newest>.log --db $OUT/backups/<newest>/payanam.db\""
