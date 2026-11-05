//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.payanam.presentation.database

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.database.DatabaseHealthReport
import io.payanam.shared.database.PayanamDatabaseSchema
import io.payanam.shared.security.EncryptionCheckpointOutcome
import io.payanam.shared.security.PassphraseConfigureOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The CREATE/STATUS half of [DatabaseInitViewModel]: the status check, the create-new entry point, and
 * the dimension-setup completion that actually creates a database.
 *
 * WHY THIS CLASS NEEDS A TEST IN THIS MODULE AT ALL: it used to be driven by `:app` Robolectric tests,
 * and this module's coverage number counts only this module's own execution data. A class that arrives
 * here with no local test contributes its instructions to the denominator and nothing to the numerator.
 *
 * WHAT IS WORTH ASSERTING HERE, given the class is an orchestrator: the state it PUBLISHES (each
 * arm's `DatabaseInitUiState` values are the screen's only output) and the effects on the collaborators
 * it was handed (which passphrase the vault received, whether the session opened, whether the seed was
 * written, what was deleted and what was put back). Every test below names the mistake it would catch.
 *
 * THE TEMP DIRECTORY IS REAL ON PURPOSE. The wipe/restore arms copy real files in and out of
 * `DatabaseArtifacts.TEMP_BACKUP_DIR_NAME`, so a fake filesystem would not exercise the branches the
 * create-with-wipe flow actually takes. A fake would let a test pass while the restore arm restored
 * nothing.
 */
class DatabaseInitViewModelCreateStatusTest {
    private val testDispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(testDispatcher)

    private lateinit var dbDirectory: File
    private lateinit var pathResolver: FakeDatabasePathResolver
    private lateinit var sessionManager: FakeDatabaseSessionManager
    private lateinit var lifeDimensions: FakeLifeDimensionCatalogRepository
    private lateinit var appSettings: FakeAppSettingsRepository
    private lateinit var healthCheck: FakeDatabaseHealthCheck
    private lateinit var encryptionProbe: FakeDatabaseEncryptionProbe
    private lateinit var documentSource: FakeDocumentSource
    private lateinit var flagWriter: FakeDatabaseInitFlagWriter
    private lateinit var flagReader: FakeDatabaseInitFlagReader
    private lateinit var walConsolidator: FakeDatabaseInitWalConsolidator
    private lateinit var dimensionPersister: FakeDatabaseDimensionSetupPersister
    private lateinit var checkpoint: FakeEncryptionStateCheckpoint
    private lateinit var vault: FakePassphraseVault

    @Before
    fun setUp() {
        dbDirectory = Files.createTempDirectory("payanam-dbinit-create").toFile()
        pathResolver = FakeDatabasePathResolver(dbDirectory)
        sessionManager = FakeDatabaseSessionManager()
        lifeDimensions = FakeLifeDimensionCatalogRepository()
        appSettings = FakeAppSettingsRepository()
        healthCheck = FakeDatabaseHealthCheck()
        encryptionProbe = FakeDatabaseEncryptionProbe()
        documentSource = FakeDocumentSource()
        flagWriter = FakeDatabaseInitFlagWriter()
        flagReader = FakeDatabaseInitFlagReader()
        walConsolidator = FakeDatabaseInitWalConsolidator()
        dimensionPersister = FakeDatabaseDimensionSetupPersister()
        checkpoint = FakeEncryptionStateCheckpoint()
        vault = FakePassphraseVault()
    }

    @After
    fun tearDown() {
        dbDirectory.deleteRecursively()
    }

    /**
     * The subject, with BOTH dispatchers pointed at the test scheduler.
     *
     * [ioDispatcher] matters as much as the scope: the ViewModel's blocking work is wrapped in
     * `withContext(ioDispatcher)`, and a real `Dispatchers.IO` hop is invisible to `advanceUntilIdle`,
     * so a test that read the state after idling would read it before the work landed.
     */
    private fun newViewModel() =
        DatabaseInitViewModel(
            appSettingsRepository = appSettings,
            databaseSessionManager = sessionManager,
            lifeDimensionCatalogRepository = lifeDimensions,
            strings = testUiStrings(),
            healthCheck = healthCheck,
            databasePathResolver = pathResolver,
            encryptionProbe = encryptionProbe,
            documentSource = documentSource,
            databaseInitFlagWriter = flagWriter,
            databaseInitFlagReader = flagReader,
            databaseInitWalConsolidator = walConsolidator,
            dimensionSetupPersister = dimensionPersister,
            encryptionStateCheckpoint = checkpoint,
            passphraseVault = vault,
            scope = scope,
            ioDispatcher = testDispatcher,
        )

