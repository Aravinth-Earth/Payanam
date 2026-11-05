//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

/**
 * One frequency window's verdict.
 *
 * `isSatisfied` and `completionRatio` are the two properties the recurrence score is built from, and
 * `completionRatio` carries the only branch that can silently invent progress: an effective target of zero or
 * less reports 1.0 rather than dividing, because a window with nothing to do is not a window that was missed.
 * The boundary cases are pinned here so the guard cannot be tightened into a division or loosened into a
 * blanket pass without the recurrence score changing underneath it.
 */
class FrequencyWindowSummaryTest {
    private fun window(
        coveredDays: Int = 7,
        completedCount: Int = 3,
        skippedCount: Int = 0,
        targetCount: Int = 4,
        effectiveTargetCount: Int = 4,
    ) = FrequencyWindowSummary(
        start = LocalDate.of(2026, 9, 15),
        end = LocalDate.of(2026, 9, 21),
        coveredDays = coveredDays,
        completedCount = completedCount,
        skippedCount = skippedCount,
        targetCount = targetCount,
        effectiveTargetCount = effectiveTargetCount,
    )

    @Test
    fun `a window is satisfied when completions reach the effective target`() {
        assertThat(window(completedCount = 4, effectiveTargetCount = 4).isSatisfied).isTrue()
        assertThat(window(completedCount = 5, effectiveTargetCount = 4).isSatisfied).isTrue()
        assertThat(window(completedCount = 3, effectiveTargetCount = 4).isSatisfied).isFalse()
    }

    @Test
    fun `the completion ratio divides completions by the effective target`() {
        assertThat(window(completedCount = 3, effectiveTargetCount = 4).completionRatio).isEqualTo(0.75)
        assertThat(window(completedCount = 0, effectiveTargetCount = 5).completionRatio).isEqualTo(0.0)
        assertThat(window(completedCount = 9, effectiveTargetCount = 3).completionRatio).isEqualTo(3.0)
    }

    @Test
    fun `a window with no effective target reports a full ratio instead of dividing`() {
        assertThat(window(completedCount = 0, effectiveTargetCount = 0).completionRatio).isEqualTo(1.0)
        assertThat(window(completedCount = 0, effectiveTargetCount = -1).completionRatio).isEqualTo(1.0)
    }

    @Test
    fun `the summary is a value object over its whole range`() {
        val summary = window()
        val same = summary.copy()

        assertThat(same).isEqualTo(summary)
        assertThat(same.hashCode()).isEqualTo(summary.hashCode())
        assertThat(summary).isNotEqualTo(window(completedCount = 4))

        val (start, end, days, completed, skipped, target, effective) = summary
        assertThat(start).isEqualTo(LocalDate.of(2026, 9, 15))
        assertThat(end).isEqualTo(LocalDate.of(2026, 9, 21))
        assertThat(listOf(days, completed, skipped, target, effective)).containsExactly(7, 3, 0, 4, 4)
        assertThat(summary.toString()).contains("2026-09-15")
    }
}
