#!/usr/bin/env bash
#  SPDX-FileCopyrightText: 2026 Aravinth-Earth
#  SPDX-License-Identifier: AGPL-3.0-or-later
#
# smoke-desktop.sh - one-command smoke test for the INSTALLED Compose Desktop app.
#
# What this proves, and what it does not:
#   Proves: the installed launcher (discovered, not assumed) starts under Xvfb, maps a real
#           viewable window of a plausible size, and paints non-blank pixels into that window.
#           That is the whole claim: "the app rendered its first screen".
#   Does NOT prove: anything about app data. A fresh profile lands on the privacy tour, and the
#           window title / DB state are deliberately NOT asserted - see WINDOW_TITLE below. Do not
#           read a green run as "the app is correct", only as "the app renders".
#
# Why an isolated HOME is non-negotiable here:
#   The app writes a real profile (and a real SQLite DB) under $HOME, and takes a single-instance
#   FileLock under $HOME/.local/share/.../runtime/. Running the installed binary against the
#   developer's own HOME would touch the real database and could collide with a real running
#   instance (the second process then prints "Payanam already running" and shows a stale window).
#   So the script always builds a throwaway HOME under the scratch dir. Nothing outside
#   $SMOKE_HOME is read or written by the app, and the throwaway HOME is left on disk (not
#   deleted) so a failed run can be inspected; scratch is pruned after 24h.
#
# Environment facts this script depends on (all measured on this machine):
#   * SKIKO_RENDER_API=SOFTWARE is MANDATORY. Xvfb has no GLX, and without it Compose dies with
#     org.jetbrains.skiko.RenderException: Cannot create Linux GL context.
#   * xwininfo -root -children also lists 1x1 UNMAPPED helper windows, so a name-only match
#     FALSE-PASSES. This script requires width > MIN_WIDTH, height > MIN_HEIGHT, and
#     IsViewable. All three, or it is not the app.
#
# Usage:  bash build-tools/scripts/smoke-desktop.sh [LAUNCHER_PATH] [SCREENSHOT_PATH]
#         The launcher is discovered from the installed package; SMOKE_LAUNCHER=/path/to/Payanam
#         overrides discovery, and LAUNCHER_PATH (arg 1) does the same.
#
# Exit codes (each failure mode is distinct and states its reason):
#   0  PASS   - all assertions held
#   2  USAGE  - bad arguments, missing prerequisite tool, launcher not executable, bad env override
#   3  XVFB   - could not start / claim an X display
#   4  NOWIN  - app process exited or no qualifying window appeared within the timeout
#   5  SMALL  - a matching window appeared but was too small to be the app
#   6  HIDDEN - window matched but Map State was not IsViewable
#   7  BLANK  - screenshot missing, undersized, or a single flat colour (nothing was painted)
set -uo pipefail

# ── Defaults ────────────────────────────────────────────────────────────────
TIMEOUT="${SMOKE_TIMEOUT:-90}"          # seconds to wait for a qualifying window
MIN_WIDTH=100                           # a real app window; helper windows are 1x1
MIN_HEIGHT=100
SCREENSHOT="${2:-}"
SCRATCH_ROOT="${TMPDIR:-${HOME}/.cache/payanam-smoke}"

# ── Find the INSTALLED launcher ─────────────────────────────────────────────
# The claim this script makes is about the INSTALLED app, so it asks where that app
# actually is instead of assuming a path. Sources, in order: an explicit override, the
# package's own file list, then the installed menu entry. Every one is a fact about the
# installation; none is a guess. There is deliberately NO hardcoded fallback: if the app
# is not installed, the script says so and stops.
discover_launcher() {
    [[ -n "${SMOKE_LAUNCHER:-}" ]] && { printf '%s\n' "$SMOKE_LAUNCHER"; return 0; }
    [[ -n "${1:-}" ]] && { printf '%s\n' "$1"; return 0; }

    local p=""
    if command -v dpkg >/dev/null 2>&1; then
        p="$(dpkg -L payanam 2>/dev/null | grep -m1 -E '^/.*/bin/[^/]+$' || true)"
        [[ -n "$p" ]] && { printf '%s\n' "$p"; return 0; }
    fi

    local f
    for f in /usr/share/applications/*ayanam*.desktop \
             /usr/local/share/applications/*ayanam*.desktop; do
        [[ -f "$f" ]] || continue
        # The first token of Exec=, with any freedesktop field code (%U, %F, ...) stripped:
        # a .desktop may legally carry one.
        p="$(grep -m1 '^Exec=' "$f" 2>/dev/null | sed -e 's/^Exec=//' -e 's/%[A-Za-z]//g' -e 's/ .*//' || true)"
        [[ -n "$p" ]] && { printf '%s\n' "$p"; return 0; }
    done
    return 1
}

