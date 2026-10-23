//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.payanam.presentation.database

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.DatabaseHealthReport
import io.payanam.shared.platform.DocumentEntry
import io.payanam.shared.platform.DocumentKind
import io.payanam.shared.security.EncryptionCheckpointOutcome
import io.payanam.shared.security.PassphraseConfigureOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The IMPORT half of [DatabaseInitViewModel]: staging a source, the whole IO block that copies,
 * folds, validates and opens a database, the encrypted-import resume, and the two ways the user walks
 * away from that prompt.
 *
 * WHAT IS WORTH ASSERTING HERE: this class is an orchestrator, so the only evidence a test can have is
 * (a) the [DatabaseInitUiState] it publishes and (b) the effects on the collaborators. Every test
 * below names the mistake it would catch, and every one of them asserts on state or on a recorded call
 * - never on "it did not throw".
 *
 * THE IMPORT IO IS REAL ON PURPOSE. `executeImportIO` copies files, folds a WAL, checks a 16-byte
 * header and restores from a backup directory. A fake filesystem would skip exactly the branches this
 * file is here to cover: an empty copy, an unrecognised header, a WAL that only exists because a
 * container import wrote it, a backup that has stopped holding the file it promised. So the temp
 * directory is real, the picked bytes are real SQLite-shaped bytes, and the collaborators are faked
 * only where the platform would be.
 *
 * THE HEADER IS NOT DECORATIVE. `isStandardSqliteFile` decides two different things from those 16
 * bytes - fold, or refuse - and `executeImportIO` IGNORES the fold's answer by design (a WAL that
 * could not be folded is kept, not reported). Both are asserted below.
 *
 * WHAT THIS FILE STOPPED ASSERTING, and why: the encrypted-only import rule makes a plaintext database
 * REFUSEABLE, so the arms that used to prove a plaintext import succeeded - the schema gate, the
 * re-encrypt to the session key, the post-import health check, the mark-and-open, the 500ms success
 * delay - no longer have a code path to reach. The encrypted path took them over, because it is the
 * only one that still completes, and each of those assertions now lives on it. The plaintext arms
 * that remain are the ones the rule created: a refusal with the right message, no conversion, no
 * mark, no open, and the user's existing database back byte-for-byte.
 */
class DatabaseInitViewModelImportTest {
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

    /** Every callback handed to `importDatabase`/`resumeImportWithPassphrase`, so a silent one is visible. */
    private var onSuccessCalls = 0

    @Before
    fun setUp() {
        dbDirectory = Files.createTempDirectory("payanam-dbinit-import").toFile()
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
        onSuccessCalls = 0
    }

    @After
    fun tearDown() {
        dbDirectory.deleteRecursively()
    }

