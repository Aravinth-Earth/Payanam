# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# Prove the SCHEDULED auto-backup path actually produces a file. Manual backup and scheduled
# backup go through the same runBackup() code, but "same code" is an argument, not evidence. This drive
# turns auto-backup on, shortens the interval to the shortest the UI offers, then DOES NOTHING AT ALL for
# long enough for the scheduler to fire on its own. If a file appears in the app's data-root db/
# directory with a timestamp later than the moment the interval was selected, then the scheduler fired
# without any human tapping anything - which is the only claim this drive can honestly make from a UI run.
#
# MEASURED PIXELS (1280x900 root). Taken from the frames of a real run, not read off the code.
#
#   privacy tour "Next"    -> (254, 739)   three taps, three pages
#
# That one is the only raw pixel here. The tour is rendered in Tamil and only eng+osd tessdata is
# installed, so the tour is NOT OCR-readable - find_text_centre cannot see those words at all. The tour is
# therefore crossed by raw pixel exactly as desktop-loop-drive-first-run-db.sh crosses it, and every other
# control on every screen after it is found BY ITS WORDS. One raw pixel, documented; nothing else guessed.
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
# The wait. Named because it is the single most important number in the file and it is load-bearing.
#
# MEASURED FROM THE SOURCE, NOT GUESSED: changing the interval RE-CALLS the scheduler, which CANCELS the
# running timer and LAUNCHES a fresh one. So the 15-minute clock starts when the interval is SELECTED, not
# when the toggle is flipped, and the sleep below is deliberately placed AFTER the interval click. Getting
# this ordering wrong makes every run hang an extra interval period and looks like the scheduler is dead.
#
# 15 min is the SHORTEST interval the UI offers ("Backup every 15 min"). The default is 60 min, so a
# toggle-only variant of this drive would have to wait an hour; this one buys the 15 by choosing it.
#
# The extra 120s on top of the 900s: the timer starts at selection, and the first firing could be delayed by
# however long a backup itself takes. 120s of slack means "the file was not there" is a real absence rather
# than a race with a slow first run. If the artifact is missing, raise SCHEDULED_WAIT_SECONDS - do NOT
# conclude the scheduler is broken off one run at exactly 900s.
SCHEDULED_WAIT_SECONDS=1020

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
# Only used for the state assertion after the toggle is flipped. The row hint reads "Disabled" while
# auto-backup is off and "Backups saved to Documents/Payanam" once it is on, so the disappearance of
# "Disabled" is a state assertion about the app, not a pixel measurement. Uses the same scale chain for
# the same unmeasured reason click_label does.
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
#
# Used only for the post-toggle confirmation hint. That is deliberately a soft check: the hard assertion
# is the disappearance of "Disabled" (above), and this is corroboration whose value would turn on OCR
# reading a line containing a slash - so a miss here is reported, not fatal.
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

say "--- scheduled-backup: scheduled auto-backup drive ---"

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
    # Dismiss through the dialog's OWN dismiss request first: in the source the dialog is created with
    # `onDismissRequest = onDismissBackupFailure`, so Escape is its documented dismiss path and reaches it
    # wherever focus sits. The text button cannot be the primary route because tesseract renders its label
    # "Dismiss Message" as "Disn Message", so a phrase match against the real string never succeeds.
    press Escape
    sleep 2
    if [[ -z "$(find_text_centre --text 'Backup issue' --scale 3 --region "$APP_REGION" 2>/dev/null)" ]] &&
       [[ -z "$(find_text_centre --text 'Backup issue' --scale 2 --region "$APP_REGION" 2>/dev/null)" ]]; then
        say "PHASE 0b: backup-failure dialog dismissed via its dismiss request"
    else
        # Fallback: click the button by the ONE word OCR does read back intact ("Message"), located by
        # measurement rather than a fixed coordinate.
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
# Raw pixels, because the tour is Tamil and only eng+osd tessdata is installed: OCR cannot read these
# words at any scale, so there is no alternative to the measured coordinate. The last tap settles longest
# because the last page hands over to the shell slowest, and a tap on a screen still animating is the
# miss that looks like a miss.
shot 01-tour-page-1
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
# PHASE 3 - scroll to the Auto-Backup card.
#
# DERIVED: the number of wheel clicks. The Settings screen is a verticalScroll Column of roughly a dozen
# cards and AUTO_BACKUP is the 8th, so it starts BELOW the fold - but there is NO scroll helper in the
# harness, so this is a raw `xdotool click 5` (wheel down; DISPLAY is already exported into this shell).
#
# FALSIFIER: if "Auto-Backup" is not found after these clicks, the count is too low (raise it) or the card
# order changed (AUTO_BACKUP is no longer 8th - then the count means nothing and must be re-derived).
# The expand step below is what makes this self-checking: click_label dies rather than mis-clicking, so a
# wrong scroll count ends the run instead of silently pressing the wrong card.
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
# PHASE 4 - expand the Auto-Backup card.
#
# MEASURED STRING: the card header renders "Auto-Backup" (note the hyphen - it is NOT "Auto Backup") and
# the header Row is itself clickable, so clicking these words EXPANDS the card. That is why this is a
# click and not a locate.
click_label "Auto-Backup" 3 "expand the Auto-Backup card"
sleep 2
shot 08-autobackup-expanded

