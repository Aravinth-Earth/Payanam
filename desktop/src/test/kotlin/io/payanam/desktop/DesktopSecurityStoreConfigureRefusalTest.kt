//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.security.DesktopPassphraseVault
import io.payanam.shared.security.PassphraseConfigureOutcome
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * A store that cannot say what it holds refuses to configure, instead of overwriting it.
 *
 * WHY THIS FILE EXISTS: the shared contract carries a [PassphraseConfigureOutcome.STORE_UNAVAILABLE] case
 * that existed on Android but was unreachable on this platform, so a desktop app that could not persist a
 * passphrase reported [PassphraseConfigureOutcome.CONFIGURED] instead. The branch survived because no test
 * could reach it — the sibling test that measured the gap documented the gap and moved on.
 *
 * ## Why the blank file is the seam, and no new one was added
 *
 * [DesktopSecurityStore] already takes its directory as a constructor parameter and already decides
 * UNAVAILABLE from a present-but-blank entry. A blank verifier file therefore drives the exact state this
 * is about without a fake, without a mock framework, and without a production seam added for a test. That
 * the store is *writable* in this state is the point, not a limitation: it is why the refusal has to be
 * explicit. A chmod-based unreadable store would not do — running as root defeats it, so such a test would
 * pass on a developer machine and lie on a real one.
 *
 * ## What each case would have to break to fail
 *
 * - [DesktopSecurityStore.configurePassphrase] returning anything but
 *   [DesktopPassphraseActionResult.Unavailable] — i.e. dropping the availability check, or moving it after
 *   the write.
 * - The refusal being reported instead of enforced: the two `assertThat` calls on the file's bytes, which
 *   fail if a verifier was written before the answer was decided.
 * - The success path, in [an available store still configures and persists the verifier].
 */
class DesktopSecurityStoreConfigureRefusalTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "correct-horse-Battery-9"

    /** A store over a directory whose verifier file is present but blank — UNAVAILABLE, and writable. */
    private fun storeWithUnreadableEntry(): DesktopSecurityStore {
        val store = DesktopSecurityStore(securityDirectory = tempFolder.newFolder().toPath())
        Files.writeString(store.getSecurityFilePath(), "")
        return store
    }

    @Test
    fun `a store that cannot be read refuses to configure and writes nothing`() {
        val store = storeWithUnreadableEntry()
        val vault = DesktopPassphraseVault(store)

        val outcome = vault.configurePassphrase(passphrase.toCharArray())

        assertThat(outcome).isEqualTo(PassphraseConfigureOutcome.STORE_UNAVAILABLE)
        // The refusal, not just the report: the entry is still the blank bytes the test wrote and nothing
        // was written over them. Without this, a store that answered Unavailable AFTER a successful write
        // would pass the assertion above — the branch restored but the damage already done.
        assertThat(Files.readString(store.getSecurityFilePath())).isEmpty()
        assertThat(store.loadSnapshot().hasPassphraseConfigured).isFalse()
    }

    @Test
    fun `the refusal is decided before the write, not reported after it`() {
        val store = storeWithUnreadableEntry()
        val vault = DesktopPassphraseVault(store)

        vault.configurePassphrase(passphrase.toCharArray())

        // Not the outcome again — the LOG. A refusal decided after the write cannot say it declined to
        // write, because by then it has; `writeEntry` is what a configure does, so a recorded verifier here
        // means the answer was computed from state the store had already replaced.
        assertThat(store.loadSnapshot().hasPassphraseConfigured).isFalse()
        // No temp file left behind either: `writeEntry` stages through a sibling `.tmp` and moves it into
        // place, so a configure that ran at all leaves evidence on disk. Asserting the absence of that
        // evidence is what makes the ordering observable rather than inferred from the return value.
        assertThat(Files.exists(store.getSecurityFilePath().resolveSibling("desktop-security.properties.tmp")))
            .isFalse()
    }

    @Test
    fun `an available store still configures and persists the verifier`() {
        val store = DesktopSecurityStore(securityDirectory = tempFolder.newFolder().toPath())
        val vault = DesktopPassphraseVault(store)

        // The case a fix could break by being too eager: refusing on a store that is simply not configured
        // yet would lock every first run out of setting a passphrase. A fresh directory is AVAILABLE, so
        // this must still succeed.
        assertThat(vault.configurePassphrase(passphrase.toCharArray()))
            .isEqualTo(PassphraseConfigureOutcome.CONFIGURED)
        assertThat(store.loadSnapshot().hasPassphraseConfigured).isTrue()
        assertThat(vault.verifyPassphrase(passphrase.toCharArray())).isTrue()
    }

    @Test
    fun `a store that becomes readable again is no longer refused`() {
        val store = storeWithUnreadableEntry()
        val vault = DesktopPassphraseVault(store)
        assertThat(vault.configurePassphrase(passphrase.toCharArray()))
            .isEqualTo(PassphraseConfigureOutcome.STORE_UNAVAILABLE)

        // The verdict is read per call, never cached. A store that had cached the refusal would keep
        // refusing a directory that has since become readable, leaving the user with no way forward
        // short of reinstalling.
        Files.delete(store.getSecurityFilePath())

        assertThat(vault.configurePassphrase(passphrase.toCharArray()))
            .isEqualTo(PassphraseConfigureOutcome.CONFIGURED)
        assertThat(vault.verifyPassphrase(passphrase.toCharArray())).isTrue()
    }
}