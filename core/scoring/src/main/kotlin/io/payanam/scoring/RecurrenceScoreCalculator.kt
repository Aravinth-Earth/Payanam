//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.Frequency as DomainFrequency
import io.payanam.domain.model.RecurrenceConfig
import java.time.LocalDate
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Completion statistics for recurring tasks.
 *
 * @property completionRate7Days Completion rate over the last 7 days.
 * @property completionRate30Days Completion rate over the last 30 days.
 * @property completionRate90Days Completion rate over the last 90 days.
 * @property allTimeRate Completion rate across all recorded occurrences.
 * @property currentStreak Current consecutive completed-days streak (from today backwards).
 * @property longestStreak Longest completed-days streak on record.
 */
data class CompletionStats(
    val completionRate7Days: Double,
    val completionRate30Days: Double,
    val completionRate90Days: Double,
    val allTimeRate: Double,
    val currentStreak: Int,
    val longestStreak: Int
)

/**
 * Summary of a single frequency window between [start] and [end].
 *
 * @property start Inclusive start date of the window.
 * @property end Inclusive end date of the window.
 * @property coveredDays Number of scheduled days that fall inside the range.
 * @property completedCount Completed occurrences within the window.
 * @property skippedCount Skipped occurrences within the window.
 * @property targetCount Raw proportional target count for the window.
 * @property effectiveTargetCount Target count after subtracting skipped days.
 */
data class FrequencyWindowSummary(
    val start: LocalDate,
    val end: LocalDate,
    val coveredDays: Int,
    val completedCount: Int,
    val skippedCount: Int,
    val targetCount: Int,
    val effectiveTargetCount: Int
) {
    /** True when completed count meets or exceeds the effective target. */
    val isSatisfied: Boolean get() = completedCount >= effectiveTargetCount
    /** Completed-to-effective-target ratio, or 1.0 when there is no target. */
    val completionRatio: Double
        get() = if (effectiveTargetCount <= 0) 1.0 else completedCount.toDouble() / effectiveTargetCount
}

/**
 * Calculates recurrence decay scores using the uHabits-inspired model.
 */
object RecurrenceScoreCalculator {

    private val logger = UnifiedLogger.getInstance()

    // Completion-rate window sizes (days) used by the rate calculations.
    private const val WINDOW_DAYS_7 = 7
    private const val WINDOW_DAYS_30 = 30
    private const val WINDOW_DAYS_90 = 90
    
    /**
     * Represents a recurrence frequency as "X times per Y days".
     *
     * @property numerator How many times the habit should occur.
     * @property denominator Across how many days.
     */
    data class Frequency(
        // How many times
        val numerator: Int,
        // Per how many days
        val denominator: Int
    ) {
        /** Converts this frequency to a rate (occurrences per day). */
        fun toDouble() = numerator.toDouble() / denominator

        companion object {
            /** One occurrence per day. */
            val DAILY = Frequency(1, 1)
            /** One occurrence every two days. */
            val EVERY_OTHER_DAY = Frequency(1, 2)
            /** One occurrence per week (7 days). */
            val WEEKLY = Frequency(1, 7)
            /** Two occurrences per week (7 days). */
            val TWO_PER_WEEK = Frequency(2, 7)
            /** Three occurrences per week (7 days). */
            val THREE_PER_WEEK = Frequency(3, 7)
            /** One occurrence per month (30 days). */
            val MONTHLY = Frequency(1, 30)
            /** One occurrence per year (365 days). */
            val YEARLY = Frequency(1, 365)
        }
    }

    /** Builds a [Frequency] from a domain [RecurrenceConfig], coercing to at least 1. */
    fun fromRecurrenceConfig(config: RecurrenceConfig): Frequency {
        val (numerator, denominator) = config.toFrequency()
        return Frequency(
            numerator = numerator.coerceAtLeast(1),
            denominator = denominator.coerceAtLeast(1),
        )
    }

    /** Builds a [Frequency] from a domain [DomainFrequency], coercing to at least 1. */
    fun fromFrequency(frequency: DomainFrequency): Frequency =
        Frequency(
            numerator = frequency.numerator.coerceAtLeast(1),
            denominator = frequency.denominator.coerceAtLeast(1),
        )

