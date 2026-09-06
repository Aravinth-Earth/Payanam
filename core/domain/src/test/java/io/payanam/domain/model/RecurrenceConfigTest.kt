//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
class RecurrenceConfigTest {
    
    // ==================== Factory Method Tests ====================
    
    @Test
    fun `daily() creates correct config`() {
        val config = RecurrenceConfig.daily()
        assertEquals(RecurrenceType.DAILY, config.type)
        assertEquals("Daily", config.displayName)
    }
    
    @Test
    fun `weekdays() creates correct config`() {
        val config = RecurrenceConfig.weekdays()
        assertEquals(RecurrenceType.WEEKDAYS_ONLY, config.type)
        assertEquals("Weekdays", config.displayName)
    }
    
    @Test
    fun `specificWeekdays() creates correct config for single day`() {
        val config = RecurrenceConfig.specificWeekdays(setOf(1)) // Monday
        assertEquals(RecurrenceType.SPECIFIC_WEEKDAYS, config.type)
        assertEquals(setOf(1), config.weekdays)
        assertEquals("Weekly (Mon)", config.displayName)
    }
    
    @Test
    fun `specificWeekdays() creates correct config for multiple days`() {
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 3, 5)) // Mon, Wed, Fri
        assertEquals(RecurrenceType.SPECIFIC_WEEKDAYS, config.type)
        assertEquals(setOf(1, 3, 5), config.weekdays)
        assertEquals("Mon, Wed, Fri", config.displayName)
    }
    
    @Test
    fun `specificWeekdays() shows count for many days`() {
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 2, 3, 4, 5)) // 5 days
        assertEquals("5 days/week", config.displayName)
    }
    
    @Test
    fun `monthlyOnDates() creates correct config for single date`() {
        val config = RecurrenceConfig.monthlyOnDates(15)
        assertEquals(RecurrenceType.MONTHLY_DATES, config.type)
        assertEquals(setOf(15), config.monthlyDates)
        assertEquals("Monthly (15th)", config.displayName)
    }
    
    @Test
    fun `monthlyOnDates() creates correct config for multiple dates`() {
        val config = RecurrenceConfig.monthlyOnDates(1, 15)
        assertEquals(RecurrenceType.MONTHLY_DATES, config.type)
        assertEquals(setOf(1, 15), config.monthlyDates)
        assertEquals("Monthly: 1st, 15th", config.displayName)
    }
    
    @Test
    fun `monthlyOnDates() handles last day of month (32)`() {
        val config = RecurrenceConfig.monthlyOnDates(32)
        assertEquals(setOf(32), config.monthlyDates)
        assertEquals("Monthly (last)", config.displayName)
    }
    
    @Test
    fun `everyNDays() creates correct config`() {
        val config = RecurrenceConfig.everyNDays(3)
        assertEquals(RecurrenceType.INTERVAL, config.type)
        assertEquals(3, config.intervalDays)
        assertEquals("Every 3 days", config.displayName)
    }
    
    @Test
    fun `everyNDays(2) shows every other day`() {
        val config = RecurrenceConfig.everyNDays(2)
        assertEquals("Every other day", config.displayName)
    }
    
    @Test
    fun `everyNDays(7) shows weekly`() {
        val config = RecurrenceConfig.everyNDays(7)
        assertEquals("Weekly", config.displayName)
    }
    
    @Test
    fun `everyNDays(14) shows bi-weekly`() {
        val config = RecurrenceConfig.everyNDays(14)
        assertEquals("Bi-weekly", config.displayName)
    }
    
    @Test
    fun `yearly() creates correct config`() {
        val config = RecurrenceConfig.yearly()
        assertEquals(RecurrenceType.YEARLY, config.type)
        assertEquals("Yearly", config.displayName)
    }
    
    @Test
    fun `timesPerWeek() creates correct config`() {
        val config = RecurrenceConfig.timesPerWeek(3)
        assertEquals(RecurrenceType.FREQUENCY, config.type)
        assertEquals(3, config.frequencyNumerator)
        assertEquals(7, config.frequencyDenominator)
        assertEquals("3×/week", config.displayName)
    }
    
    // ==================== isScheduledDay Tests ====================
    
    @Test
    fun `daily isScheduledDay returns true for any date`() {
        val config = RecurrenceConfig.daily()
        val monday = LocalDate.of(2024, 1, 8) // Monday
        val saturday = LocalDate.of(2024, 1, 13)
        assertTrue(config.isScheduledDay(monday))
        assertTrue(config.isScheduledDay(saturday))
    }
    
    @Test
    fun `weekdays isScheduledDay returns true for weekdays only`() {
        val config = RecurrenceConfig.weekdays()
        val monday = LocalDate.of(2024, 1, 8)
        val saturday = LocalDate.of(2024, 1, 13)
        val sunday = LocalDate.of(2024, 1, 14)
        assertTrue(config.isScheduledDay(monday))
        assertFalse(config.isScheduledDay(saturday))
        assertFalse(config.isScheduledDay(sunday))
    }
    
    @Test
    fun `specificWeekdays isScheduledDay returns true only for selected days`() {
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 3, 5)) // Mon, Wed, Fri
        val monday = LocalDate.of(2024, 1, 8)
        val tuesday = LocalDate.of(2024, 1, 9)
        val wednesday = LocalDate.of(2024, 1, 10)
        val friday = LocalDate.of(2024, 1, 12)
        assertTrue(config.isScheduledDay(monday))
        assertFalse(config.isScheduledDay(tuesday))
        assertTrue(config.isScheduledDay(wednesday))
        assertTrue(config.isScheduledDay(friday))
    }
    
    @Test
    fun `monthlyDates isScheduledDay returns true for specified dates`() {
        val config = RecurrenceConfig.monthlyOnDates(1, 15)
        val first = LocalDate.of(2024, 1, 1)
        val fifteenth = LocalDate.of(2024, 1, 15)
        val tenth = LocalDate.of(2024, 1, 10)
        assertTrue(config.isScheduledDay(first))
        assertTrue(config.isScheduledDay(fifteenth))
        assertFalse(config.isScheduledDay(tenth))
    }
    
    @Test
    fun `monthlyDates isScheduledDay handles last day of month (32)`() {
        val config = RecurrenceConfig.monthlyOnDates(32)
        val jan31 = LocalDate.of(2024, 1, 31) // Last day of Jan
        val feb29 = LocalDate.of(2024, 2, 29) // Last day of Feb (leap year)
        val jan30 = LocalDate.of(2024, 1, 30) // Not last day
        assertTrue(config.isScheduledDay(jan31))
        assertTrue(config.isScheduledDay(feb29))
        assertFalse(config.isScheduledDay(jan30))
    }
    
    @Test
    fun `interval isScheduledDay respects start date`() {
        val startDate = LocalDate.of(2024, 1, 1)
        val config = RecurrenceConfig.everyNDays(3, startDate)
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 1)))  // Day 0
        assertFalse(config.isScheduledDay(LocalDate.of(2024, 1, 2))) // Day 1
        assertFalse(config.isScheduledDay(LocalDate.of(2024, 1, 3))) // Day 2
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 4)))  // Day 3
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 7)))  // Day 6
    }
    
    @Test
    fun `yearly isScheduledDay matches same day and month`() {
        val startDate = LocalDate.of(2024, 3, 15)
        val config = RecurrenceConfig.yearly(startDate)
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 3, 15)))
        assertTrue(config.isScheduledDay(LocalDate.of(2025, 3, 15)))
        assertFalse(config.isScheduledDay(LocalDate.of(2024, 3, 16)))
        assertFalse(config.isScheduledDay(LocalDate.of(2024, 4, 15)))
    }
    
    @Test
    fun `isScheduledDay respects start date boundary`() {
        val config = RecurrenceConfig.daily(startDate = LocalDate.of(2024, 1, 10))
        assertFalse(config.isScheduledDay(LocalDate.of(2024, 1, 5)))
        assertFalse(config.isScheduledDay(LocalDate.of(2024, 1, 9)))
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 10)))
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 15)))
    }
    
    // ==================== getScheduledDatesInRange Tests ====================
    
    @Test
    fun `getScheduledDatesInRange for daily returns all dates`() {
        val config = RecurrenceConfig.daily()
        val start = LocalDate.of(2024, 1, 1)
        val end = LocalDate.of(2024, 1, 5)
        val dates = config.getScheduledDatesInRange(start, end)
        assertEquals(5, dates.size)
    }
    
    @Test
    fun `getScheduledDatesInRange for weekdays excludes weekends`() {
        val config = RecurrenceConfig.weekdays()
        val start = LocalDate.of(2024, 1, 8) // Monday
        val end = LocalDate.of(2024, 1, 14)   // Sunday
        val dates = config.getScheduledDatesInRange(start, end)
        assertEquals(5, dates.size) // Mon-Fri only
    }
    
    @Test
    fun `countScheduledOccurrences returns correct count`() {
        val config = RecurrenceConfig.specificWeekdays(setOf(1)) // Weekly on Monday
        val start = LocalDate.of(2024, 1, 1)
        val end = LocalDate.of(2024, 1, 31)
        val count = config.countScheduledOccurrences(start, end)
        assertEquals(5, count) // 5 Mondays in January 2024
    }
    
    // ==================== toRRule Tests ====================
    
    @Test
    fun `toRRule for daily`() {
        val config = RecurrenceConfig.daily()
        assertEquals("FREQ=DAILY;INTERVAL=1", config.toRRule())
    }
    
    @Test
    fun `toRRule for weekdays`() {
        val config = RecurrenceConfig.weekdays()
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", config.toRRule())
    }
    
    @Test
    fun `toRRule for specific weekdays`() {
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 3, 5)) // Mon, Wed, Fri
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", config.toRRule())
    }
    
    @Test
    fun `toRRule for monthly dates`() {
        val config = RecurrenceConfig.monthlyOnDates(1, 15)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=1,15", config.toRRule())
    }
    
    @Test
    fun `toRRule for interval`() {
        val config = RecurrenceConfig.everyNDays(5)
        assertEquals("FREQ=DAILY;INTERVAL=5", config.toRRule())
    }
    
    @Test
    fun `toRRule for yearly`() {
        val config = RecurrenceConfig.yearly()
        assertEquals("FREQ=YEARLY;INTERVAL=1", config.toRRule())
    }
    
    // ==================== parse Tests ====================
    
    @Test
    fun `parse FREQ=DAILY`() {
        val config = RecurrenceConfig.parse("FREQ=DAILY")
        assertEquals(RecurrenceType.DAILY, config.type)
    }
    
    @Test
    fun `parse FREQ=WEEKLY with BYDAY`() {
        val config = RecurrenceConfig.parse("FREQ=WEEKLY;BYDAY=MO,WE,FR")
        assertEquals(RecurrenceType.SPECIFIC_WEEKDAYS, config.type)
        assertEquals(setOf(1, 3, 5), config.weekdays)
    }
    
    @Test
    fun `parse FREQ=MONTHLY with BYMONTHDAY`() {
        val config = RecurrenceConfig.parse("FREQ=MONTHLY;BYMONTHDAY=1,15")
        assertEquals(RecurrenceType.MONTHLY_DATES, config.type)
        assertEquals(setOf(1, 15), config.monthlyDates)
    }
    
    @Test
    fun `parse FREQ=YEARLY`() {
        val config = RecurrenceConfig.parse("FREQ=YEARLY")
        assertEquals(RecurrenceType.YEARLY, config.type)
    }
    
    @Test
    fun `parse with INTERVAL`() {
        val config = RecurrenceConfig.parse("FREQ=DAILY;INTERVAL=3")
        assertEquals(RecurrenceType.INTERVAL, config.type)
        assertEquals(3, config.intervalDays)
    }
    
    @Test
    fun `parse null returns daily default`() {
        val config = RecurrenceConfig.parse(null)
        assertEquals(RecurrenceType.DAILY, config.type)
    }
    
    @Test
    fun `parse empty returns daily default`() {
        val config = RecurrenceConfig.parse("")
        assertEquals(RecurrenceType.DAILY, config.type)
    }
    
    @Test
    fun `parse weekdays preset`() {
        val config = RecurrenceConfig.parse("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
        assertEquals(RecurrenceType.WEEKDAYS_ONLY, config.type)
    }
    
    // ==================== toFrequency Tests ====================
    
    @Test
    fun `toFrequency for daily returns 1 to 1`() {
        val config = RecurrenceConfig.daily()
        assertEquals(1 to 1, config.toFrequency())
    }
    
    @Test
    fun `toFrequency for weekdays returns 5 to 7`() {
        val config = RecurrenceConfig.weekdays()
        assertEquals(5 to 7, config.toFrequency())
    }
    
    @Test
    fun `toFrequency for specific weekdays`() {
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 3, 5))
        assertEquals(3 to 7, config.toFrequency())
    }
    
    @Test
    fun `toFrequency for monthly`() {
        val config = RecurrenceConfig.monthlyOnDates(1, 15)
        assertEquals(2 to 30, config.toFrequency())
    }
    
    @Test
    fun `toFrequency for interval`() {
        val config = RecurrenceConfig.everyNDays(7)
        assertEquals(1 to 7, config.toFrequency())
    }
    
    @Test
    fun `toFrequency for yearly`() {
        val config = RecurrenceConfig.yearly()
        assertEquals(1 to 365, config.toFrequency())
    }
    
    // ==================== serialize/deserialize Tests ====================
    
    @Test
    fun `serialize creates valid string`() {
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 3, 5))
        val serialized = config.serialize()
        assertTrue(serialized.startsWith("CONFIG:"))
        assertTrue(serialized.contains("type=SPECIFIC_WEEKDAYS"))
        assertTrue(serialized.contains("weekdays=1,3,5"))
    }
    
    @Test
    fun `parse serialized config restores original`() {
        val original = RecurrenceConfig.specificWeekdays(setOf(1, 3, 5))
        val serialized = original.serialize()
        val parsed = RecurrenceConfig.parse(serialized)
        assertEquals(original.type, parsed.type)
        assertEquals(original.weekdays, parsed.weekdays)
    }
    
    // ==================== displayName suffix Tests ====================
    
    @Test
    fun `day suffix 1st`() {
        val config = RecurrenceConfig.monthlyOnDates(1)
        assertTrue(config.displayName.contains("1st"))
    }
    
    @Test
    fun `day suffix 2nd`() {
        val config = RecurrenceConfig.monthlyOnDates(2)
        assertTrue(config.displayName.contains("2nd"))
    }
    
    @Test
    fun `day suffix 3rd`() {
        val config = RecurrenceConfig.monthlyOnDates(3)
        assertTrue(config.displayName.contains("3rd"))
    }
    
    @Test
    fun `day suffix 4th`() {
        val config = RecurrenceConfig.monthlyOnDates(4)
        assertTrue(config.displayName.contains("4th"))
    }
    
    @Test
    fun `day suffix 11th special case`() {
        val config = RecurrenceConfig.monthlyOnDates(11)
        assertTrue(config.displayName.contains("11th"))
    }
    
    @Test
    fun `day suffix 21st`() {
        val config = RecurrenceConfig.monthlyOnDates(21)
        assertTrue(config.displayName.contains("21st"))
    }
    
    // ==================== RecurrenceType enum Tests ====================
    
    @Test
    fun `RecurrenceType has all expected values`() {
        val values = RecurrenceType.entries
        assertTrue(values.contains(RecurrenceType.DAILY))
        assertTrue(values.contains(RecurrenceType.WEEKDAYS_ONLY))
        assertTrue(values.contains(RecurrenceType.SPECIFIC_WEEKDAYS))
        assertTrue(values.contains(RecurrenceType.MONTHLY_DATES))
        assertTrue(values.contains(RecurrenceType.INTERVAL))
        assertTrue(values.contains(RecurrenceType.FREQUENCY))
        assertTrue(values.contains(RecurrenceType.YEARLY))
    }
    
    // ==================== Additional Parse Tests ====================
    
    @Test
    fun `parse FREQ=MONTHLY without BYMONTHDAY defaults to 1st`() {
        val config = RecurrenceConfig.parse("FREQ=MONTHLY")
        assertEquals(RecurrenceType.MONTHLY_DATES, config.type)
        assertEquals(setOf(1), config.monthlyDates)
    }
    
    @Test
    fun `parse handles all weekday codes`() {
        val config = RecurrenceConfig.parse("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR,SA,SU")
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7), config.weekdays)
    }
    
    @Test
    fun `parse handles invalid BYDAY code`() {
        val config = RecurrenceConfig.parse("FREQ=WEEKLY;BYDAY=XX,MO")
        // Should still parse MO, ignore XX
        assertTrue(config.weekdays.contains(1))
    }
    
    @Test
    fun `parse serialized config with interval`() {
        val original = RecurrenceConfig.everyNDays(5)
        val serialized = original.serialize()
        val parsed = RecurrenceConfig.parse(serialized)
        assertEquals(RecurrenceType.INTERVAL, parsed.type)
        assertEquals(5, parsed.intervalDays)
    }
    
    @Test
    fun `parse serialized config with monthly dates`() {
        val original = RecurrenceConfig.monthlyOnDates(1, 15, 32)
        val serialized = original.serialize()
        val parsed = RecurrenceConfig.parse(serialized)
        assertEquals(RecurrenceType.MONTHLY_DATES, parsed.type)
        assertEquals(setOf(1, 15, 32), parsed.monthlyDates)
    }
    
    @Test
    fun `parse serialized config with frequency`() {
        val original = RecurrenceConfig.timesPerWeek(3)
        val serialized = original.serialize()
        val parsed = RecurrenceConfig.parse(serialized)
        assertEquals(RecurrenceType.FREQUENCY, parsed.type)
        assertEquals(3, parsed.frequencyNumerator)
        assertEquals(7, parsed.frequencyDenominator)
    }
    
    @Test
    fun `parse serialized config with start date`() {
        val startDate = LocalDate.of(2024, 6, 15)
        val original = RecurrenceConfig.daily(startDate)
        val serialized = original.serialize()
        val parsed = RecurrenceConfig.parse(serialized)
        assertEquals(startDate, parsed.startDate)
    }
    
    @Test
    fun `parse RRULE with unknown FREQ defaults to daily`() {
        val config = RecurrenceConfig.parse("FREQ=UNKNOWN")
        assertEquals(RecurrenceType.DAILY, config.type)
    }
    
    // ==================== toRRule additional tests ====================
    
    @Test
    fun `toRRule for frequency daily`() {
        val config = RecurrenceConfig.timesPerWeek(7) // essentially daily
        val rrule = config.toRRule()
        assertTrue(rrule.contains("FREQ="))
    }
    
    @Test
    fun `toRRule for frequency weekly`() {
        val config = RecurrenceConfig.timesPerWeek(1)
        val rrule = config.toRRule()
        assertTrue(rrule.contains("FREQ=WEEKLY"))
    }
    
    @Test
    fun `toRRule for frequency monthly`() {
        val config = RecurrenceConfig(
            type = RecurrenceType.FREQUENCY,
            frequencyNumerator = 1,
            frequencyDenominator = 30
        )
        val rrule = config.toRRule()
        assertTrue(rrule.contains("FREQ=MONTHLY"))
    }
    
    @Test
    fun `toRRule for frequency with arbitrary denominator`() {
        val config = RecurrenceConfig(
            type = RecurrenceType.FREQUENCY,
            frequencyNumerator = 2,
            frequencyDenominator = 10 // Every 5 days
        )
        val rrule = config.toRRule()
        assertTrue(rrule.contains("INTERVAL=5"))
    }
    
    // ==================== isScheduledDay edge cases ====================
    
    @Test
    fun `isScheduledDay for frequency returns true for any date`() {
        val config = RecurrenceConfig.timesPerWeek(3)
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 1)))
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 12, 31)))
    }
    
    @Test
    fun `isScheduledDay for yearly without startDate returns true`() {
        val config = RecurrenceConfig.yearly()
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 1)))
    }
    
    @Test
    fun `isScheduledDay for interval without startDate returns true`() {
        val config = RecurrenceConfig.everyNDays(3)
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 1)))
    }
    
    // ==================== serialize edge cases ====================
    
    @Test
    fun `serialize with empty weekdays does not include weekdays`() {
        val config = RecurrenceConfig.daily()
        val serialized = config.serialize()
        assertFalse(serialized.contains("weekdays="))
    }
    
    @Test
    fun `serialize with empty monthlyDates does not include monthlyDates`() {
        val config = RecurrenceConfig.daily()
        val serialized = config.serialize()
        assertFalse(serialized.contains("monthlyDates="))
    }
    
    @Test
    fun `serialize weekdays config includes weekdays`() {
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 5))
        val serialized = config.serialize()
        assertTrue(serialized.contains("weekdays=1,5"))
    }
    
    // ==================== specificWeekdays with DayOfWeek ====================
    
    @Test
    fun `specificWeekdays with DayOfWeek enum`() {
        val config = RecurrenceConfig.specificWeekdays(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY)
        assertEquals(setOf(1, 5), config.weekdays)
    }
    
    // ==================== parseConfig edge cases ====================
    
    @Test
    fun `parse CONFIG with invalid date ignores it`() {
        val config = RecurrenceConfig.parse("CONFIG:type=DAILY|start=invalid-date")
        assertNull(config.startDate)
    }
    
    // ==================== Data class Tests ====================
    
    @Test
    fun `data class copy works correctly`() {
        val original = RecurrenceConfig.daily()
        val modified = original.copy(type = RecurrenceType.YEARLY)
        assertEquals(RecurrenceType.DAILY, original.type)
        assertEquals(RecurrenceType.YEARLY, modified.type)
    }
    
    @Test
    fun `data class equals works correctly`() {
        val config1 = RecurrenceConfig.specificWeekdays(setOf(1, 3))
        val config2 = RecurrenceConfig.specificWeekdays(setOf(1, 3))
        val config3 = RecurrenceConfig.specificWeekdays(setOf(1, 5))
        assertEquals(config1, config2)
        assertFalse(config1 == config3)
    }
    
    @Test
    fun `data class hashCode is consistent`() {
        val config1 = RecurrenceConfig.daily()
        val config2 = RecurrenceConfig.daily()
        assertEquals(config1.hashCode(), config2.hashCode())
    }
    
    @Test
    fun `data class toString includes type`() {
        val config = RecurrenceConfig.daily()
        assertTrue(config.toString().contains("DAILY"))
    }
}
