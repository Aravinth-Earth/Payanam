//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.notes

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import org.junit.Test
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
        assertThat(record.title).isEqualTo("First note")
        assertThat(record.details).isEqualTo("Something detailed")
        assertThat(record.dimensionId).isEqualTo(DesktopNoteContracts.DEFAULT_DIMENSION_ID)
        assertThat(record.dimensionLabel).isEqualTo(DesktopNoteContracts.DEFAULT_DIMENSION_LABEL)
        assertThat(record.tags).containsExactly("alpha", "beta").inOrder()
        assertThat(record.createdAtIso).isEqualTo(now.toString())
        assertThat(record.updatedAtIso).isEqualTo(now.toString())
    }

    @Test
    fun `update record preserves existing dimension when new id is invalid`() {
        val now = LocalDateTime.parse("2026-04-02T09:00:00")
        val existing =
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
        assertThat(updated.title).isEqualTo("Updated")
        assertThat(updated.details).isNull()
        assertThat(updated.dimensionId).isEqualTo(existing.dimensionId)
        assertThat(updated.dimensionLabel).isEqualTo(existing.dimensionLabel)
        assertThat(updated.tags).containsExactly("new", "focus").inOrder()
        assertThat(updated.createdAtIso).isEqualTo(existing.createdAtIso)
        assertThat(updated.updatedAtIso).isEqualTo(now.toString())
    }
}
