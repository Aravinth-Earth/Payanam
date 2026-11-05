//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.settings.StatRow
import io.payanam.feature.settings.ApkBuildType
import io.payanam.feature.settings.versionFromFileName
import io.payanam.presentation.download.DownloadUiState
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.shared.updates.UpdateCheckError
import io.payanam.shared.updates.UpdateOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun DebugLogExportActions(
    logger: UnifiedLogger,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    context: Context,
    habitScoreDiagnosticsInProgress: Boolean,
    onRunHabitScoreDiagnostics: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = {
                    logger.d("SettingsScreen.exportLogsButtonTapped", "Export logs button tapped")
                    scope.launch {
                        val exportedFile = logger.exportLatestLog()
                        if (exportedFile != null) {
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.settings_snackbar_latest_log_exported,
                                    exportedFile.absolutePath,
                                ),
                            )
                        } else {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.settings_snackbar_latest_log_export_failed),
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.CloudUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(id = R.string.settings_action_export_latest_log))
            }
            OutlinedButton(
                onClick = {
                    logger.d("SettingsScreen.exportLogsButtonTapped", "Export logs button tapped")
                    scope.launch {
                        val exportedFile = logger.exportAllLogs()
                        if (exportedFile != null) {
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.settings_snackbar_all_logs_exported,
                                    exportedFile.absolutePath,
                                ),
                            )
                        } else {
                            snackbarHostState.showSnackbar(
                                context.getString(R.string.settings_snackbar_all_logs_export_failed),
                            )
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(id = R.string.settings_action_export_all_logs))
            }
        }
        OutlinedButton(
            onClick = {
                logger.i(
                    "SettingsScreen.habitScoreDiagnosticsTapped",
                    "Habit score diagnostics tapped",
                )
                onRunHabitScoreDiagnostics()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !habitScoreDiagnosticsInProgress,
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(
                    id = if (habitScoreDiagnosticsInProgress) {
                        R.string.settings_action_run_habit_score_diagnostics_in_progress
                    } else {
                        R.string.settings_action_run_habit_score_diagnostics
                    },
                ),
            )
        }
    }
}

/**
 * The about card's CONTENT, without the card.
 *
 * WHY THE CARD IS NOT HERE ANY MORE: the shared screen owns the card list now, so the about section
 * arrives as a composable slot it fills and frames itself - the same arrangement the debug-log exporter
 * already used. A second card nested inside the shared one would have given the section two collapsible
 * headers fighting over one body.
 *
 * `expanded` and `onToggleExpanded` are gone for the same reason: nothing in here reads them.
 */
