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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.onboarding.OnboardingIntroScreen
import io.payanam.core.ui.preferences.AppPreferencesState
import io.payanam.shared.settings.FocusModePreset
import io.payanam.shared.startup.DesktopStartupSnapshot

private data class DesktopStartupGateInputState(
    val passphrase: String,
    val confirmPassphrase: String,
    val selectedPreset: FocusModePreset,
    /** SCAFFOLD: the editable dimension rows shown by the dimension-setup stage. */
    val dimensionInputs: List<DesktopDimensionInput>,
    val runtimeState: DesktopStartupRuntimeState,
    val databaseSnapshot: DesktopDatabaseSnapshot,
)

private data class DesktopStartupGateActions(
    val onPassphraseChanged: (String) -> Unit,
    val onConfirmPassphraseChanged: (String) -> Unit,
    val onStatusMessageChanged: (String) -> Unit,
    val onPassphraseCleared: () -> Unit,
    val onPresetSelected: (FocusModePreset) -> Unit,
    val onForgotResetRequested: () -> Unit,
    /** SCAFFOLD: leaves the onboarding intro for the database-choice stage. */
    val onOnboardingIntroAdvanced: () -> Unit = {},
    /** SCAFFOLD: records which data path the user chose and moves past onboarding. */
    val onDatabaseChoiceSelected: (DesktopDatabaseChoice) -> Unit = {},
    /** Security-scoped reset offered from the unavailable state: clears the passphrase only. */
    val onSecurityResetRequested: () -> Unit,
    /** Leaves the app from the unavailable state, which has no forward action by design. */
    val onQuitRequested: () -> Unit,
)

private data class DesktopPassphraseCardConfig(
    val title: String,
    val fieldLabel: String,
    val secondaryFieldLabel: String? = null,
    val fieldDescription: String,
    val secondaryFieldDescription: String? = null,
    val actionLabel: String,
    val helperText: String? = null,
)

@Composable
internal fun desktopStartupGateSurface(
    snapshot: DesktopStartupSnapshot,
    startupMode: DesktopStartupMode,
    runtimeState: DesktopStartupRuntimeState,
    databaseSnapshot: DesktopDatabaseSnapshot,
    /** SCAFFOLD: rows for the dimension-setup stage, built from the shared canonical catalog. */
    dimensionInputs: List<DesktopDimensionInput>,
    callbacks: DesktopShellCallbacks,
    sessionLogger: UnifiedLogger,
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

    // The onboarding intro is now the SHARED screen from :core:ui - the same composable Android renders.
    // It is a full-bleed three-slide pager, so it deliberately bypasses the gate's card chrome (header
    // card, scroll column, padding) rather than being nested inside it; that chrome is what made the
    // desktop intro look like a different product from the Android one.
    if (startupMode == DesktopStartupMode.OnboardingIntro) {
        // Dark, because this screen runs BEFORE unlock and receives no settings: the user's theme mode is
        // not an input here, and the gate has always rendered the desktop's dark appearance. Carrying the
        // preference in would mean passing it through the whole gate chain for a screen that shows one card.
        //
        // The language is the MACHINE locale, and the gate uses it in EVERY mode it renders - not only the
        // pre-unlock ones. Most modes genuinely cannot read a row (the encrypted database is closed), but
        // `InitializeDatabase` and `FocusModeSelection` are reached after the session-open check
        // (`resolveDesktopStartupMode`, in `DesktopStartupFlow.kt`) with the session open and rows
        // readable. Nothing shows the difference: this surface's own chrome is hardcoded English today.
        // Derived once so the root provider and this screen cannot disagree; the shell root reads the
        // shared rows instead, once the gate is gone.
        val machineLanguage = desktopMachineUiLanguage()
        desktopRootSurface(darkTheme = true, language = machineLanguage, preferences = AppPreferencesState(isLoading = false)) {
            OnboardingIntroScreen(language = machineLanguage, onFinished = callbacks.onOnboardingIntroAdvanced)
        }
        return
    }

    // Dark for the same reason as the onboarding branch above: this screen runs before unlock and has no
    // settings input, so the theme-mode preference is not available to it. The language is the machine
    // locale for the same reason it is above: the gate uses it in every mode, and although
    // `InitializeDatabase` and `FocusModeSelection` reach this surface with the session open and the rows
    // readable, the chrome around them is hardcoded English today, so nothing renders the difference.
    desktopRootSurface(darkTheme = true, language = desktopMachineUiLanguage(), preferences = AppPreferencesState(isLoading = false)) {
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
                        dimensionInputs = dimensionInputs,
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
                        onOnboardingIntroAdvanced = callbacks.onOnboardingIntroAdvanced,
                        onDatabaseChoiceSelected = callbacks.onDatabaseChoiceSelected,
                        onSecurityResetRequested = callbacks.onSecurityResetRequested,
                        onQuitRequested = callbacks.onQuitRequested,
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
    sessionLogger: UnifiedLogger,
    actions: DesktopStartupGateActions,
) {
    when (startupMode) {
        DesktopStartupMode.OnboardingIntro -> {
            Unit
        }

        // full-bleed, rendered above by the shared :core:ui screen

        DesktopStartupMode.OnboardingDatabaseChoice -> {
            desktopOnboardingDatabaseChoiceSection(actions = actions)
        }

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

        DesktopStartupMode.SecurityStateUnavailable -> {
            desktopSecurityUnavailableSection(actions = actions)
        }
    }
}

/**
 * SCAFFOLD stage 2 of onboarding: new empty space, or bring the Android export in.
 *
 * Both choices currently lead to the same passphrase stage. The labels describe the real behaviour so
 * the flow reads correctly, and the
 * DIFFERENCE is carried through [DesktopDatabaseChoice] rather than being collapsed here - otherwise
 * wiring the real import later would have nothing to hook into.
 */
@Composable
private fun desktopOnboardingDatabaseChoiceSection(actions: DesktopStartupGateActions) {
    Card(
        backgroundColor = desktopSurfaceColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Start fresh",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Creates a new encrypted database and asks you to set its passphrase.",
                style = MaterialTheme.typography.body1,
                color = desktopBodyTextColor(),
            )
            Button(
                modifier =
                    Modifier.semantics { contentDescription = "Create a new empty database" },
                onClick = { actions.onDatabaseChoiceSelected(DesktopDatabaseChoice.CreateNew) },
            ) {
                Text("Create new empty database")
            }

            Text(
                text = "Bring your Android data",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    "Uses the encrypted database exported from the Android app. You will pick the file " +
                        "and enter its passphrase, and the app checks the pair before adopting it.",
                style = MaterialTheme.typography.body1,
                color = desktopBodyTextColor(),
            )
            Text(
                text =
                    "Exports written by a newer or much older app version are refused with an " +
                        "explanation rather than opened.",
                style = MaterialTheme.typography.body2,
                color = desktopBodyTextColor(),
            )
            OutlinedButton(
                modifier =
                    Modifier.semantics { contentDescription = "Import an existing database" },
                onClick = { actions.onDatabaseChoiceSelected(DesktopDatabaseChoice.ImportExisting) },
            ) {
                Text("Continue with existing database")
            }
        }
    }
}

