//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import java.time.LocalDateTime

/**
 * User-defined tag that can be attached to tasks, notes, and time entries.
 */
data class Tag(
    val id: String,
    val name: String,
    val normalizedName: String,
    val usageCount: Int,
    val lastUsedAt: LocalDateTime? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
