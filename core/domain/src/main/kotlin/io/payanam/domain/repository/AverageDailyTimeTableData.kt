//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.common.logging.UnifiedLogger
import java.time.LocalDate

/**
 * AverageDailyTimeWindow.
 */
enum class AverageDailyTimeWindow(
    /** Min calendar days. */
    val minCalendarDays: Int,
) {
    /** Today so far. */
    TODAY_SO_FAR(1),
    /** Yesterday. */
    YESTERDAY(2),
    /** Last 7 days. */
    LAST_7_DAYS(7),
    /** Last 30 days. */
    LAST_30_DAYS(30),
    /** Last 90 days. */
    LAST_90_DAYS(90),
    /** Last 180 days. */
    LAST_180_DAYS(180),
    /** Last 365 days. */
    LAST_365_DAYS(365),
    /** All days. */
    ALL_DAYS(1),
}

/**
 * AverageDailyTimeRowType.
 */
enum class AverageDailyTimeRowType {
    /** Dimension. */
    DIMENSION,
    /** Unassigned. */
    UNASSIGNED,
    /** Untracked. */
    UNTRACKED,
}

/**
 * AverageDailyTimeRow.
 */
data class AverageDailyTimeRow(
    /** Row type. */
    val rowType: AverageDailyTimeRowType,
    /** Dimension id. */
    val dimensionId: String? = null,
    /** Average minutes by window. */
    val averageMinutesByWindow: Map<AverageDailyTimeWindow, Double>,
)

/**
 * AverageDailyTimeTableData.
 */
data class AverageDailyTimeTableData(
    /** First tracked date. */
    val firstTrackedDate: LocalDate,
    /** As of date. */
    val asOfDate: LocalDate,
    /** Total calendar days. */
    val totalCalendarDays: Int,
    /** Visible windows. */
    val visibleWindows: List<AverageDailyTimeWindow>,
    /** Rows. */
    val rows: List<AverageDailyTimeRow>,
)

/**
 * Average daily time table data.
 */
fun AverageDailyTimeTableData.logSummary(logger: UnifiedLogger) {
    logger.d(
        "AverageDailyTimeTableData.logSummary",
        "Prepared average daily time table",
        /** Map of. */
        mapOf(
            "firstTrackedDate" to firstTrackedDate.toString(),
            "asOfDate" to asOfDate.toString(),
            "totalCalendarDays" to totalCalendarDays,
            "visibleWindows" to visibleWindows.joinToString(",") { it.name },
            "rowCount" to rows.size,
        ),
    )
}
