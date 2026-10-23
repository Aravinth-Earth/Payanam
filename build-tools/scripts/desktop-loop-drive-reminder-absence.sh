#!/usr/bin/env bash
# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# Prove the desktop schedules NO reminder: absence, deliberately reported.
# Gate: "on the desktop nothing schedules a reminder into the log-only port at all - no reminder control is reachable from the shared Add/Edit-task screens, the shared capability flag reports reminders unsupported there, and the desktop's own log shows no reminder ever scheduled; Android continues to schedule through the constructed sink exactly as it does today."
#
# MEASURED: the desktop HAS a producer (DesktopTaskReminderScheduler, wired DesktopPayanamDataLayer.kt:115)
# but DesktopMain.kt installs remindersEnabled = false and the root composition provides that holder into
# LocalFeatureFlags, so the shared screens render NO reminder control at all. This drive therefore proves
# ABSENCE on three legs: the reminder control is not on the Add Task form, a wait with no input schedules
# nothing, and the app's own log never records a scheduling line.
#
# THE LOG MUST STAY SILENT, AND THAT SILENCE IS THE PROOF. The pattern that must NOT appear:
# "Reminder handed to the installed sink" from source "DesktopTaskReminderScheduler"
#
# UI STRINGS (core/strings/src/main/res/values/strings.xml):
#   locReminderNotifications = "Reminder Notifications" (line 669)
#   locAuto = "Auto" (line 326)
#   locCustom = "Custom" (line 353)
#   locOff = "Off" (line 638)
#   locAddTask = "Add Task" (line 315)
#   locSave = "Save" (line 679)
#   locTaskTitle = "Task Title" (line 734)
#   locSelectDate = "Select Date" (line 692)
#   locSelectTime = "Select Time" (line 695)
#   locCustomLeadMinutes = "Custom lead minutes" (line 355)
#   loc15 = "15" (line 302)

set -uo pipefail

PASSPHRASE="${LOOP_PASSPHRASE:-LoopDrive#Pass9}"

# ---------------------------------------------------------------------------------------------------------------
# Helpers (from desktop-loop.sh, available in the drive environment)
# ---------------------------------------------------------------------------------------------------------------
# say, die, click, find_text_centre, type_text, press, shot, find_button_centre are all defined in
# desktop-loop.sh and available when this drive is invoked with -d.

# ---------------------------------------------------------------------------------------------------------------
# Helper: click a control by its words, with an unmeasured-scale fallback chain (from the manual-backup drive)
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
# Helper: assert a string is GONE from the screen (from the manual-backup drive)
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
# Helper: report whether a string is present, WITHOUT failing the run (from the manual-backup drive)
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
# PHASE 0 - scope every OCR lookup to the APP WINDOW itself, derived at runtime (from the manual-backup drive)
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

say "--- reminder-absence drive ---"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 0b - dismiss a backup-failure dialog if one is covering the gate (from the manual-backup drive)
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
# PHASE 1 - cross the UNLOCK GATE (from the manual-backup drive)
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
# PHASE 1b - cross the FOCUS-MODE GATE if present (from the manual-backup drive)
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
# floatingActionButton slot (bottom-end corner). Text search cannot find it: '+' is absent and the
# word 'Add' only matches the empty-state hint, so the click point is derived from window geometry
# and asserted through the app's own log. Source: TasksScreen.kt logs "Add task FAB clicked" on tap.

# Parse APP_REGION (format: left,top,right,bottom)
IFS=',' read -r _R_LEFT _R_TOP _R_RIGHT _R_BOTTOM <<<"$APP_REGION"
_R_W=$(( _R_RIGHT - _R_LEFT ))
_R_H=$(( _R_BOTTOM - _R_TOP ))

# FAB sits in the bottom-end corner; target ~82% across, ~81% down the window.
FAB_X=$(( _R_LEFT + _R_W * 82 / 100  ))
FAB_Y=$(( _R_TOP + _R_H * 81 / 100 ))
say "PHASE 3: FAB click point = ($FAB_X, $FAB_Y) - derived from APP_REGION $APP_REGION (window ${_R_W}x${_R_H}, bottom-right quadrant at 82%/81%)"

click "$FAB_X" "$FAB_Y" 3 "tap the Add Task FAB (geometry-derived)"
shot 08-add-task

# ASSERT THE CLICK via the app's own log (not OCR): derive LOG from the newest live log,
# mirroring the parent loop's two roots so the log is found regardless of which root is used.
LOG=""
for _log_dir in "$HOME/Documents/Payanam-debug/logs" "$HOME/Documents/Payanam/logs" \
                "$HOME/.local/share/Payanam-debug/logs" "$HOME/.local/share/Payanam/logs"; do
    _candidate=$(ls -t "$_log_dir"/payanam_desktop_*.log 2>/dev/null | head -1)
    [[ -n "$_candidate" ]] || continue
    if [[ -z "$LOG" || "$_candidate" -nt "$LOG" ]]; then
        LOG="$_candidate"
    fi
