#!/usr/bin/env bash
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# Desktop closed-loop runner.
#
# Launch the BUILT Linux app on a headless X server, drive it with real clicks and keystrokes, photograph
# every step, and hand back the app's own log - so one command produces the three things a verification
# needs: what the window looked like, what the user did, and what the app said about it.
#
# WHY THE UNPACKED APP AND NOT THE .deb
# jpackage leaves a runnable copy at the path this script defaults to, byte-identical to what the .deb
# ships, and running it needs no `sudo`. A password prompt in the middle of an automated loop is the one
# thing that stops the loop, so the loop never installs.
#
# WHY AN ISOLATED HOME
# Two measured reasons, both of which silently ruin a run otherwise:
#   1. The app takes a single-instance FileLock under $HOME/.local/share/.../runtime, so a second launch
#      against a live JVM reports "Payanam already running" instead of starting.
#   2. A fresh $HOME is what makes the run a FIRST RUN: no database, no security file. That is the flow
#      under test, and it is otherwise destroyed by the previous run's state.
#
# WHY SOFTWARE RENDERING IS NOT OPTIONAL
# Xvfb has no GLX, so Skiko dies at "Cannot create Linux GL context" without SKIKO_RENDER_API=SOFTWARE.
#
# Usage:
#   bash build-tools/scripts/desktop-loop.sh                 # launch, photograph the first screen, log
#   bash build-tools/scripts/desktop-loop.sh -b              # run the build gate first, then launch
#   bash build-tools/scripts/desktop-loop.sh -f              # genuine first run: wipe the loop's HOME AND
#                                                        # park the real app state, restored on exit
#   bash build-tools/scripts/desktop-loop.sh -d drive.sh     # drive it: a file of xdotool commands/wait
#   bash build-tools/scripts/desktop-loop.sh -w 40           # longer window wait (slow first paint)
#
# The drive file is plain shell, sourced with DISPLAY and the helper functions below already set:
#   click 640 400          # move and click, then wait for the app to settle
#   type_text 'my passphrase'
#   press Return
#   shot 03-after-passphrase
#   find_button_centre --label 'Run Backup Now'   # locate a PRIMARY-coloured control by colour
#   click_text --text 'Cancel' 2.5 cancel         # locate a control BY ITS WORDS, then click it
# Anything it writes to $RUN_DIR is kept for inspection.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

APP="$ROOT/desktop/build/compose/binaries/main/app/Payanam/bin/Payanam"
RUN_DIR="${RUN_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/payanam-loop-XXXXXX")}"
LOOP_HOME="${LOOP_HOME:-${TMPDIR:-/tmp}/payanam-loop-home}"
# The display is ALLOCATED at run time, not fixed. DISPLAY_START is only where the scan begins; DISPLAY_NUM
# is honoured as an alias for it so an existing caller that exported one keeps working, but it is now a
# starting hint that is scanned upward rather than a number the run insists on.
DISPLAY_START="${DISPLAY_START:-${DISPLAY_NUM:-90}}"
DISPLAY_TRIES="${DISPLAY_TRIES:-20}"
WAIT_SECONDS=25
FRESH=0
BUILD=0
DRIVE_FILE=""
# Defaults to 0 so the observe-only path (no -d) ends on the same check below without tripping set -u.
DRIVE_RC=0

while getopts ":bfd:w:" opt; do
    case "$opt" in
        b) BUILD=1 ;;
        f) FRESH=1 ;;
        d) DRIVE_FILE="$OPTARG" ;;
        w) WAIT_SECONDS="$OPTARG" ;;
        *) echo "unknown flag -$OPTARG" >&2; exit 2 ;;
    esac
done

say() { printf '[loop] %s\n' "$*"; }
die() { printf '[loop] FATAL: %s\n' "$*" >&2; exit 1; }

# --- the drive helpers the drive file may call ------------------------------------------------------------
# A Compose page transition is an animation, and 0.6s measured short enough that three consecutive
# clicks on the tour's Next button produced three BYTE-IDENTICAL screenshots: the input was delivered
# and the UI had not finished moving yet, so a step could miss without ever looking like it missed.
# The default settle is therefore 2.5s, and a drive file may still pass its own as the third argument
# when a step needs longer (the tour's last step does).
CLICK_SETTLE="${CLICK_SETTLE:-2.5}"

# A hash of what is currently on the root window. Used to tell "the click landed" from "the click
# went nowhere" - see click().
frame_hash() { import -window root png:- 2>/dev/null | md5sum 2>/dev/null | cut -d' ' -f1; }

# click X Y [settle-seconds] [step-name]
# Photographed before and after the tap: identical bytes either side means the pointer event produced
# no visual change at all, which is exactly the silent miss.
#
# MEASURED WHY THIS DIES INSTEAD OF WARNING (2026-09-30): a click at (256,597) and one at (255,739) on
# the current build landed on nothing - the frames across both are byte-identical - and the run went on
# to report a completed first run that had created nothing. The warning WAS printed; nothing read it. A
# miss that only prints a line is a miss the loop can still finish on, so an unverified click now ends
# the run, naming the step it ended on.
#
# Pass a step name so the failure says WHICH step went blind rather than just which pixel.
click() {
    local x="$1" y="$2" settle="${3:-$CLICK_SETTLE}" step="${4:-}"
    [[ -n "$step" ]] || step="click ($x,$y)"
    local before after
    before=$(frame_hash)
    xdotool mousemove "$x" "$y" click 1
    sleep "$settle"
    after=$(frame_hash)
    if [[ -z "$before" || -z "$after" ]]; then
        die "STEP '$step': could not hash the screen before or after the click at ($x,$y), so this step is UNVERIFIED - refusing to continue and report a green run"
    elif [[ "$before" == "$after" ]]; then
        die "STEP '$step': the click at ($x,$y) left the screen byte-identical after ${settle}s - the input did not land. Failing here on purpose: continuing is how a run that exercised nothing still reports success."
    fi
    say "click $x $y [$step]: screen changed (settle ${settle}s)"
}

# --- FIND A CONTROL IN THE FRAME INSTEAD OF GUESSING ITS PIXELS --------------------------------------------
# WHY THIS EXISTS (MEASURED 2026-09-30): the drive scripts carried pixel coordinates "taken from the
# frames of a real run". They kept passing, and then the storage-mode label fix reflowed the card and
# moved the buttons - so the same coordinates silently began clicking empty background. Hardcoded
# pixels are only correct until the next layout change, and the failure mode is invisible: no error, no
# crash, just a run that quietly stopped testing anything.
#
# The rule, so it is not a magic constant: the theme paints its PRIMARY control with one solid colour.
#
# MEASURED, NOT INHERITED (2026-09-30): the value that was here, #01796B, is NOT the value in the frame.
# Sampled off a real captured frame, the submit/continue pill is exactly srgb(0,121,107) = #00796B:
#   convert 10-both-fields-typed.png -crop 400x80+55+540 +repage -colors 8 -format %c histogram:info:
#     -> 18152: #00796B   (and the exact probe at the button centre reads srgb(0,121,107))
# #01796B is one 8-bit step off in the red channel. It kept "working" ONLY because LOOP_BUTTON_FUZZ was 8%,
# which is ~20x wider than that difference - so a constant that is wrong in the last digit could not be
# distinguished from one that is right. An off-by-one colour is a real defect here: it makes the guard
# depend on a fuzz value wide enough to hide the mistake, and someone who later tightened the fuzz to
# fight edge noise would have broken targeting for a reason that looks unrelated to this file.
#
# THE FUZZ IS NOW 0.2%, and the number is MEASURED, not chosen for taste.
# Both bounds were measured with the real mask pipeline on a synthesised #00796B pill on white
# (400x300 frame, MIN_AREA 400), because -fuzz's units are not obvious and arguing them is how a value
# like the old one survives:
#
#   fuzz  0.4%  0.39%  0.3%  0.2%  0.1%  0%
#   #00796B (right)  11837  11837  11837  11837  11837  11829
#   #01796B (1 step off) 11837 11837  11837  NONE  NONE  NONE
#
# READ THAT TABLE. Three things it says that the reasoning above got wrong:
#  1. The rejection threshold is between 0.2% and 0.3%, NOT the 5 steps (2%) assumed when this was
#     first written. ImageMagick's -fuzz is a fraction of QuantumRange but the comparison it applies is
#     not a simple per-channel step count, so "2% = 5 of 255 steps" was arithmetic, not behaviour.
#     At 2% the wrong constant STILL MATCHED - verified, then the value was moved down until it did not.
#  2. At 0.2% the wrong constant is rejected while the right one still finds the pill at full size.
#  0.2% is therefore the tightest value that clears the job, and it sits on the measured edge rather
#     than 4 steps below it.
#  3. MOST IMPORTANT, and the reason this comment is long: a fuzz this small buys NO tolerance for a
#     pill that is RENDERED slightly off the declared colour. Measured: a pill rendered 1-3 steps off
#     (#037A6E) is found by NONE of 0.1%, 0.15%, 0.2% or 0.3%. So the "too tight, antialiasing and
#     colour management will break targeting" worry that motivated the old 8% does not apply at any
#     value worth having - the plain-interior case (which is what a Compose button is: thousands of
#     pixels at exactly the theme colour) is what MIN_AREA counts, and MIN_AREA absorbs an antialiased
#     RIM losing its outermost pixel ring without losing the blob.
#
# SO: fuzz here is a correctness gate on the CONSTANT, not a tolerance knob for the renderer. A
# near-miss must fail loudly, because the alternative - a guard whose pass depends on a fuzz wide
# enough to hide the mistake - is what let #01796B sit in this file while the code said #00796B.
# Anyone who later needs rendering tolerance should widen MIN_AREA (a real pill is ~11,800px, so a
# floor of 400 has enormous headroom) and leave this at 0.2%.
#
# MIN_AREA is what keeps antialiased edge pixels from being mistaken for a button: a stray few-pixel
# teal speck cannot reach 400px, and a real pill is thousands. It is deliberately generous rather than
# tuned to one button's size.
LOOP_PRIMARY_COLOR="${LOOP_PRIMARY_COLOR:-#00796B}"
LOOP_BUTTON_MIN_AREA="${LOOP_BUTTON_MIN_AREA:-400}"
LOOP_BUTTON_FUZZ="${LOOP_BUTTON_FUZZ:-0.2}"

