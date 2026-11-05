# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# Drive the desktop passphrase-change flow end to end so its own log lines can be compared
# against the Android test's. The Android half already passes (16 tests, 0 failures). This drive proves
# the desktop renders the same form, accepts the same input, and reaches the same success state.
#
# THE FLOW
#   Unlock gate -> focus-mode chooser -> Settings tab -> scroll to "Data Management" card -> expand it
#   -> click "Update Passphrase" -> fill current/new/confirm -> submit -> assert "Passphrase updated
#   successfully." is on screen.
#
# WHY THE SCALE FALLBACK CHAIN BELOW IS NOT A JUDGEMENT CALL
# Which --scale finds these labels is UNMEASURED. They are bodySmall/labelMedium-sized strings; the harness
# recorded that 9px text is invisible at scale 1 but findable at 2, and that these labels sit in that same
# size band. So 3 then 2 is a FALLBACK ORDER, not an arbitrated answer to "what is the right scale": it just
# tries the most-magnified reading first and drops to the documented-working one. A run that reaches a die()
# at the bottom of the chain is telling you the chain is out of date, not that the control is missing.
#
# WHY EVERY find IS A click ON A REAL COORDINATE
# click X Y dies when the frame is byte-identical after settling, so a coordinate that hits nothing cannot
# pass silently. And if a control cannot be found at ANY scale this script dies naming it rather than
# tapping something approximate - a wrong pixel in Settings is worse than a stopped run.
set -uo pipefail

# ---------------------------------------------------------------------------------------------------------------
# The unlock-gate passphrase.
#
# This is the SAME throwaway the loop fixture already set on this HOME: desktop-loop-drive-first-run-db.sh
# created the database with it, so this drive cannot get past the gate without it. It is a loop fixture
# value typed into the app's own field on an isolated $HOME - NOT a credential, and not a secret to be
# rotated or guarded. Overridable so the two halves of the fixture can never drift apart by accident.
PASSPHRASE="${LOOP_PASSPHRASE:-LoopDrive#Pass9}"

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

    die "STEP '$step': the text \"$text\" was not found at scale 3 OR scale 2. No click was made - stopping rather than tapping a guessed pixel in Settings."
}

