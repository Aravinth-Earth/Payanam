//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * TasksViewModelSortingRegressionTest.
 */
class TasksViewModelSortingRegressionTest {
    @Test
    /**
     * Impact desc sorts major and minimal aliases consistently.
     */
    fun impact_desc_sorts_major_and_minimal_aliases_consistently() {
        val baseTime = LocalDateTime.of(2026, 2, 15, 10, 0)
        val tasks = listOf(
            task(id = "minimal", impact = "Minimal Impact", baseTime = baseTime),
            task(id = "moderate", impact = "Moderate Impact", baseTime = baseTime),
            task(id = "major", impact = "Major Impact", baseTime = baseTime),
            task(id = "critical", impact = "Critical Impact", baseTime = baseTime),
        )
        val sorted = filterAndSortTasks(
            tasks = tasks,
            filter = TaskFilter.ALL,
            sort = TaskSortOption.IMPACT_DESC,
        )
        assertEquals(listOf("critical", "major", "moderate", "minimal"), sorted.map { it.id })
    }

    @Test
    /**
     * Not active filter includes completed and archived only.
     */
    fun not_active_filter_includes_completed_and_archived_only() {
        val baseTime = LocalDateTime.of(2026, 2, 15, 10, 0)
        val tasks = listOf(
            task(id = "pending", impact = "Moderate Impact", baseTime = baseTime, status = "pending"),
            task(id = "completed", impact = "Moderate Impact", baseTime = baseTime, status = "completed"),
            task(id = "archived", impact = "Moderate Impact", baseTime = baseTime, status = "archived"),
        )
        val filtered = filterAndSortTasks(
            tasks = tasks,
            filter = TaskFilter.NOT_ACTIVE,
            sort = TaskSortOption.CREATED_ASC,
        )
        assertEquals(listOf("completed", "archived"), filtered.map { it.id })
    }

    @Test
    /**
     * All filter keeps completed and archived tasks.
     */
    fun all_filter_keeps_completed_and_archived_tasks() {
        val baseTime = LocalDateTime.of(2026, 2, 15, 10, 0)
        val tasks = listOf(
            task(id = "pending", impact = "Moderate Impact", baseTime = baseTime, status = "pending"),
            task(id = "completed", impact = "Moderate Impact", baseTime = baseTime, status = "completed"),
            task(id = "archived", impact = "Moderate Impact", baseTime = baseTime, status = "archived"),
        )
        val filtered = filterAndSortTasks(
            tasks = tasks,
            filter = TaskFilter.ALL,
            sort = TaskSortOption.CREATED_ASC,
        )
        assertEquals(listOf("pending", "completed", "archived"), filtered.map { it.id })
    }

    private fun task(id: String, impact: String, baseTime: LocalDateTime, status: String = "pending"): Task = Task(
        id = id,
        title = id,
        status = status,
        impactLevel = impact,
        createdAt = baseTime,
        updatedAt = baseTime,
    )

    // ── Simplified habit sorts (score = runningAvg) ────────────────────────

    @Test
    /**
     * Score high low sorts by latest l1 running avg.
     */
    fun score_high_low_sorts_by_latest_l1_running_avg() {
        val t1 = task("a", "Moderate Impact", LocalDateTime.now())
        val t2 = task("b", "Moderate Impact", LocalDateTime.now())
        val t3 = task("c", "Moderate Impact", LocalDateTime.now())
        val l1 = mapOf(
            "a" to io.payanam.domain.model.HabitL1Summary("a", "2026-08-07", 1.0, 0.91234, 0.0, 3, 2, 3),
            "b" to io.payanam.domain.model.HabitL1Summary("b", "2026-08-07", 1.0, 0.41278, 0.0, 1, 1, 1),
            "c" to io.payanam.domain.model.HabitL1Summary("c", "2026-08-07", 1.0, 0.72391, 0.0, 2, 2, 2),
        )
        val sorted = sortHabits(listOf(t1, t2, t3), HabitSortOption.SCORE_HIGH_LOW, emptyMap(), latestL1ByHabit = l1)
        assertEquals(listOf("a", "c", "b"), sorted.map { it.id })
    }

