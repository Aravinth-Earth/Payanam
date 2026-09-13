# Payanam E2E Test Suite — Specification
Last Updated: 2026-09-13

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
| Clean state | a fresh install (the runner uninstalls between runs) |
| How to run | `build-tools/scripts/run-inprocess-tests.ps1 -Runs N -TestClass …` |
| Result | per-run JUnit XML + HTML report + per-test logcat; the script exits 1 when any run is red |

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
   `WidgetCommandTest`, `InsightsVisibilityTest`, `UpdateChannelTypeTest`,
   `ImportPickerUnificationTest`, `ImportSeamTest`

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
