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
    val taskCount: Int,
    val timeEntryCount: Int,
    val journalEntryCount: Int,
    val noteCount: Int,
)

@Suppress("TooGenericExceptionCaught", "SwallowedException")
internal fun readDatabaseTableCounts(dbFile: File, logger: UnifiedLogger): DatabaseTableCounts {
    if (!dbFile.exists()) {
        logger.w("readDatabaseTableCounts", "DB file missing while reading table counts", mapOf("path" to dbFile.absolutePath))
        return DatabaseTableCounts(0, 0, 0, 0)
    }
    return try {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            DatabaseTableCounts(
                taskCount = queryTableCount(db, "tasks"),
                timeEntryCount = queryTableCount(db, "time_entries"),
                journalEntryCount = queryTableCount(db, "day_journal_entries") + queryTableCount(db, "journal_notes"),
                noteCount = queryTableCount(db, "notes"),
            ).also { counts ->
                logger.i(
                    "readDatabaseTableCounts",
                    "Read database table counts",
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
    } catch (e: Exception) {
        logger.w("readDatabaseTableCounts", "Failed to read table counts", mapOf("error" to (e.message ?: "unknown")))
        DatabaseTableCounts(0, 0, 0, 0)
    }
}

internal fun queryTableCount(database: SQLiteDatabase, tableName: String): Int = try {
    database.rawQuery("SELECT COUNT(*) FROM $tableName", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }
} catch (_: Exception) {
    0
}

internal object DatabaseImportHelper {
    private val logger = UnifiedLogger.getInstance()
    /**
     * Resumes a pending encrypted import with its passphrase: verifies it can
     * open the file, adopts it as the local key, health-checks, and completes
     * setup.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun resumeImportWithPassphrase(
        viewModel: DatabaseInitViewModel,
        context: Context,
        databaseEncryptionManager: DatabaseEncryptionManager,
        passphrase: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        logger.i(
            "DatabaseImportHelper.resumeImportWithPassphrase",
            "Resume import with passphrase requested",
            mapOf(
                "hasPendingImportDb" to (viewModel.importDbFile != null),
                "passphraseLength" to passphrase.length,
            ),
        )
        try {
            val dbFile = viewModel.importDbFile
                ?: throw IllegalStateException("No pending import to resume")
            withContext(Dispatchers.IO) {
                // Verify the imported DB can be opened with the provided passphrase
                val canUnlock = DatabaseEncryptionMigrationSupport.canOpenWithSqlCipher(
                    context = context,
                    databaseFile = dbFile,
                    passphrase = passphrase,
                    logTag = "DatabaseImportHelper.resumeImportWithPassphrase",
                )
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
                val configured = databaseEncryptionManager.configurePassphrase(passphrase)
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
                val postImportHealth = DatabaseHealthChecker.checkDatabaseHealth(
                    context = context,
                    sqlCipherPassphrase = passphrase,
                )
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
            delay(500)
            viewModel.clearPendingImport()
            logger.i("DatabaseImportHelper.resumeImportWithPassphrase", "Pending import state cleared; invoking success callback")
            onSuccess()
        } catch (e: Exception) {
            logger.e("DatabaseImportHelper.resumeImportWithPassphrase", "Failed to resume import", e)
            onError(e.message ?: "Failed to unlock imported database")
        }
    }
    /**
     * Finishes an encrypted-DB import after the unlock gate: marks setup
     * complete, clears the pending import, then invokes the success callback.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun resumeEncryptedImportAfterUnlock(
        viewModel: DatabaseInitViewModel,
        context: Context,
        databaseEncryptionManager: DatabaseEncryptionManager,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        logger.i(
            "DatabaseImportHelper.resumeEncryptedImportAfterUnlock",
            "Resume encrypted import after unlock requested",
            mapOf("hasPendingImportDb" to (viewModel.importDbFile != null)),
        )
        try {
            val dbFile = viewModel.importDbFile
                ?: throw IllegalStateException("No pending import to resume")
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
            delay(500)
            viewModel.clearPendingImport()
            logger.i("DatabaseImportHelper.resumeEncryptedImportAfterUnlock", "Pending import state cleared; invoking success callback")
            onSuccess()
        } catch (e: Exception) {
            logger.e("DatabaseImportHelper.resumeEncryptedImportAfterUnlock", "Failed to resume import", e)
            onError(e.message ?: "Failed to complete encrypted import")
        }
    }
}
