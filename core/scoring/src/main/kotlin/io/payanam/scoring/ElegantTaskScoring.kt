//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import io.payanam.common.logging.UnifiedLogger
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.model.ScoringConfig
import io.payanam.domain.model.Task
import kotlin.math.pow

/**
 * Elegant Task Scoring Algorithm.
 * 
 * Port of archive-v0.0.2/src/utils/elegantTaskScoring.ts
 * 
 * Uses weighted geometric mean of normalized factors:
 * - Life dimension importance
 * - Impact level
 * - Goal alignment
 * - Energy level match
 * - Control level
 * - Duration (normalized)
 * 
 * Supports configurable weights via ScoringConfig.
 */
object ElegantTaskScoring {

    // Default weights (used if no config provided)
    private val DEFAULT_CONFIG = ScoringConfig.defaults()

    // Duration normalization tiers (minutes): shorter tasks get a slight boost.
    private const val DURATION_QUICK_WIN_MINUTES = 15
    private const val DURATION_TIER_30_MINUTES = 30
    private const val DURATION_TIER_60_MINUTES = 60
    private const val DURATION_TIER_120_MINUTES = 120
    private const val DURATION_TIER_240_MINUTES = 240
    private const val DURATION_QUICK_WIN_SCORE = 1.0
    private const val DURATION_TIER_30_SCORE = 0.9
    private const val DURATION_TIER_60_SCORE = 0.8
    private const val DURATION_TIER_120_SCORE = 0.65
    private const val DURATION_TIER_240_SCORE = 0.5
    private const val DURATION_LONG_SCORE = 0.35
    // Small floor added to each factor to avoid zeroing the product out.
    private const val SCORE_FLOOR = 0.05

    private fun normalizeDuration(minutes: Int): Double {
        return when {
            minutes <= DURATION_QUICK_WIN_MINUTES -> DURATION_QUICK_WIN_SCORE
            minutes <= DURATION_TIER_30_MINUTES -> DURATION_TIER_30_SCORE
            minutes <= DURATION_TIER_60_MINUTES -> DURATION_TIER_60_SCORE
            minutes <= DURATION_TIER_120_MINUTES -> DURATION_TIER_120_SCORE
            minutes <= DURATION_TIER_240_MINUTES -> DURATION_TIER_240_SCORE
            else -> DURATION_LONG_SCORE
        }
    }
    
    /**
     * Calculate the task score (0..1) using default weights.
     */
    fun calculateScore(task: Task): Double {
        return calculateScore(task, DEFAULT_CONFIG)
    }
    
    /**
     * Calculate the task score (0..1) using custom config.
     */
    fun calculateScore(task: Task, config: ScoringConfig): Double {
        val logger = UnifiedLogger.getInstance()
        logger.d("ElegantTaskScoring.calculateScore", "Calculating score for task", mapOf(
            "taskId" to task.id
        ))
        
        // Resolve dimension scoring by canonical dimension_id first.
        val resolvedDimensionId = task.dimensionId ?: LifeDimension.fromDisplayName(task.lifeIntentionCategory)?.id
        val dimensionValue = when {
            resolvedDimensionId != null ->
                config.dimensionWeightsById[resolvedDimensionId]
                    ?: LifeDimension.fromId(resolvedDimensionId)?.let { config.dimensionWeights[it] }
                    ?: 0.5
            else -> {
                val legacyDimension = LifeDimension.fromDisplayName(task.lifeIntentionCategory)
                if (legacyDimension != null) config.dimensionWeights[legacyDimension] ?: 0.5 else 0.5
            }
        }
        
        // Get factor values from config
        val normalizedImpactLevel = normalizeImpactLevel(task.impactLevel)
        val normalizedAlignment = normalizeAlignmentLevel(task.goalAlignment)
        val impactValue = config.impactLevelWeights[normalizedImpactLevel] ?: 0.5
        val alignmentValue = config.alignmentWeights[normalizedAlignment] ?: 0.5
        val energyValue = config.energyLevelWeights[task.energyLevel] ?: 0.5
        val controlValue = config.controlLevelWeights[task.controlLevel] ?: 0.5
        val durationValue = normalizeDuration(task.durationMinutes)
        
        // Add small floor to avoid zeroing out
        val floor = SCORE_FLOOR
        val factors = listOf(
            (dimensionValue + floor).coerceIn(floor, 1.0),
            (impactValue + floor).coerceIn(floor, 1.0),
            (alignmentValue + floor).coerceIn(floor, 1.0),
            (energyValue + floor).coerceIn(floor, 1.0),
            (controlValue + floor).coerceIn(floor, 1.0),
            (durationValue + floor).coerceIn(floor, 1.0)
        )
        
        // Factor weights from config
        val weights = listOf(
            config.dimensionWeight,
            config.impactWeight,
            config.alignmentWeight,
            config.energyWeight,
            config.controlWeight,
            config.durationWeight
        )
        val totalWeight = weights.sum()
        
        var product = 1.0
        for (i in factors.indices) {
            product *= factors[i].pow(weights[i])
        }
        
        val score = product.pow(1.0 / totalWeight)
        
        logger.i("ElegantTaskScoring.calculateScore", "Score calculated", mapOf(
            "taskId" to task.id,
            "score" to score,
            "dimensionId" to (resolvedDimensionId ?: "unknown"),
            "impact" to task.impactLevel,
            "impactNormalized" to normalizedImpactLevel,
            "alignment" to task.goalAlignment,
            "alignmentNormalized" to normalizedAlignment
        ))
        
        return score
    }
    
