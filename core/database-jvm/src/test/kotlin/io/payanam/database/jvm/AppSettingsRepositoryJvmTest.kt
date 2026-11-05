//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Tests for [AppSettingsRepositoryJvm].
 *
 * The `key` column is a SQL keyword and is quoted with backticks in every statement; a statement that lost
 * them would fail loudly, but the column NAMES are the reason these tests assert through the repository and
 * read raw rows rather than trusting the statements.
 *
 * A generated SQLCipher fixture in a per-class temp directory, never a real export.
 */
class AppSettingsRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "app-settings-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("settings.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE app_settings (
                    `key` TEXT PRIMARY KEY NOT NULL,
                    value TEXT,
                    updatedAt TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
        session = PayanamDatabaseSession(connection, 21, DatabaseOpenMode.READ_WRITE)
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun repository() = AppSettingsRepositoryJvm(session, pollIntervalMs = 10L)

    private fun rawValue(key: String): String? =
        connection.prepareStatement("SELECT value FROM app_settings WHERE `key` = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }

    private fun rawUpdatedAt(key: String): String? =
        connection.prepareStatement("SELECT updatedAt FROM app_settings WHERE `key` = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }

    private fun rowCount(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM app_settings").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    @Test
    fun `an unset key reads as null and a set one round-trips`(): Unit =
        runBlocking {
            assertThat(repository().getSetting("launch_route")).isNull()

            repository().setSetting("launch_route", "today")

            assertThat(repository().getSetting("launch_route")).isEqualTo("today")
            assertThat(rawUpdatedAt("launch_route")).isNotNull()
        }

    @Test
    fun `the observed value goes from null to the stored value`(): Unit =
        runBlocking {
            val flow = repository().observeSetting("theme")
            assertThat(flow.first()).isNull()

            repository().setSetting("theme", "dark")

            assertThat(kotlinx.coroutines.withTimeout(5_000) { flow.first { it != null } }).isEqualTo("dark")
        }

    @Test
    fun `a setting is overwritten in place rather than duplicated`(): Unit =
        runBlocking {
            repository().setSetting("theme", "dark")
            repository().setSetting("theme", "light")

            assertThat(rowCount()).isEqualTo(1)
            assertThat(repository().getSetting("theme")).isEqualTo("light")
        }

    @Test
    fun `setting a null value STORES a null row - it does not delete the key`(): Unit =
        runBlocking {
            repository().setSetting("biometric", null)

            // The Android KDoc says a null deletes the key; the code inserts the row, and the difference is
            // visible in getAllSettings().
            assertThat(rowCount()).isEqualTo(1)
            assertThat(repository().getSetting("biometric")).isNull()
            assertThat(repository().getAllSettings().first()).containsKey("biometric")
            assertThat(rawValue("biometric")).isNull()
        }

    @Test
    fun `deleteSetting removes the key`(): Unit =
        runBlocking {
            repository().setSetting("theme", "dark")

            repository().deleteSetting("theme")

            assertThat(rowCount()).isEqualTo(0)
            assertThat(repository().getSetting("theme")).isNull()
        }

    @Test
    fun `getAllSettings maps every row and re-emits after a change`(): Unit =
        runBlocking {
            repository().setSetting("a", "1")
            repository().setSetting("b", "2")

            assertThat(repository().getAllSettings().first()).containsExactly("a", "1", "b", "2")

            repository().setSetting("c", "3")

            val updated =
                kotlinx.coroutines.withTimeout(5_000) {
                    repository().getAllSettings().first { it.size == 3 }
                }
            assertThat(updated["c"]).isEqualTo("3")
        }
}
