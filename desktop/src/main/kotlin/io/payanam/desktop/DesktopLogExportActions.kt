//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

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
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import kotlinx.coroutines.launch

//
// The desktop's debug-log exporter for the shared database-init screen.
//
// Mirrors Android's `DatabaseInitLogExportActions`: the same two outlined buttons, the same shared
// strings, the same result line. The widgets are this module's own Material widgets because
// `:desktop` compiles against Compose Material 2 (as the module's own shell chrome already does)
// rather than Material 3 - the labels, order and behaviour are unchanged.
//
// Only the exporter behind the buttons differs: the desktop has no SAF picker and no R class, so
// [UnifiedLogger.exportLatestLog] and [UnifiedLogger.exportAllLogs] copy the plain files this module
// already writes into the plain log directory and report the resulting path.

/** Log action names, so the export lines name WHICH button was pressed rather than "an export". */
private const val LATEST_LOG_ACTION = "exportLatestLog"
private const val ALL_LOGS_ACTION = "exportAllLogs"

/**
 * The export lines exist because a failed export used to be invisible: the on-screen result line was
 * the ONLY feedback, so "it did nothing" and "it wrote the file" were indistinguishable from outside.
 * One requested line and one outcome line per click make the attempt reviewable in the log afterwards.
 *
 * The outcome line doubles as the failure line - `outcome` is `failed` when the export returned no
 * file - and it never logs an absolute path: only the exported FILE NAME. The user-facing line still
 * carries the full path, because that is the answer they asked for.
 */
@Composable
internal fun DesktopLogExportActions() {
    val logger = UnifiedLogger.getInstance()
    val scope = rememberCoroutineScope()
    val strings = LocalUiStrings.current
    var debugExportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = {
                logger.i(
                    source = "DesktopLogExportActions",
                    message = "Debug log export requested",
                    data = mapOf("action" to LATEST_LOG_ACTION),
                )
                scope.launch {
                    val exportedFile = logger.exportLatestLog()
                    logger.i(
                        source = "DesktopLogExportActions",
                        message = "Debug log exported",
                        data =
                            mapOf(
                                "action" to LATEST_LOG_ACTION,
                                "outcome" to if (exportedFile != null) "exported" else "failed",
                                "fileName" to (exportedFile?.name ?: "none"),
                            ),
                    )
                    debugExportMessage =
                        if (exportedFile != null) {
                            strings.settingsSnackbarLatestLogExported.formatArgs(exportedFile.absolutePath)
                        } else {
                            strings.settingsSnackbarLatestLogExportFailed
                        }
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(strings.settingsActionExportLatestLog)
        }
        OutlinedButton(
            onClick = {
                logger.i(
                    source = "DesktopLogExportActions",
                    message = "Debug log export requested",
                    data = mapOf("action" to ALL_LOGS_ACTION),
                )
                scope.launch {
                    val exportedFile = logger.exportAllLogs()
                    logger.i(
                        source = "DesktopLogExportActions",
                        message = "Debug log exported",
                        data =
                            mapOf(
                                "action" to ALL_LOGS_ACTION,
                                "outcome" to if (exportedFile != null) "exported" else "failed",
                                "fileName" to (exportedFile?.name ?: "none"),
                            ),
                    )
                    debugExportMessage =
                        if (exportedFile != null) {
                            strings.settingsSnackbarAllLogsExported.formatArgs(exportedFile.absolutePath)
                        } else {
                            strings.settingsSnackbarAllLogsExportFailed
                        }
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
            // Read into a local first: the delegated property cannot be smart-cast across calls.
            text = debugExportMessage.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
