//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.port

import io.payanam.desktop.UnifiedLogger
import io.payanam.desktop.platform.DesktopPlatformSeams
import io.payanam.domain.model.Task
import io.payanam.domain.port.TaskReminderScheduler
import io.payanam.shared.platform.ReminderRequest
import io.payanam.shared.platform.ReminderSink
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The desktop's task-reminder PRODUCER: Android's arming rule, handed to the sink the seams actually
 * installed.
 *
 * WHY THIS EXISTS: the desktop had a delivery surface (the seams' sink, with a real `notify-send` behind it)
 * and no producer - the data layer still bound the log-only no-op, so no reminder could ever reach the sink
 * and the `reminders` capability could never derive to SUPPORTED. This is the producer, and it arms by the
 * SAME rule Android does: the same eligibility, the same advance resolution, the same "not after now" skip,
 * and the same cancel-before-arm - only the mechanism underneath differs, a sink instead of an AlarmManager.
 *
 * The copy carried is the task's OWN title plus the due time as a locale-formatted VALUE: the desktop's
 * delivery has no localized notification copy of its own, and inventing app copy here would be a second copy
 * table. The sink's delivery decides what to show; this class decides when, and about what.
 *
 * The sink arrives through a provider rather than a constructor value because the seams are installed at
 * app start while this scheduler is bound into the data layer - a direct reference would freeze whichever
 * sink existed at layer-install time. A missing sink is a logged refusal, not a throw: a reminder that
 * cannot be armed must never take a task write down with it.
 */
internal class DesktopTaskReminderScheduler(
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val logger: UnifiedLogger = UnifiedLogger.getInstance(),
    private val sink: () -> ReminderSink? = { DesktopPlatformSeams.installedReminderSink() },
) : TaskReminderScheduler {
    override suspend fun scheduleForTask(
        task: Task,
        overrideScheduledAt: LocalDateTime?,
        overrideDueAt: LocalDateTime?,
        isSnoozed: Boolean,
    ) {
        val dueAt = overrideDueAt ?: task.dueDate
        if (!isEligibleForScheduling(task) || dueAt == null) {
            logger.d(
                SOURCE,
                "Task not eligible for scheduling",
                mapOf("taskId" to task.id, "status" to task.status, "hasDueAt" to (dueAt != null)),
            )
            return
        }
        val advanceMinutes = resolveAdvanceMinutes(task)
        if (advanceMinutes == null) {
            logger.d(SOURCE, "Notifications disabled for task", mapOf("taskId" to task.id))
            return
        }
        val scheduledAt = overrideScheduledAt ?: dueAt.minusMinutes(advanceMinutes)
        if (!scheduledAt.isAfter(clock())) {
            logger.d(
                SOURCE,
                "Skipping schedule - time is in the past",
                mapOf("taskId" to task.id, "scheduledAt" to scheduledAt.toString()),
            )
            return
        }
        val installedSink = sink()
        if (installedSink == null) {
            logger.w(
                SOURCE,
                "No reminder sink is installed; the reminder was not scheduled",
                mapOf("taskId" to task.id),
            )
            return
        }
        // Replace, not add - the same order Android arms in: a task's reminder moves when its due time moves.
        installedSink.cancel(task.id)
        installedSink.schedule(
            ReminderRequest(
                id = task.id,
                atEpochMillis = scheduledAt.atZone(zoneId).toInstant().toEpochMilli(),
                title = task.title,
                body = dueAt.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                relatedTaskId = task.id,
            ),
        )
        logger.i(
            SOURCE,
            "Reminder handed to the installed sink",
            mapOf("taskId" to task.id, "scheduledAt" to scheduledAt.toString()),
        )
    }

    override suspend fun cancelForTask(taskId: String) {
        sink()?.cancel(taskId)
    }

    /** The same eligibility Android's scheduler applies, minus the Android-only minimal-mode rule. */
    private fun isEligibleForScheduling(task: Task): Boolean {
        val status = task.status.lowercase()
        if (status != "pending" && status != "active") return false
        if ((task.notificationMode ?: "auto").lowercase() == "off") return false
        return true
    }

    private fun resolveAdvanceMinutes(task: Task): Long? =
        when (task.notificationMode?.lowercase() ?: "auto") {
            "off" -> null
            "custom" ->
                task.customNotificationMinutes?.takeIf { it > 0 }?.toLong()
                    ?: DEFAULT_ADVANCE_MINUTES
            else -> DEFAULT_ADVANCE_MINUTES
        }

    private companion object {
        const val SOURCE = "DesktopTaskReminderScheduler"

        /** The same default Android's scheduler uses when a task names no advance of its own. */
        const val DEFAULT_ADVANCE_MINUTES = 15L
    }
}
