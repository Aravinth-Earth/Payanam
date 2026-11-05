//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.desktop.DesktopSecurityStore
import io.payanam.desktop.security.DesktopPassphraseVault
import io.payanam.domain.port.ProcessRestart
import io.payanam.domain.port.ProcessRestartOutcome
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.PassphraseVault
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the desktop hands the shared passphrase-change screen: the ONE vault and the ONE session controller.
 *
 * ## WHY THIS TEST EXISTS RATHER THAN A DESTRUCTION-CASE ONE
 *
 * This screen does not READ the security state, it MUTATES it - it writes the new passphrase, rekeys the
 * encrypted file and then asks the process to restart. That makes a second set of collaborators worse here
 * than on the other two gates: a vault built inside this screen would persist the new credential into a
 * second security store that the unlock gate never reads, so the next unlock would ask for a passphrase
 * nothing had stored, and that second store would also carry its own failure counter, so a lockout counted
 * by one gate would not apply to the other. A second session controller would register a second process
 * shutdown hook and leave the shell's own session handle dropped.
 *
 * None of that is compiler-enforced, and none of it fails a test that only walks the happy path. So what is
 * asserted is IDENTITY - the collaborators the factory is given are the objects this test itself
 * constructed, not merely objects of the right type, because two instances of the same class would satisfy
 * a type check while holding two independent counters.
 *
 * ## WHAT IT DELIBERATELY DOES NOT CLAIM
 *
 * It does not rekey a real database and it does not spawn a process. The behaviour of the change itself -
 * the policy check, the flush gate, the rollback, the restart - belongs to the SHARED ViewModel and is
 * proven by `DatabasePassphraseChangeViewModelTest` in `:core:presentation` against fake ports. What is
 * untested here and cannot be from here is whether the desktop's own ports do their jobs: that is
 * `DesktopPassphraseVaultTest` and `DesktopProcessRestartPortTest`, separately.
 */
class DesktopPassphraseChangeViewModelTest {
    @Test
    fun `the collaborators the factory hands the shared ViewModel are the ONE vault and session controller`() {
        val harness = harness()

        val viewModel =
            databasePassphraseChangeViewModel(
                collaborators =
                    DesktopPassphraseChangeCollaborators(
                        vault = harness.vault,
                        sessionController = harness.sessionController,
                    ),
                processRestart = harness.processRestart,
            )

        assertThat(harness.theCollaboratorsAreTheOnesItWasBuiltFrom()).isTrue()
        // The screen's own state starts empty, which is the real claim here: a destination that rendered a
        // permanently-false "not saving / no error" state would look functional and be inert, so what is
        // pinned is that the factory produces a ViewModel whose state the screen can actually move.
        assertThat(viewModel.uiState.value.isSaving).isFalse()
        assertThat(viewModel.uiState.value.errorReasonCode).isNull()
        assertThat(viewModel.uiState.value.isSuccess).isFalse()
        viewModel.dispose()
    }

    @Test
    fun `a mismatch is refused before any port is touched, so no restart is attempted`() {
        val harness = harness()

        val viewModel =
            databasePassphraseChangeViewModel(
                collaborators =
                    DesktopPassphraseChangeCollaborators(
                        vault = harness.vault,
                        sessionController = harness.sessionController,
                    ),
                processRestart = harness.processRestart,
            )

        // Valid new passphrase, but the confirmation does not match it. The shared ViewModel refuses this
        // before it launches anything, so this asserts the desktop's wiring did not become a path that can
        // reach a rekey with a mismatched pair.
        viewModel.submit(
            currentPassphrase = "current",
            newPassphrase = "NewPassphrase123!",
            confirmPassphrase = "DifferentPassphrase123!",
        )

        assertThat(viewModel.uiState.value.errorReasonCode).isEqualTo("mismatch")
        assertThat(harness.processRestart.restartRequests).isEqualTo(0)
        viewModel.dispose()
    }

