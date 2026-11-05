//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.presentation.tasks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The two persisted sort orders and their key round-trips.
 *
 * These are read back from settings, so `fromKey` is what stands between a stored preference and the order the
 * user sees. The properties worth pinning are that every option survives its own key (a renamed `key` without a
 * matching migration silently resets the user's sort), that unknown and null keys fall back deliberately rather
 * than throwing, and for habits that the legacy keys of the pre-simplification 18-option model still resolve —
 * the migration table exists only for preferences saved by older builds, so nothing else exercises it.
 */
class TaskSortOptionTest {
    @Test
    fun `every task sort option round-trips through its key`() {
        TaskSortOption.entries.forEach { option ->
            assertThat(TaskSortOption.fromKey(option.key)).isEqualTo(option)
        }
    }

    @Test
    fun `an unknown or null task sort key falls back to due-date-ascending`() {
        assertThat(TaskSortOption.fromKey(null)).isEqualTo(TaskSortOption.DUE_DATE_ASC)
        assertThat(TaskSortOption.fromKey("not_a_sort")).isEqualTo(TaskSortOption.DUE_DATE_ASC)
    }

    @Test
    fun `task sort keys are unique`() {
        assertThat(TaskSortOption.entries.map { it.key }).containsNoDuplicates()
    }

    @Test
    fun `displayName is the persisted key the sort was stored under`() {
        assertThat(TaskSortOption.SCORE_DESC.displayName).isEqualTo("score_desc")
        assertThat(HabitSortOption.BY_NAME.displayName).isEqualTo("by_name")
    }

    @Test
    fun `every habit sort option round-trips through its key`() {
        HabitSortOption.entries.forEach { option ->
            assertThat(HabitSortOption.fromKey(option.key)).isEqualTo(option)
        }
    }

    @Test
    fun `a legacy habit sort key migrates instead of resetting the preference`() {
        // Saved by a build that still offered the 18-option model; both keys must reach the same simplified
        // order, and neither may fall through to the default for an unknown key.
        assertThat(HabitSortOption.fromKey("running_avg_desc")).isEqualTo(HabitSortOption.SCORE_HIGH_LOW)
        assertThat(HabitSortOption.fromKey("streak_net_asc")).isEqualTo(HabitSortOption.SCORE_LOW_HIGH)
    }

    @Test
    fun `an unknown or null habit sort key falls back to score-high-low`() {
        assertThat(HabitSortOption.fromKey(null)).isEqualTo(HabitSortOption.SCORE_HIGH_LOW)
        assertThat(HabitSortOption.fromKey("not_a_sort")).isEqualTo(HabitSortOption.SCORE_HIGH_LOW)
    }

    @Test
    fun `no legacy habit key collides with a live key`() {
        val live = HabitSortOption.entries.map { it.key }.toSet()

        assertThat(live).doesNotContain("running_avg_desc")
        assertThat(HabitSortOption.entries.map { it.key }).containsNoDuplicates()
    }
}
