//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import io.payanam.database.security.PassphrasePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * DatabasePassphraseSetupUiState.
 */
data class DatabasePassphraseSetupUiState(
    /** Is saving. */
    val isSaving: Boolean = false,
    /** Error reason code. */
    val errorReasonCode: String? = null,
    /** Has existing local database. */
    val hasExistingLocalDatabase: Boolean = false,
    /** Database size kb. */
    val databaseSizeKb: Long = 0L,
    /** Database last modified ms. */
    val databaseLastModifiedMs: Long = 0L,
    /** Task count. */
    val taskCount: Int = 0,
    /** Time entry count. */
    val timeEntryCount: Int = 0,
    /** Journal count. */
    val journalCount: Int = 0,
    /** Note count. */
    val noteCount: Int = 0,
    /** Storage mode label key. */
    val storageModeLabelKey: String = "plaintext",
)

@HiltViewModel
/**
 * DatabasePassphraseSetupViewModel.
 */
class DatabasePassphraseSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: DatabaseEncryptionManager,
) : ViewModel() {

    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(DatabasePassphraseSetupUiState())
    /** Ui state. */
    val uiState: StateFlow<DatabasePassphraseSetupUiState> = _uiState.asStateFlow()

    init {
        /** Load database summary. */
        loadDatabaseSummary()
    }

    /**
     * Configure passphrase.
     */
    fun configurePassphrase(
        /** Passphrase. */
        passphrase: String,
        /** Confirm passphrase. */
        confirmPassphrase: String,
        onSuccess: () -> Unit,
    ) {
        /** Validation. */
        val validation = PassphrasePolicy.validate(passphrase)
        /** If. */
        if (!validation.isValid) {
            _uiState.update {
                it.copy(errorReasonCode = validation.reasonCode)
            }
            /** Return. */
            return
        }
        /** If. */
        if (passphrase != confirmPassphrase) {
            _uiState.update {
                it.copy(errorReasonCode = "mismatch")
            }
            /** Return. */
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorReasonCode = null) }
            /** Migration succeeded. */
            val migrationSucceeded = runCatching {
                /** Migrate existing database if needed. */
                migrateExistingDatabaseIfNeeded(passphrase)
                /** True. */
                true
            }.getOrElse { error ->
                /** Reason code. */
                val reasonCode = if (error.message?.contains("different key or unreadable", ignoreCase = true) == true) {
                    "migration_incompatible"
                } else {
                    "migration_failed"
                }
                logger.e(
                    "DatabasePassphraseSetupViewModel.configurePassphrase",
                    "Passphrase setup failed during database migration",
                    /** Error. */
                    error,
                )
                _uiState.update { it.copy(isSaving = false, errorReasonCode = reasonCode) }
                /** False. */
                false
            }
            /** If. */
            if (!migrationSucceeded) return@launch

            /** Configured. */
            val configured = encryptionManager.configurePassphrase(passphrase)
            /** If. */
            if (!configured) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorReasonCode = "persist_failed",
                    )
                }
                return@launch
            }
            logger.i(
                "DatabasePassphraseSetupViewModel.configurePassphrase",
                "Passphrase setup completed",
            )
            _uiState.update { it.copy(isSaving = false, errorReasonCode = null) }
            /** On success. */
            onSuccess()
        }
    }

    /**
     * Clear error.
     */
    fun clearError() {
        _uiState.update { it.copy(errorReasonCode = null) }
    }

    /**
     * Reset local data and configure passphrase.
     */
    fun resetLocalDataAndConfigurePassphrase(
        /** Passphrase. */
        passphrase: String,
        /** Confirm passphrase. */
        confirmPassphrase: String,
        onSuccess: () -> Unit,
    ) {
        /** Validation. */
        val validation = PassphrasePolicy.validate(passphrase)
        /** If. */
        if (!validation.isValid) {
            _uiState.update { it.copy(errorReasonCode = validation.reasonCode) }
            /** Return. */
            return
        }
        /** If. */
        if (passphrase != confirmPassphrase) {
            _uiState.update { it.copy(errorReasonCode = "mismatch") }
            /** Return. */
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorReasonCode = null) }
            /** Reset ok. */
            val resetOk = runCatching {
                /** Delete all database files. */
                deleteAllDatabaseFiles()
                encryptionManager.resetEncryptionState()
            }.getOrElse { error ->
                logger.e(
                    "DatabasePassphraseSetupViewModel.resetLocalDataAndConfigurePassphrase",
                    "Failed to reset local data before passphrase setup",
                    /** Error. */
                    error,
                )
                /** False. */
                false
            }
            /** If. */
            if (!resetOk) {
                _uiState.update { it.copy(isSaving = false, errorReasonCode = "reset_failed") }
                return@launch
            }

            /** Configured. */
            val configured = encryptionManager.configurePassphrase(passphrase)
            /** If. */
            if (!configured) {
                _uiState.update { it.copy(isSaving = false, errorReasonCode = "persist_failed") }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isSaving = false,
                    errorReasonCode = null,
                    hasExistingLocalDatabase = false,
                )
            }
            /** On success. */
            onSuccess()
        }
    }

    private fun migrateExistingDatabaseIfNeeded(passphrase: String) {
        /** Db file. */
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        /** If. */
        if (!dbFile.exists()) {
            /** Return. */
            return
        }
        /** Pre counts. */
        val preCounts = DatabaseEncryptionMigrationSupport.readTableCounts(
            context = context,
            databaseFile = dbFile,
            passphrase = null,
            tableNames = listOf("tasks", "time_entries", "day_journal_entries", "journal_notes", "notes"),
        )
        /** Artifacts. */
        val artifacts = getDatabaseArtifactFiles(dbFile).filter { it.exists() }
        /** Backup mappings. */
        val backupMappings = backupDatabaseArtifacts(artifacts)
        try {
            DatabaseEncryptionMigrationSupport.ensureEncryptedWithPassphrase(
                context = context,
                databaseFile = dbFile,
                passphrase = passphrase,
                logTag = "DatabasePassphraseSetupViewModel.migrateExistingDatabaseIfNeeded",
            )
            /** Post counts. */
            val postCounts = DatabaseEncryptionMigrationSupport.readTableCounts(
                context = context,
                databaseFile = dbFile,
                passphrase = passphrase,
                tableNames = listOf("tasks", "time_entries", "day_journal_entries", "journal_notes", "notes"),
            )
            /** Tables to validate. */
            val tablesToValidate = listOf("tasks", "time_entries", "notes", "day_journal_entries", "journal_notes")
            /** Mismatch. */
            val mismatch = tablesToValidate.any { table ->
                (preCounts[table] ?: 0) != (postCounts[table] ?: 0)
            }
            /** If. */
            if (mismatch) {
                logger.w(
                    "DatabasePassphraseSetupViewModel.migrateExistingDatabaseIfNeeded",
                    "Pre/post migration counts mismatch; rolling back",
                    /** Map of. */
                    mapOf("preCounts" to preCounts, "postCounts" to postCounts),
                )
                throw IllegalStateException("Migration count validation failed.")
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
            /** Restore database artifacts. */
            restoreDatabaseArtifacts(backupMappings)
            throw error
        } finally {
            /** Cleanup backup artifacts. */
            cleanupBackupArtifacts(backupMappings)
        }
    }

    private fun getDatabaseArtifactFiles(dbFile: File): List<File> = listOf(
        /** Db file. */
        dbFile,
        /** File. */
        File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal"),
        /** File. */
        File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-shm"),
        /** File. */
        File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-journal"),
    )

    private fun backupDatabaseArtifacts(files: List<File>): List<Pair<File, File>> {
        /** Timestamp. */
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        return files.map { original ->
            /** Backup. */
            val backup = File(original.parent, "${original.name}.before_encrypt_$timestamp.bak")
            original.copyTo(backup, overwrite = true)
            original to backup
        }
    }

    private fun restoreDatabaseArtifacts(mappings: List<Pair<File, File>>): Int {
        /** Restored. */
        var restored = 0
        mappings.forEach { (original, backup) ->
            /** If. */
            if (backup.exists()) {
                backup.copyTo(original, overwrite = true)
                restored++
            }
        }
        logger.i(
            "DatabasePassphraseSetupViewModel.restoreDatabaseArtifacts",
            "Restored database artifacts after encryption migration failure",
            /** Map of. */
            mapOf("restoredFiles" to restored),
        )
        return restored
    }

    private fun cleanupBackupArtifacts(mappings: List<Pair<File, File>>) {
        mappings.forEach { (_, backup) ->
            /** If. */
            if (backup.exists()) {
                backup.delete()
            }
        }
    }

    private fun deleteAllDatabaseFiles(): Int {
        /** Db file. */
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        /** Files. */
        val files = listOf(
            /** Db file. */
            dbFile,
            /** File. */
            File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal"),
            /** File. */
            File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-shm"),
            /** File. */
            File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-journal"),
        )
        /** Deleted count. */
        var deletedCount = 0
        files.forEach { file ->
            /** If. */
            if (file.exists() && file.delete()) {
                deletedCount++
            }
        }
        logger.w(
            "DatabasePassphraseSetupViewModel.deleteAllDatabaseFiles",
            "Deleted local database artifacts during setup recovery",
            /** Map of. */
            mapOf("deletedCount" to deletedCount),
        )
        return deletedCount
    }

    private fun loadDatabaseSummary() {
        /** Db file. */
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        /** If. */
        if (!dbFile.exists()) {
            _uiState.update {
                it.copy(
                    hasExistingLocalDatabase = false,
                    databaseSizeKb = 0L,
                    databaseLastModifiedMs = 0L,
                    taskCount = 0,
                    timeEntryCount = 0,
                    journalCount = 0,
                    noteCount = 0,
                    storageModeLabelKey = "plaintext",
                )
            }
            /** Return. */
            return
        }
        /** Counts. */
        val counts = DatabaseEncryptionMigrationSupport.readTableCounts(
            context = context,
            databaseFile = dbFile,
            passphrase = null,
            tableNames = listOf("tasks", "time_entries", "day_journal_entries", "journal_notes", "notes"),
        )
        _uiState.update {
            it.copy(
                hasExistingLocalDatabase = true,
                databaseSizeKb = dbFile.length() / 1024,
                databaseLastModifiedMs = dbFile.lastModified(),
                taskCount = counts["tasks"] ?: 0,
                timeEntryCount = counts["time_entries"] ?: 0,
                journalCount = (counts["day_journal_entries"] ?: 0) + (counts["journal_notes"] ?: 0),
                noteCount = counts["notes"] ?: 0,
                storageModeLabelKey = "plaintext",
            )
        }
    }
}
