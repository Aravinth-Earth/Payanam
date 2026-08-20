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

/**
 * RecurrenceConfigTest.
 */
class RecurrenceConfigTest {
    
    // ==================== Factory Method Tests ====================
    
    @Test
    fun `daily() creates correct config`() {
        /** Config. */
        val config = RecurrenceConfig.daily()
        /** Assert equals. */
        assertEquals(RecurrenceType.DAILY, config.type)
        /** Assert equals. */
        assertEquals("Daily", config.displayName)
    }
    
    @Test
    fun `weekdays() creates correct config`() {
        /** Config. */
        val config = RecurrenceConfig.weekdays()
        /** Assert equals. */
        assertEquals(RecurrenceType.WEEKDAYS_ONLY, config.type)
        /** Assert equals. */
        assertEquals("Weekdays", config.displayName)
    }
    
    @Test
    fun `specificWeekdays() creates correct config for single day`() {
        /** Config. */
        val config = RecurrenceConfig.specificWeekdays(setOf(1)) // Monday
        /** Assert equals. */
        assertEquals(RecurrenceType.SPECIFIC_WEEKDAYS, config.type)
        /** Assert equals. */
        assertEquals(setOf(1), config.weekdays)
        /** Assert equals. */
        assertEquals("Weekly (Mon)", config.displayName)
    }
    
