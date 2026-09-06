//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.LifeDimension
import kotlin.math.exp

/**
 * Time deviation scoring for day planning.
 *
 * Scores how well actual tracked time matches planned allocations per dimension.
 * Uses a Gaussian deviation model centered on the planned target:
 *
 *   score = exp(-k * ((actual - planned) / planned)^2)
 *
 * Properties:
 * - Perfect match (actual == planned) → 1.0
 * - Zero actual when planned > 0 → near 0.0
 * - Over-tracking (actual >> planned) → near 0.0
 * - Bidirectional: same percentage deviation gives same penalty regardless of direction
 */
object TimeDeviationScoring {

    private val logger = UnifiedLogger.getInstance()

    /**
     * Steepness constant for the Gaussian curve.
     * exp(-4) ≈ 0.018, so 100% deviation yields ~1.8% score.
     */
    private const val STEEPNESS = 4.0

    /**
     * Calculate dimension time score for a single dimension.
     *
     * @param plannedMinutes target minutes for this dimension
     * @param actualMinutes actual tracked minutes for this dimension
     * @return score between 0.0 and 1.0
     */
    fun calculateDimensionScore(plannedMinutes: Int, actualMinutes: Long): Double {
        if (plannedMinutes == 0 && actualMinutes == 0L) return 1.0
        if (plannedMinutes == 0) {
            // No plan but tracked time: penalize proportionally
            val overHours = actualMinutes.toDouble() / 60.0
            return exp(-STEEPNESS * overHours * overHours).coerceIn(0.0, 1.0)
        }
        val ratio = (actualMinutes.toDouble() - plannedMinutes) / plannedMinutes
        return exp(-STEEPNESS * ratio * ratio).coerceIn(0.0, 1.0)
    }

    /**
     * Calculate overall day time score as weighted average of dimension scores.
     * Weights come from [LifeDimension.weight].
     */
    fun calculateDayScore(dimensionScores: List<DimensionTimeScore>): Double {
        if (dimensionScores.isEmpty()) return 0.0

        var weightedSum = 0.0
        var totalWeight = 0.0

        dimensionScores.forEach { ds ->
            val dimension = LifeDimension.fromId(ds.dimensionId)
            val weight = dimension?.weight ?: 0.5
            weightedSum += ds.score * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) (weightedSum / totalWeight).coerceIn(0.0, 1.0) else 0.0
    }

    /**
     * Calculate full day time score given planned allocations and actual time entries.
     *
     * @param dayKey YYYY-MM-DD key for the day
     * @param allocations list of (dimensionId, plannedMinutes) pairs
     * @param actualByDimension map of dimensionId to actual tracked minutes
     * @return [DayTimeScore] with per-dimension and overall scores
     */
    fun calculateFullDayScore(
        dayKey: String,
        allocations: List<Pair<String, Int>>,
        actualByDimension: Map<String, Long>
    ): DayTimeScore {
        val dimensionScores = allocations.map { (dimId, planned) ->
            val actual = actualByDimension[dimId] ?: 0L
            DimensionTimeScore(
                dimensionId = dimId,
                plannedMinutes = planned,
                actualMinutes = actual,
                score = calculateDimensionScore(planned, actual)
            )
        }

        val overallScore = calculateDayScore(dimensionScores)

        logger.d(
            "TimeDeviationScoring.calculateFullDayScore",
            "Day score computed",
            mapOf(
                "dayKey" to dayKey,
                "dimensions" to dimensionScores.size.toString(),
                "overallScore" to String.format(java.util.Locale.US, "%.3f", overallScore)
            )
        )

        return DayTimeScore(
            dayKey = dayKey,
            dimensionScores = dimensionScores,
            overallScore = overallScore,
            isPlanned = allocations.isNotEmpty()
        )
    }
}

/**
 * Score result for a single dimension's time adherence.
 *
 * @property dimensionId Identifier of the dimension scored.
 * @property plannedMinutes Planned minutes for the dimension.
 * @property actualMinutes Actual tracked minutes for the dimension.
 * @property score Adherence score between 0.0 and 1.0.
 */
data class DimensionTimeScore(
    val dimensionId: String,
    val plannedMinutes: Int,
    val actualMinutes: Long,
    val score: Double
)

/**
 * Aggregated day time score with per-dimension breakdowns.
 *
 * @property dayKey YYYY-MM-DD key for the day.
 * @property dimensionScores Per-dimension adherence scores.
 * @property overallScore Weighted overall score between 0.0 and 1.0.
 * @property isPlanned True when the day had planned allocations.
 */
data class DayTimeScore(
    val dayKey: String,
    val dimensionScores: List<DimensionTimeScore>,
    val overallScore: Double,
    val isPlanned: Boolean
)