    /** Builds a [Frequency] from a legacy RRULE-style [rule] string. */
    fun fromRule(rule: String?): Frequency = fromFrequency(DomainFrequency.legacyParse(rule))

    /** Parses an RRULE [rrule] string into a [Frequency]. */
    fun parseRRuleToFrequency(rrule: String?): Frequency = fromRule(rrule)
    
    /**
     * Calculate completion statistics from a list of occurrence statuses.
     * 
     * @param occurrences List of pairs (dayIndex, status) where dayIndex 0 = today
     * @return Completion statistics
     */
    fun calculateCompletionStats(
        occurrences: List<Pair<Int, String>>
    ): CompletionStats {
        if (occurrences.isEmpty()) {
            return CompletionStats(
                completionRate7Days = 0.0,
                completionRate30Days = 0.0,
                completionRate90Days = 0.0,
                allTimeRate = 0.0,
                currentStreak = 0,
                longestStreak = 0
            )
        }
        
        fun calculateRate(days: Int): Double {
            val relevant = occurrences.filter { it.first <= days }
            if (relevant.isEmpty()) return 1.0
            val completed = relevant.count { it.second == "completed" }
            val total = relevant.count { it.second in listOf("completed", "skipped", "missed") }
            return if (total > 0) completed.toDouble() / total else 1.0
        }
        
        // Calculate streaks
        var currentStreak = 0
        var longestStreak = 0
        var tempStreak = 0
        
        val sortedByDay = occurrences.sortedBy { it.first }
        for ((_, status) in sortedByDay) {
            if (status == "completed") {
                tempStreak++
                longestStreak = maxOf(longestStreak, tempStreak)
            } else if (status == "missed") {
                tempStreak = 0
            }
            // Skip doesn't break streak
        }
        
        // Current streak from today backwards
        val recentOccurrences = occurrences.filter { it.first <= WINDOW_DAYS_7 }.sortedBy { it.first }
        for ((_, status) in recentOccurrences) {
            if (status == "completed") {
                currentStreak++
            } else if (status == "missed") {
                break
            }
        }
        
        return CompletionStats(
            completionRate7Days = calculateRate(WINDOW_DAYS_7),
            completionRate30Days = calculateRate(WINDOW_DAYS_30),
            completionRate90Days = calculateRate(WINDOW_DAYS_90),
            allTimeRate = calculateRate(Int.MAX_VALUE),
            currentStreak = currentStreak,
            longestStreak = longestStreak
        )
    }
    
