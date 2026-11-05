//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

import java.time.Duration
import java.time.LocalDateTime

/**
 * Everything the reminder decision reads about one task, as plain values.
 *
 * WHY A VALUE AND NOT THE DOMAIN TASK MODEL:
 *
 * The decision does not need a task; it needs a handful of facts about one. Handing it the whole model
 * would make this module depend on a module above it for five fields, and would let a caller quietly
 * change an unrelated task property and move a reminder. So the inputs are named here and every one of
 * them is something a reminder genuinely turns on.
 *
 * The status and mode strings are the ones the storage layer already holds, so no new vocabulary is
 * invented and no value has to be translated on the way in. Both are matched case-insensitively, because
 * that is how the Android scheduler has always read them and a reminder that fires on Android but not on
 * the desktop because of a capital letter is the exact drift this type exists to stop.
 */
data class ReminderFacts(
    /** The task's id. Also what the dedup key is derived from, so it must be the task's own stable id. */
    val taskId: String,
    /** The task's title as the caller has it. Returned on the decision so the platform can compose its own title. */
    val taskTitle: String,
    /** `pending` and `active` are eligible; every other status is not. */
    val status: String,
    /** Whether this is a recurring task. Recorded, and used by the platform to choose its own channel. */
    val recurrenceEnabled: Boolean = false,
    /**
     * `off` refuses the reminder, `custom` reads [customNotificationMinutes], and anything else — including
     * null and an unrecognised value — reminds at the default advance.
     */
    val notificationMode: String? = null,
    /** The advance in minutes for `custom` mode. A null or non-positive value falls back to the default. */
    val customNotificationMinutes: Int? = null,
    /** When the task is due. A task without one cannot be reminded about. */
    val dueAt: LocalDateTime? = null,
    /** Whether this reminder IS a snooze, which is what selects the snoozed-until copy line. */
    val isSnoozed: Boolean = false,
    /**
     * The due time already rendered as clock text, e.g. `"9:15 pm"`.
     *
     * A PARAMETER, not something resolved here, because a clock format is a platform and language
     * decision and this type resolves no copy: it names which line applies and hands back the arguments
     * the caller already formatted. Null when the caller has nothing to render, which is its own copy case.
     */
    val dueTimeText: String? = null,
    /**
     * Replaces the computed fire time, for a snooze.
     *
     * The advance arithmetic is SKIPPED entirely when this is supplied, not applied to it, and the resulting
     * time is still subject to the in-the-future check.
     */
    val overrideFireAt: LocalDateTime? = null,
    /** Replaces the due time, for a snooze of a task whose due time has since moved. */
    val overrideDueAt: LocalDateTime? = null,
)

/**
 * The build- and install-level switches that decide whether a reminder may be armed at all.
 *
 * These are inputs rather than reads of a constant because they are exactly the things that differ per
 * platform and per build: a build with reminders compiled out must be able to say so, and a shared module
 * has no `BuildConfig` to ask. The defaults are the permissive ones so an unconfigured caller still gets a
 * decision rather than an exception.
 */
data class ReminderPolicy(
    /** False when the whole reminder channel is switched off; nothing fires, for any task. */
    val remindersEnabled: Boolean = true,
    /**
     * Minimal mode silences recurring tasks only.
     *
     * An input for the same reason as [remindersEnabled]: it is a build-time switch on the platform, not a
     * property of the task.
     */
    val minimalModeEnabled: Boolean = false,
)

/**
 * Which reminder line applies, with the arguments the caller already resolved.
 *
 * WHY A STRUCTURED LINE RATHER THAN RENDERED TEXT:
 *
 * This type does not resolve copy and imports no string table, so it cannot answer "due in 15m at 9:15 pm"
 * — but the CHOICE between "snoozed until", "due in N m at" and "due at" is a decision about the task,
 * not about a language, and it was previously buried inside an Android-only private function where no test
 * could reach it. So the decision lives here and the resolution stays with the caller, which already has
 * the clock text it passed in. This follows the same pattern as the copy-identity tables the repo already
 * ships (`SettingsLabelKey` resolves a label resource name later; `SupportStatePresentation` is handed
 * already-resolved strings) rather than introducing a third convention.
 *
 * The clock text is carried, not looked up: a `ReminderCopy` is never rendered here, only described.
 */
