//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:max-line-length", "MagicNumber")


package io.payanam.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.payanam.common.logging.UnifiedLogger
import java.io.File
import net.sqlcipher.database.SQLiteDatabase as SqlCipherDatabase

/**
 * DatabaseHealthChecker.
 */
object DatabaseHealthChecker {
    private val logger = UnifiedLogger.getInstance()

    // Current expected database version - derived from PayanamDatabase
    /** Current version. */
    const val CURRENT_VERSION = PAYANAM_DATABASE_SCHEMA_VERSION

    // Build #1081 was the first beta build shipped to users, and it used schema 16.
    // Anything older is outside the supported in-place Room migration contract.
    /** Min migratable version. */
    const val MIN_MIGRATABLE_VERSION = 16

    /**
     * HealthCheckResult.
     */
    data class HealthCheckResult(
        /** Is healthy. */
        val isHealthy: Boolean,
        /** Error message. */
        val errorMessage: String? = null,
        /** Needs repair. */
        val needsRepair: Boolean = false,
        /** Needs migration. */
        val needsMigration: Boolean = false,
        /** Current version. */
        val currentVersion: Int = 0,
        /** Target version. */
        val targetVersion: Int = CURRENT_VERSION,
    )

    /**
     * Performs a comprehensive health check on the database.
     * Returns true if database is safe to use, false if needs repair/recreation.
     */
    fun hasDatabaseArtifacts(context: Context): Boolean {
        /** Db file. */
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        /** Wal file. */
        val walFile = File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal")
        /** Shm file. */
        val shmFile = File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-shm")
        /** Journal file. */
        val journalFile = File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-journal")
        return dbFile.exists() || walFile.exists() || shmFile.exists() || journalFile.exists()
    }

    /**
     * Check database health.
     */
    fun checkDatabaseHealth(
        /** Context. */
        context: Context,
        sqlCipherPassphrase: String? = null,
    ): HealthCheckResult {
        /** Db file. */
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)

        /** If. */
        if (!hasDatabaseArtifacts(context)) {
            logger.i("DatabaseHealthChecker.checkDatabaseHealth", "No database file found")
            return HealthCheckResult(isHealthy = false, needsRepair = false)
        }

