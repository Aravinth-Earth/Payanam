//  SPDX-FileCopyrightText: 2026 Aravinth-Earth
//  SPDX-License-Identifier: AGPL-3.0-or-later
package io.payanam.shared.platform

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Tests for the backup-schedule contract, through an in-memory scheduler.
 *
 * The property that matters is that `schedule` REPLACES. Both the settings screen and the startup path
 * reconcile the schedule against the stored preference, so "schedule" is routinely called with the interval
 * already in force — an implementation that stacked a job per call would run N backups per interval after N
 * reconciliations, which is invisible until the disk fills.
 */
class BackupScheduleContractTest {
    private class FakeScheduler : BackupScheduler {
        var intervalMinutes: Int? = null
            private set
        var scheduleCalls = 0
            private set
        var cancelCalls = 0
            private set

        override suspend fun schedule(intervalMinutes: Int) {
            scheduleCalls++
            this.intervalMinutes = intervalMinutes
        }

        override suspend fun cancel() {
            cancelCalls++
            intervalMinutes = null
        }
    }

    @Test
    fun `scheduling twice with the same interval leaves one schedule in force`(): Unit =
        runBlocking {
            val scheduler = FakeScheduler()

            scheduler.schedule(60)
            scheduler.schedule(60)

            assertThat(scheduler.intervalMinutes).isEqualTo(60)
            // Two calls, one schedule: the contract is "ensure", not "add".
            assertThat(scheduler.scheduleCalls).isEqualTo(2)
        }

    @Test
    fun `a changed interval replaces the previous one`(): Unit =
        runBlocking {
            val scheduler = FakeScheduler()

            scheduler.schedule(60)
            scheduler.schedule(15)

            assertThat(scheduler.intervalMinutes).isEqualTo(15)
        }

    @Test
    fun `cancelling clears the schedule and is safe to repeat`(): Unit =
        runBlocking {
            val scheduler = FakeScheduler()
            scheduler.schedule(60)

            scheduler.cancel()
            scheduler.cancel()

            assertThat(scheduler.intervalMinutes).isNull()
            assertThat(scheduler.cancelCalls).isEqualTo(2)
        }

    @Test
    fun `scheduling after a cancel restores the schedule`(): Unit =
        runBlocking {
            val scheduler = FakeScheduler()

            scheduler.schedule(60)
            scheduler.cancel()
            scheduler.schedule(30)

            assertThat(scheduler.intervalMinutes).isEqualTo(30)
        }

    @Test
    fun `the background capability distinguishes a surviving schedule from a foreground timer`() {
        // Reported rather than hidden: "your last backup was three days ago" means something different on a
        // platform that only backs up while it is open.
        assertThat(BackupBackgroundCapability.entries)
            .containsExactly(
                BackupBackgroundCapability.BACKGROUND_CAPABLE,
                BackupBackgroundCapability.FOREGROUND_ONLY,
            )
        assertThat(BackupBackgroundCapability.valueOf("FOREGROUND_ONLY"))
            .isEqualTo(BackupBackgroundCapability.FOREGROUND_ONLY)
    }
}
