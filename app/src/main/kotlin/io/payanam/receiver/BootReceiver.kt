//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.notification.NotificationScheduler
import io.payanam.service.TrackingService
import io.payanam.widget.TimeTrackingWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores active tracking notification after device boot.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var timeEntryRepository: TimeEntryRepository

    @Inject
    lateinit var notificationScheduler: NotificationScheduler

    @Inject
    lateinit var sessionManager: DatabaseSessionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Restores tracking and re-arms reminders on boot and on package replace; re-arms reminders only on
     * a clock or timezone change.
     *
     * Boot and package replace both leave no armed alarms, so both run the same restore path. Each step
     * has its own [runCatching] so a failure in one cannot skip the rest, and the tracking restore is
     * skipped explicitly when the database session is closed instead of throwing into a shared catch
     * that would starve the reminder re-arm. The broadcast is held with [goAsync] until both steps
     * finish, so the process is not reclaimed mid-work.
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
                        restoreTracking(context)
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

    /**
     * Restores the active tracking notification and widget after boot or package replace, or the widget
     * alone when the database session is still closed.
     */
    private suspend fun restoreTracking(context: Context) {
        val logger = UnifiedLogger.getInstance()
        if (!sessionManager.isDbOpen()) {
            logger.d("BootReceiver.restoreTracking", "Skipping tracking restore; database session is closed")
            TimeTrackingWidgetProvider.requestUpdate(context)
            return
        }
        // Check for active time entries
        val activeEntries = timeEntryRepository.getActiveTimeEntries().first()
        if (activeEntries.isEmpty()) {
            logger.d("BootReceiver.restoreTracking", "No active entries to restore")
            TimeTrackingWidgetProvider.requestUpdate(context)
            return
        }
        // Restore the most recent active entry's notification
        val entry = activeEntries.first()

        logger.i(
            "BootReceiver.restoreTracking",
            "Restoring active tracking notification",
            mapOf(
                "entryId" to entry.id,
                "dimensionId" to (entry.dimensionId ?: "unknown"),
            ),
        )

        TrackingService.startTracking(
            context = context,
            taskId = entry.taskId,
            taskTitle = entry.lifeIntentionCategory,
            dimension = entry.lifeIntentionCategory,
            startTime = entry.startedAt.toString(),
        )
        TimeTrackingWidgetProvider.requestUpdate(context)
        logger.i("BootReceiver.restoreTracking", "Requested widget refresh after restoring active tracking")
    }

    /** Re-arms every eligible task reminder; a closed session makes the repository read fail upward. */
    private suspend fun reArmReminders(action: String) {
        val logger = UnifiedLogger.getInstance()
        if (!FeatureFlags.remindersEnabled) {
            logger.i("BootReceiver.reArmReminders", "Skipped reminder schedule on $action; reminders disabled")
            return
        }
        notificationScheduler.scheduleAllPendingTasks()
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
