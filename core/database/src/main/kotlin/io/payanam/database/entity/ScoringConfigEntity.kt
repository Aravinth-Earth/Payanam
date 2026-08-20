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
 * ScoringConfigEntity.
 */
data class ScoringConfigEntity(
    @PrimaryKey
    /** Id. */
    val id: Int = 1,
    // Factor weights (used in weighted geometric mean)
    /** Dimension weight. */
    val dimensionWeight: Double = 2.0,
    /** Impact weight. */
    val impactWeight: Double = 1.5,
    /** Alignment weight. */
    val alignmentWeight: Double = 1.3,
    /** Energy weight. */
    val energyWeight: Double = 1.0,
    /** Control weight. */
    val controlWeight: Double = 1.2,
    /** Duration weight. */
    val durationWeight: Double = 0.8,
    // Impact level values (0-1)
    /** Impact critical. */
    val impactCritical: Double = 1.0,
    /** Impact high. */
    val impactHigh: Double = 0.85,
    /** Impact moderate. */
    val impactModerate: Double = 0.6,
    /** Impact low. */
    val impactLow: Double = 0.35,
    /** Impact minimal. */
    val impactMinimal: Double = 0.15,
    // Goal alignment values (0-1)
    /** Alignment perfect. */
    val alignmentPerfect: Double = 1.0,
    /** Alignment strong. */
    val alignmentStrong: Double = 0.8,
    /** Alignment moderate. */
    val alignmentModerate: Double = 0.5,
    /** Alignment weak. */
    val alignmentWeak: Double = 0.25,
    /** Alignment none. */
    val alignmentNone: Double = 0.1,
    // Energy level values (0-1)
    /** Energy high. */
    val energyHigh: Double = 1.0,
    /** Energy moderate. */
    val energyModerate: Double = 0.7,
    /** Energy low. */
    val energyLow: Double = 0.4,
    // Control level values (0-1)
    /** Control full. */
    val controlFull: Double = 1.0,
    /** Control mostly. */
    val controlMostly: Double = 0.85,
    /** Control office. */
    val controlOffice: Double = 0.6,
    /** Control external. */
    val controlExternal: Double = 0.35,
    /** Control none. */
    val controlNone: Double = 0.1,
    // Per-dimension weights (keyed by LifeDimension enum name)
    /** Dimension career work. */
    val dimensionCareerWork: Double = 0.8,
    /** Dimension health wellness. */
    val dimensionHealthWellness: Double = 0.9,
    /** Dimension relationships. */
    val dimensionRelationships: Double = 0.85,
    /** Dimension personal growth. */
    val dimensionPersonalGrowth: Double = 0.8,
    /** Dimension financial. */
    val dimensionFinancial: Double = 0.75,
    /** Dimension spiritual. */
    val dimensionSpiritual: Double = 0.6,
    /** Dimension recreation. */
    val dimensionRecreation: Double = 0.7,
    /** Dimension learning. */
    val dimensionLearning: Double = 0.8,
    /** Dimension contribution. */
    val dimensionContribution: Double = 0.65,
    /** Updated at. */
    val updatedAt: String,
)
