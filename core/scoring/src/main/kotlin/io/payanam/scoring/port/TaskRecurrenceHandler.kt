//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring.port

import io.payanam.domain.model.Task
import io.payanam.scoring.CompletionStats

/**
 * Advances a recurring task's schedule when its status changes, and reports its completion history.
 *
 * Declared here rather than in `:core:domain` because [getCompletionStats] hands back [CompletionStats], which
 * the scoring module owns — and `:core:scoring` already depends on `:core:domain`, so the reverse would be a
 * dependency cycle. The implementation stays in the app: it needs the repositories' recurrence logic.
 */
interface TaskRecurrenceHandler {
    /**
     * Whether [task] is a frequency habit rather than a plain recurring task.
     */
    fun isFrequencyHabit(task: Task): Boolean

    /**
     * Records completion of [task] and advances its next occurrence.
     */
    suspend fun onTaskCompleted(task: Task, note: String? = null, reason: String? = null, nextDueStrategy: String? = null)

    /**
     * Records that [task] was skipped and advances its next occurrence.
     */
    suspend fun onTaskSkipped(task: Task, note: String? = null, reason: String? = null, nextDueStrategy: String? = null)

    /**
     * Records that [task] was missed and advances its next occurrence.
     */
    suspend fun onTaskMissed(task: Task, note: String? = null, reason: String? = null, nextDueStrategy: String? = null)

    /**
     * Returns the completion history for [task].
     */
    suspend fun getCompletionStats(task: Task): CompletionStats
}
