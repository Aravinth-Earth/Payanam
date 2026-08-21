//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.common.logging.UnifiedLogger
import java.time.LocalDate

/**
 * AverageDailyTimeWindow.
 */
enum class AverageDailyTimeWindow(
    val minCalendarDays: Int,
) {
    TODAY_SO_FAR(1),
    YESTERDAY(2),
    LAST_7_DAYS(7),
    LAST_30_DAYS(30),
    LAST_90_DAYS(90),
    LAST_180_DAYS(180),
    LAST_365_DAYS(365),
    ALL_DAYS(1),
}

/**
 * AverageDailyTimeRowType.
 */
enum class AverageDailyTimeRowType {
    DIMENSION,
    UNASSIGNED,
    UNTRACKED,
}

/**
 * AverageDailyTimeRow.
 */
data class AverageDailyTimeRow(
    val rowType: AverageDailyTimeRowType,
    val dimensionId: String? = null,
    val averageMinutesByWindow: Map<AverageDailyTimeWindow, Double>,
)

/**
 * AverageDailyTimeTableData.
 */
data class AverageDailyTimeTableData(
    val firstTrackedDate: LocalDate,
    val asOfDate: LocalDate,
    val totalCalendarDays: Int,
    val visibleWindows: List<AverageDailyTimeWindow>,
    val rows: List<AverageDailyTimeRow>,
)

/**
 * Average daily time table data.
 */
fun AverageDailyTimeTableData.logSummary(logger: UnifiedLogger) {
    logger.d(
        "AverageDailyTimeTableData.logSummary",
        "Prepared average daily time table",
        mapOf(
            "firstTrackedDate" to firstTrackedDate.toString(),
            "asOfDate" to asOfDate.toString(),
            "totalCalendarDays" to totalCalendarDays,
            "visibleWindows" to visibleWindows.joinToString(",") { it.name },
            "rowCount" to rows.size,
        ),
    )
}
