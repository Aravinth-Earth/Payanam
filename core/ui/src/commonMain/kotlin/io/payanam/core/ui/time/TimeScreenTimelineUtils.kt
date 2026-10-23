//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.core.ui.time

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.payanam.domain.model.Task
import io.payanam.domain.model.TaskOccurrence
import io.payanam.domain.model.TimeEntry
import io.payanam.shared.logging.AppLogger
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private const val GAP_MINUTES_THRESHOLD = 1
private val logger = AppLogger.current

/**
 * An untracked stretch of the day, in whole minutes from midnight.
 *
 * [computeTimeGaps] is the only producer, and there [minutes] is at least 1 and always equals
 * `endMinutes - startMinutes`. The type itself enforces neither: a hand-built instance can hold any
 * minute values at all.
 */
data class TimeGap(
    val startMinutes: Int,
    val endMinutes: Int,
    val minutes: Int,
)

/**
 * A stretch of the day covered by two or more tracked entries at once, in whole minutes from
 * midnight.
 *
 * [computeTimeOverlaps] is the only producer, and there [minutes] is at least 1 and always equals
 * `endMinutes - startMinutes`. The type itself enforces neither: a hand-built instance can hold any
 * minute values at all.
 */
data class TimeOverlap(
    val startMinutes: Int,
    val endMinutes: Int,
    val minutes: Int,
)

/**
 * A half-open span of the day, `[startMinutes, endMinutes)`, in minutes from midnight.
 *
 * Half-open because the timeline draws the span by its end minus its start
 * (`minuteHeight * (endMinutes - startMinutes)`), so a window ending where the next begins butts
 * against it rather than double-drawing the shared minute. [resolveOccurrenceWindowMinutes] is the
 * only producer, and there `endMinutes` is at most 1440 and always strictly greater than
 * [startMinutes]. The type itself enforces neither bound.
 */
data class MinuteWindow(
    val startMinutes: Int,
    val endMinutes: Int,
)

/**
 * Finds the stretches of [selectedDate] that no tracked entry covers, in minutes from midnight.
 *
 * Each entry is first clipped to the day: a start before midnight becomes midnight, an end after the
 * day's end becomes that end, and an entry with a null [TimeEntry.endedAt] (the active session) is
 * treated as running to [now]. An [activeEntry] is measured in addition to [entries], not instead
 * of them, unless [entries] already holds an entry with the same id - so passing both the list and
 * its own active member does not double-count. An entry that clips to zero length, or to zero
 * length because it lies wholly outside the day, is dropped; the count dropped is logged as a
 * warning rather than surfaced here.
 *
 * The surviving intervals are swept in start order, so entries may be passed unsorted, and each
 * interval is a union step: a second entry nested inside the first's span adds no gap. Overlapping
 * entries therefore never produce a gap between them. Entries that merely touch, where one ends
 * exactly where the next starts, are not a gap either.
 *
 * Only gaps of at least 1 minute are returned, so a fully booked day yields an empty list rather
 * than a list of sub-minute slivers. Returned in ascending [TimeGap.startMinutes] order.
 *
 * @param now "now" for the screen, passed in rather than read from the clock so the result is
 *   reproducible: on [selectedDate]'s own day it caps the sweep, on any other day it does not.
 * @return the untracked stretches, each at least 1 minute long. Empty when every entry is invalid
 *   or the day is fully covered.
 */
