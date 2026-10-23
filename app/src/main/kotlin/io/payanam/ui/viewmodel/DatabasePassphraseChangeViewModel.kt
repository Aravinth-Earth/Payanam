//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.security.DatabaseEncryptionMigrationSupport
import io.payanam.database.security.PassphrasePolicy
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.port.ProcessRestart
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.PayanamDatabaseSchema
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
 * UI state for the passphrase-change screen: saving flag, error reason code,
 * and success flag (triggers the process restart).
 */
data class DatabasePassphraseChangeUiState(
    val isSaving: Boolean = false,
    val errorReasonCode: String? = null,
    val isSuccess: Boolean = false,
)

/**
 * Passphrase-change ViewModel: validates the new passphrase, rekeys the
 * encrypted database with backup/rollback safety, then restarts the process
 * for a clean re-auth.
 */
@HiltViewModel
class DatabasePassphraseChangeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: DatabaseEncryptionManager,
    private val sessionManager: DatabaseSessionManager,
    // The process-end sequence, as a port. On Android this call does not return; the four calls it makes
    // are the ones this class used to make inline (see AndroidProcessRestartPort), unchanged.
    private val processRestart: ProcessRestart,
) : ViewModel() {
    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(DatabasePassphraseChangeUiState())
    val uiState: StateFlow<DatabasePassphraseChangeUiState> = _uiState.asStateFlow()

    /**
     * Validates and applies a passphrase change: policy + confirm checks,
     * verified current passphrase, artifact backup, rekey, rollback on any
     * failure, then process restart on success.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: passphrase change + backup restore pipeline
    fun submit(currentPassphrase: String, newPassphrase: String, confirmPassphrase: String) {
        val validation = PassphrasePolicy.validate(newPassphrase)
        if (!validation.isValid) {
            _uiState.update { it.copy(errorReasonCode = validation.reasonCode ?: "generic") }
            return
        }
        if (newPassphrase != confirmPassphrase) {
            _uiState.update { it.copy(errorReasonCode = "mismatch") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorReasonCode = null, isSuccess = false) }
            val errorCode = changePassphraseWithRollback(currentPassphrase, newPassphrase)
            if (errorCode == null) {
                // Success: UI shows success state briefly, then process restarts for clean re-auth
                _uiState.update { it.copy(isSaving = false, errorReasonCode = null, isSuccess = true) }
                // Close and restart so cold boot prompts re-auth with the new passphrase
                sessionManager.closeDatabase()
                restartProcess()
            } else {
                _uiState.update { it.copy(isSaving = false, errorReasonCode = errorCode, isSuccess = false) }
            }
        }
    }

    private suspend fun changePassphraseWithRollback(currentPassphrase: String, newPassphrase: String): String? {
        val dbFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
        if (!dbFile.exists()) {
            logger.w("DatabasePassphraseChangeViewModel.submit", "Database file missing for passphrase update")
            return "generic"
        }
        if (!encryptionManager.isEncryptionEnabled()) {
            logger.w("DatabasePassphraseChangeViewModel.submit", "Passphrase update skipped because encryption is disabled")
            return "generic"
        }
        if (!withContext(Dispatchers.IO) { encryptionManager.verifyPassphrase(currentPassphrase) }) {
            logger.w("DatabasePassphraseChangeViewModel.submit", "Current passphrase verification failed")
            return "current_invalid"
        }
        val backups = withContext(Dispatchers.IO) { backupDatabaseArtifacts() }
        // Close Room before rekeying the file on disk
        sessionManager.closeDatabase()
        return try {
            withContext(Dispatchers.IO) {
                DatabaseEncryptionMigrationSupport.rekeyEncryptedDatabase(
                    context = context,
                    databaseFile = dbFile,
                    currentPassphrase = currentPassphrase,
                    newPassphrase = newPassphrase,
                    logTag = "DatabasePassphraseChangeViewModel.submit",
                )
                val managerUpdated = encryptionManager.updatePassphrase(
                    currentPassphrase = currentPassphrase,
                    newPassphrase = newPassphrase,
                )
                if (!managerUpdated) {
                    throw IllegalStateException("Failed to persist new passphrase metadata")
                }
                cleanupBackupArtifacts(backups)
            }
            logger.i("DatabasePassphraseChangeViewModel.submit", "Passphrase changed successfully")
            null
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            logger.e("DatabasePassphraseChangeViewModel.submit", "Passphrase change failed, restoring backup", error)
            withContext(Dispatchers.IO) {
                restoreDatabaseArtifacts(backups)
                cleanupBackupArtifacts(backups)
            }
            // Re-open Room with original passphrase so the app is usable again
            sessionManager.openDatabase(currentPassphrase)
            "generic"
        }
    }

    private fun restartProcess() {
        processRestart.restartProcess()
    }

    private fun backupDatabaseArtifacts(): List<Pair<File, File>> {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
        return getDatabaseArtifactFiles()
            .filter { it.exists() }
            .map { original ->
                val backup = File(original.parent, "${original.name}.before_passphrase_change_$timestamp.bak")
                original.copyTo(backup, overwrite = true)
                original to backup
            }
    }

    private fun restoreDatabaseArtifacts(mappings: List<Pair<File, File>>) {
        mappings.forEach { (original, backup) ->
            if (backup.exists()) {
                backup.copyTo(original, overwrite = true)
            }
        }
    }

    private fun cleanupBackupArtifacts(mappings: List<Pair<File, File>>) {
        mappings.forEach { (_, backup) ->
            if (backup.exists()) {
                backup.delete()
            }
        }
    }

    private fun getDatabaseArtifactFiles(): List<File> {
        val dbFile = context.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME)
        return DatabaseArtifacts.filesFor(dbFile)
    }
}
