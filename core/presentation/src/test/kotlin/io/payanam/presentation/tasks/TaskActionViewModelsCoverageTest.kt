//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package io.payanam.presentation.tasks

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.HabitL1Summary
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskInput
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TaskReschedule
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.domain.repository.HabitMetricRepository
import io.payanam.domain.repository.TaskOccurrenceRepository
import io.payanam.domain.repository.TaskRepository
import io.payanam.domain.repository.TaskRescheduleRepository
import io.payanam.presentation.flags.FeatureFlagsState
import io.payanam.presentation.flags.PlatformFeatureFlags
import io.payanam.scoring.CompletionStats
import io.payanam.scoring.port.TaskRecurrenceHandler
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

// ═══════════════════════════════════════════════════════════════════
// Throwing wrapper fakes — delegate to the hand-written fakes from
// TasksTestFixtures but can be toggled to throw on demand so the
// ViewModel's catch blocks actually execute.
// ═══════════════════════════════════════════════════════════════════

/** [TaskRepository] that delegates to [d] and can be toggled to throw on individual methods. */
internal class ThrowingTaskRepository(
    private val d: FakeTaskRepository = FakeTaskRepository(),
) : TaskRepository by d {
    val tasks get() = d.tasks
    var throwOnGetTaskById = false
    var throwOnCreateTask = false
    var throwOnUpdateTask = false
    var throwOnCompleteTask = false
    var throwOnSkipTask = false
    var throwOnMissTask = false
    var throwOnArchiveTask = false
    var throwOnDeleteTask = false

    override suspend fun getTaskById(id: String): Task? {
        if (throwOnGetTaskById) throw RuntimeException("getTaskById error")
        return d.getTaskById(id)
    }

    override suspend fun createTask(input: TaskInput): Task {
        if (throwOnCreateTask) throw RuntimeException("createTask error")
        return d.createTask(input)
    }

    override suspend fun updateTask(
        id: String,
        input: TaskInput,
    ): Task {
        if (throwOnUpdateTask) throw RuntimeException("updateTask error")
        return d.updateTask(id, input)
    }

    override suspend fun completeTask(
        id: String,
        note: String?,
    ): Task {
        if (throwOnCompleteTask) throw RuntimeException("completeTask error")
        return d.completeTask(id, note)
    }

    override suspend fun skipTask(
        id: String,
        note: String?,
    ): Task {
        if (throwOnSkipTask) throw RuntimeException("skipTask error")
        return d.skipTask(id, note)
    }

    override suspend fun missTask(
        id: String,
        note: String?,
    ): Task {
        if (throwOnMissTask) throw RuntimeException("missTask error")
        return d.missTask(id, note)
    }

    override suspend fun archiveTask(id: String): Task {
        if (throwOnArchiveTask) throw RuntimeException("archiveTask error")
        return d.archiveTask(id)
    }

    override suspend fun deleteTask(id: String) {
        if (throwOnDeleteTask) throw RuntimeException("deleteTask error")
        d.deleteTask(id)
    }
}

/** [TaskReminderScheduler] that delegates to [d] and can be toggled to throw. */
internal class ThrowingReminderScheduler(
    private val d: FakeReminderScheduler = FakeReminderScheduler(),
) : TaskReminderScheduler {
    val scheduled get() = d.scheduled
    val cancelled get() = d.cancelled
    var throwOnSchedule = false
    var throwOnCancel = false

    override suspend fun scheduleForTask(
        task: Task,
        overrideScheduledAt: LocalDateTime?,
        overrideDueAt: LocalDateTime?,
        isSnoozed: Boolean,
    ) {
        if (throwOnSchedule) throw RuntimeException("schedule error")
        d.scheduleForTask(task, overrideScheduledAt, overrideDueAt, isSnoozed)
    }

    override suspend fun cancelForTask(taskId: String) {
        if (throwOnCancel) throw RuntimeException("cancel error")
        d.cancelForTask(taskId)
    }
}

