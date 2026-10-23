//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.database

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.port.DatabasePathResolver
import io.payanam.domain.port.DatabaseRekey
import io.payanam.domain.port.DatabaseSessionManager
import io.payanam.domain.port.ProcessRestart
import io.payanam.domain.port.ProcessRestartOutcome
import io.payanam.domain.port.ProcessRestartRefusal
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.database.DatabaseRekeySequence
import io.payanam.shared.database.WalCheckpointRule
import io.payanam.shared.security.PassphraseConfigureOutcome
import io.payanam.shared.security.PassphraseUpdateOutcome
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.SharedPassphrasePolicy
import io.payanam.shared.security.VaultAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The passphrase change over the ports it was rewritten onto.
 *
 * WHY THE FAKE IS KEYED ON WHAT IT REPORTED, NOT ON A COUNT: the failure the WAL gate exists for is a fold
 * that was asked for, ran, and still left frames behind — the main file is then a snapshot missing the user's
 * most recent writes, and re-keying rewrites the key on that stale file alone. A fake that only counted
 * calls would read as a flushed database on the very frame the main file was not one, so [FakeSession]
 * returns a real [WalCheckpointRule.FoldReport] and records the ORDERED calls it was driven through.
 *
 * WHY THE ARTIFACT BYTES ARE ASSERTED AND NOT JUST A CALL LOG: the backup/restore/cleanup trio is the only
 * thing standing between a failed rekey and an unreadable database, so the arms below write real bytes into
 * a real file, corrupt it the way a failed rekey would, and assert the ORIGINAL bytes came back and the
 * `.bak` litter did not survive. A log line cannot tell a restore from a delete.
 *
 * NO ROBOLECTRIC, NO ANDROID IMPORTS: this module runs plain JVM tests, which is the whole point of the
 * move — the class no longer has a `Context`, a `DatabaseEncryptionManager`, or a lifecycle scope to stand
 * up, so every collaborator is a fake and the database is a file in a temp directory.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabasePassphraseChangeViewModelTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var databaseDirectory: File
    private lateinit var databaseFile: File
    private lateinit var resolver: ChangeSuiteDatabasePathResolver
    private lateinit var vault: FakeVault
    private lateinit var rekey: FakeRekey
    private lateinit var session: FakeSession
    private lateinit var restart: FakeProcessRestart

    /** Valid under [SharedPassphrasePolicy]: 12+ characters with all four character classes. */
    private val newPassphrase = "N3w!Passphrase"

    private val currentPassphrase = "Old!Passphrase1"

    @Before
    fun setUp() {
        databaseDirectory = temporaryFolder.newFolder("databases")
        databaseFile = File(databaseDirectory, "payanam.db")
        databaseFile.writeBytes(ORIGINAL_BYTES)
        resolver = ChangeSuiteDatabasePathResolver(databaseDirectory)
        vault =
            FakeVault().apply {
                encryptionEnabled = true
                verifiesCurrent = true
            }
        rekey = FakeRekey()
        session = FakeSession()
        restart = FakeProcessRestart()
    }

    // ------------------------------------------------------------------ the WAL gate

    @Test
    fun `a fold that left frames behind stops the change and nothing is re-keyed`() =
        runTest {
            // A fold that ran, was NOT blocked, and still left frames in the log: the main file does not hold
            // every write, so the re-key must not start.
            session.flushReport = WalCheckpointRule.FoldReport(busy = 0, logFrames = 3, checkpointedFrames = 1)
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.isSaving).isFalse()
            assertThat(viewModel.uiState.value.isSuccess).isFalse()
            assertThat(viewModel.uiState.value.errorReasonCode).isNotNull()
            // The change did not start: the stored passphrase is untouched, so the OLD one is still the one the
            // vault would accept and the new one is not. Nothing is left half-changed for the user.
            assertThat(vault.updateCalls).isEmpty()
            assertThat(vault.configuredPassphrase()).isNull()
            assertThat(rekey.calls).isEmpty()
            // The ONLY thing asked of the session was the flush: the session was never released for a re-key, and
            // the flush carried the key the pending frames are actually encrypted under.
            assertThat(session.events).containsExactly("flush")
            assertThat(session.flushedWithPassphrase).isEqualTo(currentPassphrase)
            assertThat(restart.calls).isEqualTo(0)
        }

    @Test
    fun `a fold the engine never answered stops the change too`() =
        runTest {
            // NO_ROW is the answer when the pragma did not run. It is non-empty by construction, so a fold
            // nobody can read must never read as a flushed database.
            session.flushReport = WalCheckpointRule.NO_ROW
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.isSuccess).isFalse()
            assertThat(rekey.calls).isEmpty()
            assertThat(vault.updateCalls).isEmpty()
            assertThat(session.events).containsExactly("flush")
        }

    @Test
    fun `a fold that threw is refused rather than treated as a flushed database`() =
        runTest {
            session.flushFailure = IllegalStateException("checkpoint engine unavailable")
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.isSuccess).isFalse()
            assertThat(session.events).containsExactly("flush")
            assertThat(rekey.calls).isEmpty()
        }

    @Test
    fun `a blocked fold stops the change`() =
        runTest {
            // busy = 1: another connection held the database, so nothing was folded at all.
            session.flushReport = WalCheckpointRule.FoldReport(busy = 1, logFrames = 0, checkpointedFrames = 0)
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.isSuccess).isFalse()
            assertThat(rekey.calls).isEmpty()
            assertThat(session.events).containsExactly("flush")
        }

    @Test
    fun `a refused fold leaves no backup litter behind`() =
        runTest {
            session.flushReport = WalCheckpointRule.FoldReport(busy = 0, logFrames = 2, checkpointedFrames = 0)
            File(databaseDirectory, WAL_NAME).writeBytes(WAL_BYTES)
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            advanceUntilIdle()

            // The backup siblings are removed on this path too: a refusal that leaves `.bak` copies of the user's
            // database next to it is a refusal that leaves an unencrypted copy of their data on disk.
            assertThat(backupSiblings()).isEmpty()
            assertThat(databaseFile.readBytes()).isEqualTo(ORIGINAL_BYTES)
            assertThat(File(databaseDirectory, WAL_NAME).readBytes()).isEqualTo(WAL_BYTES)
        }

    // ------------------------------------------------------------------ the succeeding path

    @Test
    fun `an empty fold lets the change through the gate in order`() =
        runTest {
            // The fold ran, was not blocked, and left nothing: the main file holds every write.
            session.flushReport = WalCheckpointRule.FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 4)
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.isSuccess).isTrue()
            assertThat(viewModel.uiState.value.errorReasonCode).isNull()
            assertThat(viewModel.uiState.value.isSaving).isFalse()
            // Ordered on WHAT WAS WRITTEN: the flush was awaited first, and only then was the session released
            // for the re-key. The reverse order is the exact bug the gate exists to prevent.
            assertThat(session.events).containsAtLeast("flush", "close").inOrder()
            assertThat(session.flushedWithPassphrase).isEqualTo(currentPassphrase)
            // The re-key ran on the file this platform's resolver names, under the two passphrases it was given.
            assertThat(rekey.calls).hasSize(1)
            assertThat(rekey.calls.single().databaseFile).isEqualTo(databaseFile)
            assertThat(rekey.calls.single().currentPassphrase).isEqualTo(currentPassphrase)
            assertThat(rekey.calls.single().newPassphrase).isEqualTo(newPassphrase)
            // And the vault was asked to store the new passphrase, which is the only way the next cold boot can
            // authenticate against it.
            assertThat(vault.updateCalls).containsExactly(PassphraseUpdateOutcome.UPDATED)
            assertThat(vault.configuredPassphrase()).isEqualTo(newPassphrase)
            // Success ends the process so a cold boot re-authenticates: the session was closed and the restart
            // asked for, which on Android is the last thing that runs.
            assertThat(restart.calls).isEqualTo(1)
            assertThat(backupSiblings()).isEmpty()
            // The file really was rewritten by the re-key and then left that way on the SUCCESS path: the
            // backups are cleaned up rather than restored, so what survives is the re-keyed database.
            assertThat(databaseFile.readBytes()).isEqualTo(REKEYED_BYTES)
        }

    @Test
    fun `a rekey that reports the file is already on the target passphrase still completes the change`() =
        runTest {
            session.flushReport = WalCheckpointRule.FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 4)
            rekey.outcome = DatabaseRekeySequence.Outcome.ALREADY_ON_TARGET_PASSPHRASE
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.isSuccess).isTrue()
            assertThat(vault.configuredPassphrase()).isEqualTo(newPassphrase)
            assertThat(restart.calls).isEqualTo(1)
        }

    // ------------------------------------------------------------------ rollback

    @Test
    fun `a rekey that threw restores the bytes and re-opens the session on the old passphrase`() =
        runTest {
            session.flushReport = WalCheckpointRule.FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 4)
            File(databaseDirectory, WAL_NAME).writeBytes(WAL_BYTES)
            rekey.failure = IllegalStateException("PRAGMA rekey failed")
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.isSuccess).isFalse()
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("generic")
            // The vault was never asked to store a passphrase that the file does not have.
            assertThat(vault.updateCalls).isEmpty()
            assertThat(vault.configuredPassphrase()).isNull()
            // The ORIGINAL bytes are back — both the main file and the sidecar the fold had emptied — so the user
            // still has their most recent writes and no `.bak` litter.
            assertThat(databaseFile.readBytes()).isEqualTo(ORIGINAL_BYTES)
            assertThat(File(databaseDirectory, WAL_NAME).readBytes()).isEqualTo(WAL_BYTES)
            assertThat(backupSiblings()).isEmpty()
            // Room was re-opened with the passphrase that still works, so the app is usable again.
            assertThat(session.openedWithPassphrase).isEqualTo(currentPassphrase)
            assertThat(restart.calls).isEqualTo(0)
        }

    @Test
    fun `a vault that could not store the new passphrase restores the bytes it just threw away`() =
        runTest {
            session.flushReport = WalCheckpointRule.FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 4)
            // The rekey already rewrote the file under the new key when the store refused. Reporting success here
            // would leave a database no passphrase can open and a vault holding the old one.
            vault.updateOutcome = PassphraseUpdateOutcome.STORE_UNAVAILABLE
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.isSuccess).isFalse()
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("generic")
            assertThat(vault.configuredPassphrase()).isNull()
            assertThat(databaseFile.readBytes()).isEqualTo(ORIGINAL_BYTES)
            assertThat(backupSiblings()).isEmpty()
            assertThat(session.openedWithPassphrase).isEqualTo(currentPassphrase)
            assertThat(restart.calls).isEqualTo(0)
        }

    @Test
    fun `a restore that reaches a file the migration destroyed still reports the change as failed`() =
        runTest {
            session.flushReport = WalCheckpointRule.FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 4)
            rekey.failure = IllegalStateException("PRAGMA rekey failed")
            // The backup of the main file is gone, so the restore can put nothing back — the failure has to be
            // REPORTED rather than papered over by a restore that restored nothing.
            databaseFile.delete()
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.isSuccess).isFalse()
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("generic")
            assertThat(restart.calls).isEqualTo(0)
        }

    // ------------------------------------------------------------------ the refusing arms before the gate

    @Test
    fun `a database file that is not there refuses without asking the session for anything`() =
        runTest {
            databaseFile.delete()
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("generic")
            assertThat(viewModel.uiState.value.isSuccess).isFalse()
            assertThat(session.events).isEmpty()
            assertThat(rekey.calls).isEmpty()
        }

    @Test
    fun `a database that is not encrypted refuses the change rather than writing a passphrase for nothing`() =
        runTest {
            vault.encryptionEnabled = false
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("generic")
            assertThat(vault.updateCalls).isEmpty()
            assertThat(rekey.calls).isEmpty()
            assertThat(session.events).isEmpty()
        }

    @Test
    fun `a current passphrase the vault does not verify is the user's to fix and nothing is touched`() =
        runTest {
            vault.verifiesCurrent = false
            val viewModel = newViewModel()

            viewModel.submit("Wrong!Passphrase2", newPassphrase, newPassphrase)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("current_invalid")
            assertThat(viewModel.uiState.value.isSuccess).isFalse()
            // The gate is never reached: no flush was asked for, so no session was released and nothing was
            // backed up on the strength of a passphrase that never verified.
            assertThat(session.events).isEmpty()
            assertThat(rekey.calls).isEmpty()
        }

    @Test
    fun `a passphrase the policy rejects never reaches the vault`() =
        runTest {
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, "short", "short")
            advanceUntilIdle()

            val expected = SharedPassphrasePolicy.validate("short")
            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo(expected.reasonCode)
            assertThat(vault.verifyCalls).isEmpty()
            assertThat(session.events).isEmpty()
        }

    @Test
    fun `a confirmation that does not match the new passphrase is refused before anything is asked`() =
        runTest {
            val viewModel = newViewModel()

            viewModel.submit(currentPassphrase, newPassphrase, "Different!Pass9")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("mismatch")
            assertThat(vault.verifyCalls).isEmpty()
            assertThat(session.events).isEmpty()
        }

    // ------------------------------------------------------------------ lifecycle

    @Test
    fun `disposing the ViewModel stops the work it already started`() =
        runTest {
            session.flushReport = WalCheckpointRule.FoldReport(busy = 0, logFrames = 0, checkpointedFrames = 4)
            // A scope the test owns, because `dispose()` cancels it and `backgroundScope` is the runner's.
            val ownedScope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val viewModel = newViewModel(ownedScope)

            viewModel.submit(currentPassphrase, newPassphrase, newPassphrase)
            viewModel.dispose()
            advanceUntilIdle()

            // Nothing ran after the release: no re-key, no vault write, no restart. `onCleared` used to be what
            // stopped this, and the platform calls this where it used to call that.
            assertThat(rekey.calls).isEmpty()
            assertThat(vault.updateCalls).isEmpty()
            assertThat(restart.calls).isEqualTo(0)
        }

    /**
     * The ViewModel under test, with BOTH of its scopes on the test scheduler.
     *
     * The ViewModel's own scope and the `ioDispatcher` it hops to are two `StandardTestDispatcher`s over
     * `testScheduler`, so `advanceUntilIdle()` settles the whole change. Nothing here is the runner's
     * `backgroundScope`: the change is the SUBJECT of each test rather than background bookkeeping, and it
     * has to be work this test advances itself. A dispatcher built outside `runTest` belongs to a scheduler
     * the test does not own, which is how every assertion here once read a state nothing had run yet.
     */
    private fun TestScope.newViewModel(scope: CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler))) =
        DatabasePassphraseChangeViewModel(
            databasePathResolver = resolver,
            passphraseVault = vault,
            rekey = rekey,
            sessionManager = session,
            processRestart = restart,
            scope = scope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun backupSiblings(): List<File> = databaseDirectory.listFiles()?.filter { it.name.contains(".before_") }.orEmpty()

    /** Every file in the database directory with its bytes, so a failure says WHICH artifact is wrong. */
    private fun artifactContents(): Map<String, List<Byte>> =
        databaseDirectory.listFiles()?.associate { it.name to it.readBytes().toList() }.orEmpty()

    private companion object {
        val ORIGINAL_BYTES = ByteArray(16) { it.toByte() }
        val WAL_BYTES = ByteArray(8) { (it + 100).toByte() }
    }
}

