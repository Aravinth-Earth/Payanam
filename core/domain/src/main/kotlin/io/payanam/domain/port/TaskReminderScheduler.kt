//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.domain.port

import io.payanam.domain.model.Task
import java.time.LocalDateTime

/**
 * Arming and cancelling a task's reminder.
 *
 * A port rather than a repository: what the shared ViewModels need from the reminder channel is exactly these
 * two calls. Android implements it with alarms and the notification manager; the desktop implements it by
 * handing a `ReminderRequest` to its installed sink, which delivers a freedesktop notification. Both are
 * real producers over their own platform's delivery mechanism, which is what this port exists to separate.
 */
interface TaskReminderScheduler {
    /**
     * Arms the reminder for [task], overriding the task's own schedule when an override is supplied.
     */
    suspend fun scheduleForTask(
        task: Task,
        overrideScheduledAt: LocalDateTime? = null,
        overrideDueAt: LocalDateTime? = null,
        isSnoozed: Boolean = false,
    )

    /**
     * Cancels any armed reminder for [taskId].
     */
    suspend fun cancelForTask(taskId: String)
}
