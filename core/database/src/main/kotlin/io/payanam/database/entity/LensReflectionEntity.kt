//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing a lens reflection card for planning/reality gaps.
 *
 * Reflection types:
 * - untracked_time: Significant untracked time detected
 * - missed_task: Task was due but not completed
 * - missed_habit: Habit was scheduled but not completed
 * - focus_gap: Planned focus time not achieved
 * - dimension_gap: Time gap in a specific life dimension
 */
@Entity(
    tableName = "lens_reflections",
    indices = [
        /** Index. */
        Index(value = ["day_key", "dimension_id", "reflection_type"]),
        /** Index. */
        Index("created_at"),
    ],
)
/**
 * LensReflectionEntity.
 */
data class LensReflectionEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    @ColumnInfo(name = "day_key")
    /** Day key. */
    val dayKey: String,
    @ColumnInfo(name = "dimension_id")
    /** Dimension id. */
    val dimensionId: String? = null,
    @ColumnInfo(name = "reflection_type")
    /** Reflection type. */
    val reflectionType: String,
    /** Title. */
    val title: String,
    /** Description. */
    val description: String? = null,
    @ColumnInfo(name = "gap_minutes")
    /** Gap minutes. */
    val gapMinutes: Int? = null,
    @ColumnInfo(name = "related_entity_id")
    /** Related entity id. */
    val relatedEntityId: String? = null,
    @ColumnInfo(name = "is_addressed")
    /** Is addressed. */
    val isAddressed: Int = 0,
    @ColumnInfo(name = "user_note")
    /** User note. */
    val userNote: String? = null,
    @ColumnInfo(name = "created_at")
    /** Created at. */
    val createdAt: String,
)
