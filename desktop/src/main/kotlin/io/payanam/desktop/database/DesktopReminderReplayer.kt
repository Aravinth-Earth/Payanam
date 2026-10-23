//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import io.payanam.domain.model.Task
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.shared.logging.UnifiedLogger

/**
 * Re-arms this platform's reminders once a session is open - the desktop's half of what Android does in
 * `NotificationScheduler.scheduleAllPendingTasks`.
 *
 * WHY IT EXISTS: the desktop had a producer (`DesktopTaskReminderScheduler`) over a real delivery surface
 * (the seams' sink, with a freedesktop notification behind it), and **nothing called the producer**. A
 * task's reminder was therefore armed only by the single action that created it and was never re-armed
 * across a restart, while Android re-arms every eligible task on each start and again after boot.
 *
 * Every task is handed to the scheduler rather than a filtered copy: the scheduler already applies the
 * eligibility and timing rules and logs its own reason for each skip, so a filter here would be a second
 * copy of that decision, free to drift from the first.
 *
 * [readTasks] is a lambda rather than a repository so the call site states which read it is making - the
 * same shape the other maintenance steps use - and so this can be tested without standing up a database.
 *
 * Never throws. A reminder that cannot be armed must not take down the maintenance run around it.
 */
internal suspend fun replayDesktopReminders(
    readTasks: suspend () -> List<Task>,
    scheduler: TaskReminderScheduler,
    logger: UnifiedLogger,
    source: String = "DesktopReminderReplayer",
) {
    runCatching {
        val tasks = readTasks()
        logger.i(source, "Re-arming reminders for this platform's tasks", mapOf("total" to tasks.size))
        tasks.forEach { task ->
            scheduler.scheduleForTask(
                task,
                overrideScheduledAt = null,
                overrideDueAt = null,
                isSnoozed = false,
            )
        }
    }.onFailure { error ->
        logger.e(
            source,
            "Reminder re-arm failed; reminders may not be armed this session",
            error,
            mapOf("error" to (error.message ?: "Unknown error")),
        )
    }
}
