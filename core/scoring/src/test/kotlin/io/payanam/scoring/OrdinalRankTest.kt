//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later

package io.payanam.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ordinalRankToday] and [ordinalRankParts] — the shared
 * "today / max" rank helper used by both the Lenses score matrix and the
 * Habits day-metrics strip.
 *
 * Every string case is pinned a second time as the numeric PAIR it is rendered
 * from, so the number a surface scales by can never drift from the text it
 * shows.
 */
class OrdinalRankTest {

    @Test
    fun `empty history returns em dash`() {
        assertEquals("—", ordinalRankToday(emptyList()))
        assertEquals("—", ordinalRankToday(listOf(null, null)))
        assertNull(ordinalRankParts(emptyList()))
        assertNull(ordinalRankParts(listOf(null, null)))
    }

    @Test
    fun `single value is rank 1 of 1`() {
        assertEquals("1/1", ordinalRankToday(listOf(0.5)))
        assertEquals(OrdinalRankParts(rank = 1, total = 1), ordinalRankParts(listOf(0.5)))
    }

    @Test
    fun `today is best ranks 1`() {
        // today = last value = 0.9 (highest)
        assertEquals("1/4", ordinalRankToday(listOf(0.3, 0.5, 0.7, 0.9)))
        assertEquals(
            OrdinalRankParts(rank = 1, total = 4),
            ordinalRankParts(listOf(0.3, 0.5, 0.7, 0.9)),
        )
    }

    @Test
    fun `today is worst ranks last`() {
        assertEquals("4/4", ordinalRankToday(listOf(0.9, 0.7, 0.5, 0.2)))
        assertEquals(
            OrdinalRankParts(rank = 4, total = 4),
            ordinalRankParts(listOf(0.9, 0.7, 0.5, 0.2)),
        )
    }

    @Test
    fun `ties share dense rank`() {
        // distinct = {0.9, 0.5}; today 0.5 → rank 2 of 2
        assertEquals("2/2", ordinalRankToday(listOf(0.9, 0.5, 0.5, 0.5)))
        assertEquals(
            OrdinalRankParts(rank = 2, total = 2),
            ordinalRankParts(listOf(0.9, 0.5, 0.5, 0.5)),
        )
    }

    @Test
    fun `distinct values collapse repeats for denominator`() {
        // distinct = {1.0, 0.8, 0.4}; today = last = 0.4 → rank 3 of 3
        assertEquals("3/3", ordinalRankToday(listOf(1.0, 0.8, 0.8, 0.4, 0.4)))
        assertEquals(
            OrdinalRankParts(rank = 3, total = 3),
            ordinalRankParts(listOf(1.0, 0.8, 0.8, 0.4, 0.4)),
        )
    }

    @Test
    fun `nulls ignored in series`() {
        assertEquals("1/2", ordinalRankToday(listOf(null, 0.4, null, 0.9)))
        assertEquals(
            OrdinalRankParts(rank = 1, total = 2),
            ordinalRankParts(listOf(null, 0.4, null, 0.9)),
        )
    }

    @Test
    fun `the string is always the pair it was rendered from`() {
        // distinct = {0.9, 0.7, 0.5, 0.3}; today = last non-null = 0.5 → rank 3 of 4
        val series = listOf(0.3, 0.5, 0.7, 0.9, 0.5, null)

        val parts = requireNotNull(ordinalRankParts(series))

        assertEquals(OrdinalRankParts(rank = 3, total = 4), parts)
        assertEquals("${parts.rank}/${parts.total}", ordinalRankToday(series))
    }

    @Test
    fun `a rank always sits inside its own scale`() {
        val series = listOf(0.1, 0.4, 0.4, 0.9, 0.2)

        val parts = requireNotNull(ordinalRankParts(series))

        assertTrue(parts.rank in 1..parts.total)
    }
}