LAUNCHER="$(discover_launcher "${1:-}")" || {
    echo "SMOKE-DESKTOP: USAGE — the Payanam desktop app is not installed: no launcher was found (checked the 'payanam' package and the applications menu entry). Install the .deb first." >&2
    exit 2
}

# ── Helpers ─────────────────────────────────────────────────────────────────
log()  { echo "[$(date '+%H:%M:%S')] $*"; }
info() { log "$*"; }
ok()   { log "  ✅ $*"; }
warn() { log "  ⚠️  $*"; }

# $1 = reason. Prints the verdict and exits $2. Teardown still runs: the EXIT trap does not care
# how we leave, so a verdict printed from here never leaks the Xvfb or the app process.
verdict() {
    local reason="$1" code="$2" label="$3"
    echo
    echo "SMOKE-DESKTOP: $label — $reason"
    log "  launcher: $LAUNCHER"
    # Only advertise a screenshot that actually exists; a pre-window failure has none.
    [[ -n "${SHOT_PATH:-}" && -f "$SHOT_PATH" ]] && log "  screenshot: $SHOT_PATH"
    exit "$code"
}

# ── Validate before starting anything ───────────────────────────────────────
if [[ ! "$TIMEOUT" =~ ^[0-9]+$ ]] || (( TIMEOUT < 5 )); then
    echo "SMOKE-DESKTOP: USAGE — SMOKE_TIMEOUT must be an integer >= 5 (got '$TIMEOUT')" >&2
    exit 2
fi
for tool in Xvfb xwininfo xprop import xdpyinfo; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "SMOKE-DESKTOP: USAGE — required tool '$tool' is not installed (apt install x11-utils imagemagick xvfb)" >&2
        exit 2
    }
done
if [[ ! -f "$LAUNCHER" ]]; then
    echo "SMOKE-DESKTOP: USAGE — launcher not found: $LAUNCHER (install the .deb first)" >&2
    exit 2
fi
if [[ ! -x "$LAUNCHER" ]]; then
    echo "SMOKE-DESKTOP: USAGE — launcher is not executable: $LAUNCHER" >&2
    exit 2
fi
if [[ "$(uname -s)" != "Linux" ]]; then
    echo "SMOKE-DESKTOP: USAGE — Linux-only script, running on $(uname -s)" >&2
    exit 2
fi

mkdir -p "$SCRATCH_ROOT" || {
    echo "SMOKE-DESKTOP: USAGE — cannot create scratch root: $SCRATCH_ROOT" >&2
    exit 2
}
RUN_DIR="$(mktemp -d "$SCRATCH_ROOT/payanam-smoke.XXXXXX")" || {
    echo "SMOKE-DESKTOP: USAGE — cannot create run dir under $SCRATCH_ROOT" >&2
    exit 2
}
SMOKE_HOME="$RUN_DIR/home"
mkdir -p "$SMOKE_HOME"
APP_LOG="$RUN_DIR/app.log"
SHOT_PATH="${SCREENSHOT:-$RUN_DIR/smoke.png}"