/** What [FakeRekey] writes over the database: NOT the bytes a restore could put back by leaving it alone. */
private val REKEYED_BYTES = ByteArray(16) { (it + 200).toByte() }

/** The same for the `-wal` sibling, which the fold had emptied and the re-key then rewrote. */
private val REKEYED_WAL_BYTES = ByteArray(8) { (it + 201).toByte() }

/**
 * The `-wal` sibling's name, spelled through the shared artifact family rather than a second literal, so a
 * rename there cannot leave this suite asserting against a file the code no longer writes.
 */
private val WAL_NAME = DatabaseArtifacts.filesFor(File("payanam.db")).single { it.name.endsWith("-wal") }.name

/**
 * A resolver over a real directory: this class does real [File] arithmetic on whatever path it returns, so
 * a path into a real temp directory is the honest fake rather than one that always answers a constant.
 *
 * Named for this suite rather than reusing the init suite's, which is `internal` and does the same job for
 * a ViewModel with different collaborators; two top-level declarations of one name in one package is a
 * compile error, so the two fakes stay separate rather than one growing a parameter per caller.
 */
private class ChangeSuiteDatabasePathResolver(
    private val directory: File,
    private val fileName: String = "payanam.db",
) : DatabasePathResolver {
    override fun databaseDirectory(): String = directory.absolutePath

    override fun databaseFilePath(): String = File(directory, fileName).absolutePath
}

