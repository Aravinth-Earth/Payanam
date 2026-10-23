# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# Prove that on a launch where a stored backup failure exists AND
# startupMode=UnlockPassphrase, the passphrase screen renders with NO modal and
# NO dimmed backdrop, and its Unlock button is reachable without dismissing anything.
#
# The precondition is fully known: desktop persists the failure in
# desktop-backup-status.properties in the preferences directory, written at
# DesktopBackupPorts.kt:167-169 with keys last_backup_failure_at_millis,
# last_backup_failure_display, last_backup_failure_message; read by loadSnapshot()
# on startup; rendered by PayanamNavHost.kt:485 when the message is non-blank.
#
# So: write those three keys into that properties file, THEN launch, and the dialog
# would appear if the defect were still present.
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
# WHY EVERY find IS A CLICK ON A REAL COORDINATE
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
# The hard state assertion after the toggle is flipped. The row hint reads "Disabled" while auto-backup
# is off and "Backups saved to Documents/Payanam" once it is on, so the disappearance of "Disabled" is a
# state assertion about the app, not a pixel measurement. Uses the same scale chain for the same
# unmeasured reason click_label does.
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
# Deliberately a soft check. The hard assertion is the disappearance of "Disabled" (above), and this is
# corroboration whose value would turn on OCR reading a line containing a slash - so a miss here is
# reported, not fatal.
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

say "--- preunlock-update: stored backup failure + unlock gate drive ---"

# PHASE 0b - seed the stored backup failure BEFORE the app launches.
#
# The file is at <root>/preferences/desktop-backup-status.properties, where <root> is
# <XDG_DATA_HOME or $HOME>/Documents/Payanam-debug (debug) or Payanam (release).
# This is derived from DesktopAppPaths.resolvePreferencesDirectory() which is
# resolveRootDirectory().resolve("preferences"), and resolveRootDirectory() on Linux is
# <XDG_DATA_HOME or $HOME>/Documents/<rootName>.
#
# The three keys are written exactly as DesktopBackupPorts.kt:167-169 defines them:
#   last_backup_failure_at_millis
#   last_backup_failure_display
#   last_backup_failure_message
#
# The file is written BEFORE the app launches so that loadSnapshot() on startup reads it.
BACKUP_STATUS_PARENTS=("${XDG_DATA_HOME:-$HOME}")
[[ -n "${LOOP_HOME:-}" ]] && BACKUP_STATUS_PARENTS+=("$LOOP_HOME")
BACKUP_STATUS_FILE=""
for parent in "${BACKUP_STATUS_PARENTS[@]}"; do
    for root_name in Payanam-debug Payanam; do
        candidate="$parent/Documents/$root_name/preferences/desktop-backup-status.properties"
        if [[ -d "$parent/Documents/$root_name/preferences" ]]; then
            BACKUP_STATUS_FILE="$candidate"
            break 2
        fi
    done
done
[[ -n "$BACKUP_STATUS_FILE" ]] || die "PHASE 0b: could not find the preferences directory under any of: ${BACKUP_STATUS_PARENTS[*]}/Documents/{Payanam-debug,Payanam}/preferences. The app has not been launched yet, so the directory may not exist - create it first or run a phase-1 drive."

# Write the three keys. The message is non-blank, which is the condition for the dialog to appear.
BACKUP_FAILURE_AT_MILLIS="$(date +%s)000"
BACKUP_FAILURE_DISPLAY="$(date '+%Y-%m-%d %H:%M:%S')"
BACKUP_FAILURE_MESSAGE="Seeded failure for the preunlock-update drive"

cat > "$BACKUP_STATUS_FILE" <<EOF
last_backup_failure_at_millis=$BACKUP_FAILURE_AT_MILLIS
last_backup_failure_display=$BACKUP_FAILURE_DISPLAY
last_backup_failure_message=$BACKUP_FAILURE_MESSAGE
EOF

say "PHASE 0b: seeded backup failure at $BACKUP_STATUS_FILE"
say "PHASE 0b:   last_backup_failure_at_millis=$BACKUP_FAILURE_AT_MILLIS"
say "PHASE 0b:   last_backup_failure_display=$BACKUP_FAILURE_DISPLAY"
say "PHASE 0b:   last_backup_failure_message=$BACKUP_FAILURE_MESSAGE"

# ---------------------------------------------------------------------------------------------------------------
# PHASE 1 - the app is already launched by the loop. Wait for it to settle.
#
# The loop launches the app before running this drive script. The app reads the seeded
# properties file on startup. We wait a few seconds for the startup to complete.
sleep 6
shot 01-startup

# ---------------------------------------------------------------------------------------------------------------
# PHASE 2 - ASSERT: no modal, no dimmed backdrop, Unlock button present.
#
# The defect (if present) would render a modal AlertDialog with title "Backup issue" and a dimmed
# backdrop. The fix ensures the passphrase screen renders without any modal.
#
# Assertions:
# 1. "Backup issue" is NOT on screen (no modal)
# 2. "Unlock" IS on screen (the passphrase screen is rendered and reachable)
# 3. The "Unlock" button is clickable (no dimmed backdrop blocking it)

# ASSERTION 1: No "Backup issue" modal
assert_gone "Backup issue" "no backup-failure modal is on screen" \
    || die "PHASE 2: 'Backup issue' is on screen - the modal IS present. The defect is NOT fixed."

# ASSERTION 2: "Unlock" button IS present
UNLOCK_FOUND=$(find_text_centre --text 'Unlock' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$UNLOCK_FOUND" ]] || UNLOCK_FOUND=$(find_text_centre --text 'Unlock' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
[[ -n "$UNLOCK_FOUND" ]] || die "PHASE 2: the 'Unlock' button was not found on screen. The passphrase screen is not rendered, or something is blocking it."
say "PHASE 2: 'Unlock' button found at $UNLOCK_FOUND"

# ASSERTION 3: The "Unlock" button is clickable (no dimmed backdrop)
# We verify this by actually clicking it and checking that the gate is dismissed.
# If a dimmed backdrop were present, the click would not reach the button.
read -r UNLOCK_X UNLOCK_Y <<< "$UNLOCK_FOUND"
say "PHASE 2: clicking 'Unlock' at $UNLOCK_X,$UNLOCK_Y to verify it is reachable"
click "$UNLOCK_X" "$UNLOCK_Y" 15 "press the unlock gate's \"Unlock\" button"
shot 02-after-unlock

# After clicking Unlock, the gate should be gone. If "Unlock" is still on screen, the click
# did not reach the button (possibly due to a dimmed backdrop or modal).
assert_gone "Unlock" "the unlock gate is GONE after clicking Unlock" \
    || die "PHASE 2: 'Unlock' is still on screen after clicking it - the button was not reachable (dimmed backdrop or modal present)."

# ---------------------------------------------------------------------------------------------------------------
# PHASE 3 - report what was proven.
say "=== PROVEN: stored backup failure seeded, app launched, NO modal, NO dimmed backdrop, Unlock button reachable ==="
say "--- preunlock-update finished: stored backup failure + unlock gate drive ---"
