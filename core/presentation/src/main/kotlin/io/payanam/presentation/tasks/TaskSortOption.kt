//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("UndocumentedPublicProperty")

package io.payanam.presentation.tasks

/**
 * Sort orders for the one-time task list; persisted by [key] in app settings.
 */
enum class TaskSortOption(
    val key: String,
) {
    SCORE_DESC("score_desc"),
    SCORE_ASC("score_asc"),
    DUE_DATE_ASC("due_asc"),
    DUE_DATE_DESC("due_desc"),
    TITLE_ASC("title_asc"),
    TITLE_DESC("title_desc"),
    CREATED_DESC("created_desc"),
    CREATED_ASC("created_asc"),
    IMPACT_DESC("impact_desc"),
    ENERGY_ASC("energy_asc"),
    DIMENSION("dimension"),
    ;

    companion object {
        /**
         * Resolves a stored sort key back to a [TaskSortOption], defaulting to
         * due-date-ascending for unknown/null keys.
         */
        fun fromKey(key: String?): TaskSortOption = entries.find { it.key == key } ?: DUE_DATE_ASC
    }
}

/**
 * Simplified sort orders for the habit list, persisted by [key]. Legacy keys
 * from the pre-simplification 18-option model are migrated to their nearest
 * equivalent by [fromKey].
 */
enum class HabitSortOption(
    val key: String,
) {
    BY_NAME("by_name"),
    BY_NAME_REVERSE("by_name_reverse"),
    BY_DUE_TIME("by_due_time"),
    BY_DUE_TIME_REVERSE("by_due_time_reverse"),
    SCORE_HIGH_LOW("score_high_low"),
    SCORE_LOW_HIGH("score_low_high"),
    ;

    companion object {
        /**
         * Resolves a stored sort key (from preferences) back to a [HabitSortOption].
         *
         * New keys map directly. Legacy keys from the pre-simplification 18-option
         * model (metric sorts, by_status, by_life_dimension, by_position) are mapped
         * to the nearest simplified equivalent so previously saved preferences keep
         * working. Unknown or null keys fall back to [SCORE_HIGH_LOW].
         */
        private val legacyMigration =
            mapOf(
                "running_avg_desc" to SCORE_HIGH_LOW,
                "running_avg_asc" to SCORE_LOW_HIGH,
                "score_desc" to SCORE_HIGH_LOW,
                "score_asc" to SCORE_LOW_HIGH,
                "progress_desc" to SCORE_HIGH_LOW,
                "progress_asc" to SCORE_LOW_HIGH,
                "streak_pos_desc" to SCORE_HIGH_LOW,
                "streak_pos_asc" to SCORE_LOW_HIGH,
                "streak_net_desc" to SCORE_HIGH_LOW,
                "streak_net_asc" to SCORE_LOW_HIGH,
                "pos_continue_desc" to SCORE_HIGH_LOW,
                "pos_continue_asc" to SCORE_LOW_HIGH,
                "by_score" to SCORE_HIGH_LOW,
                "by_status" to BY_NAME,
                "by_life_dimension" to BY_NAME,
                "by_position" to BY_NAME,
            )

        /**
         * Resolves a stored sort key to a [HabitSortOption], mapping legacy
         * pre-simplification keys via [legacyMigration] and defaulting to
         * [SCORE_HIGH_LOW] for unknown/null.
         */
        fun fromKey(key: String?): HabitSortOption {
            if (key == null) return SCORE_HIGH_LOW
            entries.find { it.key == key }?.let { return it }
            return legacyMigration[key] ?: SCORE_HIGH_LOW
        }
    }

    /** All simplified options are always visible regardless of scoring flag. */
    fun legacyCategory(): Boolean = true
}

val TaskSortOption.displayName: String
    get() = key

val HabitSortOption.displayName: String
    get() = key
