//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.tasks

import io.payanam.shared.platform.NotificationDecision
import io.payanam.shared.platform.ReminderDecider
import io.payanam.shared.platform.ReminderFacts
import io.payanam.shared.platform.ReminderPolicy
import java.time.LocalDateTime
import java.time.LocalTime

/** The due time a recurring task saved with no date of its own is given, as the add-task ViewModel does. */
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
    now: LocalDateTime = LocalDateTime.now(),
): Boolean {
    val effectiveDueAt =
        dueAt ?: if (recurrenceEnabled) {
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
