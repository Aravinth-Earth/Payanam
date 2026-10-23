#!/usr/bin/env bash
# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# A module disabled by the stored tab-visibility rows can never be the landing route: the
# launched desktop must open into an ENABLED module. The seed makes the PREFERRED route a
# DISABLED one (`launch_destination_route=time` + `tab_visible_time=false`), so a build whose
# fallback works lands on the first enabled route (tasks), and one that regressed lands on `time`.
#
# HOW IT IS PROVEN - the log, not OCR
# `DesktopApp` logs "Desktop startup state resolved | [launchRoute=..., startupMode=...]" each
# time the startup snapshot resolves, and after unlock the stored rows are in force, so the LAST
# such line carries the real preference-derived route. Asserting on that line is the gate's own
# wording ("proven from the run and its log") and introduces no new OCR needle: the only words
# this drive reads off the screen are the launch gate's own `Unlock`.
#
# MEASURED PIXELS (1280x900 root): passphrase field centre (254,336), Unlock button centre
# (254,416) - MEASURED 2026-10-08, reused from desktop-loop-drive-habit-rollup.sh.
#
# WHY THE SEED HAPPENS BEFORE UNLOCK: the app sits on the unlock gate with NO session and holds
# no database handle, so these writes cannot race it. After unlock the app owns the DB and this
# drive never writes to it again.
set -uo pipefail

export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8

