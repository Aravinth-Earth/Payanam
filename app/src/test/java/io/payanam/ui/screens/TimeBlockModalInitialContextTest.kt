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
class TimeBlockModalInitialContextTest {

    private val logger: UnifiedLogger by lazy {
        val context = ApplicationProvider.getApplicationContext<Application>()
        UnifiedLogger.initialize(context, "test", 0)
    }

    @Before
    fun setUp() {
        logger
    }

    @Test
    fun manualCreate_defaultsToMorningWindow() {
        val selectedDate = LocalDate.of(2026, 2, 15)
        val context = buildTimeBlockModalInitialContext(
            target = TimeBlockModalTarget.ManualCreate(selectedDate),
            selectedDate = selectedDate,
            appPreferences = AppPreferencesState(),
            fallbackDimensionId = LifeDimension.CAREER_WORK.id,
            fallbackDimensionLabel = LifeDimension.CAREER_WORK.displayName,
        )

        logger.i("TimeBlockModalInitialContextTest", "Validated manual-create defaults")
        assertEquals(R.string.loc_add_time_entry, context.titleResId)
        assertEquals(LocalDateTime.of(selectedDate, LocalTime.of(9, 0)), context.initialStart)
        assertEquals(LocalDateTime.of(selectedDate, LocalTime.of(10, 0)), context.initialEnd)
        assertNull(context.initialTaskId)
    }

    @Test
    fun existingEntry_usesEntryValues() {
        val startedAt = LocalDateTime.of(2026, 2, 15, 11, 0)
        val endedAt = LocalDateTime.of(2026, 2, 15, 12, 0)
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

        val context = buildTimeBlockModalInitialContext(
            target = TimeBlockModalTarget.ExistingEntry(entry),
            selectedDate = LocalDate.of(2026, 2, 15),
            appPreferences = AppPreferencesState(
                dimensionPreferences = listOf(
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

        assertEquals(R.string.loc_edit_time_entry, context.titleResId)
        assertEquals(LifeDimension.HEALTH_WELLNESS.id, context.initialDimensionId)
        assertEquals("ஆரோக்கியம் & நலன்", context.initialDimensionLabel)
        assertEquals("task-1", context.initialTaskId)
        assertEquals(startedAt, context.initialStart)
        assertEquals(endedAt, context.initialEnd)
        assertEquals(0.75, context.initialFocusRating)
        assertEquals("Deep work", context.initialFocusNote)
    }

    @Test
    fun completedTaskBlock_prefersOccurrenceTiming() {
        val due = LocalDateTime.of(2026, 2, 15, 18, 0)
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
        val actualCompletedAt = LocalDateTime.of(2026, 2, 15, 19, 15)
        val occurrence = TaskOccurrence(
            id = "occ-1",
            taskId = task.id,
            occurrenceDate = "2026-02-15",
            status = "completed",
            actualCompletedAt = actualCompletedAt,
            actualDurationMinutes = 35,
        )

        val context = buildTimeBlockModalInitialContext(
            target = TimeBlockModalTarget.TaskBlock(task, occurrence),
            selectedDate = LocalDate.of(2026, 2, 15),
            appPreferences = AppPreferencesState(
                dimensionPreferences = listOf(
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

        assertEquals(LocalDateTime.of(2026, 2, 15, 18, 40), context.initialStart)
        assertEquals(actualCompletedAt, context.initialEnd)
        assertEquals(task.id, context.initialTaskId)
        assertEquals(LifeDimension.HEALTH_WELLNESS.id, context.initialDimensionId)
        assertEquals("ஆரோக்கியம் & நலன்", context.initialDimensionLabel)
    }
}
