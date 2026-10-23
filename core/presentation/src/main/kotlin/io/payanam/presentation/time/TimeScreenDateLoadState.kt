//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

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

/**
 * Holds the current [TimeScreenDateLoadState] of the Time screen and merges section arrivals into it
 * atomically.
 *
 * The three section collectors run concurrently on the ViewModel's dispatcher, so each one arrives at
 * [markSectionLoaded] independently. [TimeScreenDateLoadState] is immutable and its `markSectionLoaded`
 * returns a NEW instance carrying the OTHER sections' flags as read at that moment, so a plain
 * read-modify-write over a plain `var` is a lost update: two arrivals that interleave between their read
 * and their write make the later writer commit the earlier arrival's flags as still unloaded. That flag
 * is never re-marked (each collector marks its own section once), so `isReady()` can stay false forever
 * and the screen keeps its loading placeholder.
 *
 * [markSectionLoaded] therefore merges through [AtomicReference.updateAndGet], whose function is applied
 * to the CURRENT value and retried on contention - concurrent arrivals set their own bit in the value
 * they read rather than overwriting one another. Being a single atomic read-modify-write it needs no
 * suspension point, so callers stay plain non-suspending functions, and the merged snapshot it returns is
 * the one callers then read [TimeScreenDateLoadState.isReady] from, so the mark and the readiness check
 * see the same state.
 *
 * [replace] is for starting a fresh load (new [TimeScreenDateLoadState.requestId]), which must replace
 * the whole state rather than merge into the previous load's flags.
 */
class TimeScreenDateLoadStateHolder(initial: TimeScreenDateLoadState) {
    private val state = AtomicReference(initial)

    /**
     * Marks [section] loaded on the load whose id is [requestId], returning the merged state, or `null`
     * when [requestId] is not the current load (a superseded load's late arrival, which must be ignored
     * rather than marking the current one ready).
     */
    fun markSectionLoaded(requestId: Long, section: TimeScreenDateSection): TimeScreenDateLoadState? =
        state.updateAndGet { current ->
            if (current.requestId != requestId) {
                current
            } else {
                current.markSectionLoaded(section)
            }
        }.takeIf { it.requestId == requestId }

    /** The merged state after the most recent mark. */
    fun current(): TimeScreenDateLoadState = state.get()

    /** Starts a new selected-date load, discarding the flags of the load it replaces. */
    fun replace(next: TimeScreenDateLoadState) {
        state.set(next)
    }
}
