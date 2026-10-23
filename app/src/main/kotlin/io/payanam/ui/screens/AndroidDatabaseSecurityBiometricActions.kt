//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.ui.screens

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import io.payanam.R
import io.payanam.common.logging.UnifiedLogger
import io.payanam.core.ui.settings.DatabaseSecurityBiometricActions
import io.payanam.shared.security.BiometricAvailability
import io.payanam.shared.security.BiometricUnavailableReason

/**
 * Android's half of the shared database-security section's biometric slot.
 *
 * This is the `androidx.biometric` code the section used to contain, unchanged: the same
 * `BiometricManager` capability check, the same enrollment `Intent` chain with its `Settings.ACTION_*`
 * fallbacks, the same "set up biometric on device" button, and the same log lines. It left the shared
 * file only because that file must compile for the JVM desktop, and biometrics are aligned divergence #1 -
 * an Android-only capability the desktop honestly lacks.
 *
 * The enable verification is passed in as a callback rather than named here, because it belongs to
 * `SettingsViewModel`: it verifies the passphrase against the security manager, re-checks the sensor, runs
 * the `BiometricPrompt` and stores the wrapped material. What stays here is the two Android facts that
 * gate it - whether an enrolled strong biometric can authenticate now, and whether the host
 * `FragmentActivity` a prompt must be shown from is present.
 *
 * WHY THE HOST ACTIVITY IS RESOLVED ONCE IN THE CONSTRUCTOR: the old code walked the `ContextWrapper`
 * chain on every recomposition and each recomposition got to decide again whether a prompt could be
 * shown. Resolving it once means the shared section can ask the question and stay platform-free, and the
 * answer is the same for as long as the section is on screen.
 */
class AndroidDatabaseSecurityBiometricActions(
    private val context: Context,
    private val onEnableBiometricRequested: (FragmentActivity, String, (Boolean) -> Unit) -> Unit,
    private val logger: UnifiedLogger = UnifiedLogger.getInstance(),
) : DatabaseSecurityBiometricActions {
    private val hostActivity: FragmentActivity? = context.findFragmentActivity()

    /**
     * The sensor's own verdict. "Not success" collapses to one reason here rather than being mapped code by
     * code, for the reason [AndroidBiometricUnlockScreenActions] gives: the shared row needs the binary
     * "is a strong biometric usable now", and the typed reason is already reported honestly by
     * [io.payanam.platform.AndroidBiometricUnlockPrompt], which reads the same `BiometricManager` call.
     */
    override val availability: BiometricAvailability
        get() =
            if (BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
            ) {
                BiometricAvailability.Available
            } else {
                BiometricAvailability.Unavailable(BiometricUnavailableReason.UNKNOWN)
            }

    /** Whether an enable verification can be run: a `BiometricPrompt` needs the host activity. */
    override val canRequestEnable: Boolean
        get() = hostActivity != null

    override val enrollmentAction: @Composable () -> Unit = @Composable {
        TextButton(
            onClick = {
                logger.i(
                    "DatabaseSecurityPreferencesSection.biometricSetup",
                    "Biometric setup action tapped from Settings",
                    mapOf("canRequestEnable" to canRequestEnable),
                )
                openBiometricEnrollment(context, logger)
            },
        ) {
            Text(text = stringResource(id = R.string.db_passphrase_unlock_biometric_setup_action))
        }
    }

    override fun requestEnable(
        passphrase: String,
        onComplete: (Boolean) -> Unit,
    ) {
        val activity = hostActivity
        if (activity == null) {
            logger.w(
                "DatabaseSecurityPreferencesSection.biometricEnable",
                "Biometric enable verification blocked: host activity unavailable",
                mapOf("passphraseBlank" to passphrase.isBlank()),
            )
            onComplete(false)
            return
        }
        onEnableBiometricRequested(activity, passphrase, onComplete)
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

private fun openBiometricEnrollment(
    context: Context,
    logger: UnifiedLogger,
) {
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