//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.domain.model


import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Unified frequency model replacing RecurrenceConfig and RecurrenceType.
 * Stores as "num/den" or "num/den!start=YYYY-MM-DD".
 *
 * Inspired by uHabits (Loop Habit Tracker) frequency model.
 */
data class Frequency(
    val numerator: Int,
    val denominator: Int,
    val anchorDate: LocalDate? = null,
) {
    init {
        require(numerator > 0) { "numerator must be positive" }
        require(denominator > 0) { "denominator must be positive" }
    }
    val toDouble: Double get() = numerator.toDouble() / denominator
    /**
     * Human-readable label (Daily, Weekdays, 3×/week, Monthly...).
     */
    fun displayName(): String = when {
        numerator == denominator -> "Daily"
        denominator == 7 && numerator == 5 -> "Weekdays"
        denominator == 7 && numerator == 3 -> "3×/week"
        denominator == 7 && numerator == 2 -> "2×/week"
        denominator == 7 && numerator == 1 -> "Weekly"
        denominator == 7 && numerator in 1..7 -> "$numerator×/week"
        denominator == 30 && numerator == 1 -> "Monthly"
        denominator == 30 -> "$numerator×/month"
        denominator == 365 && numerator == 1 -> "Yearly"
        denominator == 1 -> "Daily"
        else -> "$numerator×/$denominator days"
    }
    /**
     * Serializes to `"num/den"` or `"num/den!start=YYYY-MM-DD"`.
     */
    fun serialize(): String = buildString {
        append("$numerator/$denominator")
        anchorDate?.let { append("!start=$it") }
    }
    /**
     * Returns a copy with [date] set as the recurrence anchor.
     */
    fun withAnchor(date: LocalDate): Frequency = copy(anchorDate = date)

    @Suppress("MagicNumber")
    companion object {
        val DAILY    = Frequency(1, 1)
        val WEEKDAYS  = Frequency(5, 7)
        val WEEKLY   = Frequency(1, 7)
        val BIWEEKLY = Frequency(1, 14)
        val MONTHLY  = Frequency(1, 30)
        val YEARLY   = Frequency(1, 365)
        private val serializedPattern = Regex("""^\d+/\d+(!start=\d{4}-\d{2}-\d{2})?$""")
        /**
         * Returns true if [rule] matches the `"num/den(!start=...)"` shape.
         */
        fun isSerializedRule(rule: String?): Boolean = !rule.isNullOrBlank() && serializedPattern.matches(rule)
        /**
         * Parses a `"num/den(!start=...)"` string into a [Frequency]; blank
         * falls back to [DAILY].
         */
        fun parse(s: String?): Frequency {
            if (s.isNullOrBlank()) return DAILY
            if (s.contains("!")) {
                val parts = s.split("!", limit = 2)
                val anchorStr = parts[1].removePrefix("start=")
                val anchor = try { LocalDate.parse(anchorStr) } catch (e: DateTimeParseException) { null }
                return parse(parts[0]).copy(anchorDate = anchor)
            }
            return parseCore(s)
        }

        private fun parseCore(s: String): Frequency {
            val slash = s.indexOf('/')
            if (slash < 0) return DAILY
            val num = s.substring(0, slash).toIntOrNull() ?: 1
            val den = s.substring(slash + 1).toIntOrNull() ?: 1
            return Frequency(
                numerator = num.coerceAtLeast(1),
                denominator = den.coerceAtLeast(1),
            )
        }

        /**
         * One-time legacy converter for old CONFIG:/RRULE: format strings.
         * Used only during DB migration from v16 to v17.
         */
        fun legacyParse(rule: String?): Frequency {
            if (rule.isNullOrBlank()) return DAILY
            if (rule.startsWith("CONFIG:")) return legacyParseConfig(rule.removePrefix("CONFIG:"))
            if (rule.startsWith("FREQ=")) return legacyParseRRule(rule)
            return parse(rule)
        }

        private fun legacyParseConfig(config: String): Frequency {
            val parts = config.split("|").associate { part ->
                val kv = part.split("=", limit = 2)
                if (kv.size == 2) kv[0].trim() to kv[1].trim() else kv[0].trim() to ""
            }
            val type = parts["type"] ?: return DAILY
            return when (type) {
                "DAILY" -> DAILY
                "WEEKDAYS_ONLY" -> WEEKDAYS
                "SPECIFIC_WEEKDAYS" -> {
                    val days = parts["weekdays"]?.split(",")
                        ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
                    Frequency(days.size.coerceAtLeast(1), 7)
                }
                "MONTHLY_DATES" -> {
                    val dates = parts["monthlyDates"]?.split(",")
                        ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(1)
                    Frequency(dates.size.coerceAtLeast(1), 30)
                }
                "INTERVAL" -> {
                    val interval = parts["interval"]?.toIntOrNull() ?: 1
                    Frequency(1, interval.coerceAtLeast(1))
                }
                "FREQUENCY" -> {
                    val freq = parts["freq"]?.split("/")
                    val num = freq?.getOrNull(0)?.toIntOrNull() ?: 1
                    val den = freq?.getOrNull(1)?.toIntOrNull() ?: 7
                    Frequency(num.coerceAtLeast(1), den.coerceAtLeast(1))
                }
                "YEARLY" -> YEARLY
                else -> DAILY
            }
        }

        private fun legacyParseRRule(rrule: String): Frequency {
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
                    Frequency(dates.size.coerceAtLeast(1), 30)
                }
                byDay != null -> {
                    val days = byDay.split(",").mapNotNull { day ->
                        when (day.trim()) {
                            "MO" -> 1; "TU" -> 2; "WE" -> 3; "TH" -> 4
                            "FR" -> 5; "SA" -> 6; "SU" -> 7; else -> null
                        }
                    }.toSet()
                    if (days == setOf(1, 2, 3, 4, 5) && interval == 1) WEEKDAYS
                    else Frequency(days.size.coerceAtLeast(1), 7 * interval.coerceAtLeast(1))
                }
                freq == "YEARLY" -> YEARLY
                freq == "MONTHLY" -> MONTHLY
                freq == "WEEKLY" -> Frequency(1, 7 * interval)
                freq == "DAILY" -> Frequency(1, interval.coerceAtLeast(1))
                else -> DAILY
            }
        }
    }
}
