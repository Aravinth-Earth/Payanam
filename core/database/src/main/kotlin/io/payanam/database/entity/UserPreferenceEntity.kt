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
    /** Key. */
    val key: String,
    /** Value type. */
    val valueType: String,
    /** String value. */
    val stringValue: String? = null,
    /** Int value. */
    val intValue: Int? = null,
    /** Double value. */
    val doubleValue: Double? = null,
    /** Bool value. */
    val boolValue: Int? = null,
    /** Updated at. */
    val updatedAt: String,
)
