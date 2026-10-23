//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.time

import io.payanam.domain.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TimeScreenTaskPickerTest {
    @Test
    fun planned_tasks_lead_and_other_pending_tasks_follow() {
        val planned = task(id = "p1")
        val other = task(id = "o1")

        val picker = buildTaskPickerTasks(listOf(planned), listOf(other, planned))

        assertEquals(listOf("p1", "o1"), picker.map { it.id })
    }

    @Test
    fun a_planned_task_is_not_offered_twice_when_it_is_also_in_the_full_list() {
        val planned = task(id = "p1")

        val picker = buildTaskPickerTasks(listOf(planned), listOf(planned))

        assertEquals(listOf("p1"), picker.map { it.id })
    }

    @Test
    fun duplicate_ids_within_the_planned_tasks_are_collapsed_to_the_first() {
        val first = task(id = "p1", title = "first")
        val second = task(id = "p1", title = "second")

        val picker = buildTaskPickerTasks(listOf(first, second), emptyList())

        assertEquals(listOf("first"), picker.map { it.title })
    }

    @Test
    fun duplicate_ids_within_the_full_list_are_collapsed_to_the_first() {
        val picker = buildTaskPickerTasks(emptyList(), listOf(task(id = "o1"), task(id = "o1")))

        assertEquals(listOf("o1"), picker.map { it.id })
    }

    @Test
    fun non_pending_planned_tasks_are_not_offered() {
        val done = task(id = "p1", status = "completed")
        val skipped = task(id = "p2", status = "skipped")
        val missed = task(id = "p3", status = "missed")
        val archived = task(id = "p4", status = "archived")

        val picker = buildTaskPickerTasks(listOf(done, skipped, missed, archived), emptyList())

        assertTrue(picker.isEmpty())
    }

    @Test
    fun non_pending_tasks_in_the_full_list_are_not_offered() {
        val picker =
            buildTaskPickerTasks(
                emptyList(),
                listOf(task(id = "o1", status = "completed"), task(id = "o2", status = "archived")),
            )

        assertTrue(picker.isEmpty())
    }

    @Test
    fun a_pending_task_in_the_full_list_is_offered_even_when_it_was_never_planned() {
        val picker = buildTaskPickerTasks(listOf(task(id = "p1", status = "completed")), listOf(task(id = "o1")))

        assertEquals(listOf("o1"), picker.map { it.id })
    }

    @Test
    fun an_empty_planned_list_offers_only_the_full_list() {
        val picker = buildTaskPickerTasks(emptyList(), listOf(task(id = "o1"), task(id = "o2")))

        assertEquals(listOf("o1", "o2"), picker.map { it.id })
    }

    @Test
    fun two_empty_lists_offer_nothing() {
        assertTrue(buildTaskPickerTasks(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun the_full_list_does_not_reorder_the_planned_tasks_that_already_come_first() {
        val picker = buildTaskPickerTasks(listOf(task(id = "p1")), listOf(task(id = "p1"), task(id = "o1")))

        assertEquals(listOf("p1", "o1"), picker.map { it.id })
    }

    private fun task(
        id: String,
        status: String = "pending",
        title: String = "task-$id",
    ): Task {
        val now = LocalDateTime.of(2026, 4, 9, 9, 0)
        return Task(
            id = id,
            title = title,
            status = status,
            createdAt = now,
            updatedAt = now,
        )
    }
}