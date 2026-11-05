//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import io.payanam.domain.model.TimeEntry
import java.time.LocalDateTime

/**
 * Starts and stops time-tracking sessions, completing the attached task when a stopped entry has one.
 *
 * A port because starting and stopping also drive a foreground service and a home-screen widget — platform
 * work the shared state layer cannot do and must not know about.
 */
interface TimeTrackingController {
    /**
     * Starts a tracking session on the platform: launches the foreground service, then refreshes the
     * home-screen widget so it shows the new session.
     *
     * [taskId] and [taskTitle] are null when the entry is not attached to a task. [dimension] labels the
     * session and [startTime] is when it began.
     */
    suspend fun startTracking(
        taskId: String?,
        taskTitle: String?,
        dimension: String,
        startTime: LocalDateTime,
    )

    /**
     * Stops the active session on the platform: sends the stop action to the foreground service so it
     * ends the session and drops its notification.
     *
     * Ends platform state only — the active entry in the shared store is stopped by
     * [stopTrackingAndCompleteTask], which this is deliberately not merged into because callers stop
     * any running session before starting another, without touching the store's own bookkeeping.
     */
    fun stopTracking()

    /**
     * Stops the active entry, completes the attached task, and returns the entry that was stopped.
     */
    suspend fun stopTrackingAndCompleteTask(
        focusRating: Double = 0.0,
        focusNote: String? = null,
    ): TimeEntry?
}
