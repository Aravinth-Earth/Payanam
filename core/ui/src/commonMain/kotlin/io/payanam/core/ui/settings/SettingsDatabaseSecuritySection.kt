//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("ktlint:standard:function-naming")

package io.payanam.core.ui.settings

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.payanam.core.ui.text.LocalUiStrings
import io.payanam.core.ui.text.UiStrings
import io.payanam.core.ui.text.formatArgs
import io.payanam.presentation.settings.SettingsUiState
import io.payanam.shared.logging.AppLogger
import io.payanam.shared.security.BiometricAvailability

/**
 * The biometric half of the database-security settings section, supplied by the platform.
 *
 * WHY THIS SLOT IS NULLABLE AND WHAT `null` MEANS: a `null` [biometricActions] is not "the sensor is off"
 * and not "the user has not chosen yet" - it is "this platform has no OS identity check to offer at all",
 * and the section then renders NO biometric control: no row, no switch, no enrollment action, and no
 * enable dialog. A biometric switch that saves a preference on a platform where nothing will ever verify
 * the user would claim a security property this platform does not have, which is the one outcome the
 * project forbids; there is no no-op implementation of this interface anywhere, so a caller cannot wire
 * one by accident. The shape follows [io.payanam.core.ui.database.BiometricUnlockScreenActions], the
 * already-landed precedent for the same question asked on the unlock gate.
 *
 * WHAT IS AND IS NOT IN HERE. Only the three genuinely platform operations are: asking the platform
 * about the sensor, rendering the platform's own enrollment affordance, and running the enable
 * verification. Everything else - the dialog, the passphrase field, the reveal toggle, the refusal copy -
 * stayed in this file, because it is a sentence and a keystroke, not a capability. The enrollment
 * affordance is a slot rather than data because it is a platform Material button that starts an OS
 * screen; re-expressing it as a title plus an onClick would put a second, drifting copy of that styling
 * here.
 */
interface DatabaseSecurityBiometricActions {
    /**
     * The sensor's OWN verdict, kept separate from the user's preference on purpose.
     *
     * This is the hardware question alone - "is an enrolled strong biometric usable right now" - and not
     * `BiometricUnlockPrompt.availability()`, which folds the user's own preference into its answer. A
     * settings row that asked the combined question would hide the row exactly when the user most needs
     * it, because the preference is off in every fresh install.
     */
    val availability: BiometricAvailability

    /**
     * Whether this platform can actually run the enable verification: Android needs the host
     * `FragmentActivity` a `BiometricPrompt` must be shown from, and a session that has no such host
     * cannot show the dialog's own success.
     */
    val canRequestEnable: Boolean

    /**
     * The enrollment affordance, drawn by the platform. Rendered only while [availability] is NOT
     * [BiometricAvailability.Available], so a platform that has an enrolled sensor shows no "set up"
     * button - which is what `:app` did before this moved.
     */
    val enrollmentAction: @Composable () -> Unit

    /**
     * Verifies [passphrase] and runs the platform's prompt, resolving with whether biometric unlock is
     * now on. Resolves EXACTLY ONCE, on a terminal event; never resolves `true` without a real
     * verification having happened.
     */
    fun requestEnable(
        passphrase: String,
        onComplete: (Boolean) -> Unit,
    )
}

