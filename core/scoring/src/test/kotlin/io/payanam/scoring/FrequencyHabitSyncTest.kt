//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * [FrequencyHabitSync] — the frequency-window half split out of [RecurrenceManager].
 *
 * A frequency habit ("3 times per 7 days") is RESYNCED to its window boundary rather than advanced
 * occurrence by occurrence, so it has its own class and its own tests. What matters to a user here is that
 * the next reminder lands on the right day: too early and they are nagged for a window they have already
 * satisfied, too late and they miss a day they have not.
 */
class FrequencyHabitSyncTest {
    private val today: LocalDate = LocalDate.now()

    /**
     * A frequency-habit fixture. [recurrenceEnabled] is settable separately from [rule] because the real
     * database can hold the two in disagreement, and the classifier has to answer from the flag first.
     */
    /**
     * Every fixture here anchors its due date at the START of today, never at a wall-clock hour.
     *
     * The window maths compares INSTANTS (`todayReminderDateTime.isBefore(now)`), so a 09:00 anchor passes
     * before 09:00 and fails after it — the suite went red overnight for exactly that reason, with no code
     * change. The start of the day is the one value no comparison can straddle.
     */
    private fun frequencyTask(
        id: String = "f1",
        rule: String = "3/7",
        dueDate: LocalDateTime? = LocalDateTime.now(),
        createdAt: LocalDateTime = LocalDateTime.now().minusDays(14),
        recurrenceEnabled: Boolean = true,
    ) = Task(
        id = id,
        title = "Gym $id",
        status = "pending",
        dueDate = dueDate,
        createdAt = createdAt,
        updatedAt = LocalDateTime.now(),
        recurrenceEnabled = recurrenceEnabled,
        recurrenceRule = rule,
    )

    private fun occurrence(
        task: Task,
        date: LocalDate,
        status: String,
    ) = TaskOccurrence(
        id = "o-" + task.id + "-" + date,
        taskId = task.id,
        occurrenceDate = date.toString(),
        status = status,
        createdAt = date.atStartOfDay(),
    )

    private fun sync(
        tasks: FakeTasks,
        occurrences: FakeOccurrences,
    ) = FrequencyHabitSync(tasks, occurrences)

    // ---- completionStatsOrNull ---------------------------------------------------------------------

    @Test
    fun `stats are null for a task that is not a frequency habit`() =
        runBlocking {
            // A null return, not a zero-filled answer: a caller that is not asking about a frequency habit
            // must be able to tell "not one" from "one with no history".
            val daily = frequencyTask(id = "d1", rule = "daily", recurrenceEnabled = true)
            val stats = sync(FakeTasks(listOf(daily)), FakeOccurrences(emptyList())).completionStatsOrNull(daily)

            assertNull(stats)
        }

    @Test
    fun `stats are null for a task with recurrence disabled`() =
        runBlocking {
            val off = frequencyTask(id = "f1", recurrenceEnabled = false)
            val stats = sync(FakeTasks(listOf(off)), FakeOccurrences(emptyList())).completionStatsOrNull(off)

            assertNull(stats)
        }

    @Test
    fun `stats count a completed occurrence in the current window`() =
        runBlocking {
            val task = frequencyTask(dueDate = today.atStartOfDay())
            val occurrences =
                FakeOccurrences(
                    listOf(occurrence(task, today, "completed"), occurrence(task, today.minusDays(1), "missed")),
                )
            val stats = sync(FakeTasks(listOf(task)), occurrences).completionStatsOrNull(task)

            assertNotNull(stats)
            // A completed occurrence today must show up as a live streak: that is the one field here
            // that a single completion definitely moves, and it is what a user sees on the habit.
            assertTrue(stats!!.currentStreak >= 1)
        }

    // ---- refresh / sync ----------------------------------------------------------------------------

    @Test
    fun `refresh is a no-op for a task that does not exist`() =
        runBlocking {
            val tasks = FakeTasks(emptyList())

            // Must return quietly: the caller is launch-time maintenance, and a habit the user deleted
            // between launches must not fail the whole run.
            sync(tasks, FakeOccurrences(emptyList())).refresh("missing")

            assertTrue(tasks.updated.isEmpty())
        }

    @Test
    fun `refresh is a no-op for a non-frequency task`() =
        runBlocking {
            val daily = frequencyTask(id = "d1", rule = "daily", recurrenceEnabled = true)
            val tasks = FakeTasks(listOf(daily))

            sync(tasks, FakeOccurrences(emptyList())).refresh("d1")

            assertTrue(tasks.updated.isEmpty())
        }

