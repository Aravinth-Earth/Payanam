//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import io.payanam.shared.journal.JournalSnapshot
import io.payanam.shared.notes.DesktopNotesSnapshot
import io.payanam.shared.settings.DesktopLanguage
import io.payanam.shared.settings.DesktopSettingsContracts
import io.payanam.shared.settings.DesktopSettingsSnapshot
import io.payanam.shared.settings.DesktopThemeMode
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.settings.FocusModePreset
import io.payanam.shared.settings.FoundationReadiness
import io.payanam.shared.settings.SettingsFoundationSnapshot
import io.payanam.shared.tasks.DesktopHabitSortOption
import io.payanam.shared.tasks.DesktopTaskBoardContracts
import io.payanam.shared.tasks.DesktopTaskBoardSnapshot
import io.payanam.shared.tasks.DesktopTaskFilter
import io.payanam.shared.tasks.DesktopTaskSortOption
import io.payanam.shared.transfer.DataModuleSelection

internal data class DesktopLifecycleState(
    val desktopSettings: DesktopSettingsSnapshot,
    val settingsFilePath: String,
    val bootstrapSnapshot: DesktopBootstrapSnapshot,
    val bootstrapFilePath: String,
    val securityFilePath: String,
    val databaseFilePath: String,
    val exportDirectoryPath: String,
)

internal data class DesktopShellRenderState(
    val navigationModel: DesktopNavigationModel,
    val activeRoute: DesktopTopLevelRoute,
    val foundationSnapshot: SettingsFoundationSnapshot,
    val desktopSettings: DesktopSettingsSnapshot,
    val settingsFilePath: String,
    val desktopLifecycleState: DesktopLifecycleState,
    val taskBoardSnapshot: DesktopTaskBoardSnapshot,
    val taskBoardFilePath: String,
    val taskCatalogFilePath: String,
    val journalState: DesktopJournalState,
    val journalFilePath: String,
    val notesState: DesktopNotesState,
    val notesFilePath: String,
)

internal data class DesktopShellCallbacks(
    val onRouteSelected: (DesktopTopLevelRoute) -> Unit,
    val onSettingsChanged: (DesktopSettingsSnapshot) -> Unit,
    val onSelectionChanged: (DataModuleSelection) -> Unit,
    val onTaskBoardChanged: (DesktopTaskBoardSnapshot) -> Unit,
    val onJournalDateSelected: (String) -> Unit,
    val onJournalOverallResponseChanged: (String, String) -> Unit,
    val onJournalDimensionResponseChanged: (String, String, String) -> Unit,
    val onCreateNote: (String, String?, String?, String?, List<String>) -> Unit,
    val onUpdateNote: (String, String, String?, String?, String?, List<String>) -> Unit,
    val onDeleteNote: (String) -> Unit,
    val onConfigurePassphrase: (String) -> DesktopPassphraseActionResult,
    val onUnlockPassphrase: (String) -> DesktopPassphraseActionResult,
    val onForgotPassphraseReset: () -> Unit,
    val onInitializeDatabase: () -> Unit,
    val onCompleteFocusModeOnboarding: (FocusModePreset) -> Unit,
    val onExportLocalState: () -> DesktopDataHandoffSnapshot,
    val onImportLocalState: () -> DesktopDataHandoffSnapshot,
)

internal data class DesktopPersistenceStores(
    val persistenceDatabase: DesktopPersistenceDatabase,
    val settingsStore: DesktopSettingsStore,
    val bootstrapStore: DesktopBootstrapStore,
    val securityStore: DesktopSecurityStore,
    val databaseStore: DesktopDatabaseStore,
    val taskBoardStore: DesktopTaskBoardStore,
    val taskCatalogStore: DesktopTaskCatalogStore,
    val journalStore: DesktopJournalStore,
    val noteStore: DesktopNoteStore,
    val dataHandoffStore: DesktopDataHandoffStore,
)

