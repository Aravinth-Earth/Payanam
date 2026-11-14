//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.database

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
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.payanam.core.ui.components.PayanamAlertDialog
import io.payanam.core.ui.components.TestTags
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.core.ui.theme.LifeDimensionColors
import io.payanam.presentation.database.DatabasePassphraseUnlockViewModel
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.BiometricAvailability
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The biometric affordances this screen renders, supplied by the platform rather than built here:
 * biometrics are Android-only, and a platform without a sensor must not be handed a fake one. Such a
 * platform omits the slot and reports a null [canAuthenticate], which renders the honest passphrase path. */
interface BiometricUnlockScreenActions {
    /** The sensor's OWN verdict, not the ViewModel's folded answer: enrollment asks about hardware. */
    val canAuthenticate: BiometricAvailability

    /** The unlock and enrollment buttons, drawn between the primary button and "forgot passphrase". */
    val actions: @Composable (isUnlocking: Boolean) -> Unit
}

/** The DB-locked gate, rendered identically by every platform. Android's layout, order, colours and copy,
 * with each platform-specific piece as a slot: biometrics, the pre-unlock update hatch and the log-export
 * buttons. Copy comes from [LocalUiStrings], and the shared ViewModel is supplied by the caller. */
@Composable
fun DatabasePassphraseUnlockScreen(
    onPassphraseUnlocked: () -> Unit,
    onForgotPassphraseReset: () -> Unit,
    isImportMode: Boolean = false,
    /** The SHARED unlock ViewModel, supplied by the caller: it is a plain class, so Hilt cannot build it. */
    viewModel: DatabasePassphraseUnlockViewModel,
    /** Android's biometric block; omitted on a platform with no biometrics. */
    biometricActions: BiometricUnlockScreenActions? = null,
    /** Android's pre-unlock update hatch; a no-op slot here, not a missing feature. */
    preUnlockUpdate: (@Composable () -> Unit)? = null,
    /** Android's log-export buttons; the reported path is Android's own file, so it renders in the slot. */
    debugLogExport: (@Composable () -> Unit)? = null,
) {
    val strings = LocalUiStrings.current
    val logger = AppLogger.current
    val uiState by viewModel.uiState.collectAsState()
    var passphrase by rememberSaveable { mutableStateOf("") }
    var showPassphrase by rememberSaveable { mutableStateOf(false) }
    var localErrorReasonCode by rememberSaveable { mutableStateOf<String?>(null) }
    var showResetConfirmDialog by rememberSaveable { mutableStateOf(false) }
    // The sensor's own verdict, kept for the ENROLLMENT affordance: a user with no enrolled fingerprint
    // is offered the settings screen, and that question is the platform's - the shared ViewModel's
    // availability folds the same verdict together with the user's own preference into one answer.
    val biometricCanAuthenticate = biometricActions?.canAuthenticate
    val canUseBiometric = biometricCanAuthenticate is BiometricAvailability.Available
    val biometricAvailability = viewModel.biometricAvailability()
    val biometricEnabled = viewModel.isBiometricUnlockEnabled()
    // What `hostActivity != null` used to gate. The prompt is a shared seam, so the question is no longer
    // "did this context find an Activity" but "can this platform show a prompt right now" - which the
    // ViewModel answers through the adapter that would show it.
    val biometricPromptAvailable = biometricAvailability is BiometricAvailability.Available
    var biometricPromptLaunched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(uiState.errorReasonCode, localErrorReasonCode) {
        val reason = localErrorReasonCode ?: uiState.errorReasonCode
        if (!reason.isNullOrBlank()) {
            logger.w(
                "DatabasePassphraseUnlockScreen.errorState",
                "Unlock screen error surfaced",
                mapOf("reasonCode" to reason),
            )
        }
    }
    LaunchedEffect(
        canUseBiometric,
        biometricEnabled,
        biometricCanAuthenticate,
        biometricPromptAvailable,
    ) {
        logger.i(
            "DatabasePassphraseUnlockScreen.biometricAvailability",
            "Biometric availability resolved for unlock screen",
            mapOf(
                "canUseBiometric" to canUseBiometric,
                "biometricEnabledPreference" to biometricEnabled,
                "canAuthenticate" to (biometricCanAuthenticate?.toString() ?: "no-biometric-platform"),
                // The shared seam's own answer, replacing the old `hostActivityPresent`: the prompt is no
                // longer shown from a resolved Activity, so its availability is the only honest gate left.
                "promptAvailable" to biometricPromptAvailable,
            ),
        )
    }
    val submitUnlock: () -> Unit = {
        logger.i("DatabasePassphraseUnlockScreen", "Submitting passphrase unlock", mapOf("isImportMode" to isImportMode.toString()))
        localErrorReasonCode = null
        viewModel.unlock(
            passphrase = passphrase,
            onSuccess = {
                passphrase = ""
                onPassphraseUnlocked()
            },
        )
    }

    // Surface uses the deep matte black from Design #2
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Header Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 32.dp),
            ) {
                Box {
                    Text(
                        text = strings.appName,
                        style =
                            MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp,
                            ),
                        color = Color.White,
                    )
                    // The Pink Dot from Design #2
                    Canvas(
                        modifier =
                            Modifier
                                .size(4.dp)
                                .align(Alignment.BottomEnd)
                                .padding(bottom = 8.dp, end = 2.dp),
                    ) {
                        drawCircle(color = LifeDimensionColors.Relationships)
                    }
                }
                Text(
                    text = strings.settingsAboutTagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray.copy(alpha = 0.8f),
                )
            }

            // Database Summary (Glassmorphism Card)
            if (uiState.hasLocalDatabase) {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.05f),
                        ),
                    shape = RoundedCornerShape(16.dp),
                    border =
                        androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Color.White.copy(alpha = 0.1f),
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val lastUpdated =
                            if (uiState.databaseLastModifiedMs > 0L) {
                                SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                                    .format(Date(uiState.databaseLastModifiedMs))
                            } else {
                                "-"
                            }
                        // Shown only for a database that IS encrypted. The else arm used to label a
                        // not-yet-encrypted database "plaintext", which describes a transient state
                        // rather than the product's storage model.
                        if (uiState.storageModeLabelKey == "encrypted") {
                            SummaryRow(
                                label = strings.dbPassphraseStorageModeLabel,
                                value = strings.dbPassphraseStorageModeEncrypted,
                            )
                        }
                        SummaryRow(
                            label = strings.locSize,
                            value = "${uiState.databaseSizeKb} KB",
                        )
                        SummaryRow(
                            label = strings.locLastModified,
                            value = lastUpdated,
                        )
                    }
                }
            }

            // Password Input Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(TestTags.PassphraseUnlock.PASSPHRASE_FIELD),
                    label = { Text(strings.dbPassphraseInputLabel) },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = {
                                if (!uiState.isUnlocking && uiState.lockoutSecondsRemaining <= 0 && passphrase.isNotBlank()) {
                                    submitUnlock()
                                }
                            },
                        ),
                    visualTransformation = if (showPassphrase) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.6f),
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassphrase = !showPassphrase }) {
                            Icon(
                                imageVector = if (showPassphrase) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassphrase) strings.dbPassphraseHideToggle else strings.dbPassphraseShowToggle,
                                tint = Color.White.copy(alpha = 0.75f),
                            )
                        }
                    },
                    colors =
                        androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = LifeDimensionColors.HealthWellness,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        ),
                    shape = RoundedCornerShape(12.dp),
                )

                // Error Text Area (Soft Coral)
                val errorText =
                    when (localErrorReasonCode ?: uiState.errorReasonCode) {
                        "invalid" -> strings.dbPassphraseUnlockErrorInvalid
                        "locked" -> strings.dbPassphraseUnlockErrorLocked.formatArgs(uiState.lockoutSecondsRemaining)
                        "key_invalidated" -> strings.dbPassphraseUnlockErrorKeyInvalidated
                        "biometric_failed", "biometric_error" -> strings.dbPassphraseUnlockErrorBiometricFailed
                        "biometric_unavailable" -> strings.dbPassphraseUnlockErrorBiometricUnavailable
                        "biometric_lockout" -> strings.dbPassphraseUnlockErrorBiometricLockout
                        "db_too_new" -> strings.dbPassphraseUnlockErrorDbTooNew
                        "db_too_old" -> strings.dbPassphraseUnlockErrorDbTooOld
                        "schema_invalid" -> strings.dbPassphraseUnlockErrorSchemaInvalid
                        "storage_incomplete" -> strings.dbPassphraseUnlockErrorStorageIncomplete
                        "migration_required" -> strings.dbPassphraseUnlockErrorMigrationRequired
                        "open_failed" -> strings.dbPassphraseUnlockErrorOpenFailed
                        "reset_failed" -> strings.dbPassphraseUnlockResetFailed
                        else -> null
                    }
                if (errorText != null) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFF28B82), // Soft coral red
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            // Primary Unlock Button (Gradient)
            GradientButton(
                text =
                    if (uiState.isUnlocking) {
                        strings.dbPassphraseUnlocking
                    } else {
                        strings.dbPassphraseUnlockAction
                    },
                onClick = submitUnlock,
                enabled = !uiState.isUnlocking && uiState.lockoutSecondsRemaining <= 0 && passphrase.isNotBlank(),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(TestTags.PassphraseUnlock.UNLOCK_BUTTON),
            )
            biometricActions?.actions?.invoke(uiState.isUnlocking)
            if (canUseBiometric && !biometricEnabled) {
                Text(
                    text = strings.dbPassphraseUnlockBiometricEnableHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }

            // Forgot Password Link
            TextButton(
                onClick = { showResetConfirmDialog = true },
                enabled = !uiState.isUnlocking,
            ) {
                Text(
                    text = strings.dbPassphraseUnlockForgotAction,
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            // Diagnostics Section (Muted)
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.White.copy(alpha = 0.1f)),
                )
                Text(
                    text = strings.dbPassphraseDiagnosticsTitle.uppercase(Locale.ROOT),
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        ),
                    color = Color.White.copy(alpha = 0.4f),
                )
                debugLogExport?.invoke()
            }

            // Pre-unlock update hatch (manual check → download → install).
            // Lives in the Diagnostics zone; works with the DB locked.
            Spacer(modifier = Modifier.height(4.dp))
            preUnlockUpdate?.invoke()

            // Privacy Footer
            Text(
                text = strings.settingsAboutDescription,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    // The auto-prompt stays here because it is logic over the SHARED ViewModel's state plus a
    // prompt-once lock; the platform slot only receives `isUnlocking`.
    if (canUseBiometric && biometricEnabled && biometricPromptAvailable) {
        LaunchedEffect(canUseBiometric, biometricEnabled, uiState.isUnlocking, biometricPromptLaunched) {
            if (!uiState.isUnlocking && !biometricPromptLaunched) {
                logger.i(
                    "DatabasePassphraseUnlockScreen.biometricAutoPrompt",
                    "Auto-triggering biometric prompt",
                )
                biometricPromptLaunched = true
                viewModel.startBiometricUnlock(onSuccess = onPassphraseUnlocked)
            } else {
                logger.d(
                    "DatabasePassphraseUnlockScreen.biometricAutoPrompt",
                    "Auto-prompt skipped",
                    mapOf(
                        "isUnlocking" to uiState.isUnlocking,
                        "biometricPromptLaunched" to biometricPromptLaunched,
                    ),
                )
            }
        }
    }
    if (showResetConfirmDialog) {
        PayanamAlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text(strings.dbPassphraseUnlockResetTitle) },
            text = { Text(strings.dbPassphraseUnlockResetMessage) },
            confirmButton = {
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

@Composable
private fun SummaryRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

/** One outlined diagnostic action, the visual language of this screen's diagnostics zone. Public so the
 * log-export buttons behind the [debugLogExport] slot look like they belong here while the exporter
 * stays in `:app`. */
@Composable
fun DatabasePassphraseUnlockDiagnosticButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(40.dp)
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.2f),
                    RoundedCornerShape(8.dp),
                ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun GradientButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha = if (enabled) 1f else 0.5f
    Box(
        modifier =
            modifier
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (enabled) {
                        Modifier.background(
                            brush =
                                Brush.horizontalGradient(
                                    colors = listOf(LifeDimensionColors.HealthWellness, LifeDimensionColors.CareerWork),
                                ),
                        )
                    } else {
                        Modifier.background(Color.White.copy(alpha = 0.1f))
                    },
                ).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = alpha),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
