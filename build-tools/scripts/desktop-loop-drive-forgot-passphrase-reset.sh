#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# Drive script for build-tools/scripts/desktop-loop.sh (-d this file) - PHASE 2 of the unlock-gate pair.
#
# WHAT THIS EXISTS FOR
# The forgot-passphrase RESET's own journey on the desktop: unlock gate -> reset -> whatever the app shows
# next. The owner's build-405 run crashed in exactly this window -
#   DesktopMain.uncaughtException | IllegalStateException: LocalDataLayer.lenses was read before a data layer
#   was installed, stack: DesktopBootstrapDataLayer.getLenses -> LensViewModel.lensViewModel ->
#   DesktopPayanamNavHostScreens.lenses
# - because the reset deleted the database and the credential while the platform's startup decision kept the
# facts it had read at launch. So the two things this run must produce are: the frame the reset hands over to,
# and the app's own log, which is where a crash would appear.
#
# WHAT A CORRECT RUN SHOWS
# The reset lands on the shared database-init gate at its CREATE form ("Secure your database" / two
# passphrase fields) - not the tour, and not the create/import choice. The owner's ruling (2026-10-14):
# *"When a user is using a reset option which means user already know how an app works only then they will be
# using the reset option. So in that case we don't need to show them the onboarding flow once more."*
#
# PHASE 1 IS REQUIRED FIRST. This HOME must already hold an encrypted database carrying the loop passphrase,
# which `desktop-loop-drive-first-run-db.sh` creates. Then run this file WITHOUT -f against that same HOME:
#   bash build-tools/scripts/desktop-loop.sh -d build-tools/scripts/desktop-loop-drive-first-run-db.sh
#   bash build-tools/scripts/desktop-loop.sh -d build-tools/scripts/desktop-loop-drive-forgot-passphrase-reset.sh
set -uo pipefail