/**
 * Parses `#RRGGBB` (with or without the leading `#`) into a Compose [Color], or null.
 *
 * Null rather than a thrown exception: the value crosses from a shared catalog into UI, and a bad
 * string there is a display problem, not a crash.
 */
internal fun parseDimensionHexColor(hex: String): Color? {
    val normalized = hex.trim().removePrefix("#")
    if (normalized.length != HEX_RGB_LENGTH) return null
    val parsed = normalized.toLongOrNull(radix = HEX_RADIX) ?: return null
    // Opaque alpha in the high byte, so a parsed RGB arrives fully opaque rather than transparent.
    return Color(OPAQUE_ALPHA_MASK or parsed)
}

/** `#RRGGBB` is six hex digits. */
private const val HEX_RGB_LENGTH = 6

/** Alpha byte forced opaque: `0xFF000000`. */
private const val OPAQUE_ALPHA_MASK = 0xFF000000L

/** Base 16, named so the parse above reads without a bare literal. */
private const val HEX_RADIX = 16

@Composable
private fun desktopSecurityUnavailableSection(actions: DesktopStartupGateActions) {
    Card(
        backgroundColor = desktopSurfaceColor(),
        shape = RoundedCornerShape(20.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "What you can do",
                style = MaterialTheme.typography.h6,
                fontWeight = FontWeight.SemiBold,
            )
            // Names what is lost and what is preserved, and deliberately names no file path.
            Text(
                text =
                    "Resetting clears the local passphrase and its lockout state only - your tasks, " +
                        "journal, notes and settings are not touched.",
                style = MaterialTheme.typography.body1,
                color = desktopBodyTextColor(),
            )
            Button(
                modifier =
                    Modifier.semantics { contentDescription = "Reset local security state action" },
                onClick = actions.onSecurityResetRequested,
            ) {
                Text("Reset local security state")
            }
            TextButton(
                modifier = Modifier.semantics { contentDescription = "Quit the application" },
                onClick = actions.onQuitRequested,
            ) {
                Text("Quit")
            }
        }
    }
}

