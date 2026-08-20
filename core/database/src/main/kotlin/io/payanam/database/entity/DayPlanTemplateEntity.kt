//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Reusable day plan template (e.g., "Work Day", "Leave Day", "Travel Day").
 * Each template holds a set of per-dimension time allocations
 * stored in [DayPlanTemplateAllocationEntity].
 */
@Entity(
    tableName = "day_plan_templates",
    indices = [
        /** Index. */
        Index(value = ["name"], unique = true),
        /** Index. */
        Index("is_active"),
    ],
)
/**
 * DayPlanTemplateEntity.
 */
data class DayPlanTemplateEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Name. */
    val name: String,
    /** Description. */
    val description: String? = null,
    @ColumnInfo(name = "is_active")
    /** Is active. */
    val isActive: Int = 1,
    @ColumnInfo(name = "sort_order")
    /** Sort order. */
    val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at")
    /** Created at. */
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    /** Updated at. */
    val updatedAt: String,
)
