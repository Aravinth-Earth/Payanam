#!/usr/bin/env bash
# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# The Settings database-stats card must show a count for EVERY model, Notes included. The bug this
# came from is a card that counted some models and silently omitted others, so the assertion is on the
# ROWS THE CARD RENDERS - Tasks, Time Entries and Notes - read off the launched build's own screen.
#
# HOW IT IS PROVEN
#   PHASE 0b seeds ONE row into each of the three models BEFORE unlock (and reads the counts back to
#     prove the fixture is non-empty, so a zero on screen cannot be blamed on an empty database).
#   PHASE 2/3 navigate to Settings and scroll the card into view (the anchor-derived tab and
#     pointer-to-centre scroll idiom the round-trip drive measured).
#   PHASE 4 finds each of the three labels and requires it ABOVE the navigation rail: the nav happens to
#     carry the words `Tasks` and `Notes` as tab labels, so a match whose y sits in the rail is not
#     evidence about the card at all. The y check is what makes the needle specific.
#
# MEASURED PIXELS (1280x900 root): passphrase field (254,336), Unlock (254,416) - MEASURED 2026-10-08.
# The Settings tab's own label is unreadable (7th tab against the window edge), so the tab position is
# DERIVED from the Tasks/Lenses anchors exactly as the round-trip drive does - measured, not guessed.
set -uo pipefail

export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8

PASSPHRASE="${LOOP_PASSPHRASE:-LoopDrive#Pass9}"
DB_PATH="/home/cawax/Documents/Payanam-debug/db/payanam.db"
NAV_Y_LIMIT=780   # anything at or below this y is the navigation rail / bottom bar, not the card

