//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val lensRangeLogger = UnifiedLogger.getInstance()

internal fun buildLensDatesForRange(
    rangeStartDate: LocalDate,
    rangeEndDate: LocalDate,
    maxRangeDays: Int,
): List<LocalDate> {
    val startDate = minOf(rangeStartDate, rangeEndDate)
    val endDate = maxOf(rangeStartDate, rangeEndDate)
    val totalDays = ChronoUnit.DAYS.between(startDate, endDate).toLong() + 1

    val normalizedStart = if (totalDays > maxRangeDays.toLong()) {
        lensRangeLogger.w(
            "LensRangeDateUtils.buildLensDatesForRange",
            "Range too large, truncating",
            mapOf("requestedDays" to totalDays, "maxDays" to maxRangeDays),
        )
        endDate.minusDays((maxRangeDays - 1).toLong())
    } else {
        startDate
    }

    val dates = mutableListOf<LocalDate>()
    var cursor = normalizedStart
    while (!cursor.isAfter(endDate)) {
        dates.add(cursor)
        cursor = cursor.plusDays(1)
    }
    return dates
}
