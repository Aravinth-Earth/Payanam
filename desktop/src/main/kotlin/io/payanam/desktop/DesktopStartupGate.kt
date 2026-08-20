//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("TooManyFunctions")

package io.payanam.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.payanam.shared.settings.FocusModePreset
import io.payanam.shared.startup.DesktopStartupSnapshot

private data class DesktopStartupGateInputState(
    /** Passphrase. */
    val passphrase: String,
    /** Confirm passphrase. */
    val confirmPassphrase: String,
    /** Selected preset. */
    val selectedPreset: FocusModePreset,
    /** Runtime state. */
    val runtimeState: DesktopStartupRuntimeState,
    /** Database snapshot. */
    val databaseSnapshot: DesktopDatabaseSnapshot,
)

private data class DesktopStartupGateActions(
    /** On passphrase changed. */
    val onPassphraseChanged: (String) -> Unit,
    /** On confirm passphrase changed. */
    val onConfirmPassphraseChanged: (String) -> Unit,
    /** On status message changed. */
    val onStatusMessageChanged: (String) -> Unit,
    /** On passphrase cleared. */
    val onPassphraseCleared: () -> Unit,
    /** On preset selected. */
    val onPresetSelected: (FocusModePreset) -> Unit,
    /** On forgot reset requested. */
    val onForgotResetRequested: () -> Unit,
)

private data class DesktopPassphraseCardConfig(
    /** Title. */
    val title: String,
    /** Field label. */
    val fieldLabel: String,
    /** Secondary field label. */
    val secondaryFieldLabel: String? = null,
    /** Field description. */
    val fieldDescription: String,
    /** Secondary field description. */
    val secondaryFieldDescription: String? = null,
    /** Action label. */
    val actionLabel: String,
    /** Helper text. */
    val helperText: String? = null,
)

@Composable
internal fun desktopStartupGateSurface(
    snapshot: DesktopStartupSnapshot,
    startupMode: DesktopStartupMode,
    runtimeState: DesktopStartupRuntimeState,
    databaseSnapshot: DesktopDatabaseSnapshot,
    callbacks: DesktopShellCallbacks,
    sessionLogger: DesktopSessionLogger,
) {
    var passphrase by remember(startupMode) { mutableStateOf("") }
    var confirmPassphrase by remember(startupMode) { mutableStateOf("") }
    var selectedPreset by remember(startupMode) { mutableStateOf(FocusModePreset.FULL_SUITE) }
    var statusMessage by remember(startupMode) { mutableStateOf<String?>(null) }
    var showResetConfirmDialog by remember(startupMode) { mutableStateOf(false) }
    LaunchedEffect(startupMode, snapshot.checks.size, snapshot.readyChecks()) {
        sessionLogger.i(
            source = "DesktopStartupGate.state",
            message = "Desktop startup gate active",
            data =
                mapOf(
                    "startupMode" to startupMode.name,
                    "readyChecks" to snapshot.readyChecks(),
                    "totalChecks" to snapshot.checks.size,
                ),
        )
    }

    desktopRootSurface {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            desktopStartupHeaderCard(startupMode = startupMode, statusMessage = statusMessage)
            desktopStartupActionSection(
                startupMode = startupMode,
                inputState =
                    DesktopStartupGateInputState(
                        passphrase = passphrase,
                        confirmPassphrase = confirmPassphrase,
                        selectedPreset = selectedPreset,
                        runtimeState = runtimeState,
                        databaseSnapshot = databaseSnapshot,
                    ),
                callbacks = callbacks,
                sessionLogger = sessionLogger,
                actions =
                    DesktopStartupGateActions(
                        onPassphraseChanged = {
                            passphrase = it
                            statusMessage = null
                        },
                        onConfirmPassphraseChanged = {
                            confirmPassphrase = it
                            statusMessage = null
                        },
                        onStatusMessageChanged = { nextMessage ->
                            statusMessage = nextMessage
                        },
                        onPassphraseCleared = {
                            passphrase = ""
                            confirmPassphrase = ""
                        },
                        onPresetSelected = { selectedPreset = it },
                        onForgotResetRequested = { showResetConfirmDialog = true },
                    ),
            )
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = { Text("Reset desktop data?") },
            text = {
                Text("This will remove the current local desktop database and clear the saved passphrase so setup can start fresh.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionLogger.w(
                            source = "DesktopStartupGate.forgotPassphraseResetConfirmed",
                            message = "Desktop forgot-passphrase reset confirmed",
                            data = mapOf("startupMode" to startupMode.name),
                        )
                        callbacks.onForgotPassphraseReset()
                        showResetConfirmDialog = false
                        passphrase = ""
                        confirmPassphrase = ""
                        statusMessage = "Desktop data was reset. Create a new passphrase to continue."
                    },
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun desktopStartupHeaderCard(
    startupMode: DesktopStartupMode,
    statusMessage: String?,
) {
    Card(
        backgroundColor = desktopAccentCardColor(),
        shape = RoundedCornerShape(22.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Desktop startup gate" }
                    .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Payanam",
                style = MaterialTheme.typography.h4,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = desktopStartupTitle(startupMode),
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = desktopStartupSummary(startupMode),
                style = MaterialTheme.typography.body1,
                color = desktopBodyTextColor(),
            )
            statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.body2,
                    color = desktopMutedTextColor(),
                )
            }
        }
    }
}