# ── Teardown: own processes only, every exit path ───────────────────────────
XVFB_PID=""
APP_PID=""
cleanup() {
    # Only ever kill PIDs this script started. A blanket pkill Payanam/Xvfb would take out the
    # developer's real running app or another session's display.
    if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
        kill -TERM "$APP_PID" 2>/dev/null
        for _ in $(seq 1 20); do
            kill -0 "$APP_PID" 2>/dev/null || break
            sleep 0.25
        done
        kill -0 "$APP_PID" 2>/dev/null && kill -KILL "$APP_PID" 2>/dev/null
    fi
    if [[ -n "$XVFB_PID" ]] && kill -0 "$XVFB_PID" 2>/dev/null; then
        kill -TERM "$XVFB_PID" 2>/dev/null
        wait "$XVFB_PID" 2>/dev/null
    fi
    # Only remove the lock for OUR display, and only if it is a stale 0-byte placeholder. Xvfb
    # deletes its own lock on a clean exit; removing a live one would let the next run steal
    # a display another session is using.
    if [[ -n "${DISPLAY_NUM:-}" && -e "/tmp/.X${DISPLAY_NUM}-lock" && ! -s "/tmp/.X${DISPLAY_NUM}-lock" ]]; then
        rm -f "/tmp/.X${DISPLAY_NUM}-lock" 2>/dev/null
    fi
}
trap cleanup EXIT INT TERM

# ── Claim a free display ────────────────────────────────────────────────────
DISPLAY_NUM=""
for n in $(seq 90 120); do
    [[ -e "/tmp/.X${n}-lock" ]] && continue
    xdpyinfo -display ":$n" >/dev/null 2>&1 && continue   # in use by something that forgot its lock
    DISPLAY_NUM="$n"
    break
done
[[ -z "$DISPLAY_NUM" ]] && { echo "SMOKE-DESKTOP: XVFB — no free display in :90-:120" >&2; exit 3; }
DISPLAY=":${DISPLAY_NUM}"

info "Launching Xvfb on $DISPLAY (1280x1024x24)"
Xvfb "$DISPLAY" -screen 0 1280x1024x24 -nolisten tcp >"$RUN_DIR/xvfb.log" 2>&1 &
XVFB_PID=$!
for _ in $(seq 1 50); do
    xdpyinfo -display "$DISPLAY" >/dev/null 2>&1 && break
    kill -0 "$XVFB_PID" 2>/dev/null || break
    sleep 0.1
done
if ! xdpyinfo -display "$DISPLAY" >/dev/null 2>&1; then
    tail -5 "$RUN_DIR/xvfb.log" >&2
    echo "SMOKE-DESKTOP: XVFB — display $DISPLAY never became ready (see $RUN_DIR/xvfb.log)" >&2
    exit 3
fi
ok "Xvfb ready (pid $XVFB_PID)"

# ── Launch the app under the isolated HOME ──────────────────────────────────
info "HOME=$SMOKE_HOME (isolated throwaway profile)"
info "Launching: $LAUNCHER"
# SKIKO_RENDER_API=SOFTWARE is not optional: Xvfb has no GLX. See the header.
env -i \
    HOME="$SMOKE_HOME" \
    PATH="/usr/bin:/bin:/usr/local/bin" \
    DISPLAY="$DISPLAY" \
    XDG_RUNTIME_DIR="$RUN_DIR/xdg-runtime" \
    SKIKO_RENDER_API=SOFTWARE \
    LANG="${LANG:-C.UTF-8}" \
    "$LAUNCHER" >"$APP_LOG" 2>&1 &
APP_PID=$!
mkdir -p "$RUN_DIR/xdg-runtime"
info "App pid $APP_PID, log: $APP_LOG"

# ── Wait for a qualifying window ────────────────────────────────────────────
# A match requires: WM_CLASS containing the app's main class (dashes, not dots) AND a real size.
# We track the two intermediate failure modes separately so the exit code says which one it was.
WIN_ID=""
WIN_W=0
WIN_H=0
WIN_STATE="(none)"
MATCHED_TINY=false
DEADLINE=$(( SECONDS + TIMEOUT ))

