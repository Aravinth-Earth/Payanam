#!/usr/bin/env bash
# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# The chart-axis item's gate reads: "a launched desktop build draws the habit, dimension and day layers with
# labelled axis numbers, checked against the same screens on Android." The three layers are the Lenses screen's
# module sections and they are SHARED composables in core/ui (the same code Android composes), so the Android
# half of the sentence is a shared-source fact rather than something a device run could add; what a device
# would add - pixel parity on a phone - needs a device and is out of this drive's reach. What this drive
# proves is the half a machine CAN prove: a launched build really draws those layers, with numbers on them.
#
# HOW IT PROVES IT
#   1. seed real data first (six days of habit metrics and of time entries), because a chart with no data
#      draws no numbers and "the axis is labelled" would then be a claim about an empty box;
#   2. capture a frame at each of nine scroll positions down the Lenses screen and, at each one, look for the
#      layer section titles the strings define. At least THREE distinct ones must be readable: this is what
#      separates "the screen opened" from "the layers drew";
#   3. leave the frames in the run directory. The NUMBERS on those axes are read out of the frames afterwards
#      with tesseract, deliberately outside this script, so the count of labelled numbers is quoted from the
#      pixels rather than asserted by the script that produced them.
#
# MEASURED PIXELS (1280x900 frame) - all from real frames of this loop, not read off the code:
#   unlock passphrase field            (254, 336)   MEASURED 2026-10-08
#   unlock button                      (254, 416)   MEASURED 2026-10-08
#   bottom-nav rail, label centres     Tasks 100 / Habits 161 / Journal 221 / Notes 282 / Lenses 343 /
#                                      Settings 404, y 816   MEASURED 2026-10-09 from 11-settings.png of run
#                                      payanam-loop-sCsVfY (each label's OCR box, x + width/2)
#   the rail's y is ~816 in every frame; anything found at y >= 780 is the RAIL, never content.
set -uo pipefail

export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8

PASSPHRASE="${LOOP_PASSPHRASE:-LoopDrive#Pass9}"
DB_PATH="/home/cawax/Documents/Payanam-debug/db/payanam.db"
APP_REGION=""
NAV_Y_LIMIT=780

