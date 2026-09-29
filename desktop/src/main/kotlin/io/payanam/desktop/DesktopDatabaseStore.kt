//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * DesktopDatabaseSnapshot.
 *
 * `initCompleted` is DERIVED, not stored. It used to be a marker in the in-memory scaffold
 * (`DesktopLocalStateMock.isInitialized()`), which is why the startup gate's database-lifecycle check
 * could never report ready: the store pointed at `<root>/database/desktop-local-state-mock.unused`, a
 * path that is deliberately never created, so `hasArtifacts` was permanently false and the whole
 * conjunction could never be true. A second flag that can disagree with the file is a bug waiting to
 * happen, so both facts now come from the real file the desktop actually opens.
 */
data class DesktopDatabaseSnapshot(
    val databaseFilePath: String,
    val hasArtifacts: Boolean,
    val initCompleted: Boolean,
    val databaseSizeKb: Long,
    val databaseLastModifiedMs: Long,
)

internal class DesktopDatabaseStore(
    /** The REAL database file, not a scaffold path - see [DesktopDatabaseSnapshot]. */
    databaseFilePath: Path = databaseFile(),
    private val logEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    private val databaseFilePath: Path = databaseFilePath

    /**
     * Current database facts: path, artifact presence, init state, size, and
     * last-modified time.
     *
     * `initCompleted` is [hasArtifacts] - the file existing IS the initialization having happened,
     * because the desktop cannot create one itself (its migration logic deliberately does not exist).
     * There is no separate marker to fall out of step with the file.
     */
    fun loadSnapshot(): DesktopDatabaseSnapshot {
        val exists = Files.exists(databaseFilePath)
        return DesktopDatabaseSnapshot(
            databaseFilePath = databaseFilePath.toString(),
            hasArtifacts = exists,
            initCompleted = exists,
            databaseSizeKb = if (exists) Files.size(databaseFilePath) / 1024L else 0L,
            databaseLastModifiedMs = if (exists) Files.getLastModifiedTime(databaseFilePath).toMillis() else 0L,
        )
    }

    /**
     * Returns fresh state. Idempotent and side-effect free by design: the previous version called
     * `markInitialized()` on the in-memory scaffold, and the flag it wrote is derived now.
     */
    fun ensureInitialized(): DesktopDatabaseSnapshot {
        logEvent(
            "DesktopDatabaseStore.ensureInitialized",
            "Read desktop database facts",
            emptyMap(),
        )
        return loadSnapshot()
    }

    /**
     * Returns fresh state. NOT destructive: the previous version called `clearStateEntries()` and
     * `clearInitializedMarker()`, which erased the scaffold's in-memory entries - but the live database
     * is the user's data and this method has no business touching it. Deleting a database is the
     * passphrase-reset path's job, and that path says so explicitly.
     */
    fun resetDatabaseArtifact(): DesktopDatabaseSnapshot {
        logEvent(
            "DesktopDatabaseStore.resetDatabaseArtifact",
            "Re-read desktop database facts without touching the file",
            emptyMap(),
        )
        return loadSnapshot()
    }

    /**
     * Path of the SQLite database file.
     */
    fun getDatabaseFilePath(): Path = databaseFilePath

    private companion object {
        /**
         * The real file, joined the same way [io.payanam.desktop.database.DesktopDatabasePathResolver]
         * joins it so the desktop has ONE answer to "where does the database live". The directory is
         * resolved on every call rather than cached in a default argument, because a default argument is
         * evaluated once per construction and a test that redirects the data root would then be reading
         * a path captured before the redirect.
         */
        fun databaseFile(): Path = Paths.get(DesktopAppPaths.resolveDatabaseDirectory().toString(), DesktopAppPaths.DATABASE_FILE_NAME)
    }
}
