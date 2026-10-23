//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.database

import io.payanam.core.ui.text.UiStrings
import io.payanam.domain.model.ConfiguredLifeDimension
import io.payanam.domain.port.DatabasePathResolver
import io.payanam.domain.port.DatabaseSessionManager
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.LifeDimensionCatalogRepository
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.database.DatabaseHealthCheck
import io.payanam.shared.database.DatabaseHealthReport
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.platform.DocumentEntry
import io.payanam.shared.platform.DocumentKind
import io.payanam.shared.platform.DocumentSource
import io.payanam.shared.platform.SupportState
import io.payanam.shared.security.EncryptionCheckpointOutcome
import io.payanam.shared.security.EncryptionStateCheckpoint
import io.payanam.shared.security.PassphraseConfigureOutcome
import io.payanam.shared.security.PassphraseUpdateOutcome
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.VaultAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.io.OutputStream

/**
 * ONE shared harness for the [DatabaseInitViewModel] tests, and the only place its collaborators are
 * faked.
 *
 * WHY IT IS SHARED AND NOT NESTED IN A TEST CLASS: the ViewModel takes thirteen collaborators, and the
 * two halves of it that the suite covers (status/create, and import) are tested by different files. A
 * fake nested in one of them is invisible to the other, so the second file would grow its own copy and
 * the two copies would drift. Everything here is top-level and `internal`-by-default, so both files
 * build the same [DatabaseInitViewModel] the same way.
 *
 * WHAT THESE FAKES ARE NOT: they are not mocks with verified call orders. Each one records WHAT IT WAS
 * ASKED - which key, which file, which passphrase, how many times - because that is the only evidence a
 * test of an orchestration class can assert on. A fake that returned a canned value and kept no
 * evidence would make a test pass whether the ViewModel asked the right question or the wrong one.
 */

// ------------------------------------------------------------------ path & session

/**
 * A resolver over a real directory.
 *
 * The ViewModel does real [File] arithmetic on whatever path this returns (`dbInitGetArtifactFiles`
 * builds the `-wal`/`-shm`/`-journal` family from it, and the temp-backup helpers copy files in and out
 * of its parent), so a path into a real temp directory is the honest fake: the code under test then
 * exercises the same filesystem branches production does, instead of a `hasArtifacts` that always
 * answers yes.
 */
internal class FakeDatabasePathResolver(
    private val directory: File,
    private val fileName: String = "payanam.db",
) : DatabasePathResolver {
    override fun databaseDirectory(): String = directory.absolutePath

    override fun databaseFilePath(): String = File(directory, fileName).absolutePath

    /** The File the ViewModel will build from [databaseFilePath] - the assertion subject, not a copy. */
    val databaseFile: File get() = File(databaseFilePath())

    /** Every artifact family member, in the shared order, so a test can plant or assert on any of them. */
    fun artifacts(): List<File> = listOf("", "-wal", "-shm", "-journal").map { File(directory, fileName + it) }
}

/** The one report that says the main file holds every write: the fold ran, was not blocked, left nothing. */
private val EMPTY_FOLD = WalCheckpointRule.FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 0)

/** A session that reports what it was opened with, and can be told to refuse. */
internal class FakeDatabaseSessionManager : DatabaseSessionManager {
    private val openState = MutableStateFlow(false)

    var openedWith: String? = null
    var openCalls = 0
    var closeCalls = 0
    var openFailure: Throwable? = null

    override suspend fun openDatabase(passphrase: String): Result<Unit> {
        openCalls++
        openedWith = passphrase
        val failure = openFailure
        return if (failure == null) {
            openState.value = true
            Result.success(Unit)
        } else {
            openState.value = false
            Result.failure(failure)
        }
    }

    override fun closeDatabase() {
        closeCalls++
        openState.value = false
    }

    /**
     * The fold a passphrase change waits on, keyed on WHAT WAS WRITTEN, not on a count.
     *
     * [flushReport] defaults to an EMPTY log, so a test that changes nothing sees the change proceed;
     * setting [flushReport] to a non-empty report is the arm where the main file does not hold every
     * write and the change must not go ahead. A count would not separate those two: a fold can be
     * asked for, answered, and still have left frames behind, which is the whole failure this exists
     * to make visible.
     */
    var flushReport: WalCheckpointRule.FoldReport = EMPTY_FOLD
    var flushCalls = 0
    var flushedWithPassphrase: String? = null

