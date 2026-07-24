//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.nio.file.Files
import java.nio.file.Path

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

    fun loadSnapshot(): DesktopDatabaseSnapshot =
        DesktopDatabaseSnapshot(
            databaseFilePath = databaseFilePath.toString(),
            hasArtifacts = Files.exists(databaseFilePath),
            initCompleted = persistenceDatabase.isInitialized(),
            databaseSizeKb = databaseFileSizeKb(),
            databaseLastModifiedMs = databaseLastModifiedEpochMillis(),
        )

    fun ensureInitialized(): DesktopDatabaseSnapshot {
        persistenceDatabase.markInitialized()
        logEvent(
            "DesktopDatabaseStore.ensureInitialized",
            "Marked desktop database as initialized",
            emptyMap(),
        )
        return loadSnapshot()
    }

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

    fun getDatabaseFilePath(): Path = databaseFilePath

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
