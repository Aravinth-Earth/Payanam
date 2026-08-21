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
    val isSaving: Boolean = false,
    val errorReasonCode: String? = null,
    val hasExistingLocalDatabase: Boolean = false,
    val databaseSizeKb: Long = 0L,
    val databaseLastModifiedMs: Long = 0L,
    val taskCount: Int = 0,
    val timeEntryCount: Int = 0,
    val journalCount: Int = 0,
    val noteCount: Int = 0,
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
    val uiState: StateFlow<DatabasePassphraseSetupUiState> = _uiState.asStateFlow()

    init {
        loadDatabaseSummary()
    }

    /**
     * Configure passphrase.
     */
    fun configurePassphrase(
        passphrase: String,
        confirmPassphrase: String,
        onSuccess: () -> Unit,
    ) {
        val validation = PassphrasePolicy.validate(passphrase)
        if (!validation.isValid) {
            _uiState.update {
                it.copy(errorReasonCode = validation.reasonCode)
            }
            return
        }
        if (passphrase != confirmPassphrase) {
            _uiState.update {
                it.copy(errorReasonCode = "mismatch")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorReasonCode = null) }
            val migrationSucceeded = runCatching {
                migrateExistingDatabaseIfNeeded(passphrase)
                true
            }.getOrElse { error ->
                val reasonCode = if (error.message?.contains("different key or unreadable", ignoreCase = true) == true) {
                    "migration_incompatible"
                } else {
                    "migration_failed"
                }
                logger.e(
                    "DatabasePassphraseSetupViewModel.configurePassphrase",
                    "Passphrase setup failed during database migration",
                    error,
                )
                _uiState.update { it.copy(isSaving = false, errorReasonCode = reasonCode) }
                false
            }
            if (!migrationSucceeded) return@launch
            val configured = encryptionManager.configurePassphrase(passphrase)
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
        passphrase: String,
        confirmPassphrase: String,
        onSuccess: () -> Unit,
    ) {
        val validation = PassphrasePolicy.validate(passphrase)
        if (!validation.isValid) {
            _uiState.update { it.copy(errorReasonCode = validation.reasonCode) }
            return
        }
        if (passphrase != confirmPassphrase) {
            _uiState.update { it.copy(errorReasonCode = "mismatch") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorReasonCode = null) }
            val resetOk = runCatching {
                deleteAllDatabaseFiles()
                encryptionManager.resetEncryptionState()
            }.getOrElse { error ->
                logger.e(
                    "DatabasePassphraseSetupViewModel.resetLocalDataAndConfigurePassphrase",
                    "Failed to reset local data before passphrase setup",
                    error,
                )
                false
            }
            if (!resetOk) {
                _uiState.update { it.copy(isSaving = false, errorReasonCode = "reset_failed") }
                return@launch
            }
            val configured = encryptionManager.configurePassphrase(passphrase)
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
            onSuccess()
        }
    }

    private fun migrateExistingDatabaseIfNeeded(passphrase: String) {
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        if (!dbFile.exists()) {
            return
        }
        val preCounts = DatabaseEncryptionMigrationSupport.readTableCounts(
            context = context,
            databaseFile = dbFile,
            passphrase = null,
            tableNames = listOf("tasks", "time_entries", "day_journal_entries", "journal_notes", "notes"),
        )
        val artifacts = getDatabaseArtifactFiles(dbFile).filter { it.exists() }
        val backupMappings = backupDatabaseArtifacts(artifacts)
        try {
            DatabaseEncryptionMigrationSupport.ensureEncryptedWithPassphrase(
                context = context,
                databaseFile = dbFile,
                passphrase = passphrase,
                logTag = "DatabasePassphraseSetupViewModel.migrateExistingDatabaseIfNeeded",
            )
            val postCounts = DatabaseEncryptionMigrationSupport.readTableCounts(
                context = context,
                databaseFile = dbFile,
                passphrase = passphrase,
                tableNames = listOf("tasks", "time_entries", "day_journal_entries", "journal_notes", "notes"),
            )
            val tablesToValidate = listOf("tasks", "time_entries", "notes", "day_journal_entries", "journal_notes")
            val mismatch = tablesToValidate.any { table ->
                (preCounts[table] ?: 0) != (postCounts[table] ?: 0)
            }
            if (mismatch) {
                logger.w(
                    "DatabasePassphraseSetupViewModel.migrateExistingDatabaseIfNeeded",
                    "Pre/post migration counts mismatch; rolling back",
                    mapOf("preCounts" to preCounts, "postCounts" to postCounts),
                )
                throw IllegalStateException("Migration count validation failed.")
            }
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
            restoreDatabaseArtifacts(backupMappings)
            throw error
        } finally {
            cleanupBackupArtifacts(backupMappings)
        }
    }

    private fun getDatabaseArtifactFiles(dbFile: File): List<File> = listOf(
        dbFile,
        File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal"),
        File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-shm"),
        File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-journal"),
    )

    private fun backupDatabaseArtifacts(files: List<File>): List<Pair<File, File>> {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        return files.map { original ->
            val backup = File(original.parent, "${original.name}.before_encrypt_$timestamp.bak")
            original.copyTo(backup, overwrite = true)
            original to backup
        }
    }

    private fun restoreDatabaseArtifacts(mappings: List<Pair<File, File>>): Int {
        var restored = 0
        mappings.forEach { (original, backup) ->
            if (backup.exists()) {
                backup.copyTo(original, overwrite = true)
                restored++
            }
        }
        logger.i(
            "DatabasePassphraseSetupViewModel.restoreDatabaseArtifacts",
            "Restored database artifacts after encryption migration failure",
            mapOf("restoredFiles" to restored),
        )
        return restored
    }

    private fun cleanupBackupArtifacts(mappings: List<Pair<File, File>>) {
        mappings.forEach { (_, backup) ->
            if (backup.exists()) {
                backup.delete()
            }
        }
    }

    private fun deleteAllDatabaseFiles(): Int {
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        val files = listOf(
            dbFile,
            File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal"),
            File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-shm"),
            File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-journal"),
        )
        var deletedCount = 0
        files.forEach { file ->
            if (file.exists() && file.delete()) {
                deletedCount++
            }
        }
        logger.w(
            "DatabasePassphraseSetupViewModel.deleteAllDatabaseFiles",
            "Deleted local database artifacts during setup recovery",
            mapOf("deletedCount" to deletedCount),
        )
        return deletedCount
    }

    private fun loadDatabaseSummary() {
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
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
            return
        }
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
