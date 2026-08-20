//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * DesktopStartupFlowTest.
 */
class DesktopStartupFlowTest {
    @Test
    fun `resolve startup mode requests setup when passphrase is missing`() {
        val mode =
            resolveDesktopStartupMode(
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

        assertThat(mode).isEqualTo(DesktopStartupMode.SetupPassphrase)
    }

    @Test
    fun `resolve startup mode requests unlock when passphrase is configured but session is closed`() {
        val mode =
            resolveDesktopStartupMode(
                DesktopStartupRuntimeState(
                    hasPassphraseConfigured = true,
                    hasDatabaseArtifacts = true,
                    databaseLifecycleReady = true,
                    sessionOpen = false,
                    focusModeOnboardingCompleted = false,
                    securityFilePath = "security",
                    databaseFilePath = "database",
                ),
            )

        assertThat(mode).isEqualTo(DesktopStartupMode.UnlockPassphrase)
    }

    @Test
    fun `resolve startup mode requests database initialization when session is open but lifecycle is not ready`() {
        val mode =
            resolveDesktopStartupMode(
                DesktopStartupRuntimeState(
                    hasPassphraseConfigured = true,
                    hasDatabaseArtifacts = false,
                    databaseLifecycleReady = false,
                    sessionOpen = true,
                    focusModeOnboardingCompleted = false,
                    securityFilePath = "security",
                    databaseFilePath = "database",
                ),
            )

        assertThat(mode).isEqualTo(DesktopStartupMode.InitializeDatabase)
    }

    @Test
    fun `resolve startup mode requests focus onboarding after database is ready`() {
        val mode =
            resolveDesktopStartupMode(
                DesktopStartupRuntimeState(
                    hasPassphraseConfigured = true,
                    hasDatabaseArtifacts = true,
                    databaseLifecycleReady = true,
                    sessionOpen = true,
                    focusModeOnboardingCompleted = false,
                    securityFilePath = "security",
                    databaseFilePath = "database",
                ),
            )

        assertThat(mode).isEqualTo(DesktopStartupMode.FocusModeSelection)
    }

    @Test
    fun `build startup runtime state reports lifecycle ready only when bootstrap and database artifacts are ready`() {
        val state =
            buildDesktopStartupRuntimeState(
                settingsSnapshot =
                    io.payanam.shared.settings
                        .DesktopSettingsSnapshot(focusModeOnboardingCompleted = true),
                bootstrapSnapshot = DesktopBootstrapSnapshot(databaseLifecycleReady = true),
                securitySnapshot = DesktopSecuritySnapshot(hasPassphraseConfigured = true),
                databaseSnapshot =
                    DesktopDatabaseSnapshot(
                        databaseFilePath = "db",
                        hasArtifacts = true,
                        initCompleted = true,
                        databaseSizeKb = 1L,
                        databaseLastModifiedMs = 1L,
                    ),
                sessionOpen = true,
                nowEpochMillis = 10_000L,
            )

        assertThat(state.databaseLifecycleReady).isTrue()
        assertThat(state.focusModeOnboardingCompleted).isTrue()
        assertThat(state.lockoutSecondsRemaining).isEqualTo(0L)
    }
}
