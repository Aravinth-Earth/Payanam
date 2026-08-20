//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.nio.file.Files
import java.nio.file.Path

/**
 * DesktopDatabaseSnapshot.

 */
data class DesktopDatabaseSnapshot(
    /** Database file path. */
    val databaseFilePath: String,
    /** Has artifacts. */
    val hasArtifacts: Boolean,
    /** Init completed. */
    val initCompleted: Boolean,
    /** Database size kb. */
    val databaseSizeKb: Long,
    /** Database last modified ms. */
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
     * Load snapshot.
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
     * Ensure initialized.
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
     * Reset database artifact.
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
     * Get database file path.
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
