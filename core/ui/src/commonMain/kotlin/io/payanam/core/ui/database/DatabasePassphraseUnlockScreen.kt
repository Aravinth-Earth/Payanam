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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * The biometric affordances this screen renders, supplied by the platform rather than built here.
 *
 * WHAT THIS IS: the decision (aligned divergence #1) that biometrics are an Android-only capability, and
 * that a platform without a sensor must not be handed a fake one. The screen below is shared, so the two
 * questions "does this device have a usable sensor" and "how does a user enroll one" are the platform's
 * to answer. They are asked through this interface instead of an `android.*` type, so the same screen
 * compiles for the JVM desktop and renders the honest passphrase path there.
 *
 * A PLATFORM WITH NO BIOMETRICS OMITS THE WHOLE SLOT and therefore reports a `null`
 * [canAuthenticate]. That is deliberately indistinguishable from "Android, sensor present, nothing
 * enrolled": both render the enrollment affordance and neither renders an unlock button, so the typed
 * passphrase remains the only way in. No stub claims a capability the platform does not have.
 *
 * WHAT IS AND IS NOT IN HERE. Only the two genuinely Android operations are: asking
 * `BiometricManager` what the sensor can do, and launching the enrollment screen. Both buttons draw
 * themselves, because they are the platform's own Material buttons and re-expressing them as data (a
 * title plus an onClick) would put a second, drifting copy of that styling into this file. Everything
 * else stayed in the shared screen: the auto-prompt, the enable hint and the lock on "only prompt once"
 * are all logic over state the SHARED ViewModel already holds, so moving them out would have hidden
 * behaviour the desktop also has to obey.
 */
interface BiometricUnlockScreenActions {
    /**
     * The sensor's OWN verdict, kept separate from the ViewModel's [BiometricAvailability] on purpose:
     * the latter folds the sensor's verdict together with the user's own preference into one answer, but
     * the enrollment affordance has to ask about the hardware specifically.
     *
     * A property rather than a constructor argument, so the sensor is re-checked on every recomposition
     * exactly as the screen's old `biometricCanAuthenticate(context)` call was.
     */
    val canAuthenticate: BiometricAvailability

    /**
     * The unlock and enrollment buttons, rendered between the primary unlock button and the
     * "forgot passphrase" link. [isUnlocking] is passed in because a platform cannot read the shared
     * ViewModel's state, and both buttons disable themselves while an unlock is in flight.
     */
    val actions: @Composable (isUnlocking: Boolean) -> Unit
}

/**
 * The DB-locked gate, rendered identically by every platform.
 *
 * Moved here from `:app` with its layout, order, colours and copy unchanged, so Android and the desktop
 * cannot drift into two different unlock screens. The desktop follows
 * Android's EXACT first-run flow - create-db, import-db and onboarding all identical, in the same shared
 * screens - and this gate is one of the screens both platforms must therefore share.
 *
 * WHAT CAME OUT, and why each piece is a slot rather than a rewrite:
 *
 *  - **Biometrics** ([biometricActions]) are aligned divergence #1: a decided, permanent platform
 *    difference, not a gap to close. `BiometricManager`, the enrollment `Intent` chain and
 *    `Settings.ACTION_*` are all Android types, and `:app` keeps the exact current behaviour behind the
 *    slot - the same `BiometricManager` checks, the same `BiometricPrompt`, the same fall-back to
 *    `Settings.ACTION_SETTINGS` when the enrollment intent fails, and the same auto-prompt. A platform with
 *    no biometrics passes nothing at all and the passphrase path renders exactly as it does on Android
 *    when biometrics are unavailable. There is deliberately no desktop no-op pretending to be biometrics.
 *    The one thing this shared file still owns is the ENABLE HINT, because it is a sentence rather than
 *    a capability, and it is copy - so it comes from [LocalUiStrings] like every other label.
 *  - **The pre-unlock update hatch** ([preUnlockUpdate]) is a `@Composable () -> Unit` slot, following
 *    [DatabaseInitScreen]'s `debugLogExport` precedent. It is an Android APK download-and-install flow
 *    (`Intent` + `FileProvider` + `PackageInstaller`, behind a `@HiltViewModel` that lives in `:app`), and
 *    this screen renders on the DB-LOCKED path, so a shared screen that called it directly would be
 *    unbuildable on the desktop for no user-visible reason. `:app` passes the existing section unchanged.
 *  - **The log-export buttons** ([debugLogExport]) take the same shape as `debugLogExport` on
 *    [DatabaseInitScreen]: `exportLatestLog()`/`exportAllLogs()` return an Android `java.io.File` written
 *    to Android's own storage, so the exporter and the path it prints stay in `:app`.
 *  - **Copy** comes from [LocalUiStrings] rather than `stringResource`. All 37 keys this screen read
 *    already ship in `:core:strings` with EN+TA parity, so nothing was added to the string tables.
 *
 * The ViewModel is unchanged: the shared
 * [io.payanam.presentation.database.DatabasePassphraseUnlockViewModel] is still supplied by the caller,
 * and the [LocalUiStrings] it already holds are the same table the screen reads, so the two can never
 * disagree about the user's language.
 *
 * The plain-passphrase path - the field, the visibility toggle, the lockout countdown, the error copy,
 * the forgot-passphrase reset dialog, the database summary card - is Android's behaviour unchanged,
 * because it is the part every platform shares.
 */
