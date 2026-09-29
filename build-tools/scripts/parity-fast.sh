#!/usr/bin/env bash
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# parity-fast.sh - JVM-only inner loop for the Android <-> Linux desktop parity work.
#
# Why this exists: the full gate (build-all-platforms.ps1 -Profile full) costs ~10 minutes per
# cycle, almost all of it Android - unit tests ~5m20s and static analysis ~4m35s. The parity
# milestones (M3b-*) touch only core/ and desktop/, which are pure JVM surfaces, so Android cost
# buys nothing there. Measured inner loop: ~110s, a 5-6x reduction.
#
# SCOPE CONTRACT - read before trusting a green run:
#   * These milestones are NOT VERIFIED by this script. It compiles nothing Android-side, installs
#     nothing, runs no device smoke test and signs nothing.
#   * The full gate (build-all-platforms.ps1 -Profile full) still runs at EVERY milestone boundary.
#     That is what preserves the guarantee; this only shortens the edit/test loop in between.
#   * If a change touches app/, core/database/ or any Android-only surface, run the full gate.
#
# WHY THE DETEKT LIST BELOW IS NARROWER THAN THE ROOT staticAnalysisCheck LIST - do not "fix" this:
#   The root gate lists :core:ui:detektAndroidMain too; this script deliberately does not. That task
#   needs :core:ui:compileAndroidMain (detekt takes its classpath from compilation output), and this
#   script promises a JVM-only loop with no Android compile. Common-source analysis
#   (:core:ui:detektMetadataCommonMain, the task that covers the bulk of the module) runs here; the
#   androidMain and desktopMain slices are covered by the full gate at the milestone boundary.
#
# Usage:  bash build-tools/scripts/parity-fast.sh
set -uo pipefail
cd "$(dirname "$0")/../.."

fail=0
step() { echo; echo "==> $*"; }

step "JVM tests + desktop compile + desktop detekt"
./gradlew \
    :core:shared:test \
    :core:domain:test \
    :core:scoring:test \
    :core:database-jvm:test \
    :desktop:compileKotlin \
    :desktop:test \
    :desktop:detekt \
    :core:ui:compileKotlinDesktop \
    :core:ui:detektMetadataCommonMain \
    :core:strings:detekt \
    --console=plain || fail=1

step "Coverage floors (core/domain, core/scoring, core/database-jvm)"
./gradlew \
    :core:domain:jacocoTestCoverageVerification \
    :core:scoring:jacocoTestCoverageVerification \
    :core:database-jvm:jacocoTestCoverageVerification \
    --console=plain || fail=1

step "Spotless ratchet (changed .kt files only)"
pwsh -NoProfile -File build-tools/scripts/spotless-ratchet.ps1 || fail=1

if [ "$fail" -ne 0 ]; then
    echo
    echo "PARITY FAST LOOP: FAILED"
    exit 1
fi

echo
echo "PARITY FAST LOOP: PASSED (inner loop only - run the full gate at the milestone boundary)"
