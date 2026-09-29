//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.security.DatabaseEncryptionManager
import io.payanam.shared.security.BiometricAuthentication
import io.payanam.shared.security.BiometricAvailability
import io.payanam.shared.security.BiometricRefusalReason
import io.payanam.shared.security.BiometricUnavailableReason
import io.payanam.shared.security.BiometricUnlockPrompt
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android's [BiometricUnlockPrompt]: the one place in the codebase that names `BiometricPrompt`,
 * `BiometricManager`, `ContextCompat` and `FragmentActivity` for the unlock gate.
 *
 * WHY IT LIVES IN `:app` AND NOT IN `:core:database`, WHERE `AndroidPassphraseVault` LIVES: the vault's
 * platform type is `DatabaseEncryptionManager`, which is in `:core:database` and needs nothing outside it.
 * The biometric types are not that. `androidx.biometric` and `androidx.fragment` are declared ONLY in the
 * app module's build file, and a wave that puts this class in `:core:database` has to add them to that
 * module first. Until a build-file change is allowed, the class that needs them sits in the one module that
 * has them — which is still the correct dependency direction, since the contract it implements lives in
 * `:core:shared` and the app is downstream of everything. Moving it later is a file move and a package
 * rename, not a rewrite: the whole body is `androidx` and `DatabaseEncryptionManager` calls.
 *
 * WHY IT TAKES AN ACTIVITY AND THE SHARED LAYER DOES NOT: a `BiometricPrompt` must be shown from a
 * `FragmentActivity` to survive a rotation, and the platform has no other way to present it. So the activity
 * is a parameter of the ADAPTER and of the caller's request, never of the shared ViewModel's own state — the
 * shared layer names a port, and this is the object that answers it with a type the shared layer cannot see.
 *
 * WHICH REAL ERRORS IT MAPS, AND WHAT IT DOES WITH AN ERROR IT DOES NOT KNOW:
 *  - `canAuthenticate` success / no-hardware / none-enrolled / hardware-unavailable map to
 *    [BiometricAvailability.Available] and the three matching unavailable reasons.
 *  - Any OTHER `canAuthenticate` result maps to [BiometricUnavailableReason.UNKNOWN] with the raw code
 *    logged. That is the deliberate treatment of an unknown: NOT silently treated as success and not
 *    collapsed into "no hardware" — the raw code goes to the log, and the user is told the platform could not
 *    answer.
 *  - `ERROR_LOCKOUT` and `ERROR_LOCKOUT_PERMANENT` map to [BiometricRefusalReason.LOCKED_OUT], together on
 *    purpose: the permanent case carries no remaining time at all and the temporary one does not publish its
 *    duration, so a shared countdown would be showing a number the platform never promised. The shared layer
 *    honours the one fact both share — retrying will not work now — and routes the wait through the vault's
 *    passphrase lockout, which does publish a remaining time.
 *  - `ERROR_USER_CANCELED`, `ERROR_CANCELED` and `ERROR_NEGATIVE_BUTTON` map to
 *    [BiometricRefusalReason.CANCELLED]. A cancellation is not a failure and the shared layer does not
 *    report it as an error state.
 *  - `onAuthenticationFailed` is NOT an outcome. It is a single rejected sample while the prompt stays open
 *    and the system shows its own retry feedback, so it resolves nothing; treating it as a terminal failure
 *    would abort a prompt the user can still succeed at.
 *  - An `onAuthenticationError` code this adapter does not recognise maps to
 *    [BiometricRefusalReason.FAILED] and its code and message are logged. Never to success.
 *  - A success carrying no cipher maps to [BiometricRefusalReason.FAILED], not to a passphrase: the platform
 *    authenticated the user and the app still cannot open anything, and a fabricated passphrase would be an
 *    unlock that unlocks nothing.
 */
