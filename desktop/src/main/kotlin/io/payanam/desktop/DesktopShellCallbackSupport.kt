//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

internal fun selectDesktopJournalDate(
    stores: DesktopPersistenceStores,
    rememberedState: DesktopRememberedState,
    selectedDateIso: String,
): DesktopJournalState =
    stores.journalStore.selectDate(
        currentState = rememberedState.journalState.value,
        requestedDateIso = selectedDateIso,
    )

internal fun saveDesktopJournalOverallResponse(
    stores: DesktopPersistenceStores,
    rememberedState: DesktopRememberedState,
    promptKey: String,
    response: String,
): DesktopJournalState =
    stores.journalStore.saveOverallResponse(
        currentState = rememberedState.journalState.value,
        promptKey = promptKey,
        response = response,
    )

internal fun saveDesktopJournalDimensionResponse(
    stores: DesktopPersistenceStores,
    rememberedState: DesktopRememberedState,
    dimensionId: String,
    promptKey: String,
    response: String,
): DesktopJournalState =
    stores.journalStore.saveDimensionResponse(
        currentState = rememberedState.journalState.value,
        dimensionId = dimensionId,
        promptKey = promptKey,
        response = response,
    )

internal fun importDesktopLocalState(
    stores: DesktopPersistenceStores,
    rememberedState: DesktopRememberedState,
): DesktopDataHandoffSnapshot {
    val handoffSnapshot = stores.dataHandoffStore.importLatestExport()
    stores.persistenceDatabase.importLegacyFilesIntoDatabase()
    rememberedState.desktopSettingsState.value = stores.settingsStore.loadSnapshot()
    rememberedState.securitySnapshotState.value = stores.securityStore.loadSnapshot()
    rememberedState.bootstrapSnapshotState.value = stores.bootstrapStore.loadSnapshot()
    rememberedState.databaseSnapshotState.value = stores.databaseStore.loadSnapshot()
    rememberedState.taskBoardSnapshotState.value = stores.taskBoardStore.loadSnapshot()
    rememberedState.taskCatalogState.value = stores.taskCatalogStore.loadState()
    rememberedState.journalState.value = stores.journalStore.loadState()
    rememberedState.notesState.value = stores.noteStore.loadState()
    rememberedState.sessionOpenState.value = false
    return handoffSnapshot
}
