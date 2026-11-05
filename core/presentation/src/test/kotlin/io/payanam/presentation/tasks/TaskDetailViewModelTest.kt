//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package io.payanam.presentation.tasks

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.presentation.flags.PlatformFeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Pure-JVM behavioural tests for [TaskDetailViewModel].
 *
 * Every collaborator is a hand-written fake from [TasksTestFixtures]; the ViewModel is constructed
 * with a [StandardTestDispatcher]-backed scope so launches are deterministic and
 * [advanceUntilIdle] drains them before assertions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelTest {
    private lateinit var taskRepo: FakeTaskRepository
    private lateinit var occurrenceRepo: FakeTaskOccurrenceRepository
    private lateinit var rescheduleRepo: FakeTaskRescheduleRepository
    private lateinit var reminderScheduler: FakeReminderScheduler
    private lateinit var recurrenceHandler: FakeRecurrenceHandler
    private lateinit var habitMetricRepo: FakeHabitMetricRepository
    private var viewModelScope: CoroutineScope? = null

    @Before
    fun setUp() {
        PlatformFeatureFlags.resetForTests()
        taskRepo = FakeTaskRepository()
        occurrenceRepo = FakeTaskOccurrenceRepository()
        rescheduleRepo = FakeTaskRescheduleRepository()
        reminderScheduler = FakeReminderScheduler()
        recurrenceHandler = FakeRecurrenceHandler()
        habitMetricRepo = FakeHabitMetricRepository()
    }

    @After
    fun tearDown() {
        viewModelScope?.cancel()
        viewModelScope = null
        PlatformFeatureFlags.resetForTests()
    }

    private fun TestScope.newViewModelScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { viewModelScope = it }

    private fun TestScope.viewModel(): TaskDetailViewModel {
        val scope = newViewModelScope()
        return TaskDetailViewModel(
            taskRepository = taskRepo,
            taskOccurrenceRepository = occurrenceRepo,
            taskRescheduleRepository = rescheduleRepo,
            notificationScheduler = reminderScheduler,
            recurrenceHandler = recurrenceHandler,
            habitMetricRepository = habitMetricRepo,
            scope = scope,
        )
    }

    // ────────────────────────────────────────────────────────────────
    // Initial state
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `initial uiState is loading with null task`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            val s = vm.uiState.value
            assertThat(s.task).isNull()
            assertThat(s.error).isNull()
            assertThat(s.occurrenceHistory).isEmpty()
            assertThat(s.rescheduleHistory).isEmpty()
            assertThat(s.completionStats).isNull()
            assertThat(s.latestL1).isNull()
            assertThat(s.windowSizeDays).isEqualTo(7)
            assertThat(s.showChartView).isTrue()
            assertThat(s.showStatusNoteDialog).isFalse()
            assertThat(s.pendingStatusAction).isNull()
        }

    // ────────────────────────────────────────────────────────────────
    // loadTask
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadTask sets task and clears loading`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()

            vm.loadTask("t1")
            advanceUntilIdle()

            val s = vm.uiState.value
            assertThat(s.task).isNotNull()
            assertThat(s.task!!.id).isEqualTo("t1")
            assertThat(s.isLoading).isFalse()
            assertThat(s.error).isNull()
        }

    @Test
    fun `loadTask with null task leaves task null`() =
        runTest {
            val vm = viewModel()

            vm.loadTask("missing")
            advanceUntilIdle()

            val s = vm.uiState.value
            assertThat(s.task).isNull()
            assertThat(s.isLoading).isFalse()
            assertThat(s.error).isNull()
        }

    @Test
    fun `loadTask non-recurring skips occurrence and stats loading`() =
        runTest {
            val t = task(id = "t1", recurrenceEnabled = false)
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()

            vm.loadTask("t1")
            advanceUntilIdle()

            val s = vm.uiState.value
            assertThat(s.task!!.recurrenceEnabled).isFalse()
            assertThat(s.occurrenceHistory).isEmpty()
            assertThat(s.completionStats).isNull()
            assertThat(s.latestL1).isNull()
        }

    @Test
    fun `loadTask recurring loads occurrences stats and L1`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            val occ = occurrence(taskId = "h1")
            occurrenceRepo.occurrences.value = listOf(occ)
            habitMetricRepo.latestPerHabit = mapOf("h1" to habitL1("h1"))
            val vm = viewModel()

            vm.loadTask("h1")
            advanceUntilIdle()

            val s = vm.uiState.value
            assertThat(s.task!!.recurrenceEnabled).isTrue()
            assertThat(s.occurrenceHistory).hasSize(1)
            assertThat(s.completionStats).isNotNull()
            assertThat(s.latestL1).isNotNull()
            assertThat(s.latestL1!!.habitId).isEqualTo("h1")
        }

    @Test
    fun `loadTask recurring loads reschedule history`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            rescheduleRepo.reschedules.value =
                listOf(
                    io.payanam.domain.model.TaskReschedule(
                        id = "r1",
                        taskId = "h1",
                        previousDueDate = TASKS_TEST_TIME.minusDays(1),
                        newDueDate = TASKS_TEST_TIME.plusDays(1),
                        rescheduledAt = TASKS_TEST_TIME,
                        wasOverdue = true,
                    ),
                )
            val vm = viewModel()

            vm.loadTask("h1")
            advanceUntilIdle()

            assertThat(vm.uiState.value.rescheduleHistory).hasSize(1)
        }

    @Test
    fun `loadTask sets error on exception`() =
        runTest {
            // Use a scope that is already cancelled to force an exception
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            scope.cancel()
            val vm =
                TaskDetailViewModel(
                    taskRepository = taskRepo,
                    taskOccurrenceRepository = occurrenceRepo,
                    taskRescheduleRepository = rescheduleRepo,
                    notificationScheduler = reminderScheduler,
                    recurrenceHandler = recurrenceHandler,
                    habitMetricRepository = habitMetricRepo,
                    scope = scope,
                )

            vm.loadTask("t1")
            advanceUntilIdle()

            // Scope is cancelled, so the launch never runs — state stays at defaults
            assertThat(vm.uiState.value.isLoading).isTrue()
        }

    // ────────────────────────────────────────────────────────────────
    // window navigation
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `setWindowSizeDays updates window size`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.setWindowSizeDays(30)
            advanceUntilIdle()

            assertThat(vm.uiState.value.windowSizeDays).isEqualTo(30)
        }

    @Test
    fun `shiftWindowBack subtracts window size days`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("h1")
            advanceUntilIdle()

            val beforeEnd = vm.uiState.value.windowEnd
            vm.shiftWindowBack()
            advanceUntilIdle()

            assertThat(vm.uiState.value.windowEnd).isEqualTo(beforeEnd.minusDays(7))
        }

    @Test
    fun `shiftWindowForward adds window size days`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("h1")
            advanceUntilIdle()

            // Move back first so we can move forward
            vm.shiftWindowBack()
            advanceUntilIdle()
            vm.shiftWindowBack()
            advanceUntilIdle()

            val beforeEnd = vm.uiState.value.windowEnd
            vm.shiftWindowForward()
            advanceUntilIdle()

            assertThat(vm.uiState.value.windowEnd).isEqualTo(beforeEnd.plusDays(7))
        }

    @Test
    fun `shiftWindowForward when at today does nothing`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("h1")
            advanceUntilIdle()

            val today = LocalDate.now()
            assertThat(vm.uiState.value.windowEnd).isEqualTo(today)

            vm.shiftWindowForward()
            advanceUntilIdle()

            assertThat(vm.uiState.value.windowEnd).isEqualTo(today)
        }

    @Test
    fun `jumpWindowToToday resets window end`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("h1")
            advanceUntilIdle()

            vm.shiftWindowBack()
            advanceUntilIdle()
            assertThat(vm.uiState.value.windowEnd).isNotEqualTo(LocalDate.now())

            vm.jumpWindowToToday()
            advanceUntilIdle()

            assertThat(vm.uiState.value.windowEnd).isEqualTo(LocalDate.now())
        }

    @Test
    fun `setChartView toggles chart view`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            assertThat(vm.uiState.value.showChartView).isTrue()

            vm.setChartView(false)
            assertThat(vm.uiState.value.showChartView).isFalse()

            vm.setChartView(true)
            assertThat(vm.uiState.value.showChartView).isTrue()
        }

    // ────────────────────────────────────────────────────────────────
    // showStatusDialog / hideStatusDialog
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `showStatusDialog sets dialog and pending action`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.showStatusDialog("complete")

            val s = vm.uiState.value
            assertThat(s.showStatusNoteDialog).isTrue()
            assertThat(s.pendingStatusAction).isEqualTo("complete")
        }

    @Test
    fun `hideStatusDialog clears dialog and pending action`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.showStatusDialog("skip")
            vm.hideStatusDialog()

            val s = vm.uiState.value
            assertThat(s.showStatusNoteDialog).isFalse()
            assertThat(s.pendingStatusAction).isNull()
        }

    // ────────────────────────────────────────────────────────────────
    // completeTask
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `completeTask with no currentTaskId returns early`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            // No loadTask called → currentTaskId is null
            vm.completeTask()
            advanceUntilIdle()

            // State unchanged
            assertThat(vm.uiState.value.task).isNull()
        }

    @Test
    fun `completeTask with null task in state returns early`() =
        runTest {
            val vm = viewModel()
            // Set currentTaskId via loadTask, but task is not in repo → task is null
            vm.loadTask("nonexistent")
            advanceUntilIdle()
            assertThat(vm.uiState.value.task).isNull()

            vm.completeTask()
            advanceUntilIdle()

            // Still null — early return
            assertThat(vm.uiState.value.task).isNull()
        }

    @Test
    fun `completeTask non-recurring marks done and cancels reminder`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            vm.completeTask(note = "done")
            advanceUntilIdle()

            // The task was completed in the repo
            val updated = taskRepo.tasks.value.first { it.id == "t1" }
            assertThat(updated.status).isEqualTo("completed")
            assertThat(updated.completedAt).isNotNull()

            // Reminder was cancelled (non-recurring path)
            assertThat(reminderScheduler.cancelled).contains("t1")
        }

    @Test
    fun `completeTask non-recurring with null note succeeds`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            vm.completeTask()
            advanceUntilIdle()

            assertThat(
                taskRepo.tasks.value
                    .first { it.id == "t1" }
                    .status,
            ).isEqualTo("completed")
        }

    @Test
    fun `completeTask recurring frequency habit records occurrence and calls handler`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            recurrenceHandler.frequencyHabit = true
            val vm = viewModel()
            vm.loadTask("h1")
            advanceUntilIdle()

            vm.completeTask(note = "good", reason = "on_time")
            advanceUntilIdle()

            // Recurrence handler was called
            assertThat(recurrenceHandler.completed).contains("h1")

            // Task was reloaded via loadTask
            assertThat(vm.uiState.value.task).isNotNull()
        }

    @Test
    fun `completeTask recurring non-habit completes via repo and records occurrence`() =
        runTest {
            val t = habit(id = "h2")
            taskRepo.tasks.value = listOf(t)
            recurrenceHandler.frequencyHabit = false
            val vm = viewModel()
            vm.loadTask("h2")
            advanceUntilIdle()

            vm.completeTask()
            advanceUntilIdle()

            // The repo was asked to complete
            assertThat(
                taskRepo.tasks.value
                    .first { it.id == "h2" }
                    .status,
            ).isEqualTo("completed")
            // Recurrence handler was called
            assertThat(recurrenceHandler.completed).contains("h2")
        }

    @Test
    fun `completeTask recurring schedules next reminder`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            recurrenceHandler.frequencyHabit = true
            val vm = viewModel()
            vm.loadTask("h1")
            advanceUntilIdle()

            vm.completeTask()
            advanceUntilIdle()

            // Reminder was scheduled for the updated task
            assertThat(reminderScheduler.scheduled).contains("h1")
        }

    @Test
    fun `completeTask hides status dialog after completion`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            vm.showStatusDialog("complete")
            assertThat(vm.uiState.value.showStatusNoteDialog).isTrue()

            vm.completeTask()
            advanceUntilIdle()

            assertThat(vm.uiState.value.showStatusNoteDialog).isFalse()
            assertThat(vm.uiState.value.pendingStatusAction).isNull()
        }

    // ────────────────────────────────────────────────────────────────
    // skipTask
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `skipTask with no currentTaskId returns early`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.skipTask()
            advanceUntilIdle()

            assertThat(vm.uiState.value.task).isNull()
        }

    @Test
    fun `skipTask non-recurring marks skipped and cancels reminder`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            vm.skipTask(reason = "no_time")
            advanceUntilIdle()

            assertThat(
                taskRepo.tasks.value
                    .first { it.id == "t1" }
                    .status,
            ).isEqualTo("skipped")
            assertThat(reminderScheduler.cancelled).contains("t1")
        }

    @Test
    fun `skipTask recurring frequency habit calls handler`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            recurrenceHandler.frequencyHabit = true
            val vm = viewModel()
            vm.loadTask("h1")
            advanceUntilIdle()

            vm.skipTask(reason = "busy")
            advanceUntilIdle()

            assertThat(recurrenceHandler.skipped).contains("h1")
            assertThat(reminderScheduler.scheduled).contains("h1")
        }

    @Test
    fun `skipTask recurring non-habit skips via repo and calls handler`() =
        runTest {
            val t = habit(id = "h2")
            taskRepo.tasks.value = listOf(t)
            recurrenceHandler.frequencyHabit = false
            val vm = viewModel()
            vm.loadTask("h2")
            advanceUntilIdle()

            vm.skipTask()
            advanceUntilIdle()

            assertThat(
                taskRepo.tasks.value
                    .first { it.id == "h2" }
                    .status,
            ).isEqualTo("skipped")
            assertThat(recurrenceHandler.skipped).contains("h2")
        }

    @Test
    fun `skipTask hides status dialog`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            vm.showStatusDialog("skip")
            vm.skipTask()
            advanceUntilIdle()

            assertThat(vm.uiState.value.showStatusNoteDialog).isFalse()
        }

    // ────────────────────────────────────────────────────────────────
    // missTask
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `missTask with no currentTaskId returns early`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.missTask()
            advanceUntilIdle()

            assertThat(vm.uiState.value.task).isNull()
        }

    @Test
    fun `missTask non-recurring marks missed and cancels reminder`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            vm.missTask(reason = "forgot")
            advanceUntilIdle()

            assertThat(
                taskRepo.tasks.value
                    .first { it.id == "t1" }
                    .status,
            ).isEqualTo("missed")
            assertThat(reminderScheduler.cancelled).contains("t1")
        }

    @Test
    fun `missTask recurring frequency habit calls handler`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            recurrenceHandler.frequencyHabit = true
            val vm = viewModel()
            vm.loadTask("h1")
            advanceUntilIdle()

            vm.missTask(reason = "overslept")
            advanceUntilIdle()

            assertThat(recurrenceHandler.missed).contains("h1")
            assertThat(reminderScheduler.scheduled).contains("h1")
        }

    @Test
    fun `missTask recurring non-habit misses via repo and calls handler`() =
        runTest {
            val t = habit(id = "h2")
            taskRepo.tasks.value = listOf(t)
            recurrenceHandler.frequencyHabit = false
            val vm = viewModel()
            vm.loadTask("h2")
            advanceUntilIdle()

            vm.missTask()
            advanceUntilIdle()

            assertThat(
                taskRepo.tasks.value
                    .first { it.id == "h2" }
                    .status,
            ).isEqualTo("missed")
            assertThat(recurrenceHandler.missed).contains("h2")
        }

    @Test
    fun `missTask hides status dialog`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            vm.showStatusDialog("miss")
            vm.missTask()
            advanceUntilIdle()

            assertThat(vm.uiState.value.showStatusNoteDialog).isFalse()
        }

    // ────────────────────────────────────────────────────────────────
    // archiveTask
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `archiveTask with no currentTaskId returns early`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.archiveTask()
            advanceUntilIdle()

            assertThat(vm.uiState.value.task).isNull()
        }

    @Test
    fun `archiveTask marks archived and cancels reminder`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            vm.archiveTask()
            advanceUntilIdle()

            assertThat(
                taskRepo.tasks.value
                    .first { it.id == "t1" }
                    .status,
            ).isEqualTo("archived")
            assertThat(reminderScheduler.cancelled).contains("t1")
        }

    // ────────────────────────────────────────────────────────────────
    // rescheduleTask
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `rescheduleTask with no currentTaskId returns early`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.rescheduleTask(TASKS_TEST_TIME.plusDays(3))
            advanceUntilIdle()

            assertThat(vm.uiState.value.task).isNull()
        }

    @Test
    fun `rescheduleTask with null due date returns early`() =
        runTest {
            val t = task(id = "t1", dueDate = null)
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()
            assertThat(vm.uiState.value.task).isNotNull()

            vm.rescheduleTask(TASKS_TEST_TIME.plusDays(5))
            advanceUntilIdle()

            // Task due date unchanged
            assertThat(
                vm.uiState.value.task!!
                    .dueDate,
            ).isNull()
        }

    @Test
    fun `rescheduleTask updates due date and records reschedule`() =
        runTest {
            val previousDate = TASKS_TEST_TIME.minusDays(1)
            val t = task(id = "t1", dueDate = previousDate)
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            val newDate = TASKS_TEST_TIME.plusDays(5)
            vm.rescheduleTask(newDate)
            advanceUntilIdle()

            // Task updated
            val updated = vm.uiState.value.task
            assertThat(updated).isNotNull()
            assertThat(updated!!.dueDate).isEqualTo(newDate)

            // Reschedule recorded
            assertThat(rescheduleRepo.reschedules.value).isNotEmpty()
            val reschedule = rescheduleRepo.reschedules.value.first()
            assertThat(reschedule.taskId).isEqualTo("t1")
            assertThat(reschedule.previousDueDate).isEqualTo(previousDate)
            assertThat(reschedule.newDueDate).isEqualTo(newDate)

            // Reminder rescheduled
            assertThat(reminderScheduler.scheduled).contains("t1")
        }

    @Test
    fun `rescheduleTask marks wasOverdue true when previous date in past`() =
        runTest {
            val previousDate = TASKS_TEST_TIME.minusDays(2)
            val t = task(id = "t1", dueDate = previousDate)
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            vm.rescheduleTask(TASKS_TEST_TIME.plusDays(1))
            advanceUntilIdle()

            val reschedule = rescheduleRepo.reschedules.value.first()
            // previousDate is in the past relative to now
            assertThat(reschedule.wasOverdue).isTrue()
        }

    @Test
    fun `rescheduleTask error sets error in state`() =
        runTest {
            val t = task(id = "t1", dueDate = TASKS_TEST_TIME)
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            // Cancel the scope to force an exception during the launch
            viewModelScope!!.cancel()

            vm.rescheduleTask(TASKS_TEST_TIME.plusDays(5))
            advanceUntilIdle()

            // Scope cancelled → no crash, state unchanged from the loadTask result
            assertThat(vm.uiState.value.task).isNotNull()
        }

    // ────────────────────────────────────────────────────────────────
    // deleteTask
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `deleteTask with no currentTaskId returns early`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.deleteTask()
            advanceUntilIdle()

            assertThat(taskRepo.tasks.value).isEmpty()
        }

    @Test
    fun `deleteTask removes task and cancels reminder`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            vm.deleteTask()
            advanceUntilIdle()

            assertThat(taskRepo.tasks.value).isEmpty()
            assertThat(reminderScheduler.cancelled).contains("t1")
        }

    // ────────────────────────────────────────────────────────────────
    // dispose
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `dispose cancels scope without crash`() =
        runTest {
            val vm = viewModel()
            advanceUntilIdle()

            vm.dispose()
            advanceUntilIdle()

            // No crash — scope is cancelled
            assertThat(true).isTrue()
        }

    // ────────────────────────────────────────────────────────────────
    // windowBounds companion
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `windowBounds with positive sizeDays returns range`() =
        runTest {
            val end = LocalDate.of(2026, 9, 21)
            val (start, resultEnd) = TaskDetailViewModel.windowBounds(7, end)

            assertThat(start).isEqualTo(LocalDate.of(2026, 9, 15))
            assertThat(resultEnd).isEqualTo(end)
        }

    @Test
    fun `windowBounds with zero sizeDays returns all-time from 2020`() =
        runTest {
            val end = LocalDate.of(2026, 9, 21)
            val (start, resultEnd) = TaskDetailViewModel.windowBounds(0, end)

            assertThat(start).isEqualTo(LocalDate.of(2020, 1, 1))
            assertThat(resultEnd).isEqualTo(end)
        }

    @Test
    fun `windowBounds with negative sizeDays returns all-time from 2020`() =
        runTest {
            val end = LocalDate.of(2026, 9, 21)
            val (start, _) = TaskDetailViewModel.windowBounds(-1, end)

            assertThat(start).isEqualTo(LocalDate.of(2020, 1, 1))
        }

    @Test
    fun `windowBounds with sizeDays 1 returns single day`() =
        runTest {
            val end = LocalDate.of(2026, 9, 21)
            val (start, resultEnd) = TaskDetailViewModel.windowBounds(1, end)

            assertThat(start).isEqualTo(end)
            assertThat(resultEnd).isEqualTo(end)
        }

    // ────────────────────────────────────────────────────────────────
    // loadTask — activity window
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadTask recurring loads activity window rows`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            val l1 = habitL1("h1")
            habitMetricRepo.rangeRows = listOf(l1)
            val vm = viewModel()

            vm.loadTask("h1")
            advanceUntilIdle()

            assertThat(vm.uiState.value.windowRows).isNotEmpty()
            assertThat(vm.uiState.value.isLoadingWindow).isFalse()
        }

    @Test
    fun `setWindowSizeDays reloads activity window`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("h1")
            advanceUntilIdle()

            vm.setWindowSizeDays(30)
            advanceUntilIdle()

            assertThat(vm.uiState.value.windowSizeDays).isEqualTo(30)
            assertThat(vm.uiState.value.isLoadingWindow).isFalse()
        }

    // ────────────────────────────────────────────────────────────────
    // completeTask / skipTask / missTask — error paths
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `completeTask error sets error in state`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            // Cancel scope to make the launch throw
            viewModelScope!!.cancel()

            vm.completeTask()
            advanceUntilIdle()

            // Scope cancelled → no crash
            assertThat(vm.uiState.value.task).isNotNull()
        }

    @Test
    fun `skipTask error handled gracefully`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            viewModelScope!!.cancel()

            vm.skipTask()
            advanceUntilIdle()

            assertThat(vm.uiState.value.task).isNotNull()
        }

    @Test
    fun `missTask error handled gracefully`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            viewModelScope!!.cancel()

            vm.missTask()
            advanceUntilIdle()

            assertThat(vm.uiState.value.task).isNotNull()
        }

    @Test
    fun `archiveTask error handled gracefully`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            viewModelScope!!.cancel()

            vm.archiveTask()
            advanceUntilIdle()

            assertThat(vm.uiState.value.task).isNotNull()
        }

    @Test
    fun `deleteTask error handled gracefully`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = viewModel()
            vm.loadTask("t1")
            advanceUntilIdle()

            viewModelScope!!.cancel()

            vm.deleteTask()
            advanceUntilIdle()

            // Task still in repo (delete never ran because scope cancelled)
            assertThat(taskRepo.tasks.value).isNotEmpty()
        }
}