    override suspend fun flushPendingChanges(passphrase: String): WalCheckpointRule.FoldReport {
        flushCalls++
        flushedWithPassphrase = passphrase
        return flushReport
    }

    override fun requireOpenPassphrase(): String = openedWith ?: error("no session open")

    override fun isSessionOpen(): Boolean = openState.value

    override val sessionOpenState = openState.asStateFlow()
}

// ------------------------------------------------------------------ health & encryption

/**
 * A health check whose verdict is set by the test, that counts what it was asked.
 *
 * [checkCalls] exists because "is the status check still running when the user taps retry" is a real
 * question this screen answers, and an assertion that cannot see the call count cannot answer it.
 */
internal class FakeDatabaseHealthCheck(
    var report: DatabaseHealthReport = DatabaseHealthReport(isHealthy = true),
    var artifactsExist: Boolean = false,
) : DatabaseHealthCheck {
    var checkCalls = 0
    var hasArtifactsCalls = 0
    var lastCheckedFile: File? = null
    var lastPassphrase: String? = null

    /** Thrown by [check] when set - the arm where the status check itself cannot answer. */
    var checkFailure: Throwable? = null

    /**
     * [report] for the first [reportsBeforeSwitch] calls, then [unhealthyReport] for every call after.
     *
     * The import flow asks the SAME question twice about the SAME file - once to read the schema
     * version, once to decide whether the file is usable - and "answered differently the second time"
     * is a real state (a fold that left the file inconsistent). A single mutable [report] cannot say
     * that. Indexed rather than queued because the `init` block's own status check also calls in, and
     * its position is fixed by construction, not by the test.
     *
     * Defaults to NEVER switching, so a test that does not care keeps the old single-report behaviour
     * and every existing test of this fake reads exactly as it did before the switch existed.
     */
    var reportsBeforeSwitch: Int = Int.MAX_VALUE
    var unhealthyReport: DatabaseHealthReport = DatabaseHealthReport(isHealthy = false)

    override suspend fun check(
        databaseFile: File,
        passphrase: String?,
    ): DatabaseHealthReport {
        checkCalls++
        lastCheckedFile = databaseFile
        lastPassphrase = passphrase
        checkFailure?.let { throw it }
        return if (checkCalls > reportsBeforeSwitch) unhealthyReport else report
    }

    override fun hasArtifacts(databaseFile: File): Boolean {
        hasArtifactsCalls++
        return artifactsExist
    }
}

/**
 * A probe whose answers are set by the test, counting what it was asked and which tables it was asked
 * about - the probe's table list is the subject of a regression test of its own, so it is recorded.
 */
internal class FakeDatabaseEncryptionProbe : DatabaseEncryptionProbe {
    var counts: Map<String, Int> = emptyMap()
    var encrypted = false
    var canOpenAnswer = true
    var isDetectablyEncryptedCalls = 0
    var canOpenCalls = 0
    var readTableCountsCalls = 0
    var lastTableNames: List<String> = emptyList()
    var lastCountsFile: File? = null
    var lastCountsPassphrase: String? = "unset"
    var ensureEncryptedCalls = 0

    override fun isDetectablyEncrypted(
        databaseFile: File,
        logTag: String,
    ): Boolean {
        isDetectablyEncryptedCalls++
        return encrypted
    }

    override fun canOpen(
        databaseFile: File,
        passphrase: String,
        logTag: String,
    ): Boolean {
        canOpenCalls++
        return canOpenAnswer
    }

    override fun ensureEncrypted(
        databaseFile: File,
        passphrase: String,
        logTag: String,
    ) {
        ensureEncryptedCalls++
    }

    override fun readTableCounts(
        databaseFile: File,
        passphrase: String?,
        tableNames: List<String>,
    ): Map<String, Int> {
        readTableCountsCalls++
        lastTableNames = tableNames
        lastCountsFile = databaseFile
        lastCountsPassphrase = passphrase
        return counts
    }
}

/**
 * A vault that records what it was configured with and can refuse, and - importantly - reports
 * [isEncryptionEnabled] from its own state.
 *
 * [isEncryptionEnabled] is the answer [DatabaseInitViewModel.detectBootstrapPlaceholder] refuses to work
 * around: an encrypted database is never treated as an untouched placeholder, whatever its counts say.
 * So a test that wants the placeholder arm must leave [encryptionEnabled] false, and one that wants
 * the encrypted arm must not be able to get it by accident.
 */
