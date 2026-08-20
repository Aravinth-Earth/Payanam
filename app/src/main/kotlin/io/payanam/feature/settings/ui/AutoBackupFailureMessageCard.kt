//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger

@Composable
internal fun autoBackupFailureMessageCard(
    errorMessage: String?,
    errorAt: String?,
    onDismiss: () -> Unit,
) {
    /** If. */
    if (errorMessage.isNullOrBlank()) return

    /** Logger. */
    val logger = remember { UnifiedLogger.getInstance() }

    /** Card. */
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        /** Column. */
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /** Text. */
            Text(
                text = stringResource(id = R.string.backup_failure_dialog_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            /** Text. */
            Text(
                text = errorAt?.let {
                    /** String resource. */
                    stringResource(
                        id = R.string.backup_failure_dialog_message_with_time,
                        /** It. */
                        it,
                        /** Error message. */
                        errorMessage,
                    )
                } ?: stringResource(
                    id = R.string.backup_failure_dialog_message_without_time,
                    /** Error message. */
                    errorMessage,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            /** Outlined button. */
            OutlinedButton(
                onClick = {
                    logger.i("AutoBackupFailureMessageCard", "Dismiss auto-backup failure message clicked")
                    /** On dismiss. */
                    onDismiss()
                },
            ) {
                /** Text. */
                Text(stringResource(id = R.string.settings_action_dismiss_error_message))
            }
        }
    }
}
