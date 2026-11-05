//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

class TimeEntryTest {
    @Test
    fun isActive_trueWhenNoEndTime() {
        val now = LocalDateTime.of(2026, 1, 31, 9, 0)
        val entry = TimeEntry(
            id = "entry-1",
            lifeIntentionCategory = "Career & Work",
            startedAt = now,
            createdAt = now,
            updatedAt = now
        )
        assertThat(entry.isActive).isTrue()
    }

    @Test
    fun isActive_falseWhenEnded() {
        val start = LocalDateTime.of(2026, 1, 31, 9, 0)
        val end = start.plusMinutes(10)
        val entry = TimeEntry(
            id = "entry-1b",
            lifeIntentionCategory = "Career & Work",
            startedAt = start,
            endedAt = end,
            createdAt = start,
            updatedAt = start
        )
        assertThat(entry.isActive).isFalse()
    }

    @Test
    fun durationMinutes_usesEndTimeWhenPresent() {
        val start = LocalDateTime.of(2026, 1, 31, 9, 0)
        val end = start.plusMinutes(45)
        val entry = TimeEntry(
            id = "entry-2",
            lifeIntentionCategory = "Learning",
            startedAt = start,
            endedAt = end,
            createdAt = start,
            updatedAt = start
        )
        assertThat(entry.durationMinutes()).isEqualTo(45)
    }

    @Test
    fun durationMinutes_usesProvidedNowForActiveEntry() {
        val start = LocalDateTime.of(2026, 1, 31, 9, 0)
        val now = start.plusMinutes(20)
        val entry = TimeEntry(
            id = "entry-3",
            lifeIntentionCategory = "Learning",
            startedAt = start,
            createdAt = start,
            updatedAt = start
        )
        assertThat(entry.durationMinutes(now)).isEqualTo(20)
    }

    @Test
    fun timeEntry_exposesFields() {
        val start = LocalDateTime.of(2026, 1, 31, 10, 0)
        val end = start.plusMinutes(30)
        val entry = TimeEntry(
            id = "entry-4",
            lifeIntentionCategory = "Focus",
            taskId = "task-9",
            startedAt = start,
            endedAt = end,
            createdAt = start,
            updatedAt = end
        )
        assertThat(entry.id).isEqualTo("entry-4")
        assertThat(entry.lifeIntentionCategory).isEqualTo("Focus")
        assertThat(entry.taskId).isEqualTo("task-9")
        assertThat(entry.startedAt).isEqualTo(start)
        assertThat(entry.endedAt).isEqualTo(end)
        assertThat(entry.createdAt).isEqualTo(start)
        assertThat(entry.updatedAt).isEqualTo(end)
    }

    @Test
    fun timeEntryInput_holdsValues() {
        val start = LocalDateTime.of(2026, 1, 31, 10, 0)
        val end = start.plusMinutes(45)
        val input = TimeEntryInput(
            lifeIntentionCategory = "Health & Wellness",
            taskId = "task-7",
            startedAt = start,
            endedAt = end
        )
        assertThat(input.lifeIntentionCategory).isEqualTo("Health & Wellness")
        assertThat(input.taskId).isEqualTo("task-7")
        assertThat(input.startedAt).isEqualTo(start)
        assertThat(input.endedAt).isEqualTo(end)
    }

}