@Composable
private fun desktopStartupActionSection(
    startupMode: DesktopStartupMode,
    inputState: DesktopStartupGateInputState,
    callbacks: DesktopShellCallbacks,
    sessionLogger: DesktopSessionLogger,
    actions: DesktopStartupGateActions,
) {
    when (startupMode) {
        DesktopStartupMode.SetupPassphrase -> {
            desktopSetupPassphraseSection(
                inputState = inputState,
                callbacks = callbacks,
                sessionLogger = sessionLogger,
                actions = actions,
            )
        }

        DesktopStartupMode.UnlockPassphrase -> {
            desktopUnlockPassphraseSection(
                startupMode = startupMode,
                inputState = inputState,
                callbacks = callbacks,
                sessionLogger = sessionLogger,
                actions = actions,
            )
        }

        DesktopStartupMode.InitializeDatabase -> {
            desktopInitializeDatabaseSection(
                inputState = inputState,
                callbacks = callbacks,
                sessionLogger = sessionLogger,
                actions = actions,
            )
        }

        DesktopStartupMode.FocusModeSelection -> {
            desktopFocusModeSelectionSection(
                inputState = inputState,
                callbacks = callbacks,
                sessionLogger = sessionLogger,
                actions = actions,
            )
        }

        DesktopStartupMode.Ready -> {
            Unit
        }
    }
}

@Composable
private fun desktopSetupPassphraseSection(
    inputState: DesktopStartupGateInputState,
    callbacks: DesktopShellCallbacks,
    sessionLogger: DesktopSessionLogger,
    actions: DesktopStartupGateActions,
) {
    desktopDatabaseSummaryCard(
        title = "Local desktop data",
        databaseSnapshot = inputState.databaseSnapshot,
        hasPassphraseConfigured = inputState.runtimeState.hasPassphraseConfigured,
    )
    desktopStartupPassphraseCard(
        config =
            DesktopPassphraseCardConfig(
                title = "Create desktop passphrase",
                fieldLabel = "New passphrase",
                secondaryFieldLabel = "Confirm passphrase",
                fieldDescription = "Desktop passphrase field",
                secondaryFieldDescription = "Desktop confirm passphrase field",
                actionLabel = "Save passphrase",
                helperText =
                    "This desktop passphrase protects the local app installation. " +
                        "If it is lost, recovery requires resetting the local data.",
            ),
        passphrase = inputState.passphrase,
        secondaryPassphrase = inputState.confirmPassphrase,
        onPassphraseChanged = actions.onPassphraseChanged,
        onSecondaryPassphraseChanged = actions.onConfirmPassphraseChanged,
        onAction = {
            if (inputState.passphrase != inputState.confirmPassphrase) {
                actions.onStatusMessageChanged("Passphrase confirmation does not match.")
                return@desktopStartupPassphraseCard
            }
            sessionLogger.i(
                source = "DesktopStartupGate.setupPassphrase",
                message = "Desktop passphrase setup requested",
                data =
                    mapOf(
                        "passphraseLength" to inputState.passphrase.length,
                        "hasExistingDatabaseArtifacts" to inputState.databaseSnapshot.hasArtifacts,
                    ),
            )
            val message = desktopSetupPassphraseMessage(callbacks.onConfigurePassphrase(inputState.passphrase))
            if (message.startsWith("Passphrase saved")) {
                actions.onPassphraseCleared()
            }
            actions.onStatusMessageChanged(message)
        },
    )
}

