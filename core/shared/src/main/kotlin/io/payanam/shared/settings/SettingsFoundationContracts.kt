//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings

import io.payanam.shared.transfer.BackupJsonContract
import io.payanam.shared.transfer.DataModuleSelection

/**
 * FoundationReadiness.
 */
enum class FoundationReadiness {
    SharedReady,
    ExtractionNext,
    AndroidOnly,
}

/**
 * FoundationArea.

 */
data class FoundationArea(
    val id: String,
    val title: String,
    val status: FoundationReadiness,
    val summary: String,
)

/**
 * SettingsFoundationSnapshot.

 */
data class SettingsFoundationSnapshot(
    val schemaVersion: Int,
    val moduleSelection: DataModuleSelection,
    val areas: List<FoundationArea>,
) {
    /**
     * Areas with status.
     */
    fun areasWithStatus(status: FoundationReadiness): Int = areas.count { it.status == status }
}

/**
 * SettingsFoundationContracts.
 */
object SettingsFoundationContracts {
    /**
     * Snapshot.
     */
    fun snapshot(
        moduleSelection: DataModuleSelection = DataModuleSelection(),
        schemaVersion: Int = BackupJsonContract.SCHEMA_VERSION,
    ): SettingsFoundationSnapshot =
        SettingsFoundationSnapshot(
            schemaVersion = schemaVersion,
            moduleSelection = moduleSelection,
            areas = defaultAreas(),
        )

    /**
     * Default areas.
     */
    fun defaultAreas(): List<FoundationArea> =
        listOf(
            FoundationArea(
                id = "settings_transfer",
                title = "Settings transfer",
                status = FoundationReadiness.SharedReady,
                summary = "Shared backup envelope and platform adapters are ready for deeper extraction.",
            ),
            FoundationArea(
                id = "settings_structure",
                title = "Settings structure",
                status = FoundationReadiness.ExtractionNext,
                summary = "Settings now lives under a feature-first package, with section-level extraction still underway.",
            ),
            FoundationArea(
                id = "tasks_time",
                title = "Tasks and time",
                status = FoundationReadiness.ExtractionNext,
                summary = "Desktop now has task-board contracts and shell preferences, with real entity/data wiring next.",
            ),
            FoundationArea(
                id = "notes_lenses",
                title = "Notes and lenses",
                status = FoundationReadiness.AndroidOnly,
                summary = "Presentation and persistence remain Android-first and will move in later slices.",
            ),
        )
}
