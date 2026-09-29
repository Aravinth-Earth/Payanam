//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.presentation.database

import io.payanam.domain.port.DatabasePathResolver
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.EncryptionCheckpointOutcome
import io.payanam.shared.security.EncryptionStateCheckpoint
import io.payanam.shared.security.PassphraseConfigureOutcome
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * UI state for the passphrase-setup gate: save-in-progress flag, error reason
 * code, and a summary of any existing plaintext database (size, last-modified,
 * per-table row counts) so the user knows what will be encrypted.
 */
data class DatabasePassphraseSetupUiState(
    val isSaving: Boolean = false,
    val errorReasonCode: String? = null,
    val hasExistingLocalDatabase: Boolean = false,
    val databaseSizeKb: Long = 0L,
    val databaseLastModifiedMs: Long = 0L,
    val taskCount: Int = 0,
    val timeEntryCount: Int = 0,
    val journalCount: Int = 0,
    val noteCount: Int = 0,
    val storageModeLabelKey: String = "plaintext",
)

/**
 * Maps a [PassphraseConfigureOutcome] to this module's reason code, or `null` for success.
 *
 * WHY THIS IS A NAMED FUNCTION AND NOT AN INLINE `!= CONFIGURED`: the old `:app` implementation asked a
 * `Boolean` and had exactly one failure to describe. The vault answers in three values, and the three do
 * not mean the same thing to a user, so each gets its own code rather than all of them collapsing into the
 * old `persist_failed`:
 *
 *  - [PassphraseConfigureOutcome.CONFIGURED] -> `null`. The gate opens.
 *  - [PassphraseConfigureOutcome.STORE_UNAVAILABLE] -> `persist_failed`. This is the exact predecessor of
 *    the old `!configured` arm: the passphrase was acceptable and the platform could not write it.
 *  - [PassphraseConfigureOutcome.REJECTED_BY_POLICY] -> `rejected_by_policy`. The passphrase is the user's
 *    to fix, which is a different fault from an unwritable store, and telling such a user "could not save"
 *    would send them to retry the same passphrase forever.
 *
 * WHY `rejected_by_policy` IS UNREACHABLE ON BOTH PLATFORMS TODAY, AND WHY IT IS STILL HANDLED: this
 * ViewModel validates with [SharedPassphrasePolicy] before it ever reaches the vault, and both platform
 * vaults validate the SAME policy internally, so a policy rejection is a contradiction rather than an
 * expected input. It is written out anyway because that is a property of today's two implementations, not
 * of the contract: a vault that tightened its policy must not be able to crash this screen, and a caller
 * that relied on "it cannot happen" is exactly the one that breaks when it does.
 *
 * NO SCREEN COPY EXISTS YET for `rejected_by_policy` - a rendering screen falls through to its generic
 * error arm, which is vague but not false. That gap is reported rather than papered over: inventing a
 * message here would put platform copy in this module.
 */
fun reasonCodeForConfigureOutcome(outcome: PassphraseConfigureOutcome): String? =
    when (outcome) {
        PassphraseConfigureOutcome.CONFIGURED -> null
        PassphraseConfigureOutcome.STORE_UNAVAILABLE -> "persist_failed"
        PassphraseConfigureOutcome.REJECTED_BY_POLICY -> "rejected_by_policy"
    }

/**
 * Maps an [EncryptionStateCheckpoint.wipeEncryptionState] answer to this module's reason code, or `null`
 * for "the reset is settled, carry on".
 *
 * THE ONE BRANCH THAT IS A DECISION AND NOT A TRANSLATION IS [EncryptionCheckpointOutcome.NOT_SUPPORTED].
 * The desktop answers it for every member of this contract, because a build that opens a plaintext
 * database has no at-rest encryption metadata to forget. Treating that as a failure would report a broken
 * reset on a platform that is not broken, and would block a passphrase the desktop CAN store. It is
 * therefore 'nothing to wipe' - success, stated as its own arm so that a reader can see the choice rather
 * than infer it from a `when` whose two success branches happen to be written the same way.
 *
 * [EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED] and [EncryptionCheckpointOutcome.STORE_UNAVAILABLE]
 * both mean the caller may not proceed, and both are reported as `reset_failed`: this screen is about to
 * delete the user's database and configure a new passphrase over it, and doing that on top of encryption
 * state that may still be there is the failure the contract's own KDoc warns about. The one thing this
 * refuses to do is claim the state is clean.
 */
