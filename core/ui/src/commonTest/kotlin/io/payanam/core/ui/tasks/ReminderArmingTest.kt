//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.core.ui.tasks

import io.payanam.domain.model.Task
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.LocalDateTime

/**
 * The gate in front of the "the reminder will not arrive" warning: a save is only worth warning about
 * when the scheduler would actually arm a reminder for it.
 */
class ReminderArmingTest {
    private val now = LocalDateTime.of(2026, 1, 15, 8, 0)

    private fun willBeArmed(
        dueAt: LocalDateTime?,
        recurrenceEnabled: Boolean = false,
        notificationMode: String? = "default",
        customNotificationMinutes: Int? = null,
        status: String = "pending",
        remindersEnabled: Boolean = true,
        minimalModeEnabled: Boolean = false,
        storedTask: Task? = null,
        now: LocalDateTime = this.now,
    ): Boolean = reminderWillBeArmed(
        taskId = "task-1",
        taskTitle = "Task",
        dueAt = dueAt,
        recurrenceEnabled = recurrenceEnabled,
        status = status,
        notificationMode = notificationMode,
        customNotificationMinutes = customNotificationMinutes,
        remindersEnabled = remindersEnabled,
        minimalModeEnabled = minimalModeEnabled,
        storedTask = storedTask,
        now = now,
    )

    /** The task as the store already holds it, which is what an edit is judged against. */
    private fun storedTask(dueDate: LocalDateTime?): Task =
        Task(
            id = "task-1",
            title = "Task",
            dueDate = dueDate,
            createdAt = now,
            updatedAt = now,
        )

    /** Saving with no reminder time arms nothing, so there is nothing to warn about. */
    @Test
    fun `no due time arms no reminder`() {
        assertFalse(willBeArmed(dueAt = null))
    }

    /** A due time ahead of now is a real alarm, so a blocked notification is worth reporting. */
    @Test
    fun `a future due time arms a reminder`() {
        assertTrue(willBeArmed(dueAt = now.plusHours(2)))
    }

    /** A due time whose advance has already passed arms nothing, so the warning would be wrong. */
    @Test
    fun `a due time whose reminder would already have fired arms nothing`() {
        assertFalse(willBeArmed(dueAt = now.plusMinutes(5)))
    }

    /** Reminders switched off on the task itself means no alarm, whatever the due time says. */
    @Test
    fun `a task with notifications off arms nothing`() {
        assertFalse(willBeArmed(dueAt = now.plusHours(2), notificationMode = "off"))
    }

    /** A completed task is not reminded about, so its save must not warn either. */
    @Test
    fun `a completed task arms nothing`() {
        assertFalse(willBeArmed(dueAt = now.plusHours(2), status = "completed"))
    }

    /** Reminders compiled out of the build arm nothing. */
    @Test
    fun `reminders disabled arms nothing`() {
        assertFalse(willBeArmed(dueAt = now.plusHours(2), remindersEnabled = false))
    }

    /** Minimal mode silences recurring tasks, and only those. */
    @Test
    fun `minimal mode silences a recurring task`() {
        assertFalse(willBeArmed(dueAt = now.plusHours(2), recurrenceEnabled = true, minimalModeEnabled = true))
        assertTrue(willBeArmed(dueAt = now.plusHours(2), recurrenceEnabled = false, minimalModeEnabled = true))
    }

    /** A recurring task saved with no date still arms one, from the same default due time the ViewModel uses. */
    @Test
    fun `a recurring task with no date arms its default reminder`() {
        assertTrue(willBeArmed(dueAt = null, recurrenceEnabled = true))
    }

    /**
     * An edit keeps the stored date for a recurring task, and that date is what the scheduler reads.
     */
    @Test
    fun `an edit is judged on the stored due date`() {
        val clock = LocalDateTime.of(2026, 1, 15, 10, 0)
        assertTrue(
            willBeArmed(
                dueAt = null,
                recurrenceEnabled = true,
                storedTask = storedTask(dueDate = clock.plusHours(1)),
                now = clock,
            ),
        )
    }

    /** An edit that resolves to no due date at all arms nothing: the recurring default belongs to creation. */
    @Test
    fun `an edit with no stored date arms nothing`() {
        assertFalse(
            willBeArmed(
                dueAt = null,
                recurrenceEnabled = true,
                storedTask = storedTask(dueDate = null),
            ),
        )
    }

    /** A custom advance is the one the scheduler subtracts, so it decides whether the alarm is real. */
    @Test
    fun `a custom advance moves the fire time`() {
        assertFalse(willBeArmed(dueAt = now.plusMinutes(20), notificationMode = "custom", customNotificationMinutes = 60))
        assertTrue(willBeArmed(dueAt = now.plusMinutes(20), notificationMode = "custom", customNotificationMinutes = 5))
    }
}
