# Payanam E2E Test Suite — Specification
# Last Updated: 2026-09-12

## Goal
Fastest possible UI-driven regression suite covering every user-facing interaction in the app.
All via Maestro UI test automation framework only — no mocking, no internal state checks.

## Two tiers, one specification

| | Maestro tier (merge gate) | In-process tier (fast inner loop) |
|---|---|---|
| Framework | Maestro, black box | Compose UI Test, inside the app process |
| Full suite | `payanam_e2e.yaml` — 161 steps, ~409 s | `FullJourneyTest` — ~57 s |
| Smoke | `smoke/payanam_smoke.yaml` — ~192 s | `SmokeInProcessTest` — ~24 s |
| Restart phase | phase 9 in the same flow (`stopApp`) | `UnlockTest`, a second invocation |
| Clean state | `launchApp clearState: true` | a fresh install (the runner uninstalls between runs) |
| How to run | `build-android.ps1 -RunMaestro` / `-Smoke` | `build-tools/scripts/run-inprocess-tests.ps1 -Runs N -TestClass …` |

Both tiers execute the same journeys from this same spec; the in-process tier is not a replacement.
The Maestro tier drives the real, minified artefact and remains the merge gate. Measured on one device
(SM-A176B): the in-process tier is ~7× faster on the full journey and ~8× on the smoke subset.

Four differences are structural. Do not try to close them with shell access — `pm clear` and `adb` are
out of scope for both tiers:

1. **No `clearState`.** The app's own Delete All Data flow cannot stand in: it restarts the app
   process, which kills the instrumentation run. A fresh install is the only reset.
2. **No mid-flow `stopApp`.** An instrumentation run hosts every test method in ONE process, so the
   lock screen only appears in a run of its own — hence `UnlockTest` as a second invocation.
3. **Maestro's `text:` matches `contentDescription` as well; Compose's `hasText` does not.** The
   in-process harness matches both, mirroring Maestro.
4. **Work on `Dispatchers.IO` (Room, WorkManager, DataStore, OkHttp) is invisible to Compose's test
   clock.** Every in-process interaction waits for the expected state rather than trusting a settle.

**Outputs differ.** Maestro pulls screenshots to the host. The in-process tier writes them into the
app's external files dir, which needs adb or MTP to retrieve, so treat them as secondary evidence: the
authoritative artefacts there are the host-side JUnit XML, the HTML report and the per-test logcat.

## Architecture: Single Maestro Flow

**One YAML file, one execution cycle:**
1. `launchApp clearState: true` — ONCE at the very top
2. Fresh setup (onboarding → passphrase → dimensions → focus) — ONCE
3. ALL test scenarios run sequentially in the SAME session
4. No app restarts, no re-setups, no repeated steps
5. Navigation between modules via bottom nav taps

**Why single file:** Maestro subflow `runFlow` with `file:` chains other YAMLs, but they share the same app session. So subflows CAN be separate files, but they MUST NOT re-launch or re-setup. The master flow handles launch + setup once, subflows just navigate and interact.

## Selector Policy

**ONLY real selectors — ZERO coordinates:**
- `tapOn: "Button Text"` — match by visible text
- `tapOn` with `id: "<testTag>"` — match a Compose `testTag` surfaced as a resource-id
- `tapOn` with `scrollUntilVisible` — for off-screen elements
- `tapOn` with `index:` — when multiple same-text elements exist
- **NO `point:` coordinates** — ever
- **NO `a11y:`** — not supported in Maestro v2.10

### Two label mechanisms (verified on device)

| Element kind | Mechanism | Selector | Localized? |
|---|---|---|---|
| User-facing label (TalkBack announces it) | `Modifier.semantics { contentDescription = stringResource(...) }` | `text:` | Yes — `values/strings.xml` + `values-ta/strings.xml`, EN/TA parity mandatory |
| Test-only identifier | `Modifier.testTag("x")` **+** `Modifier.semantics { testTagsAsResourceId = true }` on an **ancestor** | `id:` | No — test ids are not user-facing strings |

Pick `testTag` when the element set is too large to localize (e.g. the 352-icon picker). Pick
`contentDescription` when a screen-reader user genuinely benefits (e.g. the 39-colour palette).