internal data class DesktopRememberedState(
    val selectionState: MutableState<DataModuleSelection>,
    val desktopSettingsState: MutableState<DesktopSettingsSnapshot>,
    val bootstrapSnapshotState: MutableState<DesktopBootstrapSnapshot>,
    val securitySnapshotState: MutableState<DesktopSecuritySnapshot>,
    val databaseSnapshotState: MutableState<DesktopDatabaseSnapshot>,
    val taskBoardSnapshotState: MutableState<DesktopTaskBoardSnapshot>,
    val taskCatalogState: MutableState<DesktopTaskCatalogState>,
    val journalState: MutableState<DesktopJournalState>,
    val notesState: MutableState<DesktopNotesState>,
    val sessionOpenState: MutableState<Boolean>,
)

internal data class DesktopAppModels(
    val selection: DataModuleSelection,
    val desktopSettings: DesktopSettingsSnapshot,
    val startupMode: DesktopStartupMode,
    val startupRuntimeState: DesktopStartupRuntimeState,
    val startupSnapshot: io.payanam.shared.startup.DesktopStartupSnapshot,
    val databaseSnapshot: DesktopDatabaseSnapshot,
    val navigationModel: DesktopNavigationModel,
    val desktopLifecycleState: DesktopLifecycleState,
    val shellRenderState: DesktopShellRenderState,
)

internal data class DesktopShellRenderInputs(
    val startupSnapshot: io.payanam.shared.startup.DesktopStartupSnapshot,
    val navigationModel: DesktopNavigationModel,
    val activeRoute: DesktopTopLevelRoute,
    val foundationSnapshot: SettingsFoundationSnapshot,
    val desktopSettings: DesktopSettingsSnapshot,
    val desktopLifecycleState: DesktopLifecycleState,
    val taskBoardSnapshot: DesktopTaskBoardSnapshot,
    val journalState: DesktopJournalState,
    val notesState: DesktopNotesState,
)

@Composable
internal fun rememberDesktopPersistenceStores(sessionLogger: DesktopSessionLogger): DesktopPersistenceStores =
    remember(sessionLogger) {
        val persistenceDatabase =
            DesktopPersistenceDatabase(
                logEvent = { source, message, data ->
                    sessionLogger.i(source = source, message = message, data = data)
                },
            )
        DesktopPersistenceStores(
            persistenceDatabase = persistenceDatabase,
            settingsStore =
                DesktopSettingsStore(
                    persistenceDatabase = persistenceDatabase,
                    logEvent = { source, message, data ->
                        sessionLogger.i(source = source, message = message, data = data)
                    },
                ),
            bootstrapStore =
                DesktopBootstrapStore(
                    persistenceDatabase = persistenceDatabase,
                    logEvent = { source, message, data ->
                        sessionLogger.i(source = source, message = message, data = data)
                    },
                ),
            securityStore =
                DesktopSecurityStore(
                    persistenceDatabase = persistenceDatabase,
                    logEvent = { source, message, data ->
                        sessionLogger.i(source = source, message = message, data = data)
                    },
                ),
            databaseStore =
                DesktopDatabaseStore(
                    persistenceDatabase = persistenceDatabase,
                    logEvent = { source, message, data ->
                        sessionLogger.i(source = source, message = message, data = data)
                    },
                ),
            taskBoardStore =
                DesktopTaskBoardStore(
                    persistenceDatabase = persistenceDatabase,
                    logEvent = { source, message, data ->
                        sessionLogger.i(source = source, message = message, data = data)
                    },
                ),
            taskCatalogStore =
                DesktopTaskCatalogStore(
                    persistenceDatabase = persistenceDatabase,
                    logEvent = { source, message, data ->
                        sessionLogger.i(source = source, message = message, data = data)
                    },
                ),
            journalStore =
                DesktopJournalStore(
                    persistenceDatabase = persistenceDatabase,
                    logEvent = { source, message, data ->
                        sessionLogger.i(source = source, message = message, data = data)
                    },
                ),
            noteStore =
                DesktopNoteStore(
                    persistenceDatabase = persistenceDatabase,
                    logEvent = { source, message, data ->
                        sessionLogger.i(source = source, message = message, data = data)
                    },
                ),
            dataHandoffStore =
                DesktopDataHandoffStore(
                    logEvent = { source, message, data ->
                        sessionLogger.i(source = source, message = message, data = data)
                    },
                ),
        )
    }