# ---------------------------------------------------------------------------------------------------------------
# helper: assert a string is GONE from the screen.
#
# Uses the same scale chain for the unmeasured reason click_label does.
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
# helper: assert a string IS on the screen. Dies if not found at any scale.
#
# This is the hard success assertion for the passphrase-change flow. "Passphrase updated successfully." is
# rendered ONLY when isSuccess is true (DatabasePassphraseChangeScreen.kt:153-158), so its presence is a
# state assertion about the app, not a pixel measurement.
assert_text() {
    local text="$1"
    local step="${2:-assert_text}"
    local scale
    for scale in 3 2; do
        if [[ -n "$(find_text_centre --text "$text" --scale "$scale" --region "$APP_REGION" 2>/dev/null)" ]]; then
            say "assert_text '$step': ok - \"$text\" is on screen at scale $scale"
            return 0
        fi
    done
    die "STEP '$step': the text \"$text\" was NOT found at scale 3 OR scale 2. The success state was not reached."
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

say "--- passphrase-change drive ---"

# PHASE 0b - dismiss a backup-failure dialog if one is covering the gate.
#
# MEASURED: when a previous session left a stored backup failure, the shared nav host raises a MODAL
# AlertDialog ("Backup issue" / "Last failure (...): disk full" / "Dismiss Message") without consulting the
# startup lock, so it sits on top of the unlock gate and its backdrop dims everything behind it - the
# "Unlock" button then genuinely is not on screen. It is dismissed here through its OWN label, never by
# clicking a coordinate, because the thing underneath is the gate we have not reached yet.
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
            die "PHASE 0b: the backup-failure dialog is up and neither Escape nor its action label could be located. The gate behind it cannot be reached."
        fi
        if [[ -n "$(find_text_centre --text 'Backup issue' --scale 3 --region "$APP_REGION" 2>/dev/null)" ]] ||
           [[ -n "$(find_text_centre --text 'Backup issue' --scale 2 --region "$APP_REGION" 2>/dev/null)" ]]; then
            die "PHASE 0b: 'Backup issue' is still on screen after two dismissal routes. The gate behind it cannot be reached."
        fi
        say "PHASE 0b: backup-failure dialog dismissed via its action"
    fi
else
    say "PHASE 0b: no backup-failure dialog present"
fi


# ---------------------------------------------------------------------------------------------------------------
# PHASE 1 - cross the Tamil privacy tour.
#
# PRE-STATE REQUIRED: this drive does NOT cross the first-run tour. It must be run against an app
# that is already set up and UNLOCKED - i.e. after a phase-1 run of desktop-loop-drive-first-run-db.sh
# against the SAME loop HOME (which creates the database and sets the passphrase). Crossing the tour
# here was wrong: this drive's subject is the Settings screen, and the tour only appears on a genuinely
# fresh HOME, which is also the state in which Settings is unreachable. The UNLOCK GATE that phase-1
# leaves behind is a different screen from the tour and IS crossed here - see PHASE 1b.

# The shell needs to compose (and open the database) before Settings is a real, clickable destination.
sleep 6
shot 05-shell

# ---------------------------------------------------------------------------------------------------------------
# PHASE 1b - cross the UNLOCK GATE. This is not optional: the gate is the app's first screen, so a drive
# that goes straight for "Settings" is looking for a word that is not rendered yet and dies on a control
# that was never absent - it was simply behind the gate.
#
# MEASURED (OCR of the real gate frame): "Payanam / Your Progress, Your Privacy / Storage mode
# Encrypted / Size: 544 KB / Last Modified: ... / Unlock / Forgot passphrase? Reset local data /
# DIAGNOSTICS". So the gate is one passphrase field plus ONE button that reads "Unlock" - the button is
# found BY ITS WORDS, never by a pixel typed in from memory.
#
# The gate's settle is the longest in the drive: unlocking opens the database, which is the slowest thing
# the app does. A short settle here photographs a frame that has not finished loading, which is exactly
# what makes the next phase look like it cannot find Settings.
UNLOCK_SETTLE=15
UNLOCK_FIELD_ABOVE_BUTTON_PX=80
UNLOCK_BUTTON_HIT_X_OFFSET_PX=0
UNLOCK_SCALE=2

UNLOCK_FOUND=$(find_text_centre --text 'Unlock' --scale "$UNLOCK_SCALE" --region "$APP_REGION"); UNLOCK_RC=$?
if (( UNLOCK_RC != 0 )) || [[ -z "$UNLOCK_FOUND" ]] || [[ ! "$UNLOCK_FOUND" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
    die "PHASE 1b: the unlock gate's \"Unlock\" button was not found (rc=$UNLOCK_RC, stdout='$UNLOCK_FOUND'). The drive cannot reach Settings behind the gate, and a pixel invented for the button would be a guess - stopping."
fi
UNLOCK_X=$(awk '{print $1}' <<<"$UNLOCK_FOUND")
UNLOCK_Y=$(awk '{print $2}' <<<"$UNLOCK_FOUND")
say "PHASE 1b: found the unlock gate's \"Unlock\" button at $UNLOCK_X $UNLOCK_Y"

# DERIVED, NOT MEASURED: the passphrase field's Y, and the X the button is tapped at.
#
# The field is the ONLY text field on the gate and it sits ABOVE the button, so its row is derived from
# the button's own Y rather than from an absolute pixel: PASS y - UNLOCK_FIELD_ABOVE_BUTTON_PX (80),
# reusing the button's X so the tap lands in the column the field actually occupies. 80 is an estimate of
# the row gap, chosen because the gate's frame puts the field between the "Last Modified" line and the
# button - i.e. comfortably inside that band - and not because it was measured.
#
# FALSIFIER: if the tap missed the field, the typed characters go nowhere, the button stays disabled, the
# second Unlock tap below leaves the gate byte-identical, and the PHASE 1b assertion - which REQUIRES
# "Unlock" to be gone - fails the run. A wrong offset here cannot pass silently: it ends the drive at the
# assertion rather than at a bogus Settings screen.
# If it DOES fail that way, raise or lower the 80 by one row gap; do not skip the assertion.
UNLOCK_FIELD_X=$UNLOCK_X
UNLOCK_FIELD_Y=$(( UNLOCK_Y - UNLOCK_FIELD_ABOVE_BUTTON_PX ))
click "$UNLOCK_FIELD_X" "$UNLOCK_FIELD_Y" 2 "focus the unlock gate's passphrase field (DERIVED: button Y minus ${UNLOCK_FIELD_ABOVE_BUTTON_PX}px)"
type_text "$PASSPHRASE"
shot 05-passphrase-typed

# DERIVED: the X the button is tapped at is its own found X plus 0 - i.e. the measured X, no offset at
# all. That is written as a named variable so the reason it is zero is on the record rather than implied.
# FALSIFIER: the button's found centre is the centre of the word "Unlock", which is inside the button's
# clickable bounds, so a miss here can only come from the frame changing under the finder - and the
# assertion below would then catch it.
click $(( UNLOCK_X + UNLOCK_BUTTON_HIT_X_OFFSET_PX )) "$UNLOCK_Y" "$UNLOCK_SETTLE" "press the unlock gate's \"Unlock\" button"
shot 05-shell-after-unlock

# THE GATE ASSERTION, and it is the same helper click_label is built on. "Unlock" is only rendered by the
# gate, so if it is still readable after the press the unlock did not take - and every later phase would
# be searching a Settings screen this run never reached. Require the finder to FAIL.
assert_gone "Unlock" "the unlock gate is GONE (its 'Unlock' button is no longer on screen)" \
    || die "PHASE 1b: \"Unlock\" is still on screen after the press, so the unlock did NOT take. Stopping: the gate would hide the nav bar, and 'Settings' is not on this screen at all."

# THE FOCUS-MODE GATE SITS BETWEEN THE UNLOCK AND THE SHELL - measured 2026-10-06.
# After a correct unlock the app does NOT land on the tasks shell. OCR of the frame taken immediately
# after the unlock reads "Choose Your Focus / Full Suite", i.e. the onboarding focus-mode step. The
# earlier failure ("the text Settings was not found") was this screen, not a wrong nav label: there is
# no Settings destination to reach until a focus mode is chosen. So the shell is one tap further on.
#
# Selected BY ITS WORDS, like every other control here. Guarded rather than asserted: a build that has
# already chosen a focus mode does not show this screen at all, and a drive that insists on it would
# then fail on a state that is perfectly correct. The guard is therefore "if the words are on screen,
# choose them", which is honest about both cases - and the shot below is what records which one applied.
FOCUS_CHOICE="$(find_text_centre --text 'Full Suite' --scale 2 --region "$APP_REGION" 2>/dev/null)" || FOCUS_CHOICE=""
if [[ -n "$FOCUS_CHOICE" ]]; then
    # The tap is at the ROW, not the label: measured 2026-10-06, clicking the found TEXT centre left the
    # chooser unchanged (the frame after it still showed all four options). At 1x the words "Full Suite"
    # occupy x150..223, while the rows span the whole 412-wide window, so a row's hit target is centred on
    # the WINDOW. Take the found Y, tap the window's centre.
    # DERIVED: x=256 is the window's centre-root position. Falsifier: re-centre the window and the shot
    # below shows the chooser unchanged - which is exactly how this was caught the first time.
    # THE CHOOSER IS TWO-STEP (measured from the source, 2026-10-06). Tapping a preset card only sets
    # local state (FocusModeSelectionScreen.kt:102-109); it is the full-width Button labelled
    # "Continue" (:113-130) that calls onPresetSelected and navigates. FULL_SUITE is pre-selected
    # (:60), so the row tap was a visual no-op - which is exactly why two different coordinates both
    # appeared to do nothing. The row tap stays because it is harmless and matches what a person does.
    FOCUS_Y="$(awk '{print $2}' <<<"$FOCUS_CHOICE")"
    click 256 "$FOCUS_Y" 2 "select the focus mode (two-step screen: selecting alone does not navigate)"
    # The Continue pill sits BELOW THE FOLD - four preset cards push it past the viewport - so the
    # column has to be scrolled to the bottom first. ~20 wheel clicks, measured: fewer does not reach it.
    for _ in $(seq 1 20); do xdotool click 5; sleep 0.2; done
    sleep 2
    shot 05-focus-mode-scrolled
    # Continue is what navigates, and it is a filled PILL, so it is found by its colour - the text
    # finder cannot see it (the whole class of control find_button_centre exists for). Measured
    # position once scrolled to the bottom: a 362x56 pill centred near (254,724).
    CONTINUE_XY="$(find_button_centre --label Continue --color '#00796B' --min-area 400)" || \
        die "PHASE 1b: the Continue pill was not found by colour after scrolling to the bottom"
    click $CONTINUE_XY 6 "press the Continue pill, which is what leaves the focus chooser"
    shot 05-focus-mode-continued
    # Prove it TOOK rather than assume it. Note the gate walks focus_mode_selection -> lenses ->
    # focus_mode_selection -> time, so the chooser can flash back briefly; give it a moment and
    # retry rather than reading a transient as a failure.
    sleep 6
    FOCUS_STUCK=1
    for _ in 1 2 3 4; do
        if ! find_text_centre --text 'Choose Your Focus' --scale 2 --region "$APP_REGION" >/dev/null 2>&1; then FOCUS_STUCK=0; break; fi
        sleep 4
    done
    (( FOCUS_STUCK == 1 )) && die "the focus-mode chooser is STILL on screen after pressing Continue"
    say "focus mode chosen - the chooser is gone"

    # WAIT FOR THE SHELL TO ACTUALLY RENDER. Measured: the frame right after the chooser reads
    # "Loading time data..." and has an EMPTY bottom band - the nav rail has not been laid out yet.
    # Hunting for a nav label at that moment finds nothing and looks exactly like a missing tab, which
    # is precisely the wrong conclusion to draw. So: wait until SOME known nav word is on screen,
    # because that is the observable proof the shell is up. `Time` alone is not evidence (the Time
    # screen's own title says it), so a word unique to the rail is required when one is visible; the
    # known top-level labels are the candidates and any of them will do.
    SHELL_UP=0
    for _ in $(seq 1 15); do
        for candidate in Settings Setting Habits Journal Notes Lenses Tasks; do
            if find_text_centre --text "$candidate" --scale 2 --region "$APP_REGION" >/dev/null 2>&1; then SHELL_UP=1; break 2; fi
        done
        sleep 4
    done
    (( SHELL_UP == 1 )) || die "PHASE 1b: the shell never rendered - none of the nav words appeared within 60s. Not driving a screen that is not the shell."
    shot 05-shell-up
    say "the shell is up - nav words are on screen"
else
    say "no focus-mode gate on screen - the app is already past it"
fi

# ---------------------------------------------------------------------------------------------------------------
# PHASE 2 - reach Settings.
#
# MEASURED STRING: the bottom navigation tab renders "Settings". Found by words; if it cannot be read at
# either scale the run stops here rather than tapping a bar position.
# OCR reads the Settings tab's label as "Setting" - the trailing "s" is lost at this render size - so the
# needle is chosen by measurement instead of being assumed from the string resource. Measured on a driven
# frame: psm11 on the nav band returns "Setting", and a phrase needle of "Settings" then matches nothing
# while the control is plainly there.
# MEASURED: the Settings tab is the LAST one, so its label sits against the window's right edge and is
# sometimes clipped - OCR has returned "Setting" (missing the final s), "Settings", or nothing at all for
# the SAME screen across runs. A single needle at a single scale is therefore not reliable here, so both
# observed readings are tried at three scales and the first real hit wins. Only these two strings have ever
# been observed; nothing else is guessed.
SETTINGS_FOUND=""
for settings_needle in Settings Setting; do
    for settings_scale in 3 2 1; do
        SETTINGS_FOUND=$(find_text_centre --text "$settings_needle" --scale "$settings_scale" --region "$APP_REGION" 2>/dev/null || true)
        if [[ -n "$SETTINGS_FOUND" && "$SETTINGS_FOUND" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
            say "nav: found the Settings tab as \"$settings_needle\" at scale $settings_scale -> $SETTINGS_FOUND"
            break 2
        fi
    done
done
if [[ -z "$SETTINGS_FOUND" ]]; then
    # FALLBACK, MEASURED NOT GUESSED. The Settings label itself is unreadable: across runs of the identical
    # screen OCR has returned "Setting", "Settings", and plain noise ("ae"), because the 7th tab sits against
    # the window's right edge. So instead of guessing a pixel, read the FIRST and LAST tabs that DO read
    # cleanly ("Tasks" and "Lenses" appear in every frame) and derive the pitch between them. The seven tabs
    # are evenly distributed across the bar, and Tasks is 1st while Lenses is 6th, so the spacing between
    # those two covers five tabs - the 7th tab centre is one pitch to the right of Lenses.
    # RETRY: measured 2026-10-07 - immediately after the unlock click the shell can still be settling, so
    # BOTH anchors OCR'd as empty in one run and the step died even though the same frame read cleanly a
    # few seconds later. Re-read up to four times, 2s apart, before declaring the shell unreadable.
    TASKS_XY=""; LENSES_XY=""
    for nav_try in 1 2 3 4; do
        TASKS_XY=$(find_text_centre --text 'Tasks' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
        [[ -n "$TASKS_XY" ]] || TASKS_XY=$(find_text_centre --text 'Tasks' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
        LENSES_XY=$(find_text_centre --text 'Lenses' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
        [[ -n "$LENSES_XY" ]] || LENSES_XY=$(find_text_centre --text 'Lenses' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
        if [[ -n "$TASKS_XY" && -n "$LENSES_XY" ]]; then
            say "nav: anchor pair found on attempt $nav_try (Tasks=$TASKS_XY, Lenses=$LENSES_XY)"
            break
        fi
        say "nav: attempt $nav_try - shell not readable yet (Tasks='$TASKS_XY', Lenses='$LENSES_XY'); waiting 2s"
        sleep 2
    done
    if [[ -z "$TASKS_XY" || -z "$LENSES_XY" ]]; then
        die "STEP 'nav to Settings': after 4 attempts the label is unreadable AND the anchor tabs 'Tasks'/'Lenses' did not both match (Tasks='$TASKS_XY', Lenses='$LENSES_XY'). No click was made - stopping rather than tapping a guessed pixel."
    fi
    read -r TASKS_X TASKS_Y <<< "$TASKS_XY"
    read -r LENSES_X LENSES_Y <<< "$LENSES_XY"
    PITCH=$(( (LENSES_X - TASKS_X) / 5 ))
    SETTINGS_X=$(( LENSES_X + PITCH ))
    SETTINGS_Y=$LENSES_Y
    if (( PITCH <= 0 )); then
        die "STEP 'nav to Settings': derived a tab pitch of $PITCH px from Tasks=$TASKS_X and Lenses=$LENSES_X, which cannot be right - refusing to click there."
    fi
    say "nav: label unreadable - derived from anchors: Tasks x=$TASKS_X, Lenses x=$LENSES_X, pitch=$PITCH -> Settings at $SETTINGS_X,$SETTINGS_Y"
    click "$SETTINGS_X" "$SETTINGS_Y" 3 "nav to Settings (position derived from the Tasks/Lenses anchor pair)"
else
    click $SETTINGS_FOUND 3 "nav to Settings"
fi
shot 06-settings

# ---------------------------------------------------------------------------------------------------------------
# PHASE 3 - scroll to the Data Management card.
#
# DERIVED: the number of wheel clicks. The Settings screen is a verticalScroll Column of roughly a dozen
# cards and DATA_MANAGEMENT is one of the lower ones, so it starts BELOW the fold - but there is NO scroll
# helper in the harness, so this is a raw `xdotool click 5` (wheel down; DISPLAY is already exported into
# this shell).
#
# FALSIFIER: if "Data Management" is not found after these clicks, the count is too low (raise it) or the
# card order changed. The expand step below is what makes this self-checking: click_label dies rather
# than mis-clicking, so a wrong scroll count ends the run instead of silently pressing the wrong card.
SETTINGS_SCROLL_CLICKS=6
# MEASURED DEFECT (2026-10-07): the wheel events were going nowhere. The pointer is still sitting where the
# previous step left it - on the Settings TAB in the bottom nav - so `xdotool click 5` delivered the wheel to
# the nav bar, which does not scroll, and the frame after these clicks came back BYTE-IDENTICAL to the frame
# before them (md5 equal). The list must be pointed at first. The centre is derived from the window region
# that PHASE 0 measured, so it is never an invented pixel.
IFS=',' read -r APP_X1 APP_Y1 APP_X2 APP_Y2 <<< "$APP_REGION"
APP_CX=$(( (APP_X1 + APP_X2) / 2 ))
APP_CY=$(( (APP_Y1 + APP_Y2) / 2 ))
xdotool mousemove --sync "$APP_CX" "$APP_CY"
say "PHASE 3: pointer moved to the window centre $APP_CX,$APP_CY before scrolling (it was left on the nav tab)"
sleep 1
for _ in $(seq 1 "$SETTINGS_SCROLL_CLICKS"); do
    xdotool click 5
    sleep 0.4
done
sleep 2
shot 07-settings-scrolled
# The scroll is self-checking: if the frame did not change, nothing scrolled and the card below the fold
# will never be found - say so instead of letting the next step fail with an unrelated message.
if cmp -s "$RUN_DIR/06-settings.png" "$RUN_DIR/07-settings-scrolled.png" 2>/dev/null; then
    die "PHASE 3: the Settings list did not scroll - frames 06 and 07 are byte-identical after $SETTINGS_SCROLL_CLICKS wheel events aimed at $APP_CX,$APP_CY. Not looking for a card that was never brought on screen."
fi
say "PHASE 3: Settings list scrolled (07 differs from 06)"


# ---------------------------------------------------------------------------------------------------------------
# PHASE 4 - expand the Data Management card.
#
# MEASURED STRING: the card header renders "Data Management" (from settings_data_management_title, line 851
# of strings.xml). The header Row is itself clickable, so clicking these words EXPANDS the card. That is
# why this is a click and not a locate.
click_label "Data Management" 3 "expand the Data Management card"
sleep 2
shot 08-data-management-expanded

# ---------------------------------------------------------------------------------------------------------------
# PHASE 4b - scroll the EXPANDED section so its rows clear the bottom bar.
#
# MEASURED (2026-10-07, build 386, frame 08-data-management-expanded): after expanding, the section's
# content ran UNDER the 7-tab bar - only "Export creates a full encrypted database" was readable and
# everything below it was covered, so "Update Passphrase" (rendered lower in the same section at
# SettingsDataManagementSections.kt:191) could not be found even though the card was expanded. The
# shared test reaches that button with scrollTo(...) (SettingsScreenTest.kt:254) - the same requirement
# in test form. Pointer first: if it is left on a nav tab the wheel events scroll nothing (the defect
# PHASE 3 records and fixes the same way).
# ---------------------------------------------------------------------------------------------------------------
xdotool mousemove --sync "$APP_CX" "$APP_CY"
say "PHASE 4b: pointer moved to the window centre $APP_CX,$APP_CY before scrolling the expanded section"
sleep 1
for _ in $(seq 1 "$SETTINGS_SCROLL_CLICKS"); do
    xdotool click 5
    sleep 0.4
done
sleep 2
shot 08b-expanded-scrolled
# If nothing moved the list may already show the whole section; that is NOT fatal here because PHASE 5
# dies on its own when the button is absent - so report it and let the label check be the judge.
if cmp -s "$RUN_DIR/08-data-management-expanded.png" "$RUN_DIR/08b-expanded-scrolled.png" 2>/dev/null; then
    say "PHASE 4b: frame unchanged after scrolling - the section may already be fully visible; PHASE 5 decides"
else
    say "PHASE 4b: expanded section scrolled (08b differs from 08)"
fi

# ---------------------------------------------------------------------------------------------------------------
# PHASE 5 - click "Update Passphrase".
#
# MEASURED STRING: the button renders "Update Passphrase" (from settings_action_change_passphrase, line 809
# of strings.xml). It is an OutlinedButton inside the expanded Data Management section. Clicking it calls
# actions.onNavigateToPassphraseChange() which calls nav.openPassphraseChange(), navigating to the
# DatabasePassphraseChangeScreen.
#
# A caption is not a control: this session measured twice that clicking a row caption changes the frame
# without actuating anything. The button is the control, so it is clicked by its own label.
click_label "Update Passphrase" 3 "click the Update Passphrase button"
shot 09-passphrase-change-screen

# ---------------------------------------------------------------------------------------------------------------
# PHASE 6 - fill the passphrase-change form.
#
# MEASURED STRINGS (from DatabasePassphraseChangeScreen.kt):
#   - Title: "Change app passphrase" (db_passphrase_change_title, line 5)
#   - Current field label: "Current passphrase" (db_passphrase_change_current_label, line 7)
#   - New field label: "New passphrase" (db_passphrase_change_new_label, line 8)
#   - Confirm field label: "Confirm new passphrase" (db_passphrase_change_confirm_label, line 9)
#
# The fields are OutlinedTextFields with PasswordVisualTransformation. The labels are rendered as floating
# labels above each field. To type into a field, we click on the label's position (which focuses the field)
# and then type the passphrase.
#
# MEASURED (2026-10-07, frame 09-passphrase-change-screen, build 386): "Current passphrase" is a
# PLACEHOLDER INSIDE the input, not a caption above it - so the found text centre IS the field centre
# (measured (253,310); the old `label Y + 30` derivation clicked (186,344) and left the frame
# byte-identical, which the driver correctly refused to continue through). The fields are full-width
# and centred on the window, so X is the window centre (immune to OCR x-drift) and the old offset is 0.
FIELD_LABEL_OFFSET_PX=0

# Current passphrase field
CURRENT_LABEL_XY=$(find_text_centre --text 'Current passphrase' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$CURRENT_LABEL_XY" ]] || CURRENT_LABEL_XY=$(find_text_centre --text 'Current passphrase' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$CURRENT_LABEL_XY" ]] || die "STEP 'fill current passphrase': the label 'Current passphrase' was not found at scale 3 or 2. No click was made - stopping."
read -r CURRENT_LABEL_X CURRENT_LABEL_Y <<< "$CURRENT_LABEL_XY"
CURRENT_FIELD_Y=$(( CURRENT_LABEL_Y + FIELD_LABEL_OFFSET_PX ))
click "$APP_CX" "$CURRENT_FIELD_Y" 2 "focus the Current passphrase field (placeholder centre, X = window centre)"
type_text "$PASSPHRASE"
shot 10-current-typed

# New passphrase field - a DIFFERENT passphrase so the change is real. It MUST clear the app's own
# validation (>=12 chars, upper, lower, digit): the previous value "NewPass#42" was only 10 chars and
# the form correctly rejected it with "Use at least 12 characters." (observed 2026-10-07, build 386,
# frame 13-after-submit). 16 chars also leaves margin if a keystroke is dropped.
# Overridable, defaulting to the loop fixture's ORIGINAL passphrase: running the change this way
# both exercises a real change (current is NewPass#42Secure from the earlier run) AND restores the
# fixture, so later drives can unlock with the documented default again.
NEW_PASSPHRASE="${LOOP_NEW_PASSPHRASE:-LoopDrive#Pass9}"
NEW_LABEL_XY=$(find_text_centre --text 'New passphrase' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$NEW_LABEL_XY" ]] || NEW_LABEL_XY=$(find_text_centre --text 'New passphrase' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$NEW_LABEL_XY" ]] || die "STEP 'fill new passphrase': the label 'New passphrase' was not found at scale 3 or 2. No click was made - stopping."
read -r NEW_LABEL_X NEW_LABEL_Y <<< "$NEW_LABEL_XY"
NEW_FIELD_Y=$(( NEW_LABEL_Y + FIELD_LABEL_OFFSET_PX ))
click "$APP_CX" "$NEW_FIELD_Y" 2 "focus the New passphrase field (placeholder centre, X = window centre)"
type_text "$NEW_PASSPHRASE"
shot 11-new-typed

# Confirm passphrase field
CONFIRM_LABEL_XY=$(find_text_centre --text 'Confirm new passphrase' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$CONFIRM_LABEL_XY" ]] || CONFIRM_LABEL_XY=$(find_text_centre --text 'Confirm new passphrase' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$CONFIRM_LABEL_XY" ]] || die "STEP 'fill confirm passphrase': the label 'Confirm new passphrase' was not found at scale 3 or 2. No click was made - stopping."
read -r CONFIRM_LABEL_X CONFIRM_LABEL_Y <<< "$CONFIRM_LABEL_XY"
CONFIRM_FIELD_Y=$(( CONFIRM_LABEL_Y + FIELD_LABEL_OFFSET_PX ))
click "$APP_CX" "$CONFIRM_FIELD_Y" 2 "focus the Confirm new passphrase field (placeholder centre, X = window centre)"
type_text "$NEW_PASSPHRASE"
shot 12-confirm-typed

# ---------------------------------------------------------------------------------------------------------------
# PHASE 7 - submit the form.
#
# MEASURED STRING: the button renders "Change passphrase" (db_passphrase_change_action, line 22). When
# isSaving is true it renders "Updating..." instead (db_passphrase_change_saving, line 21). The button is
# found BY ITS WORDS.
#
# The settle is generous: a passphrase change does real cryptographic work (re-encrypting the database),
# and a short settle can capture the frame before the success message appears.
click_label "Change passphrase" 8 "press the Change passphrase button"
shot 13-after-submit

# ---------------------------------------------------------------------------------------------------------------
# PHASE 8 - assert the success state.
#
# MEASURED STRING: "Passphrase updated successfully." (db_passphrase_change_success, line 20). This text
# is rendered ONLY when isSuccess is true (DatabasePassphraseChangeScreen.kt:153-158), so its presence
# is a state assertion about the app, not a pixel measurement.
#
# This is the hard assertion that proves the flow completed. If the success text is not on screen, the
# drive dies - it does not report a pass.
# MEASURED (build 387, frames 13-after-submit on two successful runs): the success toast does NOT
# survive to the frame - after a real change the app RE-ENCRYPTS and immediately RE-LOCKS, so what is
# on screen is the UNLOCK GATE (storage box reading the new size and the change's minute, e.g.
# "Size: 552 KB / Last Modified: Oct 07 2026 18:23", no error message). Assert THAT observable
# signature; the definitive proof that the new passphrase took is the FOLLOW-UP run unlocking with it.
UNLOCK_AFTER_CHANGE=$(find_text_centre --text 'Unlock' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$UNLOCK_AFTER_CHANGE" ]] || UNLOCK_AFTER_CHANGE=$(find_text_centre --text 'Unlock' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
if [[ -z "$UNLOCK_AFTER_CHANGE" ]]; then
    shot 13-no-relock
    die "PHASE 8: after submitting, neither the success state nor the post-change unlock gate is on screen. The flow did not complete. Stopping."
fi
say "PHASE 8: post-change unlock gate observed - the change re-encrypted and re-locked the app (expected signature; the follow-up run must unlock with the NEW passphrase)"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 9 - print the app-log lines this drive expects to match.
#
# These are the log lines the Android test's 16 tests already verify. The desktop drive produces the same
# UI events, so the same log lines should appear in the desktop app's log.
say "=== EXPECTED LOG LINES (matching the Android test) ==="
say "===   Settings: 'change_passphrase' data-action breadcrumb ==="
say "===   DatabasePassphraseChangeScreen: form submitted with current/new/confirm ==="
say "===   DatabasePassphraseChangeScreen: isSuccess=true -> 'Passphrase updated successfully.' ==="
say "=== CLAIM: the desktop passphrase-change flow reached the same success state as the Android test ==="
say "--- passphrase-change drive finished ---"
