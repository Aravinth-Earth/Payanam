//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.notes.DesktopNoteContracts
import io.payanam.shared.notes.DesktopNoteRecord
import io.payanam.shared.notes.DesktopNotesSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.UUID

/**
 * DesktopNotesState.

 */
data class DesktopNotesState(
    val snapshot: DesktopNotesSnapshot,
    val errorMessage: String? = null,
)

internal class DesktopNoteStore(
    databaseDirectory: Path = DesktopAppPaths.resolveDatabaseDirectory(),
    private val persistenceDatabase: DesktopPersistenceDatabase =
        DesktopPersistenceDatabase(databaseDirectory = databaseDirectory),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        },
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val nextId: () -> String = { UUID.randomUUID().toString() },
    private val logEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    /**
     * Loads the load state.
     */
    fun loadState(): DesktopNotesState {
        val storedPayload = persistenceDatabase.readEntry(STATE_ENTRY_KEY)
        if (storedPayload.isNullOrBlank()) {
            saveSnapshot(DesktopNoteContracts.emptySnapshot())
            logEvent(
                "DesktopNoteStore.loadState",
                "Seeded desktop notes snapshot",
                emptyMap(),
            )
            return DesktopNotesState(snapshot = DesktopNoteContracts.emptySnapshot())
        }

        return try {
            val snapshot = json.decodeFromString<DesktopNotesSnapshot>(storedPayload)
            logEvent(
                "DesktopNoteStore.loadState",
                "Loaded desktop notes snapshot",
                mapOf("noteCount" to snapshot.notes.size),
            )
            DesktopNotesState(snapshot = snapshot)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            logEvent(
                "DesktopNoteStore.loadState",
                "Failed to decode desktop notes snapshot",
                mapOf("error" to error.message),
            )
            DesktopNotesState(
                snapshot = DesktopNoteContracts.emptySnapshot(),
                errorMessage = error.message ?: "Desktop notes could not be read.",
            )
        }
    }
    /**
     * Creates the create note.
     */
    fun createNote(
        title: String,
        details: String?,
        dimensionId: String?,
        dimensionLabel: String?,
        tags: List<String>,
    ): DesktopNotesState {
        val currentState = loadState()
        val nextRecord =
            DesktopNoteContracts.createRecord(
                id = nextId(),
                title = title,
                details = details,
                dimensionId = dimensionId,
                dimensionLabel = dimensionLabel,
                tags = tags,
                now = now(),
            )
        val nextSnapshot =
            currentState.snapshot.copy(
                notes = (currentState.snapshot.notes + nextRecord).sortedByDescending(DesktopNoteRecord::updatedAtIso),
            )
        saveSnapshot(nextSnapshot)
        logEvent(
            "DesktopNoteStore.createNote",
            "Created desktop note",
            mapOf("noteId" to nextRecord.id, "noteCount" to nextSnapshot.notes.size),
        )
        return DesktopNotesState(snapshot = nextSnapshot)
    }
    /**
     * Updates the update note.
     */
    fun updateNote(
        noteId: String,
        title: String,
        details: String?,
        dimensionId: String?,
        dimensionLabel: String?,
        tags: List<String>,
    ): DesktopNotesState {
        val currentState = loadState()
        val existing =
            currentState.snapshot.notes.firstOrNull { it.id == noteId }
                ?: return currentState.copy(errorMessage = "Desktop note $noteId could not be found.")
        val updatedRecord =
            DesktopNoteContracts.updateRecord(
                existing = existing,
                title = title,
                details = details,
                dimensionId = dimensionId,
                dimensionLabel = dimensionLabel,
                tags = tags,
                now = now(),
            )
        val nextSnapshot =
            currentState.snapshot.copy(
                notes =
                    currentState.snapshot.notes
                        .map { record -> if (record.id == noteId) updatedRecord else record }
                        .sortedByDescending(DesktopNoteRecord::updatedAtIso),
            )
        saveSnapshot(nextSnapshot)
        logEvent(
            "DesktopNoteStore.updateNote",
            "Updated desktop note",
            mapOf("noteId" to noteId, "noteCount" to nextSnapshot.notes.size),
        )
        return DesktopNotesState(snapshot = nextSnapshot)
    }
    /**
     * Removes the delete note.
     */
    fun deleteNote(noteId: String): DesktopNotesState {
        val currentState = loadState()
        val nextSnapshot =
            currentState.snapshot.copy(
                notes = currentState.snapshot.notes.filterNot { it.id == noteId },
            )
        saveSnapshot(nextSnapshot)
        logEvent(
            "DesktopNoteStore.deleteNote",
            "Deleted desktop note",
            mapOf("noteId" to noteId, "noteCount" to nextSnapshot.notes.size),
        )
        return DesktopNotesState(snapshot = nextSnapshot)
    }
    /**
     * Writes the save snapshot.
     */
    fun saveSnapshot(snapshot: DesktopNotesSnapshot) {
        persistenceDatabase.writeEntry(STATE_ENTRY_KEY, json.encodeToString(snapshot))
    }
    /**
     * Returns the get notes file path.
     */
    fun getNotesFilePath(): Path = persistenceDatabase.getDatabaseFilePath()

    internal companion object {
        internal const val STATE_ENTRY_KEY = "desktop/notes"
    }
}
