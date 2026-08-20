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
        /** Foreign key. */
        ForeignKey(
            entity = LifeDimensionEntity::class,
            parentColumns = ["id"],
            childColumns = ["dimension_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        /** Foreign key. */
        ForeignKey(
            entity = DayPlanTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        /** Index. */
        Index(value = ["day_key", "dimension_id"], unique = true),
        /** Index. */
        Index("day_key"),
        /** Index. */
        Index("dimension_id"),
        /** Index. */
        Index("template_id"),
    ],
)
/**
 * DayPlanAllocationEntity.
 */
data class DayPlanAllocationEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    @ColumnInfo(name = "day_key")
    /** Day key. */
    val dayKey: String,
    @ColumnInfo(name = "dimension_id")
    /** Dimension id. */
    val dimensionId: String,
    @ColumnInfo(name = "planned_minutes")
    /** Planned minutes. */
    val plannedMinutes: Int,
    /** Source. */
    val source: String = "manual",
    @ColumnInfo(name = "template_id")
    /** Template id. */
    val templateId: String? = null,
    @ColumnInfo(name = "created_at")
    /** Created at. */
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    /** Updated at. */
    val updatedAt: String,
)
