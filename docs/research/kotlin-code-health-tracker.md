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
