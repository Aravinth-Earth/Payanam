//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.day

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * The Day-screen state, which both platforms render from.
 *
 * Two things here are load-bearing beyond the field list. First, the prompt tables: the Day screen renders one
 * field per prompt, so a prompt whose key is missing from the table is a question the user can never answer —
 * the key sets are asserted to be non-empty and unique. Second, the "saves in flight" set: it is what keeps the
 * debounced journal editor from reporting a day as saved before the write lands, so its default must be empty and
 * not a shared instance.
 */
class DayUiStateTest {
    @Test
    fun `a fresh state is loading today on the summary tab`() {
        val state = DayUiState()

        assertThat(state.isLoading).isTrue()
        assertThat(state.selectedDate).isEqualTo(LocalDate.now())
        assertThat(state.selectedTab).isEqualTo(DayTab.SUMMARY)
        assertThat(state.journalEntry).isNull()
        assertThat(state.lastSavedJournalDate).isNull()
    }

    @Test
    fun `a fresh state has no responses and no saves in flight`() {
        val state = DayUiState()

        assertThat(state.overallResponses).isEmpty()
        assertThat(state.dimensionResponses).isEmpty()
        assertThat(state.pendingJournalSaveDates).isEmpty()
    }

    @Test
    fun `the default pending-save set is the shared empty set, so a copy must not mutate it`() {
        val first = DayUiState()
        val second = DayUiState()
        val date = LocalDate.of(2026, 9, 20)

        // The default is Kotlin's shared immutable empty set; adding a date produces a new set, so one state
        // marking a save in flight can never leak into another.
        val withSave = first.copy(pendingJournalSaveDates = first.pendingJournalSaveDates + date)

        assertThat(second.pendingJournalSaveDates).isEmpty()
        assertThat(first.pendingJournalSaveDates).isEmpty()
        assertThat(withSave.pendingJournalSaveDates).containsExactly(date)
    }

    @Test
    fun `the overall prompt table is not empty and has unique keys`() {
        val keys = OVERALL_JOURNAL_PROMPTS.map { it.first }

        assertThat(keys).isNotEmpty()
        assertThat(keys).containsNoDuplicates()
        assertThat(OVERALL_JOURNAL_PROMPTS.map { it.second }).containsNoneOf("", null)
    }

    @Test
    fun `the dimension prompt table is not empty and has unique keys`() {
        val keys = DIMENSION_JOURNAL_PROMPTS.map { it.first }

        assertThat(keys).isNotEmpty()
        assertThat(keys).containsNoDuplicates()
        assertThat(DIMENSION_JOURNAL_PROMPTS.map { it.second }).containsNoneOf("", null)
    }

    @Test
    fun `a populated state carries the responses and the in-flight save`() {
        val date = LocalDate.of(2026, 9, 20)
        val state =
            DayUiState(
                isLoading = false,
                selectedDate = date,
                overallResponses = mapOf("gratitude" to "the walk"),
                dimensionResponses = mapOf("dim_work_livelihood" to mapOf("focus" to "deep work")),
                pendingJournalSaveDates = setOf(date),
                lastSavedJournalDate = date.minusDays(1),
            )

        assertThat(state.isLoading).isFalse()
        assertThat(state.overallResponses["gratitude"]).isEqualTo("the walk")
        assertThat(state.dimensionResponses["dim_work_livelihood"]?.get("focus")).isEqualTo("deep work")
        assertThat(state.pendingJournalSaveDates).containsExactly(date)
        assertThat(state.lastSavedJournalDate).isEqualTo(date.minusDays(1))
    }
}
