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
        Index(value = ["name"], unique = true),
        Index("is_active"),
    ],
)
data class DayPlanTemplateEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String? = null,
    @ColumnInfo(name = "is_active")
    val isActive: Int = 1,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
