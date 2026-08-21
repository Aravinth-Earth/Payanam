//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for Scoring Configuration.
 * Stores configurable weights for the scoring algorithm.
 *
 * Uses a single row design (id=1) for simplicity - only one config per app.
 */
@Entity(tableName = "scoring_config")
/**
 * Holds the scoring config entity.
 */
data class ScoringConfigEntity(
    @PrimaryKey
    val id: Int = 1,
    // Factor weights (used in weighted geometric mean)
    val dimensionWeight: Double = 2.0,
    val impactWeight: Double = 1.5,
    val alignmentWeight: Double = 1.3,
    val energyWeight: Double = 1.0,
    val controlWeight: Double = 1.2,
    val durationWeight: Double = 0.8,
    // Impact level values (0-1)
    val impactCritical: Double = 1.0,
    val impactHigh: Double = 0.85,
    val impactModerate: Double = 0.6,
    val impactLow: Double = 0.35,
    val impactMinimal: Double = 0.15,
    // Goal alignment values (0-1)
    val alignmentPerfect: Double = 1.0,
    val alignmentStrong: Double = 0.8,
    val alignmentModerate: Double = 0.5,
    val alignmentWeak: Double = 0.25,
    val alignmentNone: Double = 0.1,
    // Energy level values (0-1)
    val energyHigh: Double = 1.0,
    val energyModerate: Double = 0.7,
    val energyLow: Double = 0.4,
    // Control level values (0-1)
    val controlFull: Double = 1.0,
    val controlMostly: Double = 0.85,
    val controlOffice: Double = 0.6,
    val controlExternal: Double = 0.35,
    val controlNone: Double = 0.1,
    // Per-dimension weights (keyed by LifeDimension enum name)
    val dimensionCareerWork: Double = 0.8,
    val dimensionHealthWellness: Double = 0.9,
    val dimensionRelationships: Double = 0.85,
    val dimensionPersonalGrowth: Double = 0.8,
    val dimensionFinancial: Double = 0.75,
    val dimensionSpiritual: Double = 0.6,
    val dimensionRecreation: Double = 0.7,
    val dimensionLearning: Double = 0.8,
    val dimensionContribution: Double = 0.65,
    val updatedAt: String,
)
