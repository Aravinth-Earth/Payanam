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

/** Whether saving these form values actually leaves a reminder armed: [ReminderDecider]'s decision, not a
 * local field check, so the warning and the alarm cannot disagree. The due date resolves from [storedTask]
 * first, because an edit that sends none keeps the stored one. */
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
