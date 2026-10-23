//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package io.payanam.presentation.database

import io.payanam.domain.port.DatabasePathResolver
import io.payanam.domain.port.DatabaseRekey
import io.payanam.domain.port.DatabaseSessionManager
import io.payanam.domain.port.ProcessRestart
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.PassphraseUpdateOutcome
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.SharedPassphrasePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
 *
 * PROMOTED FROM `:app`. Every Android type this class used to name is gone rather than relocated, and each
 * left through a contract that already existed:
 *  - `Context` - gone. The database's location arrives as [DatabasePathResolver], the ONE place a shared
 *    ViewModel is allowed to learn it, exactly as [DatabasePassphraseSetupViewModel] does.
 *  - `DatabaseEncryptionMigrationSupport` - gone, in favour of the [DatabaseRekey] port: the shared
 *    [io.payanam.shared.database.DatabaseRekeySequence] runs the same `PRAGMA rekey` on both platforms and
 *    only the HANDLE differs, so the platform implements that one method and the gate - the file opens
 *    under the new passphrase AND fails under the old one - stays in the shared sequence.
 *  - `DatabaseEncryptionManager` - gone, split across the ports that own its behaviour: the passphrase in
 *    [PassphraseVault] (verify, is-encrypted, replace) and the file work in [DatabaseRekey].
 *  - `PassphrasePolicy` - gone, in favour of [SharedPassphrasePolicy], the same twelve-character plus
 *    four-character-class rule with the same five reason codes, already used by the setup gate.
 *  - `UnifiedLogger` - gone. `AppLogger.current` is the shared accessor this repository already uses, so the
 *    log lines keep their text and their data maps without a platform type.
 *  - `androidx.lifecycle` AND HILT: the class no longer extends `ViewModel`, so `hiltViewModel()` cannot
 *    build it and the platform constructs it at the one call site through an entry point, like
 *    [DatabasePassphraseSetupViewModel] and [DatabasePassphraseUnlockViewModel] already are.
 *
 * WHY THE FLUSH STILL GATES THE CHANGE: `closeDatabase()` below deliberately does not fold, so without the
 * awaited fold the most recent commits stay in the `-wal` sidecar encrypted under the CURRENT key while the
 * rekey rewrites the key on the main file alone. Those frames are then left under a key nothing will ever
 * present again - unreadable, and nothing in the app says so. The wait is asked of the SHARED
 * [DatabaseSessionManager] rather than of Android's own session type precisely so both platforms keep it.
 */
