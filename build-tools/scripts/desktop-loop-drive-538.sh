# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# Issue 538: prove the MANUAL backup path produces a file, from the UI, without waiting on a timer.
# The "Run Backup Now" button is rendered ONLY inside `if (prefsState.autoBackupEnabled)` - so with
# auto-backup OFF the button does not exist on screen at all, and a drive that goes straight for it will
# fail on a screen it never reached. THIS FILE THEREFORE ENABLES THE TOGGLE FIRST. That is the one fact
# that decides the shape of the whole script: enable -> assert -> manual -> report. Skipping the enable is
# not a shorter path, it is a drive that cannot work.
#
# WHY 538 AND 525 ARE NOT REDUNDANT
# Both go through the same runBackup() path, so "same code" is an argument rather than evidence. 525 proves
# the SCHEDULER fires unattended; 538 proves a single user-initiated run lands a file. The interval does NOT
# matter here - this drive never waits on a timer - which is also why there is no interval step in it.
#
# MEASURED PIXELS (1280x900 root). Taken from the frames of a real run, not read off the code.
#
#   privacy tour "Next"    -> (254, 739)   three taps, three pages
#
# That one is the only raw pixel here. The tour is rendered in Tamil and only eng+osd tessdata is
# installed, so the tour is NOT OCR-readable - find_text_centre cannot see those words at any scale. The
# tour is therefore crossed by raw pixel exactly as desktop-loop-drive-first-run-db.sh crosses it, and every
# other control on every screen after it is found BY ITS WORDS. One raw pixel, documented; nothing else
# guessed.
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
        found=$(find_text_centre --text "$text" --scale "$scale"); rc=$?
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
# The hard state assertion after the toggle is flipped. The row hint reads "Disabled" while auto-backup
# is off and "Backups saved to Documents/Payanam" once it is on, so the disappearance of "Disabled" is a
# state assertion about the app, not a pixel measurement. Uses the same scale chain for the same
# unmeasured reason click_label does.
assert_gone() {
    local text="$1"
    local step="${2:-assert_gone}"
    local scale
    for scale in 3 2; do
        if [[ -n "$(find_text_centre --text "$text" --scale "$scale" 2>/dev/null)" ]]; then
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
# Deliberately a soft check. The hard assertion is the disappearance of "Disabled" (above), and this is
# corroboration whose value would turn on OCR reading a line containing a slash - so a miss here is
# reported, not fatal.
have_label() {
    local text="$1"
    local scale
    for scale in 3 2; do
        if [[ -n "$(find_text_centre --text "$text" --scale "$scale" 2>/dev/null)" ]]; then
            say "have_label: found \"$text\" at scale $scale"
            return 0
        fi
    done
    say "have_label: NOT found - \"$text\" (scales 3 and 2). Soft check only; not fatal."
    return 1
}

say "--- 538: manual backup drive ---"

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

UNLOCK_FOUND=$(find_text_centre --text 'Unlock' --scale "$UNLOCK_SCALE"); UNLOCK_RC=$?
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
FOCUS_CHOICE="$(find_text_centre --text 'Full Suite' --scale 2 2>/dev/null)" || FOCUS_CHOICE=""
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
        if ! find_text_centre --text 'Choose Your Focus' --scale 2 >/dev/null 2>&1; then FOCUS_STUCK=0; break; fi
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
        for candidate in Settings Habits Journal Notes Lenses Tasks; do
            if find_text_centre --text "$candidate" --scale 2 >/dev/null 2>&1; then SHELL_UP=1; break 2; fi
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
click_label "Settings" 3 "nav to Settings"
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
for _ in $(seq 1 "$SETTINGS_SCROLL_CLICKS"); do
    xdotool click 5
    sleep 0.4
done
sleep 2
shot 07-settings-scrolled

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
# PHASE 5 - ENABLE the toggle. This step is what makes the next step possible.
#
# DERIVED, NOT MEASURED: the tap point. The Switch widget has NO visible words - its contentDescription is
# not rendered as pixels, so OCR gives us the row label's Y but never the switch's X. Two ways to get the
# toggle, and this one is chosen deliberately:
#
#   (a) click the ROW LABEL ("Enable Auto-Backup"), because the whole row is clickable. Measured
#       behaviour, no coordinate invented - this is what click_label does below.
#   (b) derive an X at the row's right edge. NOT USED, and if you switch to it: DERIVED, falsified by the
#       tap landing outside the row's clickable bounds, which silently does nothing until the assertion
#       in the next step catches it. Do not use (b) - (a) exists because it needs no guess.
click_label "Enable Auto-Backup" 3 "ENABLE auto-backup (required: Run Backup Now only renders when on)"
sleep 2
shot 09-after-toggle

# THE ASSERTION, and here it is also a PRECONDITION. While auto-backup is off the row hint reads
# "Disabled"; once it is on it reads "Backups saved to Documents/Payanam". If "Disabled" is still on
# screen the toggle did NOT flip - and since "Run Backup Now" is gated on this flag, the next step would
# be searching for a button that does not exist. Stop instead of looking for it.
assert_gone "Disabled" "auto-backup is ON (precondition for the Run Backup Now button)" \
    || die "PHASE 5: the row hint still reads 'Disabled', so the toggle did NOT flip. Stopping: 'Run Backup Now' is only rendered when auto-backup is enabled, so it does not exist on this screen and clicking toward it would be a guess."

# Corroboration only - soft, because it depends on OCR reading a string that contains a slash.
have_label "Backups saved to Documents/Payanam"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 6 - press the manual backup button.
#
# MEASURED STRING: the button renders "Run Backup Now". It exists now because Phase 5 proved the toggle
# flipped. The settle is generous: a manual run does real file work, and a short settle can capture the
# frame before the button returns to its idle state.
click_label "Run Backup Now" 6 "press the manual backup button"
shot 10-after-manual-run
sleep 6
shot 11-manual-run-settled

# ---------------------------------------------------------------------------------------------------------------
# PHASE 7 - name the artifact.
#
# Automatic backups land in the app's data-root db/ subdirectory, named auto_bk_<yyyyMMdd-HHmmss>.db.
# MANUAL and SCHEDULED runs go through the SAME runBackup path - which is exactly what this drive exists
# to show, so the expected artifact is named the same way. That is read from the code that writes them,
# and it is authoritative. A KDoc elsewhere in the tree claims an "exports" directory - that comment is
# STALE and is contradicted by the code. Do not "fix" a missing artifact by looking in exports/.
#
# The line below is printed so a human reading the run log knows exactly where to look, and so the check
# is falsifiable: a file whose timestamp is after Phase 6, with the button pressed exactly once, is a
# manual run's output and nothing else.
say "=== 538 EXPECTED ARTIFACT DIRECTORY: <app data root>/db/  (file: auto_bk_<yyyyMMdd-HHmmss>.db) ==="
say "=== 538 CLAIM: one press of Run Backup Now, toggle asserted on, produced a file newer than Phase 6 ==="
say "--- 538 finished: manual backup drive ---"