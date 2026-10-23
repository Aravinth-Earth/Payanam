//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import io.payanam.core.ui.components.DimensionIconPicker
import io.payanam.core.ui.lenses.DimensionSplitSection
import io.payanam.core.ui.onboarding.OnboardingIntroScreen
import io.payanam.core.ui.notes.NotesScreen
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.BackupInterval
import io.payanam.core.ui.preferences.DimensionPreference
import io.payanam.core.ui.preferences.LaunchDestination
import io.payanam.core.ui.settings.DataManagementSettingsSection
import io.payanam.core.ui.settings.DatabaseSecurityBiometricActions
import io.payanam.core.ui.settings.ImportEncryptedDbPassphraseDialog
import io.payanam.core.ui.settings.SettingsAppearanceSection
import io.payanam.core.ui.settings.SettingsAutoBackupSection
import io.payanam.core.ui.settings.SettingsAutoTrackingSection
import io.payanam.core.ui.settings.SettingsDatabaseSecuritySection
import io.payanam.core.ui.settings.SettingsDefaultLandingSection
import io.payanam.core.ui.settings.SettingsScreenActions
import io.payanam.core.ui.settings.SettingsScreenContent
import io.payanam.core.ui.settings.SettingsTimeInsightsSection
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.time.TimeCalendarView
import io.payanam.domain.model.Note
import io.payanam.domain.model.Tag
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.NoteRepository
import io.payanam.domain.repository.TagRepository
import io.payanam.presentation.lenses.DimensionSplitState
import io.payanam.presentation.lenses.DimensionSplitWindow
import io.payanam.presentation.notes.NotesViewModel
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.presentation.tasks.TaskFilter
import io.payanam.shared.security.BiometricAvailability
import io.payanam.shared.updates.UpdateChannel
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The shared screen fixtures the UI-quality sweeps render.
 *
 * WHY THESE LIVE HERE. The interactive-control naming sweep ([InteractiveControlNamingTest]) and the
 * font-scale readability sweep ([UiReadabilityTest]) render the SAME shared screens, so the screens, the
 * fakes and the copy table they are drawn with are declared ONCE and consumed by both. A second copy of a
 * fixture is the failure mode this file exists to prevent: the two sweeps then measure two different
 * fixtures, and a screen that renders correctly under one copy can be broken under the other while both
 * stay green.
 *
 * A fixture is deliberately more than a composable. It carries the fakes the screen needs, the state that
 * decides whether the screen draws its controls at all, and - for the screens whose measured state is
 * reached by a press - the [SharedScreenFixture.prepare] step that reaches it. [renderScreen] applies all
 * of it, so the two sweeps cannot drift apart on how a screen is put on screen.
 *
 * WHY [SharedScreenFixture.prepare] EXISTS. Three of the fourteen fixtures are not measured in their
 * initial state: the Notes screen loads its list asynchronously (the sweep must wait for the note to be
 * published), the settings screen's debug switch is composed only once the debug card is opened, and the
 * icon picker's swatches exist only once its dialog is opened. Those are test-rule steps, and a
 * `@Composable` cannot perform them - a render and a reach are therefore both carried by the fixture and
 * applied by [renderScreen], rather than duplicated in each sweep.
 *
 * THE FIXTURES ARE REUSED, NOT INVENTED. Every fixture reuses state a neighbouring `desktopTest` file
 * already proves renders: the preferences state from `SettingsScreenTest.prefs` (including its deliberate
 * `isLoading = false`, without which every settings section draws only a "loading preferences" line), the
 * settings UI state from `SettingsDataManagementSectionsTest.state`, the timeline preferences from
 * `TimeEntryBlockSemanticsTest.preferences`, the notes repositories from `NotesScreenTest`'s fakes, and
 * the split state from `DimensionSplitSectionSemanticsTest.render`. Inventing state here would make the
 * sweeps a test of the fixture rather than of the screen.
 */
