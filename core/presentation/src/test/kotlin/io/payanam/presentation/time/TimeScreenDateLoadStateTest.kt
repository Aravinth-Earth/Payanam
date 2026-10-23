//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TimeScreenDateLoadStateTest {
    @Test
    fun isTimeScreenDateContentReady_false_until_all_required_sections_are_loaded() {
        val isReady =
            isTimeScreenDateContentReady(
                entriesLoaded = true,
                plannedTasksLoaded = false,
                occurrencesLoaded = true,
                needsOccurrences = true,
            )
        assertFalse(isReady)
    }

    @Test
    fun isTimeScreenDateContentReady_true_when_entries_planned_tasks_and_occurrences_are_loaded() {
        val isReady =
            isTimeScreenDateContentReady(
                entriesLoaded = true,
                plannedTasksLoaded = true,
                occurrencesLoaded = true,
                needsOccurrences = true,
            )
        assertTrue(isReady)
    }

    @Test
    fun isTimeScreenDateContentReady_true_without_occurrences_when_minimal_mode_contract_applies() {
        val isReady =
            isTimeScreenDateContentReady(
                entriesLoaded = true,
                plannedTasksLoaded = true,
                occurrencesLoaded = false,
                needsOccurrences = false,
            )
        assertTrue(isReady)
    }

    @Test
    fun isTimeScreenDateContentReady_false_without_entries() {
        val isReady =
            isTimeScreenDateContentReady(
                entriesLoaded = false,
                plannedTasksLoaded = true,
                occurrencesLoaded = true,
                needsOccurrences = true,
            )
        assertFalse(isReady)
    }

    @Test
    fun isTimeScreenDateContentReady_false_without_planned_tasks() {
        val isReady =
            isTimeScreenDateContentReady(
                entriesLoaded = true,
                plannedTasksLoaded = false,
                occurrencesLoaded = true,
                needsOccurrences = true,
            )
        assertFalse(isReady)
    }

    @Test
    fun shouldUseTodaysPlannedTasks_true_only_for_today() {
        val today = LocalDate.of(2026, 4, 9)
        assertTrue(shouldUseTodaysPlannedTasks(today, today))
        assertEquals(false, shouldUseTodaysPlannedTasks(today.minusDays(1), today))
        assertEquals(false, shouldUseTodaysPlannedTasks(today.plusDays(1), today))
    }

    @Test
    fun loadState_is_not_ready_before_any_section_arrives() {
        val state = TimeScreenDateLoadState(requestId = 1L, needsOccurrences = true)
        assertFalse(state.isReady())
    }

    @Test
    fun loadState_is_not_ready_until_the_last_required_section_arrives() {
        val entries =
            TimeScreenDateLoadState(1L, needsOccurrences = true)
                .markSectionLoaded(TimeScreenDateSection.ENTRIES)
        assertFalse(entries.isReady())

        val planned = entries.markSectionLoaded(TimeScreenDateSection.PLANNED_TASKS)
        assertFalse(planned.isReady())
    }

    @Test
    fun loadState_is_ready_once_occurrences_arrive_in_full_mode() {
        val state =
            TimeScreenDateLoadState(requestId = 1L, needsOccurrences = true)
                .markSectionLoaded(TimeScreenDateSection.ENTRIES)
                .markSectionLoaded(TimeScreenDateSection.PLANNED_TASKS)
                .markSectionLoaded(TimeScreenDateSection.OCCURRENCES)
        assertTrue(state.isReady())
    }

    @Test
    fun loadState_is_ready_without_occurrences_in_minimal_mode() {
        val state =
            TimeScreenDateLoadState(requestId = 1L, needsOccurrences = false)
                .markSectionLoaded(TimeScreenDateSection.ENTRIES)
                .markSectionLoaded(TimeScreenDateSection.PLANNED_TASKS)
        assertTrue(state.isReady())
    }

    @Test
    fun loadState_stays_not_ready_on_occurrences_alone() {
        val state =
            TimeScreenDateLoadState(requestId = 1L, needsOccurrences = true)
                .markSectionLoaded(TimeScreenDateSection.OCCURRENCES)
        assertFalse(state.isReady())
    }

    @Test
    fun loadState_ignores_occurrences_in_minimal_mode() {
        val state =
            TimeScreenDateLoadState(requestId = 1L, needsOccurrences = false)
                .markSectionLoaded(TimeScreenDateSection.ENTRIES)
                .markSectionLoaded(TimeScreenDateSection.PLANNED_TASKS)
                .markSectionLoaded(TimeScreenDateSection.OCCURRENCES)
        assertTrue(state.isReady())
    }

    @Test
    fun loadState_marking_a_section_returns_a_new_state_and_leaves_the_old_one_unchanged() {
        val original = TimeScreenDateLoadState(requestId = 1L, needsOccurrences = true)
        val marked = original.markSectionLoaded(TimeScreenDateSection.ENTRIES)
        assertFalse("marking must not mutate the previous state", original.isReady())
        assertFalse(marked.isReady())
    }

    @Test
    fun loadState_preserves_the_request_id_across_section_marks() {
        val state =
            TimeScreenDateLoadState(requestId = 42L, needsOccurrences = true)
                .markSectionLoaded(TimeScreenDateSection.ENTRIES)
        assertEquals(42L, state.requestId)
    }

    @Test
    fun loadState_repeating_a_section_mark_does_not_change_readiness() {
        val once =
            TimeScreenDateLoadState(1L, needsOccurrences = true)
                .markSectionLoaded(TimeScreenDateSection.ENTRIES)
        val twice = once.markSectionLoaded(TimeScreenDateSection.ENTRIES)
        assertFalse(once.isReady())
        assertFalse(twice.isReady())
    }
}