fun reasonCodeForWipeOutcome(outcome: EncryptionCheckpointOutcome): String? =
    when (outcome) {
        EncryptionCheckpointOutcome.CONFIRMED -> null

        // Not a failure: this platform keeps no encryption state, so there was nothing to wipe. Reporting
        // anything else here would be claiming a protection the platform does not have.
        EncryptionCheckpointOutcome.NOT_SUPPORTED -> null

        // The platform tried and cannot say whether it stuck. Unknown is not clean, so the reset path stops.
        EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED -> "reset_failed"

        EncryptionCheckpointOutcome.STORE_UNAVAILABLE -> "reset_failed"
    }

/**
 * The count-validated backup, restore and cleanup of a database artifact family around an in-place
 * re-encryption.
 *
 * PROMOTED WITH [DatabasePassphraseSetupViewModel] out of `:app`, where it was three private methods on
 * that class. They are pure `java.io.File` work - copy, timestamp naming, delete - and the only thing that
 * ever made them app-shaped was that the caller had a `Context.getDatabasePath` to hand. [filesFor] is
 * [DatabaseArtifacts.filesFor], already shared and already file-relative, so the file list arrives from the
 * caller and this object never learns where a database lives.
 *
 * WHY IT IS AN OBJECT RATHER THAN THREE MORE METHODS: these run inside the migration's `catch`/`finally`,
 * and the one thing a test of that path needs is to reach the RESTORE and CLEANUP arms without standing up
 * a database. Kept separate, they are reachable directly, and the count `restore` returns - how many files
 * actually came back - is a number a test can assert instead of a log line.
 */
object DatabaseArtifactBackupSupport {
    private val logger get() = AppLogger.current

    private val timestampFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

    /**
     * Copies every file in [files] to a timestamped sibling and returns the original-to-backup pairs.
     *
     * The backup is a SIBLING of the original rather than a file in a temp directory, because the artifact
     * family is only meaningful next to its database: a `-wal` copied somewhere else is an orphan the
     * restore cannot reason about.
     */
    fun backup(files: List<File>): List<Pair<File, File>> {
        val timestamp = LocalDateTime.now().format(timestampFormat)
        return files.map { original ->
            val backup = File(original.parent, "${original.name}.before_encrypt_$timestamp.bak")
            original.copyTo(backup, overwrite = true)
            original to backup
        }
    }

    /**
     * Copies every surviving backup back over its original, and returns how many files were restored.
     *
     * The count is returned rather than only logged because it is the difference between "rolled back" and
     * "rolled back except for the file the migration destroyed", and the second one is invisible in a log
     * line nobody is reading during a failed setup.
     */
    fun restore(mappings: List<Pair<File, File>>): Int {
        var restored = 0
        mappings.forEach { (original, backup) ->
            if (backup.exists()) {
                backup.copyTo(original, overwrite = true)
                restored++
            }
        }
        logger.i(
            "DatabaseArtifactBackupSupport.restore",
            "Restored database artifacts after encryption migration failure",
            mapOf("restoredFiles" to restored),
        )
        return restored
    }

    /**
     * Deletes every backup copy, on both the success and the failure path.
     *
     * Called from a `finally` in the caller, so it must not throw: a cleanup failure would replace a
     * migration error the user needs to see with a litter error they cannot act on.
     */
    fun cleanup(mappings: List<Pair<File, File>>) {
        mappings.forEach { (_, backup) ->
            if (backup.exists()) {
                backup.delete()
            }
        }
    }
}

