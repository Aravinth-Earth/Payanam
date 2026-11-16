# Migrating the in-process journeys to Maestro sessions

Last Updated: 2026-11-16

The Android e2e tier is moving from in-process Compose journeys to **Maestro sessions on the shipped
release build**. This file tracks that migration: what already runs as a session, what each in-process
class still owns, and what has to exist before a class can be deleted.

The rule for every journey: the app is the reference, not the test. A session that finds a product
defect gets a **fix** and then a **test that asserts the fixed behaviour** — the flow is never bent to
accept the defect.

## The layered harness

| Layer | Artefact | Where | Read by |
|---|---|---|---|
| Interaction | step timeline, screenshots, video | `build-logs/e2e/<ts>/<flow>/` | Maestro, and a human on a failure |
| Log | current session `.log` | `/sdcard/Documents/Payanam/exported-logs/` | `:tools:e2e-verify` |
| Data | `payanam.db` snapshot | `/sdcard/Documents/Payanam/data/export/auto_bk_…/payanam.db` | `:tools:e2e-verify` (SQLCipher, read-only) |
| Media | named milestone screenshots | the Maestro artefact dir | `:tools:e2e-verify` (count) and review |

A session ends with **Run Backup Now** and **Export current session log**; the pull + verify commands
are in `.maestro/README.md`. Correlations are by **UUID** (the id the app logs on create) and by
**timestamp window** (the snapshot folder must not predate the session's first log line) — never by a
title, which is redacted on purpose.

## Sessions today

| Session | Covers |
|---|---|
| `01-tasks-habits` | task create (defaults), edit, search, filter layers, sort; habit create + complete from detail; session start, backup, log export |
| `02-notes-journal-time` | note create with a tag, search, delete through the editor; one overall journal reflection that survives a tab change; one manual time entry |
| `03-task-lifecycle` | archive a task (leaves Today, listed under Archived); delete a second task through the confirmation |
| `04-habits-controls` | weekly habit create, search, sort by name, due-today filter toggle, delete from the detail |
| `05-preferences-persist` | theme Dark + landing Notes, force-stop, relaunch on Notes, restore System/Tasks |
| `06-focus-insights` | a focus-mode preset hides Tasks/Time and the full suite restores them; an insights switch round-trips |

## In-process classes and what is still missing

Delete a class only when its column says it is fully covered. Everything else keeps running until the
missing leg is a session step.

| Class | Session coverage | Missing before deletion |
|---|---|---|
| `TasksCrudTest` | 01 (create/edit/search/filter/sort) + 03 (delete) | create with non-default fields: dimension, due date/time, explicit scoring |
| `TasksDetailsTest` | partial (default create) | reminder Custom + lead minutes, scoring rows, tag editor, recurrence picker save |
| `HabitsCrudTest` | 01 (complete) + 04 (create/detail) | checkmark dialog (skip/miss) — the canvas cells are not addressable in the merged tree |
| `HabitsFilterSortTest` | 04 (search/sort/filter toggles) | filter menu multi-toggle dismissal; search-clear path |
| `TimeEntryEditTest` | 02 (manual entry) | edit an existing entry's focus note, scale presets, delete |
| `TimeGapDayPlanTest` | — | gap block → assign modal; day-plan dialog |
| `JournalCrudTest` | 02 (overall prompt) | date picker navigation; per-day write |
| `JournalPerDimensionTest` | — | per-dimension card expand/collapse |
| `NotesCrudTest` | 02 (create/search/delete) | second note, edit an existing note |
| `NotesTagsDimensionTest` | 02 (tag typed) | dimension picker change, tag persisted on reopen |
| `LensesDataTest` / `LensesChartsTest` | — | module cards, habit matrix drill-in, charts |
| `SettingsTogglesTest` | — | the settings sweep (needs control tags) |
| `SettingsBackupRunTest` | 01/02/03/04 epilogue (Run Backup Now) | the card's last-backup assertion is implicit |
| `FocusModeTest` | 06 (a preset hides its tabs, full suite restores them) | individual tab-visibility toggles |
| `InsightsVisibilityTest` | 06 (a module switch round-trips, proved from the log + snapshot) | the nested chart switches and the Lenses effect |
| `DimensionSetupTest` / `PassphraseLifecycleTest` | 01 leads setup | validation negatives, change-passphrase screen |
| `UnlockTest` / `UnlockNegativeTest` | every session's unlock subflow | wrong-passphrase and lockout (needs real time) |
| `ResetJourneyTest` | — | Delete All Data (session must ride the process restart) |
| `ImportPickerUnificationTest` | — | the SAF picker probe |
| `ImportSeamTest` | — | stays a JVM/robolectric test: it drives the ViewModel seam, not a journey |
| `WidgetCommandTest` | — | split: command semantics to unit, widget buttons to a session |
| `TouchTargetSweepTest` | — | stays: it measures geometry, which a black-box flow cannot |
| `LayoutProbeTest` | — | not a test; a desktop-parity probe |

`FullJourneyTest` and `SmokeInProcessTest` are ports of the retired Maestro flows; the four sessions
plus the root flows are their successors once the missing legs above are covered.

## Defects found by this harness (fixed)

| Finding | Layer that found it | Fix |
|---|---|---|
| Completing from the task detail was cancelled by the navigate-back, leaving the occurrence unwritten | log (`Completion cancelled before it finished`) + DB (no occurrence) vs UI (row hidden) | `TaskDetailViewModel` actions run under `NonCancellable` |

## Defects found and still open

None: the preferences session's finding below is fixed.

## Defects found by this harness (fixed)

| Finding | Layer that found it | Fix |
|---|---|---|
| **Changing the default landing screen crashed the app**: the nav graph's start destination was `remember`ed on the landing route, so changing the preference rebuilt the graph under the user and the Settings entry recomposed after destruction (`IllegalStateException: You cannot access the NavBackStackEntry's ViewModels after the NavBackStackEntry is destroyed`, retraced to `AndroidPayanamNavHostScreens.settings`). | a session that sets the landing and force-starts (the crash killed the process mid-run twice) | `PayanamNavHost` captures the landing route once (`remember(landingReady)`), so the preference applies on the next launch — which is what "default landing" means — and the open screen is untouched. Session 05 is now green and no longer tagged `blocked`. |

## Historic open-defect notes (superseded)
| **Settings destination touched a destroyed NavBackStackEntry** | `FATAL EXCEPTION: java.lang.IllegalStateException: You cannot access the NavBackStackEntry's ViewModels after the NavBackStackEntry is destroyed`, at `AndroidPayanamNavHostScreens.settings` (retraced from the release mapping). Reproduced twice inside `sessions/05-preferences-persist` (theme + landing changes around a force-stop restart) and **not** reproduced by switching the theme alone in a manual probe. | the process dies mid-session, so the run's epilogue never lands. `sessions/05-preferences-persist.yaml` is committed as the failing test and tagged `blocked`; `excludeTags: [blocked]` keeps it out of every run. Needs a deterministic reproduction (the force-stop/restart with a restored Settings entry is the leading hypothesis) before it can be fixed and the session unblocked. |
| Boot-time re-arm logs `ERROR` while the DB is locked (`BootReceiver.onReceive` → `DatabaseSessionManager.requireDatabase`) | the session log's two `ERROR` lines, allowlisted in the verify command | reminders are not re-armed until the user unlocks after a reboot; the error is real, not noise |

## Known, tracked behaviour the sessions encode

- A habit advances to its next occurrence on Complete/Skip/Miss, so a habit row can leave the list.
- `All` is the unfiltered layer: it still lists archived tasks. `Today`/the active layer excludes them.
- The Tasks filter is persisted across runs; sessions normalise to All before creating anything.
- A `DropdownMenu` is its own window: its item `testTag`s only publish when the menu itself calls
  `exposeTestTagsAsResourceIds()`.
- The notes search field and the habit search field both carry what was typed, so a later `tapOn` on
  that text hits the field unless the query is cleared first.
