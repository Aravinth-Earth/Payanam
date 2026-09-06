//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.usecase

import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.TimeEntryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class CreateTimeEntryForHabitUseCaseTest {
    @Before
    fun setUp() {
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(ApplicationProvider.getApplicationContext(), "test", 0)
        }
    }

    @Test
    /**
     * Invoke uses canonical dimension id before creating time entry.
     */
    fun invoke_uses_canonical_dimension_id_before_creating_time_entry() = runTest {
        val timeEntryRepository = FakeTimeEntryRepository()
        val appSettingsRepository = FakeAppSettingsRepository(
            mapOf(
                "auto_track_habit_time_global" to "true",
                "auto_track_dimension_dim_mental_health" to "true",
            ),
        )
        val useCase = CreateTimeEntryForHabitUseCase(timeEntryRepository, appSettingsRepository)
        val completedAt = LocalDateTime.of(2026, 3, 16, 9, 0)
        val task = Task(
            id = "task-1",
            title = "Meditation",
            recurrenceEnabled = true,
            dimensionId = "dim_mental_health",
            lifeIntentionCategory = "Mental Health",
            createdAt = completedAt.minusDays(1),
            updatedAt = completedAt.minusDays(1),
        )
        useCase(task, completedAt, 20)
        val created = timeEntryRepository.createdInput
        assertNotNull(created)
        assertEquals("dim_mental_health", created?.dimensionId)
        assertEquals("Mental Health", created?.lifeIntentionCategory)
        assertEquals("task-1", created?.taskId)
        assertEquals(completedAt.minusMinutes(20), created?.startedAt)
        assertEquals(completedAt, created?.endedAt)
    }

    private class FakeTimeEntryRepository : TimeEntryRepository {
        var createdInput: TimeEntryInput? = null

        override suspend fun getActiveTimeEntry(): TimeEntry? = null

        override fun observeActiveTimeEntry(): Flow<TimeEntry?> = flowOf(null)

        override fun getTimeEntriesForRange(start: LocalDateTime, end: LocalDateTime): Flow<List<TimeEntry>> = flowOf(emptyList())

        override fun getTimeEntriesForDate(date: LocalDate): Flow<List<TimeEntry>> = flowOf(emptyList())

        override suspend fun startTimeEntry(input: TimeEntryInput): TimeEntry = throw UnsupportedOperationException()

        override suspend fun stopActiveTimeEntry(): TimeEntry? = null

        override suspend fun stopActiveTimeEntryWithFocus(focusRating: Double, focusNote: String?): TimeEntry? = null

        override suspend fun updateTimeEntry(id: String, input: TimeEntryInput): TimeEntry = throw UnsupportedOperationException()

        override suspend fun deleteTimeEntry(id: String) = Unit

        override suspend fun createTimeEntry(input: TimeEntryInput): TimeEntry {
            createdInput = input
            val now = input.endedAt ?: input.startedAt
            return TimeEntry(
                id = "entry-1",
                lifeIntentionCategory = input.lifeIntentionCategory,
                taskId = input.taskId,
                startedAt = input.startedAt,
                endedAt = input.endedAt,
                focusRating = input.focusRating,
                focusNote = input.focusNote,
                focusRatedAt = input.focusRatedAt,
                createdAt = now,
                updatedAt = now,
                dimensionId = input.dimensionId,
            )
        }

        override fun getAllTimeEntries(): Flow<List<TimeEntry>> = flowOf(emptyList())

        override fun getActiveTimeEntries(): Flow<List<TimeEntry>> = flowOf(emptyList())

        override suspend fun updateTimeEntry(entry: TimeEntry) = Unit
    }

    private class FakeAppSettingsRepository(initial: Map<String, String?>) : AppSettingsRepository {
        private val settings = MutableStateFlow(initial)

        override suspend fun getSetting(key: String): String? = settings.value[key]

        override fun observeSetting(key: String): Flow<String?> = flowOf(settings.value[key])

        override suspend fun setSetting(key: String, value: String?) {
            settings.value = settings.value.toMutableMap().apply { put(key, value) }
        }

        override suspend fun deleteSetting(key: String) {
            settings.value = settings.value.toMutableMap().apply { remove(key) }
        }

        override fun getAllSettings(): Flow<Map<String, String?>> = settings
    }
}
