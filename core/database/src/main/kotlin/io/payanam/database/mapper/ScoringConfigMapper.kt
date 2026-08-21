//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.mapper

import io.payanam.database.entity.ScoringConfigEntity
import io.payanam.domain.model.LifeDimension
import io.payanam.domain.model.ScoringConfig
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Maps between scoringConfigEntity (Room) and scoringConfig (domain).
 */
object ScoringConfigMapper {
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    /**
     * Builds the domain [ScoringConfig] from a [ScoringConfigEntity], mapping
     * the per-dimension weight columns into the dimension-weight map (with
     * fallback defaults for dimensions the entity does not store).
     */
    fun toDomain(entity: ScoringConfigEntity): ScoringConfig =
        ScoringConfig(
            dimensionWeight = entity.dimensionWeight,
            impactWeight = entity.impactWeight,
            alignmentWeight = entity.alignmentWeight,
            energyWeight = entity.energyWeight,
            controlWeight = entity.controlWeight,
            durationWeight = entity.durationWeight,
            impactLevelWeights =
                mapOf(
                    "Critical Impact" to entity.impactCritical,
                    "High Impact" to entity.impactHigh,
                    "Moderate Impact" to entity.impactModerate,
                    "Low Impact" to entity.impactLow,
                    "Minimal Impact" to entity.impactMinimal,
                ),
            alignmentWeights =
                mapOf(
                    "Perfect Alignment" to entity.alignmentPerfect,
                    "Strong Alignment" to entity.alignmentStrong,
                    "Moderate Alignment" to entity.alignmentModerate,
                    "Weak Alignment" to entity.alignmentWeak,
                    "No Alignment" to entity.alignmentNone,
                ),
            energyLevelWeights =
                mapOf(
                    "High" to entity.energyHigh,
                    "Moderate" to entity.energyModerate,
                    "Low" to entity.energyLow,
                ),
            controlLevelWeights =
                mapOf(
                    "Full Control" to entity.controlFull,
                    "Mostly Controllable" to entity.controlMostly,
                    "Office/Colleagues Dependent" to entity.controlOffice,
                    "External Dependent" to entity.controlExternal,
                    "No Control" to entity.controlNone,
                ),
            dimensionWeights =
                mapOf(
                    LifeDimension.CAREER_WORK to entity.dimensionCareerWork,
                    LifeDimension.HEALTH_WELLNESS to entity.dimensionHealthWellness,
                    LifeDimension.RELATIONSHIPS to entity.dimensionRelationships,
                    LifeDimension.PERSONAL_GROWTH to entity.dimensionPersonalGrowth,
                    LifeDimension.FINANCIAL to entity.dimensionFinancial,
                    LifeDimension.SPIRITUAL to entity.dimensionSpiritual,
                    LifeDimension.RECREATION to entity.dimensionRecreation,
                    LifeDimension.LEARNING to entity.dimensionLearning,
                    LifeDimension.CONTRIBUTION to entity.dimensionContribution,
                ),
            dimensionWeightsById =
                mapOf(
                    LifeDimension.CAREER_WORK.id to entity.dimensionCareerWork,
                    LifeDimension.HEALTH_WELLNESS.id to entity.dimensionHealthWellness,
                    LifeDimension.RELATIONSHIPS.id to entity.dimensionRelationships,
                    LifeDimension.PERSONAL_GROWTH.id to entity.dimensionPersonalGrowth,
                    LifeDimension.FINANCIAL.id to entity.dimensionFinancial,
                    LifeDimension.SPIRITUAL.id to entity.dimensionSpiritual,
                    LifeDimension.RECREATION.id to entity.dimensionRecreation,
                    LifeDimension.LEARNING.id to entity.dimensionLearning,
                    LifeDimension.CONTRIBUTION.id to entity.dimensionContribution,
                ),
        )
    /**
     * Flattens a domain [ScoringConfig] back into a single-row
     * [ScoringConfigEntity], spreading the dimension-weight map across the
     * typed `dimension*` columns.
     */
    fun toEntity(config: ScoringConfig): ScoringConfigEntity {
        val dimensionCareerWork = resolveDimensionWeight(config, LifeDimension.CAREER_WORK, 0.8)
        val dimensionHealthWellness = resolveDimensionWeight(config, LifeDimension.HEALTH_WELLNESS, 0.9)
        val dimensionRelationships = resolveDimensionWeight(config, LifeDimension.RELATIONSHIPS, 0.85)
        val dimensionPersonalGrowth = resolveDimensionWeight(config, LifeDimension.PERSONAL_GROWTH, 0.8)
        val dimensionFinancial = resolveDimensionWeight(config, LifeDimension.FINANCIAL, 0.75)
        val dimensionSpiritual = resolveDimensionWeight(config, LifeDimension.SPIRITUAL, 0.6)
        val dimensionRecreation = resolveDimensionWeight(config, LifeDimension.RECREATION, 0.7)
        val dimensionLearning = resolveDimensionWeight(config, LifeDimension.LEARNING, 0.8)
        val dimensionContribution = resolveDimensionWeight(config, LifeDimension.CONTRIBUTION, 0.65)

        return ScoringConfigEntity(
            id = 1,
            dimensionWeight = config.dimensionWeight,
            impactWeight = config.impactWeight,
            alignmentWeight = config.alignmentWeight,
            energyWeight = config.energyWeight,
            controlWeight = config.controlWeight,
            durationWeight = config.durationWeight,
            impactCritical = config.impactLevelWeights["Critical Impact"] ?: 1.0,
            impactHigh = config.impactLevelWeights["High Impact"] ?: 0.85,
            impactModerate = config.impactLevelWeights["Moderate Impact"] ?: 0.6,
            impactLow = config.impactLevelWeights["Low Impact"] ?: 0.35,
            impactMinimal = config.impactLevelWeights["Minimal Impact"] ?: 0.15,
            alignmentPerfect = config.alignmentWeights["Perfect Alignment"] ?: 1.0,
            alignmentStrong = config.alignmentWeights["Strong Alignment"] ?: 0.8,
            alignmentModerate = config.alignmentWeights["Moderate Alignment"] ?: 0.5,
            alignmentWeak = config.alignmentWeights["Weak Alignment"] ?: 0.25,
            alignmentNone = config.alignmentWeights["No Alignment"] ?: 0.1,
            energyHigh = config.energyLevelWeights["High"] ?: 1.0,
            energyModerate = config.energyLevelWeights["Moderate"] ?: 0.7,
            energyLow = config.energyLevelWeights["Low"] ?: 0.4,
            controlFull = config.controlLevelWeights["Full Control"] ?: 1.0,
            controlMostly = config.controlLevelWeights["Mostly Controllable"] ?: 0.85,
            controlOffice = config.controlLevelWeights["Office/Colleagues Dependent"] ?: 0.6,
            controlExternal = config.controlLevelWeights["External Dependent"] ?: 0.35,
            controlNone = config.controlLevelWeights["No Control"] ?: 0.1,
            dimensionCareerWork = dimensionCareerWork,
            dimensionHealthWellness = dimensionHealthWellness,
            dimensionRelationships = dimensionRelationships,
            dimensionPersonalGrowth = dimensionPersonalGrowth,
            dimensionFinancial = dimensionFinancial,
            dimensionSpiritual = dimensionSpiritual,
            dimensionRecreation = dimensionRecreation,
            dimensionLearning = dimensionLearning,
            dimensionContribution = dimensionContribution,
            updatedAt = LocalDateTime.now().format(formatter),
        )
    }

    private fun resolveDimensionWeight(
        config: ScoringConfig,
        dimension: LifeDimension,
        fallbackValue: Double,
    ): Double =
        config.dimensionWeightsById[dimension.id]
            ?: config.dimensionWeights[dimension]
            ?: fallbackValue
}
