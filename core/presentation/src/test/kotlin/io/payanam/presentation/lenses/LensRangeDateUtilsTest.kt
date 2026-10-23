//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.lenses

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The lens range's day list, at the two points where it can disagree with what was asked for: a range
 * wider than the cap, and a range whose bounds arrive the wrong way round.
 *
 * Both are reachable from the production path and neither was covered: the truncation branch needs a range
 * wider than [maxRangeDays], and the swap needs a caller that passes its bounds unsorted. The lens window
 * picker always hands them over in order, so these are the states a screen can reach but the happy path
 * cannot produce - which is exactly why they need asserting rather than assuming.
 */
class LensRangeDateUtilsTest {
    private fun dates(from: String, to: String, maxRangeDays: Int): List<LocalDate> =
        buildLensDatesForRange(
            rangeStartDate = LocalDate.parse(from),
            rangeEndDate = LocalDate.parse(to),
            maxRangeDays = maxRangeDays,
        )

    @Test
    fun a_range_within_the_cap_keeps_every_day_from_start_to_end() {
        val resolved = dates("2026-02-01", "2026-02-05", maxRangeDays = 730)

        assertEquals(5, resolved.size)
        assertEquals(LocalDate.parse("2026-02-01"), resolved.first())
        assertEquals(LocalDate.parse("2026-02-05"), resolved.last())
    }

    @Test
    fun a_single_day_range_is_one_day() {
        val resolved = dates("2026-02-05", "2026-02-05", maxRangeDays = 730)

        assertEquals(listOf(LocalDate.parse("2026-02-05")), resolved)
    }

    @Test
    fun a_range_wider_than_the_cap_keeps_the_cap_and_drops_the_earliest_days() {
        // 10 requested days against a cap of 4: the END is what the user is looking at, so the window is
        // the last 4 days of the range, not the first 4.
        val resolved = dates("2026-02-01", "2026-02-10", maxRangeDays = 4)

        assertEquals(4, resolved.size)
        assertEquals(LocalDate.parse("2026-02-07"), resolved.first())
        assertEquals(LocalDate.parse("2026-02-10"), resolved.last())
    }

    @Test
    fun a_range_exactly_at_the_cap_is_not_truncated() {
        val resolved = dates("2026-02-01", "2026-02-04", maxRangeDays = 4)

        assertEquals(4, resolved.size)
        assertEquals("the first day survives at the boundary", LocalDate.parse("2026-02-01"), resolved.first())
    }

    @Test
    fun reversed_bounds_resolve_to_the_same_days_as_sorted_bounds() {
        val reversed = dates("2026-02-10", "2026-02-01", maxRangeDays = 730)
        val sorted = dates("2026-02-01", "2026-02-10", maxRangeDays = 730)

        assertEquals(sorted, reversed)
        assertEquals(LocalDate.parse("2026-02-01"), reversed.first())
    }

    @Test
    fun reversed_bounds_wider_than_the_cap_truncate_to_the_later_bound() {
        // minOf/maxOf put the earlier date in START, so truncation keeps the days next to the later bound -
        // the same days the sorted form would keep.
        val resolved = dates("2026-02-10", "2026-02-01", maxRangeDays = 4)

        assertEquals(4, resolved.size)
        assertEquals(LocalDate.parse("2026-02-07"), resolved.first())
        assertEquals(LocalDate.parse("2026-02-10"), resolved.last())
    }

    @Test
    fun the_resolved_days_are_one_apart_with_no_gap_and_no_repeat() {
        val resolved = dates("2026-02-01", "2026-02-06", maxRangeDays = 730)

        resolved.zipWithNext { earlier, later ->
            assertEquals(1L, java.time.temporal.ChronoUnit.DAYS.between(earlier, later))
        }
    }
}