sealed interface ReminderCopy {
    /**
     * The caller has no clock text for this reminder, so only the bare "task reminder" line can be shown.
     *
     * Its own case rather than a silent fallback to [DueAt] with an empty time, because a notification
     * reading "Due at " is worse than one that says only that a task is due.
     */
    data object NoDueTime : ReminderCopy

    /** The reminder is a snooze: the task was due at [clockText] and the user pushed it away until now. */
    data class SnoozedUntil(
        val clockText: String,
    ) : ReminderCopy

    /** The task is due at [clockText], and this reminder fires [minutesUntilDue] minutes ahead of it. */
    data class DueIn(
        val minutesUntilDue: Long,
        val clockText: String,
    ) : ReminderCopy

    /** The task is due at [clockText], and this reminder fires at or after that moment. */
    data class DueAt(
        val clockText: String,
    ) : ReminderCopy
}

/**
 * Why no reminder should be armed — named, because every one of these is a decision a user or a build made.
 *
 * Each reason maps to a distinct thing that would otherwise be one anonymous `return`: a cancelled
 * notification list cannot tell a build with reminders off from a task that was completed, and those two
 * want very different fixes.
 */
enum class ReminderSkipReason {
    /** The whole reminder channel is off, so no task is eligible however well-formed it is. */
    REMINDERS_DISABLED,

    /** The task's status is not one that can still be reminded about. */
    STATUS_INELIGIBLE,

    /** The task itself has reminders switched off (`notificationMode = off`). */
    NOTIFICATIONS_OFF,

    /** Minimal mode is on and this is a recurring task, which minimal mode silences. */
    RECURRING_TASK_IN_MINIMAL_MODE,

    /** The task has no due time, so there is nothing to count the advance back from. */
    NO_DUE_TIME,

    /** The computed fire time is not strictly in the future. */
    FIRE_TIME_NOT_IN_FUTURE,
}

/**
 * What tapping the reminder should do.
 *
 * Named as a type with one case rather than a bare task id because the two platforms will not necessarily
 * keep answering the same way — one deep-links into the app, the other focuses a window — and a bare
 * `String` would leave that difference to be re-guessed at each call site. The Android receiver currently
 * opens the app and drops the id (`TaskReminderReceiver.showNotification`), so this names the intent rather
 * than claiming the shipped behaviour.
 */
sealed interface NotificationTapAction {
    /** Open the task the reminder is about. */
    data class OpenTask(
        val taskId: String,
    ) : NotificationTapAction
}

/**
 * The answer for one task: arm a reminder, or do not.
 *
 * Two shapes rather than a data class with a boolean, because a boolean plus a nullable time leaves the
 * caller to work out which half it has, and a caller that assumes the wrong half either cancels something
 * that should fire or fires something that should have been cancelled.
 */
sealed interface NotificationDecision {
    /**
     * The key this decision is filed under, on BOTH shapes.
     *
     * Present on a skip so that "do not fire" also answers "cancel what was already armed under this key",
     * which is what every refusal path in the Android scheduler does before returning.
     */
    val dedupKey: String

    /** Nothing should be armed; cancel whatever was already armed under [dedupKey]. */
    data class Skip(
        override val dedupKey: String,
        val reason: ReminderSkipReason,
    ) : NotificationDecision

    /** A reminder should be armed at [fireAt], replacing anything already under [dedupKey]. */
    data class Fire(
        override val dedupKey: String,
        val fireAt: LocalDateTime,
        val taskTitle: String,
        val tapAction: NotificationTapAction,
        val reminderCopy: ReminderCopy,
    ) : NotificationDecision
}

