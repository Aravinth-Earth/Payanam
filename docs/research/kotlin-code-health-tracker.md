# Kotlin Code Health — Experiment Tracker

> Started: 2026-08-18 | Branch: research/kotlin-code-health
> Method: Karpathy auto-research — micro-experiments, ~5min each

## Baseline

| Metric | Value |
|--------|-------|
| Total files | 154 |
| Total lines | 50,189 |
| Files >1000 lines | 6 |
| Files >700 lines | 17 |
| Max imports in one file | 86 (TasksScreen.kt — WARNING zone) |
| compileDebugKotlin time | 1m 25s |

## Top Complexity Targets

| File | Lines | Imports | Functions | Priority |
|------|------:|--------:|----------:|----------|
| AppPreferencesViewModel.kt | 1709 | 47 | 108 | C/D |
| TasksScreen.kt | 1246 | 86 | 12 | A/C |
| LensViewModel.kt | 1134 | 31 | 37 | C |
| TasksViewModel.kt | 1052 | 35 | 18 | C |
| TimeViewModel.kt | 1041 | 32 | 30 | C |
| SettingsViewModel.kt | 1019 | 43 | 41 | C |
| MainActivity.kt | 931 | 64 | 18 | C |
| SettingsDataSections.kt | 902 | 57 | 8 | C |
| TimeScreen.kt | 857 | 80 | 4 | A/C |
| DatabaseInitViewModel.kt | 857 | 25 | 26 | C |

## Iteration Log

| # | Time | Category | File | Change | Lines Δ | Imports Δ | Build | Committed |
|---|------|----------|------|--------|---------|-----------|-------|-----------|
| 1 | 15:45 | A:Import-Cleanup | TimeScreen.kt | Remove 4 unused imports (Task, TaskOccurrence, TimeEntry, LocalTime) | 856→854 (-2) | 80→76 (-4) | PASS | yes |
| 2 | 15:52 | A:Import-Cleanup | EditTaskScreenComponents.kt | Remove 39 unused imports | 238→199 (-39) | 71→32 (-39) | PASS | yes |
| 3 | 15:58 | A:Import-Cleanup | TaskDetailHistoryAndScore.kt | Remove 32 unused imports | 166→134 (-32) | 55→23 (-32) | PASS | yes |
| 4 | 16:05 | A:Import-Cleanup | AddTaskScreenComponents.kt | Remove 12 unused imports | 727→715 (-12) | 61→49 (-12) | PASS | yes |
| 5 | 16:10 | A:Import-Cleanup | TasksScreen.kt | Remove 5 unused imports | 1246→1241 (-5) | 86→81 (-5) | PASS | yes |
| 6 | 16:10 | A:Import-Cleanup | AddTaskScreen.kt | Remove 6 unused imports | 1148→1142 (-6) | 71→65 (-6) | PASS | yes |
| 7 | 16:11 | A:Import-Cleanup | TaskDetailComponents.kt | Remove 7 unused imports | -7 lines | 59→52 (-7) | PASS | yes |
| 8 | 16:11 | A:Import-Cleanup | EditTaskScreen.kt | Remove 4 unused imports | -4 lines | 70→66 (-4) | PASS | yes |
| 9 | 16:12 | A:Import-Cleanup | NotesScreen.kt | Remove 2 unused imports | -2 lines | 73→71 (-2) | PASS | yes |
| 10 | 16:12 | A:Import-Cleanup | DimensionVisuals.kt | Remove 2 unused imports | -2 lines | 64→62 (-2) | PASS | yes |
| 11 | 16:15 | A:Import-Cleanup | TimeBlockModalDialog.kt | Remove 1 unused import | -1 line | 64→63 (-1) | PASS | yes |
| 12 | 16:15 | A:Import-Cleanup | HabitCard.kt | Remove 6 unused imports | -6 lines | 56→50 (-6) | PASS | yes |
| 13 | 16:15 | A:Import-Cleanup | DatabaseInitDimensionSetupScreen.kt | Remove 5 unused imports | -5 lines | 60→55 (-5) | PASS | yes |
| 14 | 16:15 | A:Import-Cleanup | SettingsScreen.kt | Remove 2 unused imports | -2 lines | 54→52 (-2) | PASS | yes |
| 15 | 16:16 | A:Import-Cleanup | HabitActivityDetailSection.kt | Remove 1 unused import | -1 line | 58→57 (-1) | PASS | yes |
| 16 | 16:16 | A:Import-Cleanup | LensesScreen.kt | Remove 2 unused imports | -2 lines | 50→48 (-2) | PASS | yes |
| 17 | 16:16 | A:Import-Cleanup | LensesTimeDimensionSplitSection.kt | Remove 2 unused imports | -2 lines | 52→50 (-2) | PASS | yes |
| 18 | 16:16 | A:Import-Cleanup | DatabaseInitScreen.kt | Remove 1 unused import | -1 line | 65→64 (-1) | PASS | yes |
| 19 | 16:17 | A:Import-Cleanup | TimeScreenTimeline.kt | Remove 2 unused imports | -2 lines | 58→56 (-2) | PASS | yes |
| 20 | 16:17 | A:Import-Cleanup | SettingsComponents.kt | Remove 1 unused import | -1 line | 55→54 (-1) | PASS | yes |
| 21 | 16:25 | B:Func-Extract | EditTaskViewModel.kt | Extract buildTaskInput helper (20 lines) from updateTask | 211→212 (+1) | 3→4 (+1) | PASS | yes |
| 22 | 16:32 | A:Import-Cleanup | SettingsViewModel.kt | Remove 1 unused import | -1 line | -1 import | PASS | yes |
| 23 | 16:32 | A:Import-Cleanup | SettingsDialogs.kt | Remove 3 unused imports | -3 lines | -3 imports | PASS | yes |
| 24 | 16:32 | A:Import-Cleanup | DayScreen.kt | Remove 3 unused imports | -3 lines | -3 imports | PASS | yes |
| 25 | 16:32 | A:Import-Cleanup | LensesHabitScoreMatrixSection.kt | Remove 3 unused imports | -3 lines | -3 imports | PASS | yes |
| 26 | 16:32 | A:Import-Cleanup | LensesTimeStackedBarSection.kt | Remove 6 unused imports | -6 lines | -6 imports | PASS | yes |
| 27 | 16:33 | A:Import-Cleanup | TimeScreenEntryDialogs.kt | Remove 3 unused imports | -3 lines | -3 imports | PASS | yes |
| 28 | 16:33 | A:Import-Cleanup | ScoreDetailScreen.kt | Remove 2 unused imports | -2 lines | -2 imports | PASS | yes |
| 29 | 16:33 | A:Import-Cleanup | AppPreferencesViewModel.kt | Remove 2 unused imports | -2 lines | -2 imports | PASS | yes |
| 30 | 16:33 | A:Import-Cleanup | DayPlanTemplateScreen.kt | Remove 1 unused import | -1 line | -1 import | PASS | yes |
| 31 | 16:36 | A:Import-Cleanup | CompletionDialog.kt | Remove 2 unused imports | -2 lines | -2 imports | PASS | yes |
| 32 | 16:36 | A:Import-Cleanup | DayScreenSummary.kt | Remove 1 unused import | -1 line | -1 import | PASS | yes |
| 33 | 16:36 | A:Import-Cleanup | LensesScreenDimensionSections.kt | Remove 5 unused imports | -5 lines | -5 imports | PASS | yes |
| 34 | 16:36 | A:Import-Cleanup | TimeScreenDialogs.kt | Remove 3 unused imports | -3 lines | -3 imports | PASS | yes |
| 35 | 16:40 | A:Import-Cleanup | HabitActivityDetailSection.kt | Remove 1 unused import | -1 line | -1 import | PASS | yes |
| 36 | 16:40 | A:Import-Cleanup | LensesScreenTimeModuleSection.kt | Remove 1 unused import | -1 line | -1 import | PASS | yes |
| 37 | 16:40 | A:Import-Cleanup | TimeScreenEntryDialogs.kt | Remove 1 unused import | -1 line | -1 import | PASS | yes |
| 38 | 16:40 | A:Import-Cleanup | TasksViewModel.kt | Remove 2 unused imports | -2 lines | -2 imports | PASS | yes |
| 39 | 10:47 | B:Function-Extraction | TimeViewModel.kt | Extract launchTimeEntriesCollection/PlannedTasks/Occurrences helpers from loadEntriesForDate (141→12 lines) | 1040→1050 (+10) | N/A | PASS | yes |

