//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.payanam.core.ui.database.DatabaseInitScreen
import io.payanam.core.ui.database.DatabasePassphraseChangeScreen
import io.payanam.core.ui.database.DatabasePassphraseSetupScreen
import io.payanam.core.ui.database.DatabasePassphraseUnlockScreen
import io.payanam.core.ui.day.DayScreen
import io.payanam.core.ui.day.DayScreenMode
import io.payanam.core.ui.focus.FocusModeSelectionScreen
import io.payanam.core.ui.lenses.LensesScreen
import io.payanam.core.ui.lenses.ScoringConfigScreen
import io.payanam.core.ui.navigation.PayanamNavActions
import io.payanam.core.ui.navigation.PayanamNavHostScreens
import io.payanam.core.ui.notes.NotesScreen
import io.payanam.core.ui.onboarding.OnboardingIntroScreen
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.core.ui.preferences.colorToStoredHex
import io.payanam.core.ui.preferences.knownAppOwnedCatalogLabels
import io.payanam.core.ui.preferences.localizedCatalogLabel
import io.payanam.core.ui.security.SecurityStateUnavailableScreen
import io.payanam.core.ui.settings.SettingsScreen
import io.payanam.core.ui.settings.SettingsScreenActions
import io.payanam.core.ui.tasks.AddTaskScreen
import io.payanam.core.ui.tasks.EditTaskScreen
import io.payanam.core.ui.tasks.ScoreDetailScreen
import io.payanam.core.ui.tasks.TaskDetailScreen
import io.payanam.core.ui.tasks.TasksScreen
import io.payanam.core.ui.tasks.TasksScreenMode
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.time.MAX_TIME_HOUR_HEIGHT_DP
import io.payanam.core.ui.time.MIN_TIME_HOUR_HEIGHT_DP
import io.payanam.core.ui.time.TimeScreen
import io.payanam.core.ui.updates.PreUnlockUpdateActions
import io.payanam.core.ui.updates.PreUnlockUpdateSection
import io.payanam.core.ui.updates.PreUnlockUpdateUiState
import io.payanam.desktop.database.DesktopDatabaseInitCollaborators
import io.payanam.desktop.database.DesktopPassphraseChangeCollaborators
import io.payanam.desktop.database.DesktopPassphraseUnlockCollaborators
import io.payanam.desktop.database.DesktopSettingsCollaborators
import io.payanam.desktop.database.databaseInitViewModel
import io.payanam.desktop.database.databasePassphraseChangeViewModel
import io.payanam.desktop.database.databasePassphraseSetupViewModel
import io.payanam.desktop.database.databasePassphraseUnlockViewModel
import io.payanam.desktop.port.NoOpPreUnlockUpdateActions
import io.payanam.presentation.day.dayPlanViewModel
import io.payanam.presentation.day.dayViewModel
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.presentation.lenses.lensHabitScoreViewModel
import io.payanam.presentation.lenses.lensViewModel
import io.payanam.presentation.lenses.scoringConfigViewModel
import io.payanam.presentation.notes.notesViewModel
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.presentation.preferences.AppPreferencesWriter
import io.payanam.presentation.preferences.DimensionLabelNormalizer
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.presentation.tasks.ScoreDetailType
import io.payanam.presentation.tasks.addTaskViewModel
import io.payanam.presentation.tasks.editTaskViewModel
import io.payanam.presentation.tasks.scoreDetailViewModel
import io.payanam.presentation.tasks.taskDetailViewModel
import io.payanam.presentation.tasks.tasksViewModel
import io.payanam.presentation.time.timeTagEditorViewModel
import io.payanam.presentation.time.timeViewModel
import io.payanam.presentation.time.timeVisualsViewModel
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.platform.SupportState
import io.payanam.shared.settings.FocusModePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The desktop's implementation of the shared navigation graph's destinations.
 *
 * The graph is in `:core:ui` and is identical on every platform; this file is the desktop half of the
 * seam, mirroring `AndroidPayanamNavHostScreens` method for method. It is deliberately NOT one file per
 * screen - that is the per-screen wrapper this migration exists to remove.
 *
 * **The one platform difference is one line of mechanism.** Android's half builds each shared ViewModel
 * through Hilt and retains it with `retainSharedViewModel(key, onRelease)`. The desktop has no Hilt, so
 * it builds the same ViewModel from the same shared factory (`xxxViewModel()`, which reads
 * `LocalDataLayer.current` on both platforms) and retains it with [desktopRetained]. Everything else -
 * the screens, the factories, the route strings, the tab policy - is already shared, which is why most of
 * these bodies are near-copies of Android's rather than new code.
 *
 * **Scope, honestly stated.** This file wires the destinations whose shared screen takes only a
 * ViewModel and navigation callbacks - tasks, habits, notes, journal, lenses, scoring config, database init,
 * the passphrase setup, unlock and change gates, and the four task-detail routes. The remaining destinations are
 * screens the desktop cannot reach yet, and each one below says so with the reason. Nothing
 * here is stubbed to look finished: a destination the desktop cannot render yet throws a clear, named error
 * rather than silently showing an empty pane, because a blank pane is indistinguishable from a bug.
 */
