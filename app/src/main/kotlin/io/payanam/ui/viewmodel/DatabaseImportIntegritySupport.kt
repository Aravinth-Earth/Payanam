//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.viewmodel

import android.content.Context
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import java.io.File

internal object DatabaseImportIntegritySupport {
    private val logger = UnifiedLogger.getInstance()
    private val trackedTables = listOf("tasks", "time_entries", "notes")
    /**
     * Loads the read core counts.
     */
    fun readCoreCounts(
        context: Context,
        databaseFile: File,
        passphrase: String?,
    ): Map<String, Int> {
        logger.i(
            "DatabaseImportIntegritySupport.readCoreCounts",
            "Reading import integrity core counts",
            mapOf(
                "dbFile" to databaseFile.absolutePath,
                "dbExists" to databaseFile.exists(),
                "dbSizeKB" to (databaseFile.length() / 1024),
                "hasPassphrase" to (passphrase != null),
            ),
        )
        val counts = DatabaseEncryptionMigrationSupport.readTableCounts(
            context = context,
            databaseFile = databaseFile,
            passphrase = passphrase,
            tableNames = trackedTables,
        )
        logger.i(
            "DatabaseImportIntegritySupport.readCoreCounts",
            "Read import integrity core counts",
            mapOf(
                "tasks" to (counts["tasks"] ?: 0),
                "timeEntries" to (counts["time_entries"] ?: 0),
                "notes" to (counts["notes"] ?: 0),
            ),
        )
        return counts
    }
    /**
     * Returns true when the validate counts preserved.
     */
    fun validateCountsPreserved(
        beforeCounts: Map<String, Int>,
        afterCounts: Map<String, Int>,
        logTag: String,
    ) {
        val mismatch = trackedTables.firstOrNull { table ->
            (beforeCounts[table] ?: 0) != (afterCounts[table] ?: 0)
        }
        if (mismatch != null) {
            logger.e(
                logTag,
                "Import integrity check failed",
                IllegalStateException("Counts mismatch for $mismatch"),
                mapOf(
                    "table" to mismatch,
                    "before" to (beforeCounts[mismatch] ?: 0),
                    "after" to (afterCounts[mismatch] ?: 0),
                ),
            )
            throw IllegalStateException(
                "Import integrity check failed for $mismatch: ${beforeCounts[mismatch]} -> ${afterCounts[mismatch]}",
            )
        }
        logger.i(logTag, "Import integrity check passed", mapOf("trackedTables" to trackedTables.size))
    }
}
