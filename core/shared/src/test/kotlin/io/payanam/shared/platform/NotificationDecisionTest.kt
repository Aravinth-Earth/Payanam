//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

/**
 * Tests for the reminder decision, over plain values with no Android and no clock.
 *
 * What is worth pinning is not the arithmetic but the RULES, because each one is a decision the user
 * experiences and each has a way to go quietly wrong: a task that reminds when it should not, a task that
 * does not remind when it should, a reminder that fires twice because its key moved, and copy that
 * describes a snooze as a plain advance. Every branch of the decision is asserted below, including the
 * boundary the arithmetic is most likely to trip over — a fire time equal to now, which is NOT future.
 */
class NotificationDecisionTest {
    private val now: LocalDateTime = LocalDateTime.of(2026, 4, 9, 9, 0)

    private fun facts(
        taskId: String = "task-1",
        taskTitle: String = "Pay rent",
        status: String = "pending",
        recurrenceEnabled: Boolean = false,
        notificationMode: String? = "auto",
        customNotificationMinutes: Int? = null,
        dueAt: LocalDateTime? = now.plusHours(1),
        isSnoozed: Boolean = false,
        dueTimeText: String? = "10:00 am",
        overrideFireAt: LocalDateTime? = null,
        overrideDueAt: LocalDateTime? = null,
    ) = ReminderFacts(
        taskId = taskId,
        taskTitle = taskTitle,
        status = status,
        recurrenceEnabled = recurrenceEnabled,
        notificationMode = notificationMode,
        customNotificationMinutes = customNotificationMinutes,
        dueAt = dueAt,
        isSnoozed = isSnoozed,
        dueTimeText = dueTimeText,
        overrideFireAt = overrideFireAt,
        overrideDueAt = overrideDueAt,
    )

    /** The fire time of a decision, or a failure that names what came back instead. */
    private fun NotificationDecision.fireAtOrFail(): LocalDateTime {
        assertThat(this).isInstanceOf(NotificationDecision.Fire::class.java)
        return (this as NotificationDecision.Fire).fireAt
    }

    /** The skip reason of a decision, or a failure that names what came back instead. */
    private fun NotificationDecision.skipReasonOrFail(): ReminderSkipReason {
        assertThat(this).isInstanceOf(NotificationDecision.Skip::class.java)
        return (this as NotificationDecision.Skip).reason
    }

    // ---------------------------------------------------------------- eligibility: the modes

    @Test
    fun `auto mode fires the default advance before the due time`() {
        val decision = ReminderDecider.decide(facts(), now)

        assertThat(decision.fireAtOrFail()).isEqualTo(now.plusMinutes(45))
    }

    @Test
    fun `off mode is refused with its own reason`() {
        val decision = ReminderDecider.decide(facts(notificationMode = "off"), now)

        assertThat(decision.skipReasonOrFail()).isEqualTo(ReminderSkipReason.NOTIFICATIONS_OFF)
    }

    @Test
    fun `custom mode honours a positive advance`() {
        val decision =
            ReminderDecider.decide(facts(notificationMode = "custom", customNotificationMinutes = 45), now)

        assertThat(decision.fireAtOrFail()).isEqualTo(now.plusMinutes(15))
    }

    @Test
    fun `custom mode without a usable advance falls back to the default`() {
        // `custom = 0` is the case that matters: subtracting nothing would land on the due time itself and
        // the in-the-future test would then refuse the reminder the task had explicitly asked for.
        val decision =
            ReminderDecider.decide(facts(notificationMode = "custom", customNotificationMinutes = 0), now)

        assertThat(decision.fireAtOrFail()).isEqualTo(now.plusMinutes(45))
    }

    @Test
    fun `an unrecognised mode still reminds rather than going quiet`() {
        val decision = ReminderDecider.decide(facts(notificationMode = "AUTO_PRIME"), now)

        assertThat(decision.fireAtOrFail()).isEqualTo(now.plusMinutes(45))
    }

