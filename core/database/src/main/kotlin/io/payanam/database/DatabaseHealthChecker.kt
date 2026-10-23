//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length", "MagicNumber")


package io.payanam.database

import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
import java.io.File
import net.zetetic.database.sqlcipher.SQLiteDatabase as SqlCipherDatabase
object DatabaseHealthChecker {
    private val logger = UnifiedLogger.getInstance()

    // Current expected database version - derived from PayanamDatabase
    const val CURRENT_VERSION = PAYANAM_DATABASE_SCHEMA_VERSION

    // Build #1081 was the first beta build shipped to users, and it used schema 16. Anything older is
    // outside the supported in-place Room migration contract. Sourced from the shared contract so the
    // desktop's refusal floor cannot drift from Android's.
    const val MIN_MIGRATABLE_VERSION = PayanamDatabaseSchema.MIN_MIGRATABLE_VERSION
    /**
     * Outcome of [checkDatabaseHealth]: whether the database is safe to open,
     * whether it needs a Room migration or a repair, plus the detected version.
     */
    data class HealthCheckResult(
        val isHealthy: Boolean,
        val errorMessage: String? = null,
        val needsRepair: Boolean = false,
        val needsMigration: Boolean = false,
        val currentVersion: Int = 0,
        val targetVersion: Int = CURRENT_VERSION,
    )