@Composable
internal fun rememberDesktopMutableState(stores: DesktopPersistenceStores): DesktopRememberedState =
    DesktopRememberedState(
        selectionState = remember { mutableStateOf(DataModuleSelection()) },
        desktopSettingsState =
            remember {
                mutableStateOf(
                    runCatching { stores.settingsStore.loadSnapshot() }
                        .getOrElse { DesktopSettingsContracts.defaultSnapshot() },
                )
            },
        bootstrapSnapshotState =
            remember {
                mutableStateOf(
                    runCatching { stores.bootstrapStore.ensureSnapshot() }
                        .getOrElse { DesktopBootstrapSnapshot() },
                )
            },
        securitySnapshotState =
            remember {
                mutableStateOf(
                    runCatching { stores.securityStore.ensureSnapshot() }
                        .getOrElse { DesktopSecuritySnapshot() },
                )
            },
        databaseSnapshotState =
            remember {
                mutableStateOf(
                    runCatching { stores.databaseStore.loadSnapshot() }
                        .getOrElse {
                            DesktopDatabaseSnapshot(
                                databaseFilePath = stores.databaseStore.getDatabaseFilePath().toString(),
                                hasArtifacts = false,
                                initCompleted = false,
                                databaseSizeKb = 0L,
                                databaseLastModifiedMs = 0L,
                            )
                        },
                )
            },
        taskBoardSnapshotState =
            remember {
                mutableStateOf(
                    runCatching { stores.taskBoardStore.loadSnapshot() }
                        .getOrElse { DesktopTaskBoardContracts.snapshot() },
                )
            },
        taskCatalogState =
            remember {
                mutableStateOf(
                    runCatching { stores.taskCatalogStore.loadState() }
                        .getOrElse { DesktopTaskCatalogState(catalog = DesktopTaskBoardContracts.seededCatalog()) },
                )
            },
        journalState =
            remember {
                mutableStateOf(
                    runCatching { stores.journalStore.loadState() }
                        .getOrElse {
                            DesktopJournalState(
                                snapshot = JournalSnapshot(),
                                selectedDateIso =
                                    java.time.LocalDate
                                        .now()
                                        .toString(),
                            )
                        },
                )
            },
        notesState =
            remember {
                mutableStateOf(
                    runCatching { stores.noteStore.loadState() }
                        .getOrElse { DesktopNotesState(snapshot = DesktopNotesSnapshot()) },
                )
            },
        sessionOpenState = remember { mutableStateOf(false) },
    )

internal fun DesktopThemeMode.nextDesktopOption(): DesktopThemeMode =
    DesktopThemeMode.entries[(ordinal + 1) % DesktopThemeMode.entries.size]

internal fun DesktopLanguage.nextDesktopOption(): DesktopLanguage = DesktopLanguage.entries[(ordinal + 1) % DesktopLanguage.entries.size]

internal fun DesktopTopLevelRoute.nextDesktopOption(): DesktopTopLevelRoute =
    DesktopTopLevelRoute.entries[(ordinal + 1) % DesktopTopLevelRoute.entries.size]

internal fun DesktopTaskFilter.nextDesktopOption(): DesktopTaskFilter =
    DesktopTaskFilter.entries[(ordinal + 1) % DesktopTaskFilter.entries.size]

internal fun DesktopTaskSortOption.nextDesktopOption(): DesktopTaskSortOption =
    DesktopTaskSortOption.entries[(ordinal + 1) % DesktopTaskSortOption.entries.size]

internal fun DesktopHabitSortOption.nextDesktopOption(): DesktopHabitSortOption =
    DesktopHabitSortOption.entries[(ordinal + 1) % DesktopHabitSortOption.entries.size]

internal fun FoundationReadiness.swatch(): Color =
    when (this) {
        FoundationReadiness.SharedReady -> @Suppress("MagicNumber") Color(0xFF3E7B5A)
        FoundationReadiness.ExtractionNext -> @Suppress("MagicNumber") Color(0xFFB47B2A)
        FoundationReadiness.AndroidOnly -> @Suppress("MagicNumber") Color(0xFF9C4F4F)
    }

