//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.model

import java.time.LocalDateTime

/**
 * User-defined tag that can be attached to tasks, notes, and time entries.
 */
data class Tag(
    /** Id. */
    val id: String,
    /** Name. */
    val name: String,
    /** Normalized name. */
    val normalizedName: String,
    /** Usage count. */
    val usageCount: Int,
    /** Last used at. */
    val lastUsedAt: LocalDateTime? = null,
    /** Created at. */
    val createdAt: LocalDateTime,
    /** Updated at. */
    val updatedAt: LocalDateTime
)