class SharedScreenFixture(
    val name: String,
    val minInteractiveControls: Int,
    val content: @Composable () -> Unit,
    /** Test-rule steps that put the screen into the state its sweep measures; empty for a settled screen. */
    val prepare: ComposeTestRule.() -> Unit = {},
)

/** The fourteen shared screens both sweeps render, in the order the naming sweep documents them. */
val sharedScreenFixtures: List<SharedScreenFixture> =
    listOf(
        SharedScreenFixture(
            name = "SettingsAppearanceSection",
            minInteractiveControls = AppearanceSegments,
            content = {
                var fontFamilyExpanded by remember { mutableStateOf(false) }
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    SettingsAppearanceSection(
                        prefsState = loadedPrefs(),
                        onAppLanguageChange = {},
                        onThemeModeChange = {},
                        onFontFamilyChange = {},
                        onTimeFormatChange = {},
                        fontFamilyExpanded = fontFamilyExpanded,
                        onFontFamilyExpandedChange = { fontFamilyExpanded = it },
                    )
                }
            },
        ),
        SharedScreenFixture(
            name = "SettingsDefaultLandingSection",
            minInteractiveControls = LandingSegments,
            content = {
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    SettingsDefaultLandingSection(
                        prefsState =
                            AppPreferencesState(
                                isLoading = false,
                                launchDestination =
                                    LaunchDestination(route = "tasks", taskFilter = TaskFilter.TODAY),
                            ),
                        onLaunchDestinationRouteChange = {},
                        onLaunchDestinationTimeChange = {},
                        onLaunchDestinationTasksChange = {},
                    )
                }
            },
        ),
        SharedScreenFixture(
            name = "SettingsTimeInsightsSection",
            minInteractiveControls = ChartSwitches,
            content = {
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    SettingsTimeInsightsSection(
                        prefsState =
                            AppPreferencesState(
                                chartTimeModuleEnabled = true,
                                chartTimeScoreCardsEnabled = true,
                                chartTaskModuleEnabled = true,
                                chartNoteModuleEnabled = true,
                            ),
                        onChartVisibilityChange = { _, _ -> },
                    )
                }
            },
        ),
        SharedScreenFixture(
            name = "DataManagementSettingsSection",
            minInteractiveControls = DataManagementButtons,
            content = {
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    DataManagementSettingsSection(
                        uiState = settingsUiState(),
                        onExportClick = {},
                        onImportClick = {},
                        onImportUhabitsClick = {},
                        onMapImportedHabitsClick = {},
                        onChangePassphraseClick = {},
                        onDeleteAllDataClick = {},
                    )
                }
            },
        ),
        SharedScreenFixture(
            name = "DimensionSplitSection",
            minInteractiveControls = SplitControls,
            content = {
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    DimensionSplitSection(
                        state = splitState(),
                        onWindowSelect = {},
                        onShiftLeft = {},
                        onShiftRight = {},
                    )
                }
            },
        ),
        SharedScreenFixture(
            name = "OnboardingIntroScreen",
            minInteractiveControls = OnboardingControls,
            content = {
                // No `CompositionLocalProvider` is needed here: the screen resolves its own copy from the
                // language it is handed, so the harness measures the shared composable rather than a
                // wrapper, exactly as OnboardingIntroScreenTest does.
                OnboardingIntroScreen(
                    language = UiLanguage.ENGLISH,
                    onFinished = {},
                )
            },
        ),
        SharedScreenFixture(
            name = "NotesScreen",
            minInteractiveControls = NotesControls,
            content = {
                // The real ViewModel over the same fakes NotesScreenTest declares, because a fake ViewModel
                // would be testing the test. The screen is async, so the fixture's prepare drives it to a
                // settled state before the sweep reads the tree.
                val viewModel =
                    remember {
                        NotesViewModel(
                            noteRepository = FakeNoteRepository(listOf(note())),
                            tagRepository = FakeTagRepository(),
                        )
                    }
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    NotesScreen(viewModel = viewModel)
                }
            },
            prepare = {
                // The list is published on Dispatchers.Default, which the harness clock does not control, so
                // this is a poll rather than an idle wait. The note is the fixture's own, so the wait is
                // about the SCREEN having settled rather than about a copy literal.
                waitUntil(WAIT_MS) {
                    onAllNodesWithText("Learn Kotlin").fetchSemanticsNodes().isNotEmpty()
                }
            },
        ),
        SharedScreenFixture(
            name = "TimeCalendarView",
            minInteractiveControls = TimelineBlocks,
            content = {
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    TimeCalendarView(
                        selectedDate = TIMELINE_DAY,
                        entries = listOf(timelineEntry()),
                        activeEntry = null,
                        plannedTasks = emptyList(),
                        pastOccurrences = listOf(timelineOccurrence()),
                        taskLookup = mapOf(TIMELINE_TASK_ID to timelineTask()),
                        hourHeightDp = TIMELINE_HOUR_HEIGHT_DP,
                        currentTime = TIMELINE_DAY.atTime(20, 0),
                        scrollState = rememberScrollState(),
                        use24Hour = true,
                        highlightedDimensionId = null,
                        preferences = timelinePreferences(),
                        onEntryClick = {},
                        onPlannedTaskClick = {},
                        onPastOccurrenceClick = { _, _ -> },
                    )
                }
            },
        ),
        SharedScreenFixture(
            name = "SettingsAutoBackupSection",
            minInteractiveControls = AutoBackupControls,
            content = {
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    SettingsAutoBackupSection(
                        prefsState = backupPrefs(),
                        onAutoBackupEnabledChange = {},
                        onAutoBackupIntervalChange = {},
                        onManualBackupNow = {},
                        onBackupRotationEnabledChange = {},
                        onBackupRotationCountChange = {},
                        onDismissAutoBackupFailure = {},
                        manualBackupInProgress = false,
                        intervalExpanded = false,
                        onIntervalExpandedChange = {},
                    )
                }
            },
        ),
        SharedScreenFixture(
            name = "SettingsAutoTrackingSection",
            minInteractiveControls = AutoTrackingSwitches,
            content = {
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    SettingsAutoTrackingSection(
                        prefsState = autoTrackingPrefs(),
                        onAutoTrackHabitTimeGlobalChange = {},
                        onAutoTrackDimensionChange = { _, _ -> },
                    )
                }
            },
        ),
        SharedScreenFixture(
            name = "SettingsDatabaseSecuritySection",
            minInteractiveControls = SecurityControls,
            content = {
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    SettingsDatabaseSecuritySection(
                        uiState = settingsUiState(),
                        onUpdateUnlockTimeout = {},
                        onDisableBiometricEnabled = {},
                        onEnableBiometricRequested = { _, onComplete -> onComplete(true) },
                        biometricActions = availableBiometricActions(),
                    )
                }
            },
        ),
        SharedScreenFixture(
            name = "SettingsScreenContent",
            minInteractiveControls = SettingsScreenControls,
            content = {
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    SettingsScreenContent(
                        prefsState = loadedPrefs(),
                        uiState = settingsUiState(),
                        snackbarHostState = SnackbarHostState(),
                        actions = SettingsScreenActions(),
                    )
                }
            },
            prepare = {
                // Debug logging is opened because a closed card composes no rows and the debug switch is the
                // one control on this screen that belongs to the screen itself rather than to a section.
                openCard(englishUiStrings.settingsDebugTitle)
            },
        ),
        SharedScreenFixture(
            name = "ImportEncryptedDbPassphraseDialog",
            minInteractiveControls = VerifyingGateControls,
            content = {
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    ImportEncryptedDbPassphraseDialog(
                        passphraseError = null,
                        isVerifying = true,
                        onConfirm = {},
                        onDismiss = {},
                    )
                }
            },
        ),
        SharedScreenFixture(
            name = "DimensionIconPicker",
            minInteractiveControls = IconSwatches,
            content = {
                CompositionLocalProvider(LocalUiStrings provides englishUiStrings) {
                    DimensionIconPicker(
                        selectedIconKey = PICKER_ICON_KEY,
                        usedIconKeys = emptySet(),
                        onSelect = {},
                    )
                }
            },
            prepare = {
                // The swatches live in the picker's dialog, which the picker's own button opens. The floor is
                // a swatch count, so the dialog has to be open or the sweep measures the button alone.
                onNodeWithText(englishUiStrings.dimensionPickerChooseIcon).performClick()
            },
        ),
    )

