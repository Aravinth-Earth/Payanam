//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.settings

// The four one-shot outcomes the Settings screen renders, split out of the app module's
// `SettingsUiModels` so a shared screen on either platform can name them.
//
// Each one is a result, not a status flag, because the screen's own contract is "show what happened":
// a boolean plus a message field is the shape that loses the failure case.

/**
 * Outcome of a database export: [Success] with the written file name, or
 * [Error] with a user-facing message.
 */
sealed class ExportResult {
    /**
     * Export finished; [fileName] identifies the written backup file.
     */
    data class Success(
        val fileName: String,
    ) : ExportResult()

    /**
     * Export failed; [message] is user-displayable.
     */
    data class Error(
        val message: String,
    ) : ExportResult()
}

/**
 * Outcome of a full-database JSON import: per-type row counts on [Success], or [Error].
 *
 * There is no restart surface: an import carries on smoothly (owner ruling, 2026-10-08), so no import
 * result can ask the host to relaunch.
 */
sealed class ImportResult {
    /**
     * Import finished with the number of tasks/time entries/notes imported.
     */
    data class Success(
        val tasksImported: Int,
        val timeEntriesImported: Int,
        val notesImported: Int,
    ) : ImportResult()

    /**
     * Import failed; [message] is user-displayable.
     */
    data class Error(
        val message: String,
    ) : ImportResult()
}

/**
 * Outcome of a uHabits database import: upsert counts on success, or [Error].
 */
sealed class UhabitsImportResult {
    /**
     * Import finished: habits + repetitions upserted counts.
     */
    data class Success(
        val habitsUpserted: Int,
        val repetitionsUpserted: Int,
    ) : UhabitsImportResult()

    /**
     * Import failed; [message] is user-displayable.
     */
    data class Error(
        val message: String,
    ) : UhabitsImportResult()
}

/**
 * Outcome of bulk-tagging imported habits into a life dimension: mapped
 * count + target dimension on success, or [Error].
 */
sealed class BulkHabitMappingResult {
    /**
     * Mapping finished: how many habits were tagged, into which dimension.
     */
    data class Success(
        val mappedCount: Int,
        val dimensionId: String,
    ) : BulkHabitMappingResult()

    /**
     * Mapping failed; [message] is user-displayable.
     */
    data class Error(
        val message: String,
    ) : BulkHabitMappingResult()
}
