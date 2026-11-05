//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import io.payanam.domain.model.HabitL1Summary
import io.payanam.domain.model.TaskOccurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Covers the pure data classes that moved into :core:presentation alongside the
 * Tasks-family ViewModels — [TasksChromeUiState], [HabitsTabUiState],
 * [TasksTabUiState], [TasksUiState], [TaskCheckmarks] — plus the
 * [TasksHabitCache] helpers [buildHabitCheckmarkPayload] and [buildCheckmarksForTask],
 * [PreparedTaskState], [TaskFilterCounts.countFor], and
 * [buildTaskFilterCounts].
 *
 * Sort/filter assertions for `sortHabits` / `filterAndSortTasks` /
 * `visibleHabitsForDisplay` are intentionally omitted: the app-side
 * regression suite for those functions is being ported in a parallel lane.
 */
class TasksViewModelModelsTest {
    // ── TasksChromeUiState defaults ────────────────────────────────

    @Test
    fun `TasksChromeUiState defaults are correct`() {
        val state = TasksChromeUiState()
        assertTrue(state.isLoading)
        assertEquals(0, state.recurringTaskCount)
        assertEquals(0, state.oneTimeTaskCount)
        assertEquals(HabitSortOption.SCORE_HIGH_LOW, state.habitSortOption)
        assertEquals(TaskSortOption.DUE_DATE_ASC, state.currentSort)
        assertFalse(state.showArchivedHabits)
        assertTrue(state.showCompletedHabits)
        assertFalse(state.hideAllMarkedToday)
        assertFalse(state.dueTodayOnly)
        assertFalse(state.showCompletionDialog)
        assertNull(state.completionDialogTask)
        assertNull(state.completionDialogDate)
    }

    @Test
    fun `TasksChromeUiState copy with custom values`() {
        val state =
            TasksChromeUiState(
                isLoading = false,
                recurringTaskCount = 5,
                oneTimeTaskCount = 12,
                habitSortOption = HabitSortOption.BY_NAME,
                currentSort = TaskSortOption.SCORE_DESC,
                showArchivedHabits = true,
                showCompletionDialog = true,
            )
        assertFalse(state.isLoading)
        assertEquals(5, state.recurringTaskCount)
        assertEquals(12, state.oneTimeTaskCount)
        assertEquals(HabitSortOption.BY_NAME, state.habitSortOption)
        assertEquals(TaskSortOption.SCORE_DESC, state.currentSort)
        assertTrue(state.showArchivedHabits)
        assertTrue(state.showCompletionDialog)
    }

    // ── HabitsTabUiState defaults ──────────────────────────────────

    @Test
    fun `HabitsTabUiState defaults are empty`() {
        val state = HabitsTabUiState()
        assertTrue(state.rows.isEmpty())
        assertEquals(0, state.totalHabitCount)
    }

    // ── TasksTabUiState defaults ───────────────────────────────────

    @Test
    fun `TasksTabUiState defaults are correct`() {
        val state = TasksTabUiState()
        assertTrue(state.rows.isEmpty())
        assertEquals(TaskFilter.TODAY, state.currentFilter)
        assertEquals(TaskFilterCounts(), state.filterCounts)
        assertEquals(0, state.overdueCount)
    }

    // ── TasksUiState defaults ──────────────────────────────────────

    @Test
    fun `TasksUiState defaults are correct`() {
        val state = TasksUiState()
        assertTrue(state.tasks.isEmpty())
        assertTrue(state.filteredTasks.isEmpty())
        assertTrue(state.filteredOneTimeTasks.isEmpty())
        assertTrue(state.recurringTasks.isEmpty())
        assertTrue(state.visibleRecurringTasks.isEmpty())
        assertTrue(state.oneTimeTasks.isEmpty())
        assertTrue(state.visibleHabitRows.isEmpty())
        assertTrue(state.filteredTaskRows.isEmpty())
        assertEquals(TaskFilterCounts(), state.taskFilterCounts)
        assertTrue(state.taskCheckmarks.isEmpty())
        assertTrue(state.todayHabitStatusByTaskId.isEmpty())
        assertTrue(state.dueTodayByTaskId.isEmpty())
        assertTrue(state.latestL1ByHabit.isEmpty())
        assertTrue(state.isLoading)
        assertNull(state.error)
        assertEquals(TaskFilter.TODAY, state.currentFilter)
        assertEquals(TaskSortOption.DUE_DATE_ASC, state.currentSort)
        assertEquals(0, state.todayCount)
        assertEquals(0, state.overdueCount)
        assertEquals(HabitSortOption.SCORE_HIGH_LOW, state.habitSortOption)
        assertFalse(state.showArchivedHabits)
        assertTrue(state.showCompletedHabits)
        assertFalse(state.hideAllMarkedToday)
        assertFalse(state.dueTodayOnly)
        assertFalse(state.showCompletionDialog)
        assertNull(state.completionDialogTask)
        assertNull(state.completionDialogDate)
    }

