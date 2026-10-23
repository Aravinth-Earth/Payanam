#!/usr/bin/env bash
# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# Prove that completing a recurring habit from the Tasks shell rolls the habit
# forward: a habit_metrics row for TODAY (date('now') format) must appear after
# the recurring-completion dialog is confirmed, on top of the seeded YESTERDAY
# row. The BEFORE/AFTER dayKey sets are printed and the run exits non-zero if
# today's key is absent.
#
# The loop launches the app first; the app sits on the UNLOCK GATE with NO
# session, so the DB writes in PHASE 0b are safe - the app opens the database
# only after unlock. After unlock the app owns the DB and this drive never
# writes to it again.
#
# MEASURED PIXELS (1280x900 root). Taken from the frames of real runs, not read
# off the code.
#
#   unlock passphrase field centre -> (254, 336)   MEASURED 2026-10-08
#   unlock button centre           -> (254, 416)   MEASURED 2026-10-08
#   bottom-nav 'Tasks' tab         -> (79, 830)    MEASURED 2026-10-07
#
# DERIVED (unmeasured) PIXELS:
#   habit-row checkmark -> derived from the OCR'd title position (title_x +
#   row-width estimate, clamped toward x=430, same row y). DERIVED, not
#   measured - and it MUST fail loudly: after the click we wait up to 5s for
#   the 'Mark Done' dialog title and die if it never appears, so a wrong click
#   fails the run instead of passing silently.
#
# WHY THE SCALE FALLBACK CHAIN (3 then 2) IS NOT A JUDGEMENT CALL
# Which --scale finds these labels is UNMEASURED - same reasoning as
# desktop-loop-drive-backup-failure-unlock.sh. 3 then 2 is a FALLBACK ORDER,
# not an arbitrated answer to "what is the right scale": it tries the
# most-magnified reading first and drops to the documented-working one. A run
# that reaches a die() at the bottom of the chain is telling you the chain is
# out of date, not that the control is missing.
#
# WHY EVERY find IS A CLICK ON A REAL COORDINATE
# click X Y dies when the frame is byte-identical after settling, so a
# coordinate that hits nothing cannot pass silently. And if a control cannot be
# found at ANY scale this script dies naming it rather than tapping something
# approximate - a wrong pixel is worse than a stopped run.
set -uo pipefail

# ---------------------------------------------------------------------------------------------------------------
# Top guard: the locale.
#
# Background shells drop LC_ALL/LANG, and the Tamil UI is OCR-blind without
# en_US.UTF-8 - every find_text_centre below would report nothing. Exported
# once here so every helper and every sqlcipher/shot invocation inherits it.
export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8

# ---------------------------------------------------------------------------------------------------------------
# The unlock-gate passphrase.
#
# This is the SAME throwaway the loop fixture already set on this HOME (see
# desktop-loop-drive-backup-failure-unlock.sh). It is a loop fixture value
# typed into the app's own field on an isolated $HOME - NOT a credential, and
# not a secret to be rotated or guarded. Overridable so the two halves of the
# fixture can never drift apart by accident.
PASSPHRASE="${LOOP_PASSPHRASE:-LoopDrive#Pass9}"

# ---------------------------------------------------------------------------------------------------------------
# The canonical DB path - the one the RUNNING app has open.
DB_PATH="/home/cawax/Documents/Payanam-debug/db/payanam.db"

# ---------------------------------------------------------------------------------------------------------------
# The seeded habit - unique title, fixed id.
HABIT_ID="habit-rollup-probe-1"
HABIT_TITLE="RollupProbe"

