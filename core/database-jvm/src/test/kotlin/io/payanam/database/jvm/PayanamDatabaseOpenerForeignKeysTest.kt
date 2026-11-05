//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.payanam.domain.model.TaskInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.Properties

/**
 * `PRAGMA foreign_keys` on a session this app's opener produced - the pragma Room's generated delegate
 * issues for Android and a raw JDBC connection issues for nobody.
 *
 * **Why this file exists at all.** The pragma is per-connection and is never stored in the file, so no
 * amount of reading a database can tell you whether it is on: the only place the answer lives is a live
 * connection's own state. Two platforms opening the SAME file therefore behave differently, and the
 * difference is invisible from the data. Android never meets it, because Room's open callback turns the
 * pragma on for it; the JDBC driver does not, which is how a delete the schema declares
 * `ON DELETE CASCADE` came to leave its children behind in silence on Linux.
 *
 * **What makes this a measurement rather than a restatement of the fix.**
 *
 * - The database is built by the app's own create path ([PayanamDatabaseOpener.create]), and its rows are
 *   written by the app's own repositories, so the schema under test is the generated Room DDL this build
 *   ships rather than a hand-copied fixture table this test invented to be satisfied by.
 * - The pragma is READ on the connection the opener handed back, not on a connection this file made. A
 *   test that opened its own connection and then turned the pragma on would only prove that SQLite works.
 * - [a connection that never issued the pragma leaves the child behind] is the falsification control: the
 *   same file, the same rows, the same delete, on a connection that deliberately skips the pragma, and the
 *   child must SURVIVE. Without it, a passing cascade test could be explained by the schema cascading on
 *   its own rather than by the pragma.
 * - [a re-opened file enforces the pragma again] covers the "every open" half of the claim. The pragma does
 *   not persist, so a fix that turned it on at create time only would pass the first test and fail this one
 *   - which is the exact shape of the bug being guarded.
 *
 * The file is generated per test in a temp directory and never committed.
 */
class PayanamDatabaseOpenerForeignKeysTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "foreign-keys-fixture-passphrase-0Ab@"

    /** Every session this test has opened, closed in [tearDown] so a failure cannot leak a connection. */
    private val opened = mutableListOf<PayanamDatabaseSession>()

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
    }

    private fun openSession(result: DatabaseOpenResult): PayanamDatabaseSession {
        assertWithMessage("the opener refused the fixture")
            .that(result)
            .isInstanceOf(DatabaseOpenResult.Opened::class.java)
        val session = (result as DatabaseOpenResult.Opened).session
        opened += session
        return session
    }

    /** The declared enforcement state of [connection], read rather than assumed. */
    private fun foreignKeys(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_keys").use { rows ->
                assertThat(rows.next()).isTrue()
                rows.getInt(1)
            }
        }

    private fun countOccurrences(
        connection: Connection,
        taskId: String,
    ): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM task_occurrences WHERE taskId = '$taskId'").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    /** Deletes a task with ONE statement and nothing else - no child statement of any kind. */
    private fun deleteTaskDirectly(
        connection: Connection,
        taskId: String,
    ) {
        connection.prepareStatement("DELETE FROM tasks WHERE id = ?").use { statement ->
            statement.setString(1, taskId)
            statement.executeUpdate()
        }
    }

    /**
     * A task with one child occurrence, both written through the repositories the desktop actually uses.
     *
     * The repositories rather than raw `INSERT`s, so a divergence reported here is one the shipped schema
     * and the shipped write path produce - and so this fixture cannot satisfy itself by spelling a column
     * differently from the DDL.
     */
    private fun seedTaskWithOccurrence(
        session: PayanamDatabaseSession,
        title: String,
    ): String =
        runBlocking {
            val dueAt = LocalDate.of(2026, 10, 6).atTime(9, 0)
            val task = TaskRepositoryJvm(session, pollIntervalMs = 10L).createTask(TaskInput(title = title, dueDate = dueAt))
            TaskOccurrenceRepositoryJvm(session, pollIntervalMs = 10L)
                .recordOccurrence(taskId = task.id, dueDate = dueAt, status = "completed", note = "done", completionRate = 1.0)
            task.id
        }

    /** Builds a real database through the app's create path and returns its path. */
    private fun createFixture(name: String): Path {
        val path = tempFolder.newFolder().toPath().resolve(name)
        openSession(PayanamDatabaseOpener.create(path, passphrase)).close()
        return path
    }

    /** A connection opened the way the opener opens one, but WITHOUT the pragma - the control's engine. */
    private fun openWithoutPragma(path: Path): Connection {
        Class.forName("io.github.s0d3s.sqlcipher_multiplatform_jdbc.SqlCipherDriver")
        return DriverManager.getConnection(
            "${PayanamDatabaseOpener.URL_PREFIX}${path.toAbsolutePath()}",
            Properties().apply { setProperty(PayanamDatabaseOpener.KEY_PROPERTY, passphrase) },
        )
    }

    @Test
    fun `the created session enforces foreign keys`() {
        val path = tempFolder.newFolder().toPath().resolve("created.db")
        val session = openSession(PayanamDatabaseOpener.create(path, passphrase))
        assertWithMessage("the opener's own create path must hand back a session that enforces foreign keys")
            .that(foreignKeys(session.requireConnection()))
            .isEqualTo(1)
    }

    @Test
    fun `a re-opened file enforces the pragma again`() {
        val path = createFixture("reopened.db")
        val session = openSession(PayanamDatabaseOpener.open(path, passphrase))
        assertWithMessage("the pragma does not persist in the file, so every open must re-issue it")
            .that(foreignKeys(session.requireConnection()))
            .isEqualTo(1)
    }

    @Test
    fun `a read-only session enforces the pragma too`() {
        val path = createFixture("read-only.db")
        val session = openSession(PayanamDatabaseOpener.open(path, passphrase, DatabaseOpenMode.READ_ONLY))
        assertWithMessage("read-only changes what a session may write, not whether its keys are enforced")
            .that(foreignKeys(session.requireConnection()))
            .isEqualTo(1)
    }

    @Test
    fun `a delete cascades on the opened session without any hand-written child statement`() {
        val path = createFixture("cascade.db")
        val session = openSession(PayanamDatabaseOpener.open(path, passphrase))
        val connection = session.requireConnection()
        val taskId = seedTaskWithOccurrence(session, "cascades on delete")
        assertThat(countOccurrences(connection, taskId)).isEqualTo(1)

        // ONE statement, and it names no child table. Nothing else can delete the occurrence: the
        // schema's declared ON DELETE CASCADE has to, and it can only do so while the pragma is on.
        deleteTaskDirectly(connection, taskId)

        assertWithMessage("the declared ON DELETE CASCADE did not fire, so the child survived its parent")
            .that(countOccurrences(connection, taskId))
            .isEqualTo(0)
        assertWithMessage("a surviving child is a real orphan, which foreign_key_check must also see")
            .that(DatabaseForeignKeyCheck.violations(connection))
            .isEmpty()
    }

    /**
     * The falsification control.
     *
     * Same file, same rows, same parent delete - the only difference is that this connection never issued
     * the pragma. The child MUST survive here. If it did not, the cascade test above would be proving
     * something the schema does on its own, and this whole file would restate a fact instead of measuring
     * the fix.
     */
    @Test
    fun `a connection that never issued the pragma leaves the child behind`() {
        val path = createFixture("control.db")
        val taskId =
            openSession(PayanamDatabaseOpener.open(path, passphrase)).let { session ->
                seedTaskWithOccurrence(session, "survives an unenforced delete").also { session.close() }
            }

        openWithoutPragma(path).use { connection ->
            assertWithMessage("the control is only a control while the pragma is off")
                .that(foreignKeys(connection))
                .isEqualTo(0)

            deleteTaskDirectly(connection, taskId)

            assertWithMessage("with the pragma off the child survives - which is the defect this file guards")
                .that(countOccurrences(connection, taskId))
                .isEqualTo(1)
        }
    }
}
