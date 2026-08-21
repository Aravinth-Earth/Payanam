//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Typed user preferences table.
 *
 * This is additive to app_settings and used for gradual migration from
 * untyped key-value settings to typed preference storage.
 */
@Entity(tableName = "user_preferences")
/**
 * UserPreferenceEntity.
 */
data class UserPreferenceEntity(
    @PrimaryKey
    val key: String,
    val valueType: String,
    val stringValue: String? = null,
    val intValue: Int? = null,
    val doubleValue: Double? = null,
    val boolValue: Int? = null,
    val updatedAt: String,
)
