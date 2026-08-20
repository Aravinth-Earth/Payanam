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
        /** Foreign key. */
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
    /** Id. */
    val id: String,
    /** Title. */
    val title: String,
    /** Details. */
    val details: String? = null,
    /** Life intention category. */
    val lifeIntentionCategory: String,
    @ColumnInfo(name = "dimension_id")
    /** Dimension id. */
    val dimensionId: String? = null,
    @ColumnInfo(name = "day_key")
    /** Day key. */
    val dayKey: String? = null,
    /** Created at. */
    val createdAt: String,
    /** Updated at. */
    val updatedAt: String,
)