internal class FakePassphraseVault : PassphraseVault {
    var encryptionEnabled = false
    var configured = false
    var configureOutcome: PassphraseConfigureOutcome = PassphraseConfigureOutcome.CONFIGURED
    var verifyAnswer = false
    var lockoutSecondsRemaining = 0L
    var configuredWith: String? = null
    var configureCalls = 0
    var isEncryptionEnabledCalls = 0
    var verifyCalls = 0
    var clearCalls = 0
    var recordedFailureLockout = 0L

    /**
     * How many times the caller told the vault a verification failed.
     *
     * Not the same question as [recordedFailureLockout], which is what the vault answered: a caller that
     * never reports a failure leaves both at 0, and a test that only asserted the lockout value could not
     * tell "no attempt was recorded" from "an attempt was recorded and the policy allowed it".
     */
    var recordFailedUnlockAttemptCount = 0

    override fun hasPassphraseConfigured(): Boolean = configured

    override fun availability(): VaultAvailability = VaultAvailability.AVAILABLE

    override fun verifyPassphrase(passphrase: CharArray): Boolean {
        verifyCalls++
        return verifyAnswer
    }

    override fun configurePassphrase(passphrase: CharArray): PassphraseConfigureOutcome {
        configureCalls++
        configuredWith = passphrase.concatToString()
        if (configureOutcome == PassphraseConfigureOutcome.CONFIGURED) {
            configured = true
        }
        return configureOutcome
    }

    override fun clearPassphrase() {
        clearCalls++
        configured = false
    }

    override fun isEncryptionEnabled(): Boolean {
        isEncryptionEnabledCalls++
        return encryptionEnabled
    }

    override fun updatePassphrase(
        current: CharArray,
        new: CharArray,
    ): PassphraseUpdateOutcome = PassphraseUpdateOutcome.UPDATED

    override fun unlockLockoutSecondsRemaining(): Long = lockoutSecondsRemaining

    override fun recordFailedUnlockAttempt(): Long {
        recordFailedUnlockAttemptCount++
        lockoutSecondsRemaining = recordedFailureLockout
        return lockoutSecondsRemaining
    }

    override fun resetUnlockAttempts() {
        lockoutSecondsRemaining = 0L
    }
}

/**
 * The checkpoint port, recorded as a list of the four operations in the order they were called.
 *
 * A LIST, not four booleans, because the contract's own KDoc makes order the promise ("a caller must
 * follow it") - a test that could only assert "restore was called" could not catch a restore before a
 * capture.
 */
internal class FakeEncryptionStateCheckpoint : EncryptionStateCheckpoint {
    private val calls = mutableListOf<String>()

    var outcome: EncryptionCheckpointOutcome = EncryptionCheckpointOutcome.CONFIRMED

    /** What each operation answers, so a test can drive NOT_SUPPORTED / FAILED arms. */
    val callOrder: List<String> get() = calls.toList()

    fun countOf(operation: String): Int = calls.count { it == operation }

    override fun captureCheckpoint(): EncryptionCheckpointOutcome {
        calls += "capture"
        return outcome
    }

    override fun restoreCheckpoint(): EncryptionCheckpointOutcome {
        calls += "restore"
        return outcome
    }

    override fun discardCheckpoint(): EncryptionCheckpointOutcome {
        calls += "discard"
        return outcome
    }

    override fun wipeEncryptionState(): EncryptionCheckpointOutcome {
        calls += "wipe"
        return outcome
    }
}

// ------------------------------------------------------------------ the direct-file handles

/** Records which file it was asked to mark, with the passphrase the caller held. */
internal class FakeDatabaseInitFlagWriter : DatabaseInitFlagWriter {
    var calls = 0
    var lastFile: File? = null
    var lastPassphrase: String? = null

    override fun markInitCompleted(
        databaseFile: File,
        passphrase: String?,
    ) {
        calls++
        lastFile = databaseFile
        lastPassphrase = passphrase
    }
}

/**
 * Answers one `app_settings` value out of a map the test owns.
 *
 * The map - rather than a single boolean - because "the row is absent" (null) and "the row says
 * something unparseable" are different inputs to the shared reader, and only one of them is a
 * first launch.
 */