    @Test
    fun `an unsatisfied window keeps the reminder today`() =
        runBlocking {
            // 3-per-7 with ONE completion is not satisfied, so the next reminder is still ahead of the user
            // rather than pushed a week out. Getting this backwards is how a user silently loses a week of
            // a habit.
            val task = frequencyTask(dueDate = today.atStartOfDay())
            val occurrences = FakeOccurrences(listOf(occurrence(task, today, "completed")))
            val tasks = FakeTasks(listOf(task))

            sync(tasks, occurrences).refresh(task.id)

            val update = tasks.updated.single()
            assertEquals(task.id, update.taskId)
            assertTrue(update.newDueDate.toLocalDate() <= today.plusDays(1))
        }

    @Test
    fun `a satisfied window pushes the reminder past the window end`() =
        runBlocking {
            // Three completions inside a 7-day window satisfies 3/7, so the reminder must land AFTER the
            // window rather than inside it — otherwise the user is asked again for a target already met.
            // 00:00, not 09:00: the window maths compares an INSTANT, so a 09:00 anchor passes or
            // fails depending on the hour the suite runs. The start of the day is the one value no
            // comparison can straddle.
            val task = frequencyTask(dueDate = today.atStartOfDay())
            val occurrences =
                FakeOccurrences(
                    listOf(
                        occurrence(task, today, "completed"),
                        occurrence(task, today.minusDays(1), "completed"),
                        occurrence(task, today.minusDays(2), "completed"),
                    ),
                )
            val tasks = FakeTasks(listOf(task))

            sync(tasks, occurrences).refresh(task.id)

            val update = tasks.updated.single()
            assertTrue(update.newDueDate.toLocalDate().isAfter(today))
        }

    @Test
    fun `sync never generates an occurrence`() =
        runBlocking {
            // The whole point of separating this from the advance path: a frequency habit RECORDS what the
            // user did, and this only decides when to remind. Writing an occurrence here would invent data.
            val task = frequencyTask(dueDate = today.atStartOfDay())
            val occurrences = FakeOccurrences(listOf(occurrence(task, today, "completed")))

            sync(FakeTasks(listOf(task)), occurrences).refresh(task.id)

            assertTrue(occurrences.recorded.isEmpty())
        }

    @Test
    fun `a habit whose anchor is far in the past still gets a window`() =
        runBlocking {
            // The fallback path: the calculator returns no window for an anchor with no history behind it,
            // and the sync must still produce a real window rather than giving up. The counts are zero on
            // purpose - an unmeasured window is not a satisfied one, or the user would be told they had met
            // a target they never recorded.
            val task =
                frequencyTask(
                    id = "f1",
                    rule = "3/7",
                    dueDate = today.atStartOfDay(),
                    createdAt = today.minusDays(200).atStartOfDay(),
                )
            val tasks = FakeTasks(listOf(task))
            val occurrences = FakeOccurrences(emptyList())

            sync(tasks, occurrences).refresh(task.id)

            val update = tasks.updated.single()
            assertTrue(
                "A far-past anchor must still yield a reminder date",
                update.newDueDate.toLocalDate() >= today,
            )
        }

    @Test
    fun `an occurrence with an unparseable date is ignored rather than failing the sync`() =
        runBlocking {
            // occurrenceDate is a STRING in the schema, so a malformed value is a real possibility. One bad
            // row must not take down the whole launch-time sync, which is what would strand every habit.
            val task = frequencyTask(dueDate = today.atStartOfDay())
            val junk =
                TaskOccurrence(
                    id = "junk",
                    taskId = task.id,
                    occurrenceDate = "not-a-date",
                    status = "completed",
                    createdAt = LocalDateTime.now(),
                )
            val tasks = FakeTasks(listOf(task))
            val occurrences = FakeOccurrences(listOf(junk))

            sync(tasks, occurrences).refresh(task.id)

            assertTrue("The sync must still have produced a due date", tasks.updated.isNotEmpty())
        }

    @Test
    fun `a satisfied window is judged on completions, not on the number of rows`() =
        runBlocking {
            // A window full of MISSED days is not satisfied, however many rows it has. Getting this backwards
            // would mark a habit done and stop reminding the user about it.
            val task = frequencyTask(dueDate = today.atStartOfDay())
            val occurrences =
                FakeOccurrences(
                    listOf(
                        occurrence(task, today, "missed"),
                        occurrence(task, today.minusDays(1), "missed"),
                        occurrence(task, today.minusDays(2), "missed"),
                    ),
                )
            val tasks = FakeTasks(listOf(task))

            sync(tasks, occurrences).refresh(task.id)

            // Unsatisfied, so the reminder must not be pushed a full window into the future.
            assertTrue(
                "Three missed days must not satisfy a 3-per-7 window",
                tasks.updated.single().newDueDate.toLocalDate() <= today.plusDays(1),
            )
        }
    // ---- fakes -------------------------------------------------------------------------------------

