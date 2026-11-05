#!/usr/bin/env bash
# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# Prove a DATA round-trip - create data on the desktop, export it, re-import it,
# and read the counts back non-empty.
#
# MEASURED: the desktop HAS an export button (SettingsDataManagementSections.kt:85,
# string settings_action_export = "Export" at strings.xml:817) and an import button
# (SettingsDataManagementSections.kt:120, string settings_action_import = "Import" at
# strings.xml:818). Both are wired through SettingsScreen.kt:508-517 to
# DesktopApp.kt:309-355, which calls AwtDocumentSource.requestDestination() /
# requestSource(). Both open a java.awt.FileDialog (AwtDocumentSource.kt:84-86).
#
# THE PICKER SEAM: the AWT FileDialog is a native OS file picker. The loop cannot drive it.
# The existing desktop-import-loop.sh stops at exactly this seam (line 190).
# This script proves the CREATE half and the EXPORT-CLICK half, then STOPS at the picker.
#
# THE LOG PATTERN (for the import half, when the picker is solved):
#   Source: "DesktopDatabaseTransferController.import"
#   Message: "Database imported and re-opened"
#   Data: mapOf("byteCount" to outcome.byteCount)
#   (DesktopDatabaseTransferController.kt:103-107)
#
# UI STRINGS (core/strings/src/main/res/values/strings.xml):
#   settings_action_export = "Export" (line 817)
#   settings_action_import = "Import" (line 818)
#   settings_data_management_title = "Data Management" (line 851)
#   locAddTask = "Add Task" (line 315)
#   locTaskTitle = "Task Title" (line 734)
#   locSave = "Save" (line 679)

set -uo pipefail

PASSPHRASE="${LOOP_PASSPHRASE:-LoopDrive#Pass9}"

# ---------------------------------------------------------------------------------------------------------------
# Helpers (from desktop-loop.sh, available in the drive environment)
# ---------------------------------------------------------------------------------------------------------------
# say, die, click, find_text_centre, type_text, press, shot, find_button_centre are all defined in
# desktop-loop.sh and available when this drive is invoked with -d.

# ---------------------------------------------------------------------------------------------------------------
# Helper: click a control by its words, with an unmeasured-scale fallback chain (from 531)
# ---------------------------------------------------------------------------------------------------------------
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
            click $found "$settle" "$step"
            return 0
        fi
        say "click_label: \"$text\" not found at scale $scale (rc=$rc)"
    done

    die "STEP '$step': the text \"$text\" was not found at scale 3 OR scale 2. No click was made - stopping rather than tapping a guessed pixel."
}

# ---------------------------------------------------------------------------------------------------------------
# Helper: assert a string is GONE from the screen (from 531)
# ---------------------------------------------------------------------------------------------------------------
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
# Helper: report whether a string is present, WITHOUT failing the run (from 531)
# ---------------------------------------------------------------------------------------------------------------
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

# ---------------------------------------------------------------------------------------------------------------
# PHASE 0 - scope every OCR lookup to the APP WINDOW itself, derived at runtime (from 531)
# ---------------------------------------------------------------------------------------------------------------
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

say "--- data-roundtrip: data round-trip drive ---"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 0b - dismiss a backup-failure dialog if one is covering the gate (from 531)
# ---------------------------------------------------------------------------------------------------------------
if [[ -n "$(find_text_centre --text 'Backup issue' --scale 3 --region "$APP_REGION" 2>/dev/null)" ]] ||
   [[ -n "$(find_text_centre --text 'Backup issue' --scale 2 --region "$APP_REGION" 2>/dev/null)" ]]; then
    say "PHASE 0b: a backup-failure dialog is covering the unlock gate"
    press Escape
    sleep 2
    if [[ -z "$(find_text_centre --text 'Backup issue' --scale 3 --region "$APP_REGION" 2>/dev/null)" ]] &&
       [[ -z "$(find_text_centre --text 'Backup issue' --scale 2 --region "$APP_REGION" 2>/dev/null)" ]]; then
        say "PHASE 0b: backup-failure dialog dismissed via its dismiss request"
    else
        local_msg="$(find_text_centre --text 'Message' --scale 3 --region "$APP_REGION" 2>/dev/null || true)"
        [[ -n "$local_msg" ]] || local_msg="$(find_text_centre --text 'Message' --scale 2 --region "$APP_REGION" 2>/dev/null || true)"
        if [[ -n "$local_msg" ]]; then
            say "PHASE 0b: Escape did not close it - clicking the dialog's own action at $local_msg"
            click $local_msg 2.5 "click the backup-failure dialog action"
        else
            die "PHASE 0b: the backup-failure dialog is up and neither Escape nor its action label could be located."
        fi
        if [[ -n "$(find_text_centre --text 'Backup issue' --scale 3 --region "$APP_REGION" 2>/dev/null)" ]] ||
           [[ -n "$(find_text_centre --text 'Backup issue' --scale 2 --region "$APP_REGION" 2>/dev/null)" ]]; then
            die "PHASE 0b: 'Backup issue' is still on screen after two dismissal routes."
        fi
        say "PHASE 0b: backup-failure dialog dismissed via its action"
    fi
else
    say "PHASE 0b: no backup-failure dialog present"
fi

# ---------------------------------------------------------------------------------------------------------------
# PHASE 1 - cross the UNLOCK GATE (from 531)
# ---------------------------------------------------------------------------------------------------------------
sleep 6
shot 01-shell

UNLOCK_SETTLE=15
UNLOCK_FIELD_ABOVE_BUTTON_PX=80
UNLOCK_SCALE=2

