//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopSecurityStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `configure passphrase persists desktop security verifier`() {
        val store = DesktopSecurityStore(securityDirectory = temporaryFolder.newFolder("desktop-security").toPath())

        val result = store.configurePassphrase("S3cure!Passphrase")
        val snapshot = store.loadSnapshot()

        assertThat(result).isEqualTo(DesktopPassphraseActionResult.Success)
        assertThat(snapshot.hasPassphraseConfigured).isTrue()
    }

    @Test
    fun `verify passphrase returns success for matching passphrase`() {
        val store = DesktopSecurityStore(securityDirectory = temporaryFolder.newFolder("desktop-security-ok").toPath())
        store.configurePassphrase("S3cure!Passphrase")

        val result = store.verifyPassphrase("S3cure!Passphrase")

        assertThat(result).isEqualTo(DesktopPassphraseActionResult.Success)
        assertThat(store.loadSnapshot().failedUnlockAttempts).isEqualTo(0)
    }

    @Test
    fun `verify passphrase increments attempts and lockout when repeated failures occur`() {
        var now = 1_000L
        val store =
            DesktopSecurityStore(
                securityDirectory = temporaryFolder.newFolder("desktop-security-lock").toPath(),
                clock = { now },
            )
        store.configurePassphrase("S3cure!Passphrase")

        store.verifyPassphrase("wrong-one")
        store.verifyPassphrase("wrong-two")
        val thirdResult = store.verifyPassphrase("wrong-three")

        assertThat(thirdResult)
            .isEqualTo(
                DesktopPassphraseActionResult.UnlockFailed(
                    failedAttempts = 3,
                    lockoutSecondsRemaining = 30L,
                ),
            )
        now += 1_000L
        val lockedResult = store.verifyPassphrase("S3cure!Passphrase")
        assertThat(lockedResult).isEqualTo(DesktopPassphraseActionResult.Locked(lockoutSecondsRemaining = 29L))
    }
}