@Composable
fun DatabasePassphraseUnlockScreen(
    onPassphraseUnlocked: () -> Unit,
    onForgotPassphraseReset: () -> Unit,
    isImportMode: Boolean = false,
    /**
     * The SHARED unlock ViewModel, supplied by the caller rather than fetched here.
     *
     * It used to default to `hiltViewModel()`: it is a plain class in `:core:presentation` now, and
     * `hiltViewModel<T>()` resolves through a factory that only knows `@HiltViewModel` classes - a
     * shared module cannot carry that annotation, because the desktop has no Hilt at all. The platform
     * builds it from the collaborators it binds and retains it (Android through
     * `retainSharedViewModel(key, onRelease = { it.dispose() })`, the desktop through its own holder),
     * so the wiring is visible at the call site and both platforms build the same object graph.
     */
    viewModel: DatabasePassphraseUnlockViewModel,
    /**
     * Android's biometric block. Omitted on a platform with no biometrics; see
     * [BiometricUnlockScreenActions] for what that means for the passphrase path.
     */
    biometricActions: BiometricUnlockScreenActions? = null,
    /**
     * Android's pre-unlock update hatch. A no-op slot here, not a missing feature: the flow it wraps is
     * Android's own APK install, and the desktop has no honest equivalent to offer yet.
     */
    preUnlockUpdate: (@Composable () -> Unit)? = null,
    /**
     * Android's log-export buttons AND the path they report. See [DatabaseInitScreen]'s parameter of the
     * same name; the reported path is Android's own file, so it renders inside the slot rather than here.
     */
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
            modifier = Modifier
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
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        ),
                        color = Color.White,
                    )
                    // The Pink Dot from Design #2
                    Canvas(
                        modifier = Modifier
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
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.05f),
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Color.White.copy(alpha = 0.1f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val lastUpdated = if (uiState.databaseLastModifiedMs > 0L) {
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
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.dbPassphraseInputLabel) },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
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
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = LifeDimensionColors.HealthWellness,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    ),
                    shape = RoundedCornerShape(12.dp),
                )

                // Error Text Area (Soft Coral)
                val errorText = when (localErrorReasonCode ?: uiState.errorReasonCode) {
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
                text = if (uiState.isUnlocking) {
                    strings.dbPassphraseUnlocking
                } else {
                    strings.dbPassphraseUnlockAction
                },
                onClick = submitUnlock,
                enabled = !uiState.isUnlocking && uiState.lockoutSecondsRemaining <= 0 && passphrase.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.1f)),
                )
                Text(
                    text = strings.dbPassphraseDiagnosticsTitle.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelSmall.copy(
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

    // Biometric Auto-Trigger. Stays here, in the shared screen, because it is logic over the SHARED
    // ViewModel's own state and a lock against prompting twice: both platforms have to obey it, and the
    // platform slot has no access to `uiState` beyond the one `isUnlocking` flag it is handed. The prompt
    // it triggers is the platform's, reached through the ViewModel's existing `startBiometricUnlock`.
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
        AlertDialog(
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
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = Color.White)
    }
}

/**
 * One outlined diagnostic action, the visual language of this screen's diagnostics zone.
 *
 * Public rather than private because the log-export buttons behind the [debugLogExport] slot have to
 * LOOK like they belong to this screen while the exporter that backs them stays in `:app` (it returns an
 * Android `File` written to Android's own storage). Exposing the button - rather than letting each
 * platform draw its own - is what keeps ONE copy of that visual instead of a second that drifts.
 *
 * @param text the label; copy, so it comes from [LocalUiStrings] at the call site.
 */
@Composable
fun DatabasePassphraseUnlockDiagnosticButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .border(
                1.dp,
                Color.White.copy(alpha = 0.2f),
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
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
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(
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
        Text(
            text = text,
            color = Color.White.copy(alpha = alpha),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
