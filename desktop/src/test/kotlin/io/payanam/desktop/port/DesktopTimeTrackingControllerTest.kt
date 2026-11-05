//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.port

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.port.TimeTrackingWidgetRefresher
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.scoring.CompletionStats
import io.payanam.scoring.port.TaskRecurrenceHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The desktop's tracking-port answers, asserted as a set of honesty properties.
 *
 * The risk on this platform is a quiet lie, and it differs by method. `startTracking` and `stopTracking`
 * return [Unit], so they cannot report a refusal in their type at all and their only possible honesty is the
 * log line. `stopTrackingAndCompleteTask` is the opposite: its caller logs "Tracking stopped successfully" on
 * whatever it returns, so a `null` standing in for "this platform cannot stop a session" would report a stop
 * that never happened while an entry sat running in the database. These tests exist so that a regression to
 * a log-only no-op fails here rather than reaching a user.
 */
class DesktopTimeTrackingControllerTest {
    /** What the stores were actually asked to do, so a refusal cannot satisfy any assertion below. */
    private val stops = mutableListOf<Pair<Double, String?>>()
    private val completedTasks = mutableListOf<String>()
    private val recordedOccurrences = mutableListOf<TaskOccurrence>()
    private val advancedRecurrences = mutableListOf<String>()
    private var activeEntry: TimeEntry? = null
    private var task: Task? = null
    private var frequencyHabit = false

    /**
     * Fails any repository call the controller has no business making.
     *
     * [Nothing], so a double can stand in for a method of any return type without a cast. A silent `null`
     * or empty list would let a wrong call pass unnoticed, which is the same quiet-lie failure these tests
     * exist to catch — one layer down.
     */
    private fun unexpected(method: String): Nothing = throw AssertionError("Unexpected repository call: $method")

    private val timeEntries =
        object : TimeEntryRepository {
            override suspend fun getActiveTimeEntry(): TimeEntry? = unexpected("getActiveTimeEntry")

            override fun observeActiveTimeEntry(): Flow<TimeEntry?> = emptyFlow()

            override fun getTimeEntriesForRange(
                start: LocalDateTime,
                end: LocalDateTime,
            ): Flow<List<TimeEntry>> = emptyFlow()

            override fun getTimeEntriesForDate(date: LocalDate): Flow<List<TimeEntry>> = emptyFlow()

            override suspend fun startTimeEntry(input: TimeEntryInput): TimeEntry = unexpected("startTimeEntry")

            override suspend fun stopActiveTimeEntry(): TimeEntry? = unexpected("stopActiveTimeEntry")

            override suspend fun stopActiveTimeEntryWithFocus(
                focusRating: Double,
                focusNote: String?,
            ): TimeEntry? {
                stops += focusRating to focusNote
                return activeEntry.also { activeEntry = null }
            }

            override suspend fun updateTimeEntry(
                id: String,
                input: TimeEntryInput,
            ): TimeEntry = unexpected("updateTimeEntry(id, input)")

            override suspend fun deleteTimeEntry(id: String) = unexpected("deleteTimeEntry")

            override suspend fun createTimeEntry(input: TimeEntryInput): TimeEntry = unexpected("createTimeEntry")

            override fun getAllTimeEntries(): Flow<List<TimeEntry>> = emptyFlow()

            override fun getActiveTimeEntries(): Flow<List<TimeEntry>> = emptyFlow()

            override suspend fun updateTimeEntry(entry: TimeEntry) = unexpected("updateTimeEntry(entry)")
        }

    private val tasks =
        object : TaskRepository {
            override fun getAllTasks(): Flow<List<Task>> = emptyFlow()

            override fun getTasksByStatus(status: String): Flow<List<Task>> = emptyFlow()

            override fun getTasksDueOn(date: LocalDate): Flow<List<Task>> = emptyFlow()

            override suspend fun getTaskById(id: String): Task? = task?.takeIf { it.id == id }

            override suspend fun createTask(input: TaskInput): Task = unexpected("createTask")

            override suspend fun updateTask(
                id: String,
                input: TaskInput,
            ): Task = unexpected("updateTask")

            override suspend fun updateTaskScore(
                id: String,
                score: Double,
            ) = unexpected("updateTaskScore")

            override suspend fun deleteTask(id: String) = unexpected("deleteTask")

            override suspend fun completeTask(
                id: String,
                note: String?,
            ): Task {
                completedTasks += id
                return task ?: unexpected("completeTask with no task")
            }

            override suspend fun skipTask(
                id: String,
                note: String?,
            ): Task = unexpected("skipTask")

            override suspend fun missTask(
                id: String,
                note: String?,
            ): Task = unexpected("missTask")

