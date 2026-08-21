//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.settings

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.transfer.BackupJsonContract
import io.payanam.shared.transfer.DataModuleSelection
import org.junit.Test

/**
 * SettingsFoundationContractsTest.
 */
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
        assertThat(snapshot.areas.map { it.id }).containsExactly(
            "settings_transfer",
            "settings_structure",
            "tasks_time",
            "notes_lenses",
        ).inOrder()
        assertThat(snapshot.areasWithStatus(FoundationReadiness.SharedReady)).isEqualTo(1)
        assertThat(snapshot.areasWithStatus(FoundationReadiness.ExtractionNext)).isEqualTo(2)
        assertThat(snapshot.areasWithStatus(FoundationReadiness.AndroidOnly)).isEqualTo(1)
    }
}
