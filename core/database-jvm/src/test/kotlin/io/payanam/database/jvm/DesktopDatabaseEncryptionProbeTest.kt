//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
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