    @Test
    fun `a null mode is read as the default mode`() {
        val decision = ReminderDecider.decide(facts(notificationMode = null), now)

        assertThat(decision.fireAtOrFail()).isEqualTo(now.plusMinutes(45))
    }

    @Test
    fun `mode and status are matched without regard to case`() {
        val decision = ReminderDecider.decide(facts(status = "Pending", notificationMode = "CUSTOM"), now)

        assertThat(decision.fireAtOrFail()).isEqualTo(now.plusMinutes(45))
    }

    // ---------------------------------------------------------------- eligibility: status

    @Test
    fun `a completed task is refused`() {
        val decision = ReminderDecider.decide(facts(status = "completed"), now)

        assertThat(decision.skipReasonOrFail()).isEqualTo(ReminderSkipReason.STATUS_INELIGIBLE)
    }

    @Test
    fun `an active task is eligible`() {
        // `active` is not a status the storage layer currently writes, but the rule has always accepted it
        // and a rule that is quietly narrowed here would be a behaviour change dressed as an extraction.
        val decision = ReminderDecider.decide(facts(status = "active"), now)

        assertThat(decision.fireAtOrFail()).isEqualTo(now.plusMinutes(45))
    }

    // ---------------------------------------------------------------- eligibility: policy

    @Test
    fun `a build with reminders switched off refuses every task`() {
        val decision = ReminderDecider.decide(facts(), now, ReminderPolicy(remindersEnabled = false))

        assertThat(decision.skipReasonOrFail()).isEqualTo(ReminderSkipReason.REMINDERS_DISABLED)
    }

    @Test
    fun `minimal mode silences a recurring task but not a plain one`() {
        val policy = ReminderPolicy(minimalModeEnabled = true)

        val recurring = ReminderDecider.decide(facts(recurrenceEnabled = true), now, policy)
        val plain = ReminderDecider.decide(facts(recurrenceEnabled = false), now, policy)

        assertThat(recurring.skipReasonOrFail())
            .isEqualTo(ReminderSkipReason.RECURRING_TASK_IN_MINIMAL_MODE)
        assertThat(plain.fireAtOrFail()).isEqualTo(now.plusMinutes(45))
    }

    @Test
    fun `a recurring task outside minimal mode fires`() {
        // The recurrence flag does not change WHEN a reminder fires; the platform reads it to choose a
        // channel, and a decision that moved the fire time for it would be a second, hidden rule.
        val decision = ReminderDecider.decide(facts(recurrenceEnabled = true), now)

        assertThat(decision.fireAtOrFail()).isEqualTo(now.plusMinutes(45))
    }

    @Test
    fun `status is reported ahead of a task's own switch`() {
        // Both refusals are true at once, so the order is the contract: a caller fixing one reason must be
        // pointed at the one that is still blocking after the other is dealt with.
        val decision =
            ReminderDecider.decide(facts(status = "completed", notificationMode = "off"), now)

        assertThat(decision.skipReasonOrFail()).isEqualTo(ReminderSkipReason.STATUS_INELIGIBLE)
    }

    // ---------------------------------------------------------------- the advance boundary

    @Test
    fun `a fire time equal to now is not in the future and is refused`() {
        // The boundary itself. `isAfter`, not `isBefore` inverted: a reminder computed for this exact
        // instant has nowhere to fire to, and arming it would deliver it late or not at all.
        val decision = ReminderDecider.decide(facts(dueAt = now.plusMinutes(15)), now)

        assertThat(decision.skipReasonOrFail()).isEqualTo(ReminderSkipReason.FIRE_TIME_NOT_IN_FUTURE)
    }

    @Test
    fun `a fire time one minute ahead of now fires`() {
        val decision = ReminderDecider.decide(facts(dueAt = now.plusMinutes(16)), now)

        assertThat(decision.fireAtOrFail()).isEqualTo(now.plusMinutes(1))
    }