    @Test
    fun `TasksUiState copy preserves unmodified fields`() {
        val original =
            TasksUiState(
                tasks = listOf(task("t1")),
                isLoading = false,
                error = "boom",
                todayCount = 3,
                overdueCount = 7,
            )
        val modified = original.copy(isLoading = true, error = null)
        assertEquals(1, modified.tasks.size)
        assertEquals("t1", modified.tasks[0].id)
        assertTrue(modified.isLoading)
        assertNull(modified.error)
        assertEquals(3, modified.todayCount)
        assertEquals(7, modified.overdueCount)
    }

    // ── TaskCheckmarks ─────────────────────────────────────────────

    @Test
    fun `TaskCheckmarks holds taskId and checkmark list`() {
        val checkmarks =
            listOf(
                DayCheckmark(date = TASKS_TEST_DAY, status = CheckmarkStatus.COMPLETED),
                DayCheckmark(date = TASKS_TEST_DAY.minusDays(1), status = CheckmarkStatus.SKIPPED),
            )
        val tc = TaskCheckmarks(taskId = "h1", checkmarks = checkmarks)
        assertEquals("h1", tc.taskId)
        assertEquals(2, tc.checkmarks.size)
        assertEquals(CheckmarkStatus.COMPLETED, tc.checkmarks[0].status)
        assertEquals(CheckmarkStatus.SKIPPED, tc.checkmarks[1].status)
    }

    // ── PreparedTaskState ──────────────────────────────────────────

    @Test
    fun `PreparedTaskState holds all fields`() {
        val t1 = task("t1")
        val habitRow =
            HabitRowUiModel(
                id = "h1",
                task = t1,
                checkmarks = emptyList(),
                todayStatus = CheckmarkStatus.COMPLETED,
                fingerprint = 42,
            )
        val taskRow = TaskRowUiModel(id = "t1", task = t1, fingerprint = 99)
        val counts = TaskFilterCounts(all = 10, active = 5)

        val prepared =
            PreparedTaskState(
                filteredTasks = listOf(t1),
                filteredOneTimeTasks = listOf(t1),
                recurringTasks = emptyList(),
                visibleRecurringTasks = emptyList(),
                visibleHabitRows = listOf(habitRow),
                filteredTaskRows = listOf(taskRow),
                filterCounts = counts,
            )

        assertEquals(1, prepared.filteredTasks.size)
        assertEquals(1, prepared.filteredOneTimeTasks.size)
        assertTrue(prepared.recurringTasks.isEmpty())
        assertTrue(prepared.visibleRecurringTasks.isEmpty())
        assertEquals(1, prepared.visibleHabitRows.size)
        assertEquals("h1", prepared.visibleHabitRows[0].id)
        assertEquals(1, prepared.filteredTaskRows.size)
        assertEquals("t1", prepared.filteredTaskRows[0].id)
        assertEquals(10, prepared.filterCounts.all)
        assertEquals(5, prepared.filterCounts.active)
    }

    // ── TaskFilterCounts.countFor ──────────────────────────────────

    @Test
    fun `TaskFilterCounts countFor returns correct value per filter`() {
        val counts =
            TaskFilterCounts(
                all = 20,
                active = 12,
                today = 3,
                overdue = 2,
                future = 5,
                completed = 8,
                archived = 1,
                notActive = 9,
            )
        assertEquals(20, counts.countFor(TaskFilter.ALL))
        assertEquals(12, counts.countFor(TaskFilter.ACTIVE))
        assertEquals(3, counts.countFor(TaskFilter.TODAY))
        assertEquals(2, counts.countFor(TaskFilter.OVERDUE))
        assertEquals(5, counts.countFor(TaskFilter.FUTURE))
        assertEquals(8, counts.countFor(TaskFilter.COMPLETED))
        assertEquals(1, counts.countFor(TaskFilter.ARCHIVED))
        assertEquals(9, counts.countFor(TaskFilter.NOT_ACTIVE))
    }

    @Test
    fun `TaskFilterCounts default has zero for all filters`() {
        val counts = TaskFilterCounts()
        for (filter in TaskFilter.entries) {
            assertEquals(0, counts.countFor(filter))
        }
    }

    // ── buildTaskFilterCounts ──────────────────────────────────────

