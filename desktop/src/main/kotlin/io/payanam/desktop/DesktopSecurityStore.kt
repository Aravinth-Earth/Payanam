//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import io.payanam.shared.security.PassphraseKdf
import io.payanam.shared.security.SharedPassphraseLockoutPolicy
import io.payanam.shared.security.SharedPassphrasePolicy
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

private const val DESKTOP_SECURITY_SCHEMA_VERSION = 1

/** The passphrase verifier file. Named for what it holds, stable so a restart finds it. */
private const val DESKTOP_SECURITY_FILE_NAME = "desktop-security.properties"

/**
 * Whether the persisted security state could actually be read.
 *
 * Without this, "no passphrase configured" and "could not tell" are the same value, which is the
 * fail-open this milestone closes: the startup gate would offer to configure a passphrase on an
 * install that already has one.
 */
enum class DesktopSecurityAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

/**
 * Persisted desktop security state: whether a passphrase is configured, the failed-attempt counter,
 * any active lockout, and whether the entry could be read at all.
 */
data class DesktopSecuritySnapshot(
    val schemaVersion: Int = DESKTOP_SECURITY_SCHEMA_VERSION,
    val hasPassphraseConfigured: Boolean = false,
    val failedUnlockAttempts: Int = 0,
    val lockedUntilEpochMillis: Long? = null,
    /**
     * [DesktopSecurityAvailability.UNAVAILABLE] when the entry could not be read or parsed. The
     * counters above are then whatever could still be parsed, never a silent reset to defaults -
     * defaulting them would clear an active lockout, which is the severity driver here.
     */
    val availability: DesktopSecurityAvailability = DesktopSecurityAvailability.AVAILABLE,
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

    /**
     * The store could not be read, so no verification was possible. Distinct from [UnlockFailed]
     * (which means the passphrase was wrong) and from [Locked] (which means an active lockout was
     * honoured) - both of those require a readable store.
     */
    data object Unavailable : DesktopPassphraseActionResult
}

/**
 * Severity of an injected desktop security-store log event.
 */
internal enum class DesktopSecurityLogLevel {
    INFO,
    WARN,
}

