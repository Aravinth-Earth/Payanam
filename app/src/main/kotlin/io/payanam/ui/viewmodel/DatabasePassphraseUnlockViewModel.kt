//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.ui.viewmodel

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.PayanamDatabase
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.database.session.DatabaseSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
/**
 * UI state for the unlock gate: unlock-in-progress flag, the classified error
 * reason code, live lockout countdown, and a summary of the local database
 * file (existence, size, last-modified, storage mode label).
 */
data class DatabasePassphraseUnlockUiState(
    val isUnlocking: Boolean = false,
    val errorReasonCode: String? = null,
    val lockoutSecondsRemaining: Long = 0L,
    val hasLocalDatabase: Boolean = false,
    val databaseSizeKb: Long = 0L,
    val databaseLastModifiedMs: Long = 0L,
    val storageModeLabelKey: String = "encrypted",
)

internal fun classifyDatabaseOpenFailureReason(error: Throwable?): String {
    val message = error?.message.orEmpty()
    return when {
        message.contains("newer than app supports", ignoreCase = true) -> "db_too_new"
        message.contains("too old", ignoreCase = true) -> "db_too_old"
        message.contains("Missing tables:", ignoreCase = true) ||
            message.contains("Schema issues:", ignoreCase = true) ||
            message.contains("Migration didn't properly handle", ignoreCase = true) -> "schema_invalid"
        message.contains("primary DB file missing", ignoreCase = true) -> "storage_incomplete"
        message.contains("unsupported in-place migration", ignoreCase = true) -> "migration_required"
        else -> "open_failed"
    }
}

/**
 * Unlock-gate ViewModel: verifies the passphrase (with lockout enforcement),
 * opens the encrypted DB session, supports Keystore-backed biometric unlock,
 * and offers the destructive forgot-passphrase reset.
 */
