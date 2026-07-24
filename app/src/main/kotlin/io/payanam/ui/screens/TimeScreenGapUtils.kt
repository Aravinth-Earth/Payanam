//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import io.payanam.common.logging.UnifiedLogger
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal fun resolveGapConvertDateTimeRange(
    selectedDate: LocalDate,
    gapStartTime: LocalTime,
    gapEndTime: LocalTime,
    lastEntryEndDateTime: LocalDateTime?,
): Pair<LocalDateTime, LocalDateTime> {
    val logger = UnifiedLogger.getInstance()
    val dayStart = selectedDate.atStartOfDay()
    val bridgedStart = if (gapStartTime == LocalTime.MIDNIGHT) {
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
