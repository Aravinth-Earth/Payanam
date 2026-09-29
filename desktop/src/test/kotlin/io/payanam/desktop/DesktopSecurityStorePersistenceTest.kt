//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * The passphrase verifier survives a restart, because that is the property the in-memory scaffold could
 * not have.
 *
 * The defect this pins: `DesktopSecurityStore` wrote its verifier through `DesktopLocalStateMock`, a
 * `ConcurrentHashMap`. Nothing reached disk, so after a quit `hasPassphraseConfigured` read false, the
 * startup gate resolved to `SetupPassphrase`, and setting a new passphrase produced a database on disk
 * encrypted with a key that existed nowhere else. The user was shown a first-run screen over data that
 * was already encrypted.
 *
 * **Every case here constructs a SECOND store over the same directory**, which is the shape of a restart.
 * The pre-existing suite did not: it gave each store its own `tempFolder.newFolder()`, so a store that
 * stored nothing would have passed every one of them — a test that shares no state with the thing it
 * claims to test cannot fail when that thing stops working.
 */
class DesktopSecurityStorePersistenceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "correct-horse-Battery-9"

    private fun storeIn(directory: java.nio.file.Path) = DesktopSecurityStore(securityDirectory = directory)

    @Test
    fun `a configured passphrase is still configured after a restart`() {
        val directory = tempFolder.newFolder().toPath()

        storeIn(directory).configurePassphrase(passphrase)

        // A second store over the same directory IS a restart: new instance, nothing carried in memory.
        val afterRestart = storeIn(directory).loadSnapshot()
        assertThat(afterRestart.availability).isEqualTo(DesktopSecurityAvailability.AVAILABLE)
        assertThat(afterRestart.hasPassphraseConfigured).isTrue()
    }

    @Test
    fun `the verifier written before a restart still unlocks after it`() {
        val directory = tempFolder.newFolder().toPath()
        storeIn(directory).configurePassphrase(passphrase)

        // Not `hasPassphraseConfigured` - the answer that matters. A store that reported the flag but
        // could no longer derive a hash from the stored salt would pass the test above and lock the user
        // out of their own database.
        val result = storeIn(directory).verifyPassphrase(passphrase)

        assertThat(result).isEqualTo(DesktopPassphraseActionResult.Success)
    }

    @Test
    fun `a wrong passphrase is still rejected after a restart, and the counter persists`() {
        val directory = tempFolder.newFolder().toPath()
        storeIn(directory).configurePassphrase(passphrase)

        // Well-formed but wrong, so it is an UnlockFailed - NOT a ValidationFailed. That distinction is
        // the whole point of the two result types: a policy rejection and a wrong passphrase must not
        // look the same, because only one of them should cost the user an attempt.
        val wrong = storeIn(directory).verifyPassphrase("wrong-passphrase-Definitely-1")

        val failed = wrong as DesktopPassphraseActionResult.UnlockFailed
        assertThat(failed.failedAttempts).isEqualTo(1)
        assertThat(failed.lockoutSecondsRemaining).isEqualTo(0L)
        // The lockout is rate limiting, and a counter that resets on every launch is no counter at all.
        assertThat(storeIn(directory).loadSnapshot().failedUnlockAttempts).isEqualTo(1)
    }

    @Test
    fun `the verifier file is readable only by its owner`() {
        val directory = tempFolder.newFolder().toPath()
        val store = storeIn(directory)

        store.configurePassphrase(passphrase)

        val file = store.getSecurityFilePath()
        assertThat(Files.exists(file)).isTrue()
        // The desktop analogue of Android's `MODE_PRIVATE`. Asserted on the file the store reports,
        // because a test that resolved the path itself would pass even if the store wrote elsewhere.
        assertThat(Files.getPosixFilePermissions(file))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"))
    }

    @Test
    fun `a file left with wider permissions is tightened on the next write`() {
        val directory = tempFolder.newFolder().toPath()
        val store = storeIn(directory)
        val file = store.getSecurityFilePath()
        Files.createDirectories(file.parent)
        // A file that arrived by import, or by a copy that preserved modes, must be repaired rather than
        // trusted - otherwise the store's boundary is whatever the copy left behind.
        Files.writeString(file, "irrelevant")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"))

        store.configurePassphrase(passphrase)

        assertThat(Files.getPosixFilePermissions(file))
            .isEqualTo(PosixFilePermissions.fromString("rw-------"))
    }

    @Test
    fun `no passphrase is configured on a first run, and that is not a fault`() {
        val directory = tempFolder.newFolder().toPath()

        val snapshot = storeIn(directory).loadSnapshot()

        // AVAILABLE, not UNAVAILABLE: an absent verifier is a fresh install, and reporting it as a fault
        // is what the L5 fail-closed work explicitly refuses to do.
        assertThat(snapshot.availability).isEqualTo(DesktopSecurityAvailability.AVAILABLE)
        assertThat(snapshot.hasPassphraseConfigured).isFalse()
    }
}
