//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.repository

import io.payanam.common.logging.UnifiedLogger
import java.time.LocalDate
/**
 * The rolling time windows an average-daily-time table can report against,
 * named by their calendar span. [minCalendarDays] is the minimum history the
 * window needs to be meaningful.
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
 * The kind of row in an average-daily-time table: a specific life
 * [DIMENSION], [UNASSIGNED] time, or [UNTRACKED] time.
 */
enum class AverageDailyTimeRowType {
    DIMENSION,
    UNASSIGNED,
    UNTRACKED,
}
/**
 * One row of an average-daily-time table: a [rowType] (dimension, unassigned,
 * or untracked) plus its average tracked minutes per [AverageDailyTimeWindow].
 */
data class AverageDailyTimeRow(
    val rowType: AverageDailyTimeRowType,
    val dimensionId: String? = null,
    val averageMinutesByWindow: Map<AverageDailyTimeWindow, Double>,
)
/**
 * The full average-daily-time report: the tracked-date span, the
 * [visibleWindows] requested, and one [AverageDailyTimeRow] per row type.
 */
data class AverageDailyTimeTableData(
    val firstTrackedDate: LocalDate,
    val asOfDate: LocalDate,
    val totalCalendarDays: Int,
    val visibleWindows: List<AverageDailyTimeWindow>,
    val rows: List<AverageDailyTimeRow>,
)
/**
 * Emits a debug summary of this table (date span, windows, row count) through
 * [logger] for traceability of the Lenses "average day" computation.
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
