#!/usr/bin/env bash
# Runs the reminder-action Maestro flows against a real device: PASS=... DEVICE=<serial> [flow ...].
# Labels come from the locale's resources. SUITE=exact-alarm runs 06/07; SUITE=archive runs 08/09.
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
  FLOWS=(02-date-tag-publishes.yaml 03-action-complete.yaml 04-action-more.yaml 05-action-miss.yaml)
fi

# Read-only probes for the exact-alarm appop; nothing here changes the device state.
exact_alarm_appop() {
  adb -s "$DEVICE" shell cmd appops get "$APP_ID" SCHEDULE_EXACT_ALARM 2>/dev/null | tr -d '\r'
}
exact_alarm_mode() {
  exact_alarm_appop | sed -n 's/.*SCHEDULE_EXACT_ALARM: *\([a-z]*\).*/\1/p' | head -1
}

# A flow that failed at a shade wait leaves the shade open and blocks the next launchApp.
# Focus is only read here; the lock screen is never touched.
normalise_ui_state() {
  adb -s "$DEVICE" shell cmd statusbar collapse >/dev/null 2>&1
  focus_line=""
  for _ in 1 2 3 4 5 6; do
    focus_line=$(adb -s "$DEVICE" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r')
    case "$focus_line" in *"$APP_ID"*|*[Ll]auncher*) return 0 ;; esac
    sleep 0.5
  done
  echo "app is not in front: ${focus_line:-<no mCurrentFocus>}"
  return 1
}

# Reset to a fresh database so the habits list never accumulates across runs.
if [ "${SKIP_SETUP:-0}" != "1" ]; then
  say "setup (fresh database)"
  if ! normalise_ui_state; then
    echo "setup not started; bring $APP_ID to the front and re-run"; exit 14
  fi
  if ! maestro --device "$DEVICE" test "$FLOWS_DIR/01-fresh-setup.yaml" -e PASS="$PASS" --test-output-dir "$OUT_DIR"; then
    echo "setup failed; not running the action flows"; exit 8
  fi
fi

# Checked after setup: 01-fresh-setup.yaml grants POST_NOTIFICATIONS, and a clean
# install resets it, so an earlier read only measures pre-setup state.
appop=$(adb -s "$DEVICE" shell cmd appops get "$APP_ID" POST_NOTIFICATION 2>/dev/null | tr -d '\r')
case "$appop" in
  *ignore*) echo "notifications are blocked for $APP_ID; grant them or the shade assertions cannot pass"; exit 5 ;;
esac

# Exact alarms are a precondition, not an assumption: an inexact alarm can land
# minutes late, past REMINDER_WAIT_MS, and a clean install resets this appop.
# SUITE=exact-alarm and SUITE=archive drive or ignore the appop, so they are exempt.
if [ "${SUITE:-}" != "exact-alarm" ] && [ "${SUITE:-}" != "archive" ]; then
  needs_exact_alarm=0
  for f in "${FLOWS[@]}"; do
    case "$f" in
      *03-action-complete.yaml*|*04-action-more.yaml*|*05-action-miss.yaml*) needs_exact_alarm=1 ;;
    esac
  done
  case "$(exact_alarm_appop)" in
    *"SCHEDULE_EXACT_ALARM:"*allow*) ;;
    *)
      if [ "$needs_exact_alarm" -eq 1 ]; then
        echo "SCHEDULE_EXACT_ALARM is not allowed for $APP_ID (reads: $(exact_alarm_appop))"
        echo "Without it reminders are inexact and can arrive minutes late, which the flows' wait cannot cover."
        echo "grant it and re-run: adb shell appops set $APP_ID SCHEDULE_EXACT_ALARM allow"
        exit 13
      fi
      echo "note: exact alarms unavailable for $APP_ID; no selected flow waits on a reminder"
      ;;
  esac
fi

# ---- exact-alarm sequence -----------------------------------------------------
# 06 needs the appop denied and 07 needs it granted, so the pair is opt-in only.
if [ "${SUITE:-}" = "exact-alarm" ]; then
  say "exact-alarm sequence (06 then 07)"

  wait_s="${EXACT_ALARM_WAIT_S:-300}"
  case "$wait_s" in ''|*[!0-9]*) echo "EXACT_ALARM_WAIT_S must be whole seconds"; exit 9 ;; esac

  if [ "$(exact_alarm_mode)" != "deny" ]; then
    echo "06 needs SCHEDULE_EXACT_ALARM denied; the appop reads '$(exact_alarm_appop)'"
    echo "reset it with: adb -s $DEVICE shell appops set $APP_ID SCHEDULE_EXACT_ALARM deny"
    exit 10
  fi

  if ! maestro --device "$DEVICE" test "$FLOWS_DIR/06-exact-alarm-offered.yaml" \
      -e PASS="$PASS" --test-output-dir "$OUT_DIR"; then
    echo "06 failed; 07 is not run because its precondition is the state the grant leaves behind"
    exit 11
  fi

  say "grant SCHEDULE_EXACT_ALARM by hand; this runner never changes the appop itself"
  echo "  adb -s $DEVICE shell appops set $APP_ID SCHEDULE_EXACT_ALARM allow"
  echo "waiting up to ${wait_s}s for the appop to read allow ..."
  deadline=$(( $(date +%s) + wait_s ))
  while [ "$(exact_alarm_mode)" != "allow" ] && [ "$(date +%s)" -lt "$deadline" ]; do sleep 5; done

  if [ "$(exact_alarm_mode)" != "allow" ]; then
    echo "07 needs SCHEDULE_EXACT_ALARM granted; the appop reads '$(exact_alarm_appop)'"
    exit 12
  fi

  if ! maestro --device "$DEVICE" test "$FLOWS_DIR/07-exact-alarm-hidden.yaml" \
      -e PASS="$PASS" --test-output-dir "$OUT_DIR"; then
    echo "FAIL 07-exact-alarm-hidden.yaml"; exit 1
  fi

  say "summary: 2 passed, 0 failed (exact-alarm sequence)"
  exit 0