    @Test
    fun `a due time in the past is refused`() {
        val decision = ReminderDecider.decide(facts(dueAt = now.minusMinutes(5)), now)

        assertThat(decision.skipReasonOrFail()).isEqualTo(ReminderSkipReason.FIRE_TIME_NOT_IN_FUTURE)
    }

    @Test
    fun `a task with no due time is refused`() {
        val decision = ReminderDecider.decide(facts(dueAt = null), now)

        assertThat(decision.skipReasonOrFail()).isEqualTo(ReminderSkipReason.NO_DUE_TIME)
    }

    // ---------------------------------------------------------------- snooze

    @Test
    fun `a snooze of a task not yet due fires at its due time`() {
        val dueAt = now.plusMinutes(90)
        val decision =
            ReminderDecider.decide(
                facts(dueAt = dueAt, isSnoozed = true, overrideFireAt = dueAt),
                now,
            )

        assertThat(decision.fireAtOrFail()).isEqualTo(dueAt)
    }

    @Test
    fun `a snooze override is not measured against the advance`() {
        // The advance is SKIPPED, not applied. Subtracting it again would move the snooze fifteen minutes
        // earlier than the moment the user asked to be reminded at.
        val snoozedAt = now.plusMinutes(30)
        val decision =
            ReminderDecider.decide(
                facts(dueAt = now.minusMinutes(60), isSnoozed = true, overrideFireAt = snoozedAt),
                now,
            )

        assertThat(decision.fireAtOrFail()).isEqualTo(snoozedAt)
    }

    @Test
    fun `a snooze target in the past is refused rather than delivered late`() {
        val decision =
            ReminderDecider.decide(
                facts(isSnoozed = true, overrideFireAt = now.minusMinutes(1)),
                now,
            )

        assertThat(decision.skipReasonOrFail()).isEqualTo(ReminderSkipReason.FIRE_TIME_NOT_IN_FUTURE)
    }

    @Test
    fun `a snooze uses the due time it was given rather than the task's own`() {
        val movedDue = now.plusMinutes(120)
        val decision =
            ReminderDecider.decide(
                facts(dueAt = now.minusMinutes(60), overrideDueAt = movedDue, overrideFireAt = movedDue),
                now,
            )

        // The snoozed task's due time is GONE (it was an hour ago) and the supplied one is an hour and a
        // half away, so the copy is measured against the supplied due time: it lands on the same instant as
        // the fire time, which is "due at", never "due in 15m".
        assertThat(decision).isInstanceOf(NotificationDecision.Fire::class.java)
        val copy = (decision as NotificationDecision.Fire).reminderCopy
        assertThat(copy).isEqualTo(ReminderCopy.DueAt(clockText = "10:00 am"))
    }

    @Test
    fun `a snooze target of fifteen minutes from now when the task is already due`() {
        assertThat(ReminderDecider.snoozeFireAt(now, dueAt = now.minusMinutes(1)))
            .isEqualTo(now.plusMinutes(ReminderDecider.DEFAULT_SNOOZE_MINUTES))
        assertThat(ReminderDecider.snoozeFireAt(now, dueAt = null))
            .isEqualTo(now.plusMinutes(ReminderDecider.DEFAULT_SNOOZE_MINUTES))
    }

    @Test
    fun `a snooze of a task not yet due targets its due time`() {
        val dueAt = now.plusMinutes(90)

        assertThat(ReminderDecider.snoozeFireAt(now, dueAt = dueAt)).isEqualTo(dueAt)
    }

    // ---------------------------------------------------------------- copy identity

    @Test
    fun `an advancing reminder reports the minutes until due`() {
        val decision = ReminderDecider.decide(facts(dueTimeText = "10:00 am"), now)

        val copy = (decision as NotificationDecision.Fire).reminderCopy

        assertThat(copy).isEqualTo(ReminderCopy.DueIn(minutesUntilDue = 15L, clockText = "10:00 am"))
    }

