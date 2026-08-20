//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Cached per-day rollups used by Planning/Reality lens and insights screens.
 * This table stores derived values only, and can be recomputed from source rows.
 */
@Entity(
    tableName = "daily_insights",
    foreignKeys = [
        /** Foreign key. */
        ForeignKey(
            entity = LifeDimensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["dimension_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        /** Index. */
        Index("dimension_id"),
        /** Index. */
        Index(value = ["day_key", "module", "dimension_id"]),
        /** Index. */
        Index("generated_at"),
    ],
)
/**
 * DailyInsightEntity.
 */
data class DailyInsightEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    @ColumnInfo(name = "day_key")
    /** Day key. */
    val dayKey: String,
    /** Module. */
    val module: String,
    @ColumnInfo(name = "dimension_id")
    /** Dimension id. */
    val dimensionId: String? = null,
    @ColumnInfo(name = "planned_minutes")
    /** Planned minutes. */
    val plannedMinutes: Int? = null,
    @ColumnInfo(name = "actual_minutes")
    /** Actual minutes. */
    val actualMinutes: Int? = null,
    @ColumnInfo(name = "focused_minutes")
    /** Focused minutes. */
    val focusedMinutes: Int? = null,
    @ColumnInfo(name = "completed_count")
    /** Completed count. */
    val completedCount: Int? = null,
    @ColumnInfo(name = "total_count")
    /** Total count. */
    val totalCount: Int? = null,
    @ColumnInfo(name = "summary_json")
    /** Summary json. */
    val summaryJson: String? = null,
    @ColumnInfo(name = "generated_at")
    /** Generated at. */
    val generatedAt: String,
)
