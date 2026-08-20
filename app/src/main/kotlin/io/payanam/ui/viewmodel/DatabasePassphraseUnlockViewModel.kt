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
 * DatabasePassphraseUnlockUiState.
 */
data class DatabasePassphraseUnlockUiState(
    /** Is unlocking. */
    val isUnlocking: Boolean = false,
    /** Error reason code. */
    val errorReasonCode: String? = null,
    /** Lockout seconds remaining. */
    val lockoutSecondsRemaining: Long = 0L,
    /** Has local database. */
    val hasLocalDatabase: Boolean = false,
    /** Database size kb. */
    val databaseSizeKb: Long = 0L,
    /** Database last modified ms. */
    val databaseLastModifiedMs: Long = 0L,
    /** Storage mode label key. */
    val storageModeLabelKey: String = "encrypted",
)

internal fun classifyDatabaseOpenFailureReason(error: Throwable?): String {
    /** Message. */
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

@HiltViewModel
/**
 * DatabasePassphraseUnlockViewModel.
 */
class DatabasePassphraseUnlockViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encryptionManager: DatabaseEncryptionManager,
    private val sessionManager: DatabaseSessionManager,
) : ViewModel() {
    private val logger = UnifiedLogger.getInstance()
    private val _uiState = MutableStateFlow(DatabasePassphraseUnlockUiState())
    /** Ui state. */
    val uiState: StateFlow<DatabasePassphraseUnlockUiState> = _uiState.asStateFlow()

    init {
        logger.i("DatabasePassphraseUnlockViewModel.init", "Unlock ViewModel initialized")
        /** Refresh lockout state. */
        refreshLockoutState()
        /** Load database summary. */
        loadDatabaseSummary()
    }

    /**
     * Refresh lockout state.
     */
    fun refreshLockoutState() {
        /** Remaining. */
        val remaining = encryptionManager.getUnlockRemainingSeconds()
        logger.d(
            "DatabasePassphraseUnlockViewModel.refreshLockoutState",
            "Refreshed lockout countdown",
            /** Map of. */
            mapOf("remainingSeconds" to remaining),
        )
        _uiState.update { it.copy(lockoutSecondsRemaining = remaining) }
    }

    /**
     * Unlock.
     */
    fun unlock(passphrase: String, onSuccess: () -> Unit) {
        logger.i(
            "DatabasePassphraseUnlockViewModel.unlock",
            "Passphrase unlock requested",
            /** Map of. */
            mapOf(
                "passphraseBlank" to passphrase.isBlank(),
                "passphraseLength" to passphrase.length,
            ),
        )
        /** If. */
        if (passphrase.isBlank()) {
            logger.w("DatabasePassphraseUnlockViewModel.unlock", "Unlock blocked: blank passphrase")
            _uiState.update {
                it.copy(
                    isUnlocking = false,
                    errorReasonCode = "invalid",
                    lockoutSecondsRemaining = 0L,
                )
            }
            /** Return. */
            return
        }
        /** Remaining. */
        val remaining = encryptionManager.getUnlockRemainingSeconds()
        /** If. */
        if (remaining > 0) {
            logger.w(
                "DatabasePassphraseUnlockViewModel.unlock",
                "Unlock blocked due to lockout",
                /** Map of. */
                mapOf("lockoutSecondsRemaining" to remaining),
            )
            _uiState.update {
                it.copy(
                    errorReasonCode = "locked",
                    lockoutSecondsRemaining = remaining,
                )
            }
            /** Start lockout ticker. */
            startLockoutTicker()
            /** Return. */
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isUnlocking = true, errorReasonCode = null) }
            /** Valid. */
            val valid = withContext(Dispatchers.IO) {
                encryptionManager.verifyPassphrase(passphrase)
            }
            logger.i(
                "DatabasePassphraseUnlockViewModel.unlock",
                "Passphrase verification finished",
                /** Map of. */
                mapOf("valid" to valid),
            )
            /** If. */
            if (valid) {
                /** Open result. */
                val openResult = sessionManager.openDatabase(passphrase)
                /** If. */
                if (openResult.isFailure) {
                    /** Reason code. */
                    val reasonCode = classifyDatabaseOpenFailureReason(openResult.exceptionOrNull())
                    logger.e(
                        "DatabasePassphraseUnlockViewModel.unlock",
                        "DB open failed after passphrase verified",
                        openResult.exceptionOrNull(),
                        /** Map of. */
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
                /** On success. */
                onSuccess()
            } else {
                /** Lockout seconds. */
                val lockoutSeconds = encryptionManager.recordFailedUnlockAttempt()
                logger.w(
                    "DatabasePassphraseUnlockViewModel.unlock",
                    "Passphrase unlock failed",
                    /** Map of. */
                    mapOf("lockoutSeconds" to lockoutSeconds),
                )
                _uiState.update {
                    it.copy(
                        isUnlocking = false,
                        errorReasonCode = if (lockoutSeconds > 0) "locked" else "invalid",
                        lockoutSecondsRemaining = lockoutSeconds,
                    )
                }
                /** If. */
                if (lockoutSeconds > 0) {
                    /** Start lockout ticker. */
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
        /** Activity. */
        activity: FragmentActivity,
        onSuccess: () -> Unit,
    ) {
        /** Biometric enabled. */
        val biometricEnabled = encryptionManager.isBiometricUnlockEnabled()
        logger.i(
            "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
            "Biometric unlock requested",
            /** Map of. */
            mapOf("biometricEnabledPreference" to biometricEnabled),
        )
        /** If. */
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
            /** Return. */
            return
        }

        /** Biometric manager. */
        val biometricManager = BiometricManager.from(context)
        /** Can auth. */
        val canAuth = biometricManager.canAuthenticate(BIOMETRIC_STRONG)
        logger.i(
            "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
            "Biometric capability checked",
            /** Map of. */
            mapOf("canAuthenticateResult" to canAuth),
        )
        /** If. */
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            logger.w(
                "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                "Biometric not available",
                /** Map of. */
                mapOf("canAuthResult" to canAuth),
            )
            _uiState.update {
                it.copy(
                    isUnlocking = false,
                    errorReasonCode = "biometric_unavailable",
                    lockoutSecondsRemaining = 0L,
                )
            }
            /** Return. */
            return
        }

        /** Cipher. */
        val cipher = runCatching { encryptionManager.getCipherForBiometricUnlock() }.getOrElse { error ->
            logger.e(
                "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                "Failed to initialize cipher for biometric unlock",
                /** Error. */
                error,
            )
            _uiState.update {
                it.copy(
                    isUnlocking = false,
                    errorReasonCode = "key_invalidated",
                    lockoutSecondsRemaining = 0L,
                )
            }
            /** Return. */
            return
        }

        _uiState.update { it.copy(isUnlocking = true, errorReasonCode = null) }

        /** Executor. */
        val executor = ContextCompat.getMainExecutor(context)
        /** Callback. */
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                /** Authenticated cipher. */
                val authenticatedCipher = result.cryptoObject?.cipher
                /** If. */
                if (authenticatedCipher == null) {
                    logger.e(
                        "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                        "Biometric succeeded but no cipher in CryptoObject",
                    )
                    _uiState.update {
                        it.copy(isUnlocking = false, errorReasonCode = "biometric_error")
                    }
                    /** Return. */
                    return
                }
                viewModelScope.launch {
                    /** Passphrase. */
                    val passphrase = runCatching {
                        /** With context. */
                        withContext(Dispatchers.IO) {
                            encryptionManager.unwrapPassphraseWithCipher(authenticatedCipher)
                        }
                    }.getOrElse { error ->
                        logger.e(
                            "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                            "Failed to unwrap passphrase after biometric success",
                            /** Error. */
                            error,
                        )
                        _uiState.update {
                            it.copy(isUnlocking = false, errorReasonCode = "key_invalidated")
                        }
                        return@launch
                    }
                    /** Open result. */
                    val openResult = sessionManager.openDatabase(passphrase)
                    /** If. */
                    if (openResult.isFailure) {
                        /** Reason code. */
                        val reasonCode = classifyDatabaseOpenFailureReason(openResult.exceptionOrNull())
                        logger.e(
                            "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                            "DB open failed after biometric auth",
                            openResult.exceptionOrNull(),
                            /** Map of. */
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
                    /** On success. */
                    onSuccess()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                logger.w(
                    "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                    "Biometric auth error",
                    /** Map of. */
                    mapOf("errorCode" to errorCode, "errString" to errString.toString()),
                )
                /** Reason code. */
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

            override fun onAuthenticationFailed() {
                logger.w(
                    "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                    "Biometric attempt failed (finger not recognized)",
                )
                // Don't update errorReasonCode here — the system shows its own feedback
            }
        }

        /** Prompt info. */
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
        /** Biometric prompt. */
        BiometricPrompt(activity, executor, callback).authenticate(
            /** Prompt info. */
            promptInfo,
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    /**
     * Is biometric unlock enabled.
     */
    fun isBiometricUnlockEnabled(): Boolean = encryptionManager.isBiometricUnlockEnabled()

    /**
     * Forgot passphrase reset.
     */
    fun forgotPassphraseReset(onSuccess: () -> Unit) {
        logger.w("DatabasePassphraseUnlockViewModel.forgotPassphraseReset", "Forgot-passphrase reset requested")
        viewModelScope.launch {
            _uiState.update { it.copy(isUnlocking = true, errorReasonCode = null) }
            /** Reset ok. */
            val resetOk = withContext(Dispatchers.IO) {
                /** Deleted. */
                val deleted = deleteAllDatabaseFiles()
                /** Encryption reset. */
                val encryptionReset = encryptionManager.resetEncryptionState()
                logger.w(
                    "DatabasePassphraseUnlockViewModel.forgotPassphraseReset",
                    "Forgot-passphrase reset executed",
                    /** Map of. */
                    mapOf("databaseFilesDeleted" to deleted, "encryptionStateReset" to encryptionReset),
                )
                /** Encryption reset. */
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
                /** Map of. */
                mapOf("resetOk" to resetOk),
            )
            /** If. */
            if (resetOk) {
                /** On success. */
                onSuccess()
            }
        }
    }

    private fun startLockoutTicker() {
        logger.i("DatabasePassphraseUnlockViewModel.startLockoutTicker", "Starting lockout ticker")
        viewModelScope.launch {
            /** While. */
            while (true) {
                /** Remaining. */
                val remaining = encryptionManager.getUnlockRemainingSeconds()
                _uiState.update { it.copy(lockoutSecondsRemaining = remaining) }
                /** If. */
                if (remaining <= 0) {
                    /** If. */
                    if (_uiState.value.errorReasonCode == "locked") {
                        _uiState.update { it.copy(errorReasonCode = null) }
                    }
                    logger.i("DatabasePassphraseUnlockViewModel.startLockoutTicker", "Lockout ticker finished")
                    /** Break. */
                    break
                }
                /** Delay. */
                delay(1000L)
            }
        }
    }

    private fun deleteAllDatabaseFiles(): Int {
        /** Deleted count. */
        var deletedCount = 0
        /** Get database artifact files. */
        getDatabaseArtifactFiles().forEach { file ->
            /** If. */
            if (file.exists()) {
                /** Deleted. */
                val deleted = file.delete()
                /** If. */
                if (deleted) {
                    deletedCount++
                }
                logger.i(
                    "DatabasePassphraseUnlockViewModel.deleteAllDatabaseFiles",
                    "Delete artifact attempt",
                    /** Map of. */
                    mapOf("name" to file.name, "deleted" to deleted),
                )
            }
        }
        logger.i(
            "DatabasePassphraseUnlockViewModel.deleteAllDatabaseFiles",
            "Delete-all completed",
            /** Map of. */
            mapOf("deletedCount" to deletedCount),
        )
        return deletedCount
    }

    private fun loadDatabaseSummary() {
        logger.i("DatabasePassphraseUnlockViewModel.loadDatabaseSummary", "Loading local database summary")
        viewModelScope.launch(Dispatchers.IO) {
            /** Db file. */
            val dbFile = context.getDatabasePath(PayanamDatabase.DATABASE_NAME)
            /** If. */
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
                    /** Map of. */
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
                /** Map of. */
                mapOf(
                    "path" to dbFile.absolutePath,
                    "sizeKB" to (dbFile.length() / 1024),
                    "lastModified" to dbFile.lastModified(),
                ),
            )
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
