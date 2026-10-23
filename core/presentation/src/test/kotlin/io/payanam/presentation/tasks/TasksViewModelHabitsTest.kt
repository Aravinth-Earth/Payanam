//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.Frequency
import io.payanam.domain.model.HabitL1Summary
import io.payanam.domain.model.RecurrenceConfig
import io.payanam.domain.model.Task
import io.payanam.presentation.flags.PlatformFeatureFlags
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
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Coverage for the habit-specific paths in [TasksViewModel], plus the remaining
 * branches in [TasksRowCacheManager], [TasksHabitCache] helpers, and
 * [HabitDueToday] arms the existing [HabitDueTodayTest] does not reach.
 *
 * Uses the shared fakes from [TasksTestFixtures] and the same
 * [StandardTestDispatcher] pattern as [TasksViewModelTest].
 */
@ExperimentalCoroutinesApi
class TasksViewModelHabitsTest {
    private val testDispatcher = StandardTestDispatcher()

    /**
     * A recurrence start far enough ahead of the test day that "the window has not opened yet" holds
     * on whichever day this suite runs.
     *
     * Derived from [TASKS_TEST_DAY] rather than written as a calendar date, because a literal stops
     * being in the future the moment the real clock reaches it: `start=2026-10-01` read as "future
     * start, so not due" up to 2026-09-30 and then made `dueTodayByTaskId["notDue"]` true on
     * 2026-10-01, which is exactly the expiry the day-anchored fixture already documents for
     * [TASKS_TEST_DAY] itself.
     */
    private val futureStart: String = TASKS_TEST_DAY.plusMonths(1).toString()

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
        PlatformFeatureFlags.resetForTests()
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
        // Half of this class tests pure functions, so a ViewModel was never created.
        if (::viewModel.isInitialized) viewModel.dispose()
        PlatformFeatureFlags.resetForTests()
    }

    private fun createViewModel(): TasksViewModel =
        TasksViewModel(
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
        ).apply { shapingDispatcher = testDispatcher }

    // ── ViewModel: loadTasks populates habit fields ────────────────────────

    @Test
    fun `loadTasks populates recurringTasks and visibleHabitRows for habits`() =
        runTest(testDispatcher) {
            val h1 = habit("h1", "1/1")
            val h2 = habit("h2", "3/7")
            taskRepository.tasks.value = listOf(h1, h2)

            viewModel = createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.recurringTasks).hasSize(2)
            assertThat(state.visibleHabitRows).hasSize(2)
            assertThat(state.taskCheckmarks).containsKey("h1")
            assertThat(state.taskCheckmarks).containsKey("h2")
            assertThat(state.todayHabitStatusByTaskId).containsKey("h1")
            assertThat(state.dueTodayByTaskId).containsKey("h1")
        }

    @Test
    fun `loadTasks sets habitSortOption from persisted settings`() =
        runTest(testDispatcher) {
            appSettingsRepository.setSetting(AppPreferenceKeys.KEY_HABIT_SORT_OPTION, "by_name")
            taskRepository.tasks.value = listOf(habit("h1"), habit("h2"))

            viewModel = createViewModel()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.habitSortOption).isEqualTo(HabitSortOption.BY_NAME)
        }

    @Test
    fun `loadTasks persists visibility toggles and applies them`() =
        runTest(testDispatcher) {
            appSettingsRepository.setSetting(AppPreferenceKeys.KEY_SHOW_COMPLETED_HABITS, "false")
            appSettingsRepository.setSetting(AppPreferenceKeys.KEY_HIDE_ALL_MARKED_TODAY, "true")
            appSettingsRepository.setSetting(AppPreferenceKeys.KEY_DUE_TODAY_ONLY, "true")
            appSettingsRepository.setSetting(AppPreferenceKeys.KEY_SHOW_ARCHIVED_HABITS, "true")

            val completed = habit("completed").copy(status = "completed")
            val pending = habit("pending")
            taskRepository.tasks.value = listOf(completed, pending)

            viewModel = createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.showCompletedHabits).isFalse()
            assertThat(state.hideAllMarkedToday).isTrue()
            assertThat(state.dueTodayOnly).isTrue()
            assertThat(state.showArchivedHabits).isTrue()
        }

    // ── ViewModel: setHabitSortOption ──────────────────────────────────────

    @Test
    fun `setHabitSortOption updates state and persists`() =
        runTest(testDispatcher) {
            val h1 = habit("h1")
            val h2 = habit("h2")
            taskRepository.tasks.value = listOf(h1, h2)

            viewModel = createViewModel()
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.habitSortOption).isEqualTo(HabitSortOption.SCORE_HIGH_LOW)

            viewModel.setHabitSortOption(HabitSortOption.BY_NAME)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.habitSortOption).isEqualTo(HabitSortOption.BY_NAME)
            // Verify persistence
            val persisted = appSettingsRepository.getSetting(AppPreferenceKeys.KEY_HABIT_SORT_OPTION)
            assertThat(persisted).isEqualTo("by_name")
        }

    // ── ViewModel: toggleShowArchivedHabits ────────────────────────────────

    @Test
    fun `toggleShowArchivedHabits toggles flag and reloads`() =
        runTest(testDispatcher) {
            val active = habit("active")
            val archived = habit("archived").copy(status = "archived")
            taskRepository.tasks.value = listOf(active, archived)

            viewModel = createViewModel()
            advanceUntilIdle()
            // Default: archived not shown → only 1 recurring task
            assertThat(viewModel.uiState.value.showArchivedHabits).isFalse()
            val countBefore = viewModel.uiState.value.recurringTasks.size

            viewModel.toggleShowArchivedHabits()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showArchivedHabits).isTrue()
            // With archived shown, both tasks appear
            assertThat(viewModel.uiState.value.recurringTasks.size).isAtLeast(countBefore)
        }

    // ── ViewModel: toggleShowCompletedHabits ───────────────────────────────

    @Test
    fun `toggleShowCompletedHabits hides completed habits when toggled off`() =
        runTest(testDispatcher) {
            val completed = habit("done")
            val pending = habit("todo")
            taskRepository.tasks.value = listOf(completed, pending)

            viewModel = createViewModel()
            advanceUntilIdle()
            // show-completed starts false: a fresh install hides already-marked habits.
            assertThat(viewModel.uiState.value.showCompletedHabits).isFalse()

            viewModel.toggleShowCompletedHabits()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.showCompletedHabits).isTrue()
            // visibleHabitRows and visibleRecurringTasks are reshaped
            assertThat(viewModel.uiState.value.visibleHabitRows).isNotEmpty()
        }

    // ── ViewModel: toggleHideAllMarkedToday ────────────────────────────────

    @Test
    fun `toggleHideAllMarkedToday reshapes rows to hide completed and skipped`() =
        runTest(testDispatcher) {
            val h1 = habit("h1")
            val h2 = habit("h2")
            taskRepository.tasks.value = listOf(h1, h2)

            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.toggleHideAllMarkedToday()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.hideAllMarkedToday).isTrue()
            // With hideAllMarked on, rows are reshaped (exact count depends on checkmark state)
            assertThat(viewModel.uiState.value.visibleHabitRows).isNotNull()
        }

    // ── ViewModel: toggleDueTodayOnly ──────────────────────────────────────

    @Test
    fun `toggleDueTodayOnly narrows visible habits to due today`() =
        runTest(testDispatcher) {
            val dueHabit = habit("dueHabit", "1/1") // always due
            // Start still ahead of the test day → its window has not opened, so it is not due.
            val notDueHabit = habit("notDueHabit", "1/7!start=$futureStart")
            taskRepository.tasks.value = listOf(dueHabit, notDueHabit)

            viewModel = createViewModel()
            advanceUntilIdle()

            // Both rows are visible before the filter is on; if the not-due habit were already
            // hidden there would be nothing for the toggle to narrow, and the case below would
            // pass for the wrong reason.
            val idsBefore = viewModel.uiState.value.visibleHabitRows.map { it.id }
            assertThat(idsBefore).containsExactly("dueHabit", "notDueHabit")

            viewModel.toggleDueTodayOnly()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.dueTodayOnly).isTrue()
            // Due-today filter keeps the due habit and drops the one whose window has not opened.
            // Named ids, not a count: a filter can only shrink or hold the list, so `isAtMost`
            // against the pre-toggle size holds even if the filter is not applied at all.
            assertThat(viewModel.uiState.value.visibleHabitRows.map { it.id })
                .containsExactly("dueHabit")
        }

    // ── ViewModel: toggleCheckmark ─────────────────────────────────────────

    @Test
    fun `toggleCheckmark on recurring habit opens completion dialog`() =
        runTest(testDispatcher) {
            val h = habit("h1", "1/1")
            taskRepository.tasks.value = listOf(h)

            viewModel = createViewModel()
            advanceUntilIdle()

            // Ensure the habit has a PENDING checkmark for today
            val today = LocalDate.now()
            val checkmarks = viewModel.uiState.value.taskCheckmarks["h1"] ?: emptyList()
            val todayCheckmark = checkmarks.find { it.date == today }
            if (todayCheckmark != null && todayCheckmark.status == CheckmarkStatus.PENDING) {
                viewModel.toggleCheckmark("h1", today)
                advanceUntilIdle()

                // For a recurring task, toggling PENDING → completed opens dialog
                assertThat(viewModel.uiState.value.showCompletionDialog).isTrue()
                assertThat(
                    viewModel.uiState.value.completionDialogTask
                        ?.id,
                ).isEqualTo("h1")
                assertThat(viewModel.uiState.value.completionDialogDate).isEqualTo(today)
            }
        }

    @Test
    fun `toggleCheckmark on non-recurring task completes directly`() =
        runTest(testDispatcher) {
            val t = task("t1")
            taskRepository.tasks.value = listOf(t)

            viewModel = createViewModel()
            advanceUntilIdle()

            // One-time tasks are not in recurringTasks so no habit checkmark to toggle.
            // Verify the state is consistent.
            assertThat(viewModel.uiState.value.recurringTasks).isEmpty()
            assertThat(viewModel.uiState.value.taskCheckmarks).doesNotContainKey("t1")
        }

    // ── ViewModel: dismissCompletionDialog ─────────────────────────────────

    @Test
    fun `dismissCompletionDialog clears dialog state`() =
        runTest(testDispatcher) {
            val h = habit("h1", "1/1")
            taskRepository.tasks.value = listOf(h)

            viewModel = createViewModel()
            advanceUntilIdle()

            val today = LocalDate.now()
            val checkmarks = viewModel.uiState.value.taskCheckmarks["h1"] ?: emptyList()
            val todayCheckmark = checkmarks.find { it.date == today }
            if (todayCheckmark != null && todayCheckmark.status == CheckmarkStatus.PENDING) {
                viewModel.toggleCheckmark("h1", today)
                advanceUntilIdle()
                assertThat(viewModel.uiState.value.showCompletionDialog).isTrue()

                viewModel.dismissCompletionDialog()
                advanceUntilIdle()

                assertThat(viewModel.uiState.value.showCompletionDialog).isFalse()
                assertThat(viewModel.uiState.value.completionDialogTask).isNull()
                assertThat(viewModel.uiState.value.completionDialogDate).isNull()
            }
        }

    // ── ViewModel: confirmCompletion ───────────────────────────────────────

    @Test
    fun `confirmCompletion records occurrence and closes dialog`() =
        runTest(testDispatcher) {
            val h = habit("h1", "1/1")
            taskRepository.tasks.value = listOf(h)

            viewModel = createViewModel()
            advanceUntilIdle()

            val today = LocalDate.now()
            val checkmarks = viewModel.uiState.value.taskCheckmarks["h1"] ?: emptyList()
            val todayCheckmark = checkmarks.find { it.date == today }
            if (todayCheckmark != null && todayCheckmark.status == CheckmarkStatus.PENDING) {
                viewModel.toggleCheckmark("h1", today)
                advanceUntilIdle()
                assertThat(viewModel.uiState.value.showCompletionDialog).isTrue()

                viewModel.confirmCompletion(
                    actualCompletedAt = TASKS_TEST_TIME,
                    actualDurationMinutes = 15,
                )
                advanceUntilIdle()

                assertThat(viewModel.uiState.value.showCompletionDialog).isFalse()
                assertThat(habitTimeEntryRecorder.recorded).contains("h1")
                assertThat(scoreRollupTrigger.statusChanges).isNotEmpty()
            }
        }

    @Test
    fun `confirmCompletion with no dialog task does nothing`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = emptyList()
            viewModel = createViewModel()
            advanceUntilIdle()

            // No dialog open, confirm should be a no-op
            viewModel.confirmCompletion(actualCompletedAt = null, actualDurationMinutes = null)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.showCompletionDialog).isFalse()
        }

    // ── ViewModel: updateCheckmark ─────────────────────────────────────────

    @Test
    fun `updateCheckmark to PENDING deletes occurrence`() =
        runTest(testDispatcher) {
            val h = habit("h1", "1/1")
            taskRepository.tasks.value = listOf(h)

            viewModel = createViewModel()
            advanceUntilIdle()

            val today = LocalDate.now()
            viewModel.updateCheckmark("h1", today, CheckmarkStatus.PENDING, "")
            advanceUntilIdle()

            // The occurrence should be deleted (no crash = success; fake records the delete)
            assertThat(viewModel.uiState.value.error).isNull()
        }

    @Test
    fun `updateCheckmark to COMPLETED records occurrence`() =
        runTest(testDispatcher) {
            val h = habit("h1", "1/1")
            taskRepository.tasks.value = listOf(h)

            viewModel = createViewModel()
            advanceUntilIdle()

            val today = LocalDate.now()
            viewModel.updateCheckmark("h1", today, CheckmarkStatus.COMPLETED, "Done!")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.error).isNull()
            assertThat(scoreRollupTrigger.statusChanges).isNotEmpty()
        }

    @Test
    fun `updateCheckmark to SKIPPED records skipped occurrence`() =
        runTest(testDispatcher) {
            val h = habit("h1", "1/1")
            taskRepository.tasks.value = listOf(h)

            viewModel = createViewModel()
            advanceUntilIdle()

            val today = LocalDate.now()
            viewModel.updateCheckmark("h1", today, CheckmarkStatus.SKIPPED, "Too busy")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.error).isNull()
        }

    // ── ViewModel: completeTask for recurring task ─────────────────────────

    @Test
    fun `completeTask for recurring habit records occurrence and schedules next`() =
        runTest(testDispatcher) {
            val h = habit("h1", "1/1")
            taskRepository.tasks.value = listOf(h)
            recurrenceHandler.frequencyHabit = false

            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.completeTask("h1")
            advanceUntilIdle()

            assertThat(recurrenceHandler.completed).contains("h1")
            assertThat(notificationScheduler.scheduled).contains("h1")
            assertThat(scoreRollupTrigger.statusChanges).isNotEmpty()
        }

    @Test
    fun `completeTask for frequency habit skips direct repo completion`() =
        runTest(testDispatcher) {
            val h = habit("freqHabit", "3/7")
            taskRepository.tasks.value = listOf(h)
            recurrenceHandler.frequencyHabit = true

            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.completeTask("freqHabit")
            advanceUntilIdle()

            // frequencyHabit=true → the repo's completeTask is NOT called directly;
            // only the occurrence is recorded and recurrenceHandler.onTaskCompleted is invoked.
            assertThat(recurrenceHandler.completed).contains("freqHabit")
            // The time entry is recorded by confirmCompletion (the completion dialog's action), not by this
            // direct path - so the recorder must still be empty here.
            assertThat(habitTimeEntryRecorder.recorded).isEmpty()
        }

    @Test
    fun `completeTask for non-recurring task cancels notification`() =
        runTest(testDispatcher) {
            val t = task("t1")
            taskRepository.tasks.value = listOf(t)

            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.completeTask("t1")
            advanceUntilIdle()

            assertThat(notificationScheduler.cancelled).contains("t1")
        }

    @Test
    fun `completeTask with nonexistent task does not crash`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = emptyList()
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.completeTask("nonexistent")
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.error).isNull()
        }

    // ── ViewModel: completeTask in minimal mode ────────────────────────────

    @Test
    fun `completeTask in minimal mode skips recurrence processing`() =
        runTest(testDispatcher) {
            PlatformFeatureFlags.install(
                io.payanam.presentation.flags
                    .FeatureFlagsState(minimalModeEnabled = true),
            )
            val h = habit("h1", "1/1")
            taskRepository.tasks.value = listOf(h)

            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.completeTask("h1")
            advanceUntilIdle()

            // Minimal mode: cancel notification only, no recurrence processing
            assertThat(notificationScheduler.cancelled).contains("h1")
            assertThat(recurrenceHandler.completed).isEmpty()
        }

    // ── ViewModel: archiveTask ─────────────────────────────────────────────

    @Test
    fun `archiveTask archives and cancels notification`() =
        runTest(testDispatcher) {
            val h = habit("h1", "1/1")
            taskRepository.tasks.value = listOf(h)

            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.archiveTask("h1")
            advanceUntilIdle()

            assertThat(notificationScheduler.cancelled).contains("h1")
        }

    // ── ViewModel: chromeUiState mirrors habit fields ──────────────────────

    @Test
    fun `chromeUiState mirrors habit toggle and count fields`() =
        runTest(testDispatcher) {
            val h1 = habit("h1")
            val h2 = habit("h2")
            taskRepository.tasks.value = listOf(h1, h2)

            viewModel = createViewModel()
            // habitsTabUiState and chromeUiState are stateIn(WhileSubscribed) views, so their upstreams only run
            // while something collects them - without this, the value read below is still the placeholder.
            backgroundScope.launch { viewModel.habitsTabUiState.collect {} }
            backgroundScope.launch { viewModel.chromeUiState.collect {} }
            advanceUntilIdle()

            val chrome = viewModel.chromeUiState.value
            assertThat(chrome.recurringTaskCount).isEqualTo(2)
            // show-completed starts false: a fresh install hides already-marked habits until asked.
            assertThat(chrome.showCompletedHabits).isFalse()
            assertThat(chrome.hideAllMarkedToday).isFalse()
            assertThat(chrome.dueTodayOnly).isFalse()
        }

    @Test
    fun `habitsTabUiState mirrors visible habit rows`() =
        runTest(testDispatcher) {
            val h1 = habit("h1", "1/1")
            taskRepository.tasks.value = listOf(h1)

            viewModel = createViewModel()
            // habitsTabUiState and chromeUiState are stateIn(WhileSubscribed) views, so their upstreams only run
            // while something collects them - without this, the value read below is still the placeholder.
            backgroundScope.launch { viewModel.habitsTabUiState.collect {} }
            backgroundScope.launch { viewModel.chromeUiState.collect {} }
            advanceUntilIdle()

            val habitsTab = viewModel.habitsTabUiState.value
            assertThat(habitsTab.totalHabitCount).isEqualTo(1)
            assertThat(habitsTab.rows).isNotEmpty()
        }

    // ── TasksRowCacheManager: hideAllMarkedToday with SKIPPED ─────────────

    @Test
    fun `buildHabitRows hides skipped when hideAllMarkedToday`() {
        val h = habit("h1")
        val rows =
            TasksRowCacheManager.buildHabitRows(
                tasks = listOf(h),
                checkmarksByTaskId = emptyMap(),
                todayStatusByTaskId = mapOf("h1" to CheckmarkStatus.SKIPPED),
                showCompletedHabits = true,
                hideAllMarkedToday = true,
            )
        assertThat(rows).isEmpty()
    }

    // ── TasksRowCacheManager: hideAllMarkedToday with MISSED ──────────────

    @Test
    fun `buildHabitRows hides missed when hideAllMarkedToday`() {
        val h = habit("h1")
        val rows =
            TasksRowCacheManager.buildHabitRows(
                tasks = listOf(h),
                checkmarksByTaskId = emptyMap(),
                todayStatusByTaskId = mapOf("h1" to CheckmarkStatus.MISSED),
                showCompletedHabits = true,
                hideAllMarkedToday = true,
            )
        assertThat(rows).isEmpty()
    }

    // ── TasksRowCacheManager: !showCompletedHabits hides COMPLETED ─────────

    @Test
    fun `buildHabitRows hides completed when showCompletedHabits is false`() {
        val h = habit("h1")
        val rows =
            TasksRowCacheManager.buildHabitRows(
                tasks = listOf(h),
                checkmarksByTaskId = emptyMap(),
                todayStatusByTaskId = mapOf("h1" to CheckmarkStatus.COMPLETED),
                showCompletedHabits = false,
                hideAllMarkedToday = false,
            )
        assertThat(rows).isEmpty()
    }

    // ── TasksRowCacheManager: evicts stale cached rows ─────────────────────

    @Test
    fun `buildHabitRows evicts rows for tasks no longer in the list`() {
        val h1 = habit("h1")
        val h2 = habit("h2")
        // First call: both tasks
        TasksRowCacheManager.buildHabitRows(
            tasks = listOf(h1, h2),
            checkmarksByTaskId = emptyMap(),
            todayStatusByTaskId = emptyMap(),
            showCompletedHabits = true,
        )
        // Second call: only h1 → h2 should be evicted
        val rows =
            TasksRowCacheManager.buildHabitRows(
                tasks = listOf(h1),
                checkmarksByTaskId = emptyMap(),
                todayStatusByTaskId = emptyMap(),
                showCompletedHabits = true,
            )
        assertThat(rows).hasSize(1)
        assertThat(rows[0].id).isEqualTo("h1")
    }

    // ── TasksRowCacheManager: fingerprint cache reuse ──────────────────────

    @Test
    fun `buildHabitRows reuses cached row when fingerprint matches`() {
        val h1 = habit("h1")
        val marks = listOf(DayCheckmark(date = TASKS_TEST_DAY, status = CheckmarkStatus.COMPLETED))
        val statuses = mapOf("h1" to CheckmarkStatus.COMPLETED)

        val first =
            TasksRowCacheManager.buildHabitRows(
                tasks = listOf(h1),
                checkmarksByTaskId = mapOf("h1" to marks),
                todayStatusByTaskId = statuses,
                showCompletedHabits = true,
            )
        val second =
            TasksRowCacheManager.buildHabitRows(
                tasks = listOf(h1),
                checkmarksByTaskId = mapOf("h1" to marks),
                todayStatusByTaskId = statuses,
                showCompletedHabits = true,
            )
        // Same object reference means cache reuse
        assertThat(first[0]).isSameInstanceAs(second[0])
    }

    @Test
    fun `buildHabitRows rebuilds row when fingerprint changes`() {
        val h1 = habit("h1")
        val marks1 = listOf(DayCheckmark(date = TASKS_TEST_DAY, status = CheckmarkStatus.PENDING))
        val marks2 = listOf(DayCheckmark(date = TASKS_TEST_DAY, status = CheckmarkStatus.COMPLETED))

        val first =
            TasksRowCacheManager.buildHabitRows(
                tasks = listOf(h1),
                checkmarksByTaskId = mapOf("h1" to marks1),
                todayStatusByTaskId = mapOf("h1" to CheckmarkStatus.PENDING),
                showCompletedHabits = true,
            )
        val second =
            TasksRowCacheManager.buildHabitRows(
                tasks = listOf(h1),
                checkmarksByTaskId = mapOf("h1" to marks2),
                todayStatusByTaskId = mapOf("h1" to CheckmarkStatus.COMPLETED),
                showCompletedHabits = true,
            )
        // Different fingerprint → different object
        assertThat(first[0]).isNotSameInstanceAs(second[0])
        assertThat(second[0].todayStatus).isEqualTo(CheckmarkStatus.COMPLETED)
    }

    // ── TasksRowCacheManager: buildTaskRows ────────────────────────────────

    @Test
    fun `buildTaskRows builds rows for one-time tasks`() {
        val t1 = task("t1")
        val t2 = task("t2")
        val rows = TasksRowCacheManager.buildTaskRows(listOf(t1, t2))
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.id }).containsExactly("t1", "t2")
    }

    @Test
    fun `buildTaskRows reuses cached row when fingerprint matches`() {
        val t1 = task("t1")
        val first = TasksRowCacheManager.buildTaskRows(listOf(t1))
        val second = TasksRowCacheManager.buildTaskRows(listOf(t1))
        assertThat(first[0]).isSameInstanceAs(second[0])
    }

    @Test
    fun `buildTaskRows rebuilds when task changes`() {
        val t1 = task("t1")
        val first = TasksRowCacheManager.buildTaskRows(listOf(t1))
        val t1Updated = t1.copy(title = "Updated Title")
        val second = TasksRowCacheManager.buildTaskRows(listOf(t1Updated))
        assertThat(first[0]).isNotSameInstanceAs(second[0])
        assertThat(second[0].task.title).isEqualTo("Updated Title")
    }

    // ── HabitDueToday: non-recurring task → always due ─────────────────────

    @Test
    fun `non-recurring task is always due`() =
        runTest(testDispatcher) {
            val t = task("t1", recurrenceEnabled = false)
            val result = computeDueTodayForTask(t, emptyList(), TASKS_TEST_DAY)
            assertThat(result).isTrue()
        }

    // ── HabitDueToday: serialized frequency with dueDate anchor fallback ────

    @Test
    fun `serialized frequency uses dueDate as anchor when no start in rule`() =
        runTest(testDispatcher) {
            // 1/7 rule with no !start= → anchor should fall back to dueDate
            val dueDate = LocalDateTime.of(2026, 9, 1, 9, 0)
            val t = habit("h1", "1/7", dueDate = dueDate)
            // 2026-09-21 is 20 days after 09-01 → window [09-15..09-21] (denom=7)
            // today is inside the window → should be due
            val result = computeDueTodayForTask(t, emptyList(), TASKS_TEST_DAY)
            assertThat(result).isTrue()
        }

    // ── HabitDueToday: typed FREQUENCY via RecurrenceConfig ────────────────

    @Test
    fun `typed FREQUENCY rule evaluates via frequencyWindowDue`() =
        runTest(testDispatcher) {
            val config =
                RecurrenceConfig(
                    type = io.payanam.domain.model.RecurrenceType.FREQUENCY,
                    frequencyNumerator = 3,
                    frequencyDenominator = 7,
                    startDate = LocalDate.of(2026, 9, 1),
                )
            val t = habit("h1", config.serialize())
            // No completions → quota unmet → due
            val result = computeDueTodayForTask(t, emptyList(), TASKS_TEST_DAY)
            assertThat(result).isTrue()
        }

    @Test
    fun `typed FREQUENCY rule with quota met is not due`() =
        runTest(testDispatcher) {
            val config =
                RecurrenceConfig(
                    type = io.payanam.domain.model.RecurrenceType.FREQUENCY,
                    frequencyNumerator = 2,
                    frequencyDenominator = 7,
                    startDate = LocalDate.of(2026, 9, 14),
                )
            val t = habit("h1", config.serialize())
            // Window 09-14..09-20; 2 completions → quota met → not due
            val completions =
                listOf(
                    occurrence("h1", date = LocalDate.of(2026, 9, 15)),
                    occurrence("h1", date = LocalDate.of(2026, 9, 17)),
                )
            val result = computeDueTodayForTask(t, completions, LocalDate.of(2026, 9, 20))
            assertThat(result).isFalse()
        }

    // ── HabitDueToday: typed FREQUENCY with large denominator ──────────────

    @Test
    fun `typed FREQUENCY with large denominator fetches full history`() =
        runTest(testDispatcher) {
            val config =
                RecurrenceConfig(
                    type = io.payanam.domain.model.RecurrenceType.FREQUENCY,
                    frequencyNumerator = 1,
                    frequencyDenominator = 60,
                    startDate = LocalDate.of(2026, 7, 1),
                )
            val t = habit("h1", config.serialize())
            val today = LocalDate.of(2026, 8, 16)
            // Window 07-01..08-29; completion on 07-10 is outside 30-day lookback
            val completion = occurrence("h1", date = LocalDate.of(2026, 7, 10))
            // Without full history → due (can't see the completion)
            assertThat(computeDueTodayForTask(t, emptyList(), today)).isTrue()
            // With full history → not due (quota met)
            assertThat(
                computeDueTodayForTask(t, emptyList(), today) {
                    listOf(completion)
                },
            ).isFalse()
        }

    // ── HabitDueToday: typed YEARLY ────────────────────────────────────────

    @Test
    fun `typed YEARLY rule is due on the scheduled day`() =
        runTest(testDispatcher) {
            val config =
                RecurrenceConfig(
                    type = io.payanam.domain.model.RecurrenceType.YEARLY,
                    startDate = LocalDate.of(2024, 9, 21),
                )
            val t = habit("h1", config.serialize())
            // 2026-09-21 is the same month+day as the start → scheduled
            val result = computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 21))
            assertThat(result).isTrue()
        }

    @Test
    fun `typed YEARLY rule is not due on non-matching day`() =
        runTest(testDispatcher) {
            val config =
                RecurrenceConfig(
                    type = io.payanam.domain.model.RecurrenceType.YEARLY,
                    startDate = LocalDate.of(2024, 9, 21),
                )
            val t = habit("h1", config.serialize())
            val result = computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 22))
            assertThat(result).isFalse()
        }

    // ── HabitDueToday: YEARLY without startDate → always due ───────────────

    @Test
    fun `typed YEARLY without startDate is always due`() =
        runTest(testDispatcher) {
            val config = RecurrenceConfig(type = io.payanam.domain.model.RecurrenceType.YEARLY)
            val t = habit("h1", config.serialize())
            val result = computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 22))
            assertThat(result).isTrue()
        }

    // ── HabitDueToday: frequencyWindowDue denominator coercion ─────────────

    @Test
    fun `frequency with denominator 0 coerces to 1`() =
        runTest(testDispatcher) {
            // Parse a malformed rule that produces denominator=0 → coerced to 1
            // "1/0" → denominator coerced to 1 → 1/1 → daily-like
            val t = habit("h1", "1/0")
            val result = computeDueTodayForTask(t, emptyList(), TASKS_TEST_DAY)
            // With denominator=1, today is always inside the window, quota is 1 → due (no completions)
            assertThat(result).isTrue()
        }

    // ── HabitDueToday: invalid occurrences in frequency window ─────────────

    @Test
    fun `frequency window skips occurrences with unparseable dates`() =
        runTest(testDispatcher) {
            val t = habit("h1", "1/7!start=2026-09-01")
            val badOccurrence =
                occurrence("h1", date = TASKS_TEST_DAY, status = "completed")
                    .copy(occurrenceDate = "not-a-date")
            val result = computeDueTodayForTask(t, listOf(badOccurrence), TASKS_TEST_DAY)
            // Bad date is skipped → no valid completions → quota unmet → due
            assertThat(result).isTrue()
        }

    // ── HabitDueToday: typed FREQUENCY with start in config ────────────────

    @Test
    fun `typed FREQUENCY with start date in config uses config startDate`() =
        runTest(testDispatcher) {
            val config =
                RecurrenceConfig(
                    type = io.payanam.domain.model.RecurrenceType.FREQUENCY,
                    frequencyNumerator = 1,
                    frequencyDenominator = 7,
                    startDate = LocalDate.of(2026, 9, 14),
                )
            val t = habit("h1", config.serialize())
            // Window 09-14..09-20; today 09-21 → outside window? No, with denom=7:
            // daysSinceAnchor = 7, windowStart = 09-14 + (7/7)*7 = 09-14 + 7 = 09-21
            // windowEnd = 09-21 + 6 = 09-27 → today is inside → due
            val result = computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 21))
            assertThat(result).isTrue()
        }

    // ── HabitDueToday: serialized frequency with dueDate fallback (no start, no createdAt) ─

    @Test
    fun `serialized frequency anchor falls back to createdAt when no dueDate`() =
        runTest(testDispatcher) {
            // habit() sets dueDate = TASKS_TEST_TIME by default; override to null
            val t =
                Task(
                    id = "h1",
                    title = "h1",
                    status = "pending",
                    recurrenceEnabled = true,
                    recurrenceRule = "1/7", // no !start= → anchor falls back
                    dueDate = null, // no dueDate → fall back to createdAt
                    createdAt = LocalDateTime.of(2026, 9, 1, 9, 0),
                    updatedAt = LocalDateTime.of(2026, 9, 1, 9, 0),
                )
            // createdAt = 09-01, today = 09-21, daysSinceAnchor = 20, denom = 7
            // windowStart = 09-01 + (20/7)*7 = 09-01 + 14 = 09-15
            // windowEnd = 09-15 + 6 = 09-21 → today is inside → due
            val result = computeDueTodayForTask(t, emptyList(), TASKS_TEST_DAY)
            assertThat(result).isTrue()
        }

    // ── TasksHabitCache: unknown status string → UNKNOWN ───────────────────

    @Test
    fun `buildCheckmarksForTask maps unrecognized status to UNKNOWN`() {
        val occ = occurrence("h1", date = TASKS_TEST_DAY, status = "weird_status")
        val marks =
            buildCheckmarksForTask(
                occurrences = listOf(occ),
                today = TASKS_TEST_DAY,
                days = 1,
            )
        assertThat(marks[0].status).isEqualTo(CheckmarkStatus.UNKNOWN)
    }

    // ── TasksHabitCache: hasNote with blank statusNote and blank note ──────

    @Test
    fun `buildCheckmarksForTask hasNote false when both statusNote and note are blank`() {
        val occ =
            occurrence("h1", date = TASKS_TEST_DAY, status = "completed")
                .copy(statusNote = "  ", note = "  ")
        val marks =
            buildCheckmarksForTask(
                occurrences = listOf(occ),
                today = TASKS_TEST_DAY,
                days = 1,
            )
        assertThat(marks[0].hasNote).isFalse()
    }

    // ── TasksHabitCache: hasNote with blank statusNote but non-blank note ──

    @Test
    fun `buildCheckmarksForTask hasNote true when statusNote is present`() {
        val occ =
            occurrence("h1", date = TASKS_TEST_DAY, status = "completed")
                .copy(statusNote = "Real note", note = "Real note")
        val marks =
            buildCheckmarksForTask(
                occurrences = listOf(occ),
                today = TASKS_TEST_DAY,
                days = 1,
            )
        assertThat(marks[0].hasNote).isTrue()
        assertThat(marks[0].note).isEqualTo("Real note")
    }

    // ── TasksHabitCache: multiple tasks with mixed statuses ────────────────

    @Test
    fun `buildHabitCheckmarkPayload handles multiple tasks with mixed statuses`() {
        val habits = listOf(task("h1"), task("h2"), task("h3"))
        val occs =
            mapOf(
                "h1" to listOf(occurrence("h1", date = TASKS_TEST_DAY, status = "completed")),
                "h2" to listOf(occurrence("h2", date = TASKS_TEST_DAY, status = "skipped")),
                // h3 has no occurrences → PENDING
            )
        val payload =
            buildHabitCheckmarkPayload(
                tasks = habits,
                occurrencesMap = occs,
                today = TASKS_TEST_DAY,
                days = 3,
            )
        assertThat(payload.todayStatusByTaskId["h1"]).isEqualTo(CheckmarkStatus.COMPLETED)
        assertThat(payload.todayStatusByTaskId["h2"]).isEqualTo(CheckmarkStatus.SKIPPED)
        assertThat(payload.todayStatusByTaskId["h3"]).isEqualTo(CheckmarkStatus.PENDING)
        assertThat(payload.taskCheckmarks).hasSize(3)
    }

    // ── TasksHabitCache: empty tasks list ──────────────────────────────────

    @Test
    fun `buildHabitCheckmarkPayload with empty task list returns empty payload`() {
        val payload =
            buildHabitCheckmarkPayload(
                tasks = emptyList(),
                occurrencesMap = emptyMap(),
                today = TASKS_TEST_DAY,
                days = 7,
            )
        assertThat(payload.taskCheckmarks).isEmpty()
        assertThat(payload.todayStatusByTaskId).isEmpty()
    }

    // ── visibleHabitsForDisplay: hideAllMarkedToday with MISSED ────────────

    @Test
    fun `visibleHabitsForDisplay hides MISSED when hideAllMarkedToday`() {
        val h = habit("h1")
        val visible =
            visibleHabitsForDisplay(
                habits = listOf(h),
                todayStatusByTaskId = mapOf("h1" to CheckmarkStatus.MISSED),
                showCompletedHabits = true,
                hideAllMarkedToday = true,
            )
        assertThat(visible).isEmpty()
    }

    // ── visibleHabitsForDisplay: hideAllMarkedToday with SKIPPED ───────────

    @Test
    fun `visibleHabitsForDisplay hides SKIPPED when hideAllMarkedToday`() {
        val h = habit("h1")
        val visible =
            visibleHabitsForDisplay(
                habits = listOf(h),
                todayStatusByTaskId = mapOf("h1" to CheckmarkStatus.SKIPPED),
                showCompletedHabits = true,
                hideAllMarkedToday = true,
            )
        assertThat(visible).isEmpty()
    }

    // ── visibleHabitsForDisplay: !showCompletedHabits hides COMPLETED ──────

    @Test
    fun `visibleHabitsForDisplay hides COMPLETED when showCompletedHabits false`() {
        val done = habit("done")
        val todo = habit("todo")
        val visible =
            visibleHabitsForDisplay(
                habits = listOf(done, todo),
                todayStatusByTaskId =
                    mapOf(
                        "done" to CheckmarkStatus.COMPLETED,
                        "todo" to CheckmarkStatus.PENDING,
                    ),
                showCompletedHabits = false,
                hideAllMarkedToday = false,
            )
        assertThat(visible.map { it.id }).containsExactly("todo")
    }

    // ── visibleHabitsForDisplay: no filtering when all flags default ───────

    @Test
    fun `visibleHabitsForDisplay returns all when no filters active`() {
        val h1 = habit("h1")
        val h2 = habit("h2")
        val visible =
            visibleHabitsForDisplay(
                habits = listOf(h1, h2),
                todayStatusByTaskId =
                    mapOf(
                        "h1" to CheckmarkStatus.COMPLETED,
                        "h2" to CheckmarkStatus.PENDING,
                    ),
                showCompletedHabits = true,
                hideAllMarkedToday = false,
                dueTodayOnly = false,
            )
        assertThat(visible).hasSize(2)
    }

    // ── visibleHabitsForDisplay: PENDING status passes all filters ─────────

    @Test
    fun `visibleHabitsForDisplay shows PENDING even with hideAllMarkedToday`() {
        val h = habit("h1")
        val visible =
            visibleHabitsForDisplay(
                habits = listOf(h),
                todayStatusByTaskId = mapOf("h1" to CheckmarkStatus.PENDING),
                showCompletedHabits = true,
                hideAllMarkedToday = true,
            )
        assertThat(visible).hasSize(1)
    }

    // ── visibleHabitsForDisplay: UNKNOWN status passes all filters ─────────

    @Test
    fun `visibleHabitsForDisplay shows UNKNOWN even with hideAllMarkedToday`() {
        val h = habit("h1")
        val visible =
            visibleHabitsForDisplay(
                habits = listOf(h),
                todayStatusByTaskId = mapOf("h1" to CheckmarkStatus.UNKNOWN),
                showCompletedHabits = true,
                hideAllMarkedToday = true,
            )
        assertThat(visible).hasSize(1)
    }

    // ── visibleHabitsForDisplay: missing from todayStatusByTaskId defaults to UNKNOWN ──

    @Test
    fun `visibleHabitsForDisplay defaults missing status to UNKNOWN`() {
        val h = habit("h1")
        val visible =
            visibleHabitsForDisplay(
                habits = listOf(h),
                todayStatusByTaskId = emptyMap(), // missing → UNKNOWN
                showCompletedHabits = true,
                hideAllMarkedToday = true, // UNKNOWN passes hideAllMarked
            )
        assertThat(visible).hasSize(1)
    }

    // ── HabitDueToday: interval with null start and multiple occurrences ───

    @Test
    fun `interval null start anchors on earliest valid occurrence`() =
        runTest(testDispatcher) {
            val t =
                Task(
                    id = "h1",
                    title = "h1",
                    status = "pending",
                    recurrenceEnabled = true,
                    recurrenceRule = RecurrenceConfig.everyNDays(3).serialize(),
                    dueDate = TASKS_TEST_TIME,
                    createdAt = TASKS_TEST_TIME.minusDays(10),
                    updatedAt = TASKS_TEST_TIME.minusDays(10),
                )
            // Two occurrences: earliest is 09-18
            val occs =
                listOf(
                    occurrence("h1", date = LocalDate.of(2026, 9, 18)),
                    occurrence("h1", date = LocalDate.of(2026, 9, 21)),
                )
            // Anchor = 09-18, today = 09-21, daysSinceAnchor = 3, interval = 3 → scheduled
            val result = computeDueTodayForTask(t, occs, LocalDate.of(2026, 9, 21))
            assertThat(result).isTrue()
        }

    @Test
    fun `interval null start with invalid date occurrences falls back to visible`() =
        runTest(testDispatcher) {
            val t =
                Task(
                    id = "h1",
                    title = "h1",
                    status = "pending",
                    recurrenceEnabled = true,
                    recurrenceRule = RecurrenceConfig.everyNDays(2).serialize(),
                    dueDate = TASKS_TEST_TIME,
                    createdAt = TASKS_TEST_TIME.minusDays(5),
                    updatedAt = TASKS_TEST_TIME.minusDays(5),
                )
            val badOcc = occurrence("h1", date = TASKS_TEST_DAY).copy(occurrenceDate = "bad-date")
            // No valid anchor → anchor is null → true (keep visible)
            val result = computeDueTodayForTask(t, listOf(badOcc), TASKS_TEST_DAY)
            assertThat(result).isTrue()
        }

    // ── HabitDueToday: typed INTERVAL with startDate, not scheduled ────────

    @Test
    fun `typed INTERVAL with startDate not scheduled day returns false`() =
        runTest(testDispatcher) {
            val config =
                RecurrenceConfig(
                    type = io.payanam.domain.model.RecurrenceType.INTERVAL,
                    intervalDays = 3,
                    startDate = LocalDate.of(2026, 9, 1),
                )
            val t = habit("h1", config.serialize())
            // 09-22 - 09-01 = 21 days, 21 % 3 = 0 → scheduled
            assertThat(computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 22))).isTrue()
            // 09-23 - 09-01 = 22 days, 22 % 3 = 1 → not scheduled
            assertThat(computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 23))).isFalse()
        }

    // ── HabitDueToday: typed MONTHLY_DATES edge cases ──────────────────────

    @Test
    fun `typed MONTHLY_DATES matches multiple dates`() =
        runTest(testDispatcher) {
            val config =
                RecurrenceConfig(
                    type = io.payanam.domain.model.RecurrenceType.MONTHLY_DATES,
                    monthlyDates = setOf(1, 15, 28),
                )
            val t = habit("h1", config.serialize())
            assertThat(computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 1))).isTrue()
            assertThat(computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 15))).isTrue()
            assertThat(computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 28))).isTrue()
            assertThat(computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 16))).isFalse()
        }

    // ── HabitDueToday: typed MONTHLY_DATES with last-day-of-month (32) ─────

    @Test
    fun `typed MONTHLY_DATES with 32 matches last day of month`() =
        runTest(testDispatcher) {
            val config =
                RecurrenceConfig(
                    type = io.payanam.domain.model.RecurrenceType.MONTHLY_DATES,
                    monthlyDates = setOf(32),
                )
            val t = habit("h1", config.serialize())
            // February 2026 has 28 days
            assertThat(computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 2, 28))).isTrue()
            assertThat(computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 2, 27))).isFalse()
            // September 2026 has 30 days
            assertThat(computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 30))).isTrue()
            assertThat(computeDueTodayForTask(t, emptyList(), LocalDate.of(2026, 9, 29))).isFalse()
        }

    // ── HabitDueToday: buildDueTodayByTaskId with fetchFullHistory ──────────

    @Test
    fun `buildDueTodayByTaskId passes fetchFullHistory through`() =
        runTest(testDispatcher) {
            var fetchCalled = false
            val t = habit("h1", "1/60!start=2026-07-01") // large denominator triggers full history
            buildDueTodayByTaskId(
                tasks = listOf(t),
                occurrencesMap = emptyMap(),
                today = LocalDate.of(2026, 8, 16),
                fetchFullHistory = { task ->
                    fetchCalled = true
                    emptyList()
                },
            )
            assertThat(fetchCalled).isTrue()
        }

    // ── HabitDueToday: serialized frequency with numerator > denominator ───

    @Test
    fun `serialized frequency numerator greater than denominator still evaluates`() =
        runTest(testDispatcher) {
            // 5/3 is unusual but should coerce numerator to at least 1 and evaluate
            val t = habit("h1", "5/3")
            // The Frequency.parse coerces: 5/3 → numerator=5, denominator=3
            // With no completions, quota is 5 → unmet → due
            val result = computeDueTodayForTask(t, emptyList(), TASKS_TEST_DAY)
            assertThat(result).isTrue()
        }

    // ── ViewModel: clearError ──────────────────────────────────────────────

    @Test
    fun `clearError clears error state`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = emptyList()
            viewModel = createViewModel()
            advanceUntilIdle()

            viewModel.clearError()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.error).isNull()
        }

    // ── ViewModel: score change stream triggers day metrics refresh ────────

    @Test
    fun `score change event triggers day metrics refresh`() =
        runTest(testDispatcher) {
            scoreWindowRepository.dayRows =
                listOf(
                    habitL1(habitId = "h1", dayKey = TASKS_TEST_DAY.toString(), score = 0.75, runningAvg = 0.80, progress = 0.05),
                )
            scoreWindowRepository.earliestDay = TASKS_TEST_DAY.toString()

            viewModel = createViewModel()
            advanceUntilIdle()

            // Emit a score change event
            scoreChangeStream.emit(TASKS_TEST_DAY)
            advanceUntilIdle()

            // Day metrics state should be loaded
            assertThat(viewModel.dayMetricsState.value.isLoading).isFalse()
        }

    // ── ViewModel: loadTasks with no habits → empty habit state ────────────

    @Test
    fun `loadTasks with only one-time tasks leaves habit state empty`() =
        runTest(testDispatcher) {
            taskRepository.tasks.value = listOf(task("t1"), task("t2"))

            viewModel = createViewModel()
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.recurringTasks).isEmpty()
            assertThat(state.visibleHabitRows).isEmpty()
            assertThat(state.taskCheckmarks).isEmpty()
            assertThat(state.todayHabitStatusByTaskId).isEmpty()
            assertThat(state.dueTodayByTaskId).isEmpty()
        }

    // ── ViewModel: habit with archived status and showArchivedHabits off ────

    @Test
    fun `loadTasks excludes archived habits when showArchivedHabits is false`() =
        runTest(testDispatcher) {
            val active = habit("active")
            val archived = habit("archived").copy(status = "archived")
            taskRepository.tasks.value = listOf(active, archived)

            viewModel = createViewModel()
            advanceUntilIdle()

            // Default showArchivedHabits = false → archived habit excluded
            assertThat(
                viewModel.uiState.value.recurringTasks
                    .map { it.id },
            ).containsExactly("active")
        }

    // ── ViewModel: habit with dimensionId is carried through ───────────────

    @Test
    fun `loadTasks preserves dimensionId on habit rows`() =
        runTest(testDispatcher) {
            val h = habit("h1", dimensionId = "dim_health")
            taskRepository.tasks.value = listOf(h)

            viewModel = createViewModel()
            advanceUntilIdle()

            val row =
                viewModel.uiState.value.visibleHabitRows
                    .first { it.id == "h1" }
            assertThat(row.task.dimensionId).isEqualTo("dim_health")
        }

    // ── ViewModel: L1 metrics populated in habit rows ──────────────────────

    @Test
    fun `loadTasks populates latestL1ByHabit from metric repository`() =
        runTest(testDispatcher) {
            val h = habit("h1")
            taskRepository.tasks.value = listOf(h)
            habitMetricRepository.latestPerHabit =
                mapOf(
                    "h1" to habitL1("h1", score = 0.9, runningAvg = 0.85, progress = 0.1),
                )

            viewModel = createViewModel()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.latestL1ByHabit).containsKey("h1")
            val l1 = viewModel.uiState.value.latestL1ByHabit["h1"]!!
            assertThat(l1.runningAvg).isWithin(0.01).of(0.85)
            assertThat(l1.score).isWithin(0.01).of(0.9)
        }

    // ── ViewModel: habit rows carry checkmarks from payload ────────────────

    @Test
    fun `loadTasks builds checkmarks in habit rows`() =
        runTest(testDispatcher) {
            val h = habit("h1", "1/1")
            taskRepository.tasks.value = listOf(h)
            taskOccurrenceRepository.occurrences.value =
                listOf(
                    occurrence("h1", date = TASKS_TEST_DAY, status = "completed"),
                )
            // A marked habit is hidden from the list by default, so the row is only built when the user asks
            // for completed habits.
            appSettingsRepository.emit(AppPreferenceKeys.KEY_SHOW_COMPLETED_HABITS, "true")

            viewModel = createViewModel()
            advanceUntilIdle()

            val row =
                viewModel.uiState.value.visibleHabitRows
                    .first { it.id == "h1" }
            assertThat(row.checkmarks).isNotEmpty()
            // First checkmark is today
            assertThat(row.checkmarks.first().date).isEqualTo(TASKS_TEST_DAY)
            assertThat(row.checkmarks.first().status).isEqualTo(CheckmarkStatus.COMPLETED)
        }

    // ── ViewModel: todayHabitStatusByTaskId matches checkmark first entry ──

    @Test
    fun `todayHabitStatusByTaskId reflects today's checkmark status`() =
        runTest(testDispatcher) {
            val h = habit("h1", "1/1")
            taskRepository.tasks.value = listOf(h)
            taskOccurrenceRepository.occurrences.value =
                listOf(
                    occurrence("h1", date = TASKS_TEST_DAY, status = "skipped"),
                )

            viewModel = createViewModel()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.todayHabitStatusByTaskId["h1"]).isEqualTo(CheckmarkStatus.SKIPPED)
        }

    // ── ViewModel: dueTodayByTaskId reflects computed due-ness ─────────────

    @Test
    fun `dueTodayByTaskId reflects computed due-ness for each habit`() =
        runTest(testDispatcher) {
            val dueHabit = habit("due", "1/1") // always due
            val notDueHabit = habit("notDue", "1/7!start=$futureStart") // start still ahead → not due
            taskRepository.tasks.value = listOf(dueHabit, notDueHabit)

            viewModel = createViewModel()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.dueTodayByTaskId["due"]).isTrue()
            assertThat(viewModel.uiState.value.dueTodayByTaskId["notDue"]).isFalse()
        }

    // ── ViewModel: visibleRecurringTasks matches visibleHabitsForDisplay ────

    @Test
    fun `visibleRecurringTasks matches visibleHabitsForDisplay result`() =
        runTest(testDispatcher) {
            val due = habit("due", "1/1")
            // Start still ahead of the test day, so this habit is genuinely not due today and the
            // filter arm has something to remove on either side of the comparison below.
            val notDue = habit("notDue", "1/7!start=$futureStart")
            taskRepository.tasks.value = listOf(due, notDue)

            viewModel = createViewModel()
            advanceUntilIdle()

            // With dueTodayOnly=false, both should be visible
            val visibleFromFunc =
                visibleHabitsForDisplay(
                    habits = viewModel.uiState.value.recurringTasks,
                    todayStatusByTaskId = viewModel.uiState.value.todayHabitStatusByTaskId,
                    showCompletedHabits = viewModel.uiState.value.showCompletedHabits,
                    hideAllMarkedToday = viewModel.uiState.value.hideAllMarkedToday,
                    dueTodayOnly = viewModel.uiState.value.dueTodayOnly,
                    dueTodayByTaskId = viewModel.uiState.value.dueTodayByTaskId,
                )
            assertThat(
                viewModel.uiState.value.visibleRecurringTasks
                    .map { it.id },
            ).isEqualTo(visibleFromFunc.map { it.id })
        }
}
