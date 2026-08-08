//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Frequency as DomainFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecurrenceScoreCalculatorTest {

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (!UnifiedLogger.isInitialized()) {
            UnifiedLogger.initialize(context, "test", 0)
        }
    }

    @Test
    fun `parseRRuleToFrequency parses FREQ=DAILY correctly`() {
        val rule = "FREQ=DAILY;INTERVAL=1"
        val frequency = RecurrenceScoreCalculator.parseRRuleToFrequency(rule)
        assertEquals(1, frequency.numerator)
        assertEquals(1, frequency.denominator)
    }

    @Test
    fun `parseRRuleToFrequency parses FREQ=WEEKLY correctly`() {
        val rule = "FREQ=WEEKLY;INTERVAL=1"
        val frequency = RecurrenceScoreCalculator.parseRRuleToFrequency(rule)
        assertEquals(1, frequency.numerator)
        assertEquals(7, frequency.denominator)
    }

    @Test
    fun `parseRRuleToFrequency parses FREQ=MONTHLY correctly`() {
        val rule = "FREQ=MONTHLY;INTERVAL=1"
        val frequency = RecurrenceScoreCalculator.parseRRuleToFrequency(rule)
        assertEquals(1, frequency.numerator)
        assertEquals(30, frequency.denominator)
    }

    @Test
    fun `parseRRuleToFrequency parses INTERVAL=2 for DAILY`() {
        val rule = "FREQ=DAILY;INTERVAL=2"
        val frequency = RecurrenceScoreCalculator.parseRRuleToFrequency(rule)
        assertEquals(1, frequency.numerator)
        assertEquals(2, frequency.denominator)
    }

    @Test
    fun `parseRRuleToFrequency handles null rule`() {
        val frequency = RecurrenceScoreCalculator.parseRRuleToFrequency(null)
        assertEquals(1, frequency.numerator)
        assertEquals(1, frequency.denominator)
    }

    @Test
    fun `parseRRuleToFrequency handles empty rule`() {
        val frequency = RecurrenceScoreCalculator.parseRRuleToFrequency("")
        assertEquals(1, frequency.numerator)
        assertEquals(1, frequency.denominator)
    }

    @Test
    fun `fromRule parses CONFIG frequency habits canonically`() {
        val frequency = RecurrenceScoreCalculator.fromRule("CONFIG:type=FREQUENCY|freq=3/7|start=2026-04-01")
        assertEquals(3, frequency.numerator)
        assertEquals(7, frequency.denominator)
    }

    @Test
    fun `fromRule parses canonical num den habits`() {
        val frequency = RecurrenceScoreCalculator.fromRule("5/7!start=2026-04-01")
        assertEquals(5, frequency.numerator)
        assertEquals(7, frequency.denominator)
    }

    @Test
    fun `calculateCompletionStats returns default for empty list`() {
        val stats = RecurrenceScoreCalculator.calculateCompletionStats(emptyList())
        assertEquals(0.0, stats.completionRate7Days, 0.001)
        assertEquals(0.0, stats.completionRate30Days, 0.001)
        assertEquals(0.0, stats.completionRate90Days, 0.001)
        assertEquals(0, stats.currentStreak)
        assertEquals(0, stats.longestStreak)
    }

    @Test
    fun `calculateCompletionStats calculates correct rate`() {
        val occurrences = listOf(
            1 to "completed",
            2 to "completed",
            3 to "missed",
            4 to "completed"
        )
        val stats = RecurrenceScoreCalculator.calculateCompletionStats(occurrences)
        // 3 completed out of 4 = 75%
        assertEquals(0.75, stats.completionRate7Days, 0.001)
    }

    @Test
    fun `calculateCompletionStats tracks streaks`() {
        val occurrences = listOf(
            1 to "completed",
            2 to "completed",
            3 to "completed",
            4 to "missed",
            5 to "completed"
        )
        val stats = RecurrenceScoreCalculator.calculateCompletionStats(occurrences)
        assertTrue(stats.longestStreak >= 3)
    }

    @Test
    fun `CompletionStats data class holds all fields`() {
        val stats = CompletionStats(
            completionRate7Days = 0.8,
            completionRate30Days = 0.75,
            completionRate90Days = 0.7,
            allTimeRate = 0.65,
            currentStreak = 5,
            longestStreak = 10
        )
        assertEquals(0.8, stats.completionRate7Days, 0.001)
        assertEquals(0.75, stats.completionRate30Days, 0.001)
        assertEquals(0.7, stats.completionRate90Days, 0.001)
        assertEquals(0.65, stats.allTimeRate, 0.001)
        assertEquals(5, stats.currentStreak)
        assertEquals(10, stats.longestStreak)
    }
    
    // ==================== calculateFrequencyAwareStats Tests ====================
    
    @Test
    fun `calculateFrequencyAwareStats returns default for empty list`() {
        val config = io.payanam.domain.model.RecurrenceConfig.daily()
        val firstOccurrence = java.time.LocalDate.of(2024, 1, 1)
        val stats = RecurrenceScoreCalculator.calculateFrequencyAwareStats(emptyMap(), config, firstOccurrence)
        
        assertEquals(0.0, stats.completionRate7Days, 0.001)
        assertEquals(0, stats.currentStreak)
    }
    
    @Test
    fun `calculateFrequencyAwareStats for daily habit counts all days`() {
        val config = io.payanam.domain.model.RecurrenceConfig.daily()
        val firstOccurrence = java.time.LocalDate.now().minusDays(6)
        
        // Complete every day for 7 days
        val occurrences = (0L until 7).associate { dayOffset ->
            val date = firstOccurrence.plusDays(dayOffset)
            date to "completed"
        }
        
        val stats = RecurrenceScoreCalculator.calculateFrequencyAwareStats(occurrences, config, firstOccurrence)
        assertEquals(1.0, stats.completionRate7Days, 0.001)
        assertEquals(7, stats.currentStreak)
    }
    
    @Test
    fun `calculateFrequencyAwareStats for weekly habit only counts scheduled days`() {
        val config = io.payanam.domain.model.RecurrenceConfig.specificWeekdays(setOf(1)) // Monday only
        
        // Find the most recent Monday
        val today = java.time.LocalDate.now()
        val mostRecentMonday = today.with(java.time.DayOfWeek.MONDAY)
        val firstOccurrence = if (mostRecentMonday.isAfter(today)) {
            mostRecentMonday.minusWeeks(1)
        } else {
            mostRecentMonday
        }
        
        // Complete on this Monday
        val occurrences = mapOf(
            firstOccurrence to "completed"
        )
        
        // With weekly (Monday only), completion should be 100% since we completed the only scheduled day
        val stats = RecurrenceScoreCalculator.calculateFrequencyAwareStats(occurrences, config, firstOccurrence)
        assertEquals(1.0, stats.allTimeRate, 0.001)
    }
    
    @Test
    fun `calculateFrequencyAwareStats tracks skipped days correctly`() {
        val config = io.payanam.domain.model.RecurrenceConfig.daily()
        val firstOccurrence = java.time.LocalDate.now().minusDays(6)
        
        // Complete all days except one
        val occurrences = (0L until 7).associate { dayOffset ->
            val date = firstOccurrence.plusDays(dayOffset)
            date to if (dayOffset == 3L) "skipped" else "completed"
        }
        
        val stats = RecurrenceScoreCalculator.calculateFrequencyAwareStats(occurrences, config, firstOccurrence)
        // Skipped should not count as missed, 6/6 = 100%
        assertEquals(1.0, stats.completionRate7Days, 0.01)
    }
    
    @Test
    fun `calculateFrequencyAwareStats counts missed scheduled days`() {
        val config = io.payanam.domain.model.RecurrenceConfig.daily()
        val firstOccurrence = java.time.LocalDate.now().minusDays(6)
        
        // Only complete 3 out of 7 days
        val occurrences = (0L until 3).associate { dayOffset ->
            val date = firstOccurrence.plusDays(dayOffset)
            date to "completed"
        }
        // Days 4-7 have no entries = missed
        
        val stats = RecurrenceScoreCalculator.calculateFrequencyAwareStats(occurrences, config, firstOccurrence)
        // 3 completed, 4 missed = ~43%
        assertTrue(stats.completionRate7Days < 0.5)
    }
    
    @Test
    fun `calculateFrequencyAwareStats for Mon-Wed-Fri habit`() {
        val config = io.payanam.domain.model.RecurrenceConfig.specificWeekdays(setOf(1, 3, 5)) // Mon, Wed, Fri
        
        // Use today as reference
        val today = java.time.LocalDate.now()
        val startOfWeek = today.minusDays((today.dayOfWeek.value - 1).toLong())
        
        val monday = startOfWeek
        val wednesday = startOfWeek.plusDays(2)
        val friday = startOfWeek.plusDays(4)
        
        // Complete all 3 scheduled days that have passed in this week
        val occurrences = mutableMapOf<java.time.LocalDate, String>()
        if (!monday.isAfter(today)) occurrences[monday] = "completed"
        if (!wednesday.isAfter(today)) occurrences[wednesday] = "completed"
        if (!friday.isAfter(today)) occurrences[friday] = "completed"
        
        // First occurrence is Monday
        val firstOccurrence = monday
        
        // All passed scheduled days are completed = 100%
        val stats = RecurrenceScoreCalculator.calculateFrequencyAwareStats(occurrences, config, firstOccurrence)
        assertEquals(1.0, stats.allTimeRate, 0.001)
    }
    
    @Test
    fun `calculateFrequencyAwareStats for monthly habit`() {
        val config = io.payanam.domain.model.RecurrenceConfig.monthlyOnDates(15)
        
        // Use a date in the current month
        val today = java.time.LocalDate.now()
        val the15th = today.withDayOfMonth(15)
        
        // If 15th hasn't passed, use last month's 15th
        val firstOccurrence = if (the15th.isAfter(today)) {
            the15th.minusMonths(1)
        } else {
            the15th
        }
        
        // Complete on that 15th
        val occurrences = mapOf(
            firstOccurrence to "completed"
        )
        
        val stats = RecurrenceScoreCalculator.calculateFrequencyAwareStats(occurrences, config, firstOccurrence)
        assertEquals(1.0, stats.allTimeRate, 0.001)
    }
    
    @Test
    fun `calculateFrequencyAwareStats streak tracking with frequency awareness`() {
        val config = io.payanam.domain.model.RecurrenceConfig.specificWeekdays(setOf(1)) // Monday only
        
        // Find most recent Mondays
        val today = java.time.LocalDate.now()
        val thisMonday = today.with(java.time.DayOfWeek.MONDAY)
        
        // Build list of last 4 Mondays up to today
        val mondays = mutableListOf<java.time.LocalDate>()
        var monday = if (thisMonday.isAfter(today)) thisMonday.minusWeeks(1) else thisMonday
        repeat(4) {
            if (!monday.isAfter(today)) {
                mondays.add(0, monday) // Insert at beginning for chronological order
            }
            monday = monday.minusWeeks(1)
        }
        
        // Complete all of them
        val occurrences = mondays.associateWith { "completed" }
        val firstOccurrence = mondays.firstOrNull() ?: today
        
        val stats = RecurrenceScoreCalculator.calculateFrequencyAwareStats(occurrences, config, firstOccurrence)
        // Should have streak equal to number of Mondays completed
        assertTrue("Current streak should be >= 1, got ${stats.currentStreak}", stats.currentStreak >= 1)
        assertTrue("Longest streak should be >= 1, got ${stats.longestStreak}", stats.longestStreak >= 1)
    }

    @Test
    fun `parseRRuleToFrequency handles complex RRULE strings`() {
        val rule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR"
        val frequency = RecurrenceScoreCalculator.parseRRuleToFrequency(rule)
        assertEquals(3, frequency.numerator) // 3 days specified
        assertEquals(14, frequency.denominator) // 7 * 2 (weekly interval)
    }

    @Test
    fun `parseRRuleToFrequency handles YEARLY frequency`() {
        val rule = "FREQ=YEARLY;INTERVAL=1"
        val frequency = RecurrenceScoreCalculator.parseRRuleToFrequency(rule)
        assertEquals(1, frequency.numerator)
        assertEquals(365, frequency.denominator)
    }

    @Test
    fun `Frequency toDouble converts correctly`() {
        val freq1 = RecurrenceScoreCalculator.Frequency(1, 1) // Daily
        assertEquals(1.0, freq1.toDouble(), 0.001)

        val freq2 = RecurrenceScoreCalculator.Frequency(1, 7) // Weekly
        assertEquals(1.0/7.0, freq2.toDouble(), 0.001)

        val freq3 = RecurrenceScoreCalculator.Frequency(3, 7) // 3 times per week
        assertEquals(3.0/7.0, freq3.toDouble(), 0.001)
    }

    @Test
    fun `Frequency companion constants are initialized correctly`() {
        assertEquals(1, RecurrenceScoreCalculator.Frequency.DAILY.numerator)
        assertEquals(1, RecurrenceScoreCalculator.Frequency.DAILY.denominator)
        assertEquals(1, RecurrenceScoreCalculator.Frequency.EVERY_OTHER_DAY.numerator)
        assertEquals(2, RecurrenceScoreCalculator.Frequency.EVERY_OTHER_DAY.denominator)
        assertEquals(1, RecurrenceScoreCalculator.Frequency.WEEKLY.numerator)
        assertEquals(7, RecurrenceScoreCalculator.Frequency.WEEKLY.denominator)
        assertEquals(2, RecurrenceScoreCalculator.Frequency.TWO_PER_WEEK.numerator)
        assertEquals(7, RecurrenceScoreCalculator.Frequency.TWO_PER_WEEK.denominator)
        assertEquals(3, RecurrenceScoreCalculator.Frequency.THREE_PER_WEEK.numerator)
        assertEquals(7, RecurrenceScoreCalculator.Frequency.THREE_PER_WEEK.denominator)
        assertEquals(1, RecurrenceScoreCalculator.Frequency.MONTHLY.numerator)
        assertEquals(30, RecurrenceScoreCalculator.Frequency.MONTHLY.denominator)
        assertEquals(1, RecurrenceScoreCalculator.Frequency.YEARLY.numerator)
        assertEquals(365, RecurrenceScoreCalculator.Frequency.YEARLY.denominator)
    }

    @Test
    fun `calculateFrequencyAwareStats for canonical frequency uses window targets`() {
        val frequency = DomainFrequency(3, 7, java.time.LocalDate.of(2026, 4, 1))
        val today = java.time.LocalDate.of(2026, 4, 7)
        val occurrences = mapOf(
            java.time.LocalDate.of(2026, 4, 1) to "completed",
            java.time.LocalDate.of(2026, 4, 3) to "completed",
            java.time.LocalDate.of(2026, 4, 5) to "completed",
        )

        val stats = RecurrenceScoreCalculator.calculateFrequencyAwareStats(
            occurrences = occurrences,
            frequency = frequency,
            anchorDate = frequency.anchorDate!!,
            today = today,
        )

        assertEquals(1.0, stats.completionRate7Days, 0.001)
        assertEquals(1, stats.currentStreak)
        assertEquals(1, stats.longestStreak)
    }

    @Test
    fun `calculateFrequencyAwareStats treats skipped days as target neutral`() {
        val frequency = DomainFrequency(2, 7, java.time.LocalDate.of(2026, 4, 1))
        val today = java.time.LocalDate.of(2026, 4, 7)
        val occurrences = mapOf(
            java.time.LocalDate.of(2026, 4, 2) to "completed",
            java.time.LocalDate.of(2026, 4, 5) to "skipped",
        )

        val stats = RecurrenceScoreCalculator.calculateFrequencyAwareStats(
            occurrences = occurrences,
            frequency = frequency,
            anchorDate = frequency.anchorDate!!,
            today = today,
        )

        assertEquals(1.0, stats.completionRate7Days, 0.001)
    }
}
