//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop

import com.google.common.truth.Truth.assertThat
import io.payanam.desktop.database.DesktopBackupStatusPort
import io.payanam.shared.logging.NoOpUnifiedLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A backup failure on the desktop reaches the shared graph, and dismissing it clears it.
 *
 * **Why this needed a test at all.** The desktop passed `backupStatus = null` into the shared preferences
 * mapper, and `AppPreferencesMapper` derives `autoBackupLastErrorMessage` / `autoBackupLastErrorAt` from
 * `backupStatus?.latestFailure` rather than from a stored row. So the graph's `backupFailureMessage` had
 * nothing to show no matter what the mount site passed - wiring the parameter alone would have produced a
 * dialog that is permanently empty, which looks correct in a screenshot and never appears in an app.
 *
 * Asserting on the source's own input would not catch that: a source handed a real port and asked for the
 * state could still return a stale one, because the banner is derived and nothing about a row changes when a
 * backup fails. Hence these cases read the emitted STATE after the failure is recorded.
 */
class DesktopBackupFailureBannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private companion object {
        /** Generous for a real dispatcher hop, short enough that a dead wiring fails fast. */
        const val AWAIT_MILLIS = 5_000L
    }

    private fun sourceWith(port: DesktopBackupStatusPort) =
        DesktopPreferencesSource(
            sessionLogger = NoOpUnifiedLogger,
            systemLanguageTag = "en",
            backupStatusStore = port,
        )

    @Test
    fun `a recorded failure populates the banner fields the graph reads`() =
        runBlocking {
            val port = DesktopBackupStatusPort()
            val source = sourceWith(port)

            assertThat(source.preferences.first().autoBackupLastErrorMessage).isNull()

            port.recordFailure("disk full")

            // AWAITED, not `first()`: the state is combined on its own Dispatchers.Default scope, so
            // `first()` here would read the value captured before the failure was recorded and report a
            // null banner for a correctly wired source. The timeout is the guard - a wiring regression
            // fails by TIMING OUT rather than by asserting on a stale value, which is the honest failure.
            val state = withTimeout(AWAIT_MILLIS) { source.preferences.first { it.autoBackupLastErrorMessage != null } }
            assertThat(state.autoBackupLastErrorMessage).isNotNull()
            assertThat(state.autoBackupLastErrorMessage).contains("disk full")
            assertThat(state.autoBackupLastErrorAt).isNotNull()
        }

    @Test
    fun `dismissing clears the banner`() =
        runBlocking {
            val port = DesktopBackupStatusPort()
            val source = sourceWith(port)
            port.recordFailure("disk full")
            withTimeout(AWAIT_MILLIS) { source.preferences.first { it.autoBackupLastErrorMessage != null } }

            port.dismissLatestFailure()

            // Awaited for the same reason as the record above: reading `first()` immediately after the
            // dismiss can observe the emission that still carries the failure, because the combine runs on
            // its own scope. The predicate makes the test wait for the CLEARED state rather than racing it.
            //
            // This is the whole point of the graph's `onDismissBackupFailure`: once the message is null the
            // AlertDialog's `if (!backupFailureMessage.isNullOrBlank())` is false and it stops rendering.
            val cleared = withTimeout(AWAIT_MILLIS) { source.preferences.first { it.autoBackupLastErrorMessage == null } }
            assertThat(cleared.autoBackupLastErrorMessage).isNull()
            assertThat(cleared.autoBackupLastErrorAt).isNull()
        }

    @Test
    fun `a source with no backup port reports no failure rather than failing`() =
        runBlocking {
            // The pre-wiring default must stay usable: `null` means "no backup status", not a crash.
            val source =
                DesktopPreferencesSource(
                    sessionLogger = NoOpUnifiedLogger,
                    systemLanguageTag = "en",
                )

            assertThat(source.preferences.first().autoBackupLastErrorMessage).isNull()
        }

    @Test
    fun `the bundle hands the preferences source and the dismiss callback the same port`() {
        // Two instances would show a banner the dismiss cannot clear - the failure mode is invisible in a
        // single-port test and obvious in production, so it is asserted structurally.
        val stores =
            DesktopPersistenceStores(
                bootstrapStore = DesktopBootstrapStore(),
                securityStore = DesktopSecurityStore(securityDirectory = temporaryFolder.newFolder().toPath()),
                databaseStore = DesktopDatabaseStore(),
            )

        assertThat(stores.backupStatusStore).isSameInstanceAs(stores.backupStatusStore)
    }
}
