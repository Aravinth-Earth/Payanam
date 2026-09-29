//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TaskFilter] is read back from a persisted string, so every stored key must resolve and an unknown one
 * must land on the documented default rather than throwing.
 */
class TaskFilterTest {
    @Test
    fun `every entry resolves from its own key`() {
        TaskFilter.entries.forEach { filter ->
            assertEquals(filter, TaskFilter.fromKey(filter.key))
        }
    }

    @Test
    fun `unknown and null keys fall back to TODAY`() {
        assertEquals(TaskFilter.TODAY, TaskFilter.fromKey(null))
        assertEquals(TaskFilter.TODAY, TaskFilter.fromKey("not_a_filter"))
        assertEquals(TaskFilter.TODAY, TaskFilter.fromKey(""))
    }

    @Test
    fun `displayName is the persisted key`() {
        assertEquals("archived", TaskFilter.ARCHIVED.displayName)
    }
}
