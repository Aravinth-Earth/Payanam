//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TimeEntry
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class InsightsDimensionContractTest {
    @Before
    fun setUp() {
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        }
    }

    @Test
    fun timeEntryDimensionId_prefers_explicit_entry_dimension() {
        val task = task("t1", "dim_financial")
        val entry = entry("e1", taskId = "t1", dimensionId = "dim_learning_growth", category = "Relationships")
        val resolved = InsightsDimensionContract.timeEntryDimensionId(entry, mapOf(task.id to task))
        assertEquals("dim_learning_growth", resolved)
    }

    @Test
    /**
     * Time entry dimension id uses task dimension when entry dimension missing.
     */
    fun timeEntryDimensionId_uses_task_dimension_when_entry_dimension_missing() {
        val task = task("t1", "dim_mental_health")
        val entry = entry("e2", taskId = "t1", dimensionId = null, category = "Health & Wellness")
        val resolved = InsightsDimensionContract.timeEntryDimensionId(entry, mapOf(task.id to task))
        assertEquals("dim_mental_health", resolved)
    }

    @Test
    fun timeEntryDimensionId_falls_back_to_category_mapping() {
        val entry = entry("e3", taskId = null, dimensionId = null, category = "Learning")
        val resolved = InsightsDimensionContract.timeEntryDimensionId(entry, emptyMap())
        assertEquals("dim_learning_growth", resolved)
    }

    @Test
    fun noteDimensionId_uses_canonical_dimension_id() {
        val note = io.payanam.domain.model.Note(
            id = "n1",
            title = "Note",
            lifeIntentionCategory = "Mindfulness & Spirituality",
            createdAt = LocalDateTime.of(2026, 2, 15, 10, 0),
            updatedAt = LocalDateTime.of(2026, 2, 15, 10, 0),
            dimensionId = "dim_mental_health",
        )
        val resolved = InsightsDimensionContract.noteDimensionId(note)
        assertEquals("dim_mental_health", resolved)
    }

    private fun task(id: String, dimensionId: String): Task {
        val now = LocalDateTime.of(2026, 2, 15, 10, 0)
        return Task(
            id = id,
            title = "Task $id",
            dimensionId = dimensionId,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun entry(id: String, taskId: String?, dimensionId: String?, category: String): TimeEntry {
        val start = LocalDate.of(2026, 2, 15).atTime(8, 0)
        return TimeEntry(
            id = id,
            lifeIntentionCategory = category,
            taskId = taskId,
            startedAt = start,
            endedAt = start.plusMinutes(30),
            focusRating = 0.7,
            focusNote = null,
            focusRatedAt = null,
            createdAt = start,
            updatedAt = start,
            dimensionId = dimensionId,
        )
    }
}
