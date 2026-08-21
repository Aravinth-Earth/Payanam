//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-day policy that controls how effective day-plan allocations are resolved.
 */
@Entity(
    tableName = "day_plan_policies",
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
        Index("mode"),
        Index("is_starred"),
    ],
)
/**
 * DayPlanPolicyEntity.
 */
data class DayPlanPolicyEntity(
    @PrimaryKey
    @ColumnInfo(name = "day_key")
    val dayKey: String,
    val mode: String = "auto",
    @ColumnInfo(name = "template_id")
    val templateId: String? = null,
    @ColumnInfo(name = "is_starred")
    val isStarred: Int = 0,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