    /**
     * Get default scoring parameter values.
     */
    fun getDefaults() = TaskScoringDefaults(
        durationMinutes = 10,
        impactLevel = "Moderate Impact",
        goalAlignment = "Moderate Alignment",
        energyLevel = "Moderate",
        controlLevel = "Office/Colleagues Dependent",
        lifeIntentionCategory = "Career & Work"
    )
    
    /**
     * Get all available options for each scoring parameter.
     */
    fun getOptions() = TaskScoringOptions(
        impactLevels = DEFAULT_CONFIG.impactLevelWeights.keys.toList(),
        goalAlignments = DEFAULT_CONFIG.alignmentWeights.keys.toList(),
        energyLevels = DEFAULT_CONFIG.energyLevelWeights.keys.toList(),
        controlLevels = DEFAULT_CONFIG.controlLevelWeights.keys.toList(),
        lifeDimensions = LifeDimension.allDisplayNames()
    )

    /** Normalizes a legacy impact level label to the canonical form. */
    internal fun normalizeImpactLevel(level: String): String {
        return when (level) {
            "Major Impact" -> "High Impact"
            else -> level
        }
    }

    /** Normalizes a legacy alignment level label to the canonical form. */
    internal fun normalizeAlignmentLevel(level: String): String {
        return when (level) {
            "High Alignment" -> "Strong Alignment"
            "Low Alignment" -> "Weak Alignment"
            else -> level
        }
    }
}

/**
 * Default values for a task's scoring parameters.
 *
 * @property durationMinutes Default planned duration in minutes.
 * @property impactLevel Default impact-level label.
 * @property goalAlignment Default goal-alignment label.
 * @property energyLevel Default energy-level label.
 * @property controlLevel Default control-level label.
 * @property lifeIntentionCategory Default life-intention category.
 */
data class TaskScoringDefaults(
    val durationMinutes: Int,
    val impactLevel: String,
    val goalAlignment: String,
    val energyLevel: String,
    val controlLevel: String,
    val lifeIntentionCategory: String
)

/**
 * Available option sets for each scoring parameter.
 *
 * @property impactLevels Selectable impact levels.
 * @property goalAlignments Selectable goal-alignment levels.
 * @property energyLevels Selectable energy levels.
 * @property controlLevels Selectable control levels.
 * @property lifeDimensions Selectable life-dimension display names.
 */
data class TaskScoringOptions(
    val impactLevels: List<String>,
    val goalAlignments: List<String>,
    val energyLevels: List<String>,
    val controlLevels: List<String>,
    val lifeDimensions: List<String>
)
