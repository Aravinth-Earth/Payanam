//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.lenses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chart x-axis label mapping — pure.
 *
 * Pins the two properties the chart depends on: a label is never blank (the library throws on a blank
 * one), and an out-of-range index selects the NEAREST label rather than an arbitrary end. The negative
 * case is the one that shipped wrong: a null-fallback lookup produced the LAST label for an index below
 * zero, so the axis showed the opposite end of the range.
 */
class LensesTimeAxisLabelTest {
    private val labels = listOf("Mon", "Tue", "Wed")

    @Test
    fun `a valid index selects its own label`() {
        assertEquals("Mon", axisLabelAt(labels, 0.0))
        assertEquals("Tue", axisLabelAt(labels, 1.0))
        assertEquals("Wed", axisLabelAt(labels, 2.0))
    }

    @Test
    fun `an index below zero selects the first label`() {
        assertEquals("Mon", axisLabelAt(labels, -1.0))
        assertEquals("Mon", axisLabelAt(labels, -999.0))
    }

    @Test
    fun `an index past the end selects the last label`() {
        assertEquals("Wed", axisLabelAt(labels, 3.0))
        assertEquals("Wed", axisLabelAt(labels, 9999.0))
    }

    @Test
    fun `a fractional value uses its whole part`() {
        assertEquals("Tue", axisLabelAt(labels, 1.9))
    }

    @Test
    fun `no labels yields a non-blank placeholder`() {
        val placeholder = axisLabelAt(emptyList(), 0.0)

        assertEquals("\u2014", placeholder)
        assertTrue(placeholder.isNotBlank())
    }

    @Test
    fun `the result is never blank for any index`() {
        (-5..10).forEach { index -> assertTrue(axisLabelAt(labels, index.toDouble()).isNotBlank()) }
    }
}
