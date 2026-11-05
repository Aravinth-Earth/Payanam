//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * `PRAGMA foreign_key_check` over a real encrypted database, on a connection that never enforces
 * foreign keys - the state a desktop-written file is actually in.
 *
 * **Why a real file.** The claim under test is about what a user's database holds, so a fake connection
 * would prove nothing: the pragma's whole job is to read the file's rows. The fixture therefore builds a
 * parent and a child table, orphans the child with a plain DELETE, and asks.
 *
 * The driver is loaded by name here for the same reason [PayanamDatabaseOpener] loads it: the native
 * library is extracted on first use, and this fixture must not depend on a service-loader entry other
 * tests happen to trigger first.
 */
class DatabaseForeignKeyCheckTest {
    private lateinit var directory: File
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        Class.forName("io.github.s0d3s.sqlcipher_multiplatform_jdbc.SqlCipherDriver")
        directory = Files.createTempDirectory("payanam-foreign-key-check").toFile()
        databaseFile = File(directory, "payanam.db")
        openConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE tasks (id TEXT PRIMARY KEY, title TEXT NOT NULL)")
                statement.executeUpdate(
                    "CREATE TABLE task_occurrences (id INTEGER PRIMARY KEY, task_id TEXT NOT NULL, " +
                        "occurred_on TEXT NOT NULL, FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE)",
                )
                statement.executeUpdate("INSERT INTO tasks (id, title) VALUES ('t1', 'kept')")
                statement.executeUpdate(
                    "INSERT INTO task_occurrences (task_id, occurred_on) VALUES ('t1', '2026-10-01')",
                )
            }
        }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a database with every child row intact reports no orphans`() {
        openConnection().use { connection ->
            val found = DatabaseForeignKeyCheck.violations(connection)

            assertThat(found).isEmpty()
        }
    }

    @Test
    fun `a child row left behind by a delete names the child and the missing parent`() {
        // The state an unenforced delete produces: the parent row is gone, the child row survives. Nothing
        // here enables foreign keys, which is the whole point - the file was written that way.
        openConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM tasks WHERE id = 't1'")
            }
        }

        openConnection().use { connection ->
            val found = DatabaseForeignKeyCheck.violations(connection)

            assertThat(found).hasSize(1)
            assertThat(found.single().table).isEqualTo("task_occurrences")
            assertThat(found.single().parent).isEqualTo("tasks")
            assertThat(DatabaseForeignKeyCheck.message(found)).isEqualTo("task_occurrences -> tasks (1)")
        }
    }

    @Test
    fun `the check reads the rows as they are now, so removing the orphan clears it`() {
        // A check that reported what it found the first time would pass the two tests above while being
        // useless: a repair, an import and a re-scan are all the same call. This one re-reads, twice.
        openConnection().use { connection ->
            assertThat(DatabaseForeignKeyCheck.violations(connection)).isEmpty()
            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM tasks WHERE id = 't1'")
            }
            assertThat(DatabaseForeignKeyCheck.violations(connection)).hasSize(1)

            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM task_occurrences")
            }
            assertThat(DatabaseForeignKeyCheck.violations(connection)).isEmpty()
        }
    }

    private fun openConnection(): Connection =
        DriverManager.getConnection(
            PayanamDatabaseOpener.URL_PREFIX + databaseFile.absolutePath,
            Properties().apply { setProperty(PayanamDatabaseOpener.KEY_PROPERTY, PASSPHRASE) },
        )

    private companion object {
        const val PASSPHRASE = "foreign-key-check-fixture-passphrase"
    }
}
