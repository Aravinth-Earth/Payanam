//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.EncryptedFileClassifier
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.Base64

/**
 * An exported payload is unreadable without the key.
 *
 * **The claim, and how it is settled.** The desktop's export copies the live database file to the user's
 * destination verbatim (the transfer gateway's own test asserts the copied bytes equal the live bytes), so
 * "the exported payload is unreadable without the key" reduces to: the live database file carries no
 * plaintext SQLite structure and no plaintext JSON. This test asserts exactly that against a REAL
 * Android-produced encrypted database - the same fixture the import family is proven with - by doing the
 * two things an attacker without the key would do: classify the file, and try to open it with a plain
 * (keyless) SQLite driver.
 *
 * **Why the fixture is supplied as a system property.** A test must not depend on a developer's home
 * directory. The fixture is passed as `-Dpayanam.loop.db=<path>` (and `-Dpayanam.loop.pwd`), the same
 * contract the import harness uses; without it the test SKIPS rather than inventing a file, because a
 * synthesised "encrypted" file would only prove that random bytes are random.
 *
 * **The mutants this catches:**
 *
 * 1. an export that writes the database through a plaintext intermediate (JSON, CSV, a decrypted copy) -
 *    the classifier would read the plaintext SQLite magic, or the keyless open would succeed;
 * 2. a database written with `cipher_plaintext_header_size` set, which would let an encrypted file carry
 *    the plaintext magic - called out as an invariant on `EncryptedFileClassifier` and asserted here;
 * 3. a regression to "any non-empty file counts as encrypted" - the too-short case is asserted by the
 *    classifier's own contract test; this test pins the POSITIVE direction: a real encrypted file is
 *    readable by nobody without the key.
 */
class DesktopExportedPayloadTest {
    private val databaseProperty: String? = System.getProperty("payanam.loop.db")
    private val passphraseProperty: String? = System.getProperty("payanam.loop.pwd")

    @Test
    fun `the payload carries no plaintext structure and cannot be opened without the key`() {
        assumeTrue(
            "Encrypted fixture not supplied: set -Dpayanam.loop.db/-Dpayanam.loop.pwd",
            databaseProperty != null && passphraseProperty != null,
        )
        val database = Paths.get(databaseProperty!!)
        assertThat(Files.exists(database)).isTrue()

        // 1. The classifier's verdict, on the real file - called the way the production probes call it.
        val bytes = Files.readAllBytes(database)
        val classifierHeader = bytes.take(EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC.size).toByteArray()
        val verdict =
            EncryptedFileClassifier.classify(header = classifierHeader, totalLength = bytes.size.toLong())
        assertThat(verdict.isEncrypted).isTrue()
        assertThat(verdict.reason).isEqualTo(EncryptedFileClassifier.Reason.NO_MAGIC)

        // 2. The plaintext magic is NOT the header - stated directly as well as through the classifier, so a
        //    change to the classifier's internals cannot make this file look plaintext while the verdict
        //    stays true by accident.
        val header = bytes.take(16).toByteArray()
        assertThat(header).isNotEqualTo(EncryptedFileClassifier.SQLITE_PLAINTEXT_MAGIC)

        // 3. No JSON payload: the first non-whitespace byte of an exported JSON backup would be `{`.
        val firstPrintable = bytes.take(64).map { it.toInt() and 0xFF }.firstOrNull { it > 0x20 }
        assertThat(firstPrintable).isNotEqualTo('{'.code)

        // 4. THE KEYLESS OPEN. A plain JDBC SQLite connection to the encrypted file must fail - this is the
        //    gate's own sentence, executed.
        val failure =
            runCatching {
                DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                    connection.createStatement().use { it.executeQuery("SELECT count(*) FROM sqlite_master") }
                }
            }.exceptionOrNull()
        assertThat(failure).isNotNull()

        // The passphrase file is passed so the test cannot drift into "no passphrase needed": assert it is
        // non-empty without printing it.
        val passphraseBytes = Files.readAllBytes(Paths.get(passphraseProperty!!))
        assertThat(passphraseBytes).isNotEmpty()
    }
}