internal class FakeDatabaseInitFlagReader(
    private val values: MutableMap<String, String?> = mutableMapOf(),
) : DatabaseInitFlagReader {
    var calls = 0
    var lastFile: File? = null
    var lastKey: String? = null

    /** Thrown when set - the arm where the platform's handle cannot be read at all. */
    var readFailure: Throwable? = null

    fun put(
        key: String,
        value: String?,
    ) {
        values[key] = value
    }

    override fun readSettingValue(
        databaseFile: File,
        key: String,
    ): String? {
        calls++
        lastFile = databaseFile
        lastKey = key
        readFailure?.let { throw it }
        return values[key]
    }
}

/**
 * A WAL fold whose ANSWER is set, because the contract says false is a real answer and not a failure
 * to report: a caller that keeps the log on false is behaving, and only a fake that can return false
 * can show that.
 */
internal class FakeDatabaseInitWalConsolidator : DatabaseInitWalConsolidator {
    var answer = true
    var calls = 0
    var lastFile: File? = null
    var lastLogTag: String? = null

    override fun consolidateWalAfterImport(
        databaseFile: File,
        logTag: String,
    ): Boolean {
        calls++
        lastFile = databaseFile
        lastLogTag = logTag
        return answer
    }
}

// ------------------------------------------------------------------ repositories

/** An in-memory settings table that records writes in order. */
internal class FakeAppSettingsRepository : AppSettingsRepository {
    private val values = mutableMapOf<String, String?>()
    private val writes = mutableListOf<Pair<String, String?>>()

    var setFailure: Throwable? = null

    /** Every `setSetting` in order - the evidence for "which row did the init mark write". */
    val writeLog: List<Pair<String, String?>> get() = writes.toList()

    fun seed(
        key: String,
        value: String?,
    ) {
        values[key] = value
    }

    override suspend fun getSetting(key: String): String? = values[key]

    override fun observeSetting(key: String): Flow<String?> = flowOf(values[key])

    override suspend fun setSetting(
        key: String,
        value: String?,
    ) {
        setFailure?.let { throw it }
        values[key] = value
        writes += key to value
    }

    override suspend fun deleteSetting(key: String) {
        values.remove(key)
        writes += key to null
    }

    override fun getAllSettings(): Flow<Map<String, String?>> = flowOf(values.toMap())
}

/** A catalog that records the one atomic replace it was asked for, and the row count it claims back. */
internal class FakeLifeDimensionCatalogRepository : LifeDimensionCatalogRepository {
    var replaceCalls = 0
    var replaceCallsWithEmptyList = 0
    var lastReplacement: List<ConfiguredLifeDimension> = emptyList()
    var replaceReturns = 0
    var replaceFailure: Throwable? = null

    override fun observeAllDimensions(): Flow<List<ConfiguredLifeDimension>> = flowOf(emptyList())

    override suspend fun updateDimensionLabel(
        dimensionId: String,
        label: String,
    ) = Unit

    override suspend fun updateDimensionColor(
        dimensionId: String,
        colorHex: String,
    ) = Unit

    override suspend fun updateDimensionIcon(
        dimensionId: String,
        iconKey: String,
    ) = Unit

    override suspend fun updateDimensionActiveState(
        dimensionId: String,
        isActive: Boolean,
    ) = Unit

    override suspend fun updateDimensionWeight(
        dimensionId: String,
        weight: Double,
    ) = Unit

    override suspend fun replaceAllDimensions(dimensions: List<ConfiguredLifeDimension>): Int {
        replaceCalls++
        lastReplacement = dimensions
        if (dimensions.isEmpty()) replaceCallsWithEmptyList++
        replaceFailure?.let { throw it }
        return if (replaceReturns == 0) dimensions.size else replaceReturns
    }
}

/**
 * The dimension-seed writer, as a `fun interface` fake that records the inputs it was handed.
 *
 * The persister is the ONLY thing in this constructor list that writes the new database's life
 * dimensions, so "the ViewModel asked for exactly the dimensions the user chose" is a claim this fake
 * can make and nothing else can.
 */
internal class FakeDatabaseDimensionSetupPersister : DatabaseDimensionSetupPersister {
    var calls = 0
    var lastInputs: List<NewDatabaseDimensionInput> = emptyList()
    var lastCatalog: LifeDimensionCatalogRepository? = null
    var lastSettings: AppSettingsRepository? = null

    /** Set by the test to fail the persist, which is the only way to reach the restore arm. */
    var failure: Throwable? = null

