//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/**
 * The probe answers for a file that is NOT THERE — the third shape `DesktopDatabaseEncryptionProbeTest`
 * leaves out.
 *
 * That file answers "which shape is this file", with the present-file cases covered: a plaintext database,
 * an encrypted one, a missing one under `isDetectablyEncrypted`, and the passphrase pair under `canOpen`.
 * The case neither file reaches is the one with the sharpest consequence: **`ensureEncrypted` against a
 * missing file must fail for a different reason than a refusal does.**
 *
 * WHY THAT DISTINCTION IS THE PROPERTY: this platform refuses to convert a plaintext file, and it says so
 * by throwing. If it also swallowed a request for a file that does not exist, then a caller whose copy step
 * silently produced nothing would get the SAME exception as one that legitimately hit an unsupported
 * conversion — and would report "this platform cannot encrypt databases" when the truth is that there was
 * no database. So the sequence's first step (`requirePresent`) has to win before the refusal is reached.
 * That is why the two are asserted separately, and why the message is checked for the name it carries.
 *
 * `canOpen` gets the same treatment for the same reason: a missing file must be `false`, not an exception,
 * because the import path calls it on a path it may have just failed to populate.
 */
class DesktopDatabaseEncryptionProbeAbsentFileTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "absent-file-probe-passphrase"
    private val probe = DesktopDatabaseEncryptionProbe()

    /** A path inside the temp tree that is never created. */
    private fun absent(name: String) = tempFolder.newFolder().toPath().resolve(name).toFile()

    /**
     * A PLAINTEXT file, made the way the sibling test makes one: the SQLCipher driver with NO key. There is
     * no plain `jdbc:sqlite` driver in this repo, so the driver's own plaintext mode is the honest source
     * of a file that genuinely carries the SQLite magic.
     */
    private fun plaintextFile(name: String): Path {
        val path = tempFolder.newFolder().toPath().resolve(name)
        DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE tasks (id TEXT PRIMARY KEY)")
                statement.execute("INSERT INTO tasks VALUES ('t1')")
            }
        }
        return path
    }

    /** An ENCRYPTED file under [passphrase], made the way the sibling test makes one. */
    private fun encryptedFile(name: String): Path {
        val path = tempFolder.newFolder().toPath().resolve(name)
        val properties = Properties().apply { setProperty("key", passphrase) }
        DriverManager.getConnection(PayanamDatabaseOpener.URL_PREFIX + path.toAbsolutePath(), properties).use { c ->
            c.createStatement().use { it.execute("CREATE TABLE tasks (id TEXT PRIMARY KEY)") }
        }
        return path
    }

    @Test
    fun `a passphrase cannot open a file that is not there`() {
        // False, never an exception: the import path calls `canOpen` on a path its own copy step may have
        // failed to populate, and a throw there is a crash mid-import rather than a refusal.
        assertThat(probe.canOpen(absent("never-created.db"), passphrase)).isFalse()
    }

    @Test
    fun `a wrong passphrase and a missing file are the same answer`() {
        // Both shapes are asserted individually, against the encrypted file whose wrong-passphrase answer the
        // sibling test already pins. Deliberately NOT a comparison of two inferred values: an equality
        // assertion between two answers this test has not itself measured would fail opaquely if either
        // side moved, and would hide which one did.
        val present = encryptedFile("present-encrypted.db").toFile()

        assertThat(probe.canOpen(absent("also-absent.db"), passphrase)).isFalse()
        assertThat(probe.canOpen(present, "not-the-passphrase")).isFalse()
    }

    @Test
    fun `a re-encryption request for a missing file fails as missing, not as an unsupported conversion`() {
        val missing = absent("absent-rekey.db")

        val thrown = runCatching { probe.ensureEncrypted(missing, passphrase) }.exceptionOrNull()

        // IllegalStateException from the sequence's first step, NOT the UnsupportedOperationException the
        // unsupported-platform refusal throws. A caller that catches UnsupportedOperationException to say
        // "this platform cannot convert databases" must not be handed a request for a file that is not
        // there — that would report a platform limitation where the actual fault is a failed copy.
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown).isNotInstanceOf(UnsupportedOperationException::class.java)
        // The message names the file, which is what makes the failure attributable instead of a bare
        // "does not exist" with no indication of which path the import was working on.
        assertThat(thrown).hasMessageThat().contains(missing.name)
    }

    @Test
    fun `a file that is present but not convertible fails as a refusal, so the two stay distinguishable`() {
        val present = plaintextFile("present-refusal.db").toFile()

        val thrown = runCatching { probe.ensureEncrypted(present, passphrase) }.exceptionOrNull()

        // The sibling of the case above, kept in the same file for the same reason: a caller branches on
        // this exception type, so both halves of the branch are asserted or neither is.
        assertThat(thrown).isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(thrown).isNotInstanceOf(IllegalStateException::class.java)
        // And the refusal left the file alone — the dangerous outcome would be a silent no-op, because the
        // import then compares row counts across a conversion that never happened and reports success on a
        // plaintext file.
        assertThat(present.length()).isGreaterThan(0L)
    }
}