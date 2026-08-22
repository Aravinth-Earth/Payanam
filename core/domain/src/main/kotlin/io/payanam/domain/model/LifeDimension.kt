//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

/**
 * Life Intention Categories - the 9 dimensions of life focus.
 * 
 * Matches LIFE_INTENTIONS from archive-v0.0.2/src/utils/elegantTaskScoring.ts
 */
enum class LifeDimension(
    val id: String,
    val displayName: String,
    val weight: Double,
    val description: String
) {
    CAREER_WORK(
        id = "dim_career_work",
        displayName = "Career & Work",
        weight = 1.0,
        description = "Professional development and work-related tasks"
    ),
    HEALTH_WELLNESS(
        id = "dim_health_wellness",
        displayName = "Health & Wellness",
        weight = 1.0,
        description = "Physical and mental health activities"
    ),
    RELATIONSHIPS(
        id = "dim_relationships",
        displayName = "Relationships",
        weight = 0.9,
        description = "Family, friends, and social connections"
    ),
    PERSONAL_GROWTH(
        id = "dim_personal_growth",
        displayName = "Personal Growth",
        weight = 0.85,
        description = "Self-improvement and personal development"
    ),
    FINANCIAL(
        id = "dim_financial",
        displayName = "Financial",
        weight = 0.8,
        description = "Financial planning and management"
    ),
    SPIRITUAL(
        id = "dim_spiritual",
        displayName = "Spiritual",
        weight = 0.75,
        description = "Spiritual practices and mindfulness"
    ),
    RECREATION(
        id = "dim_recreation",
        displayName = "Recreation",
        weight = 0.7,
        description = "Hobbies, leisure, and entertainment"
    ),
    LEARNING(
        id = "dim_learning",
        displayName = "Learning",
        weight = 0.8,
        description = "Education and skill acquisition"
    ),
    CONTRIBUTION(
        id = "dim_contribution",
        displayName = "Contribution",
        weight = 0.7,
        description = "Giving back, volunteering, community"
    );
    
    companion object {
        /**
         * Resolves a dimension by its stable [id] (e.g. `dim_career_work`).
         */
        fun fromId(id: String): LifeDimension? {
            return entries.find { it.id == id }
        }
        /**
         * Resolves a dimension by its [displayName] (e.g. "Career & Work").
         */
        fun fromDisplayName(name: String): LifeDimension? {
            return entries.find { it.displayName == name }
        }
        /**
         * All dimension display names, for pickers / label lists.
         */
        fun allDisplayNames(): List<String> {
            return entries.map { it.displayName }
        }
    }
}
