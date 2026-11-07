#!/usr/bin/env bash
# Runs the reminder-action Maestro flows against a real device: PASS=... DEVICE=<serial> [flow ...].
# Labels follow the app's language; setup (`pm clear`) needs RESET_APP=1; SUITE=exact-alarm|archive.
set -uo pipefail

DEVICE="${DEVICE:?set DEVICE to the adb serial}"
# Derived from this script's own location: no machine-specific path is baked in.
REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
FLOWS_DIR="${FLOWS_DIR:-$REPO/.maestro}"
OUT_DIR="${OUT_DIR:-$REPO/build-logs/e2e}"
APP_ID="${APP_ID:-io.payanam}"
PASS="${PASS:?set PASS to the database passphrase}"

mkdir -p "$OUT_DIR"

# Maestro drives the appId each flow declares, so a mismatch would inspect one package
# while the flows drive another: abort rather than report on the wrong app.
for flow_file in "$FLOWS_DIR"/*.yaml; do
  [ -f "$flow_file" ] || continue
  flow_app_id=$(sed -n 's/^appId:[[:space:]]*\([^[:space:]#]*\).*/\1/p' "$flow_file" | head -1)
  if [ "$flow_app_id" != "$APP_ID" ]; then
    echo "APP_ID=$APP_ID but ${flow_file##*/} declares appId=${flow_app_id:-<none>}"
    echo "Maestro drives that file's appId, not APP_ID; set APP_ID=${flow_app_id:-<appId>} and re-run"
    exit 20
  fi
done

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
# The app renders from its per-app locale (LocaleManager) when one is set, so the device
# locale alone can name the wrong resource folder.
app_locale=$(adb -s "$DEVICE" shell cmd locale get-app-locales "$APP_ID" 2>/dev/null \
  | tr -d '\r' | sed -n 's/.*\[\([^]]*\)\].*/\1/p' | head -1 | cut -d, -f1)
if ! [[ "$app_locale" =~ ^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*$ ]]; then
  app_locale=""
fi
system_locale=$(adb -s "$DEVICE" shell getprop persist.sys.locale 2>/dev/null | tr -d '\r')
locale=${app_locale:-$system_locale}
language=${locale%%[-_]*}
case "$language" in
  ta) RES_DIR=values-ta; EXPAND_LABEL="விரிவாக்கும்" ;;
  *)  RES_DIR=values;    EXPAND_LABEL="Expand" ;;
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

say "app locale=${app_locale:-<none>} system locale=$system_locale labels from $RES_DIR"

# ---- device-clock due time ---------------------------------------------------
# The due time comes from the device's clock and timezone: a host/device offset can place
# it in the past, where ReminderDecider arms nothing.
device_due() {
  local minutes=$1 epoch offset sign off_h off_m offset_s local_now due
  epoch=$(adb -s "$DEVICE" shell date +%s 2>/dev/null | tr -d '\r')
  offset=$(adb -s "$DEVICE" shell date +%z 2>/dev/null | tr -d '\r')
  case "$epoch" in ''|*[!0-9]*) echo "could not read the device clock"; return 1 ;; esac
  case "$offset" in
    [+-][0-9][0-9][0-9][0-9]) ;;
    *) echo "could not read the device UTC offset (got '$offset')"; return 1 ;;
  esac
  sign=${offset:0:1}
  off_h=${offset:1:2}
  off_m=${offset:3:2}
  offset_s=$(( 10#$off_h * 3600 + 10#$off_m * 60 ))
  [ "$sign" = "-" ] && offset_s=$(( -offset_s ))
  local_now=$(( epoch + offset_s ))
  due=$(( local_now + minutes * 60 ))
  # A due time past the device's midnight is not "later today", so the run is refused.
  if [ "$(date -u -d "@$local_now" '+%F')" != "$(date -u -d "@$due" '+%F')" ]; then
    echo "now+${minutes}min crosses the device's midnight"
    return 1
  fi
  printf '%s %s %s\n' \
    "$(date -u -d "@$due" '+%H')" "$(date -u -d "@$due" '+%M')" "$(date -u -d "@$local_now" '+%F')"
}

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

# 01-fresh-setup.yaml runs `pm clear` on the installed package, which destroys the real
# database, so setup happens only when the caller opts in with RESET_APP=1.
if [ "${RESET_APP:-0}" = "1" ] && [ "${SKIP_SETUP:-0}" != "1" ]; then
  say "setup (fresh database, RESET_APP=1)"
  if ! normalise_ui_state; then
    echo "setup not started; bring $APP_ID to the front and re-run"; exit 14
  fi
  if ! maestro --device "$DEVICE" test "$FLOWS_DIR/01-fresh-setup.yaml" -e PASS="$PASS" --test-output-dir "$OUT_DIR"; then
    echo "setup failed; not running the action flows"; exit 8
  fi
else
  if [ "${SKIP_SETUP:-0}" = "1" ]; then
    echo "setup skipped (SKIP_SETUP=1); the flows run against the existing $APP_ID database."
  else
    echo "setup skipped: it would run 'pm clear $APP_ID' and wipe its data; set RESET_APP=1 to allow that."
    echo "the flows then run against the existing $APP_ID database, which must have been created with PASS."
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
  alarm_count_file="$OUT_DIR/.pending-alarms"
  # A failed read must not read as zero alarms: grep -c also exits 1 on no match, so the
  # adb status comes from PIPESTATUS[0] and the count from what grep wrote.
  pending_alarms() {
    adb -s "$DEVICE" shell dumpsys alarm 2>/dev/null \
      | grep -cE "RTC_WAKEUP #[0-9]+: Alarm\{.*${app_id_re}\}" >"$alarm_count_file"
    local adb_status=${PIPESTATUS[0]} grep_status=${PIPESTATUS[1]}
    if [ "$adb_status" -ne 0 ]; then
      echo "adb dumpsys alarm failed (exit $adb_status); the pending-alarm count is unknown, not zero"
      return 1
    fi
    if [ "$grep_status" -gt 1 ]; then
      echo "grep failed (exit $grep_status); the pending-alarm count is unknown, not zero"
      return 1
    fi
    alarm_count=$(<"$alarm_count_file")
  }

  habit="archive-$(date +%H%M%S)"
  # +25 puts the fire time ~10 min past the save, so the alarm is armed at save time.
  if ! due=$(device_due 25); then
    echo "08/09 not started: the device clock cannot place the due time inside today"
    exit 23
  fi
  read -r due_hour due_minute _ <<<"$due"

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

  pending_alarms || { echo "ABORT: could not read the pending alarms after 08"; exit 21; }
  armed=$alarm_count
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

  # The archive cancels asynchronously, so the count is polled to a bounded deadline: a
  # reminder that is genuinely never cancelled still reads non-zero at the deadline.
  cancel_wait_s="${CANCEL_WAIT_S:-60}"
  case "$cancel_wait_s" in ''|*[!0-9]*) echo "CANCEL_WAIT_S must be whole seconds"; exit 24 ;; esac
  cancel_deadline=$(( $(date +%s) + cancel_wait_s ))
  while :; do
    pending_alarms || { echo "ABORT: could not read the pending alarms after 09"; exit 22; }
    cancelled=$alarm_count
    [ "$cancelled" = "0" ] && break
    [ "$(date +%s)" -lt "$cancel_deadline" ] || break
    sleep 2
  done
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
  if ! due=$(device_due 22); then
    fail_count=$((fail_count + 1))
    failed_names+=("$flow")
    echo "FAIL $flow (not run: the device clock cannot place the due time inside today)"
    continue
  fi
  read -r due_hour due_minute today <<<"$due"

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
