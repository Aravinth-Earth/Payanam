//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import java.time.LocalDate

/**
 * Status for a single day's checkmark in the habit grid.
 */
enum class CheckmarkStatus {
    COMPLETED, // Green check - task done
    SKIPPED, // Grey dash - intentionally skipped
    MISSED, // Red X - missed/not done
    PENDING, // Hollow circle - not yet due (future)
    UNKNOWN, // Faded - no data
}

/**
 * Data for a single day's checkmark.
 *
 * Declared here rather than beside the card that draws it because the ViewModels build the grid: they decide
 * each day's status, so the type belongs to the state layer and the Compose side imports it. It carries a
 * `@Composable`-free payload only, which is what lets it live in this Compose-free module. The
 * `@Immutable` hint it used to carry is deliberately gone — Compose infers stability for a data class whose
 * fields are all stable (`LocalDate`, an enum, `Boolean`, `String?`), and this module cannot see Compose at all.
 */
data class DayCheckmark(
    val date: LocalDate,
    val status: CheckmarkStatus,
    val hasNote: Boolean = false,
    val note: String? = null,
)
