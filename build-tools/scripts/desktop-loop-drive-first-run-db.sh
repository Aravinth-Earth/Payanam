#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# Drive script for build-tools/scripts/desktop-loop.sh (-d this file) - PHASE 1 of the unlock-gate pair.
#
# WHAT THIS EXISTS FOR
# The unlock gate is unreachable in a fresh HOME: with no database on disk the app resolves to
# InitializeDatabase / PassphraseSetup and stops there. So the only honest way to photograph the unlock
# gate is to CREATE a database in one loop run and RE-LAUNCH against the same HOME. This file is phase 1
# (create it); desktop-loop-drive-unlock.sh is phase 2 (photograph it).
#
# MEASURED COORDINATES (1280x900 root, window at x=50..462, y=50..870). Taken from the frames of a real
# run, not read off the code - see 06-final.png of any desktop-loop run. The helpers come from the loop
# script: click X Y [settle], type_text, press KEY, shot NAME.
#
#   privacy tour page 1/3, "Next"     -> (254, 739)   three taps, three pages
#   "Create new empty database"       -> (256, 517)   first teal pill on the database-init gate
#   passphrase field                   -> (256, 437)   1st of two password fields
#   confirm-passphrase field           -> (256, 517)   2nd of two password fields
#   "Set passphrase and continue"      -> (256, 597)   DISABLED until both fields match
#
# WHY EVERY CLICK IS FOLLOWED BY A SHOT
# A tap that misses leaves the screen byte-identical, and three identical screenshots in a row are the
# signature of three silent misses. The loop's click() already hashes before and after and warns; the
# shots are what make the sequence readable afterwards.
#
# The passphrase used here is a throwaway typed into a scratch HOME that the next phase reads. It is NOT
# a credential: this file creates an empty database in an isolated $HOME, and the real database is never
# touched.
#
# MEASURED: the submit button stays disabled - and the form then shows an INLINE error
# "குறைந்தது ஒரு குறியீடு எழுத்தை சேர்க்கவும்." ("add at least one symbol character") - unless the
# passphrase mixes upper case, lower case, a digit AND a symbol. An all-alphanumeric passphrase submits
# nothing and the run looks like it silently failed, so the default carries a '#' for that reason alone.
PASSPHRASE="${LOOP_PASSPHRASE:-LoopDrive#Pass9}"

# --- WHICH PRIMARY COLOUR THIS THEME PAINTS ---------------------------------------------------------------
# The app's primary control is #00796B (teal 700) in the LIGHT scheme and #80CBC4 (teal 200) in the DARK one.
# MEASURED 2026-10-14 from build 406's own frames: an isolated $HOME has an EMPTY preference store, so the
# theme resolves through the system's answer - which under Xvfb is DARK - and the loop's default #00796B then
# matches NOTHING on screen:
#   [create-database] no #00796B region of >=400px anywhere in the 1280x900 frame
# so every primary-button step below failed on a screen that was rendering perfectly. The finder is asked for
# the colour the theme ACTUALLY paints instead. The light colour is offered FIRST, so a light-theme run
# behaves exactly as it did before this pair was introduced.
PRIMARY_COLORS=("${LOOP_PRIMARY_COLOR:-#00796B}" "#80CBC4")

# click_primary LABEL BAND [SETTLE]
# The colour-aware form of click_button: same loud failure, one extra question ("which theme colour?").
click_primary() {
    local label="$1" band="$2" settle="${3:-3}" colour
    for colour in "${PRIMARY_COLORS[@]}"; do
        if find_button_centre --label "$label" --band "$band" --color "$colour" >/dev/null 2>&1; then
            click_button "$label" --band "$band" --color "$colour" --settle "$settle"
            return 0
        fi
    done
    die "STEP '$label': no primary-coloured control in band $band matched ANY known theme colour (${PRIMARY_COLORS[*]})"
}

shot 01-tour-page-1

