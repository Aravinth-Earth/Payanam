//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.habits

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The habits day-metrics state, which both platforms render from.
 *
 * Small, but it is the contract the strip is built on: a chip carries the day's formatted value, its ordinal
 * rank, and its caption, and the placeholder flag is what lets the UI dim a rank whose model does not exist
 * yet instead of showing a wrong ordinal. The defaults matter as much as the fields — the strip must start
 * hidden (`isLoading`) rather than empty-but-settled, or a first frame shows "no metrics" for a day that has
 * them.
 */
class HabitDayMetricsUiModelTest {
    @Test
    fun `a fresh state is loading with no chips`() {
        val state = HabitDayMetricsState()

        assertThat(state.isLoading).isTrue()
        assertThat(state.chips).isEmpty()
    }

    @Test
    fun `a chip carries its value, rank and caption, and is not a placeholder by default`() {
        val chip = DayMetricChipData(value = "72", rank = "3/9", label = "Day Score")

        assertThat(chip.value).isEqualTo("72")
        assertThat(chip.rank).isEqualTo("3/9")
        assertThat(chip.label).isEqualTo("Day Score")
        assertThat(chip.isPlaceholderRank).isFalse()
    }

    @Test
    fun `a placeholder chip says so, so the strip can dim it`() {
        val chip = DayMetricChipData(value = "2", rank = "—", label = "Due", isPlaceholderRank = true)

        assertThat(chip.isPlaceholderRank).isTrue()
        assertThat(chip.rank).isEqualTo("—")
    }

    @Test
    fun `a settled state holds the chips in the order the producer gave them`() {
        val chips =
            listOf(
                DayMetricChipData(value = "72", rank = "3/9", label = "Day Score"),
                DayMetricChipData(value = "5", rank = "2/9", label = "Run Avg"),
            )

        val state = HabitDayMetricsState(isLoading = false, chips = chips)

        assertThat(state.isLoading).isFalse()
        assertThat(state.chips.map { it.label }).containsExactly("Day Score", "Run Avg").inOrder()
    }
}
