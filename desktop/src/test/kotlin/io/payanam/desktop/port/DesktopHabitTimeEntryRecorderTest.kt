//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.port

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.model.Task
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.model.TimeEntryInput
import io.payanam.domain.repository.AppSettingsRepository
import io.payanam.domain.repository.TimeEntryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The desktop recorder's rules, mirrored from Android's `CreateTimeEntryForHabitUseCaseTest`.
 *
 * What is pinned: the entry's shape (canonical dimension id and label, task id, the completedAt-duration
 * window, the focus note) and the three ways a completion records nothing - a non-recurring task, the
 * global switch off, and the dimension switched off under a global on. The last one is the case a naive
 * port gets wrong: it reads the per-dimension key without inheriting the global answer.
 */
class DesktopHabitTimeEntryRecorderTest {
    private val completedAt: LocalDateTime = LocalDateTime.of(2026, 11, 27, 9, 30)

    private class RecordingTimeEntries : TimeEntryRepository {
        val created = mutableListOf<TimeEntryInput>()

        override suspend fun createTimeEntry(input: TimeEntryInput): TimeEntry {
            created += input
            val now = input.endedAt ?: input.startedAt
            return TimeEntry(
                id = "entry-${created.size}",
                lifeIntentionCategory = input.lifeIntentionCategory,
                taskId = input.taskId,
                startedAt = input.startedAt,
                endedAt = input.endedAt,
                focusRating = input.focusRating,
                focusNote = input.focusNote,
                createdAt = now,
                updatedAt = now,
                dimensionId = input.dimensionId,
            )
        }

        override suspend fun getActiveTimeEntry(): TimeEntry? = null

        override fun observeActiveTimeEntry(): Flow<TimeEntry?> = flowOf(null)

        override fun getActiveTimeEntries(): Flow<List<TimeEntry>> = flowOf(emptyList())

        override fun getTimeEntriesForRange(
            start: LocalDateTime,
            end: LocalDateTime,
        ): Flow<List<TimeEntry>> = flowOf(emptyList())

        override fun getTimeEntriesForDate(date: LocalDate): Flow<List<TimeEntry>> = flowOf(emptyList())

        override fun getAllTimeEntries(): Flow<List<TimeEntry>> = flowOf(emptyList())

        override suspend fun startTimeEntry(input: TimeEntryInput): TimeEntry = error("not used")

        override suspend fun stopActiveTimeEntry(): TimeEntry? = error("not used")

        override suspend fun stopActiveTimeEntryWithFocus(
            focusRating: Double,
            focusNote: String?,
        ): TimeEntry? = error("not used")

        override suspend fun updateTimeEntry(
            id: String,
            input: TimeEntryInput,
        ): TimeEntry = error("not used")

        override suspend fun updateTimeEntry(entry: TimeEntry) = error("not used")

        override suspend fun deleteTimeEntry(id: String) = error("not used")
    }

    private class FakeSettings(private val values: Map<String, String?>) : AppSettingsRepository {
        override suspend fun getSetting(key: String): String? = values[key]

        override fun observeSetting(key: String): Flow<String?> = flowOf(values[key])

        override suspend fun setSetting(
            key: String,
            value: String?,
        ) = error("not used")

        override suspend fun deleteSetting(key: String) = error("not used")

        override fun getAllSettings(): Flow<Map<String, String?>> = flowOf(values)
    }

    private fun habit(
        recurrenceEnabled: Boolean = true,
        dimensionId: String? = DimensionTaxonomyCatalog.entries.first().id,
    ): Task =
        Task(
            id = "habit-1",
            title = "Morning walk",
            recurrenceEnabled = recurrenceEnabled,
            createdAt = LocalDateTime.of(2026, 1, 1, 8, 0),
            updatedAt = LocalDateTime.of(2026, 1, 1, 8, 0),
            dimensionId = dimensionId,
        )

    @Test
    fun `completion with auto-track on writes the linked entry`() =
        runBlocking {
            val timeEntries = RecordingTimeEntries()
            val recorder =
                DesktopHabitTimeEntryRecorder(
                    timeEntries,
                    FakeSettings(mapOf("auto_track_habit_time_global" to "true")),
                )
            val canonical = DimensionTaxonomyCatalog.entries.first()

            recorder(habit(), completedAt, 25)

            assertThat(timeEntries.created).hasSize(1)
            val entry = timeEntries.created.single()
            assertThat(entry.dimensionId).isEqualTo(canonical.id)
            assertThat(entry.lifeIntentionCategory).isEqualTo(canonical.fallbackLabel)
            assertThat(entry.taskId).isEqualTo("habit-1")
            assertThat(entry.startedAt).isEqualTo(completedAt.minusMinutes(25))
            assertThat(entry.endedAt).isEqualTo(completedAt)
            assertThat(entry.focusNote).isEqualTo("Auto-tracked from habit completion")
        }

    @Test
    fun `the default duration is fifteen minutes`() =
        runBlocking {
            val timeEntries = RecordingTimeEntries()
            val recorder =
                DesktopHabitTimeEntryRecorder(
                    timeEntries,
                    FakeSettings(mapOf("auto_track_habit_time_global" to "true")),
                )

            recorder(habit(), completedAt, null)

            assertThat(timeEntries.created.single().startedAt).isEqualTo(completedAt.minusMinutes(15))
        }

    @Test
    fun `a non-recurring task records nothing`() =
        runBlocking {
            val timeEntries = RecordingTimeEntries()
            val recorder =
                DesktopHabitTimeEntryRecorder(
                    timeEntries,
                    FakeSettings(mapOf("auto_track_habit_time_global" to "true")),
                )

            recorder(habit(recurrenceEnabled = false), completedAt, 10)

            assertThat(timeEntries.created).isEmpty()
        }

    @Test
    fun `the global switch off records nothing`() =
        runBlocking {
            val timeEntries = RecordingTimeEntries()
            val recorder = DesktopHabitTimeEntryRecorder(timeEntries, FakeSettings(emptyMap()))

            recorder(habit(), completedAt, 10)

            assertThat(timeEntries.created).isEmpty()
        }

    @Test
    fun `a dimension switched off under a global on records nothing`() =
        runBlocking {
            val timeEntries = RecordingTimeEntries()
            val canonical = DimensionTaxonomyCatalog.entries.first()
            val recorder =
                DesktopHabitTimeEntryRecorder(
                    timeEntries,
                    FakeSettings(
                        mapOf(
                            "auto_track_habit_time_global" to "true",
                            "auto_track_dimension_${canonical.id}" to "false",
                        ),
                    ),
                )

            recorder(habit(dimensionId = canonical.id), completedAt, 10)

            assertThat(timeEntries.created).isEmpty()
        }

    @Test
    fun `a task without a canonical dimension records nothing`() =
        runBlocking {
            val timeEntries = RecordingTimeEntries()
            val recorder =
                DesktopHabitTimeEntryRecorder(
                    timeEntries,
                    FakeSettings(mapOf("auto_track_habit_time_global" to "true")),
                )

            recorder(habit(dimensionId = "not-a-canonical-id"), completedAt, 10)

            assertThat(timeEntries.created).isEmpty()
        }
}
