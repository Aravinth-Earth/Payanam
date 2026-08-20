//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.domain.model


import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Parser and factory functions for [RecurrenceConfig].
 */
@Suppress("MagicNumber")
internal object RecurrenceConfigCodec {
    /**
     * Parse.
     */
    fun parse(rule: String?): RecurrenceConfig {
        /** If. */
        if (rule.isNullOrBlank()) return daily(startDate = null)

        /** If. */
        if (rule.startsWith("CONFIG:")) {
            return parseConfig(rule.removePrefix("CONFIG:"))
        }

        /** If. */
        if (Frequency.isSerializedRule(rule)) {
            return parseFrequencyRule(rule)
        }

        return parseRRule(rule)
    }

    /**
     * Daily.
     */
    fun daily(startDate: LocalDate?) = RecurrenceConfig(
        type = RecurrenceType.DAILY,
        startDate = startDate
    )

    /**
     * Weekdays.
     */
    fun weekdays(startDate: LocalDate?) = RecurrenceConfig(
        type = RecurrenceType.WEEKDAYS_ONLY,
        startDate = startDate
    )

    /**
     * Specific weekdays.
     */
    fun specificWeekdays(vararg days: DayOfWeek) = RecurrenceConfig(
        type = RecurrenceType.SPECIFIC_WEEKDAYS,
        weekdays = days.map { it.value }.toSet()
    )

    /**
     * Specific weekdays.
     */
    fun specificWeekdays(days: Set<Int>) = RecurrenceConfig(
        type = RecurrenceType.SPECIFIC_WEEKDAYS,
        weekdays = days
    )

    /**
     * Monthly on dates.
     */
    fun monthlyOnDates(vararg dates: Int) = RecurrenceConfig(
        type = RecurrenceType.MONTHLY_DATES,
        monthlyDates = dates.toSet()
    )

    /**
     * Every ndays.
     */
    fun everyNDays(n: Int, startDate: LocalDate?) = RecurrenceConfig(
        type = RecurrenceType.INTERVAL,
        intervalDays = n,
        startDate = startDate
    )

    /**
     * Times per week.
     */
    fun timesPerWeek(times: Int) = RecurrenceConfig(
        type = RecurrenceType.FREQUENCY,
        frequencyNumerator = times,
        frequencyDenominator = 7
    )

    /**
     * Yearly.
     */
    fun yearly(startDate: LocalDate?) = RecurrenceConfig(
        type = RecurrenceType.YEARLY,
        startDate = startDate
    )

    private fun parseConfig(config: String): RecurrenceConfig {
        /** Parts. */
        val parts = config.split("|").associate { part ->
            /** Kv. */
            val kv = part.split("=", limit = 2)
            /** If. */
            if (kv.size == 2) kv[0] to kv[1] else kv[0] to ""
        }

        /** Type. */
        val type = RecurrenceType.valueOf(parts["type"] ?: "DAILY")
        /** Weekdays. */
        val weekdays = parts["weekdays"]?.split(",")
            ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        /** Monthly dates. */
        val monthlyDates = parts["monthlyDates"]?.split(",")
            ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        /** Interval. */
        val interval = parts["interval"]?.toIntOrNull() ?: 1
        /** Freq. */
        val freq = parts["freq"]?.split("/")
        /** Freq num. */
        val freqNum = freq?.getOrNull(0)?.toIntOrNull() ?: 1
        /** Freq den. */
        val freqDen = freq?.getOrNull(1)?.toIntOrNull() ?: 1
        /** Start date. */
        val startDate = parts["start"]?.let {
            try {
                LocalDate.parse(it)
            } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) { // detekt:ignore:TooGenericExceptionCaught
                /** Null. */
                null
            }
        }

        return RecurrenceConfig(
            type = type,
            weekdays = weekdays,
            monthlyDates = monthlyDates,
            intervalDays = interval,
            frequencyNumerator = freqNum,
            frequencyDenominator = freqDen,
            startDate = startDate
        )
    }

    private fun parseFrequencyRule(rule: String): RecurrenceConfig {
        /** Frequency. */
        val frequency = Frequency.parse(rule)
        return RecurrenceConfig(
            type = RecurrenceType.FREQUENCY,
            frequencyNumerator = frequency.numerator,
            frequencyDenominator = frequency.denominator,
            startDate = frequency.anchorDate,
        )
    }

    private fun parseRRule(rrule: String): RecurrenceConfig {
        /** Parts. */
        val parts = rrule.uppercase().split(";").associate { part ->
            /** Kv. */
            val kv = part.split("=", limit = 2)
            /** If. */
            if (kv.size == 2) kv[0] to kv[1] else kv[0] to ""
        }

        /** Freq. */
        val freq = parts["FREQ"] ?: "DAILY"
        /** Interval. */
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        /** By day. */
        val byDay = parts["BYDAY"]
        /** By month day. */
        val byMonthDay = parts["BYMONTHDAY"]

        return when {
            byMonthDay != null -> {
                /** Dates. */
                val dates = byMonthDay.split(",").mapNotNull { it.toIntOrNull() }.toSet()
                /** Recurrence config. */
                RecurrenceConfig(
                    type = RecurrenceType.MONTHLY_DATES,
                    monthlyDates = dates
                )
            }

            byDay != null -> {
                /** Weekdays. */
                val weekdays = byDay.split(",").mapNotNull { day ->
                    /** When. */
                    when (day.trim()) {
                        "MO" -> 1
                        "TU" -> 2
                        "WE" -> 3
                        "TH" -> 4
                        "FR" -> 5
                        "SA" -> 6
                        "SU" -> 7
                        else -> null
                    }
                }.toSet()
                /** If. */
                if (weekdays == setOf(1, 2, 3, 4, 5)) {
                    /** Recurrence config. */
                    RecurrenceConfig(type = RecurrenceType.WEEKDAYS_ONLY)
                } else {
                    /** Recurrence config. */
                    RecurrenceConfig(
                        type = RecurrenceType.SPECIFIC_WEEKDAYS,
                        weekdays = weekdays
                    )
                }
            }

            freq == "YEARLY" -> RecurrenceConfig(type = RecurrenceType.YEARLY)
            freq == "MONTHLY" -> RecurrenceConfig(
                type = RecurrenceType.MONTHLY_DATES,
                monthlyDates = setOf(1)
            )

            freq == "DAILY" && interval > 1 -> RecurrenceConfig(
                type = RecurrenceType.INTERVAL,
                intervalDays = interval
            )

            else -> RecurrenceConfig(type = RecurrenceType.DAILY)
        }
    }
}
