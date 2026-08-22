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
 * A per-dimension target (e.g. weekly minutes) used to surface whether actual
 * tracked time meets the user's intended allocation. One row per dimension per
 * period; [isActive] marks the currently applicable goal.
 */
data class TimeGoalEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo(name = "dimension_id")
    val dimensionId: String? = null,
    @ColumnInfo(name = "target_minutes")
    val targetMinutes: Int,
    val period: String,
    @ColumnInfo(name = "is_active")
    val isActive: Int = 1,
    val notes: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
