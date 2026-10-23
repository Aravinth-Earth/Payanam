//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import androidx.compose.ui.graphics.Color
import io.payanam.core.ui.theme.scoreColor
import io.payanam.presentation.habits.DayMetricChipData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The rank colour of a day-metrics chip.
 *
 * The strip used to paint EVERY non-placeholder rank the same green, which made two different claims
 * look identical: "this is the best value the metric has ever had", and "this metric has no history
 * at all" (the "—" rank, which is not a placeholder and so was painted green - a metric with no
 * history was drawn as the best possible one).
 *
 * The mapping is pinned on three fronts, because each one fails differently:
 *  - the SCALE: best is the green band, the middle is amber, the worst is the red band, and the bands
 *    come from the shared [scoreColor] palette rather than from a second palette of their own;
 *  - the two NEUTRAL cases: no history, and a placeholder rank (Due);
 *  - the DEGENERATE scale: one distinct value (rank 1 of 1), where the scale would divide by zero.
 *
 * It is asserted as a pure function rather than through a rendered strip, because the colour IS the
 * whole decision, and the "X/Y" text beside it is what carries the rank for a user who cannot tell
 * the colours apart - so the text needs no separate proof here.
 */
class DayMetricsStripRankColorTest {
    @Test
    fun theBestRankOnAChipsOwnScaleIsTheGreenBand() {
        // Rank 1 of 3: the best value this metric has ever had.
        assertEquals(Color(0xFF4CAF50), rankColor(rankNumber = 1, rankTotal = 3))
        assertEquals(scoreColor(1f), rankColor(rankNumber = 1, rankTotal = 3))
    }

    @Test
    fun theMiddleRankIsTheAmberBand() {
        // Rank 2 of 3: goodness 0.5, the middle band.
        assertEquals(Color(0xFFFFC107), rankColor(rankNumber = 2, rankTotal = 3))
        assertEquals(scoreColor(0.5f), rankColor(rankNumber = 2, rankTotal = 3))
    }

    @Test
    fun theWorstRankIsTheRedBand() {
        // Rank 3 of 3: the worst value this metric has ever had.
        assertEquals(Color(0xFFF44336), rankColor(rankNumber = 3, rankTotal = 3))
        assertEquals(scoreColor(0f), rankColor(rankNumber = 3, rankTotal = 3))
    }

    @Test
    fun eachChipIsScaledByItsOwnScale() {
        // The same rank number on a different scale is a different position, so it gets a different
        // colour: rank 2 of 5 is goodness 0.75 (light green), not the amber of rank 2 of 3.
        assertEquals(scoreColor(0.75f), rankColor(rankNumber = 2, rankTotal = 5))
        assertNotEquals(rankColor(rankNumber = 2, rankTotal = 3), rankColor(rankNumber = 2, rankTotal = 5))
    }

    @Test
    fun aChipWithNoHistoryIsNeutralAndNeverGreen() {
        // THE LIVE BUG: the rank reads "—" and is not a placeholder, so the strip used to paint it
        // green - "best possible" for a metric that has no history at all. It must be neutral.
        val colour = rankColor(rankNumber = null, rankTotal = null)
        assertEquals(neutralColour, colour)
        assertNotEquals(scoreColor(1f), colour)
        assertNotEquals(Color(0xFF4CAF50), colour)
    }

    @Test
    fun aPlaceholderRankIsNeutral() {
        // The Due chip carries no ordinal at all. Numbers are supplied here on purpose: the placeholder
        // flag must win even when a rank somehow rides along with it.
        val colour = rankColor(rankNumber = 2, rankTotal = 3, isPlaceholderRank = true)
        assertEquals(neutralColour, colour)
        assertNotEquals(scoreColor(1f), colour)
    }

    @Test
    fun aSingleDistinctValueIsTheBestEndAndDoesNotDivideByZero() {
        // Rank 1 of 1: the only value there has ever been, so the scale (total - 1) would divide by zero.
        // The best end is the answer - the metric is not doing badly, there is simply nothing to compare.
        assertEquals(scoreColor(1f), rankColor(rankNumber = 1, rankTotal = 1))
        assertNotEquals(scoreColor(0f), rankColor(rankNumber = 1, rankTotal = 1))
    }

    @Test
    fun aRankOutsideTheScaleIsClampedToThePaletteRange() {
        // Defensive: a rank outside 1..total must not produce a colour outside the palette's range.
        assertEquals(scoreColor(0f), rankColor(rankNumber = 9, rankTotal = 3))
        assertEquals(scoreColor(1f), rankColor(rankNumber = 0, rankTotal = 3))
    }

    /**
     * The colour the strip would draw for a chip with the given rank.
     *
     * The neutral colour is a fixed stand-in for the theme's dimmed text colour, so the neutral cases
     * are asserted against a known value rather than against whatever the theme happens to be.
     */
    private fun rankColor(
        rankNumber: Int?,
        rankTotal: Int?,
        isPlaceholderRank: Boolean = false,
    ): Color {
        val chip =
            DayMetricChipData(
                value = "72",
                rank = if (rankNumber != null && rankTotal != null) "$rankNumber/$rankTotal" else "—",
                label = "Day Score",
                isPlaceholderRank = isPlaceholderRank,
                rankNumber = rankNumber,
                rankTotal = rankTotal,
            )
        return dayMetricRankColor(chip = chip, neutralColor = neutralColour)
    }

    /** Stand-in for the theme's dimmed secondary text colour. */
    private companion object {
        val neutralColour = Color(0xFF9E9E9E)
    }
}