    /**
     * The subject, with BOTH dispatchers on the test scheduler.
     *
     * `ioDispatcher` is not optional here: the whole import block is wrapped in
     * `withContext(ioDispatcher)`, so a real `Dispatchers.IO` hop is invisible to `advanceUntilIdle` and
     * the test would read the state before the copy landed.
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

    private fun countingOnSuccess() {
        onSuccessCalls++
    }

    /**
     * The subject, with a REAL IO dispatcher for the one test that needs the import to be genuinely
     * in flight on another thread.
     *
     * Every other test runs the import on the test scheduler, so `advanceUntilIdle` sees every
     * step. The re-entry test instead has to observe the import mid-copy, which a same-thread
     * dispatcher cannot do: the test would be blocked inside `copyInto` and could not make the second
     * call that is the whole point of the test.
     */
    private fun newViewModel(
        scope: CoroutineScope = this.scope,
        ioDispatcher: CoroutineDispatcher = testDispatcher,
    ): DatabaseInitViewModel =
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
            ioDispatcher = ioDispatcher,
        )

    /** Waits until the ViewModel stops importing, for an import running on a real IO pool. */
    private fun awaitSettled(viewModel: DatabaseInitViewModel) {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MILLIS
        while (viewModel.uiState.value.isImporting) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("The import never settled")
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    private val tempBackupDir: File get() = File(dbDirectory, DatabaseArtifacts.TEMP_BACKUP_DIR_NAME)

    private val walFile: File get() = File(dbDirectory, pathResolver.databaseFile.name + "-wal")
    private val shmFile: File get() = File(dbDirectory, pathResolver.databaseFile.name + "-shm")

    /** SQLite-shaped bytes: the real 16-byte header, then filler. Enough for the format gate to pass. */
    private fun sqliteBytes(filler: Int = 4080): ByteArray = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(filler)

    /** Bytes that are NOT a SQLite file - the shape an encrypted export has, or a random file. */
    private fun opaqueBytes(size: Int = 4096): ByteArray = ByteArray(size) { (it % 251 + 7).toByte() }

    private val token = "content://import/source.db"

    /**
     * A single-file PLAINTEXT source: the source resolves, the bytes carry the real SQLite header, and
     * the health check would answer healthy with [version].
     *
     * WHAT CHANGED: this used to be named and documented as a single-file import that "gets all the
     * way through". It no longer can. The encrypted-only import rule refuses a plaintext database at the
     * header check, so the version this seeds is now a value the import must never go on to read — and
     * most tests below assert exactly that. It is kept as the ONE way to arrange "the user picked an
     * unencrypted database", because that is the shape the rule is about, and the happy path it once
     * described is no longer reachable from `executeImportIO` at all.
     */
    private fun arrangePlaintextImport(
        bytes: ByteArray = sqliteBytes(),
        version: Int = 22,
    ) {
        documentSource.displayNames[token] = "payanam.db"
        documentSource.kinds[token] = DocumentKind.FILE
        documentSource.bytesToWrite = bytes
        healthCheck.report = DatabaseHealthReport(isHealthy = true, detectedVersion = version)
    }

    // ============================================================= importDatabase: staging & guards

    @Test
    fun `a plaintext database is refused even with no existing artifacts to overwrite`() =
        runTest(testDispatcher) {
            // There is nothing to lose, so nothing could tempt the code into accepting one: if this
            // path still imported, the only reason to refuse would have been fear of a backup, and a
            // rule that only holds when a file is at risk is not a rule.
            arrangePlaintextImport()

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.showImportWipeConfirm).isFalse()
            assertThat(state.isImporting).isFalse()
            assertThat(state.errorMessage).contains(testUiStrings().settingsImportErrorPlaintextDb)
            assertThat(onSuccessCalls).isEqualTo(0)
            viewModel.dispose()
        }

    @Test
    fun `a refused plaintext database is never marked completed and never opens a session`() =
        runTest(testDispatcher) {
            arrangePlaintextImport()

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            // The two effects a real import has. A refusal that wrote the init mark would make the next
            // launch skip database-init on a database this build never adopted, and a refusal that
            // opened a session would open one on the file it just declined.
            assertThat(flagWriter.calls).isEqualTo(0)
            assertThat(sessionManager.openCalls).isEqualTo(0)
            assertThat(sessionManager.isSessionOpen()).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `a refused plaintext database is never re-encrypted and never opens a passphrase prompt`() =
        runTest(testDispatcher) {
            // The strongest form of "refused, not converted": the encrypted arms are wired on, so a
            // code path that still reached the re-encrypt would have a passphrase to reach for.
            arrangePlaintextImport()
            vault.encryptionEnabled = true
            sessionManager.openDatabase("session-key")
            val sessionOpensBefore = sessionManager.openCalls

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(encryptionProbe.ensureEncryptedCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.awaitingImportPassphrase).isFalse()
            assertThat(viewModel.importDbFile).isNull()
            // The session opened by the test's own setup and not by the import.
            assertThat(sessionManager.openCalls).isEqualTo(sessionOpensBefore)
            viewModel.dispose()
        }

    @Test
    fun `a refused plaintext import restores the encryption checkpoint and deletes the backup`() =
        runTest(testDispatcher) {
            arrangePlaintextImport()
            pathResolver.databaseFile.writeBytes(opaqueBytes(2048))

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            // Capture BEFORE anything was deleted, RESTORE on the way out, then discard the staged
            // encryption prefs - the order is the contract, and a list is the only thing that can see
            // it. A refusal that skipped the restore would leave the user's database in a temp
            // directory; one that skipped the discard would leave the pre-import prefs staged forever.
            assertThat(checkpoint.callOrder.first()).isEqualTo("capture")
            assertThat(checkpoint.countOf("restore")).isEqualTo(1)
            assertThat(checkpoint.callOrder.indexOf("restore"))
                .isGreaterThan(checkpoint.callOrder.indexOf("capture"))
            assertThat(checkpoint.callOrder.indexOf("discard"))
                .isGreaterThan(checkpoint.callOrder.indexOf("restore"))
            // And the temp backup is gone: a refusal must not leave the user's database sitting in a
            // second place waiting for a cleanup that never comes.
            assertThat(tempBackupDir.exists()).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `a plaintext database offered over an existing database is refused and the existing one is byte-identical`() =
        runTest(testDispatcher) {
            // THE ASSERTION THAT DISTINGUISHES A REFUSAL FROM A HALF-DONE IMPORT. The copy happens
            // BEFORE the header check, so the picked file really did land on the user's database path
            // and the refusal really did have to put the old bytes back. An implementation that threw
            // with the right message but left the plaintext file in place would pass a message-only
            // test and fail here.
            arrangePlaintextImport()
            val oldBytes = opaqueBytes(2048)
            pathResolver.databaseFile.writeBytes(oldBytes)

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            // Byte-identical, not merely present. This is the whole assertion: the copy ran first, so
            // the picked file really did land on the user's path, and the refusal really had to put
            // the old bytes back.
            assertThat(pathResolver.databaseFile.readBytes().toList()).isEqualTo(oldBytes.toList())
            // The refusal is honest about the restore it performed, because it did perform one - and
            // a successful restore is what clears the error message, so the message is asserted on
            // the no-backup arms above where it is actually shown to the user.
            assertThat(viewModel.uiState.value.restoreResult).isEqualTo(RestoreResult.RestoredOk)
            viewModel.dispose()
        }

    @Test
    fun `existing artifacts make import stage the source and ask for wipe confirmation`() =
        runTest(testDispatcher) {
            arrangePlaintextImport()
            pathResolver.databaseFile.writeBytes(opaqueBytes(2048))
            val wal = walFile.also { it.writeBytes(opaqueBytes(512)) }
            val shm = shmFile.also { it.writeBytes(ByteArray(32)) }

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importDatabase(token) { countingOnSuccess() }
            advanceUntilIdle()

            // Nothing may have been touched yet: the user has not confirmed the wipe, and the file on
            // disk is still the user's database.
            assertThat(viewModel.uiState.value.showImportWipeConfirm).isTrue()
            assertThat(viewModel.uiState.value.isImporting).isFalse()
            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(pathResolver.databaseFile.length()).isEqualTo(2048L)
            assertThat(wal.length()).isEqualTo(512L)
            assertThat(shm.length()).isEqualTo(32L)
            assertThat(documentSource.copyCalls).isEqualTo(0)
            viewModel.dispose()
        }

    @Test
    fun `confirming the wipe on a plaintext source backs the old database up, then puts it back and deletes the backup`() =
        runTest(testDispatcher) {
            // The backup is taken BEFORE the copy, so on the refusal it is the only thing standing
            // between the user and a lost database - and this test says the two halves of that are
            // true at once: it was created, it was used, and it is gone afterwards.
            arrangePlaintextImport()
            val oldBytes = opaqueBytes(2048)
            pathResolver.databaseFile.writeBytes(oldBytes)

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()
            viewModel.confirmImportAfterWipe { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.showImportWipeConfirm).isFalse()
            assertThat(pathResolver.databaseFile.readBytes().toList()).isEqualTo(oldBytes.toList())
            // No leftover second database: the safety copy is cleaned up once the import is over.
            assertThat(tempBackupDir.exists()).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `confirming the wipe runs the callback staged with the source, not the one passed to confirm`() =
        runTest(testDispatcher) {
            // An ENCRYPTED source, because the staged-callback contract is only observable on a source
            // that gets far enough to run one: a plaintext source is refused at the header check, and a
            // refusal never calls back at all, which would make this test pass for the wrong reason.
            arrangeEncryptedImport()
            plantExistingDatabase()

            var stagedCalls = 0
            var confirmCalls = 0
            val viewModel = newViewModel()
            advanceUntilIdle()

            // Two different callbacks: the screen stages its own at pick time and hands a fresh one to
            // the confirm dialog. If the ViewModel ran the confirm-time one, the screen's continuation
            // (which knows what it was importing) would never run.
            viewModel.importDatabase(token) { stagedCalls++ }
            advanceUntilIdle()
            viewModel.confirmImportAfterWipe { confirmCalls++ }
            advanceUntilIdle()

            assertThat(confirmCalls).isEqualTo(0)
            // The pause is where the staged source lands: still no callback, because the passphrase
            // has not been typed yet.
            assertThat(viewModel.uiState.value.awaitingImportPassphrase).isTrue()
            viewModel.resumeImportWithPassphrase("right-one") { stagedCalls++ }
            advanceUntilIdle()

            assertThat(stagedCalls).isEqualTo(1)
            viewModel.dispose()
        }

    @Test
    fun `cancelling the wipe confirm drops the staged source and leaves the database untouched`() =
        runTest(testDispatcher) {
            arrangePlaintextImport()
            val oldBytes = opaqueBytes(1024)
            pathResolver.databaseFile.writeBytes(oldBytes)

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importDatabase(token) { countingOnSuccess() }
            advanceUntilIdle()
            viewModel.cancelImportWipe()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showImportWipeConfirm).isFalse()
            assertThat(documentSource.copyCalls).isEqualTo(0)
            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(pathResolver.databaseFile.readBytes().toList()).isEqualTo(oldBytes.toList())
            viewModel.dispose()
        }

    @Test
    fun `confirming the wipe with nothing staged is a no-op rather than a crash`() =
        runTest(testDispatcher) {
            // The screen can reach this: the dialog is dismissed by a state change, and the confirm
            // button's click can still land. There is no token, so there is nothing to import - and the
            // important half is that no callback runs and no file is touched.
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.confirmImportAfterWipe { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(documentSource.copyCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.isImporting).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `a second import is rejected while the first is still copying`() =
        runTest(testDispatcher) {
            // The window is REAL, not mocked: `copyInto` runs while the test is blocked inside it on a
            // real dispatcher hop, so the second request genuinely lands while the first still owns
            // `isImporting` - the only moment a re-entry guard is observable at all. The earlier
            // version of this test used the 500ms success delay as the window, which
            // The encrypted-only import rule removed along with the plaintext success path.
            arrangePlaintextImport()
            plantExistingDatabase()
            val copyStarted = CountDownLatch(1)
            val releaseCopy = CountDownLatch(1)
            // `beforeCopy` runs inside `copyInto` on the IO thread, which is the only moment the test
            // is genuinely inside the import rather than merely having scheduled it.
            documentSource.beforeCopy = {
                copyStarted.countDown()
                releaseCopy.await(COPY_HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }

            // A REAL scope for this one test: the launch has to be able to start without the test
            // scheduler driving it, or the copy never begins and the latch never opens.
            val liveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val viewModel = newViewModel(scope = liveScope, ioDispatcher = Dispatchers.IO)

            viewModel.importAndConfirm(token) { onSuccessCalls++ }
            // Wait until the copy is really in flight, then look at the state from here.
            assertThat(copyStarted.await(COPY_HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue()
            assertThat(viewModel.uiState.value.isImporting).isTrue()

            var secondCalls = 0
            viewModel.importDatabase("content://import/other.db") { secondCalls++ }
            // A real dispatcher for the re-entry attempt too, so the guard is exercised rather than
            // merely queued behind a scheduler this test is not driving.
            kotlinx.coroutines.runBlocking { delay(SETTLE_POLL_MILLIS * 5) }

            // The second request never reached the document source at all, and never ran its callback.
            assertThat(documentSource.copyCalls).isEqualTo(1)
            assertThat(secondCalls).isEqualTo(0)

            // Let the first copy finish and wait for the refusal to land on the real IO pool.
            releaseCopy.countDown()
            awaitSettled(viewModel)
            assertThat(onSuccessCalls).isEqualTo(0)
            viewModel.dispose()
            liveScope.cancel()
        }

    @Test
    fun `a second import is rejected while the encrypted passphrase prompt is open`() =
        runTest(testDispatcher) {
            arrangeEncryptedImport()
            plantExistingDatabase()

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()
            // The pause is the dangerous window: `isImporting` is false, but the staged backup is live.
            assertThat(viewModel.uiState.value.awaitingImportPassphrase).isTrue()
            assertThat(viewModel.uiState.value.isImporting).isFalse()

            viewModel.importDatabase("content://import/other.db") { countingOnSuccess() }
            advanceUntilIdle()

            // The guard must hold on `awaitingImportPassphrase` alone, or a second import would
            // delete the only copy of the user's pre-import database.
            assertThat(viewModel.uiState.value.awaitingImportPassphrase).isTrue()
            assertThat(onSuccessCalls).isEqualTo(0)
            viewModel.dispose()
        }

    // ============================================================= executeImportIO: the copy

    @Test
    fun `a container import copies the database and its sidecars, and the fold runs on the sidecar`() =
        runTest(testDispatcher) {
            // A folder export: the picked token is a CONTAINER, so the DB and every sidecar SQLite
            // placed beside it are copied. A test that only ever picked a single file would never see
            // the -wal get written, and the fold below is the branch that depends on it.
            val container = "content://import/folder"
            documentSource.kinds[container] = DocumentKind.CONTAINER
            documentSource.children[container] =
                listOf(
                    DocumentEntry("payanam.db", "doc:db", DocumentKind.FILE),
                    DocumentEntry("payanam.db-wal", "doc:wal", DocumentKind.FILE),
                    DocumentEntry("payanam.db-shm", "doc:shm", DocumentKind.FILE),
                )
            // The database is an ENCRYPTED export's bytes, not `sqliteBytes()`: a folder import of a
            // plaintext database now stops at the header check, so the sidecar copy is what this test
            // can still reach, and it has to reach it through a source that gets that far.
            documentSource.copyBytesByToken =
                mutableMapOf(
                    "doc:db" to opaqueBytes(4096),
                    "doc:wal" to opaqueBytes(1024),
                    "doc:shm" to ByteArray(32),
                )
            healthCheck.report =
                DatabaseHealthReport(isHealthy = true, detectedVersion = 22)
            encryptionProbe.encrypted = true

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(container) { countingOnSuccess() }
            advanceUntilIdle()

            // THREE copies: the database plus each companion the folder carried. A test that asserted
            // a fourth would be asserting that an artefact was invented.
            assertThat(documentSource.copyCalls).isEqualTo(3)
            assertThat(pathResolver.databaseFile.length()).isGreaterThan(0L)
            // The companion is STILL THERE, and that is the fold guard doing its job: an encrypted
            // database's header is not the SQLite magic, so the fold is refused outright and the log
            // is kept for the passphrase flow. The assertion this test used to make - that the `-wal`
            // is gone on success - needed a plaintext database, and plaintext databases are refused.
            assertThat(walFile.exists()).isTrue()
            assertThat(walConsolidator.calls).isEqualTo(0)
            assertThat(viewModel.uiState.value.awaitingImportPassphrase).isTrue()
            viewModel.dispose()
        }

    @Test
    fun `a WAL the fold could not empty is kept and the import still succeeds`() =
        runTest(testDispatcher) {
            val container = "content://import/folder"
            documentSource.kinds[container] = DocumentKind.CONTAINER
            documentSource.children[container] =
                listOf(
                    DocumentEntry("payanam.db", "doc:db", DocumentKind.FILE),
                    DocumentEntry("payanam.db-wal", "doc:wal", DocumentKind.FILE),
                )
            documentSource.copyBytesByToken =
                mutableMapOf("doc:db" to opaqueBytes(4096), "doc:wal" to opaqueBytes(1024))
            healthCheck.report =
                DatabaseHealthReport(isHealthy = true, detectedVersion = 22)
            encryptionProbe.encrypted = true
            // The fold answers FALSE. The contract says that is a real answer, not a failure: the
            // caller keeps the log, because dropping a WAL with a pending write loses that write.
            walConsolidator.answer = false

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(container) { countingOnSuccess() }
            advanceUntilIdle()

            // The WAL survives - that is the behaviour under test, not a leftover.
            assertThat(walFile.exists()).isTrue()
            // And the import is NOT reported as failed: a refused fold is not a refused import. The
            // source pauses for its passphrase, which is a pause and not an error.
            assertThat(viewModel.uiState.value.errorMessage).isNull()
            assertThat(viewModel.uiState.value.awaitingImportPassphrase).isTrue()
            viewModel.dispose()
        }

    @Test
    fun `a copy that produces no file fails the import with the empty-database message`() =
        runTest(testDispatcher) {
            // The source is readable and the copy "succeeds", but no bytes land - the shape a truncated
            // or zero-length provider stream produces. The ViewModel must not treat that as an import.
            documentSource.displayNames[token] = "payanam.db"
            documentSource.bytesToWrite = ByteArray(0)
            healthCheck.report =
                DatabaseHealthReport(isHealthy = true, detectedVersion = 22)

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorMessage).contains("empty")
            // The gate is before the format check, so no fold was attempted on a file that is not there.
            viewModel.dispose()
        }

    @Test
    fun `a picked file that is neither a Payanam nor a readable database is refused with the unreadable message`() =
        runTest(testDispatcher) {
            // Not encrypted, not standard SQLite: the third shape. This arm used to throw a HARDCODED
            // English sentence, justified by a comment saying the shared string was wrong. That string
            // has since been reworded to tell the user to import the encrypted .db and enter its
            // passphrase, so the hardcoded copy is gone and this arm promotes the shared one - which
            // also means the wording is now translated on every platform that reads it.
            documentSource.displayNames[token] = "payanam.db"
            documentSource.bytesToWrite = opaqueBytes(4096)
            healthCheck.report =
                DatabaseHealthReport(isHealthy = true, detectedVersion = 22)

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            val message = viewModel.uiState.value.errorMessage
            assertThat(message).contains(testUiStrings().settingsImportErrorUnreadableDb)
            // The two refusals are different sentences about different files: a file that is not a
            // database at all must not be told it is an unencrypted one.
            assertThat(message).doesNotContain(testUiStrings().settingsImportErrorPlaintextDb)
            // The probe was asked, and the file was not deleted or replaced.
            assertThat(encryptionProbe.isDetectablyEncryptedCalls).isEqualTo(1)
            viewModel.dispose()
        }

    @Test
    fun `a plaintext database is refused on its version, not on its version number`() =
        runTest(testDispatcher) {
            // A version the build could never read, offered as a PLAINTEXT file. The refusal reason
            // must be the plaintext one, not "version 99": the version is never read, because an
            // unencrypted database is refused at the header check before any version is asked for.
            // Getting this backwards would tell a user with an ordinary unencrypted backup that their
            // app is out of date, which is a different problem with a different fix.
            arrangePlaintextImport(version = 99)

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorMessage)
                .contains(testUiStrings().settingsImportErrorPlaintextDb)
            assertThat(viewModel.uiState.value.errorMessage).doesNotContain("99")
            // Never opened: a gate that ran after the session opened would have already caused the
            // damage it exists to prevent.
            assertThat(sessionManager.openCalls).isEqualTo(0)
            assertThat(flagWriter.calls).isEqualTo(0)
            viewModel.dispose()
        }

    @Test
    fun `an unencrypted database is refused before the health check is ever asked about it`() =
        runTest(testDispatcher) {
            // The post-import usability check moved onto the ENCRYPTED path with the resume, which is
            // the only one that still finishes. So a plaintext file no longer reaches it - and that is
            // the assertion: the check is not asked, because the file was refused first. A code path
            // that read the file's schema and health before refusing would be doing the very work the
            // rule exists to avoid.
            arrangePlaintextImport()

            val viewModel = newViewModel()
            advanceUntilIdle()
            // The `init` block asks about the database once before the user touches anything, so the
            // baseline is read HERE rather than before the ViewModel existed.
            val checksBefore = healthCheck.checkCalls

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorMessage)
                .contains(testUiStrings().settingsImportErrorPlaintextDb)
            // Only the `init` status check ran; the import asked about nothing.
            assertThat(healthCheck.checkCalls).isEqualTo(checksBefore)
            assertThat(sessionManager.openCalls).isEqualTo(0)
            viewModel.dispose()
        }

    @Test
    fun `a session that refuses to open after a correct passphrase fails the import and restores the backup`() =
        runTest(testDispatcher) {
            // The same failure, on the only path that still completes one. The old database is back
            // byte for byte, because this failed AFTER the file was replaced - which is exactly the
            // case the safety copy exists for.
            arrangeEncryptedImport()
            val oldBytes = opaqueBytes(2048)
            pathResolver.databaseFile.writeBytes(oldBytes)
            sessionManager.openFailure = IllegalStateException("session unavailable")

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()
            viewModel.resumeImportWithPassphrase("right-one") { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(pathResolver.databaseFile.readBytes().toList()).isEqualTo(oldBytes.toList())
            assertThat(viewModel.uiState.value.restoreResult).isEqualTo(RestoreResult.RestoredOk)
            viewModel.dispose()
        }

    @Test
    fun `a failed import with no database to back up reports the failure without claiming a restore`() =
        runTest(testDispatcher) {
            documentSource.displayNames[token] = "payanam.db"
            documentSource.bytesToWrite = opaqueBytes(2048)

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            // There was no backup, so claiming one was restored would be a lie the screen would show
            // the user as "your data was recovered".
            assertThat(viewModel.uiState.value.restoreResult).isNull()
            assertThat(viewModel.uiState.value.errorMessage).isNotNull()
            assertThat(checkpoint.countOf("capture")).isEqualTo(1)
            assertThat(checkpoint.countOf("restore")).isEqualTo(1)
            viewModel.dispose()
        }

    @Test
    fun `a resume whose backup no longer holds the file reports RestoreFailed honestly`() =
        runTest(testDispatcher) {
            arrangeEncryptedImport()
            plantExistingDatabase()
            sessionManager.openFailure = IllegalStateException("session unavailable")
            // The backup is destroyed WHILE the copy is running - the only moment the test is on the
            // stack, because the restore happens later on the coroutine.
            documentSource.beforeCopy = { tempBackupDir.deleteRecursively() }

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()
            viewModel.resumeImportWithPassphrase("right-one") { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.restoreResult).isEqualTo(RestoreResult.RestoreFailed)
            viewModel.dispose()
        }

    @Test
    fun `an unopenable import message is translated rather than shown raw`() =
        runTest(testDispatcher) {
            // The provider's own wording leaks a SQL error to the user unless the screen recognises it.
            documentSource.displayNames[token] = "payanam.db"
            documentSource.copyFailure = IllegalStateException("Unable to open database file")
            healthCheck.report =
                DatabaseHealthReport(isHealthy = true, detectedVersion = 22)

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            val message = viewModel.uiState.value.errorMessage
            assertThat(message).contains("could not be converted to encrypted format")
            assertThat(message).doesNotContain("Unable to open database file")
            viewModel.dispose()
        }

    @Test
    fun `a source the picker could not open fails the import instead of importing nothing`() =
        runTest(testDispatcher) {
            // COPY_NOT_ATTEMPTED is a distinct answer from "copied zero bytes", and it must not be
            // mistaken for a successful copy of an empty database.
            documentSource.displayNames[token] = "payanam.db"
            documentSource.copyAnswer = -1L
            healthCheck.report =
                DatabaseHealthReport(isHealthy = true, detectedVersion = 22)

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorMessage).contains("Could not open")
            viewModel.dispose()
        }

    @Test
    fun `an encrypted-mode import refuses a plaintext database rather than converting it to the session key`() =
        runTest(testDispatcher) {
            // This test used to assert the OPPOSITE: that a plaintext backup picked while the user has
            // an encrypted session open is RE-ENCRYPTED to the same key. That conversion is what
            // The encrypted-only import rule removed, because it made the import path the only route by
            // which unencrypted data entered the app. The arrangement below is kept deliberately - an
            // open session is exactly when a conversion would have been most tempting - and the
            // assertion is now that the conversion does not happen.
            arrangePlaintextImport()
            vault.encryptionEnabled = true
            sessionManager.openDatabase("session-key")
            val sessionOpensBefore = sessionManager.openCalls

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorMessage)
                .contains(testUiStrings().settingsImportErrorPlaintextDb)
            // The conversion never ran, and the only session open is the test's own setup.
            assertThat(encryptionProbe.ensureEncryptedCalls).isEqualTo(0)
            assertThat(sessionManager.openCalls).isEqualTo(sessionOpensBefore)
            viewModel.dispose()
        }

    @Test
    fun `encrypted mode with no open session still refuses a plaintext database, not a missing session`() =
        runTest(testDispatcher) {
            // The refusal has to be the SAME refusal whether or not a session happens to be open, or
            // the message a user reads would depend on unrelated state. There is no longer a
            // "no open passphrase session" arm to reach: nothing on the plaintext path asks for a
            // passphrase, because nothing on it converts anything.
            arrangePlaintextImport()
            vault.encryptionEnabled = true
            healthCheck.checkFailure = null

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            val message = viewModel.uiState.value.errorMessage
            assertThat(message).contains(testUiStrings().settingsImportErrorPlaintextDb)
            assertThat(message).doesNotContain("no open passphrase session")
            // Nothing was marked, and nothing was opened: a plaintext row in an encrypted database is
            // unreadable on the next launch.
            assertThat(flagWriter.calls).isEqualTo(0)
            assertThat(sessionManager.openCalls).isEqualTo(0)
            viewModel.dispose()
        }

    // ============================================================= the encrypted import: pause & resume

    @Test
    fun `an encrypted import pauses, keeps the backup, and opens the passphrase prompt`() =
        runTest(testDispatcher) {
            arrangeEncryptedImport()
            val oldBytes = opaqueBytes(2048)
            pathResolver.databaseFile.writeBytes(oldBytes)

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.awaitingImportPassphrase).isTrue()
            assertThat(state.isImporting).isFalse()
            assertThat(state.importPassphraseError).isNull()
            assertThat(state.errorMessage).isNull()
            // The staged database is the file the resume will verify against.
            assertThat(viewModel.importDbFile?.absolutePath).isEqualTo(pathResolver.databaseFilePath())
            // The backup is still there: the pause is not a completion.
            assertThat(tempBackupDir.exists()).isTrue()
            // And the checkpoint is NOT discarded on this path - the resume still needs to restore it.
            assertThat(checkpoint.countOf("discard")).isEqualTo(0)
            viewModel.dispose()
        }

    @Test
    fun `the right passphrase completes the import, marks it and clears every piece of staged state`() =
        runTest(testDispatcher) {
            arrangeEncryptedImport()
            plantExistingDatabase()

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()
            viewModel.resumeImportWithPassphrase("right-one") { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(encryptionProbe.canOpenCalls).isEqualTo(1)
            assertThat(encryptionProbe.canOpenAnswer).isTrue()
            assertThat(vault.configuredWith).isEqualTo("right-one")
            // The mark carries the passphrase the user typed, because the file it writes into is
            // encrypted with it.
            assertThat(flagWriter.lastPassphrase).isEqualTo("right-one")
            assertThat(sessionManager.openedWith).isEqualTo("right-one")
            assertThat(checkpoint.countOf("discard")).isEqualTo(1)
            assertThat(onSuccessCalls).isEqualTo(1)
            val state = viewModel.uiState.value
            assertThat(state.awaitingImportPassphrase).isFalse()
            assertThat(state.isImporting).isFalse()
            // Nothing staged survives a success.
            assertThat(viewModel.importDbFile).isNull()
            assertThat(tempBackupDir.exists()).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `a wrong passphrase keeps the prompt open and preserves the staged database and backup`() =
        runTest(testDispatcher) {
            arrangeEncryptedImport()
            plantExistingDatabase()
            encryptionProbe.canOpenAnswer = false

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()
            viewModel.resumeImportWithPassphrase("wrong-one") { countingOnSuccess() }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            // The prompt stays open, with the reason ON the field rather than as a fatal error.
            assertThat(state.awaitingImportPassphrase).isTrue()
            assertThat(state.importPassphraseError).contains("Wrong passphrase")
            assertThat(state.errorMessage).isNull()
            assertThat(state.isImporting).isFalse()
            assertThat(onSuccessCalls).isEqualTo(0)
            // The user gets to try again, so the staged file and its backup must still be there.
            assertThat(viewModel.importDbFile?.absolutePath).isEqualTo(pathResolver.databaseFilePath())
            assertThat(tempBackupDir.exists()).isTrue()
            // And the vault was never configured with a passphrase that did not open the file.
            assertThat(vault.configureCalls).isEqualTo(0)
            viewModel.dispose()
        }

    @Test
    fun `resuming with no staged import is refused instead of verifying against nothing`() =
        runTest(testDispatcher) {
            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.resumeImportWithPassphrase("anything") { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(encryptionProbe.canOpenCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.errorMessage).isNotNull()
            viewModel.dispose()
        }

    @Test
    fun `a wrong import passphrase is NOT counted as a failed unlock`() =
        runTest(testDispatcher) {
            // RECORDED, NOT FIXED. This test documents the gap it can prove; it does not close it.
            //
            // The import prompt verifies a passphrase through `encryptionProbe.canOpen`, which is a
            // plain boolean - and `DatabaseInitViewModel` NEVER calls `PassphraseVault.recordFailedUnlockAttempt`,
            // not on a wrong passphrase and not anywhere else in the class (grep: the only hit is the
            // KDoc at :119). The sibling that unlocks the SAME vault on the SAME screen does
            // (`DatabasePassphraseUnlockViewModel:230`, with a comment explaining why the increment
            // belongs in exactly one place).
            //
            // So the escalation schedule the vault KDoc promises ("the vault owns the failure counter and
            // the escalation schedule on both platforms") is not applied on this path: the prompt can be
            // retried forever at full speed. The two verifications also differ in kind - `canOpen` is a
            // file probe, not `PassphraseVault.verifyPassphrase` - so the vault may never see the attempt
            // at all. What this test proves is the observable half: the counter does not move.
            arrangeEncryptedImport()
            plantExistingDatabase()
            encryptionProbe.canOpenAnswer = false
            val failuresBefore = vault.recordedFailureLockout

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()
            // Three wrong answers, as a guessing user would type them.
            repeat(3) {
                viewModel.resumeImportWithPassphrase("guess-$it") { countingOnSuccess() }
                advanceUntilIdle()
            }

            assertThat(encryptionProbe.canOpenCalls).isEqualTo(3)
            // The prompt is still open and still offering another try: no lockout, no escalation.
            assertThat(viewModel.uiState.value.awaitingImportPassphrase).isTrue()
            assertThat(vault.recordFailedUnlockAttemptCount).isEqualTo(0)
            assertThat(vault.recordedFailureLockout).isEqualTo(failuresBefore)
            viewModel.dispose()
        }

    @Test
    fun `a vault that refuses to configure after a correct passphrase restores the backup and closes the prompt`() =
        runTest(testDispatcher) {
            arrangeEncryptedImport()
            plantExistingDatabase()
            vault.configureOutcome = PassphraseConfigureOutcome.STORE_UNAVAILABLE

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()
            viewModel.resumeImportWithPassphrase("right-one") { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            val state = viewModel.uiState.value
            assertThat(state.awaitingImportPassphrase).isFalse()
            // A wrong-passphrase prompt would let the user retry, but the vault already refused, so
            // retrying cannot help: the user is told the error instead.
            assertThat(state.importPassphraseError).isNull()
            assertThat(state.errorMessage).isNotNull()
            assertThat(state.restoreResult).isEqualTo(RestoreResult.RestoredOk)
            viewModel.dispose()
        }

    @Test
    fun `a health check that rejects the resumed database reports RestoreFailed when the backup is gone`() =
        runTest(testDispatcher) {
            arrangeEncryptedImport()
            plantExistingDatabase()
            // The backup is destroyed while the resume is verifying - the checkpoint restore then has
            // nothing to put back, which is the one case where the screen must say so.
            documentSource.beforeCopy = { tempBackupDir.deleteRecursively() }
            healthCheck.report = DatabaseHealthReport(isHealthy = true, detectedVersion = 22)
            // The encrypted path never reaches the health check inside `executeImportIO` - it pauses
            // before the schema gate - so only the `init` status check has run. Every call from here on
            // is the resume's own usability check, and it fails.
            healthCheck.reportsBeforeSwitch = 1
            healthCheck.unhealthyReport =
                DatabaseHealthReport(
                    isHealthy = false,
                    errorMessage = "Database is corrupted",
                    needsRepair = true,
                    detectedVersion = 22,
                )

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()
            viewModel.resumeImportWithPassphrase("right-one") { countingOnSuccess() }
            advanceUntilIdle()

            assertThat(onSuccessCalls).isEqualTo(0)
            assertThat(viewModel.uiState.value.restoreResult).isEqualTo(RestoreResult.RestoreFailed)
            assertThat(checkpoint.countOf("restore")).isAtLeast(1)
            viewModel.dispose()
        }

    @Test
    fun `cancelling the passphrase prompt deletes the half-imported database and puts the old one back`() =
        runTest(testDispatcher) {
            arrangeEncryptedImport()
            val oldBytes = opaqueBytes(2048)
            pathResolver.databaseFile.writeBytes(oldBytes)

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()
            viewModel.cancelImportPassphrase()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.awaitingImportPassphrase).isFalse()
            assertThat(state.isImporting).isFalse()
            assertThat(state.importPassphraseError).isNull()
            // The user walked away from the import, so the encrypted file that was copied in must NOT
            // be left on disk pretending to be the app's database.
            assertThat(pathResolver.databaseFile.readBytes().toList()).isEqualTo(oldBytes.toList())
            assertThat(viewModel.importDbFile).isNull()
            assertThat(checkpoint.countOf("restore")).isEqualTo(1)
            viewModel.dispose()
        }

    @Test
    fun `cancelling the passphrase prompt after the backup is already gone leaves no restore claim`() =
        runTest(testDispatcher) {
            arrangeEncryptedImport()
            documentSource.beforeCopy = { tempBackupDir.deleteRecursively() }

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()
            viewModel.cancelImportPassphrase()
            advanceUntilIdle()

            // No backup means the old database really is gone. The state says so by claiming nothing -
            // it does not invent a RestoreResult on a path that never had one.
            assertThat(viewModel.uiState.value.awaitingImportPassphrase).isFalse()
            assertThat(viewModel.uiState.value.restoreResult).isNull()
            assertThat(pathResolver.databaseFile.exists()).isFalse()
            viewModel.dispose()
        }

    @Test
    fun `cancelling the passphrase prompt re-checks the database status`() =
        runTest(testDispatcher) {
            arrangeEncryptedImport()
            plantExistingDatabase()
            val checksBefore = healthCheck.checkCalls

            val viewModel = newViewModel()
            advanceUntilIdle()

            viewModel.importAndConfirm(token) { countingOnSuccess() }
            advanceUntilIdle()
            viewModel.cancelImportPassphrase()
            advanceUntilIdle()

            // The screen goes back to the status view, and the status view must be told something -
            // otherwise it still shows the pre-import verdict, which is a database that no longer exists.
            assertThat(healthCheck.checkCalls).isGreaterThan(checksBefore)
            viewModel.dispose()
        }

    // ============================================================= the two dimension-setup lambda tails

    @Test
    fun `a dimension seed that fails on the no-wipe path keeps the pending passphrase for a retry`() =
        runTest(testDispatcher) {
            dimensionPersister.failure = IllegalStateException("seed write failed")

            val viewModel = newViewModel()
            advanceUntilIdle()

            // The create path stages a passphrase first; the import tests in this file do not, so the
            // staging is done here so that the retry arm has something to retain.
            viewModel.createNewDatabase("first-pass")
            advanceUntilIdle()
            viewModel.completeNewDatabaseDimensionSetup(dimensionInputs) { countingOnSuccess() }
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.errorMessage).contains("seed write failed")
            assertThat(state.awaitingDimensionSetup).isTrue()
            assertThat(state.isCreating).isFalse()
            assertThat(onSuccessCalls).isEqualTo(0)
            viewModel.dispose()
        }

    // ============================================================= helpers

    /**
     * Runs the import the way a user does: picks a source, and CONFIRMS the wipe if the ViewModel
     * asked for one.
     *
     * Planting an existing database is how the backup/restore arms are reached - but it is also exactly
     * what makes `importDatabase` stop and ask. A test that planted a file and then called
     * `importDatabase` alone had not imported anything, and every assertion after it was asserting on a
     * staged source. Confirming only when the prompt is actually up keeps this honest for tests that
     * plant nothing as well, and the branch is itself the evidence that the prompt appeared.
     *
     * No `advanceUntilIdle` between the two calls is needed: staging is synchronous state, and the
     * import coroutine is only launched by the confirm.
     */
    private fun DatabaseInitViewModel.importAndConfirm(
        sourceToken: String = token,
        onSuccess: () -> Unit,
    ) {
        importDatabase(sourceToken, onSuccess)
        if (uiState.value.showImportWipeConfirm) confirmImportAfterWipe(onSuccess)
    }

    /** A pre-existing database, so the import takes the backup path. */
    private fun plantExistingDatabase() {
        pathResolver.databaseFile.writeBytes(opaqueBytes(2048))
    }

    /**
     * A source that is NOT standard SQLite and IS detectably encrypted - the shape of a real Payanam
     * export, and the only one that pauses for a passphrase.
     */
    private fun arrangeEncryptedImport() {
        documentSource.displayNames[token] = "payanam.db"
        documentSource.bytesToWrite = opaqueBytes(4096)
        encryptionProbe.encrypted = true
        healthCheck.report =
            DatabaseHealthReport(isHealthy = true, detectedVersion = 22)
    }

    private val dimensionInputs =
        listOf(
            NewDatabaseDimensionInput(id = "health", label = "Health", colorHex = "#FF0000", isEnabled = true),
        )

    private companion object {
        const val SETTLE_TIMEOUT_MILLIS = 20_000L
        const val POLL_INTERVAL_MILLIS = 20L
        const val SETTLE_POLL_MILLIS = 200L

        /** Upper bound on how long `beforeCopy` may hold the import, so a broken latch fails fast. */
        const val COPY_HOLD_TIMEOUT_SECONDS = 20L
    }
}
