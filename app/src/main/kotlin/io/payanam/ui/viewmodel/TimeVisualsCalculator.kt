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
        selectedDate: LocalDate,
        entries: List<TimeEntry>,
        now: LocalDateTime = LocalDateTime.now(),
    ): TimeDayOverallSummary {
        val intervals = buildIntervals(selectedDate, entries, now)
        val trackedMinutes = intervals.sumOf { Duration.between(it.first, it.second).toMinutes().coerceAtLeast(0) }
        val activeBlockCount = entries.count { it.endedAt == null }
        val weightedFocus = intervals.sumOf { (it.third * Duration.between(it.first, it.second).toMinutes()).toLong() }
        val focusedMinutesPercent = if (trackedMinutes > 0L) {
            (weightedFocus.toFloat() / trackedMinutes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val overlapCount = countOverlaps(intervals)
        val gapCount = countGaps(selectedDate, intervals, now)
        val untracked = (if (selectedDate == now.toLocalDate()) now.hour * 60 + now.minute else 24 * 60) - trackedMinutes
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
        selectedDate: LocalDate,
        entries: List<TimeEntry>,
        occurrences: List<TaskOccurrence> = emptyList(),
        taskLookup: Map<String, Task>,
        allocations: List<DayPlanAllocationRecord>,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<TimeDimensionDaySummary> {
        val intervals = buildIntervals(selectedDate, entries, now)
        val groupedEntries = entries.groupBy { entry ->
            InsightsDimensionContract.timeEntryDimensionId(entry, taskLookup)
        }
        val trackedByDimensionFromEntries = groupedEntries.mapValues { (_, dimEntries) ->
            buildIntervals(selectedDate, dimEntries, now)
                .sumOf { Duration.between(it.first, it.second).toMinutes().coerceAtLeast(0) }
                .toLong()
        }
        val tasksWithTrackedEntries = entries.mapNotNull { it.taskId }.toSet()
        val supplementalByDimension = mutableMapOf<String, Long>()
        occurrences.forEach { occurrence ->
            val task = taskLookup[occurrence.taskId] ?: return@forEach
            if (!task.recurrenceEnabled || occurrence.taskId in tasksWithTrackedEntries) {
                return@forEach
            }
            val minutes = occurrence.actualDurationMinutes?.coerceAtLeast(0)?.toLong() ?: 0L
            if (minutes <= 0L) {
                return@forEach
            }
            val dimensionId = task.dimensionId ?: return@forEach
            supplementalByDimension[dimensionId] = (supplementalByDimension[dimensionId] ?: 0L) + minutes
        }
        val totalMinutes = trackedByDimensionFromEntries.values.sum() + supplementalByDimension.values.sum()
        val plannedByDimension = allocations.associate { it.dimensionId to it.plannedMinutes }
        val dimensionIds = (trackedByDimensionFromEntries.keys + supplementalByDimension.keys + plannedByDimension.keys).toSet()
        val result = dimensionIds.map { dimensionId ->
            val dimEntries = groupedEntries[dimensionId].orEmpty()
            val dimIntervals = buildIntervals(selectedDate, dimEntries, now)
            val trackedFromEntries = trackedByDimensionFromEntries[dimensionId] ?: 0L
            val trackedSupplemental = supplementalByDimension[dimensionId] ?: 0L
            val tracked = trackedFromEntries + trackedSupplemental
            val focusedFromEntries = dimIntervals.sumOf { (it.third * Duration.between(it.first, it.second).toMinutes()).toLong() }
            val planned = plannedByDimension[dimensionId] ?: 0
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
        selectedDate: LocalDate,
        entries: List<TimeEntry>,
        now: LocalDateTime,
    ): List<Triple<LocalDateTime, LocalDateTime, Double>> {
        val dayStart = selectedDate.atStartOfDay()
        val dayEnd = if (selectedDate == now.toLocalDate()) now else LocalDateTime.of(selectedDate, LocalTime.of(23, 59, 59))
        return entries.mapNotNull { entry ->
            val start = maxOf(entry.startedAt, dayStart)
            val end = minOf(entry.endedAt ?: now, dayEnd)
            if (end.isAfter(start)) Triple(start, end, (entry.focusRating ?: 0.0).coerceIn(0.0, 1.0)) else null
        }.sortedBy { it.first }
    }

    private fun countOverlaps(intervals: List<Triple<LocalDateTime, LocalDateTime, Double>>): Int {
        if (intervals.size < 2) return 0
        var overlaps = 0
        for (i in 1 until intervals.size) {
            if (intervals[i].first.isBefore(intervals[i - 1].second)) {
                overlaps++
            }
        }
        return overlaps
    }

    private fun countGaps(
        selectedDate: LocalDate,
        intervals: List<Triple<LocalDateTime, LocalDateTime, Double>>,
        now: LocalDateTime,
    ): Int {
        if (intervals.isEmpty()) return 0
        val dayStart = selectedDate.atStartOfDay()
        val dayEnd = if (selectedDate == now.toLocalDate()) now else LocalDateTime.of(selectedDate, LocalTime.of(23, 59, 59))
        var gaps = 0
        var cursor = dayStart
        intervals.forEach { (start, end, _) ->
            if (start.isAfter(cursor)) gaps++
            if (end.isAfter(cursor)) cursor = end
        }
        if (dayEnd.isAfter(cursor)) gaps++
        return gaps
    }
}