/**
 * Renders [fixture] at the given font scale and drives it to the state its sweep measures.
 *
 * The density is rebuilt with [fontScale] applied while the fixture's own [LocalUiStrings] provider is left
 * in place, so a sweep at 2x measures the same copy and the same controls the 1x sweep does, with every
 * text node's size scaled. [SharedScreenFixture.prepare] is applied before the final idle wait, so a screen
 * whose measured state is reached by a press is measured in that state rather than in its initial one.
 */
fun ComposeContentTestRule.renderScreen(
    fixture: SharedScreenFixture,
    fontScale: Float = 1f,
) {
    setContent {
        CompositionLocalProvider(
            LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale),
        ) {
            fixture.content()
        }
    }
    fixture.prepare(this)
    waitForIdle()
}

/**
 * Every interactive node in the MERGED tree - the nodes a screen reader would walk.
 *
 * The matcher is written here rather than taken from the library because the library ships no "any of these
 * actions" matcher: `hasClickAction()` exists, `hasAnyAction()` does not, so a matcher over a SET of action
 * keys is the only way to express "interactive" without six separate queries and a de-duplication step
 * that would double-count a node carrying two actions.
 */
fun ComposeTestRule.interactiveNodes(): List<SemanticsNode> =
    onAllNodes(
        SemanticsMatcher("has an action a user or a screen reader can invoke") { node ->
            INTERACTIVE_ACTIONS.any { node.config.contains(it) }
        },
    ).fetchSemanticsNodes()

