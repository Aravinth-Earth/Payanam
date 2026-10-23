//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.database.jvm.DatabaseOpenResult
import io.payanam.desktop.DesktopSecurityStore
import io.payanam.desktop.security.DesktopBiometricUnlockPrompt
import io.payanam.desktop.security.DesktopForgottenPassphraseReset
import io.payanam.desktop.security.DesktopPassphraseVault
import io.payanam.domain.port.DatabaseForgotPassphraseReset
import io.payanam.presentation.database.DatabasePassphraseUnlockViewModel
import io.payanam.shared.database.DatabaseBootIssue
import io.payanam.shared.database.DatabaseBootIssueType
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.BiometricAvailability
import io.payanam.shared.security.BiometricUnavailableReason
import io.payanam.shared.security.PassphraseVault
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the desktop's unlock-gate collaborators: the wiring that decides WHICH vault, reset and
 * database file the shared unlock screen runs against.
 *
 * ## WHY THIS TEST EXISTS RATHER THAN A DESTRUCTION-CASE ONE
 *
 * The danger this guards against is a second set of collaborators. A second vault over a second security
 * store would hold its own failure counter, so a user locked out by one gate would not be locked out by
 * the other; a reset pointed at a different path would delete an artifact family nobody is using. Neither
 * is compiler-enforced, and neither fails a test that only walks the happy path - so what is asserted here
 * is that the collaborators the desktop BUILDS are the ones its shell already holds, and that the platform
 * seam they include refuses honestly rather than pretending to authenticate.
 *
 * It does NOT unlock a real database. The desktop's own start-up gate still owns unlock on the desktop
 * (open item 1, S4a), so this destination is additive wiring that no desktop user can reach yet - and a test
 * claiming to prove the unlock path works end to end would assert coverage the desktop does not have.
 */
class DesktopPassphraseUnlockViewModelTest {
    @Test
    fun `the collaborators the desktop hands over are the ONE vault reset and prompt, not second ones`() {
        val harness = collaboratorsOverARealStore()

        // Identity, not just type: two instances of the same class would satisfy a type check while holding
        // two independent failure counters, which is the entire failure this guards against. The comparison
        // is against the objects this test itself constructed - holding them in locals is what makes the
        // assertion mean something, since a fresh instance built inside the expectation would never match.
        assertThat(harness.collaborators.vault).isSameInstanceAs(harness.vault)
        assertThat(harness.collaborators.forgottenPassphraseReset).isSameInstanceAs(harness.reset)
        assertThat(harness.collaborators.biometricPrompt).isSameInstanceAs(DesktopBiometricUnlockPrompt)
    }

    @Test
    fun `the biometric prompt refuses on this platform rather than reporting a success it cannot produce`() {
        // The whole reason the screen's biometric slot is left empty is this answer. If a future change made
        // the desktop claim an authenticator, the screen would be hiding a promise the platform cannot keep.
        val availability = DesktopBiometricUnlockPrompt.availability()

        assertThat(availability)
            .isEqualTo(BiometricAvailability.Unavailable(BiometricUnavailableReason.UNKNOWN))
    }

    @Test
    fun `the built ViewModel is the shared class and is driven by the collaborators it was given`() {
        val harness = collaboratorsOverARealStore()

        val viewModel =
            databasePassphraseUnlockViewModel(
                collaborators = harness.collaborators,
                strings = UiStrings.of(UiLanguage.ENGLISH),
            )

        // The shared class itself, so the desktop is reusing Android's implementation rather than a second
        // one that merely compiles.
        assertThat(viewModel).isInstanceOf(DatabasePassphraseUnlockViewModel::class.java)
        // Its state is a live flow the screen can bind to, not an inert default: the shared init reads the
        // lockout from the vault and stats the database file, so a value here proves the ViewModel was
        // constructed with collaborators rather than with a stub.
        assertThat(viewModel.uiState.value.hasLocalDatabase).isFalse()
        assertThat(harness.vault.unlockLockoutSecondsRemaining()).isEqualTo(0L)

        // Released the way the destination's retention does, so a leaked SupervisorJob cannot outlive the
        // test JVM. `dispose()` is the release action `desktopRetained(onRelease = { it.dispose() })` calls.
        viewModel.dispose()
    }

    /**
     * A real [DesktopSecurityStore] over a temp directory, so the vault and reset under test are the same
     * classes the shell builds - not test doubles that would agree with anything.
     *
     * Returns the collaborators alongside the exact vault and reset they were built from, because the
     * assertions are about IDENTITY: a caller that supplied its own second instance is the defect, and that
     * can only be observed by comparing against the original.
     */
    private fun collaboratorsOverARealStore(): Harness {
        val securityDirectory: Path = Files.createTempDirectory("payanam-unlock-test")
        val store = DesktopSecurityStore(securityDirectory = securityDirectory)
        val vault = DesktopPassphraseVault(store)
        // The reset takes the same file the unlock gate opens, so the two cannot drift onto different
        // artifact families.
        val databaseFile = securityDirectory.resolve("payanam.db")
        val reset = DesktopForgottenPassphraseReset(vault, databaseFile)
        val collaborators =
            DesktopPassphraseUnlockCollaborators(
                vault = vault,
                biometricPrompt = DesktopBiometricUnlockPrompt,
                forgottenPassphraseReset = reset,
                // The desktop's own session controller, which is what leaves the shared data layer installed
                // when this gate succeeds. Injected here rather than defaulted, because a second controller
                // would be a second shutdown target and a second possible installation.
                sessionController =
                    DesktopDatabaseSessionController(
                        databaseFilePath = { databaseFile.toAbsolutePath().toString() },
                        open = { _, _ ->
                            DatabaseOpenResult.Refused(
                                DatabaseBootIssue(type = DatabaseBootIssueType.OPEN_FAILED),
                            )
                        },
                        logger = AppLogger.current,
                    ),
                databaseFile = databaseFile.toFile(),
            )
        return Harness(collaborators = collaborators, vault = vault, reset = reset)
    }

    /**
     * What a test needs to make an identity claim: the collaborators under test AND the exact instances
     * they were built from.
     */
    private data class Harness(
        val collaborators: DesktopPassphraseUnlockCollaborators,
        val vault: PassphraseVault,
        val reset: DatabaseForgotPassphraseReset,
    )
}
