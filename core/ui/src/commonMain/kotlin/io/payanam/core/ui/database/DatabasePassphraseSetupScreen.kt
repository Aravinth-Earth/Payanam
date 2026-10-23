//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.database

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.presentation.database.DatabasePassphraseSetupViewModel
import io.payanam.shared.logging.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The padding the setup form's outer column carries, as dp. Named so no bare number reaches the layout. */
private val SetupScreenPadding = 16.dp

/** The vertical gap between the form's sections, as dp. */
private val SetupScreenSpacing = 12.dp

/** The top padding between the form and its submit button, as dp. */
private val SubmitButtonTopPadding = 8.dp

/** The `SimpleDateFormat` pattern the database summary's "last updated" line renders with. */
private const val LAST_UPDATED_PATTERN = "MMM dd, yyyy HH:mm"

/** What the summary shows when the database reports no modification time at all. */
private const val NO_LAST_UPDATED = "-"

/**
 * The database passphrase setup gate, rendered identically by every platform.
 *
 * Moved here from `:app` with its layout, field order, copy and conditions unchanged, so the Android app
 * and the desktop cannot drift into two different setup forms. It is the screen a brand-new install sees
 * before it has any database at all, which makes it the first of the three DB gates both platforms must
 * share rather than one platform owning.
 *
 * WHAT CAME OUT, and why each piece is a slot rather than a rewrite:
 *
 *  - **The log-export buttons and the reported path** ([debugLogExport]) follow the identical
 *    [DatabaseInitScreen] and [DatabasePassphraseUnlockScreen] precedent: `exportLatestLog()` and
 *    `exportAllLogs()` return an Android `java.io.File` written to Android's own storage, so the exporter
 *    and the path it prints stay in `:app`. The desktop passes nothing, and this screen renders its
 *    diagnostics headings without them rather than claiming an exporter it does not have.
 *  - **Copy** comes from [LocalUiStrings], the ambient table the navigation graph installs, rather than
 *    `stringResource`. All 44 keys this screen read already ship in `:core:strings` with EN+TA parity, so
 *    nothing was added to the string tables.
 *  - **Logging** goes through [AppLogger], the shared contract each platform installs. `UnifiedLogger` is
 *    an Android type, and this module has no platform logger.
 *
 * The ViewModel is unchanged: the shared [DatabasePassphraseSetupViewModel] is still supplied by the
 * caller, so the platform owns building and retaining it and the shared screen owns none of that wiring.
 *
 * The two fields, their visibility toggles and the reset dialog stay INSIDE this composable: they are pure
 * UI state with no platform meaning, and [rememberSaveable] preserves them across Android configuration
 * changes exactly as it did before the move.
 */
