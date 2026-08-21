//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for Note table.
 */
@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = LifeDimensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["dimension_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("dimension_id"), Index("day_key")],
)
/**
 * NoteEntity.
 */
data class NoteEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val details: String? = null,
    val lifeIntentionCategory: String,
    @ColumnInfo(name = "dimension_id")
    val dimensionId: String? = null,
    @ColumnInfo(name = "day_key")
    val dayKey: String? = null,
    val createdAt: String,
    val updatedAt: String,
)
