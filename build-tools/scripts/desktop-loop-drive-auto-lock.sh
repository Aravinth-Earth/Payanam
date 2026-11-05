#!/usr/bin/env bash
# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# The auto-lock session timer must follow REAL activity and must gate the FOREGROUND: with the
# timeout seeded to 1 minute, input every 20s keeps the app unlocked, and then ~90s of silence
# locks it with the app still in front. That is the gate's own sentence, run.
#
# HOW IT IS PROVEN
# The lock screen is the SHARED `DatabasePassphraseUnlockScreen`, whose action control is the word
# `Unlock` - the same needle the other drives already locate on this host. So:
#   PHASE A (activity, ~100s): a real click on the bottom-nav Tasks tab every 20s - idempotent
#     (re-navigating to the screen you are on) and unambiguously real input. Assert `Unlock` is NOT
#     readable at the end: a timer that ignored activity would have locked during this phase.
#   PHASE B (idle, ~100s): no input at all. Assert `Unlock` IS readable: the timer fires and the
#     lock covers the foreground.
# OCR is the arbiter of BOTH directions, so neither a "never locks" regression nor a "locks
# immediately" regression can pass.
#
# MEASURED PIXELS (1280x900 root): passphrase field (254,336), Unlock button (254,416), bottom-nav
# Bottom-nav rail, MEASURED 2026-10-09 from a real frame's OCR boxes (centre = tsv x + width/2):
#   Tasks 100, Habits 161, Journal 221, Notes 282, Lenses 343, Settings 404, all at y ~816.
# The earlier (79,830)/(126,830) pair was wrong: (126,830) sits in the GAP between two tabs, so the click
# changed nothing and the drive died on it - a missing tab is not a worked run.
set -uo pipefail

export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8

PASSPHRASE="${LOOP_PASSPHRASE:-LoopDrive#Pass9}"
DB_PATH="/home/cawax/Documents/Payanam-debug/db/payanam.db"
ACTIVITY_SECONDS="${LOOP_ACTIVITY_SECONDS:-100}"
IDLE_SECONDS="${LOOP_IDLE_SECONDS:-100}"

# PHASE 0 - scope OCR to the app window (derived at runtime, never hardcoded).
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

say "--- auto-lock: the timer follows activity and gates the foreground ---"

# PHASE 0b - seed the 1-minute timeout BEFORE unlock (no session is open, so the write cannot race the app).
say "PHASE 0b: seeding session_timeout_minutes=1 BEFORE unlock"
SEED_SQL="INSERT INTO app_settings (key, value, updatedAt) VALUES
  ('session_timeout_minutes','1', strftime('%Y-%m-%dT%H:%M:%S','now','localtime')),
  ('app_language','en', strftime('%Y-%m-%dT%H:%M:%S','now','localtime')),
  ('focus_mode_onboarding_completed','true', strftime('%Y-%m-%dT%H:%M:%S','now','localtime'))
  ON CONFLICT(key) DO UPDATE SET value=excluded.value, updatedAt=excluded.updatedAt;"
SEED_OUT=$(sqlcipher "file:${DB_PATH}" -cmd "PRAGMA key='${PASSPHRASE}'" "${SEED_SQL}" 2>&1) \
    || die "PHASE 0b: seed failed: $SEED_OUT"
SEEDED=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" \
    "SELECT value FROM app_settings WHERE key='session_timeout_minutes';" 2>&1 | grep -v -x 'ok') \
    || die "PHASE 0b: read-back failed: $SEEDED"
[[ "$SEEDED" == "1" ]] || die "PHASE 0b: session_timeout_minutes did not land (read '$SEEDED')."
say "PHASE 0b: read-back ok - the timeout is 1 minute"

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
(( CLEAR >= 2 )) || die "PHASE 1: the unlock gate is still up after the passphrase. Stopping."
say "PHASE 1: unlocked (the gate's 'Unlock' cleared twice)"
# A settle so the first activity lands in a stable app, not during the unlock transition.
sleep 3

# PHASE 2 - ACTIVITY: a real, VISIBLE navigation between two tabs every 20s for ACTIVITY_SECONDS.
# The timeout is 60s, so input at 20s spacing is comfortably inside it; a timer that ignored real
# activity would lock at ~60s, i.e. INSIDE this phase, and the assertion below would fail.
#
# WHY IT ALTERNATES: `click` dies when the frame is byte-identical after settling - a deliberate guard
# against blind taps. Re-clicking the tab you are already on produces exactly that (measured on this
# drive's first run: the footer read "the drive file exited 1" while PHASE 2 was on screen). Alternating
# Tasks -> Habits -> Tasks changes the screen on every click, so each one is a provable tap AND real
# user input.
say "PHASE 2: activity phase - alternating Tasks/Habits every 20s for ${ACTIVITY_SECONDS}s"
end=$(( SECONDS + ACTIVITY_SECONDS ))
_step=0
while (( SECONDS < end )); do
    if (( _step % 2 == 0 )); then
        click 100 816 1 "activity: Tasks tab" || die "PHASE 2: activity click (Tasks) failed"
    else
        click 161 816 1 "activity: Habits tab" || die "PHASE 2: activity click (Habits) failed"
    fi
    _step=$(( _step + 1 ))
    sleep 20
done
LOCKED_AFTER_ACTIVITY=""
for _scale in 3 2; do
    if [[ -n "$(find_text_centre --text 'Unlock' --scale "$_scale" --region "$APP_REGION" 2>/dev/null || true)" ]]; then
        LOCKED_AFTER_ACTIVITY="yes"
    fi
done
[[ -z "$LOCKED_AFTER_ACTIVITY" ]] \
    || die "PHASE 2: FAILURE - the lock screen is up after ${ACTIVITY_SECONDS}s of real activity with a 1-minute timeout, so the timer ignored the activity."
say "PHASE 2: still unlocked after ${ACTIVITY_SECONDS}s of real activity - the timer followed the input"

# PHASE 3 - IDLE: no input for IDLE_SECONDS, then the lock must be readable.
# No xdotool call of any kind may run in this window (a pointer MOVE could count as activity), so
# the only thing this phase does is sleep and then read the screen.
say "PHASE 3: idle phase - no input for ${IDLE_SECONDS}s, then the lock must be up"
sleep "$IDLE_SECONDS"

LOCK_XY=""
for _scale in 3 2; do
    LOCK_XY=$(find_text_centre --text 'Unlock' --scale "$_scale" --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$LOCK_XY" ]] && break
done
[[ -n "$LOCK_XY" ]] \
    || die "PHASE 3: FAILURE - after ${IDLE_SECONDS}s of silence the app is still unlocked; the timer did not fire."
say "PHASE 3: the lock screen is up at $LOCK_XY after ${IDLE_SECONDS}s of silence"

say "=== PROVEN: with a 1-minute timeout, real activity kept the app unlocked and silence locked the foreground ==="
say "--- auto-lock drive finished ---"
