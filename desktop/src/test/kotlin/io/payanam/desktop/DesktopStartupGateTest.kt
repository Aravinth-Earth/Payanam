//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.core.ui.text.UiLanguage
import io.payanam.core.ui.text.UiStrings
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.settings.FocusModePreset
import org.junit.Test

/**
 * Provides the desktop startup gate test.
 */
class DesktopStartupGateTest {
    @Test
    fun `startup snapshot carries launch route and attention state for desktop shell`() {
        val snapshot =
            desktopStartupSnapshot(
                launchRoute = DesktopTopLevelRoute.TIME,
                appDataRoot = "C:/Users/test/AppData/Local/Payanam",
                bootstrapFilePath = "C:/Users/test/AppData/Local/Payanam/bootstrap/desktop-bootstrap.properties",
                securityFilePath = "C:/Users/test/AppData/Local/Payanam/security/desktop-security.properties",
                databaseFilePath = "C:/Users/test/AppData/Local/Payanam/database/payanam-desktop.db",
                runtimeState =
                    DesktopStartupRuntimeState(
                        hasPassphraseConfigured = false,
                        hasDatabaseArtifacts = false,
                        databaseLifecycleReady = false,
                        sessionOpen = false,
                        focusModeOnboardingCompleted = false,
                        securityFilePath = "security",
                        databaseFilePath = "database",
                    ),
            )

        assertThat(snapshot.launchRoute).isEqualTo(DesktopTopLevelRoute.TIME)
        assertThat(snapshot.requiresAttention()).isTrue()
        assertThat(snapshot.checks.map { it.id })
            .containsExactly(
                "desktop_settings",
                "desktop_app_data",
                "desktop_bootstrap",
                "desktop_passphrase",
                "desktop_session",
                "desktop_database",
            ).inOrder()
    }

    @Test
    fun `startup snapshot is fully ready when desktop lifecycle bootstrap is ready`() {
        val snapshot =
            desktopStartupSnapshot(
                launchRoute = DesktopTopLevelRoute.TASKS,
                appDataRoot = "C:/Users/test/AppData/Local/Payanam",
                bootstrapFilePath = "C:/Users/test/AppData/Local/Payanam/bootstrap/desktop-bootstrap.properties",
                securityFilePath = "C:/Users/test/AppData/Local/Payanam/security/desktop-security.properties",
                databaseFilePath = "C:/Users/test/AppData/Local/Payanam/database/payanam-desktop.db",
                runtimeState =
                    DesktopStartupRuntimeState(
                        hasPassphraseConfigured = true,
                        hasDatabaseArtifacts = true,
                        databaseLifecycleReady = true,
                        sessionOpen = true,
                        focusModeOnboardingCompleted = true,
                        securityFilePath = "security",
                        databaseFilePath = "database",
                    ),
            )

        assertThat(snapshot.requiresAttention()).isFalse()
        assertThat(snapshot.readyChecks()).isEqualTo(6)
    }

}
