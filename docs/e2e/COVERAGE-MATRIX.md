# Payanam On-Device UI Coverage Matrix

Last Updated: 2026-10-25

Purpose: a single, element-level map of every interactive surface in the app, with what an AI
agent can drive **on a real Android device today**, what needs a one-line selector added first, and
what is genuinely out of reach. This is the actionable companion to `SCENARIOS.md` (the
tier-neutral journey catalog) and `SPEC.md` (the tier's rules). It exists so "test every interactive
element like a real user" becomes a measurable checklist instead of an aspiration.

Scope: the Android in-process tier (`app/src/androidTest/java/io/payanam/e2e/`). Everything here is
about driving the **real app on a real device** through Compose UI Test — no mocks, no internal
state reads.

## 1. How to read this

| Gate | Meaning |
|---|---|
| ✅ **A** | Allowed now. A stable selector (visible text / `contentDescription` / `testTag`) already exists; the agent can drive it with `E2eHarness`. |
| 🏷️ **L** | Needs a label. The element is interactive but exposes no text, `contentDescription`, or `testTag` — an app-side one-liner must be added before it is driveable. |
| 🖐️ **C** | Coordinate tap only. No addressable node; the tap must land on a canvas/box by position. Works, but breaks the tier's zero-coordinate rule — prefer promoting to **A** with a label. |
| 📦 **D** | Data/state precondition. Driveable, but only from a seeded state (created rows, a flag on, or a provisioned install). |
| 🔒 **B** | Blocked. System UI, hardware, network, or a process-lifecycle constraint the tier cannot cross. |

Coverage column: **Covered** = asserted by an existing test; **Surface** = the screen is visited but
the element is not exercised; **None** = no on-device coverage.

Harness entry points (see `E2eHarness.kt`): `click`, `clickNav`, `clickTag`, `clickLast`, `type`,
`setChecked`, `isChecked`, `scrollTo`, `assertVisible`, `waitForGone`, `back`, `capture`, `dumpTexts`.

Selector vocabulary: labels resolve from the app's own copy via `E2eLabels` (`core/strings` →
`UiStrings`, or `R.string.*`), so every lookup is locale-correct. Names below are the `UiStrings`
property (camelCase of the `loc_*` / `settings_*` resource). `testTag` literals are called out.

## 2. Run recipe (agent closed loop)

```pwsh
# 1. Build the app (project rule: build before any run)
pwsh build-tools/scripts/build-android.ps1

# 2. One fresh-install class per invocation (a fresh install is the only clean state)
pwsh build-tools/scripts/run-inprocess-tests.ps1 -Runs 1 `
    -TestClass io.payanam.e2e.TasksCrudTest -KeepInstalled:$false

