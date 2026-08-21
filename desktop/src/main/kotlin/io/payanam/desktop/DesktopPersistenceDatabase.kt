//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

internal class DesktopPersistenceDatabase(
    private val databaseDirectory: Path = DesktopAppPaths.resolveDatabaseDirectory(),
    private val preferencesDirectory: Path = DesktopAppPaths.resolvePreferencesDirectory(),
    private val bootstrapDirectory: Path = DesktopAppPaths.resolveBootstrapDirectory(),
    private val securityDirectory: Path = DesktopAppPaths.resolveSecurityDirectory(),
    private val logEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    private val databaseFilePath: Path = databaseDirectory.resolve(DATABASE_FILE_NAME)
    private val connectionUrl = "jdbc:sqlite:${databaseFilePath.toAbsolutePath()}"
    private val legacyDatabaseMarkerDetected: Boolean

    init {
        Files.createDirectories(databaseDirectory)
        legacyDatabaseMarkerDetected = prepareDatabaseFileForSqlite()
        initializeDatabase()
        migrateLegacyFilesIfNeeded()
    }

    /**
     * Get database file path.
     */
    fun getDatabaseFilePath(): Path = databaseFilePath

    @Synchronized
    /**
     * Read entry.
     */
    fun readEntry(entryKey: String): String? =
        withConnection { connection ->
            connection
                .prepareStatement("SELECT payload FROM desktop_state_entries WHERE entry_key = ?")
                .use { statement ->
                    statement.setString(1, entryKey)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) {
                            resultSet.getString("payload")
                        } else {
                            null
                        }
                    }
                }
        }

    @Synchronized
    /**
     * Write entry.
     */
    @Suppress("MagicNumber")
    fun writeEntry(
        entryKey: String,
        payload: String,
    ) {
        withConnection { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO desktop_state_entries(entry_key, payload, updated_at_epoch_millis)
                    VALUES (?, ?, ?)
                    ON CONFLICT(entry_key) DO UPDATE SET
                        payload = excluded.payload,
                        updated_at_epoch_millis = excluded.updated_at_epoch_millis
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, entryKey)
                    statement.setString(2, payload)
                    statement.setLong(3, System.currentTimeMillis())
                    statement.executeUpdate()
                }
        }
    }

    @Synchronized
    /**
     * Delete entry.
     */
    fun deleteEntry(entryKey: String) {
        withConnection { connection ->
            connection
                .prepareStatement("DELETE FROM desktop_state_entries WHERE entry_key = ?")
                .use { statement ->
                    statement.setString(1, entryKey)
                    statement.executeUpdate()
                }
        }
    }

    @Synchronized
    /**
     * Clear state entries.
     */
    fun clearStateEntries() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM desktop_state_entries")
            }
        }
    }

    @Synchronized
    /**
     * Has entry.
     */
    fun hasEntry(entryKey: String): Boolean = readEntry(entryKey) != null

    @Synchronized
    /**
     * Mark initialized.
     */
    fun markInitialized() {
        writeEntry(INITIALIZED_ENTRY_KEY, System.currentTimeMillis().toString())
    }

    @Synchronized
    /**
     * Clear initialized marker.
     */
    fun clearInitializedMarker() {
        deleteEntry(INITIALIZED_ENTRY_KEY)
    }

    @Synchronized
    /**
     * Is initialized.
     */
    fun isInitialized(): Boolean = hasEntry(INITIALIZED_ENTRY_KEY)

    @Synchronized
    /**
     * Database entry count.
     */
    fun databaseEntryCount(): Int =
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) AS count FROM desktop_state_entries").use { resultSet ->
                    resultSet.next()
                    resultSet.getInt("count")
                }
            }
        }

    @Synchronized
    /**
     * Import legacy files into database.
     */
    fun importLegacyFilesIntoDatabase() {
        migrateLegacyFilesIfNeeded(force = true)
    }

    private fun initializeDatabase() {
        withConnection { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA synchronous = NORMAL")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS desktop_state_entries(
                        entry_key TEXT PRIMARY KEY NOT NULL,
                        payload TEXT NOT NULL,
                        updated_at_epoch_millis INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    private fun prepareDatabaseFileForSqlite(): Boolean {
        if (!Files.exists(databaseFilePath)) {
            return false
        }
        if (isSqliteDatabase(databaseFilePath)) {
            return false
        }
        Files.deleteIfExists(databaseFilePath)
        logEvent(
            "DesktopPersistenceDatabase.prepareDatabaseFileForSqlite",
            "Removed legacy desktop database marker before sqlite initialization",
            mapOf("databaseFilePath" to databaseFilePath.toString()),
        )
        return true
    }

    private fun isSqliteDatabase(path: Path): Boolean {
        if (!Files.exists(path) || Files.size(path) < SQLITE_HEADER_LENGTH.toLong()) {
            return false
        }
        val headerBytes = ByteArray(SQLITE_HEADER_LENGTH)
        Files.newInputStream(path).use { input ->
            val bytesRead = input.read(headerBytes)
            if (bytesRead < SQLITE_HEADER_LENGTH) {
                return false
            }
        }
        return String(headerBytes, StandardCharsets.US_ASCII) == SQLITE_HEADER
    }

    private fun migrateLegacyFilesIfNeeded(force: Boolean = false) {
        if (!force && hasEntry(LEGACY_MIGRATION_ENTRY_KEY)) {
            return
        }

        val migratedEntries =
            legacyEntryLocations().mapNotNull { location ->
                val payload =
                    if (Files.exists(location.path)) {
                        Files.readString(location.path, StandardCharsets.UTF_8)
                    } else {
                        null
                    }
                if (payload.isNullOrEmpty()) {
                    null
                } else {
                    writeEntry(location.entryKey, payload)
                    Files.deleteIfExists(location.path)
                    location.entryKey
                }
            }

        val hadLegacyState = migratedEntries.isNotEmpty() || legacyDatabaseMarkerDetected
        if (hadLegacyState) {
            markInitialized()
        }
        cleanupLegacyDirectories()
        writeEntry(
            LEGACY_MIGRATION_ENTRY_KEY,
            buildString {
                append("force=")
                append(force)
                append(";migratedEntries=")
                append(migratedEntries.joinToString(","))
            },
        )
        logEvent(
            "DesktopPersistenceDatabase.migrateLegacyFilesIfNeeded",
            if (hadLegacyState) "Migrated legacy desktop file state into sqlite storage" else "No legacy desktop file state found",
            mapOf(
                "migratedEntries" to migratedEntries,
                "legacyDatabaseMarkerDetected" to legacyDatabaseMarkerDetected,
                "forced" to force,
            ),
        )
    }

    private fun cleanupLegacyDirectories() {
        listOf(preferencesDirectory, bootstrapDirectory, securityDirectory)
            .forEach { directory ->
                if (Files.exists(directory)) {
                    Files.list(directory).use { children ->
                        if (!children.findAny().isPresent) {
                            Files.deleteIfExists(directory)
                        }
                    }
                }
            }
    }

    private fun <T> withConnection(block: (Connection) -> T): T {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection(connectionUrl).use(block)
    }

    private data class LegacyEntryLocation(
        val entryKey: String,
        val path: Path,
    )

    private fun legacyEntryLocations(): List<LegacyEntryLocation> =
        listOf(
            LegacyEntryLocation(
                entryKey = DesktopSettingsStore.STATE_ENTRY_KEY,
                path = preferencesDirectory.resolve("desktop-settings.properties"),
            ),
            LegacyEntryLocation(
                entryKey = DesktopBootstrapStore.STATE_ENTRY_KEY,
                path = bootstrapDirectory.resolve("desktop-bootstrap.properties"),
            ),
            LegacyEntryLocation(
                entryKey = DesktopSecurityStore.STATE_ENTRY_KEY,
                path = securityDirectory.resolve("desktop-security.properties"),
            ),
            LegacyEntryLocation(
                entryKey = DesktopTaskBoardStore.STATE_ENTRY_KEY,
                path = preferencesDirectory.resolve("desktop-task-board.properties"),
            ),
            LegacyEntryLocation(
                entryKey = DesktopTaskCatalogStore.STATE_ENTRY_KEY,
                path = databaseDirectory.resolve("desktop-task-catalog.json"),
            ),
            LegacyEntryLocation(
                entryKey = DesktopJournalStore.STATE_ENTRY_KEY,
                path = databaseDirectory.resolve("desktop-journal.json"),
            ),
            LegacyEntryLocation(
                entryKey = DesktopNoteStore.STATE_ENTRY_KEY,
                path = databaseDirectory.resolve("desktop-notes.json"),
            ),
        )

    private companion object {
        private const val DATABASE_FILE_NAME = "payanam-desktop.db"
        private const val INITIALIZED_ENTRY_KEY = "desktop/database_initialized"
        private const val LEGACY_MIGRATION_ENTRY_KEY = "desktop/legacy_file_migration_v1"
        private const val SQLITE_HEADER = "SQLite format 3\u0000"
        private const val SQLITE_HEADER_LENGTH = 16
    }
}
