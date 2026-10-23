//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.presentation.database

import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.port.DatabaseForgotPassphraseReset
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.DatabaseOpenOutcome
import io.payanam.shared.database.PayanamDatabaseOpener
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.BiometricAuthentication
import io.payanam.shared.security.BiometricAvailability
import io.payanam.shared.security.BiometricRefusalReason
import io.payanam.shared.security.BiometricUnavailableReason
import io.payanam.shared.security.BiometricUnlockPrompt
import io.payanam.shared.security.PassphraseVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UI state for the unlock gate: unlock-in-progress flag, the classified error
 * reason code, live lockout countdown, and a summary of the local database
 * file (existence, size, last-modified, storage mode label).
 *
 * The reason codes are this module's own vocabulary, not platform ones: a screen on either platform maps
 * one code to its own copy, so the codes here are the stable contract and the copy is not.
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

/**
 * Maps a refused [io.payanam.shared.database.DatabaseOpenOutcome] to this module's reason code.
 *
 * WHY THE OUTCOME AND NOT AN EXCEPTION MESSAGE ANY MORE: the old implementation in `:app` classified by
 * substring-matching an exception message ("newer than app supports", "Missing tables:"). That was the
 * Android shape, where `DatabaseSessionManager` throws. Both platforms now answer through
 * [PayanamDatabaseOpener], which refuses with a [io.payanam.shared.database.DatabaseBootIssue] carrying a
 * [DatabaseBootIssueType] - an enum, chosen by whoever refuses. A shared layer that still matched English
 * prose would break the moment the desktop's message changed, and would misclassify a refusal that has no
 * such sentence at all.
 */
fun classifyDatabaseOpenOutcome(outcome: DatabaseOpenOutcome): String =
    when (val issue = (outcome as? DatabaseOpenOutcome.Refused)?.issue) {
        null -> {
            "open_failed"
        }

        else -> {
            when (issue.type) {
                DatabaseBootIssueType.DB_TOO_NEW -> "db_too_new"

                DatabaseBootIssueType.DB_TOO_OLD -> "db_too_old"

                DatabaseBootIssueType.SCHEMA_INVALID -> "schema_invalid"

                DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING -> "storage_incomplete"

                DatabaseBootIssueType.OPEN_FAILED -> "open_failed"

                DatabaseBootIssueType.REPAIRABLE_GENERIC,
                DatabaseBootIssueType.NON_REPAIRABLE_GENERIC,
                -> "open_failed"
            }
        }
    }

/**
 * The unlock gate, owned by both platforms.
 *
 * WHY THIS CLASS IS PLATFORM-NEUTRAL AND STILL ANSWERS "SHOW A FINGERPRINT": the OS identity check is the
 * one thing in this screen that no shared module can perform - it needs a `BiometricPrompt` on a
 * `FragmentActivity` on Android and has no equivalent on the desktop at all. So it arrives as a
 * [BiometricUnlockPrompt], and its answer is a PASSPHRASE rather than a cipher: the material that unwraps
 * the passphrase never leaves the platform's Keystore, and asking for it here would have widened this
 * class with a type the shared layer could not hold. Every other Android type this file used to name -
 * `Context`, `BiometricManager`, `ContextCompat`, `FragmentActivity`, `PromptInfo`, `CryptoObject` - is
 * gone rather than relocated, and the database path arrives as a [File] the caller resolved.
 *
 * THE LOCKOUT IS THE VAULT'S, NOT THIS CLASS'S: [PassphraseVault] owns the failure counter and the
 * escalation schedule on both platforms, so this class only reads [PassphraseVault.unlockLockoutSecondsRemaining]
 * and asks for it to be refreshed after a failure. It does not keep an attempt count, because a count
 * held here would be a second, divergent one.
 */