/**
 * Passphrase-setup ViewModel: loads the existing plaintext DB summary, then
 * either encrypts it in place under a new passphrase or (destructive path)
 * wipes local data before configuring encryption.
 *
 * PROMOTED FROM `:app` (DB-VM-PROMOTION wave 1c). Every Android type this file used to name is gone rather
 * than relocated, and each of them left through a contract that already existed:
 *  - `Context` - gone. The database's location arrives as [DatabasePathResolver], which is the ONE place a
 *    shared ViewModel is allowed to learn it; the old `context.getDatabasePath` call sites now ask that
 *    resolver, so the profile-specific databases directory is still the platform's own answer.
 *  - `DatabaseEncryptionMigrationSupport` - gone, and specifically NOT through the JDBC twin. That class is
 *    in `:core:database`, an Android library that `:core:presentation` can never take (it already depends on
 *    this module for its tests). The rules it held are shared behind [DatabaseEncryptionProbe], which is what
 *    [DatabaseImportIntegritySupport] already uses, so there is now one implementation per platform rather
 *    than a second one to keep in step. The `Context.cacheDir` that Android's rekey needs stays inside the
 *    adapter, which is why no new port was required - and why the desktop's refusal to rekey a plaintext
 *    file arrives here as a thrown [UnsupportedOperationException] that the migration reports, never as a
 *    silent no-op that would let the count comparison pass over a conversion that never happened.
 *  - `PassphrasePolicy` - gone, in favour of [SharedPassphrasePolicy], which is the same twelve-character
 *    plus four-character-class rule with the same five reason codes. The Android one still exists for
 *    `DatabasePassphraseChangeViewModel`, which has not moved yet.
 *  - `DatabaseEncryptionManager` - gone, split across the two ports that own its behaviour: the passphrase
 *    in [PassphraseVault], the reset in [EncryptionStateCheckpoint].
 *
 * NO `androidx.lifecycle` AND NO HILT: the class no longer extends `ViewModel`, so `hiltViewModel()` cannot
 * build it and the platform constructs it at the one call site, through an entry point, exactly as
 * [DatabasePassphraseUnlockViewModel] and [DatabaseInitViewModel] already are.
 */