@HiltViewModel
class DatabasePassphraseUnlockViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: DatabaseEncryptionManager,
    private val sessionManager: DatabaseSessionManager,
) : ViewModel() {
    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(DatabasePassphraseUnlockUiState())
    val uiState: StateFlow<DatabasePassphraseUnlockUiState> = _uiState.asStateFlow()

    init {
        logger.i("DatabasePassphraseUnlockViewModel.init", "Unlock ViewModel initialized")
        refreshLockoutState()
        loadDatabaseSummary()
    }
    /**
     * Re-reads the lockout countdown from the encryption manager into state
     * (called on screen focus).
     */
    fun refreshLockoutState() {
        val remaining = encryptionManager.getUnlockRemainingSeconds()
        logger.d(
            "DatabasePassphraseUnlockViewModel.refreshLockoutState",
            "Refreshed lockout countdown",
            mapOf("remainingSeconds" to remaining),
        )
        _uiState.update { it.copy(lockoutSecondsRemaining = remaining) }
    }
    /**
     * Verifies [passphrase] and opens the encrypted DB session on success;
     * wrong attempts feed the lockout policy and failures surface as reason
     * codes in state.
     */
    fun unlock(passphrase: String, onSuccess: () -> Unit) {
        logger.i(
            "DatabasePassphraseUnlockViewModel.unlock",
            "Passphrase unlock requested",
            mapOf(
                "passphraseBlank" to passphrase.isBlank(),
                "passphraseLength" to passphrase.length,
            ),
        )
        if (passphrase.isBlank()) {
            logger.w("DatabasePassphraseUnlockViewModel.unlock", "Unlock blocked: blank passphrase")
            _uiState.update {
                it.copy(
                    isUnlocking = false,
                    errorReasonCode = "invalid",
                    lockoutSecondsRemaining = 0L,
                )
            }
            return
        }
        val remaining = encryptionManager.getUnlockRemainingSeconds()
        if (remaining > 0) {
            logger.w(
                "DatabasePassphraseUnlockViewModel.unlock",
                "Unlock blocked due to lockout",
                mapOf("lockoutSecondsRemaining" to remaining),
            )
            _uiState.update {
                it.copy(
                    errorReasonCode = "locked",
                    lockoutSecondsRemaining = remaining,
                )
            }
            startLockoutTicker()
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUnlocking = true, errorReasonCode = null) }
            val valid = withContext(Dispatchers.IO) {
                encryptionManager.verifyPassphrase(passphrase)
            }
            logger.i(
                "DatabasePassphraseUnlockViewModel.unlock",
                "Passphrase verification finished",
                mapOf("valid" to valid),
            )
            if (valid) {
                val openResult = sessionManager.openDatabase(passphrase)
                if (openResult.isFailure) {
                    val reasonCode = classifyDatabaseOpenFailureReason(openResult.exceptionOrNull())
                    logger.e(
                        "DatabasePassphraseUnlockViewModel.unlock",
                        "DB open failed after passphrase verified",
                        openResult.exceptionOrNull(),
                        mapOf("reasonCode" to reasonCode),
                    )
                    _uiState.update {
                        it.copy(
                            isUnlocking = false,
                            errorReasonCode = reasonCode,
                            lockoutSecondsRemaining = 0L,
                        )
                    }
                    return@launch
                }
                encryptionManager.resetUnlockAttempts()
                logger.i("DatabasePassphraseUnlockViewModel.unlock", "Passphrase unlock successful; DB session opened")
                _uiState.update {
                    it.copy(
                        isUnlocking = false,
                        errorReasonCode = null,
                        lockoutSecondsRemaining = 0L,
                    )
                }
                onSuccess()
            } else {
                val lockoutSeconds = encryptionManager.recordFailedUnlockAttempt()
                logger.w(
                    "DatabasePassphraseUnlockViewModel.unlock",
                    "Passphrase unlock failed",
                    mapOf("lockoutSeconds" to lockoutSeconds),
                )
                _uiState.update {
                    it.copy(
                        isUnlocking = false,
                        errorReasonCode = if (lockoutSeconds > 0) "locked" else "invalid",
                        lockoutSecondsRemaining = lockoutSeconds,
                    )
                }
                if (lockoutSeconds > 0) {
                    startLockoutTicker()
                }
            }
        }
    }

    /**
     * Starts a biometric prompt backed by a Keystore CryptoObject. On success, the OS-
     * authenticated cipher is used to unwrap the passphrase and open the DB session.
     */
    fun startBiometricUnlock(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
    ) {
        val biometricEnabled = encryptionManager.isBiometricUnlockEnabled()
        logger.i(
            "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
            "Biometric unlock requested",
            mapOf("biometricEnabledPreference" to biometricEnabled),
        )
        if (!biometricEnabled) {
            logger.w(
                "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                "Biometric unlock blocked by user preference",
            )
            _uiState.update {
                it.copy(
                    isUnlocking = false,
                    errorReasonCode = "biometric_unavailable",
                    lockoutSecondsRemaining = 0L,
                )
            }
            return
        }
        val biometricManager = BiometricManager.from(context)
        val canAuth = biometricManager.canAuthenticate(BIOMETRIC_STRONG)
        logger.i(
            "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
            "Biometric capability checked",
            mapOf("canAuthenticateResult" to canAuth),
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            logger.w(
                "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                "Biometric not available",
                mapOf("canAuthResult" to canAuth),
            )
            _uiState.update {
                it.copy(
                    isUnlocking = false,
                    errorReasonCode = "biometric_unavailable",
                    lockoutSecondsRemaining = 0L,
                )
            }
            return
        }
        val cipher = runCatching { encryptionManager.getCipherForBiometricUnlock() }.getOrElse { error ->
            logger.e(
                "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                "Failed to initialize cipher for biometric unlock",
                error,
            )
            _uiState.update {
                it.copy(
                    isUnlocking = false,
                    errorReasonCode = "key_invalidated",
                    lockoutSecondsRemaining = 0L,
                )
            }
            return
        }

        _uiState.update { it.copy(isUnlocking = true, errorReasonCode = null) }
        val executor = ContextCompat.getMainExecutor(context)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            /**
             * Biometric success: unwraps the stored passphrase with the OS-
             * authenticated cipher and opens the DB session.
             */
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val authenticatedCipher = result.cryptoObject?.cipher
                if (authenticatedCipher == null) {
                    logger.e(
                        "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                        "Biometric succeeded but no cipher in CryptoObject",
                    )
                    _uiState.update {
                        it.copy(isUnlocking = false, errorReasonCode = "biometric_error")
                    }
                    return
                }
                viewModelScope.launch {
                    val passphrase = runCatching {
                        withContext(Dispatchers.IO) {
                            encryptionManager.unwrapPassphraseWithCipher(authenticatedCipher)
                        }
                    }.getOrElse { error ->
                        logger.e(
                            "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                            "Failed to unwrap passphrase after biometric success",
                            error,
                        )
                        _uiState.update {
                            it.copy(isUnlocking = false, errorReasonCode = "key_invalidated")
                        }
                        return@launch
                    }
                    val openResult = sessionManager.openDatabase(passphrase)
                    if (openResult.isFailure) {
                        val reasonCode = classifyDatabaseOpenFailureReason(openResult.exceptionOrNull())
                        logger.e(
                            "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                            "DB open failed after biometric auth",
                            openResult.exceptionOrNull(),
                            mapOf("reasonCode" to reasonCode),
                        )
                        _uiState.update {
                            it.copy(isUnlocking = false, errorReasonCode = reasonCode)
                        }
                        return@launch
                    }
                    encryptionManager.resetUnlockAttempts()
                    logger.i(
                        "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                        "Biometric unlock successful; DB session opened",
                    )
                    _uiState.update {
                        it.copy(isUnlocking = false, errorReasonCode = null, lockoutSecondsRemaining = 0L)
                    }
                    onSuccess()
                }
            }

            /**
             * Hard prompt error (negative button, timeout, or sensor lockout):
             * surfaces a biometric-specific reason code.
             */
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                logger.w(
                    "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                    "Biometric auth error",
                    mapOf("errorCode" to errorCode, "errString" to errString.toString()),
                )
                val reasonCode = if (errorCode == BiometricPrompt.ERROR_LOCKOUT ||
                    errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT
                ) {
                    "biometric_lockout"
                } else {
                    "biometric_error"
                }
                _uiState.update {
                    it.copy(isUnlocking = false, errorReasonCode = reasonCode)
                }
            }

            /**
             * A single unrecognized attempt; the prompt stays open (the system
             * shows its own retry feedback).
             */
            override fun onAuthenticationFailed() {
                logger.w(
                    "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                    "Biometric attempt failed (finger not recognized)",
                )
                // Don't update errorReasonCode here — the system shows its own feedback
            }
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(io.payanam.R.string.db_passphrase_unlock_biometric_title))
            .setSubtitle(context.getString(io.payanam.R.string.db_passphrase_unlock_biometric_subtitle))
            .setNegativeButtonText(context.getString(io.payanam.R.string.db_passphrase_unlock_biometric_negative))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()

        logger.i(
            "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
            "Launching biometric prompt",
        )
        BiometricPrompt(activity, executor, callback).authenticate(
            promptInfo,
            BiometricPrompt.CryptoObject(cipher),
        )
    }
    /**
     * Whether the user has biometric unlock turned on (drives UI affordances).
     */
    fun isBiometricUnlockEnabled(): Boolean = encryptionManager.isBiometricUnlockEnabled()
    /**
     * Destructive recovery path: wipes all database artifacts and resets
     * encryption state so the user can start over with a fresh setup.
     */
    fun forgotPassphraseReset(onSuccess: () -> Unit) {
        logger.w("DatabasePassphraseUnlockViewModel.forgotPassphraseReset", "Forgot-passphrase reset requested")
        viewModelScope.launch {
            _uiState.update { it.copy(isUnlocking = true, errorReasonCode = null) }
            val resetOk = withContext(Dispatchers.IO) {
                val deleted = deleteAllDatabaseFiles()
                val encryptionReset = encryptionManager.resetEncryptionState()
                logger.w(
                    "DatabasePassphraseUnlockViewModel.forgotPassphraseReset",
                    "Forgot-passphrase reset executed",
                    mapOf("databaseFilesDeleted" to deleted, "encryptionStateReset" to encryptionReset),
                )
                encryptionReset
            }
            _uiState.update {
                it.copy(
                    isUnlocking = false,
                    errorReasonCode = if (resetOk) null else "reset_failed",
                    lockoutSecondsRemaining = 0L,
                )
            }
            logger.i(
                "DatabasePassphraseUnlockViewModel.forgotPassphraseReset",
                "Forgot-passphrase reset finished",
                mapOf("resetOk" to resetOk),
            )
            if (resetOk) {
                onSuccess()
            }
        }
    }

    private fun startLockoutTicker() {
        logger.i("DatabasePassphraseUnlockViewModel.startLockoutTicker", "Starting lockout ticker")
        viewModelScope.launch {
            while (true) {
                val remaining = encryptionManager.getUnlockRemainingSeconds()
                _uiState.update { it.copy(lockoutSecondsRemaining = remaining) }
                if (remaining <= 0) {
                    if (_uiState.value.errorReasonCode == "locked") {
                        _uiState.update { it.copy(errorReasonCode = null) }
                    }
                    logger.i("DatabasePassphraseUnlockViewModel.startLockoutTicker", "Lockout ticker finished")
                    break
                }
                delay(1000L)
            }
        }
    }

    private fun deleteAllDatabaseFiles(): Int {
        var deletedCount = 0
        getDatabaseArtifactFiles().forEach { file ->
            if (file.exists()) {
                val deleted = file.delete()
                if (deleted) {
                    deletedCount++
                }
                logger.i(
                    "DatabasePassphraseUnlockViewModel.deleteAllDatabaseFiles",
                    "Delete artifact attempt",
                    mapOf("name" to file.name, "deleted" to deleted),
                )
            }
        }
        logger.i(
            "DatabasePassphraseUnlockViewModel.deleteAllDatabaseFiles",
            "Delete-all completed",
            mapOf("deletedCount" to deletedCount),
        )
        return deletedCount
    }

    private fun loadDatabaseSummary() {
        logger.i("DatabasePassphraseUnlockViewModel.loadDatabaseSummary", "Loading local database summary")
        viewModelScope.launch(Dispatchers.IO) {
            val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
            if (!dbFile.exists()) {
                _uiState.update {
                    it.copy(
                        hasLocalDatabase = false,
                        databaseSizeKb = 0L,
                        databaseLastModifiedMs = 0L,
                    )
                }
                logger.w(
                    "DatabasePassphraseUnlockViewModel.loadDatabaseSummary",
                    "Local database file not found",
                    mapOf("path" to dbFile.absolutePath),
                )
                return@launch
            }
            _uiState.update {
                it.copy(
                    hasLocalDatabase = true,
                    databaseSizeKb = dbFile.length() / 1024,
                    databaseLastModifiedMs = dbFile.lastModified(),
                    storageModeLabelKey = if (encryptionManager.isEncryptionEnabled()) "encrypted" else "plaintext",
                )
            }
            logger.i(
                "DatabasePassphraseUnlockViewModel.loadDatabaseSummary",
                "Local database summary loaded",
                mapOf(
                    "path" to dbFile.absolutePath,
                    "sizeKB" to (dbFile.length() / 1024),
                    "lastModified" to dbFile.lastModified(),
                ),
            )
        }
    }

    private fun getDatabaseArtifactFiles(): List<File> {
        val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
        return listOf(
            dbFile,
            File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-wal"),
            File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-shm"),
            File(dbFile.parent, "${PayanamDatabase.DATABASE_NAME}-journal"),
        )
    }
}
