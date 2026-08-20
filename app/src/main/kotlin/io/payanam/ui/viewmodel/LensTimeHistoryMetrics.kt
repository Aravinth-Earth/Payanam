//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.viewmodel

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.DimensionTaxonomyCatalog
import io.payanam.domain.repository.LensRepository
import io.payanam.domain.repository.UnifiedLensSnapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.round

/**
 * TimeModuleDayMetric.
 */
data class TimeModuleDayMetric(
    /** Day key. */
    val dayKey: String,
    /** Day score. */
    val dayScore: Double,
    /** Progress delta. */
    val progressDelta: Double,
    /** Progress streak. */
    val progressStreak: Int,
    /** Per dimension scores. */
    val perDimensionScores: Map<String, Double>,
)

/**
 * TimeModuleHistorySummary.
 */
data class TimeModuleHistorySummary(
    /** Current day key. */
    val currentDayKey: String,
    /** Current day score. */
    val currentDayScore: Double,
    /** Current progress delta. */
    val currentProgressDelta: Double,
    /** Current progress streak. */
    val currentProgressStreak: Int,
    /** Day score rank. */
    val dayScoreRank: Int,
    /** Progress rank. */
    val progressRank: Int,
    /** Streak rank. */
    val streakRank: Int,
    /** Total days. */
    val totalDays: Int,
    /** Metrics. */
    val metrics: List<TimeModuleDayMetric>,
)

private const val SCORE_DECIMALS_FACTOR = 100000.0
private const val SCORE_DAY_MINUTES = 24 * 60
private val metricsLogger = UnifiedLogger.getInstance()
internal suspend fun buildTimeModuleHistorySummary(
    /** Lens repository. */
    lensRepository: LensRepository,
    /** Focus date. */
    focusDate: LocalDate,
    seededDataByDay: Map<String, UnifiedLensSnapshot> = emptyMap(),
    historyDayLimit: Int = Int.MAX_VALUE,
    snapshotLoader: suspend (String) -> UnifiedLensSnapshot = lensRepository::calculateUnifiedSnapshot,
): TimeModuleHistorySummary? {
    /** Effective focus date. */
    val effectiveFocusDate = minOf(focusDate, LocalDate.now())
    /** First tracked date. */
    val firstTrackedDate = lensRepository.getFirstTrackedDate() ?: return null
    /** If. */
    if (effectiveFocusDate.isBefore(firstTrackedDate)) {
        return null
    }

    /** Bounded history limit. */
    val boundedHistoryLimit = historyDayLimit.coerceAtLeast(1)
    /** Start date. */
    val startDate = if (historyDayLimit == Int.MAX_VALUE) {
        /** First tracked date. */
        firstTrackedDate
    } else {
        /** Max of. */
        maxOf(firstTrackedDate, effectiveFocusDate.minusDays((boundedHistoryLimit - 1).toLong()))
    }

    /** Days. */
    val days = mutableListOf<Pair<LocalDate, UnifiedLensSnapshot>>()
    /** Cursor. */
    var cursor = startDate
    /** While. */
    while (!cursor.isAfter(effectiveFocusDate)) {
        /** Day key. */
        val dayKey = cursor.format(DateTimeFormatter.ISO_LOCAL_DATE)
        /** Snapshot. */
        val snapshot = seededDataByDay[dayKey] ?: snapshotLoader(dayKey)
        days.add(cursor to snapshot)
        cursor = cursor.plusDays(1)
    }

    /** Metrics. */
    val metrics = buildTimeModuleDayMetrics(days)
    /** If. */
    if (metrics.isEmpty()) {
        return null
    }
    /** Current. */
    val current = metrics.last()

    metricsLogger.d(
        "LensTimeHistoryMetrics.buildTimeModuleHistorySummary",
        "Built time history summary",
        /** Map of. */
        mapOf(
            "focusDate" to focusDate.toString(),
            "effectiveFocusDate" to effectiveFocusDate.toString(),
            "firstTrackedDate" to firstTrackedDate.toString(),
            "startDate" to startDate.toString(),
            "requestedHistoryDayLimit" to historyDayLimit,
            "days" to metrics.size,
        ),
    )

    return TimeModuleHistorySummary(
        currentDayKey = current.dayKey,
        currentDayScore = current.dayScore,
        currentProgressDelta = current.progressDelta,
        currentProgressStreak = current.progressStreak,
        dayScoreRank = rankByDescending(metrics.map { it.dayScore }, current.dayScore),
        progressRank = rankByDescending(metrics.map { it.progressDelta }, current.progressDelta),
        streakRank = rankByDescending(metrics.map { it.progressStreak.toDouble() }, current.progressStreak.toDouble()),
        totalDays = metrics.size,
        metrics = metrics,
    )
}

