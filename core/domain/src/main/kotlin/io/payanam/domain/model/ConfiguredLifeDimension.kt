//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

/**
 * Runtime dimension row backed directly by the life_dimensions table.
 */
data class ConfiguredLifeDimension(
    val id: String,
    val key: String,
    val label: String,
    val description: String?,
    val colorHex: String,
    val iconKey: String?,
    val sortOrder: Int,
    val isActive: Boolean,
    /** User-editable relative weight for L3 day-score aggregation (C2, v20). */
    val weight: Double = 1.0,
)
