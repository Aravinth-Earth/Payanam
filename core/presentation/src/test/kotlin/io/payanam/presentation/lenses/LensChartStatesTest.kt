//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.repository.HeatmapDayData
import org.junit.Test
import java.time.LocalDate

/**
 * The chart states and the range summary, covering the shapes the Lenses screens read.
 *
 * [LensRangeSummary] is the one worth constructing in full: it is the payload every Lenses section reads, and
 * its defaulted fields are what let a partially-loaded range render. The two counts it carries in pairs —
 * planned vs completed vs missed, for tasks and for habits — are asserted to be independent fields rather
 * than derived, because a section that showed "missed = planned - completed" would report a task with no
 * completion record as missed.
 */
class LensChartStatesTest {
    @Test
    fun `a fresh chart state is not loading and holds nothing`() {
        assertThat(DimensionTrendState().isLoading).isFalse()
        assertThat(DimensionTrendState().window).isEqualTo(DimensionTrendWindow.W1)
        assertThat(DimensionTrendState().blocks).isEmpty()
        assertThat(HeatmapState().isLoading).isFalse()
        assertThat(HeatmapState().days).isEmpty()
        assertThat(WeekGridState().isLoading).isFalse()
        assertThat(WeekGridState().data.days).isEmpty()
        assertThat(MinutePatternState().isLoading).isFalse()
        assertThat(MinutePatternState().data.days).isEmpty()
    }

    @Test
    fun `a loaded heatmap keeps the days it was given`() {
        val day = HeatmapDayData(dayKey = "2026-09-20", segments = emptyList())
        val state = HeatmapState(days = listOf(day), isLoading = false)

        assertThat(state.days).hasSize(1)
        assertThat(state.days.first().dayKey).isEqualTo("2026-09-20")
        assertThat(state.days.first().segments).isEmpty()
    }

    @Test
    fun `a range summary keeps plan, completion and miss counts as separate fields`() {
        val start = LocalDate.of(2026, 9, 14)
        val summary = rangeSummary(start)

        assertThat(summary.mode).isEqualTo(LensTimeMode.PAST)
        assertThat(summary.window).isEqualTo(LensTimeWindow.LAST_7_DAYS)
        assertThat(summary.pageIndex).isEqualTo(0)
        assertThat(summary.plannedTaskCount).isEqualTo(10)
        assertThat(summary.completedTaskCount).isEqualTo(7)
        assertThat(summary.missedTaskCount).isEqualTo(2)
        assertThat(summary.plannedHabitCount).isEqualTo(4)
        assertThat(summary.completedHabitCount).isEqualTo(4)
        assertThat(summary.missedHabitCount).isEqualTo(0)
        assertThat(summary.plannedTaskCount - summary.completedTaskCount)
            .isNotEqualTo(summary.missedTaskCount)
    }

    @Test
    fun `a range summary's defaulted roll-ups are empty until loaded`() {
        val summary = rangeSummary(LocalDate.of(2026, 9, 14))

        assertThat(summary.supplementalActualMinutes).isEqualTo(0)
        assertThat(summary.supplementalActualByDimension).isEmpty()
        assertThat(summary.plannedTaskMinutes).isEqualTo(0)
    }

    @Test
    fun `a range summary carries both sides of every per-dimension split`() {
        val summary = rangeSummary(LocalDate.of(2026, 9, 14))

        assertThat(summary.plannedByDimension).containsEntry("dim_work_livelihood", 300)
        assertThat(summary.actualByDimension).containsEntry("dim_work_livelihood", 240)
        assertThat(summary.trendPoints.map { it.dayKey }).containsExactly("2026-09-20").inOrder()
    }

    @Test
    fun `a time-module summary ranks the focus day against the days it holds`() {
        val metric =
            TimeModuleDayMetric(
                dayKey = "2026-09-20",
                dayScore = 72.5,
                progressDelta = 4.0,
                progressStreak = 3,
                perDimensionScores = mapOf("dim_work_livelihood" to 80.0),
            )
        val summary =
            TimeModuleHistorySummary(
                currentDayKey = "2026-09-20",
                currentDayScore = 72.5,
                currentProgressDelta = 4.0,
                currentProgressStreak = 3,
                dayScoreRank = 2,
                progressRank = 1,
                streakRank = 1,
                totalDays = 9,
                metrics = listOf(metric),
            )

        assertThat(summary.currentDayKey).isEqualTo(metric.dayKey)
        assertThat(summary.totalDays).isEqualTo(9)
        assertThat(summary.dayScoreRank).isEqualTo(2)
        assertThat(summary.metrics).hasSize(1)
        assertThat(summary.metrics.first().perDimensionScores).containsEntry("dim_work_livelihood", 80.0)
    }

    @Test
    fun `the groupings are the three ways a lens can be sliced`() {
        assertThat(LensGrouping.entries).hasSize(3)
    }

    private fun rangeSummary(start: LocalDate) =
        LensRangeSummary(
            mode = LensTimeMode.PAST,
            window = LensTimeWindow.LAST_7_DAYS,
            pageIndex = 0,
            startDate = start,
            endDate = start.plusDays(6),
            totalPlannedMinutes = 480,
            totalActualMinutes = 300,
            totalUntrackedMinutes = 180,
            totalFocusGapMinutes = 60,
            plannedTaskCount = 10,
            completedTaskCount = 7,
            missedTaskCount = 2,
            plannedHabitCount = 4,
            completedHabitCount = 4,
            missedHabitCount = 0,
            averagePlanCompleteness = 0.7f,
            averageAdherence = 0.62f,
            plannedByDimension = mapOf("dim_work_livelihood" to 300),
            actualByDimension = mapOf("dim_work_livelihood" to 240),
            plannedTasksByDimension = mapOf("dim_work_livelihood" to 4),
            completedTasksByDimension = mapOf("dim_work_livelihood" to 3),
            missedTasksByDimension = mapOf("dim_work_livelihood" to 1),
            plannedHabitsByDimension = mapOf("dim_work_livelihood" to 2),
            completedHabitsByDimension = mapOf("dim_work_livelihood" to 2),
            missedHabitsByDimension = emptyMap(),
            trendPoints = listOf(LensTrendPoint("2026-09-20", plannedMinutes = 480, actualMinutes = 300)),
        )
}