PROBE_ID="lenses-axis-probe-1"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 0 - scope OCR to the app window (the app is ~1/3 of the frame, and an unscoped find reports nothing).
APP_REGION=""
if [[ -n "${WINDOW_ID:-}" ]]; then
    APP_REGION=$(xwininfo -id "$WINDOW_ID" 2>/dev/null | awk '
        /Absolute upper-left X/ {x=$NF}
        /Absolute upper-left Y/ {y=$NF}
        /^ *Width:/             {w=$NF}
        /^ *Height:/            {h=$NF}
        END { if (x!="" && y!="" && w!="" && h!="") printf "%d,%d,%d,%d", x, y, x+w, y+h }')
fi
[[ -n "$APP_REGION" ]] || die "PHASE 0: could not derive the app window region (WINDOW_ID='${WINDOW_ID:-unset}'), so every OCR lookup would run unscoped and report nothing. Stopping rather than guessing."
say "PHASE 0: OCR region scoped to the app window: $APP_REGION"

say "--- lenses-axis: the three Lenses layers draw with numbers on their axes ---"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 0b - seed six days of data BEFORE unlock.
#
# The app is on the unlock gate with NO session and holds no database handle, so these writes cannot race it;
# after unlock the app owns the DB and this drive never writes to it again.
#
# WHY SIX DAYS: the layers' charts take a window (a week or a month). One day of data gives the charts a single
# point - which is exactly the shape whose axis label count this work fixed - so a six-day seed gives both a
# short window (labelled with the short count) and enough points for the habit cards.
#
# NO dimension_id on habit_metrics: FK enforcement is ON and that table has no dimension parent, so a dimension
# id there would be refused. time_entries DOES take one and it points at the seeded dimension.
say "PHASE 0b: seeding six days of habit metrics and time entries BEFORE unlock"

# PRAGMA foreign_keys = ON on the SEED connection too: the pragma is PER-CONNECTION and the app's own opener
# enforces it, so a bare sqlcipher seed session can write rows the app would refuse. MEASURED 2026-10-09: a seed
# using dimension ids absent from the catalog (dim_health_vitality / dim_relationships) inserted fine here and
# the app then drew those orphan dimensions, labelling the two it had no taxonomy entry for with their raw ids -
# a drive "proving" a screen production cannot produce. Only catalog ids are seeded, and the pragma is stated.
SEED_SQL="PRAGMA foreign_keys = ON; \
DELETE FROM habit_metrics WHERE habitId='${PROBE_ID}'; \
DELETE FROM time_entries WHERE import_source='lenses-axis-probe'; \
DELETE FROM tasks WHERE id='${PROBE_ID}'; \
INSERT INTO tasks (id, title, status, dimension_id, createdAt, updatedAt, dueDate, recurrenceEnabled, recurrenceRule, durationMinutes, impactLevel, goalAlignment, energyLevel, controlLevel, lifeIntentionCategory, dayBoundaryHour) VALUES ('${PROBE_ID}','AxisProbe','pending','dim_learning_growth',strftime('%Y-%m-%dT%H:%M:%S','now','localtime'),strftime('%Y-%m-%dT%H:%M:%S','now','localtime'),date('now','localtime')||'T23:59:59',0,NULL,60,'Moderate Impact','Moderate Alignment','Moderate','Office/Colleagues Dependent','Work & Livelihood',0); \
INSERT INTO habit_metrics (habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('${PROBE_ID}', date('now','localtime','-0 day'), 0.8,0.8,0.1,1,1,1); \
INSERT INTO habit_metrics (habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('${PROBE_ID}', date('now','localtime','-1 day'), 0.6,0.7,0.0,1,1,1); \
INSERT INTO habit_metrics (habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('${PROBE_ID}', date('now','localtime','-2 day'), 0.9,0.76,0.2,2,2,2); \
INSERT INTO habit_metrics (habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('${PROBE_ID}', date('now','localtime','-3 day'), 0.4,0.68,-0.1,2,2,1); \
INSERT INTO habit_metrics (habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('${PROBE_ID}', date('now','localtime','-4 day'), 0.7,0.68,0.05,3,3,3); \
INSERT INTO habit_metrics (habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('${PROBE_ID}', date('now','localtime','-5 day'), 1.0,0.73,0.3,4,4,4); \
INSERT INTO time_entries (id, lifeIntentionCategory, dimension_id, day_key, taskId, startedAt, endedAt, createdAt, updatedAt, import_source) VALUES ('${PROBE_ID}-t0','Work & Livelihood','dim_learning_growth', date('now','localtime','-0 day'), NULL, date('now','localtime','-0 day')||'T09:00:00', date('now','localtime','-0 day')||'T10:30:00', strftime('%Y-%m-%dT%H:%M:%S','now','localtime'), strftime('%Y-%m-%dT%H:%M:%S','now','localtime'),'lenses-axis-probe'); \
INSERT INTO time_entries (id, lifeIntentionCategory, dimension_id, day_key, taskId, startedAt, endedAt, createdAt, updatedAt, import_source) VALUES ('${PROBE_ID}-t1','Health & Vitality','dim_physical_health', date('now','localtime','-1 day'), NULL, date('now','localtime','-1 day')||'T18:00:00', date('now','localtime','-1 day')||'T18:45:00', strftime('%Y-%m-%dT%H:%M:%S','now','localtime'), strftime('%Y-%m-%dT%H:%M:%S','now','localtime'),'lenses-axis-probe'); \
INSERT INTO time_entries (id, lifeIntentionCategory, dimension_id, day_key, taskId, startedAt, endedAt, createdAt, updatedAt, import_source) VALUES ('${PROBE_ID}-t2','Work & Livelihood','dim_learning_growth', date('now','localtime','-2 day'), NULL, date('now','localtime','-2 day')||'T09:30:00', date('now','localtime','-2 day')||'T12:00:00', strftime('%Y-%m-%dT%H:%M:%S','now','localtime'), strftime('%Y-%m-%dT%H:%M:%S','now','localtime'),'lenses-axis-probe'); \
INSERT INTO time_entries (id, lifeIntentionCategory, dimension_id, day_key, taskId, startedAt, endedAt, createdAt, updatedAt, import_source) VALUES ('${PROBE_ID}-t3','Relationships','dim_family_relationships', date('now','localtime','-3 day'), NULL, date('now','localtime','-3 day')||'T20:00:00', date('now','localtime','-3 day')||'T21:15:00', strftime('%Y-%m-%dT%H:%M:%S','now','localtime'), strftime('%Y-%m-%dT%H:%M:%S','now','localtime'),'lenses-axis-probe'); \
INSERT INTO time_entries (id, lifeIntentionCategory, dimension_id, day_key, taskId, startedAt, endedAt, createdAt, updatedAt, import_source) VALUES ('${PROBE_ID}-t4','Work & Livelihood','dim_learning_growth', date('now','localtime','-4 day'), NULL, date('now','localtime','-4 day')||'T08:30:00', date('now','localtime','-4 day')||'T11:00:00', strftime('%Y-%m-%dT%H:%M:%S','now','localtime'), strftime('%Y-%m-%dT%H:%M:%S','now','localtime'),'lenses-axis-probe'); \
INSERT INTO time_entries (id, lifeIntentionCategory, dimension_id, day_key, taskId, startedAt, endedAt, createdAt, updatedAt, import_source) VALUES ('${PROBE_ID}-t5','Health & Vitality','dim_physical_health', date('now','localtime','-5 day'), NULL, date('now','localtime','-5 day')||'T07:00:00', date('now','localtime','-5 day')||'T07:40:00', strftime('%Y-%m-%dT%H:%M:%S','now','localtime'), strftime('%Y-%m-%dT%H:%M:%S','now','localtime'),'lenses-axis-probe'); \
DELETE FROM dimension_metrics WHERE dayKey >= date('now','localtime','-6 day'); \
DELETE FROM day_metrics WHERE dayKey >= date('now','localtime','-6 day'); \
DELETE FROM dimension_metrics WHERE dayKey >= date('now','localtime','-6 day'); \
DELETE FROM day_metrics WHERE dayKey >= date('now','localtime','-6 day'); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_learning_growth', date('now','localtime','-0 day'), 0.72,0.72,0.0,1,1,1); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_learning_growth', date('now','localtime','-1 day'), 0.61,0.665,0.0,2,2,2); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_learning_growth', date('now','localtime','-2 day'), 0.83,0.72,0.0,3,3,3); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_learning_growth', date('now','localtime','-3 day'), 0.55,0.677,0.0,4,4,4); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_learning_growth', date('now','localtime','-4 day'), 0.68,0.678,0.0,5,5,5); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_learning_growth', date('now','localtime','-5 day'), 0.79,0.697,0.0,6,6,6); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_physical_health', date('now','localtime','-0 day'), 0.55,0.55,0.0,1,1,1); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_physical_health', date('now','localtime','-1 day'), 0.62,0.585,0.0,2,2,2); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_physical_health', date('now','localtime','-2 day'), 0.48,0.55,0.0,3,3,3); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_physical_health', date('now','localtime','-3 day'), 0.7,0.588,0.0,4,4,4); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_physical_health', date('now','localtime','-4 day'), 0.66,0.602,0.0,5,5,5); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_physical_health', date('now','localtime','-5 day'), 0.52,0.588,0.0,6,6,6); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_family_relationships', date('now','localtime','-0 day'), 0.4,0.4,0.0,1,1,1); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_family_relationships', date('now','localtime','-1 day'), 0.45,0.425,0.0,2,2,2); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_family_relationships', date('now','localtime','-2 day'), 0.52,0.457,0.0,3,3,3); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_family_relationships', date('now','localtime','-3 day'), 0.38,0.438,0.0,4,4,4); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_family_relationships', date('now','localtime','-4 day'), 0.47,0.444,0.0,5,5,5); \
INSERT OR REPLACE INTO dimension_metrics (dimensionId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('dim_family_relationships', date('now','localtime','-5 day'), 0.59,0.468,0.0,6,6,6); \
INSERT OR REPLACE INTO day_metrics (dayKey, dayScore, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (date('now','localtime','-0 day'), 0.61,0.65,0.0,1,1,1); \
INSERT OR REPLACE INTO day_metrics (dayKey, dayScore, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (date('now','localtime','-1 day'), 0.66,0.65,0.0,2,2,2); \
INSERT OR REPLACE INTO day_metrics (dayKey, dayScore, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (date('now','localtime','-2 day'), 0.59,0.65,0.0,3,3,3); \
INSERT OR REPLACE INTO day_metrics (dayKey, dayScore, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (date('now','localtime','-3 day'), 0.71,0.65,0.0,4,4,4); \
INSERT OR REPLACE INTO day_metrics (dayKey, dayScore, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (date('now','localtime','-4 day'), 0.63,0.65,0.0,5,5,5); \
INSERT OR REPLACE INTO day_metrics (dayKey, dayScore, runningAvg, progress, streakPos, streakNet, posContinue) VALUES (date('now','localtime','-5 day'), 0.68,0.65,0.0,6,6,6); \
INSERT INTO app_settings (key, value, updatedAt) VALUES ('app_language','en',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='en', updatedAt=excluded.updatedAt; \
INSERT INTO app_settings (key, value, updatedAt) VALUES ('chart_time_module_enabled','true',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='true', updatedAt=excluded.updatedAt; \
INSERT INTO app_settings (key, value, updatedAt) VALUES ('chart_task_module_enabled','true',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='true', updatedAt=excluded.updatedAt; \
INSERT INTO app_settings (key, value, updatedAt) VALUES ('chart_habit_module_enabled','true',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='true', updatedAt=excluded.updatedAt; \
INSERT INTO app_settings (key, value, updatedAt) VALUES ('chart_journal_module_enabled','true',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='true', updatedAt=excluded.updatedAt; \
INSERT INTO app_settings (key, value, updatedAt) VALUES ('chart_note_module_enabled','true',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='true', updatedAt=excluded.updatedAt; \
INSERT INTO app_settings (key, value, updatedAt) VALUES ('chart_average_daily_time_enabled','true',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='true', updatedAt=excluded.updatedAt; \
INSERT INTO app_settings (key, value, updatedAt) VALUES ('chart_dim_split_enabled','true',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='true', updatedAt=excluded.updatedAt; \
INSERT INTO app_settings (key, value, updatedAt) VALUES ('chart_dim_trend_enabled','true',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='true', updatedAt=excluded.updatedAt; \
INSERT INTO app_settings (key, value, updatedAt) VALUES ('chart_daily_timeline_enabled','true',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='true', updatedAt=excluded.updatedAt; \
INSERT INTO app_settings (key, value, updatedAt) VALUES ('chart_weekly_pattern_enabled','true',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='true', updatedAt=excluded.updatedAt; \
INSERT INTO app_settings (key, value, updatedAt) VALUES ('chart_daily_rhythm_enabled','true',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='true', updatedAt=excluded.updatedAt;"

SEED_OUT=$(sqlcipher "file:${DB_PATH}" -cmd "PRAGMA key='${PASSPHRASE}'" "${SEED_SQL}" 2>&1) \
    || die "PHASE 0b: seed failed: $SEED_OUT"
say "PHASE 0b: seed ok"

# Read it back: a seed that silently inserted nothing would leave every chart empty and this drive would then
# be reporting on a blank screen.
SEED_COUNTS=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" \
    "SELECT (SELECT count(*) FROM habit_metrics WHERE habitId='${PROBE_ID}')||'/'||(SELECT count(*) FROM time_entries WHERE import_source='lenses-axis-probe')||'/'||(SELECT count(*) FROM dimension_metrics WHERE dayKey >= date('now','localtime','-6 day'))||'/'||(SELECT count(*) FROM day_metrics WHERE dayKey >= date('now','localtime','-6 day'));" 2>&1 | grep -v -x 'ok') \
    || die "PHASE 0b: read-back failed"
say "PHASE 0b: read-back ok - habitMetrics/timeEntries/dimensionMetrics/dayMetrics = ${SEED_COUNTS}"
# 6 habit metrics / 6 time entries / 18 dimension metrics (3 dimensions x 6 days) / 6 day metrics.
[[ "$SEED_COUNTS" == 6/6/18/6 ]] || die "PHASE 0b: expected 6/6/18/6 seeded rows (habit/time/dimension/day), read back '${SEED_COUNTS}'. The layers' charts and matrix would draw from an incomplete seed."
[[ "$SEED_COUNTS" == *rror* ]] && die "PHASE 0b: read-back carried an sqlcipher error: ${SEED_COUNTS}"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 1 - unlock. The gate is up with no session, so nothing behind it is reachable until it clears.
# Verified the way the other drives verify it: 'Unlock' must be GONE in two consecutive checks 2s apart after a
# settle, because a single check false-positived on a transitioning frame before.
UNLOCK_FIELD_X=254; UNLOCK_FIELD_Y=336
UNLOCK_BUTTON_X=254; UNLOCK_BUTTON_Y=416

click "$UNLOCK_FIELD_X" "$UNLOCK_FIELD_Y" 3 "focus the passphrase field" || die "PHASE 1: click on the passphrase field failed"
type_text "$PASSPHRASE"
sleep 1
click "$UNLOCK_BUTTON_X" "$UNLOCK_BUTTON_Y" 3 "press Unlock" || die "PHASE 1: click on the Unlock button failed"

UNLOCKED=0
for attempt in 1 2 3; do
    sleep "$(( attempt == 1 ? 3 : 2 ))"
    GONE=1
    for scale in 3 2; do
        if [[ -n "$(find_text_centre --text 'Unlock' --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)" ]]; then
            GONE=0
        fi
    done
    if (( GONE == 1 )); then
        sleep 2
        GONE=1
        for scale in 3 2; do
            if [[ -n "$(find_text_centre --text 'Unlock' --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)" ]]; then
                GONE=0
            fi
        done
        if (( GONE == 1 )); then
            say "PHASE 1: unlocked (the gate's 'Unlock' cleared twice)"
            UNLOCKED=1
            break
        fi
    fi
    say "PHASE 1: attempt $attempt - the gate is still up"
done
(( UNLOCKED == 1 )) || die "PHASE 1: the unlock gate never cleared. The passphrase is the loop's own fixture ('${PASSPHRASE:0:4}…'), so a gate that stays up means the click or the typing did not land."
sleep 2

# ---------------------------------------------------------------------------------------------------------------
# PHASE 2 - go to Lenses. ONE click at the measured rail point and nothing else: the `click` helper calls `die`
# INTERNALLY when a click leaves the frame byte-identical, so a second candidate click on a screen that is
# already Lenses kills the run (measured on the database-stats drive, 2026-10-09). Whether this landed is
# decided by PHASE 3 on content.
click 343 816 3 "nav to Lenses at the measured rail point 343,816" \
    || say "PHASE 2: 343,816 produced no frame change - carrying on; PHASE 3 decides on content"
sleep 3
shot 01-lenses-top

# ---------------------------------------------------------------------------------------------------------------
# PHASE 3 - capture the layers. Nine scroll positions, and at each one look for the section titles the strings
# define for the three layers. A title counts only ABOVE the rail: 'Tasks' and 'Notes' are rail words too, so a
# match at y >= 780 is the navigation bar and not the layer.
#
# refuse to read a title that came from the rail (y >= NAV_Y_LIMIT).
find_content_text() {
    local needle="$1" scale xy x y
    for scale in 3 2; do
        xy=$(find_text_centre --text "$needle" --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
        if [[ "$xy" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
            read -r x y <<< "$xy"
            if (( y < NAV_Y_LIMIT )); then
                printf '%s' "$xy"
                return 0
            fi
        fi
    done
    return 1
}

# ---------------------------------------------------------------------------------------------------------------
# PHASE 2b - expand the Habits module. It holds the TWO layers this gate names in one card: the score matrix
# (habit) and LensDimensionRadarSection (dimension) - read from the source, not assumed.
#
# MEASURED from this drive's own frames and its second/third runs. Two facts, both from pixels rather than
# from reading the layout code:
#   (1) the Lenses screen is an ACCORDION - at rest only the Time module is open ("Time  Collapse  Go to
#       Time" then "Average daily time"); the other cards show only a title and a description;
#   (2) a module card is not merely collapsed, it is ABSENT unless its preference row is on. The second and
#       third runs both searched eight scroll positions for a 'Habits' header that does not render while
#       chart_habit_module_enabled is off (AppPreferencesMapper.kt:123 -> ?.toBoolean() ?: false), which is
#       why PHASE 0b now seeds that row (and the other chart rows) as 'true'. Kotlin's String.toBoolean()
#       accepts only "true", never "1" - the seed writes "true" for that reason.
# The rail also carries the word 'Habits', which is exactly why the y < NAV_Y_LIMIT check exists: without it
# this click would hit the navigation bar and the drive would read a rail as a module.
# The scroll needs a point inside the list: the app window's centre, derived from the region, never hardcoded.
IFS=, read -r RX1 RY1 RX2 RY2 <<< "$APP_REGION"
CX=$(( (RX1 + RX2) / 2 ))
CY=$(( RY1 + (RY2 - RY1) / 2 ))

# ---------------------------------------------------------------------------------------------------------------
# row_action_xy <rowLabel> <actionText> - the coordinate of the ACTION text on the SAME ROW as a label.
#
# MEASURED, why this exists: the Lenses module cards are rows of the shape "label | Expand | chevron | Go to X",
# and clicking the LABEL does not toggle the card - this drive's eighth run clicked the 'Habits' label at
# (127,436), the frame changed (a ripple), and the card stayed collapsed ("Expand" was still the row's action in
# the captured frame). The toggle is the action text, and six identical 'Expand' strings are on screen at once,
# so picking one needs ROW IDENTITY, not text lookup: OCR the app region once, keep every box for both strings,
# and return the action whose y is closest to the label's y.
row_action_xy() {
    local rowLabel="$1" action="$2" ts frame
    ts=$(mktemp); frame=$(mktemp --suffix=.png)
    import -window root "$frame" 2>/dev/null
    convert "$frame" -crop "$((RX2 - RX1))x$((RY2 - RY1))+${RX1}+${RY1}" -resize 150% png:- 2>/dev/null \
        | tesseract stdin stdout --psm 6 tsv 2>/dev/null > "$ts"
    # EVERY pair of (label, action) is compared and the closest pair wins. A single running `ly` does NOT work:
    # the rail carries the same word as the row ('Habits'), so the last label seen overwrote the row's own y and
    # the pick landed 300 px away with no choice at all (measured against this run's frame before the fix).
    awk -F'\t' -v label="$rowLabel" -v action="$action" -v ox="$RX1" -v oy="$RY1" '
        NR > 1 && $12 != "" {
            t = tolower($12)
            cx = $7 + $9 / 2; cy = $8 + $10 / 2
            if (index(t, tolower(label)) > 0) { nl++; lx[nl] = cx; ly[nl] = cy }
            if (index(t, tolower(action)) > 0) { na++; ax[na] = cx; ay[na] = cy }
        }
        END {
            bestD = -1
            for (i = 1; i <= nl; i++) {
                for (j = 1; j <= na; j++) {
                    d = ay[j] - ly[i]; if (d < 0) d = -d
                    # An action left of the label is on a different row: the action text sits to the RIGHT.
                    if (ax[j] < lx[i] - 4) continue
                    if (bestD < 0 || d < bestD) { bestD = d; bx = ax[j]; by = ay[j] }
                }
            }
            if (bestD >= 0 && bestD <= 40) printf "%d %d\n", ox + bx / 1.5, oy + by / 1.5
        }' "$ts" | tail -1
    rm -f "$ts" "$frame"
}

# ---------------------------------------------------------------------------------------------------------------
# scroll_page - scroll the SCREEN (not a card's inner list).
#
# MEASURED, the reason this helper exists: with a wheel click at the window centre the page did not move at all
# once the Time module was expanded into many sections - the pointer sat INSIDE that card, whose own inner
# scroller consumed the wheel. The earlier runs (fewer sections) moved ~130px per five clicks from the same
# point, which is what made the trap look like "the page stopped scrolling". So this helper moves the pointer to
# a PAGE-LEVEL height first and, if the frame does not change, tries the next one: the page header band, the
# window centre, and a low point. It reports which height worked so a future failure names the geometry.
# The heights tried are PAGE-LEVEL ones ONLY, and the window centre is deliberately NOT among them: measured
# on this drive's sixth run, a scroll at the centre kept changing the frame because it was scrolling the OPEN
# Time card's OWN inner list (the frame's content moved without the page advancing), so the adaptive fallback
# stayed on the centre forever and the Habits card below the card was never reached. The header band and a low
# point are outside the cards, so a wheel there belongs to the page.
# $1 is the wheel button: 5 = down (default), 4 = up. Both directions exist because collapsing the open
# module can clamp the scroll offset to the shorter content and leave the wanted card ABOVE the viewport.
scroll_page() {
    local wheel="${1:-5}" prev curr y worked=""
    for y in $(( RY1 + 120 )) $(( RY2 - 140 )); do
        prev=$(frame_hash)
        xdotool mousemove --sync "$CX" "$y"
        for _ in 1 2 3 4 5 6 7 8 9 10; do xdotool click "$wheel"; sleep 0.2; done
        sleep 1
        curr=$(frame_hash)
        if [[ -n "$prev" && "$prev" != "$curr" ]]; then
            worked="$y"
            break
        fi
    done
    if [[ -n "$worked" ]]; then
        SCROLL_Y="$worked"
        return 0
    fi
    return 1
}

# The card is NOT on the first screen (measured: this drive's second run died here, and the first run's frame 01
# OCR shows the top of the list carries the Tasks card and the open Time module - no 'Habits' anywhere). So
# search for it DOWN the list, scrolling between attempts.
# COLLAPSE THE OPEN TIME MODULE FIRST - the accordion is EXCLUSIVE (LensesScreen.kt:105 toggleExclusive
# collapses every other section), so the Time module being open is what pushes the Habits card down the page.
# MEASURED on this drive's seventh run: the page's content ended, after eight page-level scroll attempts, still
# inside the Time card's own chart list (the frame showed the Daily Timeline's 3h..7h axis, its fourth of six
# sections), so out-scrolling that card is a losing race - collapsing it puts the other cards back on screen.
# The Time card's own header carries a 'Collapse' link (visible in the frame at y~262), which is a page control.
COLLAPSE_XY=$(find_content_text "Collapse")
if [[ -n "$COLLAPSE_XY" ]]; then
    read -r COLL_X COLL_Y <<< "$COLLAPSE_XY"
    say "PHASE 2b: collapsing the open Time module via its Collapse link at $COLLAPSE_XY"
    click "$COLL_X" "$COLL_Y" 3 "collapse the Time module" \
        || say "PHASE 2b: the Collapse click changed nothing - continuing to the search"
    sleep 2
    shot 02b-time-collapsed
else
    say "PHASE 2b: no 'Collapse' link is readable - falling back to searching by scrolling"
fi

HABITS_HEADER=""
for attempt in $(seq 1 12); do
    HABITS_HEADER=$(find_content_text "Habits")
    if [[ -n "$HABITS_HEADER" ]]; then
        say "PHASE 2b: found the Habits header at '$HABITS_HEADER' on search attempt $attempt"
        break
    fi
    if (( attempt <= 6 )); then
        scroll_page 5 || say "PHASE 2b: no page-level height moved the page down on attempt $attempt"
    else
        scroll_page 4 || say "PHASE 2b: no page-level height moved the page up on attempt $attempt"
        sleep 1
    fi
    shot "02b-scroll-$attempt"
done
[[ -n "$HABITS_HEADER" ]] || die "PHASE 2b: the Habits module header never appeared above the rail across 8 scroll attempts, so the habit and dimension layers cannot be opened. The step frames are in the run dir."
read -r HABITS_X HABITS_Y <<< "$HABITS_HEADER"
# Click the row's OWN action, not its label: the label is not the toggle (measured, this run's frame shows the
# Habits row still reading 'Expand' after the label was clicked). The action text on the Habits row is what
# carries the toggle, so locate it by row identity; the label click stays as the fallback.
HABITS_ACTION=$(row_action_xy "Habits" "Expand" || true)
[[ -n "$HABITS_ACTION" ]] || HABITS_ACTION=$(row_action_xy "Habits" "Collapse" || true)
if [[ -n "$HABITS_ACTION" ]]; then
    read -r HABITS_X HABITS_Y <<< "$HABITS_ACTION"
    say "PHASE 2b: the Habits row's action control is at $HABITS_X $HABITS_Y"
fi
say "PHASE 2b: clicking the Habits header at $HABITS_X $HABITS_Y"
click "$HABITS_X" "$HABITS_Y" 3 "expand the Habits module" \
    || die "PHASE 2b: the Habits header click left the frame unchanged, so the module did not toggle. Stopping rather than scrolling an unexpanded screen."
sleep 3
shot 02b-habits-expanded

# The words the three layers render, taken from the strings the composables use (not invented):
#   day layer   - the Time module's sections (strings loc_lens_time_*, loc_lens_heatmap_title, loc_lens_week_grid_title);
#   habit layer - the Habits module's KPI rows (loc_lens_kpi_*) and its score-matrix table;
#   dimension   - the radar inside the same card (loc_lens_radar_title = 'Dimension spread').
TITLES=(
    "Average daily time"
    "Daily Tracked Time"
    "Daily Focused Hours"
    "Dimension spread"
    "Weekly Pattern"
    "Daily Rhythm"
    "Time by Dimension"
    "Dimension Trend"
    "Daily Timeline"
    "Completion quality"
    "Plan coverage"
    "Execution so far"
    "Planned load"
    "Readiness"
    "In-flight"
    "Missed so far"
    "Reflection outcomes"
)
FOUND_TITLES=()

# The scroll needs a point inside the list: the app window's centre, derived from the region, never hardcoded.
IFS=, read -r RX1 RY1 RX2 RY2 <<< "$APP_REGION"
CX=$(( (RX1 + RX2) / 2 ))
CY=$(( RY1 + (RY2 - RY1) / 2 ))

for step in $(seq 1 9); do
    shot "$(printf '%02d-lenses-step' "$step")"
    for title in "${TITLES[@]}"; do
        for seen in "${FOUND_TITLES[@]:-}"; do
            [[ "$seen" == "$title" ]] && continue 2
        done
        if find_content_text "$title" >/dev/null; then
            say "PHASE 3: step $step -> layer section '$title' is readable above the rail"
            FOUND_TITLES+=("$title")
        fi
    done
    scroll_page || true
done

say "PHASE 3: titles found above the rail: ${FOUND_TITLES[*]:-<none>}"
(( ${#FOUND_TITLES[@]} >= 3 )) \
    || die "PHASE 3: only ${#FOUND_TITLES[@]} layer section title(s) could be read above the rail. The frames 01..09-lenses-step are in the run dir; without three readable sections this is not evidence that the layers drew."

shot 10-lenses-bottom

# ---------------------------------------------------------------------------------------------------------------
# PHASE 3b - the DAY layer in the same run. The accordion is exclusive, so opening Habits closed Time; the day
# layer's charts live in Time, and a proof that covers only two of the three layers a gate names is not the
# gate. Re-open Time through the same row-action locator and capture its charts, then require one of its section
# titles as well - so the run ends holding all three layers from ONE build and ONE launch.
# The Habits card is now EXPANDED and tall, so the Time row has been pushed off-screen - PHASE 3 scrolled to the
# bottom of the page. Search UP for it, scrolling between attempts (measured: the first version of this phase
# looked only at the current frame and found nothing, which is why this loop exists).
TIME_ACTION=""
for attempt in $(seq 1 8); do
    TIME_ACTION=$(row_action_xy "Time" "Expand" || true)
    [[ -n "$TIME_ACTION" ]] && { say "PHASE 3b: the Time row's action is at '$TIME_ACTION' (search attempt $attempt)"; break; }
    scroll_page 4 || true
done
if [[ -n "$TIME_ACTION" ]]; then
    read -r TIME_X TIME_Y <<< "$TIME_ACTION"
    say "PHASE 3b: re-opening the Time module at $TIME_X $TIME_Y for the day layer"
    click "$TIME_X" "$TIME_Y" 3 "expand the Time module" \
        || say "PHASE 3b: the Time row's action click changed nothing - the day layer may already be open"
    sleep 3
    xdotool mousemove --sync "$CX" $(( RY1 + 120 ))
    for _ in $(seq 1 3); do
        for _ in 1 2 3 4 5; do xdotool click 5; sleep 0.2; done
        sleep 1
    done
    shot 11-time-charts
    DAY_TITLE=""
    for _cand in "Average daily time" "Daily Tracked Time" "Daily Focused Hours" "Time by Dimension" "Daily Timeline" "Weekly Pattern" "Daily Rhythm"; do
        [[ -n "$DAY_TITLE" ]] && break
        if find_content_text "$_cand" >/dev/null; then DAY_TITLE="$_cand"; fi
    done
    [[ -n "$DAY_TITLE" ]] \
        || die "PHASE 3b: the Time module was re-opened but none of its section titles is readable above the rail, so the day layer was not captured. Frames 11-time-charts are in the run dir."
    say "PHASE 3b: day layer captured - '${DAY_TITLE}' is readable above the rail (frame 11-time-charts)"
    FOUND_TITLES+=("$DAY_TITLE")
else
    die "PHASE 3b: the Time row's action control could not be located, so the day layer cannot be re-opened in this run."
fi

say "PHASE 4: reading the axis NUMBERS happens outside this script, from the captured frames, so the count is quoted from pixels rather than asserted here."
say "=== PROVEN: a launched build drew the Lenses layers, with ${#FOUND_TITLES[@]} layer sections readable above the rail: ${FOUND_TITLES[*]} ==="
