//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import io.payanam.domain.model.NoteInput
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
import io.payanam.presentation.updates.UpdateChannel
import io.payanam.shared.security.BiometricAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The net that keeps an interactive control in this module from being reachable and anonymous.
 *
 * WHY THIS FILE EXISTS. The commit that named the biometric passphrase reveal and the two timeline blocks
 * fixed four controls in three files. It also measured something worse: nothing in the build stopped the same
 * defect returning at the next call site. The reason is not carelessness, it is the API. `null` is exactly
 * what `contentDescription` is FOR: Compose reads a null description as "decorative, on purpose", so no grep
 * for a missing string can tell a deliberate decorative icon from a control that lost its name. The only
 * instrument that can is the rendered semantics tree.
 *
 * TWO LAYERS, BECAUSE NEITHER SUBSUMES THE OTHER.
 *
 * LAYER 1 - THE RENDERED SWEEP ([everyInteractiveNodeIsNamed]). Fourteen shared screens that this module's own
 * `desktopTest` tree already has working fixtures for are rendered for real on the JVM target, and every
 * interactive node in the MERGED tree must carry a name. This is the only layer that can see a description
 * that was never written, because the absence of one is exactly what it measures. It covers only what it
 * renders, so the list of what it does NOT cover is part of the contract, not an afterthought.
 *
 * LAYER 2 - THE SOURCE GUARD ([`no null-description icon escapes the recorded set`]). A rendered sweep cannot cover
 * 110 production files, and a failure in the tree does not say which line to change. The guard therefore reads
 * this module's own `commonMain` sources and fails when a `contentDescription = null` appears in a declaration
 * that has not been recorded as a deliberate decorative. It fails CLOSED on what it can see: an unrecognised
 * declaration is a failure, not a pass. A guard that waved unknown code through would be the decorative version
 * of itself.
 *
 * WHAT "NAMED" MEANS, AND WHY THE MERGED TREE IS THE RIGHT ONE. The predicate is the one an assistive
 * technology actually walks. Compose MERGES an interactive container with the semantics of its descendants, so
 * `Button { Text("Run backup now") }` is a SINGLE node that already has a name - the child `Text` merges up
 * into it. That is why this file reads the MERGED tree (`useUnmergedTree = false`, the default) and not the
 * unmerged one: a `Text` sitting BESIDE a control rather than inside it contributes no name to that control,
 * and reading the unmerged tree would let a genuinely nameless control pass because some unrelated `Text`
 * exists two rows above it.
 *
 * A node is NAMED when it carries ANY of: a non-empty `ContentDescription`, a `Text`, or an `EditableText`.
 * `EditableText` counts even for a READ-ONLY field, because Compose publishes a field's value through it in
 * both the editable and the read-only case (verified against `CoreTextFieldSemanticsModifierNode` in the
 * shipped `ui-desktop` artifact), so a read-only menu anchor announcing its value is genuinely named rather
 * than technically nameless. `InputText` is deliberately NOT in the list: it is a password-obfuscation
 * output, not a name.
 *
 * A node is INTERACTIVE when it exposes at least one action a user or a screen reader can invoke: `OnClick`,
 * `OnLongClick`, `ToggleOnOff`, `SetSelection`, `SetText`, `InsertTextAtCursor`, or `ClearText`. Two actions
 * are deliberately EXCLUDED and named as such: `SetProgress` (a `Slider`'s value is announced from its own
 * semantics, and this module's sliders are not reachable through any fixture here) and the scroll actions
 * (`ScrollBy`, `ScrollToIndex` - a scroll container is not a control the user presses to reach something).
 *
 * THE NAMED-CONTROL PROOF, CONCRETELY, FOR THE CONTROL THE LAST COMMITT FIXED. Take the biometric passphrase
 * reveal toggle, at commonMain/.../settings/SettingsDatabaseSecuritySection.kt:305-310. Its screen IS swept
 * (case 11), so its protection is Layer 1 as well. `SettingsDatabaseSecuritySection` currently has NO
 * null-description site at all, so it does not appear in [recordedSiteCounts] - and that is exactly what
 * makes the revert fail: putting `contentDescription = null` back on line 305 introduces a declaration key
 * that the inventory has no record of, which is the first branch of [`no null-description icon escapes the recorded set`].
 * ONE line of production changes, and the gate is red. Verified against this module's tree: the file holds
 * 0 null-description sites today, so the revert is a genuinely NEW site and not a count overflow.
 *
 * THE SAME PROOF FOR THE TIMELINE BLOCKS, WHICH ARE swept. If the
 * `.semantics(mergeDescendants = true) { contentDescription = compactLabel }` that
 * TimeScreenTimeline.kt now carries on its clickable block were deleted, that block would have a click
 * action, no description, and no text - the icon cascade inside it passes null descriptions throughout - so
 * [the timeline's clickable blocks are named] would fail. That is the proof that the net bites: the most
 * likely revert of the fix that prompted this file produces a red test, not a silent regression.
 *
 * COVERAGE: WHAT LAYER 1 ACTUALLY ASSERTS. Every screen below asserts the number of interactive nodes it
 * contributed, so a fixture that stops composing fails loudly instead of passing on an empty tree.
 *   1. [SettingsAppearanceSection]      3 language + 3 theme + 4 font-family + 2 time-format segments.
 *   2. [SettingsDefaultLandingSection]  3 top-level route segments, plus the task layer and state segments
 *                                        reachable from the Tasks destination.
 *   3. [SettingsTimeInsightsSection]    every chart-visibility switch this state turns on, each named by the
 *                                        semantics description a previous commit added.
 *   4. [DataManagementSettingsSection]  6 action buttons (export, import, uHabits, map habits, update
 *                                        passphrase, delete all).
 *   5. [DimensionSplitSection]          1 window chip + 2 shift arrows.
 *   6. [OnboardingIntroScreen]           1 action button + 1 skip button, on the first slide.
 *   7. [NotesScreen]                    1 add-note FAB + 1 search field + the filter-chip row + note rows.
 *   8. [TimeCalendarView]               the 2 clickable timeline blocks (entry + past occurrence).
 *   9. [SettingsAutoBackupSection]      the enable toggle, and - enabled - the run-now button, the interval
 *                                        menu anchor and the rotation toggle. The two switches were the
 *                                        last NAMELESS controls in the section; both are now named from
 *                                        their own visible titles.
 *  10. [SettingsAutoTrackingSection]    the global toggle and one per-dimension toggle, named by the
 *                                        dimension label each row already draws.
 *  11. [SettingsDatabaseSecuritySection] the unlock-timeout menu anchor and the biometric switch, on the
 *                                        fixture's AVAILABLE sensor - the state in which that switch is drawn
 *                                        at all, since a platform with no sensor renders no biometric row.
 *  12. [SettingsScreenContent]          the whole screen as [SettingsScreenActions]' defaults draw it: the
 *                                        card headers (each a `Row.clickable`, which merges descendants and so
 *                                        takes its name from the title beside it) plus every row the open
 *                                        card reaches. This is where the nameless debug `Switch` used to be.
 *                                        See the NOT COVERED entry below for what this state does not reach.
 *  13. [ImportEncryptedDbPassphraseDialog] the passphrase gate in BOTH its states - idle and verifying -
 *                                        because the confirming button is nameless in exactly the verifying
 *                                        one, and a disabled control is still interactive to a screen reader.
 *
 * NOT COVERED BY LAYER 1 - AND WHY. An unlisted gap is the defect this file exists to prevent, so this list is
 * the contract, and it is exhaustive for what this module can put on screen today:
 *  - `MinimalDataManagementSection` - EXCLUDED as a section: the two buttons it draws are the same two this
 *    file reaches through the full section, and its own fixture path is a separate state.
 *  - `DimensionIconPicker` / `DimensionColorPicker` dialogs - NOT SWEPT: the icon picker's swatch is now NAMED
 *    ([`the dimension icon picker names every swatch it draws`]), but opening either dialog needs the picker's
 *    own `OutlinedButton` to be pressed first, which is inside `DimensionPreferenceCard` - a control this
 *    module's `desktopTest` tree has no fixture that mounts. Both pickers are therefore covered by Layer 2
 *    alone, and the icon swatch's naming rests on that case rather than on a sweep.
 *  - `SettingsDialogs`' OTHER dialogs - the import-confirm, delete-all, delete-export-prompt and bulk-map
 *    dialogs are NOT swept as dialogs. Each is reachable through [SettingsScreenContent] instead (the import
 *    one through `importSourceSelected`, the delete-export one through `uiState.showDeleteExportPrompt`), and
 *    every control they draw carries a label `Text` inside the button, which is the shape Layer 1 already
 *    covers eight times. Only [ImportEncryptedDbPassphraseDialog] needed its own case, because it is the one
 *    whose control loses its label rather than merely lacking a description.
 *  - `LensesScreen` and its twelve sections, `TasksScreen`, `TaskDetailScreen`, `AddTaskScreen`,
 *    `EditTaskScreen`, `ScoreDetailScreen`, `DayScreen`, `FocusModeSelectionScreen`, `DayPlanTemplateScreen`,
 *    `AssistantScreen`, `AssistantSettingsScreen`, `ScoringConfigScreen`, `DatabaseInitScreen` and the rest of
 *    the database-gate screens, `PayanamNavHost` - NOT SWEPT: this module's `desktopTest` tree has no fixture
 *    that can put them on screen, so there is nothing to reuse. They are covered by Layer 2 only, and only to
 *    the extent of the shape Layer 2 can see (see ITS LIMIT).
 *  - Any control that is only interactive in a state no fixture here can reach (a dialog's staged-import
 *    sheet, a busy button) - NOT SWEPT: reaching that state needs a platform seam (a real file picker, an
 *    encrypting export) that no desktop fixture can supply. The ONE such state this file used to exclude -
 *    the passphrase gate's verifying spinner - is now swept by case 13, because it turned out to be reachable
 *    after all: `isVerifying` is a plain parameter, not a platform effect.
 *  - The Android and desktop APP modules' own composables - OUT OF SCOPE: this is the shared module's net, and
 *    `:app` and `:desktop` have their own trees.
 *
 * LAYER 2'S LIMIT, STATED PLAINLY. A source guard cannot see the semantics tree. It reads text, so it enforces
 * only the shape it can see: an `Icon` passing `contentDescription = null` in this module's `commonMain`. It
 * CANNOT see a `Button { Text(...) }` whose label was emptied to `""`, a `Row.clickable` whose sibling label
 * moved out of the control, a nameless control built from a `Box` with no `Icon` at all, or a control
 * rendered only on a path no fixture exercises. Those remain the rendered sweep's job, and where the sweep
 * cannot reach them either they are uncaught - stated here rather than implied away.
 *
 * WHY THE ASSERTION LIBRARY IS SPLIT. `kotlin.test` for the count and message assertions, because it reads
 * the way the arithmetic reads, and JUnit for `@Test`/`@Rule`, which is the harness every neighbouring file in
 * this source set uses. Both are on this source set's classpath (`kotlin("test")` and `libs.junit`, see
 * core/ui/build.gradle.kts).
 *
 * THE FIXTURES ARE REUSED, NOT INVENTED. Every render below reuses state a neighbouring `desktopTest` file
 * already proves renders: the preferences state from `SettingsScreenTest.prefs` (including its deliberate
 * `isLoading = false`, without which every settings section draws only a "loading preferences" line and this
 * sweep would cover nothing), the settings UI state from `SettingsDataManagementSectionsTest.state`, the
 * timeline preferences from `TimeEntryBlockSemanticsTest.preferences`, the notes repositories from
 * `NotesScreenTest`'s fakes, and the split state from `DimensionSplitSectionSemanticsTest.render`. Inventing
 * state here would make this a test of the fixture rather than of the screen - the trap
 * `SettingsScreenTest`'s own KDoc calls out.
 */
class InteractiveControlNamingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val strings: UiStrings = UiStrings.of(UiLanguage.ENGLISH)

    // -------------------------------------------------------------------------------------------
    // LAYER 1: THE RENDERED SWEEP
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the appearance section names every control it draws`() {
        // Every option's caption used to be an `R.string` id resolved in the app module, so a section that
        // still renders can draw the wrong words. The sweep asserts NAMES; the count is asserted too, so a
        // section that stopped drawing its rows fails here rather than passing on an empty tree.
        var fontFamilyExpanded = false
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
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
        }
        composeRule.waitForIdle()

        assertSwept("SettingsAppearanceSection", AppearanceSegments)
    }

    @Test
    fun `the default landing section names every control it draws`() {
        // The Tasks destination is chosen because it is the only one that draws the LAYER and STATE rows as
        // well as the route segments, so this fixture reaches the most controls of the three destinations.
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                SettingsDefaultLandingSection(
                    prefsState =
                        AppPreferencesState(
                            isLoading = false,
                            launchDestination = LaunchDestination(route = "tasks", taskFilter = TaskFilter.TODAY),
                        ),
                    onLaunchDestinationRouteChange = {},
                    onLaunchDestinationTimeChange = {},
                    onLaunchDestinationTasksChange = {},
                )
            }
        }
        composeRule.waitForIdle()

        assertSwept("SettingsDefaultLandingSection", LandingSegments)
    }

    @Test
    fun `every chart visibility switch is named`() {
        // Each switch here is named by the `.semantics { contentDescription = row.label }` a previous commit
        // added, which is the reason this screen is sweepable at all. It is therefore the screen whose sweep
        // would break FIRST if that one modifier were deleted - which is exactly the regression class this
        // file is for, so the sweep is pointed at it deliberately rather than incidentally.
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
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
        }
        composeRule.waitForIdle()

        assertSwept("SettingsTimeInsightsSection", ChartSwitches)
    }

    @Test
    fun `the data management section names every button it draws`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
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
        }
        composeRule.waitForIdle()

        assertSwept("DataManagementSettingsSection", DataManagementButtons)
    }

    @Test
    fun `the dimension split section names its chip and both shift arrows`() {
        // Both arrows are decorative icons on their own - two chevrons around a date range - so the
        // description is the ONLY thing that tells a screen reader what either one does. They are named from
        // the copy table (proved by value in DimensionSplitSectionSemanticsTest); this case is the version
        // that would catch a NEW nameless control appearing beside them.
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                DimensionSplitSection(
                    state = splitState(),
                    onWindowSelect = {},
                    onShiftLeft = {},
                    onShiftRight = {},
                )
            }
        }
        composeRule.waitForIdle()

        assertSwept("DimensionSplitSection", SplitControls)
    }

    @Test
    fun `the onboarding tour names its action and skip buttons`() {
        // No `CompositionLocalProvider` is needed here: the screen resolves its own copy from the language it
        // is handed, so the harness measures the shared composable rather than a wrapper, exactly as
        // OnboardingIntroScreenTest does.
        composeRule.setContent {
            OnboardingIntroScreen(
                language = UiLanguage.ENGLISH,
                onFinished = {},
            )
        }
        composeRule.waitForIdle()

        assertSwept("OnboardingIntroScreen", OnboardingControls)
    }

    @Test
    fun `the notes screen names every control it draws`() {
        // The real ViewModel over the same fakes NotesScreenTest declares, because a fake ViewModel would be
        // testing the test. The screen is async, so the render is driven to a settled state before sweeping.
        val viewModel =
            NotesViewModel(
                noteRepository = FakeNoteRepository(listOf(note())),
                tagRepository = FakeTagRepository(),
            )
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                NotesScreen(viewModel = viewModel)
            }
        }
        composeRule.waitUntil(timeoutMillis = WAIT_MS) { viewModel.uiState.value.notes.isNotEmpty() }

        assertSwept("NotesScreen", NotesControls)
    }

    @Test
    fun `the timeline's clickable blocks are named`() {
        // The controls the last commit named from scratch, and the only two nodes in this sweep whose entire
        // content is a decorative icon cascade. Deleting the `.semantics { contentDescription = compactLabel }`
        // they now carry leaves a clickable node with no description and no text, so this case fails - which
        // is the concrete proof, stated in the KDoc, that the net bites on the most likely revert.
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
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
        }
        composeRule.waitForIdle()

        assertSwept("TimeCalendarView", TimelineBlocks)
    }

    @Test
    fun `the auto backup section names both of its switches`() {
        // Enabled, because the rotation switch only exists once the section is on: sweeping the disabled
        // state would cover one switch and quietly skip the other, which is the defect this file exists to
        // catch. The enable toggle and the rotation toggle were both nameless; both now carry their own
        // visible title, and this case fails if either modifier is deleted.
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
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
        }
        composeRule.waitForIdle()

        assertSwept("SettingsAutoBackupSection", AutoBackupControls)
    }

    @Test
    fun `the auto tracking section names its global and per dimension switches`() {
        // Global ON, because that is the only state in which the per-dimension rows are drawn at all -
        // `SettingsAutoTrackingSectionTest` pins that visibility rule, and this case depends on it: a floor
        // met by the global switch alone would leave the per-dimension one unnamed and passing.
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                SettingsAutoTrackingSection(
                    prefsState = autoTrackingPrefs(),
                    onAutoTrackHabitTimeGlobalChange = {},
                    onAutoTrackDimensionChange = { _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()

        assertSwept("SettingsAutoTrackingSection", AutoTrackingSwitches)
    }

    @Test
    fun `the database security section names the biometric switch`() {
        // An AVAILABLE sensor, which is the only state that draws the switch at all: a platform with no
        // identity check renders no biometric row, so a sweep on that state would cover the timeout menu
        // anchor and nothing else - the screen's nameless control would go unmeasured while the case stayed
        // green. The timeout's read-only field is named by its own `EditableText`, which the KDoc above
        // explains is why a read-only anchor counts as named.
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                SettingsDatabaseSecuritySection(
                    uiState = settingsUiState(),
                    onUpdateUnlockTimeout = {},
                    onDisableBiometricEnabled = {},
                    onEnableBiometricRequested = { _, onComplete -> onComplete(true) },
                    biometricActions = availableBiometricActions(),
                )
            }
        }
        composeRule.waitForIdle()

        assertSwept("SettingsDatabaseSecuritySection", SecurityControls)
    }

    @Test
    fun `the whole settings screen names every control it draws`() {
        // The screen that INHERITED three nameless switches by composing the sections above, plus one of its
        // own. It is swept rather than excluded now that all four are named, and the card headers matter as
        // much as the rows: `SettingsCard`'s header is a `Row.clickable`, and `clickable` merges descendants,
        // so thirteen headers announce as thirteen controls and each takes its name from the title Text beside
        // it.
        //
        // Debug logging is opened, because a closed card composes no rows and the debug `Switch` is the one
        // control on this screen that belongs to the screen itself rather than to a section fixture.
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                SettingsScreenContent(
                    prefsState = loadedPrefs(),
                    uiState = settingsUiState(),
                    snackbarHostState = SnackbarHostState(),
                    actions = SettingsScreenActions(),
                )
            }
        }
        composeRule.waitForIdle()
        openCard(strings.settingsDebugTitle)

        assertSwept("SettingsScreenContent", SettingsScreenControls)
    }

    @Test
    fun `the passphrase gate names its confirming button while verifying`() {
        // THE state this file used to exclude, and the reason the exclusion was wrong. `isVerifying` is a
        // plain parameter, not a platform effect, so the state was always reachable - what was missing was a
        // case. Here the spinner REPLACES the label Text, so the button has no text and, before the fix, no
        // description either. It is still interactive: Compose publishes `onClick` for a disabled clickable and
        // marks it `disabled`, which is why this control is in Layer 1's reach and not merely out of date.
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                ImportEncryptedDbPassphraseDialog(
                    passphraseError = null,
                    isVerifying = true,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        assertSwept("ImportEncryptedDbPassphraseDialog", VerifyingGateControls)
    }

    @Test
    fun `the dimension icon picker names every swatch it draws`() {
        // The only nameless control left in the module, and the one shape no other case can reach: 353
        // clickable swatches whose entire content is one decorative glyph each. The picker's own button is
        // pressed first, which is why this case mounts `DimensionIconPicker` directly - inside
        // `DimensionPreferenceCard` there is no fixture in this source set that opens the dialog.
        //
        // The floor is a SWATCH count, not the picker's button, and it is deliberately far below 353: a lazy
        // grid composes only the rows the viewport can show, so the exact number is a viewport fact rather
        // than a property of the screen. What the floor guarantees is that the sweep measured real swatches -
        // a dialog that failed to open contributes one control and falls below it.
        composeRule.setContent {
            CompositionLocalProvider(LocalUiStrings provides strings) {
                DimensionIconPicker(
                    selectedIconKey = PICKER_ICON_KEY,
                    usedIconKeys = emptySet(),
                    onSelect = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(strings.dimensionPickerChooseIcon).performClick()
        composeRule.waitForIdle()

        assertSwept("DimensionIconPicker", IconSwatches)
    }

    /**
     * Opens a [SettingsScreenContent] card by its header, scrolling the screen's own column first.
     *
     * The screen is one `verticalScroll` column thirteen cards tall in a 1024x768 viewport, so a lower card
     * composes but is not where it is drawn - the same fact `SettingsScreenTest` records, and the reason it
     * scrolls rather than enlarging the window.
     */
    private fun openCard(title: String) {
        composeRule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(title))
        composeRule.onNodeWithText(title).performClick()
        composeRule.waitForIdle()
    }

    /**
     * The sweep itself, written once so the fourteen cases above stay about WHICH screen is covered.
     *
     * [expected] is asserted as a floor, and the floor is why a sweep that renders nothing fails: a fixture
     * that stops composing contributes zero interactive nodes, which is below every floor in this file. A
     * floor also survives a screen GAINING a control, which an exact count would turn into a failure; the
     * cost is that a screen silently LOSING one is not caught by this layer, which is stated in the KDoc's
     * NOT COVERED list rather than left to be discovered.
     */
    private fun assertSwept(
        screen: String,
        expected: Int,
    ) {
        val interactive = interactiveNodes()
        assertTrue(
            interactive.size >= expected,
            "$screen rendered ${interactive.size} interactive node(s), fewer than the $expected this sweep " +
                "is pinned to. Either the screen stopped composing or the fixture no longer reaches it.",
        )
        val nameless = interactive.filter { !it.isNamed() }
        assertEquals(
            emptyList<String>(),
            nameless.map { it.describe() },
            "$screen exposes interactive nodes a screen reader cannot name. Each entry is a control that " +
                "accepts a press and announces only its ROLE. Fix the control, not this test.",
        )
    }

    /**
     * Every interactive node in the MERGED tree - the nodes a screen reader would walk.
     *
     * The matcher is written here rather than taken from the library because the library ships no
     * "any of these actions" matcher: `hasClickAction()` exists, `hasAnyAction()` does not, so a matcher over
     * a SET of action keys is the only way to express "interactive" without six separate queries and a
     * de-duplication step that would double-count a node carrying two actions.
     */
    private fun interactiveNodes(): List<SemanticsNode> =
        composeRule
            .onAllNodes(
                SemanticsMatcher("has an action a user or a screen reader can invoke") { node ->
                    INTERACTIVE_ACTIONS.any { node.config.contains(it) }
                },
            ).fetchSemanticsNodes()

    /**
     * The name this node would announce.
     *
     * Every read checks `contains` first, because `config[key]` on an ABSENT key throws rather than returning
     * null. A sweep that crashed on the very node it was written to report would turn a real defect into a
     * stack trace instead of an assertion that names it, which is a weaker signal for the reader.
     */
    private fun SemanticsNode.isNamed(): Boolean {
        val description =
            if (config.contains(SemanticsProperties.ContentDescription)) {
                config[SemanticsProperties.ContentDescription]
            } else {
                emptyList()
            }
        val text =
            if (config.contains(SemanticsProperties.Text)) {
                config[SemanticsProperties.Text]
            } else {
                emptyList()
            }
        val editable =
            if (config.contains(SemanticsProperties.EditableText)) {
                config[SemanticsProperties.EditableText]
            } else {
                null
            }
        return description.isNotEmpty() || text.isNotEmpty() || editable != null
    }

    /** Enough of a node to find it in a failure message: the actions it exposes and where it is drawn. */
    private fun SemanticsNode.describe(): String {
        val actions = INTERACTIVE_ACTIONS.filter { config.contains(it) }.map { it.name }
        return "actions=$actions on node #$id"
    }

    /**
     * A sensor a prompt can be shown for, which is the only state that draws the biometric row.
     *
     * Mirrors `SettingsDatabaseSecuritySectionSemanticsTest`'s fake, including the fact that the enable
     * verification SUCCEEDS there - but that test only ever opens the dialog, while this case leaves the
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
     * the default draws one "loading preferences" line and NOTHING else - and every count in this file would
     * be asserted against an empty tree.
     */
    private fun loadedPrefs() =
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
    private fun settingsUiState() =
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

    // -------------------------------------------------------------------------------------------
    // LAYER 2: THE SOURCE GUARD
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the source guard reads this module's own commonMain tree`() {
        // A scan pointed at the wrong directory passes VACUOUSLY: it finds no sources and reports nothing. A
        // check that can be green by looking in the wrong place is not a check, so the walk is asserted to
        // have found this module's tree, at a count below the number of files the module actually has.
        val sources = productionSources()
        assertTrue(
            sources.size >= MinProductionFiles,
            "the source guard found ${sources.size} Kotlin file(s) under commonMain, fewer than the " +
                "$MinProductionFiles this module has. It is scanning the wrong tree.",
        )
        assertTrue(
            sources.any { it.name == "SettingsTimeInsightsSection.kt" },
            "the source guard did not find a file this module certainly has, so its attribution is meaningless.",
        )
    }

    /**
     * THE FAIL-CLOSED ASSERTION: no `contentDescription = null` outside a recorded decorative declaration.
     *
     * Every null-description site in this module's `commonMain` is attributed to the declaration that
     * encloses it, and each attribution must appear in [decorativeDeclarations] AND must not exceed that
     * declaration's recorded site count. A new null description in an unrecorded composable FAILS, naming the
     * file, the declaration and the line - which is the whole point of the layer: the next introduction of
     * this defect class stops in the diff, where the question "is this decorative on purpose?" can be
     * answered, rather than silently in a screen.
     *
     * WHY THE SITE COUNT IS PINNED AS WELL AS THE DECLARATION. "That declaration was already reviewed" is a
     * statement about a PAST state of a file, not a permanent exemption. The timeline blocks are the concrete
     * case: TimeScreenTimeline.kt#TimeEntryBlock is recorded with 2 sites (the two decorative cascades) and
     * 2 is what it holds, so ADDING a third null description to that block - the shape a naive fix for a
     * nameless sibling would take - is a failure rather than a silent third decorative icon. The biometric
     * reveal toggle is the complementary case, caught by the missing-key branch instead.
     */
    @Test
    fun `no null-description icon escapes the recorded set`() {
        val unrecorded = mutableListOf<String>()
        for (file in productionSources()) {
            val relative = file.relativeTo(commonMainRoot()).path.replace(File.separatorChar, '/')
            val source = stripComments(file.readText())
            for (site in nullDescriptionSites(source)) {
                val key = "$relative#${site.declaration}"
                val recordedSites = recordedSiteCounts[key]
                when {
                    recordedSites == null ->
                        unrecorded +=
                            "$relative:${site.line} (in ${site.declaration}) - no recorded reason for a null " +
                                "description here"

                    site.index >= recordedSites ->
                        unrecorded +=
                            "$relative:${site.line} (in ${site.declaration}) - site ${site.index + 1} of a " +
                                "declaration recorded with $recordedSites; a null description was ADDED to a " +
                                "declaration that was already reviewed as decorative"

                    else -> Unit
                }
            }
        }
        assertEquals(
            emptyList<String>(),
            unrecorded,
            "an Icon(contentDescription = null) appeared outside the recorded decorative set. Either it is " +
                "decorative, in which case record its declaration in recordedSiteCounts with the reason, or " +
                "it is the only content of a control the user presses - which is the defect this file exists " +
                "to catch.",
        )
    }

    /**
     * Every null-description site in [source], attributed to the declaration that encloses it and numbered
     * within it.
     *
     * Comments are blanked BEFORE this runs, which is what makes the inventory honest: this module's own KDoc
     * discusses the literal `contentDescription = null` many times, and a site counted from a comment would
     * both mis-attribute and eventually push a real declaration over its recorded count.
     *
     * The attribution is "the last `fun` whose signature begins at or above the site". That is deliberately
     * simple, and its one known imprecision is recorded rather than hidden: in a file whose null-description
     * site sits in a composable that DECLARES A NESTED HELPER below itself, the helper's name wins. That is
     * why both keys appear in [recordedSiteCounts] for PayanamNavHost.kt.
     */
    private fun nullDescriptionSites(source: String): List<Site> {
        val declarations =
            DECLARATION
                .findAll(source)
                .map { match ->
                    source.take(match.range.first).count { it == '\n' } + 1 to match.groupValues[1]
                }.toList()
        val sites = mutableListOf<Site>()
        val perDeclaration = mutableMapOf<String, Int>()
        for (match in CD_NULL.findAll(source)) {
            val line = source.take(match.range.first).count { it == '\n' } + 1
            var enclosing = FILE_LEVEL
            for ((declarationLine, name) in declarations) {
                if (declarationLine <= line) enclosing = name else break
            }
            val index = perDeclaration.getOrDefault(enclosing, 0)
            perDeclaration[enclosing] = index + 1
            sites += Site(declaration = enclosing, line = line, index = index)
        }
        return sites
    }

    private class Site(
        val declaration: String,
        val line: Int,
        val index: Int,
    )

    /** Every Kotlin file in this module's own `commonMain`, walked so a new file is covered without an edit. */
    private fun productionSources(): List<File> {
        val root = commonMainRoot()
        if (!root.isDirectory) return emptyList()
        return root
            .walkTopDown()
            .onEnter { it.name != "build" }
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
    }

    /**
     * This module's `commonMain` directory, DISCOVERED rather than hardcoded.
     *
     * A scan pointed at the wrong directory passes vacuously, and a scan that silently found nothing is the
     * exact failure mode this file exists to prevent. The path is resolved by walking up from the working
     * directory to the Gradle settings file and then into this module, which is the same approach
     * `DatabaseArtifactDeletionGuardTest` uses for its own repository scan, so the repo keeps ONE house way
     * of finding sources. If that walk cannot find the settings file, [productionSources] returns empty and
     * the first Layer 2 case fails on it rather than letting the guard report a green over nothing.
     */
    private fun commonMainRoot(): File {
        var candidate = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: return File(NOTH_A_DIRECTORY)
        }
        return File(candidate, "core/ui/src/commonMain")
    }

    /**
     * Replaces comment text with spaces, keeping every line's length and numbering.
     *
     * Whitespace rather than removal, so a violation is still reported at the line the reader would find it
     * on. Block comments are handled first, and the line-comment rule stops at end-of-line so a `//` inside a
     * string literal cannot truncate real code after it.
     */
    private fun stripComments(source: String): String =
        source
            .replace(BLOCK_COMMENT) { match -> match.value.blankOutKeepingNewlines() }
            .replace(LINE_COMMENT) { match -> match.value.blankOutKeepingNewlines() }

    private fun String.blankOutKeepingNewlines(): String = map { if (it == '\n') '\n' else ' ' }.joinToString("")

    private companion object {
        /**
         * The actions that make a node INTERACTIVE for the purpose of this rule.
         *
         * Deliberately EXCLUDED, and named here so the omission is a stated decision: `SetProgress` (a
         * `Slider`'s value is announced from its own semantics, and no fixture in this module reaches a
         * slider) and `ScrollBy` / `ScrollByOffset` / `ScrollToIndex` (a scroll container is not a control the
         * user presses to reach something).
         *
         * A toggle needs no entry of its own: `toggleable` composes a click action with a toggleable state, so
         * a `Switch` or a `Checkbox` is already caught by `OnClick` below. The names are taken from the shipped
         * API rather than from memory - two keys that sounded plausible (`ToggleOnOff`, `ClearText`) do not
         * exist in this version, and asking for them fails the compile rather than the assertion.
         */
        val INTERACTIVE_ACTIONS =
            listOf(
                SemanticsActions.OnClick,
                SemanticsActions.OnLongClick,
                SemanticsActions.SetSelection,
                SemanticsActions.SetText,
                SemanticsActions.InsertTextAtCursor,
            )

        /**
         * The declared-but-unused imports the compiler would reject are avoided by construction: this file
         * imports only what it uses, and the floors below are the only numbers a reader has to check.
         *
         * THE SIX NEW FLOORS ARE COUNTS, NOT MINIMA I MEASURED. Each is the number of interactive nodes the
         * case's own fixture is expected to contribute, read off the composable it renders; none has been
         * observed, because this file has never been executed. Every one is therefore stated as a floor that
         * the FIRST run either meets or reports, which is the safe direction to be wrong in: too low and the
         * sweep still measures the controls it is about, too high and the case fails loudly with the real
         * count in the message rather than passing quietly over a screen that stopped composing. Raise one
         * to the count the run reports, and record why.
         */
        const val AppearanceSegments = 9
        const val LandingSegments = 3
        const val ChartSwitches = 7
        const val DataManagementButtons = 6
        const val SplitControls = 3
        const val OnboardingControls = 2
        const val NotesControls = 3
        const val TimelineBlocks = 2

        /** Enable toggle + run-now button + interval menu anchor + rotation toggle + rotation-count field. */
        const val AutoBackupControls = 5

        /** The global toggle + one per-dimension toggle, in the single-dimension fixture above. */
        const val AutoTrackingSwitches = 2

        /** The read-only unlock-timeout field + the biometric switch. Both exist only with the fake above. */
        const val SecurityControls = 2

        /**
         * Twelve card headers, plus the DEBUG card's switch.
         *
         * A header is a `Row.clickable` and `clickable` merges descendants, so each is one interactive node
         * even though it draws two icons and a title. Twelve is what [SettingsScreenActions]' DEFAULTS
         * produce: `scoreSettingsEnabled = false` withholds the scoring card and `minimalModeEnabled = false`
         * takes the data-management branch, and `DatabaseStatsSettingsSection` draws the thirteenth card the
         * screen used to hold inline - so the count is read off the fixture's flags rather than off the
         * screen's thirteen call sites. Only the debug card is open, so no section contributes rows.
         */
        const val SettingsScreenControls = 13

        /** The passphrase field, its reveal toggle, the confirming button and the cancel button. */
        const val VerifyingGateControls = 4

        /**
         * Two swatches' worth of floor for a grid that shows a few dozen.
         *
         * `LazyVerticalGrid` composes only what fits, so the real count is a viewport fact - the desktop
         * viewport is 1024x768 and each swatch is 44dp, which is well over two columns and several rows. The
         * floor's job is to prove the dialog OPENED (a closed one contributes the picker's own single button),
         * not to pin how many swatches a viewport holds.
         */
        const val IconSwatches = 4

        /** A key that really is in [DimensionIconCatalog], so the swatch draws as SELECTED rather than plain. */
        const val PICKER_ICON_KEY = "work"

        /** Below the number of Kotlin files this module's `commonMain` actually holds. */
        const val MinProductionFiles = 50

        const val WAIT_MS = 10_000L
        const val NOTE_ID = "n1"

        /** A fixed day, so the timeline's arithmetic cannot drift with the clock. */
        val TIMELINE_DAY: LocalDate = LocalDate.of(2026, 3, 15)
        val NOTE_STAMP: LocalDateTime = LocalDateTime.of(2026, 3, 15, 10, 0)

        /** 60dp an hour, the shared default: tall enough that an 80-minute block is not a sliver. */
        const val TIMELINE_HOUR_HEIGHT_DP = 60f
        const val TIMELINE_TASK_ID = "task-1"
        const val TIMELINE_ENTRY_ID = "entry-1"
        const val TIMELINE_OCCURRENCE_ID = "occurrence-1"
        const val TIMELINE_DIMENSION_ID = "dim_health_wellness"
        const val TIMELINE_DIMENSION_LABEL = "Health & Wellness"

        /** The path [commonMainRoot] falls back to when the repository root cannot be found; never a directory. */
        const val NOTH_A_DIRECTORY = "core/ui/src/commonMain-does-not-exist"

        /** Attribution for a null-description site that has no enclosing declaration at all. */
        const val FILE_LEVEL = "<file-level>"

        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")
        val CD_NULL = Regex("""contentDescription\s*=\s*null\b""")
        val DECLARATION =
            Regex(
                """^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |expect |actual )*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*[<(]""",
                RegexOption.MULTILINE,
            )

        /**
         * The complete inventory of `contentDescription = null` sites in this module's `commonMain`, keyed by
         * `<relative path>#<enclosing declaration>` and valued by the number of sites recorded there: 45 sites
         * across 29 declarations, verified against the tree this file's own guard walks.
         *
         * Every entry is decorative in the sense defined in the KDoc: an icon whose meaning is carried by a
         * sibling `Text` or by a merging container, never the only content of a control the user presses. The
         * per-declaration comments say WHICH of those two it is.
         *
         * UNCHANGED BY NAMING THE REMAINING CONTROLS, and that is the finding worth recording rather than a
         * stale count. The six controls this file's KDoc used to exclude as nameless were nameless because
         * their labels are SIBLING `Text`s, not because an `Icon` passed a null description - so naming them
         * removed no site from this table, and the total is still 45 across 29 declarations. One declaration's
         * COMMENT changed: [DimensionIconPicker]'s second site is no longer the only content of a control
         * nobody can name, because the swatch now carries its own description (see the case of that name).
         *
         * THIS TABLE IS NOT A LIST OF NAMELESS CONTROLS, and the two are easy to confuse. It inventories
         * DECLARATIONS, so a control can be fully named by a `Button { Text(...) }` that appears nowhere here,
         * and - the case that matters for reading the table - a declaration recorded here can still hold a
         * genuinely nameless control that Layer 2 structurally cannot see, such as a `Box.clickable` whose
         * only child is one of these decorative icons. Layer 1 is what settles those.
         */
        val recordedSiteCounts: Map<String, Int> =
            mapOf(
                // components/DimensionVisuals.kt - the badge, dropdown-badge, identity and cascade/pattern
                // rows all publish the dimension's own name on their CONTAINER, so their icons are decorative.
                "kotlin/io/payanam/core/ui/components/DimensionVisuals.kt#DimensionCompactBadge" to 1,
                "kotlin/io/payanam/core/ui/components/DimensionVisuals.kt#DimensionDropdownBadge" to 1,
                "kotlin/io/payanam/core/ui/components/DimensionVisuals.kt#DimensionIconCascadeLayer" to 1,
                "kotlin/io/payanam/core/ui/components/DimensionVisuals.kt#DimensionIconPatternLayer" to 1,
                "kotlin/io/payanam/core/ui/components/DimensionVisuals.kt#DimensionIdentityRow" to 1,
                // DimensionIconPicker: site 1's icon sits beside the "Choose icon" label Text. Site 2 is the
                // swatch's own icon, and the swatch is the control here - a `Box.clickable` whose entire
                // content is that one decorative glyph. It now carries its own description (the picker frame
                // plus this option's stable key), which is what makes site 2 decorative rather than anonymous;
                // the recorded count is 2 because BOTH icons are, not because either is unlabelled.
                "kotlin/io/payanam/core/ui/components/DimensionVisuals.kt#DimensionIconPicker" to 2,
                "kotlin/io/payanam/core/ui/components/ImportDatabaseFileButton.kt#ImportDatabaseFileButtonContent" to 1,
                "kotlin/io/payanam/core/ui/components/TaskDetailHistoryAndScore.kt#RescheduleHistorySection" to 1,
                "kotlin/io/payanam/core/ui/database/DatabaseInitDimensionSetupScreen.kt#MandatoryDimensionSetupSection" to 1,
                "kotlin/io/payanam/core/ui/database/DatabaseInitScreen.kt#DatabaseInitScreen" to 5,
                "kotlin/io/payanam/core/ui/database/DatabasePassphraseUnlockScreen.kt#DatabasePassphraseUnlockScreen" to 1,
                "kotlin/io/payanam/core/ui/day/DayScreen.kt#DateNavigationBar" to 1,
                "kotlin/io/payanam/core/ui/focus/FocusModeSelectionScreen.kt#PresetCard" to 2,
                "kotlin/io/payanam/core/ui/lenses/ScoringConfigScreen.kt#ScoringConfigScreen" to 1,
                // navigation/PayanamNavHost.kt - the nav item's own icon, beside a `.semantics { contentDescription
                // = tabLabel }` AND a `Text(tabLabel)` label, so it is decorative. It is recorded under the
                // nested helper `isRouteAllowed` because that is what the last-`fun`-above-the-site attribution
                // resolves to (see nullDescriptionSites' KDoc), and the attribution is the contract the guard
                // checks - so the key is recorded as the guard sees it, not as a reader would name it.
                "kotlin/io/payanam/core/ui/navigation/PayanamNavHost.kt#isRouteAllowed" to 1,
                // onboarding - the arrow inside the "Next"/"Start" button (whose Text merges up), and the
                // slide's illustrative icon (not inside any interactive node at all).
                "kotlin/io/payanam/core/ui/onboarding/OnboardingIntroScreen.kt#OnboardingIntroScreen" to 1,
                "kotlin/io/payanam/core/ui/onboarding/OnboardingIntroScreen.kt#OnboardingSlideContent" to 1,
                // settings - the Delete icon beside "Disable"/"Enable dimension", the card header's leading icon
                // beside the merging title Text, and the three data-management button icons, each of which sits
                // in a button that also draws a label Text.
                "kotlin/io/payanam/core/ui/settings/SettingsComponents.kt#DimensionPreferenceCard" to 1,
                "kotlin/io/payanam/core/ui/settings/SettingsComponents.kt#SettingsCard" to 1,
                "kotlin/io/payanam/core/ui/settings/SettingsDataManagementSections.kt#DataManagementSettingsSection" to 3,
                "kotlin/io/payanam/core/ui/tasks/AddTaskScreen.kt#AddTaskScreen" to 1,
                "kotlin/io/payanam/core/ui/tasks/EditTaskScreen.kt#EditTaskContent" to 1,
                "kotlin/io/payanam/core/ui/tasks/TaskDetailComponents.kt#TaskDetailContent" to 5,
                "kotlin/io/payanam/core/ui/tasks/TasksScreen.kt#HabitsTabContent" to 1,
                "kotlin/io/payanam/core/ui/tasks/TasksScreen.kt#TasksScreen" to 2,
                "kotlin/io/payanam/core/ui/tasks/TasksScreenCards.kt#TaskListRow" to 1,
                "kotlin/io/payanam/core/ui/tasks/TasksScreenParts.kt#TasksTopBar" to 2,
                // time - the two icon cascades inside the timeline blocks, which are named from their CONTAINER
                // rather than from the icons (and which Layer 1 DOES sweep).
                "kotlin/io/payanam/core/ui/time/TimeScreenTimeline.kt#TimeEntryBlock" to 2,
                "kotlin/io/payanam/core/ui/time/TimeScreenTimelineOccurrenceBlock.kt#PastOccurrenceBlock" to 2,
            )

        /**
         * The declarations whose naming rests on a MODIFIER rather than on a child `Text` - the shape Layer 2
         * structurally cannot see, because the text that names them is a sibling, not a descendant.
         *
         * Every one of these is now covered by a rendered case, which is the point of recording them here: a
         * `.semantics { contentDescription = ... }` deleted from any of them leaves a control that announces
         * only its role, and Layer 2 has no way to notice, because the null-description table does not change
         * when that modifier is removed. [the dimension icon picker names every swatch it draws] is the one
         * that no other case can cover, because opening that dialog needs a control this module's `desktopTest`
         * tree has no fixture that mounts.
         *
         * It is kept as a named constant rather than folded into prose because a reader who finds a new
         * modifier-named control should add it HERE and to the matching case, and the two places that have to
         * agree are then greppable.
         */
        const val LAYER_1_COVERS_THE_REST =
            "Every control whose name comes from a `.semantics` modifier rather than from a child Text is " +
                "named by this list: the timeline entry and past-occurrence blocks, the dimension icon picker's " +
                "swatch, the biometric / auto-backup / auto-tracking / debug switches, and the passphrase gate's " +
                "confirming button. Layer 2 cannot see the loss of any of them - removing one changes no " +
                "null-description count - so each is held by a rendered case instead."
    }
}

