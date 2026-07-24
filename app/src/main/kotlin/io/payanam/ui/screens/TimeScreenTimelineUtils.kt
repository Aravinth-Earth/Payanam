//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private const val GAP_MINUTES_THRESHOLD = 1
private val logger = UnifiedLogger.getInstance()

internal data class TimeGap(
    val startMinutes: Int,
    val endMinutes: Int,
    val minutes: Int,
)

internal data class TimeOverlap(
    val startMinutes: Int,
    val endMinutes: Int,
    val minutes: Int,
)

internal data class MinuteWindow(
    val startMinutes: Int,
    val endMinutes: Int,
)

internal fun computeTimeGaps(
    selectedDate: LocalDate,
    entries: List<TimeEntry>,
    activeEntry: TimeEntry?,
    now: LocalDateTime,
): List<TimeGap> {
    val dayStart = LocalDateTime.of(selectedDate, LocalTime.MIDNIGHT)
    val dayEnd =
        if (selectedDate == now.toLocalDate()) {
            now
        } else {
            LocalDateTime.of(
                selectedDate,
                LocalTime.of(23, 59, 59),
            )
        }

    val allEntries = entries.toMutableList()
    if (activeEntry != null && allEntries.none { it.id == activeEntry.id }) {
        allEntries.add(activeEntry)
    }

    val intervals = allEntries.mapNotNull { entry ->
        val start = if (entry.startedAt.isAfter(dayStart)) entry.startedAt else dayStart
        val rawEnd = entry.endedAt ?: if (selectedDate == now.toLocalDate()) now else dayEnd
        val end = if (rawEnd.isBefore(dayEnd)) rawEnd else dayEnd
        if (end.isAfter(start)) start to end else null
    }.sortedBy { it.first }

    val omittedIntervals = allEntries.size - intervals.size
    if (omittedIntervals > 0) {
        logger.w(
            "TimeScreenTimeline.computeTimeGaps",
            "Skipped invalid intervals while computing untracked gaps",
            mapOf("omittedIntervals" to omittedIntervals, "selectedDate" to selectedDate.toString()),
        )
    }

    val gaps = mutableListOf<TimeGap>()
    var cursor = dayStart

    intervals.forEach { (start, end) ->
        if (start.isAfter(cursor)) {
            val minutes = Duration.between(cursor, start).toMinutes().toInt()
            if (minutes >= GAP_MINUTES_THRESHOLD) {
                val startMinutes = Duration.between(dayStart, cursor).toMinutes().toInt()
                gaps.add(TimeGap(startMinutes, startMinutes + minutes, minutes))
            }
        }
        if (end.isAfter(cursor)) {
            cursor = end
        }
    }

    if (dayEnd.isAfter(cursor)) {
        val minutes = Duration.between(cursor, dayEnd).toMinutes().toInt()
        if (minutes >= GAP_MINUTES_THRESHOLD) {
            val startMinutes = Duration.between(dayStart, cursor).toMinutes().toInt()
            gaps.add(TimeGap(startMinutes, startMinutes + minutes, minutes))
        }
    }

    return gaps
}

internal fun computeTimeOverlaps(
    selectedDate: LocalDate,
    entries: List<TimeEntry>,
    activeEntry: TimeEntry?,
    now: LocalDateTime,
): List<TimeOverlap> {
    val intervals = buildDayBoundIntervals(selectedDate, entries, activeEntry, now)
    if (intervals.size < 2) {
        return emptyList()
    }
    val events = mutableListOf<Pair<Int, Int>>()
    intervals.forEach { (start, end) ->
        val startMinute = minuteOfDay(start)
        val endMinute = minuteOfDay(end).coerceAtLeast(startMinute + 1)
        events.add(startMinute to 1)
        events.add(endMinute to -1)
    }
    val sortedEvents = events.sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
    if (sortedEvents.isEmpty()) {
        return emptyList()
    }

    val overlaps = mutableListOf<TimeOverlap>()
    var active = 0
    var previousMinute = sortedEvents.first().first
    sortedEvents.forEach { (minute, delta) ->
        if (minute > previousMinute && active >= 2) {
            val overlapMinutes = minute - previousMinute
            overlaps.add(
                TimeOverlap(
                    startMinutes = previousMinute,
                    endMinutes = minute,
                    minutes = overlapMinutes,
                ),
            )
        }
        active += delta
        previousMinute = minute
    }
    return overlaps
}

