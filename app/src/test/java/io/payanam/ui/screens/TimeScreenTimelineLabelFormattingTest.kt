//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TimeScreenTimelineLabelFormattingTest.
 */
class TimeScreenTimelineLabelFormattingTest {

    @Test
    /**
     * Format compact focus value formats numeric value with prefix.
     */
    fun formatCompactFocusValue_formats_numeric_value_with_prefix() {
        /** Assert equals. */
        assertEquals("F: 0.7", formatCompactFocusValue(0.7))
        /** Assert equals. */
        assertEquals("F: 1.0", formatCompactFocusValue(1.2))
        /** Assert equals. */
        assertEquals("F: 0.0", formatCompactFocusValue(-0.2))
    }

    @Test
    /**
     * Format compact focus value returns null for missing or invalid values.
     */
    fun formatCompactFocusValue_returnsNull_for_missing_or_invalid_values() {
        /** Assert null. */
        assertNull(formatCompactFocusValue(null))
        /** Assert null. */
        assertNull(formatCompactFocusValue(Double.NaN))
        /** Assert null. */
        assertNull(formatCompactFocusValue(Double.POSITIVE_INFINITY))
    }

    @Test
    /**
     * Build time block compact label preserves expected order.
     */
    fun buildTimeBlockCompactLabel_preserves_expected_order() {
        /** Label. */
        val label = buildTimeBlockCompactLabel(
            dimensionLabel = "Health",
            taskLabel = "Morning Run",
            startLabel = "09:00",
            endLabel = "10:20",
            durationLabel = "1h 20m",
            focusValueLabel = "F: 0.7",
        )

        /** Assert equals. */
        assertEquals("Health · Morning Run · 09:00 - 10:20 · 1h 20m · F: 0.7", label)
    }
}
