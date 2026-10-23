//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.ImportButtonStyle
import io.payanam.core.ui.components.ImportDatabaseFileButton
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.presentation.settings.SettingsUiState

/**
 * Data-management settings content: export, import, uHabits import, habit mapping, passphrase, delete.
 *
 * WHY NONE OF THE THREE FILE LAUNCHERS CAME WITH THIS: choosing a destination is Android's
 * `ActivityResultContracts.CreateDocument`, and choosing a source is `OpenDocument`. Both are activity
 * contracts, not composables, so they cannot exist in commonMain. What the section needs is not the
 * picker but the intent to pick - and that shape already exists in this very module:
 * [io.payanam.core.ui.database.DatabaseInitScreen] reports a tap as `onImportFileSelected(IMPORT_PICKER_MIME)`,
 * handing the caller the MIME types and receiving the chosen document back. So these callbacks are the same
 * seam, not a second one invented for the settings screen. The three launchers stay in the platform screen,
 * which owns the choice; the section reports which operation was asked for, and the callback names that
 * operation rather than a widget.
 *
 * WHY SIX WRITES: each callback is a different operation with a different destination - export writes a
 * backup document, import replaces local data from one, the uHabits import writes that app's tables, the
 * mapping writes habit-to-dimension rows, the passphrase re-keys the database, and the delete removes it.
 * The three file operations also differ in their MIME filters, which is why the screen keeps three separate
 * launchers rather than one general picker.
 *
 * WHY THE CARD IS NOT HERE: `SettingsCard` and `StatRow` belong to the settings screen's own component file,
 * and every other section promoted here has the same arrangement - the platform wraps this content in its
 * card and supplies the expanded state.
 *
 * The imported-habits count is rendered TWICE, once above the passphrase row and once below the delete row,
 * exactly as the platform screen drew it. That duplication is the shipped appearance, so it is preserved and
 * [io.payanam.core.ui.settings.SettingsDataManagementSectionsTest] pins the count rather than letting a
 * later tidy-up silently change the screen.
 */
@Composable
fun DataManagementSettingsSection(
    uiState: SettingsUiState,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    onImportUhabitsClick: () -> Unit,
    onMapImportedHabitsClick: () -> Unit,
    onChangePassphraseClick: () -> Unit,
    onDeleteAllDataClick: () -> Unit,
) {
    val strings = LocalUiStrings.current
    Column {
        Text(
            text = strings.settingsDataManagementHelp,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onExportClick,
                enabled = !uiState.isExporting && !uiState.isImporting,
                modifier = Modifier.weight(1f),
            ) {
                if (uiState.isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(strings.settingsActionExport)
            }
            ImportDatabaseFileButton(
                modifier = Modifier.weight(1f),
                height = null,
                style = ImportButtonStyle.OUTLINED,
                icon = Icons.Default.CloudDownload,
                label = strings.settingsActionImport,
                logSource = "SettingsDataManagement.importFileClicked",
                logContext = "settings data management",
                // Every busy flag, not just isImporting: while the encrypted-import passphrase
                // prompt is open the import is staged, so the button must not accept a second tap.
                enabled = !uiState.isExporting &&
                    !uiState.isImporting &&
                    !uiState.awaitingImportPassphrase,
                showProgress = uiState.isImporting || uiState.awaitingImportPassphrase,
                iconSize = 18.dp,
                onClick = onImportClick,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onImportUhabitsClick,
                enabled = !uiState.isExporting && !uiState.isImporting && !uiState.isUhabitsImporting,
                modifier = Modifier.weight(1f),
            ) {
                if (uiState.isUhabitsImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(strings.settingsActionImportUhabits)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onMapImportedHabitsClick,
                enabled = uiState.importedUhabitsHabitCount > 0 &&
                    !uiState.isUhabitsImporting &&
                    !uiState.isBulkMappingImportedHabits,
                modifier = Modifier.weight(1f),
            ) {
                if (uiState.isBulkMappingImportedHabits) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(strings.settingsActionMapImportedHabits)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = strings.settingsImportedHabitsCount.formatArgs(uiState.importedUhabitsHabitCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onChangePassphraseClick,
            enabled = !uiState.isExporting && !uiState.isImporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.settingsActionChangePassphrase)
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDeleteAllDataClick,
            enabled = !uiState.isExporting && !uiState.isImporting,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(strings.settingsActionDeleteAllData)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = strings.settingsImportedHabitsCount.formatArgs(uiState.importedUhabitsHabitCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The reduced data-management content for a minimal-mode build: passphrase and delete, and nothing else.
 *
 * Minimal mode drops export and both imports, which is why this is a separate composable rather than the
 * full section behind a flag: the omitted buttons are not disabled, they do not exist, so the shared copy
 * table needs no entries for them on that build path.
 */
@Composable
fun MinimalDataManagementSection(
    onChangePassphraseClick: () -> Unit,
    onDeleteAllDataClick: () -> Unit,
    isExporting: Boolean,
    isImporting: Boolean,
) {
    val strings = LocalUiStrings.current
    Column {
        OutlinedButton(
            onClick = onChangePassphraseClick,
            enabled = !isExporting && !isImporting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(strings.settingsActionChangePassphrase)
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDeleteAllDataClick,
            enabled = !isExporting && !isImporting,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(strings.settingsActionDeleteAllData)
        }
    }
}
