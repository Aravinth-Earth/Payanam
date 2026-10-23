//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.database

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/**
 * The "change app passphrase" form, rendered identically by every platform.
 *
 * Moved here from `:app` with its layout unchanged: same column, same spacing, same field order. Only the
 * things that are genuinely platform- or app-specific were lifted out, so the Android app and the desktop
 * cannot render two different forms:
 *
 *  - The copy comes from [LocalUiStrings], the ambient table the navigation graph installs, exactly as it
 *    arrived from the platform's resources before. Which language is active is OS interaction (per-app
 *    language on Android, `Locale.getDefault()` on the desktop) and this module holds none of it.
 *  - The ViewModel's state arrives as [isSaving], [errorReasonCode] and [isSuccess] rather than as the
 *    app's `DatabasePassphraseChangeUiState`. That keeps the module free of `:app` types, and the reason
 *    CODES are the right contract anyway - the words for them come from [UiStrings], so
 *    copy stays out of the ViewModel's vocabulary.
 *  - [onSubmit] replaces the direct `viewModel.submit(...)` call, and [onPassphraseChanged] is invoked once
 *    when [isSuccess] flips true, replacing the Android wrapper's navigation side effect.
 *
 * The three passphrase fields and their show/hide toggles stay INSIDE this composable: they are pure UI
 * state with no platform meaning, and [rememberSaveable] preserves them across Android configuration
 * changes as the original did.
 *
 * The one thing that did NOT move is the log line the button used to emit. `UnifiedLogger` is an Android
 * type and this module has no platform logger, so the submitting call site logs it instead - the same
 * event, one layer out, which is also where the rest of that screen's wiring now lives.
 */
@Composable
fun DatabasePassphraseChangeScreen(
    onSubmit: (currentPassphrase: String, newPassphrase: String, confirmPassphrase: String) -> Unit,
    onPassphraseChanged: () -> Unit = {},
    isSaving: Boolean = false,
    errorReasonCode: String? = null,
    isSuccess: Boolean = false,
) {
    val strings = LocalUiStrings.current
    var currentPassphrase by rememberSaveable { mutableStateOf("") }
    var newPassphrase by rememberSaveable { mutableStateOf("") }
    var confirmPassphrase by rememberSaveable { mutableStateOf("") }
    var showCurrentPassphrase by rememberSaveable { mutableStateOf(false) }
    var showNewPassphrase by rememberSaveable { mutableStateOf(false) }
    var showConfirmPassphrase by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(isSuccess) {
        if (isSuccess) {
            onPassphraseChanged()
        }
    }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = strings.dbPassphraseChangeTitle,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = strings.dbPassphraseChangeDesc,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = currentPassphrase,
            onValueChange = { currentPassphrase = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.dbPassphraseChangeCurrentLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showCurrentPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showCurrentPassphrase = !showCurrentPassphrase }) {
                    Icon(
                        imageVector = if (showCurrentPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showCurrentPassphrase) strings.dbPassphraseHideToggle else strings.dbPassphraseShowToggle,
                    )
                }
            },
        )
        OutlinedTextField(
            value = newPassphrase,
            onValueChange = { newPassphrase = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.dbPassphraseChangeNewLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showNewPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showNewPassphrase = !showNewPassphrase }) {
                    Icon(
                        imageVector = if (showNewPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showNewPassphrase) strings.dbPassphraseHideToggle else strings.dbPassphraseShowToggle,
                    )
                }
            },
        )
        OutlinedTextField(
            value = confirmPassphrase,
            onValueChange = { confirmPassphrase = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(strings.dbPassphraseChangeConfirmLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showConfirmPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showConfirmPassphrase = !showConfirmPassphrase }) {
                    Icon(
                        imageVector = if (showConfirmPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showConfirmPassphrase) strings.dbPassphraseHideToggle else strings.dbPassphraseShowToggle,
                    )
                }
            },
        )
        val errorText = strings.passphraseErrorMessage(errorReasonCode)
        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (isSuccess) {
            Text(
                text = strings.dbPassphraseChangeSuccess,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Button(
            onClick = { onSubmit(currentPassphrase, newPassphrase, confirmPassphrase) },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (isSaving) strings.dbPassphraseChangeSaving else strings.dbPassphraseChangeAction,
            )
        }
    }
}
