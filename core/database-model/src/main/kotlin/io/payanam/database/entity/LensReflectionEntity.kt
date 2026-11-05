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
        Index(value = ["day_key", "dimension_id", "reflection_type"]),
        Index("created_at"),
    ],
)
data class LensReflectionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "day_key")
    val dayKey: String,
    @ColumnInfo(name = "dimension_id")
    val dimensionId: String? = null,
    @ColumnInfo(name = "reflection_type")
    val reflectionType: String,
    val title: String,
    val description: String? = null,
    @ColumnInfo(name = "gap_minutes")
    val gapMinutes: Int? = null,
    @ColumnInfo(name = "related_entity_id")
    val relatedEntityId: String? = null,
    @ColumnInfo(name = "is_addressed")
    val isAddressed: Int = 0,
    @ColumnInfo(name = "user_note")
    val userNote: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
)