internal fun buildTimeModuleDayMetrics(
    days: List<Pair<LocalDate, UnifiedLensSnapshot>>,
): List<TimeModuleDayMetric> {
    /** Previous score. */
    var previousScore = 0.0
    /** Previous delta. */
    var previousDelta = 0.0
    /** Previous streak. */
    var previousStreak = 0
    return days.mapIndexed { index, (date, snapshot) ->
        /** Day score. */
        val dayScore = roundToScorePrecision(
            /** Calculate weighted time module score. */
            calculateWeightedTimeModuleScore(
                plannedByDimension = snapshot.planning.budgetAllocationsByDimension,
                actualByDimension = snapshot.reality.actualTimeByDimension,
            ),
        )
        /** Per dimension scores. */
        val perDimensionScores = calculatePerDimensionTimeScores(
            plannedByDimension = snapshot.planning.budgetAllocationsByDimension,
            actualByDimension = snapshot.reality.actualTimeByDimension,
        )
        /** Delta. */
        val delta = if (index == 0) {
            0.0
        } else {
            /** Round to score precision. */
            roundToScorePrecision(dayScore - previousScore)
        }
        /** Streak. */
        val streak = if (index == 0) {
            0
        } else if (delta > previousDelta) {
            previousStreak + 1
        } else {
            0
        }
        previousScore = dayScore
        previousDelta = delta
        previousStreak = streak
        /** Time module day metric. */
        TimeModuleDayMetric(
            dayKey = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
            dayScore = dayScore,
            progressDelta = delta,
            progressStreak = streak,
            perDimensionScores = perDimensionScores,
        )
    }
}

internal fun calculateWeightedTimeModuleScore(
    plannedByDimension: Map<String, Int>,
    actualByDimension: Map<String, Int>,
): Double {
    /** Normalized planned. */
    val normalizedPlanned = normalizeDimensionMap(plannedByDimension)
    /** Normalized actual. */
    val normalizedActual = normalizeDimensionMap(actualByDimension)
    /** Dimension ids. */
    val dimensionIds = (normalizedPlanned.keys + normalizedActual.keys).toSet()
    /** If. */
    if (dimensionIds.isEmpty()) {
        return 0.0
    }
    /** Weighted score. */
    var weightedScore = 0.0
    /** Total weight. */
    var totalWeight = 0.0
    dimensionIds.forEach { dimensionId ->
        /** Planned. */
        val planned = (normalizedPlanned[dimensionId] ?: 0).coerceIn(0, SCORE_DAY_MINUTES)
        /** Actual. */
        val actual = (normalizedActual[dimensionId] ?: 0).coerceIn(0, SCORE_DAY_MINUTES)
        /** Score. */
        val score = calculateBoundedTimeModuleScore(plannedMinutes = planned, actualMinutes = actual)
        /** Weight. */
        val weight = DimensionTaxonomyCatalog.defaultWeightForDimensionId(dimensionId)
        weightedScore += score * weight
        totalWeight += weight
    }
    return if (totalWeight > 0.0) (weightedScore / totalWeight).coerceIn(0.0, 1.0) else 0.0
}

private fun normalizeDimensionMap(map: Map<String, Int>): Map<String, Int> {
    /** Result. */
    val result = mutableMapOf<String, Int>()
    map.forEach { (dimId, minutes) ->
        /** Canonical id. */
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimId)?.id ?: dimId
        result[canonicalId] = (result[canonicalId] ?: 0) + minutes
    }
    return result
}

internal fun calculateBoundedTimeModuleScore(plannedMinutes: Int, actualMinutes: Int): Double {
    /** Planned. */
    val planned = plannedMinutes.coerceIn(0, SCORE_DAY_MINUTES)
    /** Actual. */
    val actual = actualMinutes.coerceIn(0, SCORE_DAY_MINUTES)
    /** If. */
    if (planned == actual) return 1.0
    /** If. */
    if (actual < planned) {
        /** If. */
        if (planned == 0) return 0.0
        /** Lower span. */
        val lowerSpan = planned.toDouble()
        /** Deviation. */
        val deviation = (planned - actual).toDouble()
        /** Return. */
        return (1.0 - (deviation / lowerSpan)).coerceIn(0.0, 1.0)
    }
    /** If. */
    if (planned == SCORE_DAY_MINUTES) return 0.0
    /** Upper span. */
    val upperSpan = (SCORE_DAY_MINUTES - planned).toDouble()
    /** Deviation. */
    val deviation = (actual - planned).toDouble()
    /** Return. */
    return (1.0 - (deviation / upperSpan)).coerceIn(0.0, 1.0)
}

private fun rankByDescending(values: List<Double>, current: Double): Int = 1 + values.count { it > current }

internal fun calculatePerDimensionTimeScores(
    plannedByDimension: Map<String, Int>,
    actualByDimension: Map<String, Int>,
): Map<String, Double> {
    /** Normalized planned. */
    val normalizedPlanned = normalizeDimensionMap(plannedByDimension)
    /** Normalized actual. */
    val normalizedActual = normalizeDimensionMap(actualByDimension)
    /** Dimension ids. */
    val dimensionIds = (normalizedPlanned.keys + normalizedActual.keys).toSet()
    /** If. */
    if (dimensionIds.isEmpty()) {
        return emptyMap()
    }
    return dimensionIds.associateWith { dimensionId ->
        /** Planned. */
        val planned = (normalizedPlanned[dimensionId] ?: 0).coerceIn(0, SCORE_DAY_MINUTES)
        /** Actual. */
        val actual = (normalizedActual[dimensionId] ?: 0).coerceIn(0, SCORE_DAY_MINUTES)
        /** Round to score precision. */
        roundToScorePrecision(
            /** Calculate bounded time module score. */
            calculateBoundedTimeModuleScore(
                plannedMinutes = planned,
                actualMinutes = actual,
            ),
        )
    }
}

internal fun roundToScorePrecision(value: Double): Double = round(value * SCORE_DECIMALS_FACTOR) / SCORE_DECIMALS_FACTOR
