//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the shared X-axis label rule.
 *
 * The defect these tests exist for: a chart with a 7-point window drew ONE X label, so the axis showed a
 * single number with no way to read the span. Every case below asserts the count a screen must end up with,
 * so the rule cannot silently collapse again - and the counts are asserted by SIMULATION (walking the point
 * indices a placer with that spacing would label) rather than by trusting the arithmetic.
 */
class ChartAxisPolicyTest {
    /** The indices an axis labels given [pointCount] points and the policy's spacing for them. */
    private fun labelledIndices(pointCount: Int): List<Int> {
        val spacing = ChartAxisPolicy.spacingFor(pointCount)
        return (0 until pointCount).filter { it % spacing == 0 }
    }

    @Test
    fun `a seven day window labels three days, not one`() {
        val labelled = labelledIndices(7)
        assertEquals(3, labelled.size, "a 7-point window must label the short count, got $labelled")
        assertEquals(listOf(0, 3, 6), labelled)
    }

    @Test
    fun `a thirty day window labels five days`() {
        val labelled = labelledIndices(30)
        assertTrue(labelled.size in 4..6, "a 30-point window should label about five points, got $labelled")
    }

    @Test
    fun `a ninety day window labels five days`() {
        val labelled = labelledIndices(90)
        assertTrue(labelled.size in 5..6, "a 90-point window should label five or six points, got $labelled")
    }

    @Test
    fun `every chart size from one to one hundred and twenty labels at least two points`() {
        val starved =
            (1..120).filter { labelledIndices(it).size < 2 && it >= 2 }
        assertEquals(emptyList<Int>(), starved, "no window with two or more points may draw fewer than two labels")
    }

    @Test
    fun `the label count is three or five and never anything else`() {
        val counts = (1..200).map { ChartAxisPolicy.labelCountFor(it) }.toSet()
        assertEquals(setOf(ChartAxisPolicy.SHORT_LABEL_COUNT, ChartAxisPolicy.WIDE_LABEL_COUNT), counts)
    }

    @Test
    fun `spacing is never less than one`() {
        assertTrue((0..200).all { ChartAxisPolicy.spacingFor(it) >= 1 })
    }

    @Test
    fun `scores keep two decimals and integers keep none`() {
        assertEquals(2, ChartAxisPolicy.decimalsFor(ChartAxisPolicy.Metric.SCORE))
        assertEquals(1, ChartAxisPolicy.decimalsFor(ChartAxisPolicy.Metric.RATING))
        assertEquals(0, ChartAxisPolicy.decimalsFor(ChartAxisPolicy.Metric.MINUTES))
        assertEquals(0, ChartAxisPolicy.decimalsFor(ChartAxisPolicy.Metric.COUNT))
        assertEquals(0, ChartAxisPolicy.decimalsFor(ChartAxisPolicy.Metric.STREAK))
    }

    @Test
    fun `a small score is not truncated to zero`() {
        // The original defect in this family: 0.33333 rendered as `0`, i.e. a real value shown as nothing.
        assertEquals("0.33", ChartAxisPolicy.formatAxisValue(0.33333, ChartAxisPolicy.Metric.SCORE))
        assertEquals("0.5", ChartAxisPolicy.formatAxisValue(0.5, ChartAxisPolicy.Metric.SCORE))
        assertEquals("1", ChartAxisPolicy.formatAxisValue(1.0, ChartAxisPolicy.Metric.SCORE))
    }

    @Test
    fun `counts are written as whole numbers`() {
        assertEquals("7", ChartAxisPolicy.formatAxisValue(6.6, ChartAxisPolicy.Metric.COUNT))
        assertEquals("0", ChartAxisPolicy.formatAxisValue(0.0, ChartAxisPolicy.Metric.MINUTES))
    }

    @Test
    fun `a rating keeps one decimal`() {
        assertEquals("3.5", ChartAxisPolicy.formatAxisValue(3.46, ChartAxisPolicy.Metric.RATING))
    }

    @Test
    fun `a value that cannot be drawn formats to empty rather than to nan`() {
        assertEquals("", ChartAxisPolicy.formatAxisValue(Double.NaN, ChartAxisPolicy.Metric.SCORE))
        assertEquals("", ChartAxisPolicy.formatAxisValue(Double.POSITIVE_INFINITY, ChartAxisPolicy.Metric.SCORE))
    }

    @Test
    fun `bounds come from the data with a pad, and a flat series still gets room`() {
        val (low, high) = ChartAxisPolicy.paddedBounds(listOf(2.0, 4.0, 5.0))!!
        assertTrue(low < 2.0 && high > 5.0, "bounds must clear the data, got ($low, $high)")
        val (flatLow, flatHigh) = ChartAxisPolicy.paddedBounds(listOf(3.0, 3.0, 3.0))!!
        assertTrue(
            flatHigh - flatLow > 0.0,
            "a flat series must not collapse to a zero-height axis, got ($flatLow, $flatHigh)",
        )
        assertEquals(null, ChartAxisPolicy.paddedBounds(emptyList()))
    }
}
