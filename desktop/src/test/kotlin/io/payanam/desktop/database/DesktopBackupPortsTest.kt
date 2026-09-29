//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.desktop.database

import com.google.common.truth.Truth.assertThat
import io.payanam.domain.port.BackupTrigger
import io.payanam.shared.logging.NoOpUnifiedLogger
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Tests for the desktop's backup ports.
 *
 * The status port is asserted through its observable snapshot rather than its internals, and the
 * coordinator port through both the result it returns and the status it records — a successful run
 * that forgot to record, or a failure that recorded nothing, must fail these tests.
 */
class DesktopBackupPortsTest {
    @Test
    fun `a recorded success sets the display timestamp and clears any failure`() {
        val store = DesktopBackupStatusPort()
        store.recordFailure("previous failure")

        store.recordSuccess(1_700_000_000_000L)

        val snapshot = store.status.value
        assertThat(snapshot.lastSuccessAtMillis).isEqualTo(1_700_000_000_000L)
        assertThat(snapshot.lastSuccessDisplay).isEqualTo(store.formatBackupTimestamp(1_700_000_000_000L))
        assertThat(snapshot.latestFailure).isNull()
    }

    @Test
    fun `a recorded failure keeps the last success and truncates long messages`() {
        val store = DesktopBackupStatusPort()
        store.recordSuccess(1_700_000_000_000L)

        store.recordFailure("x".repeat(500))

        val snapshot = store.status.value
        assertThat(snapshot.latestFailure?.message).hasLength(400)
        assertThat(snapshot.lastSuccessAtMillis).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `dismissing the failure clears only the failure`() {
        val store = DesktopBackupStatusPort()
        store.recordSuccess(1_700_000_000_000L)
        store.recordFailure("boom")

        store.dismissLatestFailure()

        val snapshot = store.status.value
        assertThat(snapshot.latestFailure).isNull()
        assertThat(snapshot.lastSuccessAtMillis).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `a successful backup reports the destination and records the success`() =
        runBlocking {
            val store = DesktopBackupStatusPort()
            val port =
                DesktopDatabaseBackupPort(
                    statusStore = store,
                    performBackup = { "/tmp/payanam-backups/auto_bk/payanam.db" },
                    logger = NoOpUnifiedLogger,
                )

            val result = port.backupToAppBackupDirectory(BackupTrigger.AUTO)

            assertThat(result.destinationPath).isEqualTo("/tmp/payanam-backups/auto_bk/payanam.db")
            assertThat(result.attemptsUsed).isEqualTo(1)
            assertThat(result.recordedAtDisplay).isEqualTo(store.status.value.lastSuccessDisplay)
            assertThat(store.status.value.latestFailure).isNull()
        }

    @Test
    fun `a failed backup records the failure and throws with the reason`() =
        runBlocking {
            val store = DesktopBackupStatusPort()
            val port =
                DesktopDatabaseBackupPort(
                    statusStore = store,
                    performBackup = { error("disk full") },
                    logger = NoOpUnifiedLogger,
                )

            val thrown = runCatching { port.backupToAppBackupDirectory(BackupTrigger.MANUAL) }.exceptionOrNull()

            assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
            assertThat(thrown?.message).contains("disk full")
            assertThat(
                store.status.value.latestFailure
                    ?.message,
            ).contains("disk full")
        }
}
