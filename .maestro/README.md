# Maestro tier — user journeys that need the device, not just the app

This is the **only** tier that can cross four boundaries the in-process Compose suite
(`app/src/androidTest/java/io/payanam/e2e/`, see `docs/e2e/SPEC.md`) cannot:

| Boundary | Example here |
|---|---|
| **Process death** | a reminder action tapped while the app is `am kill`ed, applied after the unlock |
| **System UI / OS state** | the notification shade, the permission dialog, the `SCHEDULE_EXACT_ALARM` app-op |
| **Cross-app state** | notifications granted/denied outside the app, then read on resume |
| **Persistence across a restart** | a row written before a kill, read back on a cold start |

Anything drivable **inside** the app process belongs in the in-process tier, which is ~7× faster and
does not need a phone. A flow is added here only when it needs one of the four.

There is **no shell wrapper**. Everything runs through `maestro test`: device state a flow changes is
changed with `launchApp.permissions` / `setPermissions`, order is Maestro's job, and each flow is
self-contained.

## Run

```bash
# whole suite (01, 06 and 07 excluded — see config.yaml)
maestro test .maestro

# one journey, by file
maestro test .maestro/03-action-complete.yaml

# a subset, by tag
maestro test .maestro --include-tags reminders
maestro test .maestro --include-tags tracking

# syntax check without a device
maestro check-syntax .maestro/03-action-complete.yaml

# FRESH INSTALL — wipes the app's real database (`pm clear`)
maestro test .maestro/01-fresh-setup.yaml
```

Artifacts (screenshots, hierarchy dumps, per-flow logs) go to `~/.maestro/tests/<timestamp>/<flow>/`.
Add `--test-output-dir build-logs/e2e` to keep them beside the repository instead.

## Preconditions

- **A provisioned app**: an installed build whose database was created with the passphrase in each
  flow's `env:` block (`E2ETestPass!2026`). `01-fresh-setup.yaml` creates exactly that state.
- **Notifications allowed** for the shade assertions. Flows that need them denied deny them
  themselves and restore the grant at the end.
- **Exact alarms allowed** for the reminder journeys (03/04/05/08/11):
  `adb shell appops set io.payanam SCHEDULE_EXACT_ALARM allow`. Without it the alarm is inexact,
  can arrive minutes late, and the 8-minute shade wait may expire. This is an app-op, so no flow can
  set it and no flow can assert it — it is a documented precondition.
- **Host clock ≈ device clock** (and no run within ~30 minutes of local midnight): the due time is
  computed with `evalScript` from the host clock, because a flow cannot read the device's clock.
- **The screen is unlocked and the app is installed**; Maestro never dismisses the keyguard.

## Locale

Every label matcher is a regex carrying **both shipped languages** (`core/strings…/values/` and
`values-ta/`), e.g. `ACTION_COMPLETE: "(Complete|முடி)"`. The flow runs unchanged on an English or a
Tamil device, and on any other locale (which falls back to English). Override any value with `-e`:

```bash
maestro test .maestro/03-action-complete.yaml -e ACTION_COMPLETE="Complete"
```

## What this tier deliberately does not assert

| Not observable from a flow | Where it is covered |
|---|---|
| the OS alarm count (armed / cancelled by archiving) | `TaskReminderSchedulerTest`, `NotificationSchedulerRegressionTest` (unit); `08-archive-cancels-reminder.yaml` documents the manual `dumpsys alarm` read |
| a live `TrackingService` after End/Stop | the tracking notification disappearing from the shade (flows 09/13) |
| the session timeout expiring after real elapsed time | `UnlockTest` + the security unit tiers; a flow has no clock control, so the real-time wait lives outside Maestro |
| the SAF file picker (export destination, import source) | `ImportSeamTest` at the ViewModel seam; the picker itself is system UI and stays manual |

## Layout

```
.maestro/
  config.yaml                  # flows, exclusions, artifact dir
  subflows/                    # shared steps, run only when a flow calls them
    unlock.yaml
    create-habit-with-reminder.yaml
    stop-running-entry.yaml
    archive-task.yaml
  01-fresh-setup.yaml          # destructive; run by file
  02-date-tag-publishes.yaml   # smoke: a dialog window publishes its tag
  03…05  reminder shade actions on a dead process
  06, 07 exact-alarm app-op (manual)
  08     archive a habit that has a reminder
  09     time entry lifecycle
  10     notification offer: denied → granted
  11     short-lead reminder warning
  12     persistence across a kill
  13     Stop on the tracking notification
```

## Device findings worth knowing

Measured on SM-A176B / Android 16 / ta-IN against the 1.1.0 release build:

- **A habit advances to its next occurrence** on Complete, Skip and Miss, so the row's *today* cell is
  gone from the list as soon as the action lands. Anything that must be asserted **after** the action
  is a one-time task, not a habit (flow `03` asserts under the **Completed** filter for this reason).
- **A gracefully killed app restores its last destination.** `killApp` + `launchApp` can come back on
  a form an earlier flow left open, and tapping the already-selected tab does not dismiss it. Use a
  force-stop start (`- launchApp`) when a flow needs a clean screen, and close a leftover form with
  `task_add.title_field` → Back where a graceful start is deliberate.
- **The habit list hides rows by preference.** "Hide completed today" and "Hide all marked today" are
  persisted per install; a completed or marked habit can be invisible, so a status assertion must not
  depend on them (flow `05` assumes marked rows are shown, and says so).
- **`?` in a label is a regex quantifier.** "Turn on notifications?" needs `\?` — unescaped, the
  matcher can never match. Any label with regex metacharacters must be escaped inside its `env` value.
- **Screenshots land in `~/.maestro/tests/<timestamp>/<flow>/`**, not the repository, unless
  `--test-output-dir` is passed.

## Writing a flow

- **Ids before text.** Every in-app control in these flows is a `testTag` published as a resource id
  (`TestTags.kt`). Text is only used for OS-drawn surfaces (the shade, the permission dialog) and for
  row content that has no stable tag.
- **Self-contained.** Handle both the locked and unlocked start with `subflows/unlock.yaml`; end on a
  state the next flow can start from, including any permission or setting you changed.
- **Unique data per run.** `evalScript` a name from `Date.now()`; a re-run must not match the last
  run's row.
- **No coordinates.** A tap by position is a defect in the app's selectors, not a flow technique.
- **Comment the why.** The long comments are the reason a flow is shaped the way it is; keep them
  accurate when you change a step.
