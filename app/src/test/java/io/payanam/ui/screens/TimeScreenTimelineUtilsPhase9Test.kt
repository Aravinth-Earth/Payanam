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
    fun setUp() {
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        }
    }

    @Test
    fun computeTimeOverlaps_returns_overlap_intervals() {
        val day = LocalDate.of(2026, 2, 15)
        val now = day.atTime(23, 0)
        val entries = listOf(
            entry("e1", day.atTime(9, 0), day.atTime(10, 0)),
            entry("e2", day.atTime(9, 30), day.atTime(11, 0)),
            entry("e3", day.atTime(10, 45), day.atTime(11, 30)),
        )
        val overlaps = computeTimeOverlaps(day, entries, activeEntry = null, now = now)
        assertEquals(2, overlaps.size)
        assertEquals(30, overlaps.first().minutes)
        assertEquals(15, overlaps.last().minutes)
    }

    @Test
    fun resolveOccurrenceWindowMinutes_prefers_task_due_time() {
        val day = LocalDate.of(2026, 2, 16)
        val now = day.atTime(8, 0)
        val task = Task(
            id = "task-1",
            title = "Workout",
            dueDate = day.plusDays(1).atTime(18, 30),
            createdAt = now,
            updatedAt = now,
            durationMinutes = 30,
        )
        val occurrence = TaskOccurrence(
            id = "occ-1",
            taskId = "task-1",
            occurrenceDate = day.toString(),
            status = "completed",
        )
        val window = resolveOccurrenceWindowMinutes(
            selectedDate = day,
            occurrence = occurrence,
            task = task,
            fallbackIndex = 0,
            fallbackTotal = 1,
            defaultDurationMinutes = 20,
        )
        assertEquals(1095, window.startMinutes)
        assertEquals(1125, window.endMinutes)
    }

    @Test
    /**
     * Resolve occurrence window minutes distributes date only occurrences.
     */
    fun resolveOccurrenceWindowMinutes_distributes_date_only_occurrences() {
        val day = LocalDate.of(2026, 2, 16)
        val occurrence = TaskOccurrence(
            id = "occ-2",
            taskId = "task-2",
            occurrenceDate = day.toString(),
            status = "missed",
        )
        val window = resolveOccurrenceWindowMinutes(
            selectedDate = day,
            occurrence = occurrence,
            task = null,
            fallbackIndex = 2,
            fallbackTotal = 5,
            defaultDurationMinutes = 20,
        )
        assertEquals(710, window.startMinutes)
        assertEquals(730, window.endMinutes)
    }

    @Test
    /**
     * Resolve occurrence window minutes ignores midnight sentinel when no completion time.
     */
    fun resolveOccurrenceWindowMinutes_ignores_midnight_sentinel_when_no_completion_time() {
        val day = LocalDate.of(2026, 2, 16)
        val occurrence = TaskOccurrence(
            id = "occ-3",
            taskId = "task-3",
            occurrenceDate = "2026-02-16T00:00:00",
            status = "skipped",
        )
        val window = resolveOccurrenceWindowMinutes(
            selectedDate = day,
            occurrence = occurrence,
            task = null,
            fallbackIndex = 0,
            fallbackTotal = 1,
            defaultDurationMinutes = 20,
        )
        assertEquals(710, window.startMinutes)
        assertEquals(730, window.endMinutes)
    }

    @Test
    /**
     * Resolve planned tasks for timeline filters tasks already tracked for day.
     */
    fun resolvePlannedTasksForTimeline_filters_tasks_already_tracked_for_day() {
        val day = LocalDate.of(2026, 2, 16)
        val planned = listOf(
            task(id = "task-1", dueDate = day.atTime(9, 0)),
            task(id = "task-2", dueDate = day.atTime(10, 0)),
        )
        val entries = listOf(
            entry("e1", day.atTime(9, 5), day.atTime(9, 25)).copy(taskId = "task-1"),
        )
        val remaining = resolvePlannedTasksForTimeline(
            selectedDate = day,
            plannedTasks = planned,
            entries = entries,
            activeEntry = null,
            pastOccurrences = emptyList(),
        )
        assertEquals(listOf("task-2"), remaining.map { it.id })
    }

    @Test
    /**
     * Resolve planned tasks for timeline filters tasks with active or occurrence.
     */
    fun resolvePlannedTasksForTimeline_filters_tasks_with_active_or_occurrence() {
        val day = LocalDate.of(2026, 2, 16)
        val planned = listOf(
            task(id = "task-1", dueDate = day.atTime(9, 0)),
            task(id = "task-2", dueDate = day.atTime(10, 0)),
            task(id = "task-3", dueDate = day.atTime(11, 0)),
        )
        val active = entry("active", day.atTime(9, 30), day.atTime(9, 40)).copy(
            endedAt = null,
            taskId = "task-2",
        )
        val occurrences = listOf(
            TaskOccurrence(
                id = "occ-1",
                taskId = "task-3",
                occurrenceDate = day.toString(),
                status = "completed",
            ),
        )
        val remaining = resolvePlannedTasksForTimeline(
            selectedDate = day,
            plannedTasks = planned,
            entries = emptyList(),
            activeEntry = active,
            pastOccurrences = occurrences,
        )
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