# ---------------------------------------------------------------------------------------------------------------
# PHASE 5 - turn auto-backup ON, and ASSERT it flipped.
#
# MEASURED BUG (2026-10-08): the old ENABLE step searched for the switch by its off-state track colour
# (#E6E0E9) inside a band derived from the label's Y. When the band search failed, find_button_centre fell
# back to a WHOLE-FRAME scan, found a blob at (373,627) OUTSIDE the band, and clicked it - flipping the
# WRONG switch. The app logged "Backup rotation toggled [enabled=true]" but onSettingChanged never fired,
# so the reconcile line "Auto-backup schedule applied from shared rows" never appeared. The next runs then
# read a hint saying "already ON" and SKIPPED the toggle entirely.
#
# THE FIX: locate the switch by its ROW LABEL, not by colour. The label "Enable Auto-Backup" is OCR-readable
# at scale 3 or 2. The switch sits at the RIGHT EDGE of that row, same Y as the label, x near the window's
# right edge. The click point is derived from APP_REGION corners: (window right edge - 35px, label Y).
# No colour search, no whole-frame scan, no band.
#
# IDEMPOTENT: the loop's data root PERSISTS between runs. If auto-backup is already ON, skip the click
# but STILL proceed to the reconcile assert - do NOT skip the whole step.

# --- (1) Row read BEFORE via sqlcipher ---
# The canonical path is the one the RUNNING app has open. The fixture passphrase is LoopDrive#Pass9.
# Tolerate a locked-file failure with a loud say, not a die - the UI is the source of truth for the toggle.
ENABLE_BEFORE=""
ENABLE_BEFORE_RC=0
ENABLE_BEFORE=$(sqlcipher "file:/home/cawax/Documents/Payanam-debug/db/payanam.db?mode=ro" \
    "PRAGMA key='${PASSPHRASE}'; SELECT value FROM app_settings WHERE key='auto_backup_enabled';" 2>&1 | grep -v -x 'ok') || ENABLE_BEFORE_RC=$?
if (( ENABLE_BEFORE_RC != 0 )); then
    say "ENABLE: WARNING - sqlcipher read BEFORE failed (rc=$ENABLE_BEFORE_RC): $ENABLE_BEFORE"
    say "ENABLE: continuing - the UI is the source of truth for the toggle state"
else
    say "ENABLE: sqlcipher read BEFORE -> auto_backup_enabled='${ENABLE_BEFORE}'"
fi

