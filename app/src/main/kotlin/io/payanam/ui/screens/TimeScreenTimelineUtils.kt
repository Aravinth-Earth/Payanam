//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

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
    /** Start minutes. */
    val startMinutes: Int,
    /** End minutes. */
    val endMinutes: Int,
    /** Minutes. */
    val minutes: Int,
)

internal data class TimeOverlap(
    /** Start minutes. */
    val startMinutes: Int,
    /** End minutes. */
    val endMinutes: Int,
    /** Minutes. */
    val minutes: Int,
)

internal data class MinuteWindow(
    /** Start minutes. */
    val startMinutes: Int,
    /** End minutes. */
    val endMinutes: Int,
)

internal fun computeTimeGaps(
    /** Selected date. */
    selectedDate: LocalDate,
    entries: List<TimeEntry>,
    activeEntry: TimeEntry?,
    /** Now. */
    now: LocalDateTime,
): List<TimeGap> {
    /** Day start. */
    val dayStart = LocalDateTime.of(selectedDate, LocalTime.MIDNIGHT)
    /** Day end. */
    val dayEnd =
        /** If. */
        if (selectedDate == now.toLocalDate()) {
            /** Now. */
            now
        } else {
            LocalDateTime.of(
                /** Selected date. */
                selectedDate,
                LocalTime.of(23, 59, 59),
            )
        }

    /** All entries. */
    val allEntries = entries.toMutableList()
    /** If. */
    if (activeEntry != null && allEntries.none { it.id == activeEntry.id }) {
        allEntries.add(activeEntry)
    }

    /** Intervals. */
    val intervals = allEntries.mapNotNull { entry ->
        /** Start. */
        val start = if (entry.startedAt.isAfter(dayStart)) entry.startedAt else dayStart
        /** Raw end. */
        val rawEnd = entry.endedAt ?: if (selectedDate == now.toLocalDate()) now else dayEnd
        /** End. */
        val end = if (rawEnd.isBefore(dayEnd)) rawEnd else dayEnd
        /** If. */
        if (end.isAfter(start)) start to end else null
    }.sortedBy { it.first }

    /** Omitted intervals. */
    val omittedIntervals = allEntries.size - intervals.size
    /** If. */
    if (omittedIntervals > 0) {
        logger.w(
            "TimeScreenTimeline.computeTimeGaps",
            "Skipped invalid intervals while computing untracked gaps",
            /** Map of. */
            mapOf("omittedIntervals" to omittedIntervals, "selectedDate" to selectedDate.toString()),
        )
    }

    /** Gaps. */
    val gaps = mutableListOf<TimeGap>()
    /** Cursor. */
    var cursor = dayStart

    intervals.forEach { (start, end) ->
        /** If. */
        if (start.isAfter(cursor)) {
            /** Minutes. */
            val minutes = Duration.between(cursor, start).toMinutes().toInt()
            /** If. */
            if (minutes >= GAP_MINUTES_THRESHOLD) {
                /** Start minutes. */
                val startMinutes = Duration.between(dayStart, cursor).toMinutes().toInt()
                gaps.add(TimeGap(startMinutes, startMinutes + minutes, minutes))
            }
        }
        /** If. */
        if (end.isAfter(cursor)) {
            cursor = end
        }
    }

    /** If. */
    if (dayEnd.isAfter(cursor)) {
        /** Minutes. */
        val minutes = Duration.between(cursor, dayEnd).toMinutes().toInt()
        /** If. */
        if (minutes >= GAP_MINUTES_THRESHOLD) {
            /** Start minutes. */
            val startMinutes = Duration.between(dayStart, cursor).toMinutes().toInt()
            gaps.add(TimeGap(startMinutes, startMinutes + minutes, minutes))
        }
    }

    return gaps
}