/**
 * The actions that make a node INTERACTIVE for the purpose of this rule.
 *
 * Deliberately EXCLUDED, and named here so the omission is a stated decision: `SetProgress` (a `Slider`'s
 * value is announced from its own semantics, and no fixture in this module reaches a slider) and
 * `ScrollBy` / `ScrollByOffset` / `ScrollToIndex` (a scroll container is not a control the user presses to
 * reach something).
 *
 * A toggle needs no entry of its own: `toggleable` composes a click action with a toggleable state, so a
 * `Switch` or a `Checkbox` is already caught by `OnClick` below. The names are taken from the shipped API
 * rather than from memory - two keys that sounded plausible (`ToggleOnOff`, `ClearText`) do not exist in
 * this version, and asking for them fails the compile rather than the assertion.
 */
internal val INTERACTIVE_ACTIONS =
    listOf(
        SemanticsActions.OnClick,
        SemanticsActions.OnLongClick,
        SemanticsActions.SetSelection,
        SemanticsActions.SetText,
        SemanticsActions.InsertTextAtCursor,
    )

/** The shipped English table, so the sweeps assert names rather than copy that happens to be hardcoded. */
private val englishUiStrings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

/**
 * Opens a [SettingsScreenContent] card by its header, scrolling the screen's own column first.
 *
 * The screen is one `verticalScroll` column thirteen cards tall in a 1024x768 viewport, so a lower card
 * composes but is not where it is drawn - the same fact `SettingsScreenTest` records, and the reason it
 * scrolls rather than enlarging the window.
 */
internal fun ComposeTestRule.openCard(title: String) {
    onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(title))
    onNodeWithText(title).performClick()
    waitForIdle()
}

/**
 * A sensor a prompt can be shown for, which is the only state that draws the biometric row.
 *
 * Mirrors `SettingsDatabaseSecuritySectionSemanticsTest`'s fake, including the fact that the enable
 * verification SUCCEEDS there - but that test only ever opens the dialog, while the sweep leaves the
 * section alone and measures the row itself. A fake claiming NO sensor would render no switch at all, and
 * the floor would be met by the timeout field alone.
 */
