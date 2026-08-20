//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.repository.AverageDailyTimeRow
import io.payanam.domain.repository.AverageDailyTimeRowType
import io.payanam.domain.repository.AverageDailyTimeTableData
import io.payanam.domain.repository.AverageDailyTimeWindow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
/**
 * LensesTimeAverageDailySectionTest.
 */
class LensesTimeAverageDailySectionTest {
    @Before
    /**
     * Set up.
     */
    fun setUp() {
        /** Context. */
        val context = ApplicationProvider.getApplicationContext<Context>()
        /** If. */
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    /**
     * Average daily time column totals sums all rows per window.
     */
    fun averageDailyTimeColumnTotals_sums_all_rows_per_window() {
        /** Summary. */
        val summary =
            /** Average daily time table data. */
            AverageDailyTimeTableData(
                firstTrackedDate = LocalDate.of(2026, 4, 1),
                asOfDate = LocalDate.of(2026, 4, 2),
                totalCalendarDays = 2,
                visibleWindows = listOf(AverageDailyTimeWindow.TODAY_SO_FAR, AverageDailyTimeWindow.ALL_DAYS),
                rows = listOf(
                    /** Average daily time row. */
                    AverageDailyTimeRow(
                        rowType = AverageDailyTimeRowType.DIMENSION,
                        dimensionId = "career_work",
                        averageMinutesByWindow = mapOf(
                            AverageDailyTimeWindow.TODAY_SO_FAR to 30.0,
                            AverageDailyTimeWindow.ALL_DAYS to 45.0,
                        ),
                    ),
                    /** Average daily time row. */
                    AverageDailyTimeRow(
                        rowType = AverageDailyTimeRowType.UNASSIGNED,
                        averageMinutesByWindow = mapOf(
                            AverageDailyTimeWindow.TODAY_SO_FAR to 10.0,
                            AverageDailyTimeWindow.ALL_DAYS to 5.0,
                        ),
                    ),
                    /** Average daily time row. */
                    AverageDailyTimeRow(
                        rowType = AverageDailyTimeRowType.UNTRACKED,
                        averageMinutesByWindow = mapOf(
                            AverageDailyTimeWindow.TODAY_SO_FAR to 20.0,
                            AverageDailyTimeWindow.ALL_DAYS to 700.0,
                        ),
                    ),
                ),
            )

        /** Totals. */
        val totals = averageDailyTimeColumnTotals(summary)

        /** Assert equals. */
        assertEquals(60.0, totals[AverageDailyTimeWindow.TODAY_SO_FAR] ?: 0.0, 0.0)
        /** Assert equals. */
        assertEquals(750.0, totals[AverageDailyTimeWindow.ALL_DAYS] ?: 0.0, 0.0)
    }

    @Test
    /**
     * Average daily time cell share returns ratio with clamp and safe zero.
     */
    fun averageDailyTimeCellShare_returns_ratio_with_clamp_and_safe_zero() {
        /** Assert equals. */
        assertEquals(0.5, averageDailyTimeCellShare(minutes = 30.0, columnTotalMinutes = 60.0), 0.0)
        /** Assert equals. */
        assertEquals(1.0, averageDailyTimeCellShare(minutes = 90.0, columnTotalMinutes = 60.0), 0.0)
        /** Assert equals. */
        assertEquals(0.0, averageDailyTimeCellShare(minutes = 10.0, columnTotalMinutes = 0.0), 0.0)
        /** Assert equals. */
        assertEquals(0.0, averageDailyTimeCellShare(minutes = Double.NaN, columnTotalMinutes = 60.0), 0.0)
    }
}