@Composable
internal fun rememberDesktopAppModels(
    stores: DesktopPersistenceStores,
    rememberedState: DesktopRememberedState,
    activeRoute: DesktopTopLevelRoute,
): DesktopAppModels {
    val selection = rememberedState.selectionState.value
    val desktopSettings = rememberedState.desktopSettingsState.value
    val bootstrapSnapshot = rememberedState.bootstrapSnapshotState.value
    val securitySnapshot = rememberedState.securitySnapshotState.value
    val databaseSnapshot = rememberedState.databaseSnapshotState.value
    val taskBoardPreferences = rememberedState.taskBoardSnapshotState.value.preferences
    val taskCatalogState = rememberedState.taskCatalogState.value
    val journalState = rememberedState.journalState.value
    val notesState = rememberedState.notesState.value
    val sessionOpen = rememberedState.sessionOpenState.value
    val foundationSnapshot = remember(selection) { desktopFoundationSnapshot(selection) }
    val startupRuntimeState =
        rememberDesktopStartupRuntimeState(
            stores = stores,
            desktopSettings = desktopSettings,
            bootstrapSnapshot = bootstrapSnapshot,
            securitySnapshot = securitySnapshot,
            databaseSnapshot = databaseSnapshot,
            sessionOpen = sessionOpen,
        )
    val startupSnapshot =
        rememberDesktopStartupSnapshot(stores = stores, desktopSettings = desktopSettings, startupRuntimeState = startupRuntimeState)
    val startupMode = remember(startupRuntimeState) { resolveDesktopStartupMode(startupRuntimeState) }
    val navigationModel = remember(desktopSettings) { desktopNavigationModel(desktopSettings) }
    val taskBoardSnapshot =
        rememberDesktopTaskBoardSnapshot(taskBoardPreferences = taskBoardPreferences, taskCatalogState = taskCatalogState)
    val desktopLifecycleState =
        rememberDesktopLifecycleState(stores = stores, desktopSettings = desktopSettings, bootstrapSnapshot = bootstrapSnapshot)
    val shellRenderInputs =
        DesktopShellRenderInputs(
            startupSnapshot = startupSnapshot,
            navigationModel = navigationModel,
            activeRoute = activeRoute,
            foundationSnapshot = foundationSnapshot,
            desktopSettings = desktopSettings,
            desktopLifecycleState = desktopLifecycleState,
            taskBoardSnapshot = taskBoardSnapshot,
            journalState = journalState,
            notesState = notesState,
        )
    val shellRenderState =
        rememberDesktopShellRenderState(
            stores = stores,
            inputs = shellRenderInputs,
        )
    return DesktopAppModels(
        selection = selection,
        desktopSettings = desktopSettings,
        startupMode = startupMode,
        startupRuntimeState = startupRuntimeState,
        startupSnapshot = startupSnapshot,
        databaseSnapshot = databaseSnapshot,
        navigationModel = navigationModel,
        desktopLifecycleState = desktopLifecycleState,
        shellRenderState = shellRenderState,
    )
}

@Composable
private fun rememberDesktopStartupRuntimeState(
    stores: DesktopPersistenceStores,
    desktopSettings: DesktopSettingsSnapshot,
    bootstrapSnapshot: DesktopBootstrapSnapshot,
    securitySnapshot: DesktopSecuritySnapshot,
    databaseSnapshot: DesktopDatabaseSnapshot,
    sessionOpen: Boolean,
): DesktopStartupRuntimeState =
    remember(
        desktopSettings,
        bootstrapSnapshot,
        securitySnapshot,
        databaseSnapshot,
        sessionOpen,
        stores.securityStore,
        stores.databaseStore,
    ) {
        buildDesktopStartupRuntimeState(
            settingsSnapshot = desktopSettings,
            bootstrapSnapshot = bootstrapSnapshot,
            securitySnapshot = securitySnapshot,
            databaseSnapshot = databaseSnapshot,
            sessionOpen = sessionOpen,
            nowEpochMillis = System.currentTimeMillis(),
        ).copy(
            securityFilePath = stores.securityStore.getSecurityFilePath().toString(),
            databaseFilePath = stores.databaseStore.getDatabaseFilePath().toString(),
        )
    }

