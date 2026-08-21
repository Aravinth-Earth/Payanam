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
    val dayKey: String,
    val dayScore: Double,
    val progressDelta: Double,
    val progressStreak: Int,
    val perDimensionScores: Map<String, Double>,
)

/**
 * TimeModuleHistorySummary.
 */
data class TimeModuleHistorySummary(
    val currentDayKey: String,
    val currentDayScore: Double,
    val currentProgressDelta: Double,
    val currentProgressStreak: Int,
    val dayScoreRank: Int,
    val progressRank: Int,
    val streakRank: Int,
    val totalDays: Int,
    val metrics: List<TimeModuleDayMetric>,
)

private const val SCORE_DECIMALS_FACTOR = 100000.0
private const val SCORE_DAY_MINUTES = 24 * 60
private val metricsLogger = UnifiedLogger.getInstance()
internal suspend fun buildTimeModuleHistorySummary(
    lensRepository: LensRepository,
    focusDate: LocalDate,
    seededDataByDay: Map<String, UnifiedLensSnapshot> = emptyMap(),
    historyDayLimit: Int = Int.MAX_VALUE,
    snapshotLoader: suspend (String) -> UnifiedLensSnapshot = lensRepository::calculateUnifiedSnapshot,
): TimeModuleHistorySummary? {
    val effectiveFocusDate = minOf(focusDate, LocalDate.now())
    val firstTrackedDate = lensRepository.getFirstTrackedDate() ?: return null
    if (effectiveFocusDate.isBefore(firstTrackedDate)) {
        return null
    }
    val boundedHistoryLimit = historyDayLimit.coerceAtLeast(1)
    val startDate = if (historyDayLimit == Int.MAX_VALUE) {
        firstTrackedDate
    } else {
        maxOf(firstTrackedDate, effectiveFocusDate.minusDays((boundedHistoryLimit - 1).toLong()))
    }
    val days = mutableListOf<Pair<LocalDate, UnifiedLensSnapshot>>()
    var cursor = startDate
    while (!cursor.isAfter(effectiveFocusDate)) {
        val dayKey = cursor.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val snapshot = seededDataByDay[dayKey] ?: snapshotLoader(dayKey)
        days.add(cursor to snapshot)
        cursor = cursor.plusDays(1)
    }
    val metrics = buildTimeModuleDayMetrics(days)
    if (metrics.isEmpty()) {
        return null
    }
    val current = metrics.last()

    metricsLogger.d(
        "LensTimeHistoryMetrics.buildTimeModuleHistorySummary",
        "Built time history summary",
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
    var previousScore = 0.0
    var previousDelta = 0.0
    var previousStreak = 0
    return days.mapIndexed { index, (date, snapshot) ->
        val dayScore = roundToScorePrecision(
            calculateWeightedTimeModuleScore(
                plannedByDimension = snapshot.planning.budgetAllocationsByDimension,
                actualByDimension = snapshot.reality.actualTimeByDimension,
            ),
        )
        val perDimensionScores = calculatePerDimensionTimeScores(
            plannedByDimension = snapshot.planning.budgetAllocationsByDimension,
            actualByDimension = snapshot.reality.actualTimeByDimension,
        )
        val delta = if (index == 0) {
            0.0
        } else {
            roundToScorePrecision(dayScore - previousScore)
        }
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
    val normalizedPlanned = normalizeDimensionMap(plannedByDimension)
    val normalizedActual = normalizeDimensionMap(actualByDimension)
    val dimensionIds = (normalizedPlanned.keys + normalizedActual.keys).toSet()
    if (dimensionIds.isEmpty()) {
        return 0.0
    }
    var weightedScore = 0.0
    var totalWeight = 0.0
    dimensionIds.forEach { dimensionId ->
        val planned = (normalizedPlanned[dimensionId] ?: 0).coerceIn(0, SCORE_DAY_MINUTES)
        val actual = (normalizedActual[dimensionId] ?: 0).coerceIn(0, SCORE_DAY_MINUTES)
        val score = calculateBoundedTimeModuleScore(plannedMinutes = planned, actualMinutes = actual)
        val weight = DimensionTaxonomyCatalog.defaultWeightForDimensionId(dimensionId)
        weightedScore += score * weight
        totalWeight += weight
    }
    return if (totalWeight > 0.0) (weightedScore / totalWeight).coerceIn(0.0, 1.0) else 0.0
}

private fun normalizeDimensionMap(map: Map<String, Int>): Map<String, Int> {
    val result = mutableMapOf<String, Int>()
    map.forEach { (dimId, minutes) ->
        val canonicalId = DimensionTaxonomyCatalog.fromCanonicalId(dimId)?.id ?: dimId
        result[canonicalId] = (result[canonicalId] ?: 0) + minutes
    }
    return result
}

internal fun calculateBoundedTimeModuleScore(plannedMinutes: Int, actualMinutes: Int): Double {
    val planned = plannedMinutes.coerceIn(0, SCORE_DAY_MINUTES)
    val actual = actualMinutes.coerceIn(0, SCORE_DAY_MINUTES)
    if (planned == actual) return 1.0
    if (actual < planned) {
        if (planned == 0) return 0.0
        val lowerSpan = planned.toDouble()
        val deviation = (planned - actual).toDouble()
        return (1.0 - (deviation / lowerSpan)).coerceIn(0.0, 1.0)
    }
    if (planned == SCORE_DAY_MINUTES) return 0.0
    val upperSpan = (SCORE_DAY_MINUTES - planned).toDouble()
    val deviation = (actual - planned).toDouble()
    return (1.0 - (deviation / upperSpan)).coerceIn(0.0, 1.0)
}

private fun rankByDescending(values: List<Double>, current: Double): Int = 1 + values.count { it > current }

internal fun calculatePerDimensionTimeScores(
    plannedByDimension: Map<String, Int>,
    actualByDimension: Map<String, Int>,
): Map<String, Double> {
    val normalizedPlanned = normalizeDimensionMap(plannedByDimension)
    val normalizedActual = normalizeDimensionMap(actualByDimension)
    val dimensionIds = (normalizedPlanned.keys + normalizedActual.keys).toSet()
    if (dimensionIds.isEmpty()) {
        return emptyMap()
    }
    return dimensionIds.associateWith { dimensionId ->
        val planned = (normalizedPlanned[dimensionId] ?: 0).coerceIn(0, SCORE_DAY_MINUTES)
        val actual = (normalizedActual[dimensionId] ?: 0).coerceIn(0, SCORE_DAY_MINUTES)
        roundToScorePrecision(
            calculateBoundedTimeModuleScore(
                plannedMinutes = planned,
                actualMinutes = actual,
            ),
        )
    }
}

internal fun roundToScorePrecision(value: Double): Double = round(value * SCORE_DECIMALS_FACTOR) / SCORE_DECIMALS_FACTOR