class AndroidBiometricUnlockPrompt(
    private val activity: FragmentActivity,
    private val encryptionManager: DatabaseEncryptionManager,
) : BiometricUnlockPrompt {
    private val logger = UnifiedLogger.getInstance()
    private val context = activity.applicationContext

    /**
     * Android asks the question the user set up: whether an enrolled BIOMETRIC_STRONG sensor can
     * authenticate right now. The user preference is read first and reported separately, because "you turned
     * this off" and "this phone cannot do it" are different sentences.
     */
    override fun availability(): BiometricAvailability {
        if (!encryptionManager.isBiometricUnlockEnabled()) {
            return BiometricAvailability.Unavailable(BiometricUnavailableReason.DISABLED_BY_PREFERENCE)
        }
        val result = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)
        logger.i("AndroidBiometricUnlockPrompt.availability", "Biometric capability checked", mapOf("canAuthenticateResult" to result))
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.Available
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                BiometricAvailability.Unavailable(BiometricUnavailableReason.NO_HARDWARE)

            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                BiometricAvailability.Unavailable(BiometricUnavailableReason.NOT_ENROLLED)

            else -> {
                // An unrecognised capability result. Logged with the raw code and reported as UNKNOWN: this
                // is the branch that keeps a future platform answer from being read as either success or
                // "this phone has no sensor".
                logger.w(
                    "AndroidBiometricUnlockPrompt.availability",
                    "Unrecognised biometric capability result; reporting the platform as unable to answer",
                    mapOf("canAuthenticateResult" to result),
                )
                BiometricAvailability.Unavailable(BiometricUnavailableReason.UNKNOWN)
            }
        }
    }

    /**
     * Shows the prompt, unwraps the stored passphrase with the OS-authenticated cipher, and hands it back.
     *
     * The cipher is obtained BEFORE the prompt is shown, so a key this device has already lost is reported
     * as a failed unwrap with nothing on screen rather than as a prompt the user completes and is then told
     * was pointless.
     */
    override suspend fun requestAuthentication(): BiometricAuthentication {
        val cipher =
            runCatching { encryptionManager.getCipherForBiometricUnlock() }.getOrElse { error ->
                logger.e(
                    "AndroidBiometricUnlockPrompt.requestAuthentication",
                    "Failed to initialise the cipher for biometric unlock",
                    error,
                )
                return BiometricAuthentication.Refused(BiometricRefusalReason.FAILED)
            }
        return suspendCancellableCoroutine { continuation ->
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), PromptCallback(continuation))
            // Registered after the prompt exists and before it is shown, so a scope cancelled during
            // show-up still dismisses the dialog rather than leaving it over a dead screen.
            continuation.invokeOnCancellation {
                runCatching { prompt.cancelAuthentication() }
                    .onFailure {
                        logger.w(
                            "AndroidBiometricUnlockPrompt.requestAuthentication",
                            "Failed to dismiss the biometric prompt after cancellation",
                            mapOf("error" to it.message),
                        )
                    }
            }
            prompt.authenticate(promptInfo(), BiometricPrompt.CryptoObject(cipher))
        }
    }

    private fun promptInfo(): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(io.payanam.R.string.db_passphrase_unlock_biometric_title))
            .setSubtitle(context.getString(io.payanam.R.string.db_passphrase_unlock_biometric_subtitle))
            .setNegativeButtonText(context.getString(io.payanam.R.string.db_passphrase_unlock_biometric_negative))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()

    /**
     * Resolves the continuation exactly once.
     *
     * A BiometricPrompt outlives configuration changes and can deliver a late callback after a cancellation,
     * so every terminal path goes through [resumeOnce]. Without it a cancelled scope that is later resumed
     * throws from `resume`, and the user is left with an unhandled failure in the log.
     */
    private inner class PromptCallback(
        private val continuation: CancellableContinuation<BiometricAuthentication>,
    ) : BiometricPrompt.AuthenticationCallback() {
        private var settled = false

        /** Unwraps the stored passphrase with the cipher the platform authenticated. */
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val authenticatedCipher = result.cryptoObject?.cipher
            if (authenticatedCipher == null) {
                logger.e(
                    "AndroidBiometricUnlockPrompt.onAuthenticationSucceeded",
                    "Biometric succeeded but the platform delivered no cipher",
                )
                resumeOnce(BiometricAuthentication.Refused(BiometricRefusalReason.FAILED))
                return
            }
            val passphrase =
                runCatching { encryptionManager.unwrapPassphraseWithCipher(authenticatedCipher) }.getOrElse { error ->
                    logger.e(
                        "AndroidBiometricUnlockPrompt.onAuthenticationSucceeded",
                        "Failed to unwrap the passphrase after biometric success",
                        error,
                    )
                    // The platform's key is gone. Not a biometric failure, and not something the caller can
                    // retry: the credential has to be re-established, which the shared layer routes to the
                    // vault rather than back to this prompt.
                    resumeOnce(BiometricAuthentication.Refused(BiometricRefusalReason.FAILED))
                    return
                }
            resumeOnce(BiometricAuthentication.Authenticated(passphrase))
        }

        override fun onAuthenticationError(
            errorCode: Int,
            errString: CharSequence,
        ) {
            logger.w(
                "AndroidBiometricUnlockPrompt.onAuthenticationError",
                "Biometric auth error",
                mapOf("errorCode" to errorCode, "errString" to errString.toString()),
            )
            val reason =
                when (errorCode) {
                    // Both lockout codes collapse to one reason on purpose: the permanent case reports no
                    // remaining time at all and the temporary one does not publish its duration, so a shared
                    // countdown would be showing a number the platform never promised. The shared layer honours
                    // the one fact they share and routes the wait through the vault's passphrase lockout.
                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                    -> BiometricRefusalReason.LOCKED_OUT

                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    -> BiometricRefusalReason.CANCELLED

                    else -> BiometricRefusalReason.FAILED
                }
            resumeOnce(BiometricAuthentication.Refused(reason))
        }

        /**
         * A single unrecognised sample. Resolves nothing: the prompt is still open and the system shows its
         * own retry feedback, so treating this as terminal would abort an attempt the user can still succeed.
         */
        override fun onAuthenticationFailed() {
            logger.w(
                "AndroidBiometricUnlockPrompt.onAuthenticationFailed",
                "Biometric sample not recognised; the prompt stays open",
            )
        }

        private fun resumeOnce(outcome: BiometricAuthentication) {
            if (settled) {
                return
            }
            settled = true
            if (continuation.isActive) {
                continuation.resume(outcome)
            }
        }
    }
}
