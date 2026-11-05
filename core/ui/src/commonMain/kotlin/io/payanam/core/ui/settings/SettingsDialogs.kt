//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.components.PayanamAlertDialog
import io.payanam.core.ui.preferences.DimensionPreference
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.shared.logging.AppLogger

/**
 * The settings screen's confirmations, shared.
 *
 * Every dialog here is a question plus its two or three answers, and none of them touches storage: the
 * writes live behind the callbacks, in whichever ViewModel the platform renders. That is why these move
 * whole and why nothing here needs a platform seam - they draw text and hand back a decision.
 *
 * The words come from the shared table rather than from the app's `R.string`, so a desktop that renders
 * these reads the same copy the Android app does instead of a second hand-written English string. Every
 * key used below was already present in `core/strings` in both languages, so this move promoted no
 * string and reworded none.
 *
 * The DESTRUCTIVE ones are coloured with `colorScheme.error` rather than left as ordinary buttons,
 * because these three are the only dialogs in the app where a mis-tap loses data irreversibly. That
 * colouring is part of the copy's meaning, so it is part of the moved code rather than a styling
 * decision left behind for the caller to repeat.
 */
@Composable
fun ImportDatabaseConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.settingsImportDialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.settingsImportDialogWarning,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = strings.settingsImportDialogDataLoss,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = strings.settingsImportDialogBackupHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The rule, stated unconditionally: with the copy stating the rule rather than the mode,
                // the clause stops being conditional on a flag. A reader whose database is plaintext gets
                // the same sentence as one whose database is encrypted - and it is true for both, because
                // the import refuses anything it cannot detect as encrypted, whatever the local mode is.
                Text(
                    text = strings.settingsImportDialogEncryptedModeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(strings.settingsActionDeleteImport)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.settingsActionCancel)
            }
        },
    )
}

/**
 * The irreversible one: everything goes, with no export offered on this dialog.
 *
 * Deletion has its own dialog rather than sharing the export-prompt dialog's two-step shape because
 * reaching this dialog means the user already answered "no, skip the backup" (or already exported), and
 * asking again would be a third question about the same fact. The screen owns that sequencing; this
 * dialog only asks the last one.
 */
@Composable
fun DeleteAllDataConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.settingsDeleteDialogTitle) },
        text = {
            Text(
                text = strings.settingsDeleteDialogMessage,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(strings.settingsActionDeleteAllData)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.settingsActionCancel)
            }
        },
    )
}

/**
 * The offer that stands in front of deletion: export first, or skip and delete.
 *
 * THREE CALLBACKS BECAUSE THREE ANSWERS EXIST, and only one of them is a write: `onBackUpFirst`
 * launches the export (the screen owns the file launcher), `onSkipAndDelete` opens the delete dialog
 * (still no write - it is navigation into another dialog), and `onDismiss` closes the prompt having
 * written nothing at all. Folding these into one `onChoice(choice)` enum would have counted one
 * callback for three decisions and hidden which of them touches storage, which is the only thing a
 * reader needs to know about this dialog.
 *
 * The destructive answer is the SECONDARY button and is tinted with `error`, deliberately: the safe
 * answer is the one the dialog's confirm action offers, so a mis-tap on the dialog's default does not
 * delete.
 */
