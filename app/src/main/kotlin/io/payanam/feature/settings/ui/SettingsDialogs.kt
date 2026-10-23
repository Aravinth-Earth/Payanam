//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.payanam.R
import io.payanam.core.ui.preferences.DimensionPreference
import io.payanam.feature.settings.SettingsViewModel
import io.payanam.presentation.settings.BulkHabitMappingResult
import io.payanam.presentation.settings.ExportResult
import io.payanam.presentation.settings.ImportResult
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.presentation.settings.UhabitsImportResult

/**
 * The half of the settings screen's import/export feedback that is not shared: this file exists because
 * every line of it is bound to the Android process, not because of the screen it belongs to.
 *
 * The dialogs themselves moved to `io.payanam.core.ui.settings.SettingsDialogs`, where the desktop can
 * render the same confirmations over the same shared copy. What stayed here is the effect host, for the
 * one seam that needs a `Context` to read `R.string` and the reasons that come with it: it takes the
 * app's `SettingsViewModel` so it can clear each result the moment it has been shown.
 *
 * The restart that used to live here is RETIRED (owner ruling, 2026-10-08): an import must transition
 * smoothly on both platforms, so no import path asks for a relaunch any more - the import re-opens the
 * imported database in-process and the app carries on. There is no restart-flavoured import copy and no
 * ordering to preserve against one.
 *
 * FOUR EFFECTS FOR FOUR RESULT CHANNELS, and that is the whole count: export, import, uHabits import and
 * habit mapping are four different operations, each with its own success and failure copy and its own
 * `clear` call on the ViewModel.
 */
@Composable
internal fun SettingsImportFeedbackEffects(
    uiState: SettingsUiState,
    snackbarHostState: SnackbarHostState,
    context: Context,
    dimensionPreferences: List<DimensionPreference>,
    viewModel: SettingsViewModel,
) {
    LaunchedEffect(uiState.exportResult) {
        uiState.exportResult?.let { result ->
            when (result) {
                is ExportResult.Success -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_export_success,
                            result.fileName,
                        ),
                    )
                    viewModel.clearExportResult()
                }

                is ExportResult.Error -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_export_failed,
                            result.message,
                        ),
                    )
                    viewModel.clearExportResult()
                }
            }
        }
    }
    LaunchedEffect(uiState.importResult) {
        uiState.importResult?.let { result ->
            when (result) {
                is ImportResult.Success -> {
                    // ONE success path, and no restart: the import carries on smoothly (owner ruling,
                    // 2026-10-08). The import re-opens the imported database in-process, so there is no
                    // restart-flavoured copy and nothing to order against a restart.
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_import_success,
                            result.tasksImported,
                            result.timeEntriesImported,
                            result.notesImported,
                        ),
                    )
                    viewModel.clearImportResult()
                }

                is ImportResult.Error -> {
                    snackbarHostState.showSnackbar(
                        message = context.getString(
                            R.string.settings_snackbar_import_failed,
                            result.message,
                        ),
                        withDismissAction = true,
                        duration = SnackbarDuration.Indefinite,
                    )
                    viewModel.clearImportResult()
                }
            }
        }
    }
    LaunchedEffect(uiState.uhabitsImportResult) {
        uiState.uhabitsImportResult?.let { result ->
            when (result) {
                is UhabitsImportResult.Success -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_uhabits_import_success,
                            result.habitsUpserted,
                            result.repetitionsUpserted,
                        ),
                    )
                    viewModel.clearUhabitsImportResult()
                }

                is UhabitsImportResult.Error -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_uhabits_import_failed,
                            result.message,
                        ),
                    )
                    viewModel.clearUhabitsImportResult()
                }
            }
        }
    }
    LaunchedEffect(uiState.bulkHabitMappingResult) {
        uiState.bulkHabitMappingResult?.let { result ->
            when (result) {
                is BulkHabitMappingResult.Success -> {
                    // A bulk mapping reports the dimension the CALLER resolved by id, so the copy has to
                    // resolve the same id back to its label - or fall back to the raw id rather than to
                    // some other dimension's label. Snackbar copy is one of the few places a wrong label
                    // is invisible until someone goes looking for the habits they just filed.
                    val resolvedLabel = dimensionPreferences
                        .firstOrNull { it.id == result.dimensionId || it.canonicalId == result.dimensionId }
                        ?.label
                        ?: result.dimensionId
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_habit_mapping_success,
                            result.mappedCount,
                            resolvedLabel,
                        ),
                    )
                    viewModel.clearBulkHabitMappingResult()
                }

                is BulkHabitMappingResult.Error -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.settings_snackbar_habit_mapping_failed,
                            result.message,
                        ),
                    )
                    viewModel.clearBulkHabitMappingResult()
                }
            }
        }
    }
}