/**
 * The vault answers for verification, the encryption state and the replacement, and records what it was
 * asked to store — the assertions read [configuredPassphrase] rather than a call count, because the
 * question is which passphrase the vault would accept next.
 */
private class FakeVault : PassphraseVault {
    var encryptionEnabled: Boolean = false
    var verifiesCurrent: Boolean = false
    var updateOutcome: PassphraseUpdateOutcome = PassphraseUpdateOutcome.UPDATED

    /** The passphrases [updatePassphrase] was asked to store, converted back from its own CharArray. */
    val updateCalls = mutableListOf<PassphraseUpdateOutcome>()

    /** Every CharArray this fake was handed, kept so a test can prove they were not left behind. */
    var storedPassphrase: CharArray? = null

    /** One entry per verification, so a test asserts the vault was NOT asked rather than counting calls. */
    val verifyCalls = mutableListOf<String>()

    fun configuredPassphrase(): String? = storedPassphrase?.concatToString()

    override fun hasPassphraseConfigured(): Boolean = storedPassphrase != null

    override fun availability(): VaultAvailability = VaultAvailability.AVAILABLE

    override fun verifyPassphrase(passphrase: CharArray): Boolean {
        verifyCalls += passphrase.concatToString()
        return verifiesCurrent
    }

    override fun configurePassphrase(passphrase: CharArray): PassphraseConfigureOutcome {
        storedPassphrase = passphrase.copyOf()
        return PassphraseConfigureOutcome.CONFIGURED
    }

