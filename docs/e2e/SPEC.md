# Payanam E2E Test Suite — Specification
Last Updated: 2026-10-25

## Goal
Fastest possible UI-driven regression suite covering every user-facing interaction in the app.
One automated tier: Compose UI Test inside the app process — no mocking, no internal state checks,
real user interactions only.

## The tier

| | In-process tier |
|---|---|
| Framework | Compose UI Test, inside the app process |
| Full suite | `FullJourneyTest` — the whole journey, one execution cycle (~110 s on SM-A176B) |
| Smoke | `SmokeInProcessTest` — the fast inner loop (~41 s) |
| Restart phase | `UnlockTest`, a second invocation |
| Clean state | one fresh install per ROUTE (see below), not per class |
| How to run | `build-tools/scripts/run-inprocess-tests.ps1 -Runs N -TestClass …` |
| Result | per-run JUnit XML + HTML report + per-test logcat; the script exits 1 when any run is red |

## Routes — one install and one setup per logical start

**A single instrumentation run hosts every test method in ONE app process, and the app's data survives
across classes in that run.** The tier used to pay a full install AND a full first-run setup for every single
class, because every feature class called `FreshSetup.run()`, which fails loudly unless the app is at
first-run. In one invocation only the first class is at first-run, so the suite was forced to one class per
invocation. That was a self-inflicted constraint, not a device one.

The suite is now grouped into **routes**, each one a single invocation, and a class only starts from scratch
when its SUBJECT is the fresh state:

| Route | Install | Classes | Why |
|---|---|---|---|
| A — first-run gates | fresh (`-KeepInstalled false`) | `FullJourneyTest`, `SmokeInProcessTest`, `DimensionSetupTest`, `FocusModeTest`, `WidgetCommandTest`, `ImportPickerUnificationTest`, `TouchTargetSweepTest` | the onboarding tour, the passphrase gate, the dimension step and the pending-command window only exist on a first run |
| B — provisioned app | clean start, reuse (`-KeepInstalled true`) | one shared invocation of 18 classes: `TasksCrudTest`, `TasksDetailsTest`, `HabitsCrudTest`, `HabitsFilterSortTest`, `NotesCrudTest`, `NotesTagsDimensionTest`, `JournalCrudTest`, `JournalPerDimensionTest`, `TimeEntryEditTest`, `TimeGapDayPlanTest`, `LensesChartsTest`, `LensesDataTest`, `InsightsVisibilityTest`, `SettingsTogglesTest`, `SettingsBackupRunTest`, `UpdateChannelTypeTest`, `LayoutProbeTest`, `ImportSeamTest` | they need a provisioned app and nothing else — and a CLEAN baseline to start from, because the reuse is inside the run, not between runs |
| C — closed session | reuse, new process | `UnlockTest` | the lock screen only appears once the session has closed, which needs a process restart |
| D — reset | reuse, new process | `ResetJourneyTest` | the reset restarts the app's own process |
| E — passphrase lifecycle | fresh (`-KeepInstalled false`), own invocation | `PassphraseLifecycleTest` | it walks the first run itself — onboarding → passphrase setup → change screen — instead of calling `FreshSetup.run()`, so it needs the app at first run and has to lead its own fresh install |

`-TestClass` maps to `-Pandroid.testInstrumentationRunnerArguments.class=`, which takes a comma-separated
list, so a route is just a list of classes — no runner change is needed.

**Measured (SM-A176B, 2026-10-17).** Route B as one invocation of 20 classes (the route's size at that
date) ran in **7 m 30 s** and passed **16**, against a baseline of **30 m 40 s** for the whole 32-test
suite with **30 failures** — 27 of them the `FreshSetup` wait expiring because the app was already
provisioned. The app's own log confirmed every class reported `setup=reused`: **the reuse mechanism
works.**

**Measured (SM-A176B, 2026-10-20).** The shared route B was **20 of 20 green** on that run, so every one of
the 18 classes the route holds today was green, and `ImportSeamTest` is one of them. It used to sit in
route A with a fresh install of its own; it was repaired to drive the REAL encryption probe, health check
and path resolver instead of stubs, so the file it imports is a real encrypted SQLCipher database on the
device and the seam under test is actually reached — which is what let it drop the fresh-install
requirement and **ride the shared route B with the rest of the feature
journeys.**

**Route B must start from a CLEAN install — the reuse is INSIDE a run, not between runs.** Three
consecutive invocations of the same route measured today say so:

- **B2** — started from an app an earlier single-class run had provisioned, with no journey data in it:
  **20 pass** of the 20 classes the route held then — effectively green.
- **B3** — started immediately after B2 finished, i.e. from the database B2 left behind: **11 of 20
  FAILED**, with "label not on screen" errors spread across unrelated screens — dimension labels, task
  titles, note titles. The app had unlocked normally (`setup=ready`), so this was NOT a provisioning
  failure: the earlier classes had created and renamed dimensions and left data the next run did not
  expect.
