//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

/**
 * Per-filter row counts for the one-time task list, shown as filter chips;
 * looked up by [countFor].
 */
data class TaskFilterCounts(
    val all: Int = 0,
    val active: Int = 0,
    val today: Int = 0,
    val overdue: Int = 0,
    val future: Int = 0,
    val completed: Int = 0,
    val archived: Int = 0,
    val notActive: Int = 0,
) {
    /**
     * Row count for the given task [filter] chip.
     */
    fun countFor(filter: TaskFilter): Int =
        when (filter) {
            TaskFilter.ALL -> all
            TaskFilter.ACTIVE -> active
            TaskFilter.TODAY -> today
            TaskFilter.OVERDUE -> overdue
            TaskFilter.FUTURE -> future
            TaskFilter.COMPLETED -> completed
            TaskFilter.ARCHIVED -> archived
            TaskFilter.NOT_ACTIVE -> notActive
        }
}
