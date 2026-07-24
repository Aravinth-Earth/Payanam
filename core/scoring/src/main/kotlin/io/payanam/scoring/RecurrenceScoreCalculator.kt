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
 */
data class CompletionStats(
    val completionRate7Days: Double,
    val completionRate30Days: Double,
    val completionRate90Days: Double,
    val allTimeRate: Double,
    val currentStreak: Int,
    val longestStreak: Int
)

data class FrequencyWindowSummary(
    val start: LocalDate,
    val end: LocalDate,
    val coveredDays: Int,
    val completedCount: Int,
    val skippedCount: Int,
    val targetCount: Int,
    val effectiveTargetCount: Int
) {
    val isSatisfied: Boolean get() = completedCount >= effectiveTargetCount
    val completionRatio: Double
        get() = if (effectiveTargetCount <= 0) 1.0 else completedCount.toDouble() / effectiveTargetCount
}

/**
 * Calculates recurrence decay scores using the uHabits-inspired model.
 */
object RecurrenceScoreCalculator {
    
    private val logger = UnifiedLogger.getInstance()
    
    private const val DECAY_CONSTANT = 13.0
    
    /**
     * Represents frequency as "X times per Y days".
     */
    data class Frequency(
        // How many times
        val numerator: Int,
        // Per how many days
        val denominator: Int
    ) {
        fun toDouble() = numerator.toDouble() / denominator
        
        companion object {
            val DAILY = Frequency(1, 1)
            val EVERY_OTHER_DAY = Frequency(1, 2)
            val WEEKLY = Frequency(1, 7)
            val TWO_PER_WEEK = Frequency(2, 7)
            val THREE_PER_WEEK = Frequency(3, 7)
            val MONTHLY = Frequency(1, 30)
            val YEARLY = Frequency(1, 365)
        }
    }

    fun fromRecurrenceConfig(config: RecurrenceConfig): Frequency {
        val (numerator, denominator) = config.toFrequency()
        return Frequency(
            numerator = numerator.coerceAtLeast(1),
            denominator = denominator.coerceAtLeast(1),
        )
    }

    fun fromFrequency(frequency: DomainFrequency): Frequency =
        Frequency(
            numerator = frequency.numerator.coerceAtLeast(1),
            denominator = frequency.denominator.coerceAtLeast(1),
        )

    fun fromRule(rule: String?): Frequency = fromFrequency(DomainFrequency.legacyParse(rule))
    
    fun parseRRuleToFrequency(rrule: String?): Frequency = fromRule(rrule)
    
    fun calculateDecayMultiplier(frequency: Frequency): Double {
        return 0.5.pow(sqrt(frequency.toDouble()) / DECAY_CONSTANT)
    }
    
    fun calculateDecayedScore(
        previousScore: Double,
        daysMissed: Int,
        frequency: Frequency
    ): Double {
        if (daysMissed <= 0) return previousScore
        
        val multiplier = calculateDecayMultiplier(frequency)
        var score = previousScore
        
        repeat(daysMissed) {
            score *= multiplier
        }
        
        return score.coerceIn(0.0, 1.0)
    }
    
    fun calculateNewScore(
        previousScore: Double,
        completed: Boolean,
        frequency: Frequency
    ): Double {
        val multiplier = calculateDecayMultiplier(frequency)
        val checkValue = if (completed) 1.0 else 0.0
        
        val newScore = previousScore * multiplier + checkValue * (1 - multiplier)
        
        logger.d("RecurrenceScoreCalculator.calculateNewScore", "Score update", mapOf(
            "previousScore" to String.format(Locale.getDefault(), "%.3f", previousScore),
            "completed" to completed,
            "frequency" to "${frequency.numerator}/${frequency.denominator}",
            "multiplier" to String.format(Locale.getDefault(), "%.4f", multiplier),
            "newScore" to String.format(Locale.getDefault(), "%.3f", newScore)
        ))
        
        return newScore.coerceIn(0.0, 1.0)
    }
    
