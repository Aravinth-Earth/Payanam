//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private val lensRangeLogger = UnifiedLogger.getInstance()

internal fun buildLensDatesForRange(
    /** Range start date. */
    rangeStartDate: LocalDate,
    /** Range end date. */
    rangeEndDate: LocalDate,
    /** Max range days. */
    maxRangeDays: Int,
): List<LocalDate> {
    /** Start date. */
    val startDate = minOf(rangeStartDate, rangeEndDate)
    /** End date. */
    val endDate = maxOf(rangeStartDate, rangeEndDate)
    /** Total days. */
    val totalDays = ChronoUnit.DAYS.between(startDate, endDate).toLong() + 1

    /** Normalized start. */
    val normalizedStart = if (totalDays > maxRangeDays.toLong()) {
        lensRangeLogger.w(
            "LensRangeDateUtils.buildLensDatesForRange",
            "Range too large, truncating",
            /** Map of. */
            mapOf("requestedDays" to totalDays, "maxDays" to maxRangeDays),
        )
        endDate.minusDays((maxRangeDays - 1).toLong())
    } else {
        /** Start date. */
        startDate
    }

    /** Dates. */
    val dates = mutableListOf<LocalDate>()
    /** Cursor. */
    var cursor = normalizedStart
    /** While. */
    while (!cursor.isAfter(endDate)) {
        dates.add(cursor)
        cursor = cursor.plusDays(1)
    }
    return dates
}