fi

# ---- archive sequence ---------------------------------------------------------
# The alarm read sits between the two flows, so this cannot be a single Maestro flow.
if [ "${SUITE:-}" = "archive" ]; then
  say "archive sequence (08 arm, read alarms, 09 archive, read alarms)"

  # Read-only: counts the pending RTC_WAKEUP alarms this package still owns.
  app_id_re="${APP_ID//./\\.}"
  pending_alarms() {
    adb -s "$DEVICE" shell dumpsys alarm 2>/dev/null \
      | grep -cE "RTC_WAKEUP #[0-9]+: Alarm\{.*${app_id_re}\}" || true
  }

  habit="archive-$(date +%H%M%S)"
  # +25 puts the fire time ~10 min past the save, so the alarm is armed at save time.
  due_hour=$(date -d '+25 minutes' '+%H')
  due_minute=$(date -d '+25 minutes' '+%M')

  if ! normalise_ui_state; then
    echo "08 not started; bring $APP_ID to the front and re-run"; exit 15
  fi

  if ! maestro --device "$DEVICE" test "$FLOWS_DIR/08-reminder-armed.yaml" \
      -e PASS="$PASS" \
      -e HABIT_NAME="$habit" \
      -e DUE_HOUR="$due_hour" -e DUE_MINUTE="$due_minute" \
      --test-output-dir "$OUT_DIR"; then
    echo "FAIL 08-reminder-armed.yaml"; exit 16
  fi

  armed=$(pending_alarms)
  echo "pending alarms after 08: $armed"
  # An unarmed reminder makes 09 pass without cancelling anything, which proves nothing.
  if [ "$armed" != "1" ]; then
    echo "ABORT: $APP_ID owns $armed pending RTC_WAKEUP alarm(s) after 08, expected exactly 1"
    echo "09 would then pass while cancelling nothing. Fix the arm step before trusting this check."
    exit 17
  fi

  if ! maestro --device "$DEVICE" test "$FLOWS_DIR/09-archive-cancels-reminder.yaml" \
      -e PASS="$PASS" \
      -e HABIT_NAME="$habit" \
      --test-output-dir "$OUT_DIR"; then
    echo "FAIL 09-archive-cancels-reminder.yaml (pending alarms before archive: $armed)"; exit 18
  fi

  cancelled=$(pending_alarms)
  echo "pending alarms after 09: $cancelled (before archive: $armed)"
  if [ "$cancelled" != "0" ]; then
    echo "FAIL archiving did not cancel the reminder: pending alarms $armed -> $cancelled"
    exit 19
  fi

  say "summary: 2 passed, 0 failed (archive sequence); pending alarms $armed -> $cancelled"
  exit 0
fi

pass_count=0
fail_count=0
failed_names=()

for flow in "${FLOWS[@]}"; do
  say "flow $flow"
  if ! normalise_ui_state; then
    fail_count=$((fail_count + 1))
    failed_names+=("$flow")
    echo "FAIL $flow (not run: app was not in front)"
    continue
  fi
  # A unique name per run: a rerun under the same name leaves the previous run's
  # row on screen and the assertion can match it.
  habit="flow-${flow%%-*}-$(date +%H%M%S)"
  # +22, not +17: creation takes ~2 min and the reminder fires 15 min before the due
  # time, so a +17 due leaves 2 min of slack and the save can land after the fire
  # time, when ReminderDecider correctly skips and arms no alarm.
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
# 06/07 need an OS appop the runner cannot set, so they stay out of the default list.
case " ${FLOWS[*]} " in
  *" 06-"*|*" 07-"*) ;;
  *) echo "exact-alarm flows 06/07 skipped: run SUITE=exact-alarm (needs SCHEDULE_EXACT_ALARM denied, then granted)" ;;
esac
# 08/09 need an alarm read between them, which one Maestro flow cannot do.
case " ${FLOWS[*]} " in
  *" 08-"*|*" 09-"*) ;;
  *) echo "archive flows 08/09 skipped: run SUITE=archive (reads the pending alarm count between the flows)" ;;
esac
[ "$fail_count" -eq 0 ] || { printf 'failed: %s\n' "${failed_names[*]}"; exit 1; }