internal class DesktopSecurityStore(
    securityDirectory: Path = DesktopAppPaths.resolveSecurityDirectory(),
    private val persistenceDatabase: DesktopKeyValueEntry =
        DesktopKeyValueEntry(securityFileIn(securityDirectory)),
    private val clock: () -> Long = System::currentTimeMillis,
    private val logEvent: (DesktopSecurityLogLevel, String, String, Map<String, Any?>) -> Unit =
        { _, _, _, _ -> },
) {
    private val secureRandom = SecureRandom()

    /** The verifier file this store reads and writes - a real path, so a restart finds it. */
    private val securityFile: Path = securityFileIn(securityDirectory)

    /**
     * Current security state, creating a fresh default snapshot when none
     * exists yet.
     *
     * Probes the entry exactly once (via [readPersistedState]) - an earlier version asked
     * `hasEntry` here *and* again inside the read, so the two answers could disagree.
     */
    fun ensureSnapshot(): DesktopSecuritySnapshot {
        val state = readPersistedState()
        if (state.entryExists || state.snapshot.availability == DesktopSecurityAvailability.UNAVAILABLE) {
            return state.snapshot
        }
        // The initialising write can fail independently of the read (read-only directory, disk
        // full). A refused start is correct there: the app cannot establish security state, and
        // proceeding as "not configured" is the fail-open being closed.
        return runCatching {
            saveSnapshot(DesktopSecuritySnapshot(), saltBase64 = null, hashBase64 = null)
            DesktopSecuritySnapshot()
        }.getOrElse { failure ->
            unavailableSnapshot("initial_write_failed", failure)
        }
    }

    /**
     * A single read of the entry: the parsed snapshot, the raw properties, and whether the entry
     * existed at all. [entryExists] is carried so callers do not have to re-probe `hasEntry` and
     * risk straddling two different states.
     */
    private data class PersistedSecurityState(
        val snapshot: DesktopSecuritySnapshot,
        val properties: Properties?,
        val entryExists: Boolean,
    )

    /**
     * Security state parsed from persisted properties.
     *
     * Never throws. "Entry absent" remains a legitimate not-configured state; "entry present but
     * blank, or unparseable" is [DesktopSecurityAvailability.UNAVAILABLE], because neither the
     * passphrase state nor the lockout counters can be trusted - and the callers are UI callbacks
     * and the startup gate, where a crash is worse than a refusal.
     */
    fun loadSnapshot(): DesktopSecuritySnapshot = readPersistedState().snapshot

    /**
     * One read of the persisted entry, so a caller cannot straddle a concurrent reset by reading
     * twice and acting on a mixture of old and new state.
     */
    private fun readPersistedState(): PersistedSecurityState {
        val entryExists =
            runCatching { persistenceDatabase.hasEntry() }.getOrElse { failure ->
                // Assumed present: absence was not proved, so the unavailable snapshot must win.
                return PersistedSecurityState(
                    unavailableSnapshot("has_entry_failed", failure),
                    properties = null,
                    entryExists = true,
                )
            }
        if (!entryExists) {
            // An absent entry is an AVAILABLE but unconfigured state, not a fault. Returning empty
            // properties (rather than null) keeps it on the same downstream path as an entry that
            // parses but carries no verifier - the `verifier_missing` outcome - instead of being
            // misreported as an unreadable store.
            return PersistedSecurityState(
                DesktopSecuritySnapshot(),
                properties = Properties(),
                entryExists = false,
            )
        }
        val payload =
            runCatching { persistenceDatabase.readEntry() }.getOrElse { failure ->
                return PersistedSecurityState(
                    unavailableSnapshot("read_failed", failure),
                    properties = null,
                    entryExists = true,
                )
            }
        if (payload.isNullOrBlank()) {
            return PersistedSecurityState(
                unavailableSnapshot("blank_payload", null),
                properties = null,
                entryExists = true,
            )
        }
        val properties =
            runCatching { loadProperties(payload) }.getOrElse { failure ->
                return PersistedSecurityState(
                    unavailableSnapshot("unparsable_payload", failure),
                    properties = null,
                    entryExists = true,
                )
            }
        return PersistedSecurityState(
            snapshot =
                DesktopSecuritySnapshot(
                    schemaVersion =
                        properties.getProperty(KEY_SCHEMA_VERSION)?.toIntOrNull()
                            ?: DESKTOP_SECURITY_SCHEMA_VERSION,
                    hasPassphraseConfigured =
                        !properties.getProperty(KEY_SALT_BASE64).isNullOrBlank() &&
                            !properties.getProperty(KEY_HASH_BASE64).isNullOrBlank(),
                    failedUnlockAttempts = properties.getProperty(KEY_FAILED_ATTEMPTS)?.toIntOrNull() ?: 0,
                    lockedUntilEpochMillis = properties.getProperty(KEY_LOCKED_UNTIL)?.toLongOrNull(),
                ),
            properties = properties,
            entryExists = true,
        )
    }

    /**
     * Builds the refusal snapshot and records why. Names the entry key and the failure class only:
     * exception messages carry absolute paths, and snapshot contents carry the verifier's shape.
     */
    private fun unavailableSnapshot(
        outcome: String,
        failure: Throwable?,
    ): DesktopSecuritySnapshot {
        logEvent(
            DesktopSecurityLogLevel.WARN,
            "DesktopSecurityStore.loadSnapshot",
            "Desktop security state unavailable",
            mapOf(
                "entryKey" to STATE_ENTRY_KEY,
                "outcome" to outcome,
                "failure" to (failure?.javaClass?.simpleName ?: "none"),
            ),
        )
        return DesktopSecuritySnapshot(availability = DesktopSecurityAvailability.UNAVAILABLE)
    }

    /**
     * Builds the refusal result for an unlock attempt that could not be evaluated, and records why.
     * Same payload discipline as [unavailableSnapshot]: entry key and failure class only.
     */
    private fun verificationUnavailable(
        outcome: String,
        failure: Throwable?,
    ): DesktopPassphraseActionResult {
        logEvent(
            DesktopSecurityLogLevel.WARN,
            "DesktopSecurityStore.verifyPassphrase",
            "Desktop security state unavailable during unlock",
            mapOf(
                "entryKey" to STATE_ENTRY_KEY,
                "outcome" to outcome,
                "failure" to (failure?.javaClass?.simpleName ?: "none"),
            ),
        )
        return DesktopPassphraseActionResult.Unavailable
    }

    /**
     * Validates and stores a new PBKDF2 salt/hash verifier for [passphrase].
     */
    fun configurePassphrase(passphrase: String): DesktopPassphraseActionResult {
        val validation = SharedPassphrasePolicy.validate(passphrase)
        if (!validation.isValid) {
            // Outcome only: reasonCode values (min_length, missing_uppercase, ...) would
            // disclose which passphrase rules the input satisfied.
            logEvent(
                DesktopSecurityLogLevel.WARN,
                "DesktopSecurityStore.configurePassphrase",
                "Rejected desktop passphrase setup",
                mapOf("outcome" to "validation_failed"),
            )
            return DesktopPassphraseActionResult.ValidationFailed(checkNotNull(validation.reasonCode))
        }

        val salt = ByteArray(PassphraseKdf.SALT_BYTES).also(secureRandom::nextBytes)
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
            DesktopSecurityLogLevel.INFO,
            "DesktopSecurityStore.configurePassphrase",
            "Configured desktop passphrase verifier",
            emptyMap(),
        )
        return DesktopPassphraseActionResult.Success
    }

    /**
     * Checks [passphrase] against the stored verifier, enforcing the lockout
     * policy on failed attempts.
     */
    fun verifyPassphrase(passphrase: String): DesktopPassphraseActionResult {
        // One read for the whole method: reading the entry three times (payload, properties, then
        // loadSnapshot) let the checks below act on a mixture of old and new state if a reset
        // landed between them.
        val state = readPersistedState()
        val properties = state.properties
        val snapshot = state.snapshot
        if (properties == null || snapshot.availability == DesktopSecurityAvailability.UNAVAILABLE) {
            return verificationUnavailable("state_unavailable", failure = null)
        }
        val now = clock()
        val lockedUntil = snapshot.lockedUntilEpochMillis
        if (lockedUntil != null && lockedUntil > now) {
            val remaining = ((lockedUntil - now) / 1000L).coerceAtLeast(1L)
            // An attempt blocked while a lockout is ALREADY active — distinct from a
            // rejected attempt that STARTS a lockout (logged below).
            logEvent(
                DesktopSecurityLogLevel.INFO,
                "DesktopSecurityStore.verifyPassphrase",
                "Unlock attempt blocked by active lockout",
                mapOf(
                    "outcome" to "locked",
                    "lockoutSecondsRemaining" to remaining,
                ),
            )
            return DesktopPassphraseActionResult.Locked(lockoutSecondsRemaining = remaining)
        }

        val saltBase64 = properties.getProperty(KEY_SALT_BASE64)
        val hashBase64 = properties.getProperty(KEY_HASH_BASE64)
        if (saltBase64.isNullOrBlank() || hashBase64.isNullOrBlank()) {
            // No verifier was ever written, so no comparison happened. A configuration fault,
            // not a wrong passphrase — logged distinctly because the two are otherwise
            // indistinguishable when failedUnlockAttempts is unchanged.
            logEvent(
                DesktopSecurityLogLevel.WARN,
                "DesktopSecurityStore.verifyPassphrase",
                "Unlock rejected because no passphrase verifier is configured",
                mapOf(
                    "outcome" to "verifier_missing",
                    "failedAttempts" to snapshot.failedUnlockAttempts,
                ),
            )
            return DesktopPassphraseActionResult.UnlockFailed(
                failedAttempts = snapshot.failedUnlockAttempts,
                lockoutSecondsRemaining = 0L,
            )
        }

        // Invalid Base64 throws IllegalArgumentException: a third throw vector, distinct from the
        // read and the parse, and the one easiest to miss - "the read is non-throwing" reads
        // naturally as covering the read calls and not the decode that follows them.
        val salt =
            runCatching { Base64.getDecoder().decode(saltBase64) }.getOrElse { failure ->
                return verificationUnavailable("invalid_salt", failure)
            }
        val expectedHash =
            runCatching { Base64.getDecoder().decode(hashBase64) }.getOrElse { failure ->
                return verificationUnavailable("invalid_hash", failure)
            }
        val actualHash = deriveHash(passphrase = passphrase, salt = salt)
        if (expectedHash.contentEquals(actualHash)) {
            saveSnapshot(
                snapshot.copy(failedUnlockAttempts = 0, lockedUntilEpochMillis = null),
                saltBase64 = saltBase64,
                hashBase64 = hashBase64,
            )
            logEvent(
                DesktopSecurityLogLevel.INFO,
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
        // Logged AFTER the persist above: a logging call must never be able to skip
        // recording the failed attempt, which would be a lockout bypass.
        logEvent(
            DesktopSecurityLogLevel.WARN,
            "DesktopSecurityStore.verifyPassphrase",
            "Rejected desktop passphrase attempt",
            mapOf(
                "outcome" to "rejected",
                "failedAttempts" to nextAttemptCount,
                "lockoutSecondsRemaining" to delaySeconds,
            ),
        )
        return DesktopPassphraseActionResult.UnlockFailed(
            failedAttempts = nextAttemptCount,
            lockoutSecondsRemaining = delaySeconds,
        )
    }

    /**
     * Clears the passphrase verifier and failure/lockout counters.
     */
    fun resetSecurityState() {
        saveSnapshot(DesktopSecuritySnapshot(), saltBase64 = null, hashBase64 = null)
        logEvent(
            DesktopSecurityLogLevel.INFO,
            "DesktopSecurityStore.resetSecurityState",
            "Reset desktop security state",
            emptyMap(),
        )
    }

    /**
     * Path of the database file holding the security state.
     */
    fun getSecurityFilePath(): Path = securityFile

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
                properties.store(writer, "Payanam Security")
                writer.toString()
            }
        persistenceDatabase.writeEntry(payload)
    }

    private fun deriveHash(
        passphrase: String,
        salt: ByteArray,
    ): ByteArray {
        // The SHARED configuration. This store derived at 10,000 iterations while Android derived at 210,000,
        // which made the desktop's verifier the cheaper artifact to attack.
        return PassphraseKdf.derive(passphrase.toCharArray(), salt)
    }

    internal companion object {
        internal const val STATE_ENTRY_KEY = "desktop/security"

        /**
         * The verifier file, named for what it holds. The directory is the platform-resolved one so the
         * file lands beside the database it protects, and the name is stable so a restart finds it -
         * which is the entire point: the previous in-memory entry did not.
         */
        fun securityFileIn(directory: Path): Path = directory.resolve(DESKTOP_SECURITY_FILE_NAME)

        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_SALT_BASE64 = "saltBase64"
        private const val KEY_HASH_BASE64 = "hashBase64"
        private const val KEY_FAILED_ATTEMPTS = "failedUnlockAttempts"
        private const val KEY_LOCKED_UNTIL = "lockedUntilEpochMillis"
    }
}
