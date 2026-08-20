//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.CrashSafeBreadcrumbs
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import io.payanam.database.security.PassphrasePolicy
import io.payanam.database.session.DatabaseSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * DatabasePassphraseChangeUiState.
 */
data class DatabasePassphraseChangeUiState(
    /** Is saving. */
    val isSaving: Boolean = false,
    /** Error reason code. */
    val errorReasonCode: String? = null,
    /** Is success. */
    val isSuccess: Boolean = false,
)

@HiltViewModel
/**
 * DatabasePassphraseChangeViewModel.
 */
class DatabasePassphraseChangeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: DatabaseEncryptionManager,
    private val sessionManager: DatabaseSessionManager,
) : ViewModel() {
    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(DatabasePassphraseChangeUiState())
    /** Ui state. */
    val uiState: StateFlow<DatabasePassphraseChangeUiState> = _uiState.asStateFlow()

    /**
     * Submit.
     */
    fun submit(currentPassphrase: String, newPassphrase: String, confirmPassphrase: String) {
        /** Validation. */
        val validation = PassphrasePolicy.validate(newPassphrase)
        /** If. */
        if (!validation.isValid) {
            _uiState.update { it.copy(errorReasonCode = validation.reasonCode ?: "generic") }
            /** Return. */
            return
        }
        /** If. */
        if (newPassphrase != confirmPassphrase) {
            _uiState.update { it.copy(errorReasonCode = "mismatch") }
            /** Return. */
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorReasonCode = null, isSuccess = false) }
            /** Error code. */
            val errorCode = changePassphraseWithRollback(currentPassphrase, newPassphrase)
            /** If. */
            if (errorCode == null) {
                // Success: UI shows success state briefly, then process restarts for clean re-auth
                _uiState.update { it.copy(isSaving = false, errorReasonCode = null, isSuccess = true) }
                // Close and restart so cold boot prompts re-auth with the new passphrase
                sessionManager.closeDatabase()
                /** Restart process. */
                restartProcess()
            } else {
                _uiState.update { it.copy(isSaving = false, errorReasonCode = errorCode, isSuccess = false) }
            }
        }
    }

    private suspend fun changePassphraseWithRollback(currentPassphrase: String, newPassphrase: String): String? {
        /** Db file. */
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        /** If. */
        if (!dbFile.exists()) {
            logger.w("DatabasePassphraseChangeViewModel.submit", "Database file missing for passphrase update")
            return "generic"
        }
        /** If. */
        if (!encryptionManager.isEncryptionEnabled()) {
            logger.w("DatabasePassphraseChangeViewModel.submit", "Passphrase update skipped because encryption is disabled")
            return "generic"
        }

        /** If. */
        if (!withContext(Dispatchers.IO) { encryptionManager.verifyPassphrase(currentPassphrase) }) {
            logger.w("DatabasePassphraseChangeViewModel.submit", "Current passphrase verification failed")
            return "current_invalid"
        }

        /** Backups. */
        val backups = withContext(Dispatchers.IO) { backupDatabaseArtifacts() }
        // Close Room before rekeying the file on disk
        sessionManager.closeDatabase()
        return try {
            /** With context. */
            withContext(Dispatchers.IO) {
                DatabaseEncryptionMigrationSupport.rekeyEncryptedDatabase(
                    context = context,
                    databaseFile = dbFile,
                    currentPassphrase = currentPassphrase,
                    newPassphrase = newPassphrase,
                    logTag = "DatabasePassphraseChangeViewModel.submit",
                )
                /** Manager updated. */
                val managerUpdated = encryptionManager.updatePassphrase(
                    currentPassphrase = currentPassphrase,
                    newPassphrase = newPassphrase,
                )
                /** If. */
                if (!managerUpdated) {
                    throw IllegalStateException("Failed to persist new passphrase metadata")
                }
                /** Cleanup backup artifacts. */
                cleanupBackupArtifacts(backups)
            }
            logger.i("DatabasePassphraseChangeViewModel.submit", "Passphrase changed successfully")
            /** Null. */
            null
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") error: Exception) {
            logger.e("DatabasePassphraseChangeViewModel.submit", "Passphrase change failed, restoring backup", error)
            /** With context. */
            withContext(Dispatchers.IO) {
                /** Restore database artifacts. */
                restoreDatabaseArtifacts(backups)
                /** Cleanup backup artifacts. */
                cleanupBackupArtifacts(backups)
            }
            // Re-open Room with original passphrase so the app is usable again
            sessionManager.openDatabase(currentPassphrase)
            "generic"
        }
    }

    private fun restartProcess() {
        CrashSafeBreadcrumbs.record(
            context = context,
            source = "DatabasePassphraseChangeViewModel.restartProcess",
            stage = "kill_process_for_passphrase_change",
        )
        logger.flush()
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun backupDatabaseArtifacts(): List<Pair<File, File>> {
        /** Timestamp. */
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        return getDatabaseArtifactFiles()
            .filter { it.exists() }
            .map { original ->
                /** Backup. */
                val backup = File(original.parent, "${original.name}.before_passphrase_change_$timestamp.bak")
                original.copyTo(backup, overwrite = true)
                original to backup
            }
    }

    private fun restoreDatabaseArtifacts(mappings: List<Pair<File, File>>) {
        mappings.forEach { (original, backup) ->
            /** If. */
            if (backup.exists()) {
                backup.copyTo(original, overwrite = true)
            }
        }
    }

    private fun cleanupBackupArtifacts(mappings: List<Pair<File, File>>) {
        mappings.forEach { (_, backup) ->
            /** If. */
            if (backup.exists()) {
                backup.delete()
            }
        }
    }

    private fun getDatabaseArtifactFiles(): List<File> {
        /** Db file. */
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        return listOf(
            /** Db file. */
            dbFile,
            /** File. */
            File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal"),
            /** File. */
            File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-shm"),
            /** File. */
            File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-journal"),
        )
    }
}
