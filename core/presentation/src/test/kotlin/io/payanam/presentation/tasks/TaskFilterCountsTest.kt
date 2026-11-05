//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The per-filter row counts the Tasks chrome renders on its filter chips.
 *
 * `countFor` is a `when` over the filter enum, so the thing worth pinning is that each chip reads *its own*
 * count: the counts are all `Int`s with the same default, which means a copy-paste slip in that `when` produces
 * a chip that silently shows another chip's number — invisible in review, wrong on screen. Every field is given
 * a distinct value here for exactly that reason.
 */
class TaskFilterCountsTest {
    private val counts =
        TaskFilterCounts(
            all = 1,
            active = 2,
            today = 3,
            overdue = 4,
            future = 5,
            completed = 6,
            archived = 7,
            notActive = 8,
        )

    @Test
    fun `each filter reads its own count`() {
        assertThat(counts.countFor(TaskFilter.ALL)).isEqualTo(1)
        assertThat(counts.countFor(TaskFilter.ACTIVE)).isEqualTo(2)
        assertThat(counts.countFor(TaskFilter.TODAY)).isEqualTo(3)
        assertThat(counts.countFor(TaskFilter.OVERDUE)).isEqualTo(4)
        assertThat(counts.countFor(TaskFilter.FUTURE)).isEqualTo(5)
        assertThat(counts.countFor(TaskFilter.COMPLETED)).isEqualTo(6)
        assertThat(counts.countFor(TaskFilter.ARCHIVED)).isEqualTo(7)
        assertThat(counts.countFor(TaskFilter.NOT_ACTIVE)).isEqualTo(8)
    }

    @Test
    fun `every filter is answered`() {
        val answers = TaskFilter.entries.map { counts.countFor(it) }

        assertThat(answers).containsExactly(1, 2, 3, 4, 5, 6, 7, 8).inOrder()
    }

    @Test
    fun `a fresh set of counts is all zeroes`() {
        val empty = TaskFilterCounts()

        assertThat(TaskFilter.entries.map { empty.countFor(it) }).containsExactly(0, 0, 0, 0, 0, 0, 0, 0).inOrder()
    }
}