    /**
     * Calculate frequency-aware completion statistics.
     * 
     * This properly handles recurring habits by:
     * 1. Only counting scheduled days (based on recurrence config)
     * 2. Starting from the first recorded occurrence
     * 3. Treating unrecorded scheduled days as "missed"
     * 
     * @param occurrences Map of date -> status for recorded occurrences
     * @param recurrenceConfig The recurrence configuration for this habit
     * @param firstOccurrenceDate The date of the first recorded occurrence (tracking start)
     * @return Completion statistics respecting the recurrence schedule
     */
    fun calculateFrequencyAwareStats(
        occurrences: Map<LocalDate, String>,
        recurrenceConfig: RecurrenceConfig,
        firstOccurrenceDate: LocalDate?
    ): CompletionStats {
        val today = LocalDate.now()
        
        // If no first occurrence, return defaults
        if (firstOccurrenceDate == null || occurrences.isEmpty()) {
            return CompletionStats(
                completionRate7Days = 0.0,
                completionRate30Days = 0.0,
                completionRate90Days = 0.0,
                allTimeRate = 0.0,
                currentStreak = 0,
                longestStreak = 0
            )
        }
        
        // Calculate rates for different periods
        fun calculateRateForDays(days: Int): Double {
            val startDate = maxOf(firstOccurrenceDate, today.minusDays(days.toLong() - 1))
            val scheduledDates = recurrenceConfig.getScheduledDatesInRange(startDate, today)
            
            if (scheduledDates.isEmpty()) return 1.0
            
            var completed = 0
            var total = 0
            
            for (date in scheduledDates) {
                val status = occurrences[date]
                when (status) {
                    "completed" -> {
                        completed++
                        total++
                    }
                    "skipped" -> {
                        // Skipped doesn't count as completed or missed
                        // It's "not applicable" for that day
                    }
                    "missed" -> {
                        total++
                    }
                    null -> {
                        // No record for a scheduled day = missed
                        total++
                    }
                }
            }
            
            return if (total > 0) completed.toDouble() / total else 1.0
        }
        
        // Calculate streaks on scheduled days only
        val allScheduledDates = recurrenceConfig.getScheduledDatesInRange(firstOccurrenceDate, today)
            .sortedDescending() // Most recent first
        
        var currentStreak = 0
        var longestStreak = 0
        var tempStreak = 0
        
        // For longest streak, process from oldest to newest
        for (date in allScheduledDates.reversed()) {
            val status = occurrences[date]
            if (status == "completed") {
                tempStreak++
                longestStreak = maxOf(longestStreak, tempStreak)
            } else if (status == "missed" || status == null) {
                tempStreak = 0
            }
            // Skipped doesn't break streak (like uHabits)
        }
        
        // For current streak, process from today backwards
        for (date in allScheduledDates) {
            val status = occurrences[date]
            if (status == "completed") {
                currentStreak++
            } else if (status == "missed" || status == null) {
                break // Streak broken
            }
            // Skip continues the streak
        }
        
        logger.d("RecurrenceScoreCalculator.calculateFrequencyAwareStats", "Stats calculated", mapOf(
            "totalScheduledDays" to allScheduledDates.size,
            "recordedOccurrences" to occurrences.size,
            "currentStreak" to currentStreak,
            "longestStreak" to longestStreak
        ))
        
        return CompletionStats(
            completionRate7Days = calculateRateForDays(WINDOW_DAYS_7),
            completionRate30Days = calculateRateForDays(WINDOW_DAYS_30),
            completionRate90Days = calculateRateForDays(WINDOW_DAYS_90),
            allTimeRate = calculateRateForDays(Int.MAX_VALUE),
            currentStreak = currentStreak,
            longestStreak = longestStreak
        )
    }

    /**
     * Calculate frequency-aware completion statistics using an explicit
     * [frequency] and [anchorDate] (rather than a [RecurrenceConfig]).
     *
     * @param occurrences Map of date -> status for recorded occurrences.
     * @param frequency The recurrence frequency for this habit.
     * @param anchorDate The date tracking started for this habit.
     * @param today The reference "today" date (defaults to [LocalDate.now]).
     * @return Completion statistics respecting the recurrence schedule.
     */
    fun calculateFrequencyAwareStats(
        occurrences: Map<LocalDate, String>,
        frequency: DomainFrequency,
        anchorDate: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): CompletionStats {
        if (today.isBefore(anchorDate)) {
            return CompletionStats(
                completionRate7Days = 0.0,
                completionRate30Days = 0.0,
                completionRate90Days = 0.0,
                allTimeRate = 0.0,
                currentStreak = 0,
                longestStreak = 0,
            )
        }

        fun calculateRateForDays(days: Int): Double {
            val startDate = maxOf(anchorDate, today.minusDays(days.toLong() - 1))
            val windows = buildFrequencyWindows(
                occurrences = occurrences,
                frequency = frequency,
                anchorDate = anchorDate,
                rangeStart = startDate,
                rangeEnd = today,
            )
            if (windows.isEmpty()) return 0.0

            val completed = windows.sumOf { min(it.completedCount, it.effectiveTargetCount) }
            val target = windows.sumOf { it.effectiveTargetCount }
            return if (target <= 0) 1.0 else completed.toDouble() / target
        }

        val allWindows = buildFrequencyWindows(
            occurrences = occurrences,
            frequency = frequency,
            anchorDate = anchorDate,
            rangeStart = anchorDate,
            rangeEnd = today,
        )

        var currentStreak = 0
        var longestStreak = 0
        var tempStreak = 0

        allWindows.forEach { window ->
            if (window.isSatisfied) {
                tempStreak++
                longestStreak = max(longestStreak, tempStreak)
            } else {
                tempStreak = 0
            }
        }

        allWindows.asReversed().forEach { window ->
            if (window.isSatisfied) {
                currentStreak++
            } else {
                return@forEach
            }
        }

        val stats = CompletionStats(
            completionRate7Days = calculateRateForDays(WINDOW_DAYS_7),
            completionRate30Days = calculateRateForDays(WINDOW_DAYS_30),
            completionRate90Days = calculateRateForDays(WINDOW_DAYS_90),
            allTimeRate = calculateRateForDays(Int.MAX_VALUE),
            currentStreak = currentStreak,
            longestStreak = longestStreak,
        )

        logger.d(
            "RecurrenceScoreCalculator.calculateFrequencyAwareStats",
            "Frequency-native stats calculated",
            mapOf(
                "frequency" to frequency.serialize(),
                "anchorDate" to anchorDate.toString(),
                "windowCount" to allWindows.size,
                "currentStreak" to stats.currentStreak,
                "longestStreak" to stats.longestStreak,
                "rate7d" to String.format(Locale.getDefault(), "%.3f", stats.completionRate7Days),
            ),
        )

        return stats
    }