    @Test
    fun `buildTaskFilterCounts tallies active and completed correctly`() {
        val tasks =
            listOf(
                task("t1", status = "pending"),
                task("t2", status = "completed"),
                task("t3", status = "archived"),
                task("t4", status = "active"),
                task("t5", status = "completed"),
            )
        val counts =
            buildTaskFilterCounts(
                oneTimeTasks = tasks,
                todayCount = 1,
                overdueCount = 2,
                futureCount = 3,
            )
        assertEquals(5, counts.all)
        assertEquals(2, counts.active) // t1 pending + t4 active
        assertEquals(1, counts.today)
        assertEquals(2, counts.overdue)
        assertEquals(3, counts.future)
        assertEquals(2, counts.completed) // t2 + t5
        assertEquals(1, counts.archived) // t3
        assertEquals(3, counts.notActive) // 2 completed + 1 archived
    }

    @Test
    fun `buildTaskFilterCounts counts the legacy active status as active`() {
        // Task.status is non-null in the domain model, so the `status == null` arm of the count is
        // unreachable; what IS reachable is the legacy "active" value a pre-v17 row can still carry.
        val tasks = listOf(task("t1").copy(status = "active"))
        val counts = buildTaskFilterCounts(oneTimeTasks = tasks, todayCount = 0, overdueCount = 0, futureCount = 0)
        assertEquals(1, counts.all)
        assertEquals(1, counts.active)
    }

    @Test
    fun `buildTaskFilterCounts with empty list produces all zeros`() {
        val counts = buildTaskFilterCounts(oneTimeTasks = emptyList(), todayCount = 0, overdueCount = 0, futureCount = 0)
        assertEquals(0, counts.all)
        assertEquals(0, counts.active)
        assertEquals(0, counts.completed)
        assertEquals(0, counts.archived)
        assertEquals(0, counts.notActive)
    }

    // ── buildHabitCheckmarkPayload ─────────────────────────────────

    @Test
    fun `buildHabitCheckmarkPayload with no occurrences produces UNKNOWN statuses`() {
        val habits = listOf(task("h1"), task("h2"))
        val payload =
            buildHabitCheckmarkPayload(
                tasks = habits,
                occurrencesMap = emptyMap(),
                today = TASKS_TEST_DAY,
                days = 7,
            )
        assertEquals(2, payload.taskCheckmarks.size)
        assertTrue(payload.taskCheckmarks.containsKey("h1"))
        assertTrue(payload.taskCheckmarks.containsKey("h2"))
        // Today with no occurrence → PENDING (not UNKNOWN, because today without data is PENDING)
        assertEquals(CheckmarkStatus.PENDING, payload.todayStatusByTaskId["h1"])
        assertEquals(CheckmarkStatus.PENDING, payload.todayStatusByTaskId["h2"])
    }

    @Test
    fun `buildHabitCheckmarkPayload with completed occurrence today`() {
        val habits = listOf(task("h1"))
        val occs =
            mapOf(
                "h1" to listOf(occurrence("h1", date = TASKS_TEST_DAY, status = "completed")),
            )
        val payload =
            buildHabitCheckmarkPayload(
                tasks = habits,
                occurrencesMap = occs,
                today = TASKS_TEST_DAY,
                days = 3,
            )
        assertEquals(CheckmarkStatus.COMPLETED, payload.todayStatusByTaskId["h1"])
        val marks = payload.taskCheckmarks["h1"]!!
        assertEquals(3, marks.size)
        assertEquals(CheckmarkStatus.COMPLETED, marks[0].status) // today
    }

    @Test
    fun `buildHabitCheckmarkPayload with skipped occurrence`() {
        val habits = listOf(task("h1"))
        val occs =
            mapOf(
                "h1" to listOf(occurrence("h1", date = TASKS_TEST_DAY, status = "skipped")),
            )
        val payload =
            buildHabitCheckmarkPayload(
                tasks = habits,
                occurrencesMap = occs,
                today = TASKS_TEST_DAY,
                days = 1,
            )
        assertEquals(CheckmarkStatus.SKIPPED, payload.todayStatusByTaskId["h1"])
    }

    @Test
    fun `buildHabitCheckmarkPayload with missed occurrence`() {
        val habits = listOf(task("h1"))
        val occs =
            mapOf(
                "h1" to listOf(occurrence("h1", date = TASKS_TEST_DAY.minusDays(1), status = "missed")),
            )
        val payload =
            buildHabitCheckmarkPayload(
                tasks = habits,
                occurrencesMap = occs,
                today = TASKS_TEST_DAY,
                days = 2,
            )
        // Today has no occurrence → PENDING
        assertEquals(CheckmarkStatus.PENDING, payload.todayStatusByTaskId["h1"])
        // Yesterday was missed
        val marks = payload.taskCheckmarks["h1"]!!
        assertEquals(CheckmarkStatus.MISSED, marks[1].status)
    }

