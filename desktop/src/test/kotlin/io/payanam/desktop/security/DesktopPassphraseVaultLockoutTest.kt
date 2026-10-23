//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.security

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.DesktopSecurityStore
import io.payanam.shared.security.SharedPassphraseLockoutPolicy
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the unlock lockout the desktop vault exposes through the shared contract.
 *
 * WHY THIS FILE IS SEPARATE FROM THE ADAPTER'S OTHER TESTS: those four answer "does a passphrase
 * round-trip", and every one of them passes whether or not the escalation schedule is applied — a wrong
 * passphrase is `false` either way. The schedule is the property only these cases can see.
 *
 * EVERY WRONG ATTEMPT GOES THROUGH THE VAULT in the order the shared unlock screen uses it (verify, then
 * record the failure it observed), because the schedule only means something if counting happens exactly
 * once per attempt.
 *
 * The store's clock is the real one deliberately. [DesktopPassphraseVault.unlockLockoutSecondsRemaining]
 * derives the countdown from `Instant.now()` rather than the store's injected clock, so a fake clock would
 * let the vault's reported countdown and the store's enforcement disagree — a test built on that would be
 * asserting a fiction. The assertions here are therefore all about behaviour that holds within the
 * milliseconds a test takes.
 */
class DesktopPassphraseVaultLockoutTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val validPassphrase = "Str0ng-Passphrase!"

    private lateinit var vault: DesktopPassphraseVault

    @Before
    fun setUp() {
        vault =
            DesktopPassphraseVault(
                DesktopSecurityStore(securityDirectory = tempFolder.newFolder().toPath()),
            )
        vault.configurePassphrase(validPassphrase.toCharArray())
    }

    /** One wrong attempt, the way the shared unlock screen performs one. */
    private fun failedAttempt(wrong: String = "Wr0ng-Passphrase!") {
        vault.verifyPassphrase(wrong.toCharArray())
        vault.recordFailedUnlockAttempt()
    }

    @Test
    fun `the first two wrong attempts cost nothing`() {
        failedAttempt()
        failedAttempt()

        assertThat(vault.unlockLockoutSecondsRemaining()).isEqualTo(0L)
    }

    @Test
    fun `the third wrong attempt starts the shared schedule's thirty seconds`() {
        repeat(3) { failedAttempt() }

        // Within a second of the shared table's first rung, and NOT zero and NOT the next rung (60s). The
        // tolerance is deliberate and is not sloppiness: the desktop derives the countdown with a floor
        // division and Android with a ceiling, so a 30s lockout reads 29 here and 30 there a few
        // milliseconds later. Neither ever reports 0 while a lockout is active, so the rounding is a display
        // convention, not the property under test — pinning an exact number would assert one platform's
        // rounding rather than the shared schedule.
        val remaining = vault.unlockLockoutSecondsRemaining()
        assertThat(remaining).isGreaterThan(0L)
        assertThat(remaining).isAtMost(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(3))
        assertThat(remaining).isNotEqualTo(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(4))
    }

    @Test
    fun `recording the failure again does not escalate the schedule twice as fast`() {
        repeat(3) { failedAttempt() }
        val afterThree = vault.unlockLockoutSecondsRemaining()

        // A fourth wrong attempt is refused while the lockout holds, so the counter does not move and a
        // second recording cannot push the delay to the next rung. This is the case that fails if the
        // store stops counting inside its own verification.
        assertThat(vault.verifyPassphrase("An0ther-Wrong!".toCharArray())).isFalse()
        val afterRecording = vault.recordFailedUnlockAttempt()

        assertThat(afterRecording).isEqualTo(afterThree)
        assertThat(vault.unlockLockoutSecondsRemaining()).isNotEqualTo(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(4))
    }

    @Test
    fun `a correct passphrase is refused while the lockout is active`() {
        repeat(3) { failedAttempt() }

        assertThat(vault.verifyPassphrase(validPassphrase.toCharArray())).isFalse()
    }

    @Test
    fun `a successful unlock clears the counter so the next failure is counted from one`() {
        failedAttempt()
        failedAttempt()
        assertThat(vault.verifyPassphrase(validPassphrase.toCharArray())).isTrue()

        failedAttempt()

        // Counted from one, not from three: a third attempt would be the first punished one, so a lockout
        // here would mean the earlier failures survived the successful unlock.
        assertThat(vault.unlockLockoutSecondsRemaining()).isEqualTo(0L)
    }

    @Test
    fun `clearing the credential takes the lockout with it`() {
        repeat(3) { failedAttempt() }

        vault.clearPassphrase()

        assertThat(vault.hasPassphraseConfigured()).isFalse()
        assertThat(vault.unlockLockoutSecondsRemaining()).isEqualTo(0L)
    }
}