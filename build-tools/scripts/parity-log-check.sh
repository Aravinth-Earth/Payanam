#!/usr/bin/env bash
# Cross-platform LOG parity check for ONE interaction, end to end.
#
# Runs the Android journey for an interaction on a connected device, pulls the app's own session log off it
# (`run-as` on a debug build), runs the DESKTOP journey for the same interaction, and diffs the two logs with
# `parity-log-diff.py`. Both apps write the SAME session-log line shape, so a marker that appears on one side
# only - or an ERROR on one side only - is a real divergence: the same interaction behaves, or at least logs,
# differently on the two platforms.
#
# WHY THIS EXISTS. Functional parity is not only "the screen works": for the same interaction both apps must
# emit the same INFO-and-above markers. This script is what turns that claim into a run rather than a hope.
#
# USAGE
#   build-tools/scripts/parity-log-check.sh <interaction>
#     notes | tasks | time | habits | journal
#
# Requires: a connected Android device (adb), and a built debug APK. The Android class is installed with
# `leaveApksInstalledAfterRun=true` so its session log survives the pull, then uninstalled.
#
# SPDX-License-Identifier: AGPL-3.0-or-later
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

case "${1:-}" in
  notes)   A=io.payanam.e2e.NotesCrudTest;             D=io.payanam.desktop.DesktopNotesJourneyTest;    G=NotesViewModel ;;
  tasks)   A=io.payanam.e2e.TasksCrudTest;             D=io.payanam.desktop.DesktopTasksCrudJourneyTest; G=AddTaskViewModel ;;
  habits)  A=io.payanam.e2e.HabitsCrudTest;            D=io.payanam.desktop.DesktopHabitsJourneyTest;   G=AddTaskViewModel ;;
  time)    A=io.payanam.e2e.TimeEntryEditTest;         D=io.payanam.desktop.DesktopTimeEntryJourneyTest; G=TimeViewModel ;;
  journal) A=io.payanam.e2e.JournalPerDimensionTest;   D=io.payanam.desktop.DesktopJournalJourneyTest;  G=DayViewModel ;;
  *) echo "usage: $0 <notes|tasks|habits|time|journal>" >&2; exit 2 ;;
esac

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
ANDROID_LOG="$WORK/android-session.log"

echo "== android: $A =="
# Keep the app installed so its session log survives the pull; the runner uninstalls it afterwards.
./gradlew :app:connectedDebugAndroidTest -Ppayanam.noMinify=true \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class="$A" >/dev/null
LOG_NAME="$(adb exec-out run-as io.payanam.debug ls files/logs | tr -d '\r' | head -1)"
echo "   android log: $LOG_NAME"
adb exec-out run-as io.payanam.debug cat "files/logs/$LOG_NAME" > "$ANDROID_LOG"
adb uninstall io.payanam.debug >/dev/null 2>&1 || true

echo "== desktop: $D =="
./gradlew :desktop:test --tests "$D" >/dev/null
RUN="$(ls -dt build-logs/desktop-journey-* | while read -r d; do [ -f "$d/session.log" ] && echo "$d" && break; done)"
[ -n "$RUN" ] || { echo "no desktop session.log found" >&2; exit 1; }
echo "   desktop log: $RUN/session.log"

echo "== diff (source contains '$G') =="
python3 build-tools/scripts/parity-log-diff.py --android "$ANDROID_LOG" --desktop "$RUN/session.log" --grep "$G"
