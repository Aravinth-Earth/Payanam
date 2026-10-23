//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The store's passphrase answer is asked of the DATABASE first.
 *
 * **Why this file exists.** The store held a hash it wrote itself, so it could only recognise a passphrase
 * this platform had configured. A database brought over from Android - the same encrypted file by design -
 * has no desktop verifier, so the owner's correct passphrase was refused and the database could not be
 * opened on the desktop at all. The file is now asked first, and these tests pin the two halves that a JVM
 * test can honestly prove: the new branch never fires on a file that is not a database, and it never
 * disturbs the store's existing verifier path.
 *
 * WHAT IS NOT PROVEN HERE, on purpose: that a file which IS the right encrypted database reports success.
 * That needs a real SQLCipher database, and the only one available on this machine is the owner's own, whose
 * passphrase is not test material. That half is proven against the real file by the desktop's own import/
 * unlock loop, not by a unit test pretending to have one.
 */
class DesktopSecurityStoreDatabaseTruthTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun storeGuarding(file: File?) =
        DesktopSecurityStore(
            securityDirectory = temporaryFolder.newFolder("security-${file?.name ?: "none"}").toPath(),
            databaseFile = { file },
        )

    @Test
    fun `a file that is not a database cannot accept a passphrase by accident`() {
        val notADatabase = temporaryFolder.newFile("payanam.db").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val store = storeGuarding(notADatabase)
        store.configurePassphrase("correct-horse-Battery-9")

        // The opener refuses the garbage, so the verdict must come from the store's own verifier: the right
        // passphrase opens, and a wrong one does not. An accidental acceptance here would be a database
        // bypass, which is the failure mode the ordering exists to avoid.
        assertThat(store.verifyPassphrase("correct-horse-Battery-9"))
            .isEqualTo(DesktopPassphraseActionResult.Success)
        assertThat(store.verifyPassphrase("wrong-horse-Battery-9"))
            .isNotEqualTo(DesktopPassphraseActionResult.Success)
    }

    @Test
    fun `a database path that does not exist leaves the store's own verdict untouched`() {
        val missing = File(temporaryFolder.root, "never-created.db")
        val store = storeGuarding(missing)
        store.configurePassphrase("correct-horse-Battery-9")

        assertThat(store.verifyPassphrase("correct-horse-Battery-9"))
            .isEqualTo(DesktopPassphraseActionResult.Success)
        assertThat(store.verifyPassphrase("nope-horse-Battery-9"))
            .isNotEqualTo(DesktopPassphraseActionResult.Success)
    }

    @Test
    fun `a store with no database at all behaves exactly as before`() {
        // The default argument every other construction relies on: no file, no new branch.
        val store = DesktopSecurityStore(securityDirectory = temporaryFolder.newFolder("security-plain").toPath())
        store.configurePassphrase("correct-horse-Battery-9")

        assertThat(store.verifyPassphrase("correct-horse-Battery-9"))
            .isEqualTo(DesktopPassphraseActionResult.Success)
    }
}
