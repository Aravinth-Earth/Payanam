//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * The Time-screen visuals state, which both platforms render from.
 *
 * The defaults are the contract: the visuals section must be able to draw itself from a state it has not filled
 * in yet — a zeroed summary, no per-dimension rows, an empty trend — rather than from nulls the UI has to guard.
 * `isLoading` defaults to false here (unlike the habits strip) because the visuals section is built only after
 * the day is loaded, so a fresh state is a settled empty day, not a pending one.
 */
class TimeVisualsModelsTest {
    @Test
    fun `a fresh visuals state is a settled empty day`() {
        val state = TimeVisualsState()

        assertThat(state.isLoading).isFalse()
        assertThat(state.perDimension).isEmpty()
        assertThat(state.selectedDimensionFilterId).isNull()
        assertThat(state.dayOverall).isEqualTo(TimeDayOverallSummary())
        assertThat(state.trend).isEqualTo(TimeTrendStripSummary())
    }

    @Test
    fun `the selected date defaults to today`() {
        assertThat(TimeVisualsState().selectedDate).isEqualTo(LocalDate.now())
    }

    @Test
    fun `an overall summary zeroes every counter it did not measure`() {
        val summary = TimeDayOverallSummary()

        assertThat(summary.trackedMinutes).isEqualTo(0L)
        assertThat(summary.activeBlockCount).isEqualTo(0)
        assertThat(summary.focusedMinutesPercent).isEqualTo(0f)
        assertThat(summary.untrackedMinutesEstimate).isEqualTo(0L)
        assertThat(summary.overlapCount).isEqualTo(0)
        assertThat(summary.gapCount).isEqualTo(0)
    }

    @Test
    fun `a dimension row keeps the planned-vs-actual delta and its sign`() {
        val over = dimensionRow(plannedMinutes = 60, plannedDeltaMinutes = 15)
        val under = dimensionRow(plannedMinutes = 60, plannedDeltaMinutes = -25)

        assertThat(over.plannedDeltaMinutes).isEqualTo(15L)
        assertThat(under.plannedDeltaMinutes).isEqualTo(-25L)
        assertThat(under.dimensionId).isEqualTo("dim_work_livelihood")
    }

    @Test
    fun `a populated state carries the rows and the trend it was given`() {
        val state =
            TimeVisualsState(
                isLoading = true,
                dayOverall = TimeDayOverallSummary(trackedMinutes = 480, activeBlockCount = 9, gapCount = 2),
                perDimension = listOf(dimensionRow(plannedMinutes = 60, plannedDeltaMinutes = 15)),
                trend = TimeTrendStripSummary(selectedDayMinutes = 480, previousDayMinutes = 300, last7AverageMinutes = 390),
                selectedDimensionFilterId = "dim_work_livelihood",
            )

        assertThat(state.isLoading).isTrue()
        assertThat(state.dayOverall.trackedMinutes).isEqualTo(480L)
        assertThat(state.dayOverall.gapCount).isEqualTo(2)
        assertThat(state.perDimension).hasSize(1)
        assertThat(state.trend.last7AverageMinutes).isEqualTo(390L)
        assertThat(state.selectedDimensionFilterId).isEqualTo("dim_work_livelihood")
    }

    private fun dimensionRow(
        plannedMinutes: Int,
        plannedDeltaMinutes: Long,
    ) = TimeDimensionDaySummary(
        dimensionId = "dim_work_livelihood",
        dimensionLabel = "Work & Livelihood",
        trackedMinutes = 300,
        sharePercent = 62.5f,
        focusedMinutes = 240,
        blockCount = 4,
        plannedMinutes = plannedMinutes,
        plannedDeltaMinutes = plannedDeltaMinutes,
    )
}
