//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.security.SharedPassphraseLockoutPolicy
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the unlock lockout on Android, through the vault the shared unlock screen actually calls.
 *
 * WHY THIS EXISTS AS A SEPARATE FILE: the adapter's other four tests answer "does a passphrase round-trip",
 * and all four of them pass whether or not the lockout is enforced at all — verifying a wrong passphrase
 * returns `false` either way. The lockout is the property no other test here could see, and the desktop has
 * enforced it inside its store all along, so the shared schedule was a promise one platform kept and the
 * other did not.
 *
 * EVERY WRONG ATTEMPT GOES THROUGH THE VAULT, in the order the shared unlock screen uses it
 * (verify, then record the failure it observed), because the bug this closes is the interaction between
 * those two calls: counting in both is what would escalate the schedule twice as fast.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidVaultLockoutEnforcementTest {
    private val validPassphrase = "Str0ng-Passphrase!"

    private lateinit var vault: AndroidPassphraseVault

    @Before
    fun setUp() {
        // The manager resolves the logger singleton at CONSTRUCTION, so this has to happen before the vault
        // is built. Without it the whole file fails on an uninitialized logger — and passes only when some
        // earlier test in the same JVM happened to initialise it, which is why it was never noticed.
        UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        vault = AndroidPassphraseVault(ApplicationProvider.getApplicationContext())
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

        // Not 0, and not 60: attempt 3 is the first punished attempt in SharedPassphraseLockoutPolicy, so a
        // value of 30 is what proves the counter reaches the shared table rather than a private copy of it.
        assertThat(vault.unlockLockoutSecondsRemaining())
            .isEqualTo(SharedPassphraseLockoutPolicy.delaySecondsForAttempt(3))
        assertThat(vault.unlockLockoutSecondsRemaining()).isEqualTo(30L)
    }

    @Test
    fun `recording the failure again does not escalate the schedule twice as fast`() {
        repeat(3) { failedAttempt() }

        // The vault's recordFailedUnlockAttempt re-reads what verification already counted. If it
        // incremented as well, the third attempt would have reached 60s and the owner would be locked out
        // on attempt 2 rather than attempt 3 — the exact double-count this method exists to avoid.
        assertThat(vault.recordFailedUnlockAttempt()).isEqualTo(30L)
        assertThat(vault.unlockLockoutSecondsRemaining()).isEqualTo(30L)
    }

    @Test
    fun `a correct passphrase is refused while the lockout is active`() {
        repeat(3) { failedAttempt() }

        assertThat(vault.verifyPassphrase(validPassphrase.toCharArray())).isFalse()
    }

    @Test
    fun `a correct passphrase re-arms the schedule`() {
        repeat(3) { failedAttempt() }

        // No clock is advanced, so this only holds because the lockout is now consulted before the
        // comparison; it is the assertion that the lockout is enforced rather than merely counted.
        assertThat(vault.verifyPassphrase(validPassphrase.toCharArray())).isFalse()

        vault.resetUnlockAttempts()

        assertThat(vault.verifyPassphrase(validPassphrase.toCharArray())).isTrue()
        assertThat(vault.unlockLockoutSecondsRemaining()).isEqualTo(0L)
    }

    @Test
    fun `a successful unlock clears the counter so the next failure is counted from one`() {
        failedAttempt()
        failedAttempt()
        vault.resetUnlockAttempts()
        assertThat(vault.verifyPassphrase(validPassphrase.toCharArray())).isTrue()

        failedAttempt()

        assertThat(vault.unlockLockoutSecondsRemaining()).isEqualTo(0L)
    }
}