internal fun computeTimeOverlaps(
    /** Selected date. */
    selectedDate: LocalDate,
    entries: List<TimeEntry>,
    activeEntry: TimeEntry?,
    /** Now. */
    now: LocalDateTime,
): List<TimeOverlap> {
    /** Intervals. */
    val intervals = buildDayBoundIntervals(selectedDate, entries, activeEntry, now)
    /** If. */
    if (intervals.size < 2) {
        return emptyList()
    }
    /** Events. */
    val events = mutableListOf<Pair<Int, Int>>()
    intervals.forEach { (start, end) ->
        /** Start minute. */
        val startMinute = minuteOfDay(start)
        /** End minute. */
        val endMinute = minuteOfDay(end).coerceAtLeast(startMinute + 1)
        events.add(startMinute to 1)
        events.add(endMinute to -1)
    }
    /** Sorted events. */
    val sortedEvents = events.sortedWith(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
    /** If. */
    if (sortedEvents.isEmpty()) {
        return emptyList()
    }

    /** Overlaps. */
    val overlaps = mutableListOf<TimeOverlap>()
    /** Active. */
    var active = 0
    /** Previous minute. */
    var previousMinute = sortedEvents.first().first
    sortedEvents.forEach { (minute, delta) ->
        /** If. */
        if (minute > previousMinute && active >= 2) {
            /** Overlap minutes. */
            val overlapMinutes = minute - previousMinute
            overlaps.add(
                /** Time overlap. */
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
    /** Selected date. */
    selectedDate: LocalDate,
    /** Occurrence. */
    occurrence: TaskOccurrence,
    task: Task?,
    /** Fallback index. */
    fallbackIndex: Int,
    /** Fallback total. */
    fallbackTotal: Int,
    /** Default duration minutes. */
    defaultDurationMinutes: Int,
): MinuteWindow {
    /** Duration minutes. */
    val durationMinutes = task?.durationMinutes?.takeIf { it > 0 } ?: defaultDurationMinutes
    /** Explicit occurrence minutes. */
    val explicitOccurrenceMinutes = parseIsoDateTime(occurrence.occurrenceDate)
        ?.takeIf { occurrence.occurrenceDate.contains("T") && it.toLocalDate() == selectedDate }
        ?.let { it.hour * 60 + it.minute }
        ?.takeUnless { minutes ->
            minutes == 0 && occurrence.completedAt == null && occurrence.actualCompletedAt == null
        }
    /** Completed at minutes. */
    val completedAtMinutes = parseIsoDateTime(occurrence.completedAt)
        ?.takeIf { it.toLocalDate() == selectedDate }
        ?.let { it.hour * 60 + it.minute }
    /** Actual completed at minutes. */
    val actualCompletedAtMinutes = occurrence.actualCompletedAt
        ?.takeIf { it.toLocalDate() == selectedDate }
        ?.let { it.hour * 60 + it.minute }
    /** Task due minutes. */
    val taskDueMinutes = task?.dueDate?.let { it.hour * 60 + it.minute }

    /** Due minutes. */
    val dueMinutes = taskDueMinutes
        ?: explicitOccurrenceMinutes
        ?: completedAtMinutes
        ?: actualCompletedAtMinutes
        ?: distributedFallbackAnchor(fallbackIndex, fallbackTotal)
    /** Safe due minutes. */
    val safeDueMinutes = dueMinutes.coerceIn(0, 1439)
    /** Start minutes. */
    val startMinutes = (safeDueMinutes - (durationMinutes / 2)).coerceAtLeast(0)
    /** End minutes. */
    val endMinutes = (startMinutes + durationMinutes).coerceAtMost(1440).coerceAtLeast(startMinutes + 1)
    return MinuteWindow(
        startMinutes = startMinutes,
        endMinutes = endMinutes,
    )
}

private fun buildDayBoundIntervals(
    /** Selected date. */
    selectedDate: LocalDate,
    entries: List<TimeEntry>,
    activeEntry: TimeEntry?,
    /** Now. */
    now: LocalDateTime,
): List<Pair<LocalDateTime, LocalDateTime>> {
    /** Day start. */
    val dayStart = LocalDateTime.of(selectedDate, LocalTime.MIDNIGHT)
    /** Day end. */
    val dayEnd =
        /** If. */
        if (selectedDate == now.toLocalDate()) {
            /** Now. */
            now
        } else {
            LocalDateTime.of(
                /** Selected date. */
                selectedDate,
                LocalTime.of(23, 59, 59),
            )
        }

    /** All entries. */
    val allEntries = entries.toMutableList()
    /** If. */
    if (activeEntry != null && allEntries.none { it.id == activeEntry.id }) {
        allEntries.add(activeEntry)
    }
    return allEntries.mapNotNull { entry ->
        /** Start. */
        val start = if (entry.startedAt.isAfter(dayStart)) entry.startedAt else dayStart
        /** Raw end. */
        val rawEnd = entry.endedAt ?: if (selectedDate == now.toLocalDate()) now else dayEnd
        /** End. */
        val end = if (rawEnd.isBefore(dayEnd)) rawEnd else dayEnd
        /** If. */
        if (end.isAfter(start)) start to end else null
    }
}

private fun minuteOfDay(time: LocalDateTime): Int = (time.hour * 60 + time.minute).coerceIn(0, 1440)

private fun distributedFallbackAnchor(index: Int, total: Int): Int {
    /** Safe total. */
    val safeTotal = total.coerceAtLeast(1)
    /** Safe index. */
    val safeIndex = index.coerceAtLeast(0)
    /** Slot. */
    val slot = (safeIndex % safeTotal) + 1
    /** Return. */
    return ((slot * 1440.0) / (safeTotal + 1)).toInt().coerceIn(0, 1439)
}

private fun parseIsoDateTime(value: String?): LocalDateTime? {
    /** If. */
    if (value.isNullOrBlank()) return null
    /** Normalized. */
    val normalized = value.trim().removeSuffix("Z")
    return runCatching { LocalDateTime.parse(normalized) }
        .recoverCatching { LocalDate.parse(normalized).atStartOfDay() }
        .getOrNull()
}

internal fun toMutedPastelColor(baseColor: Color, surfaceColor: Color): Color = lerp(baseColor, surfaceColor, 0.28f)
