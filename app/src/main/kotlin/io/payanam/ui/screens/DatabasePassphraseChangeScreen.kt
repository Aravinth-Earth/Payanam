//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.viewmodel.DatabasePassphraseChangeViewModel

@Composable
/**
 * Performs the database passphrase change screen.
 */
fun DatabasePassphraseChangeScreen(
    onPassphraseChanged: () -> Unit,
    viewModel: DatabasePassphraseChangeViewModel = hiltViewModel(),
) {
    val logger = UnifiedLogger.getInstance()
    val uiState by viewModel.uiState.collectAsState()
    var currentPassphrase by rememberSaveable { mutableStateOf("") }
    var newPassphrase by rememberSaveable { mutableStateOf("") }
    var confirmPassphrase by rememberSaveable { mutableStateOf("") }
    var showCurrentPassphrase by rememberSaveable { mutableStateOf(false) }
    var showNewPassphrase by rememberSaveable { mutableStateOf(false) }
    var showConfirmPassphrase by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(uiState.isSuccess) {
        if (uiState.isSuccess) {
            onPassphraseChanged()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(id = R.string.db_passphrase_change_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(id = R.string.db_passphrase_change_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = currentPassphrase,
            onValueChange = { currentPassphrase = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(id = R.string.db_passphrase_change_current_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showCurrentPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showCurrentPassphrase = !showCurrentPassphrase }) {
                    Icon(
                        imageVector = if (showCurrentPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            id = if (showCurrentPassphrase) R.string.db_passphrase_hide_toggle else R.string.db_passphrase_show_toggle,
                        ),
                    )
                }
            },
        )
        OutlinedTextField(
            value = newPassphrase,
            onValueChange = { newPassphrase = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(id = R.string.db_passphrase_change_new_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showNewPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showNewPassphrase = !showNewPassphrase }) {
                    Icon(
                        imageVector = if (showNewPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            id = if (showNewPassphrase) R.string.db_passphrase_hide_toggle else R.string.db_passphrase_show_toggle,
                        ),
                    )
                }
            },
        )
        OutlinedTextField(
            value = confirmPassphrase,
            onValueChange = { confirmPassphrase = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(id = R.string.db_passphrase_change_confirm_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (showConfirmPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showConfirmPassphrase = !showConfirmPassphrase }) {
                    Icon(
                        imageVector = if (showConfirmPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            id = if (showConfirmPassphrase) R.string.db_passphrase_hide_toggle else R.string.db_passphrase_show_toggle,
                        ),
                    )
                }
            },
        )
        val errorText = when (uiState.errorReasonCode) {
            "current_invalid" -> stringResource(id = R.string.db_passphrase_change_error_current_invalid)
            "min_length" -> stringResource(id = R.string.db_passphrase_error_min_length)
            "missing_uppercase" -> stringResource(id = R.string.db_passphrase_error_uppercase)
            "missing_lowercase" -> stringResource(id = R.string.db_passphrase_error_lowercase)
            "missing_digit" -> stringResource(id = R.string.db_passphrase_error_digit)
            "missing_symbol" -> stringResource(id = R.string.db_passphrase_error_symbol)
            "mismatch" -> stringResource(id = R.string.db_passphrase_error_mismatch)
            "generic" -> stringResource(id = R.string.db_passphrase_error_generic)
            else -> null
        }
        if (errorText != null) {
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (uiState.isSuccess) {
            Text(
                text = stringResource(id = R.string.db_passphrase_change_success),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Button(
            onClick = {
                logger.i("DatabasePassphraseChangeScreen", "Submitting passphrase change")
                viewModel.submit(
                    currentPassphrase = currentPassphrase,
                    newPassphrase = newPassphrase,
                    confirmPassphrase = confirmPassphrase,
                )
            },
            enabled = !uiState.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (uiState.isSaving) {
                    stringResource(id = R.string.db_passphrase_change_saving)
                } else {
                    stringResource(id = R.string.db_passphrase_change_action)
                },
            )
        }
    }
}
