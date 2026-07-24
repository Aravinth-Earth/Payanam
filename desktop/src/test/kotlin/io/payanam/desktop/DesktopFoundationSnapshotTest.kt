//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.settings.SettingsFoundationContracts
import io.payanam.shared.transfer.DataModuleSelection
import org.junit.Test

class DesktopFoundationSnapshotTest {
    @Test
    fun `desktop foundation snapshot delegates to shared contracts`() {
        val selection = DataModuleSelection(tasks = false, timeEntries = true, notes = false)

        val snapshot = desktopFoundationSnapshot(selection)

        assertThat(snapshot).isEqualTo(SettingsFoundationContracts.snapshot(selection))
        assertThat(snapshot.moduleSelection.timeEntries).isTrue()
    }
}
