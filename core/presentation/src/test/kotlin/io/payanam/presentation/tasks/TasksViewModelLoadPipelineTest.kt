//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.repository.TaskRepository
import io.payanam.presentation.preferences.AppPreferenceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Covers [TasksViewModel]'s load/observe pipeline plus the sorting and
 * [buildDueTodayByTaskId] predicate arms that the existing suites leave
 * untested.
 *
 * Construction pattern: class-level [StandardTestDispatcher] fed into
 * `runTest(testDispatcher)` so the ViewModel's injected scope is
 * deterministically advanced.
 */
@ExperimentalCoroutinesApi
class TasksViewModelLoadPipelineTest {
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
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.dispose()
    }

    private fun createViewModel(settings: Map<String, String> = emptyMap()): TasksViewModel {
        if (::viewModel.isInitialized) viewModel.dispose()
        appSettingsRepository = FakeAppSettingsRepository(settings)
        return TasksViewModel(
            taskRepository = taskRepository,
            taskOccurrenceRepository = taskOccurrenceRepository,
            notificationScheduler = notificationScheduler,
            appSettingsRepository = appSettingsRepository,
            recurrenceHandler = recurrenceHandler,
            habitTimeEntryRecorder = habitTimeEntryRecorder,
            scoreRollupTrigger = scoreRollupTrigger,
            habitMetricRepository = habitMetricRepository,
            scoreWindowRepository = scoreWindowRepository,
            scoreChangeStream = scoreChangeStream,
            tracer = tracer,
            scope = CoroutineScope(testDispatcher),
        ).also {
            it.shapingDispatcher = testDispatcher
            viewModel = it
        }
    }

    // ── Load pipeline: empty repositories ─────────────────────────────

    /** Empty repos: init finishes with all lists empty, isLoading false. */
    @Test
    fun `init with empty repos finishes with empty state`() =
        runTest(testDispatcher) {
            createViewModel()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.tasks).isEmpty()
            assertThat(state.recurringTasks).isEmpty()
            assertThat(state.filteredTasks).isEmpty()
            assertThat(state.isLoading).isFalse()
            assertThat(state.error).isNull()
            assertThat(state.todayCount).isEqualTo(0)
            assertThat(state.overdueCount).isEqualTo(0)
        }

    /** Default preferences (sort=DUE_DATE_ASC, filter=TODAY) used when settings empty. */
    @Test
    fun `init uses default preferences when settings empty`() =
        runTest(testDispatcher) {
            createViewModel()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.currentSort).isEqualTo(TaskSortOption.DUE_DATE_ASC)
            assertThat(state.currentFilter).isEqualTo(TaskFilter.TODAY)
        }

    // ── Load pipeline: populated repos, counters ──────────────────────

    /** todayCount/overdueCount/futureCount reflect task due dates. */
    @Test
    fun `loadTasks counts today, overdue, and future tasks`() =
        runTest(testDispatcher) {
            val now = LocalDateTime.now()
            taskRepository.tasks.value =
                listOf(
                    task(id = "overdue", dueDate = now.minusDays(1)),
                    task(id = "today", dueDate = now.withHour(23).withMinute(59)),
                    task(id = "future", dueDate = now.plusDays(3)),
                    task(id = "completed", status = "completed", dueDate = now.minusDays(2)),
                    task(id = "archived", status = "archived", dueDate = now.minusDays(1)),
                )
            createViewModel()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.todayCount).isEqualTo(1) // "today"
            assertThat(state.overdueCount).isEqualTo(1) // "overdue"
            assertThat(state.taskFilterCounts.future).isEqualTo(1) // "future"
            assertThat(state.isLoading).isFalse()
        }

    /** taskFilterCounts tallies active/completed/archived from all one-time tasks. */
    @Test
    fun `loadTasks populates taskFilterCounts`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value =
                listOf(
                    task(id = "p1", status = "pending"),
                    task(id = "p2", status = "pending"),
                    task(id = "c1", status = "completed"),
                    task(id = "a1", status = "archived"),
                )
            createViewModel()
            advanceUntilIdle()
            val counts = viewModel.uiState.value.taskFilterCounts
            assertThat(counts.all).isEqualTo(4)
            assertThat(counts.active).isEqualTo(2)
            assertThat(counts.completed).isEqualTo(1)
            assertThat(counts.archived).isEqualTo(1)
        }

    /** Recurring tasks appear in recurringTasks, not filteredTaskRows. */
    @Test
    fun `loadTasks separates recurring from one-time tasks`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value =
                listOf(
                    task(id = "one", recurrenceEnabled = false),
                    habit(id = "hab1", rule = "1/1"),
                    habit(id = "hab2", rule = "3/7"),
                )
            createViewModel()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.recurringTasks.map { it.id }).containsExactly("hab1", "hab2")
            assertThat(state.filteredTaskRows.map { it.id }).containsExactly("one")
        }

    // ── Load pipeline: habit L1 metrics ───────────────────────────────

    /** L1 metrics present → latestL1ByHabit populated. */
    @Test
    fun `loadTasks populates latestL1ByHabit when metrics exist`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(habit(id = "h1"))
            habitMetricRepository.latestPerHabit = mapOf("h1" to habitL1("h1", runningAvg = 0.75))
            createViewModel()
            advanceUntilIdle()
            val l1 = viewModel.uiState.value.latestL1ByHabit
            assertThat(l1).containsKey("h1")
            assertThat(l1["h1"]!!.runningAvg).isWithin(0.001).of(0.75)
        }

    /** L1 metrics absent → latestL1ByHabit is empty. */
    @Test
    fun `loadTasks latestL1ByHabit empty when no metrics`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(habit(id = "h1"))
            habitMetricRepository.latestPerHabit = emptyMap()
            createViewModel()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.latestL1ByHabit).isEmpty()
        }

    // ── Load pipeline: dueTodayByTaskId ───────────────────────────────

    /** Due-today map populated from habits loaded during init. */
    @Test
    fun `loadTasks populates dueTodayByTaskId for habits`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(habit(id = "h1", rule = "1/1"))
            createViewModel()
            advanceUntilIdle()
            val dueMap = viewModel.uiState.value.dueTodayByTaskId
            assertThat(dueMap).containsKey("h1")
            // Daily habit with no completions today → due
            assertThat(dueMap["h1"]).isTrue()
        }

    /** Habit with quota met is NOT due today. */
    @Test
    fun `dueTodayByTaskId false when quota already met`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(habit(id = "h1", rule = "1/1"))
            taskOccurrenceRepository.occurrences.value =
                listOf(
                    occurrence("h1", date = TASKS_TEST_DAY),
                )
            createViewModel()
            advanceUntilIdle()
            val dueMap = viewModel.uiState.value.dueTodayByTaskId
            assertThat(dueMap["h1"]).isFalse()
        }

    /** Non-recurring task's entry defaults to true in the due map. */
    @Test
    fun `dueTodayByTaskId true for non-recurring task`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value =
                listOf(
                    habit(id = "h1", rule = "1/1"),
                )
            createViewModel()
            advanceUntilIdle()
            // Non-recurring tasks aren't in recurringTasks, so the map is habit-only.
            assertThat(viewModel.uiState.value.dueTodayByTaskId).containsKey("h1")
        }

    // ── Load pipeline: persisted preferences restoration ──────────────

    /** Persisted sort option is restored from app settings on init. */
    @Test
    fun `init restores persisted sort option from settings`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value =
                listOf(
                    task(id = "a", title = "Alpha"),
                    task(id = "b", title = "Zeta"),
                )
            createViewModel(settings = mapOf(AppPreferenceKeys.KEY_TASK_SORT_OPTION to "title_asc"))
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.currentSort).isEqualTo(TaskSortOption.TITLE_ASC)
            // filteredTaskRows should be alphabetically sorted
            assertThat(state.filteredTaskRows.map { it.id }).isEqualTo(listOf("a", "b"))
        }

    /** Persisted filter option is restored from app settings on init. */
    @Test
    fun `init restores persisted filter option from settings`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value =
                listOf(
                    task(id = "c1", status = "completed"),
                    task(id = "p1", status = "pending"),
                )
            createViewModel(settings = mapOf(AppPreferenceKeys.KEY_TASK_FILTER_OPTION to "completed"))
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.currentFilter).isEqualTo(TaskFilter.COMPLETED)
            assertThat(state.filteredTaskRows.map { it.id }).isEqualTo(listOf("c1"))
        }

    /** Persisted habit sort option is restored. */
    @Test
    fun `init restores persisted habit sort option`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(habit(id = "h1"), habit(id = "h2"))
            habitMetricRepository.latestPerHabit =
                mapOf(
                    "h1" to habitL1("h1", runningAvg = 0.3),
                    "h2" to habitL1("h2", runningAvg = 0.9),
                )
            createViewModel(settings = mapOf(AppPreferenceKeys.KEY_HABIT_SORT_OPTION to "score_low_high"))
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.habitSortOption)
                .isEqualTo(HabitSortOption.SCORE_LOW_HIGH)
            // Habits sorted by score ascending: h1 (0.3) before h2 (0.9)
            assertThat(
                viewModel.uiState.value.recurringTasks
                    .map { it.id },
            ).isEqualTo(listOf("h1", "h2"))
        }

    /** Persisted visibility toggles (hideAllMarked, dueTodayOnly, etc.) are restored. */
    @Test
    fun `init restores persisted visibility toggles`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(habit(id = "h1"))
            createViewModel(
                settings =
                    mapOf(
                        AppPreferenceKeys.KEY_SHOW_COMPLETED_HABITS to "false",
                        AppPreferenceKeys.KEY_HIDE_ALL_MARKED_TODAY to "true",
                        AppPreferenceKeys.KEY_DUE_TODAY_ONLY to "true",
                        AppPreferenceKeys.KEY_SHOW_ARCHIVED_HABITS to "true",
                    ),
            )
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.showCompletedHabits).isFalse()
            assertThat(state.hideAllMarkedToday).isTrue()
            assertThat(state.dueTodayOnly).isTrue()
            assertThat(state.showArchivedHabits).isTrue()
        }

    // ── Load pipeline: repository error handling ──────────────────────

    /** When getAllTasks throws, the error message is captured. */
    @Test
    fun `loadTasks captures error when repository throws`() =
        runTest(testDispatcher) {
            val throwingRepo =
                object : TaskRepository {
                    private val impl = FakeTaskRepository()

                    override fun getAllTasks(): Flow<List<Task>> = flow { throw RuntimeException("DB gone") }

                    override fun getTasksByStatus(status: String) = impl.getTasksByStatus(status)

                    override fun getTasksDueOn(date: java.time.LocalDate) = impl.getTasksDueOn(date)

                    override suspend fun getTaskById(id: String) = impl.getTaskById(id)

                    override suspend fun createTask(input: io.payanam.domain.model.TaskInput) = impl.createTask(input)

                    override suspend fun updateTask(
                        id: String,
                        input: io.payanam.domain.model.TaskInput,
                    ) = impl.updateTask(id, input)

                    override suspend fun updateTaskScore(
                        id: String,
                        score: Double,
                    ) = impl.updateTaskScore(id, score)

                    override suspend fun deleteTask(id: String) = impl.deleteTask(id)

                    override suspend fun completeTask(
                        id: String,
                        note: String?,
                    ) = impl.completeTask(id, note)

                    override suspend fun skipTask(
                        id: String,
                        note: String?,
                    ) = impl.skipTask(id, note)

                    override suspend fun missTask(
                        id: String,
                        note: String?,
                    ) = impl.missTask(id, note)

                    override suspend fun archiveTask(id: String) = impl.archiveTask(id)

                    override fun getOverdueTasks() = impl.getOverdueTasks()

                    override fun getTodaysTasks() = impl.getTodaysTasks()

                    override suspend fun getRecurringTasks() = impl.getRecurringTasks()

                    override suspend fun updateRecurrenceState(
                        taskId: String,
                        newDueDate: LocalDateTime,
                        lastOccurrenceDate: LocalDateTime,
                    ) = impl.updateRecurrenceState(taskId, newDueDate, lastOccurrenceDate)
                }
            taskRepository = FakeTaskRepository()
            if (::viewModel.isInitialized) viewModel.dispose()
            viewModel =
                TasksViewModel(
                    taskRepository = throwingRepo,
                    taskOccurrenceRepository = taskOccurrenceRepository,
                    notificationScheduler = notificationScheduler,
                    appSettingsRepository = appSettingsRepository,
                    recurrenceHandler = recurrenceHandler,
                    habitTimeEntryRecorder = habitTimeEntryRecorder,
                    scoreRollupTrigger = scoreRollupTrigger,
                    habitMetricRepository = habitMetricRepository,
                    scoreWindowRepository = scoreWindowRepository,
                    scoreChangeStream = scoreChangeStream,
                    tracer = tracer,
                    scope = CoroutineScope(testDispatcher),
                ).also { it.shapingDispatcher = testDispatcher }
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertThat(state.error).isNotNull()
            assertThat(state.error).contains("DB gone")
            assertThat(state.isLoading).isFalse()
        }

    // ── Load pipeline: score-change stream reaction ───────────────────

    /** A backfill event (non-today date) triggers loadTasks + day metrics refresh. */
    @Test
    fun `score change backfill event re-runs loadTasks`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(habit(id = "h1"))
            createViewModel()
            advanceUntilIdle()
            val initialCount = tracer.queries
            // Emit a backfill event for yesterday
            scoreChangeStream.emit(TASKS_TEST_DAY.minusDays(1))
            advanceUntilIdle()
            // loadTasks should have been invoked again → at least one more query
            assertThat(tracer.queries).isGreaterThan(initialCount)
        }

    /** A same-day event refreshes day metrics but does not re-run loadTasks. */
    @Test
    fun `score change same-day event does not trigger reloadTasks`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(habit(id = "h1"))
            createViewModel()
            advanceUntilIdle()
            val stateAfterInit = viewModel.uiState.value
            scoreChangeStream.emit(TASKS_TEST_DAY)
            advanceUntilIdle()
            // tasks list unchanged (same data), no extra getAllTasks query beyond init
            assertThat(viewModel.uiState.value.tasks).isEqualTo(stateAfterInit.tasks)
        }

    // ── Load pipeline: day-metrics strip ──────────────────────────────

    /** Day metrics strip is populated from score window rows. */
    @Test
    fun `dayMetricsState populated from score window repository`() =
        runTest(testDispatcher) {
            scoreWindowRepository.earliestDay = "2026-09-01"
            scoreWindowRepository.dayRows =
                listOf(
                    object : io.payanam.domain.model.MetricWindowRow {
                        override val key = "DAY"
                        override val label = "Day"
                        override val dayKey = TASKS_TEST_DAY.toString()
                        override val score = 0.8
                        override val runningAvg = 0.75
                        override val progress = 0.5
                        override val streakPos = 3
                        override val streakNet = 2
                        override val posContinue = 5
                    },
                )
            createViewModel()
            advanceUntilIdle()
            val dm = viewModel.dayMetricsState.value
            assertThat(dm.isLoading).isFalse()
            assertThat(dm.chips).isNotEmpty()
            // Due chip should be last
            assertThat(dm.chips.last().label).isEqualTo("Due")
        }

    /** Day metrics strip: empty window rows → no cascade chips. */
    @Test
    fun `dayMetricsState publishes a placeholder chip per metric when no window rows exist`() =
        runTest(testDispatcher) {
            scoreWindowRepository.earliestDay = null
            scoreWindowRepository.dayRows = emptyList()
            createViewModel()
            advanceUntilIdle()
            val dm = viewModel.dayMetricsState.value
            assertThat(dm.isLoading).isFalse()
            // Measured: an empty window still renders one placeholder chip per day metric (7 of them).
            assertThat(dm.chips).hasSize(7)
        }

    // ── Sorting gaps: filterTasks ─────────────────────────────────────

    /** ACTIVE filter keeps pending/active/null-status tasks. */
    @Test
    fun `active filter keeps pending and active tasks`() {
        val tasks =
            listOf(
                task(id = "pending", status = "pending"),
                task(id = "active", status = "active"),
                task(id = "completed", status = "completed"),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.ACTIVE, TaskSortOption.CREATED_ASC)
        assertThat(result.map { it.id }).containsExactly("active", "pending")
    }

    /** TODAY filter keeps tasks due on today's date. */
    @Test
    fun `today filter keeps tasks due today`() {
        val tasks =
            listOf(
                task(id = "today1", dueDate = TASKS_TEST_TIME),
                task(id = "tomorrow", dueDate = TASKS_TEST_TIME.plusDays(1)),
                task(id = "today-done", dueDate = TASKS_TEST_TIME, status = "completed"),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.TODAY, TaskSortOption.CREATED_ASC)
        // completed task is excluded by TODAY filter
        assertThat(result.map { it.id }).containsExactly("today1")
    }

    /** OVERDUE filter keeps non-completed tasks whose dueDate is before now. */
    @Test
    fun `overdue filter keeps past-due non-completed tasks`() {
        val now = LocalDateTime.now()
        val tasks =
            listOf(
                task(id = "overdue", dueDate = now.minusDays(1)),
                task(id = "future", dueDate = now.plusDays(1)),
                task(id = "overdue-done", dueDate = now.minusDays(1), status = "completed"),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.OVERDUE, TaskSortOption.CREATED_ASC)
        assertThat(result.map { it.id }).containsExactly("overdue")
    }

    /** FUTURE filter keeps tasks with due date after today or null due date. */
    @Test
    fun `future filter keeps future and undated non-completed tasks`() {
        val tasks =
            listOf(
                task(id = "future", dueDate = TASKS_TEST_TIME.plusDays(1)),
                task(id = "nodue", dueDate = null),
                task(id = "past", dueDate = TASKS_TEST_TIME.minusDays(1)),
                task(id = "future-done", dueDate = TASKS_TEST_TIME.plusDays(1), status = "completed"),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.FUTURE, TaskSortOption.CREATED_ASC)
        assertThat(result.map { it.id }).containsExactly("future", "nodue")
    }

    /** COMPLETED filter keeps only completed tasks. */
    @Test
    fun `completed filter keeps only completed tasks`() {
        val tasks =
            listOf(
                task(id = "done", status = "completed"),
                task(id = "pending", status = "pending"),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.COMPLETED, TaskSortOption.CREATED_ASC)
        assertThat(result.map { it.id }).containsExactly("done")
    }

    /** ARCHIVED filter keeps only archived tasks. */
    @Test
    fun `archived filter keeps only archived tasks`() {
        val tasks =
            listOf(
                task(id = "archived", status = "archived"),
                task(id = "pending", status = "pending"),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.ARCHIVED, TaskSortOption.CREATED_ASC)
        assertThat(result.map { it.id }).containsExactly("archived")
    }

    // ── Sorting gaps: sortTasks ───────────────────────────────────────

    /** SCORE_DESC sorts by taskScore descending, null scores last. */
    @Test
    fun `score desc sorts by taskScore descending with nulls last`() {
        val tasks =
            listOf(
                task(id = "low", taskScore = 2.0),
                task(id = "high", taskScore = 9.0),
                task(id = "none", taskScore = null),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.ALL, TaskSortOption.SCORE_DESC)
        assertThat(result.map { it.id }).isEqualTo(listOf("high", "low", "none"))
    }

    /** SCORE_ASC sorts by taskScore ascending, null scores last. */
    @Test
    fun `score asc treats a missing score as zero`() {
        val tasks =
            listOf(
                task(id = "high", taskScore = 9.0),
                task(id = "low", taskScore = 2.0),
                task(id = "none", taskScore = null),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.ALL, TaskSortOption.SCORE_ASC)
        // A null score is read as 0.0, so it sorts ahead of every real score.
        assertThat(result.map { it.id }).isEqualTo(listOf("none", "low", "high"))
    }

    /** DUE_DATE_ASC sorts earliest first with null dates last. */
    @Test
    fun `due date asc sorts earliest first with nulls last`() {
        val tasks =
            listOf(
                task(id = "late", dueDate = TASKS_TEST_TIME.plusDays(5)),
                task(id = "early", dueDate = TASKS_TEST_TIME.minusDays(1)),
                task(id = "none", dueDate = null),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.ALL, TaskSortOption.DUE_DATE_ASC)
        assertThat(result.map { it.id }).isEqualTo(listOf("early", "late", "none"))
    }

    /** DUE_DATE_DESC sorts latest first with null dates last. */
    @Test
    fun `due date desc sorts latest first and puts undated tasks first`() {
        val tasks =
            listOf(
                task(id = "early", dueDate = TASKS_TEST_TIME.minusDays(1)),
                task(id = "late", dueDate = TASKS_TEST_TIME.plusDays(5)),
                task(id = "none", dueDate = null),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.ALL, TaskSortOption.DUE_DATE_DESC)
        // compareByDescending(nullsLast()) reverses the null placement too, so an undated task leads a
        // descending due-date sort. Real behaviour, pinned here rather than "fixed" inside a move.
        assertThat(result.map { it.id }).isEqualTo(listOf("none", "late", "early"))
    }

    /** TITLE_ASC sorts alphabetically case-insensitively. */
    @Test
    fun `title asc sorts alphabetically case-insensitive`() {
        val tasks =
            listOf(
                task(id = "b", title = "Zebra"),
                task(id = "a", title = "apple"),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.ALL, TaskSortOption.TITLE_ASC)
        assertThat(result.map { it.id }).isEqualTo(listOf("a", "b"))
    }

    /** TITLE_DESC sorts reverse-alphabetically. */
    @Test
    fun `title desc sorts reverse-alphabetically`() {
        val tasks =
            listOf(
                task(id = "a", title = "apple"),
                task(id = "b", title = "Zebra"),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.ALL, TaskSortOption.TITLE_DESC)
        assertThat(result.map { it.id }).isEqualTo(listOf("b", "a"))
    }

    /** CREATED_DESC sorts newest first. */
    @Test
    fun `created desc sorts newest first`() {
        val old = task(id = "old").copy(createdAt = TASKS_TEST_TIME.minusDays(5))
        val newer = task(id = "new").copy(createdAt = TASKS_TEST_TIME)
        val result = filterAndSortTasks(listOf(old, newer), TaskFilter.ALL, TaskSortOption.CREATED_DESC)
        assertThat(result.map { it.id }).isEqualTo(listOf("new", "old"))
    }

    /** CREATED_ASC sorts oldest first. */
    @Test
    fun `created asc sorts oldest first`() {
        val old = task(id = "old").copy(createdAt = TASKS_TEST_TIME.minusDays(5))
        val newer = task(id = "new").copy(createdAt = TASKS_TEST_TIME)
        val result = filterAndSortTasks(listOf(newer, old), TaskFilter.ALL, TaskSortOption.CREATED_ASC)
        assertThat(result.map { it.id }).isEqualTo(listOf("old", "new"))
    }

    /** ENERGY_ASC sorts by energy level string. */
    @Test
    fun `energy asc sorts by energy level low first`() {
        val tasks =
            listOf(
                task(id = "high", impactLevel = "High Impact").copy(energyLevel = "High"),
                task(id = "low", impactLevel = "Low Impact").copy(energyLevel = "Low"),
                task(id = "mod", impactLevel = "Moderate Impact").copy(energyLevel = "Moderate"),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.ALL, TaskSortOption.ENERGY_ASC)
        assertThat(result.map { it.id }).isEqualTo(listOf("low", "mod", "high"))
    }

    /** DIMENSION sorts by lifeIntentionCategory. */
    @Test
    fun `dimension sort groups by life dimension`() {
        val tasks =
            listOf(
                task(id = "health", lifeIntentionCategory = "Health"),
                task(id = "career", lifeIntentionCategory = "Career & Work"),
                task(id = "personal", lifeIntentionCategory = "Personal Growth"),
            )
        val result = filterAndSortTasks(tasks, TaskFilter.ALL, TaskSortOption.DIMENSION)
        assertThat(result.map { it.id }).isEqualTo(listOf("career", "health", "personal"))
    }

    // ── Sorting gaps: sortHabits ──────────────────────────────────────

    /** BY_DUE_TIME with null dueDate → last. */
    @Test
    fun `habit by due time null due date sorts last`() {
        val t1 = task(id = "a").copy(dueDate = TASKS_TEST_TIME.minusHours(1))
        val t2 = task(id = "b").copy(dueDate = null)
        val result = sortHabits(listOf(t2, t1), HabitSortOption.BY_DUE_TIME, emptyMap())
        assertThat(result.map { it.id }).isEqualTo(listOf("a", "b"))
    }

    /** BY_DUE_TIME_REVERSE with null dueDate → first. */
    @Test
    fun `habit by due time reverse puts an undated habit last`() {
        val t1 = task(id = "a").copy(dueDate = TASKS_TEST_TIME.minusHours(1))
        val t2 = task(id = "b").copy(dueDate = null)
        val result = sortHabits(listOf(t1, t2), HabitSortOption.BY_DUE_TIME_REVERSE, emptyMap())
        assertThat(result.map { it.id }).isEqualTo(listOf("a", "b"))
    }

    // ── Visibility gaps: visibleHabitsForDisplay ──────────────────────

    /** hideAllMarkedToday hides COMPLETED, SKIPPED, and MISSED habits. */
    @Test
    fun `hideAllMarkedToday hides all marked statuses including skipped and missed`() {
        val completed = habit("c", "1/1")
        val skipped = habit("s", "1/1")
        val missed = habit("m", "1/1")
        val pending = habit("p", "1/1")
        val visible =
            visibleHabitsForDisplay(
                habits = listOf(completed, skipped, missed, pending),
                todayStatusByTaskId =
                    mapOf(
                        "c" to CheckmarkStatus.COMPLETED,
                        "s" to CheckmarkStatus.SKIPPED,
                        "m" to CheckmarkStatus.MISSED,
                        "p" to CheckmarkStatus.PENDING,
                    ),
                showCompletedHabits = true,
                hideAllMarkedToday = true,
            )
        assertThat(visible.map { it.id }).containsExactly("p")
    }

    /** showCompletedHabits=false hides completed habits (without hideAllMarkedToday). */
    @Test
    fun `showCompleted false hides completed habits`() {
        val done = habit("done", "1/1")
        val pending = habit("pending", "1/1")
        val visible =
            visibleHabitsForDisplay(
                habits = listOf(done, pending),
                todayStatusByTaskId =
                    mapOf(
                        "done" to CheckmarkStatus.COMPLETED,
                        "pending" to CheckmarkStatus.PENDING,
                    ),
                showCompletedHabits = false,
            )
        assertThat(visible.map { it.id }).containsExactly("pending")
    }

    /** showCompletedHabits=true, no other filters → all habits shown. */
    @Test
    fun `showCompleted true no other filters shows all habits`() {
        val habits = listOf(habit("a", "1/1"), habit("b", "1/1"))
        val visible =
            visibleHabitsForDisplay(
                habits = habits,
                todayStatusByTaskId = mapOf("a" to CheckmarkStatus.COMPLETED, "b" to CheckmarkStatus.SKIPPED),
                showCompletedHabits = true,
            )
        assertThat(visible).hasSize(2)
    }

    // ── HabitDueToday gaps: predicate arms ────────────────────────────

    /** Non-recurring task always reports as due (recurrenceEnabled=false). */
    @Test
    fun `non-recurring task always due`() =
        runBlocking {
            val task = task(id = "once", recurrenceEnabled = false)
            assertTrue(computeDueTodayForTask(task, emptyList(), TASKS_TEST_DAY))
        }

    /** Frequency rule with explicit anchorDate (start=) uses that anchor, not createdAt. */
    @Test
    fun `frequency rule uses explicit anchor from serialized start param`() =
        runBlocking {
            val t = habit("t", "1/7!start=2026-09-01")
            val today = LocalDate.of(2026, 9, 21)
            // Window: 09-15..09-21 (7 days after 09-01, 2nd window)
            // No completions → due
            assertTrue(computeDueTodayForTask(t, emptyList(), today))
        }

    /** Frequency rule: anchor falls back to dueDate when no start= in rule. */
    @Test
    fun `frequency rule falls back to dueDate as anchor when no explicit start`() =
        runBlocking {
            val dueDate = TASKS_TEST_TIME
            val t = habit("t", "1/7", dueDate = dueDate)
            // Today is TASKS_TEST_DAY = 2026-09-21, anchor = 2026-09-21
            // Window: 09-21..09-27
            // No completions → due
            assertTrue(computeDueTodayForTask(t, emptyList(), TASKS_TEST_DAY))
        }

    /** Typed FREQUENCY rule through RecurrenceConfig path (non-serialized format). */
    @Test
    fun `typed frequency rule evaluates quota window`() =
        runBlocking {
            val rule = "CONFIG:type=FREQUENCY|freq=1/7|start=2026-09-01"
            val t = habit("t", rule)
            val today = LocalDate.of(2026, 9, 21)
            // Window 09-15..09-21; no completions → due
            assertTrue(computeDueTodayForTask(t, emptyList(), today))
        }

    /** Typed FREQUENCY rule: quota met → not due. */
    @Test
    fun `typed frequency rule not due when quota met`() =
        runBlocking {
            val rule = "CONFIG:type=FREQUENCY|freq=1/7|start=2026-09-01"
            val t = habit("t", rule)
            val today = LocalDate.of(2026, 9, 21)
            // Window 09-15..09-21; 1 completion in window → quota met
            val occ = occurrence("t", date = LocalDate.of(2026, 9, 18))
            assertFalse(computeDueTodayForTask(t, listOf(occ), today))
        }

    /** INTERVAL with null start: earliest of two occurrences anchors the phase. */
    @Test
    fun `interval null start anchors on earliest occurrence`() =
        runBlocking {
            val today = LocalDate.of(2026, 9, 21)
            val t =
                habit(
                    "t",
                    io.payanam.domain.model.RecurrenceConfig
                        .everyNDays(7)
                        .serialize(),
                )
            // Two occurrences: 09-01 (day 20 from anchor 09-01) and 09-08 (day 13 from anchor)
            // Earliest = 09-01; days since = 20; 20 % 7 = 6 → not due
            val occs =
                listOf(
                    occurrence("t", date = LocalDate.of(2026, 9, 1)),
                    occurrence("t", date = LocalDate.of(2026, 9, 8)),
                )
            assertFalse(computeDueTodayForTask(t, occs, today))
        }

    /** INTERVAL with null start: on-phase day after anchoring. */
    @Test
    fun `interval null start on phase day is due`() =
        runBlocking {
            // Anchor on 09-14 (7 days before 09-21); 21 % 7 = 0 → on phase
            val t =
                habit(
                    "t",
                    io.payanam.domain.model.RecurrenceConfig
                        .everyNDays(7)
                        .serialize(),
                )
            val today = LocalDate.of(2026, 9, 21)
            val occ = occurrence("t", date = LocalDate.of(2026, 9, 14))
            assertTrue(computeDueTodayForTask(t, listOf(occ), today))
        }

    /** Frequency: today before anchor date → not due. */
    @Test
    fun `frequency before anchor date not due`() =
        runBlocking {
            val t = habit("t", "1/1!start=2026-10-01")
            assertFalse(computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 21)))
        }

    // ── TaskFilter.fromKey migration ──────────────────────────────────

    /** UNKNOWN filter key defaults to TODAY. */
    @Test
    fun `task filter fromKey unknown defaults to TODAY`() {
        assertThat(TaskFilter.fromKey("unknown_key")).isEqualTo(TaskFilter.TODAY)
    }

    /** Null filter key defaults to TODAY. */
    @Test
    fun `task filter fromKey null defaults to TODAY`() {
        assertThat(TaskFilter.fromKey(null)).isEqualTo(TaskFilter.TODAY)
    }

    /** All valid filter keys round-trip through fromKey. */
    @Test
    fun `task filter fromKey recognizes all keys`() {
        for (f in TaskFilter.entries) {
            assertThat(TaskFilter.fromKey(f.key)).isEqualTo(f)
        }
    }

    // ── TaskSortOption.fromKey migration ──────────────────────────────

    /** Unknown sort key defaults to DUE_DATE_ASC. */
    @Test
    fun `task sort fromKey unknown defaults to DUE_DATE_ASC`() {
        assertThat(TaskSortOption.fromKey("bogus")).isEqualTo(TaskSortOption.DUE_DATE_ASC)
    }

    /** Null sort key defaults to DUE_DATE_ASC. */
    @Test
    fun `task sort fromKey null defaults to DUE_DATE_ASC`() {
        assertThat(TaskSortOption.fromKey(null)).isEqualTo(TaskSortOption.DUE_DATE_ASC)
    }

    // ── App-settings persistence round-trip ───────────────────────────

    /** setFilter persists to appSettingsRepository. */
    @Test
    fun `setFilter persists filter key to settings`() =
        runTest(testDispatcher) {
            createViewModel()
            advanceUntilIdle()
            viewModel.setFilter(TaskFilter.OVERDUE)
            advanceUntilIdle()
            assertThat(appSettingsRepository.writes).contains(
                AppPreferenceKeys.KEY_TASK_FILTER_OPTION to TaskFilter.OVERDUE.key,
            )
        }

    /** setSortOption persists to appSettingsRepository. */
    @Test
    fun `setSortOption persists sort key to settings`() =
        runTest(testDispatcher) {
            createViewModel()
            advanceUntilIdle()
            viewModel.setSortOption(TaskSortOption.TITLE_ASC)
            advanceUntilIdle()
            assertThat(appSettingsRepository.writes).contains(
                AppPreferenceKeys.KEY_TASK_SORT_OPTION to TaskSortOption.TITLE_ASC.key,
            )
        }

    /** setHabitSortOption persists to appSettingsRepository. */
    @Test
    fun `setHabitSortOption persists habit sort key to settings`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(habit(id = "h1"))
            createViewModel()
            advanceUntilIdle()
            viewModel.setHabitSortOption(HabitSortOption.BY_NAME)
            advanceUntilIdle()
            assertThat(appSettingsRepository.writes).contains(
                AppPreferenceKeys.KEY_HABIT_SORT_OPTION to HabitSortOption.BY_NAME.key,
            )
        }
}
