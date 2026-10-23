//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import io.payanam.domain.model.TimeEntry

/**
 * Stops the running time entry and completes its task when one is attached.
 *
 * A port because the implementation also stops a foreground service and pokes a home-screen widget — platform
 * work the shared state layer cannot do and must not know about.
 */
interface TimeTrackingController {
    /**
     * Stops the active entry, completes the attached task, and returns the entry that was stopped.
     */
    suspend fun stopTrackingAndCompleteTask(
        focusRating: Double = 0.0,
        focusNote: String? = null,
    ): TimeEntry?
}