private fun availableBiometricActions(): DatabaseSecurityBiometricActions =
    object : DatabaseSecurityBiometricActions {
        override val availability: BiometricAvailability = BiometricAvailability.Available

        override val canRequestEnable: Boolean = true

        // Never drawn in this state (the section renders it only when the sensor is NOT available), but
        // the interface has no default, so a fake that omitted it would not compile.
        override val enrollmentAction: @Composable () -> Unit = {}

        override fun requestEnable(
            passphrase: String,
            onComplete: (Boolean) -> Unit,
        ) {
            onComplete(true)
        }
    }

/**
 * Mirrors `SettingsAutoBackupSectionTest.loaded` with the section ENABLED, and rotation on with it.
 *
 * `autoBackupLastRun` is left null on purpose: it only decides whether a `Text` line is drawn, and a line
 * is not an interactive node, so the floor is unaffected either way.
 */
private fun backupPrefs() =
    AppPreferencesState(
        isLoading = false,
        autoBackupEnabled = true,
        autoBackupInterval = BackupInterval.SIX_HOURS,
        backupRotationEnabled = true,
        backupRotationCount = 50,
    )

/**
 * Mirrors `SettingsAutoTrackingSectionTest.state` with global ON and one visible dimension.
 *
 * One dimension, not two: the floor below is a count of switches, and a second dimension would make the
 * number depend on how many rows the fixture happens to declare rather than on the shape being pinned.
 */
private fun autoTrackingPrefs() =
    AppPreferencesState(
        isLoading = false,
        autoTrackHabitTimeGlobal = true,
        dimensionPreferences =
            listOf(
                DimensionPreference(key = "dim_work", label = "Work", color = Color.Blue, isVisible = true),
            ),
    )

private fun note() =
    Note(
        id = NOTE_ID,
        title = "Learn Kotlin",
        lifeIntentionCategory = "Learning & Growth",
        dimensionId = "dim_learning_growth",
        createdAt = NOTE_STAMP,
        updatedAt = NOTE_STAMP,
    )

private fun timelineTask() =
    Task(
        id = TIMELINE_TASK_ID,
        title = "Morning Run",
        dueDate = TIMELINE_DAY.atTime(14, 0),
        createdAt = TIMELINE_DAY.atTime(8, 0),
        updatedAt = TIMELINE_DAY.atTime(8, 0),
        durationMinutes = 30,
        lifeIntentionCategory = TIMELINE_DIMENSION_LABEL,
        dimensionId = TIMELINE_DIMENSION_ID,
    )

private fun timelineEntry() =
    TimeEntry(
        id = TIMELINE_ENTRY_ID,
        lifeIntentionCategory = TIMELINE_DIMENSION_LABEL,
        taskId = TIMELINE_TASK_ID,
        startedAt = TIMELINE_DAY.atTime(9, 0),
        endedAt = TIMELINE_DAY.atTime(10, 20),
        focusRating = 0.7,
        createdAt = TIMELINE_DAY.atTime(9, 0),
        updatedAt = TIMELINE_DAY.atTime(10, 20),
        dimensionId = TIMELINE_DIMENSION_ID,
    )

private fun timelineOccurrence() =
    TaskOccurrence(
        id = TIMELINE_OCCURRENCE_ID,
        taskId = TIMELINE_TASK_ID,
        // The ISO instant is what places the block on this day, so it carries the hour.
        occurrenceDate = "${TIMELINE_DAY}T14:00:00",
        status = "completed",
    )

/**
 * Mirrors `TimeEntryBlockSemanticsTest.preferences`. An entry whose dimension resolved to nothing would be
 * labelled with an EMPTY string, so the row the label is read from has to be registered - otherwise the
 * block announces a leading separator and the fixture, not the block, is what the sweep measures.
 */
private fun timelinePreferences() =
    AppPreferencesState(
        dimensionPreferences =
            listOf(
                DimensionPreference(
                    key = TIMELINE_DIMENSION_ID,
                    label = TIMELINE_DIMENSION_LABEL,
                    color = Color(0xFF4CAF50),
                    isVisible = true,
                ),
            ),
    )