internal class DesktopPayanamNavHostScreens(
    /**
     * The database-init screen's collaborators, so that destination builds the SHARED ViewModel from the
     * same objects the shell holds rather than reaching for a second instance of any of them.
     *
     * Supplied per instance rather than resolved inside this class because the session controller, the
     * document source and the vault are all built in `desktopApp`, which is the only place that can hold
     * them for the process's lifetime. A default would be a second session controller, and two of those
     * would install two different data layers.
     */
    private val databaseInitCollaborators: DesktopDatabaseInitCollaborators,
    /**
     * The unlock screen's collaborators, for the same reason and by the same wiring as
     * [databaseInitCollaborators]: the shared graph must reach the desktop's OWN vault, reset and database
     * file rather than a second set of them.
     *
     * Supplied per instance for the same reason. The three that need shell-held state are fields of
     * `DesktopPersistenceStores`, which is built in `desktopApp` and lives for the process, and the
     * session controller is built there too, so the unlock and the shell cannot install two layers.
     */
    private val passphraseUnlockCollaborators: DesktopPassphraseUnlockCollaborators,
    /**
     * The passphrase-change screen's collaborators, supplied for the same reason and by the same wiring as
     * [passphraseUnlockCollaborators]: this screen MUTATES the vault and closes and re-opens the session, so
     * it has to run against the shell's own instances. A vault built here would write the new passphrase
     * into a second security store that the unlock gate never reads.
     */
    private val passphraseChangeCollaborators: DesktopPassphraseChangeCollaborators,
    /**
     * The ONE language the shell root derived, for the shared screen's onboarding pager.
     *
     * Passed in rather than read from a `CompositionLocal` because `UiStrings` is the ambient table, not
     * this screen's `language` parameter: the graph installs its provider from the language it was handed,
     * so a second derivation here could give this one destination a different language than the root.
     */
    private val language: UiLanguage,
    /**
     * The settings screen's collaborators, supplied for the same reason and by the same wiring as
     * [passphraseChangeCollaborators]: this destination reads and writes the shell's OWN rows, so it has
     * to run against the shell's own preferences source rather than opening a second one.
     *
     * The state is a PARAMETER rather than a read of the ambient `LocalAppPreferences` on purpose.
     * `AppPreferencesState.isLoading` defaults to true and two of the shared sections - appearance and
     * default landing - early-return on it, so a default-constructed state renders those two sections
     * permanently and silently empty. The shell already publishes the mapped state from the rows it read
     * under the swap lock, so the parameter carries that one and there is nothing for the shared screen to
     * derive a second copy of.
     */
    private val settingsCollaborators: DesktopSettingsCollaborators,
    /**
     * The shell's own reaction to a COMPLETED forgot-passphrase reset.
     *
     * The reset runs inside the shared unlock ViewModel over this platform's port, so the shell is not told
     * about it by construction - and the startup decision it derived is then stale in the worst way: it
     * still reports the password gate the user has just left, and the graph composes `lenses` with only the
     * bootstrap layer installed (the owner's build-405 crash). The callback re-reads the facts the decision
     * is made from; it is called BEFORE the graph's own navigation runs, so the two agree.
     */
    private val onDatabaseResetObserved: () -> Unit,
) : PayanamNavHostScreens {
    // The desktop's own logger, already initialized from main() before any composable runs. Same
    // construction as Android's half: both are the platform implementation of the shared contract.
    private val logger = UnifiedLogger.getInstance()

    @Composable
    override fun passphraseSetup(
        nav: PayanamNavActions,
        onConfigured: () -> Unit,
    ) {
        // WIRED on the same terms as the two siblings below: the shared screen and its shared ViewModel are
        // built from this module's own collaborators. The desktop's own startup gate still owns WHICH gate is
        // showing, so nothing above this was rerouted, and whether it keeps that surface is a product decision
        // rather than a refactor.
        //
        // debugLogExport is NOT empty: the desktop supplies its own exporter, because the desktop has no
        // SAF picker and no R class for Android's. DesktopLogExportActions renders the same two shared
        // buttons and the same shared result line; only the destination of the export differs. The
        // argument this slot takes is IGNORED, and that is a real difference from Android rather than a
        // shortcut: Android's exporter disables its two buttons while a save is in flight, and
        // DesktopLogExportActions declares no isSaving parameter, so the desktop supplies it unchanged
        // and IGNORES the flag this slot passes (hence `{ _ -> ... }` below). No saving state is
        // fabricated to cover it - the flag arrives from the shared screen's own uiState.isSaving, and
        // the desktop has no exporter that consumes it.
        val viewModel =
            desktopRetained(onRelease = { it.dispose() }) {
                databasePassphraseSetupViewModel(
                    // The vault the unlock gate was built over, so the passphrase this gate writes is the one
                    // that gate later verifies. A vault built here would hold a credential no gate can check.
                    passphraseUnlockCollaborators = passphraseUnlockCollaborators,
                )
            }
        DatabasePassphraseSetupScreen(
            onPassphraseConfigured = onConfigured,
            viewModel = viewModel,
            debugLogExport = { _ -> DesktopLogExportActions() },
        )
    }

    @Composable
    override fun passphraseUnlock(
        nav: PayanamNavActions,
        onUnlocked: () -> Unit,
        onForgotPassphraseReset: () -> Unit,
    ) {
        // ADDITIVE, and deliberately: the desktop's own first-run gate still owns unlock for now. This
        // destination renders the SHARED screen, so the capability is wired and reachable, but nothing
        // above it was changed to route here. Whether the desktop keeps its own stricter gate is a
        // product decision, not a refactor.
        //
        // The three platform slots are the desktop's, and every one is honestly empty rather than stubbed:
        //  - biometricActions is null because biometrics are ALIGNED DIVERGENCE #1: Android-only, permanently.
        //    The desktop does hold a BiometricUnlockPrompt, but it is DesktopBiometricUnlockPrompt, which
        //    REFUSES (a desktop session has no OS identity check) - so there is no block to render. A no-op
        //    block that appeared to authenticate would be the one thing this project forbids outright, and a
        //    platform that omits the slot is the honest answer: the screen falls back to the typed passphrase,
        //    which is a complete way in.
        //  - preUnlockUpdate is NOT null: the flow it wraps is Android's own APK install, which this
        //    platform does not have - and an omitted slot is silence, not an answer. This platform renders
        //    the SHARED section and tells it the truth: support is NOT_APPLICABLE here, so the section
        //    states that updates are installed by the package manager and offers no in-app action. The
        //    Diagnostics zone says why it has nothing to offer instead of quietly showing nothing.
        //
        // debugLogExport is NOT empty: the desktop supplies its own exporter, for the same reason as on
        // the setup and database-init gates - no SAF picker, no R class for Android's, and the two
        // shared buttons plus the shared result line render identically. This slot takes no argument,
        // so nothing has to be supplied or ignored here.
        //
        // isImportMode stays at its default false: the graph has one unlock destination and this platform
        // never reaches it to import (the desktop imports through its own transfer controller, which verifies
        // the passphrase against the file being copied), so passing true would report a mode the desktop is
        // not in.
        val viewModel =
            desktopRetained(onRelease = { it.dispose() }) {
                databasePassphraseUnlockViewModel(
                    collaborators = passphraseUnlockCollaborators,
                    // The graph's own language, so this screen's copy cannot differ from the shell's.
                    strings = UiStrings.of(language),
                )
            }
        DatabasePassphraseUnlockScreen(
            onPassphraseUnlocked = onUnlocked,
            onForgotPassphraseReset = {
                // The reset ran INSIDE the shared ViewModel, through this platform's own port, so nothing
                // told the shell: the startup decision kept the facts it had read at launch and the graph
                // then composed `lenses` with only the bootstrap layer installed, which is the crash in the
                // owner's build-405 log (`LocalDataLayer.lenses was read before a data layer was
                // installed`). Re-reading the facts BEFORE the graph's own navigation into the init gate is
                // what makes the two agree.
                logger.w("PayanamNavHost.passphraseUnlock", "Forgot-passphrase reset completed; re-reading the startup facts")
                onDatabaseResetObserved()
                onForgotPassphraseReset()
            },
            viewModel = viewModel,
            biometricActions = null,
            preUnlockUpdate = {
                // The shared section, told the truth: this platform installs its app through its own
                // package manager, so there is no in-app update action to offer.
                PreUnlockUpdateSection(
                    uiState =
                        PreUnlockUpdateUiState(
                            buildNumber = DesktopBuildInfo.PLATFORM_BUILD_NUMBER,
                            platformSupport = SupportState.NOT_APPLICABLE,
                        ),
                    // Lives in the port package, not here: this file's own render guard scans it for
                    // `override fun` lines and reads each one as a destination, so an interface
                    // implementation declared here is indistinguishable from an unwired screen.
                    actions = NoOpPreUnlockUpdateActions,
                )
            },
            debugLogExport = { DesktopLogExportActions() },
        )
    }

    @Composable
    override fun passphraseChange(
        nav: PayanamNavActions,
        onPassphraseChanged: () -> Unit,
    ) {
        // WIRED on the same terms as its three siblings above: the shared screen, built here from this
        // module's own collaborators over the SHARED ViewModel. Nothing is defaulted and nothing is stubbed -
        // the values the screen reads (`isSaving`, `errorReasonCode`, `isSuccess`) all come from the real
        // ViewModel's own state, so the desktop cannot render a form whose button does nothing.
        //
        // The ViewModel is retained with `desktopRetained` rather than `rememberSaveable`, because its
        // `scope` is a SupervisorJob that must be cancelled on the way out; the shared screen keeps its three
        // passphrase fields and their visibility toggles in `rememberSaveable` internally, exactly as on
        // Android, so a desktop recompose does not clear what the user typed.
        val viewModel =
            desktopRetained(onRelease = { it.dispose() }) {
                databasePassphraseChangeViewModel(
                    collaborators = passphraseChangeCollaborators,
                )
            }
        val passphraseChangeState by viewModel.uiState.collectAsState()
        DatabasePassphraseChangeScreen(
            onSubmit = { currentPassphrase, newPassphrase, confirmPassphrase ->
                logger.i("DatabasePassphraseChangeScreen", "Submitting passphrase change")
                viewModel.submit(
                    currentPassphrase = currentPassphrase,
                    newPassphrase = newPassphrase,
                    confirmPassphrase = confirmPassphrase,
                )
            },
            // The shared screen fires this itself when `isSuccess` flips, which on this flow is immediately
            // before the process restart - so the graph's own back navigation is what runs here, the same
            // callback Android's half passes through.
            onPassphraseChanged = onPassphraseChanged,
            isSaving = passphraseChangeState.isSaving,
            errorReasonCode = passphraseChangeState.errorReasonCode,
            isSuccess = passphraseChangeState.isSuccess,
        )
    }

    @Composable
    override fun databaseInit(
        nav: PayanamNavActions,
        onDatabaseReady: () -> Unit,
        resumesAfterReset: Boolean,
    ) {
        // ADDITIVE, and deliberately: this destination renders the SHARED screen, and the desktop's own
        // stricter first-run gate that used to stand beside it is deleted now. Whether the desktop keeps
        // any stricter behaviour is a product decision, not a refactor.
        //
        // The two slots are the platform's, and both are now real rather than empty:
        //  - onImportFileSelected is where THIS platform performs the pick. The shared screen asks the
        //    platform to pick and reports the MIME filter; the ViewModel cannot pick for itself, because
        //    its import entry takes the TOKEN a picker has already produced. The call is launched rather
        //    than run inline in the tap, and the picker itself hops off this thread before opening its
        //    blocking modal - see `AwtDocumentSource`'s DIALOG_DISPATCHER for the crash that proved why.
        //  - debugLogExport is NOT empty: the desktop supplies its own exporter, because the desktop has no
        //    SAF picker and no R class for Android's. DesktopLogExportActions renders the same two shared
        //    buttons and the same shared result line; only the destination of the export differs.
        val pickScope = rememberCoroutineScope()
        val viewModel =
            desktopRetained(onRelease = { it.dispose() }) {
                databaseInitViewModel(
                    collaborators = databaseInitCollaborators,
                    // The graph's own language, so this screen's copy cannot differ from the shell's.
                    strings = UiStrings.of(language),
                )
            }
        DatabaseInitScreen(
            onDatabaseReady = onDatabaseReady,
            viewModel = viewModel,
            // The MIME filter arrives from the shared screen and is Android's; the desktop's dialog takes
            // none, and the ViewModel's artifact matching plus its plaintext refusal are the gate on what
            // comes back. Declining the dialog ends the flow here - the picker logs the dismissal.
            onImportFileSelected = { _ ->
                pickScope.launch {
                    val token = databaseInitCollaborators.documentSource.requestSource() ?: return@launch
                    viewModel.importDatabase(token, onSuccess = onDatabaseReady)
                }
            },
            language = language,
            debugLogExport = { DesktopLogExportActions() },
            // The marker is DEVICE-LEVEL and lives beside the security store, not in the database: the tour
            // is suppressed for an installation that has already been through it, and the reset (which
            // deletes the database) cannot make it claim otherwise. The desktop used to pass a literal
            // `false` here, which meant every visit to this gate re-ran the tour.
            onboardingIntroAlreadyCompleted = databaseInitCollaborators.onboardingStore.hasCompletedOnboarding(),
            onOnboardingCompleted = { databaseInitCollaborators.onboardingStore.markOnboardingCompleted() },
            // The reset's own continuation: the graph sends a completed forgot-passphrase reset here, and
            // the user asked for their data to be replaced - so this gate opens at its create form rather
            // than re-teaching the app and offering the create/import choice over a database they just had
            // wiped.
            beginAtDatabaseCreation = resumesAfterReset,
        )
    }

    @Composable
    override fun onboardingIntro(onFinished: () -> Unit) {
        // The tour is a shared screen; the desktop's own root surface used to wrap it, and the shell's
        // root now supplies that role, so this is the screen with no wrapper - the same shape Android uses.
        OnboardingIntroScreen(language = language, onFinished = onFinished)
    }

    @Composable
    override fun securityStateUnavailable(
        onSecurityResetRequested: () -> Unit,
        onQuitRequested: () -> Unit,
    ) {
        // The refusal surface is shared; the one thing the desktop adds is the confirmation dialog, because
        // resetting destroys a store only this platform can rebuild. The copy is the same table the shared
        // screen reads, so the dialog and the surface speak the same language.
        val strings = LocalUiStrings.current
        var showResetConfirmDialog by remember { mutableStateOf(false) }
        SecurityStateUnavailableScreen(
            onSecurityResetRequested = { showResetConfirmDialog = true },
            onQuitRequested = onQuitRequested,
        )
        if (showResetConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showResetConfirmDialog = false },
                title = { Text(strings.startupResetDialogTitle) },
                text = { Text(strings.startupResetDialogBody) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onSecurityResetRequested()
                            showResetConfirmDialog = false
                        },
                    ) {
                        Text(strings.startupResetAction)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirmDialog = false }) {
                        Text(strings.startupCancelAction)
                    }
                },
            )
        }
    }

    @Composable
    override fun focusModeSelection(
        nav: PayanamNavActions,
        onPresetSelected: (FocusModePreset) -> Unit,
    ) {
        // Already shared and already rendered by the desktop, so this is the shared body with no wrapper -
        // the same shape Android uses.
        FocusModeSelectionScreen(onPresetSelected = onPresetSelected)
    }

    @Composable
    override fun tasks(nav: PayanamNavActions) {
        TasksScreen(
            viewModel = desktopRetained(onRelease = { it.dispose() }) { tasksViewModel() },
            mode = TasksScreenMode.TASKS_ONLY,
            onNavigateToAddTask = {
                logger.i("PayanamNavHost", "Navigating to add task")
                nav.openAddTask()
            },
            onNavigateToTaskDetail = { taskId ->
                logger.i("PayanamNavHost", "Navigating to task detail from Tasks", mapOf("taskId" to taskId))
                nav.openTaskDetail(taskId)
            },
            onOpenDayDetail = {
                val todayKey = LocalDate.now().toString()
                logger.i("PayanamNavHost", "Opening day detail from habits strip", mapOf("day" to todayKey))
                nav.openScoreDetail("DAY", todayKey)
            },
        )
    }

    @Composable
    override fun habits(nav: PayanamNavActions) {
        TasksScreen(
            viewModel = desktopRetained(onRelease = { it.dispose() }) { tasksViewModel() },
            mode = TasksScreenMode.HABITS_ONLY,
            onNavigateToAddTask = {
                logger.i("PayanamNavHost", "Navigating to add task from Habits")
                nav.openAddTask()
            },
            onNavigateToTaskDetail = { taskId ->
                logger.i("PayanamNavHost", "Navigating to task detail from Habits", mapOf("taskId" to taskId))
                nav.openTaskDetail(taskId)
            },
            onOpenDayDetail = {
                val todayKey = LocalDate.now().toString()
                logger.i("PayanamNavHost", "Opening day detail from habits strip", mapOf("day" to todayKey))
                nav.openScoreDetail("DAY", todayKey)
            },
        )
    }

    @Composable
    override fun time(
        nav: PayanamNavActions,
        openStartTrackingDialogRequestId: Long?,
        onOpenStartTrackingDialogHandled: (Long) -> Unit,
        openStopTrackingDialogRequestId: Long?,
        onOpenStopTrackingDialogHandled: (Long) -> Unit,
    ) {
        // Complete: the screen and all four ViewModels are shared, so this is the same screen Android
        // renders. The writes go through the SAME `app_settings` row Android writes - the desktop holds
        // the identical table behind `LocalDataLayer`, so a scale preset chosen here is the one the
        // Android app reads back.
        TimeScreen(
            viewModel = desktopRetained(onRelease = { it.dispose() }) { timeViewModel() },
            timeTagEditorViewModel = desktopRetained(onRelease = { it.dispose() }) { timeTagEditorViewModel() },
            timeVisualsViewModel = desktopRetained(onRelease = { it.dispose() }) { timeVisualsViewModel() },
            dayPlanViewModel = desktopRetained(onRelease = { it.dispose() }) { dayPlanViewModel() },
            openStartTrackingDialogRequestId = openStartTrackingDialogRequestId,
            onOpenStartTrackingDialogHandled = onOpenStartTrackingDialogHandled,
            openStopTrackingDialogRequestId = openStopTrackingDialogRequestId,
            onOpenStopTrackingDialogHandled = onOpenStopTrackingDialogHandled,
            onNavigateToTask = { taskId ->
                logger.i("PayanamNavHost", "Navigating to edit task from Time", mapOf("taskId" to taskId))
                nav.openEditTask(taskId)
            },
            onTimeHourHeightDpChange = { hourHeightDp ->
                desktopPersistTimeHourHeightDp(
                    hourHeightDp = hourHeightDp,
                    // The SHELL's single-row callback, taken from the same collaborators the settings
                    // destination writes through - so this row lands under the write mutex and republishes
                    // the state `TimeScreen` reads, rather than on a scope of this file's own that nothing
                    // cancels.
                    launchWrite = ::write,
                    setSetting = settingsCollaborators.setSetting,
                )
            },
        )
    }

    @Composable
    override fun notes(nav: PayanamNavActions) {
        NotesScreen(viewModel = desktopRetained(onRelease = { it.dispose() }) { notesViewModel() })
    }

    @Composable
    override fun journal(nav: PayanamNavActions) {
        DayScreen(viewModel = desktopRetained(onRelease = { it.dispose() }) { dayViewModel() }, mode = DayScreenMode.JOURNAL_ONLY)
    }

    @Composable
    override fun lenses(nav: PayanamNavActions) {
        // Complete: both ViewModels and the screen are shared, so this replaces the desktop's Lenses
        // placeholder with the real screen the moment the graph is mounted.
        LensesScreen(
            viewModel = desktopRetained(onRelease = { it.dispose() }) { lensViewModel() },
            scoreMatrixViewModel = desktopRetained(onRelease = { it.dispose() }) { lensHabitScoreViewModel() },
            onOpenTime = { nav.openTime() },
            onOpenTasks = { nav.openTasks() },
            onOpenHabits = { nav.openHabits() },
            onOpenJournal = { nav.openJournal() },
            onOpenNotes = { nav.openNotes() },
            onOpenScoreDetail = { type, key ->
                logger.i("PayanamNavHost", "Opening score detail from lenses", mapOf("type" to type, "key" to key))
                nav.openScoreDetail(type, key)
            },
        )
    }

    @Composable
    override fun settings(nav: PayanamNavActions) {
        // Complete: the screen, its state data class and its write path are all shared, so this is the same
        // screen Android renders. There is no shared ViewModel to retain here - `SettingsViewModel` is
        // Hilt-constructed and app-only - so the state data class is BUILT from the shell's own
        // collaborators, exactly the way `passphraseChange` builds the shared ViewModel rather than
        // resolving one.
        //
        // prefsState is the shell's own `preferences`, PASSED, not read from `LocalAppPreferences`. That is
        // the one arrangement that renders the appearance and default-landing sections: `isLoading` defaults
        // to true and both sections early-return on it, so a state that arrives default-constructed makes
        // those two sections permanently and silently empty. Nothing here recomposes on its own - the state
        // is read once per composition and the shell's recomposition is what re-renders it, which is the
        // same lifetime the ambient provider would have given.
        //
        // snackbarHostState is `remember`ed and this destination collects the write outcome onto it. A
        // no-op `scope.launch { }` would compile and render, so it is here for the reason the other bodies
        // carry their collaborators: the desktop has no Hilt ViewModel to own this scope, and the shared
        // screen takes a `SnackbarHostState` rather than a flow to report through.
        val settingsState = settingsCollaborators.preferences
        // `produceState` over the collaborators' own suspending read, because the state this screen renders
        // is a MEASUREMENT rather than a field: three repository counts and a filesystem listing, which are
        // exactly the reads Android's `SettingsViewModel` does off its own scope. `produceState` is the
        // desktop's stand-in for that ViewModel's `viewModelScope` - it launches the read once per
        // composition, cancels it on the way out, and starts with the REAL schema floor and version rather
        // than a fabricated placeholder, so the first frame cannot report a database the app never opened.
        val uiState by produceState<SettingsUiState?>(initialValue = null, settingsCollaborators) {
            value = settingsCollaborators.uiState()
        }
        SettingsScreen(
            prefsState = settingsState,
            uiState = uiState ?: return,
            debugLogExport = { DesktopLogExportActions() },
            // The desktop's own rows, so a theme chosen here is the one the shell reads on its next
            // recomposition - the SAME `app_settings` rows Android writes, read back through the shared
            // mapper rather than through a desktop-private store.
            actions =
                SettingsScreenActions(
                    onAppLanguageChange = { option ->
                        write { settingsCollaborators.writer().setAppLanguage(option.key) }
                    },
                    onThemeModeChange = { option ->
                        write { settingsCollaborators.writer().setThemeMode(option.key) }
                    },
                    onFontFamilyChange = { option ->
                        write { settingsCollaborators.writer().setFontFamily(option.key) }
                    },
                    onTimeFormatChange = { option ->
                        write { settingsCollaborators.writer().setTimeFormat(option.key) }
                    },
                    onLaunchDestinationRouteChange = { route ->
                        write { settingsCollaborators.writer().setLaunchDestination(route) }
                    },
                    // ABSENT on purpose: `onLaunchDestinationTimeChange` is the one default-landing row with
                    // its own writer method, and it also CLEARS the task-filter row. The shared screen calls
                    // it beside `onLaunchDestinationRouteChange` for the same route string, so wiring it
                    // would write `launch_destination_route` twice per tap - once from each - and the second
                    // write's clear is a second transaction for one gesture. The route write above is the
                    // complete answer for that row: the shared section reports the Time option through it.
                    onLaunchDestinationTasksChange = { filter ->
                        write { settingsCollaborators.writer().setLaunchDestinationTasks(filter?.key) }
                    },
                    // Both build-time settings decisions come from the shared holder, the same source the graph
                    // and the shared ViewModels read, which `DesktopMain.main` fills from this platform's own
                    // declared values. They were bare literals here, one of which (`scoreSettingsEnabled`)
                    // disagreed with the shared actions object's own default.
                    focusModeSettingsEnabled = PlatformFeatureFlags.current.focusModeSettingsEnabled,
                    // Minimal mode comes from the shared holder, the same one the graph mount site and the
                    // shared ViewModels read, which `DesktopMain.main` fills from this platform's own declared
                    // values. The desktop takes no build-time capability flag (it is a full-feature build), so
                    // what it installs is an explicit `false` rather than a generated constant counterpart to
                    // Android's `BuildConfig.MINIMAL_MODE`.
                    // It selects a card with no file rows, so answering it true would hide the export and
                    // import rows this platform does support - which is why it must be the installed answer and
                    // not this site's.
                    minimalModeEnabled = PlatformFeatureFlags.current.minimalModeEnabled,
                    onSetActivePreset = { preset ->
                        // The shared writer's own nine-row write, which is the desktop source's rule verbatim
                        // - including the completion row, so a preset chosen here ends onboarding the same
                        // way Android's `setActivePreset` + `markFocusModeOnboardingCompleted` pair does.
                        write { settingsCollaborators.writer().setActivePreset(preset) }
                    },
                    onSetTabVisibility = { tabRoute, visible ->
                        write { settingsCollaborators.writer().setTabVisibility(tabRoute, visible) }
                    },
                    onDimensionLabelChanged = { id, label ->
                        write {
                            settingsCollaborators.writer().setDimensionLabel(
                                dimensionId = id,
                                candidateLabel = label,
                                effectiveLanguageTag = settingsState.effectiveLanguageTag,
                            )
                        }
                    },
                    onDimensionLabelReset = { id ->
                        write { settingsCollaborators.writer().resetDimensionLabel(id) }
                    },
                    onDimensionColorChanged = { id, color ->
                        // `colorToStoredHex` is the shared converter `AppPreferencesViewModel` uses, so the
                        // stored form is the same eight-digit hex the catalog column holds.
                        write { settingsCollaborators.writer().setDimensionColor(id, colorToStoredHex(color)) }
                    },
                    onDimensionIconChanged = { id, iconKey ->
                        write { settingsCollaborators.writer().setDimensionIcon(id, iconKey) }
                    },
                    onDimensionWeightChanged = { id, weight ->
                        write { settingsCollaborators.writer().setDimensionWeight(id, weight) }
                    },
                    onDimensionVisibilityChanged = { id, visible ->
                        write { settingsCollaborators.writer().setDimensionVisibility(id, visible) }
                    },
                    onAutoTrackHabitTimeGlobalChanged = { enabled ->
                        write { settingsCollaborators.writer().setAutoTrackHabitTimeGlobal(enabled) }
                    },
                    onDayBoundaryHourChanged = { hour ->
                        write { settingsCollaborators.writer().setDayBoundaryHour(hour) }
                    },
                    onAutoTrackDimensionPreferenceChanged = { id, enabled ->
                        write { settingsCollaborators.writer().setAutoTrackDimensionPreference(id, enabled) }
                    },
                    onChartVisibilityChanged = { key, enabled ->
                        // The shared writer validates the key against `CHART_VISIBILITY_KEYS` and throws on
                        // one it does not recognise, so a typo fails loudly here instead of creating a row
                        // nothing reads.
                        write { settingsCollaborators.writer().setChartVisibility(key, enabled) }
                    },
                    // Both rows go through the shell's single-row path, NOT `writer()`, for the same reason
                    // the timeout row below does: `AppPreferencesWriter` ends at the repository and asks
                    // nothing of the platform, so a toggle written there persisted without ever reaching
                    // `DesktopApp`'s `onSettingChanged` - the only caller of `reconcileBackupSchedule` - and
                    // the in-process timer stayed on its previous schedule until the next window resume.
                    // Writing the same row here through the guarded path is what makes that reconcile run on
                    // the gesture, and it is the SAME path rather than a second one because there is already
                    // one convention for a row whose desktop effect is more than the row.
                    onAutoBackupEnabledChanged = { enabled ->
                        write { settingsCollaborators.setSetting(AppPreferenceKeys.KEY_AUTO_BACKUP_ENABLED, enabled.toString()) }
                    },
                    onAutoBackupIntervalChanged = { interval ->
                        write { settingsCollaborators.setSetting(AppPreferenceKeys.KEY_AUTO_BACKUP_INTERVAL, interval.key) }
                    },
                    // ABSENT, and the omission is the point: the shell's own `onSettingChanged` reconciles
                    // the in-process schedule for exactly these two keys (`DesktopApp.kt`), so scheduling
                    // here would be a SECOND timer for one gesture. The shared screen reports both the
                    // enable-state and the interval it was left on, which is what makes that reconciliation
                    // correct rather than accidental - and which is why the two rows above reach that
                    // callback instead of the writer.
                    onManualBackupRequested = settingsCollaborators.onManualBackupRequested,
                    onBackupRotationEnabledChanged = { enabled ->
                        write { settingsCollaborators.writer().setBackupRotationEnabled(enabled) }
                    },
                    onBackupRotationCountChanged = { count ->
                        write { settingsCollaborators.writer().setBackupRotationCount(count) }
                    },
                    // The banner is the SHELL's, drawn above the graph from
                    // `preferences.autoBackupLastErrorMessage`, so this dismisses the same thing the graph's
                    // own banner row dismisses rather than clearing a desktop-private flag the banner never
                    // reads. The shell's `onDismissBackupFailure` clears the status port the banner is
                    // derived from, and that is the one answer here.
                    onAutoBackupFailureDismissed = settingsCollaborators.onDismissBackupFailure,
                    // A REAL answer, not a skip: the desktop's backup is synchronous within one click, so
                    // the busy flag is false on every frame the screen can show. Fabricating `true` would
                    // disable the row permanently.
                    manualBackupInProgress = false,
                    // The value comes from the shared holder; the reasoning is why this platform declares it
                    // enabled rather than taking the actions object's `false` default: the scoring screen IS
                    // reachable here — `nav.openScoringConfig()` is a shared graph route and `scoringConfig` is
                    // wired below — so offering the row and the button is honest, and it matches the value
                    // Android's own build config carries. Left at the default the section would be hidden and
                    // the desktop's configured scoring model would be unreachable from settings.
                    scoreSettingsEnabled = PlatformFeatureFlags.current.scoreSettingsEnabled,
                    onNavigateToScoringConfig = { nav.openScoringConfig() },
                    onDebugLoggingEnabledChanged = { enabled ->
                        write { settingsCollaborators.writer().setDebugLoggingEnabled(enabled) }
                    },
                    // The row is written directly and the shell's own `DesktopPreferencesSource.publish`
                    // re-derives and re-clamps it on every write, so what the field shows afterwards is the
                    // shell's rule rather than the number tapped.
                    // The row goes through the shell's single-row writer, which also re-derives and re-clamps
                    // it: `DesktopPreferencesSource.publish` recomputes the timeout from the rows on every
                    // write, so what this field shows afterwards is the shell's rule, not the number tapped.
                    onUnlockTimeoutChanged = { minutes ->
                        write { settingsCollaborators.setSetting(AppPreferenceKeys.KEY_SESSION_TIMEOUT_MINUTES, minutes.toString()) }
                    },
                    // ABSENT with the biometric slot empty below: this platform has no OS identity check, so
                    // there is no row to write and no prompt to offer. `DesktopBiometricUnlockPrompt`
                    // REFUSES by construction, and a no-op here would report a capability the desktop
                    // cannot honour.
                    onDeleteArtifact = { fileName -> settingsCollaborators.deleteStaleArtifact(fileName) },
                    onCleanStaleArtifacts = { settingsCollaborators.cleanStaleArtifacts() },
                    // Android names this per-export; the desktop writes one file the user chooses, so the
                    // shell's own export dialog default is the name offered here rather than a constant.
                    exportFileName = PayanamDatabaseSchema.DATABASE_NAME,
                    onExportDestinationRequested = { settingsCollaborators.onExportDatabase() },
                    // The three pickers report an intent and the platform holds the handle. The desktop has
                    // ONE real picker - `AwtDocumentSource` - already wired in `DesktopApp`, so all three
                    // report to it and only the MIME filter differs; a picker built here would be a second
                    // modal competing with the one the export and import rows already use.
                    onImportSourceRequested = { settingsCollaborators.onImportDatabase() },
                    // ABSENT, and correct: the desktop has no uHabits importer, so a chosen file would have
                    // no flow to hand it to. The shared row is still rendered, and the count it reports is
                    // zero, which disables the map row on the section's own rule - an absent flow is better
                    // than a button that accepts a document and reports success.
                    onImportConfirmed = { settingsCollaborators.onImportDatabase() },
                    onImportDismissed = {},
                    onDeleteExportPromptDismissed = {},
                    // ABSENT: the desktop has no delete-all-data flow, so the row is NOT RENDERED - an
                    // enabled button whose confirm dialog would report a deletion that never happens is
                    // worse than no button, and this flag is what removes it (the shared section's own
                    // rule). The handler defaults stay empty; nothing taps through to them.
                    deleteAllDataEnabled = false,
                    onNavigateToPassphraseChange = { nav.openPassphraseChange() },
                ),
        )
    }

    /**
     * Runs one settings write through the SHELL's scope, rather than this destination's.
     *
     * `launchWrite` is a `suspend` function type that fires and forgets on a scope the shell owns for the
     * process, which is what makes the write survive this destination leaving the composition - a write
     * owned by a composition scope is abandoned mid-flight the moment the user navigates away, which for a
     * settings write is the gesture most likely to be followed immediately by a tap on another rail card.
     */
    private fun write(block: suspend () -> Unit) {
        logger.i("DesktopPayanamNavHostScreens.settings", "Settings write requested")
        settingsWriteScope.launch { settingsCollaborators.launchWrite { block() } }
    }

    /**
     * The scope every settings write is launched on.
     *
     * Held as a field rather than `rememberCoroutineScope()` at the call site because a settings write has to
     * survive this destination leaving the composition: a composition scope is cancelled with it, and the
     * ordinary case after a settings write is a tap on the next rail card. A `remember`-free field also means
     * a write never needs a composition to have happened, which is what lets `write` be called from a plain
     * callback in the first place.
     */
    private val settingsWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Composable
    override fun addTask(nav: PayanamNavActions) {
        AddTaskScreen(
            viewModel = desktopRetained(onRelease = { it.dispose() }) { addTaskViewModel(resultDispatcher = Dispatchers.Main) },
            onNavigateBack = { nav.goBack() },
            onTaskSaved = { nav.goBack() },
        )
    }

    @Composable
    override fun taskDetail(
        nav: PayanamNavActions,
        taskId: String,
    ) {
        TaskDetailScreen(
            viewModel = desktopRetained(onRelease = { it.dispose() }) { taskDetailViewModel() },
            taskId = taskId,
            onNavigateBack = { nav.goBack() },
            onNavigateToEdit = {
                logger.i("PayanamNavHost", "Navigating to edit task", mapOf("taskId" to taskId))
                nav.openEditTask(taskId)
            },
        )
    }

    @Composable
    override fun editTask(
        nav: PayanamNavActions,
        taskId: String,
    ) {
        EditTaskScreen(
            viewModel = desktopRetained(onRelease = { it.dispose() }) { editTaskViewModel() },
            taskId = taskId,
            onNavigateBack = { nav.goBack() },
            onTaskSaved = { nav.goBack() },
        )
    }

    @Composable
    override fun scoreDetail(
        nav: PayanamNavActions,
        type: String,
        key: String,
    ) {
        val scoreDetailType = if (type == "DAY") ScoreDetailType.DAY else ScoreDetailType.DIMENSION
        ScoreDetailScreen(
            viewModel = desktopRetained(onRelease = { it.dispose() }) { scoreDetailViewModel() },
            type = scoreDetailType,
            key = key,
            onNavigateBack = { nav.goBack() },
        )
    }

    @Composable
    override fun scoringConfig(nav: PayanamNavActions) {
        ScoringConfigScreen(
            viewModel = desktopRetained(onRelease = { it.dispose() }) { scoringConfigViewModel() },
            onNavigateBack = { nav.goBack() },
        )
    }
}

