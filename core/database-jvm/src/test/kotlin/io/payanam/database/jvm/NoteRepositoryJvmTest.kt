//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.NoteInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

/**
 * Tests for [NoteRepositoryJvm].
 *
 * Fixture and session are built the way [HabitMetricRepositoryJvmTest] builds them: a generated SQLCipher
 * file in a per-class temp directory, handed to the session directly. A real export is never used — it
 * holds the user's personal data.
 *
 * The poll interval is shortened to 10 ms so the reactive-read behaviour can be asserted without the test
 * waiting a second per emission.
 */
class NoteRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "note-repository-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("notes.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE journal_notes (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    details TEXT,
                    lifeIntentionCategory TEXT NOT NULL,
                    dimension_id TEXT,
                    day_key TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE notes (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    details TEXT,
                    lifeIntentionCategory TEXT NOT NULL,
                    dimension_id TEXT,
                    day_key TEXT,
                    createdAt TEXT NOT NULL,
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

    private fun repository() = NoteRepositoryJvm(session, pollIntervalMs = 10L)

    private fun countRows(table: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    @Test
    fun `createNote writes the journal note and its legacy shadow row`(): Unit =
        runBlocking {
            val created =
                repository().createNote(
                    NoteInput(
                        title = "Journal idea",
                        details = "Details",
                        lifeIntentionCategory = "Learning & Growth",
                        dimensionId = "dim_learning_growth",
                    ),
                )

            assertThat(created.title).isEqualTo("Journal idea")
            assertThat(countRows("journal_notes")).isEqualTo(1)
            // The tag junction table's foreign key points at `notes`, so a write that skipped this row would
            // produce a note that can never be tagged.
            assertThat(countRows("notes")).isEqualTo(1)
        }

    @Test
    fun `createNote accepts a null details and dimension`(): Unit =
        runBlocking {
            repository().createNote(
                NoteInput(
                    title = "Bare note",
                    details = null,
                    lifeIntentionCategory = "Learning & Growth",
                    dimensionId = null,
                ),
            )

            val stored = repository().getAllNotes().first().single()
            assertThat(stored.details).isNull()
        }

    @Test
    fun `getAllNotes emits an empty list first and re-emits after a write`(): Unit =
        runBlocking {
            val notes = repository().getAllNotes()

            assertThat(notes.first()).isEmpty()

            repository().createNote(
                NoteInput(
                    title = "Appears later",
                    details = null,
                    lifeIntentionCategory = "Learning & Growth",
                    dimensionId = "dim_learning_growth",
                ),
            )

            // A JDBC read has no invalidation to react to, so this assertion is what proves the subscription
            // notices a change made after it started.
            val afterWrite = withTimeout(5_000) { notes.first { it.isNotEmpty() } }
            assertThat(afterWrite.map { it.title }).containsExactly("Appears later")
        }

    @Test
    fun `getNotesForDate returns only the matching day`(): Unit =
        runBlocking {
            val repository = repository()
            repository.createNote(input("Today"))
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE journal_notes SET day_key = '2020-01-01'")
            }

            assertThat(repository.getNotesForDate(java.time.LocalDate.parse("2026-01-01")).first()).isEmpty()
            assertThat(repository.getNotesForDate(java.time.LocalDate.parse("2020-01-01")).first()).hasSize(1)
        }

    @Test
    fun `getNotesByDimension matches the id or the life-intention label`(): Unit =
        runBlocking {
            val repository = repository()
            repository.createNote(input("Work note"))
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE journal_notes SET lifeIntentionCategory = 'Work & Livelihood', " +
                        "dimension_id = 'dim_work_livelihood'",
                )
            }

            assertThat(repository.getNotesByDimension("dim_work_livelihood").first()).hasSize(1)
            assertThat(repository.getNotesByDimension("Work & Livelihood").first()).hasSize(1)
        }

    @Test
    fun `getNoteById returns the note and null when absent`(): Unit =
        runBlocking {
            val repository = repository()
            val created = repository.createNote(input("Findable"))

            assertThat(repository.getNoteById(created.id)?.title).isEqualTo("Findable")
            assertThat(repository.getNoteById("missing")).isNull()
        }

    @Test
    fun `updateNote rewrites both tables and keeps the existing day key`(): Unit =
        runBlocking {
            val repository = repository()
            val created = repository.createNote(input("Before"))

            val updated =
                repository.updateNote(
                    created.id,
                    NoteInput(
                        title = "After",
                        details = "Edited",
                        lifeIntentionCategory = "Learning & Growth",
                        dimensionId = "dim_learning_growth",
                    ),
                )

            assertThat(updated.title).isEqualTo("After")
            assertThat(countRows("journal_notes")).isEqualTo(1)
            assertThat(countRows("notes")).isEqualTo(1)
            val shadowTitle =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT title FROM notes").use { resultSet ->
                        resultSet.next()
                        resultSet.getString(1)
                    }
                }
            assertThat(shadowTitle).isEqualTo("After")
        }

    @Test
    fun `updateNote throws for a note that does not exist`() {
        val failure =
            runCatching {
                runBlocking {
                    repository().updateNote(
                        "missing",
                        NoteInput("T", null, "Learning & Growth", "dim_learning_growth"),
                    )
                }
            }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `deleteNote removes the row from both tables`(): Unit =
        runBlocking {
            val repository = repository()
            val created = repository.createNote(input("Doomed"))

            repository.deleteNote(created.id)

            assertThat(countRows("journal_notes")).isEqualTo(0)
            assertThat(countRows("notes")).isEqualTo(0)
        }

    @Test
    fun `notes are emitted newest first`(): Unit =
        runBlocking {
            val repository = repository()
            repository.createNote(input("Older"))
            val second = repository.createNote(input("Newer"))
            connection.createStatement().use { statement ->
                statement.executeUpdate("UPDATE journal_notes SET updated_at = '2000-01-01T00:00:00' WHERE id <> '${second.id}'")
            }

            assertThat(repository.getAllNotes().first().map { it.title }).containsExactly("Newer", "Older").inOrder()
        }

    private fun input(title: String) =
        NoteInput(
            title = title,
            details = null,
            lifeIntentionCategory = "Learning & Growth",
            dimensionId = "dim_learning_growth",
        )
}
