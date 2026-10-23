//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.time

import io.payanam.shared.logging.AppLogger
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Turns a tapped gap's start and end clock times into the absolute range the modal will edit.
 *
 * The returned pair is the start and end of an untracked window, start inclusive and end exclusive in
 * the sense that the end is the instant the block is proposed to finish: it is always strictly after
 * the start, so a zero-length or inverted tap is repaired rather than passed through. A wrapped gap
 * whose end time is at or before its start is read as running past midnight, so the end moves to the
 * next day and the range keeps its real length instead of collapsing to nothing. Sub-minute gaps are
 * not filtered here - the caller's minimum-gap threshold is what decides whether a tap is a gap at
 * all, and this function preserves whatever window it is handed.
 *
 * The one day-crossing case it repairs is a gap that starts at midnight. That start usually means "the
 * day began untracked" rather than a window starting at 00:00, so when the day's last entry ended on a
 * previous day within 24 hours, the range is bridged to start where that entry ended and the
 * untouched time between is included. A bridged end is then rolled forward if the bridge pushed the
 * start past it, and a null [lastEntryEndDateTime], an entry that ended on the selected day itself, or
 * one that ended more than a day earlier all leave midnight as the start.
 */
fun resolveGapConvertDateTimeRange(
    selectedDate: LocalDate,
    gapStartTime: LocalTime,
    gapEndTime: LocalTime,
    lastEntryEndDateTime: LocalDateTime?,
): Pair<LocalDateTime, LocalDateTime> {
    val logger = AppLogger.current
    val dayStart = selectedDate.atStartOfDay()
    val bridgedStart =
        if (gapStartTime == LocalTime.MIDNIGHT) {
            lastEntryEndDateTime?.takeIf { endedAt ->
                endedAt.isBefore(dayStart) && Duration.between(endedAt, dayStart).toHours() <= 24
            }
        } else {
            null
        }
    val startDateTime = bridgedStart ?: LocalDateTime.of(selectedDate, gapStartTime)
    var endDateTime = LocalDateTime.of(selectedDate, gapEndTime)
    if (!endDateTime.isAfter(startDateTime)) {
        endDateTime = endDateTime.plusDays(1)
    }
    logger.d(
        "TimeScreenGapUtils.resolveGapConvertDateTimeRange",
        "Resolved gap range",
        mapOf(
            "selectedDate" to selectedDate.toString(),
            "start" to startDateTime.toString(),
            "end" to endDateTime.toString(),
        ),
    )
    return startDateTime to endDateTime
}
