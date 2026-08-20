//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.DayPlanAllocationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * TimeVisualsCalculatorTest.
 */
class TimeVisualsCalculatorTest {
    private val logger: UnifiedLogger? = runCatching { UnifiedLogger.getInstance() }.getOrNull()

    @Test
    /**
     * Compute day overall unrated entry contributes zero focused minutes.
     */
    fun computeDayOverall_unrated_entry_contributes_zero_focused_minutes() {
        logger?.d("TimeVisualsCalculatorTest.computeDayOverall", "Verifying unrated entry contributes 0 focus (no 0.5 fallback)")
        /** Day. */
        val day = LocalDate.of(2026, 2, 15)
        /** Entries. */
        val entries = listOf(
            /** Time entry. */
            TimeEntry(
                id = "e1",
                lifeIntentionCategory = "Personal Growth",
                taskId = null,
                startedAt = day.atTime(9, 0),
                endedAt = day.atTime(10, 0),
                focusRating = null, // user never rated this entry
                focusNote = null,
                focusRatedAt = null,
                createdAt = day.atStartOfDay(),
                updatedAt = day.atStartOfDay(),
                dimensionId = null,
            ),
        )
        /** Summary. */
        val summary = TimeVisualsCalculator.computeDayOverall(day, entries, now = day.atTime(23, 0))
        /** Assert equals. */
        assertEquals(0f, summary.focusedMinutesPercent)
    }

    @Test
    /**
     * Compute day overall counts overlap and gap.
     */
    fun computeDayOverall_counts_overlap_and_gap() {
        logger?.d("TimeVisualsCalculatorTest.computeDayOverall", "Running overlap/gap regression case")
        /** Day. */
        val day = LocalDate.of(2026, 2, 15)
        /** Entries. */
        val entries = listOf(
            /** Entry. */
            entry("e1", day.atTime(9, 0), day.atTime(10, 0), focus = 0.8),
            /** Entry. */
            entry("e2", day.atTime(9, 30), day.atTime(11, 0), focus = 0.6),
            /** Entry. */
            entry("e3", day.atTime(13, 0), day.atTime(14, 0), focus = 0.9),
        )

        /** Summary. */
        val summary = TimeVisualsCalculator.computeDayOverall(day, entries, now = day.atTime(23, 0))

        /** Assert equals. */
        assertEquals(1, summary.overlapCount)
        /** Assert true. */
        assertTrue(summary.gapCount >= 1)
        /** Assert true. */
        assertTrue(summary.trackedMinutes > 0)
    }

    @Test
    /**
     * Compute per dimension builds plan vs actual.
     */
    fun computePerDimension_builds_plan_vs_actual() {
        logger?.d("TimeVisualsCalculatorTest.computePerDimension", "Running plan-vs-actual baseline case")
        /** Day. */
        val day = LocalDate.of(2026, 2, 15)
        /** Task. */
        val task = Task(
            id = "t1",
            title = "Deep work",
            createdAt = day.atStartOfDay(),
            updatedAt = day.atStartOfDay(),
            dimensionId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
        )
        /** Entries. */
        val entries = listOf(
            /** Time entry. */
            TimeEntry(
                id = "e1",
                lifeIntentionCategory = "Work & Livelihood",
                taskId = "t1",
                startedAt = day.atTime(8, 0),
                endedAt = day.atTime(9, 30),
                focusRating = 0.7,
                createdAt = day.atStartOfDay(),
                updatedAt = day.atStartOfDay(),
                dimensionId = null,
            ),
        )
        /** Allocations. */
        val allocations = listOf(
            /** Day plan allocation record. */
            DayPlanAllocationRecord(
                id = "a1",
                dayKey = day.toString(),
                dimensionId = DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id,
                plannedMinutes = 60,
                source = "manual",
                templateId = null,
            ),
        )

        /** Result. */
        val result = TimeVisualsCalculator.computePerDimension(
            selectedDate = day,
            entries = entries,
            taskLookup = mapOf("t1" to task),
            allocations = allocations,
            now = day.atTime(23, 0),
        )

        /** Assert equals. */
        assertEquals(1, result.size)
        /** Assert equals. */
        assertEquals(DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id, result.first().dimensionId)
        /** Assert equals. */
        assertEquals(60, result.first().plannedMinutes)
        /** Assert true. */
        assertTrue(result.first().plannedDeltaMinutes > 0)
    }

    @Test
    /**
     * Compute per dimension adds supplemental habit minutes without double count.
     */
    fun computePerDimension_adds_supplemental_habit_minutes_without_double_count() {
        logger?.d("TimeVisualsCalculatorTest.computePerDimension", "Running supplemental occurrence anti-double-count case")
        /** Day. */
        val day = LocalDate.of(2026, 2, 15)
        /** Habit task. */
        val habitTask = Task(
            id = "h1",
            title = "Walk",
            createdAt = day.atStartOfDay(),
            updatedAt = day.atStartOfDay(),
            recurrenceEnabled = true,
            dimensionId = DimensionTaxonomyCatalog.PHYSICAL_HEALTH.id,
        )
        /** Occurrence. */
        val occurrence = TaskOccurrence(
            id = "o1",
            taskId = "h1",
            occurrenceDate = day.toString(),
            status = "completed",
            actualDurationMinutes = 30,
        )

        /** Without entry. */
        val withoutEntry = TimeVisualsCalculator.computePerDimension(
            selectedDate = day,
            entries = emptyList(),
            occurrences = listOf(occurrence),
            taskLookup = mapOf("h1" to habitTask),
            allocations = emptyList(),
            now = day.atTime(23, 0),
        )
        /** Assert equals. */
        assertEquals(30L, withoutEntry.first().trackedMinutes)

        /** Linked entry. */
        val linkedEntry = TimeEntry(
            id = "e_h1",
            lifeIntentionCategory = "Physical Health",
            taskId = "h1",
            startedAt = day.atTime(7, 0),
            endedAt = day.atTime(7, 20),
            focusRating = 0.8,
            createdAt = day.atStartOfDay(),
            updatedAt = day.atStartOfDay(),
            dimensionId = DimensionTaxonomyCatalog.PHYSICAL_HEALTH.id,
        )
        /** With linked entry. */
        val withLinkedEntry = TimeVisualsCalculator.computePerDimension(
            selectedDate = day,
            entries = listOf(linkedEntry),
            occurrences = listOf(occurrence),
            taskLookup = mapOf("h1" to habitTask),
            allocations = emptyList(),
            now = day.atTime(23, 0),
        )
        /** Assert equals. */
        assertEquals(20L, withLinkedEntry.first().trackedMinutes)
    }

    private fun entry(id: String, start: LocalDateTime, end: LocalDateTime, focus: Double): TimeEntry = TimeEntry(
        id = id,
        lifeIntentionCategory = "Learning & Growth",
        taskId = null,
        startedAt = start,
        endedAt = end,
        focusRating = focus,
        focusNote = null,
        focusRatedAt = null,
        createdAt = start,
        updatedAt = start,
        dimensionId = DimensionTaxonomyCatalog.LEARNING_GROWTH.id,
    )
}
