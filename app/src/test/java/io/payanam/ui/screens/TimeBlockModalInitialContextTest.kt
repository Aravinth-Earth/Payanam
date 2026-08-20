//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.ui.viewmodel.AppPreferencesState
import io.payanam.ui.viewmodel.DimensionPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
/**
 * TimeBlockModalInitialContextTest.
 */
class TimeBlockModalInitialContextTest {

    private val logger: UnifiedLogger by lazy {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Application>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Before
    /**
     * Set up.
     */
    fun setUp() {
        /** Logger. */
        logger
    }

    @Test
    /**
     * Manual create defaults to morning window.
     */
    fun manualCreate_defaultsToMorningWindow() {
        /** Selected date. */
        val selectedDate = LocalDate.of(2026, 2, 15)
        /** Context. */
        val context = buildTimeBlockModalInitialContext(
            target = TimeBlockModalTarget.ManualCreate(selectedDate),
            selectedDate = selectedDate,
            appPreferences = AppPreferencesState(),
            fallbackDimensionId = LifeDimension.CAREER_WORK.id,
            fallbackDimensionLabel = LifeDimension.CAREER_WORK.displayName,
        )

        logger.i("TimeBlockModalInitialContextTest", "Validated manual-create defaults")
        /** Assert equals. */
        assertEquals(R.string.loc_add_time_entry, context.titleResId)
        /** Assert equals. */
        assertEquals(LocalDateTime.of(selectedDate, LocalTime.of(9, 0)), context.initialStart)
        /** Assert equals. */
        assertEquals(LocalDateTime.of(selectedDate, LocalTime.of(10, 0)), context.initialEnd)
        /** Assert null. */
        assertNull(context.initialTaskId)
    }

    @Test
    /**
     * Existing entry uses entry values.
     */
    fun existingEntry_usesEntryValues() {
        /** Started at. */
        val startedAt = LocalDateTime.of(2026, 2, 15, 11, 0)
        /** Ended at. */
        val endedAt = LocalDateTime.of(2026, 2, 15, 12, 0)
        /** Entry. */
        val entry = TimeEntry(
            id = "entry-1",
            lifeIntentionCategory = LifeDimension.HEALTH_WELLNESS.displayName,
            taskId = "task-1",
            startedAt = startedAt,
            endedAt = endedAt,
            focusRating = 0.75,
            focusNote = "Deep work",
            focusRatedAt = startedAt,
            createdAt = startedAt,
            updatedAt = startedAt,
            dimensionId = LifeDimension.HEALTH_WELLNESS.id,
        )

        /** Context. */
        val context = buildTimeBlockModalInitialContext(
            target = TimeBlockModalTarget.ExistingEntry(entry),
            selectedDate = LocalDate.of(2026, 2, 15),
            appPreferences = AppPreferencesState(
                dimensionPreferences = listOf(
                    /** Dimension preference. */
                    DimensionPreference(
                        key = LifeDimension.HEALTH_WELLNESS.id,
                        label = "ஆரோக்கியம் & நலன்",
                        color = androidx.compose.ui.graphics.Color.Blue,
                        isVisible = true,
                    ),
                ),
            ),
            fallbackDimensionId = LifeDimension.CAREER_WORK.id,
            fallbackDimensionLabel = LifeDimension.CAREER_WORK.displayName,
        )

        /** Assert equals. */
        assertEquals(R.string.loc_edit_time_entry, context.titleResId)
        /** Assert equals. */
        assertEquals(LifeDimension.HEALTH_WELLNESS.id, context.initialDimensionId)
        /** Assert equals. */
        assertEquals("ஆரோக்கியம் & நலன்", context.initialDimensionLabel)
        /** Assert equals. */
        assertEquals("task-1", context.initialTaskId)
        /** Assert equals. */
        assertEquals(startedAt, context.initialStart)
        /** Assert equals. */
        assertEquals(endedAt, context.initialEnd)
        /** Assert equals. */
        assertEquals(0.75, context.initialFocusRating)
        /** Assert equals. */
        assertEquals("Deep work", context.initialFocusNote)
    }

    @Test
    /**
     * Completed task block prefers occurrence timing.
     */
    fun completedTaskBlock_prefersOccurrenceTiming() {
        /** Due. */
        val due = LocalDateTime.of(2026, 2, 15, 18, 0)
        /** Task. */
        val task = Task(
            id = "habit-1",
            title = "Run",
            createdAt = due.minusDays(1),
            updatedAt = due.minusDays(1),
            dueDate = due,
            recurrenceEnabled = true,
            durationMinutes = 40,
            impactLevel = "Moderate Impact",
            goalAlignment = "Moderate Alignment",
            energyLevel = "Moderate",
            controlLevel = "Office/Colleagues Dependent",
            lifeIntentionCategory = LifeDimension.HEALTH_WELLNESS.displayName,
            dimensionId = LifeDimension.HEALTH_WELLNESS.id,
        )
        /** Actual completed at. */
        val actualCompletedAt = LocalDateTime.of(2026, 2, 15, 19, 15)
        /** Occurrence. */
        val occurrence = TaskOccurrence(
            id = "occ-1",
            taskId = task.id,
            occurrenceDate = "2026-02-15",
            status = "completed",
            actualCompletedAt = actualCompletedAt,
            actualDurationMinutes = 35,
        )

        /** Context. */
        val context = buildTimeBlockModalInitialContext(
            target = TimeBlockModalTarget.TaskBlock(task, occurrence),
            selectedDate = LocalDate.of(2026, 2, 15),
            appPreferences = AppPreferencesState(
                dimensionPreferences = listOf(
                    /** Dimension preference. */
                    DimensionPreference(
                        key = LifeDimension.HEALTH_WELLNESS.id,
                        label = "ஆரோக்கியம் & நலன்",
                        color = androidx.compose.ui.graphics.Color.Blue,
                        isVisible = true,
                    ),
                ),
            ),
            fallbackDimensionId = LifeDimension.CAREER_WORK.id,
            fallbackDimensionLabel = LifeDimension.CAREER_WORK.displayName,
        )

        /** Assert equals. */
        assertEquals(LocalDateTime.of(2026, 2, 15, 18, 40), context.initialStart)
        /** Assert equals. */
        assertEquals(actualCompletedAt, context.initialEnd)
        /** Assert equals. */
        assertEquals(task.id, context.initialTaskId)
        /** Assert equals. */
        assertEquals(LifeDimension.HEALTH_WELLNESS.id, context.initialDimensionId)
        /** Assert equals. */
        assertEquals("ஆரோக்கியம் & நலன்", context.initialDimensionLabel)
    }
}
