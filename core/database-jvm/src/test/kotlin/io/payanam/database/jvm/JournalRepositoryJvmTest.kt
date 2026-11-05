//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.DayJournalResponse
import io.payanam.domain.model.DayJournalResponseInput
import io.payanam.domain.model.JournalPromptScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.util.Properties

/**
 * Tests for [JournalRepositoryJvm].
 *
 * The null-safe dimension match gets its own cases: the statement names its placeholder twice, and binding
 * it once leaves the second as SQL NULL, which makes the "no dimension" branch silently unmatchable — an
 * answer that exists becomes invisible to the lookup that is supposed to find it.
 *
 * Fixture and session follow [HabitMetricRepositoryJvmTest]: a generated SQLCipher file in a per-class temp
 * directory. A real export is never used — it holds the user's personal data.
 */
class JournalRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "journal-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    private val today: LocalDate = LocalDate.now()

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("journal.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE day_journal_entries (
                    id TEXT PRIMARY KEY NOT NULL,
                    entryDate TEXT NOT NULL,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE day_journal_responses (
                    id TEXT PRIMARY KEY NOT NULL,
                    entryId TEXT NOT NULL,
                    scope TEXT NOT NULL,
                    dimensionKey TEXT,
                    dimension_id TEXT,
                    promptKey TEXT NOT NULL,
                    responseText TEXT,
                    createdAt TEXT NOT NULL,
                    updatedAt TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TABLE daily_insights (
                    id TEXT PRIMARY KEY NOT NULL,
                    day_key TEXT NOT NULL,
                    module TEXT NOT NULL,
                    dimension_id TEXT,
                    planned_minutes INTEGER,
                    actual_minutes INTEGER,
                    focused_minutes INTEGER,
                    completed_count INTEGER,
                    total_count INTEGER,
                    summary_json TEXT,
                    generated_at TEXT NOT NULL
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

    private fun repository() = JournalRepositoryJvm(session, pollIntervalMs = 10L)

    private fun responseRows(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM day_journal_responses").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private fun rawDimensionId(id: String): String? =
        connection.prepareStatement("SELECT dimension_id FROM day_journal_responses WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }

    private fun dirtyDayCount(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM daily_insights").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    // ---- entries ----

    @Test
    fun `getOrCreateEntry persists a blank entry and returns the same row next time`(): Unit =
        runBlocking {
            val created = repository().getOrCreateEntry(today)
            val again = repository().getOrCreateEntry(today)

            assertThat(again.id).isEqualTo(created.id)
            assertThat(again.entryDate).isEqualTo(today.toString())
            val rowCount =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM day_journal_entries").use { resultSet ->
                        resultSet.next()
                        resultSet.getInt(1)
                    }
                }
            assertThat(rowCount).isEqualTo(1)
        }

    @Test
    fun `observeEntry is null before the entry exists and emits it afterwards`(): Unit =
        runBlocking {
            val flow = repository().observeEntry(today)
            assertThat(flow.first()).isNull()

            val created = repository().getOrCreateEntry(today)

            val emitted = kotlinx.coroutines.withTimeout(5_000) { flow.first { it != null } }
            assertThat(emitted?.id).isEqualTo(created.id)
        }

    @Test
    fun `insertEntry uses the caller's id and does not create a second row for the same day`(): Unit =
        runBlocking {
            repository().insertEntry(
                io.payanam.domain.model.DayJournalEntry(
                    id = "caller-owned",
                    entryDate = today.toString(),
                    createdAt = "2026-09-01T08:00:00",
                    updatedAt = "2026-09-01T08:00:00",
                ),
            )

            assertThat(repository().getEntryByDate(today.toString())?.id).isEqualTo("caller-owned")
            assertThat(repository().getEntryByDate("2026-01-01")).isNull()
        }

    @Test
    fun `all entries are emitted`(): Unit =
        runBlocking {
            repository().getOrCreateEntry(today)
            repository().getOrCreateEntry(today.minusDays(1))

            assertThat(repository().getAllJournalEntries().first()).hasSize(2)
        }

    // ---- responses ----

    @Test
    fun `re-answering a prompt edits the row instead of adding one`(): Unit =
        runBlocking {
            val entry = repository().getOrCreateEntry(today)

            val first =
                repository().saveResponse(
                    entry.id,
                    DayJournalResponseInput(
                        scope = JournalPromptScope.OVERALL,
                        promptKey = "day_summary",
                        responseText = "first",
                    ),
                )
            val second =
                repository().saveResponse(
                    entry.id,
                    DayJournalResponseInput(
                        scope = JournalPromptScope.OVERALL,
                        promptKey = "day_summary",
                        responseText = "second",
                    ),
                )

            assertThat(responseRows()).isEqualTo(1)
            assertThat(second.id).isEqualTo(first.id)
            assertThat(second.responseText).isEqualTo("second")
        }

    @Test
    fun `a response with no dimension is found by a null-dimension lookup`(): Unit =
        runBlocking {
            val entry = repository().getOrCreateEntry(today)
            repository().saveResponse(
                entry.id,
                DayJournalResponseInput(
                    scope = JournalPromptScope.OVERALL,
                    promptKey = "overall_prompt",
                    responseText = "no dimension",
                ),
            )

            val found =
                repository().getResponse(entry.id, JournalPromptScope.OVERALL, null, "overall_prompt")

            // The statement's second placeholder is the same value; binding it once leaves SQL NULL there and
            // this lookup would return null for a row that exists.
            assertThat(found?.responseText).isEqualTo("no dimension")
        }

    @Test
    fun `a response with a dimension is not returned by a null-dimension lookup and vice versa`(): Unit =
        runBlocking {
            val entry = repository().getOrCreateEntry(today)
            repository().saveResponse(
                entry.id,
                DayJournalResponseInput(
                    scope = JournalPromptScope.DIMENSION,
                    dimensionKey = "dim_work_livelihood",
                    promptKey = "dimension_prompt",
                    responseText = "with dimension",
                ),
            )

            assertThat(
                repository().getResponse(entry.id, JournalPromptScope.DIMENSION, null, "dimension_prompt"),
            ).isNull()
            assertThat(
                repository()
                    .getResponse(
                        entry.id,
                        JournalPromptScope.DIMENSION,
                        "dim_work_livelihood",
                        "dimension_prompt",
                    )?.responseText,
            ).isEqualTo("with dimension")
            // A different dimension must not match it either.
            assertThat(
                repository()
                    .getResponse(
                        entry.id,
                        JournalPromptScope.DIMENSION,
                        "dim_physical_health",
                        "dimension_prompt",
                    ),
            ).isNull()
        }

    @Test
    fun `a null response text reads back as the empty string`(): Unit =
        runBlocking {
            val entry = repository().getOrCreateEntry(today)

            val saved =
                repository().saveResponse(
                    entry.id,
                    DayJournalResponseInput(
                        scope = JournalPromptScope.OVERALL,
                        promptKey = "empty",
                        responseText = null,
                    ),
                )

            assertThat(saved.responseText).isEmpty()
        }

    @Test
    fun `responses are scoped to their entry`(): Unit =
        runBlocking {
            val first = repository().getOrCreateEntry(today)
            val second = repository().getOrCreateEntry(today.minusDays(1))
            repository().saveResponse(
                first.id,
                DayJournalResponseInput(JournalPromptScope.OVERALL, null, "p1", "for first"),
            )
            repository().saveResponse(
                second.id,
                DayJournalResponseInput(JournalPromptScope.OVERALL, null, "p1", "for second"),
            )

            assertThat(repository().getResponsesByEntryId(first.id).map { it.responseText })
                .containsExactly("for first")
            assertThat(repository().getResponses(first.id).first()).hasSize(1)
        }

    @Test
    fun `dimension_id survives an edit even though no domain model carries it`(): Unit =
        runBlocking {
            val entry = repository().getOrCreateEntry(today)
            val saved =
                repository().saveResponse(
                    entry.id,
                    DayJournalResponseInput(
                        scope = JournalPromptScope.DIMENSION,
                        dimensionKey = "dim_work_livelihood",
                        promptKey = "p",
                        responseText = "v1",
                    ),
                )
            connection.prepareStatement("UPDATE day_journal_responses SET dimension_id = ? WHERE id = ?").use { statement ->
                statement.setString(1, "dim_work_livelihood")
                statement.setString(2, saved.id)
                statement.executeUpdate()
            }

            repository().saveResponse(
                entry.id,
                DayJournalResponseInput(
                    scope = JournalPromptScope.DIMENSION,
                    dimensionKey = "dim_work_livelihood",
                    promptKey = "p",
                    responseText = "v2",
                ),
            )

            // The update copies the row it read; rebuilding the entity from the input would null this column.
            assertThat(rawDimensionId(saved.id)).isEqualTo("dim_work_livelihood")
        }

    @Test
    fun `upsertResponse keeps the caller's id on insert and edits in place when the key exists`(): Unit =
        runBlocking {
            val entry = repository().getOrCreateEntry(today)

            repository().upsertResponse(
                DayJournalResponse(
                    id = "imported-1",
                    entryId = entry.id,
                    // The convention `saveResponse` and `getResponse` share is the enum's `name`. The app's
                    // own write path uses lowercase; see the casing test below.
                    scope = "OVERALL",
                    promptKey = "p",
                    responseText = "imported",
                ),
            )
            assertThat(responseRows()).isEqualTo(1)
            val storedId =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT id FROM day_journal_responses").use { resultSet ->
                        resultSet.next()
                        resultSet.getString(1)
                    }
                }
            assertThat(storedId).isEqualTo("imported-1")

            // Same natural key, different id: the existing row is edited, not duplicated.
            repository().upsertResponse(
                DayJournalResponse(
                    id = "imported-2",
                    entryId = entry.id,
                    scope = "OVERALL",
                    promptKey = "p",
                    responseText = "re-imported",
                ),
            )

            assertThat(responseRows()).isEqualTo(1)
            assertThat(repository().getResponse(entry.id, JournalPromptScope.OVERALL, null, "p")?.responseText)
                .isEqualTo("re-imported")
        }

    @Test
    fun `the scope string is compared verbatim - a lowercase row is not found by a scope-name lookup`(): Unit =
        runBlocking {
            val entry = repository().getOrCreateEntry(today)
            // The APP's write path (DayViewModel) uses lowercase scope strings; `saveResponse` and
            // `getResponse` use `JournalPromptScope.name`, which is uppercase. Nothing bridges the two, and
            // `getResponse` has no app caller -- so the mismatch is currently unreachable. Pinned here
            // because the desktop must not quietly normalise one side and start finding rows Android
            // cannot, and because it is where a future scope-based lookup would break.
            repository().upsertResponse(
                DayJournalResponse(
                    id = "lowercase",
                    entryId = entry.id,
                    scope = "overall",
                    promptKey = "p",
                    responseText = "from the app path",
                ),
            )

            assertThat(repository().getResponse(entry.id, JournalPromptScope.OVERALL, null, "p")).isNull()
            // The app's own read path does not filter on scope, which is why it works.
            assertThat(repository().getResponsesByEntryId(entry.id).map { it.responseText })
                .containsExactly("from the app path")
        }

    @Test
    fun `the total response count tracks rows`(): Unit =
        runBlocking {
            val entry = repository().getOrCreateEntry(today)
            assertThat(repository().getTotalResponseCount().first()).isEqualTo(0)

            repository().saveResponse(
                entry.id,
                DayJournalResponseInput(JournalPromptScope.OVERALL, null, "p1", "a"),
            )
            assertThat(
                kotlinx.coroutines.withTimeout(5_000) {
                    repository().getTotalResponseCount().first { it == 1 }
                },
            ).isEqualTo(1)

            repository().saveResponse(
                entry.id,
                DayJournalResponseInput(JournalPromptScope.OVERALL, null, "p2", "b"),
            )
            assertThat(
                kotlinx.coroutines.withTimeout(5_000) {
                    repository().getTotalResponseCount().first { it == 2 }
                },
            ).isEqualTo(2)

            connection.createStatement().use { statement ->
                statement.executeUpdate("DELETE FROM day_journal_responses")
            }
            assertThat(
                kotlinx.coroutines.withTimeout(5_000) {
                    repository().getTotalResponseCount().first { it == 0 }
                },
            ).isEqualTo(0)
        }

    @Test
    fun `a journal write does not mark any day lens-dirty`(): Unit =
        runBlocking {
            val entry = repository().getOrCreateEntry(today)
            repository().saveResponse(
                entry.id,
                DayJournalResponseInput(JournalPromptScope.OVERALL, null, "p", "text"),
            )

            // Android marks dirty in every other content repository and not here; a desktop that started
            // marking would invalidate lens snapshots Android leaves alone.
            assertThat(dirtyDayCount()).isEqualTo(0)
        }
}