# The tour is THREE slides, so the choice screen appears after the THIRD tap. The control at (254,739) is
# "Next" on slides 1 and 2 and "Start" on slide 3 - three taps, not one.
#
# MEASURED 2026-10-14, build 402: this file carried "the tour is over after ONE tap" while also claiming
# "the tour is TWO pages" - the two contradict each other, and against build 402 a single tap left a screen
# with NO create control on it at all. The run died at
# `find_text_centre --text 'Create New Empty Database'` with "the gate screen has changed again", and shots
# 02, 04 and 05 were BYTE-IDENTICAL to each other: one tap had moved the tour from slide 1 to slide 2, and
# the screen stayed there. The shared tour's own source is the authority for the count - it builds
# `onboarding_slide1..3_title` into a three-entry page list - and this driver's sibling
# (`desktop-loop-drive-onboarding.sh`) has always tapped three times.
#
# The tour being the FIRST step of the shared database-init screen is also the app change this run exists to
# photograph: the desktop used to raise the tour as a startup gate of its own ahead of that screen and then
# tell the screen to skip its copy. Android has only the one copy, inside the screen.
click 254 739 2
shot 02-tour-page-2

click 254 739 2
shot 03-tour-page-3

# The third tap finishes the tour and hands over to the create/import choice on the SAME shared screen.
click 254 739 2
shot 04-after-tour

# The database-init gate needs a moment to compose and to run its health check before it is tappable -
# a tap delivered to a screen that has not finished composing is the miss that looks like a miss.
sleep 6
shot 05-setup-gate

# Create the empty database. This is the step that makes the NEXT launch resolve to the unlock gate
# instead of re-asking to create one.
#
# FOUND BY COLOUR, NOT BY ITS WORDS - the label route was retired 2026-10-14 (build 406). It used to ask
# `find_text_centre --text 'Create New Empty Database'`, which answered "no text matched" on a screen that
# was rendering the control correctly: in the dark scheme the pill is a light fill (#80CBC4) under dark
# label glyphs, and that combination is below what the OCR can read at any scale - measured by cropping the
# pill and OCRing it at 200%, 300% and 400%: every attempt returned stroke fragments ("-C-l-T---"), never
# the words. The colour finder has no such trouble, and the band below is the measured location of the
# control as a sanity check: if a reflow moves it out of the band, the run says so loudly.
click_primary create-database 450-520 3
shot 06-after-create

# Let the created database finish writing, and let the screen that asks for a passphrase compose.
# Long and deliberate: the file write and the recomposition both happen inside this window, and a shot
# taken early shows the previous screen, which is how a run looks like it did nothing.
sleep 6
shot 07-passphrase-step
sleep 6
shot 08-passphrase-settled

# --- the passphrase itself --------------------------------------------------------------------------------
# Both fields, typed with type_text (not key presses), because the shared screen reads the value out of
# the text field: synthetic key events for ordinary characters do not always reach a Compose field, and
# a field left empty is the one outcome that silently keeps the submit button disabled.
#
# The first field. click() is used rather than a bare mousemove because the field must take FOCUS, and
# click() also reports whether the tap changed the screen - the warning it prints when the frame is
# byte-identical is how a field that did not take focus gets caught here rather than three steps later.
#
# RE-MEASURED 2026-10-14 (build 406): the window's content sits ~22px higher than when (256,437)/(256,517)
# were taken, so both field points were taken again from this build's own frame - the labels read
# "Passphrase" at y=411 and "Confirm passphrase" at y=485 in `04-create-form-settled.png`.
click 253 415 1
type_text "$PASSPHRASE"
shot 09-first-field-typed

# The second field.
click 253 492 1
type_text "$PASSPHRASE"
shot 10-both-fields-typed

