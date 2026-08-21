//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-day planned time allocation for a single life dimension.
 *
 * Once a day passes, these records are historical and should not
 * be retroactively changed. The [source] column traces whether the
 * allocation came from a template, manual entry, or auto-generated
 * from default daily goals.
 */
@Entity(
    tableName = "day_plan_allocations",
    foreignKeys = [
        ForeignKey(
            entity = LifeDimensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["dimension_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = DayPlanTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["day_key", "dimension_id"], unique = true),
        Index("day_key"),
        Index("dimension_id"),
        Index("template_id"),
    ],
)
/**
 * DayPlanAllocationEntity.
 */
data class DayPlanAllocationEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "day_key")
    val dayKey: String,
    @ColumnInfo(name = "dimension_id")
    val dimensionId: String,
    @ColumnInfo(name = "planned_minutes")
    val plannedMinutes: Int,
    val source: String = "manual",
    @ColumnInfo(name = "template_id")
    val templateId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
