//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import java.time.LocalDate

/**
 * One-time legacy converter: uHabits-style "num/den" frequency rules → canonical
 * [RecurrenceConfig] serialized strings ("CONFIG:...").
 *
 * Used during the v18 migration + backfill to convert all existing x/y habits
 * into deterministic due-date recurrence types (self-governance model).
 *
 * Conversion map (user decisions, 2026-08-08):
 * - 1/1              → DAILY
 * - 1/N (any N)      → INTERVAL N, startDate = anchor
 * - 2/7              → SPECIFIC_WEEKDAYS [6,7]  (Sat, Sun — weekend dues)
 * - 5/7              → WEEKDAYS_ONLY            (Mon–Fri)
 * - 4/7              → SPECIFIC_WEEKDAYS [1,2,3,4] (Mon–Thu default)
 * - 1/365            → YEARLY
 * - anything else    → INTERVAL (denominator / numerator), startDate = anchor
 */
object NumDenToConfigConverter {

    /** 1=Mon … 7=Sun (java.time.DayOfWeek.value alignment). */
    private val WEEKEND_DAYS = setOf(6, 7)       // Sat, Sun
    private val MON_THU_DAYS = setOf(1, 2, 3, 4) // Mon–Thu default for 4/7

    /**
     * Converts a serialized num/den rule ("1/7", "3/7!start=2026-01-01") into a
     * canonical [RecurrenceConfig.serialize] string.
     *
     * @param rule the stored recurrenceRule value
     * @param anchorDate the date from which interval grids start (first
     *   occurrence / first due date for that habit); used for INTERVAL and
     *   YEARLY startDate. May be null (INTERVAL without start would degrade to
     *   daily semantics, so callers SHOULD pass a non-null anchor).
     * @return canonical CONFIG string; falls back to DAILY for unparseable input
     */
    fun convert(rule: String?, anchorDate: LocalDate?): String {
        val normalized = rule?.trim().orEmpty()
        if (normalized.isBlank()) return RecurrenceConfig.daily().serialize()

        val frequency = Frequency.parse(normalized)
        val num = frequency.numerator
        val den = frequency.denominator

        val config = when {
            // 1/1 or n/n → daily
            num >= den -> RecurrenceConfig.daily()

            // Weekly-family special cases (user decisions)
            den == 7 && num == 5 -> RecurrenceConfig.weekdays()
            den == 7 && num == 2 -> RecurrenceConfig.specificWeekdays(WEEKEND_DAYS)
            den == 7 && num == 4 -> RecurrenceConfig.specificWeekdays(MON_THU_DAYS)

            // 1/365 → yearly
            den == 365 && num == 1 -> RecurrenceConfig.yearly(anchorDate)

            // Everything else: interval of (denominator / numerator) days
            // e.g. 1/7 → every 7 days, 1/30 → every 30 days, 2/7 handled above
            else -> {
                val interval = den / num
                RecurrenceConfig.everyNDays(interval.coerceAtLeast(1), anchorDate)
            }
        }
        return config.serialize()
    }
}