@Composable
private fun desktopUnlockPassphraseSection(
    startupMode: DesktopStartupMode,
    inputState: DesktopStartupGateInputState,
    callbacks: DesktopShellCallbacks,
    sessionLogger: DesktopSessionLogger,
    actions: DesktopStartupGateActions,
) {
    desktopDatabaseSummaryCard(
        title = "Protected desktop database",
        databaseSnapshot = inputState.databaseSnapshot,
        hasPassphraseConfigured = inputState.runtimeState.hasPassphraseConfigured,
    )
    desktopStartupPassphraseCard(
        config =
            DesktopPassphraseCardConfig(
                title = "Unlock desktop session",
                fieldLabel = "Desktop passphrase",
                fieldDescription = "Desktop unlock passphrase field",
                actionLabel = "Unlock desktop",
                helperText =
                    if (inputState.runtimeState.lockoutSecondsRemaining > 0L) {
                        "Unlock is temporarily locked. Wait ${inputState.runtimeState.lockoutSecondsRemaining} seconds before retrying."
                    } else {
                        "Enter the existing desktop passphrase to reopen this local installation."
                    },
            ),
        passphrase = inputState.passphrase,
        onPassphraseChanged = actions.onPassphraseChanged,
        onAction = {
            sessionLogger.i(
                source = "DesktopStartupGate.unlockPassphrase",
                message = "Desktop passphrase unlock requested",
                data = mapOf("passphraseLength" to inputState.passphrase.length),
            )
            val message = desktopUnlockPassphraseMessage(callbacks.onUnlockPassphrase(inputState.passphrase))
            if (message == "Desktop session unlocked.") {
                actions.onPassphraseCleared()
            }
            actions.onStatusMessageChanged(message)
        },
    )
    desktopForgotPassphraseCard(
        onReset = {
            sessionLogger.w(
                source = "DesktopStartupGate.forgotPassphraseReset",
                message = "Desktop passphrase reset requested",
                data = mapOf("startupMode" to startupMode.name),
            )
            actions.onForgotResetRequested()
        },
    )
}

@Composable
private fun desktopInitializeDatabaseSection(
    inputState: DesktopStartupGateInputState,
    callbacks: DesktopShellCallbacks,
    sessionLogger: DesktopSessionLogger,
    actions: DesktopStartupGateActions,
) {
    desktopDatabaseSummaryCard(
        title = "Desktop database setup",
        databaseSnapshot = inputState.databaseSnapshot,
        hasPassphraseConfigured = inputState.runtimeState.hasPassphraseConfigured,
    )
    desktopInitializeDatabaseCard(
        onImportLatest = {
            sessionLogger.i(
                source = "DesktopStartupGate.importLatestDesktopState",
                message = "Desktop startup import requested",
            )
            val handoffSnapshot = callbacks.onImportLocalState()
            val message =
                if (handoffSnapshot.importCompleted) {
                    "Imported desktop handoff from ${handoffSnapshot.exportFilePath}. Continue with the updated startup state."
                } else {
                    "No desktop handoff bundle was found to import yet."
                }
            actions.onStatusMessageChanged(message)
        },
        onInitialize = {
            sessionLogger.i(
                source = "DesktopStartupGate.initializeDatabase",
                message = "Desktop database initialization requested",
                data = mapOf("hasDatabaseArtifacts" to inputState.runtimeState.hasDatabaseArtifacts),
            )
            callbacks.onInitializeDatabase()
            actions.onStatusMessageChanged("Desktop database initialized.")
        },
    )
}