/** [TaskOccurrenceRepository] that delegates to [d] and can be toggled to throw. */
internal class ThrowingOccurrenceRepository(
    private val d: FakeTaskOccurrenceRepository = FakeTaskOccurrenceRepository(),
) : TaskOccurrenceRepository by d {
    val occurrences get() = d.occurrences
    var throwOnGetByTaskId = false
    var throwOnRecordOccurrence = false

    override suspend fun getOccurrencesByTaskId(taskId: String): List<TaskOccurrence> {
        if (throwOnGetByTaskId) throw RuntimeException("getOccurrences error")
        return d.getOccurrencesByTaskId(taskId)
    }

    override suspend fun recordOccurrence(occurrence: TaskOccurrence) {
        if (throwOnRecordOccurrence) throw RuntimeException("recordOccurrence error")
        d.recordOccurrence(occurrence)
    }
}

/** [TaskRescheduleRepository] that delegates to [d] and can be toggled to throw. */
internal class ThrowingRescheduleRepository(
    private val d: FakeTaskRescheduleRepository = FakeTaskRescheduleRepository(),
) : TaskRescheduleRepository by d {
    val reschedules get() = d.reschedules
    var throwOnGetByTaskId = false

    override suspend fun getReschedulesByTaskId(taskId: String): List<TaskReschedule> {
        if (throwOnGetByTaskId) throw RuntimeException("reschedule error")
        return d.getReschedulesByTaskId(taskId)
    }
}

/** [TaskRecurrenceHandler] that delegates to [d] and can be toggled to throw on getCompletionStats. */
internal class ThrowingRecurrenceHandler(
    val delegate: FakeRecurrenceHandler = FakeRecurrenceHandler(),
) : TaskRecurrenceHandler by delegate {
    var throwOnGetCompletionStats = false

    override suspend fun getCompletionStats(task: Task): CompletionStats {
        if (throwOnGetCompletionStats) throw RuntimeException("getCompletionStats error")
        return delegate.getCompletionStats(task)
    }
}

/** [HabitMetricRepository] that delegates to [d] and can be toggled to throw. */
internal class ThrowingHabitMetricRepository(
    private val d: FakeHabitMetricRepository = FakeHabitMetricRepository(),
) : HabitMetricRepository by d {
    var throwOnGetLatestForHabit = false

    override suspend fun getLatestForHabit(habitId: String): HabitL1Summary? {
        if (throwOnGetLatestForHabit) throw RuntimeException("metric error")
        return d.getLatestForHabit(habitId)
    }
}

// ═══════════════════════════════════════════════════════════════════
// Tests — catch-block, inner-catch, and edge-case paths that the
// first-wave tests leave uncovered because they cancel the scope
// instead of making fakes throw.
// ═══════════════════════════════════════════════════════════════════

