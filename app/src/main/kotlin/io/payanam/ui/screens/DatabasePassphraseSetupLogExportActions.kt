//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import kotlinx.coroutines.launch

/** The horizontal gap between the two export buttons, as dp. */
private val ExportButtonSpacing = 12.dp

/**
 * Android's debug-log exporter for the shared setup screen.
 *
 * The two buttons, the report line and the reported path stayed in `:app` because `exportLatestLog()` /
 * `exportAllLogs()` return an Android `java.io.File` written to Android's own storage - the same reason
 * [DatabaseInitLogExportActions] and [DatabasePassphraseUnlockLogExportActions] stay here. What leaves
 * `:core:ui` is only the SLOT, exactly as on those two screens.
 *
 * The buttons, the spacing, the disabled-while-saving behaviour and the reported copy are the setup
 * screen's own existing block, preserved verbatim: it is this screen's Material `OutlinedButton` row and
 * the localized snackbar copy it always showed, not the unlock screen's custom diagnostic button and not
 * the init screen's hint. What changed is the WHERE - the block lived inside the screen and is now here.
 */
@Composable
internal fun DatabasePassphraseSetupLogExportActions(isSaving: Boolean) {
    val logger = UnifiedLogger.getInstance()
    val scope = rememberCoroutineScope()
    val strings = LocalUiStrings.current
    var debugExportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ExportButtonSpacing),
    ) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    val exportedFile = logger.exportLatestLog()
                    debugExportMessage =
                        if (exportedFile != null) {
                            strings.settingsSnackbarLatestLogExported.formatArgs(exportedFile.absolutePath)
                        } else {
                            strings.settingsSnackbarLatestLogExportFailed
                        }
                }
            },
            enabled = !isSaving,
            modifier = Modifier.weight(1f),
        ) {
            Text(strings.settingsActionExportLatestLog)
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    val exportedFile = logger.exportAllLogs()
                    debugExportMessage =
                        if (exportedFile != null) {
                            strings.settingsSnackbarAllLogsExported.formatArgs(exportedFile.absolutePath)
                        } else {
                            strings.settingsSnackbarAllLogsExportFailed
                        }
                }
            },
            enabled = !isSaving,
            modifier = Modifier.weight(1f),
        ) {
            Text(strings.settingsActionExportAllLogs)
        }
    }
    if (debugExportMessage != null) {
        Text(
            text = debugExportMessage!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
