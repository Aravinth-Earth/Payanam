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

**Nightly recipe.** A session creates rows and does not remove them, so the database grows a little
every run and the lists get long enough that scrolls matter. Start the night with the reset — it
re-establishes the passphrase, debug logging and auto-backup in one flow — then run everything:

```bash
maestro test .maestro/01-fresh-setup.yaml                      # pm clear + provision (~4 min)
maestro test .maestro --test-output-dir build-logs/e2e         # root flows + sessions
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
`run-as`. The paths above are the **release** package's: the logger and the backup coordinator append
`-debug` when the application id ends in `.debug` (`UnifiedLogger.kt:372`,
`DatabaseBackupCoordinator.kt:152`), so a session run against `io.payanam.debug` writes
`Documents/Payanam-debug/exported-logs/` and `Documents/Payanam-debug/data/export/auto_bk_…/`
instead — pulling the `Payanam/` tree there leaves an empty directory.

```bash
# 1. interact — one launch + one unlock for the whole session
maestro test .maestro --include-tags session --test-output-dir build-logs/e2e

# 2. pull the two artefacts each session exported, into a tree of this pull's own: the log and
#    snapshot folders accumulate across runs, and the six pairs below are picked by position, so a
#    stale file must not be in the list.
#    The destinations must exist first — a missing dest is created by adb and gets the source's
#    CONTENTS one level shallower than step 3's paths expect, so --log/--db would match nothing.
#    `Payanam/` is the release path; a `.debug` install writes `Payanam-debug/` (see above).
pull=build-logs/e2e/pull-$(date +%Y%m%d_%H%M%S)
mkdir -p "$pull/logs" "$pull/db"
adb pull /sdcard/Documents/Payanam/exported-logs "$pull/logs"
adb pull /sdcard/Documents/Payanam/data/export  "$pull/db"

