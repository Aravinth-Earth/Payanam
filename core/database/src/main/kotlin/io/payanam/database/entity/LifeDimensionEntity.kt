//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Canonical life dimension table for the modular data model.
 */
@Entity(
    tableName = "life_dimensions",
    indices = [
        /** Index. */
        Index(value = ["key"], unique = true),
        /** Index. */
        Index("sortOrder"),
    ],
)
/**
 * LifeDimensionEntity.
 */
data class LifeDimensionEntity(
    @PrimaryKey
    /** Id. */
    val id: String,
    /** Key. */
    val key: String,
    /** Label. */
    val label: String,
    /** Description. */
    val description: String? = null,
    /** Color. */
    val color: String,
    /** Icon. */
    val icon: String? = null,
    /** Sort order. */
    val sortOrder: Int,
    /** Is active. */
    val isActive: Int = 1,
    /** User-editable relative weight for L3 day-score aggregation (C2, v20). */
    val weight: Double = 1.0,
    /** Created at. */
    val createdAt: String,
    /** Updated at. */
    val updatedAt: String,
)
