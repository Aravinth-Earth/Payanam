//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length", "MagicNumber")


package io.payanam.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.payanam.common.logging.UnifiedLogger
import java.io.File
import net.sqlcipher.database.SQLiteDatabase as SqlCipherDatabase
object DatabaseHealthChecker {
    private val logger = UnifiedLogger.getInstance()

    // Current expected database version - derived from PayanamDatabase
    const val CURRENT_VERSION = PAYANAM_DATABASE_SCHEMA_VERSION

    // Build #1081 was the first beta build shipped to users, and it used schema 16.
    // Anything older is outside the supported in-place Room migration contract.
    const val MIN_MIGRATABLE_VERSION = 16
    /**
     * Holds the health check result.
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
    fun hasDatabaseArtifacts(context: Context): Boolean {
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        val walFile = File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal")
        val shmFile = File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-shm")
        val journalFile = File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-journal")
        return dbFile.exists() || walFile.exists() || shmFile.exists() || journalFile.exists()
    }
    /**
     * Returns true when the check database health.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun checkDatabaseHealth(
        context: Context,
        sqlCipherPassphrase: String? = null,
    ): HealthCheckResult {
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        if (!hasDatabaseArtifacts(context)) {
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
                SqlCipherDatabase.loadLibs(context)
                SqlCipherDatabase
                    .openDatabase(
                        dbFile.absolutePath,
                        sqlCipherPassphrase,
                        null,
                        SqlCipherDatabase.OPEN_READONLY,
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
        val requiredTables = setOf("tasks", "time_entries", "notes", "day_journal_entries", "app_settings")
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

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            issues.add("Cannot check scheduled_notifications schema: ${e.message}")
        }

        return issues
    }
}
