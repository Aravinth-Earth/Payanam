//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.day

import io.payanam.domain.model.DayJournalEntry
import io.payanam.shared.journal.JournalReflectionContracts
import java.time.LocalDate

/**
 * Tabs of the Day screen (currently only the combined SUMMARY view).
 */
enum class DayTab {
    SUMMARY,
}

/**
 * UI state for the Day screen: selected date/tab, the loaded journal entry
 * with its overall + per-dimension prompt responses, dates with saves still
 * in flight, and the last successfully saved date.
 */
data class DayUiState(
    val isLoading: Boolean = true,
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedTab: DayTab = DayTab.SUMMARY,
    val journalEntry: DayJournalEntry? = null,
    val overallResponses: Map<String, String> = emptyMap(),
    val dimensionResponses: Map<String, Map<String, String>> = emptyMap(),
    val pendingJournalSaveDates: Set<LocalDate> = emptySet(),
    val lastSavedJournalDate: LocalDate? = null,
)

val OVERALL_JOURNAL_PROMPTS = JournalReflectionContracts.overallPrompts.map { it.key to it.prompt }
val DIMENSION_JOURNAL_PROMPTS = JournalReflectionContracts.dimensionPrompts.map { it.key to it.prompt }
