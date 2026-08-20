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
        Index("template_id"),
        /** Index. */
        Index("mode"),
        /** Index. */
        Index("is_starred"),
    ],
)
/**
 * DayPlanPolicyEntity.
 */
data class DayPlanPolicyEntity(
    @PrimaryKey
    @ColumnInfo(name = "day_key")
    /** Day key. */
    val dayKey: String,
    /** Mode. */
    val mode: String = "auto",
    @ColumnInfo(name = "template_id")
    /** Template id. */
    val templateId: String? = null,
    @ColumnInfo(name = "is_starred")
    /** Is starred. */
    val isStarred: Int = 0,
    @ColumnInfo(name = "updated_at")
    /** Updated at. */
    val updatedAt: String,
)