/**
 * Mirrors `SettingsScreenTest.prefs`, including its deliberate `isLoading = false`. The default is TRUE so
 * that no section paints storage values before the first read lands, which means a section rendered with
 * the default draws one "loading preferences" line and NOTHING else - and every count in the sweeps would
 * be asserted against an empty tree.
 */
internal fun loadedPrefs() =
    AppPreferencesState(
        isLoading = false,
        dimensionPreferences =
            listOf(
                DimensionPreference(
                    key = "dim_health",
                    canonicalId = "dim_health",
                    label = "Health",
                    color = Color(0xFF4CAF50),
                    isVisible = true,
                    iconKey = "heart",
                    weight = 1.0,
                    hasCustomLabelOverride = false,
                ),
            ),
    )

/** Mirrors `SettingsDataManagementSectionsTest.state`: measured values, not invented ones. */
internal fun settingsUiState() =
    SettingsUiState(
        currentDatabaseSchemaVersion = 12,
        minimumSupportedSchemaVersion = 5,
        appVersion = "1.2.3",
        buildNumber = 42,
        updateChannel = UpdateChannel.STABLE,
        // Non-zero so the "map imported habits" row is drawn rather than greyed out; a disabled row still
        // exposes its click action, but an absent one would be a control this sweep silently skipped.
        importedUhabitsHabitCount = 3,
    )

private fun splitState() =
    DimensionSplitState(
        window = DimensionSplitWindow.W1,
        windowOffset = 0,
        firstEntryDate = TIMELINE_DAY.minusDays(30),
        effectiveStart = TIMELINE_DAY,
        effectiveEnd = TIMELINE_DAY,
        requestedStart = TIMELINE_DAY,
        requestedEnd = TIMELINE_DAY,
        byDimension = mapOf("dim_learning_growth" to 90, null to 10),
        totalMinutes = 100,
        canShiftLeft = true,
        canShiftRight = true,
    )

/**
 * The floors the sweeps are pinned to.
 *
 * THESE ARE COUNTS, NOT MINIMA SOMEONE MEASURED. Each is the number of interactive nodes the case's own
 * fixture is expected to contribute, read off the composable it renders; none has been observed, because
 * the sweep has never been executed. Every one is therefore stated as a floor that the FIRST run either
 * meets or reports, which is the safe direction to be wrong in: too low and the sweep still measures the
 * controls it is about, too high and the case fails loudly with the real count in the message rather than
 * passing quietly over a screen that stopped composing. Raise one to the count the run reports, and
 * record why.
 */
internal const val AppearanceSegments = 9
internal const val LandingSegments = 3
internal const val ChartSwitches = 7
internal const val DataManagementButtons = 6
internal const val SplitControls = 3
internal const val OnboardingControls = 2
internal const val NotesControls = 3
internal const val TimelineBlocks = 2

/** Enable toggle + run-now button + interval menu anchor + rotation toggle + rotation-count field. */
internal const val AutoBackupControls = 5

/** The global toggle + one per-dimension toggle, in the single-dimension fixture above. */
internal const val AutoTrackingSwitches = 2

/** The read-only unlock-timeout field + the biometric switch. Both exist only with the fake above. */
internal const val SecurityControls = 2

/**
 * Twelve card headers, plus the DEBUG card's switch.
 *
 * A header is a `Row.clickable` and `clickable` merges descendants, so each is one interactive node even
 * though it draws two icons and a title. Twelve is what [SettingsScreenActions]' DEFAULTS produce:
 * `scoreSettingsEnabled = false` withholds the scoring card and `minimalModeEnabled = false` takes the
 * data-management branch, and `DatabaseStatsSettingsSection` draws the thirteenth card the screen used to
 * hold inline - so the count is read off the fixture's flags rather than off the screen's thirteen call
 * sites. Only the debug card is open, so no section contributes rows.
 */
internal const val SettingsScreenControls = 13

/** The passphrase field, its reveal toggle, the confirming button and the cancel button. */
internal const val VerifyingGateControls = 4

