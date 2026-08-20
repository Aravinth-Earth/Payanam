//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
/**
 * TimeScreenTimelineUtilsPhase9Test.
 */
class TimeScreenTimelineUtilsPhase9Test {
    @Before
    /**
     * Set up.
     */
    fun setUp() {
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        }
    }

    @Test
    /**
     * Compute time overlaps returns overlap intervals.
     */
    fun computeTimeOverlaps_returns_overlap_intervals() {
        /** Day. */
        val day = LocalDate.of(2026, 2, 15)
        /** Now. */
        val now = day.atTime(23, 0)
        /** Entries. */
        val entries = listOf(
            /** Entry. */
            entry("e1", day.atTime(9, 0), day.atTime(10, 0)),
            /** Entry. */
            entry("e2", day.atTime(9, 30), day.atTime(11, 0)),
            /** Entry. */
            entry("e3", day.atTime(10, 45), day.atTime(11, 30)),
        )

        /** Overlaps. */
        val overlaps = computeTimeOverlaps(day, entries, activeEntry = null, now = now)

        /** Assert equals. */
        assertEquals(2, overlaps.size)
        /** Assert equals. */
        assertEquals(30, overlaps.first().minutes)
        /** Assert equals. */
        assertEquals(15, overlaps.last().minutes)
    }

    @Test
    /**
     * Resolve occurrence window minutes prefers task due time.
     */
    fun resolveOccurrenceWindowMinutes_prefers_task_due_time() {
        /** Day. */
        val day = LocalDate.of(2026, 2, 16)
        /** Now. */
        val now = day.atTime(8, 0)
        /** Task. */
        val task = Task(
            id = "task-1",
            title = "Workout",
            dueDate = day.plusDays(1).atTime(18, 30),
            createdAt = now,
            updatedAt = now,
            durationMinutes = 30,
        )
        /** Occurrence. */
        val occurrence = TaskOccurrence(
            id = "occ-1",
            taskId = "task-1",
            occurrenceDate = day.toString(),
            status = "completed",
        )

        /** Window. */
        val window = resolveOccurrenceWindowMinutes(
            selectedDate = day,
            occurrence = occurrence,
            task = task,
            fallbackIndex = 0,
            fallbackTotal = 1,
            defaultDurationMinutes = 20,
        )

        /** Assert equals. */
        assertEquals(1095, window.startMinutes)
        /** Assert equals. */
        assertEquals(1125, window.endMinutes)
    }

    @Test
    /**
     * Resolve occurrence window minutes distributes date only occurrences.
     */
    fun resolveOccurrenceWindowMinutes_distributes_date_only_occurrences() {
        /** Day. */
        val day = LocalDate.of(2026, 2, 16)
        /** Occurrence. */
        val occurrence = TaskOccurrence(
            id = "occ-2",
            taskId = "task-2",
            occurrenceDate = day.toString(),
            status = "missed",
        )

        /** Window. */
        val window = resolveOccurrenceWindowMinutes(
            selectedDate = day,
            occurrence = occurrence,
            task = null,
            fallbackIndex = 2,
            fallbackTotal = 5,
            defaultDurationMinutes = 20,
        )

        /** Assert equals. */
        assertEquals(710, window.startMinutes)
        /** Assert equals. */
        assertEquals(730, window.endMinutes)
    }

    @Test
    /**
     * Resolve occurrence window minutes ignores midnight sentinel when no completion time.
     */
    fun resolveOccurrenceWindowMinutes_ignores_midnight_sentinel_when_no_completion_time() {
        /** Day. */
        val day = LocalDate.of(2026, 2, 16)
        /** Occurrence. */
        val occurrence = TaskOccurrence(
            id = "occ-3",
            taskId = "task-3",
            occurrenceDate = "2026-02-16T00:00:00",
            status = "skipped",
        )

        /** Window. */
        val window = resolveOccurrenceWindowMinutes(
            selectedDate = day,
            occurrence = occurrence,
            task = null,
            fallbackIndex = 0,
            fallbackTotal = 1,
            defaultDurationMinutes = 20,
        )

        /** Assert equals. */
        assertEquals(710, window.startMinutes)
        /** Assert equals. */
        assertEquals(730, window.endMinutes)
    }

    @Test
    /**
     * Resolve planned tasks for timeline filters tasks already tracked for day.
     */
    fun resolvePlannedTasksForTimeline_filters_tasks_already_tracked_for_day() {
        /** Day. */
        val day = LocalDate.of(2026, 2, 16)
        /** Planned. */
        val planned = listOf(
            /** Task. */
            task(id = "task-1", dueDate = day.atTime(9, 0)),
            /** Task. */
            task(id = "task-2", dueDate = day.atTime(10, 0)),
        )
        /** Entries. */
        val entries = listOf(
            /** Entry. */
            entry("e1", day.atTime(9, 5), day.atTime(9, 25)).copy(taskId = "task-1"),
        )

        /** Remaining. */
        val remaining = resolvePlannedTasksForTimeline(
            selectedDate = day,
            plannedTasks = planned,
            entries = entries,
            activeEntry = null,
            pastOccurrences = emptyList(),
        )

        /** Assert equals. */
        assertEquals(listOf("task-2"), remaining.map { it.id })
    }

    @Test
    /**
     * Resolve planned tasks for timeline filters tasks with active or occurrence.
     */
    fun resolvePlannedTasksForTimeline_filters_tasks_with_active_or_occurrence() {
        /** Day. */
        val day = LocalDate.of(2026, 2, 16)
        /** Planned. */
        val planned = listOf(
            /** Task. */
            task(id = "task-1", dueDate = day.atTime(9, 0)),
            /** Task. */
            task(id = "task-2", dueDate = day.atTime(10, 0)),
            /** Task. */
            task(id = "task-3", dueDate = day.atTime(11, 0)),
        )
        /** Active. */
        val active = entry("active", day.atTime(9, 30), day.atTime(9, 40)).copy(
            endedAt = null,
            taskId = "task-2",
        )
        /** Occurrences. */
        val occurrences = listOf(
            /** Task occurrence. */
            TaskOccurrence(
                id = "occ-1",
                taskId = "task-3",
                occurrenceDate = day.toString(),
                status = "completed",
            ),
        )

        /** Remaining. */
        val remaining = resolvePlannedTasksForTimeline(
            selectedDate = day,
            plannedTasks = planned,
            entries = emptyList(),
            activeEntry = active,
            pastOccurrences = occurrences,
        )

        /** Assert equals. */
        assertEquals(listOf("task-1"), remaining.map { it.id })
    }

    private fun entry(id: String, start: LocalDateTime, end: LocalDateTime): TimeEntry = TimeEntry(
        id = id,
        lifeIntentionCategory = "Personal Growth",
        taskId = null,
        startedAt = start,
        endedAt = end,
        focusRating = 0.6,
        focusNote = null,
        focusRatedAt = null,
        createdAt = start,
        updatedAt = start,
        dimensionId = "dim_personal_growth",
    )

    private fun task(id: String, dueDate: LocalDateTime): Task = Task(
        id = id,
        title = "Task $id",
        dueDate = dueDate,
        createdAt = dueDate.minusHours(1),
        updatedAt = dueDate.minusHours(1),
    )
}