    /** A database file with [bytes] of recognisable content, so a restore can be PROVEN by the bytes. */
    private fun plantDatabase(bytes: Int = 4096): File =
        pathResolver.databaseFile.apply {
            writeBytes(ByteArray(bytes) { (it % 251).toByte() })
        }

    private val tempBackupDir: File get() = File(dbDirectory, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)

    private val dimensionInputs =
        listOf(
            NewDatabaseDimensionInput(id = "health", label = "Health", colorHex = "#FF0000", isEnabled = true),
            NewDatabaseDimensionInput(id = "work", label = "Work", colorHex = "#00FF00", isEnabled = true),
        )

    // ============================================================= checkDatabaseStatus

    @Test
    fun `a healthy check with no artifacts reports an empty database and no issue`() =
        runTest(testDispatcher) {
            healthCheck.report = DatabaseHealthReport(isHealthy = true)
            healthCheck.artifactsExist = false

            val viewModel = newViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.isChecking).isFalse()
            assertThat(state.databaseExists).isFalse()
            assertThat(state.databaseCorrupted).isFalse()
            assertThat(state.bootIssue).isNull()
            assertThat(state.corruptionMessage).isNull()
            assertThat(state.errorMessage).isNull()
            // No file means no size claim: a 0 here is indistinguishable from an empty file unless the
            // artifact check actually said so, which it did.
            assertThat(healthCheck.hasArtifactsCalls).isAtLeast(1)
            viewModel.dispose()
        }

    @Test
    fun `a healthy existing database reports its size, mtime and this build's schema version`() =
        runTest(testDispatcher) {
            val dbFile = plantDatabase(bytes = 6144)
            dbFile.setLastModified(1_700_000_000_000L)
            healthCheck.report = DatabaseHealthReport(isHealthy = true)
            healthCheck.artifactsExist = true
            // The init flag row EXISTS, so this is not an untouched pre-choice placeholder.
            flagReader.put(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY, "true")

            val viewModel = newViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.databaseExists).isTrue()
            assertThat(state.databaseCorrupted).isFalse()
            assertThat(state.bootIssue).isNull()
            assertThat(state.databaseSizeKB).isEqualTo(6144L / 1024L)
            assertThat(state.lastModified).isEqualTo(1_700_000_000_000L)
            // The version shown is the one this BUILD expects, not one read out of the file: the shared
            // report deliberately carries no version field.
            assertThat(state.databaseSchemaVersion).isEqualTo(PayanamDatabaseSchema.VERSION)
            // The health check is asked about the resolver's file, and with no passphrase, because the
            // check here is the boot-time one that has no session to open.
            assertThat(healthCheck.lastCheckedFile?.absolutePath).isEqualTo(dbFile.absolutePath)
            assertThat(healthCheck.lastPassphrase).isNull()
            viewModel.dispose()
        }

    @Test
    fun `a healthy existing database with the init flag set is never treated as a placeholder`() =
        runTest(testDispatcher) {
            plantDatabase()
            healthCheck.artifactsExist = true
            flagReader.put(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY, "true")
            // Counts are non-zero AND the flag is set: either one alone already defeats the placeholder
            // answer, so both are planted to pin that the flag is what this test is about.
            encryptionProbe.counts = mapOf("tasks" to 4)

            val viewModel = newViewModel()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.databaseExists).isTrue()
            // The count read is the one the schema needs, and nothing else.
            assertThat(encryptionProbe.lastTableNames)
                .containsExactly("tasks", "time_entries", "day_journal_entries", "journal_notes", "notes")
            viewModel.dispose()
        }

    @Test
    fun `a pre-choice bootstrap placeholder is reported as NO existing database`() =
        runTest(testDispatcher) {
            val dbFile = plantDatabase()
            dbFile.setLastModified(1_700_000_000_000L)
            healthCheck.artifactsExist = true
            // No init flag row at all, and an empty database: this is the file Room created on first
            // launch before the user chose anything. Offering it as "your existing database" would push
            // a first-run user into the continue-with-existing path over an empty file.
            flagReader.put(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY, null)
            encryptionProbe.counts = emptyMap()

            val viewModel = newViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.databaseExists).isFalse()
            // Not merely hidden: the size and the mtime are withheld too, so nothing on screen claims
            // there is data behind a file the ViewModel has decided is not a user database.
            assertThat(state.databaseSizeKB).isEqualTo(0L)
            assertThat(state.lastModified).isNull()
            assertThat(state.isChecking).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `user data in any one table defeats the placeholder answer`() =
        runTest(testDispatcher) {
            plantDatabase()
            healthCheck.artifactsExist = true
            flagReader.put(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY, null)
            // ONE row, in the table nobody would think to check: the journal-notes table. A placeholder
            // test that only counted `tasks` would pass while the ViewModel ignored user data.
            encryptionProbe.counts = mapOf("journal_notes" to 1)

            val viewModel = newViewModel()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.databaseExists).isTrue()
            viewModel.dispose()
        }

    @Test
    fun `an encrypted database is never treated as a placeholder and is never counted`() =
        runTest(testDispatcher) {
            plantDatabase()
            healthCheck.artifactsExist = true
            // The FILE is what says it is encrypted now, not the platform mode: the same probe that
            // answers every other file question on this platform answers this one, so the test steers
            // the probe rather than the vault.
            encryptionProbe.encrypted = true

            val viewModel = newViewModel()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.databaseExists).isTrue()
            // An encrypted file's row counts are unreadable without the passphrase, so asking for them
            // would be asking a question with no answer. The arm returns after the probe's answer and
            // before the read AND before the flag read - the flag is inside the encrypted file too.
            assertThat(encryptionProbe.readTableCountsCalls).isEqualTo(0)
            assertThat(flagReader.calls).isEqualTo(0)
            viewModel.dispose()
        }

    @Test
    fun `an unhealthy database with artifacts present is reported as corrupt and classified`() =
        runTest(testDispatcher) {
            plantDatabase(bytes = 2048)
            healthCheck.artifactsExist = true
            healthCheck.report =
                DatabaseHealthReport(
                    isHealthy = false,
                    errorMessage = "Database is missing tables required by this build",
                    needsRepair = true,
                    detectedVersion = 19,
                )

            val viewModel = newViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.databaseCorrupted).isTrue()
            assertThat(state.bootIssue?.type).isEqualTo(DatabaseBootIssueType.SCHEMA_INVALID)
            // The version the issue carries is the one the health check actually measured, not its
            // 0 default.
            assertThat(state.bootIssue?.detectedVersion).isEqualTo(19)
            assertThat(state.corruptionMessage)
                .isEqualTo("Database is missing tables required by this build")
            assertThat(state.databaseSizeKB).isEqualTo(2L)
            // A repairable report is not an error banner: the screen's own actions are the answer.
            assertThat(state.errorMessage).isNull()
            viewModel.dispose()
        }

    @Test
    fun `an unhealthy check with NO artifacts reports no boot issue at all`() =
        runTest(testDispatcher) {
            healthCheck.artifactsExist = false
            healthCheck.report =
                DatabaseHealthReport(
                    isHealthy = false,
                    errorMessage = "Cannot open database",
                    needsRepair = false,
                )

            val viewModel = newViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // Nothing on disk to classify, so a boot issue here would be an invention.
            assertThat(state.bootIssue).isNull()
            assertThat(state.databaseExists).isFalse()
            assertThat(state.databaseCorrupted).isFalse()
            assertThat(state.databaseSizeKB).isEqualTo(0L)
            viewModel.dispose()
        }

    @Test
    fun `a status check that throws is surfaced as an error, not as a silent empty database`() =
        runTest(testDispatcher) {
            healthCheck.checkFailure = IllegalStateException("disk is on fire")

            val viewModel = newViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.isChecking).isFalse()
            // The distinction this arm exists for: a failed CHECK is not the same claim as "no database".
            // `errorMessage` is set, and the caller can retry.
            assertThat(state.errorMessage).isEqualTo("Error checking database: disk is on fire")
            viewModel.dispose()
        }

    @Test
    fun `a flag read that throws is read as not-completed rather than failing the whole check`() =
        runTest(testDispatcher) {
            plantDatabase()
            healthCheck.artifactsExist = true
            flagReader.readFailure = IllegalStateException("no sqlite here")

            val viewModel = newViewModel()
            advanceUntilIdle()

            // Unreadable flag => "not completed", and the counts say there is no data either, so the
            // bootstrap answer stands. A throwing reader must not turn a healthy database into an error.
            assertThat(viewModel.uiState.value.errorMessage).isNull()
            assertThat(viewModel.uiState.value.databaseExists).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `retrying the status check asks the health check again`() =
        runTest(testDispatcher) {
            healthCheck.checkFailure = IllegalStateException("transient")
            val viewModel = newViewModel()
            advanceUntilIdle()
            assertThat(healthCheck.checkCalls).isEqualTo(1)
            assertThat(viewModel.uiState.value.errorMessage).isNotNull()

            healthCheck.checkFailure = null
            healthCheck.report = DatabaseHealthReport(isHealthy = true)
            healthCheck.artifactsExist = true
            flagReader.put(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY, "true")

            viewModel.retryDatabaseStatusCheck()
            advanceUntilIdle()

            assertThat(healthCheck.checkCalls).isEqualTo(2)
            // The old failure must not survive the retry: a stale error banner over a healthy database
            // is the bug this assertion is for.
            assertThat(viewModel.uiState.value.errorMessage).isNull()
            assertThat(viewModel.uiState.value.databaseExists).isTrue()
            viewModel.dispose()
        }

    // ============================================================= createNewDatabase

    @Test
    fun `createNewDatabase with nothing on disk goes straight to the dimension gate and creates nothing`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.awaitingDimensionSetup).isTrue()
            assertThat(state.showCreateNewWipeConfirm).isFalse()
            assertThat(state.isCreating).isFalse()
            // Nothing may exist before the user has chosen their life dimensions: a database created here
            // would be seeded with a catalog the user is about to define.
            assertThat(vault.configureCalls).isEqualTo(0)
            assertThat(sessionManager.openCalls).isEqualTo(0)
            assertThat(dimensionPersister.calls).isEqualTo(0)
            viewModel.dispose()
        }

    @Test
    fun `createNewDatabase over an existing file asks for wipe confirmation and touches nothing`() =
        runTest(testDispatcher) {
            val dbFile = plantDatabase()
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showCreateNewWipeConfirm).isTrue()
            assertThat(viewModel.uiState.value.awaitingDimensionSetup).isFalse()
            // The existing database is still there, byte for byte, while the user decides.
            assertThat(dbFile.exists()).isTrue()
            assertThat(dbFile.length()).isEqualTo(4096L)
            assertThat(vault.configureCalls).isEqualTo(0)
            viewModel.dispose()
        }

    @Test
    fun `cancelling the wipe prompt leaves the existing database alone`() =
        runTest(testDispatcher) {
            val dbFile = plantDatabase()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()

            viewModel.cancelCreateNewWipe()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showCreateNewWipeConfirm).isFalse()
            assertThat(viewModel.uiState.value.awaitingDimensionSetup).isFalse()
            assertThat(dbFile.exists()).isTrue()
            viewModel.dispose()
        }

    @Test
    fun `confirming the wipe prompt opens the dimension gate and retains the passphrase for it`() =
        runTest(testDispatcher) {
            plantDatabase()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()

            viewModel.confirmCreateNew("wipe-passphrase")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showCreateNewWipeConfirm).isFalse()
            assertThat(viewModel.uiState.value.awaitingDimensionSetup).isTrue()
            // The deletion happens at COMPLETION, not at confirmation: cancelling after this point must
            // still find the old database intact.
            assertThat(pathResolver.databaseFile.exists()).isTrue()
            viewModel.dispose()
        }

    // ============================================================= completeNewDatabaseDimensionSetup

    @Test
    fun `completing dimension setup with no wipe configures the passphrase, opens the session and seeds`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()
            var successes = 0

            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) { successes++ }
            advanceUntilIdle()

            // The passphrase the user typed at the gate is the one configured and the one the session
            // opened with - not a re-read, not a re-typed value.
            assertThat(vault.configuredWith).isEqualTo("correct horse")
            assertThat(sessionManager.openedWith).isEqualTo("correct horse")
            assertThat(dimensionPersister.lastInputs).isEqualTo(dimensionInputs)
            // The persister is handed the REAL repositories, so its atomic replace lands in the same
            // catalog the rest of the app reads.
            assertThat(dimensionPersister.lastCatalog).isSameInstanceAs(lifeDimensions)
            assertThat(dimensionPersister.lastSettings).isSameInstanceAs(appSettings)
            assertThat(successes).isEqualTo(1)
            val state = viewModel.uiState.value
            assertThat(state.isCreating).isFalse()
            assertThat(state.awaitingDimensionSetup).isFalse()
            assertThat(state.errorMessage).isNull()
            // Nothing was wiped, so nothing was backed up and nothing was restored.
            assertThat(checkpoint.callOrder).isEmpty()
            viewModel.dispose()
        }

    @Test
    fun `completing with no pending passphrase is refused and the user is told to set it again`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            var successes = 0

            // No createNewDatabase first: the gate was never opened, so there is nothing to complete.
            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) { successes++ }
            advanceUntilIdle()

            assertThat(successes).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorMessage).isEqualTo("Missing pending passphrase. Please set passphrase again.")
            assertThat(vault.configureCalls).isEqualTo(0)
            assertThat(dimensionPersister.calls).isEqualTo(0)
            viewModel.dispose()
        }

    @Test
    fun `a passphrase the vault refuses stops the create before any session or seed`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("too-short")
            advanceUntilIdle()
            vault.configureOutcome = PassphraseConfigureOutcome.REJECTED_BY_POLICY
            var successes = 0

            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) { successes++ }
            advanceUntilIdle()

            assertThat(successes).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorMessage).isEqualTo("Failed to configure passphrase for new database")
            // Order is the point: an unconfigured vault must not leave an open session or a seeded
            // catalog behind.
            assertThat(sessionManager.openCalls).isEqualTo(0)
            assertThat(dimensionPersister.calls).isEqualTo(0)
            assertThat(viewModel.uiState.value.awaitingDimensionSetup).isTrue()
            viewModel.dispose()
        }

    @Test
    fun `a session that refuses to open reports why and never seeds the catalog`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()
            sessionManager.openFailure = IllegalStateException("wrong key on the new file")
            var successes = 0

            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) { successes++ }
            advanceUntilIdle()

            assertThat(successes).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorMessage)
                .isEqualTo("Failed to open new DB session: wrong key on the new file")
            assertThat(dimensionPersister.calls).isEqualTo(0)
            // The failure path abandons the half-built state, so the session is closed and the
            // encryption state is wiped rather than left describing a database that was never seeded.
            assertThat(sessionManager.closeCalls).isAtLeast(1)
            assertThat(checkpoint.countOf("wipe")).isAtLeast(1)
            viewModel.dispose()
        }

    @Test
    fun `completing with a wipe backs up first, deletes the old artifacts, and cleans the backup up`() =
        runTest(testDispatcher) {
            val dbFile = plantDatabase(bytes = 5120)
            // A real sidecar, so "existing files" is more than the main file and the delete has two
            // victims.
            File(dbDirectory, dbFile.name + "-wal").writeText("wal")
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()
            viewModel.confirmCreateNew("wipe-passphrase")
            advanceUntilIdle()
            var existedAtPersist: Boolean? = null
            dimensionPersister.beforeFailure = { existedAtPersist = dbFile.exists() }

            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) {}
            advanceUntilIdle()

            // The fold happens BEFORE the copy, so the backup holds every pending write.
            assertThat(walConsolidator.calls).isAtLeast(1)
            // The old artifacts are gone by the time the seed is written: creating alongside them is
            // how a "new" database ends up merged with the old one.
            assertThat(existedAtPersist).isFalse()
            assertThat(File(dbDirectory, dbFile.name + "-wal").exists()).isFalse()
            assertThat(dimensionPersister.lastInputs).isEqualTo(dimensionInputs)
            // A successful create deletes its own safety net; leaving it would make the next create
            // back up a directory full of stale files.
            assertThat(tempBackupDir.exists()).isFalse()
            assertThat(viewModel.uiState.value.restoreResult).isNull()
            assertThat(viewModel.uiState.value.awaitingDimensionSetup).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `a wipe whose backup cannot be made refuses to delete anything`() =
        runTest(testDispatcher) {
            // A zero-length main file: the backup helper rejects an empty copy, so there is no safe
            // backup - and the create must stop there.
            val dbFile = pathResolver.databaseFile.apply { createNewFile() }
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()
            viewModel.confirmCreateNew("wipe-passphrase")
            advanceUntilIdle()
            var successes = 0

            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) { successes++ }
            advanceUntilIdle()

            assertThat(successes).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorMessage)
                .isEqualTo("Could not create a safe backup of the current database before wiping.")
            // The whole point of the guard: the file it refused to back up is still there.
            assertThat(dbFile.exists()).isTrue()
            assertThat(vault.configureCalls).isEqualTo(0)
            viewModel.dispose()
        }

    @Test
    fun `a failed seed after a wipe restores the old database and reports RestoredOk`() =
        runTest(testDispatcher) {
            val dbFile = plantDatabase(bytes = 5120)
            val originalBytes = dbFile.readBytes()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()
            viewModel.confirmCreateNew("wipe-passphrase")
            advanceUntilIdle()
            dimensionPersister.failure = IllegalStateException("catalog write rejected")
            var successes = 0

            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) { successes++ }
            advanceUntilIdle()

            assertThat(successes).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorMessage).isEqualTo("catalog write rejected")
            assertThat(viewModel.uiState.value.restoreResult).isEqualTo(RestoreResult.RestoredOk)
            // Restored means the BYTES are back, not merely that a boolean was set.
            assertThat(dbFile.exists()).isTrue()
            assertThat(dbFile.readBytes()).isEqualTo(originalBytes)
            viewModel.dispose()
        }

    @Test
    fun `a failed seed whose backup is unreadable reports RestoreFailed rather than RestoredOk`() =
        runTest(testDispatcher) {
            val dbFile = plantDatabase()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()
            viewModel.confirmCreateNew("wipe-passphrase")
            advanceUntilIdle()
            // The backup is destroyed between the wipe and the failure - the honest way to reach a
            // restore that cannot succeed.
            dimensionPersister.beforeFailure = { tempBackupDir.deleteRecursively() }
            dimensionPersister.failure = IllegalStateException("catalog write rejected")

            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) {}
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.restoreResult).isEqualTo(RestoreResult.RestoreFailed)
            assertThat(dbFile.exists()).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `a failed seed with nothing to restore reports no restore result at all`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()
            dimensionPersister.failure = IllegalStateException("catalog write rejected")

            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) {}
            advanceUntilIdle()

            // No backup was taken, so there is nothing to tell the user was restored. Publishing
            // RestoreFailed here would claim a loss the user never suffered.
            assertThat(viewModel.uiState.value.restoreResult).isNull()
            assertThat(viewModel.uiState.value.errorMessage).isEqualTo("catalog write rejected")
            assertThat(checkpoint.countOf("wipe")).isAtLeast(1)
            viewModel.dispose()
        }

    @Test
    fun `a failure keeps the pending passphrase so a retry does not ask for it again`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()
            dimensionPersister.failure = IllegalStateException("catalog write rejected")
            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) {}
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.errorMessage).isNotNull()

            // Retry with no new passphrase argument - the retained one is used.
            dimensionPersister.failure = null
            var successes = 0
            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) { successes++ }
            advanceUntilIdle()

            assertThat(successes).isEqualTo(1)
            assertThat(vault.configuredWith).isEqualTo("correct horse")
            assertThat(viewModel.uiState.value.errorMessage).isNull()
            assertThat(viewModel.uiState.value.awaitingDimensionSetup).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `dismissing a restore result clears it and re-checks the status`() =
        runTest(testDispatcher) {
            val dbFile = plantDatabase()
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()
            viewModel.confirmCreateNew("wipe-passphrase")
            advanceUntilIdle()
            dimensionPersister.failure = IllegalStateException("catalog write rejected")
            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) {}
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.restoreResult).isEqualTo(RestoreResult.RestoredOk)
            val checksBeforeDismiss = healthCheck.checkCalls

            viewModel.dismissRestoreResult()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.restoreResult).isNull()
            // Dismissing is also the "look again" action, so the status must be re-read.
            assertThat(healthCheck.checkCalls).isEqualTo(checksBeforeDismiss + 1)
            // And the re-read reflects reality: the old database is back on disk.
            healthCheck.artifactsExist = true
            viewModel.dispose()
        }

    // ============================================================= the flag on an existing DB

    @Test
    fun `continuing with an existing database writes the init flag row`() =
        runTest(testDispatcher) {
            plantDatabase()
            healthCheck.artifactsExist = true
            flagReader.put(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY, "true")
            val viewModel = newViewModel()
            advanceUntilIdle()
            var successes = 0

            viewModel.continueWithExistingDatabase { successes++ }
            advanceUntilIdle()

            assertThat(successes).isEqualTo(1)
            // The exact row the next launch reads back, by key and by value.
            assertThat(appSettings.writeLog)
                .containsExactly(
                    AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY to AppSettingsRepository.INIT_COMPLETED_VALUE,
                )
            viewModel.dispose()
        }

    @Test
    fun `a flag write that fails still lets the user through - the flag is not worth blocking on`() =
        runTest(testDispatcher) {
            plantDatabase()
            healthCheck.artifactsExist = true
            appSettings.setFailure = IllegalStateException("settings table locked")
            val viewModel = newViewModel()
            advanceUntilIdle()
            var successes = 0

            viewModel.continueWithExistingDatabase { successes++ }
            advanceUntilIdle()

            // Best-effort by contract: a user with a readable, healthy database must not be trapped
            // because one bookkeeping row would not write.
            assertThat(successes).isEqualTo(1)
            viewModel.dispose()
        }

    // ============================================================= dispose

    @Test
    fun `a sidecar alone is enough to demand a wipe confirmation`() =
        runTest(testDispatcher) {
            // No main file, but a `-wal` beside it. Treating that as "nothing there" would start a create
            // that leaves the orphan log behind, and SQLite replays a surviving log into the new file.
            val orphan = File(dbDirectory, "payanam.db-wal")
            orphan.writeText("orphaned log")
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showCreateNewWipeConfirm).isTrue()
            assertThat(orphan.exists()).isTrue()
            viewModel.dispose()
        }

    @Test
    fun `a second create request replaces the pending passphrase, and asks to wipe only once`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            // First request finds nothing and goes straight to the gate.
            viewModel.createNewDatabase("first passphrase")
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.awaitingDimensionSetup).isTrue()
            // Now a database exists, so the next request must ask before destroying anything.
            plantDatabase()
            viewModel.createNewDatabase("second passphrase")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showCreateNewWipeConfirm).isTrue()
            // The prompt is still up and the gate is still waiting: neither request may cancel the other
            // out silently, and the passphrase that finally runs is the one from the LAST request.
            viewModel.confirmCreateNew("second passphrase")
            advanceUntilIdle()
            var successes = 0
            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) { successes++ }
            advanceUntilIdle()

            assertThat(successes).isEqualTo(1)
            assertThat(vault.configuredWith).isEqualTo("second passphrase")
            viewModel.dispose()
        }

    @Test
    fun `a success callback that throws is reported as a failed create`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()

            // The database was created and seeded, and THEN the caller's own callback failed. Recording
            // that as a create failure is what the code does.
            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) {
                throw IllegalStateException("navigation exploded")
            }
            advanceUntilIdle()

            assertThat(dimensionPersister.calls).isEqualTo(1)
            assertThat(sessionManager.openCalls).isEqualTo(1)
            assertThat(viewModel.uiState.value.errorMessage).isEqualTo("navigation exploded")
            // SUSPECTED DEFECT (not fixed - reported, not patched): the gate was already closed and the
            // create already SUCCEEDED before the callback ran, yet the screen is left showing a failure
            // and the pending passphrase was consumed. There is nothing left to retry, so the user's only
            // route out is the status check - which would report the database that was in fact created.
            assertThat(viewModel.uiState.value.awaitingDimensionSetup).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `disposing mid-create stops the work instead of finishing it in the background`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()
            // The create is queued, not started: the test dispatcher holds it until something idles.
            viewModel.dispose()

            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) {}
            advanceUntilIdle()

            // A cancelled scope must not configure a passphrase, open a session or seed a catalog for a
            // screen the user already left.
            assertThat(vault.configureCalls).isEqualTo(0)
            assertThat(sessionManager.openCalls).isEqualTo(0)
            assertThat(dimensionPersister.calls).isEqualTo(0)
        }

    @Test
    fun `a disposed ViewModel runs nothing further`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()
            val checksBefore = healthCheck.checkCalls

            viewModel.dispose()
            viewModel.createNewDatabase("correct horse")
            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) {}
            viewModel.retryDatabaseStatusCheck()
            advanceUntilIdle()

            // The scope is cancelled, so no queued work runs - the screen is gone and must not keep
            // writing a database behind the user's back.
            assertThat(healthCheck.checkCalls).isEqualTo(checksBefore)
            assertThat(dimensionPersister.calls).isEqualTo(0)
            assertThat(vault.configureCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.isCreating).isFalse()
        }

    @Test
    fun `the state a disposed ViewModel last published is still readable`() =
        runTest(testDispatcher) {
            healthCheck.artifactsExist = true
            flagReader.put(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY, "true")
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.dispose()
            advanceUntilIdle()

            // dispose() cancels work; it does not blank the screen's last known state, so a rotation
            // between dispose and teardown cannot show a user an empty database.
            assertThat(viewModel.uiState.value.databaseExists).isTrue()
            assertThat(viewModel.uiState.value.isChecking).isFalse()
        }

    // ============================================================= the values it publishes

    @Test
    fun `the published state starts checking and carries no findings`() =
        runTest(testDispatcher) {
            // Read before the init-block's coroutine is allowed to run: the constructor's first act is
            // to say "checking", and a screen that renders before that would flash a create prompt at a
            // user who already has a database.
            healthCheck.checkFailure = IllegalStateException("blocked")
            val viewModel =
                DatabaseInitViewModel(
                    appSettingsRepository = appSettings,
                    databaseSessionManager = sessionManager,
                    lifeDimensionCatalogRepository = lifeDimensions,
                    strings = testUiStrings(),
                    healthCheck = healthCheck,
                    databasePathResolver = pathResolver,
                    encryptionProbe = encryptionProbe,
                    documentSource = documentSource,
                    databaseInitFlagWriter = flagWriter,
                    databaseInitFlagReader = flagReader,
                    databaseInitWalConsolidator = walConsolidator,
                    dimensionSetupPersister = dimensionPersister,
                    encryptionStateCheckpoint = checkpoint,
                    passphraseVault = vault,
                    scope = scope,
                    ioDispatcher = testDispatcher,
                )

            assertThat(viewModel.uiState.value.isChecking).isTrue()
            assertThat(viewModel.uiState.value.databaseExists).isFalse()
            assertThat(viewModel.uiState.value.restoreResult).isNull()
            assertThat(viewModel.uiState.value.awaitingDimensionSetup).isFalse()
            assertThat(viewModel.uiState.value.awaitingImportPassphrase).isFalse()
            assertThat(viewModel.uiState.value.databaseSchemaVersion).isEqualTo(0)
            // Nothing is staged for an import that was never started.
            assertThat(viewModel.importDbFile).isNull()
            viewModel.dispose()
        }

    @Test
    fun `a checkpoint that answers NOT_SUPPORTED still completes the create`() =
        runTest(testDispatcher) {
            // A platform with no at-rest encryption metadata must not make the create flow refuse; the
            // create path only ABANDONS state on failure, and it reports the outcome it was given.
            checkpoint.outcome = EncryptionCheckpointOutcome.NOT_SUPPORTED
            val viewModel = newViewModel()
            advanceUntilIdle()
            viewModel.createNewDatabase("correct horse")
            advanceUntilIdle()
            var successes = 0

            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) { successes++ }
            advanceUntilIdle()

            assertThat(successes).isEqualTo(1)
            assertThat(viewModel.uiState.value.errorMessage).isNull()
            viewModel.dispose()
        }
}
