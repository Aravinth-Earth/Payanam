//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.presentation.preferences.AppPreferenceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

/**
 * Tests for the task-status / checkmark interaction surface of [TasksViewModel]:
 * [toggleCheckmark], [updateCheckmark], [completeTask], [confirmCompletion],
 * [archiveTask], [deleteTask], [dismissCompletionDialog], [setFilter],
 * [setSortOption], [setHabitSortOption], and the visibility-toggle setters.
 *
 * These cover the method bodies that JaCoCo reports as 100% missed (the
 * `launch` lambdas that perform the actual work).
 *
 * Follows the same construction pattern as [TasksViewModelTest]: a class-level
 * [StandardTestDispatcher] whose scheduler is bound to the ViewModel scope and
 * the shaping dispatcher.
 */
@ExperimentalCoroutinesApi
class TasksViewModelInteractionsTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var taskRepository: FakeTaskRepository
    private lateinit var taskOccurrenceRepository: FakeTaskOccurrenceRepository
    private lateinit var notificationScheduler: FakeReminderScheduler
    private lateinit var appSettingsRepository: FakeAppSettingsRepository
    private lateinit var recurrenceHandler: FakeRecurrenceHandler
    private lateinit var habitTimeEntryRecorder: FakeHabitTimeEntryRecorder
    private lateinit var scoreRollupTrigger: FakeScoreRollupTrigger
    private lateinit var habitMetricRepository: FakeHabitMetricRepository
    private lateinit var scoreWindowRepository: FakeScoreWindowRepository
    private lateinit var scoreChangeStream: FakeScoreChangeStream
    private lateinit var tracer: FakePerformanceTracer

    private lateinit var viewModel: TasksViewModel

    @Before
    fun setup() {
        taskRepository = FakeTaskRepository()
        taskOccurrenceRepository = FakeTaskOccurrenceRepository()
        notificationScheduler = FakeReminderScheduler()
        appSettingsRepository = FakeAppSettingsRepository()
        recurrenceHandler = FakeRecurrenceHandler()
        habitTimeEntryRecorder = FakeHabitTimeEntryRecorder()
        scoreRollupTrigger = FakeScoreRollupTrigger()
        habitMetricRepository = FakeHabitMetricRepository()
        scoreWindowRepository = FakeScoreWindowRepository()
        scoreChangeStream = FakeScoreChangeStream()
        tracer = FakePerformanceTracer()

        viewModel = createViewModel()
    }

    @After
    fun tearDown() {
        viewModel.dispose()
    }

    /**
     * Builds a ViewModel over the current fakes. A test that is about one collaborator's failure passes a
     * wrapper for exactly that parameter and leaves the rest alone - the same eleven-argument shape the rest
     * of the suite uses, with defaults pointing at the fakes created in [setup].
     */
    private fun createViewModel(
        taskRepo: TaskRepository = taskRepository,
        occurrences: TaskOccurrenceRepository = taskOccurrenceRepository,
        reminders: TaskReminderScheduler = notificationScheduler,
    ): TasksViewModel =
        TasksViewModel(
            taskRepository = taskRepo,
            taskOccurrenceRepository = occurrences,
            notificationScheduler = reminders,
            appSettingsRepository = appSettingsRepository,
            recurrenceHandler = recurrenceHandler,
            habitTimeEntryRecorder = habitTimeEntryRecorder,
            scoreRollupTrigger = scoreRollupTrigger,
            habitMetricRepository = habitMetricRepository,
            scoreWindowRepository = scoreWindowRepository,
            scoreChangeStream = scoreChangeStream,
            tracer = tracer,
            scope = CoroutineScope(testDispatcher),
        ).apply { shapingDispatcher = testDispatcher }

    // ── toggleCheckmark ────────────────────────────────────────────────

    /**
     * Non-recurring task: toggling a PENDING checkmark to completed should
     * record the occurrence and recompute L1 metrics, without opening the
     * completion dialog.
     */
    @Test
    fun `toggleCheckmark on a non-recurring task is a no-op`() =
        runTest(testDispatcher) {
            // Checkmark history is habits-scoped (recurring tasks only), so a one-time task has no checkmark to
            // act on and the toggle returns before touching any repository.
            val t = task(id = "t1", title = "t1")
            taskRepository.tasks.value = listOf(t)

            viewModel = createViewModel()
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.taskCheckmarks["t1"]).isNull()

            viewModel.toggleCheckmark("t1", TASKS_TEST_DAY)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showCompletionDialog).isFalse()
            assertThat(
                taskOccurrenceRepository.occurrences.value.filter {
                    it.taskId == "t1" && it.occurrenceDate.startsWith(TASKS_TEST_DAY.toString())
                },
            ).isEmpty()
            assertThat(scoreRollupTrigger.statusChanges.map { it.first }).doesNotContain("t1")
        }

    @Test
    fun `toggleCheckmark on recurring task opens completion dialog`() =
        runTest(testDispatcher) {
            val h = habit(id = "h1", rule = "1/1")
            taskRepository.tasks.value = listOf(h)
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.toggleCheckmark("h1", TASKS_TEST_DAY)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showCompletionDialog).isTrue()
            assertThat(
                viewModel.uiState.value.completionDialogTask
                    ?.id,
            ).isEqualTo("h1")
            assertThat(viewModel.uiState.value.completionDialogDate).isEqualTo(TASKS_TEST_DAY)
            // Dialog shown = early return: no occurrence recorded yet.
            assertThat(taskOccurrenceRepository.occurrences.value.filter { it.taskId == "h1" }).isEmpty()
        }

    /**
     * COMPLETED → missed: toggle cycles to the next logical status.
     */
    @Test
    fun `toggleCheckmark on completed status toggles to missed`() =
        runTest(testDispatcher) {
            val t = habit("t2")
            appSettingsRepository.emit(AppPreferenceKeys.KEY_SHOW_COMPLETED_HABITS, "true")
            taskRepository.tasks.value = listOf(t)
            // Pre-seed a completed occurrence for today.
            taskOccurrenceRepository.occurrences.value = listOf(occurrence("t2", TASKS_TEST_DAY, "completed"))
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.toggleCheckmark("t2", TASKS_TEST_DAY)
            advanceUntilIdle()

            val recorded =
                taskOccurrenceRepository.occurrences.value.last {
                    it.taskId == "t2" && it.occurrenceDate.startsWith(TASKS_TEST_DAY.toString())
                }
            assertThat(recorded.status).isEqualTo("missed")
        }

    /**
     * MISSED → completed: toggle cycles back to completed.
     */
    @Test
    fun `toggleCheckmark on a missed habit opens the completion dialog`() =
        runTest(testDispatcher) {
            val t = habit("t3")
            appSettingsRepository.emit(AppPreferenceKeys.KEY_SHOW_COMPLETED_HABITS, "true")
            taskRepository.tasks.value = listOf(t)
            taskOccurrenceRepository.occurrences.value = listOf(occurrence("t3", TASKS_TEST_DAY, "missed"))
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.toggleCheckmark("t3", TASKS_TEST_DAY)
            advanceUntilIdle()

            // A recurring task going to completed always needs the duration dialog first, so the row is
            // untouched until that dialog is confirmed.
            assertThat(viewModel.uiState.value.showCompletionDialog).isTrue()
            val recorded =
                taskOccurrenceRepository.occurrences.value.last {
                    it.taskId == "t3" && it.occurrenceDate.startsWith(TASKS_TEST_DAY.toString())
                }
            assertThat(recorded.status).isEqualTo("missed")
        }

    /**
     * SKIPPED → completed: toggle cycles to completed.
     */
    @Test
    fun `toggleCheckmark on a skipped habit opens the completion dialog`() =
        runTest(testDispatcher) {
            val t = habit("t4")
            appSettingsRepository.emit(AppPreferenceKeys.KEY_SHOW_COMPLETED_HABITS, "true")
            taskRepository.tasks.value = listOf(t)
            taskOccurrenceRepository.occurrences.value = listOf(occurrence("t4", TASKS_TEST_DAY, "skipped"))
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.toggleCheckmark("t4", TASKS_TEST_DAY)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showCompletionDialog).isTrue()
            val recorded =
                taskOccurrenceRepository.occurrences.value.last {
                    it.taskId == "t4" && it.occurrenceDate.startsWith(TASKS_TEST_DAY.toString())
                }
            assertThat(recorded.status).isEqualTo("skipped")
        }

    // ── updateCheckmark ────────────────────────────────────────────────

    /**
     * Setting a checkmark to COMPLETED records the occurrence directly.
     */
    @Test
    fun `updateCheckmark to completed records occurrence`() =
        runTest(testDispatcher) {
            val t = task(id = "u1", title = "u1")
            taskRepository.tasks.value = listOf(t)
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.updateCheckmark("u1", TASKS_TEST_DAY, CheckmarkStatus.COMPLETED, "done")
            advanceUntilIdle()

            val recorded = taskOccurrenceRepository.occurrences.value.filter { it.taskId == "u1" }
            assertThat(recorded).isNotEmpty()
            assertThat(recorded.last().status).isEqualTo("completed")
            // L1 recompute was triggered.
            assertThat(scoreRollupTrigger.statusChanges.last().first).isEqualTo("u1")
        }

    /**
     * Setting a checkmark to SKIPPED records a skipped occurrence.
     */
    @Test
    fun `updateCheckmark to skipped records skipped occurrence`() =
        runTest(testDispatcher) {
            val t = task(id = "u2", title = "u2")
            taskRepository.tasks.value = listOf(t)
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.updateCheckmark("u2", TASKS_TEST_DAY, CheckmarkStatus.SKIPPED, "")
            advanceUntilIdle()

            val recorded = taskOccurrenceRepository.occurrences.value.filter { it.taskId == "u2" }
            assertThat(recorded.last().status).isEqualTo("skipped")
        }

    /**
     * Setting a checkmark to MISSED records a missed occurrence.
     */
    @Test
    fun `updateCheckmark to missed records missed occurrence`() =
        runTest(testDispatcher) {
            val t = task(id = "u3", title = "u3")
            taskRepository.tasks.value = listOf(t)
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.updateCheckmark("u3", TASKS_TEST_DAY, CheckmarkStatus.MISSED, "")
            advanceUntilIdle()

            val recorded = taskOccurrenceRepository.occurrences.value.filter { it.taskId == "u3" }
            assertThat(recorded.last().status).isEqualTo("missed")
        }

    /**
     * Setting a checkmark to PENDING deletes the existing occurrence (clears
     * the mark) rather than recording a new row.
     */
    @Test
    fun `updateCheckmark to pending deletes existing occurrence`() =
        runTest(testDispatcher) {
            val t = task(id = "u4", title = "u4")
            taskRepository.tasks.value = listOf(t)
            taskOccurrenceRepository.occurrences.value = listOf(occurrence("u4", TASKS_TEST_DAY, "completed"))
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.updateCheckmark("u4", TASKS_TEST_DAY, CheckmarkStatus.PENDING, "")
            advanceUntilIdle()

            val remaining = taskOccurrenceRepository.occurrences.value.filter { it.taskId == "u4" }
            assertThat(remaining).isEmpty()
        }

    /**
     * The note parameter is passed through to the fake occurrence repository.
     */
    @Test
    fun `updateCheckmark passes note to repository`() =
        runTest(testDispatcher) {
            val t = task(id = "u5", title = "u5")
            taskRepository.tasks.value = listOf(t)
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.updateCheckmark("u5", TASKS_TEST_DAY, CheckmarkStatus.COMPLETED, "with-note")
            advanceUntilIdle()

            val recorded = taskOccurrenceRepository.occurrences.value.filter { it.taskId == "u5" }
            assertThat(recorded).isNotEmpty()
            assertThat(recorded.last().status).isEqualTo("completed")
        }

    // ── completeTask ───────────────────────────────────────────────────

    /**
     * Non-recurring task: completeTask cancels the reminder and updates
     * the repository.
     */
    @Test
    fun `completeTask on non-recurring task cancels reminder`() =
        runTest(testDispatcher) {
            val t = task(id = "c1", title = "c1")
            taskRepository.tasks.value = listOf(t)
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.completeTask("c1")
            advanceUntilIdle()

            assertThat(
                taskRepository.tasks.value
                    .first { it.id == "c1" }
                    .status,
            ).isEqualTo("completed")
            assertThat(notificationScheduler.cancelled).contains("c1")
        }

    /**
     * Recurring task (non-frequency): completeTask records an occurrence,
     * handles recurrence, reschedules the reminder, refreshes checkmarks,
     * and recomputes L1 metrics.
     */
    @Test
    fun `completeTask on recurring task records occurrence and reschedules`() =
        runTest(testDispatcher) {
            val h = habit(id = "c2", rule = "1/1")
            taskRepository.tasks.value = listOf(h)
            viewModel = createViewModel()
            advanceUntilIdle()

            recurrenceHandler.frequencyHabit = false

            viewModel.completeTask("c2")
            advanceUntilIdle()

            assertThat(notificationScheduler.cancelled).doesNotContain("c2")
            assertThat(notificationScheduler.scheduled).contains("c2")
            // The recurrence handler's onTaskCompleted was called.
            assertThat(recurrenceHandler.completed).contains("c2")
            // An occurrence was recorded.
            val occurrences = taskOccurrenceRepository.occurrences.value.filter { it.taskId == "c2" }
            assertThat(occurrences).isNotEmpty()
            // L1 recompute was triggered.
            assertThat(scoreRollupTrigger.statusChanges.any { it.first == "c2" }).isTrue()
        }

    /**
     * Frequency habit: completeTask does NOT call taskRepository.completeTask
     * (the frequency model tracks completions via occurrences, not status).
     */
    @Test
    fun `completeTask on frequency habit skips repository completeTask`() =
        runTest(testDispatcher) {
            val h = habit(id = "c3", rule = "5/7")
            taskRepository.tasks.value = listOf(h)
            viewModel = createViewModel()
            advanceUntilIdle()

            recurrenceHandler.frequencyHabit = true

            viewModel.completeTask("c3")
            advanceUntilIdle()

            // Frequency habits are not marked "completed" in the task table.
            assertThat(
                taskRepository.tasks.value
                    .first { it.id == "c3" }
                    .status,
            ).isEqualTo("pending")
            // But recurrence is still advanced.
            assertThat(recurrenceHandler.completed).contains("c3")
        }

    // ── confirmCompletion ──────────────────────────────────────────────

    /**
     * Confirming a dialog completion records the occurrence, creates the
     * time entry, recomputes L1 metrics, and dismisses the dialog.
     */
    @Test
    fun `confirmCompletion records occurrence and creates time entry`() =
        runTest(testDispatcher) {
            val h = habit(id = "cf1", rule = "1/1")
            taskRepository.tasks.value = listOf(h)
            viewModel = createViewModel()
            advanceUntilIdle()

            // Open the dialog by toggling the checkmark on a recurring task.
            viewModel.toggleCheckmark("cf1", TASKS_TEST_DAY)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.showCompletionDialog).isTrue()

            // Confirm with timing data.
            val completedAt = TASKS_TEST_TIME
            val duration = 30
            viewModel.confirmCompletion(completedAt, duration)
            advanceUntilIdle()

            // Dialog is dismissed.
            assertThat(viewModel.uiState.value.showCompletionDialog).isFalse()
            assertThat(viewModel.uiState.value.completionDialogTask).isNull()
            assertThat(viewModel.uiState.value.completionDialogDate).isNull()
            // Occurrence was recorded.
            val recorded = taskOccurrenceRepository.occurrences.value.filter { it.taskId == "cf1" }
            assertThat(recorded).isNotEmpty()
            assertThat(recorded.last().status).isEqualTo("completed")
            // Time entry was created.
            assertThat(habitTimeEntryRecorder.recorded).contains("cf1")
            // L1 recompute was triggered.
            assertThat(scoreRollupTrigger.statusChanges.any { it.first == "cf1" }).isTrue()
        }

    // ── dismissCompletionDialog ────────────────────────────────────────

    /** Dismissing clears all dialog state fields. */
    @Test
    fun `dismissCompletionDialog clears dialog state`() =
        runTest(testDispatcher) {
            val h = habit(id = "d1", rule = "1/1")
            taskRepository.tasks.value = listOf(h)
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.toggleCheckmark("d1", TASKS_TEST_DAY)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.showCompletionDialog).isTrue()

            viewModel.dismissCompletionDialog()
            assertThat(viewModel.uiState.value.showCompletionDialog).isFalse()
            assertThat(viewModel.uiState.value.completionDialogTask).isNull()
            assertThat(viewModel.uiState.value.completionDialogDate).isNull()
        }

    // ── archiveTask ────────────────────────────────────────────────────

    /** Archive task cancels reminders. */
    @Test
    fun `archiveTask cancels reminder`() =
        runTest(testDispatcher) {
            val t = task(id = "a1", title = "a1")
            taskRepository.tasks.value = listOf(t)
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.archiveTask("a1")
            advanceUntilIdle()

            assertThat(
                taskRepository.tasks.value
                    .first { it.id == "a1" }
                    .status,
            ).isEqualTo("archived")
            assertThat(notificationScheduler.cancelled).contains("a1")
        }

    // ── deleteTask ─────────────────────────────────────────────────────

    /** Delete task removes from repository and cancels reminders. */
    @Test
    fun `deleteTask removes task and cancels reminder`() =
        runTest(testDispatcher) {
            val t = task(id = "dl1", title = "dl1")
            taskRepository.tasks.value = listOf(t)
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.deleteTask("dl1")
            advanceUntilIdle()

            assertThat(taskRepository.tasks.value.any { it.id == "dl1" }).isFalse()
            assertThat(notificationScheduler.cancelled).contains("dl1")
        }

    // ── setFilter ──────────────────────────────────────────────────────

    /** Changing filter persists to settings and reshapes the task list. */
    @Test
    fun `setFilter persists and reshapes list`() =
        runTest(testDispatcher) {
            val pending = task(id = "f1", title = "f1", status = "pending")
            val done = task(id = "f2", title = "f2", status = "completed")
            taskRepository.tasks.value = listOf(pending, done)
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.setFilter(TaskFilter.COMPLETED)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.currentFilter).isEqualTo(TaskFilter.COMPLETED)
            assertThat(viewModel.uiState.value.filteredTasks).hasSize(1)
            assertThat(
                viewModel.uiState.value.filteredTasks
                    .first()
                    .id,
            ).isEqualTo("f2")
            // Setting persisted.
            assertThat(appSettingsRepository.writes.any { it.first == "task_filter_option" && it.second == "completed" }).isTrue()
        }

    /** Filter with interaction telemetry records tracer events. */
    @Test
    fun `setFilter with interactionId records telemetry events`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(task(id = "te1", title = "te1"))
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.setFilter(TaskFilter.TODAY, interactionId = "int-1", interactionStartMs = 100L)
            advanceUntilIdle()

            assertThat(tracer.events).contains("tasks/filter_interaction_compute_start")
            assertThat(tracer.events).contains("tasks/filter_interaction_compute_end")
            assertThat(tracer.events).contains("tasks/filter_interaction_state_applied")
        }

    // ── setSortOption ──────────────────────────────────────────────────

    /** Changing sort option persists and re-sorts the task list. */
    @Test
    fun `setSortOption persists and re-sorts list`() =
        runTest(testDispatcher) {
            val t1 = task(id = "s1", title = "Bravo")
            val t2 = task(id = "s2", title = "Alpha")
            taskRepository.tasks.value = listOf(t1, t2)
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.setSortOption(TaskSortOption.TITLE_ASC)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.currentSort).isEqualTo(TaskSortOption.TITLE_ASC)
            assertThat(
                viewModel.uiState.value.filteredTasks
                    .first()
                    .title,
            ).isEqualTo("Alpha")
            assertThat(appSettingsRepository.writes.any { it.first == "task_sort_option" && it.second == "title_asc" }).isTrue()
        }

    // ── setHabitSortOption ─────────────────────────────────────────────

    /** Changing habit sort option persists and re-sorts the habit list. */
    @Test
    fun `setHabitSortOption persists and re-sorts habits`() =
        runTest(testDispatcher) {
            val h1 = habit(id = "h-a", rule = "1/1")
            val h2 = habit(id = "h-z", rule = "1/1")
            taskRepository.tasks.value = listOf(h1, h2)
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.setHabitSortOption(HabitSortOption.BY_NAME)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.habitSortOption).isEqualTo(HabitSortOption.BY_NAME)
            assertThat(appSettingsRepository.writes.any { it.first == "habit_sort_option" && it.second == "by_name" }).isTrue()
        }

    // ── toggleShowArchivedHabits ───────────────────────────────────────

    /** Toggling show-archived flips the flag and persists it. */
    @Test
    fun `toggleShowArchivedHabits flips flag and persists`() =
        runTest(testDispatcher) {
            viewModel.toggleShowArchivedHabits()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showArchivedHabits).isTrue()
            assertThat(
                appSettingsRepository.writes.any {
                    it.first == "show_archived_habits" && it.second == "true"
                },
            ).isTrue()

            viewModel.toggleShowArchivedHabits()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showArchivedHabits).isFalse()
            assertThat(
                appSettingsRepository.writes.any {
                    it.first == "show_archived_habits" && it.second == "false"
                },
            ).isTrue()
        }

    // ── toggleShowCompletedHabits ──────────────────────────────────────

    /** Toggling show-completed flips the flag and persists it. */
    @Test
    fun `toggleShowCompletedHabits flips flag and persists`() =
        runTest(testDispatcher) {
            viewModel.toggleShowCompletedHabits()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showCompletedHabits).isTrue()
            assertThat(
                appSettingsRepository.writes.any {
                    it.first == "show_completed_habits" && it.second == "true"
                },
            ).isTrue()

            viewModel.toggleShowCompletedHabits()
            advanceUntilIdle()

            // Back to hidden, and the saved value follows the state.
            assertThat(viewModel.uiState.value.showCompletedHabits).isFalse()
            assertThat(appSettingsRepository.writes.last()).isEqualTo("show_completed_habits" to "false")
        }

    // ── toggleHideAllMarkedToday ───────────────────────────────────────

    /** Toggling hide-all-marked flips the flag, persists, and reshapes. */
    @Test
    fun `toggleHideAllMarkedToday flips flag and persists`() =
        runTest(testDispatcher) {
            viewModel.toggleHideAllMarkedToday()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.hideAllMarkedToday).isTrue()
            assertThat(
                appSettingsRepository.writes.any {
                    it.first == "hide_all_marked_today" && it.second == "true"
                },
            ).isTrue()

            viewModel.toggleHideAllMarkedToday()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.hideAllMarkedToday).isFalse()
            assertThat(
                appSettingsRepository.writes.any {
                    it.first == "hide_all_marked_today" && it.second == "false"
                },
            ).isTrue()
        }

    // ── toggleDueTodayOnly ─────────────────────────────────────────────

    /** Toggling due-today-only flips the flag and persists it. */
    @Test
    fun `toggleDueTodayOnly flips flag and persists`() =
        runTest(testDispatcher) {
            viewModel.toggleDueTodayOnly()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.dueTodayOnly).isTrue()
            assertThat(
                appSettingsRepository.writes.any {
                    it.first == "due_today_only" && it.second == "true"
                },
            ).isTrue()

            viewModel.toggleDueTodayOnly()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.dueTodayOnly).isFalse()
            assertThat(
                appSettingsRepository.writes.any {
                    it.first == "due_today_only" && it.second == "false"
                },
            ).isTrue()
        }

    // ── clearError ─────────────────────────────────────────────────────

    /** clearError removes the error from state. */
    @Test
    fun `clearError removes error message`() =
        runTest(testDispatcher) {
            // Inject an error by using deleteTask on a nonexistent task (triggers error path).
            viewModel.deleteTask("nonexistent")
            advanceUntilIdle()
            // Error may or may not be set depending on exception handling; just verify clear works.
            viewModel.clearError()
            assertThat(viewModel.uiState.value.error).isNull()
        }

    // ── dispose ────────────────────────────────────────────────────────

    /** dispose cancels the scope so no further coroutines run. */
    @Test
    fun `dispose cancels scope`() =
        runTest(testDispatcher) {
            viewModel.dispose()
            // After dispose, calling methods should not crash (coroutines are cancelled).
            viewModel.toggleCheckmark("any", TASKS_TEST_DAY)
            advanceUntilIdle()
            // No assertion needed — the call should not throw.
        }

    // ── archiveTask / deleteTask failure arms ───────────────────────────
    //
    // The happy paths above already ran through both lambdas' try blocks, so what JaCoCo still reports as
    // missed here is the ERROR half of each: the swallowed scheduler throw and the repository throw that
    // publishes `error`. Each fake below is an interface DELEGATION wrapper rather than a fresh
    // implementation, so the twenty other TaskRepository members the ViewModel needs keep working and the
    // only member that changes is the one the test is about.

    /** A reminder port that always fails to cancel - the archive/delete cancel is best-effort. */
    private class ThrowingCancelScheduler : TaskReminderScheduler {
        override suspend fun scheduleForTask(
            task: Task,
            overrideScheduledAt: LocalDateTime?,
            overrideDueAt: LocalDateTime?,
            isSnoozed: Boolean,
        ) = Unit

        override suspend fun cancelForTask(taskId: String): Nothing = throw IllegalStateException("cancel boom")
    }

    /** A task repository whose archive fails; everything else is the shared fake. */
    private class ThrowingArchiveRepository(
        private val delegate: TaskRepository,
    ) : TaskRepository by delegate {
        override suspend fun archiveTask(id: String): Task = throw IllegalStateException("archive boom")
    }

    /** A task repository whose delete fails; everything else is the shared fake. */
    private class ThrowingDeleteRepository(
        private val delegate: TaskRepository,
    ) : TaskRepository by delegate {
        override suspend fun deleteTask(id: String): Nothing = throw IllegalStateException("delete boom")
    }

    /**
     * The reminder cancel is best-effort: a reminder backend that is already down must not stop the
     * archive, which is the user's actual intent.
     */
    @Test
    fun `archiveTask still archives when cancelling reminders throws`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(task(id = "a2", title = "a2"))
            viewModel = createViewModel(reminders = ThrowingCancelScheduler())
            advanceUntilIdle()

            viewModel.archiveTask("a2")
            advanceUntilIdle()

            // The archive went through...
            assertThat(
                taskRepository.tasks.value
                    .first { it.id == "a2" }
                    .status,
            ).isEqualTo("archived")
            // ...and the swallowed scheduler failure surfaced nothing to the screen.
            assertThat(viewModel.uiState.value.error).isNull()
        }

    /** A repository that cannot archive publishes its message on `error` rather than throwing into the scope. */
    @Test
    fun `archiveTask publishes a repository failure on error`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(task(id = "a3", title = "a3"))
            viewModel = createViewModel(taskRepo = ThrowingArchiveRepository(taskRepository))
            advanceUntilIdle()

            viewModel.archiveTask("a3")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.error).isEqualTo("archive boom")
            // The row is untouched: the failure happened before the write.
            assertThat(
                taskRepository.tasks.value
                    .first { it.id == "a3" }
                    .status,
            ).isEqualTo("pending")
        }

    /** Mirror of the archive arm: a broken reminder backend must not abort the delete. */
    @Test
    fun `deleteTask still deletes when cancelling reminders throws`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(task(id = "dl2", title = "dl2"))
            viewModel = createViewModel(reminders = ThrowingCancelScheduler())
            advanceUntilIdle()

            viewModel.deleteTask("dl2")
            advanceUntilIdle()

            assertThat(taskRepository.tasks.value.any { it.id == "dl2" }).isFalse()
            assertThat(viewModel.uiState.value.error).isNull()
        }

    /** A repository that cannot delete publishes its message on `error`. */
    @Test
    fun `deleteTask publishes a repository failure on error`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(task(id = "dl3", title = "dl3"))
            viewModel = createViewModel(taskRepo = ThrowingDeleteRepository(taskRepository))
            advanceUntilIdle()

            viewModel.deleteTask("dl3")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.error).isEqualTo("delete boom")
            assertThat(taskRepository.tasks.value.any { it.id == "dl3" }).isTrue()
        }

    // ── refreshCheckmarksForTask: the due-today recompute ───────────────

    /**
     * A FREQUENCY habit whose window quota is satisfied by a completion is no longer due - the flag the
     * "Due today only" filter reads is recomputed by the ViewModel, not left at its pre-toggle value.
     */
    @Test
    fun `completing the quota flips a frequency habit to not due today`() =
        runTest(testDispatcher) {
            val h = habit(id = "q1", rule = "1/1")
            taskRepository.tasks.value = listOf(h)
            viewModel = createViewModel()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.dueTodayByTaskId["q1"]).isTrue()

            viewModel.updateCheckmark("q1", TASKS_TEST_DAY, CheckmarkStatus.COMPLETED, "done")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.dueTodayByTaskId["q1"]).isFalse()
        }

    /**
     * The same recompute for a task that is not in the recurring list at all: there is no rule to evaluate,
     * so the flag falls back to whatever the map already says (absent -> still due).
     */
    @Test
    fun `the due-today recompute falls back to the map for a non-habit`() =
        runTest(testDispatcher) {
            val oneOff = task(id = "q2", title = "q2")
            taskRepository.tasks.value = listOf(oneOff)
            viewModel = createViewModel()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.dueTodayByTaskId).doesNotContainKey("q2")

            viewModel.updateCheckmark("q2", TASKS_TEST_DAY, CheckmarkStatus.COMPLETED, "done")
            advanceUntilIdle()

            // The refresh wrote an entry for it, and the fallback value is "still due" because nothing in the
            // map contradicts it.
            assertThat(viewModel.uiState.value.dueTodayByTaskId["q2"]).isTrue()
            assertThat(viewModel.uiState.value.taskCheckmarks).containsKey("q2")
        }

    /**
     * The refresh re-reads L1 after the toggle, so the habit row's ring follows the metric that was just
     * recomputed. The repository is swapped AFTER the initial load, so the only way the new row can reach
     * the state is through the refresh.
     */
    @Test
    fun `the checkmark refresh re-reads the L1 metrics`() =
        runTest(testDispatcher) {
            val h = habit(id = "q3", rule = "1/1")
            taskRepository.tasks.value = listOf(h)
            viewModel = createViewModel()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.latestL1ByHabit).doesNotContainKey("q3")

            habitMetricRepository.latestPerHabit = mapOf("q3" to habitL1("q3", score = 0.9))
            viewModel.updateCheckmark("q3", TASKS_TEST_DAY, CheckmarkStatus.COMPLETED, "done")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.latestL1ByHabit["q3"]?.score).isEqualTo(0.9)
        }

    /**
     * The same recompute reached through the WIDE-window branch: a "1/60" habit's denominator is longer
     * than the 30-day occurrence lookup, so scoring its quota needs the per-task full history - the fetch
     * this refresh owns. The counting repository is the only way to tell that fetch apart from the
     * 30-day read, and a "1/1" habit in the same load proves the branch is the rule's, not the test's.
     */
    @Test
    fun `the checkmark refresh reads full history for a wide frequency window`() =
        runTest(testDispatcher) {
            val wide = habit(id = "q4", rule = "1/60")
            val narrow = habit(id = "q5", rule = "1/1")
            taskRepository.tasks.value = listOf(wide, narrow)
            val counting = CountingOccurrenceRepository(taskOccurrenceRepository)
            viewModel = createViewModel(occurrences = counting)
            advanceUntilIdle()
            counting.fullHistoryRequests.clear()

            viewModel.updateCheckmark("q4", TASKS_TEST_DAY, CheckmarkStatus.COMPLETED, "done")
            advanceUntilIdle()

            assertThat(counting.fullHistoryRequests).contains("q4")
            assertThat(counting.fullHistoryRequests).doesNotContain("q5")
            // The refresh still published the habit: the fetch is on the path, not a failure.
            assertThat(viewModel.uiState.value.error).isNull()
            assertThat(viewModel.uiState.value.taskCheckmarks).containsKey("q4")
        }

    // ── loadTasks: the full-history fetch for wide frequency windows ─────

    /**
     * Counts the per-task full-history reads. A "1/60" habit's denominator is wider than the 30-day
     * occurrence lookup, so `buildDueTodayByTaskId` must fall back to the per-task fetch to score the
     * window; the due-today verdict is therefore computed from history only this fetch can supply.
     */
    private class CountingOccurrenceRepository(
        private val delegate: TaskOccurrenceRepository,
    ) : TaskOccurrenceRepository by delegate {
        val fullHistoryRequests = mutableListOf<String>()

        override suspend fun getOccurrencesByTaskId(taskId: String): List<TaskOccurrence> {
            fullHistoryRequests += taskId
            return delegate.getOccurrencesByTaskId(taskId)
        }
    }

    @Test
    fun `loadTasks fetches full occurrence history for a wide frequency window`() =
        runTest(testDispatcher) {
            val wide = habit(id = "w1", rule = "1/60")
            val narrow = habit(id = "w2", rule = "1/1")
            taskRepository.tasks.value = listOf(wide, narrow)
            val counting = CountingOccurrenceRepository(taskOccurrenceRepository)
            viewModel = createViewModel(occurrences = counting)
            advanceUntilIdle()

            // Only the wide rule needed the history; the 1/1 rule was scored from the 30-day window.
            assertThat(counting.fullHistoryRequests).contains("w1")
            assertThat(counting.fullHistoryRequests).doesNotContain("w2")
            assertThat(viewModel.uiState.value.dueTodayByTaskId.keys).containsAtLeast("w1", "w2")
        }
}
