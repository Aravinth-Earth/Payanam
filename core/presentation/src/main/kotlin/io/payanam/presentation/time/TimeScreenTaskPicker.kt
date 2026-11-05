//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import io.payanam.domain.model.Task

/**
 * The list of tasks the Time screen offers in its task picker: the [dueTasks]
 * planned for the selected date first, then any other pending task, each
 * appearing once.
 *
 * Only pending tasks are offered - a task that is already completed, skipped,
 * missed or archived cannot be started from the picker. Ordering matters: the
 * day's planned tasks lead, because they are the ones the user means to pick.
 *
 * @param dueTasks the tasks planned for the selected date
 * @param allTasks every task the screen has loaded
 */
fun buildTaskPickerTasks(
    dueTasks: List<Task>,
    allTasks: List<Task>,
): List<Task> {
    val duePending = dueTasks.filter { it.status == "pending" }.distinctBy { it.id }
    val allPending = allTasks.filter { it.status == "pending" }.distinctBy { it.id }
    return (duePending + allPending).distinctBy { it.id }
}
