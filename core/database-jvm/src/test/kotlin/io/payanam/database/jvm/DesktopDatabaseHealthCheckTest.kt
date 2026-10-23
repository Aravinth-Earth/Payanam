//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.database.PayanamDatabaseSchema
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.Properties

/**
 * [DesktopDatabaseHealthCheck] over real SQLCipher files.
 *
 * The property that matters is the honest one: a check that could not open the file must NOT report it
 * healthy. A "healthy" default would let a corrupt database through the init gate, which is the exact
 * class of bug this contract exists to prevent.
 */
class DesktopDatabaseHealthCheckTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "health-check-test-passphrase"
    private val check = DesktopDatabaseHealthCheck()

    private fun encryptedFile(
        name: String,
        tables: Collection<String>,
        userVersion: Int,
    ): Path {
        val path = tempFolder.newFolder().toPath().resolve(name)
        val properties = Properties().apply { setProperty("key", passphrase) }
        DriverManager.getConnection(PayanamDatabaseOpener.URL_PREFIX + path.toAbsolutePath(), properties).use { c ->
            c.createStatement().use { statement ->
                tables.forEach { statement.execute("CREATE TABLE $it (id TEXT PRIMARY KEY)") }
                // The driver cannot write user_version (measured), so every generated fixture is version 0
                // and the version guard rejects it. That is why these tests assert the TABLE rules
                // on a file the opener's own forGeneratedFixture path is built for, and the version
                // guard is covered by classifySchemaVersion in PayanamDatabaseOpenerTest.
                check(userVersion == 0) { "fixtures are version 0 by construction" }
            }
        }
        return path
    }

    @Test
    fun `a database with every critical table is healthy`() =
        runBlocking {
            val file = encryptedFile("healthy.db", PayanamDatabaseSchema.CRITICAL_TABLES, 0)

            val report = check.check(file.toFile(), passphrase)

            assertThat(report.isHealthy).isTrue()
            assertThat(report.needsRepair).isFalse()
        }

    @Test
    fun `a database missing a critical table needs repair and says which`() =
        runBlocking {
            val partial = PayanamDatabaseSchema.CRITICAL_TABLES.toList().drop(1)
            val file = encryptedFile("partial.db", partial, 0)

            val report = check.check(file.toFile(), passphrase)

            assertThat(report.isHealthy).isFalse()
            assertThat(report.needsRepair).isTrue()
            assertThat(report.errorMessage).contains(PayanamDatabaseSchema.CRITICAL_TABLES.first())
        }

    @Test
    fun `a wrong passphrase is never reported as healthy`() =
        runBlocking {
            val file = encryptedFile("wrongkey.db", PayanamDatabaseSchema.CRITICAL_TABLES, 0)

            val report = check.check(file.toFile(), "not-the-passphrase")

            assertThat(report.isHealthy).isFalse()
        }

    @Test
    fun `no passphrase is never reported as healthy`() =
        runBlocking {
            val file = encryptedFile("nopass.db", PayanamDatabaseSchema.CRITICAL_TABLES, 0)

            assertThat(check.check(file.toFile(), null).isHealthy).isFalse()
            assertThat(check.check(file.toFile(), "").isHealthy).isFalse()
        }

    @Test
    fun `a missing file is not healthy and is not reported as an artifact`() =
        runBlocking {
            val absent = tempFolder.root.toPath().resolve("absent.db")

            val report = check.check(absent.toFile(), passphrase)

            assertThat(report.isHealthy).isFalse()
            assertThat(check.hasArtifacts(absent.toFile())).isFalse()
        }

    @Test
    fun `an existing file reports as an artifact`() =
        runBlocking {
            val file = encryptedFile("present.db", PayanamDatabaseSchema.CRITICAL_TABLES, 0)

            assertThat(check.hasArtifacts(file.toFile())).isTrue()
            assertThat(Files.isRegularFile(file)).isTrue()
        }
}