/**
 * Database-security settings section: the unlock session timeout and the biometric unlock control.
 *
 * Moved here from `:app` with its rows, order, copy and behaviour unchanged, so the desktop renders the
 * same security pane rather than a lookalike.
 *
 * WHY THREE CALLBACKS AND NOT ONE: they are three different operations on two different stores. The
 * timeout writes a session-length preference and re-reads the value the security manager clamped it to;
 * disabling biometric unlock DESTROYS the platform-wrapped key material and then flips the preference;
 * enabling biometric unlock first verifies the passphrase, then authenticates the user, then stores
 * fresh wrapped material. Only the last two share a preference, and collapsing them would hide that one
 * of them is a teardown.
 *
 * WHY THE TIMEOUT'S NINE CHOICES ARE NOT NINE CALLBACKS: they are nine values of ONE write, so they
 * share a single callback and a single list - [UNLOCK_TIMEOUT_CHOICES] is what the menu draws AND what the
 * write reports, and both the read and the write format through [formatUnlockTimeoutLabel]. A row
 * therefore cannot show one preference and write another, which is the seam a nine-callback shape would
 * open.
 *
 * NOTHING HERE IS PLATFORM-BOUND: every `android.*`, `androidx.biometric`, `androidx.fragment` and
 * `stringResource` call left with the platform half of the file. Copy comes from [LocalUiStrings] - all
 * 15 keys this section read already ship in `:core:strings` with EN+TA parity, so no string was added.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDatabaseSecuritySection(
    uiState: SettingsUiState,
    onUpdateUnlockTimeout: (Int) -> Unit,
    onDisableBiometricEnabled: () -> Unit,
    onEnableBiometricRequested: (String, (Boolean) -> Unit) -> Unit,
    biometricActions: DatabaseSecurityBiometricActions? = null,
) {
    val strings = LocalUiStrings.current
    val logger = remember { AppLogger.current }
    var unlockTimeoutExpanded by remember { mutableStateOf(false) }
    var showEnableBiometricDialog by remember { mutableStateOf(false) }
    var enableBiometricPassphrase by remember { mutableStateOf("") }
    var showEnableBiometricPassphrase by remember { mutableStateOf(false) }
    var showEnableBiometricPassphraseError by remember { mutableStateOf(false) }
    val biometricAvailability = biometricActions?.availability
    val biometricCanAuthenticate = biometricAvailability is BiometricAvailability.Available
    Text(
        text = strings.settingsDbUnlockTimeoutTitle,
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
            value = formatUnlockTimeoutLabel(uiState.unlockSessionTimeoutMinutes, strings),
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unlockTimeoutExpanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        DropdownMenu(
            expanded = unlockTimeoutExpanded,
            onDismissRequest = { unlockTimeoutExpanded = false },
        ) {
            UNLOCK_TIMEOUT_CHOICES.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(formatUnlockTimeoutLabel(minutes, strings)) },
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
        text = strings.settingsDbUnlockTimeoutHelp,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // A platform with no OS identity check renders NO biometric control at all, rather than a switch that
    // would save a preference nothing could ever honour.
    if (biometricActions != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.dbPassphraseUnlockBiometricTitle,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text =
                        if (biometricCanAuthenticate) {
                            strings.settingsDbBiometricUnlockHelp
                        } else {
                            strings.settingsDbBiometricUnlockUnavailableHelp
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.biometricUnlockEnabled,
                enabled = biometricCanAuthenticate,
                // The title above is a SIBLING of this switch, not part of it, so the merged node a screen
                // reader reaches carries the switch's on/off state and nothing else - it announced as an
                // unlabelled toggle on the row whose whole subject is whether the database needs an identity
                // check to open. The description is read from the copy table rather than restated, so the
                // announcement cannot disagree with the label the user can see; there is deliberately no merge,
                // because a merge boundary moves the switch out of the unmerged semantics tree.
                modifier =
                    Modifier.semantics {
                        contentDescription = strings.dbPassphraseUnlockBiometricTitle
                    },
                onCheckedChange = { enabled ->
                    logger.i(
                        "DatabaseSecurityPreferencesSection.biometric",
                        "Biometric toggle changed",
                        mapOf(
                            "requestedEnabled" to enabled,
                            "biometricAvailable" to biometricCanAuthenticate,
                            "hostActivityPresent" to biometricActions.canRequestEnable,
                        ),
                    )
                    if (!enabled) {
                        onDisableBiometricEnabled()
                        logger.d(
                            "DatabaseSecurityPreferencesSection.biometric",
                            "Biometric unlock disabled from settings",
                            mapOf("enabled" to false),
                        )
                    } else if (biometricActions.canRequestEnable) {
                        logger.i(
                            "DatabaseSecurityPreferencesSection.biometricEnable",
                            "Biometric enable requested from settings; collecting passphrase",
                            mapOf("availability" to (biometricAvailability.toString())),
                        )
                        showEnableBiometricDialog = true
                    } else {
                        logger.w(
                            "DatabaseSecurityPreferencesSection.biometricEnable",
                            "Biometric enable verification blocked: host activity unavailable",
                            mapOf("availability" to (biometricAvailability.toString())),
                        )
                    }
                },
            )
        }
        if (!biometricCanAuthenticate) {
            biometricActions.enrollmentAction()
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
            title = { Text(text = strings.dbPassphraseUnlockBiometricEnableAction) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = strings.settingsBiometricEnablePassphraseDesc,
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
                        label = { Text(text = strings.dbPassphraseChangeCurrentLabel) },
                        visualTransformation =
                            if (showEnableBiometricPassphrase) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                        trailingIcon = {
                            IconButton(onClick = { showEnableBiometricPassphrase = !showEnableBiometricPassphrase }) {
                                Icon(
                                    imageVector =
                                        if (showEnableBiometricPassphrase) {
                                            Icons.Default.VisibilityOff
                                        } else {
                                            Icons.Default.Visibility
                                        },
                                    // The SAME two keys every other reveal toggle in this module already
                                    // announces (see `SettingsDialogs`' passphrase gate). A trailing icon
                                    // whose whole content is an eye is otherwise an unnamed button: a screen
                                    // reader says only "button", and the two words that tell the user this
                                    // is the passphrase field - and which direction the press goes - exist
                                    // nowhere in the tree. The icon CARRIES the description rather than the
                                    // button, matching the shape the other ten call sites use, so this one
                                    // cannot drift from them by taking a different route.
                                    contentDescription =
                                        if (showEnableBiometricPassphrase) {
                                            strings.dbPassphraseHideToggle
                                        } else {
                                            strings.dbPassphraseShowToggle
                                        },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (showEnableBiometricPassphraseError) {
                        Text(
                            text = strings.settingsBiometricEnablePassphraseError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val passphrase = enableBiometricPassphrase
                        val canRequestEnable = biometricActions?.canRequestEnable == true
                        if (!canRequestEnable || passphrase.isBlank()) {
                            logger.w(
                                "DatabaseSecurityPreferencesSection.biometricEnable",
                                "Biometric enable blocked: missing host activity or passphrase",
                                mapOf(
                                    "hostActivityPresent" to canRequestEnable,
                                    "passphraseBlank" to passphrase.isBlank(),
                                ),
                            )
                            showEnableBiometricPassphraseError = true
                            return@Button
                        }
                        logger.i(
                            "DatabaseSecurityPreferencesSection.biometricEnable",
                            "Submitting biometric enable verification request",
                        )
                        onEnableBiometricRequested(passphrase) { success ->
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
                    Text(text = strings.settingsBiometricEnablePassphraseConfirm)
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
                    Text(text = strings.settingsActionCancel)
                }
            },
        )
    }
}

/**
 * The unlock session timeouts the menu offers, in minutes.
 *
 * ONE list, read and write alike: the menu draws these values and the write reports these values, so a
 * row that displays one preference cannot write another. A second list for the display would be the only
 * way for that to diverge.
 */
// The numbers ARE the choices - naming nine constants would add nine names that say nothing the values and
// the KDoc above do not. Suppressed on this declaration rather than for the whole file, so the next
// un-named literal anywhere else in this section is still caught.
@Suppress("MagicNumber")
internal val UNLOCK_TIMEOUT_CHOICES = listOf(1, 2, 5, 10, 15, 30, 60, 120, 240)

/**
 * Formats a timeout in minutes the way the read and the write must agree on: plain minutes below an
 * hour, whole hours on the hour, and hours with the remainder above it.
 */
internal fun formatUnlockTimeoutLabel(
    minutes: Int,
    strings: UiStrings,
): String {
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    return when {
        hours == 0 -> strings.settingsDbUnlockTimeoutValueMinutes.formatArgs(minutes)
        remainingMinutes == 0 -> strings.settingsDbUnlockTimeoutValueHours.formatArgs(hours)
        else -> strings.settingsDbUnlockTimeoutValueHoursMinutes.formatArgs(hours, remainingMinutes)
    }
}