@Composable
fun DeleteExportPromptDialog(
    onBackUpFirst: () -> Unit,
    onSkipAndDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.settingsDeleteExportPromptTitle) },
        text = {
            Text(
                text = strings.settingsDeleteExportPromptBody,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Button(onClick = onBackUpFirst) {
                Text(strings.settingsDeleteExportPromptYes)
            }
        },
        dismissButton = {
            TextButton(onClick = onSkipAndDelete) {
                Text(
                    text = strings.settingsDeleteExportPromptNo,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

/**
 * The passphrase gate in front of an encrypted import.
 *
 * The passphrase is held in local composable state and never leaves through anything but [onConfirm],
 * so a shared caller cannot leak it by holding this dialog's state - there is no state for it to hold.
 * The field's IME action and the confirm button apply the same `isNotBlank() && !isVerifying` rule, so
 * the keyboard's Done key can never submit what the button would refuse; that duplication is the point,
 * since they are two different ways of pressing the same thing.
 *
 * [isVerifying] disables the field, both buttons and the visibility toggle's effective input, so a
 * second submission cannot start a second import while the first is still running.
 */
@Composable
fun ImportEncryptedDbPassphraseDialog(
    passphraseError: String?,
    isVerifying: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.dbImportPassphrasePromptTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = strings.dbImportPassphrasePromptDesc,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.dbPassphraseInputLabel) },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                if (passphrase.isNotBlank() && !isVerifying) {
                                    onConfirm(passphrase)
                                }
                            },
                        ),
                    visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassphrase = !showPassphrase }) {
                            Icon(
                                imageVector = if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription =
                                    if (showPassphrase) {
                                        strings.dbPassphraseHideToggle
                                    } else {
                                        strings.dbPassphraseShowToggle
                                    },
                            )
                        }
                    },
                    singleLine = true,
                    enabled = !isVerifying,
                )
                passphraseError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(passphrase) },
                enabled = passphrase.isNotBlank() && !isVerifying,
                // A DISABLED button is still an interactive node: Compose publishes `onClick` for a disabled
                // clickable and pairs it with `disabled`, so a screen reader reaches this node and reads its
                // state. That is exactly what makes the verifying state a defect rather than an excuse - the
                // label `Text` is replaced by the spinner there, so the node would carry no name at all while
                // the import is in flight, which is the moment a screen-reader user most needs to know what is
                // happening. The description is the SAME copy-table string the idle state shows in the button,
                // so the control never changes its subject - only its state.
                modifier =
                    Modifier.semantics {
                        contentDescription = strings.dbImportPassphrasePromptAction
                    },
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(modifier = Modifier.fillMaxWidth(0.5f), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(strings.dbImportPassphrasePromptAction)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isVerifying) {
                Text(strings.settingsActionCancel)
            }
        },
    )
}

/**
 * Pick the life dimension that imported uHabits habits belong to.
 *
 * THE SELECTION AND THE WRITE MUST AGREE ON ONE IDENTITY, so both sides read [DimensionPreference.id]
 * and nothing else. Marking the row by `canonicalId` while reporting `id` to [onSelectedDimensionChange]
 * would put the check on one row and the write on another: the dialog would look like it had honoured
 * the tap and the habits would land in the default dimension instead, which is the kind of mistake that
 * surfaces days later as habits filed under the wrong life area. The selected row's `id` is therefore
 * the only value that both marks and reports.
 *
 * This dialog does NOT write the mapping. [onConfirm] carries no argument, so the caller re-resolves the
 * dimension it was told about - which is what keeps this dialog honest about not being able to invent a
 * dimension the caller does not have. A row with no dimensions drawn still offers Apply, because the
 * caller's own guard (resolve, then write only if found) is the correct place to refuse.
 *
 * The mark is drawn as the shared table's own `settingsBulkMapDimensionSelected` (`✓ %1$s`) around the
 * label rather than as a separate check icon, because that string already carries the chosen-row
 * convention the rest of the app uses.
 */
@Composable
fun BulkMapImportedHabitsDialog(
    selectedDimensionId: String,
    dimensionPreferences: List<DimensionPreference>,
    onSelectedDimensionChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalUiStrings.current
    // AppLogger rather than the app's UnifiedLogger.getInstance(): the latter lives in :core:common,
    // an Android library, so naming it here would put :core:common back into the shared UI. The line
    // this writes is the one that identifies which dimension a bulk mapping went to.
    val logger = remember { AppLogger.current }
    PayanamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.settingsBulkMapDialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.settingsBulkMapDialogMessage)
                dimensionPreferences.forEach { dimension ->
                    val label = dimension.label
                    OutlinedButton(
                        onClick = {
                            logger.d(
                                "BulkMapImportedHabitsDialog",
                                "Bulk map dimension selected",
                                mapOf("dimensionId" to dimension.id),
                            )
                            onSelectedDimensionChange(dimension.id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text =
                                if (selectedDimensionId == dimension.id) {
                                    strings.settingsBulkMapDimensionSelected.format(label)
                                } else {
                                    label
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(strings.locApply)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.settingsActionCancel)
            }
        },
    )
}