# 3. verify log ∧ data ∧ media, one session at a time. The one launch wrote six of each artefact,
#    so each pair is selected rather than taken as "the newest":
#      --log     the log whose filename stamp is that session's start (the file's own `Session:`
#                header repeats it). Sorting the six ascending puts them in 01..06 order;
#      --db      the auto_bk_ folder stamped inside that log's window — a session backs up and then
#                exports its log before the next one starts, so that same order holds;
#      --maestro the flow's own output dir, which Maestro names after the flow's `name:`.
#    The pairing is checked, not trusted: db/session-window re-derives the window from the log and
#    fails a snapshot before the log's first line or more than 5 s after its last.
run=$(ls -1d build-logs/e2e/20* | tail -1)   # `latest` would shadow the stamp-named dir in the glob
mapfile -t logs  < <(ls -1  "$pull"/logs/exported-logs/*.log | sort)
mapfile -t snaps < <(ls -1d "$pull"/db/export/auto_bk_* | sort)

sessions=(
  "Session — tasks and habits, with logging, backup and log export|--entities 2 --expect-occurrence completed"
  "Session — notes, journal and a manual time entry|--entities 0 --expect-note-deleted --expect-time-entry --expect-journal"
  "Session — archive one task and delete another|--entities 2 --expect-archived 1 --expect-deleted 0"
  "Session — a habit's list controls and its deletion|--entities 1 --expect-deleted 0"
  "Session — a preference survives a cold start (theme and landing)|--entities 0 --expect-logged-pref theme_mode --expect-logged-pref launch_destination_route"
  "Session — focus mode and insights visibility|--entities 0 --expect-logged-pref chart_journal_module_enabled --expect-pref focus_mode_active_preset=full_suite"
)

fail=0
for i in "${!sessions[@]}"; do
  IFS='|' read -r flow flags <<< "${sessions[$i]}"
  ./gradlew :tools:e2e-verify:run --args="\
    --log ${logs[$i]} \
    --db ${snaps[$i]}/payanam.db \
    --passphrase 'E2ETestPass!2026' \
    --maestro \"${run}/${flow}\" \
    --min-screenshots 5 \
    --allow-error 'Failed to re-arm task reminders' \
    --allow-error 'Database requested while session closed' \
    ${flags}" || fail=$((fail + 1))
done
echo "sessions failed: ${fail}"
```

`--entities` defaults to **1** (`Main.kt:190`), so every session states its count — a session that
creates no task or habit passes `--entities 0` rather than inheriting the default:

| # | Session | Flags for `:tools:e2e-verify` |
|---|---|---|
| 01 | tasks and habits | `--entities 2 --expect-occurrence completed` |
| 02 | notes, journal, time | `--entities 0 --expect-note-deleted --expect-time-entry --expect-journal` |
| 03 | task lifecycle | `--entities 2 --expect-archived 1 --expect-deleted 0` |
| 04 | habit controls | `--entities 1 --expect-deleted 0` |
| 05 | preferences | `--entities 0 --expect-logged-pref theme_mode --expect-logged-pref launch_destination_route` |
| 06 | focus and insights | `--entities 0 --expect-logged-pref chart_journal_module_enabled --expect-pref focus_mode_active_preset=full_suite` |

`--entities 0` is a stated non-assertion, not a silent one: `db/session-tasks` and `log/created-ids` print
`not asserted (--entities 0)` instead of failing on an empty prefix, and the session's own checks carry
the data side — 02 the note, the time entry and the journal; 05 and 06 the preferences they wrote. In
03 the creation order **is** the assertion: the deleted task is made second, so index 0 is the deleted
one and index 1 the archived one (`03-task-lifecycle.yaml:11`–`:12`).

The verifier checks: the log parsed and started a session; **zero unexplained `ERROR`/`FATAL` lines**;
a backup confirmation is present (the worker's own line, or the Settings action's "Manual backup
succeeded" — WorkManager can log after the export); the DB opens read-only through the same SQLCipher
driver the desktop uses; the session's task rows exist and their occurrence statuses are reported; the
dimension catalogue reads; the snapshot's folder timestamp falls **inside** the session's log window —
not before its first line, and not more than 5 s after its last (a later periodic auto-backup holds
every session row, so it must fail rather than bind itself to this run); at least the expected
screenshots were captured. Exit code 0 = every layer agrees.

Flags select the assertions for the session:

| Flag | Assertion |
|---|---|
| `--entities N` | the newest N created task/habit ids must exist in the snapshot with the run's title. `0` is for a session that creates none: `db/session-tasks` and `log/created-ids` then report `not asserted (--entities 0)` instead of failing on an empty prefix |
| `--expect-occurrence <status>` | the newest created entity must own an occurrence with that status |
| `--expect-archived <i>` / `--expect-deleted <i>` | index *i* (0 = newest) is archived / absent in the snapshot |
| `--expect-note-deleted` | the note's create and delete log lines name the same id and the row is gone |
| `--expect-time-entry` | the manual entry's logged id exists in `time_entries` |
| `--expect-journal` | a non-empty journal response exists on a date the session's own log spans (first..last line, so a midnight-crossing run or a host in another timezone still passes — the verifier's date is not an oracle), and matches the log's `e2e-journal-<digits>` marker when a line carries one |
| `--expect-pref <key=value>` | the stored value must equal the literal given — for a preference the session restores to a known value |
| `--expect-logged-pref <key>` | the stored value equals the LAST value the session logged writing it — state-independent, for a toggle whose starting value a previous run may have left either way |
| `--allow-error <text>` | a known, filed anomaly (matched against the line's tag or message); anything unlisted still fails |

Two things the first real run proved about the design:

- **Paths are redacted in logs** (`LogSanitizer`), so the log can name a backup event but never its
  location. The snapshot is bound to the session by the timestamp window instead — that is what
  `db/session-window` asserts, with a bound on each side: the folder stamp must be `>=` the first log
  line and `<=` the last one plus 5 s.
- **`ERROR` lines can be legitimate for the app and still be anomalies for a session.** The first run
  logged a boot-time re-arm while the DB was locked; it is tracked with
  `--allow-error 'Failed to re-arm task reminders'` / `--allow-error 'Database requested while session
  closed'` rather than ignored. An unlisted error still fails the run.

The first session also found a **cross-layer divergence**: completing a habit from its detail screen
logged `TaskDetailViewModel.completeTask | Completion cancelled before it finished` and wrote **no**
occurrence row, because the screen navigates back while the write is still in flight. The UI looked
successful, the DB disagreed, the log named the cause. Until that is fixed, the session visits the
detail but does not complete from it, and `db/occurrences` is reported rather than asserted.


**The backup epilogue waits for the run, not for the last-run line.** It taps
`settings.run_backup_now`, waits for `settings.backup_in_progress` to appear (30 s), then for it to
disappear (120 s), and only then exports the log. The button carries one id at a time —
`run_backup_now` when idle, `backup_in_progress` only while a manual run executes — so a stale node can
never satisfy the wait. `settings.backup_last_run` is rendered from the persisted last *successful*
timestamp and is already on screen from the previous run, so waiting on it returned instantly and let
the log export beat the backup, which hard-failed `log/backup` on a correct run. The node is
observable only because the flag is held for a 3 s display floor measured from the tap
(`AppPreferencesViewModel.kt:506`–`:521`, `MIN_MANUAL_BACKUP_IN_PROGRESS_MS = 3_000L` at `:1038`) —
the `Manual backup succeeded` line is written *before* that delay (`:490`–`:498` precedes `:515`), so
its timestamp is not evidence the floor elapsed.

**Auto-Backup is left enabled on a test device by design** — periodic backups land in the same tree and
are harmless, and the preflight turns the setting on only when it is off, so re-runs are idempotent.
Rotation, if it is ever enabled, prunes old snapshots — pull immediately after each run.

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
- **Auto-backup ON before a session runs.** The switch row is outside the guard, but the interval and
  last-run rows and the Run Backup Now button all sit behind `prefsState.autoBackupEnabled`
  (`SettingsAutoBackupSection.kt:145`), so on a device where the setting is off the epilogue has no
  `settings.run_backup_now` to tap and the session fails at its last step. This is a flow step now:
  every session runs `subflows/ensure-auto-backup.yaml` as its first step after the boot gate. That
  subflow is idempotent — it taps the row only when it is `checked: false` — and then waits for
  `settings.run_backup_now` to appear, which is the proof the toggle took; a device already on pays
  only for the navigation. It leaves the app at the top of Settings, and the sessions that start
  elsewhere re-select their tab from there. `01-fresh-setup.yaml` still provisions the setting at
  first run.
- **Host clock ≈ device clock** (and no run within ~30 minutes of local midnight): the due time is
  computed with `evalScript` from the host clock, because a flow cannot read the device's clock. The
  verifier has no such dependency — it dates a session by the log's own timestamps.

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
    04-habits-controls.yaml    # habit create, search, sort, filter, delete
    05-preferences-persist.yaml # theme + landing survive a cold start
    06-focus-insights.yaml     # focus-mode preset and an insights switch
  subflows/                    # shared steps, run only when a flow calls them
    unlock.yaml
    create-task-with-reminder.yaml
    create-habit-with-reminder.yaml
    stop-running-entry.yaml
    archive-task.yaml
    ensure-auto-backup.yaml     # preflight: toggle auto-backup on (no-op when already on)
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
- **A DropdownMenu is its own window**, so the app root's tag exposure does not reach it: menu-item
  testTags compile and never publish until the menu itself calls
  `Modifier.exposeTestTagsAsResourceIds()`. Measured on the habit sort menu.
- **The Tasks filter is persisted across runs** (`currentFilter`), and `All` is deliberately
  unfiltered — it still lists archived rows. Sessions normalise to All at the start and assert
  "left the list" on the active layer.
- **`<dir>/latest` shadows the artifact dir** in a `ls -td build-logs/e2e/*` pick; select
  `build-logs/e2e/2026-*` when choosing the `--maestro` directory.

## Writing a flow

- **Ids before text.** Every in-app control in these flows is a `testTag` published as a resource id
  (`TestTags.kt`). Text is only used for OS-drawn surfaces (the shade, the permission dialog) and for
  row content that has no stable tag.
- **Self-contained.** Handle both the locked and unlocked start with `subflows/unlock.yaml`; end on a
  state the next flow can start from, including any permission or setting you changed.
- **Unique data per run.** `evalScript` a name from `Date.now()`; a re-run must not match the last
  run's row.
- **No coordinates.** A tap by position is a defect in the app's selectors, not a flow technique.
- **`scrollUntilVisible` returning COMPLETED is not "on screen".** It means Compose put the node in
  the semantics tree, and a node there can sit below the scroll viewport. Measured on the Galaxy A17
  (2026-11-19): `settings.auto_backup_switch` reported bounds `[84,2059][996,2091]` against a viewport
  ending at y=2052, the following `tapOn` aimed at (540,2075), landed on dead space, and the app
  logged no toggle; `settings.debug_card` failed the same way and its export buttons were never
  composed at all. So a scroll whose next step taps or waits on its target carries
  `centerElement: true` (`subflows/ensure-auto-backup.yaml:20`), and a control that only composes
  after the container expands or a toggle flips gets its own centred scroll before anything waits on
  it (`:41`–`:50`).
- **Why Settings and not the add form.** The class needs a scroll container whose last row can sit
  under a bar: `SettingsScreen` ends its scrolling `Column` with `.padding(paddingValues)` and no
  `navigationBarsPadding()`, so its viewport runs to the window bottom, under the app's bottom bar and
  the system bar alike. `AddTaskScreen` does call `.navigationBarsPadding()`, so its save button cannot
  be occluded and is left without `centerElement`. `EditTaskScreen` is the odd one out — it scrolls
  without consuming the inset — so its save button is the next candidate if a session ever fails there.
- **Comment the why.** The long comments are the reason a flow is shaped the way it is; keep them
  accurate when you change a step.
