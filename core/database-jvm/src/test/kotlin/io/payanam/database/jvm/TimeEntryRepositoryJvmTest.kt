//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.TimeEntryInput
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
import java.time.LocalDateTime
import java.util.Properties

/**
 * Tests for [TimeEntryRepositoryJvm].
 *
 * The expensive behaviours here are the two that a port silently loses: starting an entry must stop the
 * running one, and an edit that moves an entry between days must mark BOTH days lens-dirty. The
 * `COALESCE(endedAt, ?)` day query is covered because its placeholders are bound in a different order than
 * the Android call site names its arguments.
 *
 * Fixture and session follow [HabitMetricRepositoryJvmTest]: a generated SQLCipher file in a per-class temp
 * directory. A real export is never used — it holds the user's personal data.
 */
class TimeEntryRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "time-entry-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    private val today: LocalDate = LocalDate.now()
    private val nineAm: LocalDateTime = today.atTime(9, 0)
    private val elevenAm: LocalDateTime = today.atTime(11, 0)

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("time.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE time_entries (
                    id TEXT PRIMARY KEY NOT NULL,
                    lifeIntentionCategory TEXT NOT NULL,
                    dimension_id TEXT,
                    day_key TEXT,
                    taskId TEXT,
                    startedAt TEXT NOT NULL,
                    endedAt TEXT,
                    focusRating REAL,
                    focusNote TEXT,
                    focusRatedAt TEXT,
                    import_source TEXT,
                    import_id TEXT,
                    imported_at TEXT,
                    import_batch_id TEXT,
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
            // The junction a time-entry delete has to reach. Columns and the parent constraint are copied
            // from the exported schema; the tag-side constraint is omitted because this fixture never creates
            // a `tags` row, and the delete under test binds only the owning column.
            statement.execute(
                """
                CREATE TABLE time_entry_tags (
                    time_entry_id TEXT NOT NULL,
                    tag_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY(time_entry_id, tag_id),
                    FOREIGN KEY(time_entry_id) REFERENCES time_entries(id) ON UPDATE NO ACTION ON DELETE CASCADE
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

    private fun repository() = TimeEntryRepositoryJvm(session, pollIntervalMs = 10L)

    private fun dirtyDays(): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT day_key FROM daily_insights WHERE module = 'lens_dirty_day'").use { resultSet ->
                buildSet {
                    while (resultSet.next()) add(resultSet.getString(1))
                }
            }
        }

    private fun rawField(
        id: String,
        column: String,
    ): String? =
        connection.prepareStatement("SELECT $column FROM time_entries WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }

    private fun rowCount(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM time_entries").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    private fun input(
        startedAt: LocalDateTime = nineAm,
        endedAt: LocalDateTime? = null,
        dimensionId: String? = null,
        category: String = "Work & Livelihood",
        taskId: String? = null,
    ) = TimeEntryInput(
        lifeIntentionCategory = category,
        taskId = taskId,
        startedAt = startedAt,
        endedAt = endedAt,
        dimensionId = dimensionId,
    )

    @Test
    fun `startTimeEntry leaves a running entry and marks the start day dirty`(): Unit =
        runBlocking {
            val started = repository().startTimeEntry(input())

            assertThat(started.endedAt).isNull()
            assertThat(started.isActive).isTrue()
            assertThat(rawField(started.id, "day_key")).isEqualTo(today.toString())
            assertThat(rawField(started.id, "endedAt")).isNull()
            assertThat(dirtyDays()).containsExactly(today.toString())
        }

    @Test
    fun `starting a second entry stops the first`(): Unit =
        runBlocking {
            val first = repository().startTimeEntry(input())
            val second = repository().startTimeEntry(input(startedAt = today.atTime(10, 0)))

            val active = repository().getActiveTimeEntry()

            assertThat(rowCount()).isEqualTo(2)
            assertThat(active?.id).isEqualTo(second.id)
            assertThat(rawField(first.id, "endedAt")).isNotNull()
        }

    @Test
    fun `a plain stop records endedAt and a zero focus rating`(): Unit =
        runBlocking {
            val started = repository().startTimeEntry(input())

            val stopped = repository().stopActiveTimeEntry()

            assertThat(stopped?.id).isEqualTo(started.id)
            assertThat(stopped?.endedAt).isNotNull()
            assertThat(stopped?.focusRating).isEqualTo(0.0)
            assertThat(repository().getActiveTimeEntry()).isNull()
        }

    @Test
    fun `stopping with nothing running returns null`(): Unit =
        runBlocking {
            assertThat(repository().stopActiveTimeEntry()).isNull()
        }

    @Test
    fun `stopActiveTimeEntryWithFocus clamps the rating and trims a blank note`(): Unit =
        runBlocking {
            repository().startTimeEntry(input())

            val stopped = repository().stopActiveTimeEntryWithFocus(focusRating = 1.8, focusNote = "   ")

            assertThat(stopped?.focusRating).isEqualTo(1.0)
            assertThat(stopped?.focusNote).isNull()
        }

    @Test
    fun `stopActiveTimeEntryWithFocus keeps a real note and rating`(): Unit =
        runBlocking {
            repository().startTimeEntry(input())

            val stopped = repository().stopActiveTimeEntryWithFocus(focusRating = 0.75, focusNote = " deep ")

            assertThat(stopped?.focusRating).isEqualTo(0.75)
            assertThat(stopped?.focusNote).isEqualTo("deep")
        }

    @Test
    fun `the active entry flow emits the running entry then null after a stop`(): Unit =
        runBlocking {
            val flow = repository().observeActiveTimeEntry()
            assertThat(flow.first()).isNull()

            val started = repository().startTimeEntry(input())
            assertThat(kotlinx.coroutines.withTimeout(5_000) { flow.first { it != null } }?.id)
                .isEqualTo(started.id)

            repository().stopActiveTimeEntry()
            assertThat(kotlinx.coroutines.withTimeout(5_000) { flow.first { it == null } }).isNull()
        }

    @Test
    fun `entriesForDate includes an entry that started yesterday and ran into today`(): Unit =
        runBlocking {
            val overnight =
                repository().createTimeEntry(
                    input(startedAt = today.minusDays(1).atTime(22, 0), endedAt = today.atTime(1, 0)),
                )
            val inDay = repository().createTimeEntry(input(endedAt = elevenAm))
            repository().createTimeEntry(
                input(startedAt = today.minusDays(5).atTime(9, 0), endedAt = today.minusDays(5).atTime(10, 0)),
            )

            val forToday = repository().getTimeEntriesForDate(today).first()

            assertThat(forToday.map { it.id }).containsExactly(overnight.id, inDay.id)
        }

    @Test
    fun `entriesForDate counts a still-running entry`(): Unit =
        runBlocking {
            val running = repository().startTimeEntry(input())

            val forToday = repository().getTimeEntriesForDate(today).first()

            assertThat(forToday.map { it.id }).containsExactly(running.id)
        }

    @Test
    fun `entriesForRange is inclusive at both ends`(): Unit =
        runBlocking {
            val atStart = repository().createTimeEntry(input(startedAt = nineAm, endedAt = elevenAm))
            val later = repository().createTimeEntry(input(startedAt = today.atTime(15, 0), endedAt = today.atTime(16, 0)))
            repository().createTimeEntry(
                input(startedAt = today.minusDays(2).atTime(9, 0), endedAt = today.minusDays(2).atTime(10, 0)),
            )

            val ranged = repository().getTimeEntriesForRange(nineAm, today.atTime(15, 0)).first()

            assertThat(ranged.map { it.id }).containsExactly(atStart.id, later.id)
        }

    @Test
    fun `createTimeEntry persists an already-finished entry`(): Unit =
        runBlocking {
            val created = repository().createTimeEntry(input(endedAt = elevenAm))

            assertThat(created.endedAt).isEqualTo(elevenAm)
            assertThat(created.durationMinutes()).isEqualTo(120L)
            assertThat(repository().getActiveTimeEntry()).isNull()
        }

    @Test
    fun `updateTimeEntry moving an entry to another day marks both days dirty`(): Unit =
        runBlocking {
            val created = repository().createTimeEntry(input(endedAt = elevenAm))
            val target = today.minusDays(3)

            val updated = repository().updateTimeEntry(created.id, input(startedAt = target.atTime(9, 0), endedAt = target.atTime(10, 0)))

            assertThat(rawField(created.id, "day_key")).isEqualTo(target.toString())
            assertThat(dirtyDays()).containsExactly(today.toString(), target.toString())
        }

    @Test
    fun `updateTimeEntry on a missing id throws`(): Unit =
        runBlocking {
            val failure =
                runCatching { repository().updateTimeEntry("absent", input(endedAt = elevenAm)) }
            assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }

    @Test
    fun `updateTimeEntry keeps the stored dimension when the new input has no explicit one`(): Unit =
        runBlocking {
            val created = repository().createTimeEntry(input(endedAt = elevenAm, dimensionId = "dim_physical_health"))

            repository().updateTimeEntry(created.id, input(endedAt = elevenAm, category = "Health & Vitality"))

            assertThat(rawField(created.id, "dimension_id")).isEqualTo("dim_physical_health")
        }

    @Test
    fun `updateTimeEntry does not invent a dimension from a bare category label`(): Unit =
        runBlocking {
            val created = repository().createTimeEntry(input(endedAt = elevenAm))

            repository().updateTimeEntry(created.id, input(endedAt = elevenAm, category = "Work & Livelihood"))

            assertThat(rawField(created.id, "dimension_id")).isNull()
        }

    @Test
    fun `a blank task id is stored as null`(): Unit =
        runBlocking {
            val created = repository().startTimeEntry(input(taskId = "   "))

            assertThat(rawField(created.id, "taskId")).isNull()
        }

    @Test
    fun `updateTimeEntry from a domain object moves the day and marks it dirty`(): Unit =
        runBlocking {
            val created = repository().createTimeEntry(input(endedAt = elevenAm))
            val target = today.minusDays(2)

            repository().updateTimeEntry(created.copy(startedAt = target.atTime(7, 0), endedAt = target.atTime(8, 0)))

            assertThat(rawField(created.id, "day_key")).isEqualTo(target.toString())
            assertThat(dirtyDays()).containsExactly(today.toString(), target.toString())
        }

    @Test
    fun `deleteTimeEntry removes the row and marks its day dirty`(): Unit =
        runBlocking {
            val created = repository().createTimeEntry(input(endedAt = elevenAm))

            repository().deleteTimeEntry(created.id)

            assertThat(rowCount()).isEqualTo(0)
            assertThat(dirtyDays()).containsExactly(today.toString())
        }

    @Test
    fun `getAllTimeEntries emits newest-started first`(): Unit =
        runBlocking {
            val earlier = repository().createTimeEntry(input(startedAt = nineAm, endedAt = elevenAm))
            val later =
                repository().createTimeEntry(
                    input(startedAt = today.atTime(14, 0), endedAt = today.atTime(15, 0)),
                )

            assertThat(repository().getAllTimeEntries().first().map { it.id })
                .containsExactly(later.id, earlier.id)
                .inOrder()
        }

    @Test
    fun `getActiveTimeEntries lists only running entries`(): Unit =
        runBlocking {
            repository().createTimeEntry(input(endedAt = elevenAm))
            val running = repository().startTimeEntry(input(startedAt = today.atTime(16, 0)))

            assertThat(repository().getActiveTimeEntries().first().map { it.id })
                .containsExactly(running.id)
        }
}