- **B4** — the same classes after `adb uninstall io.payanam.debug; adb uninstall io.payanam.debug.test`:
  **"OK (20 tests)"**, all green.

**`-KeepInstalled:$false` cannot supply that start.** It uninstalls the app **after** a run, so a run that
follows another one still begins on the database the previous run left behind — the B3 state. The clean
baseline has to be established **before** the invocation:

```pwsh
# 1. Clean baseline FIRST: uninstall both APKs. -KeepInstalled:$false uninstalls AFTER a
#    run, so it can never clean the start of the NEXT one.
adb uninstall io.payanam.debug
adb uninstall io.payanam.debug.test

# 2. Then the 18 classes of route B in ONE shared invocation. The route table above is
#    the list of record. -Runs 1, because a second cycle would start on this cycle's data.
$routeB = @(
    'TasksCrudTest', 'TasksDetailsTest', 'HabitsCrudTest', 'HabitsFilterSortTest',
    'NotesCrudTest', 'NotesTagsDimensionTest', 'JournalCrudTest', 'JournalPerDimensionTest',
    'TimeEntryEditTest', 'TimeGapDayPlanTest', 'LensesChartsTest', 'LensesDataTest',
    'InsightsVisibilityTest', 'SettingsTogglesTest', 'SettingsBackupRunTest',
    'UpdateChannelTypeTest', 'LayoutProbeTest', 'ImportSeamTest'
) | ForEach-Object { "io.payanam.e2e.$_" }

pwsh build-tools/scripts/run-inprocess-tests.ps1 -Runs 1 -KeepInstalled:$true -TestClass ($routeB -join ',')
```

**The reuse mechanism is not in question.** `FreshSetup.runIfNeeded()` still provisions the first class of
the run, and every later class in the SAME run still reuses that app — the mechanism the 2026-10-17
measurement confirmed. What has to be clean is the baseline the run STARTS from, not the state it shares
once it is going.

**One class is its own invocation, and the measurement says why.** `PassphraseLifecycleTest` walks the
first run itself — onboarding → database chooser → passphrase setup, and then the change screen out of
Settings — and it deliberately does NOT call `FreshSetup.run()`, because it has to assert the setup screen's
validation errors BEFORE a valid passphrase is submitted. In a shared or closed-session route it stops at
its own precondition check — "the passphrase lifecycle needs a clean app, but the app is already
provisioned" — so it passes only with a fresh install (`-KeepInstalled false`), leading its own invocation.

**What route B still needs, and why it is a per-class job.** The feature journeys were written for a fresh,
EMPTY app — a one-class-per-invocation world. Sharing one app breaks that assumption in four ways, and each
has to be fixed in the class it belongs to:

1. **Empty-state preconditions** (`assertVisible(noNotesYet)`, `assertVisible(noTasksDueToday)`) fail when an
   earlier class left data. Fix: assert **this class's item is absent**, not that the list is empty.
2. **Cleanup** — a class that creates list data must delete it. The CRUD journeys already did; the rest were
   given cleanup legs.
3. **Controls that only render with/without data** — e.g. the Time screen's day-plan control sits differently
   once entries exist.
4. **Settings a class leaves changed** — the language, the debug-logging switch, the insights-visibility
   toggles. A shared run is a shared SETTINGS state, not just a shared database.

Two more traps found the same way, both worth knowing before writing another class:

- **`assertTextContains` cannot check a LABELLED field's value.** The field's merged node carries the label
  as `Text` and the value as `EditableText`, and the assertion reports the label. Use
  `E2eHarness.fieldHolds`, which reads `EditableText`. This had never surfaced because no full run had ever
  got past setup to reach it.
- **A focused text field does not render its placeholder**, so waiting on the placeholder waits for
  something that is never shown. Await the field (a node with a SetText action) instead.

**One class was misclassified and moved back to route A.** `TouchTargetSweepTest` states its own premise —
"the four top-level surfaces a fresh install can reach with **no data**". In a shared run the Lenses screen
carries an earlier class's habits, whose rows measure a few dp tall, so it fails on data rather than on a
defect. Its subject is the data-free screen; it belongs with the first-run gates.

**The two rules that make a shared route safe.**

1. **A feature class calls `FreshSetup.runIfNeeded()`, never `run()`.** It provisions on a clean install and
   REUSES the app an earlier class in the same run provisioned, and it completes the mandatory dimension step
   when one is showing, so the call site is one line. A class whose subject IS a first-run gate keeps
   `run()`.
2. **A class deletes the list data it creates.** The app process is shared, so anything left behind is data
   the NEXT class reads. The CRUD journeys already did this; the classes that did not now do.

**The app's own reset is NOT a cleanup step.** It is real and it works, but it restarts the process
("restarting process for clean Room/Hilt re-initialization") and the instrumentation runs inside that same
process — so the test dies with it and the run reports **no result at all** rather than a failure. It can
stand in for a reinstall BETWEEN routes, never inside one. Same for the passphrase-unlock screen's reset.


