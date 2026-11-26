//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.service

import android.content.Context
import io.payanam.common.logging.UnifiedLogger
import io.payanam.database.session.DatabaseSessionManager
import io.payanam.domain.repository.TimeEntryRepository
import io.payanam.widget.TimeTrackingWidgetProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Restores the tracking notification and widget for an active entry; a closed session is reported, not thrown. */
@Singleton
class TrackingRestorer
@Inject
constructor(
    private val sessionManager: DatabaseSessionManager,
    private val timeEntryRepository: TimeEntryRepository,
) {
    /** Re-posts the tracking notification and refreshes the widget for the active entry, if any. */
    suspend fun restore(context: Context) {
        val logger = UnifiedLogger.getInstance()
        if (!sessionManager.isDbOpen()) {
            logger.d("TrackingRestorer.restore", "Skipping tracking restore; database session is closed")
            TimeTrackingWidgetProvider.requestUpdate(context)
            return
        }
        val activeEntries = timeEntryRepository.getActiveTimeEntries().first()
        if (activeEntries.isEmpty()) {
            logger.d("TrackingRestorer.restore", "No active entries to restore")
            TimeTrackingWidgetProvider.requestUpdate(context)
            return
        }
        val entry = activeEntries.first()
        logger.i(
            "TrackingRestorer.restore",
            "Restoring active tracking notification",
            mapOf("entryId" to entry.id, "dimensionId" to (entry.dimensionId ?: "unknown")),
        )
        TrackingService.startTracking(
            context = context,
            taskId = entry.taskId,
            taskTitle = entry.lifeIntentionCategory,
            dimension = entry.lifeIntentionCategory,
            startTime = entry.startedAt.toString(),
        )
        TimeTrackingWidgetProvider.requestUpdate(context)
    }
}
