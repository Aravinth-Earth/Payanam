//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dagger.hilt.android.EntryPointAccessors
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.preferences.DimensionPreference
import io.payanam.domain.port.ProcessRestartOutcome
import io.payanam.feature.settings.SettingsViewModel
import io.payanam.platform.SettingsEntryPoint
import io.payanam.presentation.settings.BulkHabitMappingResult
import io.payanam.presentation.settings.ExportResult
import io.payanam.presentation.settings.ImportResult
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.presentation.settings.UhabitsImportResult
import io.payanam.shared.logging.CrashBreadcrumbs

/**
 * The half of the settings screen's import/export feedback that is not shared: this file exists because
 * every line of it is bound to the Android process, not because of the screen it belongs to.
 *
 * The dialogs themselves moved to `io.payanam.core.ui.settings.SettingsDialogs`, where the desktop can
 * render the same confirmations over the same shared copy. What stayed here is the effect host and its
 * restart helper, for two reasons that both come down to the same seam: it needs a `Context` to read
 * `R.string`, and it takes the app's `SettingsViewModel` so it can clear each result the moment it has
 * been shown. The restart itself is deliberately NOT one of those reasons any more: it goes through the
 * shared `ProcessRestart` port, whose own contract covers relaunch-and-kill on both platforms, so the
 * Android-only restart policy that used to live here is now a port detail and a shared screen does not
 * have to be handed it as a parameter.
 *
 * FOUR EFFECTS FOR FOUR RESULT CHANNELS, and that is the whole count: export, import, uHabits import and
 * habit mapping are four different operations, each with its own success and failure copy and its own
 * `clear` call on the ViewModel. The two that could be merged are deliberately not - `ImportResult`
 * alone carries `requiresAppRestart`, and the restart has to happen AFTER the restart-flavoured snackbar
 * has been dismissed, so that case cannot be folded into the ordinary success path without reordering
 * when the user sees what just happened.
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
                    if (result.requiresAppRestart) {
                        snackbarHostState.showSnackbar(
                            context.getString(
                                R.string.settings_snackbar_import_success_restart,
                                result.tasksImported,
                                result.timeEntriesImported,
                                result.notesImported,
                            ),
                        )
                        viewModel.clearImportResult()
                        restartAppAfterDatabaseImport(context)
                        return@let
                    }
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

private fun restartAppAfterDatabaseImport(context: Context) {
    val logger = UnifiedLogger.getInstance()
    logger.i(
        "SettingsImportFeedbackEffects.restartAppAfterDatabaseImport",
        "Restarting app to reopen imported database with fresh Room handles",
    )
    CrashBreadcrumbs.record(
        source = "SettingsImportFeedbackEffects.restartAppAfterDatabaseImport",
        stage = "kill_process_for_restart",
    )
    logger.flush()
    // The relaunch-and-kill sequence itself is the shared port's job, not this file's, for the reason
    // given at the top of this file: an Android-only restart policy behind an interface whose other
    // implementation is the desktop has no shared module that can name it. The log line and the
    // breadcrumb stay HERE because they are this screen's own diagnostics - the breadcrumb stage
    // names the database import that triggered this restart, which is not what the port's own
    // passphrase-change breadcrumb says, and a stage string is a key the next launch's trail is read
    // with. Nothing durable happens after the call below: on Android the port ends this process
    // inside it, so the line after it is the only line that can run, and it runs only to say why.
    val processRestart =
        EntryPointAccessors
            .fromApplication(context, SettingsEntryPoint::class.java)
            .processRestart()
    val outcome = processRestart.restartProcess()
    if (outcome is ProcessRestartOutcome.Refused) {
        logger.w(
            "SettingsImportFeedbackEffects.restartAppAfterDatabaseImport",
            "Process restart refused: ${outcome.reason}",
            mapOf("reason" to outcome.reason.name, "detail" to (outcome.detail ?: "")),
        )
    }
}
