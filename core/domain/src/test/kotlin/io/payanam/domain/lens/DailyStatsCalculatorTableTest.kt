//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.lens

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.model.TimeEntry
import io.payanam.domain.repository.AverageDailyTimeRowType
import io.payanam.domain.repository.AverageDailyTimeWindow
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Tests for [DailyStatsCalculator.calculateAverageDailyTimeTable] -- the largest single body in the lens
 * analytics layer, and the one the module's coverage floor was most sensitive to when the layer moved out of
 * the Android module.
 *
 * The table is a report over a DATE SPAN, so every case here is about the span: which windows the history is
 * long enough to show, which days the averages divide by, and what happens to entries outside the span.
 */
class DailyStatsCalculatorTableTest {
    private val today: LocalDate = LocalDate.now()
    private val noon: LocalDateTime = today.atTime(12, 0)

    private fun entry(
        startedAt: LocalDateTime,
        endedAt: LocalDateTime?,
        dimensionId: String? = "dim_work_livelihood",
        focusRating: Double? = null,
    ) = TimeEntry(
        id = "entry-${startedAt}-${dimensionId ?: "none"}",
        lifeIntentionCategory = "Work",
        startedAt = startedAt,
        endedAt = endedAt,
        focusRating = focusRating,
        dimensionId = dimensionId,
        createdAt = startedAt,
        updatedAt = startedAt,
    )

    @Test
    fun `no entries means no table`() {
        assertThat(DailyStatsCalculator.calculateAverageDailyTimeTable(emptyList(), noon)).isNull()
    }

    @Test
    fun `an as-of date before the first tracked day means no table`() {
        val future = entry(today.plusDays(3).atTime(9, 0), today.plusDays(3).atTime(10, 0))

        assertThat(DailyStatsCalculator.calculateAverageDailyTimeTable(listOf(future), noon)).isNull()
    }

    @Test
    fun `a single day of history exposes only the windows one day can fill`() {
        val table =
            DailyStatsCalculator.calculateAverageDailyTimeTable(
                listOf(entry(today.atTime(9, 0), today.atTime(11, 0))),
                noon,
            )!!

        assertThat(table.firstTrackedDate).isEqualTo(today)
        assertThat(table.asOfDate).isEqualTo(today)
        assertThat(table.totalCalendarDays).isEqualTo(1)
        // TODAY_SO_FAR and ALL_DAYS both need one calendar day; nothing longer can be honest about 1 day.
        assertThat(table.visibleWindows).containsExactly(
            AverageDailyTimeWindow.TODAY_SO_FAR,
            AverageDailyTimeWindow.ALL_DAYS,
        )
        assertThat(table.visibleWindows).doesNotContain(AverageDailyTimeWindow.LAST_7_DAYS)
        assertThat(table.rows).isNotEmpty()
    }

    @Test
    fun `a long history exposes the longer windows`() {
        val entries =
            (0..40).map { day ->
                val date = today.minusDays(day.toLong())
                entry(date.atTime(9, 0), date.atTime(10, 0))
            }

        val table = DailyStatsCalculator.calculateAverageDailyTimeTable(entries, noon)!!

        assertThat(table.totalCalendarDays).isEqualTo(41)
        assertThat(table.visibleWindows).contains(AverageDailyTimeWindow.LAST_7_DAYS)
        assertThat(table.visibleWindows).contains(AverageDailyTimeWindow.LAST_30_DAYS)
        assertThat(table.visibleWindows).doesNotContain(AverageDailyTimeWindow.LAST_90_DAYS)
        assertThat(table.visibleWindows).contains(AverageDailyTimeWindow.ALL_DAYS)
    }

