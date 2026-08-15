//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Canonical life dimension table for the modular data model.
 */
@Entity(
    tableName = "life_dimensions",
    indices = [
        Index(value = ["key"], unique = true),
        Index("sortOrder"),
    ],
)
data class LifeDimensionEntity(
    @PrimaryKey
    val id: String,
    val key: String,
    val label: String,
    val description: String? = null,
    val color: String,
    val icon: String? = null,
    val sortOrder: Int,
    val isActive: Int = 1,
    /** User-editable relative weight for L3 day-score aggregation (C2, v20). */
    @ColumnInfo(defaultValue = "1.0")
    val weight: Double = 1.0,
    val createdAt: String,
    val updatedAt: String,
)