fun computeTimeGaps(
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
    val intervals =
        allEntries
            .mapNotNull { entry ->
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

/**
 * Finds the stretches of [selectedDate] covered by two or more tracked entries simultaneously, in
 * minutes from midnight.
 *
 * Day clipping matches [computeTimeGaps]: an [activeEntry] is counted in addition to [entries]
 * unless already present by id, an entry with a null [TimeEntry.endedAt] runs to [now], entries
 * wholly outside the day or clipping to zero length are dropped, and no gap warning is logged here
 * because a dropped entry is not a gap.
 *
 * Overlap is decided on the minute grid, so it is approximate at the edges: each entry becomes a
 * `[start, end]` pair of minutes and the pair is widened to at least one minute, which means an entry
 * starting and ending inside the same minute is still reported as covering that minute. Endpoints
 * that merely touch, where one entry ends exactly on the minute the next begins, do NOT count as an
 * overlap - the shared minute is where one entry stops and the other starts. Time is counted once
 * however deep the pile-up, so three entries sharing a span is one [TimeOverlap] of that span, not
 * two or three.
 *
 * Adjacent overlaps are not merged: a third entry that bridges two separate overlaps yields two
 * [TimeOverlap]s, because a span covered by two entries followed by a different span covered by two
 * is two events, not one.
 *
 * @param now "now" for the screen, passed in rather than read from the clock so the result is
 *   reproducible. See [computeTimeGaps].
 * @return the multiply-covered stretches in ascending [TimeOverlap.startMinutes] order, each at
 *   least 1 minute long. Empty when fewer than two entries survive day clipping - one entry can
 *   never overlap itself, so a single-entry day reports nothing.
 */
fun computeTimeOverlaps(
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

/**
 * Resolves where on the day a past task occurrence should be drawn, in minutes from midnight.
 *
 * Recurring occurrences frequently carry no usable time of their own - the date is recorded, the
 * clock time often is not - so this picks the best anchor available and backs the block out from
 * there by half its duration, which puts the block's midpoint on the anchor rather than its start.
 * The anchor is the first of these that resolves:
 *
 *  1. the parent task's [Task.dueDate] time of day;
 *  2. the time in [TaskOccurrence.occurrenceDate], but only when that string carries an explicit
 *     `T` time part and falls on [selectedDate]. A bare date is not an anchor, and a time belonging
 *     to another day is not this day's block. A midnight sentinel (exactly 00:00 on an occurrence
 *     with no completion recorded) is treated as no anchor at all, since midnight on a day-only
 *     record means "no time given", not "done at midnight";
 *  3. [TaskOccurrence.completedAt], if it falls on [selectedDate];
 *  4. [TaskOccurrence.actualCompletedAt], if it falls on [selectedDate];
 *  5. a slot handed out by [fallbackIndex] among [fallbackTotal] so that several timed-out
 *     occurrences on one day are spread across it instead of stacking at the same spot.
 *
 * Unparseable date-time strings are treated as absent rather than raised. Note the task's due date is
 * taken as a time of day only and is not itself checked against [selectedDate] - the caller has
 * already decided this occurrence belongs to this day.
 *
 * The window's length is the parent task's [Task.durationMinutes] when that is a positive number, and
 * [defaultDurationMinutes] otherwise, so a task whose duration is unset or non-positive still gets a
 * drawable block instead of a zero-height one.
 *
 * @param fallbackIndex this occurrence's position among the day's undated ones, used only when no
 *   real anchor exists. Negative values are read as 0.
 * @param fallbackTotal how many undated occurrences the day has, used only when no real anchor
 *   exists. Zero or negative is read as 1, so the fallback still yields a slot.
 * @return a window inside `[0, 1440]` that is never zero-length: the start is pushed forward of
 *   midnight and the end pulled back of it as needed, and if clamping would otherwise collapse the
 *   window the end is forced one minute past the start - a block on screen has to have a height.
 */
fun resolveOccurrenceWindowMinutes(
    selectedDate: LocalDate,
    occurrence: TaskOccurrence,
    task: Task?,
    fallbackIndex: Int,
    fallbackTotal: Int,
    defaultDurationMinutes: Int,
): MinuteWindow {
    val durationMinutes = task?.durationMinutes?.takeIf { it > 0 } ?: defaultDurationMinutes
    val explicitOccurrenceMinutes =
        parseIsoDateTime(occurrence.occurrenceDate)
            ?.takeIf { occurrence.occurrenceDate.contains("T") && it.toLocalDate() == selectedDate }
            ?.let { it.hour * 60 + it.minute }
            ?.takeUnless { minutes ->
                minutes == 0 && occurrence.completedAt == null && occurrence.actualCompletedAt == null
            }
    val completedAtMinutes =
        parseIsoDateTime(occurrence.completedAt)
            ?.takeIf { it.toLocalDate() == selectedDate }
            ?.let { it.hour * 60 + it.minute }
    val actualCompletedAtMinutes =
        occurrence.actualCompletedAt
            ?.takeIf { it.toLocalDate() == selectedDate }
            ?.let { it.hour * 60 + it.minute }
    val taskDueMinutes = task?.dueDate?.let { it.hour * 60 + it.minute }
    val dueMinutes =
        taskDueMinutes
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

private fun distributedFallbackAnchor(
    index: Int,
    total: Int,
): Int {
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

/**
 * Mutes a strong block colour into a pastel tint, so a day of many blocks reads as a calendar rather
 * than as a wall of saturated chips.
 *
 * The returned colour is [baseColor] moved 28% of the way toward [surfaceColor] - the caller passes
 * the theme's surface, so the tint lands close to the background it sits on instead of fighting it.
 * Both inputs are Compose [Color] values and the result is one too, in the same colour space as
 * [baseColor]; the interpolation runs in a perceptual space, so a muted colour stays a muted version
 * of the same hue instead of drifting through a different one the way a straight RGB average does.
 * Alpha is interpolated along with the channels, so a translucent [baseColor] comes back slightly more
 * transparent again. The fraction is fixed, not a parameter: every block on the timeline is muted the
 * same amount, and a per-call fraction would make two blocks of one dimension look like two
 * dimensions.
 */
fun toMutedPastelColor(
    baseColor: Color,
    surfaceColor: Color,
): Color = lerp(baseColor, surfaceColor, 0.28f)