# --- the app's own region inside the 1280x900 root window --------------------------------------------------
# The frame is 1280x900 but the app is a phone-sized 412x820 inside it, and an OCR lookup over the WHOLE frame
# is both too coarse to read the app's copy and free to match something OUTSIDE it. The region is computed
# from the window the loop actually launched - never hardcoded, so it follows the window wherever it sits.
# (Same derivation the sibling drive files use; without it this file's `"$APP_REGION"` would abort under the
# loop's `set -u` before a single step ran.)
APP_REGION=""
if [[ -n "${WINDOW_ID:-}" ]]; then
    APP_REGION=$(xwininfo -id "$WINDOW_ID" 2>/dev/null | awk '
        /Absolute upper-left X/ {x=$NF}
        /Absolute upper-left Y/ {y=$NF}
        /^ *Width:/             {w=$NF}
        /^ *Height:/            {h=$NF}
        END { if (x!="" && y!="" && w!="" && h!="") printf "%d,%d,%d,%d", x, y, x+w, y+h }')
fi
[[ -n "$APP_REGION" ]] || die "could not derive the app window region (WINDOW_ID='${WINDOW_ID:-unset}'), so every OCR lookup would run unscoped against the full frame and report nothing. Stopping rather than guessing a region."
say "OCR region scoped to the app window: $APP_REGION"

# The same throwaway the loop fixture already set on this HOME (phase 1 created the database with it). Typed
# into an isolated $HOME's own field - NOT a credential, and overridable so the two phases cannot drift.
PASSPHRASE="${LOOP_PASSPHRASE:-LoopDrive#Pass9}"

# NOTE ON THE HELPER THIS FILE DOES NOT USE ANY MORE. It carried a `click_label` that asked
# find_text_centre for a control's words at scale 3 and then 2. That is still the right question in
# general, but on this build it cannot answer for EITHER control this file needs: asked for
# "Forgot passphrase? Reset local data" inside the window-scoped region it answered "no text matched",
# while a crop of (124,482)-(384,512) from the same frame OCRs to that phrase verbatim. The label
# matcher's own three tiers (consecutive words, gapped words, whole-phrase substring) all missed it, so
# the fallback chain could only ever have failed twice and then died. Both steps below therefore use
# measured coordinates, each with the measurement recorded above it, and each still backed by the loop's
# own byte-identical check - which is what stops a coordinate from failing silently.

# --- the unlock gate -------------------------------------------------------------------------------------
# The app was launched against a HOME that holds a database and a passphrase, so the gate it resolves to is
# the UNLOCK gate (confirmed in the app's own log: startupMode="UnlockPassphrase"). Long waits, because the
# first paint of a software-rendered window is slow and a tap delivered before the screen composes is a miss
# that looks like a slow app.
sleep 6
shot 01-unlock-gate
sleep 6
shot 02-unlock-gate-settled

# --- the reset, through its own confirmation dialog ------------------------------------------------------
# MEASURED, NOT GUESSED, and measured MEASURABLY rather than by eye: the crop (124,482)-(384,512) of
# `02-unlock-gate-settled.png`, taken at 3x, reads "Forgot passphrase? Reset local data" verbatim through
# tesseract - so the label is on screen and legible, and (254,494) is its centre.
#
# WHY NOT find_text_centre, which is the loop's answer to exactly this question: asked for that phrase at
# 2x and then 3x inside the window-scoped region, it answered "no text matched in the 1280x900 frame ...
# Not printing a guess" while this very crop of the same frame OCRs to the phrase character for character.
# The finder's matcher is therefore unable to read a label this long in this theme; the coordinate is the
# measured fallback, and the loop's own click() still refuses to let it fail silently - a tap that lands
# nowhere fails the run.
click 254 494 3 "click-forgot-passphrase"
shot 03-reset-confirm-dialog
sleep 3
shot 04-reset-confirm-dialog-settled

# The destructive action. Its own confirmation is an AlertDialog: `confirmButton` is a filled Button
# carrying [db_passphrase_unlock_reset_confirm] ("Reset and continue") and `dismissButton` a TextButton
# carrying Cancel. Material3 lays those out dismiss-then-confirm, so the confirm is the FILLED pill on the
# right - measured on this build's frame: the teal fill (rgb 120,192,192 = the dark scheme's #80CBC4
# primary) runs x 214-390 at y 520-559, and a crop of (300,516)-(400,562) OCRs to "continue" while
# (140,516)-(300,562) OCRs to "Cancel Reset anc". Centre (302,539).
#
# It deletes the artifact family and clears the credential on the shared ViewModel's own dispatcher, then
# the graph moves - which is the moment build 405 crashed on.
click 302 539 4 "click-reset-confirm"
shot 05-after-reset

sleep 6
shot 06-post-reset-settled
sleep 6
shot 07-post-reset-final

# --- what the reset handed over to -----------------------------------------------------------------------
# The create form's title is the shared screen's `db_passphrase_setup_title`, and it is what a correct reset
# shows. Its ABSENCE is the defect, so the check is explicit and says which way the run went: the loop keeps
# every frame either way, and the app log is the other half of the evidence.
on_screen() {
    # Tries both OCR scales, so a caller asks the question once instead of repeating the fallback chain.
    local text="$1" scale
    for scale in 3 2; do
        if find_text_centre --text "$text" --scale "$scale" --region "$APP_REGION" >/dev/null 2>&1; then
            return 0
        fi
    done
    return 1
}

if on_screen 'Secure your database'; then
    say "--- RESET LANDED ON THE CREATE FORM (\"Secure your database\"): the passphrase step of the database"
    say "--- that replaces the wiped one, with no tour and no create/import chooser ---"
else
    say "--- the create form was NOT found by its words. Read shots 04-06 for what the reset handed over to,"
    say "--- and grep the app log for 'LocalDataLayer' / uncaughtException before concluding anything ---"
fi

# The tour must NOT be here. Checked by its FIRST frame's own copy, not by its last slide's action: the action
# reads "Next" on slides 1-2 and only becomes "Get Started" on slide 3, so an action alone cannot tell "the
# tour is here" from "the tour is finished".
if on_screen 'Skip Tour' || on_screen 'Your Privacy First'; then
    die "the onboarding tour is on screen after a reset - the owner's ruling says it must not be"
fi
say "--- no tour on screen after the reset ---"
# The chooser is the second question that must not be asked over a database the user asked to be replaced.
if on_screen 'Create New Empty Database' || on_screen 'Import Database File (.db)'; then
    die "the create/import chooser is on screen after a reset - the reset replaced the database, it did not ask"
fi
say "--- no create/import chooser on screen after the reset ---"

say "--- read the app log next: 'Forgot-passphrase reset completed; re-reading the startup facts', then the"
say "--- init gate, and NO 'LocalDataLayer' / uncaughtException line anywhere in the session ---"
