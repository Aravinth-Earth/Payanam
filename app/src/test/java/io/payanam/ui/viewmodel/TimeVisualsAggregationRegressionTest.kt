//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.DayPlanAllocationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class TimeVisualsAggregationRegressionTest {
    @Before
    fun setUp() {
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        }
    }

    @Test
    fun computeDayOverall_calculates_weighted_focus_percent() {
        val day = LocalDate.of(2026, 2, 15)
        val entries = listOf(
            entry("e1", day.atTime(9, 0), day.atTime(10, 0), focus = 1.0),
            entry("e2", day.atTime(10, 0), day.atTime(12, 0), focus = 0.5),
        )
        val result = TimeVisualsCalculator.computeDayOverall(day, entries, now = day.atTime(23, 0))
        assertEquals(180L, result.trackedMinutes)
        assertEquals(0.66f, result.focusedMinutesPercent, 0.02f)
    }

    @Test
    fun computePerDimension_builds_share_and_plan_delta() {
        val day = LocalDate.of(2026, 2, 15)
        val entries = listOf(
            entry("e1", day.atTime(8, 0), day.atTime(9, 0), focus = 0.8, dimensionId = "dim_learning"),
            entry("e2", day.atTime(9, 0), day.atTime(10, 30), focus = 0.6, dimensionId = "dim_learning"),
            entry("e3", day.atTime(11, 0), day.atTime(11, 30), focus = 0.7, dimensionId = "dim_health_wellness"),
        )
        val allocations = listOf(
            DayPlanAllocationRecord(
                id = "a1",
                dayKey = day.toString(),
                dimensionId = "dim_learning",
                plannedMinutes = 120,
                source = "manual",
                templateId = null,
            ),
        )
        val result = TimeVisualsCalculator.computePerDimension(
            selectedDate = day,
            entries = entries,
            taskLookup = emptyMap(),
            allocations = allocations,
            now = day.atTime(23, 0),
        )
        val learning = result.first { it.dimensionId == "dim_learning" }
        assertEquals(150L, learning.trackedMinutes)
        assertEquals(120, learning.plannedMinutes)
        assertEquals(30L, learning.plannedDeltaMinutes)
        assertTrue(learning.sharePercent > 0.8f)
    }

    private fun entry(
        id: String,
        start: LocalDateTime,
        end: LocalDateTime,
        focus: Double,
        dimensionId: String = "dim_personal_growth",
    ): TimeEntry = TimeEntry(
        id = id,
        lifeIntentionCategory = "Personal Growth",
        taskId = null,
        startedAt = start,
        endedAt = end,
        focusRating = focus,
        focusNote = null,
        focusRatedAt = null,
        createdAt = start,
        updatedAt = start,
        dimensionId = dimensionId,
    )
}