    /**
     * Builds the list of frequency windows (each spanning [frequency.denominator]
     * days) covering [rangeStart]..[rangeEnd] anchored at [anchorDate], with per-window
     * completed/skipped counts derived from [occurrences].
     */
    fun buildFrequencyWindows(
        occurrences: Map<LocalDate, String>,
        frequency: DomainFrequency,
        anchorDate: LocalDate,
        rangeStart: LocalDate,
        rangeEnd: LocalDate,
    ): List<FrequencyWindowSummary> {
        if (rangeEnd.isBefore(rangeStart)) return emptyList()

        val denominator = frequency.denominator.coerceAtLeast(1)
        val numerator = frequency.numerator.coerceAtLeast(1)
        val effectiveRangeStart = if (rangeStart.isBefore(anchorDate)) anchorDate else rangeStart
        if (rangeEnd.isBefore(effectiveRangeStart)) return emptyList()

        val firstWindowIndex = Math.floorDiv(
            java.time.temporal.ChronoUnit.DAYS.between(anchorDate, effectiveRangeStart).toInt(),
            denominator,
        )
        var windowStart = anchorDate.plusDays((firstWindowIndex.toLong() * denominator.toLong()))

        if (windowStart.isAfter(effectiveRangeStart)) {
            windowStart = windowStart.minusDays(denominator.toLong())
        }

        val windows = mutableListOf<FrequencyWindowSummary>()
        while (!windowStart.isAfter(rangeEnd)) {
            val windowEnd = windowStart.plusDays((denominator - 1).toLong())
            val coveredStart = maxOf(windowStart, effectiveRangeStart)
            val coveredEnd = minOf(windowEnd, rangeEnd)
            if (!coveredEnd.isBefore(coveredStart)) {
                val coveredDays = java.time.temporal.ChronoUnit.DAYS.between(coveredStart, coveredEnd).toInt() + 1
                val completedCount = occurrences
                    .asSequence()
                    .filter { (date, status) ->
                        !date.isBefore(coveredStart) && !date.isAfter(coveredEnd) && status == "completed"
                    }
                    .map { it.key }
                    .distinct()
                    .count()
                val skippedCount = occurrences
                    .asSequence()
                    .filter { (date, status) ->
                        !date.isBefore(coveredStart) && !date.isAfter(coveredEnd) && status == "skipped"
                    }
                    .map { it.key }
                    .distinct()
                    .count()
                val proportionalTarget = if (coveredDays >= denominator) {
                    numerator
                } else {
                    ceil((coveredDays.toDouble() / denominator.toDouble()) * numerator.toDouble()).toInt().coerceAtLeast(1)
                }
                val effectiveTarget = max(0, proportionalTarget - skippedCount)

                windows += FrequencyWindowSummary(
                    start = windowStart,
                    end = windowEnd,
                    coveredDays = coveredDays,
                    completedCount = completedCount,
                    skippedCount = skippedCount,
                    targetCount = proportionalTarget,
                    effectiveTargetCount = effectiveTarget,
                )
            }
            windowStart = windowStart.plusDays(denominator.toLong())
        }

        return windows
    }
}