PASSPHRASE="${LOOP_PASSPHRASE:-LoopDrive#Pass9}"
DB_PATH="/home/cawax/Documents/Payanam-debug/db/payanam.db"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 0 - scope OCR to the app window (the loop launched it; the region is derived, never hardcoded).
APP_REGION=""
if [[ -n "${WINDOW_ID:-}" ]]; then
    APP_REGION=$(xwininfo -id "$WINDOW_ID" 2>/dev/null | awk '
        /Absolute upper-left X/ {x=$NF}
        /Absolute upper-left Y/ {y=$NF}
        /^ *Width:/             {w=$NF}
        /^ *Height:/            {h=$NF}
        END { if (x!="" && y!="" && w!="" && h!="") printf "%d,%d,%d,%d", x, y, x+w, y+h }')
fi
[[ -n "$APP_REGION" ]] || die "PHASE 0: could not derive the app window region; stopping rather than reading unscoped."
say "PHASE 0: OCR region scoped to the app window: $APP_REGION"

say "--- landing-route: a disabled module can never be the landing route ---"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 0b - seed the preference rows BEFORE unlock.
say "PHASE 0b: seeding launch_destination_route='time' with tab_visible_time='false' BEFORE unlock"

SEED_SQL="INSERT INTO app_settings (key, value, updatedAt) VALUES
  ('launch_destination_route','time', strftime('%Y-%m-%dT%H:%M:%S','now','localtime')),
  ('tab_visible_time','false', strftime('%Y-%m-%dT%H:%M:%S','now','localtime')),
  ('tab_visible_tasks','true', strftime('%Y-%m-%dT%H:%M:%S','now','localtime')),
  ('app_language','en', strftime('%Y-%m-%dT%H:%M:%S','now','localtime')),
  ('focus_mode_onboarding_completed','true', strftime('%Y-%m-%dT%H:%M:%S','now','localtime'))
  ON CONFLICT(key) DO UPDATE SET value=excluded.value, updatedAt=excluded.updatedAt;"

SEED_OUT=$(sqlcipher "file:${DB_PATH}" -cmd "PRAGMA key='${PASSPHRASE}'" "${SEED_SQL}" 2>&1) \
    || die "PHASE 0b: seed failed: $SEED_OUT"
say "PHASE 0b: seed ok"

SEEDED_PREF=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" \
    "SELECT value FROM app_settings WHERE key='launch_destination_route';" 2>&1 | grep -v -x 'ok') \
    || die "PHASE 0b: read-back failed: $SEEDED_PREF"
SEEDED_HIDDEN=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" \
    "SELECT value FROM app_settings WHERE key='tab_visible_time';" 2>&1 | grep -v -x 'ok') \
    || die "PHASE 0b: read-back failed: $SEEDED_HIDDEN"
[[ "$SEEDED_PREF" == "time" && "$SEEDED_HIDDEN" == "false" ]] \
    || die "PHASE 0b: seed did not land (launch_destination_route='$SEEDED_PREF', tab_visible_time='$SEEDED_HIDDEN')."
say "PHASE 0b: read-back ok - preferred route 'time' is hidden"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 1 - unlock the gate with the fixture passphrase (measured pixels, verified by the gate going away).
UNLOCK_FIELD_X=254
UNLOCK_FIELD_Y=336
UNLOCK_BUTTON_X=254
UNLOCK_BUTTON_Y=416

click "$UNLOCK_FIELD_X" "$UNLOCK_FIELD_Y" 2 "focus passphrase field" || die "PHASE 1: passphrase field click failed"
type_text "$PASSPHRASE"
click "$UNLOCK_BUTTON_X" "$UNLOCK_BUTTON_Y" 3 "press unlock gate's Unlock button" || die "PHASE 1: Unlock click failed"

# The gate is GONE only when 'Unlock' is unreadable twice in a row, 2s apart (a single check
# false-positived on a transitioning frame; measured 2026-10-08).
CLEAR=0
for _check in 1 2 3 4 5 6; do
    if [[ -z "$(find_text_centre --text 'Unlock' --scale 3 --region "$APP_REGION" 2>/dev/null || true)" ]]; then
        CLEAR=$(( CLEAR + 1 ))
        (( CLEAR >= 2 )) && break
    else
        CLEAR=0
    fi
    sleep 2
done
(( CLEAR >= 2 )) || die "PHASE 1: the unlock gate is still up after the passphrase - 'Unlock' is readable. Stopping."
say "PHASE 1: unlocked (the gate's 'Unlock' cleared twice)"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 2 - the assertion, from the app's own log.
LOGS_DIR="$HOME/Documents/Payanam-debug/logs"
deadline=$(( SECONDS + 20 ))
STARTUP_LINE=""
while (( SECONDS < deadline )); do
    newest=$(ls -1t "$LOGS_DIR"/*.log 2>/dev/null | head -1 || true)
    if [[ -n "$newest" ]]; then
        STARTUP_LINE=$(grep 'Desktop startup state resolved' "$newest" 2>/dev/null | tail -1 || true)
        # The preference-derived line is the one resolved AFTER the rows were read; with focus onboarding
        # already completed in the seed its startupMode is Ready. A pre-unlock line resolves against
        # absent-row defaults and reports a gate mode, so waiting for Ready is what makes the route read
        # the STORED preference rather than the default.
        if [[ "$STARTUP_LINE" == *'startupMode=Ready'* ]]; then
            break
        fi
    fi
    sleep 2
done
[[ -n "$STARTUP_LINE" ]] || die "PHASE 2: no 'Desktop startup state resolved' line appeared in the app log after unlock. Stopping."
say "PHASE 2: last startup line -> $STARTUP_LINE"

ROUTE=$(sed -n 's/.*launchRoute=["]*\([a-zA-Z_]*\)["]*.*/\1/p' <<< "$STARTUP_LINE")
[[ -n "$ROUTE" ]] || die "PHASE 2: the startup line carries no launchRoute field, so the landing route cannot be read."
say "PHASE 2: launchRoute='$ROUTE'"

[[ "$ROUTE" != "time" ]] || die "PHASE 2: FAILURE - the app landed on 'time', the module the seed DISABLED. A disabled module must never be the landing route."
[[ "$ROUTE" == "tasks" ]] || die "PHASE 2: FAILURE - expected the first ENABLED route 'tasks' (enum order Tasks, Habits, Time, ...) with 'time' hidden, got '$ROUTE'."

say "=== PROVEN: with 'time' disabled, the launched desktop landed on the enabled module 'tasks' ==="
say "--- landing-route drive finished ---"
