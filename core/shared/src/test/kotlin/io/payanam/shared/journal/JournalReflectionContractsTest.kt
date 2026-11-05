//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.journal

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime

class JournalReflectionContractsTest {
    @Test
    fun `upsert overall response creates day and trims value`() {
        val now = LocalDateTime.parse("2026-04-02T09:30:00")

        val snapshot =
            JournalReflectionContracts.upsertOverallResponse(
                snapshot = JournalReflectionContracts.emptySnapshot(),
                dateIso = "2026-04-02",
                promptKey = "gratitude",
                response = "  Family time  ",
                now = now,
            )
        assertThat(snapshot.days).hasSize(1)
        assertThat(snapshot.days.single().overallResponses["gratitude"]).isEqualTo("Family time")
    }

    @Test
    fun `upsert dimension response removes empty prompt and empty dimension`() {
        val now = LocalDateTime.parse("2026-04-02T09:45:00")
        val seeded =
            JournalReflectionContracts.upsertDimensionResponse(
                snapshot = JournalReflectionContracts.emptySnapshot(),
                dateIso = "2026-04-02",
                dimensionId = "dim_learning_growth",
                promptKey = "progress",
                response = "Studied Kotlin",
                now = now,
            )

        val cleared =
            JournalReflectionContracts.upsertDimensionResponse(
                snapshot = seeded,
                dateIso = "2026-04-02",
                dimensionId = "dim_learning_growth",
                promptKey = "progress",
                response = "   ",
                now = now.plusMinutes(15),
            )
        assertThat(cleared.days.single().dimensionResponses).doesNotContainKey("dim_learning_growth")
    }

    @Test
    fun `newly created day record stamps createdAtIso and updatedAtIso`() {
        val now = LocalDateTime.parse("2026-04-02T09:30:00")

        val snapshot =
            JournalReflectionContracts.upsertOverallResponse(
                snapshot = JournalReflectionContracts.emptySnapshot(),
                dateIso = "2026-04-02",
                promptKey = "gratitude",
                response = "Family time",
                now = now,
            )
        val day = snapshot.days.single()
        assertThat(day.createdAtIso).isEqualTo("2026-04-02T09:30")
        assertThat(day.updatedAtIso).isEqualTo("2026-04-02T09:30")
    }

    @Test
    fun `updating an existing day preserves createdAtIso but advances updatedAtIso`() {
        val created = LocalDateTime.parse("2026-04-02T09:30:00")
        val updated = LocalDateTime.parse("2026-04-02T18:00:00")

        val initial =
            JournalReflectionContracts.upsertOverallResponse(
                snapshot = JournalReflectionContracts.emptySnapshot(),
                dateIso = "2026-04-02",
                promptKey = "gratitude",
                response = "Family time",
                now = created,
            )

        val revised =
            JournalReflectionContracts.upsertOverallResponse(
                snapshot = initial,
                dateIso = "2026-04-02",
                promptKey = "gratitude",
                response = "Family time and a walk",
                now = updated,
            )
        val day = revised.days.single()
        assertThat(day.createdAtIso).isEqualTo("2026-04-02T09:30")
        assertThat(day.updatedAtIso).isEqualTo("2026-04-02T18:00")
    }

    @Test
    fun `upsert dimension response stamps createdAtIso and updatedAtIso on a new day`() {
        val now = LocalDateTime.parse("2026-04-02T20:00:00")

        val snapshot =
            JournalReflectionContracts.upsertDimensionResponse(
                snapshot = JournalReflectionContracts.emptySnapshot(),
                dateIso = "2026-04-02",
                dimensionId = "dim_learning_growth",
                promptKey = "progress",
                response = "Studied Kotlin",
                now = now,
            )
        val day = snapshot.days.single()
        assertThat(day.createdAtIso).isEqualTo("2026-04-02T20:00")
        assertThat(day.updatedAtIso).isEqualTo("2026-04-02T20:00")
    }

    @Test
    fun `dayForDate returns null when the snapshot has no matching day`() {
        val snapshot = JournalReflectionContracts.emptySnapshot()
        assertThat(JournalReflectionContracts.dayForDate(snapshot, "2026-04-02")).isNull()
    }

    @Test
    fun `dayForDate finds the record for the requested date`() {
        val now = LocalDateTime.parse("2026-04-02T09:30:00")
        val snapshot =
            JournalReflectionContracts.upsertOverallResponse(
                snapshot = JournalReflectionContracts.emptySnapshot(),
                dateIso = "2026-04-02",
                promptKey = "gratitude",
                response = "Family time",
                now = now,
            )
        val found = JournalReflectionContracts.dayForDate(snapshot, "2026-04-02")
        assertThat(found).isNotNull()
        assertThat(found!!.dateIso).isEqualTo("2026-04-02")
        assertThat(found.overallResponses["gratitude"]).isEqualTo("Family time")
    }

    @Test
    fun `upsert overall response on a new day sorts it into the snapshot`() {
        val now = LocalDateTime.parse("2026-04-03T08:00:00")
        val earlier =
            JournalReflectionContracts.upsertOverallResponse(
                snapshot = JournalReflectionContracts.emptySnapshot(),
                dateIso = "2026-04-02",
                promptKey = "gratitude",
                response = "Family time",
                now = now.minusDays(1),
            )

        val later =
            JournalReflectionContracts.upsertOverallResponse(
                snapshot = earlier,
                dateIso = "2026-04-03",
                promptKey = "gratitude",
                response = "A good night sleep",
                now = now,
            )
        assertThat(later.days.map { it.dateIso })
            .containsExactly("2026-04-03", "2026-04-02")
            .inOrder()
    }
}
