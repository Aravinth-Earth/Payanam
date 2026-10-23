//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.ui

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.EntryPointAccessors
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.updates.PreUnlockUpdateActions
import io.payanam.core.ui.updates.PreUnlockUpdateSection
import io.payanam.core.ui.updates.PreUnlockUpdateUiState
import io.payanam.core.ui.components.IMPORT_PICKER_MIME
import io.payanam.core.ui.components.ImportDatabaseFileButton
import io.payanam.core.ui.onboarding.OnboardingIntroScreen
import io.payanam.core.ui.security.SecurityStateUnavailableScreen
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
import io.payanam.core.ui.tasks.AddTaskScreen
import io.payanam.core.ui.tasks.EditTaskScreen
import io.payanam.core.ui.tasks.ScoreDetailScreen
import io.payanam.core.ui.tasks.TaskDetailScreen
import io.payanam.core.ui.tasks.TasksScreen
import io.payanam.core.ui.tasks.TasksScreenMode
import io.payanam.core.ui.text.rememberUiLanguage
import io.payanam.core.ui.time.TimeScreen
import io.payanam.di.PassphraseUnlockEntryPoint
import io.payanam.di.UnavailableBiometricUnlockPrompt
import io.payanam.feature.assistant.ui.AssistantScreen
import io.payanam.feature.assistant.ui.AssistantSettingsScreen
import io.payanam.feature.settings.ui.SettingsScreen
import io.payanam.platform.AndroidBiometricUnlockPrompt
import io.payanam.platform.AndroidOnboardingMarker
import io.payanam.platform.DatabaseInitEntryPoint
import io.payanam.platform.PassphraseChangeEntryPoint
import io.payanam.platform.PassphraseSetupEntryPoint
import io.payanam.platform.databaseInitViewModel
import io.payanam.platform.importDatabase
import io.payanam.presentation.database.DatabaseInitViewModel
import io.payanam.presentation.database.passphraseChangeViewModel
import io.payanam.presentation.database.passphraseSetupViewModel
import io.payanam.presentation.database.passphraseUnlockViewModel
import io.payanam.presentation.day.dayPlanViewModel
import io.payanam.presentation.day.dayViewModel
import io.payanam.presentation.lenses.lensHabitScoreViewModel
import io.payanam.presentation.lenses.lensViewModel
import io.payanam.presentation.lenses.scoringConfigViewModel
import io.payanam.presentation.notes.notesViewModel
import io.payanam.presentation.tasks.ScoreDetailType
import io.payanam.presentation.tasks.addTaskViewModel
import io.payanam.presentation.tasks.editTaskViewModel
import io.payanam.presentation.tasks.scoreDetailViewModel
import io.payanam.presentation.tasks.taskDetailViewModel
import io.payanam.presentation.tasks.tasksViewModel
import io.payanam.presentation.time.timeTagEditorViewModel
import io.payanam.presentation.time.timeViewModel
import io.payanam.presentation.time.timeVisualsViewModel
import io.payanam.shared.platform.SupportState
import io.payanam.shared.security.BiometricAvailability
import io.payanam.shared.settings.FocusModePreset
import io.payanam.ui.screens.AndroidBiometricUnlockScreenActions
import io.payanam.ui.screens.DatabaseInitLogExportActions
import io.payanam.ui.screens.DatabasePassphraseSetupLogExportActions
import io.payanam.ui.screens.DatabasePassphraseUnlockLogExportActions
import io.payanam.ui.viewmodel.AppPreferencesViewModel
import io.payanam.ui.viewmodel.PreUnlockUpdateViewModel
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate

/**
 * Android's implementation of the shared navigation graph's destinations.
 *
 * The graph itself is in `:core:ui` and is identical on every platform; this file is the Android half of
 * the seam - it supplies each destination's body. It is deliberately not one file per screen: that is the
 * per-screen wrapper this migration exists to remove. It is ONE file per platform, and as each screen
 * moves into `:core:ui` its body here shrinks to a call into the shared screen with the platform's state.
 *
 * Everything Android-specific about running a screen lives here rather than in the graph: the Hilt
 * ViewModel, the log lines the call sites used to emit, and the `LocalDate` used for the day-detail route.
 */
