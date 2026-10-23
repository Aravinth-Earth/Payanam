//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.platform

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import io.payanam.BuildConfig
import io.payanam.PayanamApp
import io.payanam.common.logging.UnifiedLogger
import io.payanam.service.AutoBackupWorker
import io.payanam.shared.platform.BackupBackgroundCapability
import io.payanam.shared.platform.BackupScheduler
import io.payanam.shared.platform.DebugFlags
import io.payanam.shared.platform.HapticFeedback
import io.payanam.shared.platform.HapticSink
import io.payanam.shared.platform.PlatformCapabilities
import io.payanam.shared.platform.SupportState

// Vibration durations in milliseconds. Short by design: this is touch feedback, not an alert.
private const val HAPTIC_TAP_MS = 10L
private const val HAPTIC_SELECTION_MS = 15L
private const val HAPTIC_CONFIRMATION_MS = 30L
private const val HAPTIC_REJECTION_MS = 40L

/**
 * Android's implementations of the platform seams.
 *
 * Each of these is a delegation with the platform's OWN answer to "can I do this at all", which is the only
 * thing that should differ between the platforms. The reminder sink is deliberately absent: Android's
 * reminders are DERIVED from a task (the scheduler works out when to fire from the task's due date and the
 * user's advance setting) rather than supplied as a time, so a generic sink cannot be a faithful wrapper for
 * it. Wiring it is part of moving the notification call sites, not of defining the seam.
 */
class AndroidBackupScheduler(
    private val context: Context,
) : BackupScheduler {
    /**
     * Android's schedule survives the app being closed, which is a real capability difference from the
     * desktop's in-process timer, not an implementation detail.
     */
    val capability: BackupBackgroundCapability = BackupBackgroundCapability.BACKGROUND_CAPABLE

    /** The interval last reconciled; null when no backup is scheduled. */
    var intervalMinutes: Int? = null
        private set

    override suspend fun schedule(intervalMinutes: Int) {
        require(intervalMinutes > 0) { "Backup interval must be positive; use cancel() to disable" }
        AutoBackupWorker.schedule(context, intervalMinutes.toLong())
        this.intervalMinutes = intervalMinutes
    }

    override suspend fun cancel() {
        AutoBackupWorker.cancel(context)
        intervalMinutes = null
    }
}

/**
 * The debug-logging switch.
 *
 * Android's default follows its own build flag: a debug build logs by default, a release build does not.
 * That default is the platform's business and deliberately not the shared contract's.
 */
class AndroidDebugFlags(
    initial: Boolean = BuildConfig.DEBUG,
) : DebugFlags {
    override var debugLoggingEnabled: Boolean = initial
        private set

    override fun setDebugLoggingEnabled(enabled: Boolean): Boolean {
        debugLoggingEnabled = enabled
        // The logger's own switch is the one the 184 call sites in :core:database consult, so it moves with
        // this setting rather than the setting existing on its own.
        UnifiedLogger.setDebugLoggingEnabled(enabled)
        return debugLoggingEnabled
    }
}

/**
 * Device vibration, where the device has a vibrator.
 *
 * Best-effort by contract: a device without a vibrator, or with haptics turned off system-wide, is a normal
 * configuration and must never surface as an error.
 */
class AndroidHapticSink(
    context: Context,
) : HapticSink {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator?.takeIf { it.hasVibrator() }
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
        }

    override val isActive: Boolean get() = vibrator != null

    override fun perform(feedback: HapticFeedback) {
        val device = vibrator ?: return
        try {
            device.vibrate(durationFor(feedback))
        } catch (_: SecurityException) {
            // Vibration can be refused (a missing permission, a restricted profile). Silently staying
            // still is the correct outcome for decorative feedback.
        }
    }

    private fun durationFor(feedback: HapticFeedback): Long = when (feedback) {
        HapticFeedback.TAP -> HAPTIC_TAP_MS
        HapticFeedback.SELECTION -> HAPTIC_SELECTION_MS
        HapticFeedback.CONFIRMATION -> HAPTIC_CONFIRMATION_MS
        HapticFeedback.REJECTION -> HAPTIC_REJECTION_MS
    }
}

/**
 * What this Android device can do. Read once at startup and handed to the shared UI.
 *
 * The reminders row is DERIVED from this device's notification state, never asserted. Android has no
 * [ReminderSink] to ask, and inventing one to ask would be the very lie the vocabulary exists to prevent:
 * the honest question is not "does this platform have the concept" (it does, and always does) but "will
 * anything reach the user on THIS device" — which is a runtime permission and channel answer, not a
 * build-time one. `SUPPORTED` means a notification can actually be posted right now; anything less reports
 * the state the user would see, so shared UI can explain the reminder toggle rather than offer a control
 * that posts into a disabled channel.
 */
fun androidCapabilities(context: Context): PlatformCapabilities {
    val haptics = AndroidHapticSink(context)
    return PlatformCapabilities(
        // The sensor is a device question; the state says whether offering biometrics is meaningful here.
        biometrics =
        if (hasBiometricHardware(context)) SupportState.SUPPORTED else SupportState.NOT_APPLICABLE,
        supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        supportsHaptics = haptics.isActive,
        reminders = androidReminderSupport(context),
        // `WorkManager` keeps the schedule after the app is closed.
        backupBackground = SupportState.SUPPORTED,
    )
}

/**
 * What this device's notification state actually permits, in the shared vocabulary.
 *
 * Two probes, both read from the platform's own [NotificationManager] rather than from a constant:
 *
 * - the app-wide switch (`areNotificationsEnabled`), which on API 33+ also carries the runtime
 *   `POST_NOTIFICATIONS` grant, so a user who declined the prompt lands here and is told so;
 * - the reminders channel's own importance, which is how a user silences reminders while leaving other
 *   notifications alone. The channel is registered by [PayanamApp.createNotificationChannels] at startup, so
 *   its absence means the reminders path has not been set up on this install — not that reminders work.
 *
 * NOT_APPLICABLE is deliberately NOT used for the blocked case: Android does have the concept and does
 * accept the reminder, it cannot currently SHOW it, which is [SupportState.DECLINES]'s meaning — the call
 * is accepted and the device is refusing on purpose. `NOT_SURFACED` would claim the sink merely has nothing
 * to show yet, which is the desktop's position, not this one.
 *
 * Below API 26 there is no channel, so the app-wide switch is the whole answer; this project's `minSdk`
 * is 28, so the branch exists for the contract rather than for a device anyone ships to.
 */
private fun androidReminderSupport(context: Context): SupportState {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return SupportState.DECLINES
    if (!manager.areNotificationsEnabled()) return SupportState.DECLINES
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // Channel state is only readable from 26. `getNotificationChannel` returns null for a channel the
        // user deleted from system settings, which is a refusal to show rather than an absent feature.
        val channel = manager.getNotificationChannel(PayanamApp.CHANNEL_TASK_REMINDERS) ?: return SupportState.DECLINES
        if (channel.importance == NotificationManager.IMPORTANCE_NONE) return SupportState.DECLINES
    }
    return SupportState.SUPPORTED
}

private fun hasBiometricHardware(context: Context): Boolean = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT) ||
    context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FACE)