    override fun clearPassphrase() {
        storedPassphrase = null
    }

    override fun isEncryptionEnabled(): Boolean = encryptionEnabled

    override fun updatePassphrase(
        current: CharArray,
        new: CharArray,
    ): PassphraseUpdateOutcome {
        updateCalls += updateOutcome
        if (updateOutcome == PassphraseUpdateOutcome.UPDATED) {
            storedPassphrase = new.copyOf()
        }
        return updateOutcome
    }

    override fun unlockLockoutSecondsRemaining(): Long = 0L

    override fun recordFailedUnlockAttempt(): Long = 0L

    override fun resetUnlockAttempts() = Unit
}

/**
 * Records the re-key it was driven through, on the file it was GIVEN rather than one it re-derived.
 *
 * **THIS FAKE REWRITES THE FILE, AND THAT IS THE POINT.** A re-key is a destructive in-place rewrite of the
 * database, so a fake that only recorded its arguments left every "the original bytes came back" assertion in
 * this suite trivially true: with nothing ever written, deleting the rollback's restore call passed. This
 * fake therefore writes [REKEYED_BYTES] over the main file AND its `-wal` sibling before answering, which is
 * what makes the restore observable — the bytes after a rollback can only equal the bytes from before it if
 * the restore actually ran. It was written this way after a mutation removed that call and the suite stayed
 * green.
 */
