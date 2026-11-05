//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The habit-grid and edit-form state types that moved here from `:core:ui`.
 *
 * They moved because the ViewModels own them — the screen only draws what the ViewModel decided — so the
 * properties worth pinning are the ones the state layer relies on: `DayCheckmark`'s optional note fields
 * default safely for a day with no note, and both types compare by value so a ViewModel can skip re-emitting
 * identical state. The floor of the module this now lives in is why an enum hand-check is here too: a new
 * `CheckmarkStatus` constant silently changes what the grid can express, so the set is asserted, not assumed.
 */
class CheckmarkStateTest {
    @Test
    fun `a day with no note defaults to no-note fields`() {
        val day = DayCheckmark(date = LocalDate.of(2026, 9, 21), status = CheckmarkStatus.PENDING)

        assertThat(day.hasNote).isFalse()
        assertThat(day.note).isNull()
    }

    @Test
    fun `two checkmarks for the same day and status are equal and share a hash`() {
        val day = DayCheckmark(LocalDate.of(2026, 9, 21), CheckmarkStatus.COMPLETED, hasNote = true, note = "done")
        val same = day.copy()

        assertThat(same).isEqualTo(day)
        assertThat(same.hashCode()).isEqualTo(day.hashCode())
        assertThat(day).isNotEqualTo(day.copy(status = CheckmarkStatus.MISSED))
    }

    @Test
    fun `the checkmark status set is exactly the five the grid renders`() {
        assertThat(CheckmarkStatus.entries)
            .containsExactly(
                CheckmarkStatus.COMPLETED,
                CheckmarkStatus.SKIPPED,
                CheckmarkStatus.MISSED,
                CheckmarkStatus.PENDING,
                CheckmarkStatus.UNKNOWN,
            ).inOrder()
    }

    @Test
    fun `an edit-form value carries what the screen collected`() {
        val input =
            EditTaskInput(
                title = "Read",
                description = null,
                dimensionId = "dim_work_livelihood",
                dimensionLabel = "Work & Livelihood",
                dueDate = LocalDateTime.of(2026, 9, 21, 20, 0),
                impactLevel = "Moderate Impact",
                goalAlignment = "Moderate Alignment",
                energyLevel = "Moderate",
                controlLevel = "Office/Colleagues Dependent",
                durationMinutes = 30,
                recurrenceEnabled = false,
                recurrenceRule = null,
                notificationMode = "default",
                customNotificationMinutes = null,
                explicitUrgency = null,
                focusRequired = null,
                blockedReason = null,
                externalDependency = null,
            )

        assertThat(input.tags).isEmpty()
        assertThat(input.copy(title = "Read more")).isNotEqualTo(input)
        assertThat(input.copy().hashCode()).isEqualTo(input.hashCode())
        assertThat(input.title).isEqualTo("Read")
    }
}
