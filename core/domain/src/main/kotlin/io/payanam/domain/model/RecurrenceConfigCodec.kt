//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Parser and factory functions for [RecurrenceConfig].
 */
internal object RecurrenceConfigCodec {
    fun parse(rule: String?): RecurrenceConfig {
        if (rule.isNullOrBlank()) return daily(startDate = null)

        if (rule.startsWith("CONFIG:")) {
            return parseConfig(rule.removePrefix("CONFIG:"))
        }

        if (Frequency.isSerializedRule(rule)) {
            return parseFrequencyRule(rule)
        }

        return parseRRule(rule)
    }

    fun daily(startDate: LocalDate?) = RecurrenceConfig(
        type = RecurrenceType.DAILY,
        startDate = startDate
    )

    fun weekdays(startDate: LocalDate?) = RecurrenceConfig(
        type = RecurrenceType.WEEKDAYS_ONLY,
        startDate = startDate
    )

    fun specificWeekdays(vararg days: DayOfWeek) = RecurrenceConfig(
        type = RecurrenceType.SPECIFIC_WEEKDAYS,
        weekdays = days.map { it.value }.toSet()
    )

    fun specificWeekdays(days: Set<Int>) = RecurrenceConfig(
        type = RecurrenceType.SPECIFIC_WEEKDAYS,
        weekdays = days
    )

    fun monthlyOnDates(vararg dates: Int) = RecurrenceConfig(
        type = RecurrenceType.MONTHLY_DATES,
        monthlyDates = dates.toSet()
    )

    fun everyNDays(n: Int, startDate: LocalDate?) = RecurrenceConfig(
        type = RecurrenceType.INTERVAL,
        intervalDays = n,
        startDate = startDate
    )

    fun timesPerWeek(times: Int) = RecurrenceConfig(
        type = RecurrenceType.FREQUENCY,
        frequencyNumerator = times,
        frequencyDenominator = 7
    )

    fun yearly(startDate: LocalDate?) = RecurrenceConfig(
        type = RecurrenceType.YEARLY,
        startDate = startDate
    )

    private fun parseConfig(config: String): RecurrenceConfig {
        val parts = config.split("|").associate { part ->
            val kv = part.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else kv[0] to ""
        }

        val type = RecurrenceType.valueOf(parts["type"] ?: "DAILY")
        val weekdays = parts["weekdays"]?.split(",")
            ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        val monthlyDates = parts["monthlyDates"]?.split(",")
            ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        val interval = parts["interval"]?.toIntOrNull() ?: 1
        val freq = parts["freq"]?.split("/")
        val freqNum = freq?.getOrNull(0)?.toIntOrNull() ?: 1
        val freqDen = freq?.getOrNull(1)?.toIntOrNull() ?: 1
        val startDate = parts["start"]?.let {
            try {
                LocalDate.parse(it)
            } catch (e: Exception) { // detekt:ignore:TooGenericExceptionCaught
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
        val frequency = Frequency.parse(rule)
        return RecurrenceConfig(
            type = RecurrenceType.FREQUENCY,
            frequencyNumerator = frequency.numerator,
            frequencyDenominator = frequency.denominator,
            startDate = frequency.anchorDate,
        )
    }

    private fun parseRRule(rrule: String): RecurrenceConfig {
        val parts = rrule.uppercase().split(";").associate { part ->
            val kv = part.split("=", limit = 2)
            if (kv.size == 2) kv[0] to kv[1] else kv[0] to ""
        }

        val freq = parts["FREQ"] ?: "DAILY"
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val byDay = parts["BYDAY"]
        val byMonthDay = parts["BYMONTHDAY"]

        return when {
            byMonthDay != null -> {
                val dates = byMonthDay.split(",").mapNotNull { it.toIntOrNull() }.toSet()
                RecurrenceConfig(
                    type = RecurrenceType.MONTHLY_DATES,
                    monthlyDates = dates
                )
            }

            byDay != null -> {
                val weekdays = byDay.split(",").mapNotNull { day ->
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
                if (weekdays == setOf(1, 2, 3, 4, 5)) {
                    RecurrenceConfig(type = RecurrenceType.WEEKDAYS_ONLY)
                } else {
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
