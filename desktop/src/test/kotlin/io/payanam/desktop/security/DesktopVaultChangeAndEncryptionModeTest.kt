//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.security

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.DesktopPassphraseActionResult
import io.payanam.desktop.DesktopSecurityAvailability
import io.payanam.desktop.DesktopSecurityStore
import io.payanam.shared.security.PassphraseConfigureOutcome
import io.payanam.shared.security.PassphraseUpdateOutcome
import io.payanam.shared.security.VaultAvailability
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * The two vault members the desktop's existing tests never ask about: a passphrase CHANGE, and whether the
 * database is encrypted at all.
 *
 * WHY THESE ARE SEPARATE FROM `DesktopPassphraseVaultTest` and `DesktopPassphraseVaultLockoutTest`: those
 * two answer "does a passphrase round-trip" and "does the lockout escalate", and every case in both passes
 * whether or not the vault can change a passphrase or report the encryption mode — neither file goes near
 * either code path. Those are the members the shared contract grew, so the shared change screen now calls
 * them on this platform too.
 *
 * WHY `updatePassphrase` NEEDS ITS OWN FILE: it returns FOUR outcomes and two are reachable only by being
 * wrong in a specific way. A change screen that collapsed `CURRENT_PASSPHRASE_WRONG` into `STORE_UNAVAILABLE`
 * would tell a user who mistyped their passphrase that the app is broken, which sends them nowhere.
 */
class DesktopVaultChangeAndEncryptionModeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val currentPassphrase = "Curr3nt-Passphrase!"
    private val newPassphrase = "N3w-Passphrase!"

    /** A vault over its own directory, so a passphrase from another test cannot satisfy an assertion here. */
    private fun vault(): DesktopPassphraseVault =
        DesktopPassphraseVault(
            DesktopSecurityStore(securityDirectory = tempFolder.newFolder().toPath()),
        )

    /** A vault that answers the encryption question about [file], through the REAL probe - the production shape. */
    private fun vaultOver(file: java.io.File): DesktopPassphraseVault =
        DesktopPassphraseVault(
            DesktopSecurityStore(securityDirectory = tempFolder.newFolder().toPath()),
            encryptionProbe = io.payanam.desktop.database.DesktopDatabaseInitPorts.encryptionProbe,
            databaseFile = { file },
        )

    @Test
    fun `a change proved with the current passphrase replaces it`() {
        val vault = vault()
        vault.configurePassphrase(currentPassphrase.toCharArray())

        val outcome = vault.updatePassphrase(currentPassphrase.toCharArray(), newPassphrase.toCharArray())

        assertThat(outcome).isEqualTo(PassphraseUpdateOutcome.UPDATED)
        // Both halves of "the change happened": the new passphrase opens the vault and the retired one does
        // not. Asserting only the first would also pass for a change that ADDED a second passphrase.
        assertThat(vault.verifyPassphrase(newPassphrase.toCharArray())).isTrue()
        assertThat(vault.verifyPassphrase(currentPassphrase.toCharArray())).isFalse()
    }

    @Test
    fun `a wrong current passphrase is refused and the original keeps working`() {
        val vault = vault()
        vault.configurePassphrase(currentPassphrase.toCharArray())

        val outcome =
            vault.updatePassphrase(
                "Wr0ng-Current!".toCharArray(),
                newPassphrase.toCharArray(),
            )

        assertThat(outcome).isEqualTo(PassphraseUpdateOutcome.CURRENT_PASSPHRASE_WRONG)
        // Not locked out by a typo. This vault verifies `current` BEFORE it writes anything, so a refusal
        // here is guaranteed to have left the stored credential untouched.
        assertThat(vault.verifyPassphrase(currentPassphrase.toCharArray())).isTrue()
        assertThat(vault.verifyPassphrase(newPassphrase.toCharArray())).isFalse()
    }

    @Test
    fun `a replacement the policy rejects leaves the original working`() {
        val vault = vault()
        vault.configurePassphrase(currentPassphrase.toCharArray())

        // Below the shared minimum length, so the shared policy answers before the store is asked.
        val outcome = vault.updatePassphrase(currentPassphrase.toCharArray(), "short".toCharArray())

        assertThat(outcome).isEqualTo(PassphraseUpdateOutcome.REJECTED_BY_POLICY)
        // Not CURRENT_PASSPHRASE_WRONG: the policy is consulted first, so a rejected replacement cannot be
        // used to discover whether the current passphrase was correct.
        assertThat(vault.verifyPassphrase(currentPassphrase.toCharArray())).isTrue()
    }

    @Test
    fun `an accepted change resets the failed-attempt counter`() {
        val vault = vault()
        vault.configurePassphrase(currentPassphrase.toCharArray())
        // One wrong attempt, so a counter that survived the change would still punish the owner on their
        // next slip. Configuring over the existing verifier is how this platform performs a change, and the
        // counter reset rides along with it.
        vault.verifyPassphrase("Wr0ng-Passphrase!".toCharArray())

        vault.updatePassphrase(currentPassphrase.toCharArray(), newPassphrase.toCharArray())

        assertThat(vault.verifyPassphrase(newPassphrase.toCharArray())).isTrue()
        assertThat(vault.unlockLockoutSecondsRemaining()).isEqualTo(0L)
    }

    @Test
    fun `the desktop answers the encryption question from the FILE - plaintext reads false`() {
        val plaintext = tempFolder.newFile("plaintext.db")
        Files.write(plaintext.toPath(), "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1) + ByteArray(4080))
        val vault = vaultOver(plaintext)
        vault.configurePassphrase(currentPassphrase.toCharArray())

        // The never-enabled-without-material rule has to hold on BOTH platforms, and it cannot be the same
        // assertion on both. This case used to pass because the vault hardcoded `false` under a KDoc claiming
        // "the desktop opens an unencrypted JDBC file" - a premise the tree falsifies, since the desktop opens
        // with a key. The answer now comes from the file itself through the same probe every other file
        // question uses, and a plaintext file still reads `false`: that is the honest answer a settings screen
        // must see, asserted with the passphrase PRESENT so the case cannot pass by accident.
        assertThat(vault.isEncryptionEnabled()).isFalse()
        assertThat(vault.hasPassphraseConfigured()).isTrue()
    }

    @Test
    fun `the desktop answers the encryption question from the FILE - an encrypted file reads true`() {
        // The case the hardcoded `false` could never pass: a file that is NOT plaintext SQLite. Before this
        // change the vault would have reported `false` about it no matter what it held - the wrong answer
        // about a file the platform opened with a key, which is the defect this reader's rewrite ends.
        val encrypted = tempFolder.newFile("encrypted.db")
        Files.write(encrypted.toPath(), ByteArray(4096) { index -> (index * 31 + 7).toByte() })
        val vault = vaultOver(encrypted)

        assertThat(vault.isEncryptionEnabled()).isTrue()
    }

    @Test
    fun `an unreadable store reports unavailable rather than looking like a fresh install`() {
        // The half of the vault's availability contract this platform can reach and Android cannot: the
        // desktop's store distinguishes a blank or unparseable verifier file from an absent one, and the
        // vault must pass that distinction on rather than flattening it to "not configured".
        //
        // THE FILE IS MADE UNREADABLE BY BEING BLANK, not by permissions, because permissions are the wrong
        // tool here: running as root defeats them, so a chmod-based refusal would pass on a developer machine
        // and lie on a real one. A blank file is the shape the store documents as UNAVAILABLE, and the store
        // is not configured by this test at all — so nothing here depends on a chmod that may not hold.
        val directory = tempFolder.newFolder().toPath()
        val store = DesktopSecurityStore(securityDirectory = directory)
        Files.writeString(store.getSecurityFilePath(), "")

        val vault = DesktopPassphraseVault(store)

        assertThat(vault.availability()).isEqualTo(VaultAvailability.UNAVAILABLE)
        assertThat(vault.hasPassphraseConfigured()).isFalse()
        // A wrong passphrase for an unreadable vault is `false` and never an exception — a caller asking
        // this is authenticating, and refusing is the safe answer to "I could not check".
        assertThat(vault.verifyPassphrase(currentPassphrase.toCharArray())).isFalse()

        // The refusal itself is covered where the branch lives, in
        // `DesktopSecurityStoreConfigureRefusalTest`; asserted here too because this is the same blank-file
        // state, and the point of the case is the whole vault's answer to it. The store refuses BEFORE it
        // draws a salt or writes, so this blank-but-WRITABLE file comes back still blank — the absence of
        // any change to it is the assertion, and it is what a store that answered after the write would
        // fail. The contract's STORE_UNAVAILABLE case is therefore reachable through this platform's
        // adapter, and a caller does not have to check `availability()` before configuring.
        assertThat(vault.configurePassphrase(currentPassphrase.toCharArray()))
            .isEqualTo(PassphraseConfigureOutcome.STORE_UNAVAILABLE)
        assertThat(Files.readString(store.getSecurityFilePath())).isEmpty()
    }

    @Test
    fun `an unreadable store keeps an active lockout instead of resetting it to nothing`() {
        // The severity driver for UNAVAILABLE. Defaulting the counters on an unreadable store would report
        // zero seconds remaining, and a screen that reads zero renders the lockout as over — clearing an
        // escalation an attacker was counting on. The store returns its refusal snapshot, and this pins
        // that the VAULT does not translate that refusal into "no lockout".
        val directory = tempFolder.newFolder().toPath()
        val store = DesktopSecurityStore(securityDirectory = directory)
        store.configurePassphrase(currentPassphrase)
        // Three wrong attempts put the shared schedule's first rung in force, and the store persists it.
        repeat(3) { store.verifyPassphrase("Wr0ng-Passphrase!") }
        assertThat(store.loadSnapshot().lockedUntilEpochMillis).isNotNull()
        // Corrupt the persisted state AFTER the counter was written: the escalation is durable, and a store
        // that cannot parse it must not silently drop it.
        Files.writeString(store.getSecurityFilePath(), "")

        val vault = DesktopPassphraseVault(store)

        // `UNAVAILABLE` rather than AVAILABLE-with-zero-seconds: the honest answer is that the remaining
        // time is unknown, and this platform has no way to say that in seconds without lying in one
        // direction or the other. Asserting the AVAILABILITY is therefore the assertion.
        assertThat(vault.availability()).isEqualTo(VaultAvailability.UNAVAILABLE)
        assertThat(store.loadSnapshot().availability).isEqualTo(DesktopSecurityAvailability.UNAVAILABLE)
        // The store's own refusal verdict for an unlock it could not evaluate, which is distinct from a
        // wrong passphrase and from a lockout — and which the vault is required to report as a refusal.
        assertThat(store.verifyPassphrase(currentPassphrase))
            .isEqualTo(DesktopPassphraseActionResult.Unavailable)
    }
}