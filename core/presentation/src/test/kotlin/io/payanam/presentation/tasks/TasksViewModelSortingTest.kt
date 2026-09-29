//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression tests covering task filtering/sorting, habit sorts (score, name,
 * due-time), and `HabitSortOption.fromKey` migration.
 *
 * Ported from `app/.../TasksViewModelSortingRegressionTest.kt` (plain JVM, no Android deps).
 * Uses the shared [task] and [habitL1] builders from [TasksTestFixtures].
 */
class TasksViewModelSortingTest {
    // ── Task filter & sort ─────────────────────────────────────────────────

    @Test
    fun impact_desc_sorts_major_and_minimal_aliases_consistently() {
        val tasks =
            listOf(
                task(id = "minimal", impactLevel = "Minimal Impact"),
                task(id = "moderate", impactLevel = "Moderate Impact"),
                task(id = "major", impactLevel = "Major Impact"),
                task(id = "critical", impactLevel = "Critical Impact"),
            )
        val sorted =
            filterAndSortTasks(
                tasks = tasks,
                filter = TaskFilter.ALL,
                sort = TaskSortOption.IMPACT_DESC,
            )
        assertThat(sorted.map { it.id }).isEqualTo(listOf("critical", "major", "moderate", "minimal"))
    }

    @Test
    fun not_active_filter_includes_completed_and_archived_only() {
        val tasks =
            listOf(
                task(id = "pending", impactLevel = "Moderate Impact", status = "pending"),
                task(id = "completed", impactLevel = "Moderate Impact", status = "completed"),
                task(id = "archived", impactLevel = "Moderate Impact", status = "archived"),
            )
        val filtered =
            filterAndSortTasks(
                tasks = tasks,
                filter = TaskFilter.NOT_ACTIVE,
                sort = TaskSortOption.CREATED_ASC,
            )
        assertThat(filtered.map { it.id }).isEqualTo(listOf("completed", "archived"))
    }

    @Test
    fun all_filter_keeps_completed_and_archived_tasks() {
        val tasks =
            listOf(
                task(id = "pending", impactLevel = "Moderate Impact", status = "pending"),
                task(id = "completed", impactLevel = "Moderate Impact", status = "completed"),
                task(id = "archived", impactLevel = "Moderate Impact", status = "archived"),
            )
        val filtered =
            filterAndSortTasks(
                tasks = tasks,
                filter = TaskFilter.ALL,
                sort = TaskSortOption.CREATED_ASC,
            )
        assertThat(filtered.map { it.id }).isEqualTo(listOf("pending", "completed", "archived"))
    }

    // ── Simplified habit sorts (score = runningAvg) ────────────────────────

    /**
     * Score high low sorts by latest l1 running avg.
     */
    @Test
    fun score_high_low_sorts_by_latest_l1_running_avg() {
        val t1 = task(id = "a", impactLevel = "Moderate Impact")
        val t2 = task(id = "b", impactLevel = "Moderate Impact")
        val t3 = task(id = "c", impactLevel = "Moderate Impact")
        val l1 =
            mapOf(
                "a" to habitL1("a", runningAvg = 0.91234),
                "b" to habitL1("b", runningAvg = 0.41278),
                "c" to habitL1("c", runningAvg = 0.72391),
            )
        val sorted = sortHabits(listOf(t1, t2, t3), HabitSortOption.SCORE_HIGH_LOW, emptyMap(), latestL1ByHabit = l1)
        assertThat(sorted.map { it.id }).isEqualTo(listOf("a", "c", "b"))
    }

    @Test
    fun score_low_high_sorts_lowest_first() {
        val t1 = task(id = "a", impactLevel = "Moderate Impact")
        val t2 = task(id = "b", impactLevel = "Moderate Impact")
        val l1 =
            mapOf(
                "a" to habitL1("a", runningAvg = 0.21345),
                "b" to habitL1("b", runningAvg = 0.82391),
            )
        val sorted = sortHabits(listOf(t1, t2), HabitSortOption.SCORE_LOW_HIGH, emptyMap(), latestL1ByHabit = l1)
        assertThat(sorted.map { it.id }).isEqualTo(listOf("a", "b"))
    }

