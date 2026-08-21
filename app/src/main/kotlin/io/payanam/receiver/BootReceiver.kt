//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.payanam.FeatureFlags
import io.payanam.common.logging.UnifiedLogger
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
/**
 * Provides the boot receiver.
 */
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var timeEntryRepository: TimeEntryRepository

    @Inject
    lateinit var notificationScheduler: NotificationScheduler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Handles the on receive.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val logger = UnifiedLogger.getInstance()
            logger.i("BootReceiver.onReceive", "Device boot completed, checking for active tracking")

            scope.launch {
                try {
                    // Check for active time entries
                    val activeEntries = timeEntryRepository.getActiveTimeEntries().first()
                    if (activeEntries.isNotEmpty()) {
                        // Restore the most recent active entry's notification
                        val entry = activeEntries.first()

                        logger.i(
                            "BootReceiver.onReceive",
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
                        logger.i("BootReceiver.onReceive", "Requested widget refresh after restoring active tracking")
                    } else {
                        logger.d("BootReceiver.onReceive", "No active entries to restore")
                        TimeTrackingWidgetProvider.requestUpdate(context)
                        logger.d("BootReceiver.onReceive", "Requested widget refresh with idle state after boot")
                    }
                    if (FeatureFlags.remindersEnabled) {
                        notificationScheduler.scheduleAllPendingTasks()
                        logger.i("BootReceiver.onReceive", "Scheduled task reminders after boot")
                    } else {
                        logger.i("BootReceiver.onReceive", "Skipped reminder schedule after boot; reminders disabled")
                    }
                } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
                    logger.e("BootReceiver.onReceive", "Failed to restore tracking after boot", e)
                }
            }
        }
    }
}
