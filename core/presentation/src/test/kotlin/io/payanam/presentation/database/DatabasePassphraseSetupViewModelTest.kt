//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.database

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.security.EncryptionCheckpointOutcome
import io.payanam.shared.security.EncryptionStateCheckpoint
import io.payanam.shared.security.PassphraseConfigureOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The passphrase-setup gate over the four ports it was rewritten onto.
 *
 * WHY THESE ARE THE TESTS THAT MATTER: this class had no tests at all in `:app`, and the promotion changed
 * the shape of three of its four collaborators, so the risk is not "it stopped working" but "it kept
 * compiling while answering a different question". Each test below is written to FAIL against a plausible
 * wrong implementation, and the two outcome-mapping tests are written as loops over the enums' own `entries`
 * so a member added later fails the suite rather than throwing inside a `when` on a user's reset path.
 *
 * ITS OWN FAKES RATHER THAN [DatabaseInitTestFakes]: the init suite's probe answers one canned map, where
 * this suite needs counts that DIFFER across the passphrase - the whole point of the count check. Extending
 * the shared fakes would mean changing a file two other test classes depend on, so the two probes are
 * deliberately separate rather than one fake slowly growing a parameter per caller.
 *
 * NO ROBOLECTRIC, NO ANDROID IMPORTS: this module runs plain JVM tests, and the whole point of the move is
 * that the class no longer has a `Context` to stand up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabasePassphraseSetupViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(testDispatcher)

    private lateinit var databaseDirectory: File
    private lateinit var resolver: TestDatabasePathResolver
    private lateinit var probe: TestEncryptionProbe
    private lateinit var vault: TestPassphraseVault
    private lateinit var checkpoint: TestEncryptionStateCheckpoint

    /** Valid under [io.payanam.shared.security.SharedPassphrasePolicy]: 12+ chars, all four classes. */
    private val validPassphrase = "S3cure!Passphrase"

    /** The counts a healthy, plaintext database reports before AND after a conversion that loses nothing. */
    private val stableCounts =
        mapOf(
            "tasks" to 12,
            "time_entries" to 7,
            "day_journal_entries" to 3,
            "journal_notes" to 4,
            "notes" to 9,
        )

    @Before
    fun setUp() {
        databaseDirectory =
            File.createTempFile("payanam-setup-dir", "").let {
                it.delete()
                it.mkdirs()
                it
            }
        resolver = TestDatabasePathResolver(databaseDirectory)
        probe = TestEncryptionProbe()
        vault = TestPassphraseVault()
        checkpoint = TestEncryptionStateCheckpoint()
    }

    @After
    fun tearDown() {
        databaseDirectory.deleteRecursively()
    }

    private fun viewModel(scope: CoroutineScope = this.scope) =
        DatabasePassphraseSetupViewModel(
            databasePathResolver = resolver,
            encryptionProbe = probe,
            passphraseVault = vault,
            encryptionStateCheckpoint = checkpoint,
            scope = scope,
            // Every blocking hop on the test scheduler, so `advanceUntilIdle` really does settle the work. A
            // real `Dispatchers.IO` here is how a test reads state the gate has not reached yet.
            ioDispatcher = testDispatcher,
        )

    private fun plantDatabase(sizeBytes: Int = 4096) {
        resolver.databaseFile.writeBytes(ByteArray(sizeBytes) { 1 })
    }

    /** The names left in the database directory - the evidence that a backup copy was or was not left behind. */
    private fun leftoverNames(): List<String> =
        (databaseDirectory.list() ?: emptyArray())
            .filterNot { it == resolver.databaseFile.name }
            .toList()

    // ---------------------------------------------------------------- the rejection path

    /**
     * THE MOST IMPORTANT ASSERTION IN THIS FILE: a passphrase the policy refuses must never reach the vault.
     *
     * Fails against a validation that is checked but falls through anyway, and against one that checks
     * `confirmPassphrase` instead of `passphrase`. Either would hand a rejected passphrase to the platform's
     * store - the one thing [io.payanam.shared.security.PassphraseVault.configurePassphrase] says it does not
     * expect, and the one thing a shared rewrite is most likely to get wrong by reordering the two gates.
     */
    @Test
    fun `an invalid passphrase never reaches the vault`() =
        runTest(testDispatcher) {
            plantDatabase()
            val vm = viewModel()
            advanceUntilIdle()

            vm.configurePassphrase("Ab1!", "Ab1!") { error("must not succeed") }
            advanceUntilIdle()

            assertThat(vault.configureCalls).isEqualTo(0)
            assertThat(vm.uiState.value.errorReasonCode).isEqualTo("min_length")
            // The refusal must happen before the re-encryption too, not merely before the vault.
            assertThat(probe.ensureEncryptedCalls).isEqualTo(0)
        }

    /**
     * A mismatch is refused before the vault as well - the second gate, and the one the policy is not
     * involved in, so it is a separate assertion rather than another arm of the one above.
     */
    @Test
    fun `a mismatched confirmation never reaches the vault`() =
        runTest(testDispatcher) {
            plantDatabase()
            val vm = viewModel()
            advanceUntilIdle()

            vm.configurePassphrase(validPassphrase, "${validPassphrase}x") { error("must not succeed") }
            advanceUntilIdle()

            assertThat(vault.configureCalls).isEqualTo(0)
            assertThat(vm.uiState.value.errorReasonCode).isEqualTo("mismatch")
            assertThat(probe.ensureEncryptedCalls).isEqualTo(0)
        }

    // ---------------------------------------------------------------- the configure-outcome mapping

    /**
     * EVERY value of [PassphraseConfigureOutcome] maps to a truthful code, and exactly one maps to success.
     *
     * Looped over the enum's own `entries` rather than written as three named assertions: a fourth member
     * fails this test by being unhandled, which is the failure a caller must not meet at runtime.
     */
    @Test
    fun `every configure outcome maps to a distinct truthful reason code`() {
        val codes = PassphraseConfigureOutcome.entries.map { it to reasonCodeForConfigureOutcome(it) }

        assertThat(codes)
            .containsExactly(
                PassphraseConfigureOutcome.CONFIGURED to null,
                PassphraseConfigureOutcome.REJECTED_BY_POLICY to "rejected_by_policy",
                PassphraseConfigureOutcome.STORE_UNAVAILABLE to "persist_failed",
            ).inOrder()
    }

    /**
     * An unwritable store reports `persist_failed` and does NOT call the success callback.
     *
     * This is the arm the old `Boolean` could express, so it is the regression guard for the port swap. It
     * fails against an implementation that logs the outcome and opens the gate anyway.
     */
    @Test
    fun `an unwritable store reports persist_failed and does not open the gate`() =
        runTest(testDispatcher) {
            plantDatabase()
            probe.countsByPassphrase[null] = stableCounts
            probe.countsByPassphrase[validPassphrase] = stableCounts
            vault.configureOutcome = PassphraseConfigureOutcome.STORE_UNAVAILABLE
            var succeeded = false
            val vm = viewModel()
            advanceUntilIdle()

            vm.configurePassphrase(validPassphrase, validPassphrase) { succeeded = true }
            advanceUntilIdle()

            assertThat(vm.uiState.value.errorReasonCode).isEqualTo("persist_failed")
            assertThat(succeeded).isFalse()
        }

    /**
     * A POLICY rejection at the vault is not reported as "could not save".
     *
     * The user can fix a rejected passphrase and cannot fix an unwritable store, so collapsing the two tells
     * one of them to retry something that will never work. Unreachable through both platform vaults today -
     * this class validates with the same policy first - and asserted anyway, because that is a property of two
     * implementations rather than of the contract.
     */
    @Test
    fun `a policy rejection is its own reason code, not persist_failed`() =
        runTest(testDispatcher) {
            plantDatabase()
            probe.countsByPassphrase[null] = stableCounts
            probe.countsByPassphrase[validPassphrase] = stableCounts
            vault.configureOutcome = PassphraseConfigureOutcome.REJECTED_BY_POLICY
            var succeeded = false
            val vm = viewModel()
            advanceUntilIdle()

            vm.configurePassphrase(validPassphrase, validPassphrase) { succeeded = true }
            advanceUntilIdle()

            assertThat(vm.uiState.value.errorReasonCode).isEqualTo("rejected_by_policy")
            assertThat(vm.uiState.value.errorReasonCode).isNotEqualTo("persist_failed")
            assertThat(succeeded).isFalse()
        }

    // ---------------------------------------------------------------- the NOT_SUPPORTED wipe

    /**
     * EVERY value of [EncryptionCheckpointOutcome] is handled, and exactly the two the contract calls
     * settled are `null`.
     */
    @Test
    fun `every wipe outcome maps to a reason code, and only the successful two to null`() {
        val codes = EncryptionCheckpointOutcome.entries.map { it to reasonCodeForWipeOutcome(it) }

        assertThat(codes)
            .containsExactly(
                EncryptionCheckpointOutcome.CONFIRMED to null,
                EncryptionCheckpointOutcome.NOT_SUPPORTED to null,
                EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED to "reset_failed",
                EncryptionCheckpointOutcome.STORE_UNAVAILABLE to "reset_failed",
            ).inOrder()
    }

    /**
     * THE TRAP, ASSERTED END TO END: a desktop-shaped checkpoint that keeps no encryption state answers
     * [EncryptionCheckpointOutcome.NOT_SUPPORTED], and the reset must still SUCCEED.
     *
     * Fails against the obvious wrong implementations: a `when` treating everything but [CONFIRMED] as a
     * failure, and a `!= CONFIRMED` boolean reduction with the same result. Both report a broken reset on a
     * platform that is not broken, and both block a passphrase the desktop can in fact store.
     */
    @Test
    fun `a NOT_SUPPORTED wipe is success, so the reset completes and the gate opens`() =
        runTest(testDispatcher) {
            plantDatabase()
            checkpoint.outcome = EncryptionCheckpointOutcome.NOT_SUPPORTED
            var succeeded = false
            val vm = viewModel()
            advanceUntilIdle()

            vm.resetLocalDataAndConfigurePassphrase(validPassphrase, validPassphrase) { succeeded = true }
            advanceUntilIdle()

            assertThat(succeeded).isTrue()
            assertThat(vault.configureCalls).isEqualTo(1)
            assertThat(vm.uiState.value.errorReasonCode).isNull()
            assertThat(vm.uiState.value.isSaving).isFalse()
            // The destructive half really did run: the artifacts are gone, not merely reported gone.
            assertThat(resolver.databaseFile.exists()).isFalse()
        }

    /**
     * An UNCONFIRMED wipe is NOT success, even though the platform says it probably happened.
     *
     * The screen is about to delete the user's database and configure a new passphrase over it; proceeding on
     * encryption state that may still be there is the failure the contract's own KDoc warns about. Fails
     * against a `when` that lumps `NOT_SUPPORTED` and `PERFORMED_UNCONFIRMED` together as "not confirmed but
     * probably fine".
     */
    @Test
    fun `an unconfirmed wipe stops the reset before the vault is asked`() =
        runTest(testDispatcher) {
            plantDatabase()
            checkpoint.outcome = EncryptionCheckpointOutcome.PERFORMED_UNCONFIRMED
            var succeeded = false
            val vm = viewModel()
            advanceUntilIdle()

            vm.resetLocalDataAndConfigurePassphrase(validPassphrase, validPassphrase) { succeeded = true }
            advanceUntilIdle()

            assertThat(vm.uiState.value.errorReasonCode).isEqualTo("reset_failed")
            assertThat(vault.configureCalls).isEqualTo(0)
            assertThat(succeeded).isFalse()
        }

    // ---------------------------------------------------------------- the migration and its rollback

    /**
     * A platform that REFUSES to re-encrypt - which is what the desktop does - surfaces as a failed
     * migration, never as a success over a plaintext file.
     *
     * This is the desktop arm: `DesktopDatabaseEncryptionProbe.ensureEncrypted` throws because its driver
     * refuses a plaintext rekey. A caller that caught it and reported success would configure a passphrase
     * over a database it never encrypted, and the count comparison would pass, because both sides counted the
     * same still-plaintext file.
     */
    @Test
    fun `a platform that refuses to re-encrypt reports a migration failure and never reaches the vault`() =
        runTest(testDispatcher) {
            plantDatabase()
            probe.countsByPassphrase[null] = stableCounts
            probe.ensureEncryptedFailure =
                UnsupportedOperationException("Re-encryption of a PLAINTEXT database is not supported")
            var succeeded = false
            val vm = viewModel()
            advanceUntilIdle()

            vm.configurePassphrase(validPassphrase, validPassphrase) { succeeded = true }
            advanceUntilIdle()

            assertThat(vm.uiState.value.errorReasonCode).isEqualTo("migration_failed")
            assertThat(vault.configureCalls).isEqualTo(0)
            assertThat(succeeded).isFalse()
            // The rollback ran, so the timestamped copy is gone rather than littering the directory - and
            // the original came back intact, which is what "rolled back" means.
            assertThat(resolver.databaseFile.exists()).isTrue()
            assertThat(leftoverNames()).isEmpty()
        }

    /**
     * A count mismatch across the re-encryption rolls the artifacts back and reports the failure.
     *
     * The count check is the whole safety property of this screen, and the part a "re-encryption reported
     * success so carry on" implementation drops. The fake answers with different counts before and after,
     * which is exactly what a conversion that lost rows looks like.
     */
    @Test
    fun `a row-count mismatch rolls the database back and reports the failure`() =
        runTest(testDispatcher) {
            plantDatabase()
            probe.countsByPassphrase[null] = stableCounts
            probe.countsByPassphrase[validPassphrase] = stableCounts + mapOf("tasks" to 11)
            var succeeded = false
            val vm = viewModel()
            advanceUntilIdle()

            vm.configurePassphrase(validPassphrase, validPassphrase) { succeeded = true }
            advanceUntilIdle()

            assertThat(vm.uiState.value.errorReasonCode).isEqualTo("migration_failed")
            assertThat(vault.configureCalls).isEqualTo(0)
            assertThat(succeeded).isFalse()
            assertThat(resolver.databaseFile.exists()).isTrue()
            assertThat(leftoverNames()).isEmpty()
        }

    /**
     * A migration that preserves every count completes and asks the vault exactly once.
     *
     * The positive control for the two tests above: without it, an implementation that always failed would
     * pass both of them.
     */
    @Test
    fun `a count-preserving migration reaches the vault and opens the gate`() =
        runTest(testDispatcher) {
            plantDatabase()
            probe.countsByPassphrase[null] = stableCounts
            probe.countsByPassphrase[validPassphrase] = stableCounts
            var succeeded = false
            val vm = viewModel()
            advanceUntilIdle()

            vm.configurePassphrase(validPassphrase, validPassphrase) { succeeded = true }
            advanceUntilIdle()

            assertThat(succeeded).isTrue()
            assertThat(vault.configureCalls).isEqualTo(1)
            assertThat(vm.uiState.value.errorReasonCode).isNull()
            assertThat(vm.uiState.value.isSaving).isFalse()
            assertThat(probe.ensureEncryptedCalls).isEqualTo(1)
        }

    /**
     * The re-encryption is asked for BY NAME through the probe, with the passphrase the user typed - the one
     * substitution a port swap can quietly get wrong by passing `null`, which would re-read the file as
     * plaintext and "succeed" without converting anything.
     */
    @Test
    fun `the re-encryption is asked of the probe with the typed passphrase`() =
        runTest(testDispatcher) {
            plantDatabase()
            probe.countsByPassphrase[null] = stableCounts
            probe.countsByPassphrase[validPassphrase] = stableCounts
            val vm = viewModel()
            advanceUntilIdle()

            vm.configurePassphrase(validPassphrase, validPassphrase) { }
            advanceUntilIdle()

            assertThat(probe.lastEnsureEncryptedFile).isEqualTo(resolver.databaseFile)
            assertThat(probe.lastEnsureEncryptedPassphrase).isEqualTo(validPassphrase)
            // And the counts are read BOTH ways - before as plaintext, after with the passphrase. The
            // ViewModel's own `init` read is a third, earlier plaintext read, so the assertion is that the
            // LAST TWO are the migration's pair rather than that there are exactly two reads in total.
            assertThat(probe.readPassphrasesInOrder.takeLast(2)).containsExactly(null, validPassphrase).inOrder()
        }

    // ---------------------------------------------------------------- the summary

    /**
     * The summary is read from the file and the probe, and the journal count is the SUM of both journal
     * tables - the one piece of arithmetic in this class, and the one a shared rewrite could drop.
     */
    @Test
    fun `the summary reports the existing database's size and per-table counts`() =
        runTest(testDispatcher) {
            plantDatabase(sizeBytes = 8192)
            probe.countsByPassphrase[null] = stableCounts
            val vm = viewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertThat(state.hasExistingLocalDatabase).isTrue()
            assertThat(state.databaseSizeKb).isEqualTo(8L)
            assertThat(state.taskCount).isEqualTo(12)
            assertThat(state.timeEntryCount).isEqualTo(7)
            // 3 day_journal_entries + 4 journal_notes.
            assertThat(state.journalCount).isEqualTo(7)
            assertThat(state.noteCount).isEqualTo(9)
            assertThat(state.storageModeLabelKey).isEqualTo("plaintext")
            assertThat(state.databaseLastModifiedMs).isGreaterThan(0L)
        }

    /**
     * A missing database is a fresh install, not a failed read: zero counts, and the probe is never asked
     * about a file that is not there.
     */
    @Test
    fun `a missing database reports a clean plaintext state`() =
        runTest(testDispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            assertThat(state.hasExistingLocalDatabase).isFalse()
            assertThat(state.taskCount).isEqualTo(0)
            assertThat(state.journalCount).isEqualTo(0)
            assertThat(state.storageModeLabelKey).isEqualTo("plaintext")
            assertThat(probe.readTableCountsCalls).isEqualTo(0)
        }

    /**
     * A database that cannot be counted must not take the screen down, and must not be reported as absent.
     *
     * Two things are being asserted because the naive port swap breaks both. First, the summary runs on the
     * ViewModel's own scope, so an exception escaping it CANCELS that scope - every later action, including
     * the passphrase submit this screen exists for, would silently do nothing. Second, and more quietly: the
     * file exists, so answering `hasExistingLocalDatabase = false` would tell the user their data is gone.
     *
     * This test is the one that caught a real defect in the promoted class rather than a wrong expectation:
     * the `:app` version read the counts on the caller's thread, where a throw was a crash, so the hazard
     * only appeared once the read moved onto a scope.
     */
    @Test
    fun `a database that cannot be counted is reported as present, and the gate still works`() =
        runTest(testDispatcher) {
            plantDatabase()
            probe.countsFailure = IOException("unreadable")
            val vm = viewModel()
            advanceUntilIdle()

            // Present, because the file is there - not "no database", and not zeroed counts.
            assertThat(vm.uiState.value.hasExistingLocalDatabase).isTrue()
            assertThat(vm.uiState.value.taskCount).isEqualTo(0)

            // And the scope survived, so the submit this screen exists for still runs and still refuses,
            // because a failure inside the migration is caught and reported rather than escaping.
            var succeeded = false
            vm.configurePassphrase(validPassphrase, validPassphrase) { succeeded = true }
            advanceUntilIdle()

            assertThat(succeeded).isFalse()
            assertThat(vm.uiState.value.errorReasonCode).isEqualTo("migration_failed")
        }

    /**
     * The database path comes from the RESOLVER, so the summary is of the file the platform actually opens.
     *
     * Without this, a rewrite that kept a hardcoded `payanam.db` in a guessed directory rather than asking
     * [io.payanam.domain.port.DatabasePathResolver] would pass every other test in this file, because the
     * fake resolver hands back the same name a hardcoded one would.
     */
    @Test
    fun `the database path is asked of the resolver, not assumed`() =
        runTest(testDispatcher) {
            val oddlyNamed = File(databaseDirectory, "renamed-by-platform.db")
            oddlyNamed.writeBytes(ByteArray(2048))
            val oddResolver = TestDatabasePathResolver(databaseDirectory, fileName = "renamed-by-platform.db")
            probe.countsByPassphrase[null] = mapOf("tasks" to 1)
            val vm =
                DatabasePassphraseSetupViewModel(
                    databasePathResolver = oddResolver,
                    encryptionProbe = probe,
                    passphraseVault = vault,
                    encryptionStateCheckpoint = checkpoint,
                    scope = scope,
                    ioDispatcher = testDispatcher,
                )
            advanceUntilIdle()

            assertThat(vm.uiState.value.hasExistingLocalDatabase).isTrue()
            assertThat(probe.lastCountsFile).isEqualTo(oddlyNamed)
        }

    // ---------------------------------------------------------------- lifecycle and the factory

    /**
     * The state is published on the scope, not synchronously from `init` - so a screen observing the flow
     * has a deterministic first value, and a test that idles the scope sees the summary land.
     */
    @Test
    fun `the summary is published on the scope`() =
        runTest(testDispatcher) {
            plantDatabase()
            probe.countsByPassphrase[null] = stableCounts
            val vm = viewModel()
            runCurrent()

            assertThat(vm.uiState.value.taskCount).isEqualTo(12)
        }

    /**
     * `dispose` releases the scope, which is the whole point of the method: it replaces the
     * `ViewModel.onCleared` hook this class no longer has, and a platform that forgets to call it leaks a
     * scope for the life of the process. Asserted by showing the scope no longer runs work afterwards.
     */
    @Test
    fun `dispose releases the scope`() =
        runTest(testDispatcher) {
            plantDatabase()
            val ownScope = CoroutineScope(testDispatcher)
            val vm = viewModel(scope = ownScope)
            advanceUntilIdle()
            val countsBefore = probe.readTableCountsCalls

            vm.dispose()
            advanceUntilIdle()

            assertThat(probe.readTableCountsCalls).isEqualTo(countsBefore)
        }

    /**
     * The factory builds the same object the constructor does, so a platform using it cannot get a
     * differently-wired ViewModel than a test does.
     */
    @Test
    fun `the factory wires the same collaborators the constructor does`() {
        val vm =
            passphraseSetupViewModel(
                databasePathResolver = resolver,
                encryptionProbe = probe,
                passphraseVault = vault,
                encryptionStateCheckpoint = checkpoint,
                scope = scope,
            )

        assertThat(vm.uiState.value).isEqualTo(DatabasePassphraseSetupUiState())
        vm.dispose()
    }

    /**
     * `clearError` is the only way the error banner goes away, and it touches nothing else in state -
     * asserted against a plausible implementation that resets the whole state object and so wipes the
     * database summary the screen is showing.
     */
    @Test
    fun `clearError clears only the error`() =
        runTest(testDispatcher) {
            plantDatabase()
            probe.countsByPassphrase[null] = stableCounts
            val vm = viewModel()
            advanceUntilIdle()
            vm.configurePassphrase("Ab1!", "Ab1!") { error("must not succeed") }
            advanceUntilIdle()
            val before = vm.uiState.value

            vm.clearError()

            assertThat(vm.uiState.value.errorReasonCode).isNull()
            assertThat(vm.uiState.value.hasExistingLocalDatabase).isEqualTo(before.hasExistingLocalDatabase)
            assertThat(vm.uiState.value.databaseSizeKb).isEqualTo(before.databaseSizeKb)
            assertThat(vm.uiState.value.taskCount).isEqualTo(before.taskCount)
        }

    // ---------------------------------------------------------------- the artifact backup object

    /**
     * The backup is a SIBLING of the original, so the artifact family stays together - a `-wal` copied
     * somewhere else is an orphan the restore cannot reason about.
     */
    @Test
    fun `the backup copy sits beside the original it protects`() {
        val original = File(databaseDirectory, "payanam.db")
        original.writeBytes(byteArrayOf(1, 2, 3))

        val mappings = DatabaseArtifactBackupSupport.backup(listOf(original))

        assertThat(mappings).hasSize(1)
        val (source, backup) = mappings.single()
        assertThat(source).isEqualTo(original)
        assertThat(backup.parentFile).isEqualTo(databaseDirectory)
        assertThat(backup.name).startsWith("payanam.db.before_encrypt_")
        assertThat(backup.name).endsWith(".bak")
        assertThat(backup.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    /**
     * Restore puts the bytes back and REPORTS HOW MANY, because "rolled back" and "rolled back except for
     * the file the migration destroyed" are indistinguishable in a log line nobody reads during a failed
     * setup. A backup that is gone restores nothing and must be counted as nothing.
     */
    @Test
    fun `restore reports how many files came back`() {
        val original = File(databaseDirectory, "payanam.db")
        original.writeBytes(byteArrayOf(1, 2, 3))
        val mappings = DatabaseArtifactBackupSupport.backup(listOf(original))
        original.writeBytes(byteArrayOf(9))
        mappings.forEach { (_, backup) -> backup.delete() }

        val restored = DatabaseArtifactBackupSupport.restore(mappings)

        assertThat(restored).isEqualTo(0)
    }

    /**
     * Cleanup removes the backups on both the success and the failure path, and leaves the originals alone.
     */
    @Test
    fun `cleanup removes the backups and keeps the originals`() {
        val original = File(databaseDirectory, "payanam.db")
        original.writeBytes(byteArrayOf(1, 2, 3))
        val mappings = DatabaseArtifactBackupSupport.backup(listOf(original))

        DatabaseArtifactBackupSupport.cleanup(mappings)

        assertThat(mappings.map { it.second.exists() }).containsExactly(false)
        assertThat(original.exists()).isTrue()
    }
}

/**
 * A resolver over a real directory, as the init suite's is: this class does real [File] arithmetic on
 * whatever path it returns, so a path into a real temp directory is the honest fake.
 */
private class TestDatabasePathResolver(
    private val directory: File,
    private val fileName: String = "payanam.db",
) : io.payanam.domain.port.DatabasePathResolver {
    override fun databaseDirectory(): String = directory.absolutePath

    override fun databaseFilePath(): String = File(directory, fileName).absolutePath

    val databaseFile: File get() = File(databaseFilePath())
}

/**
 * A probe whose counts are keyed by the passphrase they were read with, so a test can make the counts
 * DIFFER across the re-encryption - which is the only way to reach the mismatch arm honestly.
 */
private class TestEncryptionProbe : DatabaseEncryptionProbe {
    var countsByPassphrase: MutableMap<String?, Map<String, Int>> = mutableMapOf()

    /** Thrown by [ensureEncrypted] - the desktop's refusal, and Android's "did not complete" check. */
    var ensureEncryptedFailure: Throwable? = null

    /** Thrown by [readTableCounts] - an unreadable file. */
    var countsFailure: Throwable? = null

    var readTableCountsCalls = 0
    var lastCountsFile: File? = null
    var ensureEncryptedCalls = 0
    var lastEnsureEncryptedFile: File? = null
    var lastEnsureEncryptedPassphrase: String? = "unset"

    /** Every passphrase [readTableCounts] was asked with, in order - the evidence for "read both ways". */
    val readPassphrasesInOrder = mutableListOf<String?>()

    override fun isDetectablyEncrypted(
        databaseFile: File,
        logTag: String,
    ): Boolean = false

    override fun canOpen(
        databaseFile: File,
        passphrase: String,
        logTag: String,
    ): Boolean = true

    override fun ensureEncrypted(
        databaseFile: File,
        passphrase: String,
        logTag: String,
    ) {
        ensureEncryptedCalls++
        lastEnsureEncryptedFile = databaseFile
        lastEnsureEncryptedPassphrase = passphrase
        ensureEncryptedFailure?.let { throw it }
    }

    override fun readTableCounts(
        databaseFile: File,
        passphrase: String?,
        tableNames: List<String>,
    ): Map<String, Int> {
        readTableCountsCalls++
        lastCountsFile = databaseFile
        readPassphrasesInOrder += passphrase
        countsFailure?.let { throw it }
        return countsByPassphrase[passphrase] ?: tableNames.associateWith { 0 }
    }
}

/** A vault that records what it was configured with and can answer any of the three outcomes. */
private class TestPassphraseVault : io.payanam.shared.security.PassphraseVault {
    var configureOutcome: PassphraseConfigureOutcome = PassphraseConfigureOutcome.CONFIGURED
    var configureCalls = 0
    var configuredWith: String? = null

    override fun hasPassphraseConfigured(): Boolean = configureCalls > 0

    override fun availability(): io.payanam.shared.security.VaultAvailability = io.payanam.shared.security.VaultAvailability.AVAILABLE

    override fun verifyPassphrase(passphrase: CharArray): Boolean = true

    override fun configurePassphrase(passphrase: CharArray): PassphraseConfigureOutcome {
        configureCalls++
        configuredWith = passphrase.concatToString()
        return configureOutcome
    }

    override fun clearPassphrase() = Unit

    override fun isEncryptionEnabled(): Boolean = false

    override fun updatePassphrase(
        current: CharArray,
        new: CharArray,
    ): io.payanam.shared.security.PassphraseUpdateOutcome = io.payanam.shared.security.PassphraseUpdateOutcome.UPDATED

    override fun unlockLockoutSecondsRemaining(): Long = 0L

    override fun recordFailedUnlockAttempt(): Long = 0L

    override fun resetUnlockAttempts() = Unit
}

/** A checkpoint that answers one outcome for every member, which is what the desktop's does. */
private class TestEncryptionStateCheckpoint : EncryptionStateCheckpoint {
    var outcome: EncryptionCheckpointOutcome = EncryptionCheckpointOutcome.CONFIRMED
    val calls = mutableListOf<String>()

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
