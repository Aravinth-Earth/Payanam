//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.domain.model


import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val MONDAY = 1
private const val TUESDAY = 2
private const val WEDNESDAY = 3
private const val THURSDAY = 4
private const val FRIDAY = 5
private const val SATURDAY = 6
private const val SUNDAY = 7

/**
 * Flexible recurrence configuration supporting weekdays, dates, intervals, and frequency patterns.
 */
data class RecurrenceConfig(
    val type: RecurrenceType,
    
    // For SPECIFIC_WEEKDAYS: Which days of the week (1=Monday, 7=Sunday)
    val weekdays: Set<Int> = emptySet(),
    
    // For MONTHLY_DATES: Which days of the month (1-31, 32=last day)
    val monthlyDates: Set<Int> = emptySet(),
    
    // For INTERVAL: How many days between occurrences
    val intervalDays: Int = 1,
    
    // For FREQUENCY: X times per Y days (like uHabits)
    val frequencyNumerator: Int = 1,
    val frequencyDenominator: Int = 1,
    
    // Start date for tracking (first scheduled occurrence)
    val startDate: LocalDate? = null
) {
    /**
     * Human-readable display name for this recurrence configuration.
     */
    val displayName: String
        get() = when (type) {
            RecurrenceType.DAILY -> "Daily"
            RecurrenceType.WEEKDAYS_ONLY -> "Weekdays"
            RecurrenceType.SPECIFIC_WEEKDAYS -> {
                val dayNames = weekdays.sorted().map { dayNum ->
                    when (dayNum) {
                        MONDAY -> "Mon"
                        TUESDAY -> "Tue"
                        WEDNESDAY -> "Wed"
                        THURSDAY -> "Thu"
                        FRIDAY -> "Fri"
                        SATURDAY -> "Sat"
                        SUNDAY -> "Sun"
                        else -> ""
                    }
                }
                when {
                    weekdays.size == 1 -> "Weekly (${dayNames.first()})"
                    weekdays.size <= 3 -> dayNames.joinToString(", ")
                    else -> "${weekdays.size} days/week"
                }
            }
            RecurrenceType.MONTHLY_DATES -> {
                val dateNames = monthlyDates.sorted().map { date ->
                    if (date == 32) "last" else "${date}${getDaySuffix(date)}"
                }
                when {
                    monthlyDates.size == 1 -> "Monthly (${dateNames.first()})"
                    monthlyDates.size <= 3 -> "Monthly: ${dateNames.joinToString(", ")}"
                    else -> "Monthly (${monthlyDates.size} dates)"
                }
            }
            RecurrenceType.INTERVAL -> {
                when (intervalDays) {
                    1 -> "Daily"
                    2 -> "Every other day"
                    7 -> "Weekly"
                    14 -> "Bi-weekly"
                    else -> "Every $intervalDays days"
                }
            }
            RecurrenceType.FREQUENCY -> {
                when {
                    frequencyNumerator == frequencyDenominator -> "Daily"
                    frequencyDenominator == 7 -> "$frequencyNumerator×/week"
                    frequencyDenominator == 30 -> "$frequencyNumerator×/month"
                    else -> "$frequencyNumerator×/$frequencyDenominator days"
                }
            }
            RecurrenceType.YEARLY -> "Yearly"
        }
    
    private fun getDaySuffix(day: Int): String {
        return when {
            day in 11..13 -> "th"
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd"
            day % 10 == 3 -> "rd"
            else -> "th"
        }
    }
    
    /**
     * Checks if a given date is a scheduled day based on this config.
     */
    fun isScheduledDay(date: LocalDate): Boolean {
        // If startDate is set, dates before it are not scheduled
        startDate?.let {
            if (date.isBefore(it)) return false
        }
        
        return when (type) {
            RecurrenceType.DAILY -> true
            
            RecurrenceType.SPECIFIC_WEEKDAYS -> {
                val dayOfWeek = date.dayOfWeek.value // 1=Monday, 7=Sunday
                dayOfWeek in weekdays
            }
            
            RecurrenceType.WEEKDAYS_ONLY -> {
                val dayOfWeek = date.dayOfWeek
                dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY
            }
            
            RecurrenceType.MONTHLY_DATES -> {
                val dayOfMonth = date.dayOfMonth
                val lastDayOfMonth = date.lengthOfMonth()
                // 32 represents "last day of month"
                dayOfMonth in monthlyDates || 
                    (32 in monthlyDates && dayOfMonth == lastDayOfMonth)
            }
            
            RecurrenceType.INTERVAL -> {
                // Scheduled every N days from start date
                val start = startDate ?: return true
                val daysSinceStart = ChronoUnit.DAYS.between(start, date).toInt()
                daysSinceStart >= 0 && daysSinceStart % intervalDays == 0
            }
            
            RecurrenceType.FREQUENCY -> {
                // X times per Y days - any day in the period qualifies
                // This is flexible like uHabits - user can complete on any day
                true
            }
            
            RecurrenceType.YEARLY -> {
                // Same day and month each year
                startDate?.let { start ->
                    date.month == start.month && date.dayOfMonth == start.dayOfMonth
                } ?: true
            }
        }
    }
    
    /**
     * Converts this config to an rRULE string for storage.
     */
    fun toRRule(): String {
        return when (type) {
            RecurrenceType.DAILY -> "FREQ=DAILY;INTERVAL=1"
            
            RecurrenceType.SPECIFIC_WEEKDAYS -> {
                val byDay = weekdays.sorted().joinToString(",") { dayNum ->
                    when (dayNum) {
                        1 -> "MO"
                        2 -> "TU"
                        3 -> "WE"
                        4 -> "TH"
                        5 -> "FR"
                        6 -> "SA"
                        7 -> "SU"
                        else -> ""
                    }
                }
                "FREQ=WEEKLY;BYDAY=$byDay"
            }
            
            RecurrenceType.WEEKDAYS_ONLY -> "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
            
            RecurrenceType.MONTHLY_DATES -> {
                val byMonthDay = monthlyDates.filter { it in 1..31 }
                    .sorted().joinToString(",")
                "FREQ=MONTHLY;BYMONTHDAY=$byMonthDay"
            }
            
            RecurrenceType.INTERVAL -> "FREQ=DAILY;INTERVAL=$intervalDays"
            
            RecurrenceType.FREQUENCY -> {
                // Convert frequency to closest RRULE
                when {
                    frequencyDenominator == 1 -> "FREQ=DAILY;INTERVAL=1"
                    frequencyDenominator == 7 -> "FREQ=WEEKLY;INTERVAL=1"
                    frequencyDenominator == 30 -> "FREQ=MONTHLY;INTERVAL=1"
                    else -> "FREQ=DAILY;INTERVAL=${frequencyDenominator / frequencyNumerator}"
                }
            }
            
            RecurrenceType.YEARLY -> "FREQ=YEARLY;INTERVAL=1"
        }
    }
    
    /**
     * Serializes this config to a JSON-like string for storage in recurrenceRule.
     * Format: "CONFIG:{type}|{params}"
     */
    fun serialize(): String {
        val params = mutableListOf<String>()
        params.add("type=$type")
        if (weekdays.isNotEmpty()) {
            params.add("weekdays=${weekdays.sorted().joinToString(",")}")
        }
        if (monthlyDates.isNotEmpty()) {
            params.add("monthlyDates=${monthlyDates.sorted().joinToString(",")}")
        }
        if (type == RecurrenceType.INTERVAL) {
            params.add("interval=$intervalDays")
        }
        if (type == RecurrenceType.FREQUENCY) {
            params.add("freq=$frequencyNumerator/$frequencyDenominator")
        }
        startDate?.let {
            params.add("start=$it")
        }
        
        return "CONFIG:${params.joinToString("|")}"
    }
    
    /**
     * Gets the frequency as numerator/denominator for score calculations.
     */
    fun toFrequency(): Pair<Int, Int> {
        return when (type) {
            RecurrenceType.DAILY -> 1 to 1
            RecurrenceType.SPECIFIC_WEEKDAYS -> weekdays.size to 7
            RecurrenceType.WEEKDAYS_ONLY -> 5 to 7
            RecurrenceType.MONTHLY_DATES -> monthlyDates.size to 30
            RecurrenceType.INTERVAL -> 1 to intervalDays
            RecurrenceType.FREQUENCY -> frequencyNumerator to frequencyDenominator
            RecurrenceType.YEARLY -> 1 to 365
        }
    }
    /**
     * Returns the scheduled dates in range.
     */
    fun getScheduledDatesInRange(start: LocalDate, end: LocalDate): List<LocalDate> {
        val dates = mutableListOf<LocalDate>()
        var current = start
        while (!current.isAfter(end)) {
            if (isScheduledDay(current)) {
                dates.add(current)
            }
            current = current.plusDays(1)
        }
        return dates
    }
    /**
     * Number of scheduled occurrences between [start] and [end] (inclusive).
     */
    fun countScheduledOccurrences(start: LocalDate, end: LocalDate): Int {
        return getScheduledDatesInRange(start, end).size
    }
    
    @Suppress("MagicNumber")
    companion object {
        /**
         * Parses an rrule/CONFIG string into a [RecurrenceConfig] (delegates to [RecurrenceConfigCodec]).
         */
        fun parse(rule: String?): RecurrenceConfig = RecurrenceConfigCodec.parse(rule)
        /**
         * Builds a [RecurrenceType.DAILY] config (delegates to [RecurrenceConfigCodec]).
         */
        fun daily(startDate: LocalDate? = null): RecurrenceConfig = RecurrenceConfigCodec.daily(startDate)
        /**
         * Builds a Mon-Fri [RecurrenceType.WEEKDAYS_ONLY] config.
         */
        fun weekdays(startDate: LocalDate? = null): RecurrenceConfig = RecurrenceConfigCodec.weekdays(startDate)
        /**
         * Builds a [RecurrenceType.SPECIFIC_WEEKDAYS] config from [DayOfWeek] values.
         */
        fun specificWeekdays(vararg days: DayOfWeek): RecurrenceConfig = RecurrenceConfigCodec.specificWeekdays(*days)
        /**
         * Builds a [RecurrenceType.SPECIFIC_WEEKDAYS] config from day-of-week ints (1=Mon..7=Sun).
         */
        fun specificWeekdays(days: Set<Int>): RecurrenceConfig = RecurrenceConfigCodec.specificWeekdays(days)
        /**
         * Builds a [RecurrenceType.MONTHLY_DATES] config (32 = last day of month).
         */
        fun monthlyOnDates(vararg dates: Int): RecurrenceConfig = RecurrenceConfigCodec.monthlyOnDates(*dates)
        /**
         * Builds a [RecurrenceType.INTERVAL] config firing every [n] days.
         */
        fun everyNDays(n: Int, startDate: LocalDate? = null): RecurrenceConfig =
            RecurrenceConfigCodec.everyNDays(n, startDate)
        /**
         * Builds a [RecurrenceType.FREQUENCY] config of [times] per 7 days.
         */
        fun timesPerWeek(times: Int): RecurrenceConfig = RecurrenceConfigCodec.timesPerWeek(times)
        /**
         * Builds a [RecurrenceType.YEARLY] config (same month/day each year).
         */
        fun yearly(startDate: LocalDate? = null): RecurrenceConfig = RecurrenceConfigCodec.yearly(startDate)
    }
}

/**
 * Types of recurrence patterns.
 */
enum class RecurrenceType {
    DAILY,              // Every day
    WEEKDAYS_ONLY,      // Monday to Friday
    SPECIFIC_WEEKDAYS,  // Specific days like Mon, Wed, Fri
    MONTHLY_DATES,      // Specific dates like 1st, 15th
    INTERVAL,           // Every N days
    FREQUENCY,          // X times per Y days (flexible)
    YEARLY              // Once a year
}
