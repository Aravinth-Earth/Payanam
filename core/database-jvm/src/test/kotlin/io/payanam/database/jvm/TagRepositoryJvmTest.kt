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
 * Tests for [TagRepositoryJvm].
 *
 * Fixture and session follow [HabitMetricRepositoryJvmTest]: a generated SQLCipher file in a per-class
 * temp directory, handed to the session directly. A real export is never used — it holds the user's
 * personal data.
 */
class TagRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "tag-repository-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("tags.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection = DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE tags (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL,
                    usage_count INTEGER NOT NULL DEFAULT 0,
                    last_used_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute("CREATE TABLE note_tags (note_id TEXT, tag_id TEXT, created_at TEXT)")
            statement.execute("CREATE TABLE task_tags (task_id TEXT, tag_id TEXT, created_at TEXT)")
            statement.execute(
                "CREATE TABLE time_entry_tags (time_entry_id TEXT, tag_id TEXT, created_at TEXT)",
            )
        }
        session = PayanamDatabaseSession(connection, 21, DatabaseOpenMode.READ_WRITE)
    }

    @After
    fun tearDown() {
        session.close()
    }

    private fun repository() = TagRepositoryJvm(session, pollIntervalMs = 10L)

    private fun countRows(table: String): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    @Test
    fun `replaceNoteTags creates the tag, links it and bumps its usage count`(): Unit =
        runBlocking {
            val repository = repository()

            repository.replaceNoteTags("note-1", listOf("Focus"))

            assertThat(countRows("tags")).isEqualTo(1)
            assertThat(countRows("note_tags")).isEqualTo(1)
            val tags = repository.observeAllTags().first()
            assertThat(tags.single().name).isEqualTo("Focus")
            assertThat(tags.single().usageCount).isEqualTo(1)
        }

    @Test
    fun `a tag is reused case-insensitively instead of duplicated`(): Unit =
        runBlocking {
            val repository = repository()

            repository.replaceNoteTags("note-1", listOf("Focus"))
            repository.replaceNoteTags("note-2", listOf("  focus  "))

            assertThat(countRows("tags")).isEqualTo(1)
            assertThat(countRows("note_tags")).isEqualTo(2)
        }

    @Test
    fun `replacing tags clears the previous set`(): Unit =
        runBlocking {
            val repository = repository()

            repository.replaceNoteTags("note-1", listOf("First"))
            repository.replaceNoteTags("note-1", listOf("Second"))

            assertThat(countRows("note_tags")).isEqualTo(1)
            val linked = repository.observeTagsForNote("note-1").first()
            assertThat(linked.map { it.name }).containsExactly("Second")
        }

    @Test
    fun `an empty replacement clears links without creating tags`(): Unit =
        runBlocking {
            val repository = repository()
            repository.replaceNoteTags("note-1", listOf("First"))

            repository.replaceNoteTags("note-1", emptyList())

            assertThat(countRows("note_tags")).isEqualTo(0)
            assertThat(countRows("tags")).isEqualTo(1)
        }

    @Test
    fun `task and time-entry tags land in their own junction tables`(): Unit =
        runBlocking {
            val repository = repository()

            repository.replaceTaskTags("task-1", listOf("Work"))
            repository.replaceTimeEntryTags("entry-1", listOf("Deep"))

            assertThat(countRows("task_tags")).isEqualTo(1)
            assertThat(countRows("time_entry_tags")).isEqualTo(1)
            assertThat(repository.observeTagsForTask("task-1").first().map { it.name })
                .containsExactly("Work")
            assertThat(repository.observeTagsForTimeEntry("entry-1").first().map { it.name })
                .containsExactly("Deep")
        }

    @Test
    fun `getTagNamesForNotes batches a lookup and returns an empty map for no ids`(): Unit =
        runBlocking {
            val repository = repository()
            repository.replaceNoteTags("note-1", listOf("Alpha", "Beta"))
            repository.replaceNoteTags("note-2", listOf("Gamma"))

            val names = repository.getTagNamesForNotes(listOf("note-1", "note-2", "note-3"))

            assertThat(names["note-1"]).containsExactly("Alpha", "Beta").inOrder()
            assertThat(names["note-2"]).containsExactly("Gamma")
            assertThat(names["note-3"]).isNull()
            assertThat(repository.getTagNamesForNotes(emptyList())).isEmpty()
        }

    @Test
    fun `searchTagsByPrefix normalizes the query and honours the limit`(): Unit =
        runBlocking {
            val repository = repository()
            repository.replaceNoteTags("note-1", listOf("Focus", "Focused", "Later"))

            val upperCaseQuery = repository.searchTagsByPrefix("FOC", limit = 10).first()

            assertThat(upperCaseQuery.map { it.name }).containsExactly("Focus", "Focused")
            assertThat(repository.searchTagsByPrefix("f", limit = 1).first()).hasSize(1)
            assertThat(repository.searchTagsByPrefix("zzz", limit = 10).first()).isEmpty()
        }

    @Test
    fun `tags are ordered by usage count then name`(): Unit =
        runBlocking {
            val repository = repository()
            repository.replaceNoteTags("note-1", listOf("Rare"))
            repeat(3) { repository.replaceNoteTags("note-2", listOf("Popular")) }

            val ordered = repository.observeAllTags().first().map { it.name }

            assertThat(ordered.first()).isEqualTo("Popular")
        }

    @Test
    fun `a subscribed read re-emits after a tag is applied`(): Unit =
        runBlocking {
            val repository = repository()
            val tags = repository.observeAllTags()

            assertThat(tags.first()).isEmpty()

            repository.replaceNoteTags("note-1", listOf("Appears"))

            val after = kotlinx.coroutines.withTimeout(5_000) { tags.first { it.isNotEmpty() } }
            assertThat(after.map { it.name }).containsExactly("Appears")
        }

    @Test
    fun `lastUsedAt is recorded when a tag is applied`(): Unit =
        runBlocking {
            val repository = repository()

            repository.replaceNoteTags("note-1", listOf("Used"))

            assertThat(
                repository
                    .observeAllTags()
                    .first()
                    .single()
                    .lastUsedAt,
            ).isNotNull()
        }
}
