//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.scoring

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ordinalRankToday] — the shared "today / max" rank helper used
 * by both the Lenses score matrix and the Habits day-metrics strip.
 */
class OrdinalRankTest {

    @Test
    fun `empty history returns em dash`() {
        assertEquals("—", ordinalRankToday(emptyList()))
        assertEquals("—", ordinalRankToday(listOf(null, null)))
    }

    @Test
    fun `single value is rank 1 of 1`() {
        assertEquals("1/1", ordinalRankToday(listOf(0.5)))
    }

    @Test
    fun `today is best ranks 1`() {
        // today = last value = 0.9 (highest)
        assertEquals("1/4", ordinalRankToday(listOf(0.3, 0.5, 0.7, 0.9)))
    }

    @Test
    fun `today is worst ranks last`() {
        assertEquals("4/4", ordinalRankToday(listOf(0.9, 0.7, 0.5, 0.2)))
    }

    @Test
    fun `ties share dense rank`() {
        // distinct = {0.9, 0.5}; today 0.5 → rank 2 of 2
        assertEquals("2/2", ordinalRankToday(listOf(0.9, 0.5, 0.5, 0.5)))
    }

    @Test
    fun `distinct values collapse repeats for denominator`() {
        // distinct = {1.0, 0.8, 0.4}; today = last = 0.4 → rank 3 of 3
        assertEquals("3/3", ordinalRankToday(listOf(1.0, 0.8, 0.8, 0.4, 0.4)))
    }

    @Test
    fun `nulls ignored in series`() {
        assertEquals("1/2", ordinalRankToday(listOf(null, 0.4, null, 0.9)))
    }
}
