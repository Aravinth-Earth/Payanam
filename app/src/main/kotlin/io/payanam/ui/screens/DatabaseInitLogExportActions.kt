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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Android's debug-log exporter for the database-init screen.
 *
 * This stays in `:app` because it writes to Android's own storage and reads Android's R class.
 * The shared screen in `:core:ui` takes it as a composable slot, following the
 * MandatoryDimensionSetupSection precedent.
 */
@Composable
internal fun DatabaseInitLogExportActions() {
    val logger = UnifiedLogger.getInstance()
    val scope = rememberCoroutineScope()
    val strings = LocalUiStrings.current
    var debugExportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    DatabaseInitLogExportActions(
        logger = logger,
        scope = scope,
        debugExportMessage = debugExportMessage,
        onDebugExportMessageChange = { debugExportMessage = it },
        showHint = false,
    )
}

@Composable
internal fun DatabaseInitLogExportActions(
    logger: UnifiedLogger,
    scope: CoroutineScope,
    debugExportMessage: String?,
    onDebugExportMessageChange: (String?) -> Unit,
    showHint: Boolean,
) {
    val strings = LocalUiStrings.current
    if (showHint) {
        Text(
            text = strings.dbInitErrorExportLogsHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    val exportedFile = logger.exportLatestLog()
                    onDebugExportMessageChange(
                        if (exportedFile != null) {
                            strings.settingsSnackbarLatestLogExported.formatArgs(exportedFile.absolutePath)
                        } else {
                            strings.settingsSnackbarLatestLogExportFailed
                        },
                    )
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(strings.settingsActionExportLatestLog)
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val exportedFile = logger.exportAllLogs()
                    onDebugExportMessageChange(
                        if (exportedFile != null) {
                            strings.settingsSnackbarAllLogsExported.formatArgs(exportedFile.absolutePath)
                        } else {
                            strings.settingsSnackbarAllLogsExportFailed
                        },
                    )
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(strings.settingsActionExportAllLogs)
        }
    }
    if (debugExportMessage != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = debugExportMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
