//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-dimension time allocation within a [DayPlanTemplateEntity].
 * Cascades on template deletion.
 */
@Entity(
    tableName = "day_plan_template_allocations",
    foreignKeys = [
        /** Foreign key. */
        ForeignKey(
            entity = DayPlanTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.CASCADE,
        ),
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
        Index(value = ["template_id", "dimension_id"], unique = true),
        /** Index. */
        Index("template_id"),
        /** Index. */
        Index("dimension_id"),
    ],
)
/**
 * DayPlanTemplateAllocationEntity.
 */
data class DayPlanTemplateAllocationEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    @ColumnInfo(name = "template_id")
    /** Template id. */
    val templateId: String,
    @ColumnInfo(name = "dimension_id")
    /** Dimension id. */
    val dimensionId: String,
    @ColumnInfo(name = "planned_minutes")
    /** Planned minutes. */
    val plannedMinutes: Int,
    @ColumnInfo(name = "created_at")
    /** Created at. */
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    /** Updated at. */
    val updatedAt: String,
)
