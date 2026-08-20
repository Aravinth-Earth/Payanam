//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.repository

import io.payanam.domain.model.TimeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * DailyStatsCalculatorTest.
 */
class DailyStatsCalculatorTest {
    // --- focusedHours: zero when no focusRating set ---

    @Test
    /**
     * Focused hours unrated entry contributes zero.
     */
    fun focusedHours_unrated_entry_contributes_zero() {
        /** Day. */
        val day = LocalDate.of(2026, 3, 1)
        /** Entries. */
        val entries = listOf(entry(day.atTime(9, 0), day.atTime(10, 0), focus = null))
        /** Result. */
        val result = DailyStatsCalculator.calculateDailyFocusedHoursStats(entries)
        /** Assert equals. */
        assertEquals(1, result.size)
        /** Assert equals. */
        assertEquals(0.0, result[0].focusedHours, 0.001)
    }

    @Test
    /**
     * Focused hours rated entry computes correctly.
     */
    fun focusedHours_rated_entry_computes_correctly() {
        /** Day. */
        val day = LocalDate.of(2026, 3, 1)
        // 60 min * 0.8 focus = 48 focused minutes = 0.8h
        /** Entries. */
        val entries = listOf(entry(day.atTime(9, 0), day.atTime(10, 0), focus = 0.8))
        /** Result. */
        val result = DailyStatsCalculator.calculateDailyFocusedHoursStats(entries)
        /** Assert equals. */
        assertEquals(1, result.size)
        /** Assert equals. */
        assertEquals(0.8, result[0].focusedHours, 0.001)
    }

    // --- midnight-crossing: tracked time splits correctly ---

    @Test
    /**
     * Tracked time midnight crossing entry splits to both days.
     */
    fun trackedTime_midnight_crossing_entry_splits_to_both_days() {
        /** Day1. */
        val day1 = LocalDate.of(2026, 3, 1)
        /** Day2. */
        val day2 = LocalDate.of(2026, 3, 2)
        // Entry from 23:00 day1 to 01:00 day2 = 60 min each
        /** Entries. */
        val entries = listOf(entry(day1.atTime(23, 0), day2.atTime(1, 0), focus = null))
        /** Result. */
        val result = DailyStatsCalculator.calculateDailyTrackedTimeStats(entries)
        /** Assert equals. */
        assertEquals(2, result.size)
        /** D1. */
        val d1 = result.first { it.dayKey == day1.toString() }
        /** D2. */
        val d2 = result.first { it.dayKey == day2.toString() }
        // day1 gets 60 min / 1440 * 100 ≈ 4.17%
        /** Assert equals. */
        assertEquals(60.0 / 1440.0 * 100.0, d1.trackedPercent, 0.1)
        // day2 gets 60 min / 1440 * 100 ≈ 4.17%
        /** Assert equals. */
        assertEquals(60.0 / 1440.0 * 100.0, d2.trackedPercent, 0.1)
    }

    // --- midnight-crossing: focus avg attributed to correct days ---

    @Test
    /**
     * Focus avg midnight crossing entry appears in both days.
     */
    fun focusAvg_midnight_crossing_entry_appears_in_both_days() {
        /** Day1. */
        val day1 = LocalDate.of(2026, 3, 1)
        /** Day2. */
        val day2 = LocalDate.of(2026, 3, 2)
        /** Entries. */
        val entries = listOf(entry(day1.atTime(23, 0), day2.atTime(1, 0), focus = 0.9))
        /** Result. */
        val result = DailyStatsCalculator.calculateDailyFocusAverages(entries)
        /** Assert equals. */
        assertEquals(2, result.size)
        /** D1. */
        val d1 = result.first { it.dayKey == day1.toString() }
        /** D2. */
        val d2 = result.first { it.dayKey == day2.toString() }
        /** Assert equals. */
        assertEquals(0.9, d1.avgFocus!!, 0.001)
        /** Assert equals. */
        assertEquals(0.9, d2.avgFocus!!, 0.001)
    }

    // --- midnight-crossing: focused hours split correctly ---

