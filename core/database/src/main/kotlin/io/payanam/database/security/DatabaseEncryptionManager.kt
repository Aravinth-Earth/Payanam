//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("LargeClass", "MagicNumber")


package io.payanam.database.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.logging.CrashBreadcrumbs
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.security.PassphraseKdf
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Suppress("TooManyFunctions")
/**
 * Owns the at-rest encryption metadata for the local database: the PBKDF2
 * passphrase verifier, biometric Keystore wrapping, the session timeout,
 * and the unlock lockout counter.
 * All state is persisted in a private SharedPreferences store and every
 * mutating step is breadcrumbed for crash-safety.
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
                "hasPassphraseConfigured" to hasPassphraseConfigured(),
                "hasVerifierHash" to !prefs.getString(KEY_VERIFIER_HASH, null).isNullOrBlank(),
                "hasVerifierSalt" to !prefs.getString(KEY_VERIFIER_SALT, null).isNullOrBlank(),
                "biometricPrefEnabled" to prefs.getBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false),
                "hasBiometricWrappedPassphrase" to hasBiometricWrappedPassphrase(),
            ),
        )
        sanitizeLegacySecurityState()
    }
    /**
     * True only when encryption is on AND both the PBKDF2 verifier hash and
     * salt are present — i.e. a usable passphrase was configured.
     */
    fun hasPassphraseConfigured(): Boolean =
        !prefs.getString(KEY_VERIFIER_HASH, null).isNullOrBlank() &&
            !prefs.getString(KEY_VERIFIER_SALT, null).isNullOrBlank()
    /**
     * True when the database file is detectably encrypted — answered by the FILE.
     *
     * The answer comes from the same probe every other file question on this platform uses, so the
     * two platforms cannot disagree about what "encrypted" means, and a file that is not detectably
     * encrypted still reads `false`.
     *
     * A missing database file (fresh install) returns false — the honest answer, and the same one a
     * plaintext file gets.
     */
    fun isEncryptionEnabled(): Boolean {
        val file = appContext.getDatabasePath(PayanamDatabaseSchema.DATABASE_NAME) ?: return false
        return DatabaseEncryptionMigrationSupport.isDetectablyEncrypted(appContext, file, "DatabaseEncryptionManager.isEncryptionEnabled")
    }
    /**
     * The configured auto-lock duration in minutes, clamped to the
     * [MIN_SESSION_TIMEOUT_MINUTES]..[MAX_SESSION_TIMEOUT_MINUTES] range.
     */
    fun getSessionTimeoutMinutes(): Int {
        val stored = prefs.getInt(KEY_SESSION_TIMEOUT_MINUTES, DEFAULT_SESSION_TIMEOUT_MINUTES)
        return stored.coerceIn(MIN_SESSION_TIMEOUT_MINUTES, MAX_SESSION_TIMEOUT_MINUTES)
    }
    /**
     * Persists the auto-lock duration, normalizing [minutes] into the allowed
     * range before writing.
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
     * True only when the biometric preference is set AND the biometric-wrapped
     * passphrase material actually exists (a stale pref without material is
     * treated as disabled).
     */
    fun isBiometricUnlockEnabled(): Boolean {
        val enabled = prefs.getBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false)
        return enabled && hasBiometricWrappedPassphrase()
    }
    /**
     * Turns biometric unlock on/off. Enabling is refused (and logged) unless a
     * biometric-wrapped passphrase already exists; disabling delegates to the
     * internal teardown that also clears Keystore material.
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
     * Fully tears down biometric unlock: clears the wrapped-passphrase prefs
     * and deletes the biometric-protected Keystore alias, breadcrumbed and
     * failure-safe. Returns true on success.
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
        CrashBreadcrumbs.record(
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
            CrashBreadcrumbs.record(
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
            CrashBreadcrumbs.record(
                source = "DatabaseEncryptionManager.disableBiometricUnlock",
                stage = "failed",
                data = mapOf("error" to (error.message ?: "unknown")),
            )
            false
        }
    }
    /**
     * First-time passphrase setup. Derives and persists the PBKDF2 verifier,
     * clears any prior biometric material, and records each step via
     * breadcrumbs. Returns true on success.
     */
    fun configurePassphrase(passphrase: String): Boolean {
        logger.i(
            "DatabaseEncryptionManager.configurePassphrase",
            "Configuring database passphrase",
            mapOf(
                "passphraseProvided" to passphrase.isNotEmpty(),
                "hadPassphraseConfigured" to hasPassphraseConfigured(),
            ),
        )
        CrashBreadcrumbs.record(
            source = "DatabaseEncryptionManager.configurePassphrase",
            stage = "started",
            data =
                mapOf(
                    "passphraseProvided" to passphrase.isNotEmpty(),
                    "hadPassphraseConfigured" to hasPassphraseConfigured(),
                ),
        )
        return runCatching {
            persistVerifier(passphrase)
            CrashBreadcrumbs.record(
                source = "DatabaseEncryptionManager.configurePassphrase",
                stage = "verifier_persisted",
            )
            disableBiometricUnlockInternal()
            CrashBreadcrumbs.record(
                source = "DatabaseEncryptionManager.configurePassphrase",
                stage = "biometric_state_cleared",
            )
            check(
                prefs
                    .edit()
                    .putBoolean(KEY_BIOMETRIC_UNLOCK_ENABLED, false)
                    .commit(),
            ) { "Failed to persist encryption state" }
            CrashBreadcrumbs.record(
                source = "DatabaseEncryptionManager.configurePassphrase",
                stage = "encryption_state_persisted",
            )

            logger.i(
                "DatabaseEncryptionManager.configurePassphrase",
                "Database passphrase configured",
                mapOf("passphraseConfigured" to true),
            )
            true
        }.getOrElse { error ->
            logger.e(
                "DatabaseEncryptionManager.configurePassphrase",
                "Failed to configure database passphrase",
                error,
            )
            CrashBreadcrumbs.record(
                source = "DatabaseEncryptionManager.configurePassphrase",
                stage = "failed",
                data = mapOf("error" to (error.message ?: "unknown")),
            )
            false
        }
    }
    /**
     * Changes the passphrase: verifies [currentPassphrase] first, then persists
     * a fresh verifier and drops biometric wrapping (must be re-enrolled).
     * Returns false if the current passphrase does not verify.
     *
     * **The change is atomic in the metadata store, because the old verifier is unrecoverable
     * once the new one is committed.** [persistVerifier] commits the new salt and hash, and the
     * biometric teardown that follows can still fail, so a bare `false` would report a refusal
     * while the NEW verifier was already durable and the OLD one gone — a state no file restore
     * can repair, and one that locks the user out of their own database. So the verifier keys are
     * armed into the `_bak_` side-car keys first ([backupEncryptionPrefs]), the mutation runs, and
     * any failure restores them ([restoreEncryptionPrefs]).
     *
     * Two consequences, both deliberate:
     * - **The change is refused when the rollback cannot be armed.** An unarmed change may land
     *   half-applied, so [backupEncryptionPrefs] failing returns `false` rather than proceeding.
     * - **A successful change consumes the side-car backup it armed** ([clearEncryptionPrefsBackup]),
     *   so stale backups cannot shadow the live verifier. Do not call this inside a window where a
     *   caller has already armed the backup and still needs it: the import flows do
     *   (`SettingsEncryptedImportSupport`) and they do not change a passphrase.
     *
     * A rollback restores the metadata keys only. The teardown's Keystore aliases cannot be
     * restored, but no path that reaches a rollback has deleted one: the only throw site in
     * [disableBiometricUnlockInternal] is the prefs commit check, which precedes both deletions.
     */
    fun updatePassphrase(
        currentPassphrase: String,
        newPassphrase: String,
    ): Boolean {
        logger.i(
            "DatabaseEncryptionManager.updatePassphrase",
            "Updating database passphrase metadata",
            mapOf(
                "currentProvided" to currentPassphrase.isNotEmpty(),
                "newProvided" to newPassphrase.isNotEmpty(),
            ),
        )
        if (!verifyPassphrase(currentPassphrase)) {
            logger.w("DatabaseEncryptionManager.updatePassphrase", "Current passphrase verification failed")
            return false
        }
        if (!backupEncryptionPrefs()) {
            logger.e(
                "DatabaseEncryptionManager.updatePassphrase",
                "Refusing to change the passphrase: the verifier rollback could not be armed",
            )
            return false
        }
        val changed =
            runCatching {
                persistVerifier(newPassphrase)
                disableBiometricUnlockInternal()
                logger.i(
                    "DatabaseEncryptionManager.updatePassphrase",
                    "Database passphrase metadata updated; biometric unlock disabled for re-enrollment",
                )
                true
            }.getOrElse { error ->
                logger.e("DatabaseEncryptionManager.updatePassphrase", "Failed to update passphrase metadata", error)
                val restored = restoreEncryptionPrefs()
                logger.e(
                    "DatabaseEncryptionManager.updatePassphrase",
                    "Rolled back the passphrase verifier after a failed change",
                    null,
                    mapOf("verifierRestored" to restored),
                )
                false
            }
        if (changed) {
            clearEncryptionPrefsBackup()
        }
        return changed
    }
    /**
     * Wipes all encryption state: clears the security prefs and deletes both
     * the biometric and legacy Keystore aliases. Used when the user opts out
     * of encryption entirely. Returns true on success.
     */
    fun resetEncryptionState(): Boolean {
        CrashBreadcrumbs.record(
            source = "DatabaseEncryptionManager.resetEncryptionState",
            stage = "started",
        )
        return runCatching {
            check(prefs.edit().clear().commit()) { "Failed to clear encryption prefs" }
            deleteKeyAlias(BIOMETRIC_KEY_ALIAS)
            deleteKeyAlias(LEGACY_WRAP_KEY_ALIAS)
            logger.w("DatabaseEncryptionManager.resetEncryptionState", "Encryption state reset and keystore keys cleared")
            CrashBreadcrumbs.record(
                source = "DatabaseEncryptionManager.resetEncryptionState",
                stage = "completed",
            )
            true
        }.getOrElse { error ->
            logger.e("DatabaseEncryptionManager.resetEncryptionState", "Failed to reset encryption state", error)
            CrashBreadcrumbs.record(
                source = "DatabaseEncryptionManager.resetEncryptionState",
                stage = "failed",
                data = mapOf("error" to (error.message ?: "unknown")),
            )
            false
        }
    }
    /**
     * Copies the current encryption prefs into side-car backup keys (prefixed
     * `_bak_`) so they can be restored after a failed migration. Returns true
     * on success.
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
     * Restores encryption prefs from the `_bak_` side-car keys, removing the
     * backups afterwards, then re-sanitizes any legacy state. Returns true on
     * success.
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
     * Deletes the `_bak_` side-car backup keys once a migration is confirmed
     * good, so stale backups can't shadow live prefs.
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
     * Validates [passphrase] by re-deriving the PBKDF2 verifier from the stored
     * salt and comparing it against the stored hash with a constant-time
     * [MessageDigest.isEqual] to avoid timing leaks.
     *
     * ENFORCES THE UNLOCK LOCKOUT, and counts a wrong passphrase itself. Both halves belong here for the
     * same reason the desktop store does them: the lockout is a property of the credential, so the thing
     * that compares a passphrase has to consult the active lockout before it can compare one, and has to
     * persist the failure before it can answer - otherwise a correct passphrase is accepted while the
     * owner is locked out, and an attempt that is refused by the lockout costs nothing, which is the
     * schedule the shared policy promises (attempt 3 costs 30s) nobody was enforcing here before.
     *
     * A verifier that is absent or corrupt is a CONFIGURATION fault, not a wrong passphrase, so it is
     * refused without counting - the same distinction the desktop store draws.
     */
    fun verifyPassphrase(passphrase: String): Boolean {
        val now = System.currentTimeMillis()
        val lockoutUntil = prefs.getLong(KEY_UNLOCK_LOCKOUT_UNTIL_MS, 0L)
        if (lockoutUntil > now) {
            val remainingSeconds = ((lockoutUntil - now) + 999L) / 1000L
            logger.w(
                "DatabaseEncryptionManager.verifyPassphrase",
                "Passphrase verification blocked: unlock lockout active",
                mapOf("lockoutSecondsRemaining" to remainingSeconds),
            )
            return false
        }
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
        if (valid) {
            // Re-arm the policy so the next failure is counted from one. Clearing on success is what the
            // shared contract means by "the counter is cleared after a successful unlock", and it is why
            // a locked-out owner who finds their passphrase is not punished for the earlier attempts.
            resetUnlockAttempts()
        } else {
            // Counted here rather than left to the caller, for the same reason the lockout is consulted
            // above: both stores count inside their verification, so a shared caller that records again
            // would escalate the schedule twice as fast and lock the owner out on attempt 2.
            recordFailedUnlockAttempt()
        }
        logger.i(
            "DatabaseEncryptionManager.verifyPassphrase",
            "Passphrase verification completed",
            mapOf("valid" to valid),
        )
        return valid
    }
    /**
     * True when both the biometric-wrapped ciphertext and its IV are stored —
     * the prerequisite for offering biometric unlock.
     */
    fun hasBiometricWrappedPassphrase(): Boolean =
        !prefs.getString(KEY_BIOMETRIC_WRAPPED_PASSPHRASE, null).isNullOrBlank() &&
            !prefs.getString(KEY_BIOMETRIC_WRAPPED_IV, null).isNullOrBlank()
    /**
     * Builds an AES/GCM cipher in encrypt mode bound to (creating if needed)
     * the biometric-required Keystore key, for wrapping the passphrase during
     * biometric enrollment.
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
     * Encrypts [passphrase] with the enrollment [cipher] and persists the
     * resulting ciphertext + IV. All in-memory key material is zeroed in a
     * `finally` block after the write. Returns true on success.
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
     * Builds an AES/GCM decrypt cipher from the stored biometric IV and the
     * existing biometric Keystore key, for unwrapping the passphrase during a
     * biometric unlock.
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
     * Decrypts the stored biometric-wrapped passphrase using the unlock
     * [cipher] and returns it as cleartext; the decrypted buffer is zeroed in
     * `finally` before the string is returned.
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
     * Seconds left on the current unlock lockout (0 if no lockout is active),
     * derived from the stored lockout-until timestamp.
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
     * Registers a failed unlock: bumps the attempt counter, computes the
     * escalating lockout delay via [PassphraseLockoutPolicy], and persists the
     * new lockout-until timestamp. Returns the applied delay in seconds.
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
     * Clears the failed-attempt counter and the lockout-until timestamp, used
     * after a successful unlock to re-arm the lockout policy.
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
        val salt = ByteArray(PassphraseKdf.SALT_BYTES).also { secureRandom.nextBytes(it) }
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
        return try {
            // The SHARED configuration: Android and the desktop must not derive the same passphrase at
            // different costs, which is what a private constant on each side produced.
            PassphraseKdf.derive(passphraseChars, salt)
        } finally {
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
                "hasPassphraseConfigured" to hasPassphraseConfigured(),
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
        private const val BIOMETRIC_KEY_ALIAS = "payanam_db_biometric_passphrase_key"
        private const val LEGACY_WRAP_KEY_ALIAS = "payanam_db_passphrase_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        private const val BACKUP_PREFIX = "_bak_"
        private val BACKUP_STRING_KEYS =
            arrayOf(
                KEY_VERIFIER_HASH,
                KEY_VERIFIER_SALT,
                KEY_BIOMETRIC_WRAPPED_PASSPHRASE,
                KEY_BIOMETRIC_WRAPPED_IV,
                KEY_WRAPPED_PASSPHRASE_LEGACY,
                KEY_WRAPPED_IV_LEGACY,
            )
        private val BACKUP_BOOLEAN_KEYS = arrayOf(KEY_BIOMETRIC_UNLOCK_ENABLED)

        private const val DEFAULT_SESSION_TIMEOUT_MINUTES = 10
        private const val MIN_SESSION_TIMEOUT_MINUTES = 1
        private const val MAX_SESSION_TIMEOUT_MINUTES = 240

        private val secureRandom = SecureRandom()
    }
}
