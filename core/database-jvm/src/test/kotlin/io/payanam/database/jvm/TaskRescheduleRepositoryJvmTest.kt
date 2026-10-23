//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.jvm

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.TaskReschedule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDateTime
import java.util.Properties

/**
 * Tests for [TaskRescheduleRepositoryJvm].
 *
 * Small surface, but the rows are evidence: nothing else records that a task's due date moved, so the
 * `wasOverdue` flag and the ordering are asserted rather than assumed.
 *
 * Fixture and session follow [HabitMetricRepositoryJvmTest]: a generated SQLCipher file in a per-class temp
 * directory. A real export is never used — it holds the user's personal data.
 */
class TaskRescheduleRepositoryJvmTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val passphrase = "task-reschedule-test-passphrase"
    private lateinit var connection: Connection
    private lateinit var session: PayanamDatabaseSession

    private val monday: LocalDateTime = LocalDateTime.of(2026, 9, 14, 9, 0)
    private val tuesday: LocalDateTime = LocalDateTime.of(2026, 9, 15, 9, 0)

    @Before
    fun setUp() {
        val path = tempFolder.newFolder().toPath().resolve("reschedules.db")
        val properties = Properties().apply { setProperty("key", passphrase) }
        connection =
            DriverManager.getConnection("jdbc:sqlcipher:${path.toAbsolutePath()}", properties)
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TABLE task_reschedules (
                    id TEXT PRIMARY KEY NOT NULL,
                    taskId TEXT NOT NULL,
                    previousDueDate TEXT NOT NULL,
                    newDueDate TEXT NOT NULL,
                    rescheduledAt TEXT NOT NULL,
                    wasOverdue INTEGER NOT NULL DEFAULT 0
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

    private fun repository() = TaskRescheduleRepositoryJvm(session, pollIntervalMs = 10L)

    private fun rawOverdueFlag(id: String): Int =
        connection.prepareStatement("SELECT wasOverdue FROM task_reschedules WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }

    @Test
    fun `recording a reschedule persists it and reads back`(): Unit =
        runBlocking {
            val recorded =
                repository().recordReschedule(
                    taskId = "task-1",
                    previousDueDate = monday,
                    newDueDate = tuesday,
                    wasOverdue = false,
                )

            val history = repository().getReschedulesByTaskId("task-1")

            assertThat(history).hasSize(1)
            assertThat(history.single().id).isEqualTo(recorded.id)
            assertThat(history.single().previousDueDate).isEqualTo(monday)
            assertThat(history.single().newDueDate).isEqualTo(tuesday)
            assertThat(history.single().wasOverdue).isFalse()
            assertThat(rawOverdueFlag(recorded.id)).isEqualTo(0)
        }

    @Test
    fun `the overdue flag round-trips as an integer one`(): Unit =
        runBlocking {
            val recorded =
                repository().recordReschedule(
                    taskId = "task-1",
                    previousDueDate = monday,
                    newDueDate = tuesday,
                    wasOverdue = true,
                )

            assertThat(rawOverdueFlag(recorded.id)).isEqualTo(1)
            assertThat(repository().getReschedulesByTaskId("task-1").single().wasOverdue).isTrue()
        }

    @Test
    fun `history comes back newest-first`(): Unit =
        runBlocking {
            repository().recordReschedule("task-1", monday, tuesday, wasOverdue = false)
            repository().recordReschedule("task-1", tuesday, tuesday.plusDays(1), wasOverdue = true)

            val history = repository().getReschedulesByTaskId("task-1")

            assertThat(history).hasSize(2)
            assertThat(history.first().previousDueDate).isEqualTo(tuesday)
        }

    @Test
    fun `a domain record with a caller-owned id is persisted as given`(): Unit =
        runBlocking {
            repository().recordReschedule(
                TaskReschedule(
                    id = "caller-owned",
                    taskId = "task-9",
                    previousDueDate = monday,
                    newDueDate = tuesday,
                    rescheduledAt = monday.plusHours(1),
                    wasOverdue = true,
                ),
            )

            val history = repository().getReschedulesByTaskId("task-9")

            assertThat(history.single().id).isEqualTo("caller-owned")
            assertThat(history.single().rescheduledAt).isEqualTo(monday.plusHours(1))
        }

    @Test
    fun `history is scoped to one task`(): Unit =
        runBlocking {
            repository().recordReschedule("task-1", monday, tuesday, wasOverdue = false)
            repository().recordReschedule("task-2", monday, tuesday, wasOverdue = false)

            assertThat(repository().getReschedulesByTaskId("task-1")).hasSize(1)
            assertThat(repository().getReschedulesForTask("task-2").first()).hasSize(1)
        }

    @Test
    fun `an unknown task has no history`(): Unit =
        runBlocking {
            assertThat(repository().getReschedulesByTaskId("absent")).isEmpty()
        }

    @Test
    fun `a subscribed read re-emits after a reschedule is recorded`(): Unit =
        runBlocking {
            val flow = repository().getReschedulesForTask("task-1")
            assertThat(flow.first()).isEmpty()

            repository().recordReschedule("task-1", monday, tuesday, wasOverdue = false)

            val after = kotlinx.coroutines.withTimeout(5_000) { flow.first { it.isNotEmpty() } }
            assertThat(after).hasSize(1)
        }
}
