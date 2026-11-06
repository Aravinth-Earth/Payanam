#!/usr/bin/env bash
# Runs the four reminder-action Maestro flows against a real device.
#
# Every label the flows need is derived from the build's own resources for the
# device's locale, so the same suite runs on an English or a Tamil phone. Nothing
# here is machine-specific: device, repo and output dir are arguments/env.
#
#   PASS=... DEVICE=<serial> ./run-action-suite.sh [flow ...]
#
# Preconditions, verified before any flow starts:
#   - the device is attached, awake and unlocked (a sleeping screen has no shade,
#     and Maestro cannot wake it)
#   - the app is installed and the notification permission is granted
set -uo pipefail

DEVICE="${DEVICE:?set DEVICE to the adb serial}"
# Derived from this script's own location: no machine-specific path is baked in.
REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
FLOWS_DIR="${FLOWS_DIR:-$REPO/.maestro}"
OUT_DIR="${OUT_DIR:-$REPO/build-logs/e2e}"
APP_ID="${APP_ID:-io.payanam}"
PASS="${PASS:?set PASS to the database passphrase}"

mkdir -p "$OUT_DIR"

say() { printf '\n=== %s ===\n' "$*"; }

# ---- preconditions -----------------------------------------------------------
state=$(adb -s "$DEVICE" get-state 2>/dev/null || true)
[ "$state" = "device" ] || { echo "device $DEVICE not ready (state='$state')"; exit 2; }

if adb -s "$DEVICE" shell dumpsys window 2>/dev/null | grep -q "mDreamingLockscreen=true"; then
  echo "device is locked; unlock it and re-run (this suite never dismisses the keyguard)"; exit 3
fi

if ! adb -s "$DEVICE" shell pm list packages 2>/dev/null | grep -qx "package:$APP_ID"; then
  echo "$APP_ID is not installed; install the release APK first"; exit 4
fi

appop=$(adb -s "$DEVICE" shell cmd appops get "$APP_ID" POST_NOTIFICATION 2>/dev/null | tr -d '\r')
case "$appop" in
  *ignore*) echo "notifications are blocked for $APP_ID; grant them or the shade assertions cannot pass"; exit 5 ;;
esac

# ---- locale-derived labels ---------------------------------------------------
locale=$(adb -s "$DEVICE" shell getprop persist.sys.locale 2>/dev/null | tr -d '\r')
case "$locale" in
  ta*) RES_DIR=values-ta; EXPAND_LABEL="விரிவாக்கும்" ;;
  *)   RES_DIR=values;    EXPAND_LABEL="Expand" ;;
esac
strings_xml="$REPO/core/strings/src/main/res/$RES_DIR/strings.xml"
[ -f "$strings_xml" ] || { echo "missing $strings_xml"; exit 6; }

label() { sed -n "s/.*name=\"$1\">\([^<]*\)<.*/\1/p" "$strings_xml" | head -1; }

STATUS_COMPLETED=$(label loc_completed)
STATUS_SKIPPED=$(label loc_skipped)
STATUS_MISSED=$(label loc_missed)
ACTION_COMPLETE=$(label task_notification_action_complete)
ACTION_SKIP=$(label task_notification_action_skip)
ACTION_SNOOZE=$(label task_notification_action_snooze)
ACTION_MISS=$(label loc_miss)
ACTION_MORE=$(label task_notification_action_more)
ALL_HABITS_DONE=$(label loc_all_habits_completed_today)

for v in STATUS_COMPLETED STATUS_SKIPPED STATUS_MISSED ACTION_COMPLETE ACTION_MISS ACTION_MORE ALL_HABITS_DONE; do
  [ -n "${!v}" ] || { echo "could not resolve $v from $strings_xml"; exit 7; }
done

say "locale=$locale labels from $RES_DIR"

# ---- flow list ---------------------------------------------------------------
if [ "$#" -gt 0 ]; then
  FLOWS=("$@")
else
  FLOWS=(11-action-complete.yaml 12-action-more.yaml 14-action-miss.yaml)
fi

# Reset to a fresh database so the habits list never accumulates across runs.
if [ "${SKIP_SETUP:-0}" != "1" ]; then
  say "setup (fresh database)"
  if ! maestro --device "$DEVICE" test "$FLOWS_DIR/01-fresh-setup.yaml" -e PASS="$PASS" --test-output-dir "$OUT_DIR"; then
    echo "setup failed; not running the action flows"; exit 8
  fi
fi

pass_count=0
fail_count=0
failed_names=()

for flow in "${FLOWS[@]}"; do
  say "flow $flow"
  # A unique name per run: a rerun under the same name leaves the previous run's
  # row on screen and the assertion can match it.
  habit="flow-${flow%%-*}-$(date +%H%M%S)"
  # +22, not +17: the creation path (unlock, form, recurrence dialog, time entry,
  # scroll to save) takes ~2 minutes, and the reminder fires 15 min before the due
  # time. A +17 due leaves only 2 minutes of slack and the save can land after the
  # fire time, in which case ReminderDecider correctly skips and no alarm is armed.
  due_hour=$(date -d '+22 minutes' '+%H')
  due_minute=$(date -d '+22 minutes' '+%M')
  today=$(adb -s "$DEVICE" shell date '+%F' 2>/dev/null | tr -d '\r')

  if maestro --device "$DEVICE" test "$FLOWS_DIR/$flow" \
      -e PASS="$PASS" \
      -e HABIT_NAME="$habit" \
      -e DUE_HOUR="$due_hour" -e DUE_MINUTE="$due_minute" \
      -e REMINDER_WAIT_MS=480000 \
      -e TODAY="$today" \
      -e STATUS_COMPLETED="$STATUS_COMPLETED" \
      -e STATUS_SKIPPED="$STATUS_SKIPPED" \
      -e STATUS_MISSED="$STATUS_MISSED" \
      -e ACTION_EXPAND="$EXPAND_LABEL" \
      -e ACTION_COMPLETE="$ACTION_COMPLETE" \
      -e ACTION_MISS="$ACTION_MISS" \
      -e ACTION_MORE="$ACTION_MORE" \
      -e ALL_HABITS_DONE="$ALL_HABITS_DONE" \
      --test-output-dir "$OUT_DIR"; then
    pass_count=$((pass_count + 1))
    echo "PASS $flow"
  else
    fail_count=$((fail_count + 1))
    failed_names+=("$flow")
    echo "FAIL $flow"
  fi
done

say "summary: $pass_count passed, $fail_count failed"
[ "$fail_count" -eq 0 ] || { printf 'failed: %s\n' "${failed_names[*]}"; exit 1; }