# ---------------------------------------------------------------------------------------------------------------
# helper: click a control by its words, with an unmeasured-scale fallback chain.
#
# WHY THIS EXISTS INSTEAD OF click_text: click_text accepts only --text/settle/step and CANNOT pass
# --scale or --region. So a control that needs a non-default scale cannot be reached through click_text at
# all - it has to be located here and clicked by coordinate. That is also why the chain is written once and
# used everywhere: every label in this drive goes through it, so there is exactly one place to update when
# the measured scale changes.
#
# find_text_centre prints "x y" on stdout and returns non-zero with EMPTY stdout when the text is absent.
# Both conditions are checked, and the result is regex-checked before it is clicked, because a coordinate
# that is not a single x y point is a matcher failure and must not become a click.
click_label() {
    local text="$1"
    local settle="${2:-2}"
    local step="${3:-}"
    local scale found rc
    [[ -n "$step" ]] || step="click_label \"$text\""

    # Fallback chain: try scale 3 (most magnified) FIRST, then scale 2 (the documented-working default).
    for scale in 3 2; do
        found=$(find_text_centre --text "$text" --scale "$scale" --region "$APP_REGION"); rc=$?
        if (( rc == 0 )) && [[ -n "$found" ]] && [[ "$found" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
            say "click_label: found \"$text\" at $found (scale $scale)"
            # shellcheck disable=SC2086
            click $found "$settle" "$step"
            return 0
        fi
        say "click_label: \"$text\" not found at scale $scale (rc=$rc)"
    done

    die "STEP '$step': the text \"$text\" was not found at scale 3 OR scale 2. No click was made - stopping rather than tapping a guessed pixel."
}

# ---------------------------------------------------------------------------------------------------------------
# helper: assert a string is GONE from the screen.
#
# Uses the same scale chain for the same unmeasured reason click_label does.
assert_gone() {
    local text="$1"
    local step="${2:-assert_gone}"
    local scale
    for scale in 3 2; do
        if [[ -n "$(find_text_centre --text "$text" --scale "$scale" --region "$APP_REGION" 2>/dev/null)" ]]; then
            say "assert_gone '$step': FAILED - \"$text\" is still on screen at scale $scale"
            return 1
        fi
    done
    say "assert_gone '$step': ok - \"$text\" is gone at scale 3 and scale 2"
    return 0
}

# ---------------------------------------------------------------------------------------------------------------
# helper: wait for the screen to stop changing (frame stable across two captures).
# ---------------------------------------------------------------------------------------------------------------
wait_for_stable_frame() {
    local max_wait="${1:-10}" prev curr stable=0 elapsed=0
    prev=$(frame_hash)
    while (( elapsed < max_wait )); do
        sleep 1
        curr=$(frame_hash)
        if [[ -n "$prev" && "$prev" == "$curr" ]]; then
            stable=1
            break
        fi
        prev=$curr
        elapsed=$(( elapsed + 1 ))
    done
    if (( stable == 1 )); then
        say "wait_for_stable_frame: frame stable after ${elapsed}s"
    else
        say "wait_for_stable_frame: frame still changing after ${max_wait}s - proceeding"
    fi
}

# ---------------------------------------------------------------------------------------------------------------
# PHASE 0 - scope every OCR lookup to the APP WINDOW itself, derived at runtime.
#
# The frame is 1280x900 but the app is a phone-sized 412x820 inside it, and find_text_centre over the
# WHOLE frame is too coarse to read anything: it reports "no text matched in the 1280x900 frame" and
# prints no guess. Every earlier text-finder failure was this, not a missing control. The region is
# therefore computed from the window the loop actually launched - never hardcoded, so it follows the
# window wherever it is placed.
APP_REGION=""
if [[ -n "${WINDOW_ID:-}" ]]; then
    APP_REGION=$(xwininfo -id "$WINDOW_ID" 2>/dev/null | awk '
        /Absolute upper-left X/ {x=$NF}
        /Absolute upper-left Y/ {y=$NF}
        /^ *Width:/             {w=$NF}
        /^ *Height:/            {h=$NF}
        END { if (x!="" && y!="" && w!="" && h!="") printf "%d,%d,%d,%d", x, y, x+w, y+h }')
fi
[[ -n "$APP_REGION" ]] || die "PHASE 0: could not derive the app window region (WINDOW_ID='${WINDOW_ID:-unset}'), so every OCR lookup would run unscoped against the full frame and report nothing. Stopping rather than guessing a region."
say "PHASE 0: OCR region scoped to the app window: $APP_REGION"

say "--- habit-rollup: recurring habit completion drive ---"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 0b - seed the recurring habit + yesterday's metric BEFORE unlock, then read the BEFORE set.
#
# WHY BEFORE UNLOCK: the app is on the unlock gate with NO session and holds NO
# database handle yet (it opens the DB only after unlock), so these writes
# cannot race the app. After unlock the app owns the DB and we never write.
#
# NO dimension_id on the habit_metrics row: FK enforcement is ON and the
# seeded row deliberately has no dimension parent.
#
# The seed is one task row (recurrenceEnabled=1, recurrenceRule='1/1') plus one
# habit_metrics row for YESTERDAY (date('now','-1 day')) with a minimal
# score/streak shape, so the AFTER read has something to add today's key to.
# WHY THE LANGUAGE UPSERT: the shell renders the STORED language choice, and this host's tessdata has
# only eng+osd - a Tamil nav makes find_text_centre on 'Habits' fail at every scale (measured 2026-10-08:
# two runs died at PHASE 2 with identical geometry while the 07:22 English run passed). Pinning
# app_language='en' in the seed makes the needle deterministic; it is fixture setup, not a product change.
say "PHASE 0b: seeding habit '$HABIT_ID' ($HABIT_TITLE) + yesterday metric BEFORE unlock"

SEED_SQL="DELETE FROM habit_metrics WHERE habitId='${HABIT_ID}'; DELETE FROM task_occurrences WHERE taskId='${HABIT_ID}'; DELETE FROM task_reschedules WHERE taskId='${HABIT_ID}'; DELETE FROM scheduled_notifications WHERE taskId='${HABIT_ID}'; DELETE FROM task_tags WHERE task_id='${HABIT_ID}'; DELETE FROM tasks WHERE id='${HABIT_ID}'; INSERT INTO tasks (id, title, status, dimension_id, createdAt, updatedAt, dueDate, recurrenceEnabled, recurrenceRule, durationMinutes, impactLevel, goalAlignment, energyLevel, controlLevel, lifeIntentionCategory, dayBoundaryHour) VALUES ('${HABIT_ID}','${HABIT_TITLE}','pending','dim_learning_growth',strftime('%Y-%m-%dT%H:%M:%S','now','localtime'),strftime('%Y-%m-%dT%H:%M:%S','now','localtime'),date('now','localtime')||'T23:59:59',1,'1/1',60,'Moderate Impact','Moderate Alignment','Moderate','Office/Colleagues Dependent','Work & Livelihood',0); INSERT INTO habit_metrics (habitId, dayKey, score, runningAvg, progress, streakPos, streakNet, posContinue) VALUES ('${HABIT_ID}', date('now','-1 day'), 1.0,1.0,0.0,1,1,1); INSERT INTO app_settings (key, value, updatedAt) VALUES ('app_language','en',strftime('%Y-%m-%dT%H:%M:%S','now','localtime')) ON CONFLICT(key) DO UPDATE SET value='en', updatedAt=excluded.updatedAt;"

# mode=ro is DROPPED for the seed: the INSERT needs write access.
SEED_OUT=$(sqlcipher "file:${DB_PATH}" -cmd "PRAGMA key='${PASSPHRASE}'" "${SEED_SQL}" 2>&1) \
    || die "PHASE 0b: seed INSERT failed: $SEED_OUT"
say "PHASE 0b: seed INSERT ok"

# BEFORE read: print the dayKey set as it stands before any completion.
BEFORE_SET=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" "SELECT dayKey FROM habit_metrics WHERE habitId='${HABIT_ID}';" 2>&1 | grep -v -x 'ok') \
    || die "PHASE 0b: sqlcipher BEFORE read failed: $BEFORE_SET"
say "PHASE 0b: BEFORE set -> ${BEFORE_SET:-<empty>}"

# BEFORE reads for the gate's other two tables: the seeded dimension's L2 rows, the L3 day rows,
# and a CONTROL count of every OTHER dimension's rows (clause d: only the affected dimension changes).
BEFORE_DIM=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" "SELECT dayKey||':'||round(score,3) FROM dimension_metrics WHERE dimensionId='dim_learning_growth' AND dayKey>=date('now','-1 day') ORDER BY dayKey;" 2>&1 | grep -v -x 'ok' || true) \
    || die "PHASE 0b: dimension_metrics BEFORE read failed: $BEFORE_DIM"
[[ "${BEFORE_DIM}" == *rror* ]] && die "PHASE read for BEFORE_DIM carried an sqlcipher error: ${BEFORE_DIM}"
BEFORE_DIM_OTHER=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" "SELECT count(*) FROM dimension_metrics WHERE dimensionId<>'dim_learning_growth' AND dayKey>=date('now','-1 day');" 2>&1 | grep -v -x 'ok' || true) \
    || die "PHASE 0b: control dimension count BEFORE read failed: $BEFORE_DIM_OTHER"
[[ "${BEFORE_DIM_OTHER}" == *rror* ]] && die "PHASE read for BEFORE_DIM_OTHER carried an sqlcipher error: ${BEFORE_DIM_OTHER}"
BEFORE_DAY=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" "SELECT dayKey||':'||round(dayScore,3) FROM day_metrics WHERE dayKey>=date('now','-1 day') ORDER BY dayKey;" 2>&1 | grep -v -x 'ok' || true) \
    || die "PHASE 0b: day_metrics BEFORE read failed: $BEFORE_DAY"
[[ "${BEFORE_DAY}" == *rror* ]] && die "PHASE read for BEFORE_DAY carried an sqlcipher error: ${BEFORE_DAY}"
say "PHASE 0b: BEFORE L2 -> $(tr '\n' ' ' <<< "${BEFORE_DIM:-<empty>}")"
say "PHASE 0b: BEFORE L3 -> $(tr '\n' ' ' <<< "${BEFORE_DAY:-<empty>}")"
say "PHASE 0b: BEFORE other-dim rows -> ${BEFORE_DIM_OTHER:-?}"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 1 - the app is already launched by the loop. Unlock the gate.
#
# MEASURED 2026-10-08 (1280x900 frame): passphrase field centre (254,336),
# Unlock button centre (254,416). The gate is up with NO session, so nothing
# behind it is reachable until it clears.
#
# The verification is the FIXED helper logic from
# desktop-loop-drive-backup-failure-unlock.sh: after the click, require the
# word 'Unlock' GONE in TWO consecutive checks 2s apart after a 3s settle. A
# single check false-positived on a transitioning frame before (measured
# 2026-10-08, t-779-probe-drive2.log) - do NOT copy the old single-check
# style. A genuinely wrong passphrase keeps the gate up across every check and
# still retries once.
UNLOCK_FIELD_X=254
UNLOCK_FIELD_Y=336
UNLOCK_BUTTON_X=254
UNLOCK_BUTTON_Y=416
UNLOCK_SETTLE=3
UNLOCK_CLEAR_SETTLE=3
UNLOCK_CLEAR_SPACING=2

unlock_with_verify() {
    local attempt="$1"

    # Focus the passphrase field at its MEASURED centre.
    click "$UNLOCK_FIELD_X" "$UNLOCK_FIELD_Y" 2 "focus passphrase field (attempt $attempt)" || return 1

    # Type the passphrase.
    type_text "$PASSPHRASE"

    # Capture frame before clicking Unlock.
    local before after
    before=$(frame_hash)

    # Click the MEASURED Unlock button centre.
    click "$UNLOCK_BUTTON_X" "$UNLOCK_BUTTON_Y" "$UNLOCK_SETTLE" "press unlock gate's Unlock button (attempt $attempt)" || return 1

    # Capture frame after.
    after=$(frame_hash)

    if [[ -z "$before" || -z "$after" ]]; then
        say "unlock_with_verify $attempt: could not hash frames"
        return 1
    fi

    if [[ "$before" == "$after" ]]; then
        say "unlock_with_verify $attempt: frame unchanged after unlock click"
        return 1
    fi

    # A frame change is NOT success - a wrong-focus click also changes the frame (focus
    # ring) while the gate stays up. Success is the GATE BEING GONE: 'Unlock' must
    # no longer be readable, confirmed by TWO consecutive clears.
    local clear_count=0
    local check_num=0
    while (( clear_count < 2 )); do
        check_num=$(( check_num + 1 ))
        if (( check_num == 1 )); then
            sleep "$UNLOCK_CLEAR_SETTLE"
        else
            sleep "$UNLOCK_CLEAR_SPACING"
        fi
        local still_there=""
        for scale in 3 2; do
            still_there=$(find_text_centre --text "Unlock" --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
            [[ -n "$still_there" ]] && break
        done
        if [[ -n "$still_there" ]]; then
            say "unlock_with_verify $attempt: check $check_num - 'Unlock' is STILL on screen ($still_there) - gate not cleared"
            return 1
        fi
        clear_count=$(( clear_count + 1 ))
        say "unlock_with_verify $attempt: check $check_num - 'Unlock' is gone (clear $clear_count of 2)"
    done

    say "unlock_with_verify $attempt: frame changed AND gate cleared (2 consecutive checks) - unlock succeeded"
    return 0
}

sleep 6
shot 01-startup

if ! unlock_with_verify 1; then
    say "PHASE 1: first unlock attempt failed - retrying once"
    if ! unlock_with_verify 2; then
        die "PHASE 1: unlock failed after 2 attempts - the unlock gate could not be crossed"
    fi
fi

assert_gone "Unlock" "unlock-gate-cleared" \
    || die "PHASE 1: 'Unlock' is still on screen after unlocking - the gate did not clear."

shot 02-after-unlock

# ---------------------------------------------------------------------------------------------------------------
# PHASE 2 - navigate to the Habits tab.
#
# WHY HABITS AND NOT TASKS (measured 2026-10-08): the seeded RECURRING task does not appear on
# the Tasks tab - its counters read "All (15)" while the DB holds 16 pending rows (ours is the
# missing one), and the app logs the habit through TasksHabitCache.buildHabitCheckmarkPayload
# (habitCount=1). Recurring tasks are habits; they render under the Habits tab. The nav label
# itself is OCR'd in the nav band (raw coordinates measured earlier did NOT switch tabs -
# route stayed 'time').
#
# WHY BY WORDS AND NOT BY PIXEL: the measured raw coordinate (79,830) did NOT switch tabs -
# measured 2026-10-08: after clicking it the frame still shows the Time screen ("Time /
# Tracking, timeline" header, and its inline "Tasks (15)" widget - a false friend that reads
# like the Tasks shell). The scheduled-backup drive reaches its tab the reliable way: OCR the
# LABEL and click the label's own centre. Two facts make this safe:
#   (a) the nav labels DO OCR ("Habits" x131, "Time" x187, "Journal" x233, y~812, measured
#       2026-10-08 tsv) - so "Tasks" is expected to read too;
#   (b) scoping to the NAV BAND (y>=790) keeps the search off every in-screen heading that
#       could shadow the label.
NAV_REGION="48,790,460,868"
TASKS_FOUND=""
for scale in 3 2 1; do
    TASKS_FOUND=$(find_text_centre --text "Habits" --scale "$scale" --region "$NAV_REGION" 2>/dev/null || true)
    if [[ -n "$TASKS_FOUND" ]] && [[ "$TASKS_FOUND" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
        say "PHASE 2: found nav 'Habits' at $TASKS_FOUND (scale $scale)"
        break
    fi
    say "PHASE 2: nav 'Habits' not found at scale $scale"
    TASKS_FOUND=""
done
[[ -n "$TASKS_FOUND" ]] || die "PHASE 2: the bottom-nav 'Habits' label was not readable in the nav band at scale 3, 2 or 1 - stopping rather than tapping a bar position"
read -r TASKS_X TASKS_Y <<< "$TASKS_FOUND"

# MEASURED 2026-10-08 (the -b run, new binary): unlock is followed by a STARTUP SEQUENCE
# (passphrase_unlock -> lenses -> focus_mode_selection -> launchRoute time; the app log shows
# startupMode=UnlockPassphrase -> FocusModeSelection -> Ready at ~+0.3s), and a nav click
# landing inside that window is silently DROPPED - the log had ZERO destinationChanged for
# habits while the frame's selected tab stayed Time, and PHASE 3 then found no row after
# 8 scrolls. Two guards, both evidence-based: (1) wait for the startup gate to report Ready
# in the app's own log before touching nav; (2) click, then VERIFY in the log that a
# destinationChanged to habits appeared after the click, retrying with a fresh OCR read
# up to 3 times. Log path: the newest file under the desktop debug logs directory.
wait_for_startup_ready() {
    local logs_dir="$HOME/Documents/Payanam-debug/logs" deadline=$((SECONDS + 15)) newest
    while (( SECONDS < deadline )); do
        newest=$(ls -1t "$logs_dir"/*.log 2>/dev/null | head -1 || true)
        if [[ -n "$newest" ]] && grep -q 'startupMode=\"Ready\"' "$newest" 2>/dev/null; then
            say "PHASE 2: startup gate Ready (app log $newest)"
            return 0
        fi
        sleep 1
    done
    say "PHASE 2: startup Ready not seen in 15s - proceeding anyway (will be caught by the click verify)"
}
NEWEST_APP_LOG=""
clicked_habits() {
    grep -q 'route=\"habits\"' "$NEWEST_APP_LOG" 2>/dev/null
}
for attempt in 1 2 3; do
    wait_for_startup_ready
    NEWEST_APP_LOG=$(ls -1t "$HOME/Documents/Payanam-debug/logs"/*.log 2>/dev/null | head -1 || true)
    if [[ "$attempt" == "1" ]]; then
        :
    else
        # re-OCR the nav band before retrying (positions are re-read, never reused)
        TASKS_FOUND=""
        for scale in 3 2 1; do
            TASKS_FOUND=$(find_text_centre --text "Habits" --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
            [[ -n "$TASKS_FOUND" ]] && break
        done
        [[ -n "$TASKS_FOUND" ]] || die "PHASE 2: nav 'Habits' not readable on retry $attempt"
        read -r TASKS_X TASKS_Y <<< "$TASKS_FOUND"
    fi
    click "$TASKS_X" "$TASKS_Y" 3 "navigate to the Habits tab (label-centre click, attempt $attempt)"
    sleep 2
    if clicked_habits; then
        say "PHASE 2: app log confirms navigation to habits (attempt $attempt)"
        break
    fi
    say "PHASE 2: app log shows NO navigation to habits after attempt $attempt - retrying"
    [[ "$attempt" == "3" ]] && die "PHASE 2: three verified clicks on 'Habits' produced no navigation to habits (startup gate or nav guard) - stopping rather than searching a Time screen"
done
wait_for_stable_frame 10
shot 02a-tasks-shell

# ---------------------------------------------------------------------------------------------------------------
# PHASE 3 - complete the seeded habit from its row.
#
# WHAT THE HABITS SCREEN ACTUALLY RENDERS (measured 2026-10-08, 1280x900 frame, tesseract
# 3x crop psm4/psm6): a week-strip header (weekday letters + dates) over ONE row that reads
# "RollupProb" - the seeded title TRUNCATES its final letters at this column width, and the
# noisy readings seen were 'RollupProb' (conf 91.9) and 'RoluPrrob'. With a dimension assigned
# (2026-10-08 run) the row re-measured as 'RolupPro/be' - first L dropped - so the needle set
# now carries the observed misspellings too. MEASURED with the helper's OWN pipeline (point
# filter + --dpi 96 + tsv): the truncated title reads 'RollupPro' (double L) at every scale,
# which is what PHASE 3 searches for now. The row sits at
# original ≈ (127,382)-(193,395). So the title search uses the measured OCR variants as
# needles (the same multi-needle pattern the scheduled-backup drive uses for
# Settings/Setting), and the CLICK is not guessed from the title: it is the TODAY COLUMN -
# the weekday-letter header word for today (date +%a, uppercased; THU on 2026-10-08) gives
# the column's x, and the title row gives y. Clicking that cell is the checkmark action
# (TasksScreen.kt:431 toggleCheckmark receives that cell's date).
#
# The row can sit below the fold behind the week strip on narrow frames, so search-then-
# scroll until it appears. MEASURED TRAP from desktop-loop-drive-manual-backup.sh
# (2026-10-07): the wheel goes to the POINTER, which is still on the bottom nav after the
# tab click - move into the list content first, or the events scroll nothing.

TODAY_WD="$(date +%a | tr '[:lower:]' '[:upper:]')"
TITLE_FOUND=""
TITLE_NEEDLE=""
for scroll_try in 1 2 3 4 5 6 7 8; do
    for needle in RollupProb RollupProbe RoluPrrob RollupPro RollupP; do
        for scale in 3 2 1; do
            TITLE_FOUND=$(find_text_centre --text "$needle" --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
            if [[ -n "$TITLE_FOUND" ]] && [[ "$TITLE_FOUND" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
                TITLE_NEEDLE="$needle"
                say "PHASE 3: found habit row '$needle' at $TITLE_FOUND (scale $scale, scroll_try $scroll_try)"
                break 3
            fi
            TITLE_FOUND=""
        done
    done
    shot "03-notfound-scroll${scroll_try}"
    say "PHASE 3: habit row not visible (scroll_try $scroll_try) - wheel-down 3x from the list content"
    xdotool mousemove 254 450
    for _ in 1 2 3; do xdotool click 5; sleep 0.3; done
    sleep 1.5
done
[[ -n "$TITLE_FOUND" ]] || die "PHASE 3: the seeded habit row was not found (needles RollupProb/RollupProbe/RoluPrrob/RollupPro/RollupP) after 8 scroll attempts - stopping rather than tapping a guessed pixel"
read -r TITLE_X TITLE_Y <<< "$TITLE_FOUND"

# The checkmark is TODAY's cell in the row: its x comes from the weekday-letter header word
# for today (the column aligns under its letter), its y from the row itself. Both derived
# from measured text positions, never from a bare pixel guess. Fail-loud: the Mark Done
# dialog below must appear, so a wrong cell fails the run instead of passing silently.
WD_FOUND=""
for scale in 3 2; do
    WD_FOUND=$(find_text_centre --text "$TODAY_WD" --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
    if [[ -n "$WD_FOUND" ]] && [[ "$WD_FOUND" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
        say "PHASE 3: found weekday header '$TODAY_WD' at $WD_FOUND (scale $scale)"
        break
    fi
    WD_FOUND=""
done
[[ -n "$WD_FOUND" ]] || die "PHASE 3: today's weekday header '$TODAY_WD' was not readable - cannot derive today's cell column"
read -r WD_X WD_Y <<< "$WD_FOUND"
CHECKMARK_X=$WD_X
CHECKMARK_Y=$TITLE_Y
say "PHASE 3: DERIVED today-cell click at ($CHECKMARK_X,$CHECKMARK_Y) - x from weekday '$TODAY_WD' at $WD_X, y from row '$TITLE_NEEDLE' at $TITLE_Y; gated by the Mark Done dialog wait"
click "$CHECKMARK_X" "$CHECKMARK_Y" 2 "tap the habit's today cell (DERIVED position)"

# Fail-loud gate: the recurring-completion dialog (title 'Mark Done', strings.xml
# loc_mark_done) must appear within ~5s of the cell click.
MARK_DONE_FOUND=""
for _try in 1 2 3 4 5; do
    for scale in 3 2; do
        MARK_DONE_FOUND=$(find_text_centre --text 'Mark Done' --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
        [[ -n "$MARK_DONE_FOUND" ]] && break 2
    done
    sleep 1
done
[[ -n "$MARK_DONE_FOUND" ]] || die "PHASE 3: the 'Mark Done' dialog did not appear within 5s after the derived today-cell click at ($CHECKMARK_X,$CHECKMARK_Y) - the click missed. A wrong click must fail, not pass."
say "PHASE 3: 'Mark Done' dialog present at $MARK_DONE_FOUND"
shot 03-mark-done-dialog

# (4) Confirm the dialog. MEASURED 2026-10-08 (03-mark-done-dialog.png, tesseract 3x crop):
# the bottom row reads "Cancel" at ≈(228,632) and the CONFIRM button reads "Mark Done" at
# ≈(305-376,634) - it is NOT "OK" (the strings' loc_ok never appears on this dialog). A
# word-click cannot be used because the dialog TITLE is also "Mark Done" and would match
# first, so the button is clicked at its measured centre and the dismissal is then asserted:
# the dialog text must be GONE afterwards, so a click that misses fails the run.
DIALOG_OK_X=340
DIALOG_OK_Y=634
say "PHASE 3: clicking the dialog's confirm at ($DIALOG_OK_X,$DIALOG_OK_Y) - MEASURED 2026-10-08"
click "$DIALOG_OK_X" "$DIALOG_OK_Y" 3 "confirm the recurring completion dialog (measured Mark Done button)"
assert_gone "Mark Done" "completion dialog dismissed after confirm" \
    || die "PHASE 3: the Mark Done dialog is still on screen after clicking its confirm at ($DIALOG_OK_X,$DIALOG_OK_Y) - the click missed."
shot 04-after-complete

# ---------------------------------------------------------------------------------------------------------------
# PHASE 4 - assert the rollup happened.
#
# The recurring completion must have written a habit_metrics row for TODAY.
# date('now') in SQLite is UTC, so the expected key is the UTC date - computed
# with date -u so the shell and the DB can never disagree on which day "today"
# is across the IST/UTC boundary. Print BEFORE and AFTER sets; exit non-zero
# if today's key is absent.
TODAY_KEY=$(date -u +%F)
say "PHASE 4: AFTER read - expecting today's dayKey $TODAY_KEY (UTC, date('now') format) to be present"

AFTER_SET=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" "SELECT dayKey FROM habit_metrics WHERE habitId='${HABIT_ID}';" 2>&1 | grep -v -x 'ok') \
    || die "PHASE 4: sqlcipher AFTER read failed: $AFTER_SET"

# say() prints one line: flatten the day-key sets so the logged evidence shows EVERY key
# (a raw newline in the var printed only the first line and made a green run look red).
say "PHASE 4: BEFORE set -> $(tr '\n' ' ' <<< "${BEFORE_SET:-<empty>}")"
say "PHASE 4: AFTER  set -> $(tr '\n' ' ' <<< "${AFTER_SET:-<empty>}")"

AFTER_DIM=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" "SELECT dayKey||':'||round(score,3) FROM dimension_metrics WHERE dimensionId='dim_learning_growth' AND dayKey>=date('now','-1 day') ORDER BY dayKey;" 2>&1 | grep -v -x 'ok' || true) \
    || die "PHASE 4: dimension_metrics AFTER read failed: $AFTER_DIM"
[[ "${AFTER_DIM}" == *rror* ]] && die "PHASE read for AFTER_DIM carried an sqlcipher error: ${AFTER_DIM}"
AFTER_DIM_OTHER=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" "SELECT count(*) FROM dimension_metrics WHERE dimensionId<>'dim_learning_growth' AND dayKey>=date('now','-1 day');" 2>&1 | grep -v -x 'ok' || true) \
    || die "PHASE 4: control dimension count AFTER read failed: $AFTER_DIM_OTHER"
[[ "${AFTER_DIM_OTHER}" == *rror* ]] && die "PHASE read for AFTER_DIM_OTHER carried an sqlcipher error: ${AFTER_DIM_OTHER}"
AFTER_DAY=$(sqlcipher "file:${DB_PATH}?mode=ro" -cmd "PRAGMA key='${PASSPHRASE}'" "SELECT dayKey||':'||round(dayScore,3) FROM day_metrics WHERE dayKey>=date('now','-1 day') ORDER BY dayKey;" 2>&1 | grep -v -x 'ok' || true) \
    || die "PHASE 4: day_metrics AFTER read failed: $AFTER_DAY"
[[ "${AFTER_DAY}" == *rror* ]] && die "PHASE read for AFTER_DAY carried an sqlcipher error: ${AFTER_DAY}"
say "PHASE 4: AFTER  L2 -> $(tr '\n' ' ' <<< "${AFTER_DIM:-<empty>}")"
say "PHASE 4: AFTER  L3 -> $(tr '\n' ' ' <<< "${AFTER_DAY:-<empty>}")"
say "PHASE 4: AFTER other-dim rows -> ${AFTER_DIM_OTHER:-?}"
if [[ "$AFTER_DIM" != *"$TODAY_KEY"* ]]; then
    die "PHASE 4: dimension_metrics (L2) has no row for today $TODAY_KEY - the affected dimension was not rebuilt"
fi
if [[ "$AFTER_DAY" != *"$TODAY_KEY"* ]]; then
    die "PHASE 4: day_metrics (L3) has no row for today $TODAY_KEY - the day layer was not rebuilt"
fi
if [[ "$AFTER_DIM_OTHER" != "$BEFORE_DIM_OTHER" ]]; then
    die "PHASE 4: OTHER dimensions changed (before=$BEFORE_DIM_OTHER after=$AFTER_DIM_OTHER) - the rebuild was not scoped to the affected dimension"
fi
say "PHASE 4: L2 today present, L3 today present, other dimensions untouched (clause d)"

if [[ "$AFTER_SET" == *"$TODAY_KEY"* ]]; then
    say "=== PROVEN: completing the recurring habit rolled it forward ==="
    say "  BEFORE: $(tr '\n' ' ' <<< "${BEFORE_SET:-<empty>}")"
    say "  AFTER:  $(tr '\n' ' ' <<< "${AFTER_SET:-<empty>}")"
    say "  today's dayKey $TODAY_KEY is present after the Mark Done -> OK completion"
    say "--- habit-rollup finished: recurring habit completion drive ---"
    exit 0
else
    die "PHASE 4: today's dayKey $TODAY_KEY is NOT present in the AFTER set - the recurring completion did not roll the habit forward. BEFORE='${BEFORE_SET:-<empty>}' AFTER='${AFTER_SET:-<empty>}'"
fi