# The submit button was disabled on the frame above; with both fields filled and matching it is enabled,
# so a tap here is what actually writes the passphrase and seals the database.
#
# FOUND, NOT GUESSED (2026-09-30): the literal this step used to carry, (256,597), no longer hits
# anything on the current build - the frames across the tap are byte-identical - because the
# storage-mode label fix reflowed the card and moved the button. So the step asks for the theme's
# primary control and clicks where it actually is. The band below is the measured location as a SANITY
# CHECK: if a reflow moves the button out of it, the run says so loudly and still clicks the real
# control, which is the whole point - the coordinates are no longer load-bearing.
# The submit control is rendered in the scheme's SECONDARY container, measured #453C80 (dark) on build 406 -
# NOT the primary teal the other steps are found by, so the colour finder is the wrong question here: asked
# for teal in this band it fell back to the whole frame and clicked a stray blob outside the window (measured:
# `the click at (847,560) left the screen byte-identical`). The point below is the measured centre of the
# control in `10-both-fields-typed.png` (band y 534-589, x 72-435), and the step that follows it is checked
# against the app's OWN log line ("Passphrase accepted; waiting for mandatory dimension setup before DB
# creation") rather than against the frame, because a filled form and an empty one look the same here.
click 253 561 4
shot 11-after-submit

# MEASURED: accepting the passphrase does NOT create the database. The log says
#   "Passphrase accepted; waiting for mandatory dimension setup before DB creation"
# and the screen that replaces the form is a MANDATORY DIMENSION SETUP: 9 default dimensions, pre-filled
# and editable, and nothing to fill in. The single control that finishes the step is the primary
# "சரிபார்த்து தொடரவும்" ("verify and continue") at (255, 739) - the same (x, 739) as the tour's Next,
# which is a coincidence of layout, not a shared control.
#
# This step is what the previous run of this script was missing: it stopped on the passphrase form, so no
# database was ever written and the next launch could only ever resolve back to the setup gate.
#
# FOUND, NOT GUESSED (2026-09-30; re-measured 2026-10-14 on build 406): the literal (255,739) this step
# used to carry no longer lands, and the colour finder cannot answer for it either - asked for teal inside
# y=716-771 it fell back to the whole frame and clicked (850,744), a stray blob OUTSIDE the app window
# (the frame is the whole root window; the app is a 412px-wide child of it). The point below is the
# measured centre of this build's control: band y 716-771, x 72-435, centre (253,743). A coordinate is safe
# HERE because the loop's click() fails the run loudly when the frame does not change, so a control that
# moved cannot be clicked silently.
sleep 6
shot 12-dimension-setup

click 253 743 4
shot 13-after-continue

# The database is actually created only now, and the app recomposes into the shell. Generous waits, and a
# final frame, so the run ends on the state the NEXT launch inherits rather than mid-transition.
sleep 8
shot 14-sealed
sleep 8
shot 15-final

# --- PHASE 1.5: carry ON past creation, to the screen the app moves to next ----------------------------
#
# The previous version of this file STOPPED at creation, so the one thing it never showed was what the app
# does next. That is the defect this run exists to photograph: with the security snapshot left stale,
# `resolveDesktopStartupMode` reads `hasPassphraseConfigured = false` and lands on `SetupPassphrase` - whose
# desktop gate renders the SHARED `DatabaseInitScreen`, whose button is "create new database". A second
# create then finds the database it just wrote and raises a wipe confirm. A correct run lands on the
# passphrase UNLOCK gate instead.
#
# So the frames below are timed to let the create's recomposition and the vault's verifier write settle,
# and several are taken in a row because ONE frame cannot distinguish "the unlock gate" from "the unlock
# gate still animating". No further clicks are made on purpose: this phase observes, and a stray tap here
# is exactly how the run used to turn a create into a wipe confirm.
sleep 10
shot 16-after-create-screen

sleep 8
shot 17-post-create-settled

# Long, then one more: the wrong destination (setup gate) shows a "create new database" control and this
# script never presses it, so these frames stay stable - which is what makes them readable as a destination
# rather than a transient.
sleep 12
shot 18-final-destination

say "--- post-create destination captured; read startupMode transitions from the app log ---"
say "--- phase 1 finished; the next phase must run WITHOUT -f against this same HOME ---"