    @Test
    /**
     * Focused hours midnight crossing entry splits correctly.
     */
    fun focusedHours_midnight_crossing_entry_splits_correctly() {
        /** Day1. */
        val day1 = LocalDate.of(2026, 3, 1)
        /** Day2. */
        val day2 = LocalDate.of(2026, 3, 2)
        // 60 min on day1, 60 min on day2, focus = 1.0 → each day gets 1.0h
        /** Entries. */
        val entries = listOf(entry(day1.atTime(23, 0), day2.atTime(1, 0), focus = 1.0))
        /** Result. */
        val result = DailyStatsCalculator.calculateDailyFocusedHoursStats(entries)
        /** Assert equals. */
        assertEquals(2, result.size)
        /** D1. */
        val d1 = result.first { it.dayKey == day1.toString() }
        /** D2. */
        val d2 = result.first { it.dayKey == day2.toString() }
        /** Assert equals. */
        assertEquals(1.0, d1.focusedHours, 0.001)
        /** Assert equals. */
        assertEquals(1.0, d2.focusedHours, 0.001)
    }

    // --- regression: same-day entry must not cause infinite loop in splitToDaySegments ---
    // Bug: while(!current.toLocalDate().isAfter(end.toLocalDate())) loops forever when
    // current==end because minutes==0 so current never advances past end.
    // Fix: while(current.isBefore(end))

    @Test(timeout = 5000)
    /**
     * Split to day segments same day does not hang tracked time.
     */
    fun splitToDaySegments_sameDay_doesNotHang_trackedTime() {
        /** Day. */
        val day = LocalDate.of(2026, 3, 1)
        /** Entries. */
        val entries = listOf(entry(day.atTime(9, 0), day.atTime(10, 0), focus = null))
        /** Result. */
        val result = DailyStatsCalculator.calculateDailyTrackedTimeStats(entries)
        /** Assert equals. */
        assertEquals(1, result.size)
    }

    @Test(timeout = 5000)
    /**
     * Split to day segments same day does not hang focus avg.
     */
    fun splitToDaySegments_sameDay_doesNotHang_focusAvg() {
        /** Day. */
        val day = LocalDate.of(2026, 3, 1)
        /** Entries. */
        val entries = listOf(entry(day.atTime(9, 0), day.atTime(10, 0), focus = 0.7))
        /** Result. */
        val result = DailyStatsCalculator.calculateDailyFocusAverages(entries)
        /** Assert equals. */
        assertEquals(1, result.size)
    }

    @Test(timeout = 5000)
    /**
     * Split to day segments same day does not hang focused hours.
     */
    fun splitToDaySegments_sameDay_doesNotHang_focusedHours() {
        /** Day. */
        val day = LocalDate.of(2026, 3, 1)
        /** Entries. */
        val entries = listOf(entry(day.atTime(9, 0), day.atTime(10, 0), focus = 1.0))
        /** Result. */
        val result = DailyStatsCalculator.calculateDailyFocusedHoursStats(entries)
        /** Assert equals. */
        assertEquals(1, result.size)
        /** Assert equals. */
        assertEquals(1.0, result[0].focusedHours, 0.001)
    }

    // --- same-day entries work as before ---

    @Test
    /**
     * Focus avg days without rated entries have null avg.
     */
    fun focusAvg_days_without_rated_entries_have_null_avg() {
        /** Day1. */
        val day1 = LocalDate.of(2026, 3, 1)
        /** Day2. */
        val day2 = LocalDate.of(2026, 3, 2)
        /** Entries. */
        val entries =
            /** List of. */
            listOf(
                /** Entry. */
                entry(day1.atTime(9, 0), day1.atTime(10, 0), focus = 0.6),
                /** Entry. */
                entry(day2.atTime(9, 0), day2.atTime(10, 0), focus = null),
            )
        /** Result. */
        val result = DailyStatsCalculator.calculateDailyFocusAverages(entries)
        /** D1. */
        val d1 = result.first { it.dayKey == day1.toString() }
        /** D2. */
        val d2 = result.first { it.dayKey == day2.toString() }
        /** Assert equals. */
        assertEquals(0.6, d1.avgFocus!!, 0.001)
        /** Assert null. */
        assertNull(d2.avgFocus)
    }

    private fun entry(
        /** Start. */
        start: LocalDateTime,
        /** End. */
        end: LocalDateTime,
        focus: Double?,
    ): TimeEntry =
        /** Time entry. */
        TimeEntry(
            id = "${start}_$end",
            lifeIntentionCategory = "Personal Growth",
            taskId = null,
            startedAt = start,
            endedAt = end,
            focusRating = focus,
            focusNote = null,
            focusRatedAt = null,
            createdAt = start,
            updatedAt = start,
            dimensionId = null,
        )
}