# 3. Lock-screen / reset classes run as their OWN invocation (separate process)
pwsh build-tools/scripts/run-inprocess-tests.ps1 -Runs 1 -TestClass io.payanam.e2e.UnlockTest
```

Rules that shape every row below (from `SPEC.md`): no `clearState`, no mid-flow `stopApp`, no
`pm clear`/adb, every step waits on app state (Room/WorkManager work is invisible to the test
clock), taps are selector-based, and **no agent pushes** — all work stays local.

## 3. First-run / onboarding / auth

| ID | Screen | Element / flow | Selector | Gate | Coverage | Suggested class |
|---|---|---|---|---|---|---|
| A1 | OnboardingIntroScreen | Next / Get Started (page forward) | `onboardingActionNext` → `onboardingActionStart` | ✅ A | Surface (FreshSetup advances only) | `OnboardingTest` |
| A2 | OnboardingIntroScreen | Skip Tour | `onboardingActionSkip` | ✅ A | None | `OnboardingTest` |
| A3 | OnboardingIntroScreen | Swipe between 3 slides | gesture on pager (no tag) | 🖐️ C | None | `OnboardingTest` |
| A4 | DatabaseInitScreen | Create new empty DB | `locCreateNewEmptyDatabase` | ✅ A | Covered (FreshSetup) | — |
| A5 | DatabaseInitScreen | Import database file | testTag `import_database_file_button` | 📦 D + 🔒 B | Surface (presence only) | `ImportUiTest` (picker blocked) |
| A6 | DatabaseInitScreen | Create-new wipe confirm / cancel | `locContinue` / `settingsActionCancel` | 📦 D | None | `DatabaseInitBranchesTest` |
| A7 | DatabaseInitScreen | Import wipe confirm / cancel | `locContinue` / `settingsActionCancel` | 📦 D | None | `DatabaseInitBranchesTest` |
| A8 | DatabaseInitScreen | Existing-DB "Continue with existing" | `locContinueWithExistingDatabase` | 📦 D | None | `DatabaseInitBranchesTest` |
| A9 | DatabaseInitScreen | Boot-issue "Recheck database" | `dbInitActionRecheckDatabase` | 📦 D | None | `DatabaseInitBranchesTest` |
| A10 | DatabasePassphraseSetupScreen | Passphrase + confirm + submit | `dbPassphraseInputLabel` / `dbPassphraseConfirmLabel` / `dbPassphraseSetupAction` | ✅ A | Covered (FreshSetup) | — |
| A11 | DatabasePassphraseSetupScreen | Show/Hide toggle (both fields) | `dbPassphraseShowToggle` / `dbPassphraseHideToggle` | ✅ A | None | `PassphraseValidationTest` |
| A12 | DatabasePassphraseSetupScreen | Validation errors (len/upper/lower/digit/symbol/match) | error strings | ✅ A | None | `PassphraseValidationTest` |
| A13 | DatabasePassphraseSetupScreen | Reset-local-data (migration-incompatible path) | `dbPassphraseSetupResetAction` | 📦 D | None | `DatabaseInitBranchesTest` |
| A14 | DatabasePassphraseUnlockScreen | Passphrase + Unlock | `dbPassphraseInputLabel` / `dbPassphraseUnlockAction` | ✅ A | Covered (UnlockTest) | — |
| A15 | DatabasePassphraseUnlockScreen | Show/Hide toggle | `dbPassphraseShowToggle` / `dbPassphraseHideToggle` | ✅ A | None | `UnlockNegativeTest` |
| A16 | DatabasePassphraseUnlockScreen | Wrong passphrase error | `dbPassphraseUnlock*` error | ✅ A | None | `UnlockNegativeTest` |
| A17 | DatabasePassphraseUnlockScreen | Lockout after N attempts | lockout string | ✅ A | None | `UnlockNegativeTest` |
| A18 | DatabasePassphraseUnlockScreen | Forgot passphrase → reset → confirm | `dbPassphraseUnlockForgotAction` / `dbPassphraseUnlockResetConfirm` | ✅ A | Covered (ResetJourneyTest landing) | `ResetJourneyTest` (extend) |
| A19 | DatabasePassphraseChangeScreen | Current/new/confirm + submit | `dbPassphraseChangeCurrentLabel` / `…NewLabel` / `…ConfirmLabel` / `dbPassphraseChangeAction` | ✅ A | Surface (dialog opened, backed out) | `PassphraseChangeTest` |
| A20 | DatabasePassphraseChangeScreen | Wrong-current error | `dbPassphraseChangeErrorCurrentInvalid` | ✅ A | None | `PassphraseChangeTest` |
| A21 | DatabaseInitDimensionSetupScreen | Add New dimension | `dbInitDimensionSetupAddNewAction` | ✅ A | Surface (empty-name validation only) | `DimensionSetupTest` |
| A22 | DatabaseInitDimensionSetupScreen | Per-row Edit | `locEdit` | ✅ A | Surface | `DimensionSetupTest` |
| A23 | DatabaseInitDimensionSetupScreen | Per-row Disable / Enable | `dbInitDimensionSetupDisableAction` / `…EnableAction` | ✅ A | None | `DimensionSetupTest` |
| A24 | DatabaseInitDimensionSetupScreen | Continue (verify & proceed) | `dbInitDimensionSetupContinueAction` | ✅ A | Covered (FreshSetup) | — |
| A25 | DimensionEditDialog | Name field + Save + Cancel | `dbInitDimensionSetupNameLabel` / `locSave` / `settingsActionCancel` | ✅ A | Surface | `DimensionSetupTest` |
| A26 | DimensionEditDialog | Colour picker trigger + swatch | `dimensionPickerChooseColor` / `dimensionColor<Name>` | ✅ A | Surface (opens picker, discards) | `DimensionSetupTest` |
| A27 | DimensionEditDialog | Icon picker trigger + swatch | `dimensionPickerChooseIcon` / testTag `dimension_icon_<key>` | ✅ A | Surface (opens picker, discards) | `DimensionSetupTest` |
| A28 | DimensionEditDialog | Duplicate name / colour / icon validation | `…ErrorUniqueNames` / `…UniqueColors` / `…UniqueIcons` | ✅ A | None | `DimensionSetupTest` |
| A29 | FocusModeSelectionScreen | Preset cards (Time+Habits / Journal / Tasks / Full Suite) | `presetSimpleTimeHabits` etc. | ✅ A | None (only Skip used) | `FocusModeTest` |
| A30 | FocusModeSelectionScreen | Continue / Skip | `locContinue` / `skipFocusMode` | ✅ A | Surface (Skip only) | `FocusModeTest` |
| A31 | SecurityStateUnavailableScreen | Reset security / Quit | `startupSecurityResetAction` / `startupQuitAction` | 📦 D | None | `SecurityRefusalTest` |
| A32 | Biometric unlock prompt / enrollment | — | — | 🔒 B | None | Out of scope (hardware) |

## 4. Tasks (TasksScreen, TASKS_ONLY)

| ID | Screen | Element / flow | Selector | Gate | Coverage | Suggested class |
|---|---|---|---|---|---|---|
| B1 | TasksScreen | Empty state | `locNoTasksDueToday` / `locNoTasksYet` | ✅ A | Covered | — |
| B2 | TasksScreen | Add-task FAB | `locAddTask` | ✅ A | Covered | — |
| B3 | TasksScreen | Filter chips: All/Active/Not Active | `locTaskFilterWithCount` + `locAll` / `widgetTrackingStatusActive` / `locNotActive` | ✅ A | None | `TasksFilterSortTest` |
| B4 | TasksScreen | Sub-chips: Overdue/Today/Future/Completed/Archived | `locTaskFilterWithCount` + `locOverdue` / `locToday` / `locFuture` / `locCompleted` / `locArchived` | ✅ A | None | `TasksFilterSortTest` |
| B5 | TasksScreen | Task search field | `locSearchTasks` (placeholder `locSearchTasksPlaceholder`) | ✅ A | None | `TasksFilterSortTest` |
| B6 | TasksScreenParts | Sort button + menu (Score/Due/Title/Created/Impact/Energy/Dimension) | `locSortTasks` + `locScoreHighToLow` … `locLifeDimension` | ✅ A | None | `TasksFilterSortTest` |
| B7 | TasksScreenCards | Task row tap → detail | task title (dynamic) | ✅ A | Covered (open + back) | `TasksCrudTest` |
| B8 | AddTaskScreen | Title field | `locTaskTitle` | ✅ A | Covered | — |
| B9 | AddTaskScreen | Description field | `locDescriptionOptional` | ✅ A | Covered | — |
| B10 | AddTaskScreen | Life-dimension dropdown + items | dimension label (dynamic) | ✅ A | None | `TasksCrudTest` |
| B11 | AddTaskScreen | Task type One-time / Recurring + Change | `locOneTime` / `locRecurring` / `locChange` | ✅ A | Covered (`TasksDetailsTest`) | — |
| B12 | AddTaskScreen | Recurrence dialog (presets, custom radios, weekday/monthly chips, add/remove, OK) | `locDaily` / `locWeekdays` / `locWeekly` / `locSpecificDaysOfWeek` / `locEveryNDays` / `locYearly` / `locWeekdayM`… / `locAdd` / `locRemove` / `locOk` | ✅ A | Covered (Preset→Daily path, `TasksDetailsTest`) | `TasksRecurrenceTest` (other presets) |
| B13 | AddTaskScreen | Due date + time pickers | `locSelectDate` / `locSelectTime` / `locOk` | ✅ A | None | `TasksCrudTest` |
| B14 | AddTaskScreen | Reminder Auto/Custom/Off + lead minutes | `locAuto` / `locCustom` / `locOff` / `locCustomLeadMinutes` | ✅ A | Covered (`TasksDetailsTest`) | — |
| B15 | AddTaskScreen | Scoring rows (Impact/Goal/Energy/Control) | `locImpact` / `locGoalAlignment` / `locEnergyRequired` / `locControlLevel` + option first words | ✅ A | Covered (`TasksDetailsTest`) | — |
| B16 | AddTaskScreen | Explicit urgency / focus / blocked / dependency fields | `locExplicitUrgency01` / `locFocusRequired01` / `locBlockedReason` / `locExternalDependency` | ✅ A | Covered (`TasksDetailsTest`) | — |
| B17 | AddTaskScreen | Tag editor + suggestions | `locTagsOptional` / `locTagsHint` | ✅ A | Covered (`TasksDetailsTest`) | — |
| B18 | AddTaskScreen | Duration picker field | `locDuration` | 🖐️ C | None | `TasksCrudTest` (tap-by-bounds) |
| B19 | AddTaskScreen | Save / Cancel | `locSaveTask` | ✅ A | Covered (save only) | `TasksCrudTest` |
| B20 | TaskDetailScreen | Back / Edit / Delete + confirm | `locBack` / `locEdit` / `locDelete` / `settingsActionCancel` | ✅ A | Surface (back only) | `TasksCrudTest` |
| B21 | TaskDetailComponents | Complete button | `taskNotificationActionComplete` | ✅ A | None | `TasksLifecycleTest` |
| B22 | TaskDetailComponents | Skip (recurring) | `taskNotificationActionSkip` | ✅ A | None | `TasksLifecycleTest` |
| B23 | TaskDetailComponents | Miss (recurring) | `locMiss` | ✅ A | None | `TasksLifecycleTest` |
| B24 | TaskDetailComponents | Archive | `locArchive` | ✅ A | None | `TasksLifecycleTest` |
| B25 | TaskDetailComponents | Reschedule → dialog (date/time/confirm) | `locReschedule` / `locRescheduleTask` | ✅ A | None | `TasksLifecycleTest` |
| B26 | StatusNoteDialog | Reason rows + note + confirm | `locNotEnoughTime` … / `locCompletionNoteOptional` | ✅ A | None | `TasksLifecycleTest` |
| B27 | CompletionDialog | Override time / duration + confirm | `locOverrideWithCustomTime` / `locOverrideWithCustomDuration` / `locMarkDone` | 🏷️ L (checkboxes unlabelled) | None | `TasksLifecycleTest` |
| B28 | EditTaskScreen | All fields + Save | `locSaveChanges` (+ fields as AddTask) | ✅ A | None | `TasksCrudTest` |
| B29 | ScoreDetailScreen | Open score detail + activity window | `locLensScoreDetailDay` / `activityDetailEarlier` / `activityDetailLater` / `locToday` | ✅ A | None | `TasksScoringTest` |

## 5. Habits (TasksScreen, HABITS_ONLY)

| ID | Screen | Element / flow | Selector | Gate | Coverage | Suggested class |
|---|---|---|---|---|---|---|
| C1 | HabitsTabContent | Empty state | `locNoHabitsYet` | ✅ A | Surface | `HabitsCrudTest` |
| C2 | AddTaskScreen | Create daily habit (recurring) | `locRecurring` + `locDaily` + `locSaveTask` | ✅ A | Surface (title-only save, may not persist) | `HabitsCrudTest` |
| C3 | AddTaskScreen | Create weekly / one-time habit | `locWeekly` / `locOneTime` | ✅ A | None | `HabitsCrudTest` |
| C4 | HabitCard | **Checkmark panel (complete/skip/miss)** | canvas — no addressable node | 🖐️ C / 🏷️ L | None | `HabitsCrudTest` (add `testTag` to canvas) |
| C5 | CheckmarkDialog | Done / Skip / Missed + note + Save | `locDone` / `taskNotificationActionSkip` / `locMissed` / `locSave` | ✅ A | None | `HabitsCrudTest` |
| C6 | HabitCard | Habit row tap → detail | habit title (dynamic) | ✅ A | None | `HabitsCrudTest` |
| C7 | HabitCard | CheckmarkPanelCanvas day cells | composed `contentDescription` per cell | 🖐️ C | None | `HabitsCrudTest` |
| C8 | TasksScreenParts | Habits search + sort + filter | `locSearchHabits` / `locSortHabits` / `locFilterHabits` | ✅ A | Covered (`HabitsFilterSortTest`) | — |
| C9 | DayMetricsStrip | Metric chips → day detail | chip text (dynamic) | 🏷️ L | None | `HabitsCrudTest` |
| C10 | TaskDetailScreen | Habit delete | `locDelete` + confirm | ✅ A | None | `HabitsCrudTest` |
| C11 | HabitActivityDetailSection | Window back/forward/Today + range chips + chart/table toggle | `activityDetailEarlier` / `activityDetailLater` / `locToday` / "7d"… / `activityDetailChartView` / `activityDetailTableView` | ✅ A | None | `HabitsDetailTest` |

## 6. Time (TimeScreen)

| ID | Screen | Element / flow | Selector | Gate | Coverage | Suggested class |
|---|---|---|---|---|---|---|
| D1 | TimeScreen | Empty state | `locNoTimeTrackedYet` | ✅ A | Surface | `TimeTrackingTest` |
| D2 | TimeScreen | Start tracking FAB → dialog → Start | `locStartTracking` / `locStart` | ✅ A | Covered | — |
| D3 | StartTrackingDialog | Dimension dropdown + item | dimension label | ✅ A | None | `TimeTrackingTest` |
| D4 | StartTrackingDialog | Task dropdown + item | `locNone` / `locTaggedTitle` | ✅ A | None | `TimeTrackingTest` |
| D5 | TimeScreen | Stop tracking | `locStopTracking` | ✅ A | Covered | — |
| D6 | TimeScreen | Add-time-entry FAB → dialog (dates, times, focus slider, note) → Add | `locAddTimeEntry` / `locStartDate` / `locEndDate` / `locFocusRating01` / `locFocusNoteOptional` / `locAdd` | ✅ A (slider = set-progress) | None | `TimeEntryEditTest` |
| D7 | TimeScreen | Prev / next day + Today | `locPreviousDay` / `locNextDay` / `locToday` | ✅ A | None | `TimeNavigationTest` |
| D8 | TimeScreen | Date-header → date picker → OK | formatted date / `locOk` | 🏷️ L (header) | None | `TimeNavigationTest` |
| D9 | TimeScalePresetSelector | Scale presets 2h…1m | `locTimeScale2h` … `locTimeScale1m` | ✅ A | None | `TimeScaleTest` |
| D10 | TimeScreenTimeline | Existing-entry block tap → modal | computed compact-label `contentDescription` | ✅ A | None | `TimeEntryEditTest` |
| D11 | TimeBlockModalDialog | Section expand CONTEXT/TIME/FOCUS | `locLifeDimension` / `locTime` / `locFocus` | ✅ A | None | `TimeEntryEditTest` |
| D12 | TimeBlockModalDialog | Confirm (testTag) | testTag `edit_time_entry_confirm_button` | ✅ A | None | `TimeEntryEditTest` |
| D13 | TimeBlockModalDialog | Delete entry / task actions (Start/Done/Skip/Miss/Edit/Archive) | `locDelete` / `locStart` / `locDone2` / `taskNotificationActionSkip` / `locMiss` / `locEdit` / `locArchive` | ✅ A | None | `TimeEntryEditTest` |
| D14 | TimeScreenTimeline | Tap empty gap → assign | testTag `time_gap_block` | ✅ A | Covered (`TimeGapDayPlanTest`) | — |
| D15 | TimeScreenTimeline | Planned-task block tap | no addressable node | 🏷️ L | None | `TimeGapTest` (add label) |
| D16 | TimeScreenVisualPanels | Dimension panel row / compact tile filter | no addressable node | 🏷️ L | None | `TimeVisualsTest` (add label) |
| D17 | TimeScreen | Day-plan CTA | `locDayPlanShort` / `locDayPlanModeCustom` | ✅ A | Covered (open → Custom mode → Save, `TimeGapDayPlanTest`) | — |
| D18 | DayPlanTemplateScreen | New / Edit / Delete template; name/desc; allocations; Save/Cancel | `locNewTemplate` / `locEditTemplate` / `locDeleteTemplate` / `locTemplateName` / `locSave` | ✅ A | None | `DayPlanTest` |

## 7. Journal (DayScreen, JOURNAL_ONLY)

| ID | Screen | Element / flow | Selector | Gate | Coverage | Suggested class |
|---|---|---|---|---|---|---|
| E1 | DayScreen | Empty / today view | screen text | ✅ A | Surface (visit only) | `JournalCrudTest` |
| E2 | DayScreenSummary | Overall reflection prompts (8) — type + autosave | testTag `journal_overall_prompt_<n>` (field) / prompt copy is literal English | ✅ A (tag) | Covered (`JournalPerDimensionTest`) | — |
| E3 | DayScreenSummary | Per-dimension card expand + prompts (5) | testTag `journal_dimension_card` / dimension `contentDescription` on badge | ✅ A (tag) | Covered (`JournalPerDimensionTest`) | — |
| E4 | DayScreen | Prev / next day + Today | `locPreviousDay` / `locNextDay` / `locToday` | ✅ A | None | `JournalCrudTest` |
| E5 | DayScreen | Date row → date picker → OK | formatted date / `locOk` | 🏷️ L (row) | None | `JournalCrudTest` |
| E6 | DayScreenSummary | Saving / saved status | `locJournalSavingForDate` / `locJournalSavedForDate` | ✅ A | Covered (`JournalPerDimensionTest`) | — |

## 8. Notes (NotesScreen)

| ID | Screen | Element / flow | Selector | Gate | Coverage | Suggested class |
|---|---|---|---|---|---|---|
| F1 | NotesScreen | Empty state | `locNoNotesYet` | ✅ A | Covered | — |
| F2 | NotesScreen | Add-note FAB → title/details → Save | `locAddNote` / `locTitle` / `locDetailsOptional` / `locSave` | ✅ A | Surface (title save) | `NotesCrudTest` |
| F3 | NotesScreen | Note card tap → edit dialog | note title (dynamic) | 🏷️ L | None | `NotesCrudTest` |
| F4 | NotesScreen | Delete note | `locDeleteNote` | ✅ A | None | `NotesCrudTest` |
| F5 | NotesScreen | Search + clear | `locSearchNotes` / `locClearSearch` | ✅ A | None | `NotesSearchTest` |
| F6 | NotesScreen | Filter chips All / per-dimension | `locAll` / dimension first word | ✅ A | None | `NotesSearchTest` |
| F7 | NotesScreen | Dimension picker + items | testTag `notes_dimension_picker` | ✅ A (tag) | Covered (`NotesTagsDimensionTest`) | — |
| F8 | NotesScreen | Tags field + suggestions | `locTagsOptional` / `locTagsHint` | ✅ A | Covered (typed, `NotesTagsDimensionTest`) | — |

## 9. Lenses (LensesScreen + insights)

| ID | Screen | Element / flow | Selector | Gate | Coverage | Suggested class |
|---|---|---|---|---|---|---|
| G1 | LensesScreen | Per-dimension view renders | `locPerDimension` | ✅ A | Surface | `LensesDataTest` |
| G2 | LensesScreen | Module cards expand/collapse (Time/Tasks/Habits/Journal/Notes) | `settingsActionCollapse` / `settingsActionExpand` | ✅ A | None | `LensesDataTest` |
| G3 | LensesScreen | Module CTAs (Go to Time/Tasks/Habits/Journal/Notes) | `locGoToTime` … `locGoToNotes` | ✅ A | None | `LensesDataTest` |
| G4 | LensesScreen | Time Insights (After Time Entries) | module cards + `locLensTimeAverageDailyTitle` | ✅ A | Covered (average-daily section, `LensesChartsTest`) | — |
| G5 | LensesHabitScoreMatrixSection | Metric dropdown (Score/Avg/Progress/Streak/Net/Continue) | `locLensMetricLabel` + item labels | ✅ A | None | `LensesDataTest` |
| G6 | LensesHabitScoreMatrixSection | Matrix row drill-in (DAY / per-dimension) | row label (dynamic) | 🏷️ L | None | `LensesDataTest` |
| G7 | LensesTimeInsightsScreen | Back | `locBack` | ✅ A | None | `LensesDataTest` |
| G8 | Dimension-split / trend sections | Window chips (1/7/30/90/180/365/All) + shift | `locLensDimSplitChipW1` … `locAllTime` / `activityDetailEarlier` / `activityDetailLater` | ✅ A | None | `LensesDataTest` |
| G9 | Average-daily section | Value mode Time / Share % | `locTime` / `locLensSharePercent` | ✅ A | None | `LensesDataTest` |
| G10 | Settings → Insights | 22 chart-visibility switches | row labels (contentDescription) | ✅ A | Covered (InsightsVisibilityTest) | — |

## 10. Settings

| ID | Screen | Element / flow | Selector | Gate | Coverage | Suggested class |
|---|---|---|---|---|---|---|
| H1 | SettingsScreen | Section card expand/collapse (all cards) | section title | ✅ A | Surface | `SettingsSectionsTest` |
| H2 | Appearance | Theme System/Light/Dark | `settingsOptionTheme*` | ✅ A | Covered | — |
| H3 | Appearance | Font Sans/Serif/Mono/Cursive | `settingsOptionFont*` | ✅ A | Surface (Sans/Mono only) | `SettingsAppearanceTest` |
| H4 | Appearance | Time format 12h/24h | `settingsOptionTimeFormat*` | ✅ A | Covered | — |
| H5 | Appearance | Language System/English/Tamil | `settingsLanguageOption*` | ✅ A | Surface (English only) | `LocalizationDeviceTest` |
| H6 | Default landing | Top-level + task-layer + task-state segmented | `settingsDatabaseTasks` … `locToday` | ✅ A | Surface (opened) | `SettingsLandingTest` |
| H7 | Focus mode | Preset segmented + tab visibility switches | `preset*` / `settingsDatabaseTasks` etc. | ✅ A | None | `FocusModeSettingsTest` |
| H8 | Life Dimensions | Edit row → name field → Save | `settingsDimensionEdit` / `settingsDimensionSave` | 🏷️ L (name field) | None | `SettingsDimensionsTest` |
| H9 | Life Dimensions | Colour picker + swatches | `dimensionPickerChooseColor` / `dimensionColor<Name>` | ✅ A | None | `SettingsDimensionsTest` |
| H10 | Life Dimensions | Icon picker + swatches | testTag `dimension_icon_<key>` | ✅ A | None | `SettingsDimensionsTest` |
| H11 | Life Dimensions | Weight slider | no label on slider | 🏷️ L | None | `SettingsDimensionsTest` |
| H12 | Life Dimensions | Reset to defaults / visibility toggle | `locResetToDefaults` / `dbInitDimensionSetupDisableAction` | ✅ A | None | `SettingsDimensionsTest` |
| H13 | Auto-Track Habit Time | Global switch + per-dimension switches | `settingsAutoTrackHabitTimeGlobal` / dimension label | ✅ A | None | `SettingsAutoTrackTest` |
| H14 | Day Boundary | Hour slider | `settingsDayBoundaryTitle` (contentDescription) | ✅ A | None | `SettingsDayBoundaryTest` |
| H15 | Insights Charts Visibility | 22 switches | row labels | ✅ A | Covered | — |
| H16 | Auto-Backup | Enable switch | `settingsAutoBackupEnable` | ✅ A | None | `SettingsBackupTest` |
| H17 | Auto-Backup | Interval dropdown + items | `settingsOptionBackup*` | ✅ A | None | `SettingsBackupTest` |
| H18 | Auto-Backup | Run Backup Now | `settingsActionRunBackupNow` | 📦 D | Covered (enable → run → "Last backup" line, `SettingsBackupRunTest`) | — |
| H19 | Auto-Backup | Rotation enable + count field | `settingsBackupRotationEnable` / count field | 🏷️ L (count field) | None | `SettingsBackupTest` |
| H20 | Scoring Config | Configure weights → sliders → Save / Reset | `settingsActionConfigureScoring` / `locSave` / `locResetToDefaults` | 🏷️ L (sliders) | Surface (opened) | `ScoringConfigTest` |
| H21 | Security & Timeout | Unlock timeout dropdown + items | current label / items | 🏷️ L (anchor) | None | `SettingsSecurityTest` |
| H22 | Security & Timeout | Biometric switch + enable dialog | `dbPassphraseUnlockBiometricTitle` / `settingsBiometricEnablePassphraseConfirm` | ✅ A | None | `SettingsSecurityTest` |
| H23 | Database | Stale-file delete / Clean Stale Files | `settingsActionDeleteFile` / `settingsDatabaseCleanStale` | 📦 D | None | `SettingsDatabaseTest` |
| H24 | Data Management | Export | `settingsActionExport` | 🔒 B (SAF destination) | None | Manual |
| H25 | Data Management | Import (single-file) | testTag `import_database_file_button` / `settingsActionImport` | 🔒 B (SAF source) | Surface (presence) | Manual (seam: ImportSeamTest) |
| H26 | Data Management | Import uHabits / Map imported habits | `settingsActionImportUhabits` / `settingsActionMapImportedHabits` | 📦 D | None | `SettingsImportTest` |
| H27 | Data Management | Update Passphrase entry | `settingsActionChangePassphrase` | ✅ A | Surface | `PassphraseChangeTest` |
| H28 | Data Management | Delete All Data + confirm (+ back-up-first prompt) | `settingsActionDeleteAllData` / `settingsDeleteExportPrompt*` | 🔒 B (kills process) | None | Out of scope (see §11) |
| H29 | Debug | Enable Debug Logging switch | `settingsDebugEnable` | ✅ A | None | `SettingsDebugTest` |
| H30 | Debug | Export current / all logs; habit diagnostics | `settingsActionExportLatestLog` / `settingsActionExportAllLogs` / `settingsActionRunHabitScoreDiagnostics` | ✅ A | Surface (current-session export) | `SettingsDebugTest` |
| H31 | About | View on GitHub / Open F-Droid | `settingsActionViewOnGithub` / `settingsUpdateOpenFdroid` | 🔒 B (external) | None | Manual |
| H32 | About | Update channel dropdown + items | channel label | 🏷️ L (anchor) | Covered (block read-only) | — |
| H33 | About | Auto-download / prompt-install / wifi-only / auto-check checkboxes | sibling labels | 🏷️ L | None | `UpdatePrefsTest` |
| H34 | About | Check/Download/Install update actions | `settingsUpdate*Button` | 🔒 B (network + installer) | None | Out of scope |

## 11. Blocked / out of scope (with reason)

| Surface | Reason | Workaround |
|---|---|---|
| SAF file picker (Export destination, Import source) | Android system UI, not the app's process | Import pipeline covered at the ViewModel seam (`ImportSeamTest`); the picker step stays manual |
| Biometric enrollment / prompt | Hardware + system dialog; needs enrolled biometrics | `DesktopBiometricUnlockPromptTest` on desktop; device path manual |
| APK download / package install | Network + system package installer | `UpdateChecker*` / `ApkDownloaderTest` unit tiers |
| Notification permission dialog + actual delivery | System UI + no notification test harness | `TaskReminderSchedulerTest` / `NotificationSchedulerRegressionTest` unit tiers |
| App widget rendering | App-widget host, outside the app process | `WidgetCommandTest` covers the command path only |
| `pm clear` / adb / force-stop | Project rule forbids; instrumentation dies with the process | A fresh install (`-KeepInstalled:$false`) is the reset |
| Delete All Data | Restarts the app process, killing the run | `FreshSetup` reaches the same clean state via a fresh install |
| Lock screen after session timeout | One instrumentation run = one process, so the session never closes | `UnlockTest` as its own invocation |
| System dark-mode follow / rotation / system font scale | Device-level settings, no adb | Font-scale sweeps via a large-scale device run of `FullJourneyTest` |

## 12. Selector gaps to close first (app-side one-liners)

These are interactive elements with no addressable node. Each needs a `Modifier.testTag(...)` or
`Modifier.semantics { contentDescription = … }` on the clickable node before it can be driven
reliably (and before the **C** rows above can become **A**).

| Element | File | Proposed selector |
|---|---|---|
| Habit checkmark canvas (complete/skip/miss surface) | `components/CheckmarkPanelCanvas.kt` | `testTag("habit_checkmark_panel")` + per-cell `contentDescription` already partly present |
| Planned-task block | `time/TimeScreenTimeline.kt` | `testTag("time_planned_block")` |
| Time-entry modal dimension field | `time/TimeBlockModalDialog.kt` | `testTag("time_entry_dimension_field")` |
| Dimension panel row / compact tile | `time/TimeScreenVisualPanels.kt` | `contentDescription` = dimension label |
| Journal date row | `day/DayScreen.kt` | `contentDescription` = formatted date |
| Journal dimension expand card | `day/DayScreenSummary.kt` | `contentDescription` = dimension label |
| Scoring-config sliders | `lenses/ScoringConfigScreen.kt` | `contentDescription` = factor label |
| Lenses matrix drill-in rows | `lenses/LensesHabitScoreMatrixSection.kt` | `contentDescription` = row label |
| Notes note card / dimension picker | `notes/NotesScreen.kt` | `testTag` / `contentDescription` |
| Settings: dimension name field, weight slider, backup interval anchor, rotation count, unlock-timeout anchor, update-channel anchor | `settings/*`, `components/DimensionVisuals.kt` | `contentDescription` = the row's label |
| Update checkboxes (4) | `app/.../settings/ui/SettingsDataSections.kt` | merge the sibling `Text` into the checkbox semantics |
| CompletionDialog override checkboxes | `components/CompletionDialog.kt` | merge sibling text into the checkbox |

## 13. Suggested new classes (priority order)

Each is one fresh-install invocation (or a provisioned run where noted), modelled on the existing
classes. Ordered by coverage value. **Status** marks what is implemented and green on the device
(SM-A176B, Android 16, ta-IN) as of 2026-10-16.

1. `TasksCrudTest` — all-fields create, edit, search, sort, filter, delete (closes B3–B20, B28). **DONE — green.**
2. `HabitsCrudTest` — create recurring habit, tap→CompletionDialog, long-press→CheckmarkDialog, detail, delete (closes C1–C10). **DONE — green.**
3. `TimeEntryEditTest` — add entry with a chosen dimension, scale presets, edit focus note, delete (closes D3–D13). **DONE — green.**
4. `NotesCrudTest` — add two, edit, search, delete (closes F2–F8). **DONE — green.**
5. `JournalCrudTest` — day view + date picker (closes E1, E5). **DONE — green; the prompt WRITE is not yet covered** (unlabelled fields need a per-prompt testTag).
6. `PassphraseLifecycleTest` — setup validation, show/hide, change-screen wrong-current (closes A11–A12, A19–A20). **DONE — green.**
7. `DimensionSetupTest` — dimension rename, disable/enable, empty-name validation (closes A21–A25, A28). **DONE — green.** Duplicate-name validation is NOT covered (the error banner does not clear after the duplicate is fixed — see findings).
8. `SettingsTogglesTest` — auto-backup, auto-track, day boundary, debug logging, security timeout, scoring-config save (closes H13–H22, H29). **DONE — green.** Biometric row not exercised.
9. `FocusModeTest` — focus-mode presets + tab-visibility switches (closes H7). **DONE — green.**
10. `LensesDataTest` — seeded task, Lenses module cards, habit-score drill-in (closes G2, G3, G6). **DONE — green.**
11. `UnlockNegativeTest` — wrong passphrase + lockout (closes A16–A17). **DONE — green.** Runs as its OWN invocation against a provisioned install (the lock gate needs a process with a configured passphrase and no open session): provision with a fresh-setup class kept installed, then run this class. Asserts attempt 1 and 2 render the "wrong passphrase" copy and attempt 3 renders the shared policy's first lockout (30s).
12. `TouchTargetSweepTest` — a cross-cutting ergonomics sweep, not an element-coverage class: on Lenses, Tasks, Time and Settings it asserts every DISPLAYED pressable node is at least 24dp in both dimensions, excluding chips/tabs/segmented/nav (`Selected`) and switches/checkboxes (`ToggleableState`). **DONE — green** (Lenses 16, Tasks 2, Time 17, Settings 9 measured controls, 0 below the floor). The collector lives in `E2eHarness.pressableTargets()` / `assertTouchTargets()`.
13. `TasksDetailsTest` — Add Task deep controls: reminder Custom + lead minutes, one scoring row moved, all four explicit property fields, tag editor, and the recurrence picker (Preset→Daily) saved as a habit (closes B11–B12 partial, B14–B17). **DONE — green.**
14. `HabitsFilterSortTest` — Habits sort menu (Name A→Z), filter menu (Due today only, a multi-toggle dismissed with Back), and search (query filters one of two habits out, clear restores both) (closes C8). **DONE — green.**
15. `TimeGapDayPlanTest` — the untracked-gap block opens the assign modal, and the day-plan dialog moves to Custom mode and saves (closes D14, D17). **DONE — green.** Device finding: the day-plan dialog's mode FilterChips report ZERO bounds in the dialog window (a click must go through the semantics action, not a tap).
16. `JournalPerDimensionTest` — an overall reflection write with the app's own saved indicator, and a per-dimension card expand/collapse revealing its prompts (closes E2, E3, E6). **DONE — green.**
17. `NotesTagsDimensionTest` — add a note with a tag and a CHANGED life dimension, save, and reopen: the dimension picker shows the chosen dimension (closes F7, F8). **DONE — green.** OPEN QUESTION: the tag did not reliably come back on reopen in one run (`NotesViewModel.createNote` writes the note and its tags as TWO repository calls, so a reopen can read before the second lands) — the tag is asserted as TYPED, not as persisted.
18. `LensesChartsTest` — seeds a time entry and a habit, opens Lenses, and asserts the Time module's "Average daily time" section (G4). **DONE — green.** G5 (the matrix metric dropdown) is SKIPPED when the Habits chart module is off — it is off by default, so covering G5 needs the chart flag enabled first.
19. `SettingsBackupRunTest` — enable Auto-Backup, press "Run Backup Now", and assert the card's "Last backup: …" line (closes H18). **DONE — green.** The rest of the Settings group (biometric, delete-all, export/import) is blocked in §11 and named as such.

### Device findings from the first batch (worth knowing)

- The Tasks list opens on the **today** filter; a task with a non-today due date is hidden there — use the **All** chip (which renders its count, e.g. "All (1)") before asserting a task.
- Filter chips and other "… (N)" rows need **substring** matching: `E2eHarness.clickSubstring`.
- The habit completion canvas tag is hidden by the card's `mergeDescendants`; address it with `useUnmergedTree = true`.
- A **tap** on the habit canvas opens the CompletionDialog; a **long-press** opens the CheckmarkDialog.
- The recurrence picker can open on its **Custom** tab, where the "Daily" preset is not rendered — select the **Preset** tab first.
- The time-entry modal's dimension field lives in a **collapsible CONTEXT section** and is opened by the Add-Time-Entry FAB via `TimeBlockModalDialog` (tag `time_entry_dimension_field`), not `TimeScreenEntryDialogs`.
- Text fields expose `EditableText`, which the harness's text matchers do not match; use `assertTextContains` for a field's content.
- The Journal top-bar "Today" button was not present in the semantics tree while a previous day was shown — date navigation is asserted via previous/next instead.
- A bottom-nav label (e.g. "Lenses") is present on EVERY screen, so asserting it does not prove the destination — assert a screen-specific label (e.g. `locPerDimension` on Lenses) or the FAB.
- The dimension-setup duplicate-name error banner does **not** clear after the duplicate is fixed, so a pass/fail on it is unreliable.
- A Compose `SlotTable` teardown crash (`ArrayIndexOutOfBoundsException` in `WrappedComposition.dispose`) can fail an otherwise-green run — re-run before treating it as a test failure.
- `NotesViewModel.createNote` writes the note and then its tags as two repository calls, so a note reopened in the same instant can show no tags. Measured 2026-10-16 while driving `NotesTagsDimensionTest`.

### Desktop counterpart — parity inventory

Last Updated: 2026-10-25

The desktop's in-process journeys live in `desktop/src/test/kotlin/io/payanam/desktop/` and share one
harness (`DesktopAppJourneyHarness`). They run under `:desktop:test` with **no device and no Xvfb** over the
shared screens and a real encrypted database created by the production create path.

**The harness drives the shell's own session.** `installProductionFixtureLayer` goes through the shell's
`DesktopDatabaseSessionController.openAndInstall`, exactly as `DesktopApp` does, so the controller HOLDS the
passphrase that the passphrase-change flow reads. A fixture that installed the data layer directly left the
controller with no passphrase, which is what made that surface undrivable.
The vault is built with the encryption probe the change flow gates on, and the database file lives in the
data root beside the security directory, so the path the controller opens is the path the init flow's
`DesktopDatabasePathResolver` resolves.

**What each journey proves** — the create/edit/delete spine or the surface's own action, never how it looks:

| Journey | Proves |
|---|---|
| `DesktopFirstRunJourneyTest` | the first-run gate lands on the shared database-init screen, then Tasks/Notes/Journal render |
| `DesktopDatabaseCreationJourneyTest` | first run → tour → create → passphrase → mandatory dimension setup → a real encrypted database whose dimensions read back |
| `DesktopTasksCrudJourneyTest` | create a task → open its detail → delete it |
| `DesktopTimeEntryJourneyTest` | add a time entry → change its dimension → delete it |
| `DesktopHabitsJourneyTest` | create a Daily habit → open its detail → delete it |
| `DesktopNotesJourneyTest` | add a note → edit its title → delete it |
| `DesktopJournalJourneyTest` | type an overall reflection (saved indicator) → expand a dimension card |
| `DesktopLensesJourneyTest` | a seeded time entry → the Time module's average-daily section |
| `DesktopFocusJourneyTest` | the Focus onboarding gate renders, its presets are present, its answer path runs |
| `DesktopSettingsJourneyTest` | reach the Auto-Backup card → press its enable switch |
| `DesktopScoringConfigJourneyTest` | Settings → Configure Scoring → move a weight → Save → back on Settings |
| `DesktopDayPlanJourneyTest` | Time → day-plan CTA → Custom mode → Save |
| `DesktopSettingsSectionsJourneyTest` | Insights-Charts-Visibility (open + press a switch) and Life-Dimensions (open the editor) |
| `DesktopUnlockNegativeJourneyTest` | a wrong passphrase is refused twice, then the third locks out |
| `DesktopPassphraseChangeJourneyTest` | Settings → change → the database is REKEYED: the new passphrase opens it and the old one does not |

**Log parity** is a two-tier contract. `E2eHarness.assertLogged` (Android) and
`DesktopAppJourneyHarness.assertLogged` (desktop) require the SAME `source`/`message` markers in each app's
own session log for the same interaction; `build-tools/scripts/parity-log-diff.py` diffs two logs, and
`build-tools/scripts/parity-log-check.sh <interaction>` runs both platforms and diffs. Five interactions
verify: notes, tasks, habits, time, journal.

**The fixture-DB tier.** `DesktopUnlockAgainstFixtureTest`, `DesktopExportedPayloadTest` and
`DesktopImportLoopHarnessTest` open a REAL database produced by the app rather than a fixture, and are
skipped unless pointed at one:

```
./gradlew -I <init-script> :desktop:test \
  -Dpayanam.loop.db=<path-to/payanam.db> -Dpayanam.loop.pwd=<path-to/a-file-whose-last-line-is-the-passphrase>
```

`build-tools/scripts/desktop-import-loop.sh` writes that init script and drives the whole import loop.

**Named gaps (not implied).** The desktop has NO journey for:
- **Import / export through the picker** — the picker is an AWT file dialog; the Android seam is covered by
  `ImportSeamTest`, and the desktop's import is covered up to the picker by `DesktopImportLoopHarnessTest`.

And two harness limitations cap what Settings/Focus can assert: the harness passes a **static**
`AppPreferencesState` and a **static** startup state, so a toggle's effect and a gate's advance cannot be
observed in-process.

**Finding recorded, not fixed.** A successful passphrase change closes the session and asks the process to
relaunch. Where the relaunch is refused (the desktop refuses when the launch command is a JVM), the shell is
left reading the bootstrap layer, which throws on the first `tasks` read. In the shipped packaged binary the
relaunch succeeds and the user never sees it; `DesktopPassphraseChangeJourneyTest` therefore asserts the
FILE's verdict rather than the success copy, and the refusal path's user-visible behaviour is a named gap.


## 14. Coverage snapshot

| Module | Interactive elements (approx.) | Covered | Surface | None |
|---|---|---|---|---|
| First-run / auth | 32 | 6 | 8 | 18 |
| Tasks | 29 | 3 | 3 | 23 |
| Habits | 11 | 0 | 3 | 8 |
| Time | 18 | 2 | 1 | 15 |
| Journal | 6 | 0 | 1 | 5 |
| Notes | 8 | 1 | 1 | 6 |
| Lenses | 10 | 1 | 1 | 8 |
| Settings | 34 | 4 | 8 | 22 |

Excluded from counts: elements classified 🔒 **B** in §11, which no on-device tier can reach.
