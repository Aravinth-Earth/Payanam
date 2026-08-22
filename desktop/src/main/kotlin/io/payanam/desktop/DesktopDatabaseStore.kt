//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * DesktopDatabaseSnapshot.

 */
data class DesktopDatabaseSnapshot(
    val databaseFilePath: String,
    val hasArtifacts: Boolean,
    val initCompleted: Boolean,
    val databaseSizeKb: Long,
    val databaseLastModifiedMs: Long,
)

internal class DesktopDatabaseStore(
    databaseDirectory: Path = DesktopAppPaths.resolveDatabaseDirectory(),
    private val persistenceDatabase: DesktopPersistenceDatabase =
        DesktopPersistenceDatabase(databaseDirectory = databaseDirectory),
    private val logEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    private val databaseFilePath: Path = persistenceDatabase.getDatabaseFilePath()
    /**
     * Current database facts: path, artifact presence, init state, size, and
     * last-modified time.
     */
    fun loadSnapshot(): DesktopDatabaseSnapshot =
        DesktopDatabaseSnapshot(
            databaseFilePath = databaseFilePath.toString(),
            hasArtifacts = Files.exists(databaseFilePath),
            initCompleted = persistenceDatabase.isInitialized(),
            databaseSizeKb = databaseFileSizeKb(),
            databaseLastModifiedMs = databaseLastModifiedEpochMillis(),
        )
    /**
     * Marks the database initialized (idempotent) and returns fresh state.
     */
    fun ensureInitialized(): DesktopDatabaseSnapshot {
        persistenceDatabase.markInitialized()
        logEvent(
            "DesktopDatabaseStore.ensureInitialized",
            "Marked desktop database as initialized",
            emptyMap(),
        )
        return loadSnapshot()
    }
    /**
     * Wipes all state entries and the init marker (destructive reset).
     */
    fun resetDatabaseArtifact(): DesktopDatabaseSnapshot {
        persistenceDatabase.clearStateEntries()
        persistenceDatabase.clearInitializedMarker()
        logEvent(
            "DesktopDatabaseStore.resetDatabaseArtifact",
            "Cleared desktop database state entries",
            emptyMap(),
        )
        return loadSnapshot()
    }
    /**
     * Path of the SQLite database file.
     */
    fun getDatabaseFilePath(): Path = databaseFilePath

    @Suppress("MagicNumber")
    private fun databaseFileSizeKb(): Long =
        if (Files.exists(databaseFilePath)) {
            Files.size(databaseFilePath) / 1024L
        } else {
            0L
        }

    private fun databaseLastModifiedEpochMillis(): Long =
        if (Files.exists(databaseFilePath)) {
            Files.getLastModifiedTime(databaseFilePath).toMillis()
        } else {
            0L
        }
}