    @Test
    fun `a snoozed reminder reports the snoozed line, not the advance`() {
        val snoozedAt = now.plusMinutes(30)
        val decision =
            ReminderDecider.decide(
                facts(isSnoozed = true, overrideFireAt = snoozedAt, dueTimeText = "10:00 am"),
                now,
            )

        val copy = (decision as NotificationDecision.Fire).reminderCopy

        // Not DueIn(0): the fire time is thirty minutes AFTER the due time, and "due in 0m" would describe
        // a reminder fifteen minutes early that never happened.
        assertThat(copy).isEqualTo(ReminderCopy.SnoozedUntil("10:00 am"))
    }

    @Test
    fun `a reminder with no clock text falls back to the bare line`() {
        val decision = ReminderDecider.decide(facts(dueTimeText = null), now)

        val copy = (decision as NotificationDecision.Fire).reminderCopy

        assertThat(copy).isEqualTo(ReminderCopy.NoDueTime)
    }

    @Test
    fun `an overridden due time that is not ahead of the fire time reports due at`() {
        val decision =
            ReminderDecider.decide(
                facts(dueAt = now.plusMinutes(30), overrideFireAt = now.plusMinutes(60), dueTimeText = "11:00 am"),
                now,
            )

        val copy = (decision as NotificationDecision.Fire).reminderCopy

        assertThat(copy).isEqualTo(ReminderCopy.DueAt("11:00 am"))
    }

    @Test
    fun `the clock text the caller supplied is carried through untouched`() {
        // The decision names a line and holds the caller's arguments; it never formats a time of its own,
        // so a 24-hour caller and a 12-hour caller both get exactly what they asked for.
        val decision = ReminderDecider.decide(facts(dueTimeText = "10:00"), now)

        val copy = (decision as NotificationDecision.Fire).reminderCopy

        assertThat(copy).isInstanceOf(ReminderCopy.DueIn::class.java)
        assertThat((copy as ReminderCopy.DueIn).clockText).isEqualTo("10:00")
        assertThat(copy.minutesUntilDue).isEqualTo(ReminderDecider.DEFAULT_ADVANCE_MINUTES)
    }

    // ---------------------------------------------------------------- the dedup key

    @Test
    fun `the dedup key is stable across two calls with the same inputs`() {
        val first = ReminderDecider.decide(facts(), now)
        val second = ReminderDecider.decide(facts(), now)

        // The guarantee the whole key exists for: a reschedule is cancel-then-schedule under ONE key, so a
        // key that moved between two identical decisions would leave the first reminder armed and tell the
        // user twice.
        assertThat(first.dedupKey).isEqualTo(second.dedupKey)
        assertThat(first.dedupKey).isEqualTo(ReminderDecider.dedupKeyFor("task-1"))
    }

    @Test
    fun `the dedup key differs when the task differs`() {
        val one = ReminderDecider.decide(facts(taskId = "task-1"), now)
        val other = ReminderDecider.decide(facts(taskId = "task-2"), now)

        assertThat(one.dedupKey).isNotEqualTo(other.dedupKey)
    }

    @Test
    fun `the dedup key does not change when only the timing changes`() {
        val moved = ReminderDecider.decide(facts(dueAt = now.plusHours(3)), now)

        // Re-deciding after a due-time move must REPLACE the reminder rather than add a second one, and
        // replacement is only possible under the same key.
        assertThat(moved.dedupKey).isEqualTo(ReminderDecider.dedupKeyFor("task-1"))
    }

    @Test
    fun `a refused decision still names the key to cancel under`() {
        val decision = ReminderDecider.decide(facts(status = "completed"), now)

        // Every refusal path cancels before returning, so "do not fire" has to answer "cancel what was
        // already armed" as well, or the caller cannot cancel by this task alone.
        assertThat(decision.dedupKey).isEqualTo(ReminderDecider.dedupKeyFor("task-1"))
    }