private class FakeRekey : DatabaseRekey {
    var outcome: DatabaseRekeySequence.Outcome = DatabaseRekeySequence.Outcome.REKEYED
    var failure: Throwable? = null
    val calls = mutableListOf<Call>()

    override fun rekeyEncryptedDatabase(
        databaseFile: File,
        currentPassphrase: CharArray,
        newPassphrase: CharArray,
        logTag: String,
    ): DatabaseRekeySequence.Outcome {
        calls += Call(databaseFile, currentPassphrase.concatToString(), newPassphrase.concatToString(), logTag)
        // The rewrite happens BEFORE the failure is raised, because a re-key that fails part-way has already
        // changed the file — which is exactly when the rollback has something to undo.
        databaseFile.writeBytes(REKEYED_BYTES)
        File(databaseFile.parentFile, "${databaseFile.name}-wal").writeBytes(REKEYED_WAL_BYTES)
        failure?.let { throw it }
        return outcome
    }

    data class Call(
        val databaseFile: File,
        val currentPassphrase: String,
        val newPassphrase: String,
        val logTag: String,
    )
}

/** Records the ORDERED calls it was driven through, and answers the fold with a real report. */
private class FakeSession : DatabaseSessionManager {
    private val openState = MutableStateFlow(true)

    /** What the fold reported — the whole subject of this test. */
    var flushReport: WalCheckpointRule.FoldReport = WalCheckpointRule.FoldReport(0, 0, 0)

    /** Thrown instead of reporting, so the refusal that answers an unreadable fold is reachable. */
    var flushFailure: Throwable? = null

    val events = mutableListOf<String>()
    var flushedWithPassphrase: String? = null
    var openedWithPassphrase: String? = null

    override suspend fun flushPendingChanges(passphrase: String): WalCheckpointRule.FoldReport {
        events += "flush"
        flushedWithPassphrase = passphrase
        flushFailure?.let { throw it }
        return flushReport
    }

    override suspend fun openDatabase(passphrase: String): Result<Unit> {
        events += "open"
        openedWithPassphrase = passphrase
        openState.value = true
        return Result.success(Unit)
    }

    override fun closeDatabase() {
        events += "close"
        openState.value = false
    }

    override fun requireOpenPassphrase(): String = error("no session open in this fake")

    override fun isSessionOpen(): Boolean = openState.value

    override val sessionOpenState: StateFlow<Boolean> = openState.asStateFlow()
}

/** Counts the restarts asked for; the outcome is never read, so a refusal is recorded and ignored. */
private class FakeProcessRestart : ProcessRestart {
    var calls = 0
        private set

    override fun restartProcess(): ProcessRestartOutcome {
        calls++
        return ProcessRestartOutcome.Refused(reason = ProcessRestartRefusal.NO_LAUNCH_COMMAND)
    }
}