    private class FakeTasks(
        initial: List<Task>,
    ) : TaskRepository {
        private val all = initial.toMutableList()
        val updated = mutableListOf<Update>()

        data class Update(val taskId: String, val newDueDate: LocalDateTime, val lastOccurrenceDate: LocalDateTime)

        override suspend fun getTaskById(id: String): Task? = all.firstOrNull { it.id == id }
        override suspend fun getRecurringTasks(): List<Task> = all.filter { it.recurrenceEnabled }

        override suspend fun updateRecurrenceState(
            taskId: String,
            newDueDate: LocalDateTime,
            lastOccurrenceDate: LocalDateTime,
        ) {
            updated += Update(taskId, newDueDate, lastOccurrenceDate)
            val index = all.indexOfFirst { it.id == taskId }
            if (index >= 0) all[index] = all[index].copy(dueDate = newDueDate)
        }

        override fun getAllTasks(): Flow<List<Task>> = flowOf(all.toList())
        override fun getTasksByStatus(status: String): Flow<List<Task>> = flowOf(emptyList())
        override fun getTasksDueOn(date: LocalDate): Flow<List<Task>> = flowOf(emptyList())
        override fun getOverdueTasks(): Flow<List<Task>> = flowOf(emptyList())
        override fun getTodaysTasks(): Flow<List<Task>> = flowOf(emptyList())
        override suspend fun createTask(input: io.payanam.domain.model.TaskInput): Task = unused()
        override suspend fun updateTask(id: String, input: io.payanam.domain.model.TaskInput): Task = unused()
        override suspend fun updateTaskScore(id: String, score: Double): Unit = unused()
        override suspend fun deleteTask(id: String): Unit = unused()
        override suspend fun completeTask(id: String, note: String?): Task = unused()
        override suspend fun skipTask(id: String, note: String?): Task = unused()
        override suspend fun missTask(id: String, note: String?): Task = unused()
        override suspend fun archiveTask(id: String): Task = unused()

        private fun unused(): Nothing = error("not exercised by FrequencyHabitSync")
    }

    private class FakeOccurrences(
        initial: List<TaskOccurrence>,
    ) : TaskOccurrenceRepository {
        private val all = initial.toMutableList()
        val recorded = mutableListOf<TaskOccurrence>()

        override suspend fun getOccurrencesByTaskId(taskId: String): List<TaskOccurrence> =
            all.filter { it.taskId == taskId }

        override fun getOccurrencesForTask(taskId: String): Flow<List<TaskOccurrence>> =
            flowOf(all.filter { it.taskId == taskId })

        override fun getOccurrencesForDate(date: LocalDate): Flow<List<TaskOccurrence>> =
            flowOf(all.filter { it.occurrenceDate == date.toString() })

        override suspend fun getOccurrencesForLastNDays(taskId: String, days: Int): List<TaskOccurrence> =
            all.filter { it.taskId == taskId }

        override suspend fun getOccurrencesForTasksInLastNDays(
            taskIds: List<String>,
            days: Int,
        ): Map<String, List<TaskOccurrence>> = all.filter { it.taskId in taskIds }.groupBy { it.taskId }

        override suspend fun getOccurrenceForDate(taskId: String, date: LocalDate): TaskOccurrence? =
            all.firstOrNull { it.taskId == taskId && it.occurrenceDate == date.toString() }

        override suspend fun toggleOccurrence(
            taskId: String,
            date: LocalDate,
            newStatus: String,
            note: String?,
            reason: String?,
            actualCompletedAt: LocalDateTime?,
            actualDurationMinutes: Int?,
        ): TaskOccurrence = error("not exercised by FrequencyHabitSync")

        override suspend fun deleteOccurrence(taskId: String, date: LocalDate): Unit = unused()
        override suspend fun deleteOccurrence(id: String): Unit = unused()

        override suspend fun recordOccurrence(occurrence: TaskOccurrence) {
            recorded += occurrence
            all += occurrence
        }

        override suspend fun recordOccurrence(
            taskId: String,
            dueDate: LocalDateTime,
            status: String,
            note: String?,
            completionRate: Double?,
        ): TaskOccurrence = error("not exercised by FrequencyHabitSync")

        private fun unused(): Nothing = error("not exercised by FrequencyHabitSync")
    }
}