    /**
     * Habits without l1 metrics sort last on score high low.
     */
    @Test
    fun habits_without_l1_metrics_sort_last_on_score_high_low() {
        val t1 = task(id = "a", impactLevel = "Moderate Impact")
        val t2 = task(id = "b", impactLevel = "Moderate Impact")
        val l1 =
            mapOf(
                "a" to habitL1("a", runningAvg = 0.91234),
            )
        val sorted = sortHabits(listOf(t1, t2), HabitSortOption.SCORE_HIGH_LOW, emptyMap(), latestL1ByHabit = l1)
        assertThat(sorted.map { it.id }).isEqualTo(listOf("a", "b"))
    }

    @Test
    fun by_name_sorts_alphabetically() {
        val t1 = task(id = "c", impactLevel = "Moderate Impact")
        val t2 = task(id = "a", impactLevel = "Moderate Impact")
        val t3 = task(id = "b", impactLevel = "Moderate Impact")
        val sorted = sortHabits(listOf(t1, t2, t3), HabitSortOption.BY_NAME, emptyMap())
        assertThat(sorted.map { it.id }).isEqualTo(listOf("a", "b", "c"))
    }

    @Test
    fun by_name_reverse_sorts_reverse_alphabetically() {
        val t1 = task(id = "c", impactLevel = "Moderate Impact")
        val t2 = task(id = "a", impactLevel = "Moderate Impact")
        val t3 = task(id = "b", impactLevel = "Moderate Impact")
        val sorted = sortHabits(listOf(t1, t2, t3), HabitSortOption.BY_NAME_REVERSE, emptyMap())
        assertThat(sorted.map { it.id }).isEqualTo(listOf("c", "b", "a"))
    }

    @Test
    fun equal_score_tiebreaks_by_habit_id_stably() {
        // Two habits with identical runningAvg must land in creation-id order,
        // not in arbitrary list order.
        val t1 = task(id = "a", impactLevel = "Moderate Impact")
        val t2 = task(id = "b", impactLevel = "Moderate Impact")
        val l1 =
            mapOf(
                "a" to habitL1("a", runningAvg = 0.50137),
                "b" to habitL1("b", runningAvg = 0.50137),
            )
        val sorted = sortHabits(listOf(t2, t1), HabitSortOption.SCORE_HIGH_LOW, emptyMap(), latestL1ByHabit = l1)
        assertThat(sorted.map { it.id }).isEqualTo(listOf("a", "b"))
    }

    @Test
    fun equal_name_tiebreaks_by_habit_id_stably() {
        val t1 = task(id = "a", impactLevel = "Moderate Impact")
        val t2 = task(id = "b", impactLevel = "Moderate Impact")
        val t3 = task(id = "c", impactLevel = "Moderate Impact")
        // Same title forces an id tiebreak.
        val named = listOf(t3, t1, t2).map { it.copy(title = "Same") }
        val sorted = sortHabits(named, HabitSortOption.BY_NAME, emptyMap())
        assertThat(sorted.map { it.id }).isEqualTo(listOf("a", "b", "c"))
    }

    @Test
    fun by_due_time_sorts_early_to_late_with_nulls_last() {
        val tEarly = task(id = "a", impactLevel = "Moderate Impact").copy(dueDate = java.time.LocalDateTime.of(2026, 8, 20, 8, 0))
        val tLate = task(id = "b", impactLevel = "Moderate Impact").copy(dueDate = java.time.LocalDateTime.of(2026, 8, 20, 20, 0))
        val tNone = task(id = "c", impactLevel = "Moderate Impact").copy(dueDate = null)
        val sorted = sortHabits(listOf(tNone, tLate, tEarly), HabitSortOption.BY_DUE_TIME, emptyMap())
        assertThat(sorted.map { it.id }).isEqualTo(listOf("a", "b", "c"))
    }

