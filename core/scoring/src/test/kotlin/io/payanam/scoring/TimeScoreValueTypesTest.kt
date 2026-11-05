//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.scoring

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The time-score result types as value objects.
 *
 * These DTOs travel from the scoring engine into the shared ViewModels' state, which is why their generated
 * members are worth pinning rather than leaving to chance: a ViewModel compares two `DayTimeScore`s to decide
 * whether to re-emit state (so `equals`/`hashCode` must be structural and consistent), and copies one to adjust
 * a single field (so `copy` must not alias the per-dimension list). `toString` is asserted only to prove the
 * fields are all part of the value — a debug log that hides a field is how a scoring bug goes unnoticed.
 */
class TimeScoreValueTypesTest {
    private val adherence =
        DimensionTimeScore(
            dimensionId = "dim_work_livelihood",
            plannedMinutes = 240,
            actualMinutes = 195L,
            score = 0.75,
        )

    private val otherDimension =
        DimensionTimeScore(
            dimensionId = "dim_health",
            plannedMinutes = 60,
            actualMinutes = 90L,
            score = 1.0,
        )

    private val day =
        DayTimeScore(
            dayKey = "2026-09-21",
            dimensionScores = listOf(adherence, otherDimension),
            overallScore = 0.82,
            isPlanned = true,
        )

    @Test
    fun `two dimension scores with the same fields are equal and share a hash`() {
        val same = adherence.copy()

        assertThat(same).isEqualTo(adherence)
        assertThat(same.hashCode()).isEqualTo(adherence.hashCode())
        assertThat(adherence).isNotEqualTo(otherDimension)
    }

    @Test
    fun `copy changes only the named field`() {
        val rescored = adherence.copy(score = 0.4)

        assertThat(rescored.score).isEqualTo(0.4)
        assertThat(rescored.dimensionId).isEqualTo(adherence.dimensionId)
        assertThat(rescored.actualMinutes).isEqualTo(195L)
        assertThat(adherence.score).isEqualTo(0.75)
    }

    @Test
    fun `a dimension score destructures in declaration order`() {
        val (dimensionId, planned, actual, score) = adherence

        assertThat(dimensionId).isEqualTo("dim_work_livelihood")
        assertThat(planned).isEqualTo(240)
        assertThat(actual).isEqualTo(195L)
        assertThat(score).isEqualTo(0.75)
        assertThat(adherence.toString()).contains("dim_work_livelihood")
    }

    @Test
    fun `two day scores with the same fields are equal and share a hash`() {
        val same = day.copy()

        assertThat(same).isEqualTo(day)
        assertThat(same.hashCode()).isEqualTo(day.hashCode())
        assertThat(day).isNotEqualTo(day.copy(dayKey = "2026-09-22"))
    }

    @Test
    fun `a day score destructures and keeps its per-dimension breakdown`() {
        val (dayKey, scores, overall, planned) = day

        assertThat(dayKey).isEqualTo("2026-09-21")
        assertThat(scores).containsExactly(adherence, otherDimension).inOrder()
        assertThat(overall).isEqualTo(0.82)
        assertThat(planned).isTrue()

        val unplanned = day.copy(isPlanned = false)
        assertThat(unplanned.isPlanned).isFalse()
        assertThat(unplanned.dimensionScores).isEqualTo(day.dimensionScores)
        assertThat(day.toString()).contains("2026-09-21")
    }
}
