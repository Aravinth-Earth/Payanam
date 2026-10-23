//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Provides the desktop startup flow test.
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
                    // These cases describe the post-onboarding gates, which are only reachable once the
                    // scaffold is complete; without this the intro stage would intercept them.
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
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
                    // These cases describe the post-onboarding gates, which are only reachable once the
                    // scaffold is complete; without this the intro stage would intercept them.
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
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
                    // These cases describe the post-onboarding gates, which are only reachable once the
                    // scaffold is complete; without this the intro stage would intercept them.
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
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
                    // These cases describe the post-onboarding gates, which are only reachable once the
                    // scaffold is complete; without this the intro stage would intercept them.
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
                ),
            )

        assertThat(mode).isEqualTo(DesktopStartupMode.FocusModeSelection)
    }

    @Test
    fun `build startup runtime state reports lifecycle ready exactly when the database file is there`() {
        // Readiness is DERIVED from the file, so this is one fact, not a conjunction of three. The old
        // rule was `bootstrap.databaseLifecycleReady && hasArtifacts && initCompleted`, and it could
        // never be true: the bootstrap flag and the artifact checks all read the scaffold's
        // deliberately-never-created path, so the gate's database check reported "not ready" on every
        // launch. The false case is asserted here because the old test had no way to fail it.
        fun stateFor(filePresent: Boolean) =
            buildDesktopStartupRuntimeState(
                focusModeOnboardingCompleted = true,
                bootstrapSnapshot = DesktopBootstrapSnapshot(),
                securitySnapshot = DesktopSecuritySnapshot(hasPassphraseConfigured = true),
                databaseSnapshot =
                    DesktopDatabaseSnapshot(
                        databaseFilePath = "db",
                        hasArtifacts = filePresent,
                        initCompleted = filePresent,
                        databaseSizeKb = 1L,
                        databaseLastModifiedMs = 1L,
                    ),
                sessionOpen = true,
                nowEpochMillis = 10_000L,
            )

        assertThat(stateFor(filePresent = true).databaseLifecycleReady).isTrue()
        assertThat(stateFor(filePresent = false).databaseLifecycleReady).isFalse()
        // The other two facts are untouched by the change, and a test that only checked the field this
        // commit edited would pass even if the rest of the state had been dropped.
        val ready = stateFor(filePresent = true)
        assertThat(ready.focusModeOnboardingCompleted).isTrue()
        assertThat(ready.lockoutSecondsRemaining).isEqualTo(0L)
        assertThat(ready.hasDatabaseArtifacts).isTrue()
    }

    @Test
    fun `resolve startup mode refuses to proceed when the security state is unavailable`() {
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
                    // These cases describe the post-onboarding gates, which are only reachable once the
                    // scaffold is complete; without this the intro stage would intercept them.
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
                    securityStateUnavailable = true,
                ),
            )

        // Guards the branch ORDER, which the compiler does not: this is a boolean `when` with
        // `else -> Ready`, so moving the unavailable check below `!hasPassphraseConfigured` compiles
        // cleanly and silently restores the fail-open (an unreadable store would offer passphrase
        // setup instead of refusing). This assertion is the only thing that catches that.
        assertThat(mode).isEqualTo(DesktopStartupMode.SecurityStateUnavailable)
        assertThat(mode).isNotEqualTo(DesktopStartupMode.SetupPassphrase)
    }

    @Test
    fun `resolve startup mode still requests setup when the security state is readable`() {
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
                    // These cases describe the post-onboarding gates, which are only reachable once the
                    // scaffold is complete; without this the intro stage would intercept them.
                    scaffoldOnboardingStage = DesktopScaffoldOnboardingStage.Bypassed,
                    securityStateUnavailable = false,
                ),
            )

        // The other direction: the fix must not turn a genuine fresh install into a refusal.
        assertThat(mode).isEqualTo(DesktopStartupMode.SetupPassphrase)
    }

    @Test
    fun `build startup runtime state carries the store availability flag`() {
        val state =
            buildDesktopStartupRuntimeState(
                focusModeOnboardingCompleted = false,
                bootstrapSnapshot = DesktopBootstrapSnapshot(),
                securitySnapshot =
                    DesktopSecuritySnapshot(
                        availability = DesktopSecurityAvailability.UNAVAILABLE,
                    ),
                databaseSnapshot =
                    DesktopDatabaseSnapshot(
                        databaseFilePath = "db",
                        hasArtifacts = false,
                        initCompleted = false,
                        databaseSizeKb = 0L,
                        databaseLastModifiedMs = 0L,
                    ),
                sessionOpen = false,
                nowEpochMillis = 0L,
            )

        assertThat(state.securityStateUnavailable).isTrue()
        assertThat(resolveDesktopStartupMode(state)).isEqualTo(DesktopStartupMode.SecurityStateUnavailable)
    }
}
