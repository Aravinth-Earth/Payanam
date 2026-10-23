//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The desktop opens a REAL database brought over from Android, by passphrase, through the production store.
 *
 * **Why this file exists.** The store's verifier is a hash it wrote itself, so it only ever recognised a
 * passphrase this platform had configured; an imported database has none, and the owner's correct passphrase
 * was refused instead of opening his own data. The unit-level tests beside this one can only prove the safe
 * direction - a file that is not a database never accepts - because a real SQLCipher database cannot be
 * manufactured in a test without pretending. So this case is opt-in against the owner's own Android-produced
 * file, the same way the import loop is: it runs only when `-Dpayanam.loop.db` and `-Dpayanam.loop.pwd` are
 * supplied, and is SKIPPED (not failed) otherwise, so CI and a plain `:desktop:test` are unaffected.
 *
 * The passphrase is read from its file into a local, used, and never logged, asserted on, or printed.
 *
 * **What each case would have to break to fail**
 * - [a database from another platform opens with its own passphrase even though the store never saw it] - the
 *   database-first check itself; without it the store compares against its own unrelated verifier and refuses.
 * - [the store's own verifier is realigned to the passphrase the file accepted] - the realignment, without
 *   which `hasPassphraseConfigured` goes on denying that a passphrase exists for an encrypted database, and
 *   the first-run screens come back over live data.
 * - [the same store without the database still refuses that passphrase] - THE FALSIFIER. It runs the identical
 *   setup with no file to ask, and the verdict must flip to a refusal; if it does not, the cases above are
 *   passing for some other reason and prove nothing about the database.
 */
class DesktopUnlockAgainstFixtureTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val databaseProperty: String? = System.getProperty("payanam.loop.db")
    private val passphraseProperty: String? = System.getProperty("payanam.loop.pwd")

    /** A private copy: the owner's file is read, never written, and never opened in place. */
    private fun copiedDatabase(): Path {
        val source = File(requireNotNull(databaseProperty) { "payanam.loop.db" }).toPath()
        val target = temporaryFolder.newFolder("db").toPath().resolve(source.fileName.toString())
        return Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * The LAST non-empty line of the passphrase file, matching the import harness exactly.
     *
     * MEASURED, and the reason this is not a one-liner: the file's earlier lines are its own label, so
     * `.readText().trim()` returns the label and the secret CONCATENATED - a 36-character string for a 19+16
     * pair - and every probe then correctly refuses a passphrase that was never the passphrase. That mistake
     * looks exactly like a broken passphrase probe, and cost a detour through the opener's guard chain to
     * disprove. One reading rule, in one place, or the file appears to be the defect.
     */
    private fun fixturePassphrase(): String =
        Files
            .readAllLines(Path.of(requireNotNull(passphraseProperty) { "payanam.loop.pwd" }))
            .map { it.trim() }
            .last { it.isNotEmpty() }

    private fun storeOver(
        databaseFile: File?,
        name: String,
    ) = DesktopSecurityStore(
        securityDirectory = temporaryFolder.newFolder(name).toPath(),
        databaseFile = { databaseFile },
    )

    private fun requireFixture() {
        assumeTrue(
            "Unlock fixture not supplied: set -Dpayanam.loop.db/-Dpayanam.loop.pwd",
            databaseProperty != null && passphraseProperty != null,
        )
    }

    @Test
    fun `a database from another platform opens with its own passphrase even though the store never saw it`() {
        requireFixture()
        val database = copiedDatabase().toFile()
        val store = storeOver(database, "security-imported")
        // The state the owner was in: this desktop once wrote a verifier for some OTHER passphrase, so the
        // store's own record denies the one that actually opens the file.
        store.configurePassphrase("unrelated-horse-Battery-9")

        assertThat(store.verifyPassphrase(fixturePassphrase()))
            .isEqualTo(DesktopPassphraseActionResult.Success)
    }

    @Test
    fun `the store's own verifier is realigned to the passphrase the file accepted`() {
        requireFixture()
        val database = copiedDatabase().toFile()
        val store = storeOver(database, "security-realign")
        store.configurePassphrase("unrelated-horse-Battery-9")

        assertThat(store.verifyPassphrase(fixturePassphrase()))
            .isEqualTo(DesktopPassphraseActionResult.Success)

        // Realigned: the store now holds a verifier for the passphrase that opens the file, so the encryption
        // gate stops reading "no passphrase configured" for a database that is encrypted with one.
        assertThat(store.loadSnapshot().hasPassphraseConfigured).isTrue()
    }

    @Test
    fun `a wrong passphrase still does not open the imported database`() {
        requireFixture()
        val database = copiedDatabase().toFile()
        val store = storeOver(database, "security-wrong")
        store.configurePassphrase("unrelated-horse-Battery-9")

        // The database is asked first, so a wrong passphrase must be refused by the FILE - not merely by the
        // store's unrelated verifier, which would pass this case even if the file were never consulted.
        assertThat(store.verifyPassphrase("definitely-horse-Battery-9"))
            .isNotEqualTo(DesktopPassphraseActionResult.Success)
    }

    @Test
    fun `the same store without the database still refuses that passphrase`() {
        requireFixture()
        // The falsifier: identical store, identical verifier, no file to ask. A refusal here is what proves
        // the acceptance above came from the database and not from the store's own comparison.
        val store = storeOver(null, "security-control")
        store.configurePassphrase("unrelated-horse-Battery-9")

        assertThat(store.verifyPassphrase(fixturePassphrase()))
            .isNotEqualTo(DesktopPassphraseActionResult.Success)
    }
}