while (( SECONDS < DEADLINE )); do
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        wait "$APP_PID" 2>/dev/null
        app_rc=$?                      # capture NOW: the tail below clobbers $?
        tail -20 "$APP_LOG" >&2
        verdict "app exited before mapping a window (exit ${app_rc}, see $APP_LOG)" 4 "FAIL"
    fi
    while read -r id; do
        [[ -n "$id" ]] || continue
        wclass=$(xprop -id "$id" WM_CLASS 2>/dev/null | sed 's/.*= //')
        case "$wclass" in
            *io-payanam-desktop-*) : ;;
            *) continue ;;
        esac
        name=$(xprop -id "$id" WM_NAME 2>/dev/null | sed 's/.*= //')
        geom=$(xwininfo -id "$id" 2>/dev/null)
        w=$(printf '%s' "$geom" | awk '/Width:/  {print $2}')
        h=$(printf '%s' "$geom" | awk '/Height:/ {print $2}')
        state=$(printf '%s' "$geom" | awk -F': ' '/Map State/ {print $2}')
        [[ -n "$w" && -n "$h" ]] || continue
        if (( w <= MIN_WIDTH || h <= MIN_HEIGHT )); then
            MATCHED_TINY=true
            info "candidate ${w}x${h} state='${state}' name='${name}' (too small, waiting)"
            continue
        fi
        case "$state" in
            IsViewable) : ;;
            *) MATCHED_TINY=false; WIN_ID="$id"; WIN_W="$w"; WIN_H="$h"; WIN_STATE="$state"
               continue ;;
        esac
        WIN_ID="$id"; WIN_W="$w"; WIN_H="$h"; WIN_STATE="$state"
        info "window found: $name"
        info "  geometry ${w}x${h}, Map State: ${state}, id ${id}"
        break 2
    done < <(xwininfo -root -children 2>/dev/null | awk '/^ +0x/ {print $1}')
    sleep 0.5
done

if [[ -z "$WIN_ID" ]]; then
    if $MATCHED_TINY; then
        verdict "only a ${w:-?}x${h:-?} helper window appeared; the app window never mapped within ${TIMEOUT}s" 5 "FAIL"
    fi
    tail -20 "$APP_LOG" >&2
    verdict "no Payanam window within ${TIMEOUT}s (app still running, see $APP_LOG)" 4 "FAIL"
fi
if [[ "$WIN_STATE" != "IsViewable" ]]; then
    verdict "window 0x$WIN_ID is ${WIN_STATE}, not IsViewable" 6 "FAIL"
fi
ok "window assertion passed (${WIN_W}x${WIN_H}, IsViewable)"

# ── Screenshot + blank-pixel check ───────────────────────────────────────────
# Capture the WINDOW (not the root): on a bare Xvfb with no WM, a root grab can come back
# black even when the app painted fine.
if import -display "$DISPLAY" -window "$WIN_ID" "$SHOT_PATH" 2>>"$RUN_DIR/import.err" \
   || import -display "$DISPLAY" -window root "$SHOT_PATH" 2>>"$RUN_DIR/import.err"; then
    :
else
    cat "$RUN_DIR/import.err" >&2
    verdict "screenshot capture failed for window 0x$WIN_ID" 7 "FAIL"
fi

if [[ ! -s "$SHOT_PATH" ]]; then
    verdict "screenshot at $SHOT_PATH is missing or zero bytes" 7 "FAIL"
fi
SHOT_BYTES=$(stat -c %s "$SHOT_PATH")
# Non-blank = more than one distinct colour. A window that mapped but painted nothing (or a
# failed GL blit leaving a flat framebuffer) collapses to exactly 1.
SHOT_COLORS=$(identify -format '%k' "$SHOT_PATH" 2>/dev/null || echo 0)
SHOT_MEAN=$(identify -format '%[fx:mean]' "$SHOT_PATH" 2>/dev/null || echo 0)
if (( SHOT_COLORS < 2 )); then
    verdict "screenshot is blank (${SHOT_COLORS} colour, ${SHOT_BYTES} bytes) — the window painted nothing" 7 "FAIL"
fi
ok "screenshot ${SHOT_BYTES} bytes, ${SHOT_COLORS} distinct colours, mean ${SHOT_MEAN}"

verdict "window ${WIN_W}x${WIN_H} IsViewable, non-blank render — app started and drew its first screen" 0 "PASS"