    fun calculateSkippedScore(previousScore: Double): Double {
        return previousScore
    }

    fun calculateDerivedFrequencyScore(
        occurrences: Map<LocalDate, String>,
        frequency: DomainFrequency,
        anchorDate: LocalDate,
        today: LocalDate = LocalDate.now(),
        seedScore: Double = 1.0,
    ): Double {
        if (today.isBefore(anchorDate)) return seedScore

        val scoringFrequency = scoringFrequency(frequency)
        val windows = buildFrequencyWindows(
            occurrences = occurrences,
            frequency = frequency,
            anchorDate = anchorDate,
            rangeStart = anchorDate,
            rangeEnd = today,
        )

        var score = seedScore.coerceIn(0.0, 1.0)
        windows.forEach { window ->
            repeat(window.completedCount) {
                score = calculateNewScore(score, completed = true, frequency = scoringFrequency)
            }
            val missedCount = max(0, window.effectiveTargetCount - window.completedCount)
            repeat(missedCount) {
                score = calculateNewScore(score, completed = false, frequency = scoringFrequency)
            }
        }

        logger.d(
            "RecurrenceScoreCalculator.calculateDerivedFrequencyScore",
            "Derived frequency habit score",
            mapOf(
                "frequency" to frequency.serialize(),
                "anchorDate" to anchorDate.toString(),
                "windows" to windows.size,
                "seedScore" to String.format(Locale.getDefault(), "%.3f", seedScore),
                "derivedScore" to String.format(Locale.getDefault(), "%.3f", score),
            ),
        )

        return score.coerceIn(0.0, 1.0)
    }
    
    fun calculateScoreAfterGap(
        previousScore: Double,
        daysMissed: Int,
        frequency: Frequency
    ): Double {
        if (daysMissed <= 0) return previousScore
        
        val multiplier = calculateDecayMultiplier(frequency)
        
        // Apply decay for each missed day
        // newScore = previousScore * (multiplier ^ daysMissed)
        val compoundMultiplier = multiplier.pow(daysMissed.toDouble())
        val newScore = previousScore * compoundMultiplier
        
        logger.i("RecurrenceScoreCalculator.calculateScoreAfterGap", "Gap decay applied", mapOf(
            "previousScore" to String.format(Locale.getDefault(), "%.3f", previousScore),
            "daysMissed" to daysMissed,
            "frequency" to "${frequency.numerator}/${frequency.denominator}",
            "compoundMultiplier" to String.format(Locale.getDefault(), "%.6f", compoundMultiplier),
            "newScore" to String.format(Locale.getDefault(), "%.3f", newScore)
        ))
        
        return newScore.coerceIn(0.0, 1.0)
    }
    
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
        val recentOccurrences = occurrences.filter { it.first <= 7 }.sortedBy { it.first }
        for ((_, status) in recentOccurrences) {
            if (status == "completed") {
                currentStreak++
            } else if (status == "missed") {
                break
            }
        }
        
        return CompletionStats(
            completionRate7Days = calculateRate(7),
            completionRate30Days = calculateRate(30),
            completionRate90Days = calculateRate(90),
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
            completionRate7Days = calculateRateForDays(7),
            completionRate30Days = calculateRateForDays(30),
            completionRate90Days = calculateRateForDays(90),
            allTimeRate = calculateRateForDays(Int.MAX_VALUE),
            currentStreak = currentStreak,
            longestStreak = longestStreak
        )
    }

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
            completionRate7Days = calculateRateForDays(7),
            completionRate30Days = calculateRateForDays(30),
            completionRate90Days = calculateRateForDays(90),
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

    private fun scoringFrequency(frequency: DomainFrequency): Frequency =
        if (frequency.denominator <= 1) {
            fromFrequency(frequency)
        } else {
            Frequency(
                numerator = (frequency.numerator * 2).coerceAtLeast(1),
                denominator = (frequency.denominator * 2).coerceAtLeast(1),
            )
        }
}
