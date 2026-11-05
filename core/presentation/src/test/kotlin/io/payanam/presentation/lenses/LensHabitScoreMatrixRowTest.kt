//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import io.payanam.presentation.scoring.ScoreMetricColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The score matrix's row accessors: the pair a radar axis stores per metric, and the optional column a row
 * carries.
 *
 * WHY THESE ARE WORTH PINNING: [RadarAxis.values] is a pair per metric and its two accessors read
 * `.first` and `.second`. That is the one place in the matrix where picking the wrong component is
 * invisible - a chart that swapped today's value for the running average still renders, it just renders
 * the wrong number, and a screen that shows both never looks obviously wrong. [RadarAxis.displayLabel]
 * defaults to [RadarAxis.label] and is overridden by a user-custom label, so both paths are asserted.
 */
class LensHabitScoreMatrixRowTest {
    private val axis =
        RadarAxis(
            key = "dim_work_livelihood",
            label = "Work & Livelihood",
            colorHex = "#4C6EF5",
            values =
                mapOf(
                    ScoreMetricColumn.SCORE to (0.8 to 0.6),
                    ScoreMetricColumn.PROGRESS to (0.25 to 0.5),
                    ScoreMetricColumn.STREAK_POS to (4.0 to 2.5),
                ),
        )

    @Test
    fun today_reads_the_first_component_of_the_pairs_value() {
        assertEquals(0.8, axis.today(ScoreMetricColumn.SCORE)!!, 1e-9)
        assertEquals(0.25, axis.today(ScoreMetricColumn.PROGRESS)!!, 1e-9)
    }

    @Test
    fun running_avg_reads_the_second_component_of_the_pairs_value() {
        assertEquals(0.6, axis.runningAvg(ScoreMetricColumn.SCORE)!!, 1e-9)
        assertEquals(0.5, axis.runningAvg(ScoreMetricColumn.PROGRESS)!!, 1e-9)
    }

    @Test
    fun a_metric_the_axis_does_not_carry_reads_null_from_both_accessors() {
        // The matrix omits a metric a dimension has no data for. A missing column must read as "no value"
        // rather than as the pair's first component or a crash.
        assertNull(axis.today(ScoreMetricColumn.RUNNING_AVG))
        assertNull(axis.runningAvg(ScoreMetricColumn.RUNNING_AVG))
    }

    @Test
    fun a_metric_carrying_a_null_component_reads_null_from_that_accessor_only() {
        val partial =
            RadarAxis(
                key = "dim_learning",
                label = "Learning",
                colorHex = "#12B886",
                values = mapOf(ScoreMetricColumn.SCORE to (null to 0.4)),
            )

        assertNull("no reading for today", partial.today(ScoreMetricColumn.SCORE))
        assertEquals("but the running average is known", 0.4, partial.runningAvg(ScoreMetricColumn.SCORE)!!, 1e-9)
    }

    @Test
    fun the_display_label_falls_back_to_the_axis_label() {
        assertEquals(axis.label, axis.displayLabel)
    }

    @Test
    fun a_custom_display_label_replaces_the_axis_label_for_display_only() {
        val renamed = axis.copy(displayLabel = "Day job")

        assertEquals("the axis keeps its taxonomy label", "Work & Livelihood", renamed.label)
        assertEquals("and renders the user's own label", "Day job", renamed.displayLabel)
    }

    @Test
    fun a_row_reports_its_values_under_the_metric_they_were_stored_under() {
        val row =
            ScoreMatrixRow(
                key = "dim_work_livelihood",
                label = "Work & Livelihood",
                colorHex = "#4C6EF5",
                isDay = false,
                values = mapOf(ScoreMetricColumn.SCORE to 0.8, ScoreMetricColumn.PROGRESS to null),
                sparkline = listOf(0.1, 0.4, 0.8),
            )

        assertEquals(0.8, row.values[ScoreMetricColumn.SCORE]!!, 1e-9)
        assertNull("a metric with no reading is absent, not zero", row.values[ScoreMetricColumn.PROGRESS])
        assertEquals(3, row.sparkline.size)
        assertEquals(false, row.isDay)
    }
}
