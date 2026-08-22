//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "time_rules",
    indices = [Index("rule_type"), Index("is_active")],
)
/**
 * A configurable rule that auto-classifies or auto-allocates tracked time
 * (e.g. "any entry tagged X counts toward dimension Y"). [ruleType] selects
 * the matcher; [isActive] toggles it without deletion.
 */
data class TimeRuleEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo(name = "rule_type")
    val ruleType: String,
    @ColumnInfo(name = "config_json")
    val configJson: String? = null,
    @ColumnInfo(name = "is_active")
    val isActive: Int = 1,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: String,
)
