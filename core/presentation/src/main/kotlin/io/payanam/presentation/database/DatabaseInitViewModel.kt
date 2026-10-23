//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "SwallowedException")

package io.payanam.presentation.database

import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.port.DatabasePathResolver
import io.payanam.domain.port.DatabaseSessionManager
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.presentation.database.DatabaseImportSupport
import io.payanam.presentation.database.DatabaseInitFlagReader
import io.payanam.presentation.database.DatabaseInitFlagWriter
import io.payanam.presentation.database.DatabaseInitWalConsolidator
import io.payanam.presentation.database.NewDatabaseDimensionInput
import io.payanam.presentation.database.dbInitClassifyBootIssue
import io.payanam.presentation.database.dbInitCreateSidecarSafeTempBackup
import io.payanam.presentation.database.dbInitDeleteAllFiles
import io.payanam.presentation.database.dbInitDeleteTempBackup
import io.payanam.presentation.database.dbInitGetArtifactFiles
import io.payanam.presentation.database.dbInitMarkInitCompletedDirect
import io.payanam.presentation.database.dbInitReadInitCompletedFlag
import io.payanam.presentation.database.dbInitRestoreFromTempBackup
import io.payanam.presentation.preferences.AppPreferenceKeys
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.database.DatabaseHealthCheck
import io.payanam.shared.database.DatabaseHealthReport
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.logging.CrashBreadcrumbs
import io.payanam.shared.platform.DocumentSource
import io.payanam.shared.security.EncryptionStateCheckpoint
import io.payanam.shared.security.PassphraseConfigureOutcome
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
import java.util.Date

/**
 * Outcome of resuming/completing a database import: [RestoredOk] if the
 * pre-import backup was successfully restored after a failure, else
 * [RestoreFailed].
 */
sealed class RestoreResult {
    object RestoredOk : RestoreResult()

    object RestoreFailed : RestoreResult()
}

/**
 * Full UI state for the database-initialization screen: health/status of any
 * existing DB, counts, import/create progress and confirmation prompts, restore
 * outcome, and the awaiting-passphrase/awaiting-dimension-setup gates.
 */
data class DatabaseInitUiState(
    val isChecking: Boolean = true,
    val databaseExists: Boolean = false,
    val databaseCorrupted: Boolean = false,
    val bootIssue: DatabaseBootIssue? = null,
    val corruptionMessage: String? = null,
    val databaseSizeKB: Long = 0,
    val lastModified: Long? = null,
    val taskCount: Int = 0,
    val timeEntryCount: Int = 0,
    val journeyEntryCount: Int = 0,
    val noteCount: Int = 0,
    val databaseCreated: Long? = null,
    val databaseSchemaVersion: Int = 0,
    val isCreating: Boolean = false,
    val isImporting: Boolean = false,
    val errorMessage: String? = null,
    val showCreateNewWipeConfirm: Boolean = false,
    val showImportWipeConfirm: Boolean = false,
    val restoreResult: RestoreResult? = null,
    val awaitingImportPassphrase: Boolean = false,
    val importPassphraseError: String? = null,
    val awaitingDimensionSetup: Boolean = false,
    /**
     * True when a previous run got as far as confirming life dimensions and was quit before the
     * confirmation reached a database.
     *
     * EXISTS because the confirmation is NOT durable before the write, and losing it silently is the
     * one outcome the user cannot recover from: the screen reopens with the defaults and no hint that
     * the rows they had just chosen are gone. A quit between [completeNewDatabaseDimensionSetup]'s
     * `openDatabase` and its `dimensionSetupPersister.persist` - the dangerous point, because the file
     * exists by then but the catalog does not - leaves the vault configured and the catalog empty, and
     * nothing on the next launch could distinguish that from a run that never got there.
     *
     * The mark is written through [AppSettingsRepository] as a FIRST-RUN intent row, which is the same
     * durable place this flow already records `database_init_completed`, so no new storage exists for
     * it. It is set when the dimensions are confirmed and cleared only once the catalog write landed,
     * so "still set" after a restart means exactly one thing: the confirmation was made and lost.
     *
     * NOT the same fact as `databaseExists`. That is the file's presence, which is true for an imported
     * database and for a wiped placeholder alike; this is "this install was mid-first-run".
     */
    val firstRunDimensionsNotSaved: Boolean = false,
)

/**
 * Per-table row counts of an existing database, as the init screen shows them.
 *
 * Lived beside the (now-deleted) import helpers; it is the one declaration of that file the
 * ViewModel actually needed, so it moved here with its only reader.
 */
internal data class DatabaseTableCounts(
    val taskCount: Int,
    val timeEntryCount: Int,
    val journalEntryCount: Int,
    val noteCount: Int,
)

