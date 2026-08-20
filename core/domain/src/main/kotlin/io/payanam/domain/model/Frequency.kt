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
    /** Numerator. */
    val numerator: Int,
    /** Denominator. */
    val denominator: Int,
    /** Anchor date. */
    val anchorDate: LocalDate? = null,
) {
    init {
        /** Require. */
        require(numerator > 0) { "numerator must be positive" }
        /** Require. */
        require(denominator > 0) { "denominator must be positive" }
    }

    /** To double. */
    val toDouble: Double get() = numerator.toDouble() / denominator

    /**
     * Display name.
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
     * Serialize.
     */
    fun serialize(): String = buildString {
        /** Append. */
        append("$numerator/$denominator")
        anchorDate?.let { append("!start=$it") }
    }

    /**
     * With anchor.
     */
    fun withAnchor(date: LocalDate): Frequency = copy(anchorDate = date)

    @Suppress("MagicNumber")
    companion object {
        /** Daily. */
        val DAILY    = Frequency(1, 1)
        /** Weekdays. */
        val WEEKDAYS  = Frequency(5, 7)
        /** Weekly. */
        val WEEKLY   = Frequency(1, 7)
        /** Biweekly. */
        val BIWEEKLY = Frequency(1, 14)
        /** Monthly. */
        val MONTHLY  = Frequency(1, 30)
        /** Yearly. */
        val YEARLY   = Frequency(1, 365)
        private val serializedPattern = Regex("""^\d+/\d+(!start=\d{4}-\d{2}-\d{2})?$""")

        /**
         * Is serialized rule.
         */
        fun isSerializedRule(rule: String?): Boolean = !rule.isNullOrBlank() && serializedPattern.matches(rule)

        /**
         * Parse.
         */
        fun parse(s: String?): Frequency {
            /** If. */
            if (s.isNullOrBlank()) return DAILY
            /** If. */
            if (s.contains("!")) {
                /** Parts. */
                val parts = s.split("!", limit = 2)
                /** Anchor str. */
                val anchorStr = parts[1].removePrefix("start=")
                /** Anchor. */
                val anchor = try { LocalDate.parse(anchorStr) } catch (e: DateTimeParseException) { null }
                return parse(parts[0]).copy(anchorDate = anchor)
            }
            return parseCore(s)
        }

        private fun parseCore(s: String): Frequency {
            /** Slash. */
            val slash = s.indexOf('/')
            /** If. */
            if (slash < 0) return DAILY
            /** Num. */
            val num = s.substring(0, slash).toIntOrNull() ?: 1
            /** Den. */
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
            /** If. */
            if (rule.isNullOrBlank()) return DAILY
            /** If. */
            if (rule.startsWith("CONFIG:")) return legacyParseConfig(rule.removePrefix("CONFIG:"))
            /** If. */
            if (rule.startsWith("FREQ=")) return legacyParseRRule(rule)
            return parse(rule)
        }

        private fun legacyParseConfig(config: String): Frequency {
            /** Parts. */
            val parts = config.split("|").associate { part ->
                /** Kv. */
                val kv = part.split("=", limit = 2)
                /** If. */
                if (kv.size == 2) kv[0].trim() to kv[1].trim() else kv[0].trim() to ""
            }
            /** Type. */
            val type = parts["type"] ?: return DAILY
            return when (type) {
                "DAILY" -> DAILY
                "WEEKDAYS_ONLY" -> WEEKDAYS
                "SPECIFIC_WEEKDAYS" -> {
                    /** Days. */
                    val days = parts["weekdays"]?.split(",")
                        ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
                    /** Frequency. */
                    Frequency(days.size.coerceAtLeast(1), 7)
                }
                "MONTHLY_DATES" -> {
                    /** Dates. */
                    val dates = parts["monthlyDates"]?.split(",")
                        ?.mapNotNull { it.toIntOrNull() }?.toSet() ?: setOf(1)
                    /** Frequency. */
                    Frequency(dates.size.coerceAtLeast(1), 30)
                }
                "INTERVAL" -> {
                    /** Interval. */
                    val interval = parts["interval"]?.toIntOrNull() ?: 1
                    /** Frequency. */
                    Frequency(1, interval.coerceAtLeast(1))
                }
                "FREQUENCY" -> {
                    /** Freq. */
                    val freq = parts["freq"]?.split("/")
                    /** Num. */
                    val num = freq?.getOrNull(0)?.toIntOrNull() ?: 1
                    /** Den. */
                    val den = freq?.getOrNull(1)?.toIntOrNull() ?: 7
                    /** Frequency. */
                    Frequency(num.coerceAtLeast(1), den.coerceAtLeast(1))
                }
                "YEARLY" -> YEARLY
                else -> DAILY
            }
        }

        private fun legacyParseRRule(rrule: String): Frequency {
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
                    /** Frequency. */
                    Frequency(dates.size.coerceAtLeast(1), 30)
                }
                byDay != null -> {
                    /** Days. */
                    val days = byDay.split(",").mapNotNull { day ->
                        /** When. */
                        when (day.trim()) {
                            "MO" -> 1; "TU" -> 2; "WE" -> 3; "TH" -> 4
                            "FR" -> 5; "SA" -> 6; "SU" -> 7; else -> null
                        }
                    }.toSet()
                    /** If. */
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
