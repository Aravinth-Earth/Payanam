//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.security.SharedPassphraseLockoutPolicy
import io.payanam.shared.security.SharedPassphrasePolicy
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val DESKTOP_SECURITY_SCHEMA_VERSION = 1
private const val PBKDF2_ITERATIONS = 10_000
private const val PBKDF2_KEY_LENGTH = 256

/**
 * DesktopSecuritySnapshot.

 */
data class DesktopSecuritySnapshot(
    val schemaVersion: Int = DESKTOP_SECURITY_SCHEMA_VERSION,
    val hasPassphraseConfigured: Boolean = false,
    val failedUnlockAttempts: Int = 0,
    val lockedUntilEpochMillis: Long? = null,
)

/**
 * Result of a desktop passphrase action (setup, change, or unlock attempt).
 */
sealed interface DesktopPassphraseActionResult {
    data object Success : DesktopPassphraseActionResult

    /**
     * ValidationFailed.
    
     */
    data class ValidationFailed(
        val reasonCode: String,
    ) : DesktopPassphraseActionResult

    /**
     * UnlockFailed.
    
     */
    data class UnlockFailed(
        val failedAttempts: Int,
        val lockoutSecondsRemaining: Long,
    ) : DesktopPassphraseActionResult

    /**
     * Locked.
    
     */
    data class Locked(
        val lockoutSecondsRemaining: Long,
    ) : DesktopPassphraseActionResult
}