/** The notes the shared screen lists, as a flow the ViewModel observes; the screen test never writes. */
private class FakeNoteRepository(
    initial: List<Note>,
) : NoteRepository {
    private val notes = MutableStateFlow(initial)

    override fun getAllNotes(): Flow<List<Note>> = notes

    override fun getNotesByDimension(dimension: String): Flow<List<Note>> = flowOf(emptyList())

    override fun getNotesForDate(date: LocalDate): Flow<List<Note>> = flowOf(emptyList())

    override suspend fun getNoteById(id: String): Note? = notes.value.firstOrNull { it.id == id }

    override suspend fun createNote(input: io.payanam.domain.model.NoteInput): Note =
        throw UnsupportedOperationException("the naming sweep never writes")

    override suspend fun updateNote(
        id: String,
        input: io.payanam.domain.model.NoteInput,
    ): Note = throw UnsupportedOperationException("the naming sweep never writes")

    override suspend fun deleteNote(id: String) =
        throw UnsupportedOperationException("the naming sweep never writes")
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
    ) = throw UnsupportedOperationException("the naming sweep never writes")

    override suspend fun replaceNoteTags(
        noteId: String,
        tagNames: List<String>,
    ) = throw UnsupportedOperationException("the naming sweep never writes")

    override suspend fun replaceTimeEntryTags(
        timeEntryId: String,
        tagNames: List<String>,
    ) = throw UnsupportedOperationException("the naming sweep never writes")
}