    @Test
    fun `specificWeekdays() creates correct config for multiple days`() {
        /** Config. */
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 3, 5)) // Mon, Wed, Fri
        /** Assert equals. */
        assertEquals(RecurrenceType.SPECIFIC_WEEKDAYS, config.type)
        /** Assert equals. */
        assertEquals(setOf(1, 3, 5), config.weekdays)
        /** Assert equals. */
        assertEquals("Mon, Wed, Fri", config.displayName)
    }
    
    @Test
    fun `specificWeekdays() shows count for many days`() {
        /** Config. */
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 2, 3, 4, 5)) // 5 days
        /** Assert equals. */
        assertEquals("5 days/week", config.displayName)
    }
    
    @Test
    fun `monthlyOnDates() creates correct config for single date`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(15)
        /** Assert equals. */
        assertEquals(RecurrenceType.MONTHLY_DATES, config.type)
        /** Assert equals. */
        assertEquals(setOf(15), config.monthlyDates)
        /** Assert equals. */
        assertEquals("Monthly (15th)", config.displayName)
    }
    
    @Test
    fun `monthlyOnDates() creates correct config for multiple dates`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(1, 15)
        /** Assert equals. */
        assertEquals(RecurrenceType.MONTHLY_DATES, config.type)
        /** Assert equals. */
        assertEquals(setOf(1, 15), config.monthlyDates)
        /** Assert equals. */
        assertEquals("Monthly: 1st, 15th", config.displayName)
    }
    
    @Test
    fun `monthlyOnDates() handles last day of month (32)`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(32)
        /** Assert equals. */
        assertEquals(setOf(32), config.monthlyDates)
        /** Assert equals. */
        assertEquals("Monthly (last)", config.displayName)
    }
    
    @Test
    fun `everyNDays() creates correct config`() {
        /** Config. */
        val config = RecurrenceConfig.everyNDays(3)
        /** Assert equals. */
        assertEquals(RecurrenceType.INTERVAL, config.type)
        /** Assert equals. */
        assertEquals(3, config.intervalDays)
        /** Assert equals. */
        assertEquals("Every 3 days", config.displayName)
    }
    
    @Test
    fun `everyNDays(2) shows every other day`() {
        /** Config. */
        val config = RecurrenceConfig.everyNDays(2)
        /** Assert equals. */
        assertEquals("Every other day", config.displayName)
    }
    
    @Test
    fun `everyNDays(7) shows weekly`() {
        /** Config. */
        val config = RecurrenceConfig.everyNDays(7)
        /** Assert equals. */
        assertEquals("Weekly", config.displayName)
    }
    
    @Test
    fun `everyNDays(14) shows bi-weekly`() {
        /** Config. */
        val config = RecurrenceConfig.everyNDays(14)
        /** Assert equals. */
        assertEquals("Bi-weekly", config.displayName)
    }
    
    @Test
    fun `yearly() creates correct config`() {
        /** Config. */
        val config = RecurrenceConfig.yearly()
        /** Assert equals. */
        assertEquals(RecurrenceType.YEARLY, config.type)
        /** Assert equals. */
        assertEquals("Yearly", config.displayName)
    }
    
    @Test
    fun `timesPerWeek() creates correct config`() {
        /** Config. */
        val config = RecurrenceConfig.timesPerWeek(3)
        /** Assert equals. */
        assertEquals(RecurrenceType.FREQUENCY, config.type)
        /** Assert equals. */
        assertEquals(3, config.frequencyNumerator)
        /** Assert equals. */
        assertEquals(7, config.frequencyDenominator)
        /** Assert equals. */
        assertEquals("3×/week", config.displayName)
    }
    
    // ==================== isScheduledDay Tests ====================
    
    @Test
    fun `daily isScheduledDay returns true for any date`() {
        /** Config. */
        val config = RecurrenceConfig.daily()
        /** Monday. */
        val monday = LocalDate.of(2024, 1, 8) // Monday
        /** Saturday. */
        val saturday = LocalDate.of(2024, 1, 13)
        
        /** Assert true. */
        assertTrue(config.isScheduledDay(monday))
        /** Assert true. */
        assertTrue(config.isScheduledDay(saturday))
    }
    
    @Test
    fun `weekdays isScheduledDay returns true for weekdays only`() {
        /** Config. */
        val config = RecurrenceConfig.weekdays()
        /** Monday. */
        val monday = LocalDate.of(2024, 1, 8)
        /** Saturday. */
        val saturday = LocalDate.of(2024, 1, 13)
        /** Sunday. */
        val sunday = LocalDate.of(2024, 1, 14)
        
        /** Assert true. */
        assertTrue(config.isScheduledDay(monday))
        /** Assert false. */
        assertFalse(config.isScheduledDay(saturday))
        /** Assert false. */
        assertFalse(config.isScheduledDay(sunday))
    }
    
    @Test
    fun `specificWeekdays isScheduledDay returns true only for selected days`() {
        /** Config. */
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 3, 5)) // Mon, Wed, Fri
        /** Monday. */
        val monday = LocalDate.of(2024, 1, 8)
        /** Tuesday. */
        val tuesday = LocalDate.of(2024, 1, 9)
        /** Wednesday. */
        val wednesday = LocalDate.of(2024, 1, 10)
        /** Friday. */
        val friday = LocalDate.of(2024, 1, 12)
        
        /** Assert true. */
        assertTrue(config.isScheduledDay(monday))
        /** Assert false. */
        assertFalse(config.isScheduledDay(tuesday))
        /** Assert true. */
        assertTrue(config.isScheduledDay(wednesday))
        /** Assert true. */
        assertTrue(config.isScheduledDay(friday))
    }
    
    @Test
    fun `monthlyDates isScheduledDay returns true for specified dates`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(1, 15)
        /** First. */
        val first = LocalDate.of(2024, 1, 1)
        /** Fifteenth. */
        val fifteenth = LocalDate.of(2024, 1, 15)
        /** Tenth. */
        val tenth = LocalDate.of(2024, 1, 10)
        
        /** Assert true. */
        assertTrue(config.isScheduledDay(first))
        /** Assert true. */
        assertTrue(config.isScheduledDay(fifteenth))
        /** Assert false. */
        assertFalse(config.isScheduledDay(tenth))
    }
    
    @Test
    fun `monthlyDates isScheduledDay handles last day of month (32)`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(32)
        /** Jan31. */
        val jan31 = LocalDate.of(2024, 1, 31) // Last day of Jan
        /** Feb29. */
        val feb29 = LocalDate.of(2024, 2, 29) // Last day of Feb (leap year)
        /** Jan30. */
        val jan30 = LocalDate.of(2024, 1, 30) // Not last day
        
        /** Assert true. */
        assertTrue(config.isScheduledDay(jan31))
        /** Assert true. */
        assertTrue(config.isScheduledDay(feb29))
        /** Assert false. */
        assertFalse(config.isScheduledDay(jan30))
    }
    
    @Test
    fun `interval isScheduledDay respects start date`() {
        /** Start date. */
        val startDate = LocalDate.of(2024, 1, 1)
        /** Config. */
        val config = RecurrenceConfig.everyNDays(3, startDate)
        
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 1)))  // Day 0
        /** Assert false. */
        assertFalse(config.isScheduledDay(LocalDate.of(2024, 1, 2))) // Day 1
        /** Assert false. */
        assertFalse(config.isScheduledDay(LocalDate.of(2024, 1, 3))) // Day 2
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 4)))  // Day 3
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 7)))  // Day 6
    }
    
    @Test
    fun `yearly isScheduledDay matches same day and month`() {
        /** Start date. */
        val startDate = LocalDate.of(2024, 3, 15)
        /** Config. */
        val config = RecurrenceConfig.yearly(startDate)
        
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 3, 15)))
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2025, 3, 15)))
        /** Assert false. */
        assertFalse(config.isScheduledDay(LocalDate.of(2024, 3, 16)))
        /** Assert false. */
        assertFalse(config.isScheduledDay(LocalDate.of(2024, 4, 15)))
    }
    
    @Test
    fun `isScheduledDay respects start date boundary`() {
        /** Config. */
        val config = RecurrenceConfig.daily(startDate = LocalDate.of(2024, 1, 10))
        
        /** Assert false. */
        assertFalse(config.isScheduledDay(LocalDate.of(2024, 1, 5)))
        /** Assert false. */
        assertFalse(config.isScheduledDay(LocalDate.of(2024, 1, 9)))
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 10)))
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 15)))
    }
    
    // ==================== getScheduledDatesInRange Tests ====================
    
    @Test
    fun `getScheduledDatesInRange for daily returns all dates`() {
        /** Config. */
        val config = RecurrenceConfig.daily()
        /** Start. */
        val start = LocalDate.of(2024, 1, 1)
        /** End. */
        val end = LocalDate.of(2024, 1, 5)
        
        /** Dates. */
        val dates = config.getScheduledDatesInRange(start, end)
        /** Assert equals. */
        assertEquals(5, dates.size)
    }
    
    @Test
    fun `getScheduledDatesInRange for weekdays excludes weekends`() {
        /** Config. */
        val config = RecurrenceConfig.weekdays()
        /** Start. */
        val start = LocalDate.of(2024, 1, 8) // Monday
        /** End. */
        val end = LocalDate.of(2024, 1, 14)   // Sunday
        
        /** Dates. */
        val dates = config.getScheduledDatesInRange(start, end)
        /** Assert equals. */
        assertEquals(5, dates.size) // Mon-Fri only
    }
    
    @Test
    fun `countScheduledOccurrences returns correct count`() {
        /** Config. */
        val config = RecurrenceConfig.specificWeekdays(setOf(1)) // Weekly on Monday
        /** Start. */
        val start = LocalDate.of(2024, 1, 1)
        /** End. */
        val end = LocalDate.of(2024, 1, 31)
        
        /** Count. */
        val count = config.countScheduledOccurrences(start, end)
        /** Assert equals. */
        assertEquals(5, count) // 5 Mondays in January 2024
    }
    
    // ==================== toRRule Tests ====================
    
    @Test
    fun `toRRule for daily`() {
        /** Config. */
        val config = RecurrenceConfig.daily()
        /** Assert equals. */
        assertEquals("FREQ=DAILY;INTERVAL=1", config.toRRule())
    }
    
    @Test
    fun `toRRule for weekdays`() {
        /** Config. */
        val config = RecurrenceConfig.weekdays()
        /** Assert equals. */
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", config.toRRule())
    }
    
    @Test
    fun `toRRule for specific weekdays`() {
        /** Config. */
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 3, 5)) // Mon, Wed, Fri
        /** Assert equals. */
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE,FR", config.toRRule())
    }
    
    @Test
    fun `toRRule for monthly dates`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(1, 15)
        /** Assert equals. */
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=1,15", config.toRRule())
    }
    
    @Test
    fun `toRRule for interval`() {
        /** Config. */
        val config = RecurrenceConfig.everyNDays(5)
        /** Assert equals. */
        assertEquals("FREQ=DAILY;INTERVAL=5", config.toRRule())
    }
    
    @Test
    fun `toRRule for yearly`() {
        /** Config. */
        val config = RecurrenceConfig.yearly()
        /** Assert equals. */
        assertEquals("FREQ=YEARLY;INTERVAL=1", config.toRRule())
    }
    
    // ==================== parse Tests ====================
    
    @Test
    fun `parse FREQ=DAILY`() {
        /** Config. */
        val config = RecurrenceConfig.parse("FREQ=DAILY")
        /** Assert equals. */
        assertEquals(RecurrenceType.DAILY, config.type)
    }
    
    @Test
    fun `parse FREQ=WEEKLY with BYDAY`() {
        /** Config. */
        val config = RecurrenceConfig.parse("FREQ=WEEKLY;BYDAY=MO,WE,FR")
        /** Assert equals. */
        assertEquals(RecurrenceType.SPECIFIC_WEEKDAYS, config.type)
        /** Assert equals. */
        assertEquals(setOf(1, 3, 5), config.weekdays)
    }
    
    @Test
    fun `parse FREQ=MONTHLY with BYMONTHDAY`() {
        /** Config. */
        val config = RecurrenceConfig.parse("FREQ=MONTHLY;BYMONTHDAY=1,15")
        /** Assert equals. */
        assertEquals(RecurrenceType.MONTHLY_DATES, config.type)
        /** Assert equals. */
        assertEquals(setOf(1, 15), config.monthlyDates)
    }
    
    @Test
    fun `parse FREQ=YEARLY`() {
        /** Config. */
        val config = RecurrenceConfig.parse("FREQ=YEARLY")
        /** Assert equals. */
        assertEquals(RecurrenceType.YEARLY, config.type)
    }
    
    @Test
    fun `parse with INTERVAL`() {
        /** Config. */
        val config = RecurrenceConfig.parse("FREQ=DAILY;INTERVAL=3")
        /** Assert equals. */
        assertEquals(RecurrenceType.INTERVAL, config.type)
        /** Assert equals. */
        assertEquals(3, config.intervalDays)
    }
    
    @Test
    fun `parse null returns daily default`() {
        /** Config. */
        val config = RecurrenceConfig.parse(null)
        /** Assert equals. */
        assertEquals(RecurrenceType.DAILY, config.type)
    }
    
    @Test
    fun `parse empty returns daily default`() {
        /** Config. */
        val config = RecurrenceConfig.parse("")
        /** Assert equals. */
        assertEquals(RecurrenceType.DAILY, config.type)
    }
    
    @Test
    fun `parse weekdays preset`() {
        /** Config. */
        val config = RecurrenceConfig.parse("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
        /** Assert equals. */
        assertEquals(RecurrenceType.WEEKDAYS_ONLY, config.type)
    }
    
    // ==================== toFrequency Tests ====================
    
    @Test
    fun `toFrequency for daily returns 1 to 1`() {
        /** Config. */
        val config = RecurrenceConfig.daily()
        /** Assert equals. */
        assertEquals(1 to 1, config.toFrequency())
    }
    
    @Test
    fun `toFrequency for weekdays returns 5 to 7`() {
        /** Config. */
        val config = RecurrenceConfig.weekdays()
        /** Assert equals. */
        assertEquals(5 to 7, config.toFrequency())
    }
    
    @Test
    fun `toFrequency for specific weekdays`() {
        /** Config. */
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 3, 5))
        /** Assert equals. */
        assertEquals(3 to 7, config.toFrequency())
    }
    
    @Test
    fun `toFrequency for monthly`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(1, 15)
        /** Assert equals. */
        assertEquals(2 to 30, config.toFrequency())
    }
    
    @Test
    fun `toFrequency for interval`() {
        /** Config. */
        val config = RecurrenceConfig.everyNDays(7)
        /** Assert equals. */
        assertEquals(1 to 7, config.toFrequency())
    }
    
    @Test
    fun `toFrequency for yearly`() {
        /** Config. */
        val config = RecurrenceConfig.yearly()
        /** Assert equals. */
        assertEquals(1 to 365, config.toFrequency())
    }
    
    // ==================== serialize/deserialize Tests ====================
    
    @Test
    fun `serialize creates valid string`() {
        /** Config. */
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 3, 5))
        /** Serialized. */
        val serialized = config.serialize()
        /** Assert true. */
        assertTrue(serialized.startsWith("CONFIG:"))
        /** Assert true. */
        assertTrue(serialized.contains("type=SPECIFIC_WEEKDAYS"))
        /** Assert true. */
        assertTrue(serialized.contains("weekdays=1,3,5"))
    }
    
    @Test
    fun `parse serialized config restores original`() {
        /** Original. */
        val original = RecurrenceConfig.specificWeekdays(setOf(1, 3, 5))
        /** Serialized. */
        val serialized = original.serialize()
        /** Parsed. */
        val parsed = RecurrenceConfig.parse(serialized)
        
        /** Assert equals. */
        assertEquals(original.type, parsed.type)
        /** Assert equals. */
        assertEquals(original.weekdays, parsed.weekdays)
    }
    
    // ==================== displayName suffix Tests ====================
    
    @Test
    fun `day suffix 1st`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(1)
        /** Assert true. */
        assertTrue(config.displayName.contains("1st"))
    }
    
    @Test
    fun `day suffix 2nd`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(2)
        /** Assert true. */
        assertTrue(config.displayName.contains("2nd"))
    }
    
    @Test
    fun `day suffix 3rd`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(3)
        /** Assert true. */
        assertTrue(config.displayName.contains("3rd"))
    }
    
    @Test
    fun `day suffix 4th`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(4)
        /** Assert true. */
        assertTrue(config.displayName.contains("4th"))
    }
    
    @Test
    fun `day suffix 11th special case`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(11)
        /** Assert true. */
        assertTrue(config.displayName.contains("11th"))
    }
    
    @Test
    fun `day suffix 21st`() {
        /** Config. */
        val config = RecurrenceConfig.monthlyOnDates(21)
        /** Assert true. */
        assertTrue(config.displayName.contains("21st"))
    }
    
    // ==================== RecurrenceType enum Tests ====================
    
    @Test
    fun `RecurrenceType has all expected values`() {
        /** Values. */
        val values = RecurrenceType.entries
        /** Assert true. */
        assertTrue(values.contains(RecurrenceType.DAILY))
        /** Assert true. */
        assertTrue(values.contains(RecurrenceType.WEEKDAYS_ONLY))
        /** Assert true. */
        assertTrue(values.contains(RecurrenceType.SPECIFIC_WEEKDAYS))
        /** Assert true. */
        assertTrue(values.contains(RecurrenceType.MONTHLY_DATES))
        /** Assert true. */
        assertTrue(values.contains(RecurrenceType.INTERVAL))
        /** Assert true. */
        assertTrue(values.contains(RecurrenceType.FREQUENCY))
        /** Assert true. */
        assertTrue(values.contains(RecurrenceType.YEARLY))
    }
    
    // ==================== Additional Parse Tests ====================
    
    @Test
    fun `parse FREQ=MONTHLY without BYMONTHDAY defaults to 1st`() {
        /** Config. */
        val config = RecurrenceConfig.parse("FREQ=MONTHLY")
        /** Assert equals. */
        assertEquals(RecurrenceType.MONTHLY_DATES, config.type)
        /** Assert equals. */
        assertEquals(setOf(1), config.monthlyDates)
    }
    
    @Test
    fun `parse handles all weekday codes`() {
        /** Config. */
        val config = RecurrenceConfig.parse("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR,SA,SU")
        /** Assert equals. */
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7), config.weekdays)
    }
    
    @Test
    fun `parse handles invalid BYDAY code`() {
        /** Config. */
        val config = RecurrenceConfig.parse("FREQ=WEEKLY;BYDAY=XX,MO")
        // Should still parse MO, ignore XX
        /** Assert true. */
        assertTrue(config.weekdays.contains(1))
    }
    
    @Test
    fun `parse serialized config with interval`() {
        /** Original. */
        val original = RecurrenceConfig.everyNDays(5)
        /** Serialized. */
        val serialized = original.serialize()
        /** Parsed. */
        val parsed = RecurrenceConfig.parse(serialized)
        
        /** Assert equals. */
        assertEquals(RecurrenceType.INTERVAL, parsed.type)
        /** Assert equals. */
        assertEquals(5, parsed.intervalDays)
    }
    
    @Test
    fun `parse serialized config with monthly dates`() {
        /** Original. */
        val original = RecurrenceConfig.monthlyOnDates(1, 15, 32)
        /** Serialized. */
        val serialized = original.serialize()
        /** Parsed. */
        val parsed = RecurrenceConfig.parse(serialized)
        
        /** Assert equals. */
        assertEquals(RecurrenceType.MONTHLY_DATES, parsed.type)
        /** Assert equals. */
        assertEquals(setOf(1, 15, 32), parsed.monthlyDates)
    }
    
    @Test
    fun `parse serialized config with frequency`() {
        /** Original. */
        val original = RecurrenceConfig.timesPerWeek(3)
        /** Serialized. */
        val serialized = original.serialize()
        /** Parsed. */
        val parsed = RecurrenceConfig.parse(serialized)
        
        /** Assert equals. */
        assertEquals(RecurrenceType.FREQUENCY, parsed.type)
        /** Assert equals. */
        assertEquals(3, parsed.frequencyNumerator)
        /** Assert equals. */
        assertEquals(7, parsed.frequencyDenominator)
    }
    
    @Test
    fun `parse serialized config with start date`() {
        /** Start date. */
        val startDate = LocalDate.of(2024, 6, 15)
        /** Original. */
        val original = RecurrenceConfig.daily(startDate)
        /** Serialized. */
        val serialized = original.serialize()
        /** Parsed. */
        val parsed = RecurrenceConfig.parse(serialized)
        
        /** Assert equals. */
        assertEquals(startDate, parsed.startDate)
    }
    
    @Test
    fun `parse RRULE with unknown FREQ defaults to daily`() {
        /** Config. */
        val config = RecurrenceConfig.parse("FREQ=UNKNOWN")
        /** Assert equals. */
        assertEquals(RecurrenceType.DAILY, config.type)
    }
    
    // ==================== toRRule additional tests ====================
    
    @Test
    fun `toRRule for frequency daily`() {
        /** Config. */
        val config = RecurrenceConfig.timesPerWeek(7) // essentially daily
        /** Rrule. */
        val rrule = config.toRRule()
        /** Assert true. */
        assertTrue(rrule.contains("FREQ="))
    }
    
    @Test
    fun `toRRule for frequency weekly`() {
        /** Config. */
        val config = RecurrenceConfig.timesPerWeek(1)
        /** Rrule. */
        val rrule = config.toRRule()
        /** Assert true. */
        assertTrue(rrule.contains("FREQ=WEEKLY"))
    }
    
    @Test
    fun `toRRule for frequency monthly`() {
        /** Config. */
        val config = RecurrenceConfig(
            type = RecurrenceType.FREQUENCY,
            frequencyNumerator = 1,
            frequencyDenominator = 30
        )
        /** Rrule. */
        val rrule = config.toRRule()
        /** Assert true. */
        assertTrue(rrule.contains("FREQ=MONTHLY"))
    }
    
    @Test
    fun `toRRule for frequency with arbitrary denominator`() {
        /** Config. */
        val config = RecurrenceConfig(
            type = RecurrenceType.FREQUENCY,
            frequencyNumerator = 2,
            frequencyDenominator = 10 // Every 5 days
        )
        /** Rrule. */
        val rrule = config.toRRule()
        /** Assert true. */
        assertTrue(rrule.contains("INTERVAL=5"))
    }
    
    // ==================== isScheduledDay edge cases ====================
    
    @Test
    fun `isScheduledDay for frequency returns true for any date`() {
        /** Config. */
        val config = RecurrenceConfig.timesPerWeek(3)
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 1)))
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 12, 31)))
    }
    
    @Test
    fun `isScheduledDay for yearly without startDate returns true`() {
        /** Config. */
        val config = RecurrenceConfig.yearly()
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 1)))
    }
    
    @Test
    fun `isScheduledDay for interval without startDate returns true`() {
        /** Config. */
        val config = RecurrenceConfig.everyNDays(3)
        /** Assert true. */
        assertTrue(config.isScheduledDay(LocalDate.of(2024, 1, 1)))
    }
    
    // ==================== serialize edge cases ====================
    
    @Test
    fun `serialize with empty weekdays does not include weekdays`() {
        /** Config. */
        val config = RecurrenceConfig.daily()
        /** Serialized. */
        val serialized = config.serialize()
        /** Assert false. */
        assertFalse(serialized.contains("weekdays="))
    }
    
    @Test
    fun `serialize with empty monthlyDates does not include monthlyDates`() {
        /** Config. */
        val config = RecurrenceConfig.daily()
        /** Serialized. */
        val serialized = config.serialize()
        /** Assert false. */
        assertFalse(serialized.contains("monthlyDates="))
    }
    
    @Test
    fun `serialize weekdays config includes weekdays`() {
        /** Config. */
        val config = RecurrenceConfig.specificWeekdays(setOf(1, 5))
        /** Serialized. */
        val serialized = config.serialize()
        /** Assert true. */
        assertTrue(serialized.contains("weekdays=1,5"))
    }
    
    // ==================== specificWeekdays with DayOfWeek ====================
    
    @Test
    fun `specificWeekdays with DayOfWeek enum`() {
        /** Config. */
        val config = RecurrenceConfig.specificWeekdays(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.FRIDAY)
        /** Assert equals. */
        assertEquals(setOf(1, 5), config.weekdays)
    }
    
    // ==================== parseConfig edge cases ====================
    
    @Test
    fun `parse CONFIG with invalid date ignores it`() {
        /** Config. */
        val config = RecurrenceConfig.parse("CONFIG:type=DAILY|start=invalid-date")
        /** Assert null. */
        assertNull(config.startDate)
    }
    
    // ==================== Data class Tests ====================
    
    @Test
    fun `data class copy works correctly`() {
        /** Original. */
        val original = RecurrenceConfig.daily()
        /** Modified. */
        val modified = original.copy(type = RecurrenceType.YEARLY)
        
        /** Assert equals. */
        assertEquals(RecurrenceType.DAILY, original.type)
        /** Assert equals. */
        assertEquals(RecurrenceType.YEARLY, modified.type)
    }
    
    @Test
    fun `data class equals works correctly`() {
        /** Config1. */
        val config1 = RecurrenceConfig.specificWeekdays(setOf(1, 3))
        /** Config2. */
        val config2 = RecurrenceConfig.specificWeekdays(setOf(1, 3))
        /** Config3. */
        val config3 = RecurrenceConfig.specificWeekdays(setOf(1, 5))
        
        /** Assert equals. */
        assertEquals(config1, config2)
        /** Assert false. */
        assertFalse(config1 == config3)
    }
    
    @Test
    fun `data class hashCode is consistent`() {
        /** Config1. */
        val config1 = RecurrenceConfig.daily()
        /** Config2. */
        val config2 = RecurrenceConfig.daily()
        
        /** Assert equals. */
        assertEquals(config1.hashCode(), config2.hashCode())
    }
    
    @Test
    fun `data class toString includes type`() {
        /** Config. */
        val config = RecurrenceConfig.daily()
        /** Assert true. */
        assertTrue(config.toString().contains("DAILY"))
    }
}
