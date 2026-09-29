//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.presentation.database

import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.logging.AppLogger
import java.io.File

/**
 * The import integrity check: the same table counts are read before and after a conversion, and a
 * difference means the conversion lost data.
 *
 * PROMOTED FROM `:app` (DB-VM-PROMOTION wave 1b). It used to call
 * `io.payanam.database.security.DatabaseEncryptionMigrationSupport.readTableCounts` directly, which is an
 * Android library in `:core:database` — a dependency this plain-JVM module can never take, because
 * `:core:database` already depends on THIS module for its tests, and an Android library is not consumable
 * from a `kotlin.jvm` target anyway. The rules were already shared: [DatabaseEncryptionProbe.readTableCounts]
 * asks the same question in the same shape, and it is the contract the DB-init screen already uses
 * (`DatabaseInitViewModel.detectBootstrapPlaceholder`). So the one platform-specific call is now the
 * port both platforms implement — same behaviour, no second rule to keep in step.
 */
object DatabaseImportIntegritySupport {
    private val logger get() = AppLogger.current

    private val trackedTables = listOf("tasks", "time_entries", "notes")

    /**
     * Row counts of the tracked core tables (tasks, time_entries, notes) read
     * from a database file, plaintext or encrypted.
     *
     * [encryptionProbe] is supplied by the caller because it is the platform's own handle: Android answers
     * through SQLCipher, the desktop through JDBC, and neither is visible from here.
     */
    fun readCoreCounts(
        databaseFile: File,
        passphrase: String?,
        encryptionProbe: DatabaseEncryptionProbe,
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
        val counts =
            encryptionProbe.readTableCounts(
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
     * Asserts the import preserved every tracked table's row count; throws on
     * the first mismatch.
     *
     * A MISSING table and an EMPTY table are the same 0 here, so a file whose tables could not be counted
     * passes this check rather than failing it. That is deliberate — [DatabaseEncryptionProbe.readTableCounts]
     * reports an unreadable table as 0 rather than throwing, because a file that cannot be counted is a
     * refusal the caller reports, not a crash mid-import — and it is why the counts are logged on the way in
     * and out: a silent 0 would otherwise read as "the conversion destroyed your data" with no way to tell
     * it apart from a file that was never readable in the first place.
     */
    fun validateCountsPreserved(
        beforeCounts: Map<String, Int>,
        afterCounts: Map<String, Int>,
        logTag: String,
    ) {
        val mismatch =
            trackedTables.firstOrNull { table ->
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
