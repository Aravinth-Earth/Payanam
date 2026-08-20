//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.ui.theme.LifeDimensionColors
import io.payanam.ui.viewmodel.DatabasePassphraseUnlockViewModel
import io.payanam.ui.viewmodel.PreUnlockUpdateViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
/**
 * Database passphrase unlock screen.
 */
fun DatabasePassphraseUnlockScreen(
    onPassphraseUnlocked: () -> Unit,
    onForgotPassphraseReset: () -> Unit,
    isImportMode: Boolean = false,
    viewModel: DatabasePassphraseUnlockViewModel = hiltViewModel(),
    preUnlockUpdateViewModel: PreUnlockUpdateViewModel = hiltViewModel(),
) {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** Context. */
    val context = LocalContext.current
    /** Host activity. */
    val hostActivity = remember(context) { context.findFragmentActivity() }
    /** Launched effect. */
    LaunchedEffect(hostActivity) {
        logger.i(
            "DatabasePassphraseUnlockScreen.activityResolution",
            "Resolved host activity for biometric prompt",
            /** Map of. */
            mapOf(
                "hostActivityPresent" to (hostActivity != null),
                "hostActivityClass" to (hostActivity?.javaClass?.name ?: "null"),
            ),
        )
    }
    val uiState by viewModel.uiState.collectAsState()
    var passphrase by rememberSaveable { mutableStateOf("") }
    var showPassphrase by rememberSaveable { mutableStateOf(false) }
    var localErrorReasonCode by rememberSaveable { mutableStateOf<String?>(null) }
    var showResetConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var debugExportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    /** Scope. */
    val scope = rememberCoroutineScope()
    /** Biometric can authenticate. */
    val biometricCanAuthenticate = biometricCanAuthenticate(context)
    /** Can use biometric. */
    val canUseBiometric = biometricCanAuthenticate == BiometricManager.BIOMETRIC_SUCCESS
    /** Biometric enabled. */
    val biometricEnabled = viewModel.isBiometricUnlockEnabled()
    var biometricPromptLaunched by rememberSaveable { mutableStateOf(false) }
    /** Launched effect. */
    LaunchedEffect(uiState.errorReasonCode, localErrorReasonCode) {
        /** Reason. */
        val reason = localErrorReasonCode ?: uiState.errorReasonCode
        /** If. */
        if (!reason.isNullOrBlank()) {
            logger.w(
                "DatabasePassphraseUnlockScreen.errorState",
                "Unlock screen error surfaced",
                /** Map of. */
                mapOf("reasonCode" to reason),
            )
        }
    }
    /** Launched effect. */
    LaunchedEffect(canUseBiometric, biometricEnabled, biometricCanAuthenticate) {
        logger.i(
            "DatabasePassphraseUnlockScreen.biometricAvailability",
            "Biometric availability resolved for unlock screen",
            /** Map of. */
            mapOf(
                "canUseBiometric" to canUseBiometric,
                "biometricEnabledPreference" to biometricEnabled,
                "canAuthenticateCode" to biometricCanAuthenticate,
            ),
        )
    }
    /** Submit unlock. */
    val submitUnlock: () -> Unit = {
        logger.i("DatabasePassphraseUnlockScreen", "Submitting passphrase unlock", mapOf("isImportMode" to isImportMode.toString()))
        localErrorReasonCode = null
        viewModel.unlock(
            passphrase = passphrase,
            onSuccess = {
                passphrase = ""
                /** On passphrase unlocked. */
                onPassphraseUnlocked()
            },
        )
    }

    // Surface uses the deep matte black from Design #2
    /** Surface. */
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212),
    ) {
        /** Column. */
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Header Section
            /** Column. */
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 32.dp),
            ) {
                Box {
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.app_name),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        ),
                        color = Color.White,
                    )
                    // The Pink Dot from Design #2
                    /** Canvas. */
                    Canvas(
                        modifier = Modifier
                            .size(4.dp)
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 8.dp, end = 2.dp),
                    ) {
                        /** Draw circle. */
                        drawCircle(color = LifeDimensionColors.Relationships)
                    }
                }
                /** Text. */
                Text(
                    text = stringResource(id = R.string.settings_about_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray.copy(alpha = 0.8f),
                )
            }

            // Database Summary (Glassmorphism Card)
            /** If. */
            if (uiState.hasLocalDatabase) {
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.05f),
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = 0.1f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    /** Column. */
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        /** Last updated. */
                        val lastUpdated = if (uiState.databaseLastModifiedMs > 0L) {
                            /** Simple date format. */
                            SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                                .format(Date(uiState.databaseLastModifiedMs))
                        } else {
                            "-"
                        }
                        /** Summary row. */
                        SummaryRow(
                            label = stringResource(id = R.string.db_passphrase_db_summary_storage_mode, ""),
                            value = if (uiState.storageModeLabelKey == "encrypted") {
                                /** String resource. */
                                stringResource(id = R.string.db_passphrase_storage_mode_encrypted)
                            } else {
                                /** String resource. */
                                stringResource(id = R.string.db_passphrase_storage_mode_plaintext)
                            },
                        )
                        /** Summary row. */
                        SummaryRow(
                            label = stringResource(id = R.string.loc_size),
                            value = "${uiState.databaseSizeKb} KB",
                        )
                        /** Summary row. */
                        SummaryRow(
                            label = stringResource(id = R.string.loc_last_modified),
                            value = lastUpdated,
                        )
                    }
                }
            }

            // Password Input Section
            /** Column. */
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                /** Outlined text field. */
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(id = R.string.db_passphrase_input_label)) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            /** If. */
                            if (!uiState.isUnlocking && uiState.lockoutSecondsRemaining <= 0 && passphrase.isNotBlank()) {
                                /** Submit unlock. */
                                submitUnlock()
                            }
                        },
                    ),
                    visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = {
                        androidx.compose.material3.Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Security,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.6f),
                        )
                    },
                    trailingIcon = {
                        /** Icon button. */
                        IconButton(onClick = { showPassphrase = !showPassphrase }) {
                            /** Icon. */
                            Icon(
                                imageVector = if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    id = if (showPassphrase) R.string.db_passphrase_hide_toggle else R.string.db_passphrase_show_toggle,
                                ),
                                tint = Color.White.copy(alpha = 0.75f),
                            )
                        }
                    },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = LifeDimensionColors.HealthWellness,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                )

                // Error Text Area (Soft Coral)
                /** Error text. */
                val errorText = when (localErrorReasonCode ?: uiState.errorReasonCode) {
                    "invalid" -> stringResource(id = R.string.db_passphrase_unlock_error_invalid)

                    "locked" -> stringResource(
                        id = R.string.db_passphrase_unlock_error_locked,
                        uiState.lockoutSecondsRemaining,
                    )

                    "key_invalidated" -> stringResource(id = R.string.db_passphrase_unlock_error_key_invalidated)

                    "biometric_failed", "biometric_error" -> stringResource(id = R.string.db_passphrase_unlock_error_biometric_failed)

                    "biometric_unavailable" -> stringResource(id = R.string.db_passphrase_unlock_error_biometric_unavailable)

                    "biometric_lockout" -> stringResource(id = R.string.db_passphrase_unlock_error_biometric_lockout)

                    "db_too_new" -> stringResource(id = R.string.db_passphrase_unlock_error_db_too_new)
                    "db_too_old" -> stringResource(id = R.string.db_passphrase_unlock_error_db_too_old)
                    "schema_invalid" -> stringResource(id = R.string.db_passphrase_unlock_error_schema_invalid)
                    "storage_incomplete" -> stringResource(id = R.string.db_passphrase_unlock_error_storage_incomplete)
                    "migration_required" -> stringResource(id = R.string.db_passphrase_unlock_error_migration_required)
                    "open_failed" -> stringResource(id = R.string.db_passphrase_unlock_error_open_failed)

                    "reset_failed" -> stringResource(id = R.string.db_passphrase_unlock_reset_failed)

                    else -> null
                }
                /** If. */
                if (errorText != null) {
                    /** Text. */
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF28B82), // Soft coral red
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            // Primary Unlock Button (Gradient)
            /** Gradient button. */
            GradientButton(
                text = if (uiState.isUnlocking) {
                    /** String resource. */
                    stringResource(id = R.string.db_passphrase_unlocking)
                } else {
                    /** String resource. */
                    stringResource(id = R.string.db_passphrase_unlock_action)
                },
                onClick = submitUnlock,
                enabled = !uiState.isUnlocking && uiState.lockoutSecondsRemaining <= 0 && passphrase.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )

            /** If. */
            if (canUseBiometric && biometricEnabled) {
                /** Text button. */
                TextButton(
                    onClick = {
                        logger.i(
                            "DatabasePassphraseUnlockScreen.biometricAction",
                            "Biometric unlock action tapped",
                            /** Map of. */
                            mapOf(
                                "hostActivityPresent" to (hostActivity != null),
                                "isUnlocking" to uiState.isUnlocking,
                            ),
                        )
                        /** If. */
                        if (hostActivity != null && !uiState.isUnlocking) {
                            biometricPromptLaunched = true
                            viewModel.startBiometricUnlock(hostActivity, onSuccess = onPassphraseUnlocked)
                        } else if (uiState.isUnlocking) {
                            logger.w(
                                "DatabasePassphraseUnlockScreen.biometricAction",
                                "Biometric unlock action ignored: unlock already in progress",
                            )
                        } else if (hostActivity == null) {
                            logger.w(
                                "DatabasePassphraseUnlockScreen.biometricAction",
                                "Biometric unlock action ignored: host activity unavailable",
                            )
                        }
                    },
                    enabled = hostActivity != null && !uiState.isUnlocking,
                ) {
                    /** Icon. */
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                    )
                    /** Spacer. */
                    Spacer(modifier = Modifier.width(8.dp))
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.db_passphrase_unlock_biometric_action),
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
            /** If. */
            if (!canUseBiometric) {
                /** Text button. */
                TextButton(
                    onClick = {
                        logger.i(
                            "DatabasePassphraseUnlockScreen.biometricSetup",
                            "Biometric setup action tapped",
                            /** Map of. */
                            mapOf(
                                "canAuthenticate" to biometricCanAuthenticate,
                                "biometricEnabledPreference" to biometricEnabled,
                            ),
                        )
                        /** Open biometric enrollment. */
                        openBiometricEnrollment(context)
                    },
                    enabled = !uiState.isUnlocking,
                ) {
                    /** Icon. */
                    Icon(
                        imageVector = Icons.Default.Fingerprint,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                    )
                    /** Spacer. */
                    Spacer(modifier = Modifier.width(8.dp))
                    /** Text. */
                    Text(
                        text = stringResource(id = R.string.db_passphrase_unlock_biometric_setup_action),
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }
            /** If. */
            if (canUseBiometric && !biometricEnabled) {
                /** Text. */
                Text(
                    text = stringResource(id = R.string.db_passphrase_unlock_biometric_enable_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            // Forgot Password Link
            /** Text button. */
            TextButton(
                onClick = { showResetConfirmDialog = true },
                enabled = !uiState.isUnlocking,
            ) {
                /** Text. */
                Text(
                    text = stringResource(id = R.string.db_passphrase_unlock_forgot_action),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            // Diagnostics Section (Muted)
            /** Column. */
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                /** Box. */
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.1f)),
                )

                /** Text. */
                Text(
                    text = stringResource(id = R.string.db_passphrase_diagnostics_title).uppercase(java.util.Locale.ROOT),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    ),
                    color = Color.White.copy(alpha = 0.4f),
                )

                /** Row. */
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    /** Diagnostic button. */
                    DiagnosticButton(
                        text = stringResource(id = R.string.settings_action_export_latest_log),
                        onClick = {
                            scope.launch {
                                /** Exported file. */
                                val exportedFile = logger.exportLatestLog()
                                debugExportMessage = exportedFile?.absolutePath ?: "Export failed"
                                logger.i(
                                    "DatabasePassphraseUnlockScreen.diagnostics",
                                    "Export latest log action completed",
                                    /** Map of. */
                                    mapOf("success" to (exportedFile != null)),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    /** Diagnostic button. */
                    DiagnosticButton(
                        text = stringResource(id = R.string.settings_action_export_all_logs),
                        onClick = {
                            scope.launch {
                                /** Exported file. */
                                val exportedFile = logger.exportAllLogs()
                                debugExportMessage = exportedFile?.absolutePath ?: "Export failed"
                                logger.i(
                                    "DatabasePassphraseUnlockScreen.diagnostics",
                                    "Export all logs action completed",
                                    /** Map of. */
                                    mapOf("success" to (exportedFile != null)),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                /** If. */
                if (debugExportMessage != null) {
                    /** Text. */
                    Text(
                        text = debugExportMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Pre-unlock update hatch (manual check → download → install).
            // Lives in the Diagnostics zone; works with the DB locked.
            /** Spacer. */
            Spacer(modifier = Modifier.height(4.dp))
            /** Pre unlock update section. */
            PreUnlockUpdateSection(viewModel = preUnlockUpdateViewModel)

            // Privacy Footer
            /** Text. */
            Text(
                text = stringResource(id = R.string.settings_about_description),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    // Biometric Auto-Trigger
    /** If. */
    if (canUseBiometric && biometricEnabled && hostActivity != null) {
        /** Launched effect. */
        LaunchedEffect(canUseBiometric, biometricEnabled, uiState.isUnlocking, biometricPromptLaunched) {
            /** If. */
            if (!uiState.isUnlocking && !biometricPromptLaunched) {
                logger.i(
                    "DatabasePassphraseUnlockScreen.biometricAutoPrompt",
                    "Auto-triggering biometric prompt",
                )
                biometricPromptLaunched = true
                viewModel.startBiometricUnlock(hostActivity, onSuccess = onPassphraseUnlocked)
            } else {
                logger.d(
                    "DatabasePassphraseUnlockScreen.biometricAutoPrompt",
                    "Auto-prompt skipped",
                    /** Map of. */
                    mapOf(
                        "isUnlocking" to uiState.isUnlocking,
                        "biometricPromptLaunched" to biometricPromptLaunched,
                    ),
                )
            }
        }
    }

    /** If. */
    if (showResetConfirmDialog) {
        /** Alert dialog. */
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text(stringResource(id = R.string.db_passphrase_unlock_reset_title)) },
            text = { Text(stringResource(id = R.string.db_passphrase_unlock_reset_message)) },
            confirmButton = {
                /** Button. */
                Button(
                    onClick = {
                        logger.w(
                            "DatabasePassphraseUnlockScreen.resetDialog",
                            "User confirmed forgot-passphrase reset",
                        )
                        showResetConfirmDialog = false
                        localErrorReasonCode = null
                        viewModel.forgotPassphraseReset(onSuccess = onForgotPassphraseReset)
                    },
                ) {
                    /** Text. */
                    Text(stringResource(id = R.string.db_passphrase_unlock_reset_confirm))
                }
            },
            dismissButton = {
                /** Text button. */
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    /** Text. */
                    Text(stringResource(id = R.string.settings_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    /** Row. */
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        /** Text. */
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        /** Text. */
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

@Composable
private fun GradientButton(
    /** Text. */
    text: String,
    onClick: () -> Unit,
    /** Enabled. */
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    /** Alpha. */
    val alpha = if (enabled) 1f else 0.5f
    /** Box. */
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
                /** If. */
                if (enabled) {
                    Modifier.background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(LifeDimensionColors.HealthWellness, LifeDimensionColors.CareerWork),
                        ),
                    )
                } else {
                    Modifier.background(Color.White.copy(alpha = 0.1f))
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        /** Text. */
        Text(
            text = text,
            color = Color.White.copy(alpha = alpha),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DiagnosticButton(
    /** Text. */
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    /** Box. */
    Box(
        modifier = modifier
            .height(40.dp)
            .border(
                1.dp,
                Color.White.copy(alpha = 0.2f),
                /** Rounded corner shape. */
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        /** Text. */
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun biometricCanAuthenticate(context: Context): Int = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

private fun openBiometricEnrollment(context: Context) {
    /** Logger. */
    val logger = UnifiedLogger.getInstance()
    /** Intent. */
    val intent = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            /** Intent. */
            Intent(Settings.ACTION_BIOMETRIC_ENROLL)

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
            @Suppress("DEPRECATION")
            /** Intent. */
            Intent(Settings.ACTION_FINGERPRINT_ENROLL)

        else -> Intent(Settings.ACTION_SECURITY_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        logger.i(
            "DatabasePassphraseUnlockScreen.openBiometricEnrollment",
            "Launching biometric enrollment/settings intent",
            /** Map of. */
            mapOf("action" to (intent.action ?: "null")),
        )
        context.startActivity(intent)
    }.onFailure { firstError ->
        logger.w(
            "DatabasePassphraseUnlockScreen.openBiometricEnrollment",
            "Primary biometric enrollment intent failed; attempting generic settings fallback",
            /** Map of. */
            mapOf(
                "action" to (intent.action ?: "null"),
                "error" to (firstError.message ?: "unknown"),
            ),
        )
        runCatching {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            logger.i(
                "DatabasePassphraseUnlockScreen.openBiometricEnrollment",
                "Opened generic settings fallback",
            )
        }.onFailure { fallbackError ->
            logger.e(
                "DatabasePassphraseUnlockScreen.openBiometricEnrollment",
                "Failed to open biometric enrollment and fallback settings",
                /** Fallback error. */
                fallbackError,
            )
        }
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    /** Current. */
    var current: Context? = this
    /** While. */
    while (current is ContextWrapper) {
        /** If. */
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
