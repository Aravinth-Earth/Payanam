//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

/**
 * A reminder to be shown to the user at a point in time.
 *
 * Deliberately a plain data description rather than anything platform-shaped: what a reminder IS is the
 * same on both platforms (an id to cancel it by, a time, and the copy), while how it is surfaced is not.
 * The Android side turns this into a channel notification with a task deep-link; the desktop has no such
 * surface yet and says so rather than pretending.
 *
 * [id] is the caller's key and must be stable across a reschedule, because rescheduling is cancel-then-
 * schedule under the same id — a fresh id per call would leave the previous reminder alive and the user
 * would be told twice.
 */
data class ReminderRequest(
    val id: String,
    /** Epoch millis at which the reminder should fire. */
    val atEpochMillis: Long,
    val title: String,
    val body: String,
    /** The task this reminder is about, when there is one — the platform may use it to deep-link. */
    val relatedTaskId: String? = null,
)

/** Where a platform surfaces reminders. */
interface ReminderSink {
    /**
     * Schedules [request], replacing any existing reminder with the same [ReminderRequest.id].
     *
     * Replacing rather than adding is the honest semantic: a task's reminder moves when its due time moves,
     * and the caller cannot be expected to cancel first without racing itself.
     */
    suspend fun schedule(request: ReminderRequest)

    /** Cancels the reminder with [id], if it exists. Cancelling an unknown id is not an error. */
    suspend fun cancel(id: String)

    /**
     * Cancels every reminder this sink has scheduled.
     *
     * Exists because the two platforms disagree about ownership: Android's scheduler can enumerate the
     * tasks that are eligible and reconcile from the data, while a platform whose reminders live only in
     * the OS needs to be told to clear them when the user turns reminders off.
     */
    suspend fun cancelAll()
}

/** Whether a platform can surface reminders at all. */
enum class ReminderCapability {
    /** Reminders are delivered to the user. */
    SUPPORTED,

    /**
     * The sink accepts and remembers reminders, but nothing is shown to the user yet.
     *
     * A platform in this state must be reported as such so the UI can say so, instead of offering a
     * reminder toggle that silently does nothing.
     */
    NOT_SURFACED,
}
