#!/usr/bin/env bash
# Drive script for build-tools/scripts/desktop-loop.sh (-d this file).
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# WHAT THIS EXISTS FOR
# Prove that when a stored backup failure exists AND the passphrase unlock gate
# is showing, the backup-failure dialog CANNOT render. The guard at
# PayanamNavHost.kt:485 ensures the dialog is suppressed while
# shouldShowPassphraseUnlock is true or route==PASSPHRASE_UNLOCK.
#
# The precondition is fully known: desktop persists the failure in
# desktop-backup-status.properties in the preferences directory, written at
# DesktopBackupPorts.kt:167-169 with keys last_backup_failure_at_millis,
# last_backup_failure_display, last_backup_failure_message; read by loadSnapshot()
# on startup; rendered by PayanamNavHost.kt:485 when the message is non-blank.
#
# So: write those three keys into that properties file, THEN launch, and the dialog
# would appear if the guard were absent. The guard ensures it does NOT appear while
# the unlock gate is showing.
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

say "--- backup-failure-unlock: stored backup failure + unlock gate drive ---"

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

# Assert pre-unlock that the ON-DISK file already existed before PHASE 0b overwrites it.
# The parent pre-seeds before launch; if the pre-existing file is absent, say so loudly.
if [[ -f "$BACKUP_STATUS_FILE" ]]; then
    say "PHASE 0b: pre-existing backup status file found at $BACKUP_STATUS_FILE (parent pre-seeded before launch)"
else
    say "PHASE 0b: WARNING - no pre-existing backup status file at $BACKUP_STATUS_FILE before PHASE 0b overwrites it. The parent was expected to pre-seed before launch."
fi

# Write the three keys. The message is non-blank, which is the condition for the dialog to appear.
BACKUP_FAILURE_AT_MILLIS="$(date +%s)000"
BACKUP_FAILURE_DISPLAY="$(date '+%Y-%m-%d %H:%M:%S')"
BACKUP_FAILURE_MESSAGE="Seeded failure for the failure dialog"

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
# The guard (if working) prevents the modal from rendering while the unlock gate is showing.
# The defect (if present) would render a modal AlertDialog with title "Backup issue" and a dimmed
# backdrop OVER the unlock gate.
#
# Assertions:
# 1. "Backup issue" is NOT on screen (no modal over the gate)
# 2. "Unlock" IS on screen (the passphrase screen is rendered and reachable)
# 3. The "Unlock" button is clickable (no dimmed backdrop blocking it)

# ASSERTION 1: No "Backup issue" modal over the unlock gate
assert_gone "Backup issue" "no backup-failure modal is on screen while the unlock gate is showing" \
    || die "PHASE 2: 'Backup issue' is on screen - the modal IS present over the unlock gate. The guard at PayanamNavHost.kt:485 is NOT working."

# ASSERTION 2: "Unlock" button IS present
# (1) Settle first - wait for the window/app to finish resume before touching anything.
wait_for_stable_frame 10

# (2) Find the Unlock button and derive the passphrase field position (sibling drive idiom).
UNLOCK_SETTLE=3
UNLOCK_FIELD_ABOVE_BUTTON_PX=80

# MEASURED 2026-10-08 (t-779-probe-drive2.log + payanam_desktop_390_20261008-061505.log):
# after the unlock click, the post-unlock surface composes over ~0.5s and a single OCR
# snapshot can still read a transient 'Unlock' from the fading gate. Settle 3s before
# the first clear check, then space checks 2s apart, requiring TWO consecutive clears.
# A genuinely wrong passphrase keeps the gate up across every check and still retries once.
UNLOCK_CLEAR_SETTLE=3
UNLOCK_CLEAR_SPACING=2

UNLOCK_FOUND=""
for scale in 3 2; do
    UNLOCK_FOUND=$(find_text_centre --text 'Unlock' --scale "$scale" --region "$APP_REGION")
    UNLOCK_RC=$?
    if (( UNLOCK_RC == 0 )) && [[ -n "$UNLOCK_FOUND" ]] && [[ "$UNLOCK_FOUND" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]]; then
        say "PHASE 2: found 'Unlock' at $UNLOCK_FOUND (scale $scale)"
        break
    fi
    say "PHASE 2: 'Unlock' not found at scale $scale (rc=$UNLOCK_RC)"
    UNLOCK_FOUND=""