        /** If. */
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
            /** Map of. */
            mapOf(
                "path" to dbFile.absolutePath,
                "sizeKB" to (dbFile.length() / 1024),
            ),
        )

        return try {
            /** If. */
            if (sqlCipherPassphrase.isNullOrEmpty()) {
                /** Sqlite database. */
                SQLiteDatabase
                    .openDatabase(
                        dbFile.absolutePath,
                        /** Null. */
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    ).use { db -> validateOpenedDatabase(db) }
            } else {
                SqlCipherDatabase.loadLibs(context)
                /** Sql cipher database. */
                SqlCipherDatabase
                    .openDatabase(
                        dbFile.absolutePath,
                        /** Sql cipher passphrase. */
                        sqlCipherPassphrase,
                        /** Null. */
                        null,
                        SqlCipherDatabase.OPEN_READONLY,
                    ).use { db ->
                        /** Validate opened database compat. */
                        validateOpenedDatabaseCompat(
                            version = db.version,
                            tables =
                                /** Get critical tables. */
                                getCriticalTables(
                                    tableLoader = {
                                        db.rawQuery(
                                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'",
                                            /** Null. */
                                            null,
                                        )
                                    },
                                ),
                            schemaIssues =
                                /** Check schema integrity. */
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
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e(
                "DatabaseHealthChecker.checkDatabaseHealth",
                "Health check failed",
                /** E. */
                e,
                /** Map of. */
                mapOf(
                    "error" to (e.message ?: "Unknown error"),
                ),
            )
            /** Health check result. */
            HealthCheckResult(
                isHealthy = false,
                errorMessage = "Cannot open database: ${e.message}",
                needsRepair = true,
            )
        }
    }

    private fun validateOpenedDatabase(db: SQLiteDatabase): HealthCheckResult {
        /** Tables. */
        val tables =
            /** Get critical tables. */
            getCriticalTables(
                tableLoader = {
                    db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%'",
                        /** Null. */
                        null,
                    )
                },
            )
        /** Schema issues. */
        val schemaIssues =
            /** Check schema integrity. */
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
        /** Version. */
        version: Int,
        tables: Set<String>,
        schemaIssues: List<String>,
    ): HealthCheckResult {
        /** Required tables. */
        val requiredTables = setOf("tasks", "time_entries", "notes", "day_journal_entries", "app_settings")
        /** Missing tables. */
        val missingTables = requiredTables - tables
        /** If. */
        if (missingTables.isNotEmpty()) {
            logger.w(
                "DatabaseHealthChecker.checkDatabaseHealth",
                "Missing critical tables",
                /** Map of. */
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

        /** If. */
        if (version < CURRENT_VERSION && version >= MIN_MIGRATABLE_VERSION) {
            logger.i(
                "DatabaseHealthChecker.checkDatabaseHealth",
                "Database needs migration",
                /** Map of. */
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
        /** If. */
        if (version < MIN_MIGRATABLE_VERSION) {
            logger.w(
                "DatabaseHealthChecker.checkDatabaseHealth",
                "Version too old",
                /** Map of. */
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
        /** If. */
        if (version > CURRENT_VERSION) {
            logger.w(
                "DatabaseHealthChecker.checkDatabaseHealth",
                "Version too new",
                /** Map of. */
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

        /** If. */
        if (schemaIssues.isNotEmpty()) {
            logger.w(
                "DatabaseHealthChecker.checkDatabaseHealth",
                "Schema integrity issues",
                /** Map of. */
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
            /** Map of. */
            mapOf(
                "version" to version,
                "tables" to tables.size,
            ),
        )
        return HealthCheckResult(isHealthy = true, currentVersion = version)
    }

    private fun getCriticalTables(tableLoader: () -> android.database.Cursor): Set<String> {
        /** Tables. */
        val tables = mutableSetOf<String>()
        /** Table loader. */
        tableLoader().use { cursor ->
            /** While. */
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
        /** Issues. */
        val issues = mutableListOf<String>()

        // Check day_journal_entries - ID must be NOT NULL
        try {
            /** Info query. */
            infoQuery("day_journal_entries").use { cursor ->
                /** While. */
                while (cursor.moveToNext()) {
                    /** Column name. */
                    val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    /** Not null. */
                    val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))

                    /** If. */
                    if (columnName == "id" && notNull == 0) {
                        issues.add("day_journal_entries.id is nullable (should be NOT NULL)")
                    }
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            issues.add("Cannot check day_journal_entries schema: ${e.message}")
        }

        // Check day_journal_responses - critical columns must match entity definitions
        try {
            /** Info query. */
            infoQuery("day_journal_responses").use { cursor ->
                /** Column info. */
                val columnInfo = mutableMapOf<String, Int>() // column name -> notNull (1/0)
                /** While. */
                while (cursor.moveToNext()) {
                    /** Column name. */
                    val columnName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                    /** Not null. */
                    val notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                    columnInfo[columnName] = notNull
                }

                // responseText MUST be nullable in v0.0.3
                /** If. */
                if (columnInfo["responseText"] == 1) {
                    issues.add("day_journal_responses.responseText is NOT NULL (should be nullable)")
                }
            }

            // Check for required unique index
            /** Index query. */
            indexQuery("day_journal_responses").use { cursor ->
                /** Has composite index. */
                val hasCompositeIndex =
                    generateSequence {
                        /** If. */
                        if (cursor.moveToNext()) cursor.getString(cursor.getColumnIndexOrThrow("name")) else null
                    }.any { it.contains("entryId_scope_dimensionKey_promptKey") }

                /** If. */
                if (!hasCompositeIndex) {
                    issues.add("day_journal_responses missing unique composite index")
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            issues.add("Cannot check day_journal_responses schema: ${e.message}")
        }

        // Check app_settings - must have unique index on key
        try {
            /** Index query. */
            indexQuery("app_settings").use { cursor ->
                /** Has key index. */
                val hasKeyIndex =
                    generateSequence {
                        /** If. */
                        if (cursor.moveToNext()) cursor.getString(cursor.getColumnIndexOrThrow("name")) else null
                    }.any { it.contains("key") }

                /** If. */
                if (!hasKeyIndex) {
                    issues.add("app_settings missing unique index on key column")
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            issues.add("Cannot check app_settings schema: ${e.message}")
        }

        // Check scheduled_notifications - must have exactly 8 columns (no deliveredAt, no updatedAt)
        try {
            /** Info query. */
            infoQuery("scheduled_notifications").use { cursor ->
                /** Columns. */
                val columns = mutableListOf<String>()
                /** While. */
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }

                // Entity expects exactly 8 columns: id, taskId, scheduledAt, notificationType, title, body, isDelivered, createdAt
                // Old builds incorrectly had 10 columns with deliveredAt and updatedAt
                /** If. */
                if (columns.contains("deliveredAt") || columns.contains("updatedAt")) {
                    issues.add("scheduled_notifications has extra columns (deliveredAt/updatedAt) from old build")
                }

                /** Expected columns. */
                val expectedColumns = setOf("id", "taskId", "scheduledAt", "notificationType", "title", "body", "isDelivered", "createdAt")
                /** Missing columns. */
                val missingColumns = expectedColumns - columns.toSet()
                /** If. */
                if (missingColumns.isNotEmpty()) {
                    issues.add("scheduled_notifications missing columns: ${missingColumns.joinToString(", ")}")
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            issues.add("Cannot check scheduled_notifications schema: ${e.message}")
        }

        return issues
    }
}