@Composable
fun DatabasePassphraseSetupScreen(
    onPassphraseConfigured: () -> Unit,
    /**
     * The setup ViewModel, supplied by the caller rather than fetched here.
     *
     * It used to default to `hiltViewModel()`: it is a plain class in `:core:presentation` now, and
     * `hiltViewModel<T>()` resolves through a factory that only knows `@HiltViewModel` classes - a shared
     * module cannot carry that annotation, because the desktop has no Hilt at all. The platform builds it
     * from the collaborators it binds and retains it, so the wiring is visible at the call site.
     */
    viewModel: DatabasePassphraseSetupViewModel,
    /**
     * Android's log-export buttons AND the path they report. See [DatabaseInitScreen]'s parameter of the
     * same name; the reported path is Android's own file, so it renders inside the slot rather than here.
     *
     * [isSaving] is passed in for the reason [BiometricUnlockScreenActions.actions] gives: a platform
     * cannot read the shared ViewModel's state, and this screen's export buttons have always been
     * disabled while a configuration is in flight. A bare `() -> Unit` could not carry that, and
     * dropping it would change what Android renders. The desktop passes nothing and renders no buttons,
     * so the argument has no second shape to satisfy.
     */
    debugLogExport: (@Composable (isSaving: Boolean) -> Unit)? = null,
) {
    val logger = AppLogger.current
    val strings = LocalUiStrings.current
    val uiState by viewModel.uiState.collectAsState()
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirmPassphrase by rememberSaveable { mutableStateOf("") }
    var isPassphraseVisible by rememberSaveable { mutableStateOf(false) }
    var isConfirmPassphraseVisible by rememberSaveable { mutableStateOf(false) }
    var showResetConfirmDialog by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(SetupScreenPadding)),
        verticalArrangement = Arrangement.spacedBy(SetupScreenSpacing),
    ) {
        Text(
            text = strings.dbPassphraseSetupTitle,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = strings.dbPassphraseSetupDesc,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = strings.dbPassphraseSetupEncryptionDefault,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (uiState.hasExistingLocalDatabase) {
            val lastUpdated = if (uiState.databaseLastModifiedMs > 0L) {
                SimpleDateFormat(LAST_UPDATED_PATTERN, Locale.getDefault())
                    .format(Date(uiState.databaseLastModifiedMs))
            } else {
                NO_LAST_UPDATED
            }
            Text(
                text = strings.dbPassphraseSetupExistingDataMigration,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = strings.dbPassphraseDbSummaryTitle,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = strings.dbPassphraseDbSummaryStorageMode.formatArgs(strings.dbPassphraseStorageModePlaintext),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = strings.settingsDatabaseSizeValue.formatArgs(uiState.databaseSizeKb),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = strings.dbPassphraseDbSummaryLastUpdated.formatArgs(lastUpdated),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = strings.dbPassphraseDbSummaryTasks.formatArgs(uiState.taskCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = strings.dbPassphraseDbSummaryTimeEntries.formatArgs(uiState.timeEntryCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = strings.dbPassphraseDbSummaryJournal.formatArgs(uiState.journalCount),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = strings.dbPassphraseDbSummaryNotes.formatArgs(uiState.noteCount),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = strings.dbPassphraseNoRecoveryWarning,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = {
                passphrase = it
                viewModel.clearError()
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.dbPassphraseInputLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (isPassphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { isPassphraseVisible = !isPassphraseVisible }) {
                    Icon(
                        imageVector = if (isPassphraseVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (isPassphraseVisible) {
                            strings.dbPassphraseHideToggle
                        } else {
                            strings.dbPassphraseShowToggle
                        },
                    )
                }
            },
        )
        OutlinedTextField(
            value = confirmPassphrase,
            onValueChange = {
                confirmPassphrase = it
                viewModel.clearError()
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.dbPassphraseConfirmLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (isConfirmPassphraseVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { isConfirmPassphraseVisible = !isConfirmPassphraseVisible }) {
                    Icon(
                        imageVector = if (isConfirmPassphraseVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (isConfirmPassphraseVisible) {
                            strings.dbPassphraseHideToggle
                        } else {
                            strings.dbPassphraseShowToggle
                        },
                    )
                }
            },
        )

        // The error line, its reset affordance, the two fields and the submit button all key off the SAME
        // state as the file this screen came from. The setup-specific codes are resolved through
        // passphraseSetupErrorMessage rather than the bare shared map, because this screen has always shown
        // the generic sentence for a code it does not recognise instead of rendering nothing.
        val errorText = strings.passphraseSetupErrorMessage(uiState.errorReasonCode)
        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (uiState.errorReasonCode == MIGRATION_INCOMPATIBLE_CODE || uiState.errorReasonCode == MIGRATION_FAILED_CODE) {
            TextButton(
                onClick = { showResetConfirmDialog = true },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = strings.dbPassphraseSetupResetAction)
            }
        }
        Text(
            text = strings.dbPassphraseDiagnosticsTitle,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = strings.dbPassphraseDiagnosticsDesc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        debugLogExport?.invoke(uiState.isSaving)
        Button(
            onClick = {
                logger.i("DatabasePassphraseSetupScreen", "Submitting passphrase setup")
                viewModel.configurePassphrase(
                    passphrase = passphrase,
                    confirmPassphrase = confirmPassphrase,
                    onSuccess = onPassphraseConfigured,
                )
            },
            enabled = !uiState.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SubmitButtonTopPadding),
        ) {
            Text(
                text = if (uiState.isSaving) {
                    strings.dbPassphraseSetupSaving
                } else {
                    strings.dbPassphraseSetupAction
                },
            )
        }
    }
    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text(strings.dbPassphraseSetupResetTitle) },
            text = { Text(strings.dbPassphraseSetupResetMessage) },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirmDialog = false
                        viewModel.resetLocalDataAndConfigurePassphrase(
                            passphrase = passphrase,
                            confirmPassphrase = confirmPassphrase,
                            onSuccess = onPassphraseConfigured,
                        )
                    },
                ) {
                    Text(strings.dbPassphraseUnlockResetConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text(strings.settingsActionCancel)
                }
            },
        )
    }
}

/**
 * The existing database is sealed with a different key, so retrying cannot help and only a reset can.
 *
 * Named because the reset affordance below compares against these two codes, and a bare string literal in
 * a layout condition is a place a typo would silently disable the only recovery a user has.
 */
private const val MIGRATION_INCOMPATIBLE_CODE = "migration_incompatible"

/** The re-encryption itself failed, leaving the data unchanged. Offers the same reset as above. */
private const val MIGRATION_FAILED_CODE = "migration_failed"
