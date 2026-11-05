//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.database.BiometricUnlockScreenActions
import io.payanam.shared.security.BiometricAvailability
import io.payanam.shared.security.BiometricUnavailableReason

/**
 * Android's half of the shared unlock screen's biometric slot.
 *
 * This is the `androidx.biometric` code the screen used to contain, unchanged: the same
 * [BiometricManager] capability check, the same enrollment `Intent` chain with its
 * `Settings.ACTION_*` fallbacks, the same two buttons with the same labels, the same enabling rules and
 * the same log lines. It left the shared file only because that file must compile for the JVM desktop,
 * and biometrics are aligned divergence #1 - an Android-only capability the desktop honestly lacks.
 *
 * The screen used to compute `canUseBiometric` as `biometricCanAuthenticate(context) ==
 * BIOMETRIC_SUCCESS`, and the same comparison is [canAuthenticate] here, re-evaluated on every
 * recomposition through a `get()` rather than computed once in a constructor: the sensor can start
 * reporting differently while this screen is composed, and the old code re-asked on every recomposition.
 *
 * @param logger the app's own logger, so these lines are the same ones the screen raised before the
 *   block moved.
 */
class AndroidBiometricUnlockScreenActions(
    private val context: Context,
    private val biometricPromptAvailable: Boolean,
    private val biometricEnabled: Boolean,
    private val onUnlockRequested: () -> Unit,
    private val logger: UnifiedLogger,
) : BiometricUnlockScreenActions {
    override val canAuthenticate: BiometricAvailability
        get() =
            if (biometricCanAuthenticate(context) == BiometricManager.BIOMETRIC_SUCCESS) {
                BiometricAvailability.Available
            } else {
                // "Not success" is deliberately collapsed to one reason here rather than mapped branch by
                // branch: the shared screen only needs the binary "is a strong biometric usable now"
                // answer, and the REASON is already reported honestly to the ViewModel by
                // [io.payanam.platform.AndroidBiometricUnlockPrompt], which reads the same
                // `BiometricManager` call and keeps the typed per-code reason. A vendor code does not
                // need to exist in shared code for the shared screen to behave correctly.
                BiometricAvailability.Unavailable(BiometricUnavailableReason.UNKNOWN)
            }

    override val actions: @Composable (isUnlocking: Boolean) -> Unit = { isUnlocking ->
        val canUseBiometric = canAuthenticate is BiometricAvailability.Available
        if (canUseBiometric && biometricEnabled) {
            TextButton(
                onClick = {
                    logger.i(
                        "DatabasePassphraseUnlockScreen.biometricAction",
                        "Biometric unlock action tapped",
                        mapOf(
                            "promptAvailable" to biometricPromptAvailable,
                            "isUnlocking" to isUnlocking,
                        ),
                    )
                    if (biometricPromptAvailable && !isUnlocking) {
                        onUnlockRequested()
                    } else if (isUnlocking) {
                        logger.w(
                            "DatabasePassphraseUnlockScreen.biometricAction",
                            "Biometric unlock action ignored: unlock already in progress",
                        )
                    } else if (!biometricPromptAvailable) {
                        logger.w(
                            "DatabasePassphraseUnlockScreen.biometricAction",
                            "Biometric unlock action ignored: this platform cannot show a prompt",
                        )
                    }
                },
                enabled = biometricPromptAvailable && !isUnlocking,
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.db_passphrase_unlock_biometric_action),
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
        if (!canUseBiometric) {
            TextButton(
                onClick = {
                    logger.i(
                        "DatabasePassphraseUnlockScreen.biometricSetup",
                        "Biometric setup action tapped",
                        mapOf(
                            "canAuthenticate" to canAuthenticate.toString(),
                            "biometricEnabledPreference" to biometricEnabled,
                        ),
                    )
                    openBiometricEnrollment(context)
                },
                enabled = !isUnlocking,
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(id = R.string.db_passphrase_unlock_biometric_setup_action),
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}

private fun biometricCanAuthenticate(context: Context): Int = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)

private fun openBiometricEnrollment(context: Context) {
    val logger = UnifiedLogger.getInstance()
    val intent = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Intent(Settings.ACTION_BIOMETRIC_ENROLL)

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
            @Suppress("DEPRECATION")
            Intent(Settings.ACTION_FINGERPRINT_ENROLL)

        else -> Intent(Settings.ACTION_SECURITY_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching {
        logger.i(
            "DatabasePassphraseUnlockScreen.openBiometricEnrollment",
            "Launching biometric enrollment/settings intent",
            mapOf("action" to (intent.action ?: "null")),
        )
        context.startActivity(intent)
    }.onFailure { firstError ->
        logger.w(
            "DatabasePassphraseUnlockScreen.openBiometricEnrollment",
            "Primary biometric enrollment intent failed; attempting generic settings fallback",
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
                fallbackError,
            )
        }
    }
}