internal class DesktopSecurityStore(
    securityDirectory: Path = DesktopAppPaths.resolveSecurityDirectory(),
    private val persistenceDatabase: DesktopPersistenceDatabase =
        DesktopPersistenceDatabase(
            databaseDirectory = securityDirectory,
            securityDirectory = securityDirectory,
        ),
    private val clock: () -> Long = System::currentTimeMillis,
    private val logEvent: (String, String, Map<String, Any?>) -> Unit = { _, _, _ -> },
) {
    private val secureRandom = SecureRandom()
    /**
     * Performs the ensure snapshot.
     */
    fun ensureSnapshot(): DesktopSecuritySnapshot {
        if (persistenceDatabase.hasEntry(STATE_ENTRY_KEY)) {
            return loadSnapshot()
        }
        val snapshot = DesktopSecuritySnapshot()
        saveSnapshot(snapshot, saltBase64 = null, hashBase64 = null)
        return snapshot
    }
    /**
     * Loads the load snapshot.
     */
    fun loadSnapshot(): DesktopSecuritySnapshot {
        val payload = persistenceDatabase.readEntry(STATE_ENTRY_KEY)
        if (payload.isNullOrBlank()) {
            return DesktopSecuritySnapshot()
        }
        val properties = loadProperties(payload)
        return DesktopSecuritySnapshot(
            schemaVersion = properties.getProperty(KEY_SCHEMA_VERSION)?.toIntOrNull() ?: DESKTOP_SECURITY_SCHEMA_VERSION,
            hasPassphraseConfigured =
                !properties.getProperty(KEY_SALT_BASE64).isNullOrBlank() &&
                    !properties.getProperty(KEY_HASH_BASE64).isNullOrBlank(),
            failedUnlockAttempts = properties.getProperty(KEY_FAILED_ATTEMPTS)?.toIntOrNull() ?: 0,
            lockedUntilEpochMillis = properties.getProperty(KEY_LOCKED_UNTIL)?.toLongOrNull(),
        )
    }
    /**
     * Performs the configure passphrase.
     */
    fun configurePassphrase(passphrase: String): DesktopPassphraseActionResult {
        val validation = SharedPassphrasePolicy.validate(passphrase)
        if (!validation.isValid) {
            return DesktopPassphraseActionResult.ValidationFailed(checkNotNull(validation.reasonCode))
        }

        val salt = ByteArray(16).also(secureRandom::nextBytes)
        val hash = deriveHash(passphrase = passphrase, salt = salt)
        saveSnapshot(
            snapshot =
                DesktopSecuritySnapshot(
                    hasPassphraseConfigured = true,
                    failedUnlockAttempts = 0,
                    lockedUntilEpochMillis = null,
                ),
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            hashBase64 = Base64.getEncoder().encodeToString(hash),
        )
        logEvent(
            "DesktopSecurityStore.configurePassphrase",
            "Configured desktop passphrase verifier",
            emptyMap(),
        )
        return DesktopPassphraseActionResult.Success
    }
    /**
     * Performs the verify passphrase.
     */
    fun verifyPassphrase(passphrase: String): DesktopPassphraseActionResult {
        val payload = persistenceDatabase.readEntry(STATE_ENTRY_KEY).orEmpty()
        val properties = loadProperties(payload)
        val snapshot = loadSnapshot()
        val now = clock()
        val lockedUntil = snapshot.lockedUntilEpochMillis
        if (lockedUntil != null && lockedUntil > now) {
            return DesktopPassphraseActionResult.Locked(
                lockoutSecondsRemaining = ((lockedUntil - now) / 1000L).coerceAtLeast(1L),
            )
        }

        val saltBase64 = properties.getProperty(KEY_SALT_BASE64)
        val hashBase64 = properties.getProperty(KEY_HASH_BASE64)
        if (saltBase64.isNullOrBlank() || hashBase64.isNullOrBlank()) {
            return DesktopPassphraseActionResult.UnlockFailed(
                failedAttempts = snapshot.failedUnlockAttempts,
                lockoutSecondsRemaining = 0L,
            )
        }

        val salt = Base64.getDecoder().decode(saltBase64)
        val expectedHash = Base64.getDecoder().decode(hashBase64)
        val actualHash = deriveHash(passphrase = passphrase, salt = salt)
        if (expectedHash.contentEquals(actualHash)) {
            saveSnapshot(
                snapshot.copy(failedUnlockAttempts = 0, lockedUntilEpochMillis = null),
                saltBase64 = saltBase64,
                hashBase64 = hashBase64,
            )
            logEvent(
                "DesktopSecurityStore.verifyPassphrase",
                "Desktop passphrase verified",
                emptyMap(),
            )
            return DesktopPassphraseActionResult.Success
        }

        val nextAttemptCount = snapshot.failedUnlockAttempts + 1
        val delaySeconds = SharedPassphraseLockoutPolicy.delaySecondsForAttempt(nextAttemptCount)
        val lockedUntilEpochMillis = if (delaySeconds > 0L) now + (delaySeconds * 1000L) else null
        saveSnapshot(
            snapshot.copy(
                failedUnlockAttempts = nextAttemptCount,
                lockedUntilEpochMillis = lockedUntilEpochMillis,
            ),
            saltBase64 = saltBase64,
            hashBase64 = hashBase64,
        )
        return DesktopPassphraseActionResult.UnlockFailed(
            failedAttempts = nextAttemptCount,
            lockoutSecondsRemaining = delaySeconds,
        )
    }
    /**
     * Removes the reset security state.
     */
    fun resetSecurityState() {
        saveSnapshot(DesktopSecuritySnapshot(), saltBase64 = null, hashBase64 = null)
        logEvent(
            "DesktopSecurityStore.resetSecurityState",
            "Reset desktop security state",
            emptyMap(),
        )
    }
    /**
     * Returns the security file path.
     */
    fun getSecurityFilePath(): Path = persistenceDatabase.getDatabaseFilePath()

    private fun loadProperties(payload: String): Properties =
        Properties().also { properties ->
            if (payload.isNotBlank()) {
                StringReader(payload).use(properties::load)
            }
        }

    private fun saveSnapshot(
        snapshot: DesktopSecuritySnapshot,
        saltBase64: String?,
        hashBase64: String?,
    ) {
        val properties =
            Properties().apply {
                setProperty(KEY_SCHEMA_VERSION, snapshot.schemaVersion.toString())
                setProperty(KEY_FAILED_ATTEMPTS, snapshot.failedUnlockAttempts.toString())
                snapshot.lockedUntilEpochMillis?.let { setProperty(KEY_LOCKED_UNTIL, it.toString()) }
                saltBase64?.let { setProperty(KEY_SALT_BASE64, it) }
                hashBase64?.let { setProperty(KEY_HASH_BASE64, it) }
            }
        val payload =
            StringWriter().use { writer ->
                properties.store(writer, "Payanam Desktop Security")
                writer.toString()
            }
        persistenceDatabase.writeEntry(STATE_ENTRY_KEY, payload)
    }

    private fun deriveHash(
        passphrase: String,
        salt: ByteArray,
    ): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    internal companion object {
        internal const val STATE_ENTRY_KEY = "desktop/security"
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_SALT_BASE64 = "saltBase64"
        private const val KEY_HASH_BASE64 = "hashBase64"
        private const val KEY_FAILED_ATTEMPTS = "failedUnlockAttempts"
        private const val KEY_LOCKED_UNTIL = "lockedUntilEpochMillis"
    }
}
