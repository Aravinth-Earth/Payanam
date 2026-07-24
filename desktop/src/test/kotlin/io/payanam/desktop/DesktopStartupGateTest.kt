//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.settings.DesktopSettingsSnapshot
import io.payanam.shared.settings.DesktopTopLevelRoute
import io.payanam.shared.settings.FocusModePreset
import org.junit.Test

class DesktopStartupGateTest {
    @Test
    fun `startup snapshot carries launch route and attention state for desktop shell`() {
        val snapshot =
            desktopStartupSnapshot(
                settings = DesktopSettingsSnapshot(launchRoute = DesktopTopLevelRoute.TIME),
                settingsFilePath = "C:/Users/test/AppData/Local/Payanam/desktop-settings.properties",
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
                settings = DesktopSettingsSnapshot(launchRoute = DesktopTopLevelRoute.TASKS),
                settingsFilePath = "C:/Users/test/AppData/Local/Payanam/preferences/desktop-settings.properties",
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

    @Test
    fun `startup gate messaging covers all startup modes`() {
        assertThat(desktopStartupTitle(DesktopStartupMode.SetupPassphrase)).isEqualTo("Set up local protection")
        assertThat(desktopStartupTitle(DesktopStartupMode.UnlockPassphrase)).isEqualTo("Unlock local desktop session")
        assertThat(desktopStartupTitle(DesktopStartupMode.InitializeDatabase)).isEqualTo("Create local data space")
        assertThat(desktopStartupTitle(DesktopStartupMode.FocusModeSelection)).isEqualTo("Choose your focus mode")
        assertThat(desktopStartupTitle(DesktopStartupMode.Ready)).isEqualTo("Desktop ready")

        assertThat(desktopStartupSummary(DesktopStartupMode.SetupPassphrase)).contains("desktop passphrase")
        assertThat(
            desktopStartupSummary(DesktopStartupMode.UnlockPassphrase),
        ).contains("Unlock the existing protected desktop installation")
        assertThat(desktopStartupSummary(DesktopStartupMode.InitializeDatabase)).contains("Create the local desktop database")
        assertThat(desktopStartupSummary(DesktopStartupMode.FocusModeSelection)).contains("initial navigation preset")
        assertThat(desktopStartupSummary(DesktopStartupMode.Ready)).contains("checks are complete")
    }

    @Test
    fun `startup gate action messaging reports success and lockout states`() {
        assertThat(desktopSetupPassphraseMessage(DesktopPassphraseActionResult.Success))
            .isEqualTo("Passphrase saved. Startup can continue to local database setup.")
        assertThat(
            desktopSetupPassphraseMessage(
                DesktopPassphraseActionResult.ValidationFailed(reasonCode = "too_short"),
            ),
        ).contains("too_short")
        assertThat(
            desktopSetupPassphraseMessage(
                DesktopPassphraseActionResult.UnlockFailed(failedAttempts = 2, lockoutSecondsRemaining = 0),
            ),
        ).contains("Attempts: 2")
        assertThat(
            desktopSetupPassphraseMessage(
                DesktopPassphraseActionResult.Locked(lockoutSecondsRemaining = 45),
            ),
        ).contains("45 seconds")

        assertThat(desktopUnlockPassphraseMessage(DesktopPassphraseActionResult.Success))
            .isEqualTo("Desktop session unlocked.")
        assertThat(
            desktopUnlockPassphraseMessage(
                DesktopPassphraseActionResult.ValidationFailed(reasonCode = "empty"),
            ),
        ).contains("empty")
        assertThat(
            desktopUnlockPassphraseMessage(
                DesktopPassphraseActionResult.UnlockFailed(failedAttempts = 3, lockoutSecondsRemaining = 30),
            ),
        ).contains("Lockout: 30s.")
        assertThat(
            desktopUnlockPassphraseMessage(
                DesktopPassphraseActionResult.Locked(lockoutSecondsRemaining = 20),
            ),
        ).contains("20 seconds")
    }

    @Test
    fun `startup gate formatting covers focus presets and timestamps`() {
        assertThat(desktopFormatTimestamp(0L)).isEqualTo("-")
        assertThat(desktopFormatTimestamp(1_700_000_000_000L)).contains("2023")

        assertThat(desktopFocusPresetTitle(FocusModePreset.SIMPLE_TIME_HABITS)).isEqualTo("Time and habits")
        assertThat(desktopFocusPresetTitle(FocusModePreset.SIMPLE_JOURNAL)).isEqualTo("Journal and notes")
        assertThat(desktopFocusPresetTitle(FocusModePreset.SIMPLE_TASKS)).isEqualTo("Tasks only")
        assertThat(desktopFocusPresetTitle(FocusModePreset.FULL_SUITE)).isEqualTo("Full suite")

        assertThat(desktopFocusPresetDescription(FocusModePreset.SIMPLE_TIME_HABITS)).contains("time, habits")
        assertThat(desktopFocusPresetDescription(FocusModePreset.SIMPLE_JOURNAL)).contains("reflection-first")
        assertThat(desktopFocusPresetDescription(FocusModePreset.SIMPLE_TASKS)).contains("task management")
        assertThat(desktopFocusPresetDescription(FocusModePreset.FULL_SUITE)).contains("complete Payanam experience")
    }
}
