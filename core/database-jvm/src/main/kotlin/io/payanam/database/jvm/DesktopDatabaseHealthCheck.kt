//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import io.payanam.shared.database.DatabaseHealthCheck
import io.payanam.shared.database.DatabaseHealthReport
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.logging.AppLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/**
 * The desktop's answer to [DatabaseHealthCheck], over JDBC.
 *
 * Same rules as Android's: the shared [PayanamDatabaseSchema.CRITICAL_TABLES] list is diffed against what
 * the file actually has, and the schema version is read and classified with the same shared thresholds. Only
 * the HANDLE differs — a JDBC `Connection` where Android has a Room `SQLiteDatabase`.
 *
 * The desktop does NOT validate per-column or per-index. Android's checker does, and the difference is
 * honest rather than approximate: the table-level rule is the one that catches a truncated or foreign file,
 * which is the case the init screen has to get right. A desktop reporting a column-level detail it did not
 * measure is exactly the invented answer this migration is removing.
 */
class DesktopDatabaseHealthCheck : DatabaseHealthCheck {
    private val logger = AppLogger.current

    override suspend fun check(
        databaseFile: File,
        passphrase: String?,
    ): DatabaseHealthReport {
        if (!Files.isRegularFile(databaseFile.toPath())) {
            return DatabaseHealthReport(
                isHealthy = false,
                errorMessage = "No database file at " + databaseFile.name,
            )
        }
        if (passphrase.isNullOrBlank()) {
            // No passphrase means no way to open an encrypted file, so the check cannot conclude. Saying
            // "healthy" here would be a claim nobody measured.
            return DatabaseHealthReport(
                isHealthy = false,
                errorMessage = "No passphrase, so the database cannot be opened to check it",
            )
        }
        val properties = Properties().apply { setProperty(PayanamDatabaseOpener.KEY_PROPERTY, passphrase) }
        return runCatching {
            DriverManager
                .getConnection(PayanamDatabaseOpener.URL_PREFIX + databaseFile.toPath().toAbsolutePath(), properties)
                .use { connection ->
                    // The schema VERSION is deliberately not re-checked here: PayanamDatabaseOpener already
                    // refuses an out-of-range version at open, so a file that got this far has passed it. This
                    // check answers the other question the init screen asks - are the critical tables present -
                    // and the two together are the whole gate.
                    val missing =
                        PayanamDatabaseSchema.CRITICAL_TABLES.filterNot { table ->
                            tableExists(connection, table)
                        }
                    if (missing.isNotEmpty()) {
                        logger.w(
                            "DesktopDatabaseHealthCheck.check",
                            "Critical tables missing from the database",
                            mapOf("missing" to missing.joinToString(",")),
                        )
                        return@use DatabaseHealthReport(
                            isHealthy = false,
                            needsRepair = true,
                            errorMessage = "Missing tables: ${missing.joinToString(", ")}",
                        )
                    }
                    // No detected version: this check answers the TABLE question, and open() already
                    // refused an out-of-range version before any file got here. null rather than 0, because 0
                    // is a real (too old) version and would be a claim nobody measured.
                    DatabaseHealthReport(isHealthy = true, detectedVersion = null)
                }
        }.getOrElse { error ->
            // A wrong passphrase and a corrupt file both land here, and the desktop CANNOT tell them apart
            // any better than Android can — an open attempt answers one question. It reports that honestly
            // rather than inventing a distinction it did not measure.
            logger.w(
                "DesktopDatabaseHealthCheck.check",
                "Database could not be opened for a health check",
                mapOf("failure" to error.javaClass.simpleName),
            )
            DatabaseHealthReport(
                isHealthy = false,
                errorMessage = "Database could not be opened (${error.javaClass.simpleName})",
            )
        }
    }

    override fun hasArtifacts(databaseFile: File): Boolean = Files.isRegularFile(databaseFile.toPath())

    private fun tableExists(
        connection: java.sql.Connection,
        table: String,
    ): Boolean =
        connection.createStatement().use { statement ->
            statement
                .executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'")
                .use { rows -> rows.next() }
        }
}