@Composable
private fun desktopFocusModeSelectionSection(
    inputState: DesktopStartupGateInputState,
    callbacks: DesktopShellCallbacks,
    sessionLogger: DesktopSessionLogger,
    actions: DesktopStartupGateActions,
) {
    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Choose your focus",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Pick the desktop navigation preset that best matches how you want to start using Payanam.",
                style = MaterialTheme.typography.body2,
                color = desktopMutedTextColor(),
            )
            FocusModePreset.entries.forEach { preset ->
                desktopFocusPresetCard(
                    preset = preset,
                    isSelected = inputState.selectedPreset == preset,
                    onSelected = { actions.onPresetSelected(preset) },
                )
            }
            Button(
                modifier = Modifier.semantics { contentDescription = "Continue with focus preset" },
                onClick = {
                    sessionLogger.i(
                        source = "DesktopStartupGate.completeFocusModeOnboarding",
                        message = "Desktop focus onboarding completed",
                        data = mapOf("preset" to inputState.selectedPreset.presetId),
                    )
                    callbacks.onCompleteFocusModeOnboarding(inputState.selectedPreset)
                    actions.onStatusMessageChanged("Desktop focus preset saved.")
                },
            ) {
                Text("Continue")
            }
            TextButton(
                modifier = Modifier.semantics { contentDescription = "Skip focus preset selection" },
                onClick = {
                    sessionLogger.i(
                        source = "DesktopStartupGate.skipFocusModeOnboarding",
                        message = "Desktop focus onboarding skipped",
                        data = mapOf("preset" to FocusModePreset.FULL_SUITE.presetId),
                    )
                    callbacks.onCompleteFocusModeOnboarding(FocusModePreset.FULL_SUITE)
                    actions.onStatusMessageChanged("Desktop focus preset kept at full suite.")
                },
            ) {
                Text("Skip for now")
            }
        }
    }
}

@Composable
private fun desktopStartupPassphraseCard(
    config: DesktopPassphraseCardConfig,
    passphrase: String,
    secondaryPassphrase: String = "",
    onPassphraseChanged: (String) -> Unit,
    onSecondaryPassphraseChanged: (String) -> Unit = {},
    onAction: () -> Unit,
) {
    desktopPassphraseActionCard(
        config = config,
        passphrase = passphrase,
        secondaryPassphrase = secondaryPassphrase,
        onPassphraseChanged = onPassphraseChanged,
        onSecondaryPassphraseChanged = onSecondaryPassphraseChanged,
        onAction = onAction,
    )
}

@Composable
private fun desktopForgotPassphraseCard(onReset: () -> Unit) {
    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Forgot passphrase?",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Reset the local desktop data and start fresh with a new passphrase if the current one is lost.",
                style = MaterialTheme.typography.body1,
                color = desktopBodyTextColor(),
            )
            TextButton(
                modifier = Modifier.semantics { contentDescription = "Reset desktop data action" },
                onClick = onReset,
            ) {
                Text("Forgot passphrase?")
            }
        }
    }
}

