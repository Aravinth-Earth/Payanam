//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.transfer.BackupJsonContract
import io.payanam.shared.transfer.DataModuleSelection
import org.junit.Test

class SettingsFoundationContractsTest {
    @Test
    fun `snapshot keeps shared schema version and module selection`() {
        val selection = DataModuleSelection(tasks = true, timeEntries = false, notes = true)

        val snapshot = SettingsFoundationContracts.snapshot(moduleSelection = selection)
        assertThat(snapshot.schemaVersion).isEqualTo(BackupJsonContract.SCHEMA_VERSION)
        assertThat(snapshot.moduleSelection).isEqualTo(selection)
    }

    @Test
    fun `default areas preserve the current desktop foundation order`() {
        val snapshot = SettingsFoundationContracts.snapshot()
        assertThat(snapshot.areas.map { it.id })
            .containsExactly(
                "settings_transfer",
                "settings_structure",
                "tasks_time",
                "notes_lenses",
            ).inOrder()
        assertThat(snapshot.areasWithStatus(FoundationReadiness.SharedReady)).isEqualTo(1)
        assertThat(snapshot.areasWithStatus(FoundationReadiness.ExtractionNext)).isEqualTo(2)
        assertThat(snapshot.areasWithStatus(FoundationReadiness.AndroidOnly)).isEqualTo(1)
    }

    @Test
    fun `foundation area exposes title and summary for the checklist UI`() {
        val area = FoundationArea(
            id = "test_area",
            title = "Test Area",
            status = FoundationReadiness.ExtractionNext,
            summary = "A test summary.",
        )
        assertThat(area.id).isEqualTo("test_area")
        assertThat(area.title).isEqualTo("Test Area")
        assertThat(area.status).isEqualTo(FoundationReadiness.ExtractionNext)
        assertThat(area.summary).isEqualTo("A test summary.")
    }

    @Test
    fun `areasWithStatus counts only matching entries in a custom list`() {
        val snapshot =
            SettingsFoundationSnapshot(
                schemaVersion = 1,
                moduleSelection = DataModuleSelection(),
                areas =
                    listOf(
                        FoundationArea("a", "A", FoundationReadiness.SharedReady, ""),
                        FoundationArea("b", "B", FoundationReadiness.SharedReady, ""),
                        FoundationArea("c", "C", FoundationReadiness.AndroidOnly, ""),
                    ),
            )
        assertThat(snapshot.areasWithStatus(FoundationReadiness.SharedReady)).isEqualTo(2)
        assertThat(snapshot.areasWithStatus(FoundationReadiness.ExtractionNext)).isEqualTo(0)
        assertThat(snapshot.areasWithStatus(FoundationReadiness.AndroidOnly)).isEqualTo(1)
    }
}
