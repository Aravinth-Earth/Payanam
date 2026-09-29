//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

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
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.logging.AppLogger

@Composable
fun autoBackupFailureMessageCard(
    errorMessage: String?,
    errorAt: String?,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    if (errorMessage.isNullOrBlank()) return
    val logger = remember { AppLogger.current }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = strings.backupFailureDialogTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text =
                    errorAt?.let {
                        strings.backupFailureDialogMessageWithTime.format(
                            it,
                            errorMessage,
                        )
                    } ?: strings.backupFailureDialogMessageWithoutTime.format(errorMessage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            OutlinedButton(
                onClick = {
                    logger.i("AutoBackupFailureMessageCard", "Dismiss auto-backup failure message clicked")
                    onDismiss()
                },
            ) {
                Text(strings.settingsActionDismissErrorMessage)
            }
        }
    }
}
