//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

/**
 * Runtime dimension row backed directly by the life_dimensions table.
 */
data class ConfiguredLifeDimension(
    /** Id. */
    val id: String,
    /** Key. */
    val key: String,
    /** Label. */
    val label: String,
    /** Description. */
    val description: String?,
    /** Color hex. */
    val colorHex: String,
    /** Icon key. */
    val iconKey: String?,
    /** Sort order. */
    val sortOrder: Int,
    /** Is active. */
    val isActive: Boolean,
    /** User-editable relative weight for L3 day-score aggregation (C2, v20). */
    val weight: Double = 1.0,
)