    @Test
    fun `each tracked dimension gets its own row averaged over the span`() {
        val entries =
            listOf(
                entry(today.atTime(9, 0), today.atTime(11, 0), dimensionId = "dim_work_livelihood"),
                entry(today.atTime(14, 0), today.atTime(15, 0), dimensionId = "dim_physical_health"),
            )

        val table = DailyStatsCalculator.calculateAverageDailyTimeTable(entries, noon)!!

        val dimensionRows = table.rows.filter { it.rowType == AverageDailyTimeRowType.DIMENSION }
        assertThat(dimensionRows.mapNotNull { it.dimensionId })
            .containsExactly("dim_work_livelihood", "dim_physical_health")
        // 120 minutes and 60 minutes over a one-day span.
        val allDays = AverageDailyTimeWindow.ALL_DAYS
        assertThat(dimensionRows.single { it.dimensionId == "dim_work_livelihood" }.averageMinutesByWindow[allDays])
            .isEqualTo(120.0)
        assertThat(dimensionRows.single { it.dimensionId == "dim_physical_health" }.averageMinutesByWindow[allDays])
            .isEqualTo(60.0)
    }

    @Test
    fun `a dimension-less entry is reported under the unassigned row`() {
        val entries =
            listOf(
                entry(today.atTime(9, 0), today.atTime(10, 0), dimensionId = "dim_work_livelihood"),
                entry(today.atTime(14, 0), today.atTime(15, 0), dimensionId = null),
            )

        val table = DailyStatsCalculator.calculateAverageDailyTimeTable(entries, noon)!!

        // A blank dimension is not folded into a real dimension: it keeps the row the UI labels "unassigned".
        val ids = table.rows.mapNotNull { it.dimensionId }.toSet()
        assertThat(ids).contains("dim_work_livelihood")
        assertThat(table.rows.size).isGreaterThan(1)
    }

    @Test
    fun `a running entry counts its elapsed time and still extends the span`() {
        val completed = entry(today.minusDays(1).atTime(9, 0), today.minusDays(1).atTime(10, 0))
        val running = entry(today.atTime(9, 0), endedAt = null)

        // The as-of time is fixed at noon, so the running session has elapsed exactly three hours.
        val table = DailyStatsCalculator.calculateAverageDailyTimeTable(listOf(completed, running), noon)!!

        // A session in progress is real time already spent: it is counted for the elapsed minutes, and the
        // span it reaches into is part of the report.
        assertThat(table.firstTrackedDate).isEqualTo(today.minusDays(1))
        assertThat(table.totalCalendarDays).isEqualTo(2)
        val row = table.rows.first { it.rowType == AverageDailyTimeRowType.DIMENSION }
        assertThat(row.averageMinutesByWindow[AverageDailyTimeWindow.TODAY_SO_FAR]).isEqualTo(180.0)
        assertThat(row.averageMinutesByWindow[AverageDailyTimeWindow.YESTERDAY]).isEqualTo(60.0)
        assertThat(row.averageMinutesByWindow[AverageDailyTimeWindow.ALL_DAYS]).isEqualTo(120.0)
        // The remainder of the day is reported beside the tracked rows rather than inside them.
        assertThat(table.rows.map { it.rowType }).contains(AverageDailyTimeRowType.UNTRACKED)
    }

    @Test
    fun `the span is divided by calendar days, not by days that were tracked`() {
        val entries =
            listOf(
                entry(today.minusDays(4).atTime(9, 0), today.minusDays(4).atTime(10, 0)),
                entry(today.atTime(9, 0), today.atTime(10, 0)),
            )

        val table = DailyStatsCalculator.calculateAverageDailyTimeTable(entries, noon)!!

        assertThat(table.totalCalendarDays).isEqualTo(5)
        // 120 tracked minutes across a 5-day span is 24 a day -- a missed day counts as zero, which is the
        // point of an "average day" table.
        val row = table.rows.first { it.rowType == AverageDailyTimeRowType.DIMENSION }
        assertThat(row.averageMinutesByWindow[AverageDailyTimeWindow.ALL_DAYS]).isEqualTo(24.0)
    }
}