    @Test
    fun `buildHabitCheckmarkPayload history days are newest first`() {
        val habits = listOf(task("h1"))
        val occs =
            mapOf(
                "h1" to
                    listOf(
                        occurrence("h1", date = TASKS_TEST_DAY, status = "completed"),
                        occurrence("h1", date = TASKS_TEST_DAY.minusDays(1), status = "missed"),
                        occurrence("h1", date = TASKS_TEST_DAY.minusDays(2), status = "completed"),
                    ),
            )
        val payload =
            buildHabitCheckmarkPayload(
                tasks = habits,
                occurrencesMap = occs,
                today = TASKS_TEST_DAY,
                days = 3,
            )
        val marks = payload.taskCheckmarks["h1"]!!
        assertEquals(3, marks.size)
        assertEquals(TASKS_TEST_DAY, marks[0].date)
        assertEquals(TASKS_TEST_DAY.minusDays(1), marks[1].date)
        assertEquals(TASKS_TEST_DAY.minusDays(2), marks[2].date)
    }

    // ── buildCheckmarksForTask (direct) ────────────────────────────

    @Test
    fun `buildCheckmarksForTask empty occurrences all UNKNOWN except today PENDING`() {
        val marks = buildCheckmarksForTask(occurrences = emptyList(), today = TASKS_TEST_DAY, days = 5)
        assertEquals(5, marks.size)
        assertEquals(CheckmarkStatus.PENDING, marks[0].status) // today, no data → PENDING
        for (i in 1 until 5) {
            assertEquals(CheckmarkStatus.UNKNOWN, marks[i].status)
        }
    }

    @Test
    fun `buildCheckmarksForTask invalid date in occurrence is skipped`() {
        val badOccurrence =
            occurrence("h1", date = TASKS_TEST_DAY, status = "completed")
                .copy(occurrenceDate = "not-a-date")
        val marks =
            buildCheckmarksForTask(
                occurrences = listOf(badOccurrence),
                today = TASKS_TEST_DAY,
                days = 1,
            )
        assertEquals(1, marks.size)
        // Bad date is filtered out, so today has no valid match → PENDING
        assertEquals(CheckmarkStatus.PENDING, marks[0].status)
    }

    @Test
    fun `buildCheckmarksForTask records hasNote when statusNote present`() {
        val occ =
            occurrence("h1", date = TASKS_TEST_DAY, status = "completed")
                .copy(statusNote = "Felt great")
        val marks =
            buildCheckmarksForTask(
                occurrences = listOf(occ),
                today = TASKS_TEST_DAY,
                days = 1,
            )
        assertTrue(marks[0].hasNote)
        assertEquals("Felt great", marks[0].note)
    }

    @Test
    fun `buildCheckmarksForTask records hasNote when note field present`() {
        val occ =
            occurrence("h1", date = TASKS_TEST_DAY, status = "completed")
                .copy(note = "Quick note")
        val marks =
            buildCheckmarksForTask(
                occurrences = listOf(occ),
                today = TASKS_TEST_DAY,
                days = 1,
            )
        assertTrue(marks[0].hasNote)
        assertEquals("Quick note", marks[0].note)
    }

    // ── HabitRowUiModel ────────────────────────────────────────────

    @Test
    fun `HabitRowUiModel data class equality by fields`() {
        val t = task("h1")
        val marks = listOf(DayCheckmark(date = TASKS_TEST_DAY, status = CheckmarkStatus.COMPLETED))
        val row1 = HabitRowUiModel(id = "h1", task = t, checkmarks = marks, todayStatus = CheckmarkStatus.COMPLETED, fingerprint = 10)
        val row2 = HabitRowUiModel(id = "h1", task = t, checkmarks = marks, todayStatus = CheckmarkStatus.COMPLETED, fingerprint = 10)
        assertEquals(row1, row2)
        assertEquals(row1.hashCode(), row2.hashCode())
    }

    @Test
    fun `HabitRowUiModel with latestL1`() {
        val t = task("h1")
        val l1 = habitL1("h1", score = 0.9, runningAvg = 0.85, progress = 1.0)
        val row =
            HabitRowUiModel(
                id = "h1",
                task = t,
                checkmarks = emptyList(),
                todayStatus = CheckmarkStatus.COMPLETED,
                fingerprint = 1,
                latestL1 = l1,
            )
        assertNotNull(row.latestL1)
        assertEquals(0.85, row.latestL1!!.runningAvg, 0.001)
    }

    // ── TaskRowUiModel ─────────────────────────────────────────────

    @Test
    fun `TaskRowUiModel data class equality by fields`() {
        val t = task("t1")
        val row1 = TaskRowUiModel(id = "t1", task = t, fingerprint = 42)
        val row2 = TaskRowUiModel(id = "t1", task = t, fingerprint = 42)
        assertEquals(row1, row2)
    }

    @Test
    fun `TaskRowUiModel different fingerprint is not equal`() {
        val t = task("t1")
        val row1 = TaskRowUiModel(id = "t1", task = t, fingerprint = 42)
        val row2 = TaskRowUiModel(id = "t1", task = t, fingerprint = 99)
        assertFalse(row1 == row2)
    }
}