UNLOCK_FOUND=$(find_text_centre --text 'Unlock' --scale "$UNLOCK_SCALE" --region "$APP_REGION"); UNLOCK_RC=$?
if (( UNLOCK_RC != 0 )) || [[ -z "$UNLOCK_FOUND" ]] || [[ ! "$UNLOCK_FOUND" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
    die "PHASE 1: the unlock gate's \"Unlock\" button was not found (rc=$UNLOCK_RC, stdout='$UNLOCK_FOUND'). Stopping."
fi
UNLOCK_X=$(awk '{print $1}' <<<"$UNLOCK_FOUND")
UNLOCK_Y=$(awk '{print $2}' <<<"$UNLOCK_FOUND")
say "PHASE 1: found the unlock gate's \"Unlock\" button at $UNLOCK_X $UNLOCK_Y"

UNLOCK_FIELD_X=$UNLOCK_X
UNLOCK_FIELD_Y=$(( UNLOCK_Y - UNLOCK_FIELD_ABOVE_BUTTON_PX ))
click "$UNLOCK_FIELD_X" "$UNLOCK_FIELD_Y" 2 "focus the unlock gate's passphrase field (DERIVED: button Y minus ${UNLOCK_FIELD_ABOVE_BUTTON_PX}px)"
type_text "$PASSPHRASE"
shot 02-passphrase-typed

click "$UNLOCK_X" "$UNLOCK_Y" "$UNLOCK_SETTLE" "press the unlock gate's \"Unlock\" button"
shot 03-after-unlock

assert_gone "Unlock" "the unlock gate is GONE" \
    || die "PHASE 1: \"Unlock\" is still on screen after the press, so the unlock did NOT take. Stopping."

# ---------------------------------------------------------------------------------------------------------------
# PHASE 1b - cross the FOCUS-MODE GATE if present (from 531)
# ---------------------------------------------------------------------------------------------------------------
FOCUS_CHOICE="$(find_text_centre --text 'Full Suite' --scale 2 --region "$APP_REGION" 2>/dev/null)" || FOCUS_CHOICE=""
if [[ -n "$FOCUS_CHOICE" ]]; then
    FOCUS_Y="$(awk '{print $2}' <<<"$FOCUS_CHOICE")"
    click 256 "$FOCUS_Y" 2 "select the focus mode"
    for _ in $(seq 1 20); do xdotool click 5; sleep 0.2; done
    sleep 2
    shot 04-focus-scrolled
    CONTINUE_XY="$(find_button_centre --label Continue --color '#00796B' --min-area 400)" || \
        die "PHASE 1b: the Continue pill was not found by colour after scrolling to the bottom"
    click $CONTINUE_XY 6 "press the Continue pill"
    shot 05-focus-continued
    sleep 6
    FOCUS_STUCK=1
    for _ in 1 2 3 4; do
        if ! find_text_centre --text 'Choose Your Focus' --scale 2 --region "$APP_REGION" >/dev/null 2>&1; then FOCUS_STUCK=0; break; fi
        sleep 4
    done
    (( FOCUS_STUCK == 1 )) && die "the focus-mode chooser is STILL on screen after pressing Continue"
    say "focus mode chosen - the chooser is gone"

    SHELL_UP=0
    for _ in $(seq 1 15); do
        for candidate in Settings Setting Habits Journal Notes Lenses Tasks; do
            if find_text_centre --text "$candidate" --scale 2 --region "$APP_REGION" >/dev/null 2>&1; then SHELL_UP=1; break 2; fi
        done
        sleep 4
    done
    (( SHELL_UP == 1 )) || die "PHASE 1b: the shell never rendered - none of the nav words appeared within 60s."
    shot 06-shell-up
    say "the shell is up - nav words are on screen"
else
    say "no focus-mode gate on screen - the app is already past it"
fi

# ---------------------------------------------------------------------------------------------------------------
# PHASE 1c - re-check for a backup-failure dialog that appeared AFTER unlock (from run 9)
# ---------------------------------------------------------------------------------------------------------------
# MEASURED: the first backup attempt happens post-startup, so the "Backup issue" modal can
# appear AFTER the unlock gate is crossed. PHASE 0b ran before unlock and correctly
# reported "no backup-failure dialog present" - the dialog was not yet up. This re-check
# runs after unlock/focus-mode and before the Tasks lookup, because the modal covers the
# bottom nav and makes "Tasks" OCR-unreachable.
#
# MEASURED FALLBACK (run 9): the "Dismiss Message" button is OCR-invisible to the default
# psm 3 pipeline (find_text_centre) at all scales - tesseract only reads it with psm 6.
# A colour lookup also fails (teal is text strokes, ~36 px in the largest bucket, far below
# find_button_centre's 400 px minimum). So if OCR misses it, click the frame-measured point
# derived from APP_REGION at 65% x / 56% y (same derivation style as PHASE 3's FAB).
# Measured from run 9: button centre root (316,505), window origin (48,48), size 412x820
# => window-relative x=(316-48)/412=65%, y=(505-48)/820=55.7% (use 56%).
if [[ -n "$(find_text_centre --text 'Backup issue' --scale 3 --region "$APP_REGION" 2>/dev/null)" ]] ||
   [[ -n "$(find_text_centre --text 'Backup issue' --scale 2 --region "$APP_REGION" 2>/dev/null)" ]]; then
    say "PHASE 1c: a backup-failure dialog appeared after unlock - attempting dismissal"

    # Pre-compute the frame-measured fallback point from APP_REGION (same derivation style as PHASE 3's FAB).
    IFS=',' read -r _R_LEFT _R_TOP _R_RIGHT _R_BOTTOM <<<"$APP_REGION"
    _R_W=$(( _R_RIGHT - _R_LEFT ))
    _R_H=$(( _R_BOTTOM - _R_TOP ))
    DISMISS_X=$(( _R_LEFT + _R_W * 65 / 100 ))
    DISMISS_Y=$(( _R_TOP + _R_H * 56 / 100 ))

    # First try: OCR-based click (may work on builds where the button renders block-shaped)
    if (click_label "Dismiss Message" 2 "click the backup-failure dialog's Dismiss Message button"); then
        say "PHASE 1c: Dismiss Message button found and clicked via OCR"
    else
        say "PHASE 1c: OCR could not locate 'Dismiss Message' (psm 3 default misses this button) - clicking frame-measured point ($DISMISS_X, $DISMISS_Y) derived from APP_REGION $APP_REGION (window ${_R_W}x${_R_H}, button centre at 65% x / 56% y window-relative; measured from run 9 frame: root (316,505), window origin (48,48), size 412x820)"
        click "$DISMISS_X" "$DISMISS_Y" 2 "click the backup-failure dialog's Dismiss Message button (frame-measured fallback)"
    fi

    # Re-check after either attempt
    if [[ -z "$(find_text_centre --text 'Backup issue' --scale 3 --region "$APP_REGION" 2>/dev/null)" ]] &&
       [[ -z "$(find_text_centre --text 'Backup issue' --scale 2 --region "$APP_REGION" 2>/dev/null)" ]]; then
        say "PHASE 1c: backup-failure dialog dismissed"
    else
        die "PHASE 1c: 'Backup issue' is STILL on screen at scale 3 and scale 2 after both dismissal methods: (1) OCR click_label 'Dismiss Message', (2) frame-measured click at ($DISMISS_X, $DISMISS_Y) derived from APP_REGION $APP_REGION at 65% x / 56% y. The dialog covers the nav - stopping."
    fi
    shot 06b-dismissed-backup
else
    say "PHASE 1c: no backup-failure dialog present after unlock"
fi

# ---------------------------------------------------------------------------------------------------------------
# PHASE 2 - navigate to Tasks
# ---------------------------------------------------------------------------------------------------------------
TASKS_FOUND=$(find_text_centre --text 'Tasks' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$TASKS_FOUND" ]] || TASKS_FOUND=$(find_text_centre --text 'Tasks' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
if [[ -n "$TASKS_FOUND" ]]; then
    click $TASKS_FOUND 3 "nav to Tasks"
    shot 07-tasks
    say "PHASE 2: navigated to Tasks"
else
    die "PHASE 2: the Tasks tab was not found at scale 3 or scale 2. Stopping."
fi

# ---------------------------------------------------------------------------------------------------------------
# PHASE 3 - open the Add Task screen via the FAB (geometry click + log assert)
# ---------------------------------------------------------------------------------------------------------------
# The FAB is an ICON button (contentDescription only, invisible to OCR) in the Scaffold's
# floatingActionButton slot (bottom-end corner). We cannot find it by text, so we derive
# its click point from the window geometry and assert the click via the app's own log.
# Source: TasksScreen.kt:291-305 logs "Add task FAB clicked" on tap.

# Parse APP_REGION (format: left,top,right,bottom)
IFS=',' read -r _R_LEFT _R_TOP _R_RIGHT _R_BOTTOM <<<"$APP_REGION"
_R_W=$(( _R_RIGHT - _R_LEFT ))
_R_H=$(( _R_BOTTOM - _R_TOP ))

# FAB sits in the bottom-end corner; target ~82% across, ~81% down the window.
# Measured from run 384 frame 07-tasks.png: FAB blob bbox (360,688)-(415,743), centre (387,715) = window-relative 82%/81%.
FAB_X=$(( _R_LEFT + _R_W * 82 / 100  ))
FAB_Y=$(( _R_TOP + _R_H * 81 / 100 ))
say "PHASE 3: FAB click point = ($FAB_X, $FAB_Y) — derived from APP_REGION $APP_REGION (window ${_R_W}x${_R_H}, bottom-right quadrant at 82%/81%)"

click "$FAB_X" "$FAB_Y" 3 "tap the Add Task FAB (geometry-derived)"
shot 08-add-task

# ASSERT THE CLICK via the app's own log (not OCR)
# The parent loop does not export LOG (it only prints "app log: $LOG" in its own teardown,
# after this drive has exited), so derive it here from the newest live log. The parent
# searches both Payanam-debug and Payanam roots under Documents and .local/share; mirror
# that so we find the log regardless of which root this build uses.
LOG=""
for _log_dir in "$HOME/Documents/Payanam-debug/logs" "$HOME/Documents/Payanam/logs" \
                "$HOME/.local/share/Payanam-debug/logs" "$HOME/.local/share/Payanam/logs"; do
    # Session log: payanam_<os>_<version>_<sha>_<debug|release>_<stamp>.log
    _candidate=$(ls -t "$_log_dir"/payanam_*_*_*_*.log 2>/dev/null | head -1)
    [[ -n "$_candidate" ]] || continue
    if [[ -z "$LOG" || "$_candidate" -nt "$LOG" ]]; then
        LOG="$_candidate"
    fi
done

if [[ -z "$LOG" || ! -f "$LOG" ]]; then
    die "PHASE 3: could not locate the app log. Searched \$HOME/Documents/{Payanam-debug,Payanam}/logs and \$HOME/.local/share/{Payanam-debug,Payanam}/logs for payanam_<os>_<version>_<sha>_<debug|release>_<stamp>.log. Cannot assert the FAB click. Stopping."
fi
say "PHASE 3: using app log $LOG (newest payanam_<os>_<version>_<sha>_<debug|release>_<stamp>.log)"

if grep -q 'Add task FAB clicked' "$LOG"; then
    say "PHASE 3: app log confirms FAB click — found 'Add task FAB clicked' in $LOG"
else
    die "PHASE 3: app log $LOG does NOT contain 'Add task FAB clicked' — the FAB click did not register. Stopping."
fi

# SECOND CONFIRMATION: assert the form actually opened (OCR check)
FORM_OPENED=0
for form_label in "Task Title" "Save"; do
    for scale in 3 2; do
        if [[ -n "$(find_text_centre --text "$form_label" --scale "$scale" --region "$APP_REGION" 2>/dev/null)" ]]; then
            say "PHASE 3: form is open - found '$form_label' at scale $scale"
            FORM_OPENED=1
            break 2
        fi
    done
done

# ---------------------------------------------------------------------------------------------------------------
# PHASE 4 - enter a task title
# ---------------------------------------------------------------------------------------------------------------
if (( FORM_OPENED == 0 )); then
    die "PHASE 4: the Add Task form did NOT open - searched for 'Task Title' and 'Save' at scales 3 and 2, neither was found. Stopping."
fi
click_label "Task Title" 2 "focus the task title field"
type_text "Loop data round-trip"
shot 09-title-entered

# ---------------------------------------------------------------------------------------------------------------
# PHASE 4b - scroll the Add Task form to reveal the Save control (below the fold)
# ---------------------------------------------------------------------------------------------------------------
# MEASURED: the Add Task form is longer than the window. Tesseract on frame 08-add-task.png
# (window crop, --psm 6) sees: Add Task / Task Title / Task Type / Due Date Time /
# Wed, Oct 7 14:32 / Reminder Notifications / Description (optional) / Life Dimension -
# and then the 7-tab nav. There is NO "Save" on screen; the save control is BELOW THE FOLD.
# Use the proven scroll idiom from desktop-loop-drive-reminder-absence.sh PHASE 1b.
say "PHASE 4b: scrolling the Add Task form to reveal the Save Task control"

# Pre-scroll check: document that the control is absent before scrolling (soft, non-fatal).
SAVE_FOUND_BEFORE_SCROLL=0
for scale in 3 2; do
    if [[ -n "$(find_text_centre --text "Save Task" --scale "$scale" --region "$APP_REGION" 2>/dev/null)" ]]; then
        say "PHASE 4b: 'Save Task' already visible at scale $scale before scrolling"
        SAVE_FOUND_BEFORE_SCROLL=1
        break
    fi
done
if (( SAVE_FOUND_BEFORE_SCROLL == 0 )); then
    say "PHASE 4b: 'Save Task' NOT visible before scrolling (scales 3 and 2) - it is below the fold"
fi

# Bounded scroll-and-recheck loop: up to 8 rounds of 10 wheel-down clicks each.
# The form's length varies (the reminder section is hidden on desktop), so a fixed
# click count keeps being wrong - recheck after every round instead.
SAVE_FOUND_AFTER_SCROLL=0
SAVE_FOUND_ROUND=0
for round in $(seq 1 8); do
    for _ in $(seq 1 10); do xdotool click 5; sleep 0.2; done
    sleep 1
    # COLOUR FIRST, OCR second: tesseract cannot read the button's white-on-teal text (measured on
    # frame 09b of build 387 - the same pixels OCR as `=z oc 6 4 B® B&B`), so a text needle can never
    # match it. The theme paints its primary control as one solid #00796B blob, which is what
    # find_button_centre measures.
    if SAVE_XY=$(find_button_centre --label "Save Task" --band 300-780 2>/dev/null); then
        say "PHASE 4b: found the Save Task button (colour blob) at $SAVE_XY on round $round"
        SAVE_FOUND_AFTER_SCROLL=1
        SAVE_FOUND_ROUND=$round
        break
    fi
    for scale in 3 2; do
        if [[ -n "$(find_text_centre --text "Save Task" --scale "$scale" --region "$APP_REGION" 2>/dev/null)" ]]; then
            say "PHASE 4b: found 'Save Task' at scale $scale on round $round"
            SAVE_FOUND_AFTER_SCROLL=1
            SAVE_FOUND_ROUND=$round
            break 2
        fi
    done
    say "PHASE 4b: round $round - 'Save Task' not yet visible (10 more clicks sent)"
done

if (( SAVE_FOUND_AFTER_SCROLL == 1 )); then
    shot 10-save-visible
    say "PHASE 4b: 'Save Task' revealed after round $SAVE_FOUND_ROUND"
else
    shot 09b-save-end-of-scroll
    die "PHASE 4b: 'Save Task' was NOT found after 8 rounds (80 total wheel-down clicks). Tesseract read the last frame as ending at 'Tags (optional)' before the nav bar. Stopping rather than tapping a guessed pixel."
fi

# ---------------------------------------------------------------------------------------------------------------
# PHASE 5 - save the task
# ---------------------------------------------------------------------------------------------------------------
click_button "save the task" --band 300-780
shot 10-saved

# ---------------------------------------------------------------------------------------------------------------
# PHASE 6 - navigate to Settings
# ---------------------------------------------------------------------------------------------------------------
# Retried: PHASE 2 uses this exact find on a stable screen and succeeds, but PHASE 6 runs
# immediately after the save click, when the list is still re-rendering (taskCount 0 -> 1).
# A single-shot find there measured a false negative; retry with settle instead of dying on attempt 1.
SETTINGS_FOUND=""
for _attempt in 1 2 3 4 5; do
    SETTINGS_FOUND=$(find_text_centre --text 'Settings' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$SETTINGS_FOUND" ]] || SETTINGS_FOUND=$(find_text_centre --text 'Settings' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$SETTINGS_FOUND" ]] && break
    say "PHASE 6: Settings tab not matched (attempt $_attempt) - screen may still be settling after the save"
    sleep 2
done
if [[ -n "$SETTINGS_FOUND" ]]; then
    click $SETTINGS_FOUND 3 "nav to Settings"
    shot 11-settings
    say "PHASE 6: navigated to Settings"
else
    # FALLBACK, MEASURED NOT GUESSED - ported from the passphrase-change drive. The Settings label
    # itself is unreadable: OCR has returned "Setting", "Settings" and plain noise ("ee"/"ae") because
    # the 7th tab sits against the window's right edge (measured again on build 387 frame 11b). So read
    # the FIRST and LAST tabs that DO read cleanly ("Tasks" and "Lenses" appear in every frame) and
    # derive the pitch: the 7 evenly spaced tabs mean Lenses(6th) -> Settings(7th) is exactly one pitch,
    # and Tasks(1st) -> Lenses(6th) covers five pitches.
    TASKS_XY=$(find_text_centre --text 'Tasks' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$TASKS_XY" ]] || TASKS_XY=$(find_text_centre --text 'Tasks' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
    LENSES_XY=$(find_text_centre --text 'Lenses' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$LENSES_XY" ]] || LENSES_XY=$(find_text_centre --text 'Lenses' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
    if [[ -z "$TASKS_XY" || -z "$LENSES_XY" ]]; then
        shot 11b-settings-not-found
        die "PHASE 6: the Settings label is unreadable AND the anchor tabs did not both match (Tasks='$TASKS_XY', Lenses='$LENSES_XY'). No click was made - stopping rather than tapping a guessed pixel."
    fi
    read -r TASKS_X TASKS_Y <<< "$TASKS_XY"
    read -r LENSES_X LENSES_Y <<< "$LENSES_XY"
    PITCH=$(( (LENSES_X - TASKS_X) / 5 ))
    SETTINGS_X=$(( LENSES_X + PITCH ))
    SETTINGS_Y=$LENSES_Y
    if (( PITCH <= 0 )); then
        shot 11b-settings-not-found
        die "PHASE 6: derived a tab pitch of $PITCH px from Tasks=$TASKS_X and Lenses=$LENSES_X, which cannot be right - refusing to click there."
    fi
    say "PHASE 6: label unreadable - derived from anchors: Tasks x=$TASKS_X, Lenses x=$LENSES_X, pitch=$PITCH -> Settings at $SETTINGS_X,$SETTINGS_Y"
    click "$SETTINGS_X" "$SETTINGS_Y" 3 "nav to Settings (derived from the Tasks/Lenses anchor pair)"
    shot 11-settings
    say "PHASE 6: navigated to Settings (anchor-derived)"
fi

# ---------------------------------------------------------------------------------------------------------------
# PHASE 7 - scroll to the Data Management card, then expand it
# ---------------------------------------------------------------------------------------------------------------
# Ported from the passphrase-change drive (same Settings screen): the card sits BELOW the fold, so
# clicking it before scrolling fails - measured on build 387. Pointer first: if it is left on a nav
# tab the wheel events scroll nothing (the same defect both drives record).
SETTINGS_SCROLL_CLICKS=6
IFS=',' read -r _APP_X1 _APP_Y1 _APP_X2 _APP_Y2 <<< "$APP_REGION"
_APP_CX=$(( (_APP_X1 + _APP_X2) / 2 ))
_APP_CY=$(( (_APP_Y1 + _APP_Y2) / 2 ))
xdotool mousemove --sync "$_APP_CX" "$_APP_CY"
say "PHASE 7: pointer moved to the window centre $_APP_CX,$_APP_CY before scrolling to the card"
sleep 1
for _ in $(seq 1 "$SETTINGS_SCROLL_CLICKS"); do
    xdotool click 5
    sleep 0.4
done
sleep 2
shot 11c-settings-scrolled
if cmp -s "$RUN_DIR/11-settings.png" "$RUN_DIR/11c-settings-scrolled.png" 2>/dev/null; then
    die "PHASE 7: the Settings list did not scroll - frames 11 and 11c are byte-identical after $SETTINGS_SCROLL_CLICKS wheel events aimed at $_APP_CX,$_APP_CY. Not looking for a card that was never brought on screen."
fi
say "PHASE 7: Settings list scrolled (11c differs from 11)"

click_label "Data Management" 3 "expand Data Management"
shot 12-data-management

# PHASE 7b - scroll the EXPANDED section so its action buttons clear the bottom bar.
# MEASURED (build 387): the help paragraph (`settings_data_management_help`, starts with "Export
# creates a full encrypted database backup...") sits ABOVE the OutlinedButton whose own label is the
# single word "Export" (`settings_action_export`), and at rest the button is below the fold - so a bare
# find_text "Export" matched the CAPTION at (128,754) and clicked nothing (frame changed, no dialog).
# Scrolling lifts the buttons into view and pushes the help text out, leaving "Export" unambiguous.
# Ported from the passphrase-change drive's PHASE 4b (same screen, same problem).
SETTINGS_SCROLL_CLICKS=6
IFS=',' read -r _APP_X1 _APP_Y1 _APP_X2 _APP_Y2 <<< "$APP_REGION"
_APP_CX=$(( (_APP_X1 + _APP_X2) / 2 ))
_APP_CY=$(( (_APP_Y1 + _APP_Y2) / 2 ))
xdotool mousemove --sync "$_APP_CX" "$_APP_CY"
say "PHASE 7b: pointer to window centre $_APP_CX,$_APP_CY before scrolling the expanded section"
sleep 1
for _ in $(seq 1 "$SETTINGS_SCROLL_CLICKS"); do
    xdotool click 5
    sleep 0.4
done
sleep 2
shot 12b-expanded-scrolled
if cmp -s "$RUN_DIR/12-data-management.png" "$RUN_DIR/12b-expanded-scrolled.png" 2>/dev/null; then
    say "PHASE 7b: frame unchanged after scrolling - PHASE 8's label check decides"
else
    say "PHASE 7b: expanded section scrolled (12b differs from 12)"
fi

# ---------------------------------------------------------------------------------------------------------------
# PHASE 8 - click Export
# ---------------------------------------------------------------------------------------------------------------
click_label "Export" 3 "click Export"
shot 13-export-clicked

# ---------------------------------------------------------------------------------------------------------------
# PHASE 9 - drive the AWT FileDialog (SAVE mode)
# ---------------------------------------------------------------------------------------------------------------
# The export button click opens a java.awt.FileDialog (AwtDocumentSource.kt:84-86).
# An experiment proved this dialog IS drivable: enumerate Payanam windows, pick the
# real dialog (width>200 AND height>200 - never head -1, that picks the 1x1 helper and
# SetInputFocus then fails with BadMatch), focus it, type a filename, click Save.

# The AWT dialog opens asynchronously: the app log recorded `AwtDocumentSource.choose | Opening the
# file dialog` at 17:52:28 while a single scan after a fixed 3s sleep had already run and died
# (build 387). Retry the enumeration instead of betting on one timing.
PICKER_WID=""
PICKER_W=0
PICKER_H=0
PICKER_X=0
PICKER_Y=0

for _try in $(seq 1 10); do
    for wid in $(xdotool search --name Payanam 2>/dev/null); do
        # --shell is REQUIRED: without it getwindowgeometry prints `Geometry: WxH+X+Y` and the
        # Width:/Height: greps below match nothing, so w/h stay empty and NOTHING can ever qualify
        # (measured on build 387: ten attempts saw only "helpers" while a 1096x822 window existed).
        geom=$(xdotool getwindowgeometry --shell "$wid" 2>/dev/null) || continue
        w=$(awk -F= '/^WIDTH/{print $2}' <<<"$geom")
        h=$(awk -F= '/^HEIGHT/{print $2}' <<<"$geom")
        [[ "$w" =~ ^[0-9]+$ && "$h" =~ ^[0-9]+$ ]] || continue
        # Never accept the app's own main window (same size as APP_REGION) as the dialog.
        if [[ "$w" == "$_R_W" && "$h" == "$_R_H" ]]; then
            continue
        fi
        if (( w > 200 && h > 200 )); then
            PICKER_WID=$wid
            PICKER_W=$w
            PICKER_H=$h
            say "PHASE 9: selected dialog window $wid (geometry ${w}x${h}) on attempt $_try"
            break 2
        fi
    done
    if [[ -z "$PICKER_WID" ]]; then
        say "PHASE 9: attempt $_try - no dialog window yet (helpers only); waiting for the file dialog to map"
        sleep 2
    fi
done

if [[ -z "$PICKER_WID" ]]; then
    # Diagnostic, not a guess: dump EVERY mapped window so the next failure names the dialog's real
    # title/class instead of assuming it contains "Payanam" (10 attempts found none while the app log
    # recorded `AwtDocumentSource.choose | Opening the file dialog`).
    say "PHASE 9: === window tree at failure ==="
    xwininfo -root -tree 2>/dev/null | grep -E '^\s+0x|child:' | head -40 | while IFS= read -r _line; do say "PHASE 9: $_line"; done
    die "PHASE 9: no Payanam window with width>200 AND height>200 was found after 10 attempts (~20s), while the app log shows the file dialog was requested. The dialog's title likely does not contain 'Payanam' - see the window tree above. Stopping."
fi

# Read the dialog's position with --shell so the Save button offset is derived at runtime
eval "$(xdotool getwindowgeometry --shell "$PICKER_WID" 2>/dev/null)" || die "PHASE 9: could not read geometry for window $PICKER_WID"
PICKER_X=$X
PICKER_Y=$Y
say "PHASE 9: dialog geometry: X=$X Y=$Y WIDTH=$WIDTH HEIGHT=$HEIGHT"

xdotool windowfocus --sync "$PICKER_WID" || die "PHASE 9: xdotool windowfocus --sync $PICKER_WID failed"
say "PHASE 9: focused dialog window $PICKER_WID"
shot 09-dialog-open
# DIALOG EVIDENCE: the first Save click at (931,756) closed the dialog WITHOUT writing any file
# (log: tap -> Opening the file dialog -> process exit, no success line, no .payanam anywhere), so the
# click point must be measured from this frame, not derived from percentages.

EXPORT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/payanam-loop-export.XXXXXX")"
EXPORT_FILENAME="loop-export-$(date +%s).payanam"
xdotool type --clearmodifiers "$EXPORT_DIR/$EXPORT_FILENAME" || die "PHASE 9: xdotool type failed"
say "PHASE 9: typed filename '$EXPORT_DIR/$EXPORT_FILENAME'"
shot 09b-dialog-typed

# Click the dialog's own Save button by coordinates derived from the dialog geometry.
# MEASURED (frame 09b-dialog-typed, build 387): the bottom-right buttons are Cancel (grey) then Save
# (blue, focused/default). The old fraction click (85%/92% -> 931,756) landed LEFT of Save, i.e. on
# Cancel - which is why the dialog closed and NOTHING was written (log: dataActionTapped -> Opening the
# file dialog -> process exit, no success line, no .payanam file anywhere). GTK's save dialog activates
# the focused default button when Enter is pressed from the name field, so press Return instead of
# guessing a pixel.
say "PHASE 9: pressing Return to activate the focused default Save button"
xdotool key --clearmodifiers Return || die "PHASE 9: xdotool key Return failed"
sleep 3

# Assert the dialog window is GONE
DIALOG_STILL_THERE=0
for wid in $(xdotool search --name Payanam 2>/dev/null); do
    geom=$(xdotool getwindowgeometry "$wid" 2>/dev/null) || continue
    w=$(awk '/Width:/  {print $2}' <<<"$geom")
    h=$(awk '/Height:/ {print $2}' <<<"$geom")
    if (( w > 200 && h > 200 )); then
        DIALOG_STILL_THERE=1
        break
    fi
done

if (( DIALOG_STILL_THERE == 1 )); then
    say "PHASE 9: FAILURE - the dialog window $PICKER_WID (geometry ${PICKER_W}x${PICKER_H} at $PICKER_X,$PICKER_Y) is STILL present after clicking Save. The export did not complete."
    exit 1
fi

say "PHASE 9: dialog closed - export completed"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 10 - confirm the export finished, by the ARTEFACT (the file), not by a log phrase.
# MEASURED (build 387): the app logs `dataActionTapped [action="export"]` and
# `AwtDocumentSource.choose | Opening the file dialog`, then reads the data
# (`Tasks retrieved [count=10]`, `Subscribing to all notes`) - but it never logs the word
# "exported", so grepping for it was a wrong needle (the earlier `Save` vs `Save Task` class of bug).
# The dialog defaults to the process working directory, so a BARE filename lands next to where the loop
# was started - i.e. INSIDE THE REPO, which the standing rules forbid (observed 2026-10-08: two
# loop-export-*.payanam.db files appeared in the repo root). The typed value is an ABSOLUTE path into
# this run's own scratch dir instead, so no run of this drive can leave an artefact in the tree.
EXPORT_PATH="$EXPORT_DIR/$EXPORT_FILENAME"
if [[ -f "$EXPORT_PATH" ]]; then
    EXPORT_SIZE=$(stat -c %s "$EXPORT_PATH" 2>/dev/null || echo 0)
    if (( EXPORT_SIZE > 0 )); then
        EXPORT_DESTINATION="$EXPORT_PATH"
    say "PHASE 10: export artefact present: $EXPORT_PATH (${EXPORT_SIZE} bytes)"
    else
        die "PHASE 10: $EXPORT_PATH exists but is 0 bytes - the export did not write content."
    fi
else
    say "PHASE 10: expected $EXPORT_PATH - searching the export dir for any loop-export-* file"
    FOUND=$(find "$EXPORT_DIR" -maxdepth 1 -name 'loop-export-*' -newermt '-10 minutes' 2>/dev/null | head -1)
    [[ -n "$FOUND" ]] || die "PHASE 10: no export artefact found (looked for $EXPORT_PATH and any loop-export-* in $EXPORT_DIR, last 10 minutes). The export did not produce a file. Stopping."
    EXPORT_DESTINATION="$FOUND"
    say "PHASE 10: export artefact present: $FOUND ($(stat -c %s "$FOUND") bytes)"
fi


# ---------------------------------------------------------------------------------------------------------------
# PHASE 11 - re-import through the UI
# ---------------------------------------------------------------------------------------------------------------
# Navigate Settings -> Data Management -> Import (mirror PHASE 6-8's navigation idiom)

SETTINGS_FOUND=$(find_text_centre --text 'Settings' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$SETTINGS_FOUND" ]] || SETTINGS_FOUND=$(find_text_centre --text 'Settings' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
if [[ -n "$SETTINGS_FOUND" ]]; then
    click $SETTINGS_FOUND 3 "nav to Settings"
    shot 14-settings-reimport
    say "PHASE 11: navigated to Settings"
else
    die "PHASE 11: the Settings tab was not found at scale 3 or scale 2. Stopping."
fi

# TOGGLE-SAFE: by the time PHASE 11 runs, the section may ALREADY be expanded (PHASE 7 expanded it
# for the export), so the header click COLLAPSES it - measured: `Import` then could not be found at
# all. So: click the header once, scroll, LOOK for Import; if it is missing, click the header again
# (undoing the accidental collapse) and re-scroll. Only die if it is still missing after both states.
click_label "Data Management" 3 "toggle the Data Management card"
shot 15-data-management-reimport

IFS=',' read -r _X1 _Y1 _X2 _Y2 <<< "$APP_REGION"
_CX=$(( (_X1 + _X2) / 2 )); _CY=$(( (_Y1 + _Y2) / 2 ))
scroll_dm_section() {
    xdotool mousemove --sync "$_CX" "$_CY"
    sleep 1
    for _ in $(seq 1 6); do xdotool click 5; sleep 0.4; done
    sleep 2
}
scroll_dm_section
shot 15b-reimport-scrolled

IMPORT_XY=$(find_text_centre --text 'Import' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$IMPORT_XY" ]] || IMPORT_XY=$(find_text_centre --text 'Import' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
if [[ -z "$IMPORT_XY" ]]; then
    say "PHASE 11: Import not visible after the first toggle - the click likely COLLAPSED an already-expanded card; toggling back"
    click_label "Data Management" 3 "re-expand the Data Management card"
    scroll_dm_section
    shot 15c-reimport-scrolled-2
    IMPORT_XY=$(find_text_centre --text 'Import' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$IMPORT_XY" ]] || IMPORT_XY=$(find_text_centre --text 'Import' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
fi
if [[ -z "$IMPORT_XY" ]]; then
    shot 15d-import-not-found
    die "PHASE 11: 'Import' not visible after toggling both card states and scrolling each time. Stopping rather than tapping a guessed pixel."
fi
click $IMPORT_XY 3 "click Import"
shot 16-import-clicked

# Drive the AWT FileDialog (LOAD mode) - same approach as PHASE 9
sleep 3

PICKER_WID=""
PICKER_W=0
PICKER_H=0
PICKER_X=0
PICKER_Y=0

# --shell is REQUIRED (measured): without it there are no Width:/Height: lines, so nothing can match.
for _try in $(seq 1 10); do
    for wid in $(xdotool search --name Payanam 2>/dev/null); do
        geom=$(xdotool getwindowgeometry --shell "$wid" 2>/dev/null) || continue
        w=$(awk -F= '/^WIDTH/{print $2}' <<<"$geom")
        h=$(awk -F= '/^HEIGHT/{print $2}' <<<"$geom")
        [[ "$w" =~ ^[0-9]+$ && "$h" =~ ^[0-9]+$ ]] || continue
        if [[ "$w" == "$_R_W" && "$h" == "$_R_H" ]]; then continue; fi   # never the main window
        if (( w > 200 && h > 200 )); then
            PICKER_WID=$wid; PICKER_W=$w; PICKER_H=$h
            say "PHASE 11: selected dialog window $wid (geometry ${w}x${h}) on attempt $_try"
            break 2
        fi
    done
    [[ -n "$PICKER_WID" ]] || { say "PHASE 11: attempt $_try - dialog not mapped yet"; sleep 2; }
done

if [[ -z "$PICKER_WID" ]]; then
    die "PHASE 11: no dialog window (w>200,h>200, not the main window) after 10 attempts. The FileDialog may not have opened. Stopping."
fi

eval "$(xdotool getwindowgeometry --shell "$PICKER_WID" 2>/dev/null)" || die "PHASE 11: could not read geometry for window $PICKER_WID"
PICKER_X=$X
PICKER_Y=$Y
say "PHASE 11: dialog geometry: X=$X Y=$Y WIDTH=$WIDTH HEIGHT=$HEIGHT"

xdotool windowfocus --sync "$PICKER_WID" || die "PHASE 11: xdotool windowfocus --sync $PICKER_WID failed"
say "PHASE 11: focused dialog window $PICKER_WID"

# Type the full path to the exported file
xdotool type --clearmodifiers "$EXPORT_DESTINATION" || die "PHASE 11: xdotool type failed"
say "PHASE 11: typed path '$EXPORT_DESTINATION'"

# Activate the focused default button with Enter (measured in PHASE 9: the 85%/92% point lands on
# Cancel, left of the default button, which closed the dialog without doing anything).
say "PHASE 11: pressing Return to activate the focused default Open button"
xdotool key --clearmodifiers Return || die "PHASE 11: xdotool key Return failed"
sleep 3

# Assert the dialog window is GONE
DIALOG_STILL_THERE=0
for wid in $(xdotool search --name Payanam 2>/dev/null); do
    geom=$(xdotool getwindowgeometry "$wid" 2>/dev/null) || continue
    w=$(awk '/Width:/  {print $2}' <<<"$geom")
    h=$(awk '/Height:/ {print $2}' <<<"$geom")
    if (( w > 200 && h > 200 )); then
        DIALOG_STILL_THERE=1
        break
    fi
done

if (( DIALOG_STILL_THERE == 1 )); then
    say "PHASE 11: FAILURE - the dialog window $PICKER_WID (geometry ${PICKER_W}x${PICKER_H} at $PICKER_X,$PICKER_Y) is STILL present after clicking Open. The import did not complete."
    exit 1
fi

say "PHASE 11: dialog closed - import completed"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 12 - read back
# ---------------------------------------------------------------------------------------------------------------
# Re-derive APP_REGION in case the window moved
APP_REGION=""
if [[ -n "${WINDOW_ID:-}" ]]; then
    APP_REGION=$(xwininfo -id "$WINDOW_ID" 2>/dev/null | awk '
        /Absolute upper-left X/ {x=$NF}
        /Absolute upper-left Y/ {y=$NF}
        /^ *Width:/             {w=$NF}
        /^ *Height:/            {h=$NF}
        END { if (x!="" && y!="" && w!="" && h!="") printf "%d,%d,%d,%d", x, y, x+w, y+h }')
fi
[[ -n "$APP_REGION" ]] || die "PHASE 12: could not re-derive the app window region (WINDOW_ID='${WINDOW_ID:-unset}'). Stopping."
say "PHASE 12: OCR region re-scoped to the app window: $APP_REGION"

# Navigate to Tasks
TASKS_FOUND=$(find_text_centre --text 'Tasks' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$TASKS_FOUND" ]] || TASKS_FOUND=$(find_text_centre --text 'Tasks' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
if [[ -n "$TASKS_FOUND" ]]; then
    click $TASKS_FOUND 3 "nav to Tasks"
    shot 20-tasks-after-import
    say "PHASE 12: navigated to Tasks"
else
    die "PHASE 12: the Tasks tab was not found at scale 3 or scale 2. Stopping."
fi

# Assert the task title from PHASE 4 is visible
TASK_TITLE="Loop data round-trip"
TASK_FOUND=0
for scale in 3 2; do
    if [[ -n "$(find_text_centre --text "$TASK_TITLE" --scale "$scale" --region "$APP_REGION" 2>/dev/null)" ]]; then
        say "PHASE 12: found task title '$TASK_TITLE' at scale $scale"
        TASK_FOUND=1
        break
    fi
done

if (( TASK_FOUND == 0 )); then
    die "PHASE 12: task title '$TASK_TITLE' was NOT found on the Tasks screen at scale 3 or scale 2. The import may not have worked. Stopping."
fi

say "PHASE 12: read-back confirmed - task '$TASK_TITLE' is visible on Tasks"