@Composable
internal fun AboutSettingsContent(
    uiState: SettingsUiState,
    logger: UnifiedLogger,
    onViewGithub: () -> Unit,
    onCheckForUpdate: () -> Unit = {},
    onAutoDownloadToggled: (Boolean) -> Unit = {},
    onPromptInstallToggled: (Boolean) -> Unit = {},
    onWifiOnlyToggled: (Boolean) -> Unit = {},
    onAutoCheckToggled: (Boolean) -> Unit = {},
    onDownloadOrRetry: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onInstallNow: () -> Unit = {},
    onInstallLater: () -> Unit = {},
    isFDroidBuild: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatRow(
            label = stringResource(id = R.string.settings_about_version_label),
            value = stringResource(
                id = R.string.settings_about_version_value,
                uiState.appVersion,
            ),
        )
        StatRow(
            label = stringResource(id = R.string.settings_about_codename_label),
            value = stringResource(id = R.string.settings_about_codename_value),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(id = R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(id = R.string.settings_about_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.settings_about_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onViewGithub) {
            Text(stringResource(id = R.string.settings_action_view_on_github))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.feedback_contact_email_value, stringResource(id = R.string.feedback_contact_email)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(id = R.string.feedback_contact_signal_value, stringResource(id = R.string.feedback_contact_signal)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        if (isFDroidBuild) {
            Text(
                text = stringResource(id = R.string.settings_update_fdroid_managed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(id = R.string.settings_update_fdroid_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/io.payanam/")))
                }.onFailure { e ->
                    logger.w("AboutSettingsSection.openFDroid", "No browser available for F-Droid intent", mapOf("error" to (e.message ?: "unknown")))
                }
            }) {
                Text(stringResource(id = R.string.settings_update_open_fdroid))
            }
        } else {
            // What this install runs — static, from the same rule the asset selection
            // uses (the running build type decides what is downloadable). Static: no
            // network check needed.
            val runningBuildType = ApkBuildType.running()
            Text(
                text = stringResource(id = R.string.settings_update_running_build_type, runningBuildType),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Auto-download opt-in + check button
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = uiState.autoDownloadEnabled,
                    onCheckedChange = { onAutoDownloadToggled(it) },
                    enabled = !uiState.isCheckingForUpdate,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.settings_update_auto_download),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = uiState.promptInstallEnabled,
                    onCheckedChange = { onPromptInstallToggled(it) },
                    enabled = uiState.autoDownloadEnabled && !uiState.isCheckingForUpdate,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.settings_update_prompt_install),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = uiState.wifiOnlyEnabled,
                    onCheckedChange = { onWifiOnlyToggled(it) },
                    enabled = !uiState.isCheckingForUpdate,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.settings_update_wifi_only),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = uiState.autoCheckEnabled,
                    onCheckedChange = { onAutoCheckToggled(it) },
                    enabled = !uiState.isCheckingForUpdate,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.settings_update_auto_check),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))

            // Update check
            // Single state-driven action button (never two actions at once):
            //   Idle           → Check for update
            //   Checking       → disabled spinner "Checking…"
            //   Downloading    → disabled "Downloading… N%"
            //   Downloaded     → Install now
            //   Failed         → Retry
            //   Up to date     → Check for update (re-check allowed, cooldown guards)
            val buttonLabel: String
            val buttonEnabled: Boolean
            val buttonAction: () -> Unit
            when {
                uiState.isCheckingForUpdate -> {
                    buttonLabel = stringResource(id = R.string.settings_update_checking)
                    buttonEnabled = false
                    buttonAction = onCheckForUpdate
                }

                uiState.downloadState is DownloadUiState.Downloading -> {
                    buttonLabel = stringResource(
                        id = R.string.settings_update_downloading,
                        (uiState.downloadState as DownloadUiState.Downloading).progressPercent,
                    )
                    buttonEnabled = false
                    buttonAction = onCheckForUpdate
                }

                uiState.downloadState is DownloadUiState.Downloaded -> {
                    buttonLabel = stringResource(id = R.string.settings_update_install_now_button)
                    buttonEnabled = true
                    buttonAction = onInstallNow
                }

                uiState.downloadState is DownloadUiState.Failed -> {
                    buttonLabel = stringResource(id = R.string.settings_update_retry_button)
                    buttonEnabled = true
                    buttonAction = onDownloadOrRetry
                }

                // Update available but not downloading yet (manual download path).
                // A stale result (>15 min) reverts to "Check for update" so the
                // user always has a fresh-check exit from a stale state.
                uiState.updateCheckResult?.outcome == UpdateOutcome.UPDATE_AVAILABLE && !uiState.autoDownloadEnabled &&
                    !uiState.isUpdateResultStale() -> {
                    buttonLabel = stringResource(id = R.string.settings_update_download_button)
                    buttonEnabled = true
                    buttonAction = onDownloadOrRetry
                }

                else -> {
                    buttonLabel = stringResource(id = R.string.settings_update_check_button)
                    buttonEnabled = true
                    buttonAction = onCheckForUpdate
                }
            }
            Button(
                onClick = {
                    logger.d(
                        "SettingsScreen.updateButtonTapped",
                        "Update button tapped",
                        mapOf(
                            "label" to buttonLabel,
                            "action" to when (buttonAction) {
                                onCheckForUpdate -> "checkForUpdate"
                                onDownloadOrRetry -> "downloadOrRetry"
                                onInstallNow -> "installNow"
                                else -> "unknown"
                            },
                            "checking" to uiState.isCheckingForUpdate,
                            "outcome" to (uiState.updateCheckResult?.outcome?.name ?: "none"),
                            "autoDownload" to uiState.autoDownloadEnabled,
                            "downloadState" to uiState.downloadState::class.simpleName,
                        ),
                    )
                    buttonAction()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = buttonEnabled,
            ) {
                if (uiState.isCheckingForUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(text = buttonLabel)
            }

            // Secondary "Check again" affordance: shown whenever a manual download
            // path is active, so a fresh check is always one tap away.
            if (buttonAction == onDownloadOrRetry) {
                TextButton(
                    onClick = {
                        logger.d("SettingsScreen.updateCheckAgainTapped", "Check again tapped", mapOf("label" to buttonLabel))
                        onCheckForUpdate()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(id = R.string.settings_update_check_again_button),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            val result = uiState.updateCheckResult
            if (result != null && !uiState.isCheckingForUpdate) {
                Spacer(modifier = Modifier.height(8.dp))
                // Exhaustive over UpdateOutcome: "up to date" is only reachable for
                // a PROVEN up-to-date result — every other state shows its own copy.
                when (result.outcome) {
                    UpdateOutcome.FAILED -> {
                        val errorText = when (result.error ?: UpdateCheckError.UNKNOWN) {
                            UpdateCheckError.NO_INTERNET, UpdateCheckError.TIMEOUT ->
                                stringResource(id = R.string.settings_update_error_network)

                            UpdateCheckError.RATE_LIMITED ->
                                stringResource(id = R.string.settings_update_error_rate_limited)

                            UpdateCheckError.GITHUB_UNAVAILABLE ->
                                stringResource(id = R.string.settings_update_error_github)

                            UpdateCheckError.PARSE_ERROR, UpdateCheckError.UNKNOWN ->
                                stringResource(id = R.string.settings_update_error_parse)
                        }
                        Text(
                            text = errorText,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    UpdateOutcome.UPDATE_AVAILABLE -> {
                        Text(
                            text = stringResource(id = R.string.settings_update_available, result.latestVersionName.orEmpty()),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(onClick = {
                            result.releaseUrl?.let { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }.onFailure { e ->
                                    logger.w("AboutSettingsSection.openRelease", "No browser available for release intent", mapOf("error" to (e.message ?: "unknown")))
                                }
                            }
                        }) {
                            Text(stringResource(id = R.string.settings_update_view_release))
                        }
                    }

                    UpdateOutcome.TYPE_MISMATCH -> {
                        // Fail-closed mismatch: the newest release ships no APK for
                        // this install's build type — must never read "up to date".
                        Text(
                            text = stringResource(id = R.string.settings_update_type_mismatch),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    UpdateOutcome.NO_RELEASE -> {
                        Text(
                            text = stringResource(id = R.string.settings_update_no_releases),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    UpdateOutcome.INDETERMINATE -> {
                        Text(
                            text = stringResource(id = R.string.settings_update_error_parse),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    UpdateOutcome.UP_TO_DATE -> {
                        Text(
                            text = stringResource(id = R.string.settings_update_up_to_date, uiState.appVersion),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Auto-download progress/state
            when (val dl = uiState.downloadState) {
                is DownloadUiState.Downloading -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    val downloadLabel = downloadVersionLabel(dl.buildName.ifEmpty { dl.fileName })
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Line 1: release version · the artifact's own version label
                        Text(
                            text = stringResource(
                                id = R.string.settings_update_downloading_header,
                                uiState.updateCheckResult?.latestVersionName ?: uiState.appVersion,
                                downloadLabel,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // Line 2: full build name (small, muted)
                        Text(
                            text = dl.buildName.ifEmpty { dl.fileName },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                progress = { dl.progressPercent / 100f },
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(id = R.string.loc_percent_value, dl.progressPercent),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onCancelDownload) {
                                Text(
                                    text = stringResource(id = R.string.settings_action_cancel),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }

                is DownloadUiState.Paused -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(id = pausedMessageRes(dl.message)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is DownloadUiState.Downloaded -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(id = R.string.settings_update_downloaded),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                is DownloadUiState.Failed -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(id = failedMessageRes(dl.message)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                DownloadUiState.Idle -> Unit
            }

            // Update-install popup (shown when a download finished and prompt-install is ON)
            val pendingPath = uiState.pendingInstallPath
            if (pendingPath != null) {
                AlertDialog(
                    onDismissRequest = onInstallLater,
                    title = { Text(stringResource(id = R.string.settings_update_install_title)) },
                    text = { Text(stringResource(id = R.string.settings_update_install_message)) },
                    confirmButton = {
                        TextButton(onClick = onInstallNow) {
                            Text(stringResource(id = R.string.settings_update_install_now))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onInstallLater) {
                            Text(stringResource(id = R.string.settings_update_install_later))
                        }
                    },
                )
            }
        } // End if (!isFDroidBuild)
    }
}

/** "Payanam 1.0.4" for an artifact name; the bare app name when the name declares no version. */
@Composable
private fun downloadVersionLabel(fileName: String): String {
    val version = versionFromFileName(fileName)
    return if (version.isEmpty()) {
        stringResource(id = R.string.app_name)
    } else {
        stringResource(id = R.string.settings_update_download_label, version)
    }
}

/** Map a DownloadManager failure key to a user-friendly string resource. */
private fun failedMessageRes(key: String): Int = when (key) {
    "no_download_url" -> R.string.settings_update_error_no_url
    "enqueue_failed" -> R.string.settings_update_error_enqueue
    "file_missing" -> R.string.settings_update_error_file_missing
    "type_mismatch" -> R.string.settings_update_type_mismatch
    "install_launch_failed" -> R.string.settings_update_error_install_launch
    "checksum_mismatch" -> R.string.settings_update_error_checksum_mismatch
    "checksum_unavailable" -> R.string.settings_update_error_checksum_unavailable
    "checksum_absent" -> R.string.settings_update_error_checksum_absent
    "retry_available" -> R.string.settings_update_error_retry_later
    "download_error_file" -> R.string.settings_update_error_file
    "download_error_http" -> R.string.settings_update_error_http
    "download_error_http_data" -> R.string.settings_update_error_http
    "download_error_redirects" -> R.string.settings_update_error_http
    "download_error_space" -> R.string.settings_update_error_space
    "download_error_device" -> R.string.settings_update_error_network
    "download_error_resume" -> R.string.settings_update_error_retry_later
    "download_error_exists" -> R.string.settings_update_error_exists
    else -> R.string.settings_update_download_failed
}

/** Map a DownloadManager paused key to a user-friendly string resource. */
private fun pausedMessageRes(key: String): Int = when (key) {
    "download_paused_wifi" -> R.string.settings_update_paused_wifi
    "download_paused_retry" -> R.string.settings_update_paused_retry
    else -> R.string.settings_update_paused
}
