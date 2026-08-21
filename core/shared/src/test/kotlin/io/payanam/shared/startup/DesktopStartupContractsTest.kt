//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.startup

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.settings.DesktopTopLevelRoute
import org.junit.Test

/**
 * DesktopStartupContractsTest.
 */
class DesktopStartupContractsTest {
    @Test
    fun `startup snapshot flags missing desktop database lifecycle attention`() {
        val snapshot =
            DesktopStartupContracts.snapshot(
                launchRoute = DesktopTopLevelRoute.SETTINGS,
                paths =
                    DesktopStartupPaths(
                        settingsFilePath = "C:/Users/test/AppData/Local/Payanam/preferences.properties",
                        appDataRoot = "C:/Users/test/AppData/Local/Payanam",
                        bootstrapFilePath = "C:/Users/test/AppData/Local/Payanam/bootstrap/desktop-bootstrap.properties",
                        securityFilePath = "C:/Users/test/AppData/Local/Payanam/security/desktop-security.properties",
                        databaseFilePath = "C:/Users/test/AppData/Local/Payanam/database/payanam-desktop.db",
                    ),
                state =
                    DesktopStartupState(
                        passphraseConfigured = false,
                        sessionOpen = false,
                    ),
            )
        assertThat(snapshot.schemaVersion).isEqualTo(DesktopStartupContracts.SCHEMA_VERSION)
        assertThat(snapshot.requiresAttention()).isTrue()
        assertThat(snapshot.readyChecks()).isEqualTo(3)
        assertThat(snapshot.checks.last().status).isEqualTo(DesktopStartupCheckStatus.AttentionRequired)
    }

    @Test
    fun `startup snapshot becomes fully ready when desktop database lifecycle is supplied`() {
        val snapshot =
            DesktopStartupContracts.snapshot(
                launchRoute = DesktopTopLevelRoute.TIME,
                paths =
                    DesktopStartupPaths(
                        settingsFilePath = "settings.properties",
                        appDataRoot = "appData",
                        bootstrapFilePath = "bootstrap.properties",
                        securityFilePath = "security.properties",
                        databaseFilePath = "database.db",
                    ),
                state =
                    DesktopStartupState(
                        passphraseConfigured = true,
                        sessionOpen = true,
                        desktopDatabaseReady = true,
                    ),
            )
        assertThat(snapshot.launchRoute).isEqualTo(DesktopTopLevelRoute.TIME)
        assertThat(snapshot.requiresAttention()).isFalse()
        assertThat(snapshot.readyChecks()).isEqualTo(6)
    }
}