## Summary

| Metric | Before | After | Change |
|--------|--------|-------|--------|
| Iterations | 0 | 38 | +38 |
| Files modified | 0 | ~35 | ~35 |
| Total imports removed | 0 | ~150 | -150 |
| Build failures | 0 | 0 | 0 |
| Success rate | - | 100% | - |

### Category Breakdown

- **A: Import Cleanup** — 37 iterations, ~150 unused imports removed across 35 files
- **B: Function Extraction** — 1 iteration, extracted buildTaskInput helper in EditTaskViewModel.kt

### Key Findings

1. **getValue/setValue false positives**: Kotlin's `by` delegation syntax uses getValue/setValue implicitly. Our scanner flagged these as unused, but they're required. Verified: all files with unused getValue/setValue also use `by` keyword.
2. **Biggest wins**: EditTaskScreenComponents.kt (39 imports removed), TaskDetailHistoryAndScore.kt (32 imports removed), AddTaskScreenComponents.kt (12 imports removed)
3. **CRLF line endings**: Many files have Windows-style line endings. sed commands with `$` anchors fail; Python scripts with `rstrip('``\r\n``')` work reliably.
4. **Detekt threshold impact**: TasksScreen.kt dropped from 86 imports (WARNING zone, threshold=70) to 77 — still above threshold but closer.

### Final Metrics

- **36 files changed**
- **24 insertions, 197 deletions** (net -173 lines)
- **~150 unused imports removed** across 35 files
- **1 function extraction** (buildTaskInput helper in EditTaskViewModel.kt)
- **0 build failures** — 100% success rate
- **All work local only** — nothing pushed to remote