@Composable
private fun rememberDesktopStartupSnapshot(
    stores: DesktopPersistenceStores,
    desktopSettings: DesktopSettingsSnapshot,
    startupRuntimeState: DesktopStartupRuntimeState,
): io.payanam.shared.startup.DesktopStartupSnapshot =
    remember(desktopSettings, stores.settingsStore, stores.bootstrapStore, stores.securityStore, startupRuntimeState) {
        desktopStartupSnapshot(
            settings = desktopSettings,
            settingsFilePath = stores.settingsStore.getSettingsFilePath().toString(),
            appDataRoot = DesktopAppPaths.resolveRootDirectory().toString(),
            bootstrapFilePath = stores.bootstrapStore.getBootstrapFilePath().toString(),
            securityFilePath = stores.securityStore.getSecurityFilePath().toString(),
            databaseFilePath = startupRuntimeState.databaseFilePath,
            runtimeState = startupRuntimeState,
        )
    }

@Composable
private fun rememberDesktopTaskBoardSnapshot(
    taskBoardPreferences: io.payanam.shared.tasks.DesktopTaskBoardPreferences,
    taskCatalogState: DesktopTaskCatalogState,
): DesktopTaskBoardSnapshot =
    remember(taskBoardPreferences, taskCatalogState) {
        DesktopTaskBoardContracts.boardSnapshotForCatalog(
            catalog = taskCatalogState.catalog,
            preferences = taskBoardPreferences,
            errorMessage = taskCatalogState.errorMessage,
        )
    }

@Composable
private fun rememberDesktopLifecycleState(
    stores: DesktopPersistenceStores,
    desktopSettings: DesktopSettingsSnapshot,
    bootstrapSnapshot: DesktopBootstrapSnapshot,
): DesktopLifecycleState =
    remember(desktopSettings, stores.settingsStore, bootstrapSnapshot, stores.bootstrapStore) {
        DesktopLifecycleState(
            desktopSettings = desktopSettings,
            settingsFilePath = stores.settingsStore.getSettingsFilePath().toString(),
            bootstrapSnapshot = bootstrapSnapshot,
            bootstrapFilePath = stores.bootstrapStore.getBootstrapFilePath().toString(),
            securityFilePath = stores.securityStore.getSecurityFilePath().toString(),
            databaseFilePath = stores.databaseStore.getDatabaseFilePath().toString(),
            exportDirectoryPath = DesktopAppPaths.resolveExportDirectory().toString(),
        )
    }

@Composable
private fun rememberDesktopShellRenderState(
    stores: DesktopPersistenceStores,
    inputs: DesktopShellRenderInputs,
): DesktopShellRenderState =
    remember(
        inputs,
        stores.settingsStore,
        stores.taskBoardStore,
        stores.taskCatalogStore,
        inputs.journalState,
        stores.journalStore,
        inputs.notesState,
        stores.noteStore,
    ) {
        DesktopShellRenderState(
            navigationModel = inputs.navigationModel,
            activeRoute = inputs.activeRoute,
            foundationSnapshot = inputs.foundationSnapshot,
            desktopSettings = inputs.desktopSettings,
            settingsFilePath = stores.settingsStore.getSettingsFilePath().toString(),
            desktopLifecycleState = inputs.desktopLifecycleState,
            taskBoardSnapshot = inputs.taskBoardSnapshot,
            taskBoardFilePath = stores.taskBoardStore.getBoardFilePath().toString(),
            taskCatalogFilePath = stores.taskCatalogStore.getCatalogFilePath().toString(),
            journalState = inputs.journalState,
            journalFilePath = stores.journalStore.getJournalFilePath().toString(),
            notesState = inputs.notesState,
            notesFilePath = stores.noteStore.getNotesFilePath().toString(),
        )
    }

