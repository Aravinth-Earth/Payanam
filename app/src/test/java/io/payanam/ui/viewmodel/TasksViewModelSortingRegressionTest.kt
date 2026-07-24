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
}
