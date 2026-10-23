//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import io.payanam.core.ui.database.DatabaseInitScreen
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
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.desktop.database.DesktopDatabaseInitCollaborators
import io.payanam.desktop.database.DesktopPassphraseUnlockCollaborators
import io.payanam.desktop.database.databaseInitViewModel
import io.payanam.desktop.database.databasePassphraseUnlockViewModel
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
import io.payanam.shared.settings.FocusModePreset
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate

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
 * the passphrase unlock gate, and the four task-detail routes. The remaining destinations are startup gates
 * and screens the desktop cannot reach yet, and each one below says so with a `TODO` and the reason. Nothing
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
     * `DesktopPersistenceStores`, which is built in `desktopApp` and lives for the process; the opener and
     * the path are the desktop's own answers and default to them.
     */
    private val passphraseUnlockCollaborators: DesktopPassphraseUnlockCollaborators,
    /**
     * The ONE language the shell root derived, for the shared screen's onboarding pager.
     *
     * Passed in rather than read from a `CompositionLocal` because `UiStrings` is the ambient table, not
     * this screen's `language` parameter: the graph installs its provider from the language it was handed,
     * so a second derivation here could give this one destination a different language than the root.
     */
    private val language: UiLanguage,
) : PayanamNavHostScreens {
    // The desktop's own logger, already initialized from main() before any composable runs. Same
    // construction as Android's half: both are the platform implementation of the shared contract.
    private val logger = UnifiedLogger.getInstance()

    @Composable
    override fun passphraseSetup(
        nav: PayanamNavActions,
        onConfigured: () -> Unit,
    ) {
        // The desktop's startup gate owns the decision about WHICH gate is showing and renders it as a
        // whole surface, so there is no destination body to supply here yet: mounting the graph without
        // moving that decision would produce two competing gates. Tracked in the parity doc as V5.
        //
        // The sibling below is already wired even though its gate is also still mounted, because its screen
        // needed nothing the desktop cannot build. The asymmetry is deliberate and is recorded there too.
        desktopDestinationNotMigrated("passphrase setup", "V5")
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
        //  - preUnlockUpdate is null because the flow it wraps is Android's own APK install. It is an
        //    explicitly named no-op slot in the shared screen, not a missing feature.
        //  - debugLogExport is null because the exporter behind it is Android's own storage and R class.
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
            onForgotPassphraseReset = onForgotPassphraseReset,
            viewModel = viewModel,
            biometricActions = null,
            preUnlockUpdate = null,
            debugLogExport = null,
        )
    }

    @Composable
    override fun passphraseChange(
        nav: PayanamNavActions,
        onPassphraseChanged: () -> Unit,
    ) {
        // The shared screen exists, but the desktop has no `DatabasePassphraseChangeViewModel` - Android
        // builds it through Hilt, and nothing constructs it from the data layer yet. Rendering the screen
        // with a permanently-false "not saving / no error" state would look functional and be inert.
        desktopDestinationNotMigrated("passphrase change", "V5")
    }

    @Composable
    override fun databaseInit(
        nav: PayanamNavActions,
        onDatabaseReady: () -> Unit,
    ) {
        // ADDITIVE, and deliberately: the desktop's own stricter first-run gate (DesktopStartupGate's
        // InitializeDatabase section) is untouched. This destination now renders the SHARED screen, and
        // whether the desktop keeps that gate is a product decision, not a refactor.
        //
        // The two slots are the platform's, and both are honestly empty rather than stubbed:
        //  - onImportFileSelected is NOTIFICATION only. The ViewModel drives the actual pick through the
        //    documentSource it was built with (AwtDocumentSource, already wired in DesktopApp), so a
        //    launcher here would be a second picker competing with the one the flow actually uses.
        //  - debugLogExport is null because the desktop has no Android R class and no log exporter.
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
            onImportFileSelected = {},
            language = language,
            debugLogExport = null,
        )
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
        // The shared Time screen is still in `:app` (io.payanam.ui.screens.TimeScreen), not in :core:ui,
        // so the desktop module cannot reach it. V3 moves it; until then this destination cannot render.
        desktopDestinationNotMigrated("time", "V3")
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
            onOpenAssistant = {
                logger.i("PayanamNavHost", "Opening AI assistant from lenses")
                nav.openAssistant()
            },
        )
    }

    @Composable
    override fun settings(nav: PayanamNavActions) {
        // The desktop's own settings route already reads and writes the shared `app_settings` rows, so the
        // DATA is shared and only the UI is the desktop's. It takes ten parameters of app state that this
        // file is not given, so it cannot be called from here yet - supplying that state is the mount's
        // job, and doing it as a guessed default would render a settings pane that silently discards
        // writes. Tracked as V5.
        desktopDestinationNotMigrated("settings", "V5")
    }

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

    @Composable
    override fun assistant(nav: PayanamNavActions) {
        // The assistant screens live in :feature:assistant, which the desktop module does not depend on.
        // Adding that dependency is the V4 decision, not a navigation one.
        desktopDestinationNotMigrated("AI assistant", "V4")
    }

    @Composable
    override fun assistantSettings(nav: PayanamNavActions) {
        desktopDestinationNotMigrated("AI assistant settings", "V4")
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
 * A destination the desktop cannot render yet, said out loud.
 *
 * Throwing rather than rendering an empty pane is deliberate: the panes these replace currently show a
 * readiness card, and silently returning nothing would make a not-yet-migrated destination look like a
 * crash in the screen rather than a known gap in the port. The message names the phase that owns it, so
 * the failure points at the owner instead of costing an afternoon.
 */
@Composable
private fun desktopDestinationNotMigrated(
    destination: String,
    phase: String,
) {
    error(
        "Desktop destination '$destination' is not migrated yet - it is owned by $phase. " +
            "Rendering nothing here would look like a broken screen.",
    )
}