    @Test
    fun `two task ids that would collide under a 32-bit hash keep separate keys`() {
        // The ids below hash to the same Int, which is exactly the collision the hashed key suffered: two
        // unrelated notifications sharing one request code, and one of them able to overwrite the other.
        val one = ReminderDecider.decide(facts(taskId = "Aa"), now)
        val other = ReminderDecider.decide(facts(taskId = "BB"), now)

        assertThat(one.dedupKey).isNotEqualTo(other.dedupKey)
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode())
    }

    @Test
    fun `a task id is carried whole rather than clamped`() {
        // The hashed key had to map Int.MIN_VALUE to 0, so the key of one task could become the key of
        // another. A whole id has no such value.
        assertThat(ReminderDecider.dedupKeyFor("task-1")).isNotEqualTo(ReminderDecider.dedupKeyFor(""))
    }

    // ---------------------------------------------------------------- what a fire carries

    @Test
    fun `a fired decision carries the title and a tap action for the task`() {
        val decision = ReminderDecider.decide(facts(taskTitle = "Pay rent"), now)

        val fire = decision as NotificationDecision.Fire

        assertThat(fire.taskTitle).isEqualTo("Pay rent")
        assertThat(fire.tapAction).isEqualTo(NotificationTapAction.OpenTask("task-1"))
    }

    @Test
    fun `a decision is compared by what it says, not by identity`() {
        // Data classes here so a value can be stored, logged or diffed as one; pinned because the other
        // contract tests in this module pin the same property, and a shape that stopped being a data class
        // would silently lose it.
        val one =
            ReminderDecider.decide(facts(), now) as NotificationDecision.Fire
        val same =
            ReminderDecider.decide(facts(), now) as NotificationDecision.Fire
        val moved = same.copy(fireAt = now.plusHours(2))

        assertThat(one).isEqualTo(same)
        assertThat(one.hashCode()).isEqualTo(same.hashCode())
        assertThat(one).isNotEqualTo(moved)
        assertThat(one.copy(taskTitle = "Other")).isNotEqualTo(one)
        assertThat(one.reminderCopy).isEqualTo(
            ReminderCopy.DueIn(
                minutesUntilDue = ReminderDecider.DEFAULT_ADVANCE_MINUTES,
                clockText = "10:00 am",
            ),
        )
        assertThat(one.tapAction).isEqualTo(NotificationTapAction.OpenTask("task-1"))
        assertThat(ReminderFacts(taskId = "t", taskTitle = "x", status = "pending"))
            .isEqualTo(ReminderFacts(taskId = "t", taskTitle = "x", status = "pending"))
        assertThat(ReminderPolicy()).isEqualTo(ReminderPolicy(remindersEnabled = true, minimalModeEnabled = false))
    }

    @Test
    fun `every skip reason is reachable and distinct`() {
        // Pinned as a set so a reason added later has to say which question it answers, and so no two
        // refusals can collapse into one indistinguishable value.
        assertThat(ReminderSkipReason.entries).containsExactly(
            ReminderSkipReason.REMINDERS_DISABLED,
            ReminderSkipReason.STATUS_INELIGIBLE,
            ReminderSkipReason.NOTIFICATIONS_OFF,
            ReminderSkipReason.RECURRING_TASK_IN_MINIMAL_MODE,
            ReminderSkipReason.NO_DUE_TIME,
            ReminderSkipReason.FIRE_TIME_NOT_IN_FUTURE,
        )
    }

    @Test
    fun `a task with nothing but the required fields is still decided`() {
        // The defaults have to be the permissive ones, or a caller that knows only an id and a due time
        // would get an exception instead of a decision.
        val decision =
            ReminderDecider.decide(
                ReminderFacts(taskId = "task-9", taskTitle = "Water plants", status = "pending"),
                now,
            )

        assertThat(decision.dedupKey).isEqualTo(ReminderDecider.dedupKeyFor("task-9"))
        assertThat(decision).isInstanceOf(NotificationDecision.Skip::class.java)
        assertThat(decision.skipReasonOrFail()).isEqualTo(ReminderSkipReason.NO_DUE_TIME)
    }
}