    @Test
    /**
     * Score low high sorts lowest first.
     */
    fun score_low_high_sorts_lowest_first() {
        val t1 = task("a", "Moderate Impact", LocalDateTime.now())
        val t2 = task("b", "Moderate Impact", LocalDateTime.now())
        val l1 = mapOf(
            "a" to io.payanam.domain.model.HabitL1Summary("a", "2026-08-07", 1.0, 0.21345, 0.0, 0, 0, 0),
            "b" to io.payanam.domain.model.HabitL1Summary("b", "2026-08-07", 1.0, 0.82391, 0.0, 2, 2, 2),
        )
        val sorted = sortHabits(listOf(t1, t2), HabitSortOption.SCORE_LOW_HIGH, emptyMap(), latestL1ByHabit = l1)
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test
    /**
     * Habits without l1 metrics sort last on score high low.
     */
    fun habits_without_l1_metrics_sort_last_on_score_high_low() {
        val t1 = task("a", "Moderate Impact", LocalDateTime.now())
        val t2 = task("b", "Moderate Impact", LocalDateTime.now())
        val l1 = mapOf(
            "a" to io.payanam.domain.model.HabitL1Summary("a", "2026-08-07", 1.0, 0.91234, 0.0, 3, 3, 3),
        )
        val sorted = sortHabits(listOf(t1, t2), HabitSortOption.SCORE_HIGH_LOW, emptyMap(), latestL1ByHabit = l1)
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test
    /**
     * By name sorts alphabetically.
     */
    fun by_name_sorts_alphabetically() {
        val t1 = task("c", "Moderate Impact", LocalDateTime.now())
        val t2 = task("a", "Moderate Impact", LocalDateTime.now())
        val t3 = task("b", "Moderate Impact", LocalDateTime.now())
        val sorted = sortHabits(listOf(t1, t2, t3), HabitSortOption.BY_NAME, emptyMap())
        assertEquals(listOf("a", "b", "c"), sorted.map { it.id })
    }

    @Test
    /**
     * By name reverse sorts reverse alphabetically.
     */
    fun by_name_reverse_sorts_reverse_alphabetically() {
        val t1 = task("c", "Moderate Impact", LocalDateTime.now())
        val t2 = task("a", "Moderate Impact", LocalDateTime.now())
        val t3 = task("b", "Moderate Impact", LocalDateTime.now())
        val sorted = sortHabits(listOf(t1, t2, t3), HabitSortOption.BY_NAME_REVERSE, emptyMap())
        assertEquals(listOf("c", "b", "a"), sorted.map { it.id })
    }

    @Test
    /**
     * Equal score tiebreaks by habit id stably.
     */
    fun equal_score_tiebreaks_by_habit_id_stably() {
        // Two habits with identical runningAvg must land in creation-id order,
        // not in arbitrary list order.
        val t1 = task("a", "Moderate Impact", LocalDateTime.now())
        val t2 = task("b", "Moderate Impact", LocalDateTime.now())
        val l1 = mapOf(
            "a" to io.payanam.domain.model.HabitL1Summary("a", "2026-08-07", 1.0, 0.50137, 0.0, 2, 2, 2),
            "b" to io.payanam.domain.model.HabitL1Summary("b", "2026-08-07", 1.0, 0.50137, 0.0, 2, 2, 2),
        )
        val sorted = sortHabits(listOf(t2, t1), HabitSortOption.SCORE_HIGH_LOW, emptyMap(), latestL1ByHabit = l1)
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test
    /**
     * Equal name tiebreaks by habit id stably.
     */
    fun equal_name_tiebreaks_by_habit_id_stably() {
        val t1 = task("a", "Moderate Impact", LocalDateTime.now())
        val t2 = task("b", "Moderate Impact", LocalDateTime.now())
        val t3 = task("c", "Moderate Impact", LocalDateTime.now())
        // Same title forces an id tiebreak.
        val named = listOf(t3, t1, t2).map { it.copy(title = "Same") }
        val sorted = sortHabits(named, HabitSortOption.BY_NAME, emptyMap())
        assertEquals(listOf("a", "b", "c"), sorted.map { it.id })
    }

    @Test
    /**
     * By due time sorts early to late with nulls last.
     */
    fun by_due_time_sorts_early_to_late_with_nulls_last() {
        val tEarly = task("a", "Moderate Impact", LocalDateTime.now()).copy(dueDate = LocalDateTime.of(2026, 8, 20, 8, 0))
        val tLate = task("b", "Moderate Impact", LocalDateTime.now()).copy(dueDate = LocalDateTime.of(2026, 8, 20, 20, 0))
        val tNone = task("c", "Moderate Impact", LocalDateTime.now()).copy(dueDate = null)
        val sorted = sortHabits(listOf(tNone, tLate, tEarly), HabitSortOption.BY_DUE_TIME, emptyMap())
        assertEquals(listOf("a", "b", "c"), sorted.map { it.id })
    }

    @Test
    /**
     * By due time reverse sorts late to early with nulls last.
     */
    fun by_due_time_reverse_sorts_late_to_early_with_nulls_last() {
        val tEarly = task("a", "Moderate Impact", LocalDateTime.now()).copy(dueDate = LocalDateTime.of(2026, 8, 20, 8, 0))
        val tLate = task("b", "Moderate Impact", LocalDateTime.now()).copy(dueDate = LocalDateTime.of(2026, 8, 20, 20, 0))
        val tNone = task("c", "Moderate Impact", LocalDateTime.now()).copy(dueDate = null)
        val sorted = sortHabits(listOf(tEarly, tNone, tLate), HabitSortOption.BY_DUE_TIME_REVERSE, emptyMap())
        assertEquals(listOf("b", "a", "c"), sorted.map { it.id })
    }

    @Test
    /**
     * By due time tiebreaks by score desc when times equal.
     */
    fun by_due_time_tiebreaks_by_score_desc_when_times_equal() {
        val t1 = task("a", "Moderate Impact", LocalDateTime.now()).copy(dueDate = LocalDateTime.of(2026, 8, 20, 12, 0))
        val t2 = task("b", "Moderate Impact", LocalDateTime.now()).copy(dueDate = LocalDateTime.of(2026, 8, 20, 12, 0))
        val l1 = mapOf(
            "a" to io.payanam.domain.model.HabitL1Summary("a", "2026-08-07", 1.0, 0.31278, 0.0, 1, 1, 1),
            "b" to io.payanam.domain.model.HabitL1Summary("b", "2026-08-07", 1.0, 0.91234, 0.0, 3, 3, 3),
        )
        val sorted = sortHabits(listOf(t1, t2), HabitSortOption.BY_DUE_TIME, emptyMap(), latestL1ByHabit = l1)
        assertEquals(listOf("b", "a"), sorted.map { it.id })
    }

    // ── fromKey migration ──────────────────────────────────────────────────

    @Test
    /**
     * From key migrates legacy running avg desc to score high low.
     */
    fun fromKey_migrates_legacy_running_avg_desc_to_score_high_low() {
        assertEquals(HabitSortOption.SCORE_HIGH_LOW, HabitSortOption.fromKey("running_avg_desc"))
    }

    @Test
    /**
     * From key migrates legacy by score to score high low.
     */
    fun fromKey_migrates_legacy_by_score_to_score_high_low() {
        assertEquals(HabitSortOption.SCORE_HIGH_LOW, HabitSortOption.fromKey("by_score"))
    }

    @Test
    /**
     * From key migrates legacy by status to by name.
     */
    fun fromKey_migrates_legacy_by_status_to_by_name() {
        assertEquals(HabitSortOption.BY_NAME, HabitSortOption.fromKey("by_status"))
    }

    @Test
    /**
     * From key returns score high low for unknown key.
     */
    fun fromKey_returns_score_high_low_for_unknown_key() {
        assertEquals(HabitSortOption.SCORE_HIGH_LOW, HabitSortOption.fromKey("nonexistent_key"))
    }

    @Test
    /**
     * From key returns score high low for null.
     */
    fun fromKey_returns_score_high_low_for_null() {
        assertEquals(HabitSortOption.SCORE_HIGH_LOW, HabitSortOption.fromKey(null))
    }

    @Test
    /**
     * From key recognizes new keys directly.
     */
    fun fromKey_recognizes_new_keys_directly() {
        assertEquals(HabitSortOption.BY_NAME, HabitSortOption.fromKey("by_name"))
        assertEquals(HabitSortOption.BY_NAME_REVERSE, HabitSortOption.fromKey("by_name_reverse"))
        assertEquals(HabitSortOption.BY_DUE_TIME, HabitSortOption.fromKey("by_due_time"))
        assertEquals(HabitSortOption.BY_DUE_TIME_REVERSE, HabitSortOption.fromKey("by_due_time_reverse"))
        assertEquals(HabitSortOption.SCORE_HIGH_LOW, HabitSortOption.fromKey("score_high_low"))
        assertEquals(HabitSortOption.SCORE_LOW_HIGH, HabitSortOption.fromKey("score_low_high"))
    }
}