/**
 * The reminder decision: the rules both platforms read, over plain values and the JDK time API.
 *
 * WHAT IS DECIDED HERE, AND WHY IT WAS WORTH EXTRACTING:
 *
 * Six questions used to be answered together inside an Android-only scheduler: is this task eligible, how
 * far ahead should it fire, what does a snooze mean, which copy line applies, what key makes two decisions
 * about one task the same decision, and what a tap should do. They belong together and they belong here,
 * because a desktop that ever surfaces reminders has to answer them identically or the same task reminds
 * the user twice, at two different times, with two different keys.
 *
 * The rules are taken from the Android scheduler as they stand, including the parts that look odd:
 * - eligibility accepts `active` as well as `pending` (`NotificationScheduler.isEligibleForScheduling`),
 *   even though the stored statuses do not currently include `active` — an accepted value is not a bug to
 *   fix here, it is a rule to keep until it is deliberately withdrawn;
 * - a mode that is neither `off` nor `custom` reminds at the default advance, so a typo in stored data
 *   still reminds rather than silently going quiet;
 * - a custom advance that is missing or not positive falls back to the default, which is what keeps
 *   `custom = 0` from arming a reminder in the past and then refusing it;
 * - the fire time must be STRICTLY after now, so a reminder computed for this exact instant does not fire.
 *
 * WHAT IS DELIBERATELY NOT COPIED:
 *
 * The key derivation is one named function here rather than the hash-to-int that appeared three times over
 * (`NotificationScheduler.requestCodeFor`, `TaskReminderReceiver.notificationIdToInt`, and a copy inside
 * `NotificationSchedulerRegressionTest`). Three reasons, not one: the int is lossy where a string need not
 * be, clamping `abs(Int.MIN_VALUE)` to `0` lets two unrelated notifications share a request code, and the
 * receiver derives its four action request codes by ADDING 1..4 to that same int, so a key that is one
 * notification's can be another's action button. A caller cannot hold two keys for one task here, which is
 * the property the copy never had.
 *
 * Also not copied: reading the clock twice in one operation. The Android scheduler reads `now` when it
 * computes a snooze target and reads it AGAIN when it tests whether the result is in the future, so a task
 * can be refused by a comparison against a moment that had already passed when the decision was made. Here
 * the caller passes one [now] and every comparison uses it.
 */
object ReminderDecider {
    /** The advance used when the task names none. Fifteen minutes, as before. */
    const val DEFAULT_ADVANCE_MINUTES: Long = 15L

    /** How far a snooze pushes a reminder that has no later due time to fall back on. */
    const val DEFAULT_SNOOZE_MINUTES: Long = 15L

    private const val MODE_OFF = "off"
    private const val MODE_CUSTOM = "custom"
    private const val STATUS_PENDING = "pending"
    private const val STATUS_ACTIVE = "active"

    /**
     * The ONE derivation of a reminder's dedup key.
     *
     * Every key in the app comes from here, which is what makes the guarantee checkable: a task's key is the
     * task's id, so re-deciding for the same task yields the same key and therefore REPLACES the previous
     * reminder instead of stacking a second one, while two different tasks can never share a key. The id is
     * carried whole rather than hashed, so no two keys can collide and nothing has to be clamped.
     */
    fun dedupKeyFor(taskId: String): String = "$DEDUP_KEY_PREFIX$taskId"

    /**
     * Where a snooze should fire.
     *
     * The task's own due time when that time is still ahead of now — the user snoozed a task that has not
     * come due yet, so reminding them at it is what they asked for — and fifteen minutes from now when there
     * is no such time, which is the case that actually needs a default.
     */
    fun snoozeFireAt(
        now: LocalDateTime,
        dueAt: LocalDateTime?,
    ): LocalDateTime = dueAt?.takeIf { it.isAfter(now) } ?: now.plusMinutes(DEFAULT_SNOOZE_MINUTES)

