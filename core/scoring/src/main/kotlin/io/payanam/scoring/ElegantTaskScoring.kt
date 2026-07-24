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
    
    // Duration normalization (shorter tasks get slight boost for quick wins)
    private fun normalizeDuration(minutes: Int): Double {
        return when {
            minutes <= 15 -> 1.0    // Quick win
            minutes <= 30 -> 0.9
            minutes <= 60 -> 0.8
            minutes <= 120 -> 0.65
            minutes <= 240 -> 0.5
            else -> 0.35            // Long tasks
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
        val floor = 0.05
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

    internal fun normalizeImpactLevel(level: String): String {
        return when (level) {
            "Major Impact" -> "High Impact"
            else -> level
        }
    }

    internal fun normalizeAlignmentLevel(level: String): String {
        return when (level) {
            "High Alignment" -> "Strong Alignment"
            "Low Alignment" -> "Weak Alignment"
            else -> level
        }
    }
}

data class TaskScoringDefaults(
    val durationMinutes: Int,
    val impactLevel: String,
    val goalAlignment: String,
    val energyLevel: String,
    val controlLevel: String,
    val lifeIntentionCategory: String
)

data class TaskScoringOptions(
    val impactLevels: List<String>,
    val goalAlignments: List<String>,
    val energyLevels: List<String>,
    val controlLevels: List<String>,
    val lifeDimensions: List<String>
)
