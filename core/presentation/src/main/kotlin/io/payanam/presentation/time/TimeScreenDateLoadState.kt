//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import java.time.LocalDate

/**
 * The three independently-loading sections of one selected-date load on the
 * Time screen.
 */
enum class TimeScreenDateSection {
    ENTRIES,
    PLANNED_TASKS,
    OCCURRENCES,
}

/**
 * True when [selectedDate] is today, so the screen should read today's planned
 * tasks rather than the tasks due on that date.
 */
fun shouldUseTodaysPlannedTasks(selectedDate: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
    selectedDate == today

/**
 * True when a selected-date load has everything it needs to render: the
 * entries and planned tasks, plus the occurrences unless minimal mode skips
 * them.
 */
fun isTimeScreenDateContentReady(
    entriesLoaded: Boolean,
    plannedTasksLoaded: Boolean,
    occurrencesLoaded: Boolean,
    needsOccurrences: Boolean,
): Boolean = entriesLoaded && plannedTasksLoaded && (occurrencesLoaded || !needsOccurrences)

/**
 * The load state of one selected-date load: which sections have arrived, and
 * whether the screen may render them yet.
 *
 * Each selected date gets a fresh instance carrying its own [requestId], so a
 * section that arrives late from a superseded load can be recognised and
 * ignored rather than marking the current one ready.
 */
class TimeScreenDateLoadState(
    val requestId: Long,
    val needsOccurrences: Boolean,
    val entriesLoaded: Boolean = false,
    val plannedTasksLoaded: Boolean = false,
    val occurrencesLoaded: Boolean = false,
) {
    /**
     * Marks [section] of this selected-date load as finished.
     */
    fun markSectionLoaded(section: TimeScreenDateSection): TimeScreenDateLoadState =
        when (section) {
            TimeScreenDateSection.ENTRIES -> copy(entriesLoaded = true)
            TimeScreenDateSection.PLANNED_TASKS -> copy(plannedTasksLoaded = true)
            TimeScreenDateSection.OCCURRENCES -> copy(occurrencesLoaded = true)
        }

    /**
     * True once all required sections of this selected-date load have arrived
     * (entries + planned tasks, plus occurrences unless minimal mode skips them).
     */
    fun isReady(): Boolean =
        isTimeScreenDateContentReady(
            entriesLoaded = entriesLoaded,
            plannedTasksLoaded = plannedTasksLoaded,
            occurrencesLoaded = occurrencesLoaded,
            needsOccurrences = needsOccurrences,
        )

    private fun copy(
        entriesLoaded: Boolean = this.entriesLoaded,
        plannedTasksLoaded: Boolean = this.plannedTasksLoaded,
        occurrencesLoaded: Boolean = this.occurrencesLoaded,
    ): TimeScreenDateLoadState =
        TimeScreenDateLoadState(
            requestId = requestId,
            needsOccurrences = needsOccurrences,
            entriesLoaded = entriesLoaded,
            plannedTasksLoaded = plannedTasksLoaded,
            occurrencesLoaded = occurrencesLoaded,
        )
}
