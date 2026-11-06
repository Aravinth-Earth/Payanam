//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.tasks

import io.payanam.domain.model.Task
import io.payanam.shared.platform.NotificationDecision
import io.payanam.shared.platform.ReminderDecider
import io.payanam.shared.platform.ReminderFacts
import io.payanam.shared.platform.ReminderPolicy
import java.time.LocalDateTime
import java.time.LocalTime

/** The due time a recurring task is created with when it names no date of its own, as the add-task ViewModel does. */
private const val RECURRING_DEFAULT_DUE_HOUR = 9
private const val RECURRING_DEFAULT_DUE_MINUTE = 0

/**
 * Whether saving these form values actually leaves a reminder armed.
 *
 * WHY THIS IS NOT A LOCAL `mode != off && dueDate != null` CHECK:
 *
 * The warning it gates - "the reminder will not arrive" - is only true when a reminder exists at all.
 * Arming is [ReminderDecider]'s decision, and that decision also refuses a status that cannot be
 * reminded about, a task with no due time, and a fire time that is not in the future; a screen deriving
 * the answer from two fields warned about reminders the scheduler never armed. Asking the scheduler's own
 * decision function is what keeps the warning and the alarm from disagreeing.
 *
 * WHY THE DUE DATE IS RESOLVED FROM THE STORED TASK:
 *
 * An edit that sends no due date keeps the date the store already holds - the repository substitutes it,
 * and the scheduler reads that stored date - so the recurring default below must not stand in for it: a
 * stored future date whose fire time is still ahead arms a reminder the default would have called past.
 * The default belongs to creation only, which is the save that has no stored date to keep.
 *
 * @param storedTask the task this save edits, or null when the save creates one.
 */
fun reminderWillBeArmed(
    taskId: String,
    taskTitle: String,
    dueAt: LocalDateTime?,
    recurrenceEnabled: Boolean,
    status: String,
    notificationMode: String?,
    customNotificationMinutes: Int?,
    remindersEnabled: Boolean,
    minimalModeEnabled: Boolean,
    storedTask: Task? = null,
    now: LocalDateTime = LocalDateTime.now(),
): Boolean {
    val effectiveDueAt =
        dueAt ?: storedTask?.dueDate ?: if (recurrenceEnabled && storedTask == null) {
            LocalDateTime.of(
                now.toLocalDate(),
                LocalTime.of(RECURRING_DEFAULT_DUE_HOUR, RECURRING_DEFAULT_DUE_MINUTE),
            )
        } else {
            null
        }
    if (effectiveDueAt == null) return false
    val facts =
        ReminderFacts(
            taskId = taskId,
            taskTitle = taskTitle,
            status = status,
            recurrenceEnabled = recurrenceEnabled,
            notificationMode = notificationMode,
            customNotificationMinutes = customNotificationMinutes,
            dueAt = effectiveDueAt,
        )
    val policy = ReminderPolicy(remindersEnabled = remindersEnabled, minimalModeEnabled = minimalModeEnabled)
    return ReminderDecider.decide(facts, now, policy) is NotificationDecision.Fire
}