internal class AndroidPayanamNavHostScreens(
    /**
     * The platform's own reaction to a COMPLETED forgot-passphrase reset.
     *
     * A reset deletes the database artifact family and clears the credential, so every startup fact
     * `MainActivity.onCreate` resolved describes a database that no longer exists. Its own resolution is
     * what the graph is handed as the gate flags, so the flags have to be re-resolved at that moment or the
     * graph navigates into a state the platform's facts contradict. That was the measured defect (build
     * 2038): after the reset the app bounced from `lenses` back to `passphrase_unlock` - a gate for a
     * credential the reset had just deleted - and then entered the first-run flow.
     *
     * Undefaulted on purpose: a default here would be a silent no-op at a call site that must state when a
     * reset has changed the facts.
     */
    private val onDatabaseResetObserved: () -> Unit,
) : PayanamNavHostScreens {
    private val logger = UnifiedLogger.getInstance()

    @Composable
    override fun passphraseSetup(
        nav: PayanamNavActions,
        onConfigured: () -> Unit,
    ) {
        // The ViewModel is SHARED now (`:core:presentation`), so it is built from the installed ports and
        // retained here - the platform owns retention, the shared class owns none. It cannot come from
        // `hiltViewModel()`: that factory only knows `@HiltViewModel` classes, and a shared module cannot
        // carry the annotation because the desktop has no Hilt. Same shape as the unlock gate below.
        val context = LocalContext.current
        val viewModel = retainSharedViewModel(key = "passphrase_setup", onRelease = { it.dispose() }) {
            val entryPoint =
                EntryPointAccessors.fromApplication(
                    context.applicationContext as Application,
                    PassphraseSetupEntryPoint::class.java,
                )
            passphraseSetupViewModel(
                databasePathResolver = entryPoint.databasePathResolver(),
                encryptionProbe = entryPoint.encryptionProbe(),
                passphraseVault = entryPoint.passphraseVault(),
                encryptionStateCheckpoint = entryPoint.encryptionStateCheckpoint(),
            )
        }
        // The screen is shared now (`:core:ui`), so Android supplies what only it can: the log exporter.
        // It is a slot for a stated reason - `exportLatestLog()`/`exportAllLogs()` return an Android
        // `java.io.File` written to Android's own storage - and it keeps the exact block this screen had
        // before the move, including the buttons' disabled-while-saving behaviour.
        DatabasePassphraseSetupScreen(
            onPassphraseConfigured = onConfigured,
            viewModel = viewModel,
            debugLogExport = { isSaving ->
                DatabasePassphraseSetupLogExportActions(isSaving)
            },
        )
    }

    @Composable
    override fun passphraseUnlock(
        nav: PayanamNavActions,
        onUnlocked: () -> Unit,
        onForgotPassphraseReset: () -> Unit,
    ) {
        // The unlock ViewModel is SHARED with the desktop, so it is built from the installed data layer and
        // retained here - the platform owns retention, the shared screen owns none. It cannot come from
        // `hiltViewModel<T>()`: that goes through Hilt's factory, which only knows `@HiltViewModel` classes,
        // and a class in `:core:presentation` cannot carry that annotation because the desktop has no Hilt.
        // So this is the construction site, exactly like `tasksViewModel()` above, and the collaborators
        // are the ones Hilt already binds.
        //
        // The biometric adapter is built per Activity rather than resolved from the graph: it takes a
        // `FragmentActivity` because a `BiometricPrompt` must be shown from one to survive a rotation, and
        // an Activity is not in the SingletonComponent. `LocalContext.current` IS the MainActivity here -
        // this destination only ever renders inside it. It is read HERE, not inside the `create` lambda,
        // because that lambda is an ordinary function: a `Local` read inside it is a `@Composable`
        // invocation with no composable context, and the lambda runs once per retained key so the adapter
        // is not rebuilt on every recomposition anyway.
        val context = LocalContext.current
        val activity = context as? FragmentActivity
        val biometricPrompt =
            if (activity != null) {
                AndroidBiometricUnlockPrompt(
                    activity,
                    EntryPointAccessors
                        .fromApplication(
                            context.applicationContext as Application,
                            PassphraseUnlockEntryPoint::class.java,
                        ).databaseEncryptionManager(),
                )
            } else {
                // No Activity, so no prompt can be shown. The gate says so rather than throwing: the typed
                // passphrase is still a complete way in, and a screen that refused to render would strand a
                // user whose only problem is an unusual context.
                logger.w(
                    "PayanamNavHost.passphraseUnlock",
                    "No FragmentActivity in this context; biometric unlock unavailable on the unlock gate",
                )
                UnavailableBiometricUnlockPrompt
            }
        val unlockViewModel =
            retainSharedViewModel(key = "passphrase_unlock", onRelease = { it.dispose() }) {
                val entryPoint =
                    EntryPointAccessors.fromApplication(
                        context.applicationContext as Application,
                        PassphraseUnlockEntryPoint::class.java,
                    )
                passphraseUnlockViewModel(
                    vault = entryPoint.passphraseVault(),
                    biometricPrompt = biometricPrompt,
                    forgottenPassphraseReset = entryPoint.forgottenPassphraseReset(),
                    databaseOpener = entryPoint.payanamDatabaseOpener(),
                    databaseFile = entryPoint.databaseFile(),
                    strings = entryPoint.uiStrings(),
                )
            }
        // The screen is shared now (`:core:ui`), so Android supplies what only it can: the biometric
        // block, the pre-unlock APK-update hatch, and the log exporter. Each is a slot for a stated
        // reason, and each keeps the exact behaviour this file's screen had before the move.
        //
        // `biometricPromptAvailable` is the SAME expression the screen used inside itself, read from the
        // shared ViewModel: it deliberately is not a "is the adapter the Android one" type check, because
        // the original gate asked whether a prompt can be shown right now, and that is the adapter's
        // answer. `UnauthenticatedBiometricUnlockPrompt` still answers honestly to that question.
        val preUnlockUpdateViewModel: PreUnlockUpdateViewModel = hiltViewModel()
        DatabasePassphraseUnlockScreen(
            onPassphraseUnlocked = onUnlocked,
            onForgotPassphraseReset = {
                // The reset has just run: it deleted the database artifacts and cleared the credential. The
                // gate flags this Activity resolved in `onCreate` now describe a database that is not there,
                // so they are re-resolved BEFORE the graph's own navigation into the init gate - otherwise
                // the stale unlock flag bounces the user straight back to a gate for a credential that no
                // longer exists, which is exactly what build 2038's log shows.
                logger.w("PayanamNavHost.passphraseUnlock", "Forgot-passphrase reset completed; re-resolving the startup gates")
                onDatabaseResetObserved()
                onForgotPassphraseReset()
            },
            viewModel = unlockViewModel,
            biometricActions = AndroidBiometricUnlockScreenActions(
                context = context,
                biometricPromptAvailable = unlockViewModel.biometricAvailability() is BiometricAvailability.Available,
                biometricEnabled = unlockViewModel.isBiometricUnlockEnabled(),
                onUnlockRequested = {
                    unlockViewModel.startBiometricUnlock(onSuccess = onUnlocked)
                },
                logger = logger,
            ),
            preUnlockUpdate = {
                // The section itself now lives in :core:ui; this lambda is the Android ADAPTER - it maps
                // this platform's ViewModel onto the framework-free state/actions contract the shared
                // section takes. collectAsState lives here rather than in the shared body because this is
                // the composable scope that owns the collection.
                //
                // RENDERED ON EVERY PLATFORM NOW, off one shared contract. `isFDroidBuild` is a boolean
                // about one installer, so it is translated here, where the platform's own answer is
                // known, into the three-valued state the section renders: Android has an in-app APK
                // updater and either runs it (SUPPORTED) or has its updates taken over by F-Droid's
                // packager (DECLINES). Neither value is a mapping Android can be wrong about - both
                // platforms agree that a store-managed install draws the static note.
                val downloadState by preUnlockUpdateViewModel.downloadState.collectAsState()
                val isChecking by preUnlockUpdateViewModel.checking.collectAsState()
                val resultMessage by preUnlockUpdateViewModel.checkResultMessage.collectAsState()
                PreUnlockUpdateSection(
                    uiState = PreUnlockUpdateUiState(
                        buildNumber = preUnlockUpdateViewModel.currentBuildNumber,
                        platformSupport =
                            if (preUnlockUpdateViewModel.isFDroidBuild) {
                                SupportState.DECLINES
                            } else {
                                SupportState.SUPPORTED
                            },
                        downloadState = downloadState,
                        isChecking = isChecking,
                        checkResultMessage = resultMessage,
                    ),
                    actions = AndroidPreUnlockUpdateActions(preUnlockUpdateViewModel),
                )
            },
            debugLogExport = { DatabasePassphraseUnlockLogExportActions() },
        )
    }

    @Composable
    override fun passphraseChange(
        nav: PayanamNavActions,
        onPassphraseChanged: () -> Unit,
    ) {
        // The screen itself is in :core:ui and rendered by every platform. There is deliberately no
        // Android wrapper for it: the ViewModel and the log line are wired here, at the one call site.
        //
        // The ViewModel is SHARED now (`:core:presentation`), so it is built from the installed ports and
        // retained here - the platform owns retention, the shared class owns none, and `hiltViewModel()`
        // cannot build it because that factory only knows `@HiltViewModel` classes, which a shared module
        // cannot carry. Same shape as the unlock gate above.
        val context = LocalContext.current
        val passphraseChangeViewModel =
            retainSharedViewModel(key = "passphrase_change", onRelease = { it.dispose() }) {
                val entryPoint =
                    EntryPointAccessors.fromApplication(
                        context.applicationContext as Application,
                        PassphraseChangeEntryPoint::class.java,
                    )
                passphraseChangeViewModel(
                    databasePathResolver = entryPoint.databasePathResolver(),
                    passphraseVault = entryPoint.passphraseVault(),
                    rekey = entryPoint.databaseRekey(),
                    sessionManager = entryPoint.databaseSessionManager(),
                    processRestart = entryPoint.processRestart(),
                )
            }
        val passphraseChangeState by passphraseChangeViewModel.uiState.collectAsState()
        DatabasePassphraseChangeScreen(
            onSubmit = { currentPassphrase, newPassphrase, confirmPassphrase ->
                logger.i("DatabasePassphraseChangeScreen", "Submitting passphrase change")
                passphraseChangeViewModel.submit(
                    currentPassphrase = currentPassphrase,
                    newPassphrase = newPassphrase,
                    confirmPassphrase = confirmPassphrase,
                )
            },
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
        // The screen itself is in :core:ui and rendered by every platform. The ViewModel is built
        // from the installed data layer and retained here - the platform owns retention, the shared
        // screen owns none. The SAF picker and the log exporter are supplied as slots.
        val context = LocalContext.current
        // The onboarding fact is DEVICE-LEVEL (see [AndroidOnboardingMarker]): read from this app's private
        // preferences rather than from the database, so the reset that deletes the database cannot make the
        // user sit through the tour again.
        val onboardingMarker = remember(context) { AndroidOnboardingMarker.from(context) }
        val viewModel = retainSharedViewModel(key = "database_init", onRelease = { it.dispose() }) {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext as Application,
                DatabaseInitEntryPoint::class.java,
            )
            databaseInitViewModel(
                appSettingsRepository = entryPoint.appSettingsRepository(),
                databaseSessionManager = entryPoint.databaseSessionManager(),
                lifeDimensionCatalogRepository = entryPoint.lifeDimensionCatalogRepository(),
                healthCheck = entryPoint.healthCheck(),
                databasePathResolver = entryPoint.databasePathResolver(),
                encryptionProbe = entryPoint.encryptionProbe(),
                documentSource = entryPoint.documentSource(),
                databaseInitFlagWriter = entryPoint.databaseInitFlagWriter(),
                databaseInitFlagReader = entryPoint.databaseInitFlagReader(),
                databaseInitWalConsolidator = entryPoint.databaseInitWalConsolidator(),
                dimensionSetupPersister = entryPoint.appDimensionSetupPersister(),
                encryptionStateCheckpoint = entryPoint.encryptionStateCheckpoint(),
                passphraseVault = entryPoint.passphraseVault(),
                strings = entryPoint.uiStrings(),
            )
        }
        val importFileLauncher =
            rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { viewModel.importDatabase(it, onSuccess = onDatabaseReady) }
            }
        DatabaseInitScreen(
            onDatabaseReady = onDatabaseReady,
            viewModel = viewModel,
            onImportFileSelected = { mime -> importFileLauncher.launch(mime) },
            language = rememberUiLanguage(),
            debugLogExport = {
                DatabaseInitLogExportActions()
            },
            // Read fresh on every composition of this destination, so the write below is visible to a
            // re-entry without a second copy of the fact being kept in this file.
            onboardingIntroAlreadyCompleted = onboardingMarker.hasCompletedOnboarding(),
            onOnboardingCompleted = { onboardingMarker.markOnboardingCompleted() },
            // The reset's own continuation: the user asked for their data to be replaced, so this gate opens
            // at the create form rather than re-teaching the app and offering the create/import choice.
            beginAtDatabaseCreation = resumesAfterReset,
        )
    }

    @Composable
    override fun focusModeSelection(
        nav: PayanamNavActions,
        onPresetSelected: (FocusModePreset) -> Unit,
    ) {
        FocusModeSelectionScreen(
            onPresetSelected = onPresetSelected,
        )
    }

    @Composable
    override fun onboardingIntro(onFinished: () -> Unit) {
        // The shared tour screen. Android raises the flag that reaches this destination only if its own
        // first-run flow ever routes here; today it does not - the flag is the desktop's - so this is the
        // shared screen wired for completeness rather than a second onboarding path.
        OnboardingIntroScreen(language = rememberUiLanguage(), onFinished = onFinished)
    }

    @Composable
    override fun securityStateUnavailable(
        onSecurityResetRequested: () -> Unit,
        onQuitRequested: () -> Unit,
    ) {
        // The shared refusal surface, wired for completeness: Android's own startup flow does not raise
        // the flag that reaches it today. The two callbacks are the graph's contract, so they are passed
        // through rather than stubbed with a no-op that would look wired and do nothing.
        SecurityStateUnavailableScreen(
            onSecurityResetRequested = onSecurityResetRequested,
            onQuitRequested = onQuitRequested,
        )
    }

    @Composable
    override fun tasks(nav: PayanamNavActions) {
        TasksScreen(
            // The screen is shared with the desktop, so it takes its ViewModel as a parameter: this module
            // builds it from the installed data layer and retains it across configuration changes - the
            // platform owns retention, the shared screen owns none.
            viewModel = retainSharedViewModel(key = "tasks", onRelease = { it.dispose() }) { tasksViewModel() },
            mode = TasksScreenMode.TASKS_ONLY,
            onNavigateToAddTask = {
                logger.i("PayanamNavHost", "Navigating to add task", mapOf())
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
            // A separate key from the Tasks tab: the two tabs are different screens with different state.
            viewModel = retainSharedViewModel(key = "habits", onRelease = { it.dispose() }) { tasksViewModel() },
            mode = TasksScreenMode.HABITS_ONLY,
            onNavigateToAddTask = {
                logger.i("PayanamNavHost", "Navigating to add task from Habits", mapOf())
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
        // Android's own preferences ViewModel, created HERE rather than inside the shared screen: it is
        // Hilt-constructed and this platform's, so the screen takes the scale-preset write as a
        // parameter and each platform keeps its own way of persisting the shared row.
        val prefsViewModel: AppPreferencesViewModel = hiltViewModel()
        TimeScreen(
            viewModel = retainSharedViewModel(key = "time", onRelease = { it.dispose() }) { timeViewModel() },
            timeTagEditorViewModel =
            retainSharedViewModel(key = "timeTagEditor", onRelease = { it.dispose() }) { timeTagEditorViewModel() },
            timeVisualsViewModel = retainSharedViewModel(key = "timeVisuals", onRelease = { it.dispose() }) { timeVisualsViewModel() },
            dayPlanViewModel = retainSharedViewModel(key = "dayPlan", onRelease = { it.dispose() }) { dayPlanViewModel() },
            openStartTrackingDialogRequestId = openStartTrackingDialogRequestId,
            onOpenStartTrackingDialogHandled = onOpenStartTrackingDialogHandled,
            openStopTrackingDialogRequestId = openStopTrackingDialogRequestId,
            onOpenStopTrackingDialogHandled = onOpenStopTrackingDialogHandled,
            onNavigateToTask = { taskId ->
                logger.i("PayanamNavHost", "Navigating to edit task from Time", mapOf("taskId" to taskId))
                nav.openEditTask(taskId)
            },
            onTimeHourHeightDpChange = { hourHeightDp ->
                prefsViewModel.setTimeHourHeightDp(hourHeightDp)
            },
        )
    }

    @Composable
    override fun notes(nav: PayanamNavActions) {
        // The Notes ViewModel is shared with the desktop, so it is built from the installed
        // data layer and retained here - the platform owns retention, the shared screen owns none.
        NotesScreen(
            viewModel = retainSharedViewModel(key = "notes", onRelease = { it.dispose() }) { notesViewModel() },
        )
    }

    @Composable
    override fun journal(nav: PayanamNavActions) {
        // The Day ViewModel is shared with the desktop, so it is built from the installed data layer and
        // retained here - the platform owns retention, the shared screen owns none.
        DayScreen(
            viewModel = retainSharedViewModel(key = "day", onRelease = { it.dispose() }) { dayViewModel() },
            mode = DayScreenMode.JOURNAL_ONLY,
        )
    }

    @Composable
    override fun lenses(nav: PayanamNavActions) {
        LensesScreen(
            viewModel = retainSharedViewModel(key = "lens", onRelease = { it.dispose() }) { lensViewModel() },
            scoreMatrixViewModel = retainSharedViewModel(key = "lens_score", onRelease = { it.dispose() }) {
                lensHabitScoreViewModel()
            },
            onOpenTime = {
                logger.i("PayanamNavHost", "Navigating to time from unified lenses", mapOf())
                nav.openTime()
            },
            onOpenTasks = {
                logger.i("PayanamNavHost", "Navigating to tasks from unified lenses", mapOf())
                nav.openTasks()
            },
            onOpenHabits = {
                logger.i("PayanamNavHost", "Navigating to habits from unified lenses", mapOf())
                nav.openHabits()
            },
            onOpenJournal = {
                logger.i("PayanamNavHost", "Navigating to journal from unified lenses", mapOf())
                nav.openJournal()
            },
            onOpenNotes = {
                logger.i("PayanamNavHost", "Navigating to notes from unified lenses", mapOf())
                nav.openNotes()
            },
            onOpenScoreDetail = { type, key ->
                logger.i("PayanamNavHost", "Opening score detail from lenses", mapOf("type" to type, "key" to key))
                nav.openScoreDetail(type, key)
            },
            onOpenAssistant = {
                logger.i("PayanamNavHost", "Opening AI assistant from lenses", mapOf())
                nav.openAssistant()
            },
        )
    }

    @Composable
    override fun settings(nav: PayanamNavActions) {
        SettingsScreen(
            onNavigateToPassphraseChange = {
                logger.i("PayanamNavHost", "Navigating to passphrase change", mapOf())
                nav.openPassphraseChange()
            },
            onNavigateToScoringConfig = {
                logger.i("PayanamNavHost", "Navigating to scoring config", mapOf())
                nav.openScoringConfig()
            },
            onNavigateToDatabaseInit = {
                logger.i("PayanamNavHost", "Delete all data complete; restarting process for clean DB init", mapOf())
                nav.onRestartAfterDataDelete()
            },
        )
    }

    @Composable
    override fun addTask(nav: PayanamNavActions) {
        AddTaskScreen(
            // The result callback is delivered on this platform's UI dispatcher: on Android the shared
            // ViewModel used to reach for Dispatchers.Main itself, which a platform-neutral module cannot do.
            viewModel = retainSharedViewModel(key = "add_task", onRelease = { it.dispose() }) {
                addTaskViewModel(resultDispatcher = Dispatchers.Main.immediate)
            },
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
            viewModel = retainSharedViewModel(
                key = "task_detail_$taskId",
                onRelease = { it.dispose() },
            ) { taskDetailViewModel() },
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
            viewModel = retainSharedViewModel(
                key = "edit_task_$taskId",
                onRelease = { it.dispose() },
            ) { editTaskViewModel() },
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
        val scoreDetailType =
            if (type == "DAY") {
                ScoreDetailType.DAY
            } else {
                ScoreDetailType.DIMENSION
            }
        ScoreDetailScreen(
            viewModel = retainSharedViewModel(
                key = "score_detail_${scoreDetailType.name}_$key",
                onRelease = { it.dispose() },
            ) { scoreDetailViewModel() },
            type = scoreDetailType,
            key = key,
            onNavigateBack = { nav.goBack() },
        )
    }

    @Composable
    override fun scoringConfig(nav: PayanamNavActions) {
        ScoringConfigScreen(
            viewModel = retainSharedViewModel(key = "scoring_config", onRelease = { it.dispose() }) {
                scoringConfigViewModel()
            },
            onNavigateBack = { nav.goBack() },
        )
    }

    @Composable
    override fun assistant(nav: PayanamNavActions) {
        AssistantScreen(
            onNavigateBack = { nav.goBack() },
            onOpenSettings = {
                logger.i("PayanamNavHost", "Opening assistant settings", mapOf())
                nav.openAssistantSettings()
            },
        )
    }

    @Composable
    override fun assistantSettings(nav: PayanamNavActions) {
        AssistantSettingsScreen(
            onNavigateBack = { nav.goBack() },
        )
    }
}


/**
 * Adapts [PreUnlockUpdateViewModel] to the framework-free [PreUnlockUpdateActions] the shared section
 * takes. It exists so the shared section never has to know this platform's ViewModel type.
 */
private class AndroidPreUnlockUpdateActions(
    private val viewModel: PreUnlockUpdateViewModel,
) : PreUnlockUpdateActions {
    override fun onCheckRequested() = viewModel.checkForUpdate()

    override fun onDownloadRequested() = viewModel.download()

    override fun onInstallRequested() = viewModel.install()
}