/**
 * ViewModel for the first-run database-initialization screen: checks the
 * health of any existing DB, and drives the create-new / import / encrypted-
 * import-resume flows (each with wipe confirmation, safety backup, and
 * restore-on-failure). Also owns the mandatory life-dimension setup gate.
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
class DatabaseInitViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val databaseSessionManager: DatabaseSessionManager,
    private val lifeDimensionCatalogRepository: LifeDimensionCatalogRepository,
    /**
     * The copy table for the user's language, supplied by the platform.
     *
     * A parameter rather than a `Context.getString` call because this ViewModel is moving into
     * `:core:presentation`, a plain JVM module that cannot see Android's R class. Each platform resolves the
     * language the same way the shared screens already do.
     */
    private val strings: UiStrings,
    /**
     * The database health check, and the path it is asked about.
     *
     * Both arrive as parameters rather than being reached for through a `Context`, which is what lets this
     * class leave `:app`: the questions are shared, and each platform answers them for its own file.
     */
    private val healthCheck: DatabaseHealthCheck,
    private val databasePathResolver: DatabasePathResolver,
    /**
     * Whether a picked file is encrypted, and whether a passphrase opens it.
     *
     * The questions are shared; the handle is the platform's. The desktop REFUSES re-encryption rather than
     * approximating Android's conversion, which is why the contract carries that as a method at all.
     */
    private val encryptionProbe: DatabaseEncryptionProbe,
    /**
     * The platform's answer to "give me the file the user picked", over SAF.
     *
     * A parameter rather than a `Context` because this ViewModel is moving into `:core:presentation`,
     * a plain JVM module that cannot see Android's `ContentResolver`. Each platform supplies its own
     * implementation.
     */
    private val documentSource: DocumentSource,
    /**
     * The platform's answer to "mark DB-init as completed in the database file".
     *
     * A parameter rather than a `Context` because this ViewModel is moving into `:core:presentation`,
     * a plain JVM module that cannot see Android's `SQLiteDatabase`. Each platform supplies its own
     * implementation.
     */
    private val databaseInitFlagWriter: DatabaseInitFlagWriter,
    /**
     * The platform's answer to "read one app_settings value out of the database file".
     *
     * A parameter rather than a factory built here, because the factory lives in `:app` and a field
     * initialiser calling it would keep this class from ever leaving `:app`. The read happens before a
     * session exists, so it cannot go through `AppSettingsRepository`.
     */
    private val databaseInitFlagReader: DatabaseInitFlagReader,
    /**
     * The platform's WAL checkpoint on a temp copy.
     *
     * A parameter for the reader's reason, and one more: the `:app` factory needs a `UnifiedLogger`, which a
     * shared module cannot see - and reaching for that singleton during construction would throw in any test
     * that had not initialised logging first.
     */
    private val databaseInitWalConsolidator: DatabaseInitWalConsolidator,
    /**
     * The platform's writer for the new database's mandatory life-dimension seed.
     *
     * A port and not a call into the writer directly, because that writer builds its seed rows with
     * helpers that live in `:core:ui` - so it stays in `:app` while this class does not.
     */
    private val dimensionSetupPersister: DatabaseDimensionSetupPersister,
    /**
     * The platform's encryption state checkpoint: backup, restore, discard, and wipe.
     *
     * A parameter rather than a concrete `DatabaseEncryptionManager` because this ViewModel is moving into
     * `:core:presentation`, which does not depend on `:core:database`. The contract covers the four
     * checkpoint operations the import flow needs.
     */
    private val encryptionStateCheckpoint: EncryptionStateCheckpoint,
    /**
     * The platform's passphrase vault: whether encryption is enabled, and whether a passphrase configures it.
     *
     * A parameter rather than a concrete `DatabaseEncryptionManager` because this ViewModel is moving into
     * `:core:presentation`, which does not depend on `:core:database`. The contract covers the two
     * operations the import flow needs.
     */
    private val passphraseVault: PassphraseVault,
    /**
     * The coroutine scope for this ViewModel's work.
     *
     * A parameter rather than `viewModelScope` because this ViewModel is moving into `:core:presentation`,
     * which forbids `androidx.lifecycle` by design. The platform supplies the scope.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /**
     * Dispatcher for the blocking work - the encryption manager's file I/O, the database session's
     * open/close, and the WAL checkpoint.
     *
     * A constructor parameter rather than a mutable `internal var` for the reason
     * [io.payanam.presentation.database.DatabasePassphraseUnlockViewModel.ioDispatcher] is not: that one
     * is mutated after construction by a test in this module, and a constructor value cannot be re-pointed
     * by a test compiled as a SEPARATE source set - which is exactly how this class's test is compiled.
     * Fixed at construction it stays the same idea either way: `ioDispatcher` is a real thread pool no
     * test dispatcher tracks, so a test that idled virtual time would read the state before the work landed.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = AppLogger.current
    private val _uiState = MutableStateFlow(DatabaseInitUiState())
    val uiState: StateFlow<DatabaseInitUiState> = _uiState.asStateFlow()

    private var pendingImportDbFile: File? = null
    private var pendingImportBackupMappings: List<Pair<File, File>> = emptyList()
    private var pendingImportTempBackupDir: File? = null

    /**
     * The staged import source, as a [io.payanam.shared.platform.DocumentSource] TOKEN rather than a
     * `Uri`. The `Uri` stops at this ViewModel's own boundary (the screen and the SAF launcher are
     * Android's); everything below it speaks the shared token, so the same staged import can be resumed
     * by a platform that never had a `Uri` to begin with.
     */
    private var pendingImportSourceToken: String? = null
    private var pendingImportOnSuccess: (() -> Unit)? = null
    private var pendingCreatePassphrase: String? = null
    private var pendingCreateNeedsWipe: Boolean = false
    val importDbFile: File? get() = pendingImportDbFile

    private fun clearPendingImport() {
        pendingImportDbFile = null
        pendingImportBackupMappings = emptyList()
        pendingImportTempBackupDir = null
        pendingImportSourceToken = null
        pendingImportOnSuccess = null
    }

    private fun clearPendingCreate() {
        pendingCreatePassphrase = null
        pendingCreateNeedsWipe = false
    }

    private fun breadcrumb(
        stage: String,
        data: Map<String, Any?>? = null,
    ) {
        // orEmpty(), not a signature change: the shared record() takes a non-null map because that is the
        // honest shape of "no data" on the port, and this helper's callers are not required to pass one.
        CrashBreadcrumbs.record(
            source = "DatabaseInitViewModel",
            stage = stage,
            data = data.orEmpty(),
        )
    }

    init {
        logger.i("DatabaseInitViewModel.init", "Checking database status")
        checkDatabaseStatus()
    }

    private suspend fun detectBootstrapPlaceholder(dbFile: java.io.File): Boolean {
        // A file question, answered by the file: a bootstrap placeholder cannot be detected in a file
        // that is detectably encrypted, and that is true of THIS file regardless of the platform mode.
        val isEncrypted = encryptionProbe.isDetectablyEncrypted(dbFile)
        if (isEncrypted) return false
        val databaseInitCompleted = readDatabaseInitCompletedFlag(dbFile)
        val counts =
            withContext(ioDispatcher) {
                val countMap =
                    encryptionProbe.readTableCounts(
                        databaseFile = dbFile,
                        passphrase = null,
                        tableNames =
                            listOf(
                                "tasks",
                                "time_entries",
                                "day_journal_entries",
                                "journal_notes",
                                "notes",
                            ),
                    )
                DatabaseTableCounts(
                    taskCount = countMap["tasks"] ?: 0,
                    timeEntryCount = countMap["time_entries"] ?: 0,
                    journalEntryCount = (countMap["day_journal_entries"] ?: 0) + (countMap["journal_notes"] ?: 0),
                    noteCount = countMap["notes"] ?: 0,
                )
            }
        logger.i(
            "DatabaseInitViewModel.detectBootstrapPlaceholder",
            "Database counts",
            mapOf(
                "taskCount" to counts.taskCount,
                "timeEntryCount" to counts.timeEntryCount,
                "journalEntryCount" to counts.journalEntryCount,
                "noteCount" to counts.noteCount,
            ),
        )
        val hasUserData =
            counts.taskCount > 0 || counts.timeEntryCount > 0 ||
                counts.journalEntryCount > 0 || counts.noteCount > 0
        return !databaseInitCompleted && !hasUserData
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    private fun checkDatabaseStatus() {
        scope.launch {
            _uiState.update { it.copy(isChecking = true) }

            try {
                val healthResult = healthCheck.check(File(databasePathResolver.databaseFilePath()), null)

                logger.i(
                    "DatabaseInitViewModel.checkDatabaseStatus",
                    "Health check complete",
                    mapOf(
                        "isHealthy" to healthResult.isHealthy,
                        "needsRepair" to healthResult.needsRepair,
                        "errorMessage" to (healthResult.errorMessage ?: "N/A"),
                    ),
                )
                if (!healthResult.isHealthy) {
                    val dbFile = File(databasePathResolver.databaseFilePath())
                    val exists = healthCheck.hasArtifacts(File(databasePathResolver.databaseFilePath()))

                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            databaseExists = exists,
                            databaseCorrupted = exists && healthResult.needsRepair,
                            bootIssue = classifyBootIssue(exists, healthResult),
                            corruptionMessage = healthResult.errorMessage,
                            databaseSizeKB = if (exists) dbFile.length() / 1024 else 0,
                            errorMessage = null,
                        )
                    }
                    return@launch
                }
                val dbFile = File(databasePathResolver.databaseFilePath())
                val exists = healthCheck.hasArtifacts(File(databasePathResolver.databaseFilePath()))
                // Read on EVERY launch, before any branch: this is the one place that runs whether or not
                // a database was found, and it is where a lost first-run confirmation can be told from a
                // first launch that never got that far. A no-session read throws, so the answer is
                // `false` - "nothing to report" - rather than a guess that the user is told a
                // confirmation was lost when none was made.
                val firstRunDimensionsNotSaved = readFirstRunDimensionsPending()
                if (exists) {
                    val sizeKB = dbFile.length() / 1024
                    val lastModified = dbFile.lastModified()

                    logger.i(
                        "DatabaseInitViewModel.checkDatabaseStatus",
                        "Database file info",
                        mapOf("sizeKB" to sizeKB, "lastModified" to lastModified, "lastModifiedDate" to Date(lastModified).toString()),
                    )
                    val isBootstrapPlaceholder = detectBootstrapPlaceholder(dbFile)
                    if (isBootstrapPlaceholder) {
                        logger.w(
                            "DatabaseInitViewModel.checkDatabaseStatus",
                            "Detected bootstrap placeholder DB created before init choice; treating as no existing user database",
                            mapOf(
                                "sizeKB" to sizeKB,
                                "lastModified" to lastModified,
                            ),
                        )
                    }

                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            databaseExists = !isBootstrapPlaceholder,
                            databaseCorrupted = false,
                            bootIssue = null,
                            corruptionMessage = null,
                            databaseSizeKB = if (isBootstrapPlaceholder) 0 else sizeKB,
                            lastModified = if (isBootstrapPlaceholder) null else lastModified,
                            taskCount = 0,
                            timeEntryCount = 0,
                            journeyEntryCount = 0,
                            noteCount = 0,
                            // The shared report deliberately carries no version field - only Android's
                            // migration path reads one. The number this screen shows is the schema this
                            // build expects, which is a shared fact.
                            databaseSchemaVersion = PayanamDatabaseSchema.VERSION,
                            errorMessage = null,
                            firstRunDimensionsNotSaved = firstRunDimensionsNotSaved,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isChecking = false,
                            databaseExists = false,
                            databaseCorrupted = false,
                            bootIssue = null,
                            corruptionMessage = null,
                            firstRunDimensionsNotSaved = firstRunDimensionsNotSaved,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("DatabaseInitViewModel.checkDatabaseStatus", "Failed to check database", e)
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        databaseExists = false,
                        databaseCorrupted = false,
                        bootIssue = null,
                        errorMessage = "Error checking database: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Re-runs the database health/status check (used by the retry button after
     * a failure or corruption report).
     */
    fun retryDatabaseStatusCheck() {
        logger.i("DatabaseInitViewModel.retryDatabaseStatusCheck", "Retrying database status check")
        checkDatabaseStatus()
    }

    /**
     * Starts the "create new database" flow with [passphrase]: if existing DB
     * artifacts are present, asks for wipe confirmation first, otherwise goes
     * straight to mandatory dimension setup.
     */
    fun createNewDatabase(passphrase: String) {
        logger.i("DatabaseInitViewModel.createNewDatabase", "Create new database requested")
        val existingFiles = getDatabaseArtifactFiles().filter { it.exists() }
        if (existingFiles.isNotEmpty()) {
            logger.i(
                "DatabaseInitViewModel.createNewDatabase",
                "Existing DB artifacts found; showing wipe confirm",
                mapOf("fileCount" to existingFiles.size),
            )
            _uiState.update { it.copy(showCreateNewWipeConfirm = true) }
            return
        }
        beginMandatoryDimensionSetup(passphrase = passphrase, needsWipe = false)
    }

    /**
     * User confirmed the wipe-then-create: proceeds to mandatory dimension
     * setup with [passphrase] and [needsWipe] = true.
     */
    fun confirmCreateNew(passphrase: String) {
        logger.i("DatabaseInitViewModel.confirmCreateNew", "User confirmed create new with wipe")
        _uiState.update { it.copy(showCreateNewWipeConfirm = false) }
        beginMandatoryDimensionSetup(passphrase = passphrase, needsWipe = true)
    }

    /**
     * Dismisses the create-new wipe confirmation prompt without wiping.
     */
    fun cancelCreateNewWipe() {
        logger.i("DatabaseInitViewModel.cancelCreateNewWipe", "User cancelled create new wipe confirm")
        _uiState.update { it.copy(showCreateNewWipeConfirm = false) }
    }

    /**
     * Clears the shown restore outcome and re-checks DB status.
     */
    fun dismissRestoreResult() {
        _uiState.update { it.copy(restoreResult = null) }
        checkDatabaseStatus()
    }

    private fun beginMandatoryDimensionSetup(
        passphrase: String,
        needsWipe: Boolean,
    ) {
        pendingCreatePassphrase = passphrase
        pendingCreateNeedsWipe = needsWipe
        logger.i(
            "DatabaseInitViewModel.beginMandatoryDimensionSetup",
            "Passphrase accepted; waiting for mandatory dimension setup before DB creation",
            mapOf("needsWipe" to needsWipe),
        )
        _uiState.update {
            it.copy(
                isCreating = false,
                errorMessage = null,
                restoreResult = null,
                awaitingDimensionSetup = true,
            )
        }
    }

    /**
     * Records that the user CONFIRMED dimensions whose catalog write has not landed yet.
     *
     * The write is the whole point: [completeNewDatabaseDimensionSetup] creates the database and seeds
     * the catalog in one coroutine, so a quit between `openDatabase` and `dimensionSetupPersister.persist`
     * leaves a database that exists and a catalog that is empty. The rows are gone and cannot be
     * recovered - there was no place to have kept them - but this mark CAN be kept, and its presence on
     * the next launch is what turns a silent loss into a sentence the user reads.
     *
     * Written through [AppSettingsRepository] on the SAME repository the catalog write uses, and
     * cleared in [completeNewDatabaseDimensionSetup] only after that write landed. A failure to write it
     * is logged and swallowed rather than thrown: refusing to create a database because a warning row
     * could not be stored would trade a lost confirmation for a lost install, and the confirmation is
     * the smaller loss.
     *
     * Best-effort by construction, and that is the honest limit of this slice: it makes the loss
     * VISIBLE, it does not prevent it. Making it impossible needs a durable home for the rows before a
     * database exists, and the pre-session data layer has none by design - it refuses every write
     * rather than pretend one landed.
     */
    private suspend fun markFirstRunDimensionsPending() {
        runCatching {
            appSettingsRepository.setSetting(AppPreferenceKeys.KEY_FIRST_RUN_DIMENSIONS_PENDING, "true")
        }.onFailure { failure ->
            logger.w(
                "DatabaseInitViewModel.markFirstRunDimensionsPending",
                "Could not record that first-run dimensions are unconfirmed; a quit now would lose them silently",
                mapOf("failure" to failure.javaClass.simpleName),
            )
        }
    }

    /** Clears the pending mark, once the catalog the user confirmed is actually in the database. */
    private suspend fun clearFirstRunDimensionsPending() {
        runCatching {
            appSettingsRepository.setSetting(AppPreferenceKeys.KEY_FIRST_RUN_DIMENSIONS_PENDING, "false")
        }.onFailure { failure ->
            logger.w(
                "DatabaseInitViewModel.clearFirstRunDimensionsPending",
                "Catalog written but the pending mark could not be cleared; the next launch will say the confirmation was lost",
                mapOf("failure" to failure.javaClass.simpleName),
            )
        }
    }

    /**
     * Whether a previous run confirmed dimensions and lost them, read from the pending mark.
     *
     * Asked during the status check, which is the one place that runs on EVERY launch including one
     * with no session - so it can use [appSettingsRepository] only because that repository is
     * session-backed and pre-session throws. That is not a problem here: a thrown read answers
     * "unknown", and the honest answer to unknown is to NOT claim the confirmation was lost, so the
     * failure path is `false` and only an actual stored mark produces the message.
     */
    private suspend fun readFirstRunDimensionsPending(): Boolean =
        runCatching {
            appSettingsRepository
                .getSetting(AppPreferenceKeys.KEY_FIRST_RUN_DIMENSIONS_PENDING)
                ?.toBoolean() ?: false
        }.getOrElse { failure ->
            logger.i(
                "DatabaseInitViewModel.readFirstRunDimensionsPending",
                "Pending first-run dimension mark could not be read before a session existed",
                mapOf("failure" to failure.javaClass.simpleName),
            )
            false
        }

    /**
     * Finalizes a new database: configures the passphrase, optionally wipes the
     * old artifacts (with a safety backup), opens the session, and persists the
     * mandatory life-dimension setup; on failure restores from the backup.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun completeNewDatabaseDimensionSetup(
        dimensionInputs: List<NewDatabaseDimensionInput>,
        onSuccess: () -> Unit,
    ) {
        logger.i(
            "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
            "Persisting mandatory life-dimension setup",
            mapOf(
                "inputCount" to dimensionInputs.size,
                "hasPendingPassphrase" to (pendingCreatePassphrase != null),
                "pendingNeedsWipe" to pendingCreateNeedsWipe,
            ),
        )
        scope.launch {
            _uiState.update { it.copy(isCreating = true, errorMessage = null) }
            // BEFORE the create, so the window the mark exists to cover is closed as early as it can be:
            // from here until the catalog write lands, a quit loses the confirmed rows, and this is what
            // makes the next launch say so instead of re-asking in silence.
            markFirstRunDimensionsPending()
            var tempBackupDir: File? = null
            try {
                withContext(ioDispatcher) {
                    val createPassphrase =
                        pendingCreatePassphrase
                            ?: throw IllegalStateException("Missing pending passphrase. Please set passphrase again.")
                    if (pendingCreateNeedsWipe) {
                        val existingFiles = getDatabaseArtifactFiles().filter { it.exists() }
                        if (existingFiles.isNotEmpty()) {
                            tempBackupDir = createSidecarSafeTempBackup()
                            if (tempBackupDir == null) {
                                throw IllegalStateException("Could not create a safe backup of the current database before wiping.")
                            }
                            logger.i(
                                "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
                                "Temp backup created before wipe",
                                mapOf("dir" to tempBackupDir!!.absolutePath),
                            )
                            deleteAllDatabaseFiles()
                        }
                    }
                    val outcome = passphraseVault.configurePassphrase(createPassphrase.toCharArray())
                    if (outcome !=
                        PassphraseConfigureOutcome.CONFIGURED
                    ) {
                        throw IllegalStateException("Failed to configure passphrase for new database")
                    }
                    val openResult = databaseSessionManager.openDatabase(createPassphrase)
                    openResult.getOrElse { throw IllegalStateException("Failed to open new DB session: ${it.message}", it) }
                    dimensionSetupPersister.persist(
                        lifeDimensionCatalogRepository = lifeDimensionCatalogRepository,
                        appSettingsRepository = appSettingsRepository,
                        dimensionInputs = dimensionInputs,
                    )
                    // AFTER the catalog write and only then: the mark exists to cover the window where a
                    // confirmation is held but unlanded, and clearing it before the write would leave a
                    // real loss looking like a clean run.
                    clearFirstRunDimensionsPending()
                    tempBackupDir?.let { deleteTempBackup(it) }
                    clearPendingCreate()
                }
                logger.i(
                    "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
                    "Dimension setup saved and DB init marked complete",
                )
                _uiState.update { it.copy(isCreating = false, awaitingDimensionSetup = false) }
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(
                    "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
                    "Failed to persist mandatory dimension setup",
                    e,
                )
                val restored =
                    withContext(ioDispatcher) {
                        encryptionStateCheckpoint.wipeEncryptionState()
                        databaseSessionManager.closeDatabase()
                        val dir = tempBackupDir
                        if (dir != null) restoreFromTempBackup(dir) else false
                    }
                // Keep the pending create passphrase so the user can retry the
                // dimension setup without re-entering the passphrase flow.
                logger.i(
                    "DatabaseInitViewModel.completeNewDatabaseDimensionSetup",
                    "Dimension setup failed; pending passphrase retained for retry",
                    mapOf(
                        "restoredFromBackup" to restored,
                        "pendingPassphraseRetained" to (pendingCreatePassphrase != null),
                    ),
                )
                _uiState.update { it.copy(isCreating = false, errorMessage = e.message) }
                if (tempBackupDir != null) {
                    _uiState.update {
                        it.copy(
                            restoreResult = if (restored) RestoreResult.RestoredOk else RestoreResult.RestoreFailed,
                        )
                    }
                }
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
     * Begins importing a database from [sourceToken]: if existing DB artifacts are
     * present, asks for wipe confirmation first, otherwise imports immediately;
     * [onSuccess] runs after a successful import.
     *
     * Only an ENCRYPTED database is importable. A plaintext one is refused at the header check
     * (the encrypted-only import rule): Payanam's export IS the encrypted file, so an unencrypted
     * database is a file no version of Payanam can produce, and accepting one would make this screen
     * the only route by which unencrypted data entered the app.
     */
    fun importDatabase(
        sourceToken: String,
        onSuccess: () -> Unit,
    ) {
        // Re-entry guard. `isImporting || awaitingImportPassphrase`, not `isImporting` alone: while the
        // encrypted-import passphrase prompt is open, `isImporting` is false but the staged state
        // (pendingImportDbFile / pendingImportTempBackupDir) is live, and a second import would
        // overwrite the only pre-import restore copy. The UI disables its controls too; this guard is
        // the belt-and-braces half so the invariant holds even if a future UI path forgets.
        if (uiState.value.isImporting || uiState.value.awaitingImportPassphrase) {
            logger.i(
                "DatabaseInitViewModel.importDatabase",
                "import_reentry_rejected: an import is already in progress; ignoring the new request",
                mapOf(
                    "isImporting" to uiState.value.isImporting.toString(),
                    "awaitingImportPassphrase" to uiState.value.awaitingImportPassphrase.toString(),
                    "sourceToken" to sourceToken,
                ),
            )
            return
        }
        logger.i(
            "DatabaseInitViewModel.importDatabase",
            "Import database requested",
            mapOf(
                "sourceToken" to sourceToken,
            ),
        )
        val existingFiles = getDatabaseArtifactFiles().filter { it.exists() }
        if (existingFiles.isNotEmpty()) {
            logger.i(
                "DatabaseInitViewModel.importDatabase",
                "Existing DB artifacts found; showing wipe confirm",
                mapOf("fileCount" to existingFiles.size),
            )
            pendingImportSourceToken = sourceToken
            pendingImportOnSuccess = onSuccess
            _uiState.update { it.copy(showImportWipeConfirm = true) }
            return
        }
        executeImportDatabase(sourceToken, onSuccess)
    }

    /**
     * User confirmed the wipe-then-import: runs the actual import of the
     * previously-staged source token into the app's database.
     */
    fun confirmImportAfterWipe(onSuccess: () -> Unit) {
        logger.i("DatabaseInitViewModel.confirmImportAfterWipe", "User confirmed import with wipe")
        _uiState.update { it.copy(showImportWipeConfirm = false) }
        val token = pendingImportSourceToken ?: return
        val cb = pendingImportOnSuccess ?: onSuccess
        pendingImportSourceToken = null
        pendingImportOnSuccess = null
        executeImportDatabase(token, cb)
    }

    /**
     * Dismisses the import wipe confirmation prompt without importing.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun cancelImportWipe() {
        logger.i("DatabaseInitViewModel.cancelImportWipe", "User cancelled import wipe confirm")
        pendingImportSourceToken = null
        pendingImportOnSuccess = null
        _uiState.update { it.copy(showImportWipeConfirm = false) }
    }

    private sealed class ImportIOResult {
        /**
         * The imported file is encrypted: import is paused so the UI can prompt
         * for the passphrase; [dbFile] and [tempBackupDir] are retained for resume.
         */
        data class NeedsPassphrase(
            val dbFile: File,
            val tempBackupDir: File?,
        ) : ImportIOResult()

        /**
         * The import finished successfully; [dbFile] is the finalized database and
         * [passphrase] is the session key (null in plaintext mode).
         *
         * DEAD SINCE THE ENCRYPTED-ONLY IMPORT RULE, AND LEFT IN PLACE DELIBERATELY. Nothing constructs
         * this any more: the only producer was the plaintext continuation in [executeImportIO], which
         * refused unencrypted databases and was removed with it. Every successful import now completes
         * through [resumeImportWithPassphrase], which does its own health check, marks the database
         * init completed, opens the session and discards the checkpoint before it calls back — so the
         * `is ImportIOResult.Completed` arm in [executeImportDatabase] is unreachable too.
         *
         * It is kept rather than deleted because it is the sealed hierarchy's declaration of the
         * SUCCESS outcome, and removing it would leave a public `onSuccess` parameter on
         * [importDatabase] and [confirmImportAfterWipe] that no code path could ever invoke — a
         * signature the shared screen and the desktop both bind to, silently doing nothing. Whoever
         * changes this next should decide the signature question deliberately rather than inherit a
         * dead branch that looks live.
         */
        data class Completed(
            val dbFile: File,
            val passphrase: String?,
        ) : ImportIOResult()

        /**
         * The import failed: [cause] is the error, [restoreAttempted]/
         * [restoreSucceeded] describe whether the pre-import backup was recovered.
         */
        data class Failed(
            val cause: Throwable,
            val restoreAttempted: Boolean,
            val restoreSucceeded: Boolean,
        ) : ImportIOResult()
    }

    /**
     * Performs the IO-intensive portion of database import: backup, copy, validate,
     * optionally re-encrypt, health-check, and session-open.
     * Returns a sealed result so the caller can update UI state without
     * deeply-nested try/catch/finally control flow.
     */
    private suspend fun executeImportIO(
        sourceToken: String,
        dbFile: File,
        existingDatabaseFiles: List<File>,
    ): ImportIOResult =
        withContext(ioDispatcher) {
            var tempBackupDir: File? = null
            var result: ImportIOResult = ImportIOResult.Failed(IllegalStateException("unreachable"), false, false)
            try {
                if (existingDatabaseFiles.isNotEmpty()) {
                    tempBackupDir = createSidecarSafeTempBackup()
                    if (tempBackupDir == null) {
                        throw IllegalStateException("Could not create a safe backup of the current database before importing.")
                    }
                    breadcrumb(
                        stage = "temp_backup_created",
                        data = mapOf("path" to tempBackupDir!!.absolutePath),
                    )
                    logger.i(
                        "DatabaseInitViewModel.executeImportIO",
                        "Temp backup created",
                        mapOf("dir" to tempBackupDir!!.absolutePath),
                    )
                }

                encryptionStateCheckpoint.captureCheckpoint()
                breadcrumb(stage = "encryption_prefs_backed_up")
                deleteAllDatabaseFiles()
                breadcrumb(stage = "runtime_artifacts_deleted")
                val copyResult =
                    DatabaseImportSupport.copyDatabaseArtifacts(
                        documentSource = documentSource,
                        sourceToken = sourceToken,
                        targetDatabaseFile = dbFile,
                        strings = strings,
                    )
                breadcrumb(
                    stage = "import_artifacts_copied",
                    data =
                        mapOf(
                            "sourceKind" to copyResult.sourceKind,
                            "primaryFileName" to copyResult.primaryFileName,
                            "companionFilesCopied" to copyResult.companionFilesCopied,
                        ),
                )
                logger.i(
                    "DatabaseInitViewModel.executeImportIO",
                    "Database file copied",
                    mapOf(
                        "bytesCopiedKB" to (copyResult.bytesCopied / 1024),
                        "filePath" to dbFile.absolutePath,
                        "sourceKind" to copyResult.sourceKind,
                        "primaryFileName" to copyResult.primaryFileName,
                        "companionFilesCopied" to copyResult.companionFilesCopied,
                    ),
                )
                if (!dbFile.exists() || dbFile.length() == 0L) {
                    throw Exception(strings.settingsImportErrorEmptyDb)
                }

                DatabaseImportSupport.consolidateWalAfterImport(
                    dbFile = dbFile,
                    logTag = "DatabaseInitViewModel.executeImportIO",
                    walConsolidator = walConsolidator,
                )
                breadcrumb(
                    stage = "wal_consolidation_done",
                    data = mapOf("dbSizeKB" to (dbFile.length() / 1024)),
                )
                val importedDbIsStandardSqlite =
                    DatabaseImportSupport.isStandardSqliteFile(
                        databaseFile = dbFile,
                        logTag = "DatabaseInitViewModel.executeImportIO",
                    )
                if (importedDbIsStandardSqlite) {
                    // The file carries the SQLite plaintext magic, so it is an UNENCRYPTED database, and
                    // Payanam exports IS the encrypted file - no version of Payanam can produce this one.
                    // Refused, never converted: converting it here would make this screen the only route by
                    // which unencrypted data entered the app. The throw leaves through the catch below,
                    // which restores the temp backup, so the database the user already had is put back
                    // byte-for-byte rather than left replaced by a file we just declined to accept.
                    logger.w(
                        "DatabaseInitViewModel.executeImportIO",
                        "Refusing an unencrypted database: Payanam imports encrypted databases only",
                        mapOf("file" to dbFile.name, "sizeBytes" to dbFile.length()),
                    )
                    breadcrumb(stage = "import_refused_plaintext", data = mapOf("file" to dbFile.name))
                    throw IllegalStateException(strings.settingsImportErrorPlaintextDb)
                }
                val isEncrypted = encryptionProbe.isDetectablyEncrypted(dbFile)
                if (isEncrypted) {
                    logger.i(
                        "DatabaseInitViewModel.executeImportIO",
                        "Encrypted import detected; pausing and awaiting user passphrase",
                    )
                    breadcrumb(stage = "awaiting_import_passphrase")
                    result = ImportIOResult.NeedsPassphrase(dbFile, tempBackupDir)
                    return@withContext result
                } else {
                    throw IllegalStateException(strings.settingsImportErrorUnreadableDb)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(
                    "DatabaseInitViewModel.executeImportIO",
                    "Import copy/conversion failed; restoring from temp backup",
                    e,
                )
                encryptionStateCheckpoint.restoreCheckpoint()
                breadcrumb(stage = "import_failure_encryption_prefs_restored")
                val restoreAttempted = tempBackupDir != null
                val restoreSucceeded = tempBackupDir?.let { dir -> restoreFromTempBackup(dir) } ?: false
                breadcrumb(
                    stage = "import_failure_restore_attempted",
                    data =
                        mapOf(
                            "restoreAttempted" to restoreAttempted,
                            "restoreSucceeded" to restoreSucceeded,
                        ),
                )
                logger.i(
                    "DatabaseInitViewModel.executeImportIO",
                    "Restore attempt completed after import failure",
                    mapOf(
                        "restoreAttempted" to restoreAttempted,
                        "restoreSucceeded" to restoreSucceeded,
                    ),
                )
                result = ImportIOResult.Failed(e, restoreAttempted, restoreSucceeded)
            } finally {
                if (result !is ImportIOResult.NeedsPassphrase) {
                    encryptionStateCheckpoint.discardCheckpoint()
                    tempBackupDir?.let { dir -> deleteTempBackup(dir) }
                    breadcrumb(stage = "import_cleanup_completed")
                }
            }
            result
        }

    private fun executeImportDatabase(
        sourceToken: String,
        onSuccess: () -> Unit,
    ) {
        logger.i(
            "DatabaseInitViewModel.executeImportDatabase",
            "Importing database",
            mapOf(
                "sourceToken" to sourceToken,
            ),
        )
        breadcrumb(
            stage = "import_started",
            data = mapOf("sourceToken" to sourceToken),
        )
        scope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null, restoreResult = null) }
            val dbFile = File(databasePathResolver.databaseFilePath())
            val existingDatabaseFiles = getDatabaseArtifactFiles().filter { it.exists() }
            val result = executeImportIO(sourceToken, dbFile, existingDatabaseFiles)
            when (result) {
                is ImportIOResult.NeedsPassphrase -> {
                    pendingImportDbFile = result.dbFile
                    pendingImportTempBackupDir = result.tempBackupDir
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            awaitingImportPassphrase = true,
                            importPassphraseError = null,
                        )
                    }
                }

                is ImportIOResult.Completed -> {
                    delay(500)
                    _uiState.update { it.copy(isImporting = false) }
                    breadcrumb(stage = "import_success_callback")
                    onSuccess()
                }

                is ImportIOResult.Failed -> {
                    logger.e("DatabaseInitViewModel.executeImportDatabase", "Import failed", result.cause)
                    breadcrumb(
                        stage = "import_failed",
                        data =
                            mapOf(
                                "errorType" to result.cause.javaClass.simpleName,
                                "errorMessage" to (result.cause.message ?: "unknown"),
                            ),
                    )
                    clearPendingImport()
                    val rawMessage =
                        result.cause.message
                            ?: strings.locUnknownErrorOccurred
                    val resolvedMessage =
                        if (
                            rawMessage.contains("unable to open database", ignoreCase = true) ||
                            rawMessage.contains("cannot open database", ignoreCase = true) ||
                            rawMessage.contains("unreadable", ignoreCase = true)
                        ) {
                            strings.settingsImportErrorEncryptionConvertFailed
                        } else {
                            strings.settingsSnackbarImportFailed.format(rawMessage)
                        }
                    val restoreResult =
                        when {
                            result.restoreAttempted && result.restoreSucceeded -> RestoreResult.RestoredOk
                            result.restoreAttempted && !result.restoreSucceeded -> RestoreResult.RestoreFailed
                            else -> null
                        }
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            errorMessage = if (result.restoreAttempted && result.restoreSucceeded) null else resolvedMessage,
                            restoreResult = restoreResult,
                        )
                    }
                }
            }
        }
    }

    /**
     * Resumes an encrypted import using the user-supplied [passphrase]: verifies
     * it unlocks the staged DB, configures it, health-checks, opens the session,
     * and on wrong passphrase keeps the prompt open (other failures restore backup).
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun resumeImportWithPassphrase(
        passphrase: String,
        onSuccess: () -> Unit,
    ) {
        logger.i("DatabaseInitViewModel.resumeImportWithPassphrase", "Resuming encrypted import with user passphrase")
        breadcrumb(
            stage = "resume_import_with_passphrase_started",
            data = mapOf("passphraseProvided" to passphrase.isNotEmpty()),
        )
        scope.launch {
            _uiState.update { it.copy(isImporting = true, importPassphraseError = null) }
            try {
                withContext(ioDispatcher) {
                    val dbFile =
                        pendingImportDbFile
                            ?: throw IllegalStateException("No pending import DB to resume")
                    breadcrumb(
                        stage = "resume_import_pending_db_loaded",
                        data = mapOf("dbPath" to dbFile.absolutePath),
                    )
                    val canUnlock = encryptionProbe.canOpen(dbFile, passphrase)
                    if (!canUnlock) {
                        throw IllegalStateException(
                            strings.dbImportPassphraseWrong,
                        )
                    }
                    breadcrumb(stage = "resume_import_passphrase_verified")
                    val outcome = passphraseVault.configurePassphrase(passphrase.toCharArray())
                    if (outcome != PassphraseConfigureOutcome.CONFIGURED) {
                        throw IllegalStateException(strings.settingsImportErrorEncryptionConvertFailed)
                    }
                    breadcrumb(stage = "resume_import_configure_passphrase_ok")
                    val postImportHealth =
                        healthCheck.check(
                            File(databasePathResolver.databaseFilePath()),
                            passphrase,
                        )
                    if (!postImportHealth.isHealthy) {
                        throw IllegalStateException(
                            postImportHealth.errorMessage
                                ?: strings.locDatabaseNeedsRepair,
                        )
                    }
                    markDatabaseInitCompletedDirect(dbFile, passphrase)
                    breadcrumb(stage = "resume_import_database_init_completed_marked")
                    val openResult = databaseSessionManager.openDatabase(passphrase)
                    openResult.getOrElse { openError ->
                        throw IllegalStateException(
                            "Encrypted import finalized but session open failed: ${openError.message}",
                            openError,
                        )
                    }
                    breadcrumb(stage = "resume_import_session_opened")

                    encryptionStateCheckpoint.discardCheckpoint()
                    breadcrumb(stage = "resume_import_encryption_backup_cleared")
                    val dir = pendingImportTempBackupDir
                    clearPendingImport()
                    dir?.let { deleteTempBackup(it) }
                    breadcrumb(stage = "resume_import_pending_state_cleared")
                }
                delay(500)
                _uiState.update { it.copy(isImporting = false, awaitingImportPassphrase = false) }
                breadcrumb(stage = "resume_import_success_callback")
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("DatabaseInitViewModel.resumeImportWithPassphrase", "Failed to resume import with passphrase", e)
                breadcrumb(
                    stage = "resume_import_failed",
                    data =
                        mapOf(
                            "errorType" to e.javaClass.simpleName,
                            "errorMessage" to (e.message ?: "unknown"),
                        ),
                )
                val isWrongPassphrase = e.message == strings.dbImportPassphraseWrong
                if (isWrongPassphrase) {
                    _uiState.update { it.copy(isImporting = false, importPassphraseError = e.message) }
                } else {
                    val hadBackup = pendingImportTempBackupDir != null
                    var restoreSucceeded = false
                    withContext(ioDispatcher) {
                        encryptionStateCheckpoint.restoreCheckpoint()
                        restoreSucceeded = pendingImportTempBackupDir?.let { dir -> restoreFromTempBackup(dir) } ?: false
                        clearPendingImport()
                    }
                    breadcrumb(
                        stage = "resume_import_failure_restore_complete",
                        data = mapOf("restoreSucceeded" to restoreSucceeded, "hadBackup" to hadBackup),
                    )
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            awaitingImportPassphrase = false,
                            errorMessage = e.message,
                            restoreResult =
                                when {
                                    hadBackup && restoreSucceeded -> RestoreResult.RestoredOk
                                    hadBackup -> RestoreResult.RestoreFailed
                                    else -> null
                                },
                        )
                    }
                }
            }
        }
    }

    /**
     * Cancels the encrypted-import passphrase prompt: restores pre-import backup,
     * deletes the staged file, and returns to the status screen.
     */
    fun cancelImportPassphrase() {
        logger.i("DatabaseInitViewModel.cancelImportPassphrase", "User cancelled imported DB passphrase prompt")
        breadcrumb(stage = "resume_import_cancelled_by_user")
        scope.launch {
            withContext(ioDispatcher) {
                encryptionStateCheckpoint.restoreCheckpoint()
                deleteAllDatabaseFiles()
                val dir = pendingImportTempBackupDir
                if (dir != null) {
                    restoreFromTempBackup(dir)
                }
                clearPendingImport()
            }
            breadcrumb(stage = "resume_import_cancel_cleanup_completed")
            _uiState.update {
                it.copy(
                    awaitingImportPassphrase = false,
                    isImporting = false,
                    importPassphraseError = null,
                )
            }
            checkDatabaseStatus()
        }
    }
    // The shared helpers take Files and platform handles rather than a Context, so this is the one place
    // Android answers for itself: the path comes from `databasePathResolver` (the already-injected
    // `DatabasePathResolver`, so there is still exactly one resolver on each platform) and the three
    // pre-session steps are answered with the same `SQLiteDatabase`/`SqlCipherDatabase` handles the code
    // used before the promotion. Nothing else about the flow moved.

    private val databaseFile: File get() = File(databasePathResolver.databaseFilePath())

    private fun deleteAllDatabaseFiles() = dbInitDeleteAllFiles(databaseFile)

    private fun getDatabaseArtifactFiles(): List<File> = dbInitGetArtifactFiles(databaseFile)

    private fun createSidecarSafeTempBackup(): File? = dbInitCreateSidecarSafeTempBackup(databaseFile, walConsolidator)

    private fun restoreFromTempBackup(tempBackupDir: File): Boolean = dbInitRestoreFromTempBackup(databaseFile, tempBackupDir)

    private fun deleteTempBackup(tempBackupDir: File) = dbInitDeleteTempBackup(tempBackupDir)

    /**
     * The direct-file mark, kept as a private method so its 14 call sites are untouched.
     *
     * The flag WRITE is Android's: it goes through [appDatabaseInitFlagWriter], which calls
     * `DatabaseEncryptionMigrationSupport.markDatabaseInitCompleted` — that support class already owns
     * the SQLCipher-vs-plain branch and the `INSERT OR REPLACE` upsert, and the shared contract's KEY and
     * VALUE come from `AppSettingsRepository`, so the row written is the one a session-backed
     * `markDatabaseInitCompleted()` would write.
     */
    private fun markDatabaseInitCompletedDirect(
        dbFile: File,
        passphrase: String?,
    ) {
        dbInitMarkInitCompletedDirect(
            databaseFile = dbFile,
            passphrase = passphrase,
            flagWriter = directFileFlagWriter,
        )
    }

    private val directFileFlagWriter = databaseInitFlagWriter

    private fun readDatabaseInitCompletedFlag(dbFile: File): Boolean =
        dbInitReadInitCompletedFlag(databaseFile = dbFile, flagReader = directFileFlagReader)

    private val directFileFlagReader = databaseInitFlagReader

    private val walConsolidator = databaseInitWalConsolidator

    /**
     * Marks DB-init as completed for an already-healthy existing database and
     * proceeds (best-effort; still calls [onSuccess] if the flag write fails).
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: multi-operation try block; any repo call can throw
    fun continueWithExistingDatabase(onSuccess: () -> Unit) {
        scope.launch {
            try {
                appSettingsRepository.setSetting("database_init_completed", "true")
                logger.i("DatabaseInitViewModel.continueWithExistingDatabase", "Database init completed flag set")
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e("DatabaseInitViewModel.continueWithExistingDatabase", "Failed to set flag", e)
                onSuccess() // Still proceed
            }
        }
    }

    private fun classifyBootIssue(
        databaseArtifactsExist: Boolean,
        healthResult: DatabaseHealthReport,
    ): DatabaseBootIssue? = dbInitClassifyBootIssue(databaseArtifactsExist, healthResult)
}
