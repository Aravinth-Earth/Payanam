//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.port.DatabaseRekey
import io.payanam.shared.database.DatabaseRekeySequence
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/**
 * [DesktopDatabaseEncryptionProbe]'s classification, which is the answer the import path branches on.
 *
 * The property that matters most: a PLAINTEXT database must never be reported as encrypted, because the
 * caller reads "encrypted" as "ask the user for a passphrase" — and a user with a plaintext backup has no
 * passphrase to give. Android gets this right with Room; the desktop has to earn it over JDBC.
 */
class DesktopDatabaseEncryptionProbeTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "encryption-probe-test-passphrase"
    private val probe = DesktopDatabaseEncryptionProbe()

    /**
     * A PLAINTEXT file, made the way the sibling tests make one: the SQLCipher driver with NO key.
     * There is no plain `jdbc:sqlite` driver in this repo - grep finds exactly one use, and it was
     * this one.
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

    private fun encryptedFile(name: String): Path {
        val path = tempFolder.newFolder().toPath().resolve(name)
        val properties = Properties().apply { setProperty("key", passphrase) }
        DriverManager.getConnection(PayanamDatabaseOpener.URL_PREFIX + path.toAbsolutePath(), properties).use { c ->
            c.createStatement().use { it.execute("CREATE TABLE tasks (id TEXT PRIMARY KEY)") }
        }
        return path
    }

    @Test
    fun `a plaintext database is not reported as encrypted`() =
        runBlocking {
            val file = plaintextFile("plain.db").toFile()

            assertThat(probe.isDetectablyEncrypted(file)).isFalse()
        }

    @Test
    fun `an encrypted database is reported as encrypted`() =
        runBlocking {
            val file = encryptedFile("enc.db").toFile()

            assertThat(probe.isDetectablyEncrypted(file)).isTrue()
        }

    @Test
    fun `a missing file is not encrypted`() =
        runBlocking {
            val absent =
                tempFolder.root
                    .toPath()
                    .resolve("absent.db")
                    .toFile()

            assertThat(probe.isDetectablyEncrypted(absent)).isFalse()
        }

    @Test
    fun `the right passphrase opens an encrypted file and a wrong one does not`() =
        runBlocking {
            val file = encryptedFile("pair.db").toFile()

            assertThat(probe.canOpen(file, passphrase)).isTrue()
            assertThat(probe.canOpen(file, "wrong-passphrase")).isFalse()
        }

    @Test
    fun `re-encryption refuses rather than claiming success`() =
        runBlocking {
            val file = plaintextFile("convert.db").toFile()

            val failure = runCatching { probe.ensureEncrypted(file, passphrase) }.exceptionOrNull()

            // The dangerous outcome would be a silent no-op: the caller would then compare counts across a
            // conversion that never happened and report success on a plaintext file.
            assertThat(failure).isNotNull()
            assertThat(file.length()).isGreaterThan(0L)
        }

    @Test
    fun `the rekey port changes the key and the old passphrase stops working`() {
        val file = encryptedFile("rekey-port.db")
        val newPassphrase = "rekey-port-new-passphrase"
        val rekey: DatabaseRekey = probe

        val outcome = rekey.rekeyEncryptedDatabase(file.toFile(), passphrase.toCharArray(), newPassphrase.toCharArray(), "test")

        // The gate this port exists to guarantee: the new key works AND the old one is refused. Reporting
        // only the first would leave a user believing a passphrase changed when the file still opens with
        // the one they just retired.
        assertThat(outcome).isEqualTo(DatabaseRekeySequence.Outcome.REKEYED)
        assertThat(probe.canOpen(file.toFile(), newPassphrase, "test")).isTrue()
        assertThat(probe.canOpen(file.toFile(), passphrase, "test")).isFalse()
    }

    @Test
    fun `a rekey to the same passphrase is reported without touching the file`() {
        val file = encryptedFile("rekey-noop.db")
        val before = Files.size(file)
        val rekey: DatabaseRekey = probe

        val outcome = rekey.rekeyEncryptedDatabase(file.toFile(), passphrase.toCharArray(), passphrase.toCharArray(), "test")

        // Not REKEYED: nothing was issued, and a caller may only report success for REKEYED.
        assertThat(outcome).isEqualTo(DatabaseRekeySequence.Outcome.ALREADY_ON_TARGET_PASSPHRASE)
        assertThat(Files.size(file)).isEqualTo(before)
        assertThat(probe.canOpen(file.toFile(), passphrase, "test")).isTrue()
    }

    @Test
    fun `a rekey refuses rather than claiming success when the current passphrase is wrong`() {
        val file = encryptedFile("rekey-wrong-current.db")
        val rekey: DatabaseRekey = probe

        val thrown = runCatching {
            rekey.rekeyEncryptedDatabase(file.toFile(), "not-the-passphrase".toCharArray(), "whatever-new".toCharArray(), "test")
        }.exceptionOrNull()

        // The refusal must leave the file on its ORIGINAL key: a rekey attempted with the wrong current
        // passphrase is the case that would otherwise lock the user out of their own database.
        assertThat(thrown).isNotNull()
        assertThat(probe.canOpen(file.toFile(), passphrase, "test")).isTrue()
    }

    @Test
    fun `counts are read from a file the passphrase opens`() =
        runBlocking {
            val file = encryptedFile("counts.db").toFile()

            val counts = probe.readTableCounts(file, passphrase, listOf("tasks", "notes"))

            assertThat(counts).containsKey("tasks")
            assertThat(counts["tasks"]).isEqualTo(0)
            // A table that does not exist reports 0 rather than throwing, so a partial file is a refusal the
            // caller reports instead of a crash mid-import.
            assertThat(counts["notes"]).isEqualTo(0)
        }
}