# find_button_centre --label TEXT [--band Y1-Y2] [--color HEX] [--min-area N]
# Prints "<x> <y>" on stdout - the centre of the largest blob of the primary colour, in ROOT window
# coordinates (the frame is the whole root window, so the blob's own offset is already a screen point).
# All narration goes to stderr, because stdout here is the return value.
#
# --band is a HINT, not the answer: it narrows where to look, and when the control is not there the
# helper says so out loud and falls back to the whole frame rather than reporting the hint's midpoint
# as if it had found something. That disagreement is the signal a layout moved.
find_button_centre() {
    local label="" band="" color="$LOOP_PRIMARY_COLOR" min_area="$LOOP_BUTTON_MIN_AREA"
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --label)    label="$2"; shift 2 ;;
            --band)     band="$2"; shift 2 ;;
            --color)    color="$2"; shift 2 ;;
            --min-area) min_area="$2"; shift 2 ;;
            *) say "find_button_centre: unknown option '$1'" >&2; return 2 ;;
        esac
    done
    [[ -n "$label" ]] || { say "find_button_centre: --label is required so a failure can name the step" >&2; return 2; }

    local frame mask cc w h y0 y1 ch best
    frame=$(mktemp "${TMPDIR:-/tmp}/payanam-find-XXXXXX.png")
    mask=$(mktemp "${TMPDIR:-/tmp}/payanam-mask-XXXXXX.png")
    cc=$(mktemp "${TMPDIR:-/tmp}/payanam-cc-XXXXXX.txt")
    # shellcheck disable=SC2064
    trap "rm -f '$frame' '$mask' '$cc'" RETURN

    import -window root "$frame" 2>/dev/null || { say "[$label] could not photograph the screen" >&2; return 2; }
    w=$(identify -format %w "$frame" 2>/dev/null) || return 2
    h=$(identify -format %h "$frame" 2>/dev/null) || return 2
    [[ -n "$w" && -n "$h" ]] || { say "[$label] could not read the frame's size" >&2; return 2; }

    # find_largest_blob MIN_Y MAX_Y USE_BAND(0|1)  ->  "<area> <w> <h> <x> <y>" or empty
    # The band is applied by cropping the FRAME first and the crop offset is added back afterwards, so
    # every coordinate reported is a root-window coordinate and the caller never has to know a crop
    # happened.
    #
    # USE_BAND is an explicit argument rather than the function reading the global `band`: when the band
    # comes back empty the caller must be able to scan the WHOLE frame, and a function that re-reads the
    # global band re-runs the identical crop and returns empty again - so the fallback silently never
    # fell back, and a moved layout was reported as a missing control.
    find_largest_blob() {
        local lo="$1" hi="$2" use_band="$3"
        local x0=0 x1=$((h-1)) chh
        if (( use_band )); then
            x0="$lo"; x1="$hi"
            (( x0 < 0 )) && x0=0
            (( x1 > h-1 )) && x1=$((h-1))
            (( x1 > x0 )) || return 1
        fi
        chh=$((x1 - x0 + 1))
        # The mask has to be built in this order, and the order is load-bearing:
        #   1. `+opaque colour` blacks out everything that is NOT the colour,
        #   2. `-opaque colour` paints the surviving colour white.
        # Filling the matches white FIRST and then `+opaque white` is the obvious-looking version and it
        # is wrong: +opaque fills pixels MATCHING white, so the white card behind the button survives the
        # mask and becomes the largest blob - the helper then reports the card's centre as "the control"
        # and taps empty background. That was measured here: a 311x56 teal pill against a 413x821 white
        # card returned the card, 338969px vs the pill's 17000px.
        #
        # -fuzz is re-stated on each operator rather than once: it is a setting, not a modifier, so a
        # single -fuzz 8% would leave the +opaque at 8% too and swallow near-colours from the frame.
        # MEASURED: the verbose component table is written to STDOUT, not stderr. Capturing it with
        # `2>"$cc" >/dev/null` - the obvious way - yields an empty file and the helper then reports "no
        # control found anywhere" on every single frame, which is indistinguishable from a real miss.
        # That is the same class of bug this whole file exists to remove, so it is called out here.
        # stdout is captured and stderr is left flowing to the run's own output for real warnings.
        convert "$frame" -crop "${w}x${chh}+0+${x0}" +repage -alpha off \
            -fuzz "$LOOP_BUTTON_FUZZ%" -fill black +opaque "$color" \
            -fuzz "$LOOP_BUTTON_FUZZ%" -fill white -opaque "$color" \
            -define connected-components:verbose=true -connected-components 8 null: >"$cc"
        # An empty table means the toolchain produced nothing at all, which is NOT the same as "no
        # control here". Reported separately so a broken matcher can never masquerade as a blank frame.
        if [[ ! -s "$cc" ]]; then
            say "[$label] the component scan produced no output at all - the matcher itself failed, so this is NOT evidence that the control is missing" >&2
            return 2
        fi
        # The band offset is passed IN to awk with -v. Referring to the shell's x0 inside the awk program
        # reads an UNSET awk variable, i.e. 0 - so a banded scan silently returned the control's
        # crop-local y (46) instead of its root y (566) and the loop would have clicked 500px above the
        # button. A band is only a filter; the answer it produces must still be a root-window point.
        awk -v min="$min_area" -v yoff="$x0" '
            /^[[:space:]]*[0-9]+:/ {
                id = $1; sub(":", "", id)
                if (id + 0 < 1) next                       # object 0 is the background
                if ($4 + 0 < min) next                     # too small to be a control
                split($2, g, "+"); split(g[1], wh, "x")
                printf "%d %s %s %d %d\n", $4 + 0, wh[1], wh[2], g[2] + 0, g[3] + yoff
            }' "$cc" | sort -rn | head -1
    }

    # Band FIRST when one was given (it is the tighter, more specific question), whole frame only as the
    # fallback when the band comes back empty.
    best=""
    if [[ -n "$band" ]]; then
        best=$(find_largest_blob "${band%%-*}" "${band##*-}" 1)
    else
        best=$(find_largest_blob 0 $((h-1)) 0)
    fi
    if [[ -n "$band" && -z "$best" ]]; then
        say "[$label] WARNING: no $color control of >=${min_area}px inside the band y=${band}; the band and the build disagree. Scanning the whole frame so the report can name what IS there." >&2
        best=$(find_largest_blob 0 $((h-1)) 0)
    fi
    if [[ -z "$best" ]]; then
        say "[$label] no $color region of >=${min_area}px anywhere in the ${w}x${h} frame - there is no control to click, and guessing a pixel here is the bug this helper replaces" >&2
        return 1
    fi

    local area bw bh bx by cx cy
    read -r area bw bh bx by <<<"$best"
    cx=$((bx + bw / 2)); cy=$((by + bh / 2))
    say "[$label] found $color blob ${bw}x${bh}+${bx}+${by} (${area}px) -> centre (${cx},${cy})" >&2
    if [[ -n "$band" ]]; then
        if (( cy < ${band%%-*} || cy > ${band##*-} )); then
            say "[$label] WARNING: the control found is at y=${cy}, OUTSIDE the expected band y=${band}" >&2
        fi
    fi
    printf '%s %s\n' "$cx" "$cy"
    return 0
}

# click_button STEP-NAME [--band Y1-Y2] [--color HEX] [--min-area N] [--settle SECONDS]
# Find the control by its colour, then click it - so the step follows the control rather than a pixel,
# and a missing control is a loud failure instead of a tap into empty background.
click_button() {
    local label="$1"; shift
    local settle="$CLICK_SETTLE" found
    local args=()
    # Every option here takes a value, so an option with nothing after it is a caller bug - caught here
    # with a clear name rather than by `set -u` aborting on "$2: unbound variable" halfway down the run.
    while [[ $# -gt 0 ]]; do
        [[ $# -ge 2 ]] || die "click_button '$label': option '$1' was given with no value"
        case "$1" in
            --settle) settle="$2"; shift 2 ;;
            *) args+=("$1" "$2"); shift 2 ;;
        esac
    done
    # The exit status must be read DIRECTLY, not through a command substitution.
    #
    # MEASURED BUG THIS FIXES (2026-09-30): `if ! found=$(find_button_centre ...)` with a command
    # substitution that PRINTS NOTHING makes the assignment's status the substitution's status - which
    # looked right - but the helper's own failure return was lost, so the run went on to
    # `click $found ...` with found="" and dispatched a literal click to "(4, submit-passphrase)" and
    # still reported success. The run below the mutation proves a guard that cannot fail is worthless:
    # that mutation made the run GREENER, not redder.
    #
    # So: capture the output, then capture the return code separately and test THAT.
    found=$(find_button_centre --label "$label" ${args[@]+"${args[@]}"}); rc=$?
    if (( rc != 0 )) || [[ -z "$found" ]]; then
        die "STEP '$label': could not find the control to click (see the [loop] lines above for why). No click was made - stopping here rather than tapping a guessed pixel."
    fi
    [[ "$found" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]] \
        || die "STEP '$label': the matcher returned '$found', which is not a single x y point - refusing to click it"
    # shellcheck disable=SC2086
    click $found "$settle" "$label"
}

# --- FIND A CONTROL BY ITS LABEL, INSTEAD OF ITS COLOUR ----------------------------------------------------
# WHY THIS IS NOT A REPLACEMENT FOR find_button_centre, BUT A SECOND ANSWER TO THE SAME QUESTION
# find_button_centre finds the PRIMARY-coloured pill. It cannot see a secondary button, a plain text
# link, a menu row, a list item, a tab, or a heading - all of which the drive may need to touch, and all
# of which are exactly the controls that have no fixed pixel to hardcode. So this asks a different
# question: not "where is the teal" but "where are these WORDS".
#
# The same failure mode kills both, which is why the contract is identical: stdout is ONLY "<x> <y>",
# every human-readable line goes to stderr, and a miss is a LOUD one. A finder that guesses is worse
# than no finder, because the click lands, the run looks green, and nothing was exercised.
#
# find_text_centre --text TEXT [--region X1,Y1,X2,Y2] [--scale N] [--lang LANG]
# Prints "<x> <y>" on stdout - the centre of the matched words, in ROOT window coordinates.
#
# --region X1,Y1,X2,Y2 is a rectangle in root coordinates whose far corner is EXCLUSIVE, i.e. the crop
#   is (X2-X1)x(Y2-Y1) at +X1+Y1, the same convention as ImageMagick's -crop WxH+X+Y. It is a HINT in
#   the useful sense and a hard filter in the honest one: it shrinks the pixels OCR has to read, which
#   makes small text much more likely to be recognised, and it does NOT make the helper fall back to the
#   whole frame when the text is missing. Silently searching somewhere else is how a rename turns into a
#   run that reports a button it never found.
#
# --scale N upscales before OCR. DEFAULT 2, AND THE DEFAULT IS LOAD-BEARING (measured here, on a 9px
#   DejaVu rendering of "Run Backup Now"): at scale 1 tesseract returns NO words at all for that line and
#   the helper reports "no match"; at scale 2 and 3 it returns the full phrase. Small-font controls -
#   secondary text, list rows, tooltips - are invisible at 1x. -filter point is used deliberately: for
#   integer magnification of rendered glyphs it reproduces each pixel as a clean NxN block, and measured
#   against Lanczos/Mitchell/Cubic it gave the same match and the same coordinates.
#
# --lang eng IS THE DEFAULT BECAUSE IT IS THE ONLY ONE THAT WORKS HERE. MEASURED on this host:
#   tesseract 5.5.0 with tessdata at /usr/share/tesseract-ocr/5/tessdata/ containing eng.traineddata and
#   osd.traineddata and NOTHING else. No tesseract-ocr-tam, so a --lang tam run does not degrade to
#   English - it fails outright.
#   READ THIS BEFORE YOU TRUST TEXT SEARCH OVER THE WHOLE APP: the first-run tour renders in TAMIL, so
#   click_text CANNOT reach the tour. A drive still has to cross the first run by the coordinates the
#   existing drives already use, and text search is for the English screens past it. Do not assume
#   "the helper found no text" means "the app has no text there" - it can mean the script is in a script
#   this install cannot read. The fix is a language pack, not a different coordinate.
find_text_centre() {
    local text="" region="" scale=2 lang=eng
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --text)   [[ $# -ge 2 ]] || { say "find_text_centre: --text was given with no value" >&2; return 2; }
                      text="$2"; shift 2 ;;
            --region) [[ $# -ge 2 ]] || { say "find_text_centre: --region was given with no value" >&2; return 2; }
                      region="$2"; shift 2 ;;
            --scale)  [[ $# -ge 2 ]] || { say "find_text_centre: --scale was given with no value" >&2; return 2; }
                      scale="$2"; shift 2 ;;
            --lang)   [[ $# -ge 2 ]] || { say "find_text_centre: --lang was given with no value" >&2; return 2; }
                      lang="$2"; shift 2 ;;
            *) say "find_text_centre: unknown option '$1'" >&2; return 2 ;;
        esac
    done
    if [[ -z "$text" ]]; then
        say "find_text_centre: --text is required so a failure can name what it searched for" >&2
        return 2
    fi
    [[ "$scale" =~ ^[0-9]+$ ]] && (( scale >= 1 )) || { say "find_text_centre: --scale must be an integer >= 1, got '$scale'" >&2; return 2; }

    local frame work tsv prog err w h
    frame=$(mktemp "${TMPDIR:-/tmp}/payanam-text-XXXXXX.png")
    work=$(mktemp "${TMPDIR:-/tmp}/payanam-text-ocr-XXXXXX.png")
    tsv=$(mktemp "${TMPDIR:-/tmp}/payanam-text-XXXXXX.tsv")
    err=$(mktemp "${TMPDIR:-/tmp}/payanam-text-XXXXXX.err")
    prog=$(mktemp "${TMPDIR:-/tmp}/payanam-text-XXXXXX.awk")
    # shellcheck disable=SC2064
    trap "rm -f '$frame' '$work' '$tsv' '$err' '$prog'" RETURN

    import -window root "$frame" 2>/dev/null || { say "[$text] could not photograph the screen" >&2; return 2; }
    w=$(identify -format %w "$frame" 2>/dev/null) || return 2
    h=$(identify -format %h "$frame" 2>/dev/null) || return 2
    [[ -n "$w" && -n "$h" ]] || { say "[$text] could not read the frame's size" >&2; return 2; }

    # Where the crop starts in ROOT coordinates. Without a region this is the whole frame at 0,0.
    local cx=0 cy=0 cw=$w ch=$h
    if [[ -n "$region" ]]; then
        local rx1 ry1 rx2 ry2
        if [[ ! "$region" =~ ^([0-9]+),([0-9]+),([0-9]+),([0-9]+)$ ]]; then
            say "[$text] --region must be X1,Y1,X2,Y2 (four non-negative integers), got '$region'" >&2
            return 2
        fi
        rx1="${BASH_REMATCH[1]}" ry1="${BASH_REMATCH[2]}" rx2="${BASH_REMATCH[3]}" ry2="${BASH_REMATCH[4]}"
        # Clamp to the frame. A drive that hardcodes a band from an older build must not be able to
        # make ImageMagick fail with "geometry out of bounds", which would read as a toolchain problem
        # rather than as the layout having moved.
        (( rx1 >= w )) && { say "[$text] region x1=$rx1 is outside the ${w}x${h} frame" >&2; return 2; }
        (( ry1 >= h )) && { say "[$text] region y1=$ry1 is outside the ${w}x${h} frame" >&2; return 2; }
        (( rx2 > w )) && rx2=$w
        (( ry2 > h )) && ry2=$h
        if (( rx2 - rx1 < 1 || ry2 - ry1 < 1 )); then
            say "[$text] region $region is empty once clamped to the ${w}x${h} frame" >&2
            return 2
        fi
        cx=$rx1; cy=$ry1; cw=$((rx2 - rx1)); ch=$((ry2 - ry1))
    fi

    # Crop THEN upscale, in that order: magnifying the whole 1280x900 root window to catch a 9px label
    # costs several times the pixels for no gain, while magnifying only the region keeps the glyphs
    # large AND the frame small.
    convert "$frame" -crop "${cw}x${ch}+${cx}+${cy}" +repage -filter point -resize "$((scale * 100))%" "$work" \
        || { say "[$text] could not crop/scale the frame to ${cw}x${ch}+${cx}+${cy} at ${scale}x" >&2; return 2; }

    # tesseract's output base is `stdout` and the TSV lands on stdout, so the parse and the diagnostics
    # are separated into two files on purpose. Capturing both into one stream is the same mistake this
    # file already documents for connected-components (an empty file then reads as "nothing is there").
    tesseract "$work" stdout --dpi 96 -l "$lang" tsv >"$tsv" 2>"$err"
    local tesseract_rc=$?
    # --dpi 96 IS NOT COSMETIC. Without it tesseract prints "Estimating resolution as N" and spends the
    # time guessing; the measured cost on the test images was 0.32s against 0.18s with byte-IDENTICAL
    # recognition (same md5 over the TSV), so it is free accuracy and less noise. Never drop it for
    # being "redundant".
    if (( tesseract_rc != 0 )); then
        say "[$text] tesseract failed (rc $tesseract_rc) reading '$text' --lang $lang. On this host only 'eng' and 'osd' are installed, so a missing language pack is by far the likeliest cause:" >&2
        sed 's/^/    /' "$err" >&2
        return 2
    fi
    # An empty TSV is NOT the same as "the text is not there". It means OCR produced no words at all -
    # a blank frame, a wrong region, or a language that cannot read this script. Reporting it as a
    # missing label would be a false negative that reads exactly like the opposite of a real miss.
    [[ -s "$tsv" ]] || {
        say "[$text] tesseract produced an EMPTY TSV for the ${cw}x${ch} region at ${scale}x with --lang $lang - that is the OCR failing to read the frame, NOT evidence the text is absent" >&2
        return 2
    }

    # The matcher arrives as a QUOTED heredoc so that nothing in the awk source is expanded: $1, $7,
    # $12, the backslashes in norm()'s character class and the embedded single quotes all have to reach
    # awk byte for byte.
    #
    # The needle goes in through the ENVIRONMENT, never `awk -v`. -v runs its argument through awk's own
    # escape processing first, so a needle containing a backslash, a quote or a $ is rewritten before
    # awk ever compares it - the search then silently fails to match text that is plainly on screen.
    # ENVIRON[] hands it over unchanged. The scale and the crop offset travel the same way, and the
    # scaled->root conversion (divide by SCALE, add the offset) happens INSIDE the awk, once per word,
    # so every matching tier sees the same root-window points and this function must NOT divide again.
    cat >"$prog" <<'LOOP_OCR_MATCHER_AWK'
# Matcher for find_text_centre: tesseract `tsv` on stdin, "<x> <y> <tier> <matches> <words>" or nothing.
# It reports WHERE text was found; it never proposes a coordinate for text that is not there.
function norm(s) {
    s = tolower(s)
    gsub(/^[!"#$%&'()*+,\-.\/:;<=>?@\[\\\]\^_`{|}~[:space:]]+/, "", s)
    gsub(/[!\"#$%&'()*+,\-.\/:;<=>?@\[\\\]\^_`{|}~[:space:]]+$/, "", s)
    return s
}
# Tier 1, the strongest: the needle's words appear CONSECUTIVELY on one OCR text line.
function try_exact(   i, j, hit) {
    for (i = 1; i + nneedle - 1 <= nw; i++) {
        hit = 1
        for (j = 0; j < nneedle; j++)
            if (LWORD[i + j] != NEEDLE[j + 1]) { hit = 0; break }
        if (hit) { consider(1, i, nneedle); return }
    }
}
# Tier 2: the needle's words appear in order with other words between them (a button label that OCR
# split, or a label with an icon or a field value inside it).
function try_gapped(   i, j, k) {
    for (i = 1; i <= nw; i++) {
        if (LWORD[i] != NEEDLE[1]) continue
        j = 2; k = i + 1
        while (j <= nneedle && k <= nw) {
            if (LWORD[k] == NEEDLE[j]) j++
            k++
        }
        if (j > nneedle) { consider(2, i, nneedle); return }
    }
}
# Tier 3, the weakest: the whole normalised needle occurs as a substring of the line, which is what
# catches a needle OCR has punctured mid-word ("Run-Backup Now" vs "Run Backup Now").
function try_phrase(   i, joined) {
    joined = ""
    for (i = 1; i <= nw; i++) joined = joined (i > 1 ? " " : "") LWORD[i]
    if (index(joined, PHRASE) > 0) consider(3, 1, nw)
}
# consider TIER FIRST COUNT -> union the boxes of the matched words into this line's best run.
# Lowest tier wins; within a tier the LARGEST box wins, so a duplicate word resolves to the bigger one
# rather than to whichever the OCR happened to list first.
function consider(t, first, count,   i, x0, x1, y0, y1, a) {
    x0 = 1e9; x1 = -1; y0 = 1e9; y1 = -1
    for (i = first; i < first + count; i++) {
        if (LCX[i] < x0) x0 = LCX[i]
        if (LCX[i] + LW[i] > x1) x1 = LCX[i] + LW[i]
        if (LCY[i] < y0) y0 = LCY[i]
        if (LCY[i] + LH[i] > y1) y1 = LCY[i] + LH[i]
    }
    a = (x1 - x0 > 0 ? x1 - x0 : 1) * (y1 - y0 > 0 ? y1 - y0 : 1)
    if (lbesttier == 0 || t < lbesttier || (t == lbesttier && a > larea)) {
        lbesttier = t; larea = a; lbx0 = x0; lbx1 = x1; lby0 = y0; lby1 = y1
    }
    lmatches++
}
function flush_line() {
    if (nw > 0) {
        try_exact(); if (!lbesttier) try_gapped(); if (!lbesttier) try_phrase()
        if (lbesttier) {
            a = (lbx1 - lbx0) * (lby1 - lby0)
            if (btier == 0 || lbesttier < btier || (lbesttier == btier && a > barea)) {
                btier = lbesttier; barea = a
                bx0 = lbx0; bx1 = lbx1; by0 = lby0; by1 = lby1
                bmatches = lmatches
            }
        }
    }
    delete LWORD; delete LCX; delete LCY; delete LW; delete LH
    nw = 0; lbesttier = 0; larea = 0; lmatches = 0
}
BEGIN {
    FS = "\t"                      # tesseract tsv is tab separated; without this $1 is "level"
    PHRASE = norm(ENVIRON["LOOP_TEXT_NEEDLE"])
    if (PHRASE == "") { print "find_text_centre: empty needle" > "/dev/stderr"; fatal = 1 }
    nneedle = split(PHRASE, NEEDLE, " ")
    XOFF = ENVIRON["LOOP_TEXT_XOFF"] + 0
    YOFF = ENVIRON["LOOP_TEXT_YOFF"] + 0
    SCALE = ENVIRON["LOOP_TEXT_SCALE"] + 0
    if (SCALE < 1) SCALE = 1
    nw = 0; btier = 0; barea = 0; bmatches = 0
    lbesttier = 0; larea = 0; lmatches = 0
}
$1 == 5 && $12 != "" && $11 + 0 > 0 {
    word = norm($12)
    if (word != "") {
        key = $2 SUBSEP $3 SUBSEP $4 SUBSEP $5
        if (nw > 0 && key != linekey) flush_line()
        linekey = key
        nw++
        LWORD[nw] = word
        # Scaled -> root conversion happens here once, so every tier sees the same points.
        LCX[nw] = XOFF + int($7 / SCALE)
        LCY[nw] = YOFF + int($8 / SCALE)
        LW[nw] = int($9 / SCALE)
        LH[nw] = int($10 / SCALE)
    }
}
END {
    if (nw > 0) flush_line()
    if (fatal) exit 3
    if (!btier) exit 1        # no match: EMPTY stdout and a non-zero code, so a caller cannot mistake
    printf "%d %d %d %d %d\n", int((bx0 + bx1) / 2), int((by0 + by1) / 2), btier, bmatches, nneedle
}
LOOP_OCR_MATCHER_AWK

    # status is read DIRECTLY, not inferred from whether anything was printed - see the measured bug
    # documented in click_button: a command substitution that prints nothing can hide the helper's own
    # failure, and the run then goes on to act on an empty result.
    local hit rc
    hit=$(LOOP_TEXT_NEEDLE="$text" LOOP_TEXT_XOFF="$cx" LOOP_TEXT_YOFF="$cy" LOOP_TEXT_SCALE="$scale" \
        awk -f "$prog" "$tsv")
    rc=$?
    case "$rc" in
        0) ;;
        1) say "[$text] no text matched in the ${w}x${h} frame (searched region ${cx},${cy} ${cw}x${ch} at ${scale}x, --lang $lang). Not printing a guess - a coordinate invented here is the bug this helper replaces. If this screen renders in Tamil, see the --lang note on find_text_centre: only 'eng' and 'osd' are installed." >&2
           return 1 ;;
        3) say "[$text] the needle was empty after normalisation - nothing to search for" >&2
           return 2 ;;
        *) say "[$text] the matcher failed (rc $rc)" >&2
           return 2 ;;
    esac
    [[ -n "$hit" ]] || { say "[$text] the matcher reported a match but printed nothing - treating that as a failure" >&2; return 2; }

    local mx my tier matches words
    read -r mx my tier matches words <<<"$hit"
    [[ "$mx" =~ ^[0-9]+$ && "$my" =~ ^[0-9]+$ ]] \
        || { say "[$text] the matcher returned '$hit', which is not an x y point" >&2; return 2; }
    # Tier names the reason the match was found, which is the first thing to read when a finder lands on
    # the wrong control: 1 = the words are consecutive, 2 = they were in order but split, 3 = only a
    # substring of the line matched.
    local tier_name
    case "$tier" in
        1) tier_name="consecutive words" ;;
        2) tier_name="gapped words" ;;
        *) tier_name="phrase substring" ;;
    esac
    say "[$text] matched ${words} words (tier $tier = $tier_name, ${matches} match(es)) -> centre (${mx},${my}) in root coordinates" >&2
    printf '%s %s\n' "$mx" "$my"
    return 0
}

# click_text --text TEXT [settle-seconds] [step-name]
# Locate the words, then click their centre - so a drive states what it wants instead of a pixel, and a
# label that is not there is a loud failure rather than a tap into empty background.
#
# Positional settle and step-name come after --text so a drive reads as one line, matching click()'s
# shape. --settle and --step are also accepted for anyone who prefers the flag form.
click_text() {
    local text="" settle="$CLICK_SETTLE" step="" pos=0
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --text)   [[ $# -ge 2 ]] || die "click_text: --text was given with no value"
                      text="$2"; shift 2 ;;
            --settle) [[ $# -ge 2 ]] || die "click_text: --settle was given with no value"
                      settle="$2"; shift 2 ;;
            --step)   [[ $# -ge 2 ]] || die "click_text: --step was given with no value"
                      step="$2"; shift 2 ;;
            -*) say "click_text: unknown option '$1'" >&2; return 2 ;;
            *) pos=$((pos + 1))
               if   (( pos == 1 )); then settle="$1"
               elif (( pos == 2 )); then step="$1"
               else say "click_text: unexpected extra argument '$1' (expected: --text TEXT [settle] [step])" >&2; return 2
               fi
               shift ;;
        esac
    done
    [[ -n "$text" ]] || die "click_text: --text is required so a failure can name what it searched for"
    [[ -n "$step" ]] || step="click_text \"$text\""

    local found rc
    found=$(find_text_centre --text "$text"); rc=$?
    if (( rc != 0 )) || [[ -z "$found" ]]; then
        die "STEP '$step': could not find the text \"$text\" to click (see the [loop] lines above for why). No click was made - stopping here rather than tapping a guessed pixel."
    fi
    [[ "$found" =~ ^[0-9]+[[:space:]]+[0-9]+$ ]] \
        || die "STEP '$step': the text matcher returned '$found', which is not a single x y point - refusing to click it"
    # shellcheck disable=SC2086
    click $found "$settle" "$step"
}
type_text() { xdotool type --delay 25 "$1"; sleep 0.4; }
press() { xdotool key "$1"; sleep 0.6; }
shot() { import -window root "$RUN_DIR/$1.png" && say "shot: $RUN_DIR/$1.png"; }

# --- the REAL app state, which -f has to be able to move out of the way ---------------------------------
# MEASURED: jpackage's launcher resolves the REAL user home, so HOME=$LOOP_HOME does NOT move the app's
# data. The SQLCipher database, the passphrase verifier and the single-instance lock all live in the real
# ~/.local/share/Payanam. So wiping the loop's HOME left the previous run's database in place, the app took
# the boot-issue path (NON_REPAIRABLE_GENERIC, databaseArtifactsExist=true) and a "first run" was never a
# first run at all. A -f that cannot see the real state is a lie.
#
# So -f MOVES the real state aside to a timestamped sibling, and the EXIT/INT/TERM trap puts it back on
# every exit path - success, failure, interrupt, or the script being killed. Nothing is ever deleted: the
# user's own directory is moved back byte-for-byte, and whatever this run created is kept in a sibling
# .loop-run- directory and named in the output.
#
# DECISION ON ~/.config/Payanam: it gets the same treatment. It does not exist on this machine today (so
# the move is a no-op carrying no risk), and the single-instance lock - the one thing that can make a run
# report "Payanam already running" instead of starting - has already been measured to live under the
# .local/share data dir's runtime/ subdir, which this move covers. Handling .config as well costs a few
# lines and closes the gap if a future build ever writes runtime state or preferences there.
STATE_STAMP="$(date +%Y%m%d-%H%M%S)"
STATE_MANIFEST=""
STATE_MOVED=0
REAL_STATE_PATHS=(
    "$HOME/.local/share/Payanam"
    "$HOME/.config/Payanam"
)

move_state_aside() {
    local path="$1" parent base backup
    if [[ ! -e "$path" ]]; then
        say "no real state at $path - nothing to move"
        return 0
    fi
    parent="$(dirname "$path")"
    base="$(basename "$path")"
    backup="$parent/$base.loop-backup-$STATE_STAMP"
    [[ -e "$backup" ]] && die "$backup already exists - refusing to touch either directory"
    mv -- "$path" "$backup" || die "could not move $path aside - refusing to continue, nothing was deleted"
    printf '%s\t%s\t%s\n' "$path" "$backup" "$base" >>"$STATE_MANIFEST"
    STATE_MOVED=1
    say "MOVED aside: $path  ->  $backup"
}

restore_state() {
    (( STATE_MOVED )) || return 0
    [[ -s "${STATE_MANIFEST:-}" ]] || return 0
    local path backup base leftover newest
    while IFS=$'\t' read -r path backup base; do
        [[ -n "${path:-}" && -e "$backup" ]] || continue
        if [[ -e "$path" ]]; then
            # This run recreated the path. Park it beside the backup - never delete it - so the user's
            # original can go back to where it was.
            leftover="$(dirname "$backup")/$base.loop-run-$STATE_STAMP"
            mv -- "$path" "$leftover" 2>/dev/null
            if [[ -e "$path" ]]; then
                say "WARNING: could not park this run's state at $leftover - original at $backup NOT restored"
                continue
            fi
            say "kept this run's own state (not deleted): $leftover"
            # The log is the evidence a run is read from afterwards and the restored directory is the
            # user's own, so a copy goes to the run dir before the original goes back.
            newest=$(find "$leftover/logs" -maxdepth 1 -name '*.log' -printf '%T@ %p\n' 2>/dev/null \
                | sort -rn | head -1 | cut -d' ' -f2-)
            if [[ -n "$newest" ]]; then
                cp -- "$newest" "$RUN_DIR/app.log" 2>/dev/null \
                    && say "copy of this run's app log: $RUN_DIR/app.log"
            fi
        fi
        mv -- "$backup" "$path" 2>/dev/null \
            && say "RESTORED: $backup  ->  $path" \
            || say "WARNING: FAILED to restore $backup back to $path - it is safe at $backup"
    done <"$STATE_MANIFEST"
    return 0
}

# Kill first, then restore: a still-running app would write into the directory being moved, and the
# trap is what puts the user's state back when a run is interrupted or dies - so it cannot be optional.
cleanup() {
    trap - EXIT INT TERM
    if [[ -n "${APP_PID:-}" ]]; then
        kill "$APP_PID" 2>/dev/null
        sleep 1
        kill -0 "$APP_PID" 2>/dev/null && kill -9 "$APP_PID" 2>/dev/null
    fi
    # Every Xvfb this run started - not just the accepted one. An attempt that was started and then
    # found unusable has released its lock but may still be alive, and one live Xvfb holding a display
    # makes the NEXT parallel run skip it. Released here, through the same trap as the state, so an
    # interrupted run (Ctrl-C, kill) releases the display exactly like a clean exit does.
    local pid
    for pid in ${XVFB_PIDS[@]+"${XVFB_PIDS[@]}"}; do
        [[ -n "$pid" ]] || continue
        kill "$pid" 2>/dev/null
    done
    sleep 0.5
    for pid in ${XVFB_PIDS[@]+"${XVFB_PIDS[@]}"}; do
        [[ -n "$pid" ]] || continue
        kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
    done
    # The lock of the display this run was on, so the display is immediately available to the next run
    # rather than looking taken until X cleans up after a killed server.
    [[ -n "${DISPLAY_NUM:-}" ]] && rm -f "${TMPDIR:-/tmp}/.X${DISPLAY_NUM}-lock" 2>/dev/null
    # Released BEFORE the state goes back, and before this function returns, so the next queued run
    # starts against a machine that is already back in its pre-run shape.
    release_run_lock
    restore_state
    return 0
}
trap cleanup EXIT INT TERM

# --- preconditions, checked so the failure names the missing thing ----------------------------------------
command -v Xvfb >/dev/null || die "Xvfb is not installed (apt install xvfb)"
command -v import >/dev/null || die "imagemagick's 'import' is not installed (apt install imagemagick)"
# xdotool is only needed to DRIVE the app. Launch, wait, photograph and read-the-log all work without
# it, so it is required per-use rather than upfront: an observe-only run on a machine without it still
# produces the screenshot and the app's own log, which is where a crash shows up.
if [[ -n "$DRIVE_FILE" ]]; then
    command -v xdotool >/dev/null || die "xdotool is not installed (apt install xdotool) and -d was given, so there is nothing to drive the app with"
fi
# tesseract is only needed to READ A LABEL - find_text_centre and click_text. Like xdotool above it is
# required per-use rather than upfront, so a drive that only hardcodes pixels still runs on a machine
# without it; it dies, by name, on the first click_text instead.
if [[ -n "$DRIVE_FILE" ]]; then
    command -v tesseract >/dev/null || die "tesseract is not installed (apt install tesseract-ocr)"
fi
command -v flock >/dev/null || die "flock is not installed (apt install util-linux) - without it a second run would collide with the first instead of waiting for it"

# --- ONE LOOP RUN AT A TIME ------------------------------------------------------------------------------
# WHY THIS IS A LOCK AND NOT THE DISPLAY (MEASURED 2026-09-30, two lanes at once):
# The display collision was the SMALLER of the two problems. Allocating a display per run does not make
# two runs safe, because the things the loop cannot virtualise are shared:
#   1. THE APP IS SINGLE-INSTANCE. It takes a lock under the resolved data dir and refuses a second
#      process, and jpackage resolves the REAL user home for that path even when HOME is overridden. So
#      run B's app dies on start while run A keeps driving - and B then photographs run A's window, which
#      is worse than no run at all: B's frames and B's log lines describe the wrong app.
#   2. `-f` RUNS SHARE THE REAL STATE. Both park $HOME/.local/share/Payanam to a timestamped sibling and
#      both put it back from the EXIT trap. Two -f runs overlap: B's "move aside" picks up the state A
#      re-created, and whichever trap fires second moves the user's directory again. Each run restores
#      correctly only in isolation, so the PREMISE is corrupted rather than the output.
#
# So: code work stays parallel, and the app-level verification QUEUES. A waiting run is the correct
# outcome - it is one honest "I am second" instead of a collided run that reports a false result.
#
# Held for the WHOLE run (lock -> trap -> state parking -> app -> drive -> restore), and taken BEFORE the
# state is moved aside, because the window in which two runs could both park the same directory is
# exactly the window the lock has to close.
#
# The lock is a global path, not one under $LOOP_HOME: two runs with different LOOP_HOMEs are still two
# processes that both want the single-instance lock and both want the real state. Keyed by nothing on
# purpose - one loop per machine, which is what the app allows anyway.
LOOP_LOCK_FILE="${LOOP_LOCK_FILE:-${TMPDIR:-/tmp}/payanam-loop.runlock}"
LOOP_LOCK_WAIT="${LOOP_LOCK_WAIT:-900}"   # seconds; bounded on purpose, see the timeout below
RUN_LOCK_FD=""

# holder_line NAME -- what a waiting run reads to name who it is waiting for.
holder_line() { [[ -s "$LOOP_LOCK_FILE" ]] && tr '\n' ' ' <"$LOOP_LOCK_FILE" || printf 'another run (no holder info yet)'; }

acquire_run_lock() {
    # Append mode, NOT `>`: opening with `>` truncates, which would erase the CURRENT holder's identity
    # before this run has any right to write anything into that file.
    exec {RUN_LOCK_FD}>>"$LOOP_LOCK_FILE" || die "could not open the run lock $LOOP_LOCK_FILE"
    local waited=0
    if flock -n "$RUN_LOCK_FD"; then
        : # free
    else
        say "another loop run holds $LOOP_LOCK_FILE: $(holder_line)"
        say "waiting up to ${LOOP_LOCK_WAIT}s for it to finish - the app is single-instance and -f runs share the real state, so two at once would report each other's result"
        while ! flock -n "$RUN_LOCK_FD"; do
            # A holder that died without releasing leaves the fd closed and the lock free, so the poll
            # above recovers by itself. A holder that is genuinely alive is not interrupted here.
            if (( waited >= LOOP_LOCK_WAIT )); then
                die "still waiting after ${LOOP_LOCK_WAIT}s for the run holding $LOOP_LOCK_FILE ($(holder_line)). Not proceeding: a second run now would collide on the single-instance lock and the real state, and its result would be a lie. Raise LOOP_LOCK_WAIT, or find and stop the holder if it is not running."
            fi
            (( waited % 30 == 0 )) && say "still queued: ${waited}s of ${LOOP_LOCK_WAIT}s, holder = $(holder_line)"
            sleep 5
            waited=$((waited + 5))
        done
        say "the previous run released the lock after ${waited}s - proceeding"
    fi
    # Only written while the lock is HELD, so it always describes the real holder.
    printf 'pid %s on %s, run dir %s, since %s\n' "$$" "$(hostname)" "$RUN_DIR" "$(date '+%H:%M:%S')" >"$LOOP_LOCK_FILE"
    say "run lock held: $LOOP_LOCK_FILE"
}

# Release before restoring the state: the lock exists to serialise the runs, and the state move is the
# last thing the previous run does. Closing the fd is enough - the OS drops an flock when the fd closes,
# including on an exit this script never sees.
release_run_lock() {
    [[ -n "$RUN_LOCK_FD" ]] || return 0
    flock -u "$RUN_LOCK_FD" 2>/dev/null
    exec {RUN_LOCK_FD}>&- 2>/dev/null
    RUN_LOCK_FD=""
    return 0
}

acquire_run_lock

if (( BUILD )); then
    say "building (full profile: detekt, tests, coverage, package)"
    bash "$SCRIPT_DIR/build-linux.sh" --profile full || die "the build gate failed - nothing to launch"
fi
[[ -x "$APP" ]] || die "no built app at $APP - run with -b, or bash build-tools/scripts/build-linux.sh"

# --- a run that starts from nothing ----------------------------------------------------------------------
if (( FRESH )); then
    rm -rf "$LOOP_HOME"
    say "wiped $LOOP_HOME"
    if pgrep -x Payanam >/dev/null 2>&1; then
        say "WARNING: a Payanam process is already running and can recreate the state being moved aside - run 'pkill -x Payanam' first"
    fi
    STATE_MANIFEST="$RUN_DIR/real-state-moved.tsv"
    : >"$STATE_MANIFEST"
    for state_path in "${REAL_STATE_PATHS[@]}"; do
        move_state_aside "$state_path"
    done
    say "real app state is parked in a timestamped sibling and will be put back by the EXIT trap - nothing is deleted"
fi
mkdir -p "$LOOP_HOME"

say "run dir:  $RUN_DIR"
say "app home: $LOOP_HOME"

# --- the display and the app -----------------------------------------------------------------------------
# The display is allocated per run, but it is NOT what makes concurrent runs safe: the app is
# single-instance and -f runs share the real state, so the run lock above serialises whole runs. What
# the display allocation still buys is a run that does not collide with an unrelated X server, and a
# run that can start while some OTHER display is busy - which is why it stays, and why a run that
# cannot get a display says which candidates it tried.
#
# Allocation, not choice. Three rules, each from a way this can go wrong:
#   1. A candidate is skipped when its LOCK file exists AND a live server answers on it - that is
#      somebody else's display, and writing on it would put this run's app window in their run's frames.
#   2. A candidate whose lock exists but which answers NOTHING is a stale lock from a killed run. It is
#      removed, because leaving it means the display is lost for the rest of the machine's uptime.
#   3. Starting Xvfb is NOT evidence that it started. Xvfb can stay alive while failing to own the
#      display, so every candidate is VERIFIED with xdpyinfo against that exact display before it is
#      accepted. `kill -0` alone is what let the old bug read as "the display was free".
#
# Every attempt's PID is remembered, including the ones that failed, so cleanup kills Xvfb instances this
# run started and then finds unusable - otherwise a rejected attempt keeps a display held for the whole run.
XVFB_PIDS=()
XVFB_ATTEMPTED=()

# display_is_live N -> 0 when a server answers on :N, 1 otherwise.
display_is_live() { xdpyinfo -display ":$1" >/dev/null 2>&1; }

start_xvfb() {
    local lock="" candidate n pid
    XVFB_ATTEMPTED=()
    for (( n = DISPLAY_START; n < DISPLAY_START + DISPLAY_TRIES; n++ )); do
        candidate=":$n"
        XVFB_ATTEMPTED+=("$candidate")
        lock="${TMPDIR:-/tmp}/.X${n}-lock"
        # A lock that exists is not itself proof of a live server - only an answering server is.
        if [[ -e "$lock" ]] && display_is_live "$n"; then
            say "display $candidate is in use by a live X server - skipping it"
            continue
        fi
        if [[ -e "$lock" ]]; then
            say "display $candidate has a stale lock ($lock) with no server behind it - clearing it"
            rm -f "$lock" || { say "could not remove the stale lock $lock - skipping $candidate"; continue; }
        fi
        Xvfb "$candidate" -screen 0 1280x900x24 >>"$RUN_DIR/xvfb.log" 2>&1 &
        pid=$!
        XVFB_PIDS+=("$pid")
        # Give the server long enough to answer before judging it; 0.5s x 10 covers a cold start without
        # the 2s the old fixed path paid on every single run.
        local waited=0
        while (( waited < 5 )); do
            if display_is_live "$n"; then
                DISPLAY_NUM="$n"
                say "display $candidate allocated (pid $pid, verified live with xdpyinfo)"
                return 0
            fi
            kill -0 "$pid" 2>/dev/null || break   # the process is gone: no point waiting on it
            sleep 0.5
            waited=$((waited + 1))
        done
        say "display $candidate did not come up (pid $pid) - releasing it and trying the next one"
        kill "$pid" 2>/dev/null
        sleep 0.3
        kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null
        rm -f "$lock" 2>/dev/null
    done
    die "could not start Xvfb on any of: ${XVFB_ATTEMPTED[*]} (all taken, or every start failed - see $RUN_DIR/xvfb.log). Raise DISPLAY_START/DISPLAY_TRIES if the machine really has that many X displays in use."
}

start_xvfb
XVFB_PID="${XVFB_PIDS[-1]}"
say "using display :$DISPLAY_NUM (allocated; was a fixed number before 2026-09-30)"
# Exported, not passed per command: `import` and any xdotool call in the drive file need a DISPLAY in
# their OWN environment, and a per-command assignment reaches only the command it is written on. Without
# this the window probe above still passes while the screenshot step fails with "missing an image
# filename", which reads like a bad path and is not one.
export DISPLAY=":$DISPLAY_NUM"

DISPLAY=":$DISPLAY_NUM" SKIKO_RENDER_API=SOFTWARE LIBGL_ALWAYS_SOFTWARE=1 HOME="$LOOP_HOME" \
    LANG=en_US.UTF-8 LANGUAGE=en_US LC_ALL=en_US.UTF-8 \
    "$APP" >"$RUN_DIR/app-stdout.log" 2>&1 &
APP_PID=$!
# Stamped before the launch and compared against afterwards, so a pre-existing log from an earlier
# session can never be mistaken for this run's account of it.
LOOP_LAUNCHED_AT="$(date +%s)"
say "launched pid $APP_PID on :$DISPLAY_NUM (software rendering)"

# --- wait for a REAL window ------------------------------------------------------------------------------
# A name-only grep false-passes: Xvfb also lists 1x1 unmapped helper windows. So the check requires a
# mapped window wider than 100px, which is the measured difference between "the app is up" and "something
# exists".
WINDOW_ID=""
for _ in $(seq 1 "$WAIT_SECONDS"); do
    sleep 1
    kill -0 "$APP_PID" 2>/dev/null || break
    WINDOW_ID=$(DISPLAY=":$DISPLAY_NUM" xwininfo -root -children 2>/dev/null \
        | grep -i "Payanam" | grep -vE '^\s*$' | head -1 | grep -oE '^[[:space:]]*0x[0-9a-f]+' | tr -d ' ')
    if [[ -n "$WINDOW_ID" ]]; then
        geometry=$(DISPLAY=":$DISPLAY_NUM" xwininfo -id "$WINDOW_ID" 2>/dev/null \
            | grep -E "Width|Height|Map State" | tr '\n' ' ')
        width=$(printf '%s' "$geometry" | grep -oE 'Width: [0-9]+' | grep -oE '[0-9]+' || echo 0)
        if [[ "${width:-0}" -gt 100 ]] && printf '%s' "$geometry" | grep -q "IsViewable"; then
            say "window up: $WINDOW_ID ($geometry)"
            break
        fi
    fi
    WINDOW_ID=""
done

# Exported because a drive file runs in a CHILD shell (`bash -c "source '<drive>'"`), so only exported
# variables and exported functions reach it. A drive that derives its OCR region from the window it was
# launched into cannot see WINDOW_ID otherwise, and would fail safe rather than OCR the whole frame.
export WINDOW_ID

if [[ -z "$WINDOW_ID" ]]; then
    say "the app did not present a viewable window within ${WAIT_SECONDS}s"
    say "--- app stdout (tail) ---"; tail -20 "$RUN_DIR/app-stdout.log" 2>/dev/null
else
    shot 00-first-screen
    identify "$RUN_DIR/00-first-screen.png" 2>/dev/null | head -1
fi

# --- drive it ---------------------------------------------------------------------------------------------
if [[ -n "$DRIVE_FILE" ]]; then
    [[ -f "$DRIVE_FILE" ]] || die "no drive file at $DRIVE_FILE"
    say "driving with $DRIVE_FILE"
    # The drive file is sourced in a CHILD shell, which does not inherit this shell's functions - so the
    # helpers it calls must be exported, along with the directory its shots land in. Without this every
    # click and shot in the drive file fails as "command not found" while the loop itself looks healthy,
    # and a run that did nothing reports success.
    export RUN_DIR
    # frame_hash is called by click, so it has to travel with it; say is called by shot and by click's
    # own verification, so it must travel too. An unexported helper is the exact failure this list
    # exists to prevent: "command not found" in the child shell, a loop that still reports healthy.
    # `die` is called from INSIDE click() and click_button(), both of which run in the child shell. Leaving it
    # unexported makes every loud failure in the drive run as "die: command not found" - which is not
    # fatal, returns 127, and lets the step CONTINUE. That is precisely the silent-pass this work exists to
    # remove: a guard that cannot fail is not a guard. Measured here - the colour mutation made the run
    # report SUCCESS because the failing die never executed.
    # CLICK_SETTLE travels for the same reason: an unset default becomes an empty settle in the child, so
    # click() would wait 0s and then measure a screen that had not moved yet.
    # find_text_centre and click_text are on this list for the same reason as everything else on it: a
    # drive calls them from the CHILD shell, and an unexported helper is "command not found" there - not
    # a crash, just a drive that stops finding anything and looks otherwise healthy.
    export -f click type_text press shot say frame_hash click_button find_button_centre find_text_centre click_text die
    export CLICK_SETTLE LOOP_PRIMARY_COLOR LOOP_BUTTON_MIN_AREA LOOP_BUTTON_FUZZ
    # A drive file that dies (see click/click_button) exits the CHILD non-zero. "say ... exited non-zero"
    # is how a failed step used to read as a completed run, so the code is kept and the run ends on it.
    # The log is still read first: a blind step is exactly when the app's own account is most wanted.
    DRIVE_RC=0
    bash -c "source '$DRIVE_FILE'" || DRIVE_RC=$?
    if (( DRIVE_RC != 0 )); then
        say "FATAL: the drive file exited ${DRIVE_RC} - a step failed on purpose rather than continue blind"
    else
        say "drive file finished"
    fi
else
    say "no drive file given: photographed the first screen only"
fi

# --- the app's own account of the run ----------------------------------------------------------------------
# MEASURED TWICE, and both times the PROBE was the thing that was wrong, not the app:
#   1. jpackage's launcher resolves the REAL user home for the log, so grepping the isolated $LOOP_HOME
#      printed "no app log was written" on runs that had in fact logged everything. A false negative here
#      is worse than no probe: it reads as "the app is silent", the opposite of the crash being hunted.
#   2. The live log sits in a DEBUG-SUFFIXED root (Documents/Payanam-debug/logs) while the search covered
#      only the unsuffixed one, so every run reported a 296-line log as missing.
# The isolated HOME is still correct for app DATA - that is what makes this a first run - so only the log
# LOCATION is derived now, and it is derived from the rule the app itself uses rather than from paths
# typed in here (DesktopAppPaths):  <parent>/Documents/<rootName>/logs is the current layout and
# <parent>/.local/share/<rootName>/logs the pre-migration one the app still reads.
#   <parent>   = $XDG_DATA_HOME when set, else the home the launcher resolves
#   <rootName> = "Payanam" + "-debug" on a debug build, plain "Payanam" on a release build
# BOTH root names are searched whichever this build claims to be. The debug flag is frozen in at build
# time, so a binary left in the tree from an earlier build answers for the wrong root - and choosing a
# root by a flag that can be stale is this same defect one level up. The newest log across every root
# wins, so a stale flag can cost a redundant find but never a false "no app log".
APP_BUILD_INFO="$ROOT/desktop/build/generated/buildinfo/io/payanam/desktop/DesktopBuildInfo.kt"
if [[ ! -r "$APP_BUILD_INFO" ]]; then
    say "no generated build info at $APP_BUILD_INFO - cannot tell debug from release, so both root names are searched"
elif grep -q 'IS_DEBUG_BUILD = true' "$APP_BUILD_INFO"; then
    say "this build reports IS_DEBUG_BUILD=true, so its root is Payanam-debug (both names are searched regardless)"
else
    say "this build reports IS_DEBUG_BUILD=false, so its root is Payanam (both names are searched regardless)"
fi
LOG_PARENTS=("$HOME")
# XDG_DATA_HOME replaces <home> as the parent when the launcher resolves it (DesktopAppPaths), so a
# caller that set one has its log under THAT, not under $HOME. Appended only when it is a different
# path, so the same directory is never searched twice for no gain.
if [[ -n "${XDG_DATA_HOME:-}" && "$XDG_DATA_HOME" != "$HOME" ]]; then
    LOG_PARENTS+=("$XDG_DATA_HOME")
fi
LOG_PARENTS+=("$LOOP_HOME")
LOG_DIRS=()
for parent in "${LOG_PARENTS[@]}"; do
    for root_name in "Payanam-debug" "Payanam"; do
        LOG_DIRS+=("$parent/Documents/$root_name/logs" "$parent/.local/share/$root_name/logs")
    done
done
CRASH_LINE="appSettings was read before a data layer was installed"
LOG=""
for dir in "${LOG_DIRS[@]}"; do
    # -newermt on the launch stamp, not ls -t, so yesterday's log can never be reported as today's.
    # The 5s slack absorbs second-granularity stamps: the log file is opened a moment after the
    # launcher forks, and "newest file, any age" was how the wrong file got picked in the first place.
    # The exported copy is excluded: it is a bundle the operator copied out, not this session's log.
    candidate=$(find "$dir" -maxdepth 1 -name '*.log' ! -name 'payanam_desktop_latest_export.log' \
        -newermt "@$((LOOP_LAUNCHED_AT - 5))" -printf '%T@ %p\n' 2>/dev/null \
        | sort -rn | head -1 | cut -d' ' -f2-)
    [[ -n "$candidate" ]] || continue
    if [[ -z "$LOG" || "$candidate" -nt "$LOG" ]]; then
        LOG="$candidate"
    fi
done

if [[ -n "$LOG" ]]; then
    say "app log: $LOG"
    say "--- last 15 lines ---"
    tail -15 "$LOG"
    # The specific line this whole loop was built to catch, counted rather than grepped so the number
    # is visible when it is zero. grep -c prints "0" AND exits 1 when there is no match, so the default
    # has to be a substitution, not an `|| echo 0` - otherwise the count is printed twice.
    CRASH_COUNT=$(grep -cF "$CRASH_LINE" "$LOG" 2>/dev/null)
    CRASH_COUNT="${CRASH_COUNT:-0}"
    say "lines matching \"$CRASH_LINE\": $CRASH_COUNT"
    if grep -qE "uncaughtException|FATAL|IllegalStateException" "$LOG"; then
        say "--- the app reported a failure ---"
        grep -nE "uncaughtException|FATAL|IllegalStateException|error=" "$LOG" | tail -12
    else
        say "no crash marker in the log"
    fi
else
    say "no app log was written for this run (launched at $(date -d "@$LOOP_LAUNCHED_AT" '+%H:%M:%S')) in any of:"
    for dir in "${LOG_DIRS[@]}"; do
        say "  $dir"
    done
fi

say "artifacts: $RUN_DIR"

# A failed step is reported last, so the log above it is readable and the run's exit code matches what
# it printed. The EXIT trap still restores the real app state and releases the run lock - `exit` runs
# the trap exactly as a `return` would, and unlike `return` it is legal here: this script is EXECUTED
# (`bash desktop-loop.sh`), never sourced, so a top-level `return` aborts with "return: can only
# 'return' from a function or sourced script" - which replaces the step's real exit code with 1 and
# turns a targeted failure into a confusing one.
(( DRIVE_RC == 0 )) || exit "$DRIVE_RC"
exit 0
