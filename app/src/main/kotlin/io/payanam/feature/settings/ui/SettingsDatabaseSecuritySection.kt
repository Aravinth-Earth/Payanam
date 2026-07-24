//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.feature.settings.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.feature.settings.SettingsUiState

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun DatabaseSecurityPreferencesSection(
    uiState: SettingsUiState,
    context: Context,
    onUpdateUnlockTimeout: (Int) -> Unit,
    onDisableBiometricEnabled: () -> Unit,
    onEnableBiometricRequested: (FragmentActivity, String, (Boolean) -> Unit) -> Unit,
) {
    val logger = remember { UnifiedLogger.getInstance() }
    var unlockTimeoutExpanded by remember { mutableStateOf(false) }
    var showEnableBiometricDialog by remember { mutableStateOf(false) }
    var enableBiometricPassphrase by remember { mutableStateOf("") }
    var showEnableBiometricPassphrase by remember { mutableStateOf(false) }
    var showEnableBiometricPassphraseError by remember { mutableStateOf(false) }
    val unlockTimeoutChoices = remember { listOf(1, 2, 5, 10, 15, 30, 60, 120, 240) }
    val biometricCanAuthenticateCode = remember(context) { biometricCanAuthenticateCode(context) }
    val biometricAvailable = biometricCanAuthenticateCode == BiometricManager.BIOMETRIC_SUCCESS
    val hostActivity = remember(context) { context.findFragmentActivity() }

    Text(
        text = stringResource(id = R.string.settings_db_unlock_timeout_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ExposedDropdownMenuBox(
        expanded = unlockTimeoutExpanded,
        onExpandedChange = {
            unlockTimeoutExpanded = it
            logger.d(
                "DatabaseSecurityPreferencesSection.timeout",
                "Unlock timeout selector expansion changed",
                mapOf("expanded" to it),
            )
        },
    ) {
        OutlinedTextField(
            value = formatTimeoutDisplay(uiState.unlockSessionTimeoutMinutes, context),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unlockTimeoutExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(
            expanded = unlockTimeoutExpanded,
            onDismissRequest = { unlockTimeoutExpanded = false },
        ) {
            unlockTimeoutChoices.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(formatTimeoutDisplay(minutes, context)) },
                    onClick = {
                        onUpdateUnlockTimeout(minutes)
                        logger.d(
                            "DatabaseSecurityPreferencesSection.timeout",
                            "Updated unlock session timeout",
                            mapOf("minutes" to minutes),
                        )
                        unlockTimeoutExpanded = false
                    },
                )
            }
        }
    }
    Text(
        text = stringResource(id = R.string.settings_db_unlock_timeout_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(id = R.string.db_passphrase_unlock_biometric_title),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (biometricAvailable) {
                    stringResource(id = R.string.settings_db_biometric_unlock_help)
                } else {
                    stringResource(id = R.string.settings_db_biometric_unlock_unavailable_help)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = uiState.biometricUnlockEnabled,
            enabled = biometricAvailable,
            onCheckedChange = { enabled ->
                logger.i(
                    "DatabaseSecurityPreferencesSection.biometric",
                    "Biometric toggle changed",
                    mapOf(
                        "requestedEnabled" to enabled,
                        "biometricAvailable" to biometricAvailable,
                        "hostActivityPresent" to (hostActivity != null),
                    ),
                )
                if (!enabled) {
                    onDisableBiometricEnabled()
                    logger.d(
                        "DatabaseSecurityPreferencesSection.biometric",
                        "Biometric unlock disabled from settings",
                        mapOf("enabled" to false),
                    )
                } else if (hostActivity != null) {
                    logger.i(
                        "DatabaseSecurityPreferencesSection.biometricEnable",
                        "Biometric enable requested from settings; collecting passphrase",
                        mapOf(
                            "canAuthenticate" to biometricCanAuthenticateCode,
                            "hostActivityClass" to hostActivity.javaClass.name,
                        ),
                    )
                    showEnableBiometricDialog = true
                } else {
                    logger.w(
                        "DatabaseSecurityPreferencesSection.biometricEnable",
                        "Biometric enable verification blocked: host activity unavailable",
                        mapOf("canAuthenticate" to biometricCanAuthenticateCode),
                    )
                }
            },
        )
    }
    if (!biometricAvailable) {
        androidx.compose.material3.TextButton(onClick = {
            logger.i(
                "DatabaseSecurityPreferencesSection.biometricSetup",
                "Biometric setup action tapped from Settings",
                mapOf("canAuthenticate" to biometricCanAuthenticateCode),
            )
            openBiometricEnrollment(context, logger)
        }) {
            Text(text = stringResource(id = R.string.db_passphrase_unlock_biometric_setup_action))
        }
    }

    if (showEnableBiometricDialog) {
        AlertDialog(
            onDismissRequest = {
                showEnableBiometricDialog = false
                enableBiometricPassphrase = ""
                showEnableBiometricPassphrase = false
                showEnableBiometricPassphraseError = false
            },
            title = { Text(text = stringResource(id = R.string.db_passphrase_unlock_biometric_enable_action)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(id = R.string.settings_biometric_enable_passphrase_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = enableBiometricPassphrase,
                        onValueChange = {
                            enableBiometricPassphrase = it
                            if (showEnableBiometricPassphraseError) {
                                showEnableBiometricPassphraseError = false
                            }
                        },
                        singleLine = true,
                        label = { Text(text = stringResource(id = R.string.db_passphrase_change_current_label)) },
                        visualTransformation = if (showEnableBiometricPassphrase) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { showEnableBiometricPassphrase = !showEnableBiometricPassphrase }) {
                                Icon(
                                    imageVector = if (showEnableBiometricPassphrase) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (showEnableBiometricPassphraseError) {
                        Text(
                            text = stringResource(id = R.string.settings_biometric_enable_passphrase_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val activity = hostActivity
                        if (activity == null || enableBiometricPassphrase.isBlank()) {
                            logger.w(
                                "DatabaseSecurityPreferencesSection.biometricEnable",
                                "Biometric enable blocked: missing host activity or passphrase",
                                mapOf(
                                    "hostActivityPresent" to (activity != null),
                                    "passphraseBlank" to enableBiometricPassphrase.isBlank(),
                                ),
                            )
                            showEnableBiometricPassphraseError = true
                            return@Button
                        }
                        logger.i(
                            "DatabaseSecurityPreferencesSection.biometricEnable",
                            "Submitting biometric enable verification request",
                        )
                        onEnableBiometricRequested(activity, enableBiometricPassphrase) { success ->
                            logger.i(
                                "DatabaseSecurityPreferencesSection.biometricEnable",
                                "Biometric enable verification callback",
                                mapOf("success" to success),
                            )
                            if (success) {
                                showEnableBiometricDialog = false
                                enableBiometricPassphrase = ""
                                showEnableBiometricPassphrase = false
                                showEnableBiometricPassphraseError = false
                            } else {
                                showEnableBiometricPassphraseError = true
                            }
                        }
                    },
                ) {
                    Text(text = stringResource(id = R.string.settings_biometric_enable_passphrase_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        logger.d(
                            "DatabaseSecurityPreferencesSection.biometricEnable",
                            "Biometric enable dialog dismissed by user",
                        )
                        showEnableBiometricDialog = false
                        enableBiometricPassphrase = ""
                        showEnableBiometricPassphrase = false
                        showEnableBiometricPassphraseError = false
                    },
                ) {
                    Text(text = stringResource(id = R.string.settings_action_cancel))
                }
            },
        )
    }
}

private fun biometricCanAuthenticateCode(context: Context): Int = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

private fun openBiometricEnrollment(context: Context, logger: UnifiedLogger) {
    val intent = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Intent(Settings.ACTION_BIOMETRIC_ENROLL)

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
            @Suppress("DEPRECATION")
            Intent(Settings.ACTION_FINGERPRINT_ENROLL)

        else -> Intent(Settings.ACTION_SECURITY_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        logger.i(
            "DatabaseSecurityPreferencesSection.openBiometricEnrollment",
            "Launching biometric enrollment/settings intent",
            mapOf("action" to (intent.action ?: "null")),
        )
        context.startActivity(intent)
    }.onFailure { firstError ->
        logger.w(
            "DatabaseSecurityPreferencesSection.openBiometricEnrollment",
            "Primary biometric enrollment intent failed; attempting generic settings fallback",
            mapOf(
                "action" to (intent.action ?: "null"),
                "error" to (firstError.message ?: "unknown"),
            ),
        )
        runCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            logger.i(
                "DatabaseSecurityPreferencesSection.openBiometricEnrollment",
                "Opened generic settings fallback",
            )
        }.onFailure { fallbackError ->
            logger.e(
                "DatabaseSecurityPreferencesSection.openBiometricEnrollment",
                "Failed to open biometric enrollment and fallback settings",
                fallbackError,
            )
        }
    }
}

private fun formatTimeoutDisplay(minutes: Int, context: Context): String {
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours == 0 -> context.getString(R.string.settings_db_unlock_timeout_value_minutes, minutes)
        remainingMinutes == 0 -> context.getString(R.string.settings_db_unlock_timeout_value_hours, hours)
        else -> context.getString(R.string.settings_db_unlock_timeout_value_hours_minutes, hours, remainingMinutes)
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