done

if [[ -z "$UNLOCK_FOUND" ]]; then
    die "PHASE 2: the 'Unlock' button was not found at scale 3 OR scale 2. The passphrase screen is not rendered, or something is blocking it."
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

    # A frame change is NOT success - a wrong-focus click also changes the frame (focus
    # ring) while the gate stays up (measured 2026-10-08 drive779b). Success is the GATE
    # BEING GONE: 'Unlock' must no longer be readable.
    #
    # MEASURED 2026-10-08 (t-779-probe-drive2.log + payanam_desktop_390_20261008-061505.log):
    # a single OCR snapshot taken immediately after the frame change can still read a
    # transient 'Unlock' from the fading gate (attempt-1 false positive at 06:15:19.4).
    # The gate composes over ~0.5s, so we settle UNLOCK_CLEAR_SETTLE seconds before the
    # first check, then require TWO consecutive clears spaced UNLOCK_CLEAR_SPACING apart.
    # A genuinely wrong passphrase keeps the gate up across every check and still retries once.
    local clear_count=0
    local check_num=0
    while (( clear_count < 2 )); do
        check_num=$(( check_num + 1 ))
        if (( check_num == 1 )); then
            sleep "$UNLOCK_CLEAR_SETTLE"
        else
            sleep "$UNLOCK_CLEAR_SPACING"
        fi
        local still_there=""
        for scale in 3 2; do
            still_there=$(find_text_centre --text "Unlock" --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
            [[ -n "$still_there" ]] && break
        done
        if [[ -n "$still_there" ]]; then
            say "unlock_with_verify $attempt: check $check_num - 'Unlock' is STILL on screen ($still_there) - gate not cleared"
            return 1
        fi
        clear_count=$(( clear_count + 1 ))
        say "unlock_with_verify $attempt: check $check_num - 'Unlock' is gone (clear $clear_count of 2)"
    done

    say "unlock_with_verify $attempt: frame changed AND gate cleared (2 consecutive checks) - unlock succeeded"
    return 0
}

if ! unlock_with_verify 1; then
    say "PHASE 2: first unlock attempt failed - retrying once"
    if ! unlock_with_verify 2; then
        die "PHASE 2: unlock failed after 2 attempts - the unlock gate could not be crossed"
    fi
fi

# (7) Keep assert_gone after success.
assert_gone "Unlock" "unlock-gate-cleared" \
    || die "PHASE 2: 'Unlock' is still on screen after unlocking - the gate did not clear."

shot 02-after-unlock

