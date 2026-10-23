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

shot 01-tour-page-1

# The tour: three "Next" taps. A page transition is an animation, so the settle is a full 2s, and the
# last one is longer because the last page is the slowest to hand over to the gate.
click 254 739 2
shot 02-tour-page-2

click 254 739 2
shot 03-tour-page-3

click 254 739 4
shot 04-after-tour

# The database-init gate needs a moment to compose and to run its health check before it is tappable -
# a tap delivered to a screen that has not finished composing is the miss that looks like a miss.
sleep 6
shot 05-setup-gate

# Create the empty database. This is the step that makes the NEXT launch resolve to the unlock gate
# instead of re-asking to create one.
click 256 517 3
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
click 256 437 1
type_text "$PASSPHRASE"
shot 09-first-field-typed

# The second field. Its centre is (256, 517) - the SAME y as the "create new database" button on the
# previous screen, which is why the shot above is what tells the two screens apart: the coordinates are
# reused and the layout is not.
click 256 517 1
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
click_button submit-passphrase --band 520-610 --settle 4
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
# FOUND, NOT GUESSED (2026-09-30): the literal (255,739) this step used to carry also no longer lands -
# same byte-identical frames - so it is found by colour now, with y=724-779 as the sanity band.
sleep 6
shot 12-dimension-setup

click_button dimension-setup-continue --band 700-800 --settle 4
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
