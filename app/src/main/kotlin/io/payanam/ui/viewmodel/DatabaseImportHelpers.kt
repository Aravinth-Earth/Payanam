//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.viewmodel

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.DatabaseHealthChecker
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

internal data class DatabaseTableCounts(
    /** Task count. */
    val taskCount: Int,
    /** Time entry count. */
    val timeEntryCount: Int,
    /** Journal entry count. */
    val journalEntryCount: Int,
    /** Note count. */
    val noteCount: Int,
)

internal fun readDatabaseTableCounts(dbFile: File, logger: UnifiedLogger): DatabaseTableCounts {
    /** If. */
    if (!dbFile.exists()) {
        logger.w("readDatabaseTableCounts", "DB file missing while reading table counts", mapOf("path" to dbFile.absolutePath))
        return DatabaseTableCounts(0, 0, 0, 0)
    }
    return try {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            /** Database table counts. */
            DatabaseTableCounts(
                taskCount = queryTableCount(db, "tasks"),
                timeEntryCount = queryTableCount(db, "time_entries"),
                journalEntryCount = queryTableCount(db, "day_journal_entries") + queryTableCount(db, "journal_notes"),
                noteCount = queryTableCount(db, "notes"),
            ).also { counts ->
                logger.i(
                    "readDatabaseTableCounts",
                    "Read database table counts",
                    /** Map of. */
                    mapOf(
                        "path" to dbFile.absolutePath,
                        "taskCount" to counts.taskCount,
                        "timeEntryCount" to counts.timeEntryCount,
                        "journalEntryCount" to counts.journalEntryCount,
                        "noteCount" to counts.noteCount,
                    ),
                )
            }
        }
    } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
        logger.w("readDatabaseTableCounts", "Failed to read table counts", mapOf("error" to (e.message ?: "unknown")))
        /** Database table counts. */
        DatabaseTableCounts(0, 0, 0, 0)
    }
}

internal fun queryTableCount(database: SQLiteDatabase, tableName: String): Int = try {
    database.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
        /** If. */
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }
} catch (_: Exception) {
    0
}

internal object DatabaseImportHelper {
    private val logger = UnifiedLogger.getInstance()

    /**
     * Resume import with passphrase.
     */
    suspend fun resumeImportWithPassphrase(
        /** View model. */
        viewModel: DatabaseInitViewModel,
        /** Context. */
        context: Context,
        /** Database encryption manager. */
        databaseEncryptionManager: DatabaseEncryptionManager,
        /** Passphrase. */
        passphrase: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        logger.i(
            "DatabaseImportHelper.resumeImportWithPassphrase",
            "Resume import with passphrase requested",
            /** Map of. */
            mapOf(
                "hasPendingImportDb" to (viewModel.importDbFile != null),
                "passphraseLength" to passphrase.length,
            ),
        )
        try {
            /** Db file. */
            val dbFile = viewModel.importDbFile
                ?: throw IllegalStateException("No pending import to resume")

            /** With context. */
            withContext(Dispatchers.IO) {
                // Verify the imported DB can be opened with the provided passphrase
                /** Can unlock. */
                val canUnlock = DatabaseEncryptionMigrationSupport.canOpenWithSqlCipher(
                    context = context,
                    databaseFile = dbFile,
                    passphrase = passphrase,
                    logTag = "DatabaseImportHelper.resumeImportWithPassphrase",
                )
                /** If. */
                if (!canUnlock) {
                    throw IllegalStateException(
                        context.getString(io.payanam.R.string.db_passphrase_unlock_error_invalid),
                    )
                }

                logger.i(
                    "DatabaseImportHelper.resumeImportWithPassphrase",
                    "Imported DB unlocked successfully with provided passphrase",
                )

                // Use imported passphrase as the local encryption key
                /** Configured. */
                val configured = databaseEncryptionManager.configurePassphrase(passphrase)
                /** If. */
                if (!configured) {
                    throw IllegalStateException("Failed to configure encryption with imported passphrase")
                }
                // No session gate to mark — onSuccess() triggers restartProcess(), and the cold
                // boot auth flow will open the DB with the newly configured passphrase.

                logger.i(
                    "DatabaseImportHelper.resumeImportWithPassphrase",
                    "Encryption enabled with imported DB passphrase",
                )

                // Verify the imported DB is healthy with the passphrase
                /** Post import health. */
                val postImportHealth = DatabaseHealthChecker.checkDatabaseHealth(
                    context = context,
                    sqlCipherPassphrase = passphrase,
                )
                /** If. */
                if (!postImportHealth.isHealthy) {
                    throw IllegalStateException(
                        postImportHealth.errorMessage
                            ?: context.getString(io.payanam.R.string.loc_database_needs_repair),
                    )
                }

                // Mark database initialization as complete
                viewModel.settingsRepository.setSetting("database_init_completed", "true")
                logger.i(
                    "DatabaseImportHelper.resumeImportWithPassphrase",
                    "Marked database_init_completed during resume path",
                )
            }

            /** Delay. */
            delay(500)
            viewModel.clearPendingImport()
            logger.i("DatabaseImportHelper.resumeImportWithPassphrase", "Pending import state cleared; invoking success callback")
            /** On success. */
            onSuccess()
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("DatabaseImportHelper.resumeImportWithPassphrase", "Failed to resume import", e)
            /** On error. */
            onError(e.message ?: "Failed to unlock imported database")
        }
    }

    /**
     * Resume encrypted import after unlock.
     */
    suspend fun resumeEncryptedImportAfterUnlock(
        /** View model. */
        viewModel: DatabaseInitViewModel,
        /** Context. */
        context: Context,
        /** Database encryption manager. */
        databaseEncryptionManager: DatabaseEncryptionManager,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        logger.i(
            "DatabaseImportHelper.resumeEncryptedImportAfterUnlock",
            "Resume encrypted import after unlock requested",
            /** Map of. */
            mapOf("hasPendingImportDb" to (viewModel.importDbFile != null)),
        )
        try {
            /** Db file. */
            val dbFile = viewModel.importDbFile
                ?: throw IllegalStateException("No pending import to resume")

            /** With context. */
            withContext(Dispatchers.IO) {
                logger.i(
                    "DatabaseImportHelper.resumeEncryptedImportAfterUnlock",
                    "Resuming encrypted import after passphrase unlock screen",
                )

                // At this point, the encryption manager has verified the passphrase
                // and marked it as unlocked. We can now proceed with the import.
                // Mark database initialization as complete
                viewModel.settingsRepository.setSetting("database_init_completed", "true")

                logger.i(
                    "DatabaseImportHelper.resumeEncryptedImportAfterUnlock",
                    "Encrypted import completed successfully",
                )
            }

            /** Delay. */
            delay(500)
            viewModel.clearPendingImport()
            logger.i("DatabaseImportHelper.resumeEncryptedImportAfterUnlock", "Pending import state cleared; invoking success callback")
            /** On success. */
            onSuccess()
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            logger.e("DatabaseImportHelper.resumeEncryptedImportAfterUnlock", "Failed to resume import", e)
            /** On error. */
            onError(e.message ?: "Failed to complete encrypted import")
        }
    }
}
