//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.shared.startup.startupGateInputs
import org.junit.Test

/**
 * Provides the desktop startup flow test.
 */
class DesktopStartupFlowTest {
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
                bootstrapSchema = DesktopBootstrapSchema(),
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
    fun `build startup runtime state carries the store availability flag`() {
        // The fail-closed signal travels with the store's own read. Without it, "no passphrase configured" and
        // "could not tell" are the same value and the gate would offer to configure a passphrase over an
        // install that already has one - so this mapping is asserted at both ends, and what the graph is TOLD
        // is asserted rather than only the field, because the field is not what the user meets.
        fun stateFor(availability: DesktopSecurityAvailability) =
            buildDesktopStartupRuntimeState(
                focusModeOnboardingCompleted = false,
                bootstrapSchema = DesktopBootstrapSchema(),
                securitySnapshot = DesktopSecuritySnapshot(availability = availability),
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

        val unreadable = stateFor(DesktopSecurityAvailability.UNAVAILABLE)
        assertThat(unreadable.securityStateUnavailable).isTrue()
        assertThat(startupGateInputs(unreadable).shouldShowSecurityStateUnavailable)
            .isTrue()

        val readable = stateFor(DesktopSecurityAvailability.AVAILABLE)
        assertThat(readable.securityStateUnavailable).isFalse()
        assertThat(startupGateInputs(readable).shouldShowSecurityStateUnavailable)
            .isFalse()
    }
}
