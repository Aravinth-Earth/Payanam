//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.UnifiedLogger
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Row counts of a few core tables, read from a database file that may be encrypted or not.
 *
 * The JVM counterpart of `DatabaseEncryptionMigrationSupport.readTableCounts` in `:core:database`. That
 * version took a `Context` it never used on this path — the only `context` reference in the whole file is
 * `context.cacheDir`, used by the plaintext→SQLCipher MIGRATION, not by counting — so the count logic was
 * never actually platform-bound. This is the same logic over the JVM SQLCipher driver: try the passphrase,
 * fall back to a plaintext open, and report zeros for anything unreadable rather than throwing.
 *
 * A separate object rather than a method on an existing repository because it opens its OWN short-lived
 * read-only connection to a file it does not own the session for — the integrity check that runs mid-import
 * must see the file on disk, not the live session, or it would be comparing a file against itself.
 */
object DatabaseTableCountReader {
    // Read on each use rather than captured at class load: the logger is a process-wide singleton whose
    // order of installation is not a contract, so a captured reference would pin whichever logger happened
    // to be current when this object first loaded - and a test that installs a recorder would then assert
    // against a different logger than the one the reader writes to.
    private val logger: UnifiedLogger get() = AppLogger.current

    /**
     * Returns each requested table's row count, with 0 for any table that cannot be read.
     *
     * A wrong-but-plausible count is the dangerous failure here: the import integrity check compares these
     * before and after a conversion, so a silent 0 would read as "the conversion destroyed your data". The
     * fallback is therefore visible — every path that yields zeros logs why.
     *
     * @param databaseFile the file to read; must not be a sidecar (`-wal`/`-shm`).
     * @param passphrase the file's passphrase, or null/blank when the file is plaintext.
     */
    fun readTableCounts(
        databaseFile: Path,
        passphrase: String?,
        tableNames: List<String>,
    ): Map<String, Int> {
        if (!Files.isRegularFile(databaseFile)) {
            logger.w(
                "DatabaseTableCountReader.readTableCounts",
                "No database file to count; reporting zeros",
                mapOf("file" to databaseFile.fileName?.toString()),
            )
            return tableNames.associateWith { 0 }
        }
        if (!passphrase.isNullOrBlank()) {
            // Null rather than a thrown failure: a wrong passphrase is an ordinary user outcome, and the
            // caller needs an answer for every table rather than an exception to translate.
            val encrypted = runCatching { openReadOnly(databaseFile, passphrase) }.getOrNull()
            if (encrypted != null) {
                return encrypted.use { connection -> countAll(connection, tableNames) }
            }
        }
        val plaintext =
            runCatching {
                openReadOnly(databaseFile, passphrase = null)
            }.getOrNull()
        if (plaintext != null) {
            return plaintext.use { connection -> countAll(connection, tableNames) }
        }
        logger.w(
            "DatabaseTableCountReader.readTableCounts",
            "Database could not be opened with the given passphrase or as plaintext; reporting zeros",
            mapOf("file" to databaseFile.fileName?.toString(), "hasPassphrase" to (passphrase != null)),
        )
        return tableNames.associateWith { 0 }
    }

    /** Opens the file read-only through the SQLCipher driver, which reads a plaintext file as itself. */
    private fun openReadOnly(
        databaseFile: Path,
        passphrase: String?,
    ): Connection {
        val properties = Properties()
        if (passphrase != null) properties.setProperty("key", passphrase)
        val connection =
            DriverManager.getConnection(
                "jdbc:sqlcipher:${databaseFile.toAbsolutePath()}",
                properties,
            )
        // Read-only is set via PRAGMA and read back, never via `?mode=ro`: the driver silently IGNORES that
        // URL parameter (it connects, query_only stays 0, and a write SUCCEEDS), so a "read-only" open that
        // trusted the URL would be a false guarantee on the file a conversion is about to rewrite.
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA query_only = ON")
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA query_only").use { rs ->
                check(rs.next() && rs.getInt(1) == 1) { "query_only did not take effect" }
            }
        }
        return connection
    }

    private fun countAll(
        connection: Connection,
        tableNames: List<String>,
    ): Map<String, Int> =
        tableNames.associateWith { tableName ->
            // `tableName` comes from the caller's own constant list, never user input, so it is safe to
            // interpolate; a parameter marker is not valid for an identifier in SQLite.
            runCatching {
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM $tableName").use { rs ->
                        if (rs.next()) rs.getInt(1) else 0
                    }
                }
            }.getOrElse { error ->
                logger.w(
                    "DatabaseTableCountReader.countAll",
                    "Could not count a table; reporting zero for it",
                    mapOf("table" to tableName, "error" to (error.message ?: "Unknown error")),
                )
                0
            }
        }
}
