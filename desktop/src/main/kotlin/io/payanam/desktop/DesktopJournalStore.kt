//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.journal.JournalReflectionContracts
import io.payanam.shared.journal.JournalSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * DesktopJournalState.

 */
data class DesktopJournalState(
    /** Snapshot. */
    val snapshot: JournalSnapshot,
    /** Selected date iso. */
    val selectedDateIso: String,
    /** Last saved date iso. */
    val lastSavedDateIso: String? = null,
    /** Error message. */
    val errorMessage: String? = null,
)

internal class DesktopJournalStore(
    databaseDirectory: Path = DesktopAppPaths.resolveDatabaseDirectory(),
    private val persistenceDatabase: DesktopPersistenceDatabase =
        DesktopPersistenceDatabase(databaseDirectory = databaseDirectory),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        },
    private val today: () -> LocalDate = { LocalDate.now() },
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val logEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    /**
     * Load state.
     */
    fun loadState(): DesktopJournalState {
        val selectedDateIso = today().toString()
        val storedPayload = persistenceDatabase.readEntry(STATE_ENTRY_KEY)
        if (storedPayload.isNullOrBlank()) {
            saveSnapshot(JournalReflectionContracts.emptySnapshot())
            logEvent(
                "DesktopJournalStore.loadState",
                "Seeded desktop journal snapshot",
                emptyMap(),
            )
            return DesktopJournalState(snapshot = JournalReflectionContracts.emptySnapshot(), selectedDateIso = selectedDateIso)
        }

        return try {
            val snapshot = json.decodeFromString<JournalSnapshot>(storedPayload)
            logEvent(
                "DesktopJournalStore.loadState",
                "Loaded desktop journal snapshot",
                mapOf("dayCount" to snapshot.days.size),
            )
            DesktopJournalState(snapshot = snapshot, selectedDateIso = selectedDateIso)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            logEvent(
                "DesktopJournalStore.loadState",
                "Failed to decode desktop journal snapshot",
                mapOf("error" to error.message),
            )
            DesktopJournalState(
                snapshot = JournalReflectionContracts.emptySnapshot(),
                selectedDateIso = selectedDateIso,
                errorMessage = error.message ?: "Desktop journal could not be read.",
            )
        }
    }

    /**
     * Select date.
     */
    fun selectDate(
        currentState: DesktopJournalState,
        requestedDateIso: String,
    ): DesktopJournalState {
        val todayDate = today()
        val boundedDate =
            runCatching { LocalDate.parse(requestedDateIso) }
                .getOrElse { todayDate }
                .let { requestedDate -> if (requestedDate.isAfter(todayDate)) todayDate else requestedDate }
        return currentState.copy(selectedDateIso = boundedDate.toString(), errorMessage = null)
    }

    /**
     * Save overall response.
     */
    fun saveOverallResponse(
        currentState: DesktopJournalState,
        promptKey: String,
        response: String,
    ): DesktopJournalState {
        val selectedDateIso = currentState.selectedDateIso
        val nextSnapshot =
            JournalReflectionContracts.upsertOverallResponse(
                snapshot = currentState.snapshot,
                dateIso = selectedDateIso,
                promptKey = promptKey,
                response = response,
                now = now(),
            )
        saveSnapshot(nextSnapshot)
        logEvent(
            "DesktopJournalStore.saveOverallResponse",
            "Saved desktop journal overall response",
            mapOf("selectedDateIso" to selectedDateIso, "promptKey" to promptKey, "responseLength" to response.length),
        )
        return currentState.copy(snapshot = nextSnapshot, lastSavedDateIso = selectedDateIso, errorMessage = null)
    }

    /**
     * Save dimension response.
     */
    fun saveDimensionResponse(
        currentState: DesktopJournalState,
        dimensionId: String,
        promptKey: String,
        response: String,
    ): DesktopJournalState {
        val selectedDateIso = currentState.selectedDateIso
        val nextSnapshot =
            JournalReflectionContracts.upsertDimensionResponse(
                snapshot = currentState.snapshot,
                dateIso = selectedDateIso,
                dimensionId = dimensionId,
                promptKey = promptKey,
                response = response,
                now = now(),
            )
        saveSnapshot(nextSnapshot)
        logEvent(
            "DesktopJournalStore.saveDimensionResponse",
            "Saved desktop journal dimension response",
            mapOf(
                "selectedDateIso" to selectedDateIso,
                "dimensionId" to dimensionId,
                "promptKey" to promptKey,
                "responseLength" to response.length,
            ),
        )
        return currentState.copy(snapshot = nextSnapshot, lastSavedDateIso = selectedDateIso, errorMessage = null)
    }

    /**
     * Save snapshot.
     */
    fun saveSnapshot(snapshot: JournalSnapshot) {
        persistenceDatabase.writeEntry(STATE_ENTRY_KEY, json.encodeToString(snapshot))
    }

    /**
     * Get journal file path.
     */
    fun getJournalFilePath(): Path = persistenceDatabase.getDatabaseFilePath()

    internal companion object {
        internal const val STATE_ENTRY_KEY = "desktop/journal"
    }
}
