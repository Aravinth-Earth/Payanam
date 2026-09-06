//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber")

package io.payanam.domain.model


/**
 * Domain model for Scoring Configuration.
 * Stores all configurable weights for the elegant task scoring algorithm.
 */
data class ScoringConfig(
    // Factor weights (used in weighted geometric mean)
    val dimensionWeight: Double = 2.0,
    val impactWeight: Double = 1.5,
    val alignmentWeight: Double = 1.3,
    val energyWeight: Double = 1.0,
    val controlWeight: Double = 1.2,
    val durationWeight: Double = 0.8,
    
    // Impact level values (0-1)
    val impactLevelWeights: Map<String, Double> = mapOf(
        "Critical Impact" to 1.0,
        "High Impact" to 0.85,
        "Moderate Impact" to 0.6,
        "Low Impact" to 0.35,
        "Minimal Impact" to 0.15
    ),
    
    // Goal alignment values (0-1)
    val alignmentWeights: Map<String, Double> = mapOf(
        "Perfect Alignment" to 1.0,
        "Strong Alignment" to 0.8,
        "Moderate Alignment" to 0.5,
        "Weak Alignment" to 0.25,
        "No Alignment" to 0.1
    ),
    
    // Energy level values (0-1)
    val energyLevelWeights: Map<String, Double> = mapOf(
        "High" to 1.0,
        "Moderate" to 0.7,
        "Low" to 0.4
    ),
    
    // Control level values (0-1)
    val controlLevelWeights: Map<String, Double> = mapOf(
        "Full Control" to 1.0,
        "Mostly Controllable" to 0.85,
        "Office/Colleagues Dependent" to 0.6,
        "External Dependent" to 0.35,
        "No Control" to 0.1
    ),
    
    // Per-dimension weights
    val dimensionWeightsById: Map<String, Double> = mapOf(
        DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id to 0.8,
        DimensionTaxonomyCatalog.PHYSICAL_HEALTH.id to 0.9,
        DimensionTaxonomyCatalog.FAMILY_RELATIONSHIPS.id to 0.85,
        DimensionTaxonomyCatalog.HOME_ENVIRONMENT.id to 0.75,
        DimensionTaxonomyCatalog.MONEY_FINANCE.id to 0.75,
        DimensionTaxonomyCatalog.MENTAL_HEALTH.id to 0.6,
        DimensionTaxonomyCatalog.RECREATION_LEISURE.id to 0.7,
        DimensionTaxonomyCatalog.LEARNING_GROWTH.id to 0.8,
        DimensionTaxonomyCatalog.COMMUNITY_SERVICE.id to 0.65
    ),
    // Legacy compatibility for still-migrating enum consumers.
    val dimensionWeights: Map<LifeDimension, Double> = mapOf(
        LifeDimension.CAREER_WORK to (dimensionWeightsById[DimensionTaxonomyCatalog.WORK_LIVELIHOOD.id] ?: 0.8),
        LifeDimension.HEALTH_WELLNESS to (dimensionWeightsById[DimensionTaxonomyCatalog.PHYSICAL_HEALTH.id] ?: 0.9),
        LifeDimension.RELATIONSHIPS to (dimensionWeightsById[DimensionTaxonomyCatalog.FAMILY_RELATIONSHIPS.id] ?: 0.85),
        LifeDimension.PERSONAL_GROWTH to (dimensionWeightsById[DimensionTaxonomyCatalog.LEARNING_GROWTH.id] ?: 0.8),
        LifeDimension.FINANCIAL to (dimensionWeightsById[DimensionTaxonomyCatalog.MONEY_FINANCE.id] ?: 0.75),
        LifeDimension.SPIRITUAL to (dimensionWeightsById[DimensionTaxonomyCatalog.MENTAL_HEALTH.id] ?: 0.6),
        LifeDimension.RECREATION to (dimensionWeightsById[DimensionTaxonomyCatalog.RECREATION_LEISURE.id] ?: 0.7),
        LifeDimension.LEARNING to (dimensionWeightsById[DimensionTaxonomyCatalog.LEARNING_GROWTH.id] ?: 0.8),
        LifeDimension.CONTRIBUTION to (dimensionWeightsById[DimensionTaxonomyCatalog.COMMUNITY_SERVICE.id] ?: 0.65)
    )
) {
    @Suppress("MagicNumber")
    companion object {
        /**
         * The out-of-the-box scoring weights/threshold configuration, used
         * when the user has not customized [ScoringConfig] yet.
         */
        fun defaults() = ScoringConfig()
        
        /**
         * Factor labels for UI display.
         */
        val FACTOR_LABELS = listOf(
            "Dimension" to "dimensionWeight",
            "Impact" to "impactWeight",
            "Goal Alignment" to "alignmentWeight",
            "Energy Required" to "energyWeight",
            "Control Level" to "controlWeight",
            "Duration" to "durationWeight"
        )
    }
}
