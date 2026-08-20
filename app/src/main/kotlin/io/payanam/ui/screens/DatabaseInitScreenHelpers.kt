//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.DatabaseBootIssue
import io.payanam.ui.viewmodel.DatabaseBootIssueType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun bootIssueTitleRes(type: DatabaseBootIssueType): Int = when (type) {
    DatabaseBootIssueType.DB_TOO_NEW -> R.string.db_init_issue_title_update_app_required

    DatabaseBootIssueType.DB_TOO_OLD -> R.string.db_init_issue_title_db_too_old

    DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING -> R.string.db_init_issue_title_incomplete_db_files

    DatabaseBootIssueType.SCHEMA_INVALID -> R.string.db_init_issue_title_db_schema_not_usable

    DatabaseBootIssueType.OPEN_FAILED -> R.string.db_init_issue_title_db_open_failed

    DatabaseBootIssueType.REPAIRABLE_GENERIC,
    DatabaseBootIssueType.NON_REPAIRABLE_GENERIC,
    -> R.string.loc_database_needs_repair
}

internal fun bootIssueHeadlineRes(type: DatabaseBootIssueType): Int = when (type) {
    DatabaseBootIssueType.DB_TOO_NEW -> R.string.db_init_issue_headline_newer_db_detected

    DatabaseBootIssueType.DB_TOO_OLD -> R.string.db_init_issue_headline_unsupported_old_db

    DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING -> R.string.db_init_issue_headline_missing_primary_db

    DatabaseBootIssueType.SCHEMA_INVALID -> R.string.db_init_issue_headline_schema_or_table_problem

    DatabaseBootIssueType.OPEN_FAILED -> R.string.db_init_issue_headline_cannot_open_database

    DatabaseBootIssueType.REPAIRABLE_GENERIC,
    DatabaseBootIssueType.NON_REPAIRABLE_GENERIC,
    -> R.string.loc_database_corruption_detected
}

@Composable
internal fun bootIssueBodyText(
    /** Boot issue. */
    bootIssue: DatabaseBootIssue,
    fallbackCorruptionMessage: String?,
): String {
    /** Detail. */
    val detail = bootIssue.detailMessage ?: fallbackCorruptionMessage
    return when (bootIssue.type) {
        DatabaseBootIssueType.DB_TOO_NEW ->
            /** Detail. */
            detail
                ?: androidx.compose.ui.res.stringResource(id = R.string.db_init_issue_body_update_app_required_default)

        DatabaseBootIssueType.DB_TOO_OLD ->
            /** Detail. */
            detail
                ?: androidx.compose.ui.res.stringResource(id = R.string.db_init_issue_body_db_too_old_default)

        DatabaseBootIssueType.SIDECAR_PRIMARY_MISSING ->
            /** Detail. */
            detail
                ?: androidx.compose.ui.res.stringResource(id = R.string.db_init_issue_body_incomplete_db_files_default)

        DatabaseBootIssueType.SCHEMA_INVALID ->
            /** Detail. */
            detail
                ?: androidx.compose.ui.res.stringResource(id = R.string.db_init_issue_body_schema_invalid_default)

        DatabaseBootIssueType.OPEN_FAILED ->
            /** Detail. */
            detail
                ?: androidx.compose.ui.res.stringResource(id = R.string.db_init_issue_body_open_failed_default)

        DatabaseBootIssueType.REPAIRABLE_GENERIC,
        DatabaseBootIssueType.NON_REPAIRABLE_GENERIC,
        ->
            /** Detail. */
            detail
                ?: androidx.compose.ui.res.stringResource(id = io.payanam.R.string.loc_database_schema_errors)
    }
}

@Composable
internal fun DatabaseInitLogExportActions(
    /** Logger. */
    logger: UnifiedLogger,
    context: android.content.Context,
    /** Scope. */
    scope: CoroutineScope,
    debugExportMessage: String?,
    onDebugExportMessageChange: (String?) -> Unit,
    /** Show hint. */
    showHint: Boolean,
) {
    /** If. */
    if (showHint) {
        /** Text. */
        Text(
            text = androidx.compose.ui.res.stringResource(id = R.string.db_init_error_export_logs_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        /** Spacer. */
        Spacer(modifier = Modifier.height(8.dp))
    }

    /** Row. */
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        /** Outlined button. */
        OutlinedButton(
            onClick = {
                scope.launch {
                    /** Exported file. */
                    val exportedFile = logger.exportLatestLog()
                    /** On debug export message change. */
                    onDebugExportMessageChange(
                        /** If. */
                        if (exportedFile != null) {
                            context.getString(
                                R.string.settings_snackbar_latest_log_exported,
                                exportedFile.absolutePath,
                            )
                        } else {
                            context.getString(R.string.settings_snackbar_latest_log_export_failed)
                        },
                    )
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            /** Text. */
            Text(androidx.compose.ui.res.stringResource(id = R.string.settings_action_export_latest_log))
        }
        /** Outlined button. */
        OutlinedButton(
            onClick = {
                scope.launch {
                    /** Exported file. */
                    val exportedFile = logger.exportAllLogs()
                    /** On debug export message change. */
                    onDebugExportMessageChange(
                        /** If. */
                        if (exportedFile != null) {
                            context.getString(
                                R.string.settings_snackbar_all_logs_exported,
                                exportedFile.absolutePath,
                            )
                        } else {
                            context.getString(R.string.settings_snackbar_all_logs_export_failed)
                        },
                    )
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            /** Text. */
            Text(androidx.compose.ui.res.stringResource(id = R.string.settings_action_export_all_logs))
        }
    }
    /** If. */
    if (debugExportMessage != null) {
        /** Spacer. */
        Spacer(modifier = Modifier.height(8.dp))
        /** Text. */
        Text(
            text = debugExportMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun passphraseValidationMessage(context: android.content.Context, reasonCode: String?): String = when (reasonCode) {
    "min_length" -> context.getString(R.string.db_passphrase_error_min_length)
    "missing_uppercase" -> context.getString(R.string.db_passphrase_error_uppercase)
    "missing_lowercase" -> context.getString(R.string.db_passphrase_error_lowercase)
    "missing_digit" -> context.getString(R.string.db_passphrase_error_digit)
    "missing_symbol" -> context.getString(R.string.db_passphrase_error_symbol)
    else -> context.getString(R.string.db_passphrase_error_generic)
}