@Composable
private fun desktopFocusPresetCard(
    preset: FocusModePreset,
    isSelected: Boolean,
    onSelected: () -> Unit,
) {
    Card(
        backgroundColor = if (isSelected) desktopSelectedCardColor() else desktopSurfaceColor(),
        shape = RoundedCornerShape(18.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSelected)
                    .semantics { contentDescription = "Focus preset ${preset.presetId}" }
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = desktopFocusPresetTitle(preset),
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = desktopFocusPresetDescription(preset),
                style = MaterialTheme.typography.body2,
                color = desktopMutedTextColor(),
            )
            TextButton(
                modifier = Modifier.semantics { contentDescription = "Use focus preset ${preset.presetId}" },
                onClick = onSelected,
            ) {
                Text(if (isSelected) "Selected" else "Use this preset")
            }
        }
    }
}

@Composable
private fun desktopInitializeDatabaseCard(
    onImportLatest: () -> Unit,
    onInitialize: () -> Unit,
) {
    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Create local desktop database",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    "Finish the local-first setup by creating the desktop database for this installation before entering the app.",
                style = MaterialTheme.typography.body1,
                color = desktopBodyTextColor(),
            )
            Text(
                text =
                    "If you already exported a desktop handoff bundle, you can import it here and resume with the recovered startup state.",
                style = MaterialTheme.typography.body2,
                color = desktopMutedTextColor(),
            )
            Button(
                modifier = Modifier.semantics { contentDescription = "Import latest desktop handoff action" },
                onClick = onImportLatest,
            ) {
                Text("Import latest handoff")
            }
            Button(
                modifier = Modifier.semantics { contentDescription = "Initialize desktop database action" },
                onClick = onInitialize,
            ) {
                Text("Create database")
            }
        }
    }
}

@Composable
private fun desktopPassphraseActionCard(
    config: DesktopPassphraseCardConfig,
    passphrase: String,
    secondaryPassphrase: String = "",
    onPassphraseChanged: (String) -> Unit,
    onSecondaryPassphraseChanged: (String) -> Unit = {},
    onAction: () -> Unit,
) {
    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = config.title,
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            config.helperText?.let { helper ->
                Text(
                    text = helper,
                    style = MaterialTheme.typography.body2,
                    color = desktopMutedTextColor(),
                )
            }
            OutlinedTextField(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = config.fieldDescription },
                value = passphrase,
                onValueChange = onPassphraseChanged,
                label = { Text(config.fieldLabel) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            if (config.secondaryFieldLabel != null && config.secondaryFieldDescription != null) {
                OutlinedTextField(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = config.secondaryFieldDescription },
                    value = secondaryPassphrase,
                    onValueChange = onSecondaryPassphraseChanged,
                    label = { Text(config.secondaryFieldLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            Button(
                modifier = Modifier.semantics { contentDescription = config.actionLabel },
                enabled = passphrase.isNotBlank(),
                onClick = onAction,
            ) {
                Text(config.actionLabel)
            }
        }
    }
}

@Composable
private fun desktopDatabaseSummaryCard(
    title: String,
    databaseSnapshot: DesktopDatabaseSnapshot,
    hasPassphraseConfigured: Boolean,
) {
    Card(
        backgroundColor = desktopCardColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    if (databaseSnapshot.hasArtifacts) {
                        "A local database artifact already exists for this desktop installation."
                    } else {
                        "No desktop database has been created yet for this installation."
                    },
                style = MaterialTheme.typography.body2,
                color = desktopMutedTextColor(),
            )
            desktopSummaryRow(label = "Storage mode", value = if (hasPassphraseConfigured) "Encrypted startup gate" else "Not configured")
            desktopSummaryRow(label = "Database size", value = "${databaseSnapshot.databaseSizeKb} KB")
            desktopSummaryRow(
                label = "Last modified",
                value = desktopFormatTimestamp(databaseSnapshot.databaseLastModifiedMs),
            )
            Text(
                text = databaseSnapshot.databaseFilePath,
                style = MaterialTheme.typography.caption,
                color = desktopMutedTextColor(),
            )
        }
    }
}

