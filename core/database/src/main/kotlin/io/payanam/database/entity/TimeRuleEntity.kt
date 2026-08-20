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
 * TimeRuleEntity.
 */
data class TimeRuleEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Name. */
    val name: String,
    @ColumnInfo(name = "rule_type")
    /** Rule type. */
    val ruleType: String,
    @ColumnInfo(name = "config_json")
    /** Config json. */
    val configJson: String? = null,
    @ColumnInfo(name = "is_active")
    /** Is active. */
    val isActive: Int = 1,
    @ColumnInfo(name = "created_at")
    /** Created at. */
    val createdAt: String,
    @ColumnInfo(name = "updated_at")
    /** Updated at. */
    val updatedAt: String,
)