            override suspend fun archiveTask(id: String): Task = unexpected("archiveTask")

            override fun getOverdueTasks(): Flow<List<Task>> = emptyFlow()

            override fun getTodaysTasks(): Flow<List<Task>> = emptyFlow()

            override suspend fun getRecurringTasks(): List<Task> = unexpected("getRecurringTasks")

            override suspend fun updateRecurrenceState(
                taskId: String,
                newDueDate: LocalDateTime,
                lastOccurrenceDate: LocalDateTime,
            ) = unexpected("updateRecurrenceState")
        }

    private val taskOccurrences =
        object : TaskOccurrenceRepository {
            override suspend fun getOccurrencesByTaskId(taskId: String): List<TaskOccurrence> = unexpected("getOccurrencesByTaskId")

            override fun getOccurrencesForTask(taskId: String): Flow<List<TaskOccurrence>> = emptyFlow()

            override suspend fun getOccurrencesForLastNDays(
                taskId: String,
                days: Int,
            ): List<TaskOccurrence> = unexpected("getOccurrencesForLastNDays")

            override suspend fun getOccurrencesForTasksInLastNDays(
                taskIds: List<String>,
                days: Int,
            ): Map<String, List<TaskOccurrence>> = unexpected("getOccurrencesForTasksInLastNDays")

            override suspend fun getOccurrenceForDate(
                taskId: String,
                date: LocalDate,
            ): TaskOccurrence? = unexpected("getOccurrenceForDate")

            override suspend fun toggleOccurrence(
                taskId: String,
                date: LocalDate,
                newStatus: String,
                note: String?,
                reason: String?,
                actualCompletedAt: LocalDateTime?,
                actualDurationMinutes: Int?,
            ): TaskOccurrence = unexpected("toggleOccurrence")

            override fun getOccurrencesForDate(date: LocalDate): Flow<List<TaskOccurrence>> = emptyFlow()

            override suspend fun deleteOccurrence(
                taskId: String,
                date: LocalDate,
            ) = unexpected("deleteOccurrence(taskId, date)")

            override suspend fun recordOccurrence(occurrence: TaskOccurrence) {
                recordedOccurrences += occurrence
            }

            override suspend fun recordOccurrence(
                taskId: String,
                dueDate: LocalDateTime,
                status: String,
                note: String?,
                completionRate: Double?,
            ): TaskOccurrence = unexpected("recordOccurrence(fields)")

            override suspend fun deleteOccurrence(id: String) = unexpected("deleteOccurrence(id)")
        }

    private val recurrence =
        object : TaskRecurrenceHandler {
            override fun isFrequencyHabit(task: Task): Boolean = frequencyHabit

            override suspend fun onTaskCompleted(
                task: Task,
                note: String?,
                reason: String?,
                nextDueStrategy: String?,
            ) {
                advancedRecurrences += task.id
            }

            override suspend fun onTaskSkipped(
                task: Task,
                note: String?,
                reason: String?,
                nextDueStrategy: String?,
            ) = Unit

            override suspend fun onTaskMissed(
                task: Task,
                note: String?,
                reason: String?,
                nextDueStrategy: String?,
            ) = Unit

            override suspend fun getCompletionStats(task: Task) = NO_STATS
        }

    private fun controller() =
        DesktopTimeTrackingController(
            timeEntries = timeEntries,
            tasks = tasks,
            taskOccurrences = taskOccurrences,
            recurrenceHandler = recurrence,
        )

    /**
     * THE property. A refusal returns `null`, and the caller logs that as a successful stop — so this
     * asserts the STORE was written, which a refusal cannot fake.
     */
    @Test
    fun stopTrackingAndCompleteTaskReallyStopsTheActiveEntry() =
        runTest {
            activeEntry = entry(id = "entry-1", taskId = null)

            val stopped = controller().stopTrackingAndCompleteTask(focusRating = 0.5, focusNote = "deep work")

            assertThat(stopped?.id).isEqualTo("entry-1")
            assertThat(stops).containsExactly(0.5 to "deep work")
        }

    @Test
    fun returnsNullWhenNothingWasActiveAndCompletesNoTask() =
        runTest {
            activeEntry = null

            assertThat(controller().stopTrackingAndCompleteTask(focusRating = 0.0, focusNote = null)).isNull()
            assertThat(completedTasks).isEmpty()
            assertThat(stops).hasSize(1)
        }

    /** Android's clamp, kept: a rating outside 0..1 must not be stored as given, nor a blank note. */
    @Test
    fun clampsAnOutOfRangeRatingAndDropsABlankNote() =
        runTest {
            activeEntry = entry(id = "entry-2", taskId = null)

            controller().stopTrackingAndCompleteTask(focusRating = 4.2, focusNote = "   ")

            assertThat(stops.single().first).isEqualTo(1.0)
            assertThat(stops.single().second).isNull()
        }

