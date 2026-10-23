//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import io.payanam.domain.model.HabitL1Summary
import io.payanam.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Regression: rebuilt habit rows must carry the L1 map (zero-ring bug). */
class TasksRowCacheManagerTest {
    private fun habit(id: String): Task =
        Task(
            id = id,
            title = "Habit $id",
            status = "pending",
            createdAt = LocalDateTime.of(2026, 8, 1, 10, 0),
            updatedAt = LocalDateTime.of(2026, 8, 1, 10, 0),
            recurrenceEnabled = true,
            recurrenceRule = "CONFIG: FREQ=DAILY",
            dimensionId = "dim_physical_health",
        )

    @Test
    fun buildHabitRows_preservesLatestL1WhenPassed() {
        val l1 =
            mapOf(
                "h1" to
                    HabitL1Summary(
                        habitId = "h1",
                        dayKey = "2026-08-14",
                        score = 0.85,
                        runningAvg = 0.87,
                        progress = 0.05,
                        streakPos = 27,
                        streakNet = 41,
                        posContinue = 152,
                    ),
            )
        val tasks = listOf(habit("h1"))
        val checkmarks =
            mapOf("h1" to listOf(DayCheckmark(date = LocalDate.of(2026, 8, 14), status = CheckmarkStatus.COMPLETED)))
        val statuses = mapOf("h1" to CheckmarkStatus.COMPLETED)
        val rows =
            TasksRowCacheManager.buildHabitRows(
                tasks = tasks,
                checkmarksByTaskId = checkmarks,
                todayStatusByTaskId = statuses,
                showCompletedHabits = true,
                hideAllMarkedToday = false,
                latestL1ByHabit = l1,
            )
        assertEquals(1, rows.size)
        val row = rows.first { it.id == "h1" }
        assertNotNull("L1 must be preserved on rebuilt rows", row.latestL1)
        assertEquals(0.87, row.latestL1!!.runningAvg, 0.0)
        assertEquals(0.85, row.latestL1!!.score, 0.0)
    }

    @Test
    fun buildHabitRows_nullL1StaysNullWithoutMap() {
        val tasks = listOf(habit("h2"))
        val rows =
            TasksRowCacheManager.buildHabitRows(
                tasks = tasks,
                checkmarksByTaskId = emptyMap(),
                todayStatusByTaskId = emptyMap(),
                showCompletedHabits = true,
                hideAllMarkedToday = false,
            )
        assertEquals(1, rows.size)
        assertNull(rows.first { it.id == "h2" }.latestL1)
    }
}
