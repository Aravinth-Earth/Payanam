//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.notification.NotificationScheduler
import io.payanam.service.TrackingRestorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores active tracking notification after device boot.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var notificationScheduler: NotificationScheduler

    @Inject
    lateinit var trackingRestorer: TrackingRestorer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Restores tracking and re-arms reminders on boot and package replace; re-arms reminders only on a
     * clock or timezone change. Each step uses [runCatching] independently and the broadcast is held
     * with [goAsync] until both finish.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in RE_ARM_ACTIONS) return

        val logger = UnifiedLogger.getInstance()
        val restoresTracking = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED
        logger.i("BootReceiver.onReceive", "Handling $action")

        val pendingResult = goAsync()
        scope.launch {
            try {
                if (restoresTracking) {
                    runCatching {
                        trackingRestorer.restore(context)
                    }.onFailure { failure ->
                        logger.e("BootReceiver.onReceive", "Failed to restore tracking", failure)
                    }
                }

                runCatching {
                    reArmReminders(action)
                }.onFailure { failure ->
                    logger.e("BootReceiver.onReceive", "Failed to re-arm task reminders on $action", failure)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Re-arms every eligible task reminder; a closed session makes the repository read fail upward. */
    private suspend fun reArmReminders(action: String) {
        val logger = UnifiedLogger.getInstance()
        if (!FeatureFlags.remindersEnabled) {
            logger.i("BootReceiver.reArmReminders", "Skipped reminder schedule on $action; reminders disabled")
            return
        }
        if (action == Intent.ACTION_TIME_CHANGED || action == Intent.ACTION_TIMEZONE_CHANGED) {
            notificationScheduler.reArmPendingReminders()
        } else {
            notificationScheduler.scheduleAllPendingTasks()
        }
        logger.i("BootReceiver.reArmReminders", "Scheduled task reminders on $action")
    }

    companion object {
        /** Broadcasts after which no reminder alarm is armed, or the armed wall-clock time has moved. */
        private val RE_ARM_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
        )
    }
}