**Maestro tier (retired 2026-09-13).** The suite began as Maestro YAML flows (full ~409 s, smoke
~192 s, driven by `build-android.ps1 -RunMaestro`/`-Smoke`). The in-process tier measured ~7×
faster on the full journey and ~8× on the smoke subset, so the Maestro tier was unwired from the
build script and its flow artefacts were removed — the specification and the journey catalog
(SCENARIOS.md) are its durable successors. Git history holds the YAML if the tier is ever revived.

## Structural constraints
These shaped the harness; do not try to close them with shell access — `pm clear` and `adb` are out
of scope:

1. **No `clearState`.** The app's own Delete All Data flow cannot stand in: it restarts the app
   process, which kills the instrumentation run. A fresh install is the only reset.
2. **No mid-flow `stopApp`.** An instrumentation run hosts every test method in ONE process, so the
   lock screen only appears in a run of its own — hence `UnlockTest` as a second invocation.
3. **Text matching includes `contentDescription`.** The harness matches a node by visible text or
   contentDescription, so a labelled icon-only control stays tappable — a11y labels are functional
   selectors here, not decoration.
4. **Work on `Dispatchers.IO` (Room, WorkManager, DataStore, OkHttp) is invisible to Compose's test
   clock.** Every interaction waits for the expected state rather than trusting a settle.

**Outputs.** Screenshots are written into the app's external files dir, which needs adb or MTP to
retrieve, so treat them as secondary evidence: the authoritative artefacts are the host-side JUnit
XML, the HTML report and the per-test logcat.

## Architecture: one journey, one execution cycle

`FullJourneyTest` runs the whole first-run journey in a single method chain inside ONE install:

1. Fresh setup (onboarding → passphrase → dimensions → focus) — once
2. Modules and settings phases run sequentially in the SAME session
3. No re-setups and no repeated steps; every action happens once
4. Journeys that need their own process or their own clean install are separate classes, each run by
   its own runner invocation — `UnlockTest` (the lock screen), `SmokeInProcessTest`,
   `WidgetCommandTest`, `ImportPickerUnificationTest`. The route table above is the authority on which
   classes share an invocation; `ImportSeamTest` rides the shared route B.

## Label & selector policy (app-side)

Taps are selector-based, never coordinate-based. The harness matches by visible text or
contentDescription.

### Two label mechanisms (verified on device)

| Element kind | Mechanism | Localized? |
|---|---|---|
| User-facing label (TalkBack announces it) | `Modifier.semantics { contentDescription = stringResource(...) }` | Yes — `values/strings.xml` + `values-ta/strings.xml`, EN/TA parity mandatory |
| Test-only identifier | `Modifier.testTag("x")` (+ `testTagsAsResourceId` on an ancestor for id-based tooling) | No — test ids are not user-facing strings |

Pick `testTag` when the element set is too large to localize (e.g. the 352-icon picker). Pick
`contentDescription` when a screen-reader user genuinely benefits (e.g. the 39-colour palette).

**Two traps:**

1. A `testTag` with no ancestor setting `testTagsAsResourceId` is invisible to id-based tooling.
2. Text matching is substring-based — `"Green 600"` also matches `"Light Green 600"`. Choose
   uniquely-distinguishable labels, or anchor the assertion.

**Empty-content clickables** (a `Surface`/`Box` with `onClick` and no child text) expose nothing
to the a11y tree. Put the label on the clickable node: `.semantics { contentDescription = ... }`.

### When an element can't be found

1. STOP the test immediately
2. Log the gap: "Element X not found on screen Y — needs an a11y label or testTag"
3. Add the label/testTag to Kotlin code (see the table above)
4. Build with `build-tools/scripts/build-android.ps1`
5. Re-run the class on the device

## Step efficiency rules

1. **Zero repetition:** every action (create task, add note, …) happens ONCE in the flow
2. **Sequential navigation:** Tasks → Habits → Time → Journal → Notes → Lenses → Settings
3. **Data flows forward:** created tasks appear in Tasks, affect Lenses insights, show in Settings
4. **No redundant asserts:** assert a screen ONCE per visit
5. **No redundant screenshots:** one capture per key verification point

## Journey order

Group A (setup) first, then B–F (modules), G (lenses, needs data), H (settings), I (passphrase),
J (export/import), K (scoring), L (cross-cutting). The dependency graph and the full per-journey
scenario inventory live in `SCENARIOS.md` (same directory).

## What's NOT in scope (yet)

- Biometric unlock (requires device enrollment)
- Driving the SAF picker itself (a system UI, out of reach for the automated tier). The import
  journey behind it is covered by `ImportSeamTest` at the ViewModel seam; picking a file is
  the one manual step (SCENARIOS.md J3a)
- Multi-device sync (not implemented)
- Notification testing