APP_REGION=""
if [[ -n "${WINDOW_ID:-}" ]]; then
    APP_REGION=$(xwininfo -id "$WINDOW_ID" 2>/dev/null | awk '
        /Absolute upper-left X/ {x=$NF}
        /Absolute upper-left Y/ {y=$NF}
        /^ *Width:/             {w=$NF}
        /^ *Height:/            {h=$NF}
        END { if (x!="" && y!="" && w!="" && h!="") printf "%d,%d,%d,%d", x, y, x+w, y+h }')
fi
[[ -n "$APP_REGION" ]] || die "PHASE 0: could not derive the app window region."
say "PHASE 0: OCR region scoped to the app window: $APP_REGION"

say "--- database stats: every model is counted, Notes included ---"

# PHASE 0b - one row per model, before unlock.
say "PHASE 0b: seeding one task, one time entry and one note BEFORE unlock"
NOW="strftime('%Y-%m-%dT%H:%M:%S','now','localtime')"
SEED_SQL="DELETE FROM tasks WHERE id='stats-probe-task'; DELETE FROM time_entries WHERE id='stats-probe-entry'; DELETE FROM notes WHERE id='stats-probe-note';
INSERT INTO tasks (id, title, status, dimension_id, createdAt, updatedAt, dueDate, recurrenceEnabled, recurrenceRule, durationMinutes, impactLevel, goalAlignment, energyLevel, controlLevel, lifeIntentionCategory, dayBoundaryHour)
  VALUES ('stats-probe-task','StatsProbeTask','pending','dim_learning_growth',${NOW},${NOW},date('now','localtime')||'T23:59:59',0,'',30,'Moderate Impact','Moderate Alignment','Moderate','Self Dependent','Learning & Growth',0);
INSERT INTO time_entries (id, lifeIntentionCategory, startedAt, createdAt, updatedAt)
  VALUES ('stats-probe-entry','Learning & Growth',${NOW},${NOW},${NOW});
INSERT INTO notes (id, title, lifeIntentionCategory, createdAt, updatedAt)
  VALUES ('stats-probe-note','StatsProbeNote','Learning & Growth',${NOW},${NOW});
INSERT INTO app_settings (key, value, updatedAt) VALUES
  ('app_language','en',${NOW}), ('focus_mode_onboarding_completed','true',${NOW})
  ON CONFLICT(key) DO UPDATE SET value=excluded.value, updatedAt=excluded.updatedAt;"

SEED_OUT=$(sqlcipher "file:${DB_PATH}" -cmd "PRAGMA key='${PASSPHRASE}'" "${SEED_SQL}" 2>&1) \
    || die "PHASE 0b: seed failed: $SEED_OUT"
say "PHASE 0b: seed ok"

COUNTS=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" \
    "SELECT (SELECT count(*) FROM tasks)||'/'||(SELECT count(*) FROM time_entries)||'/'||(SELECT count(*) FROM notes);" 2>&1 | grep -v -x 'ok') \
    || die "PHASE 0b: count read-back failed: $COUNTS"
read -r T_COUNT _TE_COUNT _N_COUNT <<< "$(tr '/' ' ' <<< "$COUNTS")"
(( T_COUNT > 0 && _TE_COUNT > 0 && _N_COUNT > 0 )) \
    || die "PHASE 0b: the fixture is NOT non-empty (tasks/timeEntries/notes = $COUNTS); a zero on screen could not be attributed."
say "PHASE 0b: read-back ok - tasks/timeEntries/notes = $COUNTS"

# PHASE 1 - unlock.
click 254 336 2 "focus passphrase field" || die "PHASE 1: passphrase field click failed"
type_text "$PASSPHRASE"
click 254 416 3 "press unlock gate's Unlock button" || die "PHASE 1: Unlock click failed"
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
(( CLEAR >= 2 )) || die "PHASE 1: the unlock gate is still up. Stopping."
say "PHASE 1: unlocked"
sleep 3

# PHASE 2 - navigate to Settings. The rail's tab x positions are FIXED, and an OCR-derived x is not: this
# drive's first run read `Lenses` at x=344 and derived a Settings x of 389, which landed on the wrong tab
# (the frame's own `Tasks` title was then found at y=95, i.e. the drive never left Tasks). The measured
# geometry is the rail: Tasks 119, notes 307 (both MEASURED), pitch 47 -> Settings 401.
#
# THE CLICK IS TOLERATED, THE SCREEN IS VERIFIED. A tab click leaves the frame byte-identical when the app
# is ALREADY on that tab, and `click` treats that as a failure - which this drive's second run hit exactly
# (the 401 click DID navigate; the next candidate died on the unchanged frame). So the click is best-effort
# here and the destination is proven on CONTENT further down, after the scroll.
# EXACTLY ONE CLICK, THEN CONTENT DECIDES. A second click at a neighbouring x on a screen that is already
# Settings produces a byte-identical frame, and `click` treats that as fatal by calling `die` INSIDE the
# helper - so no `||` in this script can catch it (measured: run 3 died at the 405 candidate with the log
# showing PHASE 1 → FATAL, while the 401 click had in fact navigated). One click is therefore the maximum
# this drive may spend here; whether it landed is decided by PHASE 3c on content, after the scroll.
click 401 816 3 "nav to Settings at 401,816 (the measured rail point)" \
    || say "PHASE 2: 401,816 produced no frame change - carrying on; PHASE 3c decides on content"
sleep 2
shot 11-settings

# PHASE 3 - scroll the Database card into view (pointer to the window centre first: with the pointer
# resting on the rail the wheel scrolls nothing - measured by the sibling drives).
IFS=',' read -r _X1 _Y1 _X2 _Y2 <<< "$APP_REGION"
CX=$(( (_X1 + _X2) / 2 )); CY=$(( (_Y1 + _Y2) / 2 ))
xdotool mousemove --sync "$CX" "$CY"
sleep 1
for _ in $(seq 1 8); do xdotool click 5; sleep 0.4; done
sleep 2
say "PHASE 3: scrolled the Settings list toward the Database card"
shot 12-stats-scrolled

# PHASE 3b - the section may need expanding (its rows are behind the card header). Try to open it, then
# scroll again so the rows clear the bottom bar; a card that is already open just stays open.
if [[ -n "$(find_text_centre --text 'Database' --scale 3 --region "$APP_REGION" 2>/dev/null || true)" ]] \
|| [[ -n "$(find_text_centre --text 'Database' --scale 2 --region "$APP_REGION" 2>/dev/null || true)" ]]; then
    DB_XY=$(find_text_centre --text 'Database' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$DB_XY" ]] || DB_XY=$(find_text_centre --text 'Database' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
    read -r _dbx _dby <<< "$DB_XY"
    if (( _dby < NAV_Y_LIMIT )); then
        click "$_dbx" "$_dby" 3 "expand the Database card"
        sleep 1
        xdotool mousemove --sync "$CX" "$CY"
        for _ in $(seq 1 6); do xdotool click 5; sleep 0.4; done
        sleep 2
        say "PHASE 3b: Database card expanded and re-scrolled"
    else
        say "PHASE 3b: the only 'Database' match is in the rail (y=$_dby) - not expanding anything"
    fi
else
    say "PHASE 3b: no 'Database' header readable - proceeding to read the rows as they stand"
fi
shot 12b-stats-expanded

# PHASE 3c - VERIFY the screen before reading anything off it. 'Data Management', 'Database' and
# 'Clean Stale Files' are words only the Settings screen carries, and all three live below its fold, so
# they are checked AFTER the scroll. If none is readable the drive stops: reading a card on the wrong
# screen is exactly how a drive reports a pass for a screen it never opened.
ON_SETTINGS=""
for _needle in 'Data Management' 'Database' 'Clean Stale Files'; do
    for scale in 3 2; do
        XY=$(find_text_centre --text "$_needle" --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
        if [[ -n "$XY" ]]; then
            read -r _nx _ny <<< "$XY"
            if (( _ny < NAV_Y_LIMIT )); then
                ON_SETTINGS="$_needle at $XY"
                break 2
            fi
        fi
    done
done
[[ -n "$ON_SETTINGS" ]] \
    || die "PHASE 3c: none of the Settings-only words ('Data Management'/'Database'/'Clean Stale Files') is readable above the rail, so this is NOT the Settings screen. Frames 11-settings/12-stats-* are in the run dir; stopping rather than reading a card that is not there."
say "PHASE 3c: on the Settings screen (confirmed by '$ON_SETTINGS')"

# PHASE 4 - each model's label must be readable ABOVE the rail.
MISSING=()
for label in 'Tasks' 'Time Entries' 'Notes'; do
    FOUND_ABOVE=""
    for scale in 3 2; do
        XY=$(find_text_centre --text "$label" --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
        if [[ -n "$XY" ]]; then
            read -r _lx _ly <<< "$XY"
            if (( _ly < NAV_Y_LIMIT )); then
                FOUND_ABOVE="$XY (scale $scale)"
                break
            fi
        fi
    done
    if [[ -n "$FOUND_ABOVE" ]]; then
        say "PHASE 4: '$label' rendered in the card at $FOUND_ABOVE"
    else
        say "PHASE 4: '$label' NOT readable above y=$NAV_Y_LIMIT (a match in the rail does not count)"
        MISSING+=("$label")
    fi
done
(( ${#MISSING[@]} == 0 )) \
    || die "PHASE 4: the database-stats card did not render every model; missing above the rail: ${MISSING[*]}. Slide a copy of the frame for review."

say "=== PROVEN: the Settings database-stats card renders a row for Tasks, Time Entries and Notes ==="
say "--- database-stats drive finished ---"