    @Test
    fun completesTheTaskTheStoppedEntryWasAttachedTo() =
        runTest {
            activeEntry = entry(id = "entry-3", taskId = "task-9")
            task = task(id = "task-9", recurring = false)

            val stopped = controller().stopTrackingAndCompleteTask(focusRating = 0.0, focusNote = null)

            assertThat(stopped?.taskId).isEqualTo("task-9")
            assertThat(completedTasks).containsExactly("task-9")
        }

    /**
     * A recurring task's completion advances the schedule through the INJECTED handler — never a second one
     * built inside the controller, which would reintroduce the duplicate owner the seam exists to prevent.
     */
    @Test
    fun aRecurringTaskRecordsAnOccurrenceAndAdvancesTheInjectedHandler() =
        runTest {
            activeEntry = entry(id = "entry-4", taskId = "task-7")
            task = task(id = "task-7", recurring = true)
            frequencyHabit = false

            controller().stopTrackingAndCompleteTask(focusRating = 0.0, focusNote = null)

            assertThat(completedTasks).containsExactly("task-7")
            assertThat(advancedRecurrences).containsExactly("task-7")
            assertThat(recordedOccurrences).hasSize(1)
            assertThat(recordedOccurrences.single().status).isEqualTo("completed")
            // The occurrence carries the real measured duration, not the wall clock at completion time.
            assertThat(recordedOccurrences.single().actualDurationMinutes).isEqualTo(60)
        }

    /** A frequency habit is NOT closed out: its completion IS its schedule advancing. */
    @Test
    fun aFrequencyHabitIsNotCompletedButStillRecordsItsOccurrence() =
        runTest {
            activeEntry = entry(id = "entry-5", taskId = "task-8")
            task = task(id = "task-8", recurring = true)
            frequencyHabit = true

            controller().stopTrackingAndCompleteTask(focusRating = 0.0, focusNote = null)

            assertThat(completedTasks).isEmpty()
            assertThat(advancedRecurrences).containsExactly("task-8")
            assertThat(recordedOccurrences).hasSize(1)
        }

    /** A task deleted between the entry and the stop must not throw, and must not discard the stop. */
    @Test
    fun aVanishedAttachedTaskLeavesTheStopIntact() =
        runTest {
            activeEntry = entry(id = "entry-6", taskId = "task-missing")
            task = null

            val stopped = controller().stopTrackingAndCompleteTask(focusRating = 0.0, focusNote = null)

            assertThat(stopped?.id).isEqualTo("entry-6")
            assertThat(completedTasks).isEmpty()
        }

    /** The two platform methods must not throw: the Time screen is promoted on the strength of this port. */
    @Test
    fun startAndStopDoNotThrowWhenNoSessionExists() =
        runTest {
            controller().startTracking(
                taskId = "task-1",
                taskTitle = "Write the report",
                dimension = "Work",
                startTime = START,
            )
            controller().stopTracking()
        }

    @Test
    fun refreshingTheWidgetSucceedsQuietlyRatherThanFailingAScreen() {
        // No widget exists to redraw, so the call returns rather than throwing: shared code calls it on
        // every session change, and an exception over an absent presentation surface would break the screen.
        DesktopUnsupportedPorts.timeTrackingWidgetRefresher.refreshWidget()
    }

    @Test
    fun theBoundWidgetRefresherIsThePortTypeTheScreenNeeds() {
        val refresher: TimeTrackingWidgetRefresher = DesktopUnsupportedPorts.timeTrackingWidgetRefresher
        assertThat(refresher).isNotNull()
    }

    private companion object {
        val START: LocalDateTime = LocalDateTime.of(2026, 10, 1, 9, 0)

        val NO_STATS =
            CompletionStats(
                completionRate7Days = 0.0,
                completionRate30Days = 0.0,
                completionRate90Days = 0.0,
                allTimeRate = 0.0,
                currentStreak = 0,
                longestStreak = 0,
            )
    }

    private fun entry(
        id: String,
        taskId: String?,
    ) = TimeEntry(
        id = id,
        lifeIntentionCategory = "Work",
        taskId = taskId,
        startedAt = START,
        endedAt = START.plusHours(1),
        createdAt = START,
        updatedAt = START.plusHours(1),
        dimensionId = "dim-work",
    )

    private fun task(
        id: String,
        recurring: Boolean,
    ) = Task(
        id = id,
        title = "Write the report",
        status = "pending",
        createdAt = START,
        updatedAt = START,
        recurrenceEnabled = recurring,
    )
}
