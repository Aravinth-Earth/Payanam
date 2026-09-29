//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.settings

/** A database file as the Settings backup list shows it. */
data class DatabaseArtifactUiModel(
    val fileName: String,
    val sizeKb: Long,
    val lastModifiedLabel: String,
    val isActive: Boolean = true,
)