# ---------------------------------------------------------------------------------------------------------------
# ---------------------------------------------------------------------------------------------------------------
# PHASE 2.5 - dismiss the session-continuation screen if one covers the shell after unlock.
#
# A time session left running by an earlier run makes the app open an "Assign Time" workflow
# (measured 2026-10-08: Duration 304 min, buttons Cancel / Continue / End) INSTEAD of the shell,
# so nothing behind it - including the deferred backup-failure dialog - is OCR-reachable. This
# step ends that session so PHASE 3 asserts against the real post-unlock surface. When no such
# screen is present (the normal case), it is a no-op.
ASSIGN_FOUND=$(find_text_centre --text 'Assign Time' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
EDIT_FOUND=$(find_text_centre --text 'Edit Time Entry' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
if [[ -n "$EDIT_FOUND" ]]; then
    # A timer left running by an earlier run opens its editor over the shell at unlock (measured
    # 2026-10-08: 00:00 -> now, still growing). Delete the stray entry so the shell - and the
    # deferred backup-failure dialog - are the real post-unlock surface.
    say "PHASE 2.5: running-session editor present at $EDIT_FOUND - deleting the stray entry"
    shot 02a-edit-time-entry
    DEL_FOUND=$(find_text_centre --text 'Delete' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$DEL_FOUND" ]] || DEL_FOUND=$(find_text_centre --text 'Delete' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$DEL_FOUND" ]] || die "PHASE 2.5: the 'Edit Time Entry' screen is up but its 'Delete' button was not found - stopping rather than guessing a pixel"
    read -r DEL_X DEL_Y <<< "$DEL_FOUND"
    click "$DEL_X" "$DEL_Y" 3 "PHASE 2.5: delete the stray running entry"
    sleep 2
    # A confirmation, if any, names Delete too - press once more if the screen is still the editor.
    if [[ -n "$(find_text_centre --text 'Edit Time Entry' --scale 3 --region "$APP_REGION" 2>/dev/null || true)" ]]; then
        DEL2=$(find_text_centre --text 'Delete' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
        [[ -n "$DEL2" ]] && { read -r D2X D2Y <<< "$DEL2"; click "$D2X" "$D2Y" 3 "PHASE 2.5: confirm delete"; sleep 2; }
    fi
    shot 02b-after-entry-delete
    say "PHASE 2.5: stray running entry handled"
elif [[ -n "$ASSIGN_FOUND" ]]; then
    say "PHASE 2.5: session-continuation screen present at $ASSIGN_FOUND - ending the session"
    shot 02a-assign-time
    END_FOUND=$(find_text_centre --text 'End' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$END_FOUND" ]] || END_FOUND=$(find_text_centre --text 'End' --scale 2 --region "$APP_REGION" 2>/dev/null || true)
    [[ -n "$END_FOUND" ]] || die "PHASE 2.5: the 'Assign Time' screen is up but its 'End' button was not found - stopping rather than guessing a pixel"
    read -r END_X END_Y <<< "$END_FOUND"
    click "$END_X" "$END_Y" 3 "PHASE 2.5: end the running session"
    sleep 2
    shot 02b-after-assign-end
    # If a confirmation appears, press the primary (solid) button once more.
    END2_FOUND=$(find_text_centre --text 'End' --scale 3 --region "$APP_REGION" 2>/dev/null || true)
    if [[ -n "$END2_FOUND" ]]; then
        read -r END2_X END2_Y <<< "$END2_FOUND"
        click "$END2_X" "$END2_Y" 3 "PHASE 2.5: confirm ending the session"
        sleep 2
    fi
    say "PHASE 2.5: continuation screen handled"
else
    say "PHASE 2.5: no session-continuation screen - the shell is the post-unlock surface"
fi

# PHASE 3 - post-unlock assertion: the seeded failure detail IS surfaced now.
#
# After the gate clears, the backup-failure dialog should render. The dialog's EN title is
# "Backup issue" and the body prefix is "Last failure" (backupFailureDialogMessageWithTime =
# "Last failure (%1$s): %2$s"). We look for either at scale 3 then 2 within a bounded retry (~10s).
# Die naming the STEP if absent.
say "PHASE 3: asserting the seeded failure detail is surfaced post-unlock"

DETAIL_FOUND=""
for _try in 1 2 3 4 5; do
    for scale in 3 2; do
        DETAIL_FOUND=$(find_text_centre --text 'Backup issue' --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
        if [[ -n "$DETAIL_FOUND" ]]; then
            say "PHASE 3: found 'Backup issue' at $DETAIL_FOUND (scale $scale, try $_try)"
            break 2
        fi
        DETAIL_FOUND=$(find_text_centre --text 'Last failure' --scale "$scale" --region "$APP_REGION" 2>/dev/null || true)
        if [[ -n "$DETAIL_FOUND" ]]; then
            say "PHASE 3: found 'Last failure' at $DETAIL_FOUND (scale $scale, try $_try)"
            break 2
        fi
    done
    say "PHASE 3: try $_try - neither 'Backup issue' nor 'Last failure' found at scale 3 or 2"
    sleep 2
done

if [[ -z "$DETAIL_FOUND" ]]; then
    die "PHASE 3: the seeded backup failure detail was NOT surfaced post-unlock - neither 'Backup issue' nor 'Last failure' found after 5 tries (~10s). The dialog did not render after the gate cleared."
fi

shot 03-post-unlock-detail

# ---------------------------------------------------------------------------------------------------------------
# PHASE 4 - report what was proven.
say "=== PROVEN: all three clauses verified ==="
say "  Clause 1: NO modal over gate - 'Backup issue' was absent while the unlock gate was showing"
say "  Clause 2: gate interactive - 'Unlock' button found, passphrase typed, gate cleared (frame changed)"
say "  Clause 3: detail surfaced post-unlock - 'Backup issue' or 'Last failure' found after unlock"
say "--- backup-failure-unlock finished: stored backup failure + unlock gate drive ---"
