//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.database.DatabasePassphraseUnlockDiagnosticButton
import io.payanam.core.ui.text.LocalUiStrings
import kotlinx.coroutines.launch

/**
 * Android's debug-log exporter for the shared unlock screen.
 *
 * The two buttons, the report line and the reported path stayed in `:app` because
 * `exportLatestLog()`/`exportAllLogs()` return an Android `java.io.File` written to Android's own
 * storage - the same reason [DatabaseInitLogExportActions] stays here. Only the BUTTONS came from
 * `:core:ui` ([DatabasePassphraseUnlockDiagnosticButton]), because they are this screen's own visual
 * language and a second hand-drawn copy per platform would be exactly the drift this migration ends.
 *
 * The message rendered here is the raw `absolutePath` (or `Export failed`) and the style is the screen's
 * outlined diagnostic button - deliberately NOT the localized snackbar copy and `OutlinedButton` of
 * [DatabaseInitLogExportActions]. This is the unlock screen's existing diagnostics block preserved
 * exactly, not a re-skin onto the init screen's one.
 */
@Composable
internal fun DatabasePassphraseUnlockLogExportActions() {
    val logger = UnifiedLogger.getInstance()
    val scope = rememberCoroutineScope()
    val strings = LocalUiStrings.current
    var debugExportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DatabasePassphraseUnlockDiagnosticButton(
                text = strings.settingsActionExportLatestLog,
                onClick = {
                    scope.launch {
                        val exportedFile = logger.exportLatestLog()
                        debugExportMessage = exportedFile?.absolutePath ?: "Export failed"
                        logger.i(
                            "DatabasePassphraseUnlockScreen.diagnostics",
                            "Export latest log action completed",
                            mapOf("success" to (exportedFile != null)),
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )
            DatabasePassphraseUnlockDiagnosticButton(
                text = strings.settingsActionExportAllLogs,
                onClick = {
                    scope.launch {
                        val exportedFile = logger.exportAllLogs()
                        debugExportMessage = exportedFile?.absolutePath ?: "Export failed"
                        logger.i(
                            "DatabasePassphraseUnlockScreen.diagnostics",
                            "Export all logs action completed",
                            mapOf("success" to (exportedFile != null)),
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (debugExportMessage != null) {
            Text(
                text = debugExportMessage!!,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