    @Test
    fun by_due_time_reverse_sorts_late_to_early_with_nulls_last() {
        val tEarly = task(id = "a", impactLevel = "Moderate Impact").copy(dueDate = java.time.LocalDateTime.of(2026, 8, 20, 8, 0))
        val tLate = task(id = "b", impactLevel = "Moderate Impact").copy(dueDate = java.time.LocalDateTime.of(2026, 8, 20, 20, 0))
        val tNone = task(id = "c", impactLevel = "Moderate Impact").copy(dueDate = null)
        val sorted = sortHabits(listOf(tEarly, tNone, tLate), HabitSortOption.BY_DUE_TIME_REVERSE, emptyMap())
        assertThat(sorted.map { it.id }).isEqualTo(listOf("b", "a", "c"))
    }

    @Test
    fun by_due_time_tiebreaks_by_score_desc_when_times_equal() {
        val t1 = task(id = "a", impactLevel = "Moderate Impact").copy(dueDate = java.time.LocalDateTime.of(2026, 8, 20, 12, 0))
        val t2 = task(id = "b", impactLevel = "Moderate Impact").copy(dueDate = java.time.LocalDateTime.of(2026, 8, 20, 12, 0))
        val l1 =
            mapOf(
                "a" to habitL1("a", runningAvg = 0.31278),
                "b" to habitL1("b", runningAvg = 0.91234),
            )
        val sorted = sortHabits(listOf(t1, t2), HabitSortOption.BY_DUE_TIME, emptyMap(), latestL1ByHabit = l1)
        assertThat(sorted.map { it.id }).isEqualTo(listOf("b", "a"))
    }

    // ── fromKey migration ──────────────────────────────────────────────────

    /**
     * From key migrates legacy running avg desc to score high low.
     */
    @Test
    fun fromKey_migrates_legacy_running_avg_desc_to_score_high_low() {
        assertThat(HabitSortOption.fromKey("running_avg_desc")).isEqualTo(HabitSortOption.SCORE_HIGH_LOW)
    }

    @Test
    fun fromKey_migrates_legacy_by_score_to_score_high_low() {
        assertThat(HabitSortOption.fromKey("by_score")).isEqualTo(HabitSortOption.SCORE_HIGH_LOW)
    }

    @Test
    fun fromKey_migrates_legacy_by_status_to_by_name() {
        assertThat(HabitSortOption.fromKey("by_status")).isEqualTo(HabitSortOption.BY_NAME)
    }

    @Test
    fun fromKey_returns_score_high_low_for_unknown_key() {
        assertThat(HabitSortOption.fromKey("nonexistent_key")).isEqualTo(HabitSortOption.SCORE_HIGH_LOW)
    }

    @Test
    fun fromKey_returns_score_high_low_for_null() {
        assertThat(HabitSortOption.fromKey(null)).isEqualTo(HabitSortOption.SCORE_HIGH_LOW)
    }

    @Test
    fun fromKey_recognizes_new_keys_directly() {
        assertThat(HabitSortOption.fromKey("by_name")).isEqualTo(HabitSortOption.BY_NAME)
        assertThat(HabitSortOption.fromKey("by_name_reverse")).isEqualTo(HabitSortOption.BY_NAME_REVERSE)
        assertThat(HabitSortOption.fromKey("by_due_time")).isEqualTo(HabitSortOption.BY_DUE_TIME)
        assertThat(HabitSortOption.fromKey("by_due_time_reverse")).isEqualTo(HabitSortOption.BY_DUE_TIME_REVERSE)
        assertThat(HabitSortOption.fromKey("score_high_low")).isEqualTo(HabitSortOption.SCORE_HIGH_LOW)
        assertThat(HabitSortOption.fromKey("score_low_high")).isEqualTo(HabitSortOption.SCORE_LOW_HIGH)
    }
}