    /**
     * Runs BEFORE the failure is thrown, on the create path, so a test can destroy the temp backup
     * directory the ViewModel is about to restore from. That is how the RESTORE-FAILED arm is reached
     * honestly: a backup that no longer holds the file it promised.
     */
    var beforeFailure: (() -> Unit)? = null

    override suspend fun persist(
        lifeDimensionCatalogRepository: LifeDimensionCatalogRepository,
        appSettingsRepository: AppSettingsRepository,
        dimensionInputs: List<NewDatabaseDimensionInput>,
    ) {
        calls++
        lastInputs = dimensionInputs
        lastCatalog = lifeDimensionCatalogRepository
        lastSettings = appSettingsRepository
        beforeFailure?.invoke()
        failure?.let { throw it }
    }
}

// ------------------------------------------------------------------ platform document source

/**
 * A document source that answers from a token map.
 *
 * The import half of the ViewModel is the only consumer; the status/create half never touches it, and
 * the fake is here anyway so BOTH halves build a complete collaborator set rather than a half one.
 */
internal class FakeDocumentSource : DocumentSource {
    override val fileAccess: SupportState = SupportState.SUPPORTED
    override val containerAccess: SupportState = SupportState.SUPPORTED

    var sourceToReturn: String? = null
    var destinationToReturn: String? = null
    var displayNames = mutableMapOf<String, String>()
    var kinds = mutableMapOf<String, DocumentKind>()
    var children = mutableMapOf<String, List<DocumentEntry>>()
    var leadingBytes: ByteArray? = null
    var copyAnswer = COPY_NOT_ATTEMPTED_SENTINEL
    var copyCalls = 0
    var readLeadingBytesCalls = 0

    /**
     * Bytes written into the destination stream before [copyAnswer] is reported.
     *
     * The import half of the ViewModel checks the file it just copied (`exists()`, `length()`, the
     * SQLite header), so a fake that reports a byte count without writing anything would make the copy
     * succeed and the file be absent. Null keeps the original "claim a count, write nothing" fake.
     */
    var bytesToWrite: ByteArray? = null

    /**
     * Per-token bytes, for a CONTAINER source whose children are copied one by one.
     *
     * A folder import copies the database AND its sidecars, and the WAL fold only has a WAL to fold if
     * the `-wal` child was actually written - so "this token carries these bytes" is a question the
     * import half asks and a single [bytesToWrite] cannot answer.
     */
    var copyBytesByToken: MutableMap<String, ByteArray> = mutableMapOf()

    /**
     * Runs at the top of [copyInto], before the failure is thrown and before anything is written.
     *
     * Same purpose as [FakeDatabaseDimensionSetupPersister.beforeFailure] and for the same reason: the
     * only honest way to reach a RESTORE-FAILED arm is to remove the backup while the copy is still
     * running, because by the time the ViewModel tries to restore, the test is no longer on the stack.
     */
    var beforeCopy: (() -> Unit)? = null

    /** Thrown by [copyInto] when set - the partial-copy arm, where a truncated file must not be a success. */
    var copyFailure: Throwable? = null

    override suspend fun requestSource(suggestedName: String?): String? = sourceToReturn

    override suspend fun requestDestination(suggestedName: String): String? = destinationToReturn

    override fun displayNameOf(token: String): String? = displayNames[token]

    override fun kindOf(token: String): DocumentKind = kinds[token] ?: DocumentKind.FILE

    override fun copyInto(
        token: String,
        destination: OutputStream,
    ): Long {
        copyCalls++
        beforeCopy?.invoke()
        copyFailure?.let { throw it }
        val payload = copyBytesByToken[token] ?: bytesToWrite
        payload?.let {
            destination.write(it)
            destination.flush()
        }
        return if (copyBytesByToken.containsKey(token) || bytesToWrite != null) payload!!.size.toLong() else copyAnswer
    }

    override fun readLeadingBytes(
        token: String,
        byteCount: Int,
    ): ByteArray? {
        readLeadingBytesCalls++
        return leadingBytes
    }

    override fun childrenOf(token: String): List<DocumentEntry> = children[token] ?: emptyList()
}

/** Local mirror of `DocumentSource.COPY_NOT_ATTEMPTED`, so the fake does not import the platform file for one constant. */
private const val COPY_NOT_ATTEMPTED_SENTINEL = -1L

/** The shared strings table, resolved the same way each platform resolves it. */
internal fun testUiStrings(): UiStrings = UiStrings.of(io.payanam.core.ui.text.UiLanguage.ENGLISH)