internal fun resolveOccurrenceWindowMinutes(
    selectedDate: LocalDate,
    occurrence: TaskOccurrence,
    task: Task?,
    fallbackIndex: Int,
    fallbackTotal: Int,
    defaultDurationMinutes: Int,
): MinuteWindow {
    val durationMinutes = task?.durationMinutes?.takeIf { it > 0 } ?: defaultDurationMinutes
    val explicitOccurrenceMinutes = parseIsoDateTime(occurrence.occurrenceDate)
        ?.takeIf { occurrence.occurrenceDate.contains("T") && it.toLocalDate() == selectedDate }
        ?.let { it.hour * 60 + it.minute }
        ?.takeUnless { minutes ->
            minutes == 0 && occurrence.completedAt == null && occurrence.actualCompletedAt == null
        }
    val completedAtMinutes = parseIsoDateTime(occurrence.completedAt)
        ?.takeIf { it.toLocalDate() == selectedDate }
        ?.let { it.hour * 60 + it.minute }
    val actualCompletedAtMinutes = occurrence.actualCompletedAt
        ?.takeIf { it.toLocalDate() == selectedDate }
        ?.let { it.hour * 60 + it.minute }
    val taskDueMinutes = task?.dueDate?.let { it.hour * 60 + it.minute }

    val dueMinutes = taskDueMinutes
        ?: explicitOccurrenceMinutes
        ?: completedAtMinutes
        ?: actualCompletedAtMinutes
        ?: distributedFallbackAnchor(fallbackIndex, fallbackTotal)
    val safeDueMinutes = dueMinutes.coerceIn(0, 1439)
    val startMinutes = (safeDueMinutes - (durationMinutes / 2)).coerceAtLeast(0)
    val endMinutes = (startMinutes + durationMinutes).coerceAtMost(1440).coerceAtLeast(startMinutes + 1)
    return MinuteWindow(
        startMinutes = startMinutes,
        endMinutes = endMinutes,
    )
}

private fun buildDayBoundIntervals(
    selectedDate: LocalDate,
    entries: List<TimeEntry>,
    activeEntry: TimeEntry?,
    now: LocalDateTime,
): List<Pair<LocalDateTime, LocalDateTime>> {
    val dayStart = LocalDateTime.of(selectedDate, LocalTime.MIDNIGHT)
    val dayEnd =
        if (selectedDate == now.toLocalDate()) {
            now
        } else {
            LocalDateTime.of(
                selectedDate,
                LocalTime.of(23, 59, 59),
            )
        }

    val allEntries = entries.toMutableList()
    if (activeEntry != null && allEntries.none { it.id == activeEntry.id }) {
        allEntries.add(activeEntry)
    }
    return allEntries.mapNotNull { entry ->
        val start = if (entry.startedAt.isAfter(dayStart)) entry.startedAt else dayStart
        val rawEnd = entry.endedAt ?: if (selectedDate == now.toLocalDate()) now else dayEnd
        val end = if (rawEnd.isBefore(dayEnd)) rawEnd else dayEnd
        if (end.isAfter(start)) start to end else null
    }
}

private fun minuteOfDay(time: LocalDateTime): Int = (time.hour * 60 + time.minute).coerceIn(0, 1440)

private fun distributedFallbackAnchor(index: Int, total: Int): Int {
    val safeTotal = total.coerceAtLeast(1)
    val safeIndex = index.coerceAtLeast(0)
    val slot = (safeIndex % safeTotal) + 1
    return ((slot * 1440.0) / (safeTotal + 1)).toInt().coerceIn(0, 1439)
}

private fun parseIsoDateTime(value: String?): LocalDateTime? {
    if (value.isNullOrBlank()) return null
    val normalized = value.trim().removeSuffix("Z")
    return runCatching { LocalDateTime.parse(normalized) }
        .recoverCatching { LocalDate.parse(normalized).atStartOfDay() }
        .getOrNull()
}

internal fun toMutedPastelColor(baseColor: Color, surfaceColor: Color): Color = lerp(baseColor, surfaceColor, 0.28f)