/**
 * Coverage tests for the catch-block, inner-catch, and edge-case paths
 * in [TaskDetailViewModel], [AddTaskViewModel], and [EditTaskViewModel].
 *
 * Every collaborator is a hand-written fake from [TasksTestFixtures] or a
 * [Throwing*] wrapper that delegates to it; the ViewModel is constructed
 * with a [StandardTestDispatcher]-backed scope so launches are deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskActionViewModelsCoverageTest {
    private lateinit var taskRepo: ThrowingTaskRepository
    private lateinit var occurrenceRepo: ThrowingOccurrenceRepository
    private lateinit var rescheduleRepo: ThrowingRescheduleRepository
    private lateinit var scheduler: ThrowingReminderScheduler
    private lateinit var recurrenceHandler: ThrowingRecurrenceHandler
    private lateinit var habitMetricRepo: ThrowingHabitMetricRepository
    private lateinit var tagRepo: FakeTagRepository
    private lateinit var scoreCalc: FakeScoreCalculator
    private lateinit var scoreRollupTrigger: FakeScoreRollupTrigger
    private var vmScope: CoroutineScope? = null

    @Before
    fun setUp() {
        PlatformFeatureFlags.resetForTests()
        taskRepo = ThrowingTaskRepository()
        occurrenceRepo = ThrowingOccurrenceRepository()
        rescheduleRepo = ThrowingRescheduleRepository()
        scheduler = ThrowingReminderScheduler()
        recurrenceHandler = ThrowingRecurrenceHandler()
        habitMetricRepo = ThrowingHabitMetricRepository()
        tagRepo = FakeTagRepository()
        scoreCalc = FakeScoreCalculator()
        scoreRollupTrigger = FakeScoreRollupTrigger()
    }

    @After
    fun tearDown() {
        vmScope?.cancel()
        vmScope = null
        PlatformFeatureFlags.resetForTests()
    }

    private fun TestScope.newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { vmScope = it }

    private fun TestScope.detailVm(): TaskDetailViewModel {
        val scope = newScope()
        return TaskDetailViewModel(
            taskRepository = taskRepo,
            taskOccurrenceRepository = occurrenceRepo,
            taskRescheduleRepository = rescheduleRepo,
            notificationScheduler = scheduler,
            recurrenceHandler = recurrenceHandler,
            habitMetricRepository = habitMetricRepo,
            scope = scope,
        )
    }

    private fun TestScope.addVm(): AddTaskViewModel {
        val scope = newScope()
        return AddTaskViewModel(
            taskRepository = taskRepo,
            tagRepository = tagRepo,
            notificationScheduler = scheduler,
            scoreCalculator = scoreCalc,
            resultDispatcher = StandardTestDispatcher(testScheduler),
            scope = scope,
        )
    }

    private fun TestScope.editVm(): EditTaskViewModel {
        val scope = newScope()
        return EditTaskViewModel(
            taskRepository = taskRepo,
            tagRepository = tagRepo,
            notificationScheduler = scheduler,
            scoreRollupTrigger = scoreRollupTrigger,
            scoreCalculator = scoreCalc,
            scope = scope,
        )
    }

    private fun editInput(
        title: String = "Edited",
        recurrenceEnabled: Boolean = false,
        tags: List<String> = emptyList(),
    ) = EditTaskInput(
        title = title,
        description = null,
        dimensionId = "dim_work",
        dimensionLabel = "Work",
        dueDate = TASKS_TEST_TIME,
        impactLevel = "Moderate Impact",
        goalAlignment = "Moderate Alignment",
        energyLevel = "Moderate",
        controlLevel = "Office/Colleagues Dependent",
        durationMinutes = 60,
        recurrenceEnabled = recurrenceEnabled,
        recurrenceRule = if (recurrenceEnabled) "1/1" else null,
        notificationMode = "default",
        customNotificationMinutes = null,
        explicitUrgency = null,
        focusRequired = null,
        blockedReason = null,
        externalDependency = null,
        tags = tags,
    )

    // ────────────────────────────────────────────────────────────────
    // TaskDetailViewModel — load* catch blocks
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadTask — repo throws — error surfaced in state`() =
        runTest {
            val vm = detailVm()
            taskRepo.throwOnGetTaskById = true

            vm.loadTask("t1")
            advanceUntilIdle()

            val s = vm.uiState.value
            assertThat(s.isLoading).isFalse()
            assertThat(s.error).isNotNull()
            assertThat(s.task).isNull()
        }

    @Test
    fun `loadTask recurring — L1 metric throws — latestL1 null via runCatching`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            habitMetricRepo.throwOnGetLatestForHabit = true
            val vm = detailVm()

            vm.loadTask("h1")
            advanceUntilIdle()

            // runCatching swallows the exception — latestL1 stays null, no error in state
            assertThat(vm.uiState.value.latestL1).isNull()
            assertThat(vm.uiState.value.error).isNull()
            assertThat(vm.uiState.value.task).isNotNull()
        }

    @Test
    fun `loadCompletionStats — handler throws — stats stay null`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            recurrenceHandler.throwOnGetCompletionStats = true
            val vm = detailVm()

            vm.loadTask("h1")
            advanceUntilIdle()

            // Handler error is caught internally — stats remain null, no crash
            assertThat(vm.uiState.value.completionStats).isNull()
            assertThat(vm.uiState.value.task).isNotNull()
        }

    @Test
    fun `loadTask — occurrence repo throws — occurrence and window loading cleared`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            occurrenceRepo.throwOnGetByTaskId = true
            val vm = detailVm()

            vm.loadTask("h1")
            advanceUntilIdle()

            // Both loadOccurrenceHistory and loadActivityWindow call getOccurrencesByTaskId
            assertThat(vm.uiState.value.isLoadingOccurrences).isFalse()
            assertThat(vm.uiState.value.occurrenceHistory).isEmpty()
            assertThat(vm.uiState.value.isLoadingWindow).isFalse()
        }

    @Test
    fun `loadTask — reschedule repo throws — isLoadingReschedules cleared`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            rescheduleRepo.throwOnGetByTaskId = true
            val vm = detailVm()

            vm.loadTask("t1")
            advanceUntilIdle()

            assertThat(vm.uiState.value.isLoadingReschedules).isFalse()
            assertThat(vm.uiState.value.rescheduleHistory).isEmpty()
        }

    // ────────────────────────────────────────────────────────────────
    // TaskDetailViewModel — completeTask/skipTask/missTask
    // non-recurring: cancel reminder throws (inner catch)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `non-recurring actions — cancel reminder throws — inner catch`() =
        runTest {
            val vm = detailVm()

            // completeTask → cancelForTask throws → inner catch
            taskRepo.tasks.value = listOf(task(id = "t1"))
            vm.loadTask("t1")
            advanceUntilIdle()
            scheduler.throwOnCancel = true
            vm.completeTask()
            advanceUntilIdle()
            assertThat(
                taskRepo.tasks.value
                    .first { it.id == "t1" }
                    .status,
            ).isEqualTo("completed")
            assertThat(vm.uiState.value.error).isNull()
            assertThat(vm.uiState.value.showStatusNoteDialog).isFalse()

            // skipTask → cancelForTask throws → inner catch
            scheduler.throwOnCancel = false
            taskRepo.tasks.value = taskRepo.tasks.value + task(id = "t2")
            vm.loadTask("t2")
            advanceUntilIdle()
            scheduler.throwOnCancel = true
            vm.skipTask()
            advanceUntilIdle()
            assertThat(
                taskRepo.tasks.value
                    .first { it.id == "t2" }
                    .status,
            ).isEqualTo("skipped")
            assertThat(vm.uiState.value.error).isNull()

            // missTask → cancelForTask throws → inner catch
            scheduler.throwOnCancel = false
            taskRepo.tasks.value = taskRepo.tasks.value + task(id = "t3")
            vm.loadTask("t3")
            advanceUntilIdle()
            scheduler.throwOnCancel = true
            vm.missTask()
            advanceUntilIdle()
            assertThat(
                taskRepo.tasks.value
                    .first { it.id == "t3" }
                    .status,
            ).isEqualTo("missed")
            assertThat(vm.uiState.value.error).isNull()
        }

    // ────────────────────────────────────────────────────────────────
    // TaskDetailViewModel — completeTask/skipTask/missTask
    // recurring freq habit: schedule reminder throws (inner catch)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `recurring freq habit — schedule throws — inner catch for complete skip miss`() =
        runTest {
            val vm = detailVm()
            recurrenceHandler.delegate.frequencyHabit = true

            // completeTask → scheduleForTask throws → inner catch
            taskRepo.tasks.value = listOf(habit(id = "h1"))
            vm.loadTask("h1")
            advanceUntilIdle()
            scheduler.throwOnSchedule = true
            vm.completeTask()
            advanceUntilIdle()
            assertThat(recurrenceHandler.delegate.completed).contains("h1")
            assertThat(vm.uiState.value.error).isNull()

            // skipTask → scheduleForTask throws → inner catch
            scheduler.throwOnSchedule = false
            taskRepo.tasks.value = taskRepo.tasks.value + habit(id = "h2")
            vm.loadTask("h2")
            advanceUntilIdle()
            scheduler.throwOnSchedule = true
            vm.skipTask()
            advanceUntilIdle()
            assertThat(recurrenceHandler.delegate.skipped).contains("h2")
            assertThat(vm.uiState.value.error).isNull()

            // missTask → scheduleForTask throws → inner catch
            scheduler.throwOnSchedule = false
            taskRepo.tasks.value = taskRepo.tasks.value + habit(id = "h3")
            vm.loadTask("h3")
            advanceUntilIdle()
            scheduler.throwOnSchedule = true
            vm.missTask()
            advanceUntilIdle()
            assertThat(recurrenceHandler.delegate.missed).contains("h3")
            assertThat(vm.uiState.value.error).isNull()
        }

    // ────────────────────────────────────────────────────────────────
    // TaskDetailViewModel — recurring non-habit: schedule throws
    // (tests the repo.completeTask → recordOccurrence → schedule path)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `completeTask recurring non-habit — schedule throws — inner catch`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            recurrenceHandler.delegate.frequencyHabit = false
            val vm = detailVm()
            vm.loadTask("h1")
            advanceUntilIdle()

            scheduler.throwOnSchedule = true
            vm.completeTask()
            advanceUntilIdle()

            assertThat(
                taskRepo.tasks.value
                    .first { it.id == "h1" }
                    .status,
            ).isEqualTo("completed")
            assertThat(recurrenceHandler.delegate.completed).contains("h1")
            assertThat(vm.uiState.value.error).isNull()
        }

    // ────────────────────────────────────────────────────────────────
    // TaskDetailViewModel — archiveTask/deleteTask
    // cancel reminder throws (inner catch)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `archiveTask deleteTask — cancel throws — inner catch`() =
        runTest {
            val vm = detailVm()

            // archiveTask → cancelForTask throws → inner catch
            taskRepo.tasks.value = listOf(task(id = "t1"))
            vm.loadTask("t1")
            advanceUntilIdle()
            scheduler.throwOnCancel = true
            vm.archiveTask()
            advanceUntilIdle()
            assertThat(
                taskRepo.tasks.value
                    .first { it.id == "t1" }
                    .status,
            ).isEqualTo("archived")
            assertThat(vm.uiState.value.error).isNull()

            // deleteTask → cancelForTask throws → inner catch
            scheduler.throwOnCancel = false
            taskRepo.tasks.value = listOf(task(id = "t2"))
            vm.loadTask("t2")
            advanceUntilIdle()
            scheduler.throwOnCancel = true
            vm.deleteTask()
            advanceUntilIdle()
            assertThat(taskRepo.tasks.value.none { it.id == "t2" }).isTrue()
            assertThat(vm.uiState.value.error).isNull()
        }

    // ────────────────────────────────────────────────────────────────
    // TaskDetailViewModel — rescheduleTask
    // schedule reminder throws (inner catch)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `rescheduleTask — schedule throws — inner catch`() =
        runTest {
            val t = task(id = "t1", dueDate = TASKS_TEST_TIME)
            taskRepo.tasks.value = listOf(t)
            val vm = detailVm()
            vm.loadTask("t1")
            advanceUntilIdle()

            scheduler.throwOnSchedule = true
            vm.rescheduleTask(TASKS_TEST_TIME.plusDays(5))
            advanceUntilIdle()

            assertThat(
                vm.uiState.value.task!!
                    .dueDate,
            ).isEqualTo(TASKS_TEST_TIME.plusDays(5))
            assertThat(rescheduleRepo.reschedules.value).isNotEmpty()
            assertThat(vm.uiState.value.error).isNull()
        }

    // ────────────────────────────────────────────────────────────────
    // TaskDetailViewModel — action repo throws → outer catch → error
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `action errors — repo throws — error surfaced in state`() =
        runTest {
            val vm = detailVm()

            // completeTask
            taskRepo.tasks.value = listOf(task(id = "t1"))
            vm.loadTask("t1")
            advanceUntilIdle()
            taskRepo.throwOnCompleteTask = true
            vm.completeTask()
            advanceUntilIdle()
            assertThat(vm.uiState.value.error).isNotNull()

            // skipTask
            taskRepo.throwOnCompleteTask = false
            taskRepo.tasks.value = listOf(task(id = "t2"))
            vm.loadTask("t2")
            advanceUntilIdle()
            taskRepo.throwOnSkipTask = true
            vm.skipTask()
            advanceUntilIdle()
            assertThat(vm.uiState.value.error).isNotNull()

            // missTask
            taskRepo.throwOnSkipTask = false
            taskRepo.tasks.value = listOf(task(id = "t3"))
            vm.loadTask("t3")
            advanceUntilIdle()
            taskRepo.throwOnMissTask = true
            vm.missTask()
            advanceUntilIdle()
            assertThat(vm.uiState.value.error).isNotNull()

            // archiveTask
            taskRepo.throwOnMissTask = false
            taskRepo.tasks.value = listOf(task(id = "t4"))
            vm.loadTask("t4")
            advanceUntilIdle()
            taskRepo.throwOnArchiveTask = true
            vm.archiveTask()
            advanceUntilIdle()
            assertThat(vm.uiState.value.error).isNotNull()

            // deleteTask
            taskRepo.throwOnArchiveTask = false
            taskRepo.tasks.value = listOf(task(id = "t5"))
            vm.loadTask("t5")
            advanceUntilIdle()
            taskRepo.throwOnDeleteTask = true
            vm.deleteTask()
            advanceUntilIdle()
            assertThat(vm.uiState.value.error).isNotNull()
        }

    @Test
    fun `rescheduleTask — repo updateTask throws — error in state`() =
        runTest {
            val t = task(id = "t1", dueDate = TASKS_TEST_TIME)
            taskRepo.tasks.value = listOf(t)
            val vm = detailVm()
            vm.loadTask("t1")
            advanceUntilIdle()

            taskRepo.throwOnUpdateTask = true
            vm.rescheduleTask(TASKS_TEST_TIME.plusDays(5))
            advanceUntilIdle()

            assertThat(vm.uiState.value.error).isNotNull()
        }

    // ────────────────────────────────────────────────────────────────
    // TaskDetailViewModel — rescheduleTask wasOverdue false
    // (previous date in the future)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `rescheduleTask — wasOverdue false with future previous date`() =
        runTest {
            val futureDate = TASKS_TEST_TIME.plusDays(30)
            val t = task(id = "t1", dueDate = futureDate)
            taskRepo.tasks.value = listOf(t)
            val vm = detailVm()
            vm.loadTask("t1")
            advanceUntilIdle()

            val newDate = TASKS_TEST_TIME.plusDays(60)
            vm.rescheduleTask(newDate)
            advanceUntilIdle()

            val reschedule = rescheduleRepo.reschedules.value.first()
            assertThat(reschedule.wasOverdue).isFalse()
            assertThat(reschedule.previousDueDate).isEqualTo(futureDate)
            assertThat(reschedule.newDueDate).isEqualTo(newDate)
        }

    // ────────────────────────────────────────────────────────────────
    // TaskDetailViewModel — recordOccurrence internal catch
    // (occurrence repo record throws — caught within, operation continues)
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `recordOccurrence — record throws — caught within, operation continues`() =
        runTest {
            val t = habit(id = "h1")
            taskRepo.tasks.value = listOf(t)
            recurrenceHandler.delegate.frequencyHabit = true
            occurrenceRepo.throwOnRecordOccurrence = true
            val vm = detailVm()
            vm.loadTask("h1")
            advanceUntilIdle()

            vm.completeTask(note = "done")
            advanceUntilIdle()

            // recordOccurrence caught the error — code continued to recurrenceHandler
            assertThat(recurrenceHandler.delegate.completed).contains("h1")
            // No error surfaced to state
            assertThat(vm.uiState.value.error).isNull()
            // Dialog hidden at the end of completeTask
            assertThat(vm.uiState.value.showStatusNoteDialog).isFalse()
        }

    // ────────────────────────────────────────────────────────────────
    // TaskDetailViewModel — window navigation with null currentTaskId
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `window navigation — null currentTaskId — updates state but skips load`() =
        runTest {
            val vm = detailVm()
            advanceUntilIdle()

            // No loadTask called → currentTaskId is null

            vm.setWindowSizeDays(30)
            assertThat(vm.uiState.value.windowSizeDays).isEqualTo(30)
            assertThat(vm.uiState.value.isLoadingWindow).isFalse()

            val prevEnd = vm.uiState.value.windowEnd
            vm.shiftWindowBack()
            assertThat(vm.uiState.value.windowEnd).isEqualTo(prevEnd.minusDays(30))
            assertThat(vm.uiState.value.isLoadingWindow).isFalse()

            vm.shiftWindowForward()
            // Already at today after shiftWindowBack then shiftWindowForward with size 30
            assertThat(vm.uiState.value.isLoadingWindow).isFalse()

            vm.jumpWindowToToday()
            assertThat(vm.uiState.value.windowEnd).isEqualTo(LocalDate.now())
            assertThat(vm.uiState.value.isLoadingWindow).isFalse()
        }

    // ────────────────────────────────────────────────────────────────
    // AddTaskViewModel — createTask error and reminder error
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `createTask — repo throws — catch calls onResult failure`() =
        runTest {
            val vm = addVm()
            advanceUntilIdle()

            taskRepo.throwOnCreateTask = true
            var failureResult: Throwable? = null
            vm.createTask(
                title = "Failing task",
                dimensionId = "dim_work",
                dimensionLabel = "Work",
                onResult = { if (it.isFailure) failureResult = it.exceptionOrNull() },
            )
            advanceUntilIdle()

            assertThat(failureResult).isNotNull()
            assertThat(failureResult!!.message).isEqualTo("createTask error")
            assertThat(taskRepo.tasks.value).isEmpty()
        }

    @Test
    fun `createTask — reminder throws — inner catch, task still created`() =
        runTest {
            PlatformFeatureFlags.install(
                FeatureFlagsState(scoringEnabled = false, remindersEnabled = true),
            )
            val vm = addVm()
            advanceUntilIdle()

            scheduler.throwOnSchedule = true
            var successResult = false
            vm.createTask(
                title = "Task with bad reminder",
                dimensionId = "dim_work",
                dimensionLabel = "Work",
                onResult = { if (it.isSuccess) successResult = true },
            )
            advanceUntilIdle()

            // Task was created despite reminder failure
            assertThat(taskRepo.tasks.value).hasSize(1)
            assertThat(
                taskRepo.tasks.value
                    .first()
                    .title,
            ).isEqualTo("Task with bad reminder")
            // onResult success was called (reminder failure is swallowed)
            assertThat(successResult).isTrue()
        }

    // ────────────────────────────────────────────────────────────────
    // EditTaskViewModel — loadTask and updateTask error paths
    // ────────────────────────────────────────────────────────────────

    @Test
    fun `loadTask — repo throws — error surfaced in state (edit view model)`() =
        runTest {
            val vm = editVm()
            advanceUntilIdle()

            taskRepo.throwOnGetTaskById = true
            vm.loadTask("t1")
            advanceUntilIdle()

            val s = vm.uiState.value
            assertThat(s.isLoading).isFalse()
            assertThat(s.error).isNotNull()
            assertThat(s.task).isNull()
        }

    @Test
    fun `updateTask — repo throws — error surfaced, isSaving cleared`() =
        runTest {
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = editVm()
            vm.loadTask("t1")
            advanceUntilIdle()

            taskRepo.throwOnUpdateTask = true
            vm.updateTask(editInput())
            advanceUntilIdle()

            assertThat(vm.uiState.value.isSaving).isFalse()
            assertThat(vm.uiState.value.error).isNotNull()
        }

    @Test
    fun `updateTask — reminder throws — inner catch, task still updated`() =
        runTest {
            PlatformFeatureFlags.install(
                FeatureFlagsState(scoringEnabled = false, remindersEnabled = true),
            )
            val t = task(id = "t1")
            taskRepo.tasks.value = listOf(t)
            val vm = editVm()
            vm.loadTask("t1")
            advanceUntilIdle()

            scheduler.throwOnSchedule = true
            vm.updateTask(editInput(title = "Updated"))
            advanceUntilIdle()

            assertThat(
                taskRepo.tasks.value
                    .first()
                    .title,
            ).isEqualTo("Updated")
            assertThat(vm.uiState.value.isSaving).isFalse()
            assertThat(vm.uiState.value.error).isNull()
        }
}
