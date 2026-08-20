//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.DayPlanAllocationRecord
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal object TimeVisualsCalculator {
    private val logger: UnifiedLogger? = runCatching { UnifiedLogger.getInstance() }.getOrNull()

    /**
     * Compute day overall.
     */
    fun computeDayOverall(
        /** Selected date. */
        selectedDate: LocalDate,
        entries: List<TimeEntry>,
        now: LocalDateTime = LocalDateTime.now(),
    ): TimeDayOverallSummary {
        /** Intervals. */
        val intervals = buildIntervals(selectedDate, entries, now)
        /** Tracked minutes. */
        val trackedMinutes = intervals.sumOf { Duration.between(it.first, it.second).toMinutes().coerceAtLeast(0) }
        /** Active block count. */
        val activeBlockCount = entries.count { it.endedAt == null }
        /** Weighted focus. */
        val weightedFocus = intervals.sumOf { (it.third * Duration.between(it.first, it.second).toMinutes()).toLong() }
        /** Focused minutes percent. */
        val focusedMinutesPercent = if (trackedMinutes > 0L) {
            (weightedFocus.toFloat() / trackedMinutes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        /** Overlap count. */
        val overlapCount = countOverlaps(intervals)
        /** Gap count. */
        val gapCount = countGaps(selectedDate, intervals, now)
        /** Untracked. */
        val untracked = (if (selectedDate == now.toLocalDate()) now.hour * 60 + now.minute else 24 * 60) - trackedMinutes
        /** Result. */
        val result = TimeDayOverallSummary(
            trackedMinutes = trackedMinutes,
            activeBlockCount = activeBlockCount,
            focusedMinutesPercent = focusedMinutesPercent,
            untrackedMinutesEstimate = untracked.coerceAtLeast(0),
            overlapCount = overlapCount,
            gapCount = gapCount,
        )
        logger?.d(
            "TimeVisualsCalculator.computeDayOverall",
            "Computed day overall summary",
            /** Map of. */
            mapOf(
                "selectedDate" to selectedDate.toString(),
                "trackedMinutes" to trackedMinutes,
                "activeBlockCount" to activeBlockCount,
                "overlapCount" to overlapCount,
                "gapCount" to gapCount,
            ),
        )
        return result
    }

    /**
     * Compute per dimension.
     */
    fun computePerDimension(
        /** Selected date. */
        selectedDate: LocalDate,
        entries: List<TimeEntry>,
        occurrences: List<TaskOccurrence> = emptyList(),
        taskLookup: Map<String, Task>,
        allocations: List<DayPlanAllocationRecord>,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<TimeDimensionDaySummary> {
        /** Intervals. */
        val intervals = buildIntervals(selectedDate, entries, now)
        /** Grouped entries. */
        val groupedEntries = entries.groupBy { entry ->
            InsightsDimensionContract.timeEntryDimensionId(entry, taskLookup)
        }
        /** Tracked by dimension from entries. */
        val trackedByDimensionFromEntries = groupedEntries.mapValues { (_, dimEntries) ->
            /** Build intervals. */
            buildIntervals(selectedDate, dimEntries, now)
                .sumOf { Duration.between(it.first, it.second).toMinutes().coerceAtLeast(0) }
                .toLong()
        }

        /** Tasks with tracked entries. */
        val tasksWithTrackedEntries = entries.mapNotNull { it.taskId }.toSet()
        /** Supplemental by dimension. */
        val supplementalByDimension = mutableMapOf<String, Long>()
        occurrences.forEach { occurrence ->
            /** Task. */
            val task = taskLookup[occurrence.taskId] ?: return@forEach
            /** If. */
            if (!task.recurrenceEnabled || occurrence.taskId in tasksWithTrackedEntries) {
                return@forEach
            }
            /** Minutes. */
            val minutes = occurrence.actualDurationMinutes?.coerceAtLeast(0)?.toLong() ?: 0L
            /** If. */
            if (minutes <= 0L) {
                return@forEach
            }
            /** Dimension id. */
            val dimensionId = task.dimensionId ?: return@forEach
            supplementalByDimension[dimensionId] = (supplementalByDimension[dimensionId] ?: 0L) + minutes
        }

        /** Total minutes. */
        val totalMinutes = trackedByDimensionFromEntries.values.sum() + supplementalByDimension.values.sum()
        /** Planned by dimension. */
        val plannedByDimension = allocations.associate { it.dimensionId to it.plannedMinutes }
        /** Dimension ids. */
        val dimensionIds = (trackedByDimensionFromEntries.keys + supplementalByDimension.keys + plannedByDimension.keys).toSet()
        /** Result. */
        val result = dimensionIds.map { dimensionId ->
            /** Dim entries. */
            val dimEntries = groupedEntries[dimensionId].orEmpty()
            /** Dim intervals. */
            val dimIntervals = buildIntervals(selectedDate, dimEntries, now)
            /** Tracked from entries. */
            val trackedFromEntries = trackedByDimensionFromEntries[dimensionId] ?: 0L
            /** Tracked supplemental. */
            val trackedSupplemental = supplementalByDimension[dimensionId] ?: 0L
            /** Tracked. */
            val tracked = trackedFromEntries + trackedSupplemental
            /** Focused from entries. */
            val focusedFromEntries = dimIntervals.sumOf { (it.third * Duration.between(it.first, it.second).toMinutes()).toLong() }
            /** Planned. */
            val planned = plannedByDimension[dimensionId] ?: 0
            /** Time dimension day summary. */
            TimeDimensionDaySummary(
                dimensionId = dimensionId,
                dimensionLabel = InsightsDimensionContract.dimensionLabel(dimensionId),
                trackedMinutes = tracked,
                sharePercent = if (totalMinutes > 0) (tracked.toFloat() / totalMinutes.toFloat()).coerceIn(0f, 1f) else 0f,
                focusedMinutes = focusedFromEntries.coerceAtLeast(0),
                blockCount = dimEntries.size,
                plannedMinutes = planned,
                plannedDeltaMinutes = tracked - planned.toLong(),
            )
        }.sortedByDescending { it.trackedMinutes }
        logger?.d(
            "TimeVisualsCalculator.computePerDimension",
            "Computed per-dimension time rollup",
            /** Map of. */
            mapOf(
                "selectedDate" to selectedDate.toString(),
                "dimensions" to result.size.toString(),
                "trackedEntries" to entries.size.toString(),
                "plannedDimensions" to plannedByDimension.size.toString(),
                "supplementalOccurrences" to occurrences.size.toString(),
            ),
        )
        return result
    }

    private fun buildIntervals(
        /** Selected date. */
        selectedDate: LocalDate,
        entries: List<TimeEntry>,
        /** Now. */
        now: LocalDateTime,
    ): List<Triple<LocalDateTime, LocalDateTime, Double>> {
        /** Day start. */
        val dayStart = selectedDate.atStartOfDay()
        /** Day end. */
        val dayEnd = if (selectedDate == now.toLocalDate()) now else LocalDateTime.of(selectedDate, LocalTime.of(23, 59, 59))
        return entries.mapNotNull { entry ->
            /** Start. */
            val start = maxOf(entry.startedAt, dayStart)
            /** End. */
            val end = minOf(entry.endedAt ?: now, dayEnd)
            /** If. */
            if (end.isAfter(start)) Triple(start, end, (entry.focusRating ?: 0.0).coerceIn(0.0, 1.0)) else null
        }.sortedBy { it.first }
    }

    private fun countOverlaps(intervals: List<Triple<LocalDateTime, LocalDateTime, Double>>): Int {
        /** If. */
        if (intervals.size < 2) return 0
        /** Overlaps. */
        var overlaps = 0
        /** For. */
        for (i in 1 until intervals.size) {
            /** If. */
            if (intervals[i].first.isBefore(intervals[i - 1].second)) {
                overlaps++
            }
        }
        return overlaps
    }

    private fun countGaps(
        /** Selected date. */
        selectedDate: LocalDate,
        intervals: List<Triple<LocalDateTime, LocalDateTime, Double>>,
        /** Now. */
        now: LocalDateTime,
    ): Int {
        /** If. */
        if (intervals.isEmpty()) return 0
        /** Day start. */
        val dayStart = selectedDate.atStartOfDay()
        /** Day end. */
        val dayEnd = if (selectedDate == now.toLocalDate()) now else LocalDateTime.of(selectedDate, LocalTime.of(23, 59, 59))
        /** Gaps. */
        var gaps = 0
        /** Cursor. */
        var cursor = dayStart
        intervals.forEach { (start, end, _) ->
            /** If. */
            if (start.isAfter(cursor)) gaps++
            /** If. */
            if (end.isAfter(cursor)) cursor = end
        }
        /** If. */
        if (dayEnd.isAfter(cursor)) gaps++
        return gaps
    }
}
