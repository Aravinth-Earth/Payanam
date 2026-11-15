# Maestro e2e — user journeys on the shipped APK

Two kinds of flow live here, and both run against the real build, not a test build:

- **`sessions/`** — one long **user session** each: launch, unlock, drive several features, export, exit.
  One launch and one unlock per session instead of one per feature.
- **root `*.yaml`** — the journeys a session cannot contain: process death (reminder actions on a killed
  app), the notification shade, an OS app-op, a reset that restarts the process.

They are the migration target for the in-process Compose journeys; the migration and what stays behind
are described in `docs/e2e/SPEC.md`. A session proves what a user can see. The other layers — the session
log, the database, the media — are checked after the run by `:tools:e2e-verify` (see *The layered
harness* below).

What only this tier can reach:

| Boundary | Example here |
|---|---|
| **Process death** | a reminder action tapped while the app is `am kill`ed, applied after the unlock |
| **System UI / OS state** | the notification shade, the permission dialog, the `SCHEDULE_EXACT_ALARM` app-op |
| **Cross-app state** | notifications granted/denied outside the app, then read on resume |
| **Persistence across a restart** | a row written before a kill, read back on a cold start |

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

## The layered harness

A session flow ends with two app actions that make the other layers pullable, then the run is verified
outside Maestro:

| Layer | Artifact | Where the app writes it | Read by |
|---|---|---|---|
| UI | step timeline (`commands.json`), screenshots, video | `~/.maestro/tests/<ts>/<flow>/` | Maestro, and a human on a failure |
| Log | current session `.log` (**Settings → Debug → Export current session**) | `/sdcard/Documents/Payanam/exported-logs/` | `:tools:e2e-verify` |
| Data | `payanam.db` snapshot (**Settings → Auto-Backup → Run Backup Now**) | `/sdcard/Documents/Payanam/data/export/auto_bk_…/payanam.db` | `:tools:e2e-verify` (SQLCipher, read-only) |

Both folders are **public Documents**, so `adb pull` reads them on a release build — no SAF, no
`run-as`. The debug package uses `Payanam-debug/` for both.

```bash
# 1. interact — one launch + one unlock for the whole session
maestro test .maestro --include-tags session --test-output-dir build-logs/e2e

# 2. pull the two artefacts the session exported
adb pull /sdcard/Documents/Payanam/exported-logs build-logs/e2e/latest/logs
adb pull /sdcard/Documents/Payanam/data/export  build-logs/e2e/latest/db

# 3. verify log ∧ data ∧ media against each other
./gradlew :tools:e2e-verify:run --args="\
  --log build-logs/e2e/latest/logs/exported-logs/<newest>.log \
  --db "$(ls -1d build-logs/e2e/latest/db/export/auto_bk_* | sort -t_ -k7,8 | tail -1)"/payanam.db \
  --passphrase 'E2ETestPass!2026' \
  --maestro build-logs/e2e/<timestamp> \
  --min-screenshots 5 \
  --allow-error 'Failed to re-arm task reminders' \
  --allow-error 'Database requested while session closed'"
```

The verifier checks: the log parsed and started a session; **zero unexplained `ERROR`/`FATAL` lines**;
a backup confirmation is present (the worker's own line, or the Settings action's "Manual backup
succeeded" — WorkManager can log after the export); the DB opens read-only through the same SQLCipher
driver the desktop uses; the session's task rows exist and their occurrence statuses are reported; the
dimension catalogue reads; the snapshot's folder timestamp is not older than the session's first log
line; at least the expected screenshots were captured. Exit code 0 = every layer agrees.

Flags select the assertions for the session:

| Flag | Assertion |
|---|---|
| `--entities N` | the newest N created task/habit ids must exist in the snapshot with the run's title (`0` for a session that creates none) |
| `--expect-occurrence <status>` | the newest created entity must own an occurrence with that status |
| `--expect-archived <i>` / `--expect-deleted <i>` | index *i* (0 = newest) is archived / absent in the snapshot |
| `--expect-note-deleted` | the note's create and delete log lines name the same id and the row is gone |
| `--expect-time-entry` | the manual entry's logged id exists in `time_entries` |
| `--expect-journal` | today has a non-empty journal response |
| `--allow-error <text>` | a known, filed anomaly (matched against the line's tag or message); anything unlisted still fails |

Two things the first real run proved about the design:

- **Paths are redacted in logs** (`LogSanitizer`), so the log can name a backup event but never its
  location. The snapshot is bound to the session by the timestamp window instead — that is what
  `db/session-window` asserts.
- **`ERROR` lines can be legitimate for the app and still be anomalies for a session.** The first run
  logged a boot-time re-arm while the DB was locked; it is tracked with
  `--allow-error 'Failed to re-arm task reminders'` / `--allow-error 'Database requested while session
  closed'` rather than ignored. An unlisted error still fails the run.

The first session also found a **cross-layer divergence**: completing a habit from its detail screen
logged `TaskDetailViewModel.completeTask | Completion cancelled before it finished` and wrote **no**
occurrence row, because the screen navigates back while the write is still in flight. The UI looked
successful, the DB disagreed, the log named the cause. Until that is fixed, the session visits the
detail but does not complete from it, and `db/occurrences` is reported rather than asserted.


**Auto-Backup is left enabled on a test device by design** — periodic backups land in the same tree and
are harmless. The epilogue turns it on only when it is off (the `Backups saved to Documents/Payanam`
hint is the state probe), so re-runs are idempotent. Rotation, if it is ever enabled, prunes old
snapshots — pull immediately after each run.

## Preconditions

- **A provisioned app**: an installed build whose database was created with the passphrase in each
  flow's `env:` block (`E2ETestPass!2026`). `01-fresh-setup.yaml` creates exactly that state.
- **Notifications allowed** for the shade assertions. Flows that need them denied deny them
  themselves and restore the grant at the end.
- **Exact alarms allowed** for the reminder journeys (03/04/05/08/11):
  `adb shell appops set io.payanam SCHEDULE_EXACT_ALARM allow`. Without it the alarm is inexact,
  can arrive minutes late, and the 8-minute shade wait may expire. This is an app-op, so no flow can
  set it and no flow can assert it — it is a documented precondition.
- **The screen stays awake.** A keyguard-free, always-on screen is a hard precondition — Maestro does
  not dismiss the keyguard, and a session that loses the screen fails mid-flow:
  `adb shell svc power stayon true`.
- **Host clock ≈ device clock** (and no run within ~30 minutes of local midnight): the due time is
  computed with `evalScript` from the host clock, because a flow cannot read the device's clock.

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
  config.yaml                  # flows, exclusions
  sessions/                    # ONE user session each: launch → features → export → exit
    01-tasks-habits.yaml       # task create/edit/search/filter, habit create/complete
    02-notes-journal-time.yaml # note create/search/delete, journal write, manual time entry
    03-task-lifecycle.yaml     # archive one task, delete another
  subflows/                    # shared steps, run only when a flow calls them
    unlock.yaml
    create-task-with-reminder.yaml
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

`tools/e2e-verify/` is the post-run oracle for a session: log ∧ database ∧ media.

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