class DatabasePassphraseChangeViewModel(
    /** Where this platform's database lives. */
    private val databasePathResolver: DatabasePathResolver,
    /** Where the passphrase is stored, and the answer to "is the database actually encrypted". */
    private val passphraseVault: PassphraseVault,
    /** The platform's own handle for re-encrypting an already-encrypted file. */
    private val rekey: DatabaseRekey,
    /** The SHARED session contract, not Android's own session manager. */
    private val sessionManager: DatabaseSessionManager,
    /** The process-end sequence, as a port. */
    private val processRestart: ProcessRestart,
    /** The scope this ViewModel's work runs on. `viewModelScope` is not available in this module. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Dispatcher for the blocking work - the file copies and the re-encryption. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val logger = AppLogger.current
    private val _uiState = MutableStateFlow(DatabasePassphraseChangeUiState())
    val uiState: StateFlow<DatabasePassphraseChangeUiState> = _uiState.asStateFlow()

    /**
     * Validates and applies a passphrase change: policy + confirm checks,
     * verified current passphrase, artifact backup, an AWAITED flush of the
     * write-ahead log, rekey, rollback on any failure, then process restart on
     * success.
     *
     * The flush is the gate, not a warning: if the main file does not hold every pending write when the
     * change starts, the change does not start, and the old passphrase keeps working.
     */
    fun submit(currentPassphrase: String, newPassphrase: String, confirmPassphrase: String) {
        val validation = SharedPassphrasePolicy.validate(newPassphrase)
        if (!validation.isValid) {
            _uiState.update { it.copy(errorReasonCode = validation.reasonCode ?: "generic") }
            return
        }
        if (newPassphrase != confirmPassphrase) {
            _uiState.update { it.copy(errorReasonCode = "mismatch") }
            return
        }

        scope.launch {
            _uiState.update { it.copy(isSaving = true, errorReasonCode = null, isSuccess = false) }
            val errorCode = changePassphraseWithRollback(currentPassphrase, newPassphrase)
            if (errorCode == null) {
                // Close and restart so cold boot prompts re-auth with the new passphrase. The spawn
                // runs BEFORE the success state flips, because the success state IS the host's quit
                // signal: spawning after it began put the fork inside the app's own teardown (a
                // SIGSEGV storm under software rendering), and the replacement degenerated into a
                // main-class-less JVM that printed the usage and exited. Order measured 2026-10-05.
                sessionManager.closeDatabase()
                restartProcess()
                // Success: UI shows success state briefly, then the host quits this process.
                _uiState.update { it.copy(isSaving = false, errorReasonCode = null, isSuccess = true) }
            } else {
                _uiState.update { it.copy(isSaving = false, errorReasonCode = errorCode, isSuccess = false) }
            }
        }
    }

    /**
     * Runs the whole change, returning `null` on success and a reason code on every refusal.
     *
     * The passphrases are converted to [CharArray] at the ports that want them erasable and cleared as soon
     * as that port returns; the only copy that outlives this call is the immutable `String` the platform's
     * own session port already takes.
     */
    private suspend fun changePassphraseWithRollback(currentPassphrase: String, newPassphrase: String): String? {
        val dbFile = File(databasePathResolver.databaseFilePath())
        if (!dbFile.exists()) {
            logger.w("DatabasePassphraseChangeViewModel.submit", "Database file missing for passphrase update")
            return "generic"
        }
        if (!passphraseVault.isEncryptionEnabled()) {
            logger.w("DatabasePassphraseChangeViewModel.submit", "Passphrase update skipped because encryption is disabled")
            return "generic"
        }
        val currentChars = currentPassphrase.toCharArray()
        val verified = withContext(ioDispatcher) { passphraseVault.verifyPassphrase(currentChars) }
        currentChars.fill('\u0000')
        if (!verified) {
            logger.w("DatabasePassphraseChangeViewModel.submit", "Current passphrase verification failed")
            return "current_invalid"
        }
        val backups = withContext(ioDispatcher) { backupDatabaseArtifacts(dbFile) }
        // The change WAITS for the write-ahead log to empty, and only then proceeds.
        val flushed = runCatching { sessionManager.flushPendingChanges(currentPassphrase) }
            .getOrElse { error ->
                logger.e(
                    "DatabasePassphraseChangeViewModel.submit",
                    "Refusing the passphrase change because the pending changes could not be flushed",
                    error,
                )
                WalCheckpointRule.NO_ROW
            }
        if (!WalCheckpointRule.walIsEmpty(flushed)) {
            logger.w(
                "DatabasePassphraseChangeViewModel.submit",
                "Refusing the passphrase change because the write-ahead log is not empty",
                mapOf(
                    "busy" to flushed.busy,
                    "logFrames" to flushed.logFrames,
                    "checkpointedFrames" to flushed.checkpointedFrames,
                ),
            )
            // The old passphrase keeps working, and the session was never closed, so nothing is left
            // half-changed for the user to recover from.
            withContext(ioDispatcher) { DatabaseArtifactBackupSupport.cleanup(backups) }
            // The EXISTING generic code, deliberately: the change screen renders nothing at all for a code
            // it has no copy for, so a new code here would make this refusal a SILENT one. Nothing new is
            // invented in a shared module - the user is told the change did not happen, and the log line
            // above carries the frame counts that explain why.
            return "generic"
        }
        // Close Room before rekeying the file on disk
        sessionManager.closeDatabase()
        return try {
            withContext(ioDispatcher) {
                val currentChars = currentPassphrase.toCharArray()
                val newChars = newPassphrase.toCharArray()
                try {
                    rekey.rekeyEncryptedDatabase(
                        databaseFile = dbFile,
                        currentPassphrase = currentChars,
                        newPassphrase = newChars,
                        logTag = "DatabasePassphraseChangeViewModel.submit",
                    )
                } finally {
                    currentChars.fill('\u0000')
                    newChars.fill('\u0000')
                }
                // Anything but UPDATED is a failed change, and it goes down the SAME rollback path as a
                // rekey that threw. A CURRENT_PASSPHRASE_WRONG here would mean the vault contradicted the
                // verification above, which is a reason to restore rather than to trust: the contract says
                // the original passphrase must still verify after every other outcome, and a restore is
                // what this path guarantees.
                val outcome = withContext(ioDispatcher) {
                    val currentChars = currentPassphrase.toCharArray()
                    val newChars = newPassphrase.toCharArray()
                    try {
                        passphraseVault.updatePassphrase(current = currentChars, new = newChars)
                    } finally {
                        currentChars.fill('\u0000')
                        newChars.fill('\u0000')
                    }
                }
                if (outcome != PassphraseUpdateOutcome.UPDATED) {
                    throw IllegalStateException("Failed to persist new passphrase metadata: $outcome")
                }
                DatabaseArtifactBackupSupport.cleanup(backups)
            }
            logger.i("DatabasePassphraseChangeViewModel.submit", "Passphrase changed successfully")
            null
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            logger.e("DatabasePassphraseChangeViewModel.submit", "Passphrase change failed, restoring backup", error)
            withContext(ioDispatcher) {
                DatabaseArtifactBackupSupport.restore(backups)
                DatabaseArtifactBackupSupport.cleanup(backups)
            }
            // Re-open Room with original passphrase so the app is usable again
            sessionManager.openDatabase(currentPassphrase)
            "generic"
        }
    }

    /**
     * Ends the process so a cold boot re-authenticates against the new passphrase.
     *
     * The outcome is not read: on Android nothing after this call runs at all, so a branch on the answer
     * would exist on one platform and be unreachable on the other. The port's own KDoc carries why.
     */
    private fun restartProcess() {
        processRestart.restartProcess()
    }

    /**
     * Copies the artifact family to timestamped siblings, returning the original-to-backup pairs.
     *
     * Only the files that exist are backed up, which is why the filter is here rather than inside
     * [DatabaseArtifactBackupSupport]: a `-shm` that was never created is not a file that failed to copy.
     */
    private fun backupDatabaseArtifacts(dbFile: File): List<Pair<File, File>> =
        DatabaseArtifactBackupSupport.backup(DatabaseArtifacts.filesFor(dbFile).filter { it.exists() })

    /**
     * Cancels the scope this ViewModel runs on.
     *
     * `onCleared` used to do this: it was the androidx lifecycle hook, and this class no longer has one. The
     * platform calls this where it used to call that - Android through the release action it registers
     * when it retains the ViewModel, the desktop from a `DisposableEffect`.
     */
    fun dispose() {
        scope.cancel()
    }
}

/**
 * Builds the passphrase-change screen's ViewModel from the platform's own ports.
 *
 * Every collaborator is supplied by the caller, for the reason [passphraseSetupViewModel] and
 * [passphraseUnlockViewModel] are written that way: the vault, the rekey, the session and the restart are
 * platform implementations of shared contracts, and the database path is the platform's own. A factory
 * that resolved them from a `LocalDataLayer` would be inventing a second installation seam for this one
 * screen.
 */
fun passphraseChangeViewModel(
    databasePathResolver: DatabasePathResolver,
    passphraseVault: PassphraseVault,
    rekey: DatabaseRekey,
    sessionManager: DatabaseSessionManager,
    processRestart: ProcessRestart,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): DatabasePassphraseChangeViewModel =
    DatabasePassphraseChangeViewModel(
        databasePathResolver = databasePathResolver,
        passphraseVault = passphraseVault,
        rekey = rekey,
        sessionManager = sessionManager,
        processRestart = processRestart,
        scope = scope,
    )
