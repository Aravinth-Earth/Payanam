//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.journal

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Test

/**
 * JournalReflectionContractsTest.
 */
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

        /** Assert that. */
        assertThat(snapshot.days).hasSize(1)
        /** Assert that. */
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

        /** Assert that. */
        assertThat(cleared.days.single().dimensionResponses).doesNotContainKey("dim_learning_growth")
    }
}
