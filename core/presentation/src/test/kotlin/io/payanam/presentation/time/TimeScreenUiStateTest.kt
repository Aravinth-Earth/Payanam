//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The Time screen's state contract, which both platforms render from.
 *
 * Nothing built this shape before: the ViewModel was the only thing that constructed it, so the two
 * things the screen actually depends on - that a fresh state means "loading, today, nothing loaded",
 * and that the value semantics let a `StateFlow` decide whether an update is worth re-emitting - were
 * never asserted anywhere. They are asserted here because they are what the screen keys its effects on.
 */
class TimeScreenUiStateTest {
    @Test
    fun `a fresh state is loading today with nothing loaded`() {
        val state = TimeScreenUiState()

        assertThat(state.selectedDate).isEqualTo(LocalDate.now())
        assertThat(state.isLoading).isTrue()
        assertThat(state.isDateContentReady).isFalse()
        assertThat(state.error).isNull()
        assertThat(state.activeEntry).isNull()
        assertThat(state.lastEntry).isNull()
    }

    @Test
    fun `a fresh state carries no entries, tasks, or occurrences`() {
        val state = TimeScreenUiState()

        assertThat(state.timeEntries).isEmpty()
        assertThat(state.tasks).isEmpty()
        assertThat(state.taskPickerTasks).isEmpty()
        assertThat(state.plannedTasks).isEmpty()
        assertThat(state.pastOccurrences).isEmpty()
    }

    @Test
    fun `the default empty lists are shared, so one state's loaded data cannot leak into another`() {
        val first = TimeScreenUiState()
        val second = TimeScreenUiState()
        val entry = sampleEntry("entry-1")

        val withEntry = first.copy(timeEntries = listOf(entry))

        // Two freshly constructed states must not see each other's loads - a screen is rebuilt long
        // after its ViewModel, and a shared mutable default would show the previous day's entries.
        assertThat(second.timeEntries).isEmpty()
        assertThat(first.timeEntries).isEmpty()
        assertThat(withEntry.timeEntries).containsExactly(entry)
    }

    @Test
    fun `copy replaces only the named field and leaves the rest untouched`() {
        val entry = sampleEntry("entry-2")
        val loaded = TimeScreenUiState(timeEntries = listOf(entry), isLoading = false, isDateContentReady = true)
        val date = LocalDate.of(2026, 9, 20)

        val nextDay = loaded.copy(selectedDate = date)

        assertThat(nextDay.selectedDate).isEqualTo(date)
        assertThat(nextDay.timeEntries).containsExactly(entry)
        assertThat(nextDay.isLoading).isFalse()
        assertThat(nextDay.isDateContentReady).isTrue()
        // The source is a value, not a shared mutable box: it must not have followed the copy.
        assertThat(loaded.selectedDate).isEqualTo(LocalDate.now())
    }

    @Test
    fun `an update to a field the screen does not key on does not re-emit, and a real change does`() {
        // The screen keys effects on these fields, and the StateFlow only re-emits when the new value
        // is not equal to the current one. If the state lost value semantics, either a needed
        // recomposition would be skipped or a no-op update would churn the screen.
        val flow = MutableStateFlow(TimeScreenUiState())
        val emissions = mutableListOf<TimeScreenUiState>()

        val unchanged = flow.value.copy(isLoading = true)
        flow.value = unchanged
        emissions += flow.value

        val dateChanged = flow.value.copy(selectedDate = LocalDate.of(2026, 9, 20))
        flow.value = dateChanged
        emissions += flow.value

        assertThat(emissions[0]).isEqualTo(flow.value.let { TimeScreenUiState() }.copy(isLoading = true))
        assertThat(emissions[0]).isNotSameInstanceAs(emissions[1])
        assertThat(emissions[0]).isNotEqualTo(emissions[1])
        assertThat(emissions[1].selectedDate).isEqualTo(LocalDate.of(2026, 9, 20))
    }

    @Test
    fun `two states with the same fields are equal and a changed entry breaks that`() {
        val entry = sampleEntry("entry-3")
        val one = TimeScreenUiState(timeEntries = listOf(entry))
        val same = TimeScreenUiState(timeEntries = listOf(entry))
        val different = TimeScreenUiState(timeEntries = listOf(entry.copy(focusRating = 4.0)))

        assertThat(one).isEqualTo(same)
        assertThat(one.hashCode()).isEqualTo(same.hashCode())
        assertThat(one).isNotEqualTo(different)
    }

    @Test
    fun `an error is carried as a message and clears back to null`() {
        val failed = TimeScreenUiState(isLoading = false, error = "Could not load entries")

        assertThat(failed.error).isEqualTo("Could not load entries")
        assertThat(failed.isLoading).isFalse()
        assertThat(failed.copy(error = null).error).isNull()
    }

    @Test
    fun `planned, picker, and all-task lists are held separately`() {
        // The picker is derived from planned + all tasks, so the state must be able to hold the derived
        // list and the source lists at once; collapsing them would change what the screen can show.
        val all = listOf(sampleTask("task-1"), sampleTask("task-2"))
        val planned = listOf(all[0])
        val picker = listOf(all[0], all[1])

        val state = TimeScreenUiState(tasks = all, plannedTasks = planned, taskPickerTasks = picker)

        assertThat(state.tasks).hasSize(2)
        assertThat(state.plannedTasks).containsExactly(all[0])
        assertThat(state.taskPickerTasks).containsExactly(all[0], all[1])
    }

    private fun sampleEntry(
        id: String,
        rating: Double? = null,
    ) = TimeEntry(
        id = id,
        lifeIntentionCategory = "Work",
        startedAt = LocalDateTime.of(2026, 9, 20, 9, 0),
        endedAt = LocalDateTime.of(2026, 9, 20, 10, 0),
        focusRating = rating,
        createdAt = LocalDateTime.of(2026, 9, 20, 9, 0),
        updatedAt = LocalDateTime.of(2026, 9, 20, 10, 0),
    )

    private fun sampleTask(id: String) =
        Task(
            id = id,
            title = "Task $id",
            createdAt = LocalDateTime.of(2026, 9, 20, 8, 0),
            updatedAt = LocalDateTime.of(2026, 9, 20, 8, 0),
        )
}