    @Test
    fun `a new passphrase that fails the shared policy is refused and never reaches the vault`() {
        val harness = harness()

        val viewModel =
            databasePassphraseChangeViewModel(
                collaborators =
                    DesktopPassphraseChangeCollaborators(
                        vault = harness.vault,
                        sessionController = harness.sessionController,
                    ),
                processRestart = harness.processRestart,
            )

        // Too short for `SharedPassphrasePolicy`, and it MATCHES its confirmation, so the only reason this
        // can be refused is the policy check. That is the point: it isolates the policy gate from the
        // mismatch gate above, so neither assertion is satisfied by the other branch.
        viewModel.submit(
            currentPassphrase = "current",
            newPassphrase = "short",
            confirmPassphrase = "short",
        )

        assertThat(viewModel.uiState.value.errorReasonCode).isNotEqualTo("mismatch")
        assertThat(viewModel.uiState.value.errorReasonCode).isNotNull()
        assertThat(harness.processRestart.restartRequests).isEqualTo(0)
        viewModel.dispose()
    }

/**
     * The SHELL builds these collaborators over its own instances, and that is the half an identity check
     * on the factory cannot see.
     *
     * Why this case exists: the factory tests above build their own collaborators and assert the factory
     * hands those same objects to the shared ViewModel. That is real, but it went green when `DesktopApp.kt`
     * was mutated to pass a SECOND vault - a fresh `DesktopPassphraseVault` over a fresh
     * `DesktopSecurityStore` - because the mutation never touched the factory. A test that cannot notice the
     * defect it documents is worse than no test, so the assertion belongs where the decision is actually
     * made: the shell's own call site.
     *
     * It reads `DesktopApp.kt` as source, the way `DesktopNavHostParityTest` already does for the same
     * reason. Reading it rather than mounting the shell is what keeps the assertion pointed at the wiring
     * decision instead of at the screen: the shell's constructor needs the whole persisted model, which is
     * exactly what a small unit test should not have to build.
     *
     * The negative half is the one that carries the weight. A positive check for `stores.passphraseVault`
     * would still pass if the line were present but commented out or overridden a few lines later, so what
     * is pinned is that the block contains NO constructor call for either collaborator: the shell can only
     * name its own instances there, because constructing one is the defect.
     */
    @Test
    fun `the shell hands this screen its own vault and session controller rather than second ones`() {
        val source = File("..", "desktop/src/main/kotlin/io/payanam/desktop/DesktopApp.kt")

        val block = passphraseChangeCollaboratorsBlock(source.readText())

        // The block exists and is the passphrase-change one: an absent block would make every assertion
        // below vacuously true, which is the failure mode this file's other case documents.
        assertThat(block).isNotEmpty()
        // Named, so the shell's own instances are what reach the screen.
        assertThat(block).contains("vault = stores.passphraseVault")
        assertThat(block).contains("sessionController = databaseSessionController")
        // And not constructed: this is the arm a positive check alone would miss.
        assertThat(block).doesNotContain("DesktopPassphraseVault(")
        assertThat(block).doesNotContain("DesktopSecurityStore(")
        assertThat(block).doesNotContain("DesktopDatabaseSessionController(")
    }

    /**
     * The `DesktopPassphraseChangeCollaborators(...)` block in the shell, as source.
     *
     * Read from the collaborator CONSTRUCTOR up to the parenthesis that closes it, tracking
     * nesting so the collaborators' own constructor arguments do not end the scan early. Deliberately
     * simple rather than a Kotlin parser: it looks for one name and one block, and the vacuity guard in
     * the case above is what keeps a stale pattern from passing quietly.
     */
    private fun passphraseChangeCollaboratorsBlock(source: String): String {
        // The CONSTRUCTOR name, not the argument name: `passphraseChangeCollaborators =` also matches the
        // `remember(...)` key list above the assignment, and scanning from there reads the remembered KEYS
        // rather than the block - which fails for a reason that has nothing to do with the wiring.
        val start = source.indexOf("DesktopPassphraseChangeCollaborators(")
        assertThat(start).isGreaterThan(-1)
        val open = source.indexOf("(", start)
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '(' -> {
                    depth += 1
                }

                ')' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index += 1
        }
        throw AssertionError("unbalanced parentheses from the passphraseChangeCollaborators block")
    }

    private fun harness(): Harness {
        val directory: Path = Files.createTempDirectory("payanam-change-collaborators")
        val store = DesktopSecurityStore(directory)
        val vault = DesktopPassphraseVault(store)
        val databaseFile = directory.resolve("payanam.db")
        // The real controller over a directory that holds no database, so this test never opens a file.
        // Its `open` is injected for the same reason `DesktopPassphraseUnlockViewModelTest` injects it: the
        // controller's contract is what is under test elsewhere, and a real driver would make this a
        // database test with a different name.
        val sessionController =
            DesktopDatabaseSessionController(
                databaseFilePath = { databaseFile.toAbsolutePath().toString() },
                open = { _, _ ->
                    DatabaseOpenResult.Refused(DatabaseBootIssue(type = DatabaseBootIssueType.OPEN_FAILED))
                },
                logger = AppLogger.current,
            )
        val processRestart = RecordingProcessRestart()
        val collaborators =
            DesktopPassphraseChangeCollaborators(
                vault = vault,
                sessionController = sessionController,
            )
        return Harness(
            vault = vault,
            sessionController = sessionController,
            processRestart = processRestart,
            collaborators = collaborators,
        )
    }

    private class Harness(
        val vault: PassphraseVault,
        val sessionController: DesktopDatabaseSessionController,
        val processRestart: RecordingProcessRestart,
        val collaborators: DesktopPassphraseChangeCollaborators,
    ) {
        /**
         * The identity assertion, phrased as a helper so a failure names the collaborator rather than a line.
         *
         * `===` rather than Truth's `isSameInstanceAs` because this method returns the combined boolean the
         * assertion above consumes; Truth reports which side differed, but only on the object it is given.
         */
        fun theCollaboratorsAreTheOnesItWasBuiltFrom(): Boolean =
            collaborators.vault === vault && collaborators.sessionController === sessionController
    }

    /** A restart port that records the request and does not spawn anything. */
    private class RecordingProcessRestart : ProcessRestart {
        var restartRequests: Int = 0
            private set

        // `Launched` is the honest answer for a fake that was asked and did nothing harmful: the assertion
        // that matters is the COUNT below, so a refusal here would only make the fake look like it tried.
        override fun restartProcess(): ProcessRestartOutcome {
            restartRequests += 1
            return ProcessRestartOutcome.Launched
        }
    }
}