@Composable
private fun desktopSummaryRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.body2,
            color = desktopMutedTextColor(),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal fun desktopStartupTitle(startupMode: DesktopStartupMode): String =
    when (startupMode) {
        DesktopStartupMode.SetupPassphrase -> "Set up local protection"
        DesktopStartupMode.UnlockPassphrase -> "Unlock local desktop session"
        DesktopStartupMode.InitializeDatabase -> "Create local data space"
        DesktopStartupMode.FocusModeSelection -> "Choose your focus mode"
        DesktopStartupMode.Ready -> "Desktop ready"
    }

internal fun desktopStartupSummary(startupMode: DesktopStartupMode): String =
    when (startupMode) {
        DesktopStartupMode.SetupPassphrase -> {
            "Create the desktop passphrase for this installation before continuing into the local-first app."
        }

        DesktopStartupMode.UnlockPassphrase -> {
            "Unlock the existing protected desktop installation to continue into the app."
        }

        DesktopStartupMode.InitializeDatabase -> {
            "Create the local desktop database so this installation can start working on its own."
        }

        DesktopStartupMode.FocusModeSelection -> {
            "Choose the initial navigation preset for this desktop installation before entering the app shell."
        }

        DesktopStartupMode.Ready -> {
            "Desktop startup checks are complete."
        }
    }

internal fun desktopSetupPassphraseMessage(result: DesktopPassphraseActionResult): String =
    when (result) {
        DesktopPassphraseActionResult.Success -> {
            "Passphrase saved. Startup can continue to local database setup."
        }

        is DesktopPassphraseActionResult.ValidationFailed -> {
            "Passphrase validation failed: ${result.reasonCode}"
        }

        is DesktopPassphraseActionResult.UnlockFailed -> {
            "Passphrase could not be stored. Attempts: ${result.failedAttempts}."
        }

        is DesktopPassphraseActionResult.Locked -> {
            "Desktop passphrase actions are locked for ${result.lockoutSecondsRemaining} seconds."
        }
    }

internal fun desktopUnlockPassphraseMessage(result: DesktopPassphraseActionResult): String =
    when (result) {
        DesktopPassphraseActionResult.Success -> {
            "Desktop session unlocked."
        }

        is DesktopPassphraseActionResult.ValidationFailed -> {
            "Desktop unlock validation failed: ${result.reasonCode}"
        }

        is DesktopPassphraseActionResult.UnlockFailed -> {
            "Desktop unlock failed. Attempts: ${result.failedAttempts}. Lockout: ${result.lockoutSecondsRemaining}s."
        }

        is DesktopPassphraseActionResult.Locked -> {
            "Desktop is locked. Wait ${result.lockoutSecondsRemaining} seconds before retrying."
        }
    }

internal fun desktopFormatTimestamp(timestampMillis: Long): String =
    if (timestampMillis <= 0L) {
        "-"
    } else {
        java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestampMillis))
    }

internal fun desktopFocusPresetTitle(preset: FocusModePreset): String =
    when (preset) {
        FocusModePreset.SIMPLE_TIME_HABITS -> "Time and habits"
        FocusModePreset.SIMPLE_JOURNAL -> "Journal and notes"
        FocusModePreset.SIMPLE_TASKS -> "Tasks only"
        FocusModePreset.FULL_SUITE -> "Full suite"
    }

internal fun desktopFocusPresetDescription(preset: FocusModePreset): String =
    when (preset) {
        FocusModePreset.SIMPLE_TIME_HABITS -> "Shows time, habits, lenses, and settings for a lighter daily flow."
        FocusModePreset.SIMPLE_JOURNAL -> "Shows journal, notes, lenses, and settings for reflection-first use."
        FocusModePreset.SIMPLE_TASKS -> "Shows tasks, lenses, and settings for focused task management."
        FocusModePreset.FULL_SUITE -> "Keeps the full desktop surface map visible, matching the complete Payanam experience."
    }