**Two traps:**
1. `testTag` with no ancestor setting `testTagsAsResourceId` is **invisible to Maestro**.
2. `text:` matching is regex/substring-based — `"Green 600"` also matches `"Light Green 600"`.
   Choose uniquely-distinguishable labels, or anchor with `- tapOn: "^Green 600$"`.

**Empty-content clickables** (a `Surface`/`Box` with `onClick` and no child text) expose nothing
to the a11y tree. Put the label on the clickable node: `.semantics { contentDescription = ... }`.

**Before writing a picker tap:** run `~/.maestro/bin/maestro hierarchy --compact` and confirm the
target shows `enabled=true`. Nodes missing it are disabled and swallow taps.

### When an element can't be found

1. STOP the test immediately
2. Log the gap: "Element X not found on screen Y — needs an a11y label or testTag"
3. Switch to dev mode: add the label/testTag to Kotlin code (see table above)
4. Build with `build-tools/scripts/build-android.ps1`
5. Install on device
6. Resume test development from that point

## Step Efficiency Rules

1. **Zero repetition:** Every action (create task, add note, etc.) happens ONCE in the flow
2. **Sequential navigation:** Tasks → Habits → Time → Journal → Notes → Lenses → Settings (left to right on bottom nav)
3. **Data flows forward:** Created tasks appear in Tasks tab, affect Lenses insights, show in Settings database info
4. **No redundant asserts:** Assert visible ONCE per screen, not multiple times for the same element
5. **No redundant screenshots:** One screenshot per key verification point, not per step

## Execution Order (optimized for zero backtracking)

```
1. FRESH SETUP (once)
   Onboarding → Skip → DB Create → Passphrase → Dimensions → Focus → Lenses

2. TASKS (navigate right from Lenses)
   Empty state → Create task (minimal) → Create task (all fields) →
   View detail → Complete task → Verify in Completed tab →
   Create recurring task → Search → Back to list

3. HABITS (navigate right from Tasks)
   Empty state → Create daily habit → Complete habit →
   View calendar → Sort → Search → Back

4. TIME (navigate right from Habits)
   Empty state → Start tracking → Stop tracking →
   Multiple entries → Time scales → Back

5. JOURNAL (navigate right from Time)
   Empty state → Add entry → Edit entry → Navigate dates → Back

6. NOTES (navigate right from Journal)
   Empty state → Add note → Add multiple → Edit → Search → Back

7. LENSES (navigate right from Notes)
   Main view → Dimension detail → Insights → Charts →
   Day detail → Score breakdown → Back

8. SETTINGS (navigate right from Lenses)
   Overview → Theme dark → Theme light → Theme system →
   Font change → Time format → Language Tamil → Language English →
   Default landing → Tab visibility → Life dimensions →
   Auto-backup → Database info → Export → Logs →
   About → Debug toggle → Security timeout → Back

9. PASSPHRASE (from Settings)
   Change passphrase → Wrong current → Back to Settings

10. SCORING (from Settings)
    View config → Adjust weight → Back to Settings

11. VERIFY INSIGHTS (navigate back to Lenses)
    All data populated → Charts render → Scores calculated

12. EXPORT/IMPORT (from Settings)
    Export → Delete all → Fresh setup → Import → Verify data

13. UNLOCK FLOW (kill + relaunch)
    Stop app → Launch → Wrong passphrase → Correct passphrase →
    Verify data persists
```

## Screenshots

One screenshot per key state change:
- After each empty state assertion
- After each CRUD create
- After each settings toggle
- After insights verification
- After export/import round-trip
- After unlock verification

## Build Pipeline (when a11y gaps found)

```bash
# When test fails due to missing selector:
# 1. Add a11y label to Kotlin code
# 2. Build:
pwsh build-tools/scripts/build-android.ps1
# 3. Install:
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 4. Resume test from that point
```

## Output

Date-time stamped folder per run:
```
output/e2e/YYYY-MM-DD_HH-mm-ss/
  ├── maestro.log          # Full Maestro output
  ├── screenshots/         # All takeScreenshot captures
  └── result.json          # Pass/fail per step
```

## What's NOT in scope (yet)

- Biometric unlock (requires device enrollment)
- Driving the SAF picker itself (a system UI, out of reach for both automated tiers). The import
  journey behind it is covered by `ImportSeamTest` at the ViewModel seam; picking a file is
  the one manual step (SCENARIOS.md J3a)
- Multi-device sync (not implemented)
- Notification testing