# --- (2) Locate the switch by its ROW LABEL ---
# Find "Enable Auto-Backup" at scale 3 then 2, region-scoped. If unreadable, expand the Auto-Backup
# section first (reuse the existing expand step), then try again. If STILL unreadable, die naming the STEP.
ENABLE_LABEL=""
for enable_scale in 3 2; do
    ENABLE_LABEL=$(find_text_centre --text 'Enable Auto-Backup' --scale "$enable_scale" --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$ENABLE_LABEL" ]] && break
done
if [[ -z "$ENABLE_LABEL" ]]; then
    say "ENABLE: 'Enable Auto-Backup' not found at scale 3 or 2 - expanding the Auto-Backup card first"
    click_label "Auto-Backup" 3 "expand the Auto-Backup card (retry after label miss)"
    sleep 2
    for enable_scale in 3 2; do
        ENABLE_LABEL=$(find_text_centre --text 'Enable Auto-Backup' --scale "$enable_scale" --region "$APP_REGION" 2>/dev/null || true)
        [[ -n "$ENABLE_LABEL" ]] && break
    done
fi
[[ -n "$ENABLE_LABEL" ]] || die "STEP 'enable auto-backup': the row label 'Enable Auto-Backup' was not found at scale 3 or 2 even after expanding the Auto-Backup card. No click was made - stopping."
read -r ENABLE_LABEL_X ENABLE_LABEL_Y <<< "$ENABLE_LABEL"
say "ENABLE: row label 'Enable Auto-Backup' found at $ENABLE_LABEL_X,$ENABLE_LABEL_Y"

# Derive the switch click point: (window right edge - 35px, label Y).
# The switch sits at the RIGHT EDGE of the row, same Y as the label.
IFS=',' read -r _ENABLE_APP_X1 _ENABLE_APP_Y1 _ENABLE_APP_X2 _ENABLE_APP_Y2 <<< "$APP_REGION"
ENABLE_SWITCH_X=$(( _ENABLE_APP_X2 - 35 ))
ENABLE_SWITCH_Y=$ENABLE_LABEL_Y
say "ENABLE: switch click point derived as ($ENABLE_SWITCH_X, $ENABLE_SWITCH_Y) = (window right edge $_ENABLE_APP_X2 - 35px, label Y $ENABLE_LABEL_Y)"

# --- (3) Verify the label's current state text first ---
# If the hint already reads enabled ('Backups saved'), SKIP the click but STILL proceed to the reconcile assert.
ENABLE_ALREADY_ON=0
if [[ -z "$(find_text_centre --text 'Disabled' --scale 3 --region "$APP_REGION" 2>/dev/null)" ]] &&
   [[ -n "$(find_text_centre --text 'Backups saved' --scale 3 --region "$APP_REGION" 2>/dev/null || find_text_centre --text 'Backups saved' --scale 2 --region "$APP_REGION" 2>/dev/null)" ]]; then
    ENABLE_ALREADY_ON=1
    say "ENABLE: auto-backup is ALREADY ON (hint reads 'Backups saved...') - skipping the click but STILL proceeding to the reconcile assert"
fi

# --- (4) Click once with the frame-hash changed check ---
if (( ENABLE_ALREADY_ON == 0 )); then
    say "ENABLE: clicking the switch at ($ENABLE_SWITCH_X, $ENABLE_SWITCH_Y)"
    ENABLE_CLICK_BEFORE=$(frame_hash)
    click "$ENABLE_SWITCH_X" "$ENABLE_SWITCH_Y" 3 "click the Enable Auto-Backup switch at ($ENABLE_SWITCH_X, $ENABLE_SWITCH_Y)"
    ENABLE_CLICK_AFTER=$(frame_hash)
    if [[ -n "$ENABLE_CLICK_BEFORE" && "$ENABLE_CLICK_BEFORE" == "$ENABLE_CLICK_AFTER" ]]; then
        say "ENABLE: first click left the frame byte-identical - retrying once"
        sleep 2
        ENABLE_CLICK_BEFORE=$(frame_hash)
        click "$ENABLE_SWITCH_X" "$ENABLE_SWITCH_Y" 3 "retry click the Enable Auto-Backup switch at ($ENABLE_SWITCH_X, $ENABLE_SWITCH_Y)"
        ENABLE_CLICK_AFTER=$(frame_hash)
        if [[ -n "$ENABLE_CLICK_BEFORE" && "$ENABLE_CLICK_BEFORE" == "$ENABLE_CLICK_AFTER" ]]; then
            die "STEP 'enable auto-backup': the switch click at ($ENABLE_SWITCH_X, $ENABLE_SWITCH_Y) left the frame byte-identical twice - the input did not land. Stopping."
        fi
    fi
    say "ENABLE: switch click verified (frame changed)"
fi
sleep 2
shot 09-after-toggle

# --- (5) Row read AFTER via sqlcipher ---
# Assert the value is now 'true'. If it was already true before, say so and continue.
ENABLE_AFTER=""
ENABLE_AFTER_RC=0
ENABLE_AFTER=$(sqlcipher "file:/home/cawax/Documents/Payanam-debug/db/payanam.db?mode=ro" \
    "PRAGMA key='${PASSPHRASE}'; SELECT value FROM app_settings WHERE key='auto_backup_enabled';" 2>&1 | grep -v -x 'ok') || ENABLE_AFTER_RC=$?
if (( ENABLE_AFTER_RC != 0 )); then
    say "ENABLE: WARNING - sqlcipher read AFTER failed (rc=$ENABLE_AFTER_RC): $ENABLE_AFTER"
    say "ENABLE: cannot assert the DB value - but the UI state and reconcile assert below are the real proof"
else
    say "ENABLE: sqlcipher read AFTER -> auto_backup_enabled='${ENABLE_AFTER}'"
    if [[ "$ENABLE_AFTER" != "true" ]]; then
        die "STEP 'enable auto-backup': sqlcipher read AFTER returned '${ENABLE_AFTER}', expected 'true'. The toggle did NOT flip in the database. Stopping."
    fi
    if [[ "$ENABLE_BEFORE" == "true" ]]; then
        say "ENABLE: auto_backup_enabled was already 'true' before the click - the toggle was already ON"
    else
        say "ENABLE: auto_backup_enabled changed from '${ENABLE_BEFORE}' to 'true' - the toggle flipped"
    fi
fi

# --- (6) Reconcile assert ---
# Grep the newest payanam_desktop_*.log for 'Auto-backup schedule applied from shared rows' with a bounded retry (~15s).
# This is the REAL proof that onSettingChanged fired and the reconcile path ran.
RECONCILE_LOG=""
for _reconcile_dir in "$HOME/Documents/Payanam-debug/logs" "$HOME/Documents/Payanam/logs" \
                      "$HOME/.local/share/Payanam-debug/logs" "$HOME/.local/share/Payanam/logs"; do
    _reconcile_candidate=$(ls -t "$_reconcile_dir"/payanam_desktop_*.log 2>/dev/null | head -1)
    [[ -n "$_reconcile_candidate" ]] || continue
    if [[ -z "$RECONCILE_LOG" || "$_reconcile_candidate" -nt "$RECONCILE_LOG" ]]; then
        RECONCILE_LOG="$_reconcile_candidate"
    fi
done
[[ -n "$RECONCILE_LOG" ]] || die "STEP 'enable auto-backup': could not locate any payanam_desktop_*.log in the standard log directories. Cannot assert the reconcile line."
say "ENABLE: using app log $RECONCILE_LOG for reconcile assert"

RECONCILE_FOUND=0
for _reconcile_try in $(seq 1 15); do
    if grep -q 'Auto-backup schedule applied from shared rows' "$RECONCILE_LOG" 2>/dev/null; then
        RECONCILE_FOUND=1
        break
    fi
    sleep 1
done
if (( RECONCILE_FOUND == 1 )); then
    say "ENABLE: reconcile assert PASSED - found 'Auto-backup schedule applied from shared rows' in $RECONCILE_LOG"
else
    die "STEP 'enable auto-backup': the app log $RECONCILE_LOG does NOT contain 'Auto-backup schedule applied from shared rows' after 15s of retry. onSettingChanged did not fire or the reconcile path did not run. Stopping."
fi

# Corroboration only - soft, because it depends on OCR reading a string that contains a slash.
have_label "Backups saved to Documents/Payanam"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 6 - choose the shortest interval.
#
# MEASURED STRING: the label is "Backup Interval". Opening it is what reveals the menu, so this is a
# click. MEASURED VALUE: the menu entry is "Backup every 15 min".
#
# ORDERING MATTERS AND IS NOT ARBITRARY: this is placed AFTER the toggle because changing the interval
# re-calls the scheduler, which cancels and relaunches its timer. The wait below must be measured from
# the value selection below, not from the toggle. See SCHEDULED_WAIT_SECONDS at the top.
#
# SCHEDULED_AT is the boundary PHASE 8 asserts against, so it is stamped on the VALUE selection below - the
# click that relaunches the scheduler's timer - and not after the wait. A stamp taken after the wait could
# not tell a scheduled run's artifact from one an earlier session left behind. Opening the menu is
# deliberately NOT the stamp point: that click only reveals the list, and the timer starts at selection.
# MEASURED 2026-10-07: clicking the "Backup Interval" CAPTION opens NOTHING. The frame after that click
# still showed only the row value "Backup every 1 hour" with no option list anywhere in the window, so the
# search for "Backup every 15 min" had nothing to find. This control is an ExposedDropdownMenuBox: the
# clickable part is the read-only VALUE field, not its caption - the same lesson as the switch row above,
# where the caption was not the control either. The needle is "Backup every" so it matches whatever the
# current value reads (the default is 60 minutes, rendering "Backup every 1 hour").
INTERVAL_VALUE=$(find_text_centre --text 'Backup every' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
if [[ -z "$INTERVAL_VALUE" ]]; then
    INTERVAL_VALUE=$(find_text_centre --text 'Backup every' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
fi
if [[ -n "$INTERVAL_VALUE" && "$INTERVAL_VALUE" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
    say "INTERVAL: value field at $INTERVAL_VALUE - clicking the FIELD (the caption does not open the menu)"
    click $INTERVAL_VALUE 3 "open the backup interval dropdown via its value field"
else
    die "INTERVAL: neither the value field ('Backup every') nor a readable caption could be located in $APP_REGION. No click was made - stopping rather than tapping a guessed pixel."
fi
sleep 2
shot 10-interval-menu

SCHEDULED_AT="$(date +%s)"
click_label "Backup every 15 min" 3 "select the 15-minute interval"
sleep 3
shot 11-interval-selected

# ---------------------------------------------------------------------------------------------------------------
# PHASE 7 - do nothing, on purpose, and wait.
#
# This is the phase the whole drive exists for. No clicks in this block, deliberately: any interaction
# here would make the artifact at the end unattributable to the scheduler. Frames are taken during the
# wait only to show the screen is still sitting there un-interacted.
say "--- waiting ${SCHEDULED_WAIT_SECONDS}s for the scheduler to fire on its own (no input in this window) ---"
sleep $(( SCHEDULED_WAIT_SECONDS / 2 ))
shot 12-mid-wait
sleep $(( SCHEDULED_WAIT_SECONDS / 2 ))
shot 13-after-wait

# ---------------------------------------------------------------------------------------------------------------
# PHASE 8 - ASSERT the artifact.
#
# Automatic backups land in the app's data-root db/ subdirectory, named auto_bk_<yyyyMMdd-HHmmss>.db.
# That is read from the code that writes them, and it is authoritative. A KDoc elsewhere in the tree
# claims an "exports" directory - that comment is STALE and is contradicted by the code. Do not "fix" a
# missing artifact by looking in exports/.
#
# WHY THE ASSERTION BELONGS AFTER PHASE 7, NOT BEFORE IT: the 15-minute clock starts when the interval is
# SELECTED (see SCHEDULED_WAIT_SECONDS), so before that wait there is provably nothing to find and the
# check would read as a false failure. A run that waits the full window and still finds nothing is the
# only honest "the scheduler did not fire" - which is exactly what this drive exists to catch. Checked
# before the wait, the same absence would be indistinguishable from a scheduler that has not come due yet.
#
# WHY THE DIRECTORY IS DERIVED, NOT TYPED: <root> is not a path this file may invent. On Linux
# DesktopAppPaths resolves it as <parent>/Documents/<rootName>, where <parent> is XDG_DATA_HOME when set and
# the launcher's home otherwise, and <rootName> is Payanam or Payanam-debug on a debug build - so the
# candidate list below is that expression evaluated in this shell. BOTH root names are searched because the
# debug flag is frozen in at build time, so a binary left in the tree from an earlier build answers for the
# wrong root; a stale flag must cost a redundant find, never a false "no artifact". That is the same rule
# desktop-loop.sh applies when it hunts for the app's log.
BACKUP_PARENTS=("${XDG_DATA_HOME:-$HOME}")
# The loop does not export LOOP_HOME, so this line adds its Documents tree only for a caller who did export
# it - the loop searches it for the same reason: the isolated HOME is where app DATA is meant to land.
[[ -n "${LOOP_HOME:-}" ]] && BACKUP_PARENTS+=("$LOOP_HOME")
BACKUP_DB_DIRS=()
for parent in "${BACKUP_PARENTS[@]}"; do
    for root_name in Payanam-debug Payanam; do
        BACKUP_DB_DIRS+=("$parent/Documents/$root_name/db")
    done
done
say "artifact candidates: ${BACKUP_DB_DIRS[*]}"

# -newermt against the interval-selection stamp, not ls -t: "newest file, any age" is how an artifact from
# an earlier run gets reported as this one's. The 5s slack is second-granularity and absorbs a stamp that
# rounds down into the second the interval was selected in. The name carries the stamp, so the newest
# auto_bk_*.db IS the scheduled run's output, and the -NN suffix desktopScheduledBackupDestination appends
# for two runs inside one second still matches the glob. No input happened between SCHEDULED_AT and here
# (PHASE 7 does none), so a file newer than that boundary is attributable to the scheduler alone.
# 2026-10-08 parity change: tolerate both flat auto_bk_*.db files and auto_bk_ session dirs containing payanam.db.
NEWEST_ARTIFACT=""
for dir in "${BACKUP_DB_DIRS[@]}"; do
    candidate=$(find "$dir" -maxdepth 1 \( -name 'auto_bk_*.db' -o -name 'auto_bk_*' \) -newermt "@$((SCHEDULED_AT - 5))" \
        -printf '%T@ %p\n' 2>/dev/null | sort -rn | head -1 | cut -d' ' -f2-)
    [[ -n "$candidate" ]] || continue
    # Resolve to the actual .db file: flat file is itself, dir artifact is payanam.db inside
    if [[ -d "$candidate" ]]; then
        candidate="$candidate/payanam.db"
    fi
    [[ -f "$candidate" ]] || continue
    if [[ -z "$NEWEST_ARTIFACT" || "$candidate" -nt "$NEWEST_ARTIFACT" ]]; then
        NEWEST_ARTIFACT="$candidate"
    fi
done
[[ -n "$NEWEST_ARTIFACT" ]] || die "PHASE 8: no auto_bk_<yyyyMMdd-HHmmss>.db file or auto_bk_<yyyyMMdd-HHmmss>/payanam.db directory newer than the 15-minute interval selection ($(date -d "@$SCHEDULED_AT" '+%H:%M:%S')) in any of: ${BACKUP_DB_DIRS[*]}, after waiting the full ${SCHEDULED_WAIT_SECONDS}s with no input. The toggle was asserted on and the shortest interval was chosen, so the scheduler fired nothing. If this repeats, raise SCHEDULED_WAIT_SECONDS - do NOT conclude the scheduler is broken off one run at exactly ${SCHEDULED_WAIT_SECONDS}s."

# Printed AFTER the assertion, so a passing run still says where the file is and a failing one stops above.
say "=== EXPECTED ARTIFACT DIRECTORY: ${BACKUP_DB_DIRS[*]}  (file: auto_bk_<yyyyMMdd-HHmmss>.db or dir: auto_bk_<yyyyMMdd-HHmmss>/payanam.db) ==="
say "=== CLAIM: a file or dir newer than the interval selection, with no input during the wait, proves the SCHEDULED path ==="
say "=== ARTIFACT: $NEWEST_ARTIFACT ($(date -d "@$(stat -c %Y "$NEWEST_ARTIFACT")" '+%H:%M:%S')) ==="
say "--- scheduled-backup finished: scheduled auto-backup drive ---"