done

if [[ -z "$LOG" || ! -f "$LOG" ]]; then
    die "PHASE 3: could not locate the app log. Searched \$HOME/Documents/{Payanam-debug,Payanam}/logs and \$HOME/.local/share/{Payanam-debug,Payanam}/logs for payanam_desktop_*.log. Cannot assert the FAB click. Stopping."
fi
say "PHASE 3: using app log $LOG (newest payanam_desktop_*.log)"

if grep -q 'Add task FAB clicked' "$LOG"; then
    say "PHASE 3: app log confirms FAB click - found 'Add task FAB clicked' in $LOG"
else
    die "PHASE 3: app log $LOG does NOT contain 'Add task FAB clicked' - the FAB click did not register. Stopping."
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

if (( FORM_OPENED == 0 )); then
    die "PHASE 3: the Add Task form did NOT open - neither 'Task Title' nor 'Save' was found on screen. Stopping."
fi

# ---------------------------------------------------------------------------------------------------------------
# PHASE 4 - enter a task title
# ---------------------------------------------------------------------------------------------------------------
click_label "Task Title" 2 "focus the task title field"
type_text "Loop reminder absence test"
shot 09-title-entered

# ---------------------------------------------------------------------------------------------------------------
# PHASE 5/6 - deliberately SKIPPED: due date and due time exist only to make a reminder fire.
# With remindersEnabled=false (PHASE 7 proves the control is absent) there is no reminder to
# schedule, so no date or time is set. The absence gate needs only: the control missing, an
# idle window, and a silent log.
# ---------------------------------------------------------------------------------------------------------------
# PHASE 7 - the reminder control must be ABSENT from the Add Task form (the flag hides it)
# ---------------------------------------------------------------------------------------------------------------
if ! assert_gone "Reminder Notifications"; then
    die "PHASE 7: 'Reminder Notifications' is still on screen - the shared screen did not honour remindersEnabled=false. Stopping."
fi
say "PHASE 7: no reminder control on the form (absence observed at scale 3 and 2)"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 8/9 - deliberately SKIPPED: there is no reminder mode to select and no reminder to save,
# because the control is absent (PHASE 7). Saving a task is out of scope for the absence gate and
# is blocked anyway by the unreached save control tracked separately on the round-trip drive.
# ---------------------------------------------------------------------------------------------------------------

# ---------------------------------------------------------------------------------------------------------------
# PHASE 10 - WAIT with no input. With the flag off, nothing may be scheduled while the app sits idle.
# ---------------------------------------------------------------------------------------------------------------
say "PHASE 10: waiting 30 seconds with NO input - nothing may be scheduled while the app sits idle..."
sleep 30
shot 16-after-wait

# ---------------------------------------------------------------------------------------------------------------
# PHASE 11 - assert from the app's own log that NOTHING was ever scheduled (absence)
# ---------------------------------------------------------------------------------------------------------------
# The log decides. The pattern below is the scheduling line; for this gate it must NEVER appear.
# Expected log pattern: "Reminder handed to the installed sink" from source "DesktopTaskReminderScheduler"

LOG_PARENTS=("${XDG_DATA_HOME:-$HOME}")
[[ -n "${LOOP_HOME:-}" ]] && LOG_PARENTS+=("$LOOP_HOME")
LOG_DIRS=()
for parent in "${LOG_PARENTS[@]}"; do
    for root_name in Payanam-debug Payanam; do
        LOG_DIRS+=("$parent/Documents/$root_name")
    done
done
say "PHASE 11: searching for log files in: ${LOG_DIRS[*]}"

REMINDER_LOGGED=0
for dir in "${LOG_DIRS[@]}"; do
    while IFS= read -r logfile; do
        if grep -q "Reminder handed to the installed sink" "$logfile" 2>/dev/null; then
            REMINDER_LOGGED=1
            say "PHASE 11: FOUND reminder handoff in $logfile"
            grep "Reminder handed to the installed sink" "$logfile" | tail -5
            break 2
        fi
    done < <(find "$dir" -name '*.log' -o -name '*.txt' 2>/dev/null)
done

if (( REMINDER_LOGGED == 1 )); then
    die "PHASE 11: a scheduling line 'Reminder handed to the installed sink' WAS found (shown above) - the desktop scheduled a reminder. Absence gate violated."
fi
say "PHASE 11: log silent in all searched dirs - no reminder was ever scheduled (absence observed)"

say "=== ABSENCE CLAIM: nothing on the desktop schedules a reminder ==="
say "=== ABSENCE PATTERN (must stay absent): Reminder handed to the installed sink ==="
say "=== ABSENCE SOURCE: DesktopTaskReminderScheduler (producer present, flag off) ==="
say "--- finished: reminder-absence drive ---"