internal fun buildDesktopShellCallbacks(
    stores: DesktopPersistenceStores,
    rememberedState: DesktopRememberedState,
    onRouteSelected: (DesktopTopLevelRoute) -> Unit,
): DesktopShellCallbacks =
    DesktopShellCallbacks(
        onRouteSelected = onRouteSelected,
        onSettingsChanged = { updated ->
            rememberedState.desktopSettingsState.value = updated
            stores.settingsStore.saveSnapshot(updated)
            rememberedState.bootstrapSnapshotState.value =
                stores.bootstrapStore.recordStartupCompleted(updated.launchRoute)
        },
        onSelectionChanged = { updatedSelection ->
            rememberedState.selectionState.value = updatedSelection
        },
        onTaskBoardChanged = { updatedSnapshot ->
            rememberedState.taskBoardSnapshotState.value = updatedSnapshot
            stores.taskBoardStore.saveSnapshot(updatedSnapshot)
        },
        onJournalDateSelected = { selectedDateIso ->
            rememberedState.journalState.value = selectDesktopJournalDate(stores, rememberedState, selectedDateIso)
        },
        onJournalOverallResponseChanged = { promptKey, response ->
            rememberedState.journalState.value = saveDesktopJournalOverallResponse(stores, rememberedState, promptKey, response)
        },
        onJournalDimensionResponseChanged = { dimensionId, promptKey, response ->
            rememberedState.journalState.value =
                saveDesktopJournalDimensionResponse(stores, rememberedState, dimensionId, promptKey, response)
        },
        onCreateNote = { title, details, dimensionId, dimensionLabel, tags ->
            rememberedState.notesState.value =
                stores.noteStore.createNote(
                    title = title,
                    details = details,
                    dimensionId = dimensionId,
                    dimensionLabel = dimensionLabel,
                    tags = tags,
                )
        },
        onUpdateNote = { noteId, title, details, dimensionId, dimensionLabel, tags ->
            rememberedState.notesState.value =
                stores.noteStore.updateNote(
                    noteId = noteId,
                    title = title,
                    details = details,
                    dimensionId = dimensionId,
                    dimensionLabel = dimensionLabel,
                    tags = tags,
                )
        },
        onDeleteNote = { noteId ->
            rememberedState.notesState.value = stores.noteStore.deleteNote(noteId)
        },
        onConfigurePassphrase = { passphrase ->
            val result = stores.securityStore.configurePassphrase(passphrase)
            rememberedState.securitySnapshotState.value = stores.securityStore.loadSnapshot()
            if (result is DesktopPassphraseActionResult.Success) {
                rememberedState.sessionOpenState.value = true
            }
            result
        },
        onUnlockPassphrase = { passphrase ->
            val result = stores.securityStore.verifyPassphrase(passphrase)
            rememberedState.securitySnapshotState.value = stores.securityStore.loadSnapshot()
            if (result is DesktopPassphraseActionResult.Success) {
                rememberedState.sessionOpenState.value = true
            }
            result
        },
        onForgotPassphraseReset = {
            rememberedState.databaseSnapshotState.value = stores.databaseStore.resetDatabaseArtifact()
            val resetSettings = DesktopSettingsContracts.defaultSnapshot()
            rememberedState.desktopSettingsState.value = resetSettings
            stores.settingsStore.saveSnapshot(resetSettings)
            stores.securityStore.resetSecurityState()
            stores.bootstrapStore.updateDatabaseLifecycleReady(false)
            rememberedState.sessionOpenState.value = false
            rememberedState.securitySnapshotState.value = stores.securityStore.loadSnapshot()
            rememberedState.bootstrapSnapshotState.value = stores.bootstrapStore.loadSnapshot()
        },
        onInitializeDatabase = {
            rememberedState.databaseSnapshotState.value = stores.databaseStore.ensureInitialized()
            rememberedState.bootstrapSnapshotState.value = stores.bootstrapStore.updateDatabaseLifecycleReady(true)
        },
        onCompleteFocusModeOnboarding = { preset ->
            val updatedSettings =
                rememberedState.desktopSettingsState.value.copy(
                    activePreset = preset,
                    focusModeOnboardingCompleted = true,
                    routeVisibility = DesktopSettingsContracts.routeVisibilityForPreset(preset),
                )
            rememberedState.desktopSettingsState.value = updatedSettings
            stores.settingsStore.saveSnapshot(updatedSettings)
            rememberedState.bootstrapSnapshotState.value =
                stores.bootstrapStore.recordStartupCompleted(updatedSettings.launchRoute)
        },
        onExportLocalState = { stores.dataHandoffStore.exportLocalState() },
        onImportLocalState = { importDesktopLocalState(stores, rememberedState) },
    )