/**
 * Two swatches' worth of floor for a grid that shows a few dozen.
 *
 * `LazyVerticalGrid` composes only what fits, so the real count is a viewport fact - the desktop viewport
 * is 1024x768 and each swatch is 44dp, which is well over two columns and several rows. The floor's job is
 * to prove the dialog OPENED (a closed one contributes the picker's own single button), not to pin how many
 * swatches a viewport holds.
 */
internal const val IconSwatches = 4

/** A key that really is in [DimensionIconCatalog], so the swatch draws as SELECTED rather than plain. */
internal const val PICKER_ICON_KEY = "work"

/** Long enough for a real dispatcher hop on a loaded CI box, short enough to fail rather than hang. */
internal const val WAIT_MS = 10_000L
internal const val NOTE_ID = "n1"

/** A fixed day, so the timeline's arithmetic cannot drift with the clock. */
internal val TIMELINE_DAY: LocalDate = LocalDate.of(2026, 3, 15)
internal val NOTE_STAMP: LocalDateTime = LocalDateTime.of(2026, 3, 15, 10, 0)

/** 60dp an hour, the shared default: tall enough that an 80-minute block is not a sliver. */
internal const val TIMELINE_HOUR_HEIGHT_DP = 60f
internal const val TIMELINE_TASK_ID = "task-1"
internal const val TIMELINE_ENTRY_ID = "entry-1"
internal const val TIMELINE_OCCURRENCE_ID = "occurrence-1"
internal const val TIMELINE_DIMENSION_ID = "dim_health_wellness"
internal const val TIMELINE_DIMENSION_LABEL = "Health & Wellness"

/** The notes the shared screen lists, as a flow the ViewModel observes; the sweeps never write. */
private class FakeNoteRepository(
    initial: List<Note>,
) : NoteRepository {
    private val notes = MutableStateFlow(initial)

    override fun getAllNotes(): Flow<List<Note>> = notes

    override fun getNotesByDimension(dimension: String): Flow<List<Note>> = flowOf(emptyList())

    override fun getNotesForDate(date: LocalDate): Flow<List<Note>> = flowOf(emptyList())

    override suspend fun getNoteById(id: String): Note? = notes.value.firstOrNull { it.id == id }

    override suspend fun createNote(input: io.payanam.domain.model.NoteInput): Note =
        throw UnsupportedOperationException("the sweep never writes")

    override suspend fun updateNote(
        id: String,
        input: io.payanam.domain.model.NoteInput,
    ): Note = throw UnsupportedOperationException("the sweep never writes")

    override suspend fun deleteNote(id: String) =
        throw UnsupportedOperationException("the sweep never writes")
}

/** Tag lookups the shared screen performs while rendering; nothing to suggest, nothing attached. */
private class FakeTagRepository : TagRepository {
    override fun observeAllTags(): Flow<List<Tag>> = flowOf(emptyList())

    override fun searchTagsByPrefix(
        query: String,
        limit: Int,
    ): Flow<List<Tag>> = flowOf(emptyList())

    override fun observeTagsForTask(taskId: String): Flow<List<Tag>> = flowOf(emptyList())

    override fun observeTagsForNote(noteId: String): Flow<List<Tag>> = flowOf(emptyList())

    override suspend fun getTagNamesForNotes(noteIds: List<String>): Map<String, List<String>> = emptyMap()

    override fun observeTagsForTimeEntry(timeEntryId: String): Flow<List<Tag>> = flowOf(emptyList())

    override suspend fun replaceTaskTags(
        taskId: String,
        tagNames: List<String>,
    ) = throw UnsupportedOperationException("the sweep never writes")

    override suspend fun replaceNoteTags(
        noteId: String,
        tagNames: List<String>,
    ) = throw UnsupportedOperationException("the sweep never writes")

    override suspend fun replaceTimeEntryTags(
        timeEntryId: String,
        tagNames: List<String>,
    ) = throw UnsupportedOperationException("the sweep never writes")
}
