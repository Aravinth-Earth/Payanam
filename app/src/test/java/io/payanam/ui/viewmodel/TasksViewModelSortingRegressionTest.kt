//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class TasksViewModelSortingRegressionTest {
    @Test
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

    // ── Inc 4: metric sorts (latest L1 runningAvg) ────────────────────────

    @Test
    fun running_avg_desc_sorts_by_latest_l1_running_avg() {
        val t1 = task("a", "Moderate Impact", LocalDateTime.now())
        val t2 = task("b", "Moderate Impact", LocalDateTime.now())
        val t3 = task("c", "Moderate Impact", LocalDateTime.now())
        val l1 = mapOf(
            "a" to io.payanam.domain.model.HabitL1Summary("a", "2026-08-07", 1.0, 0.9, 0.0, 3, 2, 3),
            "b" to io.payanam.domain.model.HabitL1Summary("b", "2026-08-07", 1.0, 0.4, 0.0, 1, 1, 1),
            "c" to io.payanam.domain.model.HabitL1Summary("c", "2026-08-07", 1.0, 0.7, 0.0, 2, 2, 2),
        )
        val sorted = sortHabits(listOf(t1, t2, t3), HabitSortOption.RUNNING_AVG_DESC, emptyMap(), latestL1ByHabit = l1)
        assertEquals(listOf("a", "c", "b"), sorted.map { it.id })
    }

    @Test
    fun running_avg_asc_sorts_lowest_first() {
        val t1 = task("a", "Moderate Impact", LocalDateTime.now())
        val t2 = task("b", "Moderate Impact", LocalDateTime.now())
        val l1 = mapOf(
            "a" to io.payanam.domain.model.HabitL1Summary("a", "2026-08-07", 1.0, 0.2, 0.0, 0, 0, 0),
            "b" to io.payanam.domain.model.HabitL1Summary("b", "2026-08-07", 1.0, 0.8, 0.0, 2, 2, 2),
        )
        val sorted = sortHabits(listOf(t1, t2), HabitSortOption.RUNNING_AVG_ASC, emptyMap(), latestL1ByHabit = l1)
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test
    fun streak_pos_desc_sorts_by_streak() {
        val t1 = task("a", "Moderate Impact", LocalDateTime.now())
        val t2 = task("b", "Moderate Impact", LocalDateTime.now())
        val l1 = mapOf(
            "a" to io.payanam.domain.model.HabitL1Summary("a", "2026-08-07", 1.0, 0.5, 0.0, 1, 1, 1),
            "b" to io.payanam.domain.model.HabitL1Summary("b", "2026-08-07", 1.0, 0.5, 0.0, 9, 9, 9),
        )
        val sorted = sortHabits(listOf(t1, t2), HabitSortOption.STREAK_POS_DESC, emptyMap(), latestL1ByHabit = l1)
        assertEquals(listOf("b", "a"), sorted.map { it.id })
    }

    @Test
    fun habits_without_l1_metrics_sort_last_desc() {
        val t1 = task("a", "Moderate Impact", LocalDateTime.now())
        val t2 = task("b", "Moderate Impact", LocalDateTime.now())
        val l1 = mapOf(
            "a" to io.payanam.domain.model.HabitL1Summary("a", "2026-08-07", 1.0, 0.9, 0.0, 3, 3, 3),
        )
        val sorted = sortHabits(listOf(t1, t2), HabitSortOption.RUNNING_AVG_DESC, emptyMap(), latestL1ByHabit = l1)
        assertEquals(listOf("a", "b"), sorted.map { it.id })
    }

    @Test
    fun by_score_aliases_running_avg_desc() {
        val t1 = task("a", "Moderate Impact", LocalDateTime.now())
        val t2 = task("b", "Moderate Impact", LocalDateTime.now())
        val l1 = mapOf(
            "a" to io.payanam.domain.model.HabitL1Summary("a", "2026-08-07", 1.0, 0.3, 0.0, 0, 0, 0),
            "b" to io.payanam.domain.model.HabitL1Summary("b", "2026-08-07", 1.0, 0.9, 0.0, 0, 0, 0),
        )
        val sorted = sortHabits(listOf(t1, t2), HabitSortOption.BY_SCORE, emptyMap(), latestL1ByHabit = l1)
        assertEquals(listOf("b", "a"), sorted.map { it.id })
    }
}