/**
 * Remembers a shared ViewModel for as long as this destination is composed, and releases it on the way out.
 *
 * The desktop's stand-in for Android's `retainSharedViewModel(key, onRelease)`, with the same shape:
 * the caller names how the ViewModel is released, so this helper never has to guess and never silently
 * skips a release it cannot perform. The shared ViewModels expose `dispose()` without implementing
 * `AutoCloseable`, so a generic `close()` here would either not compile or - worse - compile as a
 * no-op cast that leaks a running `SupervisorJob` scope for the process's lifetime.
 *
 * The one difference from Android's is the missing `key`: the desktop has no configuration changes to
 * survive, so composition scope IS the retention scope.
 */
@Composable
private fun <T> desktopRetained(
    onRelease: (T) -> Unit,
    factory: () -> T,
): T {
    val viewModel = remember { factory() }
    DisposableEffect(viewModel) { onDispose { onRelease(viewModel) } }
    return viewModel
}

/**
 * Persists the timeline hour height into the same `app_settings` row Android writes, through the SHELL's
 * single-row path rather than straight to the repository.
 *
 * The desktop has no `AppPreferencesViewModel` - that ViewModel is Hilt-constructed and Android-only - so
 * the value goes where every other settings write goes: `DesktopShellCallbacks.onSettingChanged`
 * (`DesktopApp.kt`) launches it on the shell's scope and calls `DesktopPreferencesSource.setSetting`, which
 * writes the row under its write mutex, drops a write that raced a database swap, and republishes the
 * mapped state.
 *
 * WHY THIS MATTERS, since both routes end at the same row: the write this replaces called
 * `LocalDataLayer.current.appSettings.setSetting` itself, on a throwaway `CoroutineScope(Dispatchers.IO)`
 * nobody cancels. That skipped three things the guarded path owns - the write mutex a concurrent
 * `reload()` takes, the generation guard that discards a write made against a database that has since been
 * replaced, and the republish - and `TimeScreen` reads `LocalAppPreferences` (`TimeScreen.kt:106`), which is
 * fed only by that republish. So the bypass is why a scale preset could persist and still leave the screen
 * showing the previous height until something else repainted it.
 *
 * The format is the SHARED formatter ([AppPreferencesWriter.formatHourHeightDp]), not a second
 * `String.format` here: it is the same one the writer's own `setTimeHourHeightDp` uses, so a caller that
 * formatted differently could store a row [io.payanam.core.ui.preferences.AppPreferencesMapper] parses as a
 * different height. The clamp bound comes from `:core:ui` because that is where it is declared.
 */
private fun desktopPersistTimeHourHeightDp(
    hourHeightDp: Float,
    launchWrite: (suspend () -> Unit) -> Unit,
    setSetting: (String, String?) -> Unit,
) {
    val clamped = hourHeightDp.coerceIn(MIN_TIME_HOUR_HEIGHT_DP, MAX_TIME_HOUR_HEIGHT_DP)
    launchWrite {
        setSetting(
            AppPreferenceKeys.KEY_TIME_HOUR_HEIGHT_DP,
            AppPreferencesWriter.formatHourHeightDp(clamped),
        )
    }
}
