//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Template mapping used by auto day-plan mode for a given day type.
 */
@Entity(
    tableName = "day_type_template_preferences",
    foreignKeys = [
        ForeignKey(
            entity = DayPlanTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("template_id"),
    ],
)
/**
 * DayTypeTemplatePreferenceEntity.
 */
data class DayTypeTemplatePreferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "day_type")
    val dayType: String,
    @ColumnInfo(name = "template_id")
    val templateId: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
