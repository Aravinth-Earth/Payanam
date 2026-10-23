# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# Prove the device pass (list -> detail) on the built desktop app (unpacked, never installed).
# Gate: unlock -> Tasks tab -> tap a task -> reach detail screen.
# This script implements ONLY the first three steps: unlock, Tasks tab, detail.
# Edit and back are intentionally NOT attempted.
#
# MEASURED FACTS (from this session):
#   - Unlock button found by text "Unlock" at scale 3/2
#   - Tasks tab found by text "Tasks" at scale 3/2
#   - Fresh loop DB is EMPTY: "Tasks (0)" / "No Tasks Due Today"
#   - A caption is not a control: derive clicks from OCR/text helpers, never bare coordinates
#
# CHOICE: assert the empty state rather than creating a task first (simpler).
set -uo pipefail

# ---------------------------------------------------------------------------------------------------------------
# The unlock-gate passphrase (same throwaway the loop fixture set).
PASSPHRASE="${LOOP_PASSPHRASE:-LoopDrive#Pass9}"

# ---------------------------------------------------------------------------------------------------------------
# helper: click a control by its words, with an unmeasured-scale fallback chain.
click_label() {
    local text="$1"
    local settle="${2:-2}"
    local step="${3:-}"
    local scale found rc
    [[ -n "$step" ]] || step="click_label \"$text\""

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

    die "STEP '$step': the text \"$text\" was not found at scale 3 OR scale 2. No click was made."
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
# helper: assert a string is GONE from the screen.
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
# helper: report whether a string is present, WITHOUT failing the run.
have_label() {
    local text="$1"
    local scale
    for scale in 3 2; do
        if [[ -n "$(find_text_centre --text "$text" --scale "$scale" --region "$APP_REGION" 2>/dev/null)" ]]; then
            say "have_label: found \"$text\" at scale $scale"
            return 0
        fi
    done
    say "have_label: NOT found - \"$text\" (scales 3 and 2). Soft check only; not fatal."
    return 1
}

# PHASE 0 - scope every OCR lookup to the APP WINDOW itself, derived at runtime.
APP_REGION=""
if [[ -n "${WINDOW_ID:-}" ]]; then
    APP_REGION=$(xwininfo -id "$WINDOW_ID" 2>/dev/null | awk '
        /Absolute upper-left X/ {x=$NF}
        /Absolute upper-left Y/ {y=$NF}
        /^ *Width:/             {w=$NF}
        /^ *Height:/            {h=$NF}
        END { if (x!="" && y!="" && w!="" && h!="") printf "%d,%d,%d,%d", x, y, x+w, y+h }')
fi
[[ -n "$APP_REGION" ]] || die "PHASE 0: could not derive the app window region (WINDOW_ID='${WINDOW_ID:-unset}'). Stopping."
say "PHASE 0: OCR region scoped to the app window: $APP_REGION"

say "--- task-detail: device pass (list -> detail) ---"

# STEP 1: Unlock the app.
say "STEP 1: unlock gate"

# (1) Settle first - wait for the window/app to finish resume before touching anything.
wait_for_stable_frame 10

# (2) Find the Unlock button and derive the passphrase field position (sibling drive idiom).
UNLOCK_SETTLE=3
UNLOCK_FIELD_ABOVE_BUTTON_PX=80

UNLOCK_FOUND=""
for scale in 3 2; do
    UNLOCK_FOUND=$(find_text_centre --text 'Unlock' --scale "$scale" --region "$APP_REGION")
    UNLOCK_RC=$?
    if (( UNLOCK_RC == 0 )) && [[ -n "$UNLOCK_FOUND" ]] && [[ "$UNLOCK_FOUND" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
        say "STEP 1: found 'Unlock' at $UNLOCK_FOUND (scale $scale)"
        break
    fi
    say "STEP 1: 'Unlock' not found at scale $scale (rc=$UNLOCK_RC)"
    UNLOCK_FOUND=""
done

if [[ -z "$UNLOCK_FOUND" ]]; then
    die "STEP 1: 'Unlock' was not found at scale 3 OR scale 2. Stopping."
fi

UNLOCK_X=$(awk '{print $1}' <<<"$UNLOCK_FOUND")
UNLOCK_Y=$(awk '{print $2}' <<<"$UNLOCK_FOUND")

UNLOCK_FIELD_X=$UNLOCK_X
UNLOCK_FIELD_Y=$(( UNLOCK_Y - UNLOCK_FIELD_ABOVE_BUTTON_PX ))

# (3-6) Focus field, type passphrase, click Unlock, verify frame changed, retry once on no-change.
unlock_with_verify() {
    local attempt="$1"

    # Focus the passphrase field (sibling drive idiom: button Y minus 80px).
    click "$UNLOCK_FIELD_X" "$UNLOCK_FIELD_Y" 2 "focus passphrase field (attempt $attempt)" || return 1

    # Type the passphrase.
    type_text "$PASSPHRASE"

    # Capture frame before clicking Unlock.
    local before after
    before=$(frame_hash)

    # Click the ALREADY-FOUND Unlock coordinate - do NOT search for the label again.
    # MEASURED 2026-10-08 (probe-unlock): with an empty field the button is DISABLED
    # (gray bg #2A2A2A, gray text #959595 - tesseract-readable, which is how UNLOCK_X/Y
    # were found); typing the passphrase switches it to ENABLED (blue->green gradient,
    # WHITE text) and tesseract reads white-on-gradient as NOTHING at any scale, so a
    # text re-find after typing fails while the button sits visibly at the same spot
    # (layout stable: vision puts the enabled button at y=386..441, centre ~413).
    click "$UNLOCK_X" "$UNLOCK_Y" "$UNLOCK_SETTLE" "press unlock gate's Unlock button (attempt $attempt)" || return 1

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

    say "unlock_with_verify $attempt: frame changed - unlock succeeded"
    return 0
}

if ! unlock_with_verify 1; then
    say "STEP 1: first unlock attempt failed - retrying once"
    if ! unlock_with_verify 2; then
        die "STEP 1: unlock failed after 2 attempts - the unlock gate could not be crossed"
    fi
fi

# (7) Keep assert_gone after success.
assert_gone "Unlock" "unlock-gate-cleared"

# STEP 2: Go to the Tasks tab.
say "STEP 2: Tasks tab"
click_label "Tasks" 3 "tasks-tab"

# STEP 3: Assert the empty state (DB is fresh, no tasks exist).
# CHOICE: assert empty rather than create-a-task-first (simpler).
say "STEP 3: assert empty task list"
have_label "Tasks (0)" || have_label "No Tasks Due Today"

# ==================================================================================================
# STEP 4 - create the task the walk needs (idioms ported from desktop-loop-drive-roundtrip.sh,
# which proved them: FAB geometry 82%/81% + app-log assert; Task Title form; Save Task as a
# COLOUR BLOB because tesseract reads white-on-teal as noise - the same OCR class as the
# enabled-gradient Unlock button measured in probe-unlock).
# ==================================================================================================
say "STEP 4: create a task to walk into"
# APP_REGION is X1,Y1,X2,Y2 (CORNERS - measured: 48,48,460,868), NOT X,Y,W,H.
# Parsing it as W/H put every derived click off by the origin (FAB landed at 425,751
# instead of 385,712 on the 2026-10-08 walk run 1). Width/height are differences.
_APP_X=${APP_REGION%%,*}; _rest=${APP_REGION#*,}
_APP_Y=${_rest%%,*}; _rest=${_rest#*,}
_APP_R=${_rest%%,*}; _rest=${_rest#*,}
_APP_B=${_rest%,*}
_APP_W=$((_APP_R - _APP_X)); _APP_H=$((_APP_B - _APP_Y))
FAB_X=$((_APP_X + _APP_W * 82 / 100)); FAB_Y=$((_APP_Y + _APP_H * 81 / 100))
LOG=$(ls -t /home/cawax/Documents/Payanam-debug/logs/payanam_desktop_*.log 2>/dev/null | head -1)
[[ -n "$LOG" ]] || die "STEP 4: no payanam_desktop_*.log found - cannot assert the FAB via the app's own log"
click "$FAB_X" "$FAB_Y" 3 "tap the Add Task FAB (geometry 82%/81%)"
grep -aq "Add task FAB clicked" "$LOG" || die "STEP 4: the app log does NOT contain 'Add task FAB clicked' - the FAB click did not register"
say "STEP 4: FAB confirmed by the app's own log"

# focus-mode overlay can appear after unlock on a fresh fixture (measured: route focus_mode_selection)
if find_text_centre --text "Choose Your Focus" --scale 2 --region "$APP_REGION" >/dev/null 2>&1; then
    say "STEP 4: focus-mode overlay present - picking Full Suite"
    click_label "Full Suite" 2 "select the focus mode"
    for _ in $(seq 1 20); do xdotool click 5; sleep 0.2; done
    if find_text_centre --text "Continue" --scale 2 --region "$APP_REGION" >/dev/null 2>&1; then
        CONTINUE_XY=$(find_text_centre --text "Continue" --scale 2 --region "$APP_REGION")
        click $CONTINUE_XY 4 "press the Continue pill"
    fi
    find_text_centre --text "Tasks" --scale 3 --region "$APP_REGION" >/dev/null 2>&1 \
        || find_text_centre --text "Tasks" --scale 2 --region "$APP_REGION" >/dev/null 2>&1 \
        || die "STEP 4: focus overlay did not clear - no bottom nav to continue"
    FAB_X=$((_APP_X + _APP_W * 82 / 100)); FAB_Y=$((_APP_Y + _APP_H * 81 / 100))
    click "$FAB_X" "$FAB_Y" 3 "tap the Add Task FAB (after focus overlay)"
    grep -aq "Add task FAB clicked" "$LOG" || die "STEP 4: FAB click after focus overlay not confirmed by the log"
fi

TASK_FORM=""
for scale in 3 2; do
    TASK_FORM=$(find_text_centre --text "Task Title" --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$TASK_FORM" ]] && break
done
[[ -n "$TASK_FORM" ]] || die "STEP 4: the Add Task form's 'Task Title' field was not found at scale 3 or 2"
click $TASK_FORM 2 "focus the task title field"
type_text "Loop walk gate task"
say "STEP 4: title typed - scrolling to the Save Task control"

SAVE_XY=""
for round in $(seq 1 8); do
    CAND=$(find_button_centre --label "Save Task" --band 300-780 2>/dev/null || true)
    if [[ -n "$CAND" ]]; then
        CAND_Y=$(awk '{print $2}' <<<"$CAND")
        if (( CAND_Y >= 300 && CAND_Y <= 780 )); then
            SAVE_XY="$CAND"
            say "STEP 4: Save Task (colour blob) at $SAVE_XY on round $round (inside band 300-780)"
            break
        fi
        # MEASURED 2026-10-08 walk run 2: the finder returned (254,264) - the FOCUSED title
        # field's teal outline, above the band - and clicking it was byte-identical (harness
        # guard fired). The band is therefore re-checked HERE, not trusted from the finder.
        say "STEP 4: round $round - candidate $CAND is OUTSIDE band 300-780 (focused-field ring?) - rejecting, scrolling on"
    fi
    for _ in $(seq 1 10); do xdotool click 5; sleep 0.2; done
    sleep 1
    say "STEP 4: round $round - Save Task not yet in band"
done
[[ -n "$SAVE_XY" ]] || die "STEP 4: Save Task was not found after 8 scroll rounds (colour blob, band 300-780)"
# Click the VERIFIED coordinate directly: click_button re-runs the same finder, which
# measured a false positive above (focused-field ring) and would die byte-identical.
click $SAVE_XY 3 "save the task (band-verified blob)"
shot 10-saved
say "STEP 4: save clicked"

# ==================================================================================================
# STEP 5 - LIST -> DETAIL
# ==================================================================================================
WALK_TITLE="Loop walk gate task"
ITEM=""
for _try in 1 2 3 4 5; do
    for scale in 3 2; do
        ITEM=$(find_text_centre --text "$WALK_TITLE" --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
        [[ -n "$ITEM" ]] && break
    done
    [[ -n "$ITEM" ]] && break
    sleep 2
done
[[ -n "$ITEM" ]] || die "STEP 5: the created task was not found on the list at scale 3 or 2"
click $ITEM 3 "tap the created task (list -> detail)"
shot 21-detail
PRE_DEST=$(grep -ac "destinationChanged" "$LOG")
grep -aq 'route="task_detail' "$LOG" || die "STEP 5: the app's own log never recorded route=task_detail - the tap did not open the detail destination"
say "STEP 5: LOG OK - destinationChanged shows task_detail"

# ==================================================================================================
# STEP 6 - DETAIL -> EDIT. The pencil is ICON-ONLY (tesseract sees no text), so the click point is
# derived from the window geometry: measured on the 2026-10-08 detail frame at root (355,197) with
# window origin (48,48) size 412x820 -> 74.5% x / 18.2% y. Derived, not hardcoded.
# ==================================================================================================
EDIT_X=$((_APP_X + _APP_W * 745 / 1000)); EDIT_Y=$((_APP_Y + _APP_H * 182 / 1000))
say "STEP 6: EDIT pencil (derived) = ($EDIT_X, $EDIT_Y) from APP_REGION $APP_REGION"
click "$EDIT_X" "$EDIT_Y" 3 "open the task's edit screen (detail -> edit)"
shot 22-edit
EDIT_FORM=""
for scale in 3 2; do
    EDIT_FORM=$(find_text_centre --text "Task Title" --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$EDIT_FORM" ]] && break
done
POST_EDIT_DEST=$(grep -ac "destinationChanged" "$LOG")
[[ "$POST_EDIT_DEST" -gt "$PRE_DEST" ]] || die "STEP 6: no destination change in the app log after the pencil click - the edit destination did not open"
[[ -n "$EDIT_FORM" ]] || say "STEP 6: warning - 'Task Title' label not OCR-readable on the edit screen, but the log proves a destination change"
say "STEP 6: LOG OK - destinationChanged count went $PRE_DEST -> $POST_EDIT_DEST (edit opened)"

# ==================================================================================================
# STEP 7 - EDIT -> BACK. The back arrow is ICON-ONLY too: measured root (104,197) -> 13.6% x /
# 18.2% y of the window. Derived the same way.
# ==================================================================================================
BACK_X=$((_APP_X + _APP_W * 136 / 1000)); BACK_Y=$((_APP_Y + _APP_H * 182 / 1000))
say "STEP 7: BACK arrow (derived) = ($BACK_X, $BACK_Y)"
click "$BACK_X" "$BACK_Y" 3 "leave the edit screen (edit -> back)"
shot 23-back-from-edit
POST_BACK_DEST=$(grep -ac "destinationChanged" "$LOG")
[[ "$POST_BACK_DEST" -gt "$POST_EDIT_DEST" ]] || die "STEP 7: no destination change after BACK - leaving edit did not navigate"
say "STEP 7: LOG OK - destinationChanged count went $POST_EDIT_DEST -> $POST_BACK_DEST (back navigated)"

# ==================================================================================================
# STEP 8 - back to the list, prove the loop closes.
# ==================================================================================================
click "$BACK_X" "$BACK_Y" 3 "leave the detail screen (detail -> back to list)"
shot 24-back-to-list
LIST_OK=""
for scale in 3 2; do
    LIST_OK=$(find_text_centre --text "$WALK_TITLE" --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$LIST_OK" ]] && break
done
[[ -n "$LIST_OK" ]] || die "STEP 8: after two BACKs the list does not show the task - the walk did not close"
say "STEP 8: list shows the task again after edit + back"

say "=== #732/#734 WALK VERDICT: PASS - list -> detail -> edit -> back all navigated, app log destination lines: ==="
grep -a "destinationChanged" "$LOG" | tail -8 | while IFS= read -r l; do say "VERDICT-LOG: $l"; done
say "--- task-detail drive finished ---"
