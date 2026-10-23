//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.repository.AppSettingsRepository
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
 * The database-init-completed flag is the ONE row Android writes through raw SQLCipher and reads back
 * through raw SQLite, bypassing the repository that owns the table. These tests exist to prove the shared
 * [AppSettingsRepository] accessors write and read EXACTLY that row, so a promoted shared helper can
 * replace the two raw call sites without moving the storage location.
 *
 * The danger is specific and silent: a first-run desktop write that lands on a different key, a different
 * value spelling, or a second row leaves Android's next launch still reading `false` - or reads a flag the
 * user never set. Neither failure is loud, so each test asserts through RAW SQL against the row on disk
 * rather than only through the repository, and one test seeds the row the way Android's own raw writer
 * does and reads it back through the shared accessor.
 *
 * A generated SQLCipher fixture in a per-class temp directory, never a real export.
 */
class DatabaseInitCompletedFlagJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "db-init-flag-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("init-flag.db")
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

    /** Reads the flag row the way the raw SQLite site did: one row, one column, the value as a string. */
    private fun rawFlagValue(): String? =
        connection.prepareStatement("SELECT value FROM app_settings WHERE key = ? LIMIT 1").use { statement ->
            statement.setString(1, AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY)
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
    fun `the key is the one Android already stores`() {
        assertThat(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY).isEqualTo("database_init_completed")
    }

    @Test
    fun `the value is the string true Android already stores, not 1`() {
        assertThat(AppSettingsRepository.INIT_COMPLETED_VALUE).isEqualTo("true")
    }

    @Test
    fun `markDatabaseInitCompleted writes the row Android's raw SQL writes`() =
        runBlocking {
            repository().markDatabaseInitCompleted()

            // The raw column read is the whole point: a shared accessor that parsed its own boolean and
            // re-serialized it would pass a repository-only assertion and break Android's reader.
            assertThat(rawFlagValue()).isEqualTo("true")
            assertThat(rowCount()).isEqualTo(1)
        }

    @Test
    fun `a row written by Android's raw SQL is read back as completed`() =
        runBlocking {
            // Byte-for-byte the statement and arguments from
            // DatabaseEncryptionMigrationSupport.markDatabaseInitCompleted.
            connection.prepareStatement(
                "INSERT OR REPLACE INTO app_settings(`key`, value, updatedAt) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, "database_init_completed")
                statement.setString(2, "true")
                statement.setString(3, "2026-09-28T01:34:18")
                statement.executeUpdate()
            }

            assertThat(repository().isDatabaseInitCompleted()).isTrue()
        }

    @Test
    fun `a fresh database with no row reads as NOT completed`() =
        runBlocking {
            assertThat(repository().isDatabaseInitCompleted()).isFalse()
        }

    @Test
    fun `a null or unparseable value reads as NOT completed rather than throwing`() =
        runBlocking {
            repository().setSetting(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY, null)
            assertThat(repository().isDatabaseInitCompleted()).isFalse()

            repository().setSetting(AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY, "maybe")
            assertThat(repository().isDatabaseInitCompleted()).isFalse()
        }

    @Test
    fun `marking twice is one row, not two - a second launch cannot duplicate the flag`() =
        runBlocking {
            repository().markDatabaseInitCompleted()
            repository().markDatabaseInitCompleted()

            assertThat(rowCount()).isEqualTo(1)
            assertThat(rawFlagValue()).isEqualTo("true")
        }

    @Test
    fun `the written row carries a stamped updatedAt, as Android's raw writer supplies`() =
        runBlocking {
            repository().markDatabaseInitCompleted()

            val updatedAt =
                connection.prepareStatement("SELECT updatedAt FROM app_settings WHERE `key` = ?").use { statement ->
                    statement.setString(1, AppSettingsRepository.DATABASE_INIT_COMPLETED_KEY)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) resultSet.getString(1) else null
                    }
                }
            assertThat(updatedAt).isNotNull()
            assertThat(updatedAt).isNotEmpty()
        }
}