class DatabasePassphraseSetupViewModel(
    /** Where this platform's database lives. The single replacement for three `Context.getDatabasePath` calls. */
    private val databasePathResolver: DatabasePathResolver,
    /**
     * The platform's own handle for reading and re-encrypting that file.
     *
     * Android answers through SQLCipher and a `Context`; the desktop through JDBC, and refuses the
     * plaintext-to-encrypted half outright rather than approximating it.
     */
    private val encryptionProbe: DatabaseEncryptionProbe,
    /**
     * Where the passphrase is stored. Replaces `DatabaseEncryptionManager.configurePassphrase`, whose
     * boolean could not tell a policy rejection from an unwritable store - see
     * [reasonCodeForConfigureOutcome].
     */
    private val passphraseVault: PassphraseVault,
    /**
     * The abandon path for encryption state, as the [EncryptionCheckpointOutcome] the contract is written
     * in rather than the manager's lossy boolean - see [reasonCodeForWipeOutcome].
     */
    private val encryptionStateCheckpoint: EncryptionStateCheckpoint,
    /**
     * The coroutine scope for this ViewModel's work.
     *
     * A parameter rather than `viewModelScope` because this module forbids `androidx.lifecycle` by design.
     * The platform supplies the scope and calls [dispose] when it releases the ViewModel.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Dispatcher for the blocking work - the row counts, the file copies and the re-encryption.
     *
     * A constructor parameter for the reason [DatabasePassphraseUnlockViewModel] is not: a test in this
     * module is a separate source set, so a dispatcher fixed at construction is the only one virtual time
     * can actually track.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val logger = AppLogger.current
    private val _uiState = MutableStateFlow(DatabasePassphraseSetupUiState())
    val uiState: StateFlow<DatabasePassphraseSetupUiState> = _uiState.asStateFlow()

    /** The tables whose counts are compared across the re-encryption, and shown in the summary. */
    private val trackedTables = listOf("tasks", "time_entries", "day_journal_entries", "journal_notes", "notes")

    init {
        loadDatabaseSummary()
    }

    /**
     * Validates + persists a new passphrase, encrypting the existing plaintext
     * database in place (with count-validated backup/rollback on failure).
     */
    fun configurePassphrase(
        passphrase: String,
        confirmPassphrase: String,
        onSuccess: () -> Unit,
    ) {
        val validation = SharedPassphrasePolicy.validate(passphrase)
        if (!validation.isValid) {
            _uiState.update {
                it.copy(errorReasonCode = validation.reasonCode)
            }
            return
        }
        if (passphrase != confirmPassphrase) {
            _uiState.update {
                it.copy(errorReasonCode = "mismatch")
            }
            return
        }

        scope.launch {
            _uiState.update { it.copy(isSaving = true, errorReasonCode = null) }
            val migrationSucceeded = runCatching {
                withContext(ioDispatcher) { migrateExistingDatabaseIfNeeded(passphrase) }
                true
            }.getOrElse { error ->
                val reasonCode = if (error.message?.contains("different key or unreadable", ignoreCase = true) == true) {
                    "migration_incompatible"
                } else {
                    "migration_failed"
                }
                logger.e(
                    "DatabasePassphraseSetupViewModel.configurePassphrase",
                    "Passphrase setup failed during database migration",
                    error,
                )
                _uiState.update { it.copy(isSaving = false, errorReasonCode = reasonCode) }
                false
            }
            if (!migrationSucceeded) return@launch
            val passphraseChars = passphrase.toCharArray()
            val outcome = withContext(ioDispatcher) { passphraseVault.configurePassphrase(passphraseChars) }
            passphraseChars.fill('\u0000')
            val failureReasonCode = reasonCodeForConfigureOutcome(outcome)
            if (failureReasonCode != null) {
                logger.w(
                    "DatabasePassphraseSetupViewModel.configurePassphrase",
                    "Passphrase was not stored",
                    mapOf("outcome" to outcome.name),
                )
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorReasonCode = failureReasonCode,
                    )
                }
                return@launch
            }
            logger.i(
                "DatabasePassphraseSetupViewModel.configurePassphrase",
                "Passphrase setup completed",
            )
            _uiState.update { it.copy(isSaving = false, errorReasonCode = null) }
            onSuccess()
        }
    }

    /**
     * Clears the current error reason code shown on the screen.
     */
    fun clearError() {
        _uiState.update { it.copy(errorReasonCode = null) }
    }

    /**
     * Destructive alternative: wipes all local database artifacts and encryption
     * state, then configures the new passphrase on a clean slate.
     */
    fun resetLocalDataAndConfigurePassphrase(
        passphrase: String,
        confirmPassphrase: String,
        onSuccess: () -> Unit,
    ) {
        val validation = SharedPassphrasePolicy.validate(passphrase)
        if (!validation.isValid) {
            _uiState.update { it.copy(errorReasonCode = validation.reasonCode) }
            return
        }
        if (passphrase != confirmPassphrase) {
            _uiState.update { it.copy(errorReasonCode = "mismatch") }
            return
        }

        scope.launch {
            _uiState.update { it.copy(isSaving = true, errorReasonCode = null) }
            val resetOk = runCatching {
                deleteAllDatabaseFiles()
                reasonCodeForWipeOutcome(encryptionStateCheckpoint.wipeEncryptionState())
            }.getOrElse { error ->
                logger.e(
                    "DatabasePassphraseSetupViewModel.resetLocalDataAndConfigurePassphrase",
                    "Failed to reset local data before passphrase setup",
                    error,
                )
                "reset_failed"
            }
            if (resetOk != null) {
                _uiState.update { it.copy(isSaving = false, errorReasonCode = resetOk) }
                return@launch
            }
            val passphraseChars = passphrase.toCharArray()
            val outcome = withContext(ioDispatcher) { passphraseVault.configurePassphrase(passphraseChars) }
            passphraseChars.fill('\u0000')
            val failureReasonCode = reasonCodeForConfigureOutcome(outcome)
            if (failureReasonCode != null) {
                _uiState.update { it.copy(isSaving = false, errorReasonCode = failureReasonCode) }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isSaving = false,
                    errorReasonCode = null,
                    hasExistingLocalDatabase = false,
                )
            }
            onSuccess()
        }
    }

    /**
     * Releases the coroutine scope this ViewModel created.
     *
     * `onCleared` used to do this: it was the androidx lifecycle hook, and this class no longer has one.
     * The platform calls this where it used to call that - Android through the release action it registers
     * when it retains the ViewModel, the desktop from a `DisposableEffect`.
     */
    fun dispose() {
        scope.cancel()
    }

    /**
     * Re-encrypts the existing database in place, refusing the whole operation if the table counts do not
     * survive it.
     *
     * The order is the safety property: backup the family, convert, count again, and roll back the family on
     * any failure. A caller that reached the vault after a half-converted database would be configuring a
     * passphrase over rows the count check exists to prove are still there.
     *
     * The re-encryption runs inside the `try` rather than before it, so a platform that REFUSES it - which
     * the desktop does, with [UnsupportedOperationException] - goes down the rollback path like any other
     * failure instead of being reported as a success over a plaintext file.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: DB migration + backup restore pipeline
    private fun migrateExistingDatabaseIfNeeded(passphrase: String) {
        val dbFile = File(databasePathResolver.databaseFilePath())
        if (!dbFile.exists()) {
            return
        }
        val preCounts = readCounts(dbFile, passphraseOrNull = null)
        val artifacts = getDatabaseArtifactFiles(dbFile).filter { it.exists() }
        val backupMappings = DatabaseArtifactBackupSupport.backup(artifacts)
        try {
            encryptionProbe.ensureEncrypted(
                databaseFile = dbFile,
                passphrase = passphrase,
                logTag = "DatabasePassphraseSetupViewModel.migrateExistingDatabaseIfNeeded",
            )
            val postCounts = readCounts(dbFile, passphraseOrNull = passphrase)
            val tablesToValidate = listOf("tasks", "time_entries", "notes", "day_journal_entries", "journal_notes")
            val mismatch = tablesToValidate.any { table ->
                (preCounts[table] ?: 0) != (postCounts[table] ?: 0)
            }
            if (mismatch) {
                logger.w(
                    "DatabasePassphraseSetupViewModel.migrateExistingDatabaseIfNeeded",
                    "Pre/post migration counts mismatch; rolling back",
                    mapOf("preCounts" to preCounts, "postCounts" to postCounts),
                )
                throw IllegalStateException("Migration count validation failed.")
            }
        } catch (error: Exception) {
            DatabaseArtifactBackupSupport.restore(backupMappings)
            throw error
        } finally {
            DatabaseArtifactBackupSupport.cleanup(backupMappings)
        }
    }

    /** The artifact family this platform's database belongs to, which follows the file and not a name. */
    private fun getDatabaseArtifactFiles(dbFile: File): List<File> = DatabaseArtifacts.filesFor(dbFile)

    /** The one call every count in this class makes, so the tracked table list cannot drift between them. */
    private fun readCounts(
        databaseFile: File,
        passphraseOrNull: String?,
    ): Map<String, Int> = encryptionProbe.readTableCounts(
        databaseFile = databaseFile,
        passphrase = passphraseOrNull,
        tableNames = trackedTables,
    )

    private fun deleteAllDatabaseFiles(): Int {
        val dbFile = File(databasePathResolver.databaseFilePath())
        val files = DatabaseArtifacts.filesFor(dbFile)
        var deletedCount = 0
        files.forEach { file ->
            if (file.exists() && file.delete()) {
                deletedCount++
            }
        }
        logger.w(
            "DatabasePassphraseSetupViewModel.deleteAllDatabaseFiles",
            "Deleted local database artifacts during setup recovery",
            mapOf("deletedCount" to deletedCount),
        )
        return deletedCount
    }

    private fun loadDatabaseSummary() {
        // Read on IO, published on this ViewModel's own scope - the same split
        // [DatabasePassphraseUnlockViewModel.loadDatabaseSummary] uses, and for the same reason: a summary
        // written straight to the flow from a worker thread lands at a moment the caller cannot observe, which
        // makes the first frame of the setup screen nondeterministic.
        scope.launch {
            val dbFile = File(databasePathResolver.databaseFilePath())
            if (!dbFile.exists()) {
                _uiState.update {
                    it.copy(
                        hasExistingLocalDatabase = false,
                        databaseSizeKb = 0L,
                        databaseLastModifiedMs = 0L,
                        taskCount = 0,
                        timeEntryCount = 0,
                        journalCount = 0,
                        noteCount = 0,
                        storageModeLabelKey = "plaintext",
                    )
                }
                return@launch
            }
            val counts =
                runCatching { withContext(ioDispatcher) { readCounts(dbFile, passphraseOrNull = null) } }
                    .getOrElse { error ->
                        // The file is there — `exists()` already answered that — so the summary still says
                        // so, with the counts left at zero rather than the read reported as absent. Publishing
                        // `hasExistingLocalDatabase = false` here would tell the user their data is gone,
                        // which is a different and much worse claim than "we could not read it".
                        //
                        // It is CAUGHT rather than allowed to escape because this runs inside the ViewModel's
                        // own scope: an exception thrown from a scope's child cancels that scope, so an
                        // unreadable database at construction time would leave every later action - including
                        // the passphrase submit this screen exists for - silently doing nothing. The `:app`
                        // version could not have this problem only because it read on the caller's thread,
                        // where the failure was a crash rather than a dead ViewModel.
                        logger.e(
                            "DatabasePassphraseSetupViewModel.loadDatabaseSummary",
                            "Existing database could not be counted; showing it as present with no counts",
                            error,
                            mapOf("path" to dbFile.absolutePath),
                        )
                        emptyMap()
                    }
            _uiState.update {
                it.copy(
                    hasExistingLocalDatabase = true,
                    databaseSizeKb = dbFile.length() / 1024,
                    databaseLastModifiedMs = dbFile.lastModified(),
                    taskCount = counts["tasks"] ?: 0,
                    timeEntryCount = counts["time_entries"] ?: 0,
                    journalCount = (counts["day_journal_entries"] ?: 0) + (counts["journal_notes"] ?: 0),
                    noteCount = counts["notes"] ?: 0,
                    storageModeLabelKey = "plaintext",
                )
            }
        }
    }
}

/**
 * The one-line entry point a platform's setup gate calls to get the ViewModel.
 *
 * Every collaborator is supplied by the caller, for the reason
 * [io.payanam.presentation.database.DatabasePassphraseUnlockViewModel]'s factory is: the vault, the probe and
 * the checkpoint are platform implementations of shared contracts, and the database path is the platform's
 * own. A factory that resolved them from a `LocalDataLayer` would be inventing a second installation seam
 * for this one screen.
 */
fun passphraseSetupViewModel(
    databasePathResolver: DatabasePathResolver,
    encryptionProbe: DatabaseEncryptionProbe,
    passphraseVault: PassphraseVault,
    encryptionStateCheckpoint: EncryptionStateCheckpoint,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): DatabasePassphraseSetupViewModel =
    DatabasePassphraseSetupViewModel(
        databasePathResolver = databasePathResolver,
        encryptionProbe = encryptionProbe,
        passphraseVault = passphraseVault,
        encryptionStateCheckpoint = encryptionStateCheckpoint,
        scope = scope,
    )
