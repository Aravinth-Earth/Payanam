//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.notes

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Test

/**
 * DesktopNoteContractsTest.
 */
class DesktopNoteContractsTest {
    @Test
    fun `create record normalizes title details tags and dimension fallback`() {
        val now = LocalDateTime.parse("2026-04-02T08:30:00")

        val record =
            DesktopNoteContracts.createRecord(
                id = "note-1",
                title = "  First note  ",
                details = "  Something detailed  ",
                dimensionId = null,
                dimensionLabel = "",
                tags = listOf("alpha", " alpha ", "", "beta"),
                now = now,
            )

        /** Assert that. */
        assertThat(record.title).isEqualTo("First note")
        /** Assert that. */
        assertThat(record.details).isEqualTo("Something detailed")
        /** Assert that. */
        assertThat(record.dimensionId).isEqualTo(DesktopNoteContracts.DEFAULT_DIMENSION_ID)
        /** Assert that. */
        assertThat(record.dimensionLabel).isEqualTo(DesktopNoteContracts.DEFAULT_DIMENSION_LABEL)
        /** Assert that. */
        assertThat(record.tags).containsExactly("alpha", "beta").inOrder()
        /** Assert that. */
        assertThat(record.createdAtIso).isEqualTo(now.toString())
        /** Assert that. */
        assertThat(record.updatedAtIso).isEqualTo(now.toString())
    }

    @Test
    fun `update record preserves existing dimension when new id is invalid`() {
        val now = LocalDateTime.parse("2026-04-02T09:00:00")
        val existing =
            /** Desktop note record. */
            DesktopNoteRecord(
                id = "note-2",
                title = "Existing",
                details = "Details",
                dimensionId = "dim_mental_health",
                dimensionLabel = "Mental Health",
                tags = listOf("old"),
                createdAtIso = "2026-04-01T09:00:00",
                updatedAtIso = "2026-04-01T09:00:00",
            )

        val updated =
            DesktopNoteContracts.updateRecord(
                existing = existing,
                title = "  Updated  ",
                details = "   ",
                dimensionId = "",
                dimensionLabel = "",
                tags = listOf("new", "new", "focus"),
                now = now,
            )

        /** Assert that. */
        assertThat(updated.title).isEqualTo("Updated")
        /** Assert that. */
        assertThat(updated.details).isNull()
        /** Assert that. */
        assertThat(updated.dimensionId).isEqualTo(existing.dimensionId)
        /** Assert that. */
        assertThat(updated.dimensionLabel).isEqualTo(existing.dimensionLabel)
        /** Assert that. */
        assertThat(updated.tags).containsExactly("new", "focus").inOrder()
        /** Assert that. */
        assertThat(updated.createdAtIso).isEqualTo(existing.createdAtIso)
        /** Assert that. */
        assertThat(updated.updatedAtIso).isEqualTo(now.toString())
    }
}