class DatabasePassphraseUnlockViewModel(
    private val vault: PassphraseVault,
    private val biometricPrompt: BiometricUnlockPrompt,
    private val forgottenPassphraseReset: DatabaseForgotPassphraseReset,
    private val databaseOpener: PayanamDatabaseOpener,
    private val databaseFile: File,
    private val strings: UiStrings,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Dispatcher for the blocking work - the vault's verification, the database open, the file stat and
     * the reset.
     *
     * A constructor parameter rather than a mutable `internal var` for the reason
     * [io.payanam.presentation.tasks.TasksViewModel.shapingDispatcher] is not: that one is mutated after
     * construction by a test in this module, and a constructor value cannot be re-pointed by a test
     * compiled as a SEPARATE source set - which is exactly how this class's test is compiled. Fixed at
     * construction it stays the same idea either way: `Dispatchers.IO` is a real thread pool no test
     * dispatcher tracks, so a test that idled virtual time would read the state before the work landed.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Dispatcher the platform's own identity prompt is invoked on.
     *
     * WHY THE PLATFORM SUPPLIES IT: the prompt is the one thing here that reaches real platform UI, and on
     * Android `BiometricPrompt.authenticate(...)` is MAIN-THREAD-ONLY - called from any other thread it
     * throws. [startBiometricUnlock] runs inside [scope], whose default is a background pool, so the prompt
     * used to be invoked off the main thread and the thrown exception escaped the coroutine and killed the
     * process on every launch (the prompt is auto-triggered by the unlock gate). This is the same seam
     * `:app` already uses for `addTaskViewModel(resultDispatcher = Dispatchers.Main.immediate)`: a
     * platform-neutral module cannot name a main thread, so the caller that has one passes it in. The
     * default suits a platform with no OS prompt (the desktop), where the prompt is a no-op.
     */
    private val promptDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val logger = AppLogger.current
    private val _uiState = MutableStateFlow(DatabasePassphraseUnlockUiState())
    val uiState: StateFlow<DatabasePassphraseUnlockUiState> = _uiState.asStateFlow()

    init {
        logger.i("DatabasePassphraseUnlockViewModel.init", "Unlock ViewModel initialized")
        refreshLockoutState()
        loadDatabaseSummary()
    }

    /**
     * The message a reason code renders as, read from the shared copy table.
     *
     * WHY THE VM KNOWS ITS COPY AT ALL: the three reasons this class can raise on its own - a blank or
     * wrong passphrase, a lockout, a failed database open - have no platform-owned equivalent, and a
     * screen that had to map them would have to re-map them identically on two platforms. The biometric
     * trio is NOT here: those reasons belong to the prompt, which is platform code, and the screen that
     * offers a fingerprint already reads them from its own table.
     */
    fun messageForReasonCode(reasonCode: String?): String? =
        when (reasonCode) {
            null -> null
            "invalid" -> strings.dbPassphraseUnlockErrorInvalid
            "locked" -> strings.dbPassphraseUnlockErrorLocked
            "reset_failed" -> strings.dbPassphraseUnlockResetFailed
            "db_too_new" -> strings.dbPassphraseUnlockErrorDbTooNew
            "db_too_old" -> strings.dbPassphraseUnlockErrorDbTooOld
            "schema_invalid" -> strings.dbPassphraseUnlockErrorSchemaInvalid
            "storage_incomplete" -> strings.dbPassphraseUnlockErrorStorageIncomplete
            "migration_required" -> strings.dbPassphraseUnlockErrorMigrationRequired
            "open_failed" -> strings.dbPassphraseUnlockErrorOpenFailed
            "loc_database_needs_repair" -> strings.locDatabaseNeedsRepair
            else -> null
        }

    /**
     * Re-reads the lockout countdown from the vault into state (called on screen focus).
     */
    fun refreshLockoutState() {
        val remaining = vault.unlockLockoutSecondsRemaining()
        logger.d(
            "DatabasePassphraseUnlockViewModel.refreshLockoutState",
            "Refreshed lockout countdown",
            mapOf("remainingSeconds" to remaining),
        )
        _uiState.update { it.copy(lockoutSecondsRemaining = remaining) }
    }

    /**
     * Verifies [passphrase] and opens the encrypted database on success; wrong attempts feed the vault's
     * lockout policy and failures surface as reason codes in state.
     */
    fun unlock(
        passphrase: String,
        onSuccess: () -> Unit,
    ) {
        logger.i(
            "DatabasePassphraseUnlockViewModel.unlock",
            "Passphrase unlock requested",
            mapOf(
                "passphraseBlank" to passphrase.isBlank(),
                "passphraseProvided" to passphrase.isNotEmpty(),
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
        val remaining = vault.unlockLockoutSecondsRemaining()
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

        val passphraseChars = passphrase.toCharArray()
        scope.launch {
            _uiState.update { it.copy(isUnlocking = true, errorReasonCode = null) }
            val valid =
                withContext(ioDispatcher) {
                    vault.verifyPassphrase(passphraseChars)
                }
            passphraseChars.fill('\u0000')
            logger.i(
                "DatabasePassphraseUnlockViewModel.unlock",
                "Passphrase verification finished",
                mapOf("valid" to valid),
            )
            if (valid) {
                openSessionAndSucceed(passphrase, onSuccess, "passphrase")
            } else {
                // The failure is reported so the platform's store can apply its own schedule. It is NOT
                // incremented here and no counter is kept: both stores count inside their verification, so
                // adding one would make the schedule escalate twice as fast and lock the owner out earlier
                // than the shared policy promises.
                val lockoutSeconds = vault.recordFailedUnlockAttempt()
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
     * Whether this platform could show a working prompt right now, as a reason code or null.
     *
     * Two questions, one answer for a screen: the platform's capability and the user's own preference both
     * decide whether the affordance appears, and a screen that asked them separately would have to fold
     * "no sensor" and "you turned this off" into one boolean to render one button.
     */
    fun biometricAvailability(): BiometricAvailability = biometricPrompt.availability()

    /**
     * Asks the platform to prove the user's identity, then opens the database with the passphrase the
     * platform hands back.
     *
     * NO TIMEOUT AND NO FALLBACK PROMPT: [BiometricUnlockPrompt.requestAuthentication] resolves exactly once
     * and only on a terminal event, so a timeout added here would race a dialog that is still legitimately
     * on screen and report a failure the user has not made.
     *
     * WHY THE CATCH IS BROAD: this path is auto-triggered by the unlock gate, and the platform's
     * prompt is the only thing here that reaches real platform UI, so an escaping exception would be
     * a launch crash-loop — a reported UI error is preferable to an unopenable app.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: auto-triggered; an escaping exception is a launch crash-loop
    fun startBiometricUnlock(onSuccess: () -> Unit) {
        scope.launch {
            when (val availability = biometricPrompt.availability()) {
                is BiometricAvailability.Unavailable -> {
                    logger.w(
                        "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                        "Biometric unlock unavailable on this platform",
                        mapOf("reason" to availability.reason),
                    )
                    _uiState.update {
                        it.copy(
                            isUnlocking = false,
                            errorReasonCode = reasonCodeFor(availability.reason),
                            lockoutSecondsRemaining = 0L,
                        )
                    }
                    return@launch
                }

                BiometricAvailability.Available -> {
                    Unit
                }
            }

            _uiState.update { it.copy(isUnlocking = true, errorReasonCode = null) }
            val outcome =
                try {
                    // The prompt MUST be invoked on the dispatcher this platform supplied. On Android
                    // `BiometricPrompt.authenticate(...)` is main-thread-only, and this method used to reach
                    // it from [scope]'s background pool, which threw on every launch.
                    withContext(promptDispatcher) { biometricPrompt.requestAuthentication() }
                } catch (cancellation: CancellationException) {
                    // A teardown is not a failure to report: rethrow so structured concurrency still works.
                    throw cancellation
                } catch (failure: Exception) {
                    // A misbehaving platform prompt that throws an Exception must not take the app down
                    // with it. This call is auto-triggered on the unlock gate, so an escaping exception here
                    // is a launch crash-loop - an unopenable app is worse than a failed unlock. Report it as
                    // a UI error instead. Errors are not caught here, only Exception.
                    logger.e(
                        "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                        "Biometric prompt threw; keeping the app open and reporting the failure",
                        failure,
                    )
                    _uiState.update {
                        it.copy(isUnlocking = false, errorReasonCode = "biometric_error")
                    }
                    return@launch
                }
            when (outcome) {
                is BiometricAuthentication.Authenticated -> {
                    openSessionAndSucceed(outcome.passphrase, onSuccess, "biometric")
                }

                is BiometricAuthentication.Refused -> {
                    // A dismissal is not a failure: reporting one would put an error on screen for a user
                    // who deliberately backed out.
                    if (outcome.reason == BiometricRefusalReason.CANCELLED) {
                        logger.i(
                            "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                            "Biometric prompt dismissed; nothing to report",
                        )
                        _uiState.update { it.copy(isUnlocking = false) }
                    } else {
                        logger.w(
                            "DatabasePassphraseUnlockViewModel.startBiometricUnlock",
                            "Biometric auth refused",
                            mapOf("reason" to outcome.reason),
                        )
                        _uiState.update {
                            it.copy(isUnlocking = false, errorReasonCode = "biometric_lockout_or_error")
                        }
                    }
                }
            }
        }
    }

    /**
     * Whether the platform will let a prompt be shown at all (drives UI affordances).
     */
    fun isBiometricUnlockEnabled(): Boolean = biometricPrompt.availability() == BiometricAvailability.Available

    /**
     * Destructive recovery path: the platform's own reset deletes the database artifact family and clears
     * the unlock credential, in that order.
     */
    fun forgotPassphraseReset(onSuccess: () -> Unit) {
        logger.w("DatabasePassphraseUnlockViewModel.forgotPassphraseReset", "Forgot-passphrase reset requested")
        scope.launch {
            _uiState.update { it.copy(isUnlocking = true, errorReasonCode = null) }
            // Asked for as an ordinary blocking call rather than through a callback, because the port
            // RETURNS the report. Nothing here can now be left unsignalled: a platform that answered a
            // reset with nothing would hold the user on a busy flag forever, on the one screen they
            // cannot leave.
            val report = withContext(ioDispatcher) { forgottenPassphraseReset.reset() }
            logger.w(
                "DatabasePassphraseUnlockViewModel.forgotPassphraseReset",
                "Forgot-passphrase reset executed",
                mapOf(
                    "artifactsDeleted" to report.artifactsDeleted,
                    "credentialCleared" to report.credentialCleared,
                ),
            )
            _uiState.update {
                it.copy(
                    isUnlocking = false,
                    errorReasonCode = if (report.succeeded) null else "reset_failed",
                    lockoutSecondsRemaining = 0L,
                )
            }
            if (report.succeeded) {
                onSuccess()
            }
        }
    }

    /**
     * Releases the coroutine scope this ViewModel created.
     *
     * `onCleared` used to do this: it was the androidx lifecycle hook, and this class no longer has one.
     * The platform calls this at the same point - Android through the release action it registers when it
     * retains the ViewModel, the desktop from a `DisposableEffect`.
     */
    fun dispose() {
        scope.cancel()
    }

    /**
     * Opens the session with an already-verified [passphrase] and reports the outcome as a reason code.
     *
     * Shared by both unlock paths, because a biometric success and a typed passphrase reach the same place
     * and had drifted before: the typed path cleared the lockout and the biometric path did not.
     */
    private suspend fun openSessionAndSucceed(
        passphrase: String,
        onSuccess: () -> Unit,
        source: String,
    ) {
        val passphraseChars = passphrase.toCharArray()
        val outcome =
            withContext(ioDispatcher) {
                databaseOpener.open(
                    databasePath = databaseFile.absolutePath,
                    passphrase = passphraseChars,
                    readOnly = false,
                )
            }
        passphraseChars.fill('\u0000')
        if (outcome is DatabaseOpenOutcome.Refused) {
            val reasonCode = classifyDatabaseOpenOutcome(outcome)
            logger.e(
                "DatabasePassphraseUnlockViewModel.openSessionAndSucceed",
                "DB open failed after credential verified",
                null,
                mapOf("source" to source, "reasonCode" to reasonCode),
            )
            _uiState.update {
                it.copy(
                    isUnlocking = false,
                    errorReasonCode = reasonCode,
                    lockoutSecondsRemaining = 0L,
                )
            }
            return
        }
        // A verified credential starts the next failure from one. Deliberately asked of the vault rather
        // than tracked here, and it must never touch the stored passphrase.
        vault.resetUnlockAttempts()
        logger.i(
            "DatabasePassphraseUnlockViewModel.openSessionAndSucceed",
            "Unlock successful; database session opened",
            mapOf("source" to source),
        )
        _uiState.update {
            it.copy(
                isUnlocking = false,
                errorReasonCode = null,
                lockoutSecondsRemaining = 0L,
            )
        }
        onSuccess()
    }

    /** The reason code for a platform that cannot show a prompt, kept distinct so the copy can differ. */
    private fun reasonCodeFor(reason: BiometricUnavailableReason): String =
        when (reason) {
            BiometricUnavailableReason.NO_HARDWARE,
            BiometricUnavailableReason.NOT_ENROLLED,
            BiometricUnavailableReason.UNKNOWN,
            -> "biometric_unavailable"

            BiometricUnavailableReason.DISABLED_BY_PREFERENCE -> "biometric_unavailable"

            BiometricUnavailableReason.KEY_UNAVAILABLE -> "key_invalidated"
        }

    private fun startLockoutTicker() {
        logger.i("DatabasePassphraseUnlockViewModel.startLockoutTicker", "Starting lockout ticker")
        scope.launch {
            while (true) {
                val remaining = vault.unlockLockoutSecondsRemaining()
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

    private fun loadDatabaseSummary() {
        logger.i("DatabasePassphraseUnlockViewModel.loadDatabaseSummary", "Loading local database summary")
        // The file is stat'ed on IO, but the STATE is published on the ViewModel's own scope: a summary
        // written straight to the flow from an IO thread would land at a moment the caller cannot observe,
        // which makes the storage label untestable and the screen's first frame nondeterministic.
        scope.launch {
            val summary =
                withContext(ioDispatcher) {
                    if (!databaseFile.exists()) {
                        null
                    } else {
                        Summary(
                            sizeKb = databaseFile.length() / 1024,
                            lastModifiedMs = databaseFile.lastModified(),
                        )
                    }
                }
            if (summary == null) {
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
                    mapOf("path" to databaseFile.absolutePath),
                )
                return@launch
            }
            _uiState.update {
                it.copy(
                    hasLocalDatabase = true,
                    databaseSizeKb = summary.sizeKb,
                    databaseLastModifiedMs = summary.lastModifiedMs,
                    // Whether the database is encrypted at rest is a property of the vault's own state, not
                    // a second question asked of the platform.
                    storageModeLabelKey = if (vault.isEncryptionEnabled()) "encrypted" else "plaintext",
                )
            }
            logger.i(
                "DatabasePassphraseUnlockViewModel.loadDatabaseSummary",
                "Local database summary loaded",
                mapOf(
                    "path" to databaseFile.absolutePath,
                    "sizeKB" to summary.sizeKb,
                    "lastModified" to summary.lastModifiedMs,
                ),
            )
        }
    }

    /** The three numbers read off the file, read once on IO and published together. */
    private data class Summary(
        val sizeKb: Long,
        val lastModifiedMs: Long,
    )

    /** The artifact family this platform's reset would delete, for a screen that wants to show it. */
    fun databaseArtifactFiles(): List<File> = DatabaseArtifacts.filesFor(databaseFile)
}

/**
 * The one-line entry point a platform's unlock gate calls to get the ViewModel.
 *
 * Every collaborator is supplied by the caller, because the four of them are precisely the things a shared
 * module cannot build: the vault, the prompt and the reset are platform implementations of shared
 * contracts, and the database path is the platform's own. A factory that read a `LocalDataLayer` for them
 * would be inventing a second installation seam for the unlock gate and would have to be extended every
 * time the gate gained a dependency.
 */
fun passphraseUnlockViewModel(
    vault: PassphraseVault,
    biometricPrompt: BiometricUnlockPrompt,
    forgottenPassphraseReset: DatabaseForgotPassphraseReset,
    databaseOpener: PayanamDatabaseOpener,
    databaseFile: File,
    strings: UiStrings,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Dispatcher the platform's identity prompt runs on. Defaults to a background pool for a platform with
     * no OS prompt; Android passes `Dispatchers.Main.immediate` because its `BiometricPrompt` is
     * main-thread-only.
     */
    promptDispatcher: CoroutineDispatcher = Dispatchers.Default,
): DatabasePassphraseUnlockViewModel =
    DatabasePassphraseUnlockViewModel(
        vault = vault,
        biometricPrompt = biometricPrompt,
        forgottenPassphraseReset = forgottenPassphraseReset,
        databaseOpener = databaseOpener,
        databaseFile = databaseFile,
        strings = strings,
        scope = scope,
        promptDispatcher = promptDispatcher,
    )