    /**
     * Answers, in plain values, whether [facts] should be reminded about at [now], when, under which key,
     * and what a tap should do.
     *
     * The order of the checks is the Android scheduler's and is part of the contract: eligibility before
     * due time before advance before the in-the-future test. A task that is both completed AND has
     * reminders off is reported as completed, and a task whose advance would land in the past is reported
     * as a past fire time rather than as an off-mode task, so a caller reading one reason gets the one that
     * would actually have to be fixed.
     *
     * @param now the single moment every comparison in this call is made against.
     */
    fun decide(
        facts: ReminderFacts,
        now: LocalDateTime,
        policy: ReminderPolicy = ReminderPolicy(),
    ): NotificationDecision {
        val key = dedupKeyFor(facts.taskId)
        if (!policy.remindersEnabled) {
            return NotificationDecision.Skip(key, ReminderSkipReason.REMINDERS_DISABLED)
        }
        val ineligible = skipReasonFor(facts, policy)
        if (ineligible != null) {
            return NotificationDecision.Skip(key, ineligible)
        }
        val dueAt =
            facts.overrideDueAt ?: facts.dueAt
                ?: return NotificationDecision.Skip(key, ReminderSkipReason.NO_DUE_TIME)
        // An override REPLACES the computed fire time rather than being measured against it, so a snooze
        // never has the advance subtracted from it a second time.
        val fireAt = facts.overrideFireAt ?: dueAt.minusMinutes(resolveAdvanceMinutes(facts))
        if (!fireAt.isAfter(now)) {
            return NotificationDecision.Skip(key, ReminderSkipReason.FIRE_TIME_NOT_IN_FUTURE)
        }
        return NotificationDecision.Fire(
            dedupKey = key,
            fireAt = fireAt,
            taskTitle = facts.taskTitle,
            tapAction = NotificationTapAction.OpenTask(facts.taskId),
            reminderCopy = resolveCopy(facts, fireAt, dueAt),
        )
    }

    /**
     * The reason [facts] is ineligible, or null when it is eligible.
     *
     * Written once, read once: a gate that re-derived the answer would be free to disagree with the reason
     * it reports, and the disagreement would be invisible.
     */
    private fun skipReasonFor(
        facts: ReminderFacts,
        policy: ReminderPolicy,
    ): ReminderSkipReason? {
        val status = facts.status.lowercase()
        if (status != STATUS_PENDING && status != STATUS_ACTIVE) {
            return ReminderSkipReason.STATUS_INELIGIBLE
        }
        if (modeOf(facts) == MODE_OFF) {
            return ReminderSkipReason.NOTIFICATIONS_OFF
        }
        if (policy.minimalModeEnabled && facts.recurrenceEnabled) {
            return ReminderSkipReason.RECURRING_TASK_IN_MINIMAL_MODE
        }
        return null
    }

    /** The mode as it is read: null means the default mode, and matching is case-insensitive. */
    private fun modeOf(facts: ReminderFacts): String = facts.notificationMode?.lowercase() ?: "auto"

    /**
     * The advance in minutes for an already-eligible task.
     *
     * `custom` honours a positive [ReminderFacts.customNotificationMinutes] and otherwise falls back to the
     * default, which is what keeps `custom = 0` from arming a reminder in the past and then refusing it;
     * every other mode uses the default, so a typo in stored data still reminds rather than going quiet.
     *
     * There is no `off` case because an `off` task never reaches here — the gate in [skipReasonFor] refuses
     * it first — and a branch here for a state that cannot occur would be a rule that reads as if it were
     * doing the refusing when it is not.
     */
    private fun resolveAdvanceMinutes(facts: ReminderFacts): Long =
        when (modeOf(facts)) {
            MODE_CUSTOM -> facts.customNotificationMinutes?.takeIf { it > 0 }?.toLong() ?: DEFAULT_ADVANCE_MINUTES
            else -> DEFAULT_ADVANCE_MINUTES
        }

    /**
     * The copy line for a reminder that WILL fire.
     *
     * The minutes are measured from the fire time to the due time, not from now: a snooze's fire time is
     * not fifteen minutes before the due time, and reporting the advance that was nominally configured
     * would misdescribe it.
     */
    private fun resolveCopy(
        facts: ReminderFacts,
        fireAt: LocalDateTime,
        dueAt: LocalDateTime,
    ): ReminderCopy {
        val clockText = facts.dueTimeText ?: return ReminderCopy.NoDueTime
        if (facts.isSnoozed) {
            return ReminderCopy.SnoozedUntil(clockText)
        }
        val minutesUntilDue = Duration.between(fireAt, dueAt).toMinutes()
        return if (minutesUntilDue > 0) {
            ReminderCopy.DueIn(minutesUntilDue, clockText)
        } else {
            ReminderCopy.DueAt(clockText)
        }
    }

    /** Namespaced so a key cannot be confused with any other string this app persists. */
    private const val DEDUP_KEY_PREFIX = "payanam:task-reminder:"
}