@Composable
private fun desktopSetupPassphraseSection(
    inputState: DesktopStartupGateInputState,
    callbacks: DesktopShellCallbacks,
    sessionLogger: UnifiedLogger,
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
                sessionLogger.w(
                    source = "DesktopStartupGate.setupPassphrase",
                    message = "Passphrase confirmation does not match",
                    data = emptyMap(),
                )
                actions.onStatusMessageChanged("Passphrase confirmation does not match.")
                return@desktopStartupPassphraseCard
            }
            sessionLogger.i(
                source = "DesktopStartupGate.setupPassphrase",
                message = "Desktop passphrase setup requested",
                data =
                    mapOf(
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
    sessionLogger: UnifiedLogger,
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
                data = emptyMap(),
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
    sessionLogger: UnifiedLogger,
    actions: DesktopStartupGateActions,
) {
    desktopDatabaseSummaryCard(
        title = "Desktop database setup",
        databaseSnapshot = inputState.databaseSnapshot,
        hasPassphraseConfigured = inputState.runtimeState.hasPassphraseConfigured,
    )
    desktopInitializeDatabaseCard(
        onImportDatabase = {
            sessionLogger.i(
                source = "DesktopStartupGate.importLatestDesktopState",
                message = "Desktop startup import requested",
            )
            // The real import: pick the file, verify the passphrase against it, and only then replace
            // anything. On a fresh install there is no database to open, so this is the step that brings one
            // in - and the verification happens BEFORE a single byte is copied.
            callbacks.onImportDatabase()
            actions.onStatusMessageChanged(
                "Choose the database file exported from the Android app. Nothing is replaced until it opens " +
                    "with your passphrase.",
            )
        },
        onInitialize = {
            sessionLogger.i(
                source = "DesktopStartupGate.initializeDatabase",
                message = "Desktop database initialization requested",
                data = mapOf("hasDatabaseArtifacts" to inputState.runtimeState.hasDatabaseArtifacts),
            )
            callbacks.onInitializeDatabase()
            // The REAL outcome of the create, from the same handler above. This used to publish an
            // unconditional "Desktop database initialized." on a button press that had done nothing at
            // all - the one thing a user must never be told about a database they do not have. A
            // caller that supplies no outcome publisher (a UI test, rendering the card with a stub)
            // simply publishes nothing rather than a success it cannot vouch for.
            callbacks.onInitializeDatabaseOutcome?.invoke()?.let(actions.onStatusMessageChanged)
        },
    )
}

@Composable
private fun desktopFocusModeSelectionSection(
    inputState: DesktopStartupGateInputState,
    callbacks: DesktopShellCallbacks,
    sessionLogger: UnifiedLogger,
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
    onImportDatabase: () -> Unit,
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
                    "If you already use the Android app, export its database there and import that file here: " +
                        "the two apps read the same encrypted file, so this is how this installation gets your data.",
                style = MaterialTheme.typography.body2,
                color = desktopMutedTextColor(),
            )
            Button(
                modifier = Modifier.semantics { contentDescription = "Import database from Android action" },
                onClick = onImportDatabase,
            ) {
                Text("Import database")
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
        DesktopStartupMode.OnboardingIntro -> "Welcome to Payanam"
        DesktopStartupMode.OnboardingDatabaseChoice -> "Where should your data come from?"
        DesktopStartupMode.SetupPassphrase -> "Set up local protection"
        DesktopStartupMode.UnlockPassphrase -> "Unlock local desktop session"
        DesktopStartupMode.InitializeDatabase -> "Create local data space"
        DesktopStartupMode.FocusModeSelection -> "Choose your focus mode"
        DesktopStartupMode.Ready -> "Desktop ready"
        DesktopStartupMode.SecurityStateUnavailable -> "Security state unreadable"
    }

internal fun desktopStartupSummary(startupMode: DesktopStartupMode): String =
    when (startupMode) {
        DesktopStartupMode.OnboardingIntro -> {
            "Payanam is a local-first life tracker. Nothing leaves this machine, and your database is " +
                "encrypted with a passphrase only you know."
        }

        DesktopStartupMode.OnboardingDatabaseChoice -> {
            "Start a new empty space, or bring in the encrypted database exported from the Android app. " +
                "Both paths end in the same encrypted database."
        }

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

        DesktopStartupMode.SecurityStateUnavailable -> {
            "Payanam cannot read its local passphrase configuration. Your data has not been changed."
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

        DesktopPassphraseActionResult.Unavailable -> {
            "The local security state could not be read, so the passphrase was not saved."
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

        DesktopPassphraseActionResult.Unavailable -> {
            "The local security state could not be read, so the passphrase was not checked."
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
