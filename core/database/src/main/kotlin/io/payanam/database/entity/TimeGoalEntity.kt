//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "time_goals",
    foreignKeys = [
        /** Foreign key. */
        ForeignKey(
            entity = LifeDimensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["dimension_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("dimension_id"), Index("period"), Index("is_active")],
)
/**
 * TimeGoalEntity.
 */
data class TimeGoalEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Name. */
    val name: String,
    @ColumnInfo(name = "dimension_id")
    /** Dimension id. */
    val dimensionId: String? = null,
    @ColumnInfo(name = "target_minutes")
    /** Target minutes. */
    val targetMinutes: Int,
    /** Period. */
    val period: String,
    @ColumnInfo(name = "is_active")
    /** Is active. */
    val isActive: Int = 1,
    /** Notes. */
    val notes: String? = null,
    @ColumnInfo(name = "created_at")
    /** Created at. */
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    /** Updated at. */
    val updatedAt: String,
)
