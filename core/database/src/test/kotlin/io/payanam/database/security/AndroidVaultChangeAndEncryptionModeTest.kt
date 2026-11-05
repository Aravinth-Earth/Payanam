//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.security

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.payanam.common.logging.UnifiedLogger
import io.payanam.shared.database.DatabaseEncryptionProbe
import io.payanam.shared.security.PassphraseConfigureOutcome
import io.payanam.shared.security.PassphraseUpdateOutcome
import io.payanam.shared.security.VaultAvailability
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The two vault members Android's existing tests never ask about: a passphrase CHANGE, and whether the
 * database is encrypted at all.
 *
 * WHY THESE ARE SEPARATE FROM THE OTHER ADAPTER TESTS: `AndroidPassphraseVaultTest` and
 * `AndroidVaultLockoutEnforcementTest` answer "does a passphrase round-trip" and "does the lockout
 * escalate". Both of those pass whether or not the vault can CHANGE a passphrase or report the encryption
 * mode, because neither goes near either code path — and those are the members the shared contract grew,
 * so the shared change screen now calls them on this platform.
 *
 * WHY `updatePassphrase` NEEDS ITS OWN FILE RATHER THAN ONE MORE CASE IN THE ROUND-TRIP FILE: it returns
 * FOUR outcomes, and two of them are only reachable by being wrong in a specific way. `CURRENT_PASSPHRASE_
 * WRONG` means the user mistyped the passphrase they are replacing; `REJECTED_BY_POLICY` means the new one
 * is unusable. A change screen that mapped both to one message would tell a user with a fine passphrase to
 * type a different one, and the "your passphrase is wrong" advice they get instead is wrong.
 *
 * WHY THE MODE IS ASSERTED HERE AND NOT ELSEWHERE: `isEncryptionEnabled` is a MODE, not a consequence of
 * holding a passphrase, and the unlock gate renders it as a label over the user's data. The property worth
 * pinning is that the two flags flip TOGETHER through the vault's own operations — so there is no window in
 * which the app claims the user's database is encrypted without a passphrase that can open it, and none in
 * which it claims plaintext while a passphrase exists.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidVaultChangeAndEncryptionModeTest {
    private val currentPassphrase = "Curr3nt-Passphrase!"
    private val newPassphrase = "N3w-Passphrase!"

    private lateinit var vault: AndroidPassphraseVault

    @Before
    fun setUp() {
        // The manager resolves the logger singleton at CONSTRUCTION, so this must precede the vault.
        // The guard rather than a bare call so the file does not depend on test-class execution order.
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        }
        // THE SAME STORE EVERY OTHER TEST IN THIS MODULE USES. Robolectric hands every test in a JVM the
        // one application context, so `DatabaseEncryptionManager` resolves the same "db_security_prefs"
        // blob here as in `AndroidPassphraseVaultTest`, `AndroidVaultLockoutEnforcementTest` and
        // `DatabaseEncryptionManagerSecurityModelTest`. Several cases below assert that a vault is FRESH —
        // which a passphrase left behind by an earlier class would contradict, turning a real assertion into
        // an order-dependent one. Cleared here for that reason alone, exactly as the security-model test
        // does it.
        ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        vault = AndroidPassphraseVault(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `a change proved with the current passphrase replaces it`() {
        vault.configurePassphrase(currentPassphrase.toCharArray())

        val outcome = vault.updatePassphrase(currentPassphrase.toCharArray(), newPassphrase.toCharArray())

        assertThat(outcome).isEqualTo(PassphraseUpdateOutcome.UPDATED)
        // Both halves of "the change happened": the new passphrase opens the vault and the retired one
        // does not. Asserting only the first would pass for a change that added a second passphrase.
        assertThat(vault.verifyPassphrase(newPassphrase.toCharArray())).isTrue()
        assertThat(vault.verifyPassphrase(currentPassphrase.toCharArray())).isFalse()
    }

    @Test
    fun `a wrong current passphrase is refused and the original keeps working`() {
        vault.configurePassphrase(currentPassphrase.toCharArray())

        val outcome =
            vault.updatePassphrase(
                "Wr0ng-Current!".toCharArray(),
                newPassphrase.toCharArray(),
            )

        assertThat(outcome).isEqualTo(PassphraseUpdateOutcome.CURRENT_PASSPHRASE_WRONG)
        // The contract's promise for a refused change, and the one that matters most here: the owner is not
        // locked out of their own data by a typo. A vault that cleared the credential to report the refusal
        // would satisfy the outcome enum and destroy the user's access.
        assertThat(vault.verifyPassphrase(currentPassphrase.toCharArray())).isTrue()
        assertThat(vault.verifyPassphrase(newPassphrase.toCharArray())).isFalse()
    }

    @Test
    fun `a replacement the policy rejects leaves the original working`() {
        vault.configurePassphrase(currentPassphrase.toCharArray())

        // Below the shared minimum length, so the shared policy answers before storage is touched.
        val outcome = vault.updatePassphrase(currentPassphrase.toCharArray(), "short".toCharArray())

        assertThat(outcome).isEqualTo(PassphraseUpdateOutcome.REJECTED_BY_POLICY)
        // Not CURRENT_PASSPHRASE_WRONG: the policy is consulted before the current passphrase is compared,
        // so a rejected replacement cannot be used to probe whether the current one was right.
        assertThat(vault.verifyPassphrase(currentPassphrase.toCharArray())).isTrue()
    }

    @Test
    fun `the encryption answer comes from the file, and the store keeps answering about the material`() {
        // The vault no longer answers this member from the stored mode. It answers from the FILE through
        // the platform's probe - the same question every other file reader asks - so this test steers the
        // probe and the file, not a preferences flag. The store still owns the MATERIAL question, and the
        // never-enabled-without-material rule now lives exactly there: a fresh install has no passphrase,
        // and configuring one turns the material answer on. The two answers are asserted together so a
        // vault that collapsed them again would fail here.
        val probeFile = java.io.File.createTempFile("vault-encryption", ".db")
        val probe =
            object : DatabaseEncryptionProbe {
                var encrypted = false

                override fun isDetectablyEncrypted(
                    databaseFile: java.io.File,
                    logTag: String,
                ): Boolean = encrypted

                override fun canOpen(
                    databaseFile: java.io.File,
                    passphrase: String,
                    logTag: String,
                ): Boolean = true

                override fun ensureEncrypted(
                    databaseFile: java.io.File,
                    passphrase: String,
                    logTag: String,
                ) = Unit

                override fun readTableCounts(
                    databaseFile: java.io.File,
                    passphrase: String?,
                    tableNames: List<String>,
                ): Map<String, Int> = emptyMap()
            }
        val fresh =
            AndroidPassphraseVault(
                ApplicationProvider.getApplicationContext(),
                encryptionProbe = probe,
                databaseFile = { probeFile },
            )

        assertThat(fresh.isEncryptionEnabled()).isFalse()
        assertThat(fresh.hasPassphraseConfigured()).isFalse()

        assertThat(fresh.configurePassphrase(currentPassphrase.toCharArray()))
            .isEqualTo(PassphraseConfigureOutcome.CONFIGURED)

        // THE DISTINGUISHING CASE: a passphrase is now stored, so the MODE flag says encrypted - and the
        // FILE says plaintext. The vault must report the FILE. Asserting `true` here (as this test first
        // did) could not tell the two answers apart, and a mutation that put the mode read back survived
        // it; this assertion is the one that fails when the member stops asking the file.
        assertThat(fresh.hasPassphraseConfigured()).isTrue()
        assertThat(fresh.isEncryptionEnabled()).isFalse()

        // And the file answer follows the file: flip the probe and the vault follows it, with the store
        // untouched.
        probe.encrypted = true
        assertThat(fresh.isEncryptionEnabled()).isTrue()
        assertThat(fresh.hasPassphraseConfigured()).isTrue()
    }

    @Test
    fun `a passphrase the policy rejects leaves encryption switched off`() {
        val fresh = AndroidPassphraseVault(ApplicationProvider.getApplicationContext())

        val outcome = fresh.configurePassphrase("short".toCharArray())

        assertThat(outcome).isEqualTo(PassphraseConfigureOutcome.REJECTED_BY_POLICY)
        // The never-enabled-without-material rule, pinned on the rejected path as well as the accepted one:
        // a refused passphrase must not leave the mode flag claiming protection the vault cannot open.
        assertThat(fresh.isEncryptionEnabled()).isFalse()
        assertThat(fresh.hasPassphraseConfigured()).isFalse()
    }

    @Test
    fun `clearing the credential switches encryption off and leaves the vault readable`() {
        vault.configurePassphrase(currentPassphrase.toCharArray())

        vault.clearPassphrase()

        // The credential is gone, so the mode cannot still claim the database is encrypted — the two flip
        // together, in the direction that under-claims rather than over-claims.
        assertThat(vault.hasPassphraseConfigured()).isFalse()
        assertThat(vault.isEncryptionEnabled()).isFalse()
        // Still readable, not unavailable: clearing is a routine action, and reporting the vault unreadable
        // afterwards would send a caller down the "I cannot find out" path it reserves for corruption.
        assertThat(vault.availability()).isEqualTo(VaultAvailability.AVAILABLE)
    }

    private companion object {
        /** `DatabaseEncryptionManager`'s own store name; see that file's `PREFS_NAME`. */
        const val PREFS_NAME = "db_security_prefs"
    }
}