#!/usr/bin/env bash
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
# Run the on-device Maestro suite against the build you choose.
#
#   ./build-tools/scripts/run-maestro.sh              # release app, io.payanam
#   ./build-tools/scripts/run-maestro.sh --debug      # debug build, io.payanam.debug
#   ./build-tools/scripts/run-maestro.sh --debug .maestro/sessions/07-settings-cards.yaml
#
# WHY THE APP ID IS AN ARGUMENT: every flow declares `appId: ${APP_ID}`, so one suite runs unchanged
# against the release package and the debug one (which carries the .debug suffix). Without this the
# flows could only ever drive the release build, and a debug APK had to be renamed or re-signed first.
set -euo pipefail

APP_ID=io.payanam
if [[ "${1:-}" == "--debug" ]]; then
    APP_ID=io.payanam.debug
    shift
fi

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
exec maestro test -e APP_ID="$APP_ID" "${@:-.maestro}"