    /**
     * Performs a comprehensive health check on the database.
     * Returns true if database is safe to use, false if needs repair/recreation.
     */
    fun hasDatabaseArtifacts(context: Context): Boolean =
        hasDatabaseArtifacts(context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME))

    /**
     * The same question about an explicit file. The app's own database is not the only file that gets
     * checked: an import candidate is one the user picked and it lives outside the app's database
     * directory, so re-deriving the path from a `Context` would answer a question nobody asked.
     * Sidecars follow the file they belong to, which is why the name is taken from the file itself.
     */
    fun hasDatabaseArtifacts(databaseFile: File): Boolean =
        // The FULL family, not the sidecars alone: the caller immediately below tells "no artifacts
        // at all" (a first install, so a fresh database is correct) apart from "sidecars survived but
        // the main file did not" (rows exist in the `-wal`, so the answer must be repair, never a
        // fresh database). [DatabaseArtifacts.filesFor] is the declaration of that family, main file
        // first, and it is the whole answer to "is there anything here worth recovering" — so the
        // question is asked of the family rather than of a spelling of it. Members that do not exist
        // are still listed, which is what an existence check here needs.
        DatabaseArtifacts.filesFor(databaseFile).any { it.exists() }
    /**
     * Opens the database read-only (plain SQLite or SQLCipher when
     * [sqlCipherPassphrase] is given) and validates version, presence of
     * critical tables, and schema integrity. Returns a [HealthCheckResult]
     * telling the caller whether to open, migrate, or repair.
     */
    fun checkDatabaseHealth(
        context: Context,
        sqlCipherPassphrase: String? = null,
    ): HealthCheckResult = checkDatabaseHealth(
        databaseFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME),
        sqlCipherPassphrase = sqlCipherPassphrase,
    )

    /**
     * The same check against an explicit file. The port in `:core:shared` promises that the file it was
     * handed is the file checked, and an import candidate is never the app's own database, so the path
     * has to arrive as a parameter instead of being re-derived from a `Context`.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")  // Intentional: System.loadLibrary can throw UnsatisfiedLinkError (Error, not Exception) which propagates to caller
    fun checkDatabaseHealth(
        databaseFile: File,
        sqlCipherPassphrase: String? = null,
    ): HealthCheckResult {
        val dbFile = databaseFile
        if (!hasDatabaseArtifacts(dbFile)) {
            logger.i("DatabaseHealthChecker.checkDatabaseHealth", "No database file found")
            return HealthCheckResult(isHealthy = false, needsRepair = false)
        }
        if (!dbFile.exists()) {
            logger.w("DatabaseHealthChecker.checkDatabaseHealth", "Database sidecar files found but primary DB file missing")
            return HealthCheckResult(
                isHealthy = false,
                errorMessage = "Database sidecar files found but primary DB file missing.",
                needsRepair = true,
            )
        }

        logger.i(
            "DatabaseHealthChecker.checkDatabaseHealth",
            "Checking database health",
            mapOf(
                "path" to dbFile.absolutePath,
                "sizeKB" to (dbFile.length() / 1024),
            ),
        )

        return try {
            if (sqlCipherPassphrase.isNullOrEmpty()) {
                SQLiteDatabase
                    .openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    ).use { db -> validateOpenedDatabase(db) }
            } else {
                System.loadLibrary("sqlcipher")
                SqlCipherDatabase
                    .openDatabase(
                        dbFile.absolutePath,
                        sqlCipherPassphrase,
                        null,
                        SqlCipherDatabase.OPEN_READONLY,
                        null,
                    ).use { db ->
                        validateOpenedDatabaseCompat(
                            version = db.version,
                            tables =
                                getCriticalTables(
                                    tableLoader = {
                                        db.rawQuery(
                                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'",
                                            null,
                                        )
                                    },
                                ),
                            schemaIssues =
                                checkSchemaIntegrity(
                                    infoQuery = { table ->
                                        db.rawQuery("PRAGMA table_info($table)", null)
                                    },
                                    indexQuery = { table ->
                                        db.rawQuery("PRAGMA index_list($table)", null)
                                    },
                                ),
                        )
                    }
            }
        } catch (e: Exception) {
            logger.e(
                "DatabaseHealthChecker.checkDatabaseHealth",
                "Health check failed",
                e,
                mapOf(
                    "error" to (e.message ?: "Unknown error"),
                ),
            )
            HealthCheckResult(
                isHealthy = false,
                errorMessage = "Cannot open database: ${e.message}",
                needsRepair = true,
            )
        }
    }

    private fun validateOpenedDatabase(db: SQLiteDatabase): HealthCheckResult {
        val tables =
            getCriticalTables(
                tableLoader = {
                    db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'",
                        null,
                    )
                },
            )
        val schemaIssues =
            checkSchemaIntegrity(
                infoQuery = { table -> db.rawQuery("PRAGMA table_info($table)", null) },
                indexQuery = { table -> db.rawQuery("PRAGMA index_list($table)", null) },
            )
        return validateOpenedDatabaseCompat(
            version = db.version,
            tables = tables,
            schemaIssues = schemaIssues,
        )
    }

    private fun validateOpenedDatabaseCompat(
        version: Int,
        tables: Set<String>,
        schemaIssues: List<String>,
    ): HealthCheckResult {
        val requiredTables = PayanamDatabaseSchema.CRITICAL_TABLES
        val missingTables = requiredTables - tables
        if (missingTables.isNotEmpty()) {
            logger.w(
                "DatabaseHealthChecker.checkDatabaseHealth",
                "Missing critical tables",
                mapOf(
                    "missing" to missingTables.joinToString(", "),
                ),
            )
            return HealthCheckResult(
                isHealthy = false,
                errorMessage = "Missing tables: ${missingTables.joinToString(", ")}",
                needsRepair = true,
                // The version WAS read - it is the parameter above - so reporting the default 0 here
                // would claim a measurement nobody made, and 0 means "too old" to every reader. An
                // import candidate is checked for its version before anything else, and it can be
                // missing tables for reasons that have nothing to do with its schema version.
                currentVersion = version,
            )
        }
        if (version < CURRENT_VERSION && version >= MIN_MIGRATABLE_VERSION) {
            logger.i(
                "DatabaseHealthChecker.checkDatabaseHealth",
                "Database needs migration",
                mapOf(
                    "current" to version,
                    "target" to CURRENT_VERSION,
                ),
            )
            return HealthCheckResult(
                isHealthy = true,
                needsMigration = true,
                currentVersion = version,
                targetVersion = CURRENT_VERSION,
            )
        }
        if (version < MIN_MIGRATABLE_VERSION) {
            logger.w(
                "DatabaseHealthChecker.checkDatabaseHealth",
                "Version too old",
                mapOf(
                    "found" to version,
                    "minimum" to MIN_MIGRATABLE_VERSION,
                ),
            )
            return HealthCheckResult(
                isHealthy = false,
                errorMessage = "Database version $version is too old. Minimum supported schema is $MIN_MIGRATABLE_VERSION.",
                needsRepair = true,
                currentVersion = version,
            )
        }
        if (version > CURRENT_VERSION) {
            logger.w(
                "DatabaseHealthChecker.checkDatabaseHealth",
                "Version too new",
                mapOf(
                    "found" to version,
                    "expected" to CURRENT_VERSION,
                ),
            )
            return HealthCheckResult(
                isHealthy = false,
                errorMessage = "Database version $version is newer than app supports ($CURRENT_VERSION). Please update the app.",
                needsRepair = false,
                currentVersion = version,
            )
        }
        if (schemaIssues.isNotEmpty()) {
            logger.w(
                "DatabaseHealthChecker.checkDatabaseHealth",
                "Schema integrity issues",
                mapOf(
                    "issues" to schemaIssues.joinToString("; "),
                ),
            )
            return HealthCheckResult(
                isHealthy = false,
                errorMessage = "Schema issues: ${schemaIssues.first()}",
                needsRepair = true,
            )
        }

        logger.i(
            "DatabaseHealthChecker.checkDatabaseHealth",
            "Database is healthy",
            mapOf(
                "version" to version,
                "tables" to tables.size,
            ),
        )
        return HealthCheckResult(isHealthy = true, currentVersion = version)
    }

    private fun getCriticalTables(tableLoader: () -> android.database.Cursor): Set<String> {
        val tables = mutableSetOf<String>()
        tableLoader().use { cursor ->
            while (cursor.moveToNext()) {
                tables.add(cursor.getString(0))
            }
        }
        return tables
    }

    private fun checkSchemaIntegrity(
        infoQuery: (String) -> android.database.Cursor,
        indexQuery: (String) -> android.database.Cursor,
    ): List<String> {
        val issues = mutableListOf<String>()

        // Check day_journal_entries - ID must be NOT NULL
        try {
            infoQuery("day_journal_entries").use { cursor ->
                while (cursor.moveToNext()) {
                    val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                    if (columnName == "id" && notNull == 0) {
                        issues.add("day_journal_entries.id is nullable (should be NOT NULL)")
                    }
                }
            }
        } catch (e: SQLException) {
            issues.add("Cannot check day_journal_entries schema: ${e.message}")
        }

        // Check day_journal_responses - critical columns must match entity definitions
        try {
            infoQuery("day_journal_responses").use { cursor ->
                val columnInfo = mutableMapOf<String, Int>() // column name -> notNull (1/0)
                while (cursor.moveToNext()) {
                    val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                    columnInfo[columnName] = notNull
                }

                // responseText MUST be nullable in v0.0.3
                if (columnInfo["responseText"] == 1) {
                    issues.add("day_journal_responses.responseText is NOT NULL (should be nullable)")
                }
            }

            // Check for required unique index
            indexQuery("day_journal_responses").use { cursor ->
                val hasCompositeIndex =
                    generateSequence {
                        if (cursor.moveToNext()) cursor.getString(cursor.getColumnIndexOrThrow("name")) else null
                    }.any { it.contains("entryId_scope_dimensionKey_promptKey") }
                if (!hasCompositeIndex) {
                    issues.add("day_journal_responses missing unique composite index")
                }
            }
        } catch (e: SQLException) {
            issues.add("Cannot check day_journal_responses schema: ${e.message}")
        }

        // Check app_settings - must have unique index on key
        try {
            indexQuery("app_settings").use { cursor ->
                val hasKeyIndex =
                    generateSequence {
                        if (cursor.moveToNext()) cursor.getString(cursor.getColumnIndexOrThrow("name")) else null
                    }.any { it.contains("key") }
                if (!hasKeyIndex) {
                    issues.add("app_settings missing unique index on key column")
                }
            }
        } catch (e: SQLException) {
            issues.add("Cannot check app_settings schema: ${e.message}")
        }

        // Check scheduled_notifications - must have exactly 8 columns (no deliveredAt, no updatedAt)
        try {
            infoQuery("scheduled_notifications").use { cursor ->
                val columns = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }

                // Entity expects exactly 8 columns: id, taskId, scheduledAt, notificationType, title, body, isDelivered, createdAt
                // Old builds incorrectly had 10 columns with deliveredAt and updatedAt
                if (columns.contains("deliveredAt") || columns.contains("updatedAt")) {
                    issues.add("scheduled_notifications has extra columns (deliveredAt/updatedAt) from old build")
                }
                val expectedColumns = setOf("id", "taskId", "scheduledAt", "notificationType", "title", "body", "isDelivered", "createdAt")
                val missingColumns = expectedColumns - columns.toSet()
                if (missingColumns.isNotEmpty()) {
                    issues.add("scheduled_notifications missing columns: ${missingColumns.joinToString(", ")}")
                }
            }
        } catch (e: SQLException) {
            issues.add("Cannot check scheduled_notifications schema: ${e.message}")
        }

        return issues
    }
}
