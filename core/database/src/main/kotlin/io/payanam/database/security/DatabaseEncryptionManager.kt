//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("LargeClass", "MagicNumber")


package io.payanam.database.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.payanam.common.logging.CrashSafeBreadcrumbs
import io.payanam.common.logging.UnifiedLogger
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

@Suppress("TooManyFunctions")
/**
 * Provides the database encryption manager.
 */
class DatabaseEncryptionManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val logger = UnifiedLogger.getInstance()
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        logger.i(
            "DatabaseEncryptionManager.init",
            "Initializing encryption manager",
            mapOf(
                "mode" to (prefs.getString(KEY_MODE, MODE_PLAINTEXT) ?: MODE_PLAINTEXT),
                "hasVerifierHash" to !prefs.getString(KEY_VERIFIER_HASH, null).isNullOrBlank(),
                "hasVerifierSalt" to !prefs.getString(KEY_VERIFIER_SALT, null).isNullOrBlank(),
                "biometricPrefEnabled" to prefs.getBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false),
                "hasBiometricWrappedPassphrase" to hasBiometricWrappedPassphrase(),
            ),
        )
        sanitizeLegacySecurityState()
    }
    /**
     * Returns true when the has passphrase configured.
     */
    fun hasPassphraseConfigured(): Boolean =
        prefs.getString(KEY_MODE, MODE_PLAINTEXT) == MODE_ENCRYPTED &&
            !prefs.getString(KEY_VERIFIER_HASH, null).isNullOrBlank() &&
            !prefs.getString(KEY_VERIFIER_SALT, null).isNullOrBlank()
    /**
     * Returns true when the is encryption enabled.
     */
    fun isEncryptionEnabled(): Boolean = prefs.getString(KEY_MODE, MODE_PLAINTEXT) == MODE_ENCRYPTED
    /**
     * Returns the session timeout minutes.
     */
    fun getSessionTimeoutMinutes(): Int {
        val stored = prefs.getInt(KEY_SESSION_TIMEOUT_MINUTES, DEFAULT_SESSION_TIMEOUT_MINUTES)
        return stored.coerceIn(MIN_SESSION_TIMEOUT_MINUTES, MAX_SESSION_TIMEOUT_MINUTES)
    }
    /**
     * Updates the set session timeout minutes.
     */
    fun setSessionTimeoutMinutes(minutes: Int) {
        val normalized = minutes.coerceIn(MIN_SESSION_TIMEOUT_MINUTES, MAX_SESSION_TIMEOUT_MINUTES)
        prefs.edit().putInt(KEY_SESSION_TIMEOUT_MINUTES, normalized).apply()
        logger.i(
            "DatabaseEncryptionManager.setSessionTimeoutMinutes",
            "Updated passphrase session timeout",
            mapOf("sessionTimeoutMinutes" to normalized),
        )
    }
    /**
     * Returns true when the is biometric unlock enabled.
     */
    fun isBiometricUnlockEnabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false)
        return enabled && hasBiometricWrappedPassphrase()
    }
    /**
     * Updates the set biometric unlock enabled.
     */
    fun setBiometricUnlockEnabled(enabled: Boolean) {
        logger.i(
            "DatabaseEncryptionManager.setBiometricUnlockEnabled",
            "Biometric unlock preference update requested",
            mapOf(
                "enabled" to enabled,
                "hasWrappedPassphrase" to hasBiometricWrappedPassphrase(),
            ),
        )
        if (!enabled) {
            disableBiometricUnlock()
            return
        }
        if (!hasBiometricWrappedPassphrase()) {
            logger.w(
                "DatabaseEncryptionManager.setBiometricUnlockEnabled",
                "Ignoring biometric enable request because no biometric-wrapped passphrase exists",
            )
            return
        }
        prefs.edit().putBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, true).apply()
        logger.i(
            "DatabaseEncryptionManager.setBiometricUnlockEnabled",
            "Updated biometric unlock preference",
            mapOf("enabled" to true),
        )
    }
    /**
     * Performs the disable biometric unlock.
     */
    fun disableBiometricUnlock(): Boolean {
        logger.i(
            "DatabaseEncryptionManager.disableBiometricUnlock",
            "Disabling biometric unlock",
            mapOf(
                "biometricPrefEnabled" to prefs.getBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false),
                "hasWrappedPassphrase" to hasBiometricWrappedPassphrase(),
            ),
        )
        CrashSafeBreadcrumbs.record(
            context = appContext,
            source = "DatabaseEncryptionManager.disableBiometricUnlock",
            stage = "requested",
            data =
                mapOf(
                    "biometricPrefEnabled" to prefs.getBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false),
                    "hasWrappedPassphrase" to hasBiometricWrappedPassphrase(),
                ),
        )
        return runCatching {
            disableBiometricUnlockInternal()
            CrashSafeBreadcrumbs.record(
                context = appContext,
                source = "DatabaseEncryptionManager.disableBiometricUnlock",
                stage = "completed",
            )
            logger.i(
                "DatabaseEncryptionManager.disableBiometricUnlock",
                "Disabled biometric unlock and removed biometric material",
            )
            true
        }.getOrElse { error ->
            logger.e(
                "DatabaseEncryptionManager.disableBiometricUnlock",
                "Failed to disable biometric unlock cleanly",
                error,
            )
            CrashSafeBreadcrumbs.record(
                context = appContext,
                source = "DatabaseEncryptionManager.disableBiometricUnlock",
                stage = "failed",
                data = mapOf("error" to (error.message ?: "unknown")),
            )
            false
        }
    }
    /**
     * Performs the configure passphrase.
     */
    fun configurePassphrase(passphrase: String): Boolean {
        logger.i(
            "DatabaseEncryptionManager.configurePassphrase",
            "Configuring database passphrase",
            mapOf(
                "passphraseLength" to passphrase.length,
                "modeBefore" to (prefs.getString(KEY_MODE, MODE_PLAINTEXT) ?: MODE_PLAINTEXT),
            ),
        )
        CrashSafeBreadcrumbs.record(
            context = appContext,
            source = "DatabaseEncryptionManager.configurePassphrase",
            stage = "started",
            data =
                mapOf(
                    "passphraseLength" to passphrase.length,
                    "modeBefore" to (prefs.getString(KEY_MODE, MODE_PLAINTEXT) ?: MODE_PLAINTEXT),
                ),
        )
        return runCatching {
            persistVerifier(passphrase)
            CrashSafeBreadcrumbs.record(
                context = appContext,
                source = "DatabaseEncryptionManager.configurePassphrase",
                stage = "verifier_persisted",
            )
            disableBiometricUnlockInternal()
            CrashSafeBreadcrumbs.record(
                context = appContext,
                source = "DatabaseEncryptionManager.configurePassphrase",
                stage = "biometric_state_cleared",
            )
            check(
                prefs
                    .edit()
                    .putString(KEY_MODE, MODE_ENCRYPTED)
                    .putBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false)
                    .commit(),
            ) { "Failed to persist encryption mode" }
            CrashSafeBreadcrumbs.record(
                context = appContext,
                source = "DatabaseEncryptionManager.configurePassphrase",
                stage = "mode_persisted",
            )

            logger.i(
                "DatabaseEncryptionManager.configurePassphrase",
                "Database passphrase configured",
                mapOf("mode" to MODE_ENCRYPTED),
            )
            true
        }.getOrElse { error ->
            logger.e(
                "DatabaseEncryptionManager.configurePassphrase",
                "Failed to configure database passphrase",
                error,
            )
            CrashSafeBreadcrumbs.record(
                context = appContext,
                source = "DatabaseEncryptionManager.configurePassphrase",
                stage = "failed",
                data = mapOf("error" to (error.message ?: "unknown")),
            )
            false
        }
    }
    /**
     * Updates the update passphrase.
     */
    fun updatePassphrase(
        currentPassphrase: String,
        newPassphrase: String,
    ): Boolean {
        logger.i(
            "DatabaseEncryptionManager.updatePassphrase",
            "Updating database passphrase metadata",
            mapOf(
                "currentLength" to currentPassphrase.length,
                "newLength" to newPassphrase.length,
            ),
        )
        if (!verifyPassphrase(currentPassphrase)) {
            logger.w("DatabaseEncryptionManager.updatePassphrase", "Current passphrase verification failed")
            return false
        }
        return runCatching {
            persistVerifier(newPassphrase)
            disableBiometricUnlockInternal()
            logger.i(
                "DatabaseEncryptionManager.updatePassphrase",
                "Database passphrase metadata updated; biometric unlock disabled for re-enrollment",
            )
            true
        }.getOrElse { error ->
            logger.e("DatabaseEncryptionManager.updatePassphrase", "Failed to update passphrase metadata", error)
            false
        }
    }
    /**
     * Removes the reset encryption state.
     */
    fun resetEncryptionState(): Boolean {
        CrashSafeBreadcrumbs.record(
            context = appContext,
            source = "DatabaseEncryptionManager.resetEncryptionState",
            stage = "started",
        )
        return runCatching {
            check(prefs.edit().clear().commit()) { "Failed to clear encryption prefs" }
            deleteKeyAlias(BIOMETRIC_KEY_ALIAS)
            deleteKeyAlias(LEGACY_WRAP_KEY_ALIAS)
            logger.w("DatabaseEncryptionManager.resetEncryptionState", "Encryption state reset and keystore keys cleared")
            CrashSafeBreadcrumbs.record(
                context = appContext,
                source = "DatabaseEncryptionManager.resetEncryptionState",
                stage = "completed",
            )
            true
        }.getOrElse { error ->
            logger.e("DatabaseEncryptionManager.resetEncryptionState", "Failed to reset encryption state", error)
            CrashSafeBreadcrumbs.record(
                context = appContext,
                source = "DatabaseEncryptionManager.resetEncryptionState",
                stage = "failed",
                data = mapOf("error" to (error.message ?: "unknown")),
            )
            false
        }
    }
    /**
     * Performs the backup encryption prefs.
     */
    fun backupEncryptionPrefs(): Boolean {
        logger.i(
            "DatabaseEncryptionManager.backupEncryptionPrefs",
            "Backing up encryption preferences",
            mapOf(
                "stringKeys" to BACKUP_STRING_KEYS.size,
                "booleanKeys" to BACKUP_BOOLEAN_KEYS.size,
            ),
        )
        return runCatching {
            val editor = prefs.edit()
            BACKUP_STRING_KEYS.forEach { key ->
                val value = prefs.getString(key, null)
                if (value != null) editor.putString("$BACKUP_PREFIX$key", value) else editor.remove("$BACKUP_PREFIX$key")
            }
            BACKUP_BOOLEAN_KEYS.forEach { key ->
                val value = prefs.getBoolean(key, false)
                editor.putString("$BACKUP_PREFIX$key", value.toString())
            }
            check(editor.commit()) { "Failed to backup encryption prefs" }
            logger.i("DatabaseEncryptionManager.backupEncryptionPrefs", "Encryption prefs backed up")
            true
        }.getOrElse { error ->
            logger.e("DatabaseEncryptionManager.backupEncryptionPrefs", "Failed to backup encryption prefs", error)
            false
        }
    }
    /**
     * Loads the restore encryption prefs.
     */
    fun restoreEncryptionPrefs(): Boolean {
        logger.i(
            "DatabaseEncryptionManager.restoreEncryptionPrefs",
            "Restoring encryption preferences from backup",
        )
        return runCatching {
            val editor = prefs.edit()
            BACKUP_STRING_KEYS.forEach { key ->
                val backupKey = "$BACKUP_PREFIX$key"
                val value = prefs.getString(backupKey, null)
                if (value != null) editor.putString(key, value) else editor.remove(key)
                editor.remove(backupKey)
            }
            BACKUP_BOOLEAN_KEYS.forEach { key ->
                val backupKey = "$BACKUP_PREFIX$key"
                val value = prefs.getString(backupKey, null)
                if (value != null) editor.putBoolean(key, value.toBoolean()) else editor.remove(key)
                editor.remove(backupKey)
            }
            check(editor.commit()) { "Failed to restore encryption prefs" }
            sanitizeLegacySecurityState()
            logger.i("DatabaseEncryptionManager.restoreEncryptionPrefs", "Encryption prefs restored from backup")
            true
        }.getOrElse { error ->
            logger.e("DatabaseEncryptionManager.restoreEncryptionPrefs", "Failed to restore encryption prefs", error)
            false
        }
    }
    /**
     * Removes the clear encryption prefs backup.
     */
    fun clearEncryptionPrefsBackup() {
        val editor = prefs.edit()
        (BACKUP_STRING_KEYS + BACKUP_BOOLEAN_KEYS).forEach { key -> editor.remove("$BACKUP_PREFIX$key") }
        val committed = editor.commit()
        logger.i(
            "DatabaseEncryptionManager.clearEncryptionPrefsBackup",
            "Cleared encryption preference backup keys",
            mapOf(
                "committed" to committed,
                "totalBackupKeys" to (BACKUP_STRING_KEYS.size + BACKUP_BOOLEAN_KEYS.size),
            ),
        )
    }
    /**
     * Performs the verify passphrase.
     */
    fun verifyPassphrase(passphrase: String): Boolean {
        val salt = decodeOrNull(prefs.getString(KEY_VERIFIER_SALT, null))
        if (salt == null) {
            logger.w("DatabaseEncryptionManager.verifyPassphrase", "Passphrase verification blocked: missing verifier salt")
            return false
        }
        val expected = decodeOrNull(prefs.getString(KEY_VERIFIER_HASH, null))
        if (expected == null) {
            logger.w("DatabaseEncryptionManager.verifyPassphrase", "Passphrase verification blocked: missing verifier hash")
            return false
        }
        val actual = deriveVerifier(passphrase, salt)
        val valid = MessageDigest.isEqual(expected, actual)
        logger.i(
            "DatabaseEncryptionManager.verifyPassphrase",
            "Passphrase verification completed",
            mapOf("valid" to valid),
        )
        return valid
    }
    /**
     * Returns true when the has biometric wrapped passphrase.
     */
    fun hasBiometricWrappedPassphrase(): Boolean =
        !prefs.getString(KEY_BIOMETRIC_WRAPPED_PASSPHRASE, null).isNullOrBlank() &&
            !prefs.getString(KEY_BIOMETRIC_WRAPPED_IV, null).isNullOrBlank()
    /**
     * Returns the cipher for biometric enrollment.
     */
    fun getCipherForBiometricEnrollment(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateBiometricKey())
        logger.i(
            "DatabaseEncryptionManager.getCipherForBiometricEnrollment",
            "Initialized cipher for biometric enrollment",
        )
        return cipher
    }
    /**
     * Performs the store biometric wrapped passphrase with cipher.
     */
    fun storeBiometricWrappedPassphraseWithCipher(
        cipher: Cipher,
        passphrase: String,
    ): Boolean =
        runCatching {
            val passphraseBytes = passphrase.toByteArray(StandardCharsets.UTF_8)
            val cipherText = cipher.doFinal(passphraseBytes)
            val iv = checkNotNull(cipher.iv) { "Cipher returned empty IV for biometric wrap" }
            try {
                val persisted =
                    prefs
                        .edit()
                        .putString(KEY_BIOMETRIC_WRAPPED_PASSPHRASE, base64(cipherText))
                        .putString(KEY_BIOMETRIC_WRAPPED_IV, base64(iv))
                        .commit()
                check(persisted) { "Failed to persist biometric wrapped passphrase" }
                logger.i(
                    "DatabaseEncryptionManager.storeBiometricWrappedPassphraseWithCipher",
                    "Stored biometric-wrapped passphrase",
                    mapOf("persisted" to persisted, "wrappedBytes" to cipherText.size),
                )
            } finally {
                passphraseBytes.fill(0)
                cipherText.fill(0)
                iv.fill(0)
            }
            true
        }.getOrElse { error ->
            logger.e(
                "DatabaseEncryptionManager.storeBiometricWrappedPassphraseWithCipher",
                "Failed to persist biometric wrapped passphrase",
                error,
            )
            false
        }
    /**
     * Returns the cipher for biometric unlock.
     */
    fun getCipherForBiometricUnlock(): Cipher {
        val encodedIv =
            checkNotNull(prefs.getString(KEY_BIOMETRIC_WRAPPED_IV, null)) {
                "No biometric wrapped IV; biometric unlock not configured"
            }
        val iv = checkNotNull(decodeOrNull(encodedIv)) { "Corrupt biometric wrapped IV" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getExistingBiometricKey(), gcmSpec)
        logger.i(
            "DatabaseEncryptionManager.getCipherForBiometricUnlock",
            "Initialized cipher for biometric unlock",
            mapOf("ivBytes" to iv.size),
        )
        return cipher
    }
    /**
     * Performs the unwrap passphrase with cipher.
     */
    fun unwrapPassphraseWithCipher(cipher: Cipher): String {
        val encodedCipherText =
            checkNotNull(prefs.getString(KEY_BIOMETRIC_WRAPPED_PASSPHRASE, null)) {
                "No biometric wrapped passphrase stored"
            }
        val cipherText = checkNotNull(decodeOrNull(encodedCipherText)) { "Corrupt biometric wrapped passphrase" }
        val clear = cipher.doFinal(cipherText)
        return try {
            logger.i(
                "DatabaseEncryptionManager.unwrapPassphraseWithCipher",
                "Unwrapped biometric-protected passphrase",
                mapOf("clearBytes" to clear.size),
            )
            String(clear, StandardCharsets.UTF_8)
        } finally {
            clear.fill(0)
        }
    }
    /**
     * Returns the unlock remaining seconds.
     */
    fun getUnlockRemainingSeconds(): Long {
        val lockoutUntil = prefs.getLong(KEY_UNLOCK_LOCKOUT_UNTIL_MS, 0L)
        val now = System.currentTimeMillis()
        return if (lockoutUntil > now) {
            ((lockoutUntil - now) + 999L) / 1000L
        } else {
            0L
        }
    }
    /**
     * Performs the record failed unlock attempt.
     */
    fun recordFailedUnlockAttempt(): Long {
        val attempts = prefs.getInt(KEY_UNLOCK_FAILED_ATTEMPTS, 0) + 1
        val delaySeconds = PassphraseLockoutPolicy.delaySecondsForAttempt(attempts)
        val lockoutUntil =
            if (delaySeconds > 0) {
                System.currentTimeMillis() + (delaySeconds * 1000L)
            } else {
                0L
            }
        prefs
            .edit()
            .putInt(KEY_UNLOCK_FAILED_ATTEMPTS, attempts)
            .putLong(KEY_UNLOCK_LOCKOUT_UNTIL_MS, lockoutUntil)
            .apply()
        logger.w(
            "DatabaseEncryptionManager.recordFailedUnlockAttempt",
            "Invalid unlock attempt",
            mapOf("attempts" to attempts, "lockoutSeconds" to delaySeconds),
        )
        return delaySeconds
    }
    /**
     * Removes the reset unlock attempts.
     */
    fun resetUnlockAttempts() {
        prefs
            .edit()
            .putInt(KEY_UNLOCK_FAILED_ATTEMPTS, 0)
            .putLong(KEY_UNLOCK_LOCKOUT_UNTIL_MS, 0L)
            .apply()
        logger.i("DatabaseEncryptionManager.resetUnlockAttempts", "Unlock attempt counters reset")
    }

    private fun persistVerifier(passphrase: String) {
        val salt = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }
        val hash = deriveVerifier(passphrase, salt)
        try {
            val persisted =
                prefs
                    .edit()
                    .putString(KEY_VERIFIER_SALT, base64(salt))
                    .putString(KEY_VERIFIER_HASH, base64(hash))
                    .remove(KEY_WRAPPED_PASSPHRASE_LEGACY)
                    .remove(KEY_WRAPPED_IV_LEGACY)
                    .commit()
            check(persisted) { "Failed to persist passphrase verifier metadata" }
            logger.i(
                "DatabaseEncryptionManager.persistVerifier",
                "Persisted passphrase verifier metadata",
                mapOf("persisted" to persisted, "saltBytes" to salt.size, "hashBytes" to hash.size),
            )
        } finally {
            hash.fill(0)
            salt.fill(0)
        }
    }

    private fun deriveVerifier(
        passphrase: String,
        salt: ByteArray,
    ): ByteArray {
        val passphraseChars = passphrase.toCharArray()
        val keySpec = PBEKeySpec(passphraseChars, salt, PBKDF2_ITERATIONS, PBKDF2_BITS)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(keySpec).encoded
        } finally {
            keySpec.clearPassword()
            Arrays.fill(passphraseChars, '\u0000')
        }
    }

    private fun getOrCreateBiometricKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keystore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            logger.i(
                "DatabaseEncryptionManager.getOrCreateBiometricKey",
                "Reusing existing biometric-required Keystore key",
            )
            return existing
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val paramsBuilder =
            KeyGenParameterSpec
                .Builder(
                    BIOMETRIC_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            paramsBuilder.setInvalidatedByBiometricEnrollment(true)
        }
        keyGenerator.init(paramsBuilder.build())
        logger.i(
            "DatabaseEncryptionManager.getOrCreateBiometricKey",
            "Created biometric-required Keystore key",
        )
        return keyGenerator.generateKey()
    }

    private fun getExistingBiometricKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key =
            checkNotNull(keystore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey) {
                "Biometric key alias not found"
            }
        logger.i(
            "DatabaseEncryptionManager.getExistingBiometricKey",
            "Loaded existing biometric Keystore key",
        )
        return key
    }

    private fun disableBiometricUnlockInternal() {
        logger.i(
            "DatabaseEncryptionManager.disableBiometricUnlockInternal",
            "Clearing biometric state in prefs and Keystore",
            mapOf(
                "hadWrappedPassphrase" to hasBiometricWrappedPassphrase(),
                "biometricPrefEnabled" to prefs.getBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false),
            ),
        )
        val persisted =
            prefs
                .edit()
                .putBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false)
                .remove(KEY_BIOMETRIC_WRAPPED_PASSPHRASE)
                .remove(KEY_BIOMETRIC_WRAPPED_IV)
                .remove(KEY_WRAPPED_PASSPHRASE_LEGACY)
                .remove(KEY_WRAPPED_IV_LEGACY)
                .commit()
        check(persisted) { "Failed to clear biometric state from prefs" }
        deleteKeyAlias(BIOMETRIC_KEY_ALIAS)
        deleteKeyAlias(LEGACY_WRAP_KEY_ALIAS)
    }

    private fun sanitizeLegacySecurityState() {
        val hadLegacyWrapped =
            !prefs.getString(KEY_WRAPPED_PASSPHRASE_LEGACY, null).isNullOrBlank() ||
                !prefs.getString(KEY_WRAPPED_IV_LEGACY, null).isNullOrBlank()
        if (hadLegacyWrapped) {
            prefs
                .edit()
                .remove(KEY_WRAPPED_PASSPHRASE_LEGACY)
                .remove(KEY_WRAPPED_IV_LEGACY)
                .apply()
            logger.w(
                "DatabaseEncryptionManager.sanitizeLegacySecurityState",
                "Removed legacy non-biometric wrapped passphrase artifacts",
            )
        }
        if (prefs.getBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false) && !hasBiometricWrappedPassphrase()) {
            prefs.edit().putBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false).apply()
            logger.w(
                "DatabaseEncryptionManager.sanitizeLegacySecurityState",
                "Disabled biometric preference because biometric-wrapped passphrase is missing",
            )
        }
        logger.i(
            "DatabaseEncryptionManager.sanitizeLegacySecurityState",
            "Security state sanitized",
            mapOf(
                "mode" to (prefs.getString(KEY_MODE, MODE_PLAINTEXT) ?: MODE_PLAINTEXT),
                "biometricEnabled" to prefs.getBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false),
                "hasWrappedPassphrase" to hasBiometricWrappedPassphrase(),
            ),
        )
        deleteKeyAlias(LEGACY_WRAP_KEY_ALIAS)
    }

    private fun deleteKeyAlias(alias: String) {
        runCatching {
            val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keystore.containsAlias(alias)) {
                keystore.deleteEntry(alias)
                logger.i(
                    "DatabaseEncryptionManager.deleteKeyAlias",
                    "Deleted keystore alias",
                    mapOf("alias" to alias),
                )
            }
        }.onFailure { error ->
            logger.w(
                "DatabaseEncryptionManager.deleteKeyAlias",
                "Failed to delete keystore alias",
                mapOf(
                    "alias" to alias,
                    "error" to (error.message ?: "unknown"),
                ),
            )
        }
    }

    private fun base64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private fun decodeOrNull(value: String?): ByteArray? {
        if (value.isNullOrBlank()) return null
        return runCatching { Base64.decode(value, Base64.NO_WRAP) }.getOrElse { error ->
            logger.w(
                "DatabaseEncryptionManager.decodeOrNull",
                "Failed to decode base64 value",
                mapOf("error" to (error.message ?: "unknown")),
            )
            null
        }
    }

    private companion object {
        private const val PREFS_NAME = "db_security_prefs"
        private const val KEY_MODE = "db_mode"
        private const val KEY_UNLOCK_FAILED_ATTEMPTS = "unlock_failed_attempts"
        private const val KEY_UNLOCK_LOCKOUT_UNTIL_MS = "unlock_lockout_until_ms"
        private const val KEY_SESSION_TIMEOUT_MINUTES = "session_timeout_minutes"
        private const val KEY_BIOMETRIC_UNLOCK_ENABLED = "biometric_unlock_enabled"
        private const val KEY_VERIFIER_HASH = "db_verifier_hash"
        private const val KEY_VERIFIER_SALT = "db_verifier_salt"
        private const val KEY_BIOMETRIC_WRAPPED_PASSPHRASE = "db_biometric_wrapped_passphrase"
        private const val KEY_BIOMETRIC_WRAPPED_IV = "db_biometric_wrapped_iv"
        private const val KEY_WRAPPED_PASSPHRASE_LEGACY = "db_wrapped_passphrase"
        private const val KEY_WRAPPED_IV_LEGACY = "db_wrapped_iv"
        private const val MODE_PLAINTEXT = "plaintext"
        private const val MODE_ENCRYPTED = "encrypted"

        private const val BIOMETRIC_KEY_ALIAS = "payanam_db_biometric_passphrase_key"
        private const val LEGACY_WRAP_KEY_ALIAS = "payanam_db_passphrase_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val SALT_BYTES = 32

        private const val BACKUP_PREFIX = "_bak_"
        private val BACKUP_STRING_KEYS =
            arrayOf(
                KEY_MODE,
                KEY_VERIFIER_HASH,
                KEY_VERIFIER_SALT,
                KEY_BIOMETRIC_WRAPPED_PASSPHRASE,
                KEY_BIOMETRIC_WRAPPED_IV,
                KEY_WRAPPED_PASSPHRASE_LEGACY,
                KEY_WRAPPED_IV_LEGACY,
            )
        private val BACKUP_BOOLEAN_KEYS = arrayOf(KEY_BIOMETRIC_UNLOCK_ENABLED)

        private const val PBKDF2_ITERATIONS = 210_000
        private const val PBKDF2_BITS = 256
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val DEFAULT_SESSION_TIMEOUT_MINUTES = 10
        private const val MIN_SESSION_TIMEOUT_MINUTES = 1
        private const val MAX_SESSION_TIMEOUT_MINUTES = 240

        private val secureRandom = SecureRandom()
    }
}
