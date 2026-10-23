//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.security

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.port.DatabaseForgotPassphraseReset
import io.payanam.shared.database.DatabaseArtifacts
import io.payanam.shared.security.PassphraseConfigureOutcome
import io.payanam.shared.security.PassphraseUpdateOutcome
import io.payanam.shared.security.PassphraseVault
import io.payanam.shared.security.Report
import io.payanam.shared.security.VaultAvailability
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the desktop's destructive recovery path, over the real [DesktopForgottenPassphraseReset].
 *
 * WHY THIS EXISTS AND WHY IT IS OVER FILES: this is the one shared operation that deletes a user's data, and
 * the contract ([DatabaseForgotPassphraseReset]) states two promises a caller is entitled to rely on and that
 * nothing in the tree was checking — that the ORDER is artifacts-then-credential, and that the credential is
 * cleared even when some artifacts could not be deleted. Both are load-bearing: reversing the order leaves
 * the user locked out of data that is still on disk, and aborting on the first delete failure strands them
 * with a vault they never asked to keep.
 *
 * The order is asserted by OBSERVING the world at each step rather than by trusting a comment: the vault
 * fake records the file's existence at the moment `clearPassphrase` runs, and the assertions then say the
 * database was already gone. A reordering that only reordered the log lines would not survive this.
 *
 * A real [io.payanam.desktop.DesktopSecurityStore]-backed vault is NOT used here, because the property under
 * test is about the reset's sequencing, not about storage. The fake implements the whole contract so that a
 * member added to [PassphraseVault] fails to compile here rather than silently defaulting.
 */
class DesktopForgottenPassphraseResetTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var databaseFile: Path

    /** The artifact family present on disk, as the names the reset would act on. */
    private fun artifactFiles(): List<Path> = DatabaseArtifacts.filesFor(databaseFile.toFile()).map { it.toPath() }

    /**
     * Writes the whole artifact family so the deletion half of the contract has something real to delete.
     * Every artifact matters: a surviving `-wal` is how a wiped main file turns back into recovered rows.
     */
    private fun writeWholeArtifactFamily(): List<Path> =
        artifactFiles().also { files ->
            files.forEach { path ->
                Files.createDirectories(path.parent)
                Files.write(path, "artifact".toByteArray())
            }
        }

    private fun resetFor(vault: PassphraseVault) = DesktopForgottenPassphraseReset(vault, databaseFile)

    @Test
    fun `the reset deletes the whole artifact family and clears the credential`() {
        databaseFile = tempFolder.newFolder("db").toPath().resolve("payanam.db")
        val written = writeWholeArtifactFamily()
        val vault = RecordingVault()

        val report: Report = resetFor(vault).reset()

        assertThat(report).isNotNull()
        written.forEach { path -> assertThat(Files.exists(path)).isFalse() }
        assertThat(vault.clearCalls).isEqualTo(1)
        assertThat(report.artifactsDeleted).isEqualTo(written.size)
        assertThat(report.artifactsAttempted).isEqualTo(DatabaseArtifacts.filesFor(databaseFile.toFile()).size)
        assertThat(report.credentialCleared).isTrue()
    }

    @Test
    fun `the database is already gone when the credential is cleared`() {
        databaseFile = tempFolder.newFolder("db").toPath().resolve("payanam.db")
        val written = writeWholeArtifactFamily()
        // The probe lists the artifacts that still exist at the instant the credential is dropped. It reads
        // the real filesystem, so a reordered reset — credential first — reports the family still present.
        val vault = RecordingVault(probe = { written.filter { Files.exists(it) } })

        resetFor(vault).reset()

        // The order the contract fixes, asserted at the moment it matters rather than from the source text.
        assertThat(vault.databaseExistedWhenCredentialCleared).isEmpty()
    }

    @Test
    fun `a fresh install reports zero deletions and still clears the credential`() {
        databaseFile = tempFolder.newFolder("db").toPath().resolve("payanam.db")
        val vault = RecordingVault()

        val report: Report = resetFor(vault).reset()

        // Nothing to delete is a legitimate outcome, not a failure — and it must not be reported as one.
        assertThat(report.artifactsDeleted).isEqualTo(0)
        assertThat(report.credentialCleared).isTrue()
        assertThat(report.succeeded).isTrue()
    }

    @Test
    fun `an undeletable artifact does not abandon the credential or lie about success`() {
        databaseFile = tempFolder.newFolder("db").toPath().resolve("payanam.db")
        writeWholeArtifactFamily()
        // A NON-EMPTY directory is what a delete actually fails on: Files.delete on an empty directory
        // succeeds on Linux, so the first version of this test stood in an empty one, had it removed, and
        // reported 4 deletions where it expected 3 — a guard that passed for the wrong reason until the count
        // was asserted. A non-empty path raises DirectoryNotEmptyException, which is the portable stand-in
        // for a permission error, a file held open by another process, or a read-only mount.
        val undeletable = artifactFiles().first().parent.resolve("payanam.db-journal")
        Files.deleteIfExists(undeletable)
        Files.createDirectories(undeletable)
        Files.write(undeletable.resolve("still-here"), "held open".toByteArray())
        val vault = RecordingVault()

        val report: Report = resetFor(vault).reset()

        // The credential is still cleared: the user asked to start over, and a partial delete must not
        // strand them holding a vault for a database they no longer have.
        assertThat(vault.clearCalls).isEqualTo(1)
        assertThat(report.credentialCleared).isTrue()
        // The count omits the artifact that survived rather than counting it as deleted.
        assertThat(report.artifactsDeleted).isEqualTo(DatabaseArtifacts.filesFor(databaseFile.toFile()).size - 1)
        // AND THE SURVIVOR IS WHAT MAKES THIS A FAILURE, which is the half this test's name promised and did
        // not assert until the report carried the attempted count. The credential cleared perfectly here, so
        // a `succeeded` derived from that half alone reported success while a member of the user's own
        // database was still on disk — a user told their data was destroyed, and a `-wal` still readable
        // beside the wiped main file. Both halves were true; only one of them was asserted.
        assertThat(report.artifactsAttempted).isEqualTo(DatabaseArtifacts.filesFor(databaseFile.toFile()).size)
        assertThat(report.succeeded).isFalse()
        assertThat(Files.exists(undeletable)).isTrue()
    }

    @Test
    fun `a vault that cannot be written reports the failure instead of claiming a clean reset`() {
        databaseFile = tempFolder.newFolder("db").toPath().resolve("payanam.db")
        writeWholeArtifactFamily()
        val vault = RecordingVault(clearThrows = true)

        val report: Report = resetFor(vault).reset()

        // The two halves fail independently, so the report is not a roll-up: the data really did go, and the
        // credential really did not clear. `succeeded` is false, so no caller may report this as a reset.
        assertThat(report.artifactsDeleted).isEqualTo(DatabaseArtifacts.filesFor(databaseFile.toFile()).size)
        assertThat(report.credentialCleared).isFalse()
        assertThat(report.succeeded).isFalse()
        artifactFiles().forEach { path -> assertThat(Files.exists(path)).isFalse() }
    }

    @Test
    fun `one call yields exactly one complete answer and one credential clear`() {
        databaseFile = tempFolder.newFolder("db").toPath().resolve("payanam.db")
        val written = writeWholeArtifactFamily()
        val vault = RecordingVault()

        val report: Report = resetFor(vault).reset()

        // THE SHAPE THIS PORT CHANGED, asserted where the old callback shape made it unassertable. The
        // answer is a RETURN VALUE, so there is no second hand-over to count and no way to withhold one:
        // a caller that invoked a callback-based reset could be resumed twice (the unlock gate was, by a
        // `suspendCancellableCoroutine` that a double callback would resume twice) or never resumed at all,
        // holding the user on a busy flag forever on the one screen they cannot leave. Both are failures
        // this signature can no longer express.
        assertThat(report.succeeded).isTrue()
        assertThat(report.artifactsDeleted).isEqualTo(written.size)
        // And the credential is still cleared exactly once — a second clear would be a second write to the
        // user's credential store for one user request.
        assertThat(vault.clearCalls).isEqualTo(1)
    }

    /**
     * A [PassphraseVault] that records what the reset let it observe.
     *
     * [databaseExistedWhenCredentialCleared] is the whole point: it is captured INSIDE `clearPassphrase`, so
     * it answers whether the data had already been deleted at the instant the credential was dropped.
     */
    private class RecordingVault(
        private val clearThrows: Boolean = false,
        private val probe: () -> List<Path> = { emptyList() },
    ) : PassphraseVault {
        var clearCalls: Int = 0
            private set

        var databaseExistedWhenCredentialCleared: List<Path> = emptyList()
            private set

        override fun hasPassphraseConfigured(): Boolean = true

        override fun availability(): VaultAvailability = VaultAvailability.AVAILABLE

        override fun verifyPassphrase(passphrase: CharArray): Boolean = true

        override fun configurePassphrase(passphrase: CharArray): PassphraseConfigureOutcome = PassphraseConfigureOutcome.CONFIGURED

        override fun clearPassphrase() {
            databaseExistedWhenCredentialCleared = probe()
            if (clearThrows) {
                error("the credential store is unwritable")
            }
            clearCalls++
        }

        override fun isEncryptionEnabled(): Boolean = true

        override fun updatePassphrase(
            current: CharArray,
            new: CharArray,
        ): PassphraseUpdateOutcome = PassphraseUpdateOutcome.UPDATED

        override fun unlockLockoutSecondsRemaining(): Long = 0L

        override fun recordFailedUnlockAttempt(): Long = 0L

        override fun resetUnlockAttempts() = Unit
    }
